-- =====================================================================
-- V1: 优惠券核心域建表（阶段0）
-- 依据：docs/01-领域模型与券状态机.md 4.2 节 DDL + docs/10-统一规范 10.1 节
-- 建表规范（对标阿里 Java 开发手册数据库规约）：
--   1. 库/表/字段必须 utf8mb4（utf8 是残缺的三字节实现，存不了 emoji 和部分生僻字）
--   2. 每张表、每一列必须写 COMMENT——三个月后没人记得 status=3 是什么意思
--   3. 必备三件套：id / create_time / update_time（可变更表）
--   4. 禁用外键与级联：跨表一致性一律在应用层保证（分库分表后外键本来就不可用）
--   5. 索引只服务于真实查询，每个索引对应一条已知 SQL（推导过程见 docs/01a 第八节）
-- 阶段0取舍（docs/adr/ADR-0004）：
--   - user_coupon 暂不分库分表（量级未到），分表在阶段2落地
--   - 每人限领暂只支持 1 张，用唯一索引 (user_id, template_id) 强制；通用限领阶段1由 Redis 计数解决
-- =====================================================================

-- 1. 券模板（批次）表：运营配置的"一类券"，核心字段创建后不可改
CREATE TABLE coupon_template (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  template_code       VARCHAR(32)    NOT NULL                COMMENT '对外业务编码（上游契约使用它而非内部id）',
  activity_id         BIGINT         NOT NULL                COMMENT '所属活动id，向上归因预算',
  name                VARCHAR(128)   NOT NULL                COMMENT '券名称',
  coupon_type         TINYINT        NOT NULL                COMMENT '券类型:1满减 2满折 3无门槛(=满0减X)',
  face_value          BIGINT         NOT NULL                COMMENT '面额，单位分；满折存折扣基点(85折=8500)',
  discount_cap        BIGINT         DEFAULT NULL            COMMENT '满折优惠上限(分)，满折券强制必填，满减为NULL',
  threshold           BIGINT         NOT NULL DEFAULT 0      COMMENT '使用门槛，单位分',
  total_count         BIGINT         NOT NULL                COMMENT '审批总库存，只增不减',
  issued_count        BIGINT         NOT NULL DEFAULT 0      COMMENT '已发放数量（阶段0扣库存的条件更新计数器）',
  per_user_limit      INT            NOT NULL DEFAULT 1      COMMENT '每人限领张数（阶段0仅支持1，由唯一索引强制）',
  valid_type          TINYINT        NOT NULL                COMMENT '有效期类型:1固定区间 2领取后N天',
  valid_start         DATETIME       DEFAULT NULL            COMMENT '固定区间-开始（valid_type=1时必填）',
  valid_end           DATETIME       DEFAULT NULL            COMMENT '固定区间-结束（valid_type=1时必填）',
  valid_days          INT            DEFAULT NULL            COMMENT '相对有效期天数（valid_type=2时必填），截止统一到当日23:59:59',
  approve_status      TINYINT        NOT NULL DEFAULT 0      COMMENT '审批状态:0待审 1通过 2拒绝（与publish正交，交叉审核）',
  publish_status      TINYINT        NOT NULL DEFAULT 0      COMMENT '生命周期:0未发布 1已发布 2已下线',
  version             INT            NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (template_code),
  KEY idx_activity (activity_id)          -- 服务于M端"查这个活动下的所有券"
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '券模板/批次';

-- 2. 用户券表：用户领取后的一张具体券（阶段0单表；阶段2按 user_id 分库分表）
CREATE TABLE user_coupon (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键（分表后仅分片内唯一，全局标识阶段1引入分布式ID）',
  user_id             BIGINT         NOT NULL                COMMENT '用户id（未来的分片路由因子）',
  template_id         BIGINT         NOT NULL                COMMENT '所属模板id',
  template_code       VARCHAR(32)    NOT NULL                COMMENT '模板编码冗余（排查/对账用）',
  -- 规则快照：防模板篡改导致历史券"变性" + 分表后免join（冗余字段宜少而精）
  face_value          BIGINT         NOT NULL                COMMENT '面额快照，分',
  threshold           BIGINT         NOT NULL                COMMENT '门槛快照，分',
  discount_cap        BIGINT         DEFAULT NULL            COMMENT '满折上限快照，分',
  valid_end           DATETIME       NOT NULL                COMMENT '过期时间（领取时固化，统一到当日23:59:59）',
  status              TINYINT        NOT NULL DEFAULT 0      COMMENT '状态:0待使用 1已锁定 2已核销(终) 3已过期(终) 4已作废(终)',
  receive_time        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  idempotent_key      VARCHAR(64)    NOT NULL                COMMENT '领券幂等键(上游requestId)',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (user_id, idempotent_key),      -- 幂等终审：唯一索引含路由因子user_id（分片铁律）
  UNIQUE KEY uk_user_template (user_id, template_id),-- 阶段0限领1张的强制约束（通用限领阶段1替换）
  KEY idx_user_status (user_id, status),             -- 服务于最高频查询"查我的券"
  KEY idx_expire (status, valid_end)                 -- 服务于过期扫描任务（等值在前范围在后）
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户券实例';

-- 3. 占用单：券与订单的绑定记录，交易链路的幂等锚点（回退/对账的唯一裁判）
CREATE TABLE coupon_usage (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  usage_no            VARCHAR(64)    NOT NULL                COMMENT '幂等号=订单号+券id（订单号在前，支持按订单前缀查询）',
  user_id             BIGINT         NOT NULL                COMMENT '用户id（与user_coupon同路由）',
  user_coupon_id      BIGINT         NOT NULL                COMMENT '用户券id',
  order_no            VARCHAR(64)    NOT NULL                COMMENT '订单号',
  status              TINYINT        NOT NULL DEFAULT 0      COMMENT '状态:0占用中 1已核销 2已释放',
  discount_amt        BIGINT         DEFAULT NULL            COMMENT '本单实际抵扣金额，分（核销时写入）',
  occupy_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '占用时间',
  use_time            DATETIME       DEFAULT NULL            COMMENT '核销时间',
  release_time        DATETIME       DEFAULT NULL            COMMENT '释放时间',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_usage_no (usage_no),
  KEY idx_user (user_id)                             -- 客服排查"这个用户的占用记录"
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '券占用/核销单';

-- 4. 事件明细表：唯一事实源——领取/锁定/核销/释放/过期/作废全部落事件，统计与对账从这里回放
--    只追加不修改，因此无 update_time；阶段2按月分表+归档
CREATE TABLE coupon_event (
  id                  BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  event_no            VARCHAR(64)    NOT NULL                COMMENT '事件幂等号（重放/补偿的重复事件靠唯一索引撞掉）',
  event_type          TINYINT        NOT NULL                COMMENT '事件类型:1发放 2锁定 3核销 4释放回退 5过期 6作废',
  template_id         BIGINT         NOT NULL                COMMENT '模板id',
  user_coupon_id      BIGINT         NOT NULL                COMMENT '用户券id',
  user_id             BIGINT         NOT NULL                COMMENT '用户id',
  order_no            VARCHAR(64)    DEFAULT NULL            COMMENT '关联订单号（无则为空）',
  amount              BIGINT         DEFAULT NULL            COMMENT '该事件金额，分（核销事件=实际抵扣额）',
  occur_time          DATETIME(3)    NOT NULL                COMMENT '事件发生时间（毫秒精度）',
  create_time         DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_no (event_no),
  KEY idx_tpl_time (template_id, occur_time)         -- 对账统计："某模板某时段的领取/核销数"
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '券事件明细（事实源）';
