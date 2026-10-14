package com.example.supportagent.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** Stores the vector IDs belonging to a source document so replacement and deletion survive restarts. */
@Entity
@Table(name = "knowledge_manifests")
public class KnowledgeManifest {

    @Id
    private String id;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(name = "document_id", nullable = false)
    private String documentId;
    @Lob
    @Column(name = "chunk_ids", nullable = false)
    private String chunkIds;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeManifest() {}

    public static KnowledgeManifest of(String tenantId, String documentId, List<String> chunkIds) {
        return of(tenantId + ":" + documentId, tenantId, documentId, chunkIds);
    }

    public static KnowledgeManifest of(String id, String tenantId, String documentId, List<String> chunkIds) {
        KnowledgeManifest manifest = new KnowledgeManifest();
        manifest.id = id;
        manifest.tenantId = tenantId;
        manifest.documentId = documentId;
        manifest.chunkIds = String.join("\n", chunkIds);
        manifest.updatedAt = Instant.now();
        return manifest;
    }

    public List<String> chunkIdList() {
        return chunkIds == null || chunkIds.isBlank() ? List.of() : chunkIds.lines().toList();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getDocumentId() { return documentId; }
    public String getChunkIds() { return chunkIds; }
    public Instant getUpdatedAt() { return updatedAt; }
}
