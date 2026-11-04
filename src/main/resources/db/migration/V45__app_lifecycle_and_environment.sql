-- V45（V6 阶段 2B 第一刀，2026-11-04）：租户生命周期状态机 + 沙箱环境。
--
-- 设计依据：docs/research-2026-10-21/01 §4 2B（裁剪版，裁剪裁定见各列注释）。
-- 内容：① open_app 加 5 列（environment / lifecycle_status / closed_at / retention_until / legal_hold）
--       ② 新表 open_app_lifecycle_event（迁移留痕）
--
-- 存量兼容：全部新列带默认值——存量行（含内置应用 '1'）自然成为 environment=PROD、
-- lifecycle_status=ACTIVE，既有开放面调用零感知（本刀不动 731 例的任何行为前提）。
--
-- 裁剪登记（设计原文有、本刀不做，见 docs/runbook/发放批次.md §6）：
--   · retention_until 只写不做——分级清理自动化不建，到期清理是 runbook 手动事项；
--   · NDJSON/manifest 全量导出管道不建——导出是一次性 JSON（app 行 + 模板清单 + 计数）；
--   · 租户自助 lifecycle 端点不建——生命周期是平台运营动作，只走内部面 /api。

-- ===================================================================
-- ① open_app 加列：环境轴 + 生命周期轴（两条正交的轴）
--    environment 回答"这是什么环境"（资金边界守卫的依据）；
--    lifecycle_status 回答"这个租户走到哪一步了"（开放面可否调用的依据）。
--    既有 status（0停用/1启用）是 S1 的运营开关，保留不动——三者在
--    OpenApiAuthFilter 里按"存在 → status → lifecycle"的顺序共同裁决。
-- ===================================================================
ALTER TABLE open_app
  ADD COLUMN environment      VARCHAR(8)  NOT NULL DEFAULT 'PROD'
      COMMENT '环境：PROD 生产 / SANDBOX 沙箱（沙箱在账单/退款/预算三处被 EnvironmentGuard 拒绝）',
  ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
      COMMENT '生命周期：DRAFT/PENDING_REVIEW/SANDBOX_ACTIVE/ACTIVE/SUSPENDED/CLOSING/CLOSED/DELETED（显式迁移表管控，见 AppLifecycle）',
  ADD COLUMN closed_at        DATETIME(3) NULL
      COMMENT '注销完成时刻（confirm_close 迁移写入）',
  ADD COLUMN retention_until  DATE NULL
      COMMENT '数据保留截止（confirm_close 按 180 天默认保留期写入；到期清理是手动事项，无自动化）',
  ADD COLUMN legal_hold       TINYINT     NOT NULL DEFAULT 0
      COMMENT '法务保留：1=禁止删除（审计/诉讼冻结时置 1，清空后人工解除才允许 delete）';

-- ===================================================================
-- ② 生命周期事件表：每次迁移一行（含导出这类"非迁移但一次性"的动作）
--    from_status/to_status 对导出动作记录相同值（from==to 表示"没有状态变化"）。
-- ===================================================================
CREATE TABLE open_app_lifecycle_event (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id      VARCHAR(32)  NOT NULL COMMENT '接入方标识',
  from_status VARCHAR(20)  NOT NULL COMMENT '迁移前生命周期状态（导出类事件=当前状态）',
  to_status   VARCHAR(20)  NOT NULL COMMENT '迁移后生命周期状态（导出类事件=当前状态）',
  action      VARCHAR(32)  NOT NULL COMMENT '动作：submit_review/approve_sandbox/activate/suspend/resume/close/confirm_close/delete/data_export',
  operator    VARCHAR(64)  NOT NULL COMMENT '操作者（会话用户名；旧令牌/系统触发记 admin）',
  reason      VARCHAR(255) DEFAULT NULL COMMENT '操作原因（运营填写，审计追溯用）',
  create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_app_time (app_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '租户生命周期事件（V6 2B：迁移与导出留痕；导出动作的存在性=一次性闸门的判据）';
