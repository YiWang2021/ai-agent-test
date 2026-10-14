package com.example.supportagent.rag;

public record RetrievedChunk(
        String id,
        String title,
        String source,
        String text,
        double score
) {}
