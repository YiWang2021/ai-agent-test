package com.example.supportagent.agent;

import com.example.supportagent.config.SupportProperties;
import com.example.supportagent.memory.ChatTurn;
import com.example.supportagent.memory.ConversationGuard;
import com.example.supportagent.memory.ConversationMemory;
import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
public class AgentChatService {
    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);
    private final ReliableAgentRunner runner;
    private final ConversationMemory memory;
    private final ConversationGuard guard;
    private final SupportProperties properties;
    private final Executor executor;
    private final ToolTraceStore traceStore;
    private final MeterRegistry metrics;
    private final com.example.supportagent.concurrency.AdmissionController admission;

    public AgentChatService(ReliableAgentRunner runner, ConversationMemory memory, ConversationGuard guard,
            SupportProperties properties, @Qualifier("agentExecutor") Executor executor,
            MeterRegistry metrics, ToolTraceStore traceStore, com.example.supportagent.concurrency.AdmissionController admission) {
        this.runner = runner; this.memory = memory; this.guard = guard; this.properties = properties;
        this.executor = executor; this.metrics = metrics; this.traceStore = traceStore;
        this.admission = admission;
    }

    public AgentAnswer ask(AgentRequest request, RequestIdentity identity) {
        validateWriteRequest(request);
        AgentExecution execution = execution();
        long started = System.nanoTime();
        try (var resources = resources(request, identity, execution)) {
            log.info("Agent started traceId={}", execution.traceId());
            AgentAnswer answer = runner.run(request, identity, history(request, identity), execution);
            resources.capacity.checkValid();
            persistConversation(request, identity, answer.answer(), resources.conversation);
            log.info("Agent completed traceId={}", execution.traceId());
            return answer;
        } catch (RuntimeException error) {
            metrics.counter("support.agent.request.failures").increment();
            throw error;
        } finally {
            execution.cancel();
            traceStore.drainCitations(execution.traceId());
            log.info("Agent tools traceId={} events={}", execution.traceId(), traceStore.drainEvents(execution.traceId()));
            metrics.timer("support.agent.request.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public SseEmitter stream(AgentRequest request, RequestIdentity identity) {
        validateWriteRequest(request);
        AgentExecution execution = execution();
        long started = System.nanoTime();
        var resources = resources(request, identity, execution);
        List<ChatTurn> history;
        try { history = history(request, identity); }
        catch (RuntimeException error) { execution.cancel(); resources.close(); throw error; }
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(properties.agent().timeoutSeconds() + 2).toMillis());
        var subscription = Disposables.swap();
        StringBuilder complete = new StringBuilder();
        var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleaned.compareAndSet(false, true)) return;
            execution.cancel(); subscription.dispose();
            traceStore.drainCitations(execution.traceId()); resources.close();
            metrics.timer("support.agent.request.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            log.info("Agent stream tools traceId={} events={}", execution.traceId(), traceStore.drainEvents(execution.traceId()));
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
        subscription.update(runner.stream(request, identity, history, execution)
                .subscribeOn(Schedulers.fromExecutor(executor))
                .subscribe(token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                        complete.append(token);
                    } catch (IOException error) {
                        cleanup.run(); emitter.completeWithError(error);
                    }
                }, error -> {
                    metrics.counter("support.agent.request.failures").increment();
                    log.error("Agent stream failed traceId=" + execution.traceId(), error);
                    try {
                        emitter.send(SseEmitter.event().name("error").data(Map.of(
                                "traceId", execution.traceId(), "code", "STREAM_INTERRUPTED",
                                "message", "回答中断，请勿将已显示的片段视为完整结论；如涉及报修，请先核实工单状态")));
                        emitter.complete();
                    } catch (IOException sendError) { emitter.completeWithError(sendError); }
                    finally { cleanup.run(); }
                }, () -> {
                    try {
                        resources.capacity.checkValid();
                        persistConversation(request, identity, complete.toString(), resources.conversation);
                        emitter.send(SseEmitter.event().name("done").data(Map.of(
                                "traceId", execution.traceId(), "citations", traceStore.drainCitations(execution.traceId()))));
                        emitter.complete();
                        log.info("Agent stream completed traceId={}", execution.traceId());
                    } catch (Exception error) {
                        log.error("Agent stream completion failed traceId=" + execution.traceId(), error);
                        emitter.completeWithError(error);
                    } finally { cleanup.run(); }
                }));
        } catch (RuntimeException error) { cleanup.run(); throw error; }
        return emitter;
    }

    private List<ChatTurn> history(AgentRequest request, RequestIdentity identity) {
        List<ChatTurn> history = memory.recent(identity.tenantId(), identity.userId(), request.conversationId(), properties.agent().historyLimit());
        // Character budget, not advertised as a tokenizer-accurate token limit.
        int characters = 0, from = history.size();
        while (from > 0 && characters + history.get(from - 1).content().length() <= 12000) {
            characters += history.get(--from).content().length();
        }
        while (from < history.size() && !"USER".equals(history.get(from).role())) from++;
        return List.copyOf(history.subList(from, history.size()));
    }
    private void persistConversation(AgentRequest request, RequestIdentity identity, String answer, ConversationGuard.Lease lease) {
        memory.appendExchange(identity.tenantId(), identity.userId(), request.conversationId(), request.message(), answer, lease);
    }
    private RequestResources resources(AgentRequest request, RequestIdentity identity, AgentExecution execution) {
        var capacity = admission.acquire(identity);
        try {
            var conversation = guard.acquire(identity, request.conversationId(), leaseDuration());
            execution.protectWith(capacity); execution.protectWith(conversation);
            return new RequestResources(capacity, conversation);
        } catch (RuntimeException error) { capacity.close(); throw error; }
    }
    private record RequestResources(com.example.supportagent.concurrency.Lease capacity,
                                    ConversationGuard.Lease conversation) implements AutoCloseable {
        @Override public void close() { try { conversation.close(); } finally { capacity.close(); } }
    }
    private AgentExecution execution() {
        String traceId = MDC.get("traceId");
        return new AgentExecution(traceId == null ? UUID.randomUUID().toString() : traceId,
                Duration.ofSeconds(properties.agent().timeoutSeconds()), properties.agent().maxToolCalls());
    }
    private Duration leaseDuration() { return Duration.ofSeconds(properties.agent().timeoutSeconds() + 10); }
    private void validateWriteRequest(AgentRequest request) {
        if (request.confirmWrite() && (request.idempotencyKey() == null || request.idempotencyKey().isBlank())) {
            throw new IllegalArgumentException("confirmed write requests require idempotencyKey");
        }
    }
}
