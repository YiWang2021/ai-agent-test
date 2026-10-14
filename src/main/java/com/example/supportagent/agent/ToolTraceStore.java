package com.example.supportagent.agent;

import com.example.supportagent.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Request-scoped-by-trace-id evidence collected during tool execution. */
@Component
public class ToolTraceStore {
    public record ToolEvent(String tool, String code) {}
    private final java.util.Map<String, List<ToolEvent>> events = new java.util.LinkedHashMap<>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, List<ToolEvent>> eldest) { return size() > 1024; }
    };

    public synchronized void recordTool(String traceId, String tool, String code) {
        if (traceId == null || traceId.isBlank()) return;
        List<ToolEvent> trace = events.computeIfAbsent(traceId, ignored -> new java.util.ArrayList<>());
        if (trace.size() < 32) trace.add(new ToolEvent(tool, code));
    }
    public synchronized List<ToolEvent> drainEvents(String traceId) {
        List<ToolEvent> trace = events.remove(traceId);
        return trace == null ? List.of() : List.copyOf(trace);
    }

    private final ConcurrentHashMap<String, List<Citation>> citations = new ConcurrentHashMap<>();

    public void recordRetrieval(String traceId, List<RetrievedChunk> chunks) {
        if (traceId == null || traceId.isBlank()) return;
        citations.put(traceId, chunks.stream()
                .map(chunk -> new Citation(chunk.title(), chunk.source(), chunk.score()))
                .toList());
    }

    public List<Citation> drainCitations(String traceId) {
        List<Citation> result = citations.remove(traceId);
        return result == null ? List.of() : result;
    }
}
