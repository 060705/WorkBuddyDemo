package com.example.demo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kafka 收件箱（演示用内存容器）。
 *
 * 作用：@KafkaListener 消费到的消息先暂存到这里，再由 /kafka/messages 接口读出来，
 * 从而端到端证明"Producer 发出去的消息，确实被 Consumer 投递收到了"（发→收闭环）。
 *
 * 注意：这是纯演示手段，存在应用 JVM 内存里，应用重启即清空，不保证持久/不丢。
 * 真实业务里消费者应该直接处理消息（落库 / 调下游），而不是缓存起来给人看。
 */
@Component
public class KafkaInbox {

    /** 收到的消息列表，每条是一个 {time, topic, partition, key, payload} 的 Map */
    public final List<Map<String, Object>> received = new ArrayList<>();

    public List<Map<String, Object>> all() {
        return received;
    }

    public void clear() {
        received.clear();
    }
}
