# WorkBuddyDemo — AI Agent 从 0 到 1 实践项目

基于 **Spring Boot 3.3 + Spring AI 1.1.2** 的 AI Agent 教学项目，覆盖四个阶段：

| 阶段 | 主题 | 核心能力 |
|------|------|---------|
| 0 | 多模型多供应商接入 | 枚举 + 工厂，4 供应商 16 模型动态切换 |
| 1 | SSE 流式输出 | 边生成边返回，逐字输出 |
| 2 | RAG 检索增强 | 手写 TF-IDF 向量库，上传 md 自动入库 |
| 3 | 多轮记忆 + 工具编排 | sessionId 记忆 + `@Tool` Function Calling |
| 4 | MCP 协议 | Client/Server 分离，标准工具接入 |

## 快速开始

```bash
# 设置 API Key（至少一个供应商）
export SENSENOVA_API_KEY=sk-xxx
export CHATANYWHERE_API_KEY=sk-xxx

# 启动主应用（默认端口 8089）
./mvnw spring-boot:run
```

## 核心接口（全部 GET，浏览器可直接访问）

| 接口 | 说明 |
|------|------|
| `GET /ai/models` | 列出所有模型 id |
| `GET /ai/stream?modelId=&message=&sessionId=` | 流式对话（SSE） |
| `GET /ai/chat?modelId=&message=` | 非流式对话 |
| `GET /ai/rag?question=&sessionId=` | RAG 知识库问答（可溯源） |
| `POST /ai/kb/upload` | 上传 `.md` 到知识库 |
| `GET /ai/kb` | 查看知识库状态 |
| `GET /ai/mcp?message=` | MCP 工具演示（天气查询） |
| `GET /ai/mcp/tools` | 列出 MCP 已接入工具 |
| `GET /ai/trace` | 查看工具调用轨迹 |
| `GET /ai/memory/clear` | 清空会话记忆 |

## 前端

启动后访问 `http://localhost:8089`，内置 tab 页（普通对话 / 知识库问答），支持模型下拉选择、md 上传、sessionId 自动生成。

## 目录结构

```
src/main/java/com/example/demo/
├── ai/            # AiProvider / AiModel / AiChatClientFactory（模型接入层）
├── rag/           # VectorStoreService / KnowledgeBaseTool（RAG 层）
├── memory/        # ChatMemoryService（多轮记忆）
├── agent/         # ToolTrace（工具编排观测）
├── mcpserver/     # MCP Server 独立进程 + WeatherMcpTool
└── AgentController.java   # 全部 HTTP 接口入口
```

## 详细学习笔记

见 [docs/AI-Agent学习笔记.md](docs/AI-Agent学习笔记.md)，含原理讲解、关键代码、踩坑清单与简历项目描述。
