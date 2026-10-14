package com.example.supportagent.agent;

import java.util.List;

public record AgentAnswer(String answer, List<Citation> citations, String traceId) {}
