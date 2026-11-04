-- ===================================================================
-- V47（V6 3min 结算最小版，2026-11-05）：L76 质量列 + 主体账单应付口径 + settlement_hold 冻结挂点
--
-- 1) coupon_share_detail / coupon_refund_detail 加 quality_flags（位掩码）+ settle_eligible：
--    L76 清偿——历史 __default__ 行里"平台券合法空主体"与"商家主体丢失"终于可区分。
--    settle_eligible=0 的行不进真实结算口径（两张日账单聚合同步过滤，等式仍成立）。
--    位语义（常量类 com.newcoupon.domain.settle.SettleQualityFlags，注释与代码同源）：
--      bit0 LEGACY_DEFAULT=1     商家/共担出资的钱落在 __default__ 主体（主体丢失）→ 不进真实结算
--      bit1 NO_ITEMS=2           老式订单级分账行（order_line_no IS NULL，无行级 items；
--                                与 coupon_refund_order.funding_known=0 同族的"粗粒度"标记。
--                                出资拆分真实，保持可结算——只降观测粒度，不排除）
--      bit2 FUNDING_UNKNOWN=4    出资方不明的退款明细（关联退款单 funding_known=0）→ 不进真实结算
--      bit3 ORPHAN_TEMPLATE=8    模板主数据缺失（保留位：写入路径防御性使用，回填未涉及）
--      bit4 NEGATIVE_ADJUSTMENT=16 负账单调整（保留位：3B 调整单的领域，本刀不做）
-- 2) 历史行回填：三段 UPDATE，判定口径与新写入路径完全一致（测试用同款 SQL 复验判定逻辑）。
-- 3) coupon_settle_party_daily_bill 加 hold/payable/质量桶计数四列。
-- 4) coupon_settlement_hold 新表（单表不分片）：4B 风控的冻结挂点。
--
-- 分片核实（决定迁移写法）：coupon_share_detail（V9）/coupon_refund_detail（V21）都是
-- 单表 CREATE，从未分片（分片的是 user_coupon_0..63 / coupon_usage_0..63）——单条 ALTER 即可，
-- 不存在逐物理表执行的问题。
--
-- 生产在线 DDL 纪律（照 V28 先例）：两明细表是核销/退款热路径的写入表，加列带默认值
-- 在 MySQL 8 是 INSTANT DDL（不重建表），但回填 UPDATE 全表扫描——生产先在预发演练，
-- 低峰窗口执行；本地/Testcontainers 由 Flyway 直接执行。
-- ===================================================================

-- ── ① 质量列（两张明细表同构）────────────────────────────────────
ALTER TABLE coupon_share_detail
    ADD COLUMN quality_flags INT     NOT NULL DEFAULT 0
        COMMENT '质量位掩码（SettleQualityFlags：1=商家钱落__default__ 2=老式订单级 4=出资不明 8=模板缺失 16=负调整保留）'
        AFTER share_total,
    ADD COLUMN settle_eligible TINYINT NOT NULL DEFAULT 1
        COMMENT '1=进真实结算口径（日账单聚合）；0=质量标记排除（历史 __default__ 商家钱/出资不明）'
        AFTER quality_flags;

ALTER TABLE coupon_refund_detail
    ADD COLUMN quality_flags INT     NOT NULL DEFAULT 0
        COMMENT '质量位掩码（同 coupon_share_detail；4=出资方不明最常见）'
        AFTER refund_merchant_amt,
    ADD COLUMN settle_eligible TINYINT NOT NULL DEFAULT 1
        COMMENT '1=进真实结算口径；0=质量标记排除（关联退款单 funding_known=0 等）'
        AFTER quality_flags;

-- ── ② 历史回填：判定需要 coupon_type——JOIN coupon_template 取 funder_type（1平台 2商家 3共担）。
--    商家/共担券的分账/退款行落在 __default__ = 主体丢失（灰度关期间的写入、V22 前的老行），
--    这正是 L76 的核心场景；平台券 __default__ 是合法空主体，不动。
UPDATE coupon_share_detail s
  JOIN coupon_template t ON t.id = s.template_id
   SET s.quality_flags = s.quality_flags | 1,
       s.settle_eligible = 0
 WHERE s.settle_party_id = '__default__'
   AND t.funder_type IN (2, 3);

UPDATE coupon_refund_detail d
  JOIN coupon_template t ON t.id = d.template_id
   SET d.quality_flags = d.quality_flags | 1,
       d.settle_eligible = 0
 WHERE d.settle_party_id = '__default__'
   AND t.funder_type IN (2, 3);

-- 老式订单级分账行（无行号）：NO_ITEMS 只降观测粒度——出资拆分是真实的（split 来自平台计算），
-- 不排除出账单。平台券订单级行的主体也是合法 __default__，上面两段不会碰它。
UPDATE coupon_share_detail
   SET quality_flags = quality_flags | 2
 WHERE order_line_no IS NULL
   AND quality_flags & 2 = 0;

-- 出资不明的退款明细（关联退款单 funding_known=0）：老式退款本来不写明细行，
-- 这段是防御性口径（未来若有"写明细但出资不明"的路径，回填与新写入同一判定）。
UPDATE coupon_refund_detail d
  JOIN coupon_refund_order o ON o.refund_no = d.refund_no
   SET d.quality_flags = d.quality_flags | 4,
       d.settle_eligible = 0
 WHERE o.funding_known = 0;

-- ── ③ 主体账单加 hold/payable/质量桶计数 ──────────────────────────
--    payable = discount_total - hold_amount，负数允许并如实展示（负 payable=该主体有
--    未释放 hold 超过当期净额，财务需人工关注；钳制会掩盖问题）。生成时算好存列，
--    重跑幂等覆盖。存量已生成的账单行四列为默认 0——补数重跑该日即可刷新（upsert 覆盖）。
ALTER TABLE coupon_settle_party_daily_bill
    ADD COLUMN hold_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '生成时命中的 ACTIVE hold 扣减额（分）：主体级 hold 对该主体每个模板行全额扣减，模板级只扣对应行'
        AFTER merchant_burden,
    ADD COLUMN payable_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '应付净额（分）=discount_total-hold_amount；负数如实展示=未释放 hold 超当期净额，需人工关注'
        AFTER hold_amount,
    ADD COLUMN ineligible_share_count INT NOT NULL DEFAULT 0
        COMMENT '当日被质量标记排除的分账行数（settle_eligible=0；可观测，金额不计入本行）'
        AFTER payable_amount,
    ADD COLUMN ineligible_refund_count INT NOT NULL DEFAULT 0
        COMMENT '当日被质量标记排除的退款明细行数（settle_eligible=0；可观测）'
        AFTER ineligible_share_count;

-- ── ④ 券侧应付冻结（4B 风控挂点；单表不分片）───────────────────────
--    语义边界：hold 只改账单 payable 口径（分账-退款-hold），不动真实资金、不影响用户退款。
CREATE TABLE coupon_settlement_hold (
  id              BIGINT       NOT NULL COMMENT '雪花ID（SnowflakeIdGenerator，应用侧赋值）',
  hold_no         VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '冻结单号（幂等锚点；同号重放返回已存在的冻结，不新建）',
  app_id          VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '租户/接入方（大小写敏感；须真实存在于 open_app）',
  settle_party_id VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL COMMENT '结算主体（大小写敏感；账单按 (app_id,主体) 命中扣减）',
  template_id     BIGINT       DEFAULT NULL COMMENT '模板级冻结（NULL=主体级：该主体全部模板行的应付都扣这笔；非空只扣该模板行）',
  amount          BIGINT       NOT NULL COMMENT '冻结金额（分，>0；只影响账单应付口径，不动真实资金/不影响用户退款）',
  reason          VARCHAR(255) NOT NULL COMMENT '冻结原因（必填；审计与财务复核的第一现场）',
  source          VARCHAR(16)  NOT NULL COMMENT '来源：RISK_RULE=风控规则（阶段4B接入）/MANUAL=人工',
  status          VARCHAR(16)  NOT NULL COMMENT '状态机：ACTIVE（生效中，账单 payable 扣减）→RELEASED（已释放）或 VOIDED（已作废）；终态不可再迁移',
  created_by      VARCHAR(64)  NOT NULL COMMENT '创建人（管理面会话用户 / 风控规则标识）',
  released_by     VARCHAR(64)  DEFAULT NULL COMMENT '终态操作人（RELEASED/VOIDED 的收场留痕）',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  released_at     DATETIME     DEFAULT NULL COMMENT '终态时间（RELEASED/VOIDED 共用）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_hold_no (hold_no),
  KEY idx_app_status (app_id, status),
  KEY idx_app_party_status (app_id, settle_party_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '券侧应付冻结（4B 风控挂点；payable=分账-退款-hold；不动真实资金/不影响用户退款）';
