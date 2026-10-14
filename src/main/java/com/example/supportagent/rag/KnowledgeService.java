package com.example.supportagent.rag;

import com.example.supportagent.config.SupportProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeService {

    private final VectorStore vectorStore;
    private final SupportProperties properties;
    private final KnowledgeManifestRepository manifestRepository;
    private final Timer retrievalTimer;
    private final Counter retrievalMissCounter;
    private final KnowledgeReranker reranker;
    private final int candidateK;
    private final boolean rerankEnabled;
    private final String indexNamespace;
    private final KnowledgeWriteCoordinator writes;

    public KnowledgeService(VectorStore vectorStore, SupportProperties properties,
                            KnowledgeManifestRepository manifestRepository, MeterRegistry registry,
                            KnowledgeReranker reranker,
                            @Value("${support.rag.candidate-k:12}") int candidateK,
                            @Value("${support.rag.rerank-enabled:true}") boolean rerankEnabled,
                            @Value("${support.rag.index-namespace:}") String indexNamespace,
                            KnowledgeWriteCoordinator writes) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.manifestRepository = manifestRepository;
        this.retrievalTimer = registry.timer("support.rag.retrieval.duration");
        this.retrievalMissCounter = registry.counter("support.rag.retrieval.miss");
        this.reranker = reranker;
        this.candidateK = Math.max(candidateK, properties.rag().topK());
        this.rerankEnabled = rerankEnabled;
        this.indexNamespace = indexNamespace;
        this.writes = writes;
    }

    public int upsert(String tenantId, KnowledgeDocumentRequest request) {
        validateIdentityPart(tenantId);
        validateIdentityPart(request.documentId());
        String documentKey = manifestKey(tenantId, request.documentId());
        return writes.write(documentKey, () -> upsertLocked(tenantId, request, documentKey));
    }

    private int upsertLocked(String tenantId, KnowledgeDocumentRequest request, String documentKey) {
        deleteInternal(documentKey);

        List<String> parts = split(request.content(), properties.rag().chunkSize(), properties.rag().chunkOverlap());
        List<Document> documents = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            // PgVectorStore uses UUID primary keys; retain stable IDs across upserts.
            String chunkId = UUID.nameUUIDFromBytes(
                    (documentKey + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            chunkIds.add(chunkId);
            documents.add(new Document(chunkId, parts.get(i), Map.of(
                    "tenant_id", tenantId,
                    "document_id", request.documentId(),
                    "title", request.title(),
                    "source", request.source(),
                    "chunk_index", i
            )));
        }
        vectorStore.add(documents);
        manifestRepository.save(KnowledgeManifest.of(documentKey, tenantId, request.documentId(), chunkIds));
        return documents.size();
    }

    public boolean delete(String tenantId, String documentId) {
        validateIdentityPart(tenantId);
        validateIdentityPart(documentId);
        String documentKey = manifestKey(tenantId, documentId);
        return writes.write(documentKey, () -> deleteInternal(documentKey));
    }

    public List<RetrievedChunk> search(String tenantId, String query) {
        return inspect(tenantId, query, rerankEnabled).selected();
    }

    public RetrievalReport inspect(String tenantId, String query, boolean rerank) {
        validateIdentityPart(tenantId);
        if (query == null || query.isBlank() || query.length() > 4000) throw new IllegalArgumentException("invalid knowledge query");
        long started = System.nanoTime();
        List<Document> results = retrievalTimer.record(() -> vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(candidateK)
                        .similarityThreshold(properties.rag().similarityThreshold())
                        .filterExpression("tenant_id == '" + tenantId + "'")
                        .build()));
        if (results == null || results.isEmpty()) {
            retrievalMissCounter.increment();
            return new RetrievalReport(List.of(), List.of(), List.of(), rerank, (System.nanoTime() - started) / 1_000_000);
        }
        List<RetrievedChunk> candidates = results.stream().map(document -> new RetrievedChunk(
                document.getId(),
                String.valueOf(document.getMetadata().get("title")),
                String.valueOf(document.getMetadata().get("source")),
                document.getText(),
                document.getScore() == null ? 0.0 : document.getScore()
        )).toList();
        List<KnowledgeReranker.RankedChunk> ranked = rerank ? reranker.rank(query, candidates) : List.of();
        List<RetrievedChunk> selected = (rerank ? ranked.stream().map(KnowledgeReranker.RankedChunk::chunk) : candidates.stream())
                .limit(properties.rag().topK()).toList();
        long millis = (System.nanoTime() - started) / 1_000_000;
        LoggerFactory.getLogger(KnowledgeService.class).info("RAG completed candidates={} selected={} rerank={} durationMs={}", candidates.size(), selected.size(), rerank, millis);
        return new RetrievalReport(candidates, ranked, selected, rerank, millis);
    }

    private String manifestKey(String tenantId, String documentId) {
        return (indexNamespace.isBlank() ? "" : indexNamespace + ":") + tenantId + ":" + documentId;
    }

    private boolean deleteInternal(String documentKey) {
        KnowledgeManifest manifest = manifestRepository.findById(documentKey).orElse(null);
        if (manifest == null) return false;
        List<String> oldIds = manifest.chunkIdList();
        if (!oldIds.isEmpty()) vectorStore.delete(oldIds);
        manifestRepository.delete(manifest);
        manifestRepository.flush();
        return true;
    }

    static List<String> split(String content, int chunkSize, int overlap) {
        if (chunkSize < 100) throw new IllegalArgumentException("chunkSize must be >= 100");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("invalid chunkOverlap");
        String normalized = content.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + chunkSize);
            int end = findSemanticBoundary(normalized, start, hardEnd);
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) break;
            int next = Math.max(start + 1, end - overlap);
            start = next;
        }
        return chunks;
    }

    private static int findSemanticBoundary(String text, int start, int hardEnd) {
        if (hardEnd == text.length()) return hardEnd;
        int minimum = start + (hardEnd - start) * 2 / 3;
        for (int i = hardEnd; i > minimum; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == ';' || c == '；') return i;
        }
        return hardEnd;
    }

    private void validateIdentityPart(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("identifier contains illegal characters");
        }
    }
}
