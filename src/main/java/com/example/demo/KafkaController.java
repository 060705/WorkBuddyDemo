package com.example.demo;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 收发测试接口：用于端到端验证 Spring Boot -> Kafka -> 消费者 链路。
 *
 * 接口清单：
 * - GET/POST /kafka/send?topic=demo-topic&key=k1&message=hello   发送一条消息，返回落盘分区/offset
 * - GET       /kafka/messages                                     查看本应用消费到的消息（闭环验证）
 * - GET       /kafka/clear                                        清空收件箱（方便重复演示）
 *
 * 验证思路：先 /kafka/send 发几条 -> 再 /kafka/messages 看是否都收到了 -> 收到即证明
 * "Producer 发 -> Kafka 存 -> Consumer 拉"全链路打通。
 */
@RestController
@RequestMapping("/kafka")
public class KafkaController {

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private KafkaInbox inbox;

    /** 发送消息（GET/POST 都支持，方便浏览器与 curl 调试） */
    @RequestMapping(value = "/send", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> send(@RequestParam(defaultValue = "demo-topic") String topic,
                                    @RequestParam(required = false) String key,
                                    @RequestParam String message) throws Exception {
        // 有 key 用 key（保证同 key 进同一分区，保序）；无 key 让 Kafka 轮询分配分区
        CompletableFuture<SendResult<String, String>> future = (key == null || key.isBlank())
                ? kafkaTemplate.send(topic, message)
                : kafkaTemplate.send(topic, key, message);

        SendResult<String, String> r = future.get();   // 同步等结果（演示用；生产建议异步回调 onSuccess/onFailure）
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", r.getRecordMetadata().topic());
        m.put("partition", r.getRecordMetadata().partition());
        m.put("offset", r.getRecordMetadata().offset());
        m.put("key", key);
        m.put("message", message);
        m.put("status", "SENT");
        return m;
    }

    /** 查看本应用消费到的消息（证明 Kafka 真的把消息投递给了消费者） */
    @GetMapping("/messages")
    public List<Map<String, Object>> messages() {
        return inbox.all();
    }

    /** 清空收件箱（重复演示前先清） */
    @GetMapping("/clear")
    public String clear() {
        inbox.clear();
        return "kafka inbox cleared";
    }
}
