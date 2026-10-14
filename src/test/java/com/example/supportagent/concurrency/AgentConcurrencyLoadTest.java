package com.example.supportagent.concurrency;

import com.example.supportagent.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local HTTP + application pipeline; model latency is simulated, NOT a paid-provider capacity benchmark. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "support.concurrency.instance-limit=8","support.concurrency.global-limit=8",
        "support.concurrency.tenant-limit=4","support.concurrency.user-limit=1",
        "spring.datasource.url=jdbc:h2:mem:load_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class AgentConcurrencyLoadTest {
    @LocalServerPort int port;
    @MockitoBean AiResponder responder;
    @DynamicPropertySource static void accounts(DynamicPropertyRegistry registry) {
        for(int i=0;i<32;i++) {
            int n=i;String prefix="support.auth.accounts["+i+"].";
            registry.add(prefix+"api-key",()->"load-test-key-"+n);
            registry.add(prefix+"tenant-id",()->"load-tenant-"+(n/8));
            registry.add(prefix+"user-id",()->"user-"+n);
            registry.add(prefix+"role",()->"USER");
        }
    }
    record Result(int status,long elapsedMillis,String body){}
    @Test void burstRejectsExcessWorkWithoutOversubscriptionOrLeakingPermits() throws Exception {
        var active=new AtomicInteger();var peak=new AtomicInteger();
        when(responder.respond(any(),any(),anyList(),any(AgentExecution.class))).thenAnswer(call->{
            int current=active.incrementAndGet();peak.accumulateAndGet(current,Math::max);
            try {Thread.sleep(1000);return new AgentAnswer("load-test-ok",List.of(),call.<AgentExecution>getArgument(3).traceId());}
            finally {active.decrementAndGet();}
        });
        var clients=Executors.newFixedThreadPool(32);var start=new CountDownLatch(1);
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var futures=new ArrayList<Future<Result>>();
        long began=System.nanoTime();
        try {
            for(int i=0;i<32;i++){int n=i;futures.add(clients.submit(()->{
                start.await();long t=System.nanoTime();
                var response=http.send(request(n,"burst-"+n),HttpResponse.BodyHandlers.ofString());
                return new Result(response.statusCode(),(System.nanoTime()-t)/1_000_000,response.body());
            }));}
            start.countDown();var results=new ArrayList<Result>();
            for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
            long ok=results.stream().filter(r->r.status==200&&r.body.contains("load-test-ok")).count();
            long rejected=results.stream().filter(r->r.status==429).count();
            assertThat(ok).isPositive();assertThat(rejected).isPositive();
            assertThat(ok+rejected).isEqualTo(32);assertThat(peak.get()).isBetween(1,8);assertThat(active.get()).isZero();
            var recovered=http.send(request(0,"after-burst"),HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(200);assertThat(recovered.body()).contains("load-test-ok");
            var latencies=results.stream().map(Result::elapsedMillis).sorted().toList();
            String report="""
                    {"type":"local-http-simulated-model","requests":32,"clientConcurrency":32,
                    "instanceLimit":8,"simulatedModelMillis":1000,"successful":%d,"rejected429":%d,
                    "unexpectedResponses":0,"peakModelExecutions":%d,"p95AllRequestsMillis":%d,
                    "elapsedIncludingRecoveryMillis":%d,"recoverySucceeded":true}
                    """.formatted(ok,rejected,peak.get(),latencies.get(30),(System.nanoTime()-began)/1_000_000);
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/concurrency-load-report.json"),report);
        }finally{start.countDown();clients.shutdownNow();}
    }
    private HttpRequest request(int user,String conversation) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/chat"))
                .timeout(Duration.ofSeconds(15)).header("X-API-Key","load-test-key-"+user)
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\""+conversation+"\",\"message\":\"test\",\"confirmWrite\":false}")).build();
    }
}

