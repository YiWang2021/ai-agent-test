package com.example.supportagent.agent;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SafeFallback {
    public AgentAnswer answer(AgentRequest request, AgentExecution execution) {
        String message;
        if (execution.writeStarted()) {
            message = "智能服务暂时不可用，本次报修的结果需要核实。请先查询未关闭工单；重试请使用原请求编号，避免重复报修。";
        } else if (request.confirmWrite()) {
            message = "智能服务暂时不可用，本次未开始提交工单。请稍后使用原请求重试。";
        } else {
            message = "智能服务暂时不可用，已进入安全降级模式。本次无法确认设备状态或生成可靠解答，请稍后重试；不会编造设备信息或自动报修。";
        }
        return new AgentAnswer(message, List.of(), execution.traceId());
    }
}
