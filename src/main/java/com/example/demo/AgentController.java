package com.example.demo;

import com.example.demo.ai.AiChatClientFactory;
import com.example.demo.ai.AiModel;
import com.example.demo.ai.AiProvider;
import com.example.demo.agent.ToolTrace;
import com.example.demo.memory.ChatMemoryService;
import com.example.demo.rag.KnowledgeBaseTool;
import com.example.demo.rag.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
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
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final ChatMemoryService memory;
    private final ToolTrace trace;

    public AgentController(AiChatClientFactory factory, TimeTool timeTool,
                           KnowledgeBaseTool knowledgeBaseTool, ChatMemoryService memory,
                           ToolTrace trace) {
        this.factory = factory;
        this.timeTool = timeTool;
        this.knowledgeBaseTool = knowledgeBaseTool;
        this.memory = memory;
        this.trace = trace;
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
                             @RequestParam String message,
                             @RequestParam(required = false) String sessionId) {
        AiModel model = resolveModel(modelId);
        ChatClient client = factory.get(model.getId());

        // 3 分钟超时：推理模型（如 deepseek-reasoner）会想很久
        SseEmitter emitter = new SseEmitter(180_000L);

        send(emitter, "meta", model.getId() + " | " + model.getProvider().getDisplayName() + " | "
                + model.getModelName() + " | " + memory.describe(sessionId));

        // 多轮记忆：把历史消息塞回 prompt（模型无状态，靠我们替它复习）
        List<Message> history = memory.getHistory(sessionId);

        // 用 StringBuilder 累积流式内容，结束后存入记忆
        StringBuilder answer = new StringBuilder();

        Flux<String> flux = client.prompt()
                .messages(history)   // 先放历史
                .user(message)        // 再放本轮问题
                .tools(timeTool, knowledgeBaseTool)   // 模型可自动查时间 + 查知识库
                .stream()
                .content();

        flux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            answer.append(chunk);
                            send(emitter, "chunk", chunk);
                        },
                        err -> {
                            log.error("[AI] 流式生成失败, model={}", model.getId(), err);
                            send(emitter, "error", String.valueOf(err.getMessage()));
                            emitter.completeWithError(err);
                        },
                        () -> {
                            // 生成完毕：把本轮问答存入会话记忆
                            memory.append(sessionId, message, answer.toString());
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
                                    @RequestParam String message,
                                    @RequestParam(required = false) String sessionId) {
        AiModel model = resolveModel(modelId);
        ChatClient client = factory.get(model.getId());
        long start = System.currentTimeMillis();
        List<Message> history = memory.getHistory(sessionId);
        String content = client.prompt()
                .messages(history)
                .user(message)
                .tools(timeTool, knowledgeBaseTool)
                .call()
                .content();
        memory.append(sessionId, message, content == null ? "" : content);
        return Map.of(
                "modelId", model.getId(),
                "provider", model.getProvider().getCode(),
                "model", model.getModelName(),
                "message", message,
                "content", content == null ? "" : content,
                "costMs", System.currentTimeMillis() - start
        );
    }

    /** 清空指定会话的记忆（用户主动「新会话」时调用） */
    @GetMapping("/memory/clear")
    public Map<String, Object> clearMemory(@RequestParam(required = false) String sessionId) {
        memory.clear(sessionId);
        return Map.of("cleared", true, "sessions", memory.sessionCount());
    }

    /**
     * Agent 工具调用轨迹：观察模型「思考-行动」的编排过程。
     * GET /ai/trace?n=10  返回最近 n 次工具调用（倒序）
     */
    @GetMapping("/trace")
    public Map<String, Object> trace(@RequestParam(defaultValue = "10") int n) {
        return Map.of("entries", trace.recentAsMaps(n));
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

    /**
     * RAG 知识库问答（流式）：GET /ai/rag?question=xxx
     *
     * <p>与 /ai/stream 的区别：会在回答末尾额外标注「参考了哪几个片段」，
     * 让你直观看到「检索 → 生成」的证据链，而不只是模型空口回答。
     */
    @GetMapping(value = "/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter rag(@RequestParam(required = false) String modelId,
                          @RequestParam String question,
                          @RequestParam(required = false) String sessionId) {
        AiModel model = resolveModel(modelId);
        ChatClient client = factory.get(model.getId());

        // 先本地检索，把命中的片段也发出去（放在回答前的 sources 事件里）
        List<VectorStoreService.Hit> hits = knowledgeBaseTool.getStore().search(question, 5);

        SseEmitter emitter = new SseEmitter(180_000L);
        send(emitter, "meta", model.getId() + " | " + model.getProvider().getDisplayName() + " | "
                + model.getModelName() + " | " + memory.describe(sessionId));

        // 发检索到的参考片段（sources 事件，前端可显示成「参考资料」）
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            VectorStoreService.Hit h = hits.get(i);
            refs.append("[").append(i + 1).append("] ")
                 .append(h.chunk.source).append("（相关度 ").append(String.format("%.2f", h.score)).append("）\n")
                 .append(h.chunk.text).append("\n\n");
        }
        send(emitter, "sources", refs.toString());

        // 组装带上下文的提示词，让模型「只照着资料答，没资料就明说不知道」
        String system = "你是鼎校教育内部知识助手。请严格依据下面【参考资料】回答用户问题；"
                + "资料里没有的信息，直接说「资料里没有提到」，不要编造。回答末尾用一行列出你引用的出处。\n\n"
                + "【参考资料】\n" + (refs.length() > 0 ? refs : "（无）");

        // 多轮记忆：历史消息塞回 prompt
        List<Message> history = memory.getHistory(sessionId);
        StringBuilder answer = new StringBuilder();

        Flux<String> flux = client.prompt()
                .system(system)
                .messages(history)
                .user(question)
                .stream()
                .content();

        flux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        chunk -> {
                            answer.append(chunk);
                            send(emitter, "chunk", chunk);
                        },
                        err -> {
                            log.error("[AI] RAG 流式生成失败, model={}", model.getId(), err);
                            send(emitter, "error", String.valueOf(err.getMessage()));
                            emitter.completeWithError(err);
                        },
                        () -> {
                            memory.append(sessionId, question, answer.toString());
                            send(emitter, "done", "[DONE]");
                            emitter.complete();
                        }
                );
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /** 知识库状态：看当前库里有多少片段（用于确认 seeder 是否加载成功） */
    @GetMapping("/kb")
    public Map<String, Object> kb() {
        return Map.of(
                "chunks", knowledgeBaseTool.getStore().size(),
                "sources", knowledgeBaseTool.getStore().listSources()
        );
    }

    /**
     * 上传 Markdown 文档到知识库：POST /ai/kb/upload
     *
     * <p>支持两种传参：
     * <ul>
     *   <li>multipart 表单：字段名 file（MultipartFile），文件名作为来源</li>
     *   <li>JSON：{"name":"文档标题","content":"markdown 正文"}</li>
     * </ul>
     * 上传后自动 rebuild 索引，立即可用于 /ai/rag 咨询。
     */
    @PostMapping(value = "/kb/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) name = "未命名文档";
        // 去掉扩展名作为来源名
        String source = name.replaceAll("\\.(md|markdown|txt)$", "");
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("读取文件失败: " + e.getMessage());
        }
        int added = knowledgeBaseTool.getStore().addDocument(source, content);
        knowledgeBaseTool.getStore().build();
        return Map.of(
                "source", source,
                "addedChunks", added,
                "totalChunks", knowledgeBaseTool.getStore().size()
        );
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
