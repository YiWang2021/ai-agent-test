package com.example.supportagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support")
public record SupportProperties(
        String apiKey,
        Rag rag,
        Agent agent
) {
    public record Rag(int topK, double similarityThreshold, int chunkSize, int chunkOverlap) {}

    public record Agent(int historyLimit, int maxToolCalls, long timeoutSeconds) {}
}
