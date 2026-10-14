package com.example.supportagent.rag;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeManifestRepository extends JpaRepository<KnowledgeManifest, String> {
}
