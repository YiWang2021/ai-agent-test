package com.example.supportagent.agent;

import com.example.supportagent.security.RequestIdentity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReliableAgentRunnerTest {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AiResponder responder = mock(AiResponder.class);
    private final ReliabilityProperties config = new ReliabilityProperties(2, 0, 0, 1, 1);
    private final ModelCircuitBreaker circuit = new ModelCircuitBreaker(config);
    private final ReliableAgentRunner runner = new ReliableAgentRunner(responder, executor, config, circuit, new SafeFallback(), new SimpleMeterRegistry());
    private final RequestIdentity identity = new RequestIdentity("tenant-a", "user-001", "USER");
    private AgentRequest request(boolean write) { return new AgentRequest("reliable", "查询设备", write ? "write-1" : null, write); }
    private AgentExecution execution() { return new AgentExecution("test", Duration.ofSeconds(2), 8); }
    @AfterEach void stop() { executor.shutdownNow(); }

    @Test void transientReadFailureRetriesAndRecovers() {
        when(responder.respond(any(), any(), anyList(), any(AgentExecution.class)))
                .thenThrow(new RuntimeException(new IOException("connection reset")))
                .thenReturn(new AgentAnswer("recovered", List.of(), "test"));
        assertThat(runner.run(request(false), identity, List.of(), execution()).answer()).isEqualTo("recovered");
        verify(responder, times(2)).respond(any(), any(), anyList(), any(AgentExecution.class));
        assertThat(circuit.state()).isEqualTo("CLOSED");
    }
    @Test void authenticationFailureIsNotRetriedOrCountedAsTransientOutage() {
        when(responder.respond(any(), any(), anyList(), any(AgentExecution.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        assertThat(runner.run(request(false), identity, List.of(), execution()).answer()).contains("降级");
        verify(responder).respond(any(), any(), anyList(), any(AgentExecution.class));
        assertThat(circuit.state()).isEqualTo("CLOSED");
    }
    @Test void confirmedWriteIsNeverAutomaticallyReplayedAndOutcomeIsHonest() {
        when(responder.respond(any(), any(), anyList(), any(AgentExecution.class))).thenAnswer(invocation -> {
            invocation.<AgentExecution>getArgument(3).beginWrite();
            throw new RuntimeException(new IOException("response lost after commit"));
        });
        assertThat(runner.run(request(true), identity, List.of(), execution()).answer()).contains("结果需要核实");
        verify(responder).respond(any(), any(), anyList(), any(AgentExecution.class));
    }
    @Test void exhaustedRetriesOpenCircuitAndNextRequestDoesNotCallModel() {
        when(responder.respond(any(), any(), anyList(), any(AgentExecution.class)))
                .thenThrow(new RuntimeException(new IOException("offline")));
        runner.run(request(false), identity, List.of(), execution());
        runner.run(request(false), identity, List.of(), execution());
        verify(responder, times(2)).respond(any(), any(), anyList(), any(AgentExecution.class));
        assertThat(circuit.state()).isEqualTo("OPEN");
    }
    @Test void deadlineCancelsRequestAndPreventsLateWriteEvenIfProviderIgnoresInterrupt() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean writeBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        when(responder.respond(any(), any(), anyList(), any(AgentExecution.class))).thenAnswer(invocation -> {
            var context = invocation.<AgentExecution>getArgument(3);
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { /* simulate provider returning after cancellation */ }
            try { context.beginWrite(); } catch (CancellationException expected) { writeBlocked.set(true); }
            finally { completed.countDown(); }
            return new AgentAnswer("late", List.of(), "test");
        });
        var execution = new AgentExecution("timeout", Duration.ofMillis(100), 8);
        assertThat(runner.run(request(true), identity, List.of(), execution).answer()).contains("未开始提交");
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(writeBlocked).isTrue();
    }
    @Test void streamRetriesBeforeFirstTokenButNeverAfterPartialAnswer() {
        when(responder.stream(any(), any(), anyList(), any(AgentExecution.class)))
                .thenReturn(Flux.error(new IOException("offline")))
                .thenReturn(Flux.just("ok"));
        assertThat(runner.stream(request(false), identity, List.of(), execution()).collectList().block(Duration.ofSeconds(2)))
                .containsExactly("ok");
        reset(responder);
        when(responder.stream(any(), any(), anyList(), any(AgentExecution.class)))
                .thenReturn(Flux.concat(Flux.just("partial"), Flux.error(new IOException("disconnected"))));
        var signals = runner.stream(request(false), identity, List.of(), execution()).materialize().collectList().block(Duration.ofSeconds(2));
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).get()).isEqualTo("partial");
        assertThat(signals.get(1).isOnError()).isTrue();
        verify(responder).stream(any(), any(), anyList(), any(AgentExecution.class));
    }
}
