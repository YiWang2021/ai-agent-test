package com.example.supportagent.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Component
@Profile("redis")
public class RedisConversationMemory implements ConversationMemory {
    private static final DefaultRedisScript<Long> FENCED_APPEND = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[2]) ~= ARGV[3] then return 0 end
            redis.call('rpush', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ltrim', KEYS[1], -100, -1)
            redis.call('expire', KEYS[1], 604800)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> APPEND_EXCHANGE = new DefaultRedisScript<>("""
            redis.call('rpush', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ltrim', KEYS[1], -100, -1)
            redis.call('expire', KEYS[1], 604800)
            return redis.call('llen', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisConversationMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<ChatTurn> recent(String tenantId, String userId, String conversationId, int limit) {
        String key = key(tenantId, userId, conversationId);
        List<String> values = redis.opsForList().range(key, -limit, -1);
        if (values == null) return List.of();
        return values.stream().map(this::decode).toList();
    }

    @Override
    public void append(String tenantId, String userId, String conversationId, ChatTurn turn) {
        String key = key(tenantId, userId, conversationId);
        redis.opsForList().rightPush(key, encode(turn));
        redis.opsForList().trim(key, -100, -1);
        redis.expire(key, Duration.ofDays(7));
    }

    private String encode(ChatTurn turn) {
        String content = Base64.getUrlEncoder().encodeToString(turn.content().getBytes(StandardCharsets.UTF_8));
        return turn.role() + "|" + turn.createdAt().toEpochMilli() + "|" + content;
    }

    @Override
    public void appendExchange(String tenantId, String userId, String conversationId, String user, String assistant) {
        redis.execute(APPEND_EXCHANGE, List.of(key(tenantId, userId, conversationId)),
                encode(ChatTurn.user(user)), encode(ChatTurn.assistant(assistant)));
    }

    @Override
    public void appendExchange(String tenantId, String userId, String conversationId, String user, String assistant,
                               ConversationGuard.Lease lease) {
        lease.checkValid();
        if (lease.token().isBlank() || !lease.lockKey().equals("support:lock:" + tenantId + ":" + userId + ":" + conversationId)) {
            throw new com.example.supportagent.concurrency.LeaseLostException();
        }
        Long saved = redis.execute(FENCED_APPEND, List.of(key(tenantId, userId, conversationId), lease.lockKey()),
                encode(ChatTurn.user(user)), encode(ChatTurn.assistant(assistant)), lease.token());
        if (!Long.valueOf(1).equals(saved)) throw new com.example.supportagent.concurrency.LeaseLostException();
    }

    private ChatTurn decode(String value) {
        String[] parts = value.split("\\|", 3);
        String content = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        return new ChatTurn(parts[0], content, Instant.ofEpochMilli(Long.parseLong(parts[1])));
    }

    private String key(String tenantId, String userId, String conversationId) {
        return "support:memory:" + tenantId + ":" + userId + ":" + conversationId;
    }
}
