package com.example.demo.rag;

import com.example.demo.agent.ToolTrace;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 把「知识库检索」暴露成 LLM 可调用的工具（Function Calling）。
 *
 * <p>用法：模型遇到「私有知识」相关问题时，会自己决定调用 {@link #search}，
 * 拿回最相关的片段，再照着片段组织回答。和 {@code TimeTool} 同一个玩法。
 *
 * <p>这里刻意让工具返回「片段原文 + 相似度 + 出处」，而不是让模型瞎编，
 * 这正是 RAG「先检索、后生成」能减少幻觉的关键。
 */
@Component
public class KnowledgeBaseTool {

    private final VectorStoreService store;
    private final ToolTrace trace;

    public KnowledgeBaseTool(VectorStoreService store, ToolTrace trace) {
        this.store = store;
        this.trace = trace;
    }

    /** 暴露底层向量库，供 Controller 直接检索（用于 /ai/rag 标注参考片段） */
    public VectorStoreService getStore() {
        return store;
    }

    /**
     * 检索知识库。
     *
     * @param query 用户想查的问题或关键词
     * @param topK  返回最相关的前几条（默认 5）
     * @return 检索到的知识片段（含出处），若知识库为空则返回提示
     */
    @Tool(description = "从内部知识库检索与问题最相关的资料片段。当用户问的是私有/内部知识（如公司接口、表结构、业务流程、内部文档）时使用。返回片段原文、相似度与出处。")
    public String search(
            @ToolParam(description = "要检索的问题或关键词") String query,
            @ToolParam(description = "返回片段条数，默认 5") Integer topK) {

        int k = (topK == null || topK <= 0) ? 5 : topK;
        if (store.size() == 0) {
            trace.record("search", "query=" + query, "(知识库为空)");
            return "（知识库当前为空，没有可检索的资料）";
        }
        List<VectorStoreService.Hit> hits = store.search(query, k);
        String result = hits.stream()
                .map(h -> String.format("【出处：%s｜相关度 %.2f】\n%s",
                        h.chunk.source, h.score, h.chunk.text))
                .collect(Collectors.joining("\n\n---\n\n"));
        trace.record("search", "query=" + query + ", topK=" + k,
                "命中 " + hits.size() + " 条");
        return result;
    }
}
