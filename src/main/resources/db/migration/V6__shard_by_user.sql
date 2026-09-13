-- V6（ADR-0010 批次二）：user_id 分表路由先行——三张用户维度大表拆 64 物理分表，
-- 全部落在当前 1 个物理库。路由规则（ShardingSphere）：表 = user_coupon_{user_id % 64}。
--
-- 为什么现在拆、为什么 64：
--   ① 表数今天定死永不改（改表数=对全部数据重哈希=停机迁移）——分片数量是不可逆决策；
--   ② 10亿券 ÷ 64 ≈ 1560 万行/表：单表千万级是 MySQL 舒适区（B+树 3-4 层、DDL 秒级）；
--   ③ 未来扩 16 个物理库：表名不变，按 user_id%16 把整表搬迁到对应库——纯数据搬运，
--     应用 SQL 零改动（ShardingSphere 只改 actualDataNodes 配置）。
--
-- 为什么这三张表：user_coupon（10亿目标）、coupon_usage/coupon_expire_task（用户维度、
-- 与主表同路由键——绑定表，join 不跨片）。coupon_event 暂不分（模板维度查询为主，M4 配
-- ES 宽表）；coupon_template 是小配置表，永不分。
--
-- 索引随表复制：uk_idem/uk_user_seq/uk_usage_no 等唯一索引全部"含 user_id 或业务唯一键"，
-- 分片内唯一性=全局唯一性（分片铁律：唯一约束要么含路由键，要么业务键全局唯一——本表族满足）。

-- =====================================================================
-- 1) user_coupon → user_coupon_0..63
-- =====================================================================

-- 1.1 建 64 张分表（结构与 V1+V2 终态一致；主键改显式雪花ID——不再依赖 AUTO_INCREMENT）
-- 生成式 DDL：MySQL 不支持 PL/SQL 式循环，此处为展开后的 64 段（由脚本生成，勿手改）

-- 1.2 建 64 张 user_coupon 分表
CREATE TABLE user_coupon_0 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表0';

CREATE TABLE user_coupon_1 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表1';

CREATE TABLE user_coupon_2 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表2';

CREATE TABLE user_coupon_3 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表3';

CREATE TABLE user_coupon_4 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表4';

CREATE TABLE user_coupon_5 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表5';

CREATE TABLE user_coupon_6 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表6';

CREATE TABLE user_coupon_7 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表7';

CREATE TABLE user_coupon_8 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表8';

CREATE TABLE user_coupon_9 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表9';

CREATE TABLE user_coupon_10 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表10';

CREATE TABLE user_coupon_11 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表11';

CREATE TABLE user_coupon_12 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表12';

CREATE TABLE user_coupon_13 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表13';

CREATE TABLE user_coupon_14 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表14';

CREATE TABLE user_coupon_15 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表15';

CREATE TABLE user_coupon_16 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表16';

CREATE TABLE user_coupon_17 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表17';

CREATE TABLE user_coupon_18 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表18';

CREATE TABLE user_coupon_19 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表19';

CREATE TABLE user_coupon_20 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表20';

CREATE TABLE user_coupon_21 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表21';

CREATE TABLE user_coupon_22 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表22';

CREATE TABLE user_coupon_23 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表23';

CREATE TABLE user_coupon_24 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表24';

CREATE TABLE user_coupon_25 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表25';

CREATE TABLE user_coupon_26 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表26';

CREATE TABLE user_coupon_27 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表27';

CREATE TABLE user_coupon_28 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表28';

CREATE TABLE user_coupon_29 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表29';

CREATE TABLE user_coupon_30 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表30';

CREATE TABLE user_coupon_31 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表31';

CREATE TABLE user_coupon_32 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表32';

CREATE TABLE user_coupon_33 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表33';

CREATE TABLE user_coupon_34 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表34';

CREATE TABLE user_coupon_35 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表35';

CREATE TABLE user_coupon_36 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表36';

CREATE TABLE user_coupon_37 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表37';

CREATE TABLE user_coupon_38 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表38';

CREATE TABLE user_coupon_39 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表39';

CREATE TABLE user_coupon_40 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表40';

CREATE TABLE user_coupon_41 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表41';

CREATE TABLE user_coupon_42 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表42';

CREATE TABLE user_coupon_43 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表43';

CREATE TABLE user_coupon_44 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表44';

CREATE TABLE user_coupon_45 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表45';

CREATE TABLE user_coupon_46 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表46';

CREATE TABLE user_coupon_47 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表47';

CREATE TABLE user_coupon_48 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表48';

CREATE TABLE user_coupon_49 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表49';

CREATE TABLE user_coupon_50 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表50';

CREATE TABLE user_coupon_51 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表51';

CREATE TABLE user_coupon_52 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表52';

CREATE TABLE user_coupon_53 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表53';

CREATE TABLE user_coupon_54 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表54';

CREATE TABLE user_coupon_55 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表55';

CREATE TABLE user_coupon_56 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表56';

CREATE TABLE user_coupon_57 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表57';

CREATE TABLE user_coupon_58 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表58';

CREATE TABLE user_coupon_59 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表59';

CREATE TABLE user_coupon_60 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表60';

CREATE TABLE user_coupon_61 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表61';

CREATE TABLE user_coupon_62 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表62';

CREATE TABLE user_coupon_63 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID，全局唯一且趋势递增）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键（user_id%64=表号）',
  template_id         BIGINT        NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)   NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  face_value          BIGINT        NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT        NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT        DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME      NOT NULL                COMMENT '过期时间（领取时固化）',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)   NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  seq                 INT           NOT NULL DEFAULT 1      COMMENT '同用户同模板发放序号(1..N)，列序与V2追加位置一致(表尾)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),
  UNIQUE KEY uk_user_seq (user_id, template_id, seq),
  KEY idx_user_status (user_id, status),
  KEY idx_expire (status, valid_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例 分表63';

-- 1.3 数据搬迁：按 user_id%64 分发到对应分表（搬迁期间新写入也走分表——先建表后搬迁，
--            老表数据只读不锁：本项目当前数据量小，同步搬迁即可；亿级时的双写灰度方案见 M4 演练）
INSERT INTO user_coupon_0 SELECT * FROM user_coupon WHERE user_id % 64 = 0;
INSERT INTO user_coupon_1 SELECT * FROM user_coupon WHERE user_id % 64 = 1;
INSERT INTO user_coupon_2 SELECT * FROM user_coupon WHERE user_id % 64 = 2;
INSERT INTO user_coupon_3 SELECT * FROM user_coupon WHERE user_id % 64 = 3;
INSERT INTO user_coupon_4 SELECT * FROM user_coupon WHERE user_id % 64 = 4;
INSERT INTO user_coupon_5 SELECT * FROM user_coupon WHERE user_id % 64 = 5;
INSERT INTO user_coupon_6 SELECT * FROM user_coupon WHERE user_id % 64 = 6;
INSERT INTO user_coupon_7 SELECT * FROM user_coupon WHERE user_id % 64 = 7;
INSERT INTO user_coupon_8 SELECT * FROM user_coupon WHERE user_id % 64 = 8;
INSERT INTO user_coupon_9 SELECT * FROM user_coupon WHERE user_id % 64 = 9;
INSERT INTO user_coupon_10 SELECT * FROM user_coupon WHERE user_id % 64 = 10;
INSERT INTO user_coupon_11 SELECT * FROM user_coupon WHERE user_id % 64 = 11;
INSERT INTO user_coupon_12 SELECT * FROM user_coupon WHERE user_id % 64 = 12;
INSERT INTO user_coupon_13 SELECT * FROM user_coupon WHERE user_id % 64 = 13;
INSERT INTO user_coupon_14 SELECT * FROM user_coupon WHERE user_id % 64 = 14;
INSERT INTO user_coupon_15 SELECT * FROM user_coupon WHERE user_id % 64 = 15;
INSERT INTO user_coupon_16 SELECT * FROM user_coupon WHERE user_id % 64 = 16;
INSERT INTO user_coupon_17 SELECT * FROM user_coupon WHERE user_id % 64 = 17;
INSERT INTO user_coupon_18 SELECT * FROM user_coupon WHERE user_id % 64 = 18;
INSERT INTO user_coupon_19 SELECT * FROM user_coupon WHERE user_id % 64 = 19;
INSERT INTO user_coupon_20 SELECT * FROM user_coupon WHERE user_id % 64 = 20;
INSERT INTO user_coupon_21 SELECT * FROM user_coupon WHERE user_id % 64 = 21;
INSERT INTO user_coupon_22 SELECT * FROM user_coupon WHERE user_id % 64 = 22;
INSERT INTO user_coupon_23 SELECT * FROM user_coupon WHERE user_id % 64 = 23;
INSERT INTO user_coupon_24 SELECT * FROM user_coupon WHERE user_id % 64 = 24;
INSERT INTO user_coupon_25 SELECT * FROM user_coupon WHERE user_id % 64 = 25;
INSERT INTO user_coupon_26 SELECT * FROM user_coupon WHERE user_id % 64 = 26;
INSERT INTO user_coupon_27 SELECT * FROM user_coupon WHERE user_id % 64 = 27;
INSERT INTO user_coupon_28 SELECT * FROM user_coupon WHERE user_id % 64 = 28;
INSERT INTO user_coupon_29 SELECT * FROM user_coupon WHERE user_id % 64 = 29;
INSERT INTO user_coupon_30 SELECT * FROM user_coupon WHERE user_id % 64 = 30;
INSERT INTO user_coupon_31 SELECT * FROM user_coupon WHERE user_id % 64 = 31;
INSERT INTO user_coupon_32 SELECT * FROM user_coupon WHERE user_id % 64 = 32;
INSERT INTO user_coupon_33 SELECT * FROM user_coupon WHERE user_id % 64 = 33;
INSERT INTO user_coupon_34 SELECT * FROM user_coupon WHERE user_id % 64 = 34;
INSERT INTO user_coupon_35 SELECT * FROM user_coupon WHERE user_id % 64 = 35;
INSERT INTO user_coupon_36 SELECT * FROM user_coupon WHERE user_id % 64 = 36;
INSERT INTO user_coupon_37 SELECT * FROM user_coupon WHERE user_id % 64 = 37;
INSERT INTO user_coupon_38 SELECT * FROM user_coupon WHERE user_id % 64 = 38;
INSERT INTO user_coupon_39 SELECT * FROM user_coupon WHERE user_id % 64 = 39;
INSERT INTO user_coupon_40 SELECT * FROM user_coupon WHERE user_id % 64 = 40;
INSERT INTO user_coupon_41 SELECT * FROM user_coupon WHERE user_id % 64 = 41;
INSERT INTO user_coupon_42 SELECT * FROM user_coupon WHERE user_id % 64 = 42;
INSERT INTO user_coupon_43 SELECT * FROM user_coupon WHERE user_id % 64 = 43;
INSERT INTO user_coupon_44 SELECT * FROM user_coupon WHERE user_id % 64 = 44;
INSERT INTO user_coupon_45 SELECT * FROM user_coupon WHERE user_id % 64 = 45;
INSERT INTO user_coupon_46 SELECT * FROM user_coupon WHERE user_id % 64 = 46;
INSERT INTO user_coupon_47 SELECT * FROM user_coupon WHERE user_id % 64 = 47;
INSERT INTO user_coupon_48 SELECT * FROM user_coupon WHERE user_id % 64 = 48;
INSERT INTO user_coupon_49 SELECT * FROM user_coupon WHERE user_id % 64 = 49;
INSERT INTO user_coupon_50 SELECT * FROM user_coupon WHERE user_id % 64 = 50;
INSERT INTO user_coupon_51 SELECT * FROM user_coupon WHERE user_id % 64 = 51;
INSERT INTO user_coupon_52 SELECT * FROM user_coupon WHERE user_id % 64 = 52;
INSERT INTO user_coupon_53 SELECT * FROM user_coupon WHERE user_id % 64 = 53;
INSERT INTO user_coupon_54 SELECT * FROM user_coupon WHERE user_id % 64 = 54;
INSERT INTO user_coupon_55 SELECT * FROM user_coupon WHERE user_id % 64 = 55;
INSERT INTO user_coupon_56 SELECT * FROM user_coupon WHERE user_id % 64 = 56;
INSERT INTO user_coupon_57 SELECT * FROM user_coupon WHERE user_id % 64 = 57;
INSERT INTO user_coupon_58 SELECT * FROM user_coupon WHERE user_id % 64 = 58;
INSERT INTO user_coupon_59 SELECT * FROM user_coupon WHERE user_id % 64 = 59;
INSERT INTO user_coupon_60 SELECT * FROM user_coupon WHERE user_id % 64 = 60;
INSERT INTO user_coupon_61 SELECT * FROM user_coupon WHERE user_id % 64 = 61;
INSERT INTO user_coupon_62 SELECT * FROM user_coupon WHERE user_id % 64 = 62;
INSERT INTO user_coupon_63 SELECT * FROM user_coupon WHERE user_id % 64 = 63;

DROP TABLE user_coupon;

-- =====================================================================
-- 2) coupon_usage → coupon_usage_0..63（与 user_coupon 同路由键=绑定表，join不跨片）
-- =====================================================================

CREATE TABLE coupon_usage_0 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表0';

CREATE TABLE coupon_usage_1 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表1';

CREATE TABLE coupon_usage_2 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表2';

CREATE TABLE coupon_usage_3 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表3';

CREATE TABLE coupon_usage_4 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表4';

CREATE TABLE coupon_usage_5 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表5';

CREATE TABLE coupon_usage_6 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表6';

CREATE TABLE coupon_usage_7 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表7';

CREATE TABLE coupon_usage_8 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表8';

CREATE TABLE coupon_usage_9 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表9';

CREATE TABLE coupon_usage_10 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表10';

CREATE TABLE coupon_usage_11 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表11';

CREATE TABLE coupon_usage_12 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表12';

CREATE TABLE coupon_usage_13 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表13';

CREATE TABLE coupon_usage_14 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表14';

CREATE TABLE coupon_usage_15 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表15';

CREATE TABLE coupon_usage_16 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表16';

CREATE TABLE coupon_usage_17 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表17';

CREATE TABLE coupon_usage_18 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表18';

CREATE TABLE coupon_usage_19 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表19';

CREATE TABLE coupon_usage_20 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表20';

CREATE TABLE coupon_usage_21 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表21';

CREATE TABLE coupon_usage_22 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表22';

CREATE TABLE coupon_usage_23 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表23';

CREATE TABLE coupon_usage_24 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表24';

CREATE TABLE coupon_usage_25 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表25';

CREATE TABLE coupon_usage_26 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表26';

CREATE TABLE coupon_usage_27 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表27';

CREATE TABLE coupon_usage_28 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表28';

CREATE TABLE coupon_usage_29 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表29';

CREATE TABLE coupon_usage_30 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表30';

CREATE TABLE coupon_usage_31 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表31';

CREATE TABLE coupon_usage_32 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表32';

CREATE TABLE coupon_usage_33 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表33';

CREATE TABLE coupon_usage_34 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表34';

CREATE TABLE coupon_usage_35 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表35';

CREATE TABLE coupon_usage_36 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表36';

CREATE TABLE coupon_usage_37 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表37';

CREATE TABLE coupon_usage_38 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表38';

CREATE TABLE coupon_usage_39 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表39';

CREATE TABLE coupon_usage_40 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表40';

CREATE TABLE coupon_usage_41 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表41';

CREATE TABLE coupon_usage_42 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表42';

CREATE TABLE coupon_usage_43 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表43';

CREATE TABLE coupon_usage_44 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表44';

CREATE TABLE coupon_usage_45 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表45';

CREATE TABLE coupon_usage_46 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表46';

CREATE TABLE coupon_usage_47 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表47';

CREATE TABLE coupon_usage_48 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表48';

CREATE TABLE coupon_usage_49 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表49';

CREATE TABLE coupon_usage_50 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表50';

CREATE TABLE coupon_usage_51 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表51';

CREATE TABLE coupon_usage_52 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表52';

CREATE TABLE coupon_usage_53 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表53';

CREATE TABLE coupon_usage_54 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表54';

CREATE TABLE coupon_usage_55 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表55';

CREATE TABLE coupon_usage_56 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表56';

CREATE TABLE coupon_usage_57 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表57';

CREATE TABLE coupon_usage_58 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表58';

CREATE TABLE coupon_usage_59 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表59';

CREATE TABLE coupon_usage_60 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表60';

CREATE TABLE coupon_usage_61 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表61';

CREATE TABLE coupon_usage_62 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表62';

CREATE TABLE coupon_usage_63 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  usage_no            VARCHAR(64)   NOT NULL                COMMENT '幂等号=订单号+券id（全业务唯一）',
  user_id             BIGINT        NOT NULL                COMMENT '用户id=分片路由键',
  user_coupon_id      BIGINT        NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)   NOT NULL                COMMENT '订单号',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放 3过期关闭',
  discount_amt        BIGINT        DEFAULT NULL            COMMENT '本单实际抵扣金额，分',
  occupy_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME      DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME      DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id),
  KEY idx_coupon (user_coupon_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单 分表63';

INSERT INTO coupon_usage_0 SELECT * FROM coupon_usage WHERE user_id % 64 = 0;
INSERT INTO coupon_usage_1 SELECT * FROM coupon_usage WHERE user_id % 64 = 1;
INSERT INTO coupon_usage_2 SELECT * FROM coupon_usage WHERE user_id % 64 = 2;
INSERT INTO coupon_usage_3 SELECT * FROM coupon_usage WHERE user_id % 64 = 3;
INSERT INTO coupon_usage_4 SELECT * FROM coupon_usage WHERE user_id % 64 = 4;
INSERT INTO coupon_usage_5 SELECT * FROM coupon_usage WHERE user_id % 64 = 5;
INSERT INTO coupon_usage_6 SELECT * FROM coupon_usage WHERE user_id % 64 = 6;
INSERT INTO coupon_usage_7 SELECT * FROM coupon_usage WHERE user_id % 64 = 7;
INSERT INTO coupon_usage_8 SELECT * FROM coupon_usage WHERE user_id % 64 = 8;
INSERT INTO coupon_usage_9 SELECT * FROM coupon_usage WHERE user_id % 64 = 9;
INSERT INTO coupon_usage_10 SELECT * FROM coupon_usage WHERE user_id % 64 = 10;
INSERT INTO coupon_usage_11 SELECT * FROM coupon_usage WHERE user_id % 64 = 11;
INSERT INTO coupon_usage_12 SELECT * FROM coupon_usage WHERE user_id % 64 = 12;
INSERT INTO coupon_usage_13 SELECT * FROM coupon_usage WHERE user_id % 64 = 13;
INSERT INTO coupon_usage_14 SELECT * FROM coupon_usage WHERE user_id % 64 = 14;
INSERT INTO coupon_usage_15 SELECT * FROM coupon_usage WHERE user_id % 64 = 15;
INSERT INTO coupon_usage_16 SELECT * FROM coupon_usage WHERE user_id % 64 = 16;
INSERT INTO coupon_usage_17 SELECT * FROM coupon_usage WHERE user_id % 64 = 17;
INSERT INTO coupon_usage_18 SELECT * FROM coupon_usage WHERE user_id % 64 = 18;
INSERT INTO coupon_usage_19 SELECT * FROM coupon_usage WHERE user_id % 64 = 19;
INSERT INTO coupon_usage_20 SELECT * FROM coupon_usage WHERE user_id % 64 = 20;
INSERT INTO coupon_usage_21 SELECT * FROM coupon_usage WHERE user_id % 64 = 21;
INSERT INTO coupon_usage_22 SELECT * FROM coupon_usage WHERE user_id % 64 = 22;
INSERT INTO coupon_usage_23 SELECT * FROM coupon_usage WHERE user_id % 64 = 23;
INSERT INTO coupon_usage_24 SELECT * FROM coupon_usage WHERE user_id % 64 = 24;
INSERT INTO coupon_usage_25 SELECT * FROM coupon_usage WHERE user_id % 64 = 25;
INSERT INTO coupon_usage_26 SELECT * FROM coupon_usage WHERE user_id % 64 = 26;
INSERT INTO coupon_usage_27 SELECT * FROM coupon_usage WHERE user_id % 64 = 27;
INSERT INTO coupon_usage_28 SELECT * FROM coupon_usage WHERE user_id % 64 = 28;
INSERT INTO coupon_usage_29 SELECT * FROM coupon_usage WHERE user_id % 64 = 29;
INSERT INTO coupon_usage_30 SELECT * FROM coupon_usage WHERE user_id % 64 = 30;
INSERT INTO coupon_usage_31 SELECT * FROM coupon_usage WHERE user_id % 64 = 31;
INSERT INTO coupon_usage_32 SELECT * FROM coupon_usage WHERE user_id % 64 = 32;
INSERT INTO coupon_usage_33 SELECT * FROM coupon_usage WHERE user_id % 64 = 33;
INSERT INTO coupon_usage_34 SELECT * FROM coupon_usage WHERE user_id % 64 = 34;
INSERT INTO coupon_usage_35 SELECT * FROM coupon_usage WHERE user_id % 64 = 35;
INSERT INTO coupon_usage_36 SELECT * FROM coupon_usage WHERE user_id % 64 = 36;
INSERT INTO coupon_usage_37 SELECT * FROM coupon_usage WHERE user_id % 64 = 37;
INSERT INTO coupon_usage_38 SELECT * FROM coupon_usage WHERE user_id % 64 = 38;
INSERT INTO coupon_usage_39 SELECT * FROM coupon_usage WHERE user_id % 64 = 39;
INSERT INTO coupon_usage_40 SELECT * FROM coupon_usage WHERE user_id % 64 = 40;
INSERT INTO coupon_usage_41 SELECT * FROM coupon_usage WHERE user_id % 64 = 41;
INSERT INTO coupon_usage_42 SELECT * FROM coupon_usage WHERE user_id % 64 = 42;
INSERT INTO coupon_usage_43 SELECT * FROM coupon_usage WHERE user_id % 64 = 43;
INSERT INTO coupon_usage_44 SELECT * FROM coupon_usage WHERE user_id % 64 = 44;
INSERT INTO coupon_usage_45 SELECT * FROM coupon_usage WHERE user_id % 64 = 45;
INSERT INTO coupon_usage_46 SELECT * FROM coupon_usage WHERE user_id % 64 = 46;
INSERT INTO coupon_usage_47 SELECT * FROM coupon_usage WHERE user_id % 64 = 47;
INSERT INTO coupon_usage_48 SELECT * FROM coupon_usage WHERE user_id % 64 = 48;
INSERT INTO coupon_usage_49 SELECT * FROM coupon_usage WHERE user_id % 64 = 49;
INSERT INTO coupon_usage_50 SELECT * FROM coupon_usage WHERE user_id % 64 = 50;
INSERT INTO coupon_usage_51 SELECT * FROM coupon_usage WHERE user_id % 64 = 51;
INSERT INTO coupon_usage_52 SELECT * FROM coupon_usage WHERE user_id % 64 = 52;
INSERT INTO coupon_usage_53 SELECT * FROM coupon_usage WHERE user_id % 64 = 53;
INSERT INTO coupon_usage_54 SELECT * FROM coupon_usage WHERE user_id % 64 = 54;
INSERT INTO coupon_usage_55 SELECT * FROM coupon_usage WHERE user_id % 64 = 55;
INSERT INTO coupon_usage_56 SELECT * FROM coupon_usage WHERE user_id % 64 = 56;
INSERT INTO coupon_usage_57 SELECT * FROM coupon_usage WHERE user_id % 64 = 57;
INSERT INTO coupon_usage_58 SELECT * FROM coupon_usage WHERE user_id % 64 = 58;
INSERT INTO coupon_usage_59 SELECT * FROM coupon_usage WHERE user_id % 64 = 59;
INSERT INTO coupon_usage_60 SELECT * FROM coupon_usage WHERE user_id % 64 = 60;
INSERT INTO coupon_usage_61 SELECT * FROM coupon_usage WHERE user_id % 64 = 61;
INSERT INTO coupon_usage_62 SELECT * FROM coupon_usage WHERE user_id % 64 = 62;
INSERT INTO coupon_usage_63 SELECT * FROM coupon_usage WHERE user_id % 64 = 63;

DROP TABLE coupon_usage;

-- =====================================================================
-- 3) coupon_expire_task → coupon_expire_task_0..63
-- =====================================================================

CREATE TABLE coupon_expire_task_0 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表0';

CREATE TABLE coupon_expire_task_1 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表1';

CREATE TABLE coupon_expire_task_2 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表2';

CREATE TABLE coupon_expire_task_3 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表3';

CREATE TABLE coupon_expire_task_4 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表4';

CREATE TABLE coupon_expire_task_5 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表5';

CREATE TABLE coupon_expire_task_6 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表6';

CREATE TABLE coupon_expire_task_7 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表7';

CREATE TABLE coupon_expire_task_8 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表8';

CREATE TABLE coupon_expire_task_9 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表9';

CREATE TABLE coupon_expire_task_10 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表10';

CREATE TABLE coupon_expire_task_11 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表11';

CREATE TABLE coupon_expire_task_12 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表12';

CREATE TABLE coupon_expire_task_13 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表13';

CREATE TABLE coupon_expire_task_14 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表14';

CREATE TABLE coupon_expire_task_15 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表15';

CREATE TABLE coupon_expire_task_16 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表16';

CREATE TABLE coupon_expire_task_17 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表17';

CREATE TABLE coupon_expire_task_18 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表18';

CREATE TABLE coupon_expire_task_19 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表19';

CREATE TABLE coupon_expire_task_20 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表20';

CREATE TABLE coupon_expire_task_21 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表21';

CREATE TABLE coupon_expire_task_22 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表22';

CREATE TABLE coupon_expire_task_23 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表23';

CREATE TABLE coupon_expire_task_24 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表24';

CREATE TABLE coupon_expire_task_25 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表25';

CREATE TABLE coupon_expire_task_26 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表26';

CREATE TABLE coupon_expire_task_27 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表27';

CREATE TABLE coupon_expire_task_28 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表28';

CREATE TABLE coupon_expire_task_29 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表29';

CREATE TABLE coupon_expire_task_30 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表30';

CREATE TABLE coupon_expire_task_31 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表31';

CREATE TABLE coupon_expire_task_32 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表32';

CREATE TABLE coupon_expire_task_33 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表33';

CREATE TABLE coupon_expire_task_34 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表34';

CREATE TABLE coupon_expire_task_35 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表35';

CREATE TABLE coupon_expire_task_36 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表36';

CREATE TABLE coupon_expire_task_37 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表37';

CREATE TABLE coupon_expire_task_38 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表38';

CREATE TABLE coupon_expire_task_39 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表39';

CREATE TABLE coupon_expire_task_40 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表40';

CREATE TABLE coupon_expire_task_41 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表41';

CREATE TABLE coupon_expire_task_42 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表42';

CREATE TABLE coupon_expire_task_43 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表43';

CREATE TABLE coupon_expire_task_44 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表44';

CREATE TABLE coupon_expire_task_45 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表45';

CREATE TABLE coupon_expire_task_46 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表46';

CREATE TABLE coupon_expire_task_47 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表47';

CREATE TABLE coupon_expire_task_48 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表48';

CREATE TABLE coupon_expire_task_49 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表49';

CREATE TABLE coupon_expire_task_50 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表50';

CREATE TABLE coupon_expire_task_51 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表51';

CREATE TABLE coupon_expire_task_52 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表52';

CREATE TABLE coupon_expire_task_53 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表53';

CREATE TABLE coupon_expire_task_54 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表54';

CREATE TABLE coupon_expire_task_55 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表55';

CREATE TABLE coupon_expire_task_56 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表56';

CREATE TABLE coupon_expire_task_57 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表57';

CREATE TABLE coupon_expire_task_58 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表58';

CREATE TABLE coupon_expire_task_59 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表59';

CREATE TABLE coupon_expire_task_60 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表60';

CREATE TABLE coupon_expire_task_61 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表61';

CREATE TABLE coupon_expire_task_62 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表62';

CREATE TABLE coupon_expire_task_63 (
  id                  BIGINT        NOT NULL                COMMENT '主键（雪花ID）',
  coupon_id           BIGINT        NOT NULL                COMMENT 'user_coupon.id',
  user_id             BIGINT        NOT NULL                COMMENT '冗余用户id=分片路由键（与主表同规则）',
  shard               INT           NOT NULL DEFAULT 0      COMMENT '扫描分片号 = coupon_id % 16（XXL-JOB分片广播）',
  next_scan_time      DATETIME      NOT NULL                COMMENT '下次应扫描时间；失败退避只推进此字段',
  status              TINYINT       NOT NULL DEFAULT 0      COMMENT '0待处理 1已终结 3死信',
  retry_count         INT           NOT NULL DEFAULT 0,
  create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_coupon (coupon_id),
  KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务 分表63';

INSERT INTO coupon_expire_task_0 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 0;
INSERT INTO coupon_expire_task_1 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 1;
INSERT INTO coupon_expire_task_2 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 2;
INSERT INTO coupon_expire_task_3 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 3;
INSERT INTO coupon_expire_task_4 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 4;
INSERT INTO coupon_expire_task_5 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 5;
INSERT INTO coupon_expire_task_6 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 6;
INSERT INTO coupon_expire_task_7 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 7;
INSERT INTO coupon_expire_task_8 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 8;
INSERT INTO coupon_expire_task_9 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 9;
INSERT INTO coupon_expire_task_10 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 10;
INSERT INTO coupon_expire_task_11 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 11;
INSERT INTO coupon_expire_task_12 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 12;
INSERT INTO coupon_expire_task_13 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 13;
INSERT INTO coupon_expire_task_14 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 14;
INSERT INTO coupon_expire_task_15 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 15;
INSERT INTO coupon_expire_task_16 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 16;
INSERT INTO coupon_expire_task_17 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 17;
INSERT INTO coupon_expire_task_18 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 18;
INSERT INTO coupon_expire_task_19 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 19;
INSERT INTO coupon_expire_task_20 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 20;
INSERT INTO coupon_expire_task_21 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 21;
INSERT INTO coupon_expire_task_22 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 22;
INSERT INTO coupon_expire_task_23 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 23;
INSERT INTO coupon_expire_task_24 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 24;
INSERT INTO coupon_expire_task_25 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 25;
INSERT INTO coupon_expire_task_26 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 26;
INSERT INTO coupon_expire_task_27 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 27;
INSERT INTO coupon_expire_task_28 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 28;
INSERT INTO coupon_expire_task_29 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 29;
INSERT INTO coupon_expire_task_30 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 30;
INSERT INTO coupon_expire_task_31 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 31;
INSERT INTO coupon_expire_task_32 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 32;
INSERT INTO coupon_expire_task_33 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 33;
INSERT INTO coupon_expire_task_34 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 34;
INSERT INTO coupon_expire_task_35 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 35;
INSERT INTO coupon_expire_task_36 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 36;
INSERT INTO coupon_expire_task_37 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 37;
INSERT INTO coupon_expire_task_38 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 38;
INSERT INTO coupon_expire_task_39 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 39;
INSERT INTO coupon_expire_task_40 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 40;
INSERT INTO coupon_expire_task_41 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 41;
INSERT INTO coupon_expire_task_42 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 42;
INSERT INTO coupon_expire_task_43 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 43;
INSERT INTO coupon_expire_task_44 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 44;
INSERT INTO coupon_expire_task_45 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 45;
INSERT INTO coupon_expire_task_46 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 46;
INSERT INTO coupon_expire_task_47 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 47;
INSERT INTO coupon_expire_task_48 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 48;
INSERT INTO coupon_expire_task_49 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 49;
INSERT INTO coupon_expire_task_50 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 50;
INSERT INTO coupon_expire_task_51 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 51;
INSERT INTO coupon_expire_task_52 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 52;
INSERT INTO coupon_expire_task_53 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 53;
INSERT INTO coupon_expire_task_54 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 54;
INSERT INTO coupon_expire_task_55 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 55;
INSERT INTO coupon_expire_task_56 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 56;
INSERT INTO coupon_expire_task_57 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 57;
INSERT INTO coupon_expire_task_58 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 58;
INSERT INTO coupon_expire_task_59 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 59;
INSERT INTO coupon_expire_task_60 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 60;
INSERT INTO coupon_expire_task_61 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 61;
INSERT INTO coupon_expire_task_62 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 62;
INSERT INTO coupon_expire_task_63 SELECT * FROM coupon_expire_task WHERE user_id % 64 = 63;

DROP TABLE coupon_expire_task;
