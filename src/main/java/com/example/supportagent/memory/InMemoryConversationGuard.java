package com.example.supportagent.memory;

import com.example.supportagent.security.RequestIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!redis")
public class InMemoryConversationGuard implements ConversationGuard {
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();
    public Lease acquire(RequestIdentity identity, String conversationId, Duration duration) {
        String key = ConversationGuard.key(identity, conversationId);
        if (active.putIfAbsent(key, true) != null) throw new ConversationBusyException();
        AtomicBoolean closed = new AtomicBoolean();
        return new Lease() {
            public void checkValid() { if (closed.get()) throw new com.example.supportagent.concurrency.LeaseLostException(); }
            public void close() { if (closed.compareAndSet(false, true)) active.remove(key); }
        };
    }
}
