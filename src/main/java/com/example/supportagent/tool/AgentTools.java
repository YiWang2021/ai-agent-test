package com.example.supportagent.tool;

import com.example.supportagent.agent.ToolTraceStore;
import com.example.supportagent.agent.AgentExecution;
import com.example.supportagent.domain.Device;
import com.example.supportagent.domain.DeviceRepository;
import com.example.supportagent.domain.SupportTicket;
import com.example.supportagent.domain.SupportTicketRepository;
import com.example.supportagent.rag.KnowledgeService;
import com.example.supportagent.rag.RetrievedChunk;
import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class AgentTools {
    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    private final DeviceRepository deviceRepository;
    private final SupportTicketRepository ticketRepository;
    private final TicketService ticketService;
    private final KnowledgeService knowledgeService;
    private final MeterRegistry meterRegistry;
    private final ToolTraceStore traceStore;

    public AgentTools(DeviceRepository deviceRepository, SupportTicketRepository ticketRepository,
                      TicketService ticketService, KnowledgeService knowledgeService, MeterRegistry meterRegistry,
                      ToolTraceStore traceStore) {
        this.deviceRepository = deviceRepository;
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.knowledgeService = knowledgeService;
        this.meterRegistry = meterRegistry;
        this.traceStore = traceStore;
    }

    @Tool(name = "search_product_knowledge", description = "检索产品手册、故障码、维护、充电和隐私政策。回答产品知识问题前必须调用。")
    public ToolResult searchKnowledge(
            @ToolParam(description = "用户的完整问题") String question,
            ToolContext context) {
        return execute("search_product_knowledge", context, List.of(String.valueOf(question)), true, () -> {
            if (question == null || question.isBlank() || question.length() > 4000) return ToolResult.rejected("INVALID_QUERY", "请提供有效的知识问题");
            RequestIdentity identity = identity(context);
            List<RetrievedChunk> chunks = knowledgeService.search(identity.tenantId(), question);
            if (context.getContext().get("execution") instanceof AgentExecution execution) execution.checkActive();
            if (Boolean.TRUE.equals(context.getContext().get("captureCitations"))) {
                traceStore.recordRetrieval(String.valueOf(context.getContext().getOrDefault("traceId", "")), chunks);
            }
            meterRegistry.counter("support.tool.calls", "tool", "search_product_knowledge").increment();
            if (chunks.isEmpty()) return ToolResult.rejected("NO_KNOWLEDGE", "知识库没有找到可靠依据，请转人工客服");
            return ToolResult.ok("找到知识库证据", Map.of("chunks", chunks));
        });
    }

    @Tool(name = "get_device_status", description = "查询当前用户自己的设备状态、电量、错误码和最后在线时间。只读工具。")
    public ToolResult getDeviceStatus(
            @ToolParam(description = "设备编号，例如 device-001") String deviceId,
            ToolContext context) {
        return execute("get_device_status", context, List.of(String.valueOf(deviceId)), true, () -> {
            RequestIdentity identity = identity(context);
            Device device = ownedDevice(deviceId, identity);
            meterRegistry.counter("support.tool.calls", "tool", "get_device_status").increment();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deviceId", device.getId());
            data.put("model", device.getModel());
            data.put("status", device.getStatus());
            data.put("batteryLevel", device.getBatteryLevel());
            data.put("errorCode", device.getErrorCode() == null ? "NONE" : device.getErrorCode());
            data.put("lastSeenAt", device.getLastSeenAt().toString());
            return ToolResult.ok("设备状态查询成功", data);
        });
    }

    @Tool(name = "get_consumable_advice", description = "查询当前用户设备的主刷剩余寿命，并给出是否更换的建议。只读工具。")
    public ToolResult getConsumableAdvice(
            @ToolParam(description = "设备编号，例如 device-001") String deviceId,
            ToolContext context) {
        return execute("get_consumable_advice", context, List.of(String.valueOf(deviceId)), true, () -> {
            RequestIdentity identity = identity(context);
            Device device = ownedDevice(deviceId, identity);
            meterRegistry.counter("support.tool.calls", "tool", "get_consumable_advice").increment();
            int life = device.getBrushLifePercent();
            String advice = life < 10 ? "应尽快更换主刷" : life < 20 ? "建议准备更换主刷" : "暂不需要更换主刷";
            return ToolResult.ok(advice, Map.of("deviceId", deviceId, "brushLifePercent", life, "advice", advice));
        });
    }

    @Tool(name = "list_open_tickets", description = "查询当前用户指定设备尚未关闭的售后工单。只读工具。")
    public ToolResult listOpenTickets(
            @ToolParam(description = "设备编号，例如 device-001") String deviceId,
            ToolContext context) {
        return execute("list_open_tickets", context, List.of(String.valueOf(deviceId)), true, () -> {
            RequestIdentity identity = identity(context);
            ownedDevice(deviceId, identity);
            List<Map<String, Object>> tickets = ticketRepository
                    .findByTenantIdAndOwnerIdAndDeviceIdAndStatusOrderByCreatedAtDesc(
                            identity.tenantId(), identity.userId(), deviceId, "OPEN")
                    .stream().map(this::ticketMap).toList();
            meterRegistry.counter("support.tool.calls", "tool", "list_open_tickets").increment();
            return ToolResult.ok("查询到 " + tickets.size() + " 个未关闭工单", Map.of("tickets", tickets));
        });
    }

    @Tool(name = "create_support_ticket", description = "为当前用户自己的设备创建售后工单。只有用户在当前请求明确确认后才能执行。写操作。")
    public ToolResult createSupportTicket(
            @ToolParam(description = "设备编号，例如 device-001") String deviceId,
            @ToolParam(description = "具体故障现象，不能为空") String issue,
            ToolContext context) {
        return execute("create_support_ticket", context, List.of(String.valueOf(deviceId), String.valueOf(issue)), false, () -> {
            RequestIdentity identity = identity(context);
            ownedDevice(deviceId, identity);
            boolean confirmed = Boolean.TRUE.equals(context.getContext().get("confirmedWrite"));
            String idempotencyKey = String.valueOf(context.getContext().getOrDefault("idempotencyKey", ""));
            if (!confirmed) return ToolResult.rejected("CONFIRMATION_REQUIRED", "创建工单是写操作，需要用户明确确认");
            if (idempotencyKey.isBlank()) return ToolResult.rejected("IDEMPOTENCY_KEY_REQUIRED", "写操作缺少幂等键");
            if (issue == null || issue.isBlank() || issue.length() > 1000) {
                return ToolResult.rejected("INVALID_ISSUE", "故障描述长度必须为 1 到 1000 个字符");
            }
            if (context.getContext().get("execution") instanceof AgentExecution execution) execution.beginWrite();
            SupportTicket ticket = ticketService.createIdempotently(identity.tenantId(), identity.userId(),
                    deviceId, issue.trim(), idempotencyKey);
            meterRegistry.counter("support.tool.calls", "tool", "create_support_ticket").increment();
            return ToolResult.ok("售后工单已创建或已存在", ticketMap(ticket));
        });
    }

    private ToolResult execute(String tool, ToolContext context, List<String> arguments, boolean readOnly, Supplier<ToolResult> action) {
        if (context.getContext().get("execution") instanceof AgentExecution execution) {
            return execution.call(tool, arguments, readOnly, () -> loggedExecute(tool, context, action));
        }
        return loggedExecute(tool, context, action);
    }

    private ToolResult loggedExecute(String tool, ToolContext context, Supplier<ToolResult> action) {
        String traceId = String.valueOf(context.getContext().getOrDefault("traceId", ""));
        long started = System.nanoTime();
        log.info("Tool started tool={} traceId={}", tool, traceId);
        try {
            ToolResult result = action.get();
            traceStore.recordTool(traceId, tool, result.code());
            log.info("Tool completed tool={} traceId={} code={} durationMs={}",
                    tool, traceId, result.code(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (ToolAccessDeniedException denied) {
            traceStore.recordTool(traceId, tool, "RESOURCE_UNAVAILABLE");
            log.warn("Tool rejected tool={} traceId={} code=RESOURCE_UNAVAILABLE", tool, traceId);
            return ToolResult.rejected("RESOURCE_UNAVAILABLE",
                    "设备不存在或不属于当前用户，请核对设备编号。可以继续咨询通用故障排查方法。");
        } catch (IdempotencyConflictException conflict) {
            traceStore.recordTool(traceId, tool, "IDEMPOTENCY_CONFLICT");
            log.warn("Tool rejected tool={} traceId={} code=IDEMPOTENCY_CONFLICT", tool, traceId);
            return ToolResult.rejected("IDEMPOTENCY_CONFLICT", conflict.getMessage());
        } catch (RuntimeException error) {
            traceStore.recordTool(traceId, tool, "ERROR");
            log.error("Tool failed tool=" + tool + " traceId=" + traceId, error);
            throw error;
        }
    }

    private Device ownedDevice(String deviceId, RequestIdentity identity) {
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new ToolAccessDeniedException("设备不存在或不属于当前用户");
        }
        return deviceRepository.findByIdAndTenantIdAndOwnerId(deviceId, identity.tenantId(), identity.userId())
                .orElseThrow(() -> new ToolAccessDeniedException("设备不存在或不属于当前用户"));
    }

    private RequestIdentity identity(ToolContext context) {
        Map<String, Object> values = context.getContext();
        String tenantId = String.valueOf(values.getOrDefault("tenantId", ""));
        String userId = String.valueOf(values.getOrDefault("userId", ""));
        if (tenantId.isBlank() || userId.isBlank()) throw new ToolAccessDeniedException("工具上下文缺少身份信息");
        return new RequestIdentity(tenantId, userId, String.valueOf(values.getOrDefault("role", "USER")));
    }

    private Map<String, Object> ticketMap(SupportTicket ticket) {
        return Map.of(
                "ticketId", ticket.getId(),
                "deviceId", ticket.getDeviceId(),
                "issue", ticket.getIssue(),
                "status", ticket.getStatus(),
                "createdAt", ticket.getCreatedAt().toString()
        );
    }
}
