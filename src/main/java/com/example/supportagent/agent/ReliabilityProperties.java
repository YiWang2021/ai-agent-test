package com.example.supportagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("support.reliability")
public record ReliabilityProperties(int maxAttempts, long backoffMillis, long maxBackoffMillis,
                                    int failureThreshold, long openSeconds) {
    public ReliabilityProperties {
        if (maxAttempts < 1 || maxAttempts > 4 || backoffMillis < 0 || maxBackoffMillis < backoffMillis
                || failureThreshold < 1 || openSeconds < 1) throw new IllegalArgumentException("Invalid reliability configuration");
    }
}
