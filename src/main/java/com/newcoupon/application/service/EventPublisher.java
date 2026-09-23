package com.newcoupon.application.service;

/**
 * 事件发布器（ADR-0011 决策 3：传输与发件箱解耦）。
 * 阶段一实现：LogEventPublisher（结构化日志——MQ 未接入前可观测、可测试）；
 * 阶段二实现：RocketMQEventPublisher（容器就绪后新增实现类，发件箱语义与表结构零改动）。
 * 契约：publish 抛异常 = 投递失败（发件箱退避重试）；正常返回 = 传输层已接受。
 */
public interface EventPublisher {

    /**
     * @param topic    主题（阶段一为日志分类标签；阶段二为 RocketMQ topic）
     * @param eventNo  事件幂等号（消费方去重锚点）
     * @param payload  消息体 JSON（发送方不解析，透传）
     */
    void publish(String topic, String eventNo, String payload);
}
