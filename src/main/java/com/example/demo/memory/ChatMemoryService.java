package com.example.demo.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆：按 sessionId 保存历史消息，让模型「记得上一句」。
 *
 * <p>这是阶段 3 的「记忆」教学核心。所谓「记忆」在 LLM 里并不神秘：
 * <b>就是把之前几轮的 user / assistant 消息原样塞回下一次请求的 prompt</b>。
 * 模型本身无状态，是我们在每次请求时替它「复习」历史。
 *
 * <p>实现要点：
 * <ul>
 *   <li>内存 {@link ConcurrentHashMap} 存 sessionId → 消息队列，进程重启即清空（教学版）</li>
 *   <li>每会话只保留最近 {@link #MAX_TURNS} 条消息，防 prompt 无限膨胀</li>
 *   <li>生产环境应换成 Redis / 数据库 + 向量化「长期记忆」；这里先用最朴素的方式讲清原理</li>
 * </ul>
 */
@Service
public class ChatMemoryService {

    /** 每个会话保留的最大消息条数（user+assistant 都算，约等于保留最近 5 轮问答） */
    private static final int MAX_TURNS = 10;

    /** sessionId -> 历史消息队列 */
    private final ConcurrentHashMap<String, Deque<Message>> sessions = new ConcurrentHashMap<>();

    /**
     * 取某会话的历史消息（按时间顺序），返回只读列表。
     * 不存在或为空时返回空列表。
     */
    public List<Message> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        Deque<Message> deque = sessions.get(sessionId);
        if (deque == null) {
            return Collections.emptyList();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /**
     * 追加一轮对话到会话记忆：一条 user 消息 + 一条 assistant 回复。
     * 超过 {@link #MAX_TURNS} 时淘汰最旧的消息。
     */
    public void append(String sessionId, String userText, String assistantText) {
        if (sessionId == null || sessionId.isBlank()) {
            return; // 未传 sessionId 则不记忆（保持向后兼容的单轮模式）
        }
        Deque<Message> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new UserMessage(userText));
            if (assistantText != null && !assistantText.isBlank()) {
                deque.addLast(new AssistantMessage(assistantText));
            }
            while (deque.size() > MAX_TURNS) {
                deque.removeFirst(); // 淘汰最旧，防止上下文无限增长
            }
        }
    }

    /**
     * 清空某会话记忆（用户主动「新会话」时调用）。
     */
    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /** 当前内存中的会话数（用于观测/调试） */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 把一个「系统提示词」+ 历史消息 + 新问题，组装成一段可视化的「记忆上下文」说明文本。
     * 用于 meta 事件里告诉前端「本会话已带了多少条历史」。
     */
    public String describe(String sessionId) {
        int n = getHistory(sessionId).size();
        return n > 0 ? "已携带 " + n + " 条历史消息（多轮记忆）" : "无历史（首轮）";
    }
}
