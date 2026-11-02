-- V26（ADR-0018 §3.6）：库存调整审计。
-- 调整库存是低频管理操作，但必须可追溯：谁、何时、为什么、调整前后总量、各片额度变化。
-- requestId + template_id 唯一：同一调整请求重试不会重复加/减额度。
CREATE TABLE coupon_stock_adjust_log (
  id                 BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  template_id        BIGINT       NOT NULL COMMENT '模板id',
  request_id         VARCHAR(64)  NOT NULL COMMENT '调整请求幂等键',
  operator           VARCHAR(64)  DEFAULT NULL COMMENT '操作人/系统标识',
  reason             VARCHAR(255) DEFAULT NULL COMMENT '调整原因',
  expected_total     BIGINT       NOT NULL COMMENT '请求预期的调整前总量（CAS 用）',
  before_total       BIGINT       NOT NULL COMMENT '实际调整前总量',
  target_total       BIGINT       NOT NULL COMMENT '调整后总量',
  before_shards_json TEXT         DEFAULT NULL COMMENT '调整前各片 {shardNo,quota,used}',
  after_shards_json  TEXT         DEFAULT NULL COMMENT '调整后各片 {shardNo,quota,used}',
  create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_request (template_id, request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = 'C1 库存调整审计（requestId 幂等）';
