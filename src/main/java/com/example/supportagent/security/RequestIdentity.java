package com.example.supportagent.security;

public record RequestIdentity(String tenantId, String userId, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
