package com.example.supportagent.web;

import com.example.supportagent.rag.KnowledgeDocumentRequest;
import com.example.supportagent.rag.KnowledgeService;
import com.example.supportagent.security.ApiKeyInterceptor;
import com.example.supportagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {
    public record SearchRequest(@jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 4000) String query, boolean rerank) {}

    @PostMapping("/search")
    public com.example.supportagent.rag.RetrievalReport search(@Valid @RequestBody SearchRequest request,
                                                             HttpServletRequest servletRequest) {
        return service.inspect(requireAdmin(servletRequest).tenantId(), request.query(), request.rerank());
    }

    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upsert(@Valid @RequestBody KnowledgeDocumentRequest request,
                                      HttpServletRequest servletRequest) {
        RequestIdentity identity = requireAdmin(servletRequest);
        int chunks = service.upsert(identity.tenantId(), request);
        return Map.of("documentId", request.documentId(), "chunks", chunks);
    }

    @DeleteMapping("/{documentId}")
    public Map<String, Object> delete(@PathVariable String documentId, HttpServletRequest servletRequest) {
        RequestIdentity identity = requireAdmin(servletRequest);
        return Map.of("deleted", service.delete(identity.tenantId(), documentId));
    }

    private RequestIdentity requireAdmin(HttpServletRequest request) {
        RequestIdentity identity = (RequestIdentity) request.getAttribute(ApiKeyInterceptor.IDENTITY_ATTRIBUTE);
        if (!identity.isAdmin()) throw new ForbiddenException("ADMIN role required");
        return identity;
    }
}
