package com.example.supportagent.evaluation;

import java.util.List;

public record EvaluationReport(
        String mode,
        int total,
        int successful,
        double successRate,
        double averageLatencyMs,
        double p95LatencyMs,
        double meanReciprocalRank,
        String responder,
        String embeddingModel,
        List<EvaluationFailure> failures
) {
    public record EvaluationFailure(String question, String expected, String actual) {}
}
