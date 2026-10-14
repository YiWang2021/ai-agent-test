package com.example.supportagent.tool;

import java.util.Map;

public record ToolResult(boolean success, String code, String message, Map<String, Object> data) {
    public static ToolResult ok(String message, Map<String, Object> data) {
        return new ToolResult(true, "OK", message, data);
    }

    public static ToolResult rejected(String code, String message) {
        return new ToolResult(false, code, message, Map.of());
    }
}
