package com.example.supportagent.memory;

import com.example.supportagent.security.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:redis_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("redis")
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisMemoryIntegrationTest {
    @Autowired ConversationMemory memory;
    @Autowired ConversationGuard guard;
    @Autowired StringRedisTemplate redis;

    @Test void liveConversationRenewsBeyondInitialTtl() throws Exception {
        String conversation="renew-"+UUID.randomUUID();
        var user=new RequestIdentity("tenant-a","user-001","USER");
        String key="support:lock:"+ConversationGuard.key(user,conversation);
        try(var lease=guard.acquire(user,conversation,Duration.ofMillis(600))) {
            Thread.sleep(1400);
            assertThat(redis.opsForValue().get(key)).isEqualTo(lease.token());
            assertThatThrownBy(()->guard.acquire(user,conversation,Duration.ofSeconds(3))).isInstanceOf(ConversationBusyException.class);
            lease.checkValid();
        } finally {redis.delete(key);}
    }

    @Test void atomicHistoryWriteRejectsStaleOwnerEvenIfLocalCheckPassed() {
        String conversation="fence-"+UUID.randomUUID();
        var user=new RequestIdentity("tenant-a","user-001","USER");
        String key="support:lock:"+ConversationGuard.key(user,conversation);
        String memoryKey="support:memory:"+ConversationGuard.key(user,conversation);
        try(var lease=guard.acquire(user,conversation,Duration.ofSeconds(30))) {
            memory.appendExchange("tenant-a","user-001",conversation,"q1","a1",lease);
            var stale=new ConversationGuard.Lease(){
                public String lockKey(){return key;}
                public String token(){return lease.token();}
                public void close(){}
                public void checkValid(){} // ownership can change after a preliminary check
            };
            redis.opsForValue().set(key,"new-owner",Duration.ofSeconds(30));
            assertThatThrownBy(()->memory.appendExchange("tenant-a","user-001",conversation,"stale","wrong",stale))
                    .isInstanceOf(com.example.supportagent.concurrency.LeaseLostException.class);
            assertThat(memory.recent("tenant-a","user-001",conversation,10)).hasSize(2);
        } finally {redis.delete(List.of(key,memoryKey));}
    }

    @Test void realRedisPairsAreAtomicBoundedIsolatedAndExpire() throws Exception {
        String conversation = "test-" + UUID.randomUUID();
        String key = "support:memory:tenant-a:user-001:" + conversation;
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<Callable<Void>>();
            for (int i=0;i<60;i++) { int n=i; jobs.add(() -> { memory.appendExchange("tenant-a", "user-001", conversation, "q"+n, "a"+n); return null; }); }
            for (var future : executor.invokeAll(jobs)) future.get();
            var history = memory.recent("tenant-a", "user-001", conversation, 100);
            assertThat(history).hasSize(100);
            for (int i=0;i<history.size();i+=2) {
                assertThat(history.get(i).role()).isEqualTo("USER");
                assertThat(history.get(i+1).content()).isEqualTo("a" + history.get(i).content().substring(1));
            }
            assertThat(redis.getExpire(key)).isBetween(604700L, 604800L);
            assertThat(memory.recent("tenant-b", "user-001", conversation, 100)).isEmpty();
            assertThat(memory.recent("tenant-a", "user-002", conversation, 100)).isEmpty();
        } finally { executor.shutdownNow(); redis.delete(key); }
    }
    @Test void oldLeaseCannotDeleteAReplacementLease() {
        String conversation = "lock-test-" + UUID.randomUUID();
        var user = new RequestIdentity("tenant-a", "user-001", "USER");
        String key = "support:lock:" + ConversationGuard.key(user, conversation);
        try {
            var first = guard.acquire(user, conversation, Duration.ofSeconds(30));
            assertThatThrownBy(() -> guard.acquire(user, conversation, Duration.ofSeconds(30))).isInstanceOf(ConversationBusyException.class);
            redis.opsForValue().set(key, "replacement", Duration.ofSeconds(30));
            first.close();
            assertThat(redis.opsForValue().get(key)).isEqualTo("replacement");
        } finally { redis.delete(key); }
    }
}
