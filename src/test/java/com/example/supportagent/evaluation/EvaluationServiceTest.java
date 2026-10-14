package com.example.supportagent.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvaluationServiceTest {

    @Autowired
    EvaluationService evaluationService;

    @Test
    void toolScenariosCheckInvocationsPermissionsAndWriteConfirmation() {
        EvaluationReport report = evaluationService.run("tools", 50);
        assertThat(report.total()).isEqualTo(10);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void retrievalComparisonReportsRankingAndLatency() {
        EvaluationReport baseline = evaluationService.run("retrieval-baseline", 50);
        EvaluationReport reranked = evaluationService.run("retrieval", 50);
        assertThat(baseline.meanReciprocalRank()).isBetween(0.0, 1.0);
        assertThat(reranked.meanReciprocalRank()).isBetween(0.0, 1.0);
        assertThat(reranked.p95LatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(reranked.embeddingModel()).isEqualTo("HashingEmbeddingModel");
    }

    @Test
    void retrievalDatasetHasFiftyCasesAndMeetsQualityGate() {
        EvaluationReport report = evaluationService.run("retrieval", 50);

        assertThat(report.total()).isEqualTo(50);
        assertThat(report.successRate()).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void deterministicAgentMeetsKeywordQualityGate() {
        EvaluationReport report = evaluationService.run("agent", 50);

        assertThat(report.total()).isEqualTo(50);
        assertThat(report.successRate()).isGreaterThanOrEqualTo(0.80);
    }
}
