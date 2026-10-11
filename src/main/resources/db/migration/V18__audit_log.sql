-- V18: 操作审计日志表（第 1 环 1-1 项——"动钱先审计"）
-- 设计要点：
--   1. 单表不分片：审计是平台级横切关注，不属于任何用户维度
--   2. 自增 ID：哈希链需要严格有序，自增保证写入顺序
--   3. 哈希链两列：record_hash（本条内容的 SHA-256）+ prev_hash（前条的 record_hash）
--   4. 六要素列：app_id/user_id（谁）、created_at（何时）、ip（何处）、
--      action/resource_type/resource_id（何事）、before/after_snapshot（前后值）、trace_id（链路）
--   5. 审计表权限隔离：通过独立的 AdminTokenFilter 保护管理面，审计表只写不改不删
--      （"只写不改不删"由应用层保证——代码中无 UPDATE/DELETE 操作此表的路径）

CREATE TABLE audit_log (
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键（自增——哈希链依赖严格有序）',
    app_id           VARCHAR(32)   NOT NULL        COMMENT '谁：调用方 appId（来自签名认证）',
    user_id          BIGINT        DEFAULT NULL    COMMENT '谁：终端用户 ID（从请求参数提取，可为空）',
    trace_id         VARCHAR(64)   NOT NULL        COMMENT '链路追踪 ID（贯穿一次调用的全部日志与审计）',
    action           VARCHAR(128)  NOT NULL        COMMENT '何事：操作类型（如 COUPON_CALCULATE / COUPON_CONFIRM）',
    resource_type    VARCHAR(64)   DEFAULT NULL    COMMENT '何事：资源类型（如 COUPON / TEMPLATE / USAGE）',
    resource_id      VARCHAR(64)   DEFAULT NULL    COMMENT '何事：资源标识（如券编号，从入参提取）',
    before_snapshot  TEXT                          COMMENT '前值快照：操作前的状态/入参（JSON 序列化）',
    after_snapshot   TEXT                          COMMENT '后值快照：操作后的状态/出参（JSON 序列化）',
    ip               VARCHAR(45)   DEFAULT NULL    COMMENT '何处：来源 IP（IPv4 最长 15，IPv6 最长 45）',
    result_code      INT           DEFAULT 0       COMMENT '操作结果码（0=成功，非零=业务码或 500=异常）',
    prev_hash        VARCHAR(64)   NOT NULL        COMMENT '哈希链：前一条记录的 record_hash（首条=GENESIS）',
    record_hash      VARCHAR(64)   NOT NULL        COMMENT '哈希链：本条全部字段+prev_hash 的 SHA-256（防篡改）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '何时：操作时间',
    PRIMARY KEY (id),
    KEY idx_app_action_time (app_id, action, created_at),
    KEY idx_trace (trace_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作审计日志（只写不改不删；哈希链防篡改）';
