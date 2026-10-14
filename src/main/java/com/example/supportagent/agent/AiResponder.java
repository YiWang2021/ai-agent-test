package com.example.supportagent.agent;

import com.example.supportagent.memory.ChatTurn;
import com.example.supportagent.security.RequestIdentity;
import reactor.core.publisher.Flux;

import java.util.List;

public interface AiResponder {
    AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, String traceId);

    default AgentAnswer respond(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        return respond(request, identity, history, execution.traceId());
    }

    default Flux<String> stream(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, AgentExecution execution) {
        return stream(request, identity, history, execution.traceId());
    }

    default Flux<String> stream(AgentRequest request, RequestIdentity identity, List<ChatTurn> history, String traceId) {
        AgentAnswer answer = respond(request, identity, history, traceId);
        return Flux.fromArray(answer.answer().split("(?<=。)|(?<=！)|(?<=？)|(?<=，)"));
    }
}
