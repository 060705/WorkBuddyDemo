package com.example.demo.agent;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 工具调用轨迹记录器：记录 Agent 每次「调用工具」的动作，用于演示「编排（ReAct）」。
 *
 * <p>阶段 3 的「Agent 编排」本质：模型不是一次性答完，而是经历
 * <b>思考(Thought) → 行动(Action 调工具) → 观察(Observation 工具结果) → 再思考 → 最终回答</b> 的循环。
 * 这里把每次 Action（调工具）记下来，你就能直观看到模型「自己决定连调多个工具」的过程。
 *
 * <p>轨迹是进程内的滑动窗口（只留最近 {@link #MAX_ENTRIES} 条），供 /ai/trace 接口观测。
 */
@Component
public class ToolTrace {

    private static final int MAX_ENTRIES = 50;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** 一条工具调用记录 */
    public record Entry(String time, String tool, String args, String result) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /** 记录一次工具调用 */
    public void record(String tool, String args, String result) {
        entries.add(new Entry(LocalTime.now().format(FMT), tool, args,
                result == null ? "" : truncate(result, 200)));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** 取最近 N 条轨迹（倒序，最新在前） */
    public List<Entry> recent(int n) {
        int size = entries.size();
        int from = Math.max(0, size - n);
        List<Entry> sub = entries.subList(from, size);
        java.util.Collections.reverse(sub);
        return sub;
    }

    /** 清空轨迹 */
    public void clear() {
        entries.clear();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 转成可序列化的 Map 列表（供接口返回） */
    public List<Map<String, String>> recentAsMaps(int n) {
        return recent(n).stream()
                .map(e -> Map.of("time", e.time(), "tool", e.tool(), "args", e.args(), "result", e.result()))
                .toList();
    }
}
