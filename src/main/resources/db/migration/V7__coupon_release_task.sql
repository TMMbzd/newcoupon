-- V7（L5 锁定超时兜底，docs/L5 技术方案 D2）：锁定超时任务表——"锁了必有兜底"。
-- 骨架与 coupon_expire_task（V5）同构："同一骨架换谓词换动作"：
--   谓词：next_scan_time 初值 = occupy_time + 锁定窗口（30 分钟，可配置）而非有效期终点；
--   动作：到点收场为 占用单→超时关闭(4)、券→(未过期?待使用:已过期)。
-- 关键差异（评审修正，2026-09-15）：唯一键是 uk_usage_no 而非 uk_coupon——
--   一张券一生可签多份合同（锁定→释放→再锁定→再超时），按券唯一会挡第二次注册；
--   usage_no 全局唯一，任务的主体从"哪张券"修正为"哪份合同"。
-- 顺带给 coupon_usage 补审计索引 idx_occupy(status, occupy_time)：T+1 审计按
-- "占用中且锁龄超窗"直查占用单侧（在途占用本就是小集合，等值前缀精确命中）。
-- 分片纪律：新表生而分片（user_id%64 路由，与主表同规则，绑定表组同步登记）。

CREATE TABLE coupon_release_task_0 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表0';

CREATE TABLE coupon_release_task_1 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表1';

CREATE TABLE coupon_release_task_2 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表2';

CREATE TABLE coupon_release_task_3 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表3';

CREATE TABLE coupon_release_task_4 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表4';

CREATE TABLE coupon_release_task_5 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表5';

CREATE TABLE coupon_release_task_6 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表6';

CREATE TABLE coupon_release_task_7 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表7';

CREATE TABLE coupon_release_task_8 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表8';

CREATE TABLE coupon_release_task_9 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表9';

CREATE TABLE coupon_release_task_10 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表10';

CREATE TABLE coupon_release_task_11 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表11';

CREATE TABLE coupon_release_task_12 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表12';

CREATE TABLE coupon_release_task_13 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表13';

CREATE TABLE coupon_release_task_14 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表14';

CREATE TABLE coupon_release_task_15 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表15';

CREATE TABLE coupon_release_task_16 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表16';

CREATE TABLE coupon_release_task_17 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表17';

CREATE TABLE coupon_release_task_18 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表18';

CREATE TABLE coupon_release_task_19 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表19';

CREATE TABLE coupon_release_task_20 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表20';

CREATE TABLE coupon_release_task_21 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表21';

CREATE TABLE coupon_release_task_22 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表22';

CREATE TABLE coupon_release_task_23 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表23';

CREATE TABLE coupon_release_task_24 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表24';

CREATE TABLE coupon_release_task_25 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表25';

CREATE TABLE coupon_release_task_26 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表26';

CREATE TABLE coupon_release_task_27 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表27';

CREATE TABLE coupon_release_task_28 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表28';

CREATE TABLE coupon_release_task_29 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表29';

CREATE TABLE coupon_release_task_30 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表30';

CREATE TABLE coupon_release_task_31 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表31';

CREATE TABLE coupon_release_task_32 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表32';

CREATE TABLE coupon_release_task_33 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表33';

CREATE TABLE coupon_release_task_34 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表34';

CREATE TABLE coupon_release_task_35 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表35';

CREATE TABLE coupon_release_task_36 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表36';

CREATE TABLE coupon_release_task_37 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表37';

CREATE TABLE coupon_release_task_38 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表38';

CREATE TABLE coupon_release_task_39 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表39';

CREATE TABLE coupon_release_task_40 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表40';

CREATE TABLE coupon_release_task_41 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表41';

CREATE TABLE coupon_release_task_42 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表42';

CREATE TABLE coupon_release_task_43 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表43';

CREATE TABLE coupon_release_task_44 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表44';

CREATE TABLE coupon_release_task_45 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表45';

CREATE TABLE coupon_release_task_46 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表46';

CREATE TABLE coupon_release_task_47 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表47';

CREATE TABLE coupon_release_task_48 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表48';

CREATE TABLE coupon_release_task_49 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表49';

CREATE TABLE coupon_release_task_50 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表50';

CREATE TABLE coupon_release_task_51 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表51';

CREATE TABLE coupon_release_task_52 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表52';

CREATE TABLE coupon_release_task_53 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表53';

CREATE TABLE coupon_release_task_54 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表54';

CREATE TABLE coupon_release_task_55 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表55';

CREATE TABLE coupon_release_task_56 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表56';

CREATE TABLE coupon_release_task_57 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表57';

CREATE TABLE coupon_release_task_58 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表58';

CREATE TABLE coupon_release_task_59 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表59';

CREATE TABLE coupon_release_task_60 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表60';

CREATE TABLE coupon_release_task_61 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表61';

CREATE TABLE coupon_release_task_62 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表62';

CREATE TABLE coupon_release_task_63 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '占用单号=订单号:券id（合同唯一）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间=占用时间+锁定窗口；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '锁定超时任务 分表63';


-- 占用单审计索引（T+1 锁龄审计直查 usage 侧；在途占用=小集合，等值前缀命中）
ALTER TABLE coupon_usage_0 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_1 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_2 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_3 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_4 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_5 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_6 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_7 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_8 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_9 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_10 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_11 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_12 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_13 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_14 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_15 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_16 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_17 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_18 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_19 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_20 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_21 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_22 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_23 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_24 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_25 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_26 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_27 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_28 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_29 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_30 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_31 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_32 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_33 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_34 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_35 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_36 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_37 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_38 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_39 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_40 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_41 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_42 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_43 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_44 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_45 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_46 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_47 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_48 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_49 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_50 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_51 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_52 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_53 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_54 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_55 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_56 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_57 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_58 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_59 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_60 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_61 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_62 ADD KEY idx_occupy (status, occupy_time);
ALTER TABLE coupon_usage_63 ADD KEY idx_occupy (status, occupy_time);
