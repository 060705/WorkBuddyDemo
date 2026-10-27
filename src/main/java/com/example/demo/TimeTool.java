package com.example.demo;

import com.example.demo.agent.ToolTrace;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 演示 Agent 的「工具调用（Function Calling）」：把一个普通 Java 方法暴露给 LLM，
 * 让模型在需要时自己决定调用它。这是 Agent 与你现有后端 Service 结合的关键入口。
 */
@Component
public class TimeTool {

    private final ToolTrace trace;

    public TimeTool(ToolTrace trace) {
        this.trace = trace;
    }

    @Tool(description = "获取当前服务器的日期和时间，当用户询问现在几点、今天几号、当前时间等时间相关问题时使用")
    public String getCurrentTime() {
        String result = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        trace.record("getCurrentTime", "(无参数)", result);
        return result;
    }
}
