-- V44（V6 阶段 2A 第一刀，2026-11-03）：管理面 RBAC 五表 + 权限/角色种子。
--
-- 设计立场（docs/research-2026-10-21/01 §4 2A）：
--   1. 会话只存身份（AdminPrincipal），权限每请求从 DB 重载——"改权限立刻生效"，
--      代价是管理面每个已认证请求一次 join 查询（管理面低频，不做缓存，见 AdminAuthorityRefreshFilter）。
--   2. 全部未分片（走分片规则里的默认库路由，同 open_app/audit_log）。
--   3. 种子用户不在 SQL 里：初始密码来自配置 coupon.security.seed-admin-password
--      （BCrypt 哈希必须应用启动时算，见 AdminSeedInitializer），SQL 只种静态的权限码/角色/绑定。
--
-- 本刀裁剪（第二刀再做的，先留位不建表）：TENANT_ADMIN/TENANT_VIEWER 角色与
-- admin_user_role.tenant_scope 租户隔离列；admin_api_token（PAT）。

-- ===================================================================
-- ① 管理用户：password_hash 带 {bcrypt} 前缀（DelegatingPasswordEncoder 约定）
-- ===================================================================
CREATE TABLE admin_user (
  id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL COMMENT '登录名（唯一）',
  password_hash VARCHAR(100) NOT NULL COMMENT '密码哈希（{bcrypt}前缀 + BCrypt(12)，见 SecurityConfig）',
  display_name  VARCHAR(64)  DEFAULT NULL COMMENT '显示名（运营界面上的人名）',
  enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用（停用后该用户全部会话下一请求即失效）',
  auth_version  BIGINT       NOT NULL DEFAULT 0 COMMENT '认证版本号：改密/强制重登时 +1，旧会话比对不上即失效',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '管理面用户（控制台登录主体；密码 BCrypt，令牌类凭证第二刀引入）';

-- ===================================================================
-- ② 角色 / 权限：码值即授权判断的字符串（hasAuthority('template:write')），
--    角色只按"职责"建、不按页面建（设计红线：页面会改，职责不会）
-- ===================================================================
CREATE TABLE admin_role (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64) NOT NULL COMMENT '角色码（PLATFORM_*；TENANT_* 第二刀）',
  name        VARCHAR(64) NOT NULL COMMENT '角色名（人类可读）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '管理面角色（按职责建模：管理员/操作员/审计员）';

CREATE TABLE admin_permission (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  code        VARCHAR(64)  NOT NULL COMMENT '权限码（资源:动作，如 template:write）',
  description VARCHAR(255) NOT NULL COMMENT '权限说明（给配置权限的人看）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_permission_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '管理面权限码字典（@PreAuthorize 引用的即这里的 code）';

CREATE TABLE admin_user_role (
  user_id     BIGINT UNSIGNED NOT NULL COMMENT 'admin_user.id',
  role_id     BIGINT UNSIGNED NOT NULL COMMENT 'admin_role.id',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, role_id),
  KEY idx_role_user (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '用户-角色绑定（改绑后下一请求即生效：权限每请求重载）';

CREATE TABLE admin_role_permission (
  role_id       BIGINT UNSIGNED NOT NULL COMMENT 'admin_role.id',
  permission_id BIGINT UNSIGNED NOT NULL COMMENT 'admin_permission.id',
  create_time   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '角色-权限绑定（PLATFORM_ADMIN 持全部码；变更即全量生效）';

-- ===================================================================
-- ③ 权限码种子：18 个（显式 id，绑定表按 id 引用，SQL 可直接读出对应关系）
-- ===================================================================
INSERT INTO admin_permission (id, code, description) VALUES
  (1,  'template:read',    '查看券模板'),
  (2,  'template:write',   '创建/发布/调整券模板（写）'),
  (3,  'template:approve', '审批券模板（高危：放行铸造额度）'),
  (4,  'issuance:read',    '查看发放任务/进度'),
  (5,  'issuance:write',   '创建/取消发放任务'),
  (6,  'budget:read',      '查看预算账户/流水'),
  (7,  'budget:write',     '调整预算配置'),
  (8,  'settlement:read',  '查看结算单/账单'),
  (9,  'settlement:write', '调整结算配置/手工调整单'),
  (10, 'risk:read',        '查看风控规则/名单/决策日志'),
  (11, 'risk:write',       '配置风控规则/名单'),
  (12, 'tenant:lifecycle', '租户生命周期操作（开通/停用/注销——影响面跨租户）'),
  (13, 'app:*',            '接入方应用管理全操作（建应用/轮换密钥）'),
  (14, 'audit:read',       '查询审计日志'),
  (15, 'audit:export',     '导出审计日志（数据出域，独立于查询）'),
  (16, 'config:write',     '修改系统配置/开关'),
  (17, 'plan:apply',       '应用开关预案（plan:apply，批量变更系统行为）'),
  (18, 'session:revoke',   '强制下线其它用户（会话治理）');

-- ===================================================================
-- ④ 角色种子：3 个平台角色（TENANT_ADMIN/TENANT_VIEWER 留第二刀）
--    PLATFORM_OPERATOR = 全部 :read/:write 码，减 approve/lifecycle/session:revoke/plan:apply
--    （app:* 与 audit:export 属管理/治理动作而非读写，同样只给 ADMIN）
--    PLATFORM_AUDITOR  = 全部 :read + audit:read/export（审计员能看、能导出，不能改）
--    PLATFORM_APPROVER = template:approve + template:read（审查补齐：设计四平台角色，maker-checker 与运营分离）
-- ===================================================================
INSERT INTO admin_role (id, code, name) VALUES
  (1, 'PLATFORM_ADMIN',    '平台管理员（全部权限）'),
  (2, 'PLATFORM_OPERATOR', '平台运营（读+写，无审批/租户生命周期/会话治理/预案）'),
  (3, 'PLATFORM_AUDITOR',  '平台审计员（全部只读 + 审计查询/导出）'),
  (4, 'PLATFORM_APPROVER', '平台审批人（审批券模板 + 只读——与运营分离，maker-checker）');

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT 1, p.id FROM admin_permission p;                          -- ADMIN：全量

INSERT INTO admin_role_permission (role_id, permission_id) VALUES
  (4, 3),   -- APPROVER：template:approve（审批）
  (4, 1);   --           template:read（审批前要看详情）

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT 2, p.id FROM admin_permission p
WHERE p.code IN ('template:read','template:write',
                 'issuance:read','issuance:write',
                 'budget:read','budget:write',
                 'settlement:read','settlement:write',
                 'risk:read','risk:write',
                 'audit:read','config:write');                   -- OPERATOR：读+写

INSERT INTO admin_role_permission (role_id, permission_id)
SELECT 3, p.id FROM admin_permission p
WHERE p.code IN ('template:read','issuance:read','budget:read',
                 'settlement:read','risk:read',
                 'audit:read','audit:export');                   -- AUDITOR：只读+审计
