package com.example.supportagent.concurrency;

import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!redis")
public class InMemoryAdmissionController implements AdmissionController {
    private final ConcurrencyProperties config;
    private final MeterRegistry metrics;
    private final Map<String, Integer> tenants = new HashMap<>(), users = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> rates = new HashMap<>();
    private int active;
    public InMemoryAdmissionController(ConcurrencyProperties config, MeterRegistry metrics) {
        this.config=config; this.metrics=metrics;
        metrics.gauge("support.admission.active", this, gate -> gate.activeCount());
    }
    private synchronized int activeCount() { return active; }
    @Override public synchronized Lease acquire(RequestIdentity identity) {
        String tenant=identity.tenantId(), user=tenant+":"+identity.userId();
        if (active >= config.instanceLimit()) throw rejected("instance");
        if (active >= config.globalLimit()) throw rejected("global");
        if (tenants.getOrDefault(tenant, 0) >= config.tenantLimit()) throw rejected("tenant");
        if (users.getOrDefault(user, 0) >= config.userLimit()) throw rejected("user");
        long now=System.nanoTime(), window=60_000_000_000L;
        rates.entrySet().removeIf(entry -> entry.getValue().isEmpty() || now-entry.getValue().getLast() >= window);
        var rate=rates.computeIfAbsent(user, ignored -> new ArrayDeque<>());
        while (!rate.isEmpty() && now-rate.getFirst() >= window) rate.removeFirst();
        if (rate.size() >= config.userRequestsPerMinute()) throw rejected("rate");
        rate.add(now); active++; tenants.merge(tenant,1,Integer::sum); users.merge(user,1,Integer::sum);
        AtomicBoolean closed=new AtomicBoolean();
        metrics.counter("support.admission.accepted").increment();
        return new Lease() {
            @Override public void checkValid() { if (closed.get()) throw new LeaseLostException(); }
            @Override public void close() {
                synchronized (InMemoryAdmissionController.this) {
                    if (!closed.compareAndSet(false,true)) return;
                    active--; decrement(tenants,tenant); decrement(users,user);
                }
            }
        };
    }
    private void decrement(Map<String,Integer> values,String key) { values.computeIfPresent(key,(k,v)->v==1?null:v-1); }
    private CapacityExceededException rejected(String scope) {
        metrics.counter("support.admission.rejected","scope",scope).increment();
        return new CapacityExceededException(scope);
    }
}

