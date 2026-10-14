package com.example.supportagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic local embedding for tests and the no-key demo profile.
 * It uses hashed word/character n-gram features. Replace it with a production
 * embedding model without changing the VectorStore or RAG service contracts.
 */
public final class HashingEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            embeddings.add(new Embedding(vectorize(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] vectorize(String input) {
        String text = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        float[] vector = new float[dimensions];

        for (String token : text.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) add(vector, "w:" + token, 2.0f);
        }

        String compact = text.replaceAll("\\s+", "");
        addDomainConcepts(vector, compact);
        for (int i = 0; i < compact.length(); i++) {
            add(vector, "c:" + compact.charAt(i), 0.35f);
            if (i + 1 < compact.length()) add(vector, "b:" + compact.substring(i, i + 2), 1.0f);
            if (i + 2 < compact.length()) add(vector, "t:" + compact.substring(i, i + 3), 0.65f);
        }

        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm > 0) {
            float divisor = (float) Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= divisor;
        }
        return vector;
    }

    /**
     * The offline demo has no neural embedding model. These synonym groups make
     * the deterministic fallback useful for the supplied Chinese domain corpus.
     * The production profile can replace this bean with BGE/Qwen embeddings.
     */
    private void addDomainConcepts(float[] vector, String text) {
        addConceptWhenPresent(vector, text, "concept:e03", "e03", "错误码", "故障码", "滚刷卡住", "主刷被");
        addConceptWhenPresent(vector, text, "concept:maintenance", "耗材", "寿命", "滤网", "边刷", "保养", "维护", "换新");
        addConceptWhenPresent(vector, text, "concept:connectivity", "离线", "不在线", "联网", "网络", "充电", "电量", "充电座", "重启");
        addConceptWhenPresent(vector, text, "concept:privacy", "隐私", "地图", "泄露", "其他用户", "租户", "权限", "敏感信息", "设备数据");
    }

    private void addConceptWhenPresent(float[] vector, String text, String concept, String... aliases) {
        for (String alias : aliases) {
            if (text.contains(alias)) {
                add(vector, concept, 8.0f);
                return;
            }
        }
    }

    private void add(float[] vector, String feature, float weight) {
        int hash = feature.hashCode();
        int index = (hash & 0x7fffffff) % dimensions;
        vector[index] += (hash & 1) == 0 ? weight : -weight;
    }
}
