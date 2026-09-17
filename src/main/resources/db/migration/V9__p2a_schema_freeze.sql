-- V8（P2a 结构定版，2026-09-17 ROADMAP v4：不可逆数据层一次定死——ADR-0010 自律）
-- 依据：大厂审查 E1/E2/E3/E7 裁决 + 二轮调研。
-- 内容：①coupon_activity 新表（预算/活动层）②coupon_template 补四列（互斥组/叠加组/出资方/适用范围）
--       ③coupon_event 补归因列（activity_id/source）④coupon_share_detail 新表（成本分摊）
--       ⑤coupon_usage 补 refund_no（退款幂等锚点，先建列后实现）
-- 注意：coupon_event 未分片（V6 决策：模板维度查询为主），ALTER 直接改；分片表的 ALTER 逐物理表执行。

-- ===================================================================
-- ① coupon_activity 新表（预算-活动-批次三层模型的事实源）
-- ===================================================================
CREATE TABLE coupon_activity (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  activity_code       VARCHAR(32)    NOT NULL       COMMENT '对外活动编码',
  name                VARCHAR(128)   NOT NULL       COMMENT '活动名称',
  budget_amount       BIGINT         NOT NULL       COMMENT '预算金额（分）——面额×库存的上限',
  funder_type         TINYINT        NOT NULL DEFAULT 1 COMMENT '出资方:1平台 2商家 3联合',
  funder_ratio        INT            DEFAULT NULL   COMMENT '联合出资时平台承担百分比(0~100)，纯平台/商家为NULL',
  start_time          DATETIME       NOT NULL       COMMENT '活动开始时间',
  end_time            DATETIME       NOT NULL       COMMENT '活动结束时间',
  status              TINYINT        NOT NULL DEFAULT 0 COMMENT '0草稿 1进行中 2已结束 3已取消',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_activity_code (activity_code),
  KEY idx_status_time (status, end_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '营销活动（预算层——活动1:N批次）';

-- ===================================================================
-- ② coupon_template 补四列（互斥组/叠加组/出资方/适用范围）
-- ===================================================================
ALTER TABLE coupon_template
  ADD COLUMN exclusion_group VARCHAR(32) DEFAULT NULL COMMENT '互斥组标识（同组券不可同时使用，NULL=不参与互斥）',
  ADD COLUMN stacking_group  VARCHAR(32) DEFAULT NULL COMMENT '叠加组标识（同组券可叠加使用，NULL=不参与叠加）',
  ADD COLUMN funder_type     TINYINT    NOT NULL DEFAULT 1 COMMENT '出资方:1平台 2商家 3联合（M5 成本分摊的判定依据）',
  ADD COLUMN scope_type      TINYINT    NOT NULL DEFAULT 0 COMMENT '适用范围:0全场 1指定品类 2指定商品（P5 规则引擎消费）';

-- ===================================================================
-- ③ coupon_event 补归因列（activity_id / source——写时不采即永久丢失）
-- ===================================================================
ALTER TABLE coupon_event
  ADD COLUMN activity_id BIGINT DEFAULT NULL COMMENT '所属活动id（发放时从模板行冗余；归因维度，NULL=存量事件或无活动）',
  ADD COLUMN source      VARCHAR(32) DEFAULT NULL COMMENT '发放来源:APP/活动/定向/客服/开放API（逐事件事实，不可追补）',
  ADD KEY idx_activity_time (activity_id, occur_time);

-- ===================================================================
-- ④ coupon_share_detail 新表（成本分摊明细——核销事务写入，schema 先建 P5 填数据）
-- ===================================================================
CREATE TABLE coupon_share_detail (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  user_coupon_id      BIGINT         NOT NULL       COMMENT '用户券id',
  user_id             BIGINT         NOT NULL       COMMENT '用户id（分片路由键，与主表同规则）',
  template_id         BIGINT         NOT NULL       COMMENT '模板id',
  usage_no            VARCHAR(64)    NOT NULL       COMMENT '占用单号（幂等锚点——与核销一一配对）',
  order_no            VARCHAR(64)    NOT NULL       COMMENT '订单号',
  order_line_no       VARCHAR(64)    DEFAULT NULL   COMMENT '商品行号（按行分摊时必填，整单分摊为NULL）',
  order_line_amount   BIGINT         DEFAULT NULL   COMMENT '该行商品金额（分）',
  platform_burden     BIGINT         NOT NULL DEFAULT 0 COMMENT '平台承担金额（分）',
  merchant_burden     BIGINT         NOT NULL DEFAULT 0 COMMENT '商家承担金额（分）',
  share_total         BIGINT         NOT NULL COMMENT '本行分摊总额=platform+merchant（守恒校验锚点）',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_line (usage_no, order_line_no),
  KEY idx_tpl_time (template_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '成本分摊明细（核销事务原子写入，sum(share)=discount_amt 守恒）';

-- ===================================================================
-- ⑤ coupon_usage 补 refund_no（退款幂等锚点，先建列后实现——P5 用）
-- coupon_usage 已分片（V6），64 张物理表逐表 ALTER
-- ===================================================================
ALTER TABLE coupon_usage_0 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_1 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_2 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_3 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_4 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_5 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_6 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_7 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_8 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_9 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_10 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_11 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_12 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_13 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_14 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_15 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_16 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_17 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_18 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_19 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_20 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_21 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_22 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_23 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_24 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_25 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_26 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_27 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_28 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_29 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_30 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_31 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_32 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_33 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_34 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_35 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_36 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_37 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_38 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_39 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_40 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_41 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_42 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_43 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_44 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_45 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_46 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_47 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_48 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_49 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_50 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_51 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_52 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_53 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_54 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_55 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_56 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_57 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_58 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_59 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_60 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_61 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_62 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
ALTER TABLE coupon_usage_63 ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT '退款单号（退款冲销的幂等锚点+配对键，P5 实现）';
