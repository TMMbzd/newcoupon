-- Redis gate 修复队列（V36）。
-- 为什么放在数据库而不是 Redis：Redis 正是可能损坏的组件，修复任务本身不能依赖它。
-- 请求路径只在"需要修但没修成/没抢到单飞许可"时登记一行；后台任务按 SKIP LOCKED 领取。
CREATE TABLE coupon_issue_gate_repair (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type    TINYINT      NOT NULL COMMENT '0=模板级 1=用户级',
    template_id   BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL DEFAULT 0,
    stat_day      DATE         NOT NULL DEFAULT '1970-01-01' COMMENT '日限归属日；模板级用哨兵日期',
    error_code    INT          NOT NULL COMMENT '触发修复的 gate 返回码',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2完成 3死信',
    attempts      INT          NOT NULL DEFAULT 0,
    next_retry_time DATETIME(3) NOT NULL,
    lease_owner   VARCHAR(64)  DEFAULT NULL,
    lease_until   DATETIME(3)  DEFAULT NULL,
    last_error    VARCHAR(255) DEFAULT NULL,
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_gate_repair_scope (scope_type, template_id, user_id, stat_day, error_code),
    KEY idx_gate_repair_due (status, next_retry_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Redis gate 修复队列';
