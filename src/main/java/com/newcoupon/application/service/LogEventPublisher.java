package com.newcoupon.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志传输实现（ADR-0011 阶段一）：把消息打成结构化日志行——
 * [OUTBOX-MSG] 标签可被日志系统采集（阶段二前的事实上的"MQ"）。
 * 阶段二 RocketMQ 容器就绪后，本类退役为测试/降级通道，生产切 RocketMQEventPublisher。
 */
@Component
public class LogEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogEventPublisher.class);

    @Override
    public void publish(String topic, String eventNo, String payload) {
        log.info("[OUTBOX-MSG] topic={} eventNo={} payload={}", topic, eventNo, payload);
    }
}
