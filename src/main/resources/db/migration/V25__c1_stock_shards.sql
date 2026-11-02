-- V25（ADR-0018 C1，2026-10-22）：库存模型从“Redis 权威 + 16 桶 + 世代”迁移到
-- “DB 固定分片条件更新”。本迁移只做加法，不动旧表和旧数据，保证回滚窗口。
--
-- 设计要点：
-- 1) coupon_stock_shard 是 C1 的唯一库存账本：每个模板固定 N 行，
--    N_eff = min(stock_shard_count, total_count)，Σquota = total_count。
-- 2) 热路径只执行一条条件 UPDATE：
--      UPDATE coupon_stock_shard
--         SET used = used + 1, version = version + 1
--       WHERE template_id = ? AND shard_no = ?
--         AND status = 0 AND used < quota;
--    影响 0 行 = 该片满/停发，换下一片；全部 0 行 = 售罄/停发。
-- 3) coupon_template.stock_model：0=legacy（旧 Redis + stock_bucket），
--    1=C1，2=迁移中（冻结迁移动画；新请求不再进入）。
-- 4) 旧 stock_bucket 保留，供迁移期回滚和存量模板只读；C1 模板不写它。

ALTER TABLE coupon_template
  ADD COLUMN stock_model TINYINT NOT NULL DEFAULT 0
    COMMENT '库存模型：0=legacy(Redis+stock_bucket) 1=C1(DB 固定分片) 2=迁移中'
    AFTER allocated,
  ADD COLUMN stock_shard_count INT NOT NULL DEFAULT 16
    COMMENT 'C1 分片数 N；实际 N_eff=min(N,total_count)'
    AFTER stock_model,
  ADD COLUMN stock_layout_version INT NOT NULL DEFAULT 1
    COMMENT 'C1 布局版本；改 N 时冻结→抽干→新布局，不在热路径改'
    AFTER stock_shard_count;

CREATE TABLE coupon_stock_shard (
  id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  template_id   BIGINT       NOT NULL COMMENT '模板id',
  shard_no      INT          NOT NULL COMMENT '分片号 0..N_eff-1',
  quota         BIGINT       NOT NULL COMMENT '本片额度；Σquota=total_count',
  used          BIGINT       NOT NULL DEFAULT 0 COMMENT '本片已用；条件更新 used<quota 保证不超发',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0活跃 1停发/冻结（已发券不受影响）',
  version       BIGINT       NOT NULL DEFAULT 0 COMMENT '条件更新版本号（观测/后续 CAS 用）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_shard (template_id, shard_no),
  KEY idx_tpl_status (template_id, status),
  CONSTRAINT chk_shard_quota_nonneg CHECK (quota >= 0),
  CONSTRAINT chk_shard_used_range CHECK (used >= 0 AND used <= quota)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = 'C1 库存分片账本（DB 是唯一权威；Redis 不存库存）';

-- 迁移期只读的存量模板保持 stock_model=0；本迁移不把旧模板自动翻成 C1。
-- 逐模板迁移由 0C 的迁移工具执行：冻结→排空→快照 Σused→建分片→翻 model=1。
