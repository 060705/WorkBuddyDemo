# AI Agent 从 0 到 1 学习笔记

> 基于 `WorkBuddyDemo`（Spring Boot 3.3 + Spring AI 1.1.2）的完整实践记录。
> 覆盖四个阶段：**模型接入 → RAG 检索增强 → 记忆与工具编排 → MCP 协议**。
> 本文既是复盘笔记，也是「简历怎么写」的素材来源。

---

## 目录

1. [阶段 0：多模型多供应商接入](#阶段0多模型多供应商接入)
2. [阶段 1：SSE 流式输出](#阶段1sse流式输出)
3. [阶段 2：RAG 检索增强生成](#阶段2rag检索增强生成)
4. [阶段 3：多轮记忆 + Agent 编排 + 工具调用](#阶段3多轮记忆--agent编排--工具调用)
5. [阶段 4：MCP 协议](#阶段4mcp协议)
6. [踩坑清单（血泪史）](#踩坑清单)
7. [简历项目描述](#简历项目描述)

---

## 阶段0：多模型多供应商接入

**目标**：一个接口，通过传不同 `modelId`，动态切换不同供应商、不同模型。

### 核心设计：枚举 = 配置

用两个枚举把「供应商」和「模型」彻底解耦：

```java
// AiProvider：供应商枚举（决定端点 + 密钥环境变量名）
SENSENOVA("商汤 SenseNova", "https://token.sensenova.cn/v1", "SENSENOVA_API_KEY"),
DASHSCOPE("阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY"),
DEEPSEEK("DeepSeek 官方", "https://api.deepseek.com", "DEEPSEEK_API_KEY"),
CHATANYWHERE("ChatAnywhere", "https://api.chatanywhere.tech/v1", "CHATANYWHERE_API_KEY");
```

```java
// AiModel：每个「供应商+模型」组合一个枚举常量，带全局唯一 id
SENSENOVA_GLM52("sensenova-glm52", SENSENOVA, "glm-5.2", "GLM-5.2 旗舰，1M 上下文"),
CA_GPT4O_MINI("ca-gpt4o-mini", CHATANYWHERE, "gpt-4o-mini", "ChatAnywhere · GPT-4o mini"),
// ... 共 16 个模型
```

**关键点**：新增一个模型 = 加一行枚举，业务代码零改动。调用方只认 `id`。

### 为什么不用 Spring AI 自动配置的单例 ChatClient？

自动配置只能绑死「一个端点 + 一个模型」。要支持动态切换，必须自己写工厂：

```java
// AiChatClientFactory：按 id 现建 {端点 + 密钥 + 模型名} 三元组，并缓存复用
public ChatClient get(String modelId) {
    AiModel model = AiModel.of(modelId).orElseThrow(...);
    return cache.computeIfAbsent(model.getId(), this::build);
}
```

`ConcurrentHashMap` 缓存避免每次请求都重建连接池。

**接口一览**（全部 GET，便于浏览器直接调）：
- `GET /ai/models` —— 列出所有模型 id
- `GET /ai/stream?modelId=&message=` —— 流式（SSE）
- `GET /ai/chat?modelId=&message=` —— 非流式（对照调试）
- `GET /ai/providers` —— 供应商一览

---

## 阶段1：SSE 流式输出

**目标**：让回答像 ChatGPT 一样逐字蹦出来，而不是干等几秒一次性返回。

### 原理

- **HTTP 长连接 + `text/event-stream`**：服务端分多次把内容推给浏览器，边生成边返回。
- Spring 里用 `SseEmitter`（同步风格）或 `Flux<String>`（响应式）承载。

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
public SseEmitter stream(@RequestParam String message, ...) {
    SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时，推理模型会想很久
    Flux<String> flux = client.prompt().user(message).stream().content();
    flux.subscribeOn(Schedulers.boundedElastic()).subscribe(
        chunk -> send(emitter, "chunk", chunk),   // 每段内容推给前端
        err  -> emitter.completeWithError(err),
        ()   -> { send(emitter, "done", "[DONE]"); emitter.complete(); }
    );
    return emitter;
}
```

**事件协议**：`meta`（用了哪个模型）→ 若干 `chunk`（正文片段）→ `done`（结束）。

### 两个坑（都踩过）

1. **中文乱码**：`produces` 里必须显式带 `charset=UTF-8`，同时用 `StringHttpMessageConverter(UTF_8)` + Filter 强制字符集。
2. **base-url 双 `/v1`**：Spring AI 会自动拼 `/v1`，配置里别写 `/v1`，否则变 `/v1/v1/` → 404。

---

## 阶段2：RAG 检索增强生成

**目标**：让模型「照着资料答」，而不是凭空瞎编。上传 `.md` → 自动入库 → 提问时先检索再生成。

### 核心认知：RAG = 检索（Retrieval） + 生成（Generation）

很多人以为 RAG 只是「关键字搜索」，错。**检索只是第一步**，真正的价值在于把检索到的片段塞进 prompt，让模型**基于资料生成**答案。

```
用户提问
   │
   ▼
① 检索：问题向量化 → 找最相关的资料片段（Top-K）
   │
   ▼
② 生成：把片段拼进 system prompt → 模型照着资料回答 + 标注出处
```

### 手写 TF-IDF 向量库（零依赖，教学版）

**为什么手写**：把 RAG 最关键的一步「文本怎么变向量、怎么算相似度」彻底拆开。看懂这个，换真 Embedding 只是把 `vectorOf` 换成调 API。

```
① 切分：文档按 # 标题切块（标题+正文 = 一个 chunk）
② 向量化：TF-IDF —— 词在片段出现越多(TF高)、全库越少见(IDF高)，权重越高
③ 检索：问题向量 vs 每个 chunk 向量 → 余弦相似度 → Top-K
```

**TF-IDF 一句话**：`权重 = 词频(TF) × 逆文档频率(IDF)`。向量维度 = 词表大小。

**中文分词**：用 2-gram 滑动窗口（`家`+`庭`→`家庭`），避免依赖 jieba 等分词库。

### 三个「为什么答不出来」的修复

| 问题 | 原因 | 修复 |
|------|------|------|
| 只有标题没有正文 | 按空行切，标题和正文被拆成两块 | **按标题切块**，标题+正文永远同块 |
| 命中标题块却漏正文 | 检索只返回命中块本身 | **相邻块拼接**（前后各1块，同文档） |
| 无关片段混进上下文 | 相似度≈0 的片段也返回 | **最小阈值过滤**（score ≥ 0.02） |

### 接口

- `POST /ai/kb/upload` —— 上传 `.md`，自动切块 + rebuild 索引
- `GET /ai/rag?question=&sessionId=` —— 检索式问答，SSE 先发 `sources` 再发回答
- `GET /ai/kb` —— 查看知识库状态（chunk 数、来源）

**关键细节**：system prompt 明确约束「资料里没有就直接说不知道，不要编造」——这是 RAG 防止幻觉的核心。

---

## 阶段3：多轮记忆 + Agent 编排 + 工具调用

**目标**：让模型「记得上一句」，并且能主动调用工具（查时间、查知识库）。

### 多轮记忆：不神秘，就是「塞回历史」

LLM 本身**无状态**，所谓「记忆」就是：

> 把之前几轮的 user / assistant 消息，原样拼进下一次请求的 prompt。

```java
// ChatMemoryService：sessionId → 历史消息队列
public void append(String sessionId, String userText, String assistantText) {
    Deque<Message> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
    deque.addLast(new UserMessage(userText));
    deque.addLast(new AssistantMessage(assistantText));
    while (deque.size() > MAX_TURNS) deque.removeFirst(); // 防 prompt 无限膨胀
}
```

调用时 `.messages(history)` 先放历史，`.user(message)` 再放本轮问题。

**边界**：只保留最近 N 条（`MAX_TURNS=10`），防止上下文窗口被打爆。生产环境应换 Redis + 向量化长期记忆。

### 工具调用（Function Calling）

模型不是只能「说」，还能「做」。给它挂工具，它会在需要时自动发起工具调用：

```java
@Tool(description = "查询当前时间")  // @Tool 注解的方法会被自动扫描成工具
public String getCurrentTime() { ... }
```

```java
client.prompt()
    .user(message)
    .tools(timeTool, knowledgeBaseTool)  // 挂工具：模型可自动查时间 + 查知识库
    .stream().content();
```

**核心理解**：`@Tool`（进程内方法）和 `@McpTool`（MCP 远端工具）的区别——前者是本地 Java 方法，后者走协议调用外部服务。这是阶段4 的伏笔。

### Agent 编排（ToolTrace）

`ToolTrace` 记录每次工具调用的时间戳/参数/结果，让你**直观看到模型「思考-行动」的过程**——这就是 Agent 编排的雏形。

---

## 阶段4：MCP 协议

**目标**：通过标准协议，让模型调用「外部系统」的工具（天气查询），而非进程内方法。

### MCP 是什么

**Model Context Protocol（模型上下文协议）**——Anthropic 提出的开放标准，让 LLM 应用通过统一协议接入外部工具/数据源。类比：**MCP 之于 Agent，就像 USB 之于外设**——一个标准接口，插上就能用。

```
┌─────────────┐    stdio/SSE     ┌─────────────┐   HTTP    ┌─────────────┐
│  MCP Client  │ ◄──────────────► │  MCP Server  │ ─────────► │  wttr.in     │
│ （主应用）    │  tools/list/call │ （独立进程）  │  天气服务  │  （外部系统） │
└─────────────┘                  └─────────────┘            └─────────────┘
```

### 关键架构决策：Client 与 Server 必须分离

**踩过的坑**：MCP Client 和 MCP Server 的自动配置会**互相触发连接 → 死锁**，不能共存于同一进程。

**方案**：拆成两个独立进程，用 `stdio` 通信。
- 主应用（`DemoApplication`）= **MCP Client**，排除所有 Server 自动配置
- `McpServerApplication` = 独立 MCP Server，通过 `pom.xml` 二次 repackage 打出独立 jar（`classifier=mcp-server`）

### Server 端：@McpTool 暴露工具

```java
@McpTool(name = "get_weather", description = "查询指定城市的实时天气...")
public String getWeather(@McpToolParam(description = "城市名") String city) {
    // 真正调用外部 HTTP 服务 wttr.in
    return "城市：" + city + "\n" + summarize(weatherJson);
}
```

### Client 端：把 MCP 工具挂进 ChatClient

```java
ToolCallback[] mcpTools = mcpToolProvider.getToolCallbacks();
builder.defaultToolCallbacks(mcpTools);  // 必须用 defaultToolCallbacks()，不是 defaultTools()
```

**大坑**：`defaultTools()` 是给「带 @Tool 注解的对象」用的，传现成的 `ToolCallback[]` 会报 `No @Tool annotated methods found`。

### 完整闭环验证

```
用户问「合肥今天天气怎么样」
  → LLM 判断需要调用 get_weather
  → MCP Client 经 stdio 协议发 tools/call
  → 独立 MCP Server 进程收到，调 wttr.in
  → 返回真实天气数据（合肥 18℃ 晴天）
  → LLM 归纳成自然语言回答
```

---

## 踩坑清单

| # | 坑 | 现象 | 解决 |
|---|----|------|------|
| 1 | Spring AI 坐标错误 | 依赖找不到 | `spring-ai-starter-model-openai` |
| 2 | 阿里云镜像缺 Spring AI | 下载失败 | 项目 settings.xml 指向 Central |
| 3 | base-url 双 `/v1/v1/` | 404 | 去掉配置里的 `/v1`（SDK 自动拼） |
| 4 | 商汤 429 / ChatAnywhere 503 | 限流 | 换免费额度更充足的模型 |
| 5 | SSE 中文乱码 | 乱码 | produces 带 charset + UTF-8 转换器 |
| 6 | 按空行切块漏正文 | RAG 答不出 | 按标题切块 + 相邻拼接 + 阈值 |
| 7 | MCP 同进程死锁 | 启动卡死 | Client/Server 拆成独立进程 |
| 8 | `defaultTools()` 传 ToolCallback 报错 | 异常 | 改 `defaultToolCallbacks()` |
| 9 | stdio 中文乱码 | `鍚堣` | `-Dfile.encoding=UTF-8` |
| 10 | 独立进程连不上外网 | 超时 | JVM 代理参数走 Clash |

---

## 简历项目描述

### 版本 A：技术向（突出架构与难点）

**项目名称**：多模型 AI Agent 平台（Spring Boot + Spring AI）

**项目背景**：独立完成一个从零搭建的 AI Agent 教学项目，覆盖大模型接入、RAG 检索增强、多轮记忆、工具调用与 MCP 协议全链路。

**技术栈**：Spring Boot 3.3 / Spring AI 1.1.2 / SSE 流式 / TF-IDF 向量检索 / MCP (Model Context Protocol)

**核心工作**：

1. **多模型多供应商统一接入**：基于枚举 + 工厂模式实现 4 大供应商（商汤/通义/DeepSeek/ChatAnywhere）16 个模型的动态切换，调用方仅传模型 id，新增模型零业务代码改动；ChatClient 缓存复用避免连接池反复创建。
2. **SSE 流式输出**：基于 `SseEmitter` + `Flux` 实现边生成边返回，解决中文乱码（显式 charset）与 base-url 重复拼接问题。
3. **自研轻量 RAG 检索引擎**：零依赖手写 TF-IDF 向量化 + 余弦相似度检索，通过「标题切块 + 相邻块拼接 + 相似度阈值」三招解决检索召回不全问题；支持 `.md` 文档上传自动入库。
4. **多轮记忆与工具编排**：基于 sessionId 的消息队列实现上下文记忆（滑动窗口防膨胀），通过 `@Tool` 注解实现 Function Calling，让模型自动调用时间/知识库工具。
5. **MCP 协议落地**：将 Client/Server 拆成独立进程（stdio 通信），通过 `@McpTool` 暴露外部天气服务，实现「LLM → 协议调用 → 外部系统」的标准工具接入链路。

**项目成果**：全链路闭环验证通过（RAG 问答可溯源、MCP 天气查询返回真实数据），沉淀 10 项工程踩坑解决方案。

### 版本 B：简短版（一句话抓重点）

> 基于 Spring AI 独立搭建多模型 AI Agent 平台：通过枚举+工厂实现 4 供应商 16 模型动态切换，手写 TF-IDF 向量检索实现 RAG 知识库问答（可上传 md 自动入库、答案可溯源），基于 SSE 实现流式输出，落地 MCP 协议让 Agent 通过标准接口调用外部工具。全链路验证通过，沉淀 10 项踩坑经验。

### 版本 C：成果突出版（面试讲故事）

> **从 0 到 1 搭建 AI Agent 全栈实践**（Spring Boot + Spring AI + MCP）
>
> 一个人独立完成大模型应用从接入到生产的完整技术验证：
> - 统一了 4 家厂商、16 个模型的接入（枚举+工厂+缓存），调用方一行 id 切换
> - 自研 RAG 检索引擎，解决了「资料在库里却答不出来」的召回难题，实现答案可溯源
> - 通过 MCP 协议打通 Agent 与外部系统的标准调用，理解并解决 Client/Server 进程隔离等架构难点
>
> 成果：完整闭环（检索→生成、工具→协议→外部服务）验证通过，形成可复用的工程模板与 10 项踩坑清单。

---

## 附：项目关键文件索引

| 文件 | 职责 |
|------|------|
| `ai/AiProvider.java` | 供应商枚举（端点 + 密钥） |
| `ai/AiModel.java` | 模型枚举（唯一 id） |
| `ai/AiChatClientFactory.java` | 动态构建 + 缓存 ChatClient，挂 MCP 工具 |
| `rag/VectorStoreService.java` | 手写 TF-IDF 向量库 |
| `rag/KnowledgeBaseTool.java` | `@Tool` 知识库检索工具 |
| `memory/ChatMemoryService.java` | 多轮记忆（sessionId → 历史） |
| `agent/ToolTrace.java` | 工具调用轨迹（编排观测） |
| `mcpserver/McpServerApplication.java` | 独立 MCP Server 进程 |
| `mcpserver/WeatherMcpTool.java` | `@McpTool` 天气工具 |
| `AgentController.java` | 全部接口入口（stream/rag/mcp/...） |
