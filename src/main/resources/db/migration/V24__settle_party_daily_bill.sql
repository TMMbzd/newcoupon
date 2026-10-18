-- ===================================================================
-- V24 环 3：按结算主体的 T+1 日账单 + 查询索引（2026-10-18）
--
-- 1) coupon_settle_party_daily_bill：日期 + app_id + 主体 + 模板；
--    正分账行计核销，退款明细/旧负分账行计冲回；净额与模板日账单同源。
--    独立新表，旧 coupon_daily_bill 不动；重跑按唯一键幂等覆盖。
-- 2) 历史退款明细主体回填：V22 之前退款明细没有主体，迁移时统一落
--    __default__；这里按 (usage_no, order_line_no) 从原分账行回填，
--    只有原分账行有真实主体时才覆盖。仍无法追溯的（原分账行也是
--    __default__/老式无明细退款）保留 __default__，不能用于真实商家结算。
-- 3) 查询/聚合索引：按 create_time 扫描的日聚合、按主体/模板查询。
--
-- 明确不做：
--   - 不修改 coupon_daily_bill 的字段/唯一键（旧模板账单保持原样）；
--   - 不做打款状态/结算时点/门店层级（环 3 标准版边界）；
--   - 老式无 items 退款没有明细，不进主体账单的退款冲回（已知边界）。
-- ===================================================================

CREATE TABLE coupon_settle_party_daily_bill (
  id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  bill_date        DATE          NOT NULL       COMMENT '账单日（按分账/退款明细 create_time 归属）',
  app_id           VARCHAR(32)   COLLATE utf8mb4_bin NOT NULL COMMENT '租户/接入方（大小写敏感）',
  settle_party_id  VARCHAR(64)   COLLATE utf8mb4_bin NOT NULL COMMENT '结算主体（大小写敏感；无商家概念为 __default__）',
  template_id      BIGINT        NOT NULL       COMMENT '模板id',
  confirm_count    INT           NOT NULL DEFAULT 0 COMMENT '核销行数（正分账行）',
  refund_count     INT           NOT NULL DEFAULT 0 COMMENT '退款冲回行数（退款明细/旧负行）',
  discount_total   BIGINT        NOT NULL DEFAULT 0 COMMENT '净优惠总额（分）= 平台 + 非平台承担',
  platform_burden  BIGINT        NOT NULL DEFAULT 0 COMMENT '平台承担净额（分）',
  merchant_burden  BIGINT        NOT NULL DEFAULT 0 COMMENT '非平台出资方承担净额（分）',
  create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_date_app_party_tpl (bill_date, app_id, settle_party_id, template_id),
  KEY idx_date_app_party (bill_date, app_id, settle_party_id),
  KEY idx_date_tpl (bill_date, template_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '按结算主体的 T+1 日账单（重跑幂等覆盖；主体大小写敏感）';

-- 历史退款明细主体回填：只从原分账行复制，不猜商家
UPDATE coupon_refund_detail d
  JOIN coupon_share_detail s
    ON s.usage_no = d.usage_no
   AND s.order_line_no = d.order_line_no
   SET d.app_id = s.app_id,
       d.settle_party_id = s.settle_party_id
 WHERE d.settle_party_id = '__default__'
   AND s.settle_party_id <> '__default__';

-- 日聚合按 create_time 过滤；退款查询按日期过滤
ALTER TABLE coupon_share_detail
    ADD INDEX idx_share_time (create_time);
ALTER TABLE coupon_refund_detail
    ADD INDEX idx_refund_detail_time (create_time);
-- 老式退款（无 items/无分账）没有出资拆分：platform/merchant 写 0 不可用于结算，
-- 用 funding_known=0 明确标注，查询 API 原样返回，财务不会把 0 当真实出资。
ALTER TABLE coupon_refund_order
    ADD COLUMN funding_known TINYINT NOT NULL DEFAULT 1
        COMMENT '0=老式退款无出资明细（platform/merchant=0 不可用于结算）'
        AFTER item_count;

ALTER TABLE coupon_refund_order
    ADD INDEX idx_refund_order_time (create_time),
    ADD INDEX idx_refund_order_no_time (order_no, create_time);
