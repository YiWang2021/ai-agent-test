package com.example.supportagent.agent;

import com.example.supportagent.security.RequestIdentity;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real Spring AI HTTP + tool loop, with deterministic local provider responses. No model key required. */
@SpringBootTest(properties = {"spring.ai.openai.api-key=transport-test-key",
        "spring.datasource.url=jdbc:h2:mem:transport_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "support.reliability.backoff-millis=0", "support.reliability.max-backoff-millis=0"})
@ActiveProfiles("ai")
class ModelTransportIntegrationTest {
    record Reply(int status, String body) {}
    static final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    static final AtomicInteger calls = new AtomicInteger();
    static final HttpServer server = startServer();
    @Autowired AgentChatService service;

    static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                calls.incrementAndGet();
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{\"error\":{\"message\":\"unexpected request\"}}");
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void provider(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @BeforeEach void reset() { replies.clear(); requests.clear(); calls.set(0); }
    @AfterAll static void stop() { server.stop(0); }

    @Test void realToolLoopConvertsMissingDeviceToModelReadableResult() {
        replies.add(new Reply(200, """
                {"id":"first","object":"chat.completion","created":1,"model":"test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                   {"id":"tool-1","type":"function","function":{"name":"get_device_status","arguments":"{\\\"deviceId\\\":\\\"dev-00023\\\"}"}}
                 ]},"finish_reason":"tool_calls"}]}
                """));
        replies.add(new Reply(200, answer("设备不存在或不属于当前用户")));
        AgentAnswer result = service.ask(new AgentRequest("transport-tools", "查询 dev-00023 当前状态", null, false),
                new RequestIdentity("tenant-a", "user-001", "USER"));
        assertThat(result.answer()).contains("设备不存在");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(requests.stream().skip(1).findFirst().orElseThrow()).contains("RESOURCE_UNAVAILABLE");
    }
    @Test void actualProvider503IsRetriedByApplicationOnly() {
        replies.add(new Reply(503, "{\"error\":{\"message\":\"temporary outage\"}}"));
        replies.add(new Reply(200, answer("连接已恢复")));
        AgentAnswer result = service.ask(new AgentRequest("transport-retry", "你好", null, false),
                new RequestIdentity("tenant-a", "user-001", "USER"));
        assertThat(result.answer()).isEqualTo("连接已恢复");
        assertThat(calls.get()).isEqualTo(2);
    }
    private static String answer(String text) {
        return "{\"id\":\"final\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}
