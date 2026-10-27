package com.example.demo;

import com.example.demo.ai.AiChatClientFactory;
import com.example.demo.ai.AiModel;
import com.example.demo.ai.AiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 多模型多供应商的 AI Agent 入口。
 *
 * <p>三个接口（全部 GET）：
 * <ul>
 *   <li>GET /ai/models  —— 列出所有可用模型 id（供应商 / 模型名 / 说明）</li>
 *   <li>GET /ai/stream?modelId=xxx&message=xxx —— <b>流式</b>输出（SSE），边生成边返回</li>
 *   <li>GET /ai/chat?modelId=xxx&message=xxx   —— 非流式（一次性返回，便于对比/调试）</li>
 * </ul>
 *
 * <p>modelId 省略时用默认模型 {@link AiModel#defaultModel()}。
 * 模型可自动调用 {@link TimeTool}（工具调用 / Function Calling）。
 */
@RestController
@RequestMapping("/ai")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AiChatClientFactory factory;
    private final TimeTool timeTool;

    public AgentController(AiChatClientFactory factory, TimeTool timeTool) {
        this.factory = factory;
        this.timeTool = timeTool;
    }

    /** 列出所有可用模型：先看这个拿 id，再调 /ai/stream */
    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> list = Arrays.stream(AiModel.values())
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "provider", m.getProvider().getCode(),
                        "providerName", m.getProvider().getDisplayName(),
                        "model", m.getModelName(),
                        "remark", m.getRemark(),
                        "default", m == AiModel.defaultModel()
                ))
                .toList();
        return Map.of(
                "defaultModelId", AiModel.defaultModel().getId(),
                "models", list
        );
    }

    /**
     * 流式对话：GET /ai/stream?modelId=sensenova-glm52&message=现在几点
     *
     * <p>返回 SSE（text/event-stream），浏览器直接访问地址就能看到逐字输出；
     * 命令行用 {@code curl -N}。事件：meta（用了哪个模型）→ 若干 chunk → done。
     */
    // produces 显式带 charset=UTF-8，让浏览器/curl 用 UTF-8 解析中文（配合 WebConfig 里的转换器）
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter stream(@RequestParam(required = false) String modelId,
                             @RequestParam String message) {
        AiModel model = resolveModel(modelId);
        ChatClient client = factory.get(model.getId());

        // 3 分钟超时：推理模型（如 deepseek-reasoner）会想很久
        SseEmitter emitter = new SseEmitter(180_000L);

        send(emitter, "meta", model.getId() + " | " + model.getProvider().getDisplayName() + " | " + model.getModelName());

        Flux<String> flux = client.prompt()
                .user(message)
                .tools(timeTool)      // 让模型按需自动调用工具
                .stream()
                .content();

        flux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> send(emitter, "chunk", chunk),
                        err -> {
                            log.error("[AI] 流式生成失败, model={}", model.getId(), err);
                            send(emitter, "error", String.valueOf(err.getMessage()));
                            emitter.completeWithError(err);
                        },
                        () -> {
                            send(emitter, "done", "[DONE]");
                            emitter.complete();
                        }
                );

        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.warn("[AI] SSE 连接异常: {}", e.getMessage()));
        return emitter;
    }

    /**
     * 非流式对话（保留对照）：GET /ai/chat?modelId=dashscope-qwen-plus&message=你好
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam(required = false) String modelId,
                                    @RequestParam String message) {
        AiModel model = resolveModel(modelId);
        ChatClient client = factory.get(model.getId());
        long start = System.currentTimeMillis();
        String content = client.prompt()
                .user(message)
                .tools(timeTool)
                .call()
                .content();
        return Map.of(
                "modelId", model.getId(),
                "provider", model.getProvider().getCode(),
                "model", model.getModelName(),
                "message", message,
                "content", content == null ? "" : content,
                "costMs", System.currentTimeMillis() - start
        );
    }

    /** 解析模型 id：为空用默认，非法 id 直接 400 并回传可用列表 */
    private AiModel resolveModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return AiModel.defaultModel();
        }
        return AiModel.of(modelId).orElseThrow(() -> new IllegalArgumentException(
                "未知模型 id: [" + modelId + "]，可用 id: " + Arrays.toString(AiModel.allIds())));
    }

    /** 安全发送：客户端断开时静默失败，避免刷异常栈 */
    private void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.warn("[AI] SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 参数/配置类错误统一返回 400 + 可读原因（模型 id 不存在、供应商没配 Key 等），
     * 避免前端只收到一个干巴巴的 500。
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleBadRequest(RuntimeException e) {
        log.warn("[AI] 请求参数或配置错误: {}", e.getMessage());
        return Map.of("error", String.valueOf(e.getMessage()));
    }

    /** 供应商一览（方便确认端点/环境变量名） */
    @GetMapping("/providers")
    public List<Map<String, String>> providers() {
        return Arrays.stream(AiProvider.values())
                .map(p -> Map.of(
                        "code", p.getCode(),
                        "name", p.getDisplayName(),
                        "baseUrl", p.getDefaultBaseUrl(),
                        "apiKeyEnv", p.getApiKeyEnv() == null ? "(无需 Key)" : p.getApiKeyEnv()))
                .toList();
    }
}
