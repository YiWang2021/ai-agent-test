package com.example.supportagent.agent;

import com.example.supportagent.memory.ChatTurn;
import com.example.supportagent.security.RequestIdentity;
import com.example.supportagent.tool.AgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Component
@Profile("ai")
public class SpringAiResponder implements AiResponder {

    private final ChatClient chatClient;
    private final AgentTools tools;
    private final ToolTraceStore traceStore;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public SpringAiResponder(ChatClient.Builder builder, AgentTools tools, ToolTraceStore traceStore,
                             io.micrometer.core.instrument.MeterRegistry metrics) {
        this.chatClient = builder.build();
        this.tools = tools;
        this.traceStore = traceStore;
        this.metrics = metrics;
    }

    @Override
    public AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, String traceId) {
        return respond(request, identity, history, new AgentExecution(traceId, java.time.Duration.ofSeconds(30), 8));
    }

    @Override
    public AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        String traceId = execution.traceId();
        try {
            var response = request(history, request, identity, execution).call().chatResponse();
            if (response == null || response.getResult() == null) throw new IllegalStateException("Model returned no answer");
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                int input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                int output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                metrics.counter("support.agent.model.tokens", "type", "input").increment(Math.max(0, input));
                metrics.counter("support.agent.model.tokens", "type", "output").increment(Math.max(0, output));
                org.slf4j.LoggerFactory.getLogger(SpringAiResponder.class).info(
                        "Model usage traceId={} inputTokens={} outputTokens={} scope=returned_response", traceId, input, output);
            }
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) throw new IllegalStateException("Model returned empty answer");
            return new AgentAnswer(content, traceStore.drainCitations(traceId), traceId);
        } catch (RuntimeException error) {
            traceStore.drainCitations(traceId);
            throw error;
        }
    }

    @Override
    public Flux<String> stream(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, String traceId) {
        return stream(request, identity, history, new AgentExecution(traceId, java.time.Duration.ofSeconds(30), 8));
    }

    @Override
    public Flux<String> stream(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        return request(history, request, identity, execution).stream().content();
    }

    private ChatClient.ChatClientRequestSpec request(List<ChatTurn> history, AgentRequest request,
                                                     RequestIdentity identity, AgentExecution execution) {
        List<Message> previousMessages = history.stream().map(turn ->
                "USER".equals(turn.role()) ? new UserMessage(turn.content()) : new AssistantMessage(turn.content())
        ).map(Message.class::cast).toList();

        return chatClient.prompt()
                .system(AgentPrompt.SYSTEM)
                .messages(previousMessages)
                .user(request.message())
                .tools(tools)
                .toolContext(Map.of(
                        "tenantId", identity.tenantId(),
                        "userId", identity.userId(),
                        "role", identity.role(),
                        "confirmedWrite", request.confirmWrite(),
                        "idempotencyKey", request.idempotencyKey() == null ? "" : request.idempotencyKey(),
                        "traceId", execution.traceId(),
                        "execution", execution,
                        "captureCitations", true
                ));
    }
}
