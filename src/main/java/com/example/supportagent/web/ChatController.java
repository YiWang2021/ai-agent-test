package com.example.supportagent.web;

import com.example.supportagent.agent.AgentAnswer;
import com.example.supportagent.agent.AgentChatService;
import com.example.supportagent.agent.AgentRequest;
import com.example.supportagent.security.ApiKeyInterceptor;
import com.example.supportagent.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentChatService service;

    public ChatController(AgentChatService service) {
        this.service = service;
    }

    @PostMapping
    public AgentAnswer chat(@Valid @RequestBody AgentRequest request, HttpServletRequest servletRequest) {
        return service.ask(request, identity(servletRequest));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody AgentRequest request, HttpServletRequest servletRequest) {
        return service.stream(request, identity(servletRequest));
    }

    private RequestIdentity identity(HttpServletRequest request) {
        return (RequestIdentity) request.getAttribute(ApiKeyInterceptor.IDENTITY_ATTRIBUTE);
    }
}
