package com.example.supportagent.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class KnowledgeRerankerTest {
    @Test void exactFaultEvidenceBeatsLessRelevantHighRecallCandidate() {
        var vague = new RetrievedChunk("1", "设备指南", "general", "清扫结束可以回到充电座", 0.8);
        var exact = new RetrievedChunk("2", "E03故障", "faults", "E03 主刷缠绕，请关机清理主刷", 0.75);
        var result = new KnowledgeReranker().rank("E03 主刷缠绕", List.of(vague, exact));
        assertThat(result.get(0).chunk()).isEqualTo(exact);
        assertThat(result.get(0).lexicalScore()).isGreaterThan(result.get(1).lexicalScore());
        assertThat(result.get(0).chunk().score()).isEqualTo(0.75);
    }
}
