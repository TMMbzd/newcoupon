-- ===================================================================
-- V20 T+1 日账单（Phase 1 算钱域收官项）
-- 按模板聚合当日 coupon_share_detail（净额——退款负行自动冲回），
-- 定时任务每日 00:30 出昨日账单；重跑 = 幂等覆盖（uk 冲突走 UPDATE）。
-- 金额单位：分；discount_total = SUM(share_total) = 平台 + 商家承担
-- ===================================================================
CREATE TABLE coupon_daily_bill (
  id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  bill_date        DATE          NOT NULL       COMMENT '账单日（按分账行 create_time 归属）',
  template_id      BIGINT        NOT NULL       COMMENT '模板id',
  confirm_count    INT           NOT NULL DEFAULT 0 COMMENT '核销行数（正分账行）',
  refund_count     INT           NOT NULL DEFAULT 0 COMMENT '退款冲回行数（负分账行）',
  discount_total   BIGINT        NOT NULL DEFAULT 0 COMMENT '净优惠总额（分）=SUM(share_total)，退款自动净额',
  platform_burden  BIGINT        NOT NULL DEFAULT 0 COMMENT '平台承担净额（分）',
  merchant_burden  BIGINT        NOT NULL DEFAULT 0 COMMENT '商家承担净额（分）',
  create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_date_tpl (bill_date, template_id),
  KEY idx_date (bill_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'T+1 日账单（按模板日汇总，重跑幂等覆盖）';
