-- V30（2026-10-23）：C1 已是唯一库存模型，stock_model 不再有语义。
-- 本地/新环境直接删除；已有数据按“旧方案废弃”处理，不做回填/兼容。
ALTER TABLE coupon_template DROP COLUMN stock_model;
