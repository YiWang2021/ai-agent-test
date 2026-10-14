package com.example.supportagent.memory;

import java.util.List;

public interface ConversationMemory {
    List<ChatTurn> recent(String tenantId, String userId, String conversationId, int limit);
    void append(String tenantId, String userId, String conversationId, ChatTurn turn);
    void appendExchange(String tenantId, String userId, String conversationId, String user, String assistant);
    default void appendExchange(String tenantId, String userId, String conversationId, String user, String assistant,
                                ConversationGuard.Lease lease) {
        lease.checkValid();
        appendExchange(tenantId, userId, conversationId, user, assistant);
    }
}
