package com.example.demo.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.annotations.McpClientAnnotationScannerAutoConfiguration;

/**
 * 独立的 MCP Server 进程（stdio 传输）。
 *
 * <p>这是 MCP 的<b>正确形态</b>：Server 是一个独立进程，通过标准输入输出（stdio）与
 * MCP Client 通信。本项目主应用（DemoApplication）只扮演 Client，通过 stdio 拉起本进程，
 * 从而拿到 WeatherMcpTool 这个「外部工具」。
 *
 * <p>为什么必须是独立进程？因为 MCP Server 与 Client 的自动配置会互相扫描对方的
 * {@code @Tool}，若同进程会导致「client 连接 server → server 又反过来 listTools 连 client」
 * 的自引用死锁。拆成两个进程后，边界清晰、协议交互真实（Claude Desktop 也是这么连 MCP 的）。
 *
 * <p>运行方式（独立 jar 时）：
 * <pre>
 *   java -jar mcp-server.jar
 * </pre>
 * 它启动后监听 stdin，等待 MCP Client 发来的 JSON-RPC 消息，通过 stdout 返回结果。
 */
@SpringBootApplication(exclude = {
        // 本进程只做 MCP Server，不需要 LLM 模型
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        // 排除 MCP Client 自动配置（避免 client/server 纠缠）
        StdioTransportAutoConfiguration.class,
        McpClientAutoConfiguration.class,
        McpToolCallbackAutoConfiguration.class,
        McpClientAnnotationScannerAutoConfiguration.class
})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

}
