package com.example.supportagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

/** Credentials are bound to identities on the server, never by client supplied headers. */
@ConfigurationProperties("support.auth")
public record AuthProperties(List<Account> accounts) {
    public record Account(String apiKey, String tenantId, String userId, String role) {
        @Override public String toString() { return "Account[credentials=REDACTED]"; }
    }
    @Override public String toString() { return "AuthProperties[credentials=REDACTED]"; }
}
