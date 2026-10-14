package com.example.supportagent.agent;

import com.example.supportagent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AgentExecutionTest {
    @Test void onlyIdenticalReadCallsWithinOneRequestAreReused() {
        var first = new AgentExecution("first", Duration.ofSeconds(5), 8);
        var second = new AgentExecution("second", Duration.ofSeconds(5), 8);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<ToolResult> action = () -> ToolResult.ok("ok", Map.of("call", calls.incrementAndGet()));
        first.call("status", List.of("device-001"), true, action);
        first.call("status", List.of("device-001"), true, action);
        first.call("status", List.of("device-002"), true, action);
        second.call("status", List.of("device-001"), true, action);
        assertThat(calls.get()).isEqualTo(3);
        first.beginWrite();
        first.call("status", List.of("device-001"), true, action);
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void cancelledRequestCannotStartToolsOrWrites() {
        var execution = new AgentExecution("cancel", Duration.ofSeconds(5), 8);
        execution.cancel();
        assertThatThrownBy(execution::beginWrite).isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> execution.call("status", List.of(), true, () -> { throw new AssertionError("executed"); }))
                .isInstanceOf(CancellationException.class);
        assertThat(execution.writeStarted()).isFalse();
    }
    @Test void cacheHitsStillCountTowardsLoopBudget() {
        var execution = new AgentExecution("loop", Duration.ofSeconds(5), 1);
        execution.call("status", List.of(), true, () -> ToolResult.ok("ok", Map.of()));
        assertThatThrownBy(() -> execution.call("status", List.of(), true, () -> ToolResult.ok("ok", Map.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("limit");
    }
}
