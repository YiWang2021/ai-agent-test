package com.example.supportagent.rag;

import java.util.List;

public record RetrievalReport(List<RetrievedChunk> candidates, List<KnowledgeReranker.RankedChunk> ranked,
                              List<RetrievedChunk> selected, boolean rerankEnabled, long durationMillis) {}
