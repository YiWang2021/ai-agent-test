package com.example.supportagent.concurrency;

public class CapacityExceededException extends RuntimeException {
    private final String scope;
    public CapacityExceededException(String scope) {
        super("请求过于频繁或服务繁忙，请稍后重试");
        this.scope = scope;
    }
    public String scope() { return scope; }
}

