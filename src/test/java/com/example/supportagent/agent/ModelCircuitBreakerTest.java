package com.example.supportagent.agent;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class ModelCircuitBreakerTest {
    static class TestClock extends Clock {
        long millis;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }
    @Test void opensAllowsSingleProbeAndRecoversWithoutStaleSuccessClosingIt() {
        var clock = new TestClock();
        var breaker = new ModelCircuitBreaker(new ReliabilityProperties(2, 0, 0, 1, 1), clock);
        var first = breaker.acquire().orElseThrow();
        var stale = breaker.acquire().orElseThrow();
        breaker.failure(first);
        breaker.success(stale);
        assertThat(breaker.acquire()).isEmpty();
        clock.millis = 1001;
        var probe = breaker.acquire().orElseThrow();
        assertThat(probe.probe()).isTrue();
        assertThat(breaker.acquire()).isEmpty();
        breaker.success(probe);
        assertThat(breaker.state()).isEqualTo("CLOSED");
        assertThat(breaker.acquire()).isPresent();
    }
}
