package com.example.supportagent.agent;

import com.example.supportagent.memory.ChatTurn;
import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReliableAgentRunner {
    private static final Logger log = LoggerFactory.getLogger(ReliableAgentRunner.class);
    private final AiResponder responder;
    private final Executor executor;
    private final ReliabilityProperties config;
    private final ModelCircuitBreaker circuit;
    private final SafeFallback fallback;
    private final MeterRegistry metrics;

    public ReliableAgentRunner(AiResponder responder, @Qualifier("agentExecutor") Executor executor,
            ReliabilityProperties config, ModelCircuitBreaker circuit, SafeFallback fallback, MeterRegistry metrics) {
        this.responder = responder; this.executor = executor; this.config = config;
        this.circuit = circuit; this.fallback = fallback; this.metrics = metrics;
    }

    public AgentAnswer run(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        var permit = circuit.acquire().orElse(null);
        if (permit == null) return degrade(request, execution, "circuit_open");
        long started = System.nanoTime();
        try {
            for (int attempt = 1; ; attempt++) {
                execution.checkPermits();
                log.info("Model attempt traceId={} attempt={} responder={} circuit={}",
                        execution.traceId(), attempt, responder.getClass().getSimpleName(), circuit.state());
                metrics.counter("support.agent.model.attempts").increment();
                FutureTask<AgentAnswer> task = new FutureTask<>(() -> {
                    execution.checkPermits();
                    return responder.respond(request, identity, history, execution);
                });
                try {
                    executor.execute(task);
                    AgentAnswer answer = task.get(Math.max(1, execution.remainingMillis()), TimeUnit.MILLISECONDS);
                    circuit.success(permit);
                    return answer;
                } catch (RejectedExecutionException rejected) {
                    task.cancel(true); circuit.abandon(permit);
                    throw new com.example.supportagent.concurrency.CapacityExceededException("executor");
                } catch (InterruptedException interrupted) {
                    execution.cancel(); task.cancel(true); circuit.abandon(permit);
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Agent caller interrupted");
                } catch (Exception error) {
                    Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
                    task.cancel(true);
                    log.warn("Model attempt failed traceId=" + execution.traceId() + " attempt=" + attempt, cause);
                    long delay = backoff(attempt);
                    if (!canRetry(request, execution, attempt, cause) || execution.remainingMillis() <= delay) {
                        if (TransientFailureClassifier.isTransient(cause)) circuit.failure(permit); else circuit.abandon(permit);
                        return degrade(request, execution, cause instanceof TimeoutException ? "timeout" : "model_failure");
                    }
                    metrics.counter("support.agent.model.retries").increment();
                    try { Thread.sleep(delay); }
                    catch (InterruptedException interrupted) {
                        execution.cancel(); circuit.abandon(permit); Thread.currentThread().interrupt();
                        throw new CancellationException("Agent retry interrupted");
                    }
                }
            }
        } catch (CancellationException cancelled) {
            circuit.abandon(permit);
            return degrade(request, execution, "cancelled");
        } finally {
            circuit.abandon(permit);
            metrics.timer("support.agent.model.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** Retry only before the first visible token. Never concatenate a restarted answer after partial output. */
    public Flux<String> stream(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        return Flux.defer(() -> {
            var permit = circuit.acquire().orElse(null);
            if (permit == null) return Flux.just(degrade(request, execution, "circuit_open").answer());
            AtomicBoolean emitted = new AtomicBoolean();
            return streamAttempt(request, identity, history, execution, emitted, 1)
                    .takeUntilOther(Mono.delay(Duration.ofMillis(Math.max(1, execution.remainingMillis())))
                            .flatMap(ignored -> Mono.error(new TimeoutException("Agent stream deadline exceeded"))))
                    .doOnComplete(() -> circuit.success(permit))
                    .onErrorResume(error -> {
                        if (TransientFailureClassifier.isTransient(error)) circuit.failure(permit); else circuit.abandon(permit);
                        log.error("Model stream failed traceId=" + execution.traceId(), error);
                        execution.cancel();
                        if (emitted.get()) return Flux.error(error);
                        return Flux.just(degrade(request, execution, "stream_failure").answer());
                    })
                    .doFinally(signal -> { execution.cancel(); circuit.abandon(permit); });
        });
    }

    private Flux<String> streamAttempt(AgentRequest request, RequestIdentity identity, List<ChatTurn> history,
                                      AgentExecution execution, AtomicBoolean emitted, int attempt) {
        return Flux.defer(() -> {
            execution.checkPermits();
            metrics.counter("support.agent.model.attempts").increment();
            log.info("Model stream attempt traceId={} attempt={}", execution.traceId(), attempt);
            return responder.stream(request, identity, history, execution);
        }).doOnNext(token -> { execution.checkActive(); if (!token.isEmpty()) emitted.set(true); }).onErrorResume(error -> {
            long delay = backoff(attempt);
            if (!emitted.get() && canRetry(request, execution, attempt, error) && execution.remainingMillis() > delay) {
                metrics.counter("support.agent.model.retries").increment();
                return Mono.delay(Duration.ofMillis(delay)).thenMany(streamAttempt(request, identity, history, execution, emitted, attempt + 1));
            }
            return Flux.error(error);
        });
    }

    private boolean canRetry(AgentRequest request, AgentExecution execution, int attempt, Throwable error) {
        return attempt < config.maxAttempts() && !request.confirmWrite() && !execution.writeStarted()
                && TransientFailureClassifier.isTransient(error) && execution.remainingMillis() > 0;
    }
    private long backoff(int attempt) { return Math.min(config.maxBackoffMillis(), config.backoffMillis() * (1L << Math.min(attempt - 1, 10))); }
    private AgentAnswer degrade(AgentRequest request, AgentExecution execution, String reason) {
        execution.cancel();
        metrics.counter("support.agent.fallbacks", "reason", reason).increment();
        log.warn("Agent fallback traceId={} reason={} circuit={} writeStarted={}", execution.traceId(), reason, circuit.state(), execution.writeStarted());
        return fallback.answer(request, execution);
    }
}
