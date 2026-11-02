-- V29（2026-10-23）：旧 Redis 桶/世代模型已彻底废弃，本地/新环境不再保留 stock_bucket。
-- 这里只删表；历史库如需保留审计，请先自行归档（本仓库当前按“旧数据不要”处理）。
DROP TABLE IF EXISTS stock_bucket;
