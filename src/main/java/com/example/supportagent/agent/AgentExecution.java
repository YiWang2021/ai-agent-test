package com.example.supportagent.agent;

import com.example.supportagent.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/** One HTTP request, including its read-only retries. Never reused across users or requests. */
public final class AgentExecution {
    private record ReadKey(String tool, List<String> arguments) {}
    private final long deadline;
    private final int maxCalls;
    private final String traceId;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean writeStarted = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final ConcurrentHashMap<ReadKey, ToolResult> reads = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> guards = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AgentExecution(String traceId, Duration timeout, int maxCalls) {
        this.traceId = traceId;
        this.deadline = System.nanoTime() + timeout.toNanos();
        this.maxCalls = maxCalls;
    }
    public String traceId() { return traceId; }
    public boolean writeStarted() { return writeStarted.get(); }
    public long remainingMillis() { return Math.max(0, (deadline - System.nanoTime()) / 1_000_000); }
    public void cancel() { cancelled.set(true); }
    public void checkActive() {
        if (cancelled.get() || remainingMillis() == 0 || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Agent request deadline exceeded or cancelled");
        }
    }
    public void protectWith(com.example.supportagent.concurrency.Lease lease) {
        guards.add(lease::checkValid);
        lease.onLost(this::cancel);
    }
    public void checkPermits() { checkActive(); guards.forEach(Runnable::run); checkActive(); }
    public void beginWrite() { checkPermits(); writeStarted.set(true); reads.clear(); }

    public ToolResult call(String tool, List<String> arguments, boolean readOnly, Supplier<ToolResult> action) {
        checkPermits();
        if (calls.incrementAndGet() > maxCalls) throw new IllegalStateException("Agent tool call limit exceeded");
        if (!readOnly) return action.get();
        ReadKey key = new ReadKey(tool, List.copyOf(arguments));
        if (reads.containsKey(key)) {
            LoggerFactory.getLogger(AgentExecution.class).info("Tool cache hit tool={} traceId={}", tool, traceId);
        }
        return reads.computeIfAbsent(key, ignored -> { checkActive(); return action.get(); });
    }
}
