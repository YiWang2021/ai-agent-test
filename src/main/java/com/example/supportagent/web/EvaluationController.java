package com.example.supportagent.web;

import com.example.supportagent.evaluation.EvaluationReport;
import com.example.supportagent.evaluation.EvaluationService;
import com.example.supportagent.security.ApiKeyInterceptor;
import com.example.supportagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="support.evaluation.enabled", havingValue="true", matchIfMissing=true)
@RequestMapping("/api/admin/evaluations")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public EvaluationReport run(@RequestParam(defaultValue = "retrieval") String mode,
                                @RequestParam(defaultValue = "50") int limit,
                                HttpServletRequest request) {
        RequestIdentity identity = (RequestIdentity) request.getAttribute(ApiKeyInterceptor.IDENTITY_ATTRIBUTE);
        if (!identity.isAdmin()) throw new ForbiddenException("ADMIN role required");
        if (!"tenant-a".equals(identity.tenantId())) throw new ForbiddenException("The bundled evaluation fixture belongs to tenant-a");
        return service.run(mode, limit);
    }
}
