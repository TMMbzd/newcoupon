-- V8（ADR-0009 守恒式迁移，2026-09-16）：两项配套 DDL。
-- ① idx_tpl(template_id)：守卫④（账实互证 COUNT）与 T+1 审计的 COUNT 现为全表扫——
--    既是性能尖峰也是秒级陈旧窗来源（ADR-0009 §5）。64 分表逐张补索引。
-- ② stock_drift_log 适配：+redis_void（守恒式第四态读数）、+disposition（处置回填：
--    true/false 误报/manual 人工——v3 修订8）；redis_issued 允许 NULL 并注废弃
--    （issued 键族随守恒迁移删除，历史行保留原值不清洗）。

ALTER TABLE user_coupon_0 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_1 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_2 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_3 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_4 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_5 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_6 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_7 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_8 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_9 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_10 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_11 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_12 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_13 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_14 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_15 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_16 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_17 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_18 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_19 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_20 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_21 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_22 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_23 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_24 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_25 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_26 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_27 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_28 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_29 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_30 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_31 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_32 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_33 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_34 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_35 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_36 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_37 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_38 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_39 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_40 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_41 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_42 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_43 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_44 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_45 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_46 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_47 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_48 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_49 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_50 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_51 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_52 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_53 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_54 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_55 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_56 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_57 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_58 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_59 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_60 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_61 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_62 ADD KEY idx_tpl (template_id);
ALTER TABLE user_coupon_63 ADD KEY idx_tpl (template_id);

ALTER TABLE stock_drift_log
    ADD COLUMN redis_void BIGINT NULL COMMENT '守恒式第四态（已撤销）读数——issued 废弃后 dbi/void 并列' AFTER redis_dbi,
    ADD COLUMN disposition VARCHAR(16) NULL COMMENT '处置回填：true 真漂移/false 误报/manual 人工（v3 修订8）' AFTER detail,
    MODIFY COLUMN redis_issued BIGINT NULL COMMENT '【废弃 2026-09-16】守恒迁移删除 issued 键族，历史行保留原值';
