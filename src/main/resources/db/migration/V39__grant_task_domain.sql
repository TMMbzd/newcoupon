-- V39（V6 阶段 1A 第一刀，2026-11-02）：批量发放任务域三张表。
--
-- 设计立场：本域是"任务编排/进度明细"，不是库存账本——库存权威仍在
-- coupon_stock_shard 的条件更新（C1），每用户发券复用 C1ReceiveAppService.receive，
-- 幂等键确定性派生 grant-{taskNo}-{seq}，worker 重放安全。因此三张表保持单表
-- （同 V27 的理由），ShardingSphere 的 !SINGLE *.* 会把它们落到 ds0。
--
-- 计数守恒不变量（任何时刻）：total = success + fail + skip + pending。
-- item 状态 CAS（status=0 才允许迁移）与计数累加在同一事务，多 worker 并发
-- 重放同一 item 时只有 CAS 赢家计数。取消的 item 归入 skip（skip 语义=未发放），
-- 不单列 canceled 计数。

-- 批次头：一次 API 调用一行。uk(app_id, request_id) 是受理幂等锚点。
CREATE TABLE coupon_grant_task (
  id              BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  task_no         VARCHAR(40)  NOT NULL COMMENT '任务号=GT+雪花ID（服务端生成，对外暴露它而非自增id）',
  app_id          VARCHAR(32)  NOT NULL COMMENT '租户/接入方（与模板归属一致）',
  template_id     BIGINT       NOT NULL COMMENT '模板id',
  grant_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=批量 2=定向（本刀仅 1，列先占位）',
  source_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=API（本刀仅开放/内部 API）',
  request_id      VARCHAR(64)  NOT NULL COMMENT '调用方幂等键',
  total_count     BIGINT       NOT NULL DEFAULT 0 COMMENT '去重后用户数',
  success_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '已发放',
  fail_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '终态失败（售罄/日限/模板不可领）',
  skip_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '未发放（已限领跳过 + 取消）',
  pending_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '待执行 = total - 其余三项',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 PENDING 1 RUNNING 2 SUCCESS 3 FAILED 4 CANCELING 5 CANCELED',
  schedule_time   DATETIME(3)  DEFAULT NULL COMMENT '定时开始时间（本刀只建列不实现定时）',
  rate_limit      INT          NOT NULL DEFAULT 0 COMMENT '每秒发放上限；0=不限（MVP：item 间 sleep）',
  max_concurrency INT          NOT NULL DEFAULT 1 COMMENT '单任务最大并发 chunk 数（本刀=1，列先占位）',
  operator        VARCHAR(64)  DEFAULT NULL COMMENT '操作人（审计用）',
  version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本（状态机 CAS 时同步自增）',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_no (task_no),
  UNIQUE KEY uk_app_request (app_id, request_id),
  KEY idx_tpl_status (template_id, status),
  KEY idx_status_create (status, create_time),
  CONSTRAINT chk_grant_task_status CHECK (status BETWEEN 0 AND 5),
  CONSTRAINT chk_grant_task_counts_nonneg CHECK (
      total_count >= 0 AND success_count >= 0 AND fail_count >= 0
      AND skip_count >= 0 AND pending_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '批量发放任务（批次头；进度计数守恒 total=success+fail+skip+pending）';

-- 执行单元：同库存分片（user_id%N 相同）的 item 聚成一个 chunk，
-- 领取用 FOR UPDATE SKIP LOCKED + lease（照 coupon_issue_task 的领取模型）。
CREATE TABLE coupon_grant_chunk (
  id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  task_id       BIGINT       NOT NULL COMMENT '所属任务 id',
  chunk_no      INT          NOT NULL COMMENT '任务内递增编号（按 shard_no 升序分配）',
  shard_no      INT          NOT NULL COMMENT '库存分片号 = user_id % 模板分片数',
  total_count   BIGINT       NOT NULL DEFAULT 0 COMMENT 'chunk 内 item 数',
  success_count BIGINT       NOT NULL DEFAULT 0,
  fail_count    BIGINT       NOT NULL DEFAULT 0,
  skip_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '含已限领跳过与取消',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 PENDING 1 RUNNING 2 DONE 3 FAILED（取消收尾也用 3）',
  lease_owner   VARCHAR(64)  DEFAULT NULL COMMENT '当前 worker 标识',
  lease_until   DATETIME(3)  DEFAULT NULL COMMENT '租约到期；过期的 RUNNING 可被重新领取（item 级幂等保证重放安全）',
  attempt       INT          NOT NULL DEFAULT 0 COMMENT '退避轮次',
  next_retry_at DATETIME(3)  DEFAULT NULL COMMENT '下次可领取时间（可重试 item 留下的退避）',
  create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_chunk (task_id, chunk_no),
  KEY idx_status_retry (status, next_retry_at),
  KEY idx_task_status (task_id, status),
  CONSTRAINT chk_grant_chunk_status CHECK (status BETWEEN 0 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '批量发放执行单元（按库存分片聚合；lease 支撑崩溃恢复）';

-- 按用户明细：一行=一个用户一张目标券。status 只允许 0→终态单向迁移。
CREATE TABLE coupon_grant_item (
  id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  task_id        BIGINT       NOT NULL COMMENT '所属任务 id',
  chunk_no       INT          NOT NULL COMMENT '所属 chunk 编号',
  user_id        BIGINT       NOT NULL COMMENT '目标用户',
  seq            INT          NOT NULL COMMENT '任务内序号；receive 幂等键 = grant-{taskNo}-{seq}（确定性派生）',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 PENDING 1 SUCCESS 2 FAIL 3 SKIP 4 CANCELED',
  user_coupon_id BIGINT       DEFAULT NULL COMMENT '成功后回填的用户券 id',
  fail_code      INT          DEFAULT NULL COMMENT '失败/跳过业务码（3001/3002/5001 等）',
  fail_msg       VARCHAR(255) DEFAULT NULL COMMENT '失败/跳过原因（排障用，不对外泄漏堆栈）',
  attempt        INT          NOT NULL DEFAULT 0 COMMENT '执行尝试次数（含可重试）',
  create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_task_seq (user_id, task_id, seq),
  KEY idx_task_chunk_status (task_id, chunk_no, status),
  CONSTRAINT chk_grant_item_status CHECK (status BETWEEN 0 AND 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '批量发放按用户明细（item 状态 CAS 是计数守恒的锚点）';
