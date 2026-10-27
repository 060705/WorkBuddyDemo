package com.example.demo.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型多供应商的客户端工厂：按模型 id 动态构建 ChatClient，并缓存复用。
 *
 * <p>为什么不用 Spring AI 自动配置的那个单例 ChatClient：自动配置只能绑死一个端点 + 一个模型。
 * 这里按 id 现建 {供应商端点 + 密钥 + 模型名} 三元组，才能真正做到「入参 id 决定走哪个模型」。
 */
@Component
public class AiChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientFactory.class);

    private final Environment env;
    /** MCP 工具提供者：把 MCP Server 暴露的外部工具（如天气查询）注册进 ChatClient。可空（未配 MCP 时） */
    private final SyncMcpToolCallbackProvider mcpToolProvider;
    /** modelId -> ChatClient，构建一次后复用（避免每次请求都新建连接池） */
    private final ConcurrentHashMap<String, ChatClient> cache = new ConcurrentHashMap<>();

    public AiChatClientFactory(Environment env,
                               ObjectProvider<SyncMcpToolCallbackProvider> mcpToolProvider) {
        this.env = env;
        // 可选注入：MCP 未配置/连接失败时不影响应用启动，只是不挂 MCP 工具
        this.mcpToolProvider = mcpToolProvider.getIfAvailable();
    }

    /**
     * 按模型 id 获取 ChatClient。
     *
     * @param modelId 模型唯一 id，如 sensenova-glm52；为空则用默认模型
     * @throws IllegalArgumentException 模型 id 不存在
     * @throws IllegalStateException    该供应商未配置 API Key
     */
    public ChatClient get(String modelId) {
        AiModel model = AiModel.of(modelId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "未知模型 id: [" + modelId + "]，可用 id: " + Arrays.toString(AiModel.allIds())));
        return cache.computeIfAbsent(model.getId(), id -> build(model));
    }

    private ChatClient build(AiModel model) {
        AiProvider provider = model.getProvider();
        // 端点：yaml 的 ai.providers.<code>.base-url 可覆盖枚举默认值
        String baseUrl = env.getProperty("ai.providers." + provider.getCode() + ".base-url",
                provider.getDefaultBaseUrl());
        String apiKey = resolveApiKey(provider);

        if (provider.isApiKeyRequired() && !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("供应商 [" + provider.getDisplayName() + "] 未配置 API Key："
                    + "请设置环境变量 " + provider.getApiKeyEnv()
                    + "，或在 application.yaml 配 ai.providers." + provider.getCode() + ".api-key");
        }
        if (!StringUtils.hasText(apiKey)) {
            apiKey = "ollama";  // 本地 Ollama 不需要真实 Key，但 SDK 要求非空
        }

        log.info("[AI] 构建 ChatClient: id={}, provider={}, model={}, baseUrl={}",
                model.getId(), provider.getCode(), model.getModelName(), baseUrl);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model.getModelName())
                        .temperature(0.7)
                        .build())
                .build();

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 注册 MCP 工具：让模型能调用 MCP Server 暴露的外部工具（天气查询等）。
        // 注意：@Tool 的进程内工具（TimeTool/KnowledgeBaseTool）在各 Controller 里单独 .tools() 传入，
        // 这里只挂 MCP 工具，二者互补不冲突。
        // 关键：MCP 工具是现成的 ToolCallback，必须用 defaultToolCallbacks()；
        //       defaultTools() 是给「带 @Tool 注解的对象」用的，传 ToolCallback 会报
        //       "No @Tool annotated methods found"。
        if (mcpToolProvider != null) {
            try {
                ToolCallback[] mcpTools = mcpToolProvider.getToolCallbacks();
                if (mcpTools.length > 0) {
                    builder.defaultToolCallbacks(mcpTools);
                    log.info("[AI] 已注册 MCP 工具 {} 个: id={}",
                            mcpTools.length, model.getId());
                }
            } catch (Exception e) {
                // MCP Server 未就绪/连接失败时不阻塞对话，仅降级为「无 MCP 工具」
                log.warn("[AI] 注册 MCP 工具失败（MCP Server 可能未就绪），本次对话降级为无 MCP 工具: {}",
                        e.getMessage());
            }
        }

        return builder.build();
    }

    /** 密钥优先取 yaml（ai.providers.<code>.api-key），取不到再回落环境变量 */
    private String resolveApiKey(AiProvider provider) {
        if (!provider.isApiKeyRequired()) {
            return null;
        }
        String fromYaml = env.getProperty("ai.providers." + provider.getCode() + ".api-key");
        if (StringUtils.hasText(fromYaml)) {
            return fromYaml.trim();
        }
        return System.getenv(provider.getApiKeyEnv());
    }

    /** 返回当前 MCP 工具的名称列表（供 /ai/mcp/tools 展示，也便于排查 MCP 是否接通） */
    public java.util.List<String> getMcpToolNames() {
        if (mcpToolProvider == null) {
            return java.util.List.of();
        }
        try {
            ToolCallback[] callbacks = mcpToolProvider.getToolCallbacks();
            return Arrays.stream(callbacks)
                    .map(c -> {
                        c.getToolDefinition();
                        c.getToolDefinition().name();
                        return c.getToolDefinition().name();
                    })
                    .toList();
        } catch (Exception e) {
            return java.util.List.of("(MCP 未接通: " + e.getMessage() + ")");
        }
    }
}
