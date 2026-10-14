package com.example.supportagent.concurrency;

public class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("请求的并发保护已失效，请核实操作结果后重试"); }
}

