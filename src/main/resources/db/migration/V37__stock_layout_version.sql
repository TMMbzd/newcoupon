-- V37（2026-10-25，layout-version Slice A）：库存布局版本化。
--
-- 1) layout_version 标记分片行属于哪个不可变布局；只有
--    coupon_template.stock_layout_version 指向的版本可扣减。
-- 2) reserved 为 Phase 2 TCC 预约预留；Phase 1 恒 0（现在加列，避免 Phase 2 二次 ALTER）。
-- 3) 新唯一键 (template_id, layout_version, shard_no) 允许同一模板多版本共存；
--    **保留 uk_tpl_shard(template_id, shard_no)** 一个发布周期，V37-aware 代码上线前
--    旧实例仍能按旧键工作。第一次 reshard 前由 V38 删除旧键（Slice B）。
-- 4) DEFAULT 1 回填现有行；现有模板的 stock_layout_version 都是 1。

ALTER TABLE coupon_stock_shard
  ADD COLUMN layout_version INT NOT NULL DEFAULT 1
    COMMENT '库存布局版本；仅 template.stock_layout_version 对应版本可扣减'
    AFTER template_id,
  ADD COLUMN reserved BIGINT NOT NULL DEFAULT 0
    COMMENT 'Phase2 TCC 预占；Phase1 恒 0'
    AFTER used,
  ADD UNIQUE KEY uk_tpl_layout_shard (template_id, layout_version, shard_no),
  ADD KEY idx_tpl_layout_status (template_id, layout_version, status);
