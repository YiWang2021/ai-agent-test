package com.example.supportagent.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_ATTRIBUTE = RequestIdentity.class.getName();
    private final CredentialRegistry credentials;

    public ApiKeyInterceptor(CredentialRegistry credentials) {
        this.credentials = credentials;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        RequestIdentity identity = credentials.authenticate(request.getHeader("X-API-Key")).orElse(null);
        if (identity == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid X-API-Key\"}");
            return false;
        }

        if (mismatched(request, "X-Tenant-Id", identity.tenantId())
                || mismatched(request, "X-User-Id", identity.userId())
                || mismatched(request, "X-Role", identity.role())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"IDENTITY_MISMATCH\",\"message\":\"identity is determined by the credential\"}");
            return false;
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }

    private boolean mismatched(HttpServletRequest request, String name, String expected) {
        String value = request.getHeader(name);
        return value != null && !value.equals(expected);
    }
}
