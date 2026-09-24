package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponEventConsumedMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 装配（ADR-0011 阶段二——传输可插拔的"另一头"）。
 *
 * <p>裸客户端手写装配而非 spring-boot-starter：Boot3 的 starter 版本兼容混乱；
 * 手写让生产者/消费者的每个参数可见（教学与运维双收益）。
 *
 * <p>开关：coupon.outbox.transport=rocketmq 时整套生效（publisher 换实现+消费者启动）；
 * 默认 log——测试与无 MQ 环境零依赖（LogEventPublisher 退为测试/降级通道）。
 *
 * <p>消费语义：push 消费者，并发监听；handler 幂等（INSERT IGNORE 判重投），
 * 业务异常返回 RECONSUME_LATER（RocketMQ 按延迟级别自动重投——重试预算在 MQ 侧，
 * 最终进死信队列 %DLQ%消费组；发件箱侧的退避/死信是生产端对称面）。
 */
@Configuration
@ConditionalOnProperty(name = "coupon.outbox.transport", havingValue = "rocketmq")
public class RocketMqOutboxConfig {

    private static final Logger log = LoggerFactory.getLogger(RocketMqOutboxConfig.class);
    public static final String TOPIC_ISSUED = "coupon-issued";

    /** 生产者（投递器经 EventPublisher 接口使用） */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer outboxProducer(
            @Value("${coupon.outbox.namesrv-addr:localhost:9876}") String namesrvAddr) throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer("outbox-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setSendMsgTimeout(3000);
        producer.start();
        log.info("RocketMQ producer started: namesrv={}", namesrvAddr);
        return producer;
    }

    /** RocketMQ 传输实现（替换 LogEventPublisher——发件箱语义与表结构零改动） */
    @Bean
    public EventPublisher rocketMqEventPublisher(DefaultMQProducer producer) {
        return (topic, eventNo, payload) -> {
            try {
                org.apache.rocketmq.common.message.Message msg = new org.apache.rocketmq.common.message.Message(
                        topic, "outbox", eventNo, payload.getBytes(StandardCharsets.UTF_8));
                producer.send(msg);
            } catch (Exception e) {
                throw new RuntimeException("rocketmq send failed: topic=" + topic + " eventNo=" + eventNo, e);
            }
        };
    }

    /** 消费者（发放事件 → 幂等处理）：异常返回 RECONSUME_LATER 走 MQ 重投/死信 */
    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer issueEventConsumer(
            @Value("${coupon.outbox.namesrv-addr:localhost:9876}") String namesrvAddr,
            IssueEventHandler handler) throws MQClientException {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("issue-event-consumer");
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.subscribe(TOPIC_ISSUED, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (List<MessageExt> msgs,
                                                                        ConsumeConcurrentlyContext ctx) -> {
            for (MessageExt msg : msgs) {
                try {
                    String eventNo = msg.getKeys();
                    String payload = new String(msg.getBody(), StandardCharsets.UTF_8);
                    handler.handle(TOPIC_ISSUED, eventNo, payload);
                } catch (RuntimeException e) {
                    log.warn("consume failed (RECONSUME_LATER): msgId={} keys={}",
                            msg.getMsgId(), msg.getKeys(), e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;   // MQ 重投→耗尽进 %DLQ%
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ issue-event consumer started: namesrv={}", namesrvAddr);
        return consumer;
    }
}
