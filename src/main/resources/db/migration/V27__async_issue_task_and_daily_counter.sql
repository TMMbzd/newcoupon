-- V27（ADR-0019，2026-10-22）：异步发放任务表 WAL + 硬日限计数表。
--
-- 三张表都保持单表，不复制 64 分片（ADR-0020 §4.2）：
--   * coupon_issue_task 是“已受理请求”的 WAL，不是库存账本；
--   * 两张日计数表按 (模板/用户, 日期) 点更新，分片只会增加跨表聚合成本。
-- ShardingSphere 的 !SINGLE *.* 会把它们落到 ds0。
--
-- 任务表唯一键 (app_id, user_id, request_id) 是异步幂等锚点；跨租户同 user+requestId
-- 不互相冲突。券表的 uk_idem 仍需 V28 补 app_id，二者一起才能保证最终不串租户。

CREATE TABLE coupon_issue_task (
  id             BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id         VARCHAR(32)  NOT NULL COMMENT '租户/接入方（与模板/券的归属一致）',
  user_id        BIGINT       NOT NULL COMMENT '用户id',
  template_id    BIGINT       NOT NULL COMMENT '模板id',
  request_id     VARCHAR(64)  NOT NULL COMMENT '业务幂等键（上游 requestId）',
  stat_day       DATE         NOT NULL COMMENT '受理日（日限归属日；worker 不用执行日重新取日期，避免跨天漂移）',
  seq            INT          NOT NULL DEFAULT 0 COMMENT '预分配序号；0=未分配，worker 以 MAX(seq)+1 终审',
  source         VARCHAR(32)  DEFAULT NULL COMMENT '来源（开放面/内部面/补偿等，排障用）',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=PROCESSING 2=SUCCESS 3=REJECTED 4=DEAD',
  retry_count    INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_scan_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可领取时间（退避）',
  lease_owner    VARCHAR(64)  DEFAULT NULL COMMENT '当前 worker 标识',
  lease_until    DATETIME(3)  DEFAULT NULL COMMENT '租约到期时间；到期可被其他 worker 回收',
  coupon_id      BIGINT       DEFAULT NULL COMMENT '成功后的用户券id',
  error_code     INT          DEFAULT NULL COMMENT '拒绝/失败业务码（3001/3002/3003/5001 等）',
  error_msg      VARCHAR(255) DEFAULT NULL COMMENT '拒绝/失败原因（排障用，不对外泄漏堆栈）',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_request (app_id, user_id, request_id),
  KEY idx_status_scan (status, next_scan_time),
  KEY idx_app_status_scan (app_id, status, next_scan_time),
  KEY idx_status_create (status, create_time),
  KEY idx_day_status (stat_day, status),
  KEY idx_tpl_status (template_id, status),
  CONSTRAINT chk_issue_task_status CHECK (status BETWEEN 0 AND 4),
  CONSTRAINT chk_issue_task_retry_nonneg CHECK (retry_count >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '异步发放任务（WAL；不是库存账本）';

-- 计数表不冗余 app_id：template_id 全局唯一且只属于一个 app；同一 user_id 在不同 app
-- 下使用不同模板，键自然不同。若未来模板允许跨租户共享/迁移，再引入 app_id 并迁移计数键。
CREATE TABLE coupon_user_daily_counter (
  user_id     BIGINT NOT NULL COMMENT '用户id',
  template_id BIGINT NOT NULL COMMENT '模板id',
  stat_day    DATE   NOT NULL COMMENT '统计日（本地时区）',
  used        BIGINT NOT NULL DEFAULT 0 COMMENT '当日已成功发放数',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (user_id, template_id, stat_day),
  KEY idx_day (stat_day),
  CONSTRAINT chk_user_daily_used_nonneg CHECK (used >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '每人每日已发计数（硬日限；worker 同事务条件更新）';

CREATE TABLE coupon_daily_counter (
  template_id BIGINT NOT NULL COMMENT '模板id',
  stat_day    DATE   NOT NULL COMMENT '统计日（本地时区）',
  used        BIGINT NOT NULL DEFAULT 0 COMMENT '当日已成功发放数',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (template_id, stat_day),
  KEY idx_day (stat_day),
  CONSTRAINT chk_daily_used_nonneg CHECK (used >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '模板每日已发计数（硬日限；单行热点，超过约 300–615 TPS 再分段）';

-- 说明：ADR-0019 设计稿里的列名是 day；实现用 stat_day，避免与 MySQL 的 DAY() 关键字
-- 混淆。语义不变（按本地日期统计）。
