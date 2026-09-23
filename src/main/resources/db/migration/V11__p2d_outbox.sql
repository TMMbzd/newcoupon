-- V11（P2d 发件箱，ADR-0011）：与 coupon_event 同事务双写的投递状态表。
-- 设计要点：coupon_event 保持只追加（事实账本不许被投递器回写）；本表是可变的
-- 运营状态（status/retry/next_scan_time）。守恒：发放类事件行数 ≡ 发件箱行数。

CREATE TABLE coupon_outbox (
  id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  event_no       VARCHAR(64)    NOT NULL       COMMENT '关联 coupon_event.event_no（同事务写入，唯一；消费方幂等锚点内嵌于 payload）',
  event_type     TINYINT        NOT NULL       COMMENT '事件类型（与 coupon_event 同枚举：1发放…）',
  payload        VARCHAR(2048)  NOT NULL       COMMENT 'MQ 消息体 JSON（投递器透传，发送方不解析）',
  status         TINYINT        NOT NULL DEFAULT 0 COMMENT '投递状态:0待投递 1已投递 2死信（重试耗尽，人工介入——消息永不丢）',
  retry_count    INT            NOT NULL DEFAULT 0 COMMENT '已重试次数（退避 2^retry 秒）',
  next_scan_time DATETIME(3)    NOT NULL       COMMENT '下次可投递时刻（投递器按 status+此时间扫）',
  last_error     VARCHAR(512)   DEFAULT NULL   COMMENT '最近一次投递失败原因（死信归因）',
  create_time    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_no (event_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '发件箱（ADR-0011：与 coupon_event 同事务双写；投递器异步搬进 MQ；传输可插拔）';
