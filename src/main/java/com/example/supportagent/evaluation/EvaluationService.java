package com.example.supportagent.evaluation;

import com.example.supportagent.agent.*;
import com.example.supportagent.config.SupportProperties;
import com.example.supportagent.rag.KnowledgeService;
import com.example.supportagent.rag.RetrievedChunk;
import com.example.supportagent.security.RequestIdentity;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class EvaluationService {
    private final KnowledgeService knowledge;
    private final ReliableAgentRunner runner;
    private final AiResponder responder;
    private final EmbeddingModel embedding;
    private final ToolTraceStore traces;
    private final SupportProperties config;
    private final com.example.supportagent.concurrency.AdmissionController admission;

    public EvaluationService(KnowledgeService knowledge, ReliableAgentRunner runner, AiResponder responder,
                             EmbeddingModel embedding, ToolTraceStore traces, SupportProperties config,
                             com.example.supportagent.concurrency.AdmissionController admission) {
        this.knowledge = knowledge; this.runner = runner; this.responder = responder;
        this.embedding = embedding; this.traces = traces; this.config = config;
        this.admission = admission;
    }

    public EvaluationReport run(String mode, int limit) {
        if (!Set.of("retrieval", "retrieval-baseline", "agent", "tools").contains(mode)) {
            throw new IllegalArgumentException("mode must be retrieval, retrieval-baseline, agent or tools");
        }
        String file = mode.equals("tools") ? "evaluation/tool-dataset.tsv" : "evaluation/dataset.tsv";
        List<String[]> cases = load(file).stream().limit(Math.max(1, Math.min(limit, 100))).toList();
        List<EvaluationReport.EvaluationFailure> failures = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        double reciprocalRank = 0;
        for (String[] row : cases) {
            long started = System.nanoTime();
            String traceId = "eval-" + UUID.randomUUID();
            AgentExecution execution = new AgentExecution(traceId, Duration.ofSeconds(config.agent().timeoutSeconds()), config.agent().maxToolCalls());
            try {
                if (mode.startsWith("retrieval")) {
                    List<RetrievedChunk> chunks = knowledge.inspect("tenant-a", row[0], !mode.equals("retrieval-baseline")).selected();
                    int rank = -1;
                    for (int i=0;i<chunks.size();i++) if (chunks.get(i).source().equals(row[1])) { rank=i+1; break; }
                    if (rank > 0) reciprocalRank += 1.0 / rank;
                    else failures.add(new EvaluationReport.EvaluationFailure(row[0], row[1],
                            chunks.stream().map(RetrievedChunk::source).distinct().toList().toString()));
                } else {
                    // All bundled scenarios are read-only or unconfirmed writes. Never authorize a write in this endpoint.
                    var identity = new RequestIdentity("tenant-a", "user-001", "USER");
                    AgentAnswer answer;
                    try (var permit = admission.acquire(identity)) {
                        execution.protectWith(permit);
                        answer = runner.run(new AgentRequest(traceId, row[0], null, false), identity, List.of(), execution);
                    }
                    List<ToolTraceStore.ToolEvent> events = traces.drainEvents(traceId);
                    boolean passed = mode.equals("tools")
                            ? events.stream().anyMatch(event -> event.tool().equals(row[1]) && event.code().equals(row[2]))
                                && answer.answer().contains(row[3]) && !answer.answer().contains("B-ONLY-7788")
                            : answer.answer().contains(row[2])
                                && answer.citations().stream().anyMatch(citation -> citation.source().equals(row[1]));
                    if (!passed) failures.add(new EvaluationReport.EvaluationFailure(row[0],
                            mode.equals("tools") ? row[1] + ":" + row[2] + ":" + row[3] : row[1] + ":" + row[2],
                            abbreviate(answer.answer()) + " tools=" + events));
                }
            } catch (RuntimeException error) {
                failures.add(new EvaluationReport.EvaluationFailure(row[0], "successful evaluation", error.getClass().getSimpleName()));
            } finally {
                execution.cancel(); traces.drainCitations(traceId); traces.drainEvents(traceId);
                latencies.add((System.nanoTime() - started) / 1_000_000.0);
            }
        }
        int successful = cases.size() - failures.size();
        List<Double> sorted = latencies.stream().sorted().toList();
        return new EvaluationReport(mode, cases.size(), successful, round((double) successful / Math.max(1, cases.size())),
                round(latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                sorted.isEmpty() ? 0 : round(sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1))),
                round(reciprocalRank / Math.max(1, cases.size())), responder.getClass().getSimpleName(),
                embedding.getClass().getSimpleName(), List.copyOf(failures));
    }

    private List<String[]> load(String file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).map(line -> line.split("\\t", -1)).toList();
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot load evaluation fixture", error); }
    }
    private String abbreviate(String value) { return value.length() <= 160 ? value : value.substring(0, 160) + "..."; }
    private double round(double value) { return Math.round(value * 10000.0) / 10000.0; }
}
