package edu.zjnu.interview.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * Kafka 持续消费示例（Ctrl+C 退出）
 *
 * 配置读取顺序: 环境变量 > 仓库根目录 .env > 默认值（.env 已 gitignore, 不要提交真实地址）
 *
 * 运行（先在仓库根目录 .env 中配置 broker 地址）:
 *   mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.kafka.SimpleConsumer
 *
 * 可用环境变量（也可写在 .env）:
 *   KAFKA_BOOTSTRAP_SERVERS  broker 地址, 默认 localhost:9092
 *   KAFKA_TOPIC              订阅的 topic, 默认 demo-topic
 *   KAFKA_GROUP_ID           消费组, 默认 kafka-demo-group
 *
 * 验证面试点:
 * 1. auto.offset.reset=earliest：新消费组从最早的消息开始读
 * 2. 同一消费组内, 一个 partition 同时只会被一个消费者消费
 * 3. 默认自动提交 offset；需要精确控制可改为手动 commitSync / commitAsync
 */
public class SimpleConsumer {

    public static void main(String[] args) {
        String bootstrap = KafkaDemoEnv.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String topic = KafkaDemoEnv.get("KAFKA_TOPIC", "demo-topic");
        String groupId = KafkaDemoEnv.get("KAFKA_GROUP_ID", "kafka-demo-group");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("收到 partition=%d offset=%d key=%s value=%s%n",
                            record.partition(), record.offset(), record.key(), record.value());
                }
            }
        }
    }
}
