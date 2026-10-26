package com.example.demo;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kafka 消费者：用 @KafkaListener 订阅主题，收到消息即入收件箱。
 *
 * 关键点（面试常考）：
 * 1. topics 指定订阅的主题；group-id 来自 application.yaml 的 spring.kafka.consumer.group-id。
 * 2. 方法参数默认收到的是消息 value（反序列化后的对象）。若要拿分区/offset/key，
 *    把参数类型换成 ConsumerRecord<String, String> 即可（下面注释里示范）。
 * 3. 同一个 group 内一条消息只给一个消费者实例（并行靠多分区/多实例）；不同 group 各自独立消费（广播）。
 */
@Component
public class KafkaConsumer {

    @Resource
    private KafkaInbox inbox;

    @KafkaListener(topics = "demo-topic", groupId = "demo-group")
    public void consume(String payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", LocalTime.now().toString());
        m.put("payload", payload);
        inbox.received.add(m);
    }

    // 需要分区/offset/key 时改用 ConsumerRecord（保留备用，不影响上面的监听）：
    // @KafkaListener(topics = "demo-topic", groupId = "demo-group")
    // public void consume(ConsumerRecord<String, String> record) {
    //     Map<String, Object> m = new LinkedHashMap<>();
    //     m.put("time", LocalTime.now().toString());
    //     m.put("topic", record.topic());
    //     m.put("partition", record.partition());
    //     m.put("offset", record.offset());
    //     m.put("key", record.key());
    //     m.put("payload", record.value());
    //     inbox.received.add(m);
    // }
}
