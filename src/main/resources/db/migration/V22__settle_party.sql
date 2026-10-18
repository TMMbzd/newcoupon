-- ===================================================================
-- V22 环 1：结算主体可选 + 行级归属（2026-10-18）
--
-- 1) coupon_template 补 settle_party_id：
--    平台券可空（租户级，按订单行主体解析）；商家/共担券必填。
--    存量商家/共担模板没有真实主体，统一置 __default__ 并留给应用层告警
--    （LEGACY_PARTY_MISSING），不猜真实商家。
-- 2) coupon_share_detail / coupon_refund_detail 补 app_id + settle_party_id：
--    核销/退款按行记录“这笔优惠算到谁头上、和谁结算”。
--    settle_party_id 非空，默认哨兵 __default__（未归属/无商家概念）。
-- 3) coupon_refund_order 补 app_id：退款单按租户查询/隔离。
-- 4) 索引：按主体日汇总、按模板+主体查退款。
--
-- 明确不做：
--   - 不动 64 张 coupon_usage 分表（主体在分账/退款明细，不进入占用单）；
--   - 不在 V22 加 pricing_id（那是环 2 的 V23）；
--   - 不删/不改旧列；新列有默认值，回滚可停用新路径。
-- ===================================================================

ALTER TABLE coupon_template
    ADD COLUMN settle_party_id VARCHAR(64) DEFAULT NULL
        COMMENT '结算主体（可空=平台券租户级；商家/共担必填）'
        AFTER budget_ratio;

-- 存量商家/共担模板没有真实主体：置哨兵 + 应用层告警；平台券保持 NULL（租户级）
UPDATE coupon_template
   SET settle_party_id = '__default__'
 WHERE settle_party_id IS NULL
   AND funder_type IN (2, 3);

ALTER TABLE coupon_share_detail
    ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1'
        COMMENT '租户归属（从模板回填；新写入取券的 app_id）'
        AFTER template_id,
    ADD COLUMN settle_party_id VARCHAR(64) NOT NULL DEFAULT '__default__'
        COMMENT '结算主体（行级；平台券/无商家产品为 __default__）'
        AFTER app_id;

UPDATE coupon_share_detail s
  JOIN coupon_template t ON s.template_id = t.id
   SET s.app_id = t.app_id;

ALTER TABLE coupon_refund_detail
    ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1'
        COMMENT '租户归属（从分账行复制）'
        AFTER template_id,
    ADD COLUMN settle_party_id VARCHAR(64) NOT NULL DEFAULT '__default__'
        COMMENT '结算主体（从分账行复制，不读退款请求）'
        AFTER app_id;

UPDATE coupon_refund_detail d
  JOIN coupon_template t ON d.template_id = t.id
   SET d.app_id = t.app_id;

ALTER TABLE coupon_refund_order
    ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1'
        COMMENT '租户归属（从券/模板带出，供按租户查询）'
        AFTER template_id;

UPDATE coupon_refund_order o
  JOIN coupon_template t ON o.template_id = t.id
   SET o.app_id = t.app_id;

-- 按主体日汇总/查询
ALTER TABLE coupon_share_detail
    ADD INDEX idx_share_party (app_id, settle_party_id, create_time),
    ADD INDEX idx_share_tpl_party (template_id, settle_party_id, create_time);

ALTER TABLE coupon_refund_detail
    ADD INDEX idx_refund_detail_party (app_id, settle_party_id, create_time),
    ADD INDEX idx_refund_detail_tpl_party (template_id, settle_party_id, create_time);

ALTER TABLE coupon_refund_order
    ADD INDEX idx_refund_order_app (app_id, create_time);
