package com.example.demo.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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
    /** modelId -> ChatClient，构建一次后复用（避免每次请求都新建连接池） */
    private final ConcurrentHashMap<String, ChatClient> cache = new ConcurrentHashMap<>();

    public AiChatClientFactory(Environment env) {
        this.env = env;
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

        return ChatClient.builder(chatModel).build();
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
}
