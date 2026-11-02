-- ADR-0019/0021：异步 gate 终态补偿。
-- 任务从“受理 WAL”扩展为“gate 释放状态机”：成功=1 永不释放；拒绝/死信由补偿任务按错误码释放。
ALTER TABLE coupon_issue_task
    ADD COLUMN gate_release_state TINYINT NOT NULL DEFAULT 0
        COMMENT 'gate释放状态:0待处理 1已签发(不释放) 2已释放 3待重试',
    ADD COLUMN gate_next_retry_time DATETIME(3) DEFAULT NULL
        COMMENT '补偿任务下次尝试时间',
    ADD COLUMN gate_max_per_user INT NOT NULL DEFAULT 0
        COMMENT '受理时每人总限快照（0=未预扣）',
    ADD COLUMN gate_per_user_daily INT NOT NULL DEFAULT 0
        COMMENT '受理时每人日限快照（0=未预扣）',
    ADD COLUMN gate_daily_limit BIGINT NOT NULL DEFAULT 0
        COMMENT '受理时模板日限快照（0=未预扣）',
    ADD KEY idx_gate_release (gate_release_state, gate_next_retry_time);

-- 历史回填：
-- 成功任务不能释放（否则会把已消耗的 gate 再加回去）；
-- 在途任务保持 0；旧终态交给补偿任务，按 idem/DB 幂等重试。
UPDATE coupon_issue_task
SET gate_release_state = 1
WHERE status = 2;

UPDATE coupon_issue_task
SET gate_release_state = 0,
    gate_next_retry_time = NOW(3)
WHERE status IN (3, 4);
