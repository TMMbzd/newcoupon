-- V14（S1 开放接入层，2026-10-01）：开放平台的三张新表 + 数据归属列。
-- 设计依据：docs/S1-开放接入层-需求与设计-2026-10-01.md §4.2。
-- 内容：① open_app（接入方）② open_app_secret（密钥多版本，支撑轮换）③ open_api_audit（调用审计）
--       ④ coupon_template 加归属列 ⑤ user_coupon_0..63 加归属列（64 张逐表 ALTER，沿用 V9 先例）
-- 归属列的意义：多租户隔离维度——(app_id, user_id) 才是"一个逻辑用户"，
-- 不同接入方传相同 user_id 是不同的人；查询/操作按 app_id 过滤（越权防线）。

-- ===================================================================
-- ① open_app：接入方（开放的"租户"主数据）
-- ===================================================================
CREATE TABLE open_app (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id      VARCHAR(32)  NOT NULL COMMENT '接入方标识（对外公开，签名头 Credential 里明文传——秘密是 SK 不是 appId）',
  app_name    VARCHAR(128) NOT NULL COMMENT '接入方名称',
  status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
  qps_limit   INT          NOT NULL DEFAULT 100 COMMENT '每秒请求上限（0=不限）',
  daily_quota INT          NOT NULL DEFAULT 1000000 COMMENT '每日请求配额（0=不限）',
  contact     VARCHAR(128) DEFAULT NULL COMMENT '联系人/联系方式（运营用）',
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放平台接入方（S1）';

-- ===================================================================
-- ② open_app_secret：密钥多版本共存（轮换的本质是"新旧并存一段时间"）
-- 存的是 AES-GCM 密文而非哈希：HMAC 验签需要原密钥，"使用"语义无法哈希（只有"验证"语义能哈希）。
-- 注意 expire_time 用 DATETIME(3) 而不是 DATETIME：MySQL 往秒精度列写带小数的时刻是
-- **四舍五入**（不是截断）——16:10:04.6 会被存成 16:10:05，把失效时刻悄悄推后最多 0.5 秒。
-- 7 天轮换期无所谓，但"紧急止血：立刻让旧密钥失效"（零窗口轮换）会被这 0.5 秒破功
-- （S1 第 1 步的红灯用例当场抓出）。
-- ===================================================================
CREATE TABLE open_app_secret (
  id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id        VARCHAR(32)  NOT NULL COMMENT '接入方标识',
  secret_cipher VARCHAR(256) NOT NULL COMMENT 'SK 密文（AES-GCM：Base64(iv(12)||ciphertext||tag(16))）',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '0已失效 1当前 2轮换期旧密钥（仍可验签）',
  expire_time   DATETIME(3)  DEFAULT NULL COMMENT '轮换期旧密钥的失效时刻（NULL=不自动失效；毫秒精度：秒精度会四舍五入）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_app_status (app_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入方密钥（多版本共存支撑轮换，S1）';

-- ===================================================================
-- ③ open_api_audit：开放接口调用审计（成功与失败都落——审计一半的价值在失败调用上）
-- 不记请求/响应体：可能含用户隐私，要查内容用 trace_id 去应用日志找。
-- ===================================================================
CREATE TABLE open_api_audit (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  trace_id    VARCHAR(32)  DEFAULT NULL COMMENT '链路 id（与响应头 X-Trace-Id 同值）',
  app_id      VARCHAR(32)  DEFAULT NULL COMMENT '接入方（验签失败时取头里的声明值，不全信）',
  verified    TINYINT      NOT NULL DEFAULT 0 COMMENT '0未通过验签 1已通过（区分"谁在敲门"和"谁进了门"）',
  resource    VARCHAR(128) DEFAULT NULL COMMENT '资源名 接口[:维度[:值]]，如 receive:template:2001',
  method      VARCHAR(8)   DEFAULT NULL COMMENT 'HTTP 方法',
  path        VARCHAR(128) DEFAULT NULL COMMENT '请求路径',
  http_status INT          DEFAULT NULL COMMENT 'HTTP 状态码',
  code        INT          DEFAULT NULL COMMENT '业务错误码（Result.code）',
  cost_ms     INT          DEFAULT NULL COMMENT '耗时（毫秒）',
  client_ip   VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
  user_id     BIGINT       DEFAULT NULL COMMENT '业务用户（验签通过且解析到才有）',
  create_time DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_app_time (app_id, create_time),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放接口调用审计（每次 /open/** 调用一行，S1）';

-- ===================================================================
-- ④ 内置应用（app_id='1'）：存量数据的归属方
-- 存量模板/券的 app_id 回填为 '1'，必须有一条对应的应用记录，否则归属校验查不到主体。
-- 不插密钥行：内置应用不走签名面（内部面用管理令牌），它是"数据归属标记"而非"对外接入方"。
-- ===================================================================
INSERT INTO open_app (app_id, app_name, status, qps_limit, daily_quota, contact)
VALUES ('1', '内置应用（存量数据归属）', 1, 0, 0, 'internal');

-- ===================================================================
-- ⑤ 归属列：coupon_template（单表，直接 ALTER）
-- ===================================================================
ALTER TABLE coupon_template
  ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（多租户隔离维度；V14 前存量=1）',
  ADD KEY idx_app (app_id);

ALTER TABLE user_coupon_0 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_1 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_2 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_3 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_4 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_5 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_6 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_7 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_8 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_9 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_10 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_11 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_12 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_13 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_14 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_15 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_16 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_17 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_18 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_19 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_20 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_21 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_22 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_23 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_24 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_25 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_26 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_27 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_28 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_29 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_30 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_31 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_32 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_33 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_34 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_35 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_36 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_37 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_38 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_39 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_40 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_41 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_42 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_43 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_44 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_45 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_46 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_47 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_48 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_49 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_50 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_51 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_52 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_53 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_54 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_55 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_56 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_57 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_58 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_59 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_60 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_61 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_62 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
ALTER TABLE user_coupon_63 ADD COLUMN app_id VARCHAR(32) NOT NULL DEFAULT '1' COMMENT '归属接入方（写时从模板冗余；查询/操作的隔离维度）';
