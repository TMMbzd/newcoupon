-- V12（P2d 阶段二）：消费端幂等锚点表——at-least-once 传输上的 effectively-once 消费。
-- 原理：INSERT IGNORE 撞 uk_event_no 返回 0 行=重投（已消费过），直接 ack 跳过；
-- 返回 1 行=首次消费，执行业务。消息重投多少次，业务只发生一次。
CREATE TABLE coupon_event_consumed (
  id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  event_no     VARCHAR(64)    NOT NULL       COMMENT '消费幂等锚点（=outbox/事件表的 event_no；重投撞唯一键即跳过）',
  topic        VARCHAR(64)    NOT NULL       COMMENT '来源主题（归因：从哪条通道消费的）',
  consumed_at  DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_no (event_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '消费端幂等锚点（ADR-0011 阶段二：INSERT IGNORE 判重投，业务只发生一次）';
