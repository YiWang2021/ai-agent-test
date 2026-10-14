package com.example.supportagent.security;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CredentialRegistry {
    private record Credential(byte[] digest, RequestIdentity identity) {}
    private final List<Credential> credentials = new ArrayList<>();

    public CredentialRegistry(AuthProperties properties) {
        for (var account : properties.accounts() == null ? List.<AuthProperties.Account>of() : properties.accounts()) {
            if (account.apiKey() == null || account.apiKey().isBlank()) continue;
            if (!validId(account.tenantId()) || !validId(account.userId())
                    || !List.of("USER", "ADMIN").contains(account.role())) {
                throw new IllegalArgumentException("Invalid server-side account identity");
            }
            byte[] digest = digest(account.apiKey());
            if (credentials.stream().anyMatch(existing -> MessageDigest.isEqual(existing.digest(), digest))) {
                throw new IllegalArgumentException("Each configured account must have a distinct credential");
            }
            credentials.add(new Credential(digest,
                    new RequestIdentity(account.tenantId(), account.userId(), account.role())));
        }
        if (credentials.isEmpty()) throw new IllegalArgumentException("At least one server-side account is required");
    }

    public Optional<RequestIdentity> authenticate(String key) {
        if (key == null || key.isBlank() || key.length() > 1024) return Optional.empty();
        byte[] supplied = digest(key);
        return credentials.stream().filter(item -> MessageDigest.isEqual(item.digest(), supplied))
                .map(Credential::identity).findFirst();
    }

    private static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,80}"); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
