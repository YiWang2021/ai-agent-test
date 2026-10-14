package com.example.supportagent.concurrency;

import com.example.supportagent.agent.AgentExecution;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class LeaseSupervisorTest {
    @Test void backendFailureCancelsExecutionAndPreventsLateWrite() {
        var supervisor=new LeaseSupervisor();
        var releaseCount=new AtomicInteger();
        var healthy=new AtomicBoolean(true);
        try(var lease=supervisor.supervise(Duration.ofSeconds(10),()->{
            if(!healthy.get())throw new IllegalStateException("simulated Redis outage");
            return true;
        },releaseCount::incrementAndGet)) {
            var execution=new AgentExecution("test",Duration.ofSeconds(30),8);
            execution.protectWith(lease);
            healthy.set(false);
            assertThatThrownBy(lease::checkValid).isInstanceOf(LeaseLostException.class);
            assertThatThrownBy(execution::beginWrite).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(execution.writeStarted()).isFalse();
            lease.close(); lease.close();
            assertThat(releaseCount).hasValue(1);
        } finally {supervisor.shutdown();}
    }
    @Test void closedLeaseCannotBeReused() {
        var supervisor=new LeaseSupervisor();
        try {
            var lease=supervisor.supervise(Duration.ofSeconds(10),()->true,()->{});
            lease.close();
            assertThatThrownBy(lease::checkValid).isInstanceOf(LeaseLostException.class);
        } finally {supervisor.shutdown();}
    }
}

