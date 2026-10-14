package com.example.supportagent.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeDocumentRequest(
        @NotBlank @Size(max = 100) String documentId,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 500) String source,
        @NotBlank @Size(max = 100_000) String content
) {}
