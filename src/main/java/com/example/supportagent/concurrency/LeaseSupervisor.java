package com.example.supportagent.concurrency;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

@Component
public class LeaseSupervisor {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(4, task -> {
        Thread thread = new Thread(task, "lease-renewal");
        thread.setDaemon(true);
        return thread;
    });
    public LeaseSupervisor() { scheduler.setRemoveOnCancelPolicy(true); }
    public Lease supervise(Duration ttl, BooleanSupplier renew, Runnable release) {
        return new Renewable(ttl, renew, release);
    }
    @PreDestroy public void shutdown() { scheduler.shutdownNow(); }

    private final class Renewable implements Lease {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicReference<Runnable> callback = new AtomicReference<>(() -> {});
        private final BooleanSupplier renew;
        private final Runnable release;
        private final long ttlNanos;
        private final String traceId = org.slf4j.MDC.get("traceId");
        private volatile long expiresAt;
        private final ScheduledFuture<?> heartbeat;

        Renewable(Duration ttl, BooleanSupplier renew, Runnable release) {
            this.renew = renew; this.release = release; this.ttlNanos = ttl.toNanos();
            this.expiresAt = System.nanoTime() + ttlNanos;
            long interval = Math.max(10, ttl.toMillis() / 3);
            this.heartbeat = scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        }
        private synchronized boolean refresh() {
            if (closed.get() || lost.get()) return false;
            long started = System.nanoTime();
            // Never revive a lease after a JVM pause longer than its TTL.
            if (started >= expiresAt) { markLost(); return false; }
            try {
                if (!renew.getAsBoolean()) { markLost(); return false; }
                expiresAt = started + ttlNanos; // conservative: exclude round-trip time
                if (System.nanoTime() >= expiresAt) { markLost(); return false; }
                return true;
            } catch (RuntimeException error) {
                LoggerFactory.getLogger(LeaseSupervisor.class).warn("Lease renewal failed; rejecting further work", error);
                markLost(); return false;
            }
        }
        private void tick() { refresh(); }
        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                LoggerFactory.getLogger(LeaseSupervisor.class).warn("Lease ownership lost traceId={}; rejecting further work", traceId);
                try { callback.get().run(); }
                catch (RuntimeException error) { LoggerFactory.getLogger(LeaseSupervisor.class).warn("Lease loss callback failed", error); }
            }
        }
        @Override public void checkValid() { if (!refresh()) throw new LeaseLostException(); }
        @Override public void onLost(Runnable action) {
            callback.set(action);
            if (lost.get()) action.run();
        }
        @Override public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            heartbeat.cancel(false);
            try { release.run(); }
            catch (RuntimeException error) {
                // Do not mask the original result; Redis expiration recovers abandoned permits.
                LoggerFactory.getLogger(LeaseSupervisor.class).warn("Lease release failed; awaiting TTL recovery", error);
            }
        }
    }
}

