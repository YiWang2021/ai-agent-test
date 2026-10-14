package com.example.supportagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:semantic_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("semantic")
@EnabledIfEnvironmentVariable(named = "RUN_SEMANTIC_TESTS", matches = "true")
class SemanticEmbeddingIntegrationTest {
    @Autowired EmbeddingModel embedding;
    @Autowired KnowledgeService knowledge;

    @Test void realTransformerUnderstandsChineseParaphrases() {
        float[] query = embedding.embed("机器人充不进电");
        assertThat(query).hasSize(384);
        assertThat(cosine(query, embedding.embed("设备无法充电")))
                .isGreaterThan(cosine(query, embedding.embed("用户可以删除地图和隐私数据")));
    }
    @Test void realSemanticRetrievalReturnsFaultSourceAndKeepsTenantIsolation() {
        assertThat(knowledge.search("tenant-a", "E03主刷缠绕怎么办"))
                .anyMatch(chunk -> chunk.source().equals("manual/error-codes.md"));
        assertThat(knowledge.search("tenant-a", "企业B保修编码"))
                .noneMatch(chunk -> chunk.text().contains("B-ONLY-7788"));
    }
    private double cosine(float[] a, float[] b) {
        double dot=0, aa=0, bb=0;
        for (int i=0;i<a.length;i++) { dot+=a[i]*b[i]; aa+=a[i]*a[i]; bb+=b[i]*b[i]; }
        return dot / Math.sqrt(aa*bb);
    }
}
