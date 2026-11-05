-- ===================================================================
-- V49（V6 阶段 4B 审查 M2，2026-11-05）：预算账本流水加 (account_id, create_time) 复合索引
--
-- 0) 背景：4B 的预算燃烧信号（R_BUDGET_BURN）每次领券/兑换决策都要 COUNT 该活动
--    预算账户的"当日"流水行数（CouponBudgetLedgerMapper.countCreatedSince）。
--    V42 建表时只有 idx_account 单列索引——create_time 不在索引里，COUNT 退化为
--    "沿 idx_account 扫该账户全部历史行再逐行过滤时间"：
--    ① 只追加表只增不减，扫描量随账户历史单调增长（O(全史)）；
--    ② 攻击者每领一次券既触发一次扫描又给账本添两行——二次方自增强；
--    ③ 慢扫描推高租户 mysql 熔断的慢调用统计，一条 REVIEW 信号规则变成领券可用性的
--       单点放大器（可用性防线反过来被信号规则打死，方向反了）。
-- 1) 修复：复合索引 (account_id, create_time) 让"当日"谓词变成索引上的 range scan，
--    扫描量=当日行数（有界），与账户历史解耦。
-- 2) 只加索引不改结构/不改数据：无回填、无锁表风险（小表 + InnoDB online DDL）。
--    V42 已合入 main 不可改，按 Flyway 纪律走新迁移。
-- ===================================================================

ALTER TABLE coupon_budget_ledger
  ADD KEY idx_account_time (account_id, create_time);
