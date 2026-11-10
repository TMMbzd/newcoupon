-- V50（V6 5B 刀 A 审查 M1，2026-11-10）：ops:drain 权限码种子。
--
-- 背景：/internal/admin/drain|undrain（摘流量/回流量）此前只有认证（旧令牌或会话）
-- 没有 @PreAuthorize 终审——任何有效会话（含只读 PLATFORM_AUDITOR）都能逐台摘光
-- 全部实例（全站无声 outage）。本迁移只加种子数据、不改表结构（审查边界）。
--
-- 授予裁定：
--   PLATFORM_ADMIN     —— 给。V44 的设计是"ADMIN 持全部码"，但 V44 那条
--                         `SELECT 1, p.id FROM admin_permission` 全量绑定只在 V44 执行过，
--                         新码必须显式补授。
--   PLATFORM_OPERATOR  —— 给。理由：滚动重启/维护是运营日常，drain 不改数据、
--                         可逆（undrain / 重启自动恢复）、有审计留痕，影响面与
--                         OPERATOR 已持有的 config:write 同量级；V44 划给 ADMIN-only 的
--                         是审批/租户生命周期/会话治理/预案这类治理动作，摘流量不属于。
--   PLATFORM_AUDITOR / PLATFORM_APPROVER —— 不给。只读职责；这正是本权限码要挡的
--                         "只读角色摘光集群"路径。

INSERT INTO admin_permission (id, code, description) VALUES
  (19, 'ops:drain', '实例摘流量/回流量（drain/undrain——滚动重启与维护时把实例请出 LB 池）');

INSERT INTO admin_role_permission (role_id, permission_id) VALUES
  (1, 19),   -- PLATFORM_ADMIN
  (2, 19);   -- PLATFORM_OPERATOR
