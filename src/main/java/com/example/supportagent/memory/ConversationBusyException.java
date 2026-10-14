package com.example.supportagent.memory;

public class ConversationBusyException extends RuntimeException {
    public ConversationBusyException() { super("当前对话仍有请求在处理，请等待完成后再发送"); }
}
