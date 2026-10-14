package com.example.supportagent.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Optional;

/** Small, explicit state machine for teaching: CLOSED -> OPEN -> one HALF_OPEN probe. */
@Component
public class ModelCircuitBreaker {
    public record Permit(long generation, boolean probe) {}
    private final ReliabilityProperties config;
    private final Clock clock;
    private long generation;
    private long openUntil;
    private int failures;
    private boolean open;
    private boolean probing;

    @Autowired
    public ModelCircuitBreaker(ReliabilityProperties config) { this(config, Clock.systemUTC()); }
    ModelCircuitBreaker(ReliabilityProperties config, Clock clock) { this.config = config; this.clock = clock; }

    public synchronized Optional<Permit> acquire() {
        if (!open) return Optional.of(new Permit(generation, false));
        if (clock.millis() < openUntil || probing) return Optional.empty();
        probing = true;
        return Optional.of(new Permit(generation, true));
    }
    public synchronized void success(Permit permit) {
        if (permit.generation() != generation) return;
        if (permit.probe()) { open = false; probing = false; generation++; }
        failures = 0;
    }
    public synchronized void failure(Permit permit) {
        if (permit.generation() != generation) return;
        if (permit.probe() || ++failures >= config.failureThreshold()) {
            open = true; probing = false; generation++;
            openUntil = clock.millis() + config.openSeconds() * 1000;
        }
    }
    public synchronized void abandon(Permit permit) {
        if (permit.generation() == generation && permit.probe()) probing = false;
    }
    public synchronized String state() { return !open ? "CLOSED" : probing ? "HALF_OPEN" : "OPEN"; }
}
