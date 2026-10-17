-- ===================================================================
-- V21 第 2 环行业标准版：行级分账 + 按行部分退款（2026-10-18）
--
-- 1) coupon_share_detail 补 order_line_quantity：
--    核销时一行商品一条分账，记录该行数量；按数量部分退款用它拆行内优惠。
-- 2) coupon_refund_order / coupon_refund_detail：
--    退款单 + 退款明细。refund_no 是退款幂等锚点（同号重放返回原结果，
--    同号异参报冲突）；明细按“行 × 退款单”记录冲回金额与出资方。
-- 3) coupon_return_log：
--    整单退款把券退回可用时留痕，用于“一券限退 N 次”与审计。
-- 4) coupon_usage 64 张分表补 idx_user_order：
--    核销时按 (user_id, order_no) 找回同订单全部占用单（L64 整栈复算）。
--    带 user_id 是分片路由键，单分片命中，不广播。
--
-- 资金账仍以 coupon_share_detail 的正行为准；退款负行改落 coupon_refund_detail，
-- 日账单聚合时两表 UNION ALL（正行计核销、退款明细计冲回），避免往分账行上
-- 叠 ":REFUND" 后缀导致同一行多次部分退款撞唯一键。
-- ===================================================================

ALTER TABLE coupon_share_detail
    ADD COLUMN order_line_quantity INT DEFAULT NULL
        COMMENT '该行商品数量（按数量部分退款时在行内拆优惠；老式核销为NULL）'
        AFTER order_line_amount;

CREATE TABLE coupon_refund_order (
  id                    BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  refund_no             VARCHAR(64)  NOT NULL COMMENT '退款单号（幂等锚点；同号重放返回原结果，同号异参报冲突）',
  usage_no              VARCHAR(64)  NOT NULL COMMENT '占用单号（=订单号:券id）',
  user_coupon_id        BIGINT       NOT NULL COMMENT '用户券id',
  user_id               BIGINT       NOT NULL COMMENT '用户id',
  template_id           BIGINT       NOT NULL COMMENT '模板id',
  order_no              VARCHAR(64)  NOT NULL COMMENT '订单号',
  refund_type           VARCHAR(16)  NOT NULL COMMENT 'PARTIAL=部分退款 / FULL=整单退完',
  refund_discount_amt   BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲回的优惠总额（分）',
  refund_platform_amt   BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲回的平台承担（分）',
  refund_merchant_amt   BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲回的商家承担（分）',
  request_digest        VARCHAR(64)  NOT NULL COMMENT '请求摘要（usageNo+行号+数量），同号异参检测',
  item_count            INT          NOT NULL DEFAULT 0 COMMENT '本次退款涉及的商品行数',
  coupon_returned       TINYINT      NOT NULL DEFAULT 0 COMMENT '整单退时券是否已退回可用（1=是）',
  coupon_status         VARCHAR(32)  DEFAULT NULL COMMENT '退款完成时的券状态（CouponStatus.name()，同号重放原样返回）',
  usage_status          VARCHAR(32)  DEFAULT NULL COMMENT '退款完成时的占用单状态（UsageStatus.name()，同号重放原样返回）',
  create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  KEY idx_usage (usage_no),
  KEY idx_template_time (template_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '券退款单（按行部分退款；refund_no 幂等）';

CREATE TABLE coupon_refund_detail (
  id                    BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  refund_no             VARCHAR(64)  NOT NULL COMMENT '退款单号',
  usage_no              VARCHAR(64)  NOT NULL COMMENT '占用单号',
  order_line_no         VARCHAR(64)  NOT NULL COMMENT '商品行号（与 coupon_share_detail 对齐）',
  user_coupon_id        BIGINT       NOT NULL COMMENT '用户券id',
  user_id               BIGINT       NOT NULL COMMENT '用户id',
  template_id           BIGINT       NOT NULL COMMENT '模板id',
  refund_qty            INT          NOT NULL COMMENT '本次退款数量',
  refund_discount_amt   BIGINT       NOT NULL COMMENT '本次冲回优惠（分）',
  refund_platform_amt   BIGINT       NOT NULL COMMENT '本次冲回平台承担（分）',
  refund_merchant_amt   BIGINT       NOT NULL COMMENT '本次冲回商家承担（分）',
  create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_line (refund_no, order_line_no),
  KEY idx_usage_line (usage_no, order_line_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '券退款明细（一行商品一条；累计退款数量不得超过原数量）';

CREATE TABLE coupon_return_log (
  id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  user_coupon_id   BIGINT       NOT NULL COMMENT '用户券id',
  refund_no        VARCHAR(64)  NOT NULL COMMENT '触发退券的退款单号',
  return_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '退券时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_return_refund (refund_no),
  KEY idx_return_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '整单退款退券留痕（一券限退 N 次防“下单-退款”刷券）';

-- 64 张 coupon_usage 分表补订单号索引：核销按 (user_id, order_no) 找回同订单全部占用单
ALTER TABLE coupon_usage_0 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_1 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_2 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_3 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_4 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_5 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_6 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_7 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_8 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_9 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_10 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_11 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_12 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_13 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_14 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_15 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_16 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_17 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_18 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_19 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_20 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_21 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_22 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_23 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_24 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_25 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_26 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_27 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_28 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_29 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_30 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_31 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_32 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_33 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_34 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_35 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_36 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_37 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_38 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_39 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_40 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_41 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_42 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_43 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_44 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_45 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_46 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_47 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_48 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_49 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_50 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_51 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_52 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_53 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_54 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_55 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_56 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_57 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_58 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_59 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_60 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_61 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_62 ADD INDEX idx_user_order (user_id, order_no);
ALTER TABLE coupon_usage_63 ADD INDEX idx_user_order (user_id, order_no);
