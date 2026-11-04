-- V46: 审计 actor 四列 + 审计导出任务表（V6 2C）。
--
-- actor 三列解决的可观测缺口：存量 audit_log 只能回答"哪个 appId 调的、业务用户是谁"，
-- 回答不了"是哪个管理员在哪个会话里干的"——管理面动作此前统一记 appId='SYSTEM'，
-- 六要素里的"谁"对内部操作是空白的。
--   actor_type 四值：ADMIN（管理面会话）/ TENANT（开放面签名应用）/ USER（C 端预留，
--                    当前 C 端调用审计在 open_api_audit 专表）/ SYSTEM（后台任务）。
--   存量行为 NULL——历史行不回填：回填要么编造（SYSTEM 不一定对），要么逐行考据（做不到），
--   NULL 本身就是诚实的"此行写入时没有 actor 概念"。
--   actor_id：ADMIN=AdminPrincipal.userId 字符串 / TENANT=appId / SYSTEM='SYSTEM'。
--   session_id：管理面会话**指纹**（SHA-256 前 16 hex，AuditActorResolver.fingerprint 统一转换），
--   **不是凭据原值**——session 原值就是 SESSION Cookie 本身，审计行可被持 audit:read 的
--   低权限角色查到，原值入库=把高权限账号的会话凭据递给低权限角色（劫持提权链）；
--   指纹可关联同会话多行、区分不同会话，还原凭据不可行。开放面与后台任务为空。
--
-- hash_version：哈希算法版本。存量行=1（新列不参与哈希拼接，链不重算——重算等于
-- 平台自己改写全部历史哈希，恰好毁掉哈希链要防的东西）；新写入=2（actor 三列参与
-- record_hash 拼接，改 actor 即篡改，可被 verify 检出）。校验按行的 hash_version 选算法。
--
-- 索引裁定（宁少勿多）：
--   只加 idx_actor_time (actor_type, actor_id, created_at)——查询端点的
--   actor 过滤没有既有索引可用；而 action 过滤始终伴随强制时间窗（≤31 天），
--   既有 idx_created 已把扫描收窄到窗口内，idx_action_time 留给行数失控时再议。

ALTER TABLE audit_log
    ADD COLUMN actor_type   VARCHAR(16) DEFAULT NULL COMMENT 'actor 类型：ADMIN/TENANT/USER(预留)/SYSTEM；存量行为 NULL（写入时无 actor 概念）',
    ADD COLUMN actor_id     VARCHAR(64) DEFAULT NULL COMMENT 'actor 标识：ADMIN=管理员 userId / TENANT=appId / SYSTEM=SYSTEM',
    ADD COLUMN session_id   VARCHAR(64) DEFAULT NULL COMMENT '管理面会话指纹（SHA-256 前 16 hex，非凭据原值；开放面/后台任务为空）',
    ADD COLUMN hash_version TINYINT     NOT NULL DEFAULT 1 COMMENT 'record_hash 算法版本：1=不含 actor 列（存量），2=含 actor 列（V46 起）',
    ADD KEY idx_actor_time (actor_type, actor_id, created_at);

-- 审计导出任务表（异步任务模式）：创建=PENDING，构建线程跑 RUNNING→DONE/FAILED，
-- 文件本体落 LONGTEXT（导出窗口 ≤31 天 + 行数上限兜底，LONGTEXT 4GB 上界足够），
-- 下载一次性（downloaded_at 非空即拒），过期行由下一次创建任务时惰性清理（无独立调度）。
CREATE TABLE audit_export_job (
    id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    job_no        VARCHAR(64)  NOT NULL COMMENT '任务号（返回给调用方轮询/下载用）',
    requested_by  VARCHAR(64)  NOT NULL COMMENT '发起人（管理员 userId 或 legacy 令牌标识）',
    filters_json  TEXT         COMMENT '导出过滤条件快照（JSON：时间窗+各过滤字段）',
    status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/DONE/FAILED',
    row_count     BIGINT       DEFAULT NULL COMMENT '导出行数（DONE 时回填，manifest 同源）',
    sha256        CHAR(64)     DEFAULT NULL COMMENT '数据段（表头+数据行）的 SHA-256，manifest 同源',
    file_content  LONGTEXT     COMMENT 'CSV 本体（UTF-8 BOM + manifest 行 + 表头 + 数据行）',
    error_message VARCHAR(512) DEFAULT NULL COMMENT 'FAILED 时的原因（如超行数上限）',
    expires_at    DATETIME     NOT NULL COMMENT '过期时刻（过期后不可下载，创建新任务时惰性清理）',
    downloaded_at DATETIME     DEFAULT NULL COMMENT '首次下载时刻；非空=已下载，再下载拒绝（一次性）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_no (job_no),
    KEY idx_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计导出任务（异步构建，一次性下载，过期清理）';
