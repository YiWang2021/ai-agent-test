package com.example.supportagent.memory;

import com.example.supportagent.security.RequestIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.supportagent.concurrency.LeaseSupervisor;

@Component
@Profile("redis")
public class RedisConversationGuard implements ConversationGuard {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private final StringRedisTemplate redis;
    private final LeaseSupervisor supervisor;
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) else return 0 end", Long.class);
    public RedisConversationGuard(StringRedisTemplate redis, LeaseSupervisor supervisor) { this.redis = redis; this.supervisor = supervisor; }
    public Lease acquire(RequestIdentity identity, String conversationId, Duration duration) {
        String key = "support:lock:" + ConversationGuard.key(identity, conversationId);
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, duration))) throw new ConversationBusyException();
        var managed = supervisor.supervise(duration,
                () -> Long.valueOf(1).equals(redis.execute(RENEW, List.of(key), token, "" + duration.toMillis())),
                () -> redis.execute(RELEASE, List.of(key), token));
        return new Lease() {
            public void checkValid() { managed.checkValid(); }
            public void onLost(Runnable callback) { managed.onLost(callback); }
            public String lockKey() { return key; }
            public String token() { return token; }
            public void close() { managed.close(); }
        };
    }
}
