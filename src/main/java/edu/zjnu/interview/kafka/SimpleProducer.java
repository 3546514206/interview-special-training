package edu.zjnu.interview.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.UUID;

/**
 * Kafka 持续发送示例（Ctrl+C 退出）
 *
 * 配置读取顺序: 环境变量 > 仓库根目录 .env > 默认值（.env 已 gitignore, 不要提交真实地址）
 *
 * 运行（先在仓库根目录 .env 中配置 broker 地址）:
 *   mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.kafka.SimpleProducer
 *
 * 可用环境变量（也可写在 .env）:
 *   KAFKA_BOOTSTRAP_SERVERS  broker 地址, 默认 localhost:9092
 *   KAFKA_TOPIC              目标 topic, 默认 demo-topic（不存在时由 broker 自动创建）
 *   KAFKA_SEND_INTERVAL_MS   发送间隔毫秒, 默认 1000
 *
 * 验证面试点:
 * 1. acks=all + enable.idempotence=true：等所有 ISR 确认且生产端幂等
 * 2. 相同 key 落到同一 partition，消息按 key 有序
 */
public class SimpleProducer {

    public static void main(String[] args) throws InterruptedException {
        String bootstrap = KafkaDemoEnv.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = KafkaDemoEnv.get("KAFKA_TOPIC", "demo-topic");
        long intervalMs = Long.parseLong(KafkaDemoEnv.get("KAFKA_SEND_INTERVAL_MS", "1000"));

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (long seq = 0; ; seq++) {
                String key = "key-" + (seq % 3);
                String value = "seq=" + seq + " rand=" + UUID.randomUUID();
                producer.send(new ProducerRecord<>(topic, key, value), (metadata, e) -> {
                    if (e != null) {
                        System.err.println("发送失败: " + e.getMessage());
                    } else {
                        System.out.printf("已发送 key=%s value=%s -> partition=%d offset=%d%n",
                                key, value, metadata.partition(), metadata.offset());
                    }
                });
                Thread.sleep(intervalMs);
            }
        }
    }
}
