package com.example.supportagent.concurrency;

import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
@Profile("redis")
public class RedisAdmissionController implements AdmissionController {
    // All four keys share a Redis Cluster hash tag. Time comes from Redis, never instance wall clocks.
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        local t=redis.call('TIME')
        local now=t[1]*1000+math.floor(t[2]/1000)
        for i=1,3 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now) end
        redis.call('ZREMRANGEBYSCORE',KEYS[4],'-inf',now-60000)
        for i=1,3 do
          if redis.call('ZCARD',KEYS[i]) >= tonumber(ARGV[i+2]) then return i end
        end
        if redis.call('ZCARD',KEYS[4]) >= tonumber(ARGV[6]) then return 4 end
        for i=1,3 do
          redis.call('ZADD',KEYS[i],now+tonumber(ARGV[2]),ARGV[1])
          redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2)
        end
        redis.call('ZADD',KEYS[4],now,ARGV[1])
        redis.call('PEXPIRE',KEYS[4],60000)
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
        local t=redis.call('TIME')
        local now=t[1]*1000+math.floor(t[2]/1000)
        for i=1,3 do
          local score=redis.call('ZSCORE',KEYS[i],ARGV[1])
          if not score or tonumber(score)<=now then return 0 end
        end
        for i=1,3 do
          redis.call('ZADD',KEYS[i],'XX',now+tonumber(ARGV[2]),ARGV[1])
          redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2)
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        for i=1,3 do redis.call('ZREM',KEYS[i],ARGV[1]) end
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ConcurrencyProperties config;
    private final LeaseSupervisor supervisor;
    private final MeterRegistry metrics;
    private final Semaphore local;
    public RedisAdmissionController(StringRedisTemplate redis,ConcurrencyProperties config,
                                    LeaseSupervisor supervisor,MeterRegistry metrics) {
        this.redis=redis; this.config=config; this.supervisor=supervisor; this.metrics=metrics;
        this.local=new Semaphore(config.instanceLimit());
        metrics.gauge("support.admission.active",local,s -> config.instanceLimit()-s.availablePermits());
    }
    @Override public Lease acquire(RequestIdentity identity) {
        if (!local.tryAcquire()) throw rejected("instance");
        String prefix="support:{"+config.namespace()+"}:admission:";
        List<String> keys=List.of(prefix+"global",prefix+"tenant:"+identity.tenantId(),
                prefix+"user:"+identity.tenantId()+":"+identity.userId(),
                prefix+"rate:"+identity.tenantId()+":"+identity.userId());
        String token=UUID.randomUUID().toString();
        Duration ttl=Duration.ofSeconds(config.leaseSeconds());
        try {
            Long result=redis.execute(ACQUIRE,keys,token,""+ttl.toMillis(),""+config.globalLimit(),
                    ""+config.tenantLimit(),""+config.userLimit(),""+config.userRequestsPerMinute());
            if (result == null) throw new LeaseLostException();
            if (result != 0) throw rejected(List.of("global","tenant","user","rate").get(result.intValue()-1));
            Lease lease=supervisor.supervise(ttl,
                    () -> Long.valueOf(1).equals(redis.execute(RENEW,keys.subList(0,3),token,""+ttl.toMillis())),
                    () -> { try { redis.execute(RELEASE,keys.subList(0,3),token); } finally { local.release(); } });
            metrics.counter("support.admission.accepted").increment();
            return lease;
        } catch (RuntimeException error) {
            local.release();
            // Acquisition may have succeeded before a connection failure. TTL bounds that leak.
            throw error;
        }
    }
    private CapacityExceededException rejected(String scope) {
        metrics.counter("support.admission.rejected","scope",scope).increment();
        return new CapacityExceededException(scope);
    }
}

