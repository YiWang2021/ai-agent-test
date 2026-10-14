package com.example.supportagent.config;

import com.example.supportagent.rag.HashingEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import java.util.Map;

@Configuration
public class AiConfiguration {

    public static final int EMBEDDING_DIMENSIONS = 384;

    @Bean
    @Profile("!semantic")
    EmbeddingModel embeddingModel() {
        return new HashingEmbeddingModel(EMBEDDING_DIMENSIONS);
    }

    @Bean
    @Profile("semantic")
    TransformersEmbeddingModel semanticEmbeddingModel(
            @Value("${support.embedding.model:file:./data/models/multilingual-minilm/model.onnx}") String model,
            @Value("${support.embedding.tokenizer:file:./data/models/multilingual-minilm/tokenizer.json}") String tokenizer) {
        TransformersEmbeddingModel embedding = new TransformersEmbeddingModel();
        embedding.setModelResource(model);
        embedding.setTokenizerResource(tokenizer);
        embedding.setDisableCaching(true);
        embedding.setTokenizerOptions(Map.of("modelMaxLength", "128", "maxLength", "128", "truncation", "true", "padding", "true"));
        return embedding;
    }

    @Bean
    @Profile("!postgres")
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    @Profile("postgres")
    VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
            @Value("${support.rag.vector-table:vector_store}") String vectorTable) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .vectorTableName(vectorTable)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .vectorTableValidationsEnabled(true)
                .build();
    }
}
