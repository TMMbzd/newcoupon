-- V38（2026-10-25，layout-version Slice B）：删除旧唯一键。
--
-- V37 的长注释说明过：uk_tpl_shard(template_id, shard_no) 保留一个发布周期，
-- 让 V37-aware 代码上线前旧实例仍能工作。本迁移之后，同一模板才允许
-- 多个 layout_version 使用相同的 shard_no —— 这是离线 reshard 的硬前提。
--
-- 门禁：StockReshardService 在每次 reshard 前查 information_schema.statistics，
-- 若本索引仍存在则拒绝执行（滚动发布时新代码先于迁移启动的窗口）。

ALTER TABLE coupon_stock_shard DROP INDEX uk_tpl_shard;
