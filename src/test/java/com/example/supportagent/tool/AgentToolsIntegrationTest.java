package com.example.supportagent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentToolsIntegrationTest {

    @Autowired
    AgentTools tools;
    @Autowired TicketService ticketService;

    @Test
    void idempotencyReplayMustMatchOwnerDeviceAndIssue() {
        String key = "collision-" + java.util.UUID.randomUUID();
        ticketService.createIdempotently("tenant-a", "user-001", "device-001", "E03", key);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ticketService.createIdempotently("tenant-a", "user-002", "device-001", "E03", key))
                .isInstanceOf(ToolAccessDeniedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ticketService.createIdempotently("tenant-a", "user-001", "device-002", "E03", key))
                .isInstanceOf(IdempotencyConflictException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ticketService.createIdempotently("tenant-a", "user-001", "device-001", "different issue", key))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void writeToolRequiresConfirmation() {
        ToolResult result = tools.createSupportTicket("device-001", "E03反复出现", context(false, "write-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    void writeToolIsIdempotent() {
        ToolResult first = tools.createSupportTicket("device-001", "E03反复出现", context(true, "write-2"));
        ToolResult second = tools.createSupportTicket("device-001", "E03反复出现", context(true, "write-2"));

        assertThat(first.success()).isTrue();
        assertThat(second.data().get("ticketId")).isEqualTo(first.data().get("ticketId"));
    }

    @Test
    void databaseConstraintKeepsConcurrentWritesIdempotent() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> tools.createSupportTicket(
                        "device-001", "并发报修", context(true, "concurrent-write")).data().get("ticketId"));
            }
            var futures = executor.invokeAll(tasks);
            List<Object> ids = new ArrayList<>();
            for (var future : futures) ids.add(future.get());
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tenantAndOwnerIsolationIsEnforcedInsideTool() {
        for (String deviceId : List.of("dev-00023", "device-900")) {
            List<ToolResult> results = List.of(
                    tools.getDeviceStatus(deviceId, context(false, "read-1")),
                    tools.getConsumableAdvice(deviceId, context(false, "read-1")),
                    tools.listOpenTickets(deviceId, context(false, "read-1")),
                    tools.createSupportTicket(deviceId, "充电异常", context(true, "denied-write")));
            for (ToolResult result : results) {
                assertThat(result.success()).isFalse();
                assertThat(result.code()).isEqualTo("RESOURCE_UNAVAILABLE");
                assertThat(result.message()).contains("设备不存在或不属于当前用户");
                assertThat(result.data()).isEmpty();
            }
        }
    }

    private ToolContext context(boolean confirmed, String key) {
        return new ToolContext(Map.of(
                "tenantId", "tenant-a",
                "userId", "user-001",
                "role", "USER",
                "confirmedWrite", confirmed,
                "idempotencyKey", key,
                "traceId", "test-trace"
        ));
    }
}
