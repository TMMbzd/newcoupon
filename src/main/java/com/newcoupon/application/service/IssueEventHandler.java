package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponEventConsumedMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发放事件消费处理器（ADR-0011 阶段二：消费端幂等协议）。
 *
 * <p>幂等锚点：coupon_event_consumed 表的 uk_event_no。INSERT IGNORE 返回 1=首次（执行业务），
 * 返回 0=重投（MQ 至少一次投递的重复面）——直接跳过并 ack。**传输可插拔的对称面**：
 * 消费端协议同样不绑 RocketMQ（任何 at-least-once 传输灌进来都是 effectively-once）。
 *
 * <p>阶段二的"业务"= 记录消费+日志（计域的种子——P5 经营指标从这里长出来）。
 */
@Component
public class IssueEventHandler {

    private static final Logger log = LoggerFactory.getLogger(IssueEventHandler.class);

    private final CouponEventConsumedMapper consumedMapper;

    public IssueEventHandler(CouponEventConsumedMapper consumedMapper) {
        this.consumedMapper = consumedMapper;
    }

    /**
     * @return true=首次消费（业务已执行）；false=重投（幂等跳过，直接 ack）
     */
    public boolean handle(String topic, String eventNo, String payload) {
        int inserted = consumedMapper.insertIgnore(eventNo, topic);
        if (inserted == 0) {
            log.info("duplicate delivery ignored (at-least-once redelivery): eventNo={}", eventNo);
            return false;
        }
        // 首次消费——业务落位（阶段二=记录+日志；P5 经营指标消费者在此扩展）
        log.info("issue event consumed: topic={} eventNo={}", topic, eventNo);
        return true;
    }
}
