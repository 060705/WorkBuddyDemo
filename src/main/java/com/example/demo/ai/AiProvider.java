package com.example.demo.ai;

/**
 * 模型供应商枚举：定义「谁提供模型」以及它 OpenAI 兼容端点的地址。
 *
 * <p>baseUrl 一律 <b>不带 /v1</b>：Spring AI 内部会自动拼接 {@code /v1/chat/completions}，
 * 自己再带 /v1 会拼成 {@code /v1/v1/...} 直接 404（已踩坑）。
 *
 * <p>apiKeyEnv 是读取密钥的环境变量名；同时也支持在 application.yaml 里用
 * {@code ai.providers.<code>.api-key} 覆盖（yaml 优先于环境变量，方便本地调试）。
 */
public enum AiProvider {

    /** 商汤 SenseNova Token Plan：免费额度 500 次/5h，三个模型实测可用 */
    SENSENOVA("sensenova", "商汤 SenseNova（Token Plan，免费 500 次/5h）",
            "https://token.sensenova.cn", "SENSENOVA_API_KEY"),

    /** 阿里通义千问 DashScope 的 OpenAI 兼容模式 */
    DASHSCOPE("dashscope", "阿里通义千问 DashScope",
            "https://dashscope.aliyuncs.com/compatible-mode", "DASHSCOPE_API_KEY"),

    /** DeepSeek 官方 */
    DEEPSEEK("deepseek", "DeepSeek 官方",
            "https://api.deepseek.com", "DEEPSEEK_API_KEY"),

    /** OpenAI 官方 */
    OPENAI("openai", "OpenAI 官方",
            "https://api.openai.com", "OPENAI_API_KEY"),

    /** 本地 Ollama（OpenAI 兼容端点，无需 Key） */
    OLLAMA("ollama", "本地 Ollama（localhost:11434）",
            "http://localhost:11434", null),

    /** CHATANYWHERE（OpenAI 兼容端点，无需 Key） */
    CHATANYWHERE("ollama", "CHATANYWHERE",
            "https://api.chatanywhere.tech", null);

    /** 供应商唯一编码，用于 ai.providers.<code> 配置定位 */
    private final String code;
    /** 中文展示名 */
    private final String displayName;
    /** 默认 OpenAI 兼容端点（不含 /v1） */
    private final String defaultBaseUrl;
    /** 读取 API Key 的环境变量名；null 表示无需 Key（如本地 Ollama） */
    private final String apiKeyEnv;

    AiProvider(String code, String displayName, String defaultBaseUrl, String apiKeyEnv) {
        this.code = code;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.apiKeyEnv = apiKeyEnv;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String getApiKeyEnv() {
        return apiKeyEnv;
    }

    /** 是否需要 API Key（本地模型不需要） */
    public boolean isApiKeyRequired() {
        return apiKeyEnv != null;
    }

    /** 按 code 查找供应商（忽略大小写、去空格） */
    public static AiProvider ofCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toLowerCase();
        for (AiProvider p : values()) {
            if (p.code.equals(normalized)) {
                return p;
            }
        }
        return null;
    }
}
