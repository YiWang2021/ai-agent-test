package com.example.supportagent.memory;

import com.example.supportagent.security.RequestIdentity;
import java.time.Duration;

public interface ConversationGuard {
    interface Lease extends com.example.supportagent.concurrency.Lease {
        default String lockKey() { return ""; }
        default String token() { return ""; }
    }
    Lease acquire(RequestIdentity identity, String conversationId, Duration duration);
    static String key(RequestIdentity identity, String conversationId) {
        return identity.tenantId() + ":" + identity.userId() + ":" + conversationId;
    }
}
