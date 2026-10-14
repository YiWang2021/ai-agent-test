package com.example.supportagent.tool;

public class ToolAccessDeniedException extends RuntimeException {
    public ToolAccessDeniedException(String message) {
        super(message);
    }
}
