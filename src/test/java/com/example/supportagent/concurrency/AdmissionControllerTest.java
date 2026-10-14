package com.example.supportagent.concurrency;

import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class AdmissionControllerTest {
    private RequestIdentity user(String tenant,String user) { return new RequestIdentity(tenant,user,"USER"); }
    @Test void userTenantAndGlobalBudgetsAreIndependentAndReleaseIsIdempotent() {
        var gate=new InMemoryAdmissionController(new ConcurrencyProperties(8,3,2,1,120,30,"test"),new SimpleMeterRegistry());
        var first=gate.acquire(user("a","1"));
        assertThatThrownBy(()->gate.acquire(user("a","1"))).isInstanceOf(CapacityExceededException.class);
        var second=gate.acquire(user("a","2"));
        assertThatThrownBy(()->gate.acquire(user("a","3"))).isInstanceOf(CapacityExceededException.class);
        var third=gate.acquire(user("b","1"));
        assertThatThrownBy(()->gate.acquire(user("c","1"))).isInstanceOf(CapacityExceededException.class);
        first.close(); first.close();
        try(var replacement=gate.acquire(user("c","1"))) {
            assertThatThrownBy(()->gate.acquire(user("d","1"))).isInstanceOf(CapacityExceededException.class);
        }
        second.close(); third.close();
    }
    @Test void burstCannotOversubscribeAndAllPermitsAreReusable() throws Exception {
        var gate=new InMemoryAdmissionController(new ConcurrencyProperties(8,5,5,1,120,30,"test"),new SimpleMeterRegistry());
        var workers=Executors.newFixedThreadPool(16);
        List<Lease> accepted=new ArrayList<>();
        try {
            var jobs=new ArrayList<Callable<Lease>>();
            for(int i=0;i<64;i++) { int n=i; jobs.add(()->{try{return gate.acquire(user("a","u"+n));}catch(CapacityExceededException rejected){return null;}}); }
            for(var result:workers.invokeAll(jobs)) { var lease=result.get(); if(lease!=null)accepted.add(lease); }
            assertThat(accepted).hasSize(5);
            accepted.forEach(Lease::close);
            accepted.clear();
            for(int i=0;i<5;i++)accepted.add(gate.acquire(user("a","again"+i)));
        } finally {accepted.forEach(Lease::close);workers.shutdownNow();}
    }
    @Test void changingConversationDoesNotBypassUserRateBudget() {
        var gate=new InMemoryAdmissionController(new ConcurrencyProperties(8,8,8,2,2,30,"test"),new SimpleMeterRegistry());
        var user=user("a","1");
        gate.acquire(user).close(); gate.acquire(user).close();
        assertThatThrownBy(()->gate.acquire(user)).isInstanceOf(CapacityExceededException.class)
                .extracting("scope").isEqualTo("rate");
        gate.acquire(user("a","2")).close();
    }
}

