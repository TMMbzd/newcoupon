-- =====================================================================
-- V2: 阶段1——限领通用化 + 日限配置（ADR-0006）
-- 变更1：coupon_template 增加日限两列（0=不限）
-- 变更2：user_coupon 的限领约束从"每人1张"(uk_user_template)升级为
--        "每人N张"(uk_user_seq)：seq = 同用户同模板内的发放序号(1..N)，
--        (user_id, template_id, seq) 唯一 ⇒ 物理上不可能超过 N 行。
--        Redis 用户计数即序号来源；Redis 计数丢失时序号重复会被此索引撞掉——
--        DB 始终是限领的最后防线（不依赖 Redis 的正确性）。
-- 规范：已合入的脚本只增不改（ADR-0002）；历史数据先回填再建索引。
-- =====================================================================

ALTER TABLE coupon_template
  ADD COLUMN daily_limit            BIGINT NOT NULL DEFAULT 0 COMMENT '批次每日发放上限，0=不限',
  ADD COLUMN per_user_daily_limit   INT    NOT NULL DEFAULT 0 COMMENT '每人每日限领张数，0=不限';

ALTER TABLE user_coupon
  ADD COLUMN seq INT NOT NULL DEFAULT 1 COMMENT '同用户同模板内的发放序号(1..N)，来自Redis用户计数';

-- 阶段0历史数据回填：当时限领恒为1，全部序号=1
UPDATE user_coupon SET seq = 1;

ALTER TABLE user_coupon
  DROP INDEX uk_user_template,
  ADD UNIQUE KEY uk_user_seq (user_id, template_id, seq);
