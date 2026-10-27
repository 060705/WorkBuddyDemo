package com.example.demo.ai;

import java.util.Arrays;
import java.util.Optional;

/**
 * 模型枚举：每个「供应商 + 模型」组合是一个枚举常量，并带一个全局唯一 id。
 *
 * <p>调用方只需传 id（如 {@code sensenova-glm52}），由工厂根据 id 找到对应供应商端点、
 * 密钥和模型名，动态构建 ChatClient。新增模型 = 加一行枚举，不用改任何业务代码。
 */
public enum AiModel {

    // ==================== 商汤 SenseNova（实测三个模型均可用、均支持工具调用）====================
    SENSENOVA_GLM52("sensenova-glm52", AiProvider.SENSENOVA, "glm-5.2",
            "GLM-5.2 旗舰，1M 上下文，支持工具调用（默认）"),
    SENSENOVA_DS_V4_FLASH("sensenova-ds-v4-flash", AiProvider.SENSENOVA, "deepseek-v4-flash",
            "DeepSeek V4 Flash，思考/工具调用，500 次/5h"),
    SENSENOVA_68_FLASH_LITE("sensenova-68-flash-lite", AiProvider.SENSENOVA, "sensenova-6.8-flash-lite",
            "SenseNova 6.8 Flash Lite，多模态智能体，1500 次/5h"),

    // ==================== 阿里通义千问 DashScope ====================
    DASHSCOPE_QWEN_MAX("dashscope-qwen-max", AiProvider.DASHSCOPE, "qwen-max",
            "通义千问 Max，能力最强（免费额度有限）"),
    DASHSCOPE_QWEN_PLUS("dashscope-qwen-plus", AiProvider.DASHSCOPE, "qwen-plus",
            "通义千问 Plus，性价比均衡"),
    DASHSCOPE_QWEN_TURBO("dashscope-qwen-turbo", AiProvider.DASHSCOPE, "qwen-turbo",
            "通义千问 Turbo，速度最快、成本最低"),

    // ==================== DeepSeek 官方 ====================
    DEEPSEEK_CHAT("deepseek-chat", AiProvider.DEEPSEEK, "deepseek-chat",
            "DeepSeek V3 通用对话"),
    DEEPSEEK_REASONER("deepseek-reasoner", AiProvider.DEEPSEEK, "deepseek-reasoner",
            "DeepSeek R1 推理模型（慢但强）"),

    // ==================== OpenAI 官方 ====================
    OPENAI_GPT4O_MINI("openai-gpt4o-mini", AiProvider.OPENAI, "gpt-4o-mini",
            "GPT-4o mini，便宜好用"),

    // ==================== 本地 Ollama（无需 Key）====================
    OLLAMA_QWEN25_7B("ollama-qwen25-7b", AiProvider.OLLAMA, "qwen2.5:7b",
            "本地 Qwen2.5 7B，需先 ollama pull qwen2.5:7b");

    /** 全局唯一 id，入参就传这个 */
    private final String id;
    /** 所属供应商（决定端点 + 密钥） */
    private final AiProvider provider;
    /** 传给大模型 API 的真实模型名 */
    private final String modelName;
    /** 中文说明 */
    private final String remark;

    AiModel(String id, AiProvider provider, String modelName, String remark) {
        this.id = id;
        this.provider = provider;
        this.modelName = modelName;
        this.remark = remark;
    }

    public String getId() {
        return id;
    }

    public AiProvider getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getRemark() {
        return remark;
    }

    /** 默认模型：未传 modelId 时使用 */
    public static AiModel defaultModel() {
        return SENSENOVA_GLM52;
    }

    /** 按 id 查找（忽略大小写、去首尾空格） */
    public static Optional<AiModel> of(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(m -> m.id.equalsIgnoreCase(normalized))
                .findFirst();
    }

    /** 所有可用模型 id，用于报错提示 / /ai/models 接口 */
    public static String[] allIds() {
        return Arrays.stream(values()).map(AiModel::getId).toArray(String[]::new);
    }
}
