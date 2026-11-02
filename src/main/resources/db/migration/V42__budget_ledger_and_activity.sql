-- V42（V6 阶段 1C，2026-11-03）：预算账本三表 + coupon_activity 激活 + 模板单张成本。
--
-- 设计立场：预算是"钱的账本"，与库存（张数的账本，coupon_stock_shard 条件更新）同构但
-- 不同源——库存权威在分片行，预算权威在账户行的条件更新（reserved+consumed+新增<=total）。
-- 预算全部挂在活动级 budget_enabled 开关后，默认 0：不开闸门，发放链路不多一次预算 SQL、
-- 行为与 1C 之前完全一致（零变化回滚保证）。
--
-- 三张表的分工（记账三段式，行业通用模型：账户/预占/流水）：
--   account     = 余额快照（钱的当前状态，可变行，条件更新并发安全）
--   reservation = 预占凭证（一次业务意图一行：chunk/单人/兑换码；状态机 0→1/2/3）
--   ledger      = 只追加流水（每次账户变动一条，带 balance_after 快照——对账的回放源）
--
-- 时钟纪律（同源原则）：expire_time 由应用钟写入（MyBatis 插入 PO 字段），
-- 过期回收的判据也用应用钟参数传入，两侧同源；update_time/create_time 用 DB NOW(3)，
-- 只做展示不参与判定。

-- ===================================================================
-- ① coupon_activity 激活（V9 建表、一直无 Service）：补归属/开关/创建人/版本四列
-- ===================================================================
ALTER TABLE coupon_activity
  ADD COLUMN app_id        VARCHAR(32) NOT NULL DEFAULT '1'
      COMMENT '租户/接入方（归属链与模板一致；内部面创建=内置应用 1）',
  ADD COLUMN budget_enabled TINYINT    NOT NULL DEFAULT 0
      COMMENT '预算闸门开关：0=关（发放不受预算约束，1C 之前的行为），1=开（同步领取/兑换码/批量 chunk 预占）',
  ADD COLUMN created_by    VARCHAR(64) DEFAULT NULL
      COMMENT '创建人（审计用）',
  ADD COLUMN version       BIGINT      NOT NULL DEFAULT 0
      COMMENT '乐观锁版本';

-- ===================================================================
-- ② 模板单张成本（建券时算好写死，发放时不再按类型现场推导）
-- ===================================================================
ALTER TABLE coupon_template
  ADD COLUMN budget_unit_cost BIGINT NOT NULL DEFAULT 0
      COMMENT '单张预算成本（分）：满减/无门槛=面额 face_value，满折=优惠上限 discount_cap；预算按 张数×本值 计（1C）';

-- ===================================================================
-- ③ 预算账户：一行 = 一个（范围，周期）的钱袋子。MVP 组合只有 活动级(1)+总量(1)
-- ===================================================================
CREATE TABLE coupon_budget_account (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  scope_type  TINYINT     NOT NULL COMMENT '范围:1活动（2租户/3模板列占位，本刀不用）',
  scope_id    BIGINT      NOT NULL COMMENT '范围主体 id（scope_type=1 时=活动 id）',
  app_id      VARCHAR(32) NOT NULL COMMENT '租户/接入方（从活动行冗余，对账与归属用）',
  period_type TINYINT     NOT NULL DEFAULT 1 COMMENT '周期:1总量（2日/3月列占位——period_key 自然切换，不每天清零）',
  period_key  VARCHAR(16) NOT NULL DEFAULT 'TOTAL' COMMENT '周期标识：总量恒 TOTAL；日/月预算为 2026-11-03 这类键',
  total       BIGINT      NOT NULL COMMENT '预算总额（分）——首建取活动 budget_amount，之后以本行为权威',
  reserved    BIGINT      NOT NULL DEFAULT 0 COMMENT '已预占未终态（分）',
  consumed    BIGINT      NOT NULL DEFAULT 0 COMMENT '已确认消耗（分）',
  version     BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本（条件更新时同步自增）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope_period (scope_type, scope_id, period_type, period_key),
  CONSTRAINT chk_budget_account_scope CHECK (scope_type BETWEEN 1 AND 3),
  CONSTRAINT chk_budget_account_period CHECK (period_type BETWEEN 1 AND 3),
  CONSTRAINT chk_budget_account_nonneg CHECK (
      total > 0 AND reserved >= 0 AND consumed >= 0),
  CONSTRAINT chk_budget_account_pool CHECK (reserved + consumed <= total)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '预算账户（余额快照；条件更新 reserved+consumed+amount<=total 是并发安全锚点）';

-- ===================================================================
-- ④ 预占凭证：一次业务意图一行。状态机 0 预留 →1 确认（终态）；
--    0 →2 释放（终态）；0 →3 过期回收（终态，reaper 兜底）。
--    同一 biz 允许多行：批量任务 retry-failed 重开 chunk 时按重试张数追加新预占。
-- ===================================================================
CREATE TABLE coupon_budget_reservation (
  id              BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  reserve_no      VARCHAR(40)  NOT NULL COMMENT '预占单号=BR+雪花ID（对外与流水引用它而非自增id）',
  account_id      BIGINT       NOT NULL COMMENT '所属预算账户 id',
  biz_type        TINYINT      NOT NULL COMMENT '业务来源:1批量chunk 2单人领取 3兑换码',
  biz_id          VARCHAR(64)  NOT NULL COMMENT '业务定位键：chunk={taskNo}-{chunkNo}，单人/兑换码={requestId}',
  amount          BIGINT       NOT NULL COMMENT '预占额（分）',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0预留 1确认 2释放 3过期回收',
  confirmed_amount BIGINT      NOT NULL DEFAULT 0 COMMENT '已确认额（分，累进；终态时=实际消耗）',
  expire_time     DATETIME(3)  NOT NULL COMMENT '预占过期时刻（应用钟写入；超时未终态由 reaper 回收回池）',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_reserve_no (reserve_no),
  KEY idx_biz (biz_type, biz_id),
  KEY idx_status_expire (status, expire_time),
  CONSTRAINT chk_budget_res_status CHECK (status BETWEEN 0 AND 3),
  CONSTRAINT chk_budget_res_amount CHECK (amount > 0 AND confirmed_amount >= 0
      AND confirmed_amount <= amount)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '预算预占凭证（chunk 级/单人级；过期回收兜底崩溃残留）';

-- ===================================================================
-- ⑤ 账本流水：只追加，永不 UPDATE/DELETE。每条带变动后的 (reserved,consumed) 快照，
--    同账户行锁保证流水严格有序 → 对账=每账户末条快照 vs 账户行 + 开口预占合计守恒。
-- ===================================================================
CREATE TABLE coupon_budget_ledger (
  id              BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键（同账户内递增=时序）',
  account_id      BIGINT      NOT NULL COMMENT '所属预算账户 id',
  reservation_id  BIGINT      NOT NULL COMMENT '引发本次变动的预占凭证 id（无凭证的调整记 0）',
  op              VARCHAR(8)  NOT NULL COMMENT 'RESERVE 预留 / CONFIRM 确认转移 / RELEASE 释放 / EXPIRE 过期回收',
  amount          BIGINT      NOT NULL COMMENT '本次变动的有符号金额（分）：预留+/确认+/释放-/回收-',
  reserved_after  BIGINT      NOT NULL COMMENT '变动后账户 reserved 快照（分）',
  consumed_after  BIGINT      NOT NULL COMMENT '变动后账户 consumed 快照（分）',
  note            VARCHAR(255) DEFAULT NULL COMMENT '备注（业务定位键/差额说明，排障用）',
  create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_account (account_id),
  CONSTRAINT chk_budget_ledger_op CHECK (op IN ('RESERVE','CONFIRM','RELEASE','EXPIRE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '预算账本流水（只追加；balance_after 快照是对账回放的依据）';
