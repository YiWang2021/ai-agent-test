package com.example.supportagent.tool;

import com.example.supportagent.agent.ToolTraceStore;
import com.example.supportagent.domain.DeviceRepository;
import com.example.supportagent.domain.SupportTicketRepository;
import com.example.supportagent.rag.KnowledgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentToolsFailureTest {
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final TicketService tickets = mock(TicketService.class);
    private final AgentTools tools = new AgentTools(devices, mock(SupportTicketRepository.class),
            tickets, mock(KnowledgeService.class), new SimpleMeterRegistry(), new ToolTraceStore());
    private final ToolContext context = new ToolContext(Map.of(
            "tenantId", "tenant-a", "userId", "user-001", "traceId", "failure-test",
            "confirmedWrite", true, "idempotencyKey", "denied-write"));

    @Test
    void modelToolCallbackReturnsBusinessFailureInsteadOfThrowing() {
        when(devices.findByIdAndTenantIdAndOwnerId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        var callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("get_device_status"))
                .findFirst().orElseThrow();
        String result = callback.call("{\"deviceId\":\"dev-00023\"}", context);
        assertThat(result).contains("RESOURCE_UNAVAILABLE", "设备不存在或不属于当前用户");
    }

    @Test
    void deniedWriteDoesNotReachTicketService() {
        when(devices.findByIdAndTenantIdAndOwnerId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertThat(tools.createSupportTicket("dev-00023", "充电异常", context).success()).isFalse();
        verifyNoInteractions(tickets);
    }

    @Test
    void databaseOutageIsNotDisguisedAsMissingDevice() {
        var failure = new IllegalStateException("database unavailable");
        when(devices.findByIdAndTenantIdAndOwnerId(anyString(), anyString(), anyString()))
                .thenThrow(failure);
        assertThatThrownBy(() -> tools.getDeviceStatus("device-001", context)).isSameAs(failure);
    }
}
