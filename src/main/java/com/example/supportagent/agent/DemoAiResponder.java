package com.example.supportagent.agent;

import com.example.supportagent.memory.ChatTurn;
import com.example.supportagent.rag.RetrievedChunk;
import com.example.supportagent.security.RequestIdentity;
import com.example.supportagent.tool.AgentTools;
import com.example.supportagent.tool.ToolAccessDeniedException;
import com.example.supportagent.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("!ai")
public class DemoAiResponder implements AiResponder {

    private static final Pattern DEVICE_ID = Pattern.compile("device-\\d+");
    private final AgentTools tools;

    public DemoAiResponder(AgentTools tools) {
        this.tools = tools;
    }

    @Override
    public AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, String traceId) {
        return respond(request, identity, history, new AgentExecution(traceId, java.time.Duration.ofSeconds(30), 8));
    }

    @Override
    public AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        String traceId = execution.traceId();
        String message = request.message();
        boolean hasDevice = DEVICE_ID.matcher(message).find();
        ToolContext context = context(request, identity, execution);
        try {
            if (containsAny(message, "创建工单", "提交工单", "报修")) {
                ToolResult result = tools.createSupportTicket(deviceId(message), message, context);
                return answer(result.message() + formatData(result), List.of(), traceId);
            }
            if (containsAny(message, "未关闭工单", "查询工单", "工单状态")) {
                ToolResult result = tools.listOpenTickets(deviceId(message), context);
                return answer(result.message() + formatData(result), List.of(), traceId);
            }
            if (hasDevice && containsAny(message, "耗材", "主刷寿命", "更换主刷")) {
                ToolResult result = tools.getConsumableAdvice(deviceId(message), context);
                return answer(result.message() + formatData(result), List.of(), traceId);
            }
            if (hasDevice && containsAny(message, "设备状态", "当前状态", "电量", "是否在线")) {
                ToolResult result = tools.getDeviceStatus(deviceId(message), context);
                return answer(result.message() + formatData(result), List.of(), traceId);
            }
            return groundedKnowledgeAnswer(message, context, traceId);
        } catch (ToolAccessDeniedException denied) {
            return answer("无法查询该资源：设备不存在或不属于当前用户。", List.of(), traceId);
        }
    }

    @SuppressWarnings("unchecked")
    private AgentAnswer groundedKnowledgeAnswer(String question, ToolContext context, String traceId) {
        ToolResult result = tools.searchKnowledge(question, context);
        if (!result.success()) return answer(result.message(), List.of(), traceId);
        List<RetrievedChunk> chunks = (List<RetrievedChunk>) result.data().get("chunks");
        if (chunks == null || chunks.isEmpty()) {
            return answer("当前知识库没有足够依据回答这个问题，请补充信息或转人工客服。", List.of(), traceId);
        }
        RetrievedChunk best = chunks.get(0);
        String response = "根据《" + best.title() + "》：" + best.text() + "\n来源：" + best.source();
        List<Citation> citations = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            citations.add(new Citation(chunk.title(), chunk.source(), chunk.score()));
        }
        return answer(response, citations, traceId);
    }

    private ToolContext context(AgentRequest request, RequestIdentity identity, AgentExecution execution) {
        return new ToolContext(Map.of(
                "tenantId", identity.tenantId(),
                "userId", identity.userId(),
                "role", identity.role(),
                "confirmedWrite", request.confirmWrite(),
                "idempotencyKey", request.idempotencyKey() == null ? "" : request.idempotencyKey(),
                "traceId", execution.traceId(),
                "execution", execution
        ));
    }

    @Override
    public reactor.core.publisher.Flux<String> stream(AgentRequest request, RequestIdentity identity,
            List<ChatTurn> history, AgentExecution execution) {
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.just(
                respond(request, identity, history, execution).answer()));
    }

    private AgentAnswer answer(String text, List<Citation> citations, String traceId) {
        return new AgentAnswer(text, citations, traceId);
    }

    private String deviceId(String message) {
        Matcher matcher = DEVICE_ID.matcher(message);
        return matcher.find() ? matcher.group() : "";
    }

    private boolean containsAny(String message, String... words) {
        for (String word : words) if (message.contains(word)) return true;
        return false;
    }

    private String formatData(ToolResult result) {
        return result.data().isEmpty() ? "" : "\n数据：" + result.data();
    }
}
