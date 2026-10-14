package com.example.supportagent.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryConversationMemory implements ConversationMemory {

    private final Map<String, List<ChatTurn>> storage = new ConcurrentHashMap<>();

    @Override
    public List<ChatTurn> recent(String tenantId, String userId, String conversationId, int limit) {
        List<ChatTurn> turns = storage.getOrDefault(key(tenantId, userId, conversationId), List.of());
        synchronized (turns) {
            int from = Math.max(0, turns.size() - limit);
            return List.copyOf(turns.subList(from, turns.size()));
        }
    }

    @Override
    public void append(String tenantId, String userId, String conversationId, ChatTurn turn) {
        List<ChatTurn> turns = storage.computeIfAbsent(key(tenantId, userId, conversationId), ignored -> new ArrayList<>());
        synchronized (turns) {
            turns.add(turn);
            if (turns.size() > 100) turns.remove(0);
        }
    }

    private String key(String tenantId, String userId, String conversationId) {
        return tenantId + ":" + userId + ":" + conversationId;
    }

    @Override
    public void appendExchange(String tenantId, String userId, String conversationId, String user, String assistant) {
        List<ChatTurn> turns = storage.computeIfAbsent(key(tenantId, userId, conversationId), ignored -> new ArrayList<>());
        synchronized (turns) {
            turns.add(ChatTurn.user(user));
            turns.add(ChatTurn.assistant(assistant));
            if (turns.size() > 100) turns.subList(0, turns.size() - 100).clear();
        }
    }
}
