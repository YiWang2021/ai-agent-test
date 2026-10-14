package com.example.supportagent.concurrency;

import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:redis_admission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("redis")
@EnabledIfEnvironmentVariable(named="RUN_REDIS_TESTS",matches="true")
class RedisAdmissionIntegrationTest {
    @Autowired StringRedisTemplate redis;
    private final LeaseSupervisor supervisor=new LeaseSupervisor();
    private final List<Lease> leases=new ArrayList<>();
    private String namespace;
    @BeforeEach void setup(){namespace="test-"+UUID.randomUUID();}
    @AfterEach void cleanup(){
        leases.forEach(Lease::close);supervisor.shutdown();
        var keys=redis.keys("support:{"+namespace+"}:admission:*");
        if(keys!=null&&!keys.isEmpty())redis.delete(keys);
    }
    private RedisAdmissionController gate(int global,int tenant,int user,int rate){
        return new RedisAdmissionController(redis,new ConcurrencyProperties(32,global,tenant,user,rate,3,namespace),
                supervisor,new SimpleMeterRegistry());
    }
    private RequestIdentity user(String tenant,String name){return new RequestIdentity(tenant,name,"USER");}
    @Test void independentInstancesShareGlobalBudgetAtomically() throws Exception {
        var one=gate(5,5,1,120);var two=gate(5,5,1,120);
        var workers=Executors.newFixedThreadPool(16);
        try {
            var jobs=new ArrayList<Callable<Lease>>();
            for(int i=0;i<40;i++){int n=i;jobs.add(()->{try{return(n%2==0?one:two).acquire(user("a","u"+n));}catch(CapacityExceededException rejected){return null;}});}
            for(var result:workers.invokeAll(jobs)){var lease=result.get();if(lease!=null)leases.add(lease);}
            assertThat(leases).hasSize(5);
            leases.forEach(Lease::close);leases.clear();
            for(int i=0;i<5;i++)leases.add(two.acquire(user("b","u"+i)));
        }finally{workers.shutdownNow();}
    }
    @Test void userTenantAndRateBudgetsCannotBeBypassedAcrossInstances(){
        var one=gate(10,2,1,2);var two=gate(10,2,1,2);
        var first=one.acquire(user("a","1"));leases.add(first);
        assertThatThrownBy(()->two.acquire(user("a","1"))).isInstanceOf(CapacityExceededException.class);
        leases.add(two.acquire(user("a","2")));
        assertThatThrownBy(()->one.acquire(user("a","3"))).isInstanceOf(CapacityExceededException.class);
        leases.add(two.acquire(user("b","1")));
        first.close();
        two.acquire(user("a","1")).close();
        assertThatThrownBy(()->one.acquire(user("a","1"))).isInstanceOf(CapacityExceededException.class).extracting("scope").isEqualTo("rate");
    }
    @Test void lostPermitFailsClosedAndCannotRemoveANewPermit(){
        var one=gate(1,1,1,120);var two=gate(1,1,1,120);
        var first=one.acquire(user("a","1"));leases.add(first);
        String prefix="support:{"+namespace+"}:admission:";
        redis.delete(List.of(prefix+"global",prefix+"tenant:a",prefix+"user:a:1"));
        var replacement=two.acquire(user("a","1"));leases.add(replacement);
        assertThatThrownBy(first::checkValid).isInstanceOf(LeaseLostException.class);
        first.close();
        replacement.checkValid();
        assertThatThrownBy(()->one.acquire(user("b","1"))).isInstanceOf(CapacityExceededException.class);
    }
}

