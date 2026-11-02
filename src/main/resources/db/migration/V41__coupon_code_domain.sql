-- V41（V6 阶段 1B，2026-11-02）：兑换码域三张表。
--
-- 设计立场：兑换码是"发券入口的另一种钥匙"，不是新的发券链路——兑换成功最终走
-- C1ReceiveAppService.receive（幂等键派生 redeem-{requestId}），库存/限领/日限
-- 全部沿用既有权威。本域只管"码的生成、哈希存取、一次性导出、兑换状态机"。
--
-- 明文永不落库（安全立场）：DB 只存 HMAC-SHA256(pepper, 规范化码) 的 hex。
-- 建批响应与首次 CSV 导出是明文仅有的两个出口（各自一次性），之后明文在系统内
-- 不可再得——码一旦大规模离开系统（印到物料上），剩下的只有哈希比对。
--
-- 状态机（coupon_code.status）：0 ACTIVE →(兑换 CAS)→ 1 LOCKED →(成功)→ 2 USED；
-- 1 LOCKED →(发放失败/超时回收)→ 0 ACTIVE；0/1 →(停用)→ 3 DISABLED。

-- 批次头：一次生成调用一行。uk(app_id, request_id) 是受理幂等锚点（照 grant_task 模式）。
CREATE TABLE coupon_code_batch (
  id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  batch_no     VARCHAR(40)  NOT NULL COMMENT '批次号=CB+雪花ID（服务端生成，对外暴露它而非自增id）',
  app_id       VARCHAR(32)  NOT NULL COMMENT '租户/接入方（与模板归属一致）',
  template_id  BIGINT       NOT NULL COMMENT '兑换该模板的券',
  code_type    TINYINT      NOT NULL COMMENT '1=手工码16字符（含1位校验符） 2=二维码26字符',
  total        INT          NOT NULL COMMENT '码总数',
  used         INT          NOT NULL DEFAULT 0 COMMENT '已兑换数（成功结算时+1，与 coupon_code 的 USED 行数一致）',
  status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用（停用后全批不可兑换）',
  export_state TINYINT      NOT NULL DEFAULT 0 COMMENT '0 未导出 1 已导出（明文 CSV 只允许导出一次）',
  request_id   VARCHAR(64)  NOT NULL COMMENT '调用方幂等键',
  operator     VARCHAR(64)  DEFAULT NULL COMMENT '操作人（审计用）',
  create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_batch_no (batch_no),
  UNIQUE KEY uk_app_request (app_id, request_id),
  KEY idx_tpl (template_id),
  CONSTRAINT chk_code_batch_type CHECK (code_type BETWEEN 1 AND 2),
  CONSTRAINT chk_code_batch_status CHECK (status BETWEEN 0 AND 1),
  CONSTRAINT chk_code_batch_export CHECK (export_state BETWEEN 0 AND 1),
  CONSTRAINT chk_code_batch_counts CHECK (total > 0 AND used >= 0 AND used <= total)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '兑换码批次（明文不落库；export_state 保证明文 CSV 只导出一次）';

-- 码行：一行=一个码。code_hash 全局唯一（跨批也唯一——撞哈希=撞明文，重生成）。
CREATE TABLE coupon_code (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  batch_id    BIGINT      NOT NULL COMMENT '所属批次 id',
  code_hash   VARCHAR(64) NOT NULL COMMENT 'HMAC-SHA256(pepper, 规范化码) hex——无 pepper 不可逆推明文',
  status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0 ACTIVE 1 LOCKED(兑换中) 2 USED 3 DISABLED',
  user_id     BIGINT      DEFAULT NULL COMMENT '兑换成功后回填的兑换人',
  redeem_time DATETIME(3) DEFAULT NULL COMMENT '兑换成功时间',
  version     BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本（状态机 CAS 时同步自增）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
      ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_code_hash (code_hash),
  KEY idx_batch_id (batch_id),
  CONSTRAINT chk_code_status CHECK (status BETWEEN 0 AND 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '兑换码（只存哈希；ACTIVE→LOCKED→USED 单向 + LOCKED 回 ACTIVE 的失败回收）';

-- 兑换流水：一次兑换尝试一行（成功/失败都记），运营对账与防枚举分析的数据源。
-- batch_id/code_id/app_id 可空：限流拦截、码不存在等未解析出码的失败没有这些维度。
CREATE TABLE coupon_code_redeem_log (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  batch_id    BIGINT      DEFAULT NULL COMMENT '所属批次 id（未解析出码的失败为 NULL）',
  code_id     BIGINT      DEFAULT NULL COMMENT '码行 id（同上）',
  app_id      VARCHAR(32) DEFAULT NULL COMMENT '发起兑换的接入方（开放面=调用方）',
  user_id     BIGINT      NOT NULL COMMENT '兑换人',
  request_id  VARCHAR(64) NOT NULL COMMENT '调用方幂等键（重试复用同一个）',
  result      TINYINT     NOT NULL COMMENT '1 成功 2 失败',
  fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因（not_found/rate_limited/used/…；防枚举下只进日志不进响应）',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_batch_create (batch_id, create_time),
  CONSTRAINT chk_redeem_result CHECK (result BETWEEN 1 AND 2)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '兑换流水（成功/失败各记一行；失败原因进日志不进响应，防枚举）';
