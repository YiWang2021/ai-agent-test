package com.example.supportagent.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String conversationId,
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 100) String idempotencyKey,
        boolean confirmWrite
) {}
