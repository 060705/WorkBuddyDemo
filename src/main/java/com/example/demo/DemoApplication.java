package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;

// 排除 Spring AI 的 OpenAI 自动配置：它只能绑死「一个端点 + 一个 Key」的单例 ChatClient。
// 本项目改成按模型 id 动态构建（见 ai/AiChatClientFactory），支持多供应商多模型，故不需要自动配置。
// 同时排除 MCP Server 自动配置：本应用只扮演「MCP Client」，MCP Server 是独立进程
// （见 mcpserver/McpServerApplication），二者同进程会导致 client/server 自动配置互相触发连接而死锁。
@SpringBootApplication(exclude = {
        // OpenAI 模型自动配置（多模型动态构建，不需要单例）
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class,
        // MCP Server 自动配置（本应用只做 Client，Server 独立进程）
        McpServerAutoConfiguration.class,
        McpServerObjectMapperAutoConfiguration.class,
        ToolCallbackConverterAutoConfiguration.class,
        McpServerStatelessAutoConfiguration.class,
        StatelessToolCallbackConverterAutoConfiguration.class,
        McpServerSpecificationFactoryAutoConfiguration.class,
        StatelessServerSpecificationFactoryAutoConfiguration.class,
        McpServerAnnotationScannerAutoConfiguration.class
})
// 组件扫描排除 mcpserver 包：独立的 MCP Server 进程（McpServerApplication + WeatherMcpTool）
// 不该被主应用加载，否则会被当成本应用的工具/Bean。
@ComponentScan(
        basePackages = "com.example.demo",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.example\\.demo\\.mcpserver\\..*"
        )
)
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
