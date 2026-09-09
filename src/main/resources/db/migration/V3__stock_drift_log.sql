-- V3（ADR-0008）：库存恒等式漂移日志表。
-- 为什么需要：恒等式 issued − dbi − pend ≡ 0 一旦打破，说明有变更逃出了
-- 复式记账的原子配对（崩溃/程序错误/人工干预）。这类事件必须留痕：
-- ①告警的原始读数（复现与归因）②修复动作与判定依据（审计）③连续漂移的频次（升级人工的依据）。
-- 只插入、不更新——日志是追加型事实，不是可变状态。

CREATE TABLE stock_drift_log (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    template_id   BIGINT       NOT NULL COMMENT '模板 id',
    check_time    DATETIME(3)  NOT NULL COMMENT '发现漂移的检查时刻',

    -- 恒等式三本账的 Redis 读数（快照，供事后归因）
    redis_issued  BIGINT       NOT NULL COMMENT 'Redis 侧已发放（借方）',
    redis_dbi     BIGINT       NOT NULL COMMENT 'Redis 侧 DB 确认镜像（贷方）',
    redis_pending BIGINT       NOT NULL COMMENT '在途令牌数（清理过期后）',

    -- 修复决策依据的 DB 读数
    db_total      BIGINT       NOT NULL COMMENT 'DB 审批总量',
    db_issued     BIGINT       NOT NULL COMMENT 'DB 账本已发放',
    coupon_count  BIGINT       NULL     COMMENT 'COUNT(user_coupon) 互证读数（未执行为 NULL）',

    redis_stock   BIGINT       NULL     COMMENT '修复前的 Redis 余量（key 缺失为 NULL）',
    delta         BIGINT       NOT NULL COMMENT 'issued − dbi − pending（漂移量与方向）',

    -- ALARM_ONLY：守卫未过/在途未清，只告警不修复
    -- REPAIRED：三本账已按 DB 口径重置
    -- GUARD_FAILED：issued_count 与 COUNT(user_coupon) 互证失败——账本自相矛盾，人工介入
    -- DEFERRED_PENDING：在途未清空，本轮推迟（下轮再试）
    action        VARCHAR(32)  NOT NULL COMMENT '本轮动作',
    detail        VARCHAR(512) NULL     COMMENT '归因备注（如 confirm-lost / redis-side）',

    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tpl_time (template_id, check_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '库存恒等式漂移日志（只追加）';
