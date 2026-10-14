package com.example.supportagent.tool;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("该请求编号已用于其他报修内容，请核对请求；新报修须使用新的请求编号");
    }
}
