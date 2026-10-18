-- ===================================================================
-- V23 环 2：试算价格快照 pricingId（2026-10-18）
--
-- 1) coupon_pricing_snapshot：独立非分片表。
--    - pricing_id 是返回给调用方的报价 ID（P + 128 位随机串，不可枚举）；
--    - live_digest：ACTIVE 时 = request_digest，过期后置 NULL。
--      MySQL 唯一索引允许多个 NULL，因此“过期后重建”可以产生新行、新 pricingId，
--      历史行保留审计，不会因为同摘要唯一键被迫删旧快照；
--    - payload 用 MEDIUMTEXT 存 JSON 文本（与 V16/V18 的 TEXT 存 JSON 惯例一致；
--      100 行 × 10 券的行级分摊可能超过 TEXT 的 64KB，故用 MEDIUMTEXT）；
--    - 不碰 coupon_usage 的 64 张分表（与 V22 同一条裁决）。
--
-- 2) coupon_pricing_usage：占用单与快照的绑定（lock 同事务写入）。
--    - 回答“这次锁券到底用了哪份快照”；
--    - confirm 漏传 pricingId 时按绑定自动解析，避免重新按当前模板算价；
--    - confirm 传了与绑定不同的 ID → 4007。
--
-- 3) coupon_share_detail.pricing_id：核销时记录用了哪份快照，供审计追溯；
--    老式核销/不带 pricingId 的调用方为 NULL。
--
-- 4) coupon_confirm_exception：支付后核销异常。
--    用户已支付但快照校验失败时，占用单进入 CONFIRM_EXCEPTION；
--    券保持 LOCKED，不进入 30 分钟超时释放（否则“已付款 + 券回可用”=双重优惠）；
--    人工核实订单后通过管理面 CONFIRM（按快照原价）或 RELEASE（订单已退款）解除。
-- ===================================================================

CREATE TABLE coupon_pricing_snapshot (
  id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键（不对外）',
  pricing_id            VARCHAR(64)  NOT NULL COMMENT '价格快照ID（P + 128位随机串，对外返回，不可枚举）',
  app_id                VARCHAR(32)  NOT NULL COMMENT '租户/接入方（内部面为内置应用1）',
  user_id               BIGINT       NOT NULL COMMENT '用户id（租户内语义）',
  order_no              VARCHAR(64)  NOT NULL COMMENT '订单号',
  coupon_set            VARCHAR(512) NOT NULL COMMENT '快照中实际可用的券ID，升序逗号分隔（L70 券集合校验）',
  request_digest        CHAR(64)     NOT NULL COMMENT '请求摘要（canonical SHA-256）',
  live_digest           CHAR(64)     DEFAULT NULL COMMENT 'ACTIVE 时=request_digest；过期后置 NULL，允许同摘要重建',
  payload               MEDIUMTEXT   NOT NULL COMMENT '快照JSON：行/券规则/每券每行分摊/主体/扩展决策',
  payload_version       INT          NOT NULL DEFAULT 1 COMMENT 'payload 结构版本（不兼容时 fail-closed）',
  engine_version        VARCHAR(32)  NOT NULL COMMENT '计算/分摊算法版本（审计与回放）',
  payload_bytes         INT          NOT NULL COMMENT 'payload 字节数（容量监控/上限防线）',
  line_count            INT          NOT NULL COMMENT '商品行数',
  applied_coupon_count  INT          NOT NULL COMMENT '实际参与优惠的券数',
  original_amount       BIGINT       NOT NULL COMMENT '订单原价（分）',
  discount_amount       BIGINT       NOT NULL COMMENT '总优惠（分）',
  final_amount          BIGINT       NOT NULL COMMENT '应付（分）',
  expires_at            DATETIME(3)  NOT NULL COMMENT '权威过期时间；锁券时原子续期到锁窗+宽限',
  last_bound_at         DATETIME(3)  DEFAULT NULL COMMENT '最近一次锁券绑定时间（清理保护/运维）',
  create_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pricing_id (pricing_id),
  UNIQUE KEY uk_live_digest (app_id, user_id, order_no, live_digest),
  KEY idx_expire (expires_at),
  KEY idx_app_order (app_id, user_id, order_no, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '试算价格快照（环2；独立非分片表）';

CREATE TABLE coupon_pricing_usage (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  usage_no    VARCHAR(64) NOT NULL COMMENT '占用单号（全表唯一）',
  pricing_id  VARCHAR(64) NOT NULL COMMENT '价格快照ID',
  app_id      VARCHAR(32) NOT NULL COMMENT '券的租户归属',
  user_id     BIGINT      NOT NULL COMMENT '用户id',
  order_no    VARCHAR(64) NOT NULL COMMENT '订单号',
  coupon_id   BIGINT      NOT NULL COMMENT '用户券id',
  bound_at    DATETIME(3) NOT NULL COMMENT '绑定时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pricing_usage (usage_no),
  KEY idx_pricing_usage_pricing (pricing_id),
  KEY idx_pricing_usage_coupon (coupon_id, bound_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '占用单与价格快照的绑定（lock 同事务写入）';

ALTER TABLE coupon_share_detail
    ADD COLUMN pricing_id VARCHAR(64) DEFAULT NULL
        COMMENT '核销使用的价格快照ID（老式核销/未启用快照为NULL）'
        AFTER settle_party_id;

ALTER TABLE coupon_share_detail
    ADD INDEX idx_share_pricing (pricing_id);

CREATE TABLE coupon_confirm_exception (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  usage_no        VARCHAR(64)  NOT NULL COMMENT '占用单号',
  app_id          VARCHAR(32)  NOT NULL COMMENT '券的租户归属',
  user_id         BIGINT       NOT NULL COMMENT '用户id',
  user_coupon_id  BIGINT       NOT NULL COMMENT '用户券id',
  order_no        VARCHAR(64)  NOT NULL COMMENT '订单号',
  pricing_id      VARCHAR(64)  DEFAULT NULL COMMENT '失败时绑定/携带的价格快照ID（可能为空）',
  reason          VARCHAR(512) NOT NULL COMMENT '进入异常的原因（快照过期/归属不符/券集合不一致等）',
  resolve_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/RELEASED',
  resolve_reason  VARCHAR(512) DEFAULT NULL COMMENT '人工处理说明',
  resolve_time    DATETIME(3)  DEFAULT NULL COMMENT '人工处理时间',
  create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_exception_usage (usage_no),
  KEY idx_exception_status (resolve_status, create_time),
  KEY idx_exception_order (app_id, user_id, order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付后核销异常（暂停超时释放，人工处理）';
