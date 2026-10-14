package com.example.supportagent.memory;

import java.time.Instant;

public record ChatTurn(String role, String content, Instant createdAt) {
    public static ChatTurn user(String content) { return new ChatTurn("USER", content, Instant.now()); }
    public static ChatTurn assistant(String content) { return new ChatTurn("ASSISTANT", content, Instant.now()); }
}
