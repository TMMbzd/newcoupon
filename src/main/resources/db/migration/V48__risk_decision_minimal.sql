-- ===================================================================
-- V48（V6 阶段 4A 风控最小版，2026-11-05）：名单表 + 决策日志表
--
-- 0) 裁剪线（与 V6 原文 §4A 的差异，全部登记 docs/runbook/风控最小版.md）：
--    原文的规则引擎六表（risk_scene/ruleset/rule/factor/rule_factor/action）、
--    risk_list_hit、risk_signal 本刀不做——最小规则集=代码规则+配置阈值
--    （RiskRule 接口两实现：R_LIST_BLACK 名单 / R_DEVICE_DAILY 设备日限），
--    好处是"决策路径可审计、无热更新复杂度"；升级路径=六表迁移+版本灰度（runbook §裁剪登记）。
-- 1) risk_list_entry：黑/白/灰名单。value 存**明文**——管理面要显示给运营看拉黑的是谁，
--    等值查询列即业务列；与兑换码存哈希不同（名单不是凭据，泄漏面=名单本身）。
--    拖库风险下的 HMAC 化升级路径登记 runbook（列宽 128 已为 HMAC 摘要留足空间）。
-- 2) risk_decision_log：决策日志=回放的根基。所有被评估规则的因子快照（factors_json）
--    与命中规则（matched_rules）全部落库——"命中日志可解释、可复核"的物理形态。
--    开关关时不写行（回归零变化承诺，用例钉住）。
--
-- 两表全部非分片（风控域无租户数据量级压力；分片的是 user_coupon_0..63 / coupon_usage_0..63）。
-- 只加不改：本迁移不触碰任何既有表。
-- ===================================================================

-- ── ① 名单（黑/白/灰）────────────────────────────────────────────
--    app_id 用 NOT NULL DEFAULT '' 表示平台级（空串=对所有租户生效）而不是 NULL：
--    MySQL 唯一索引对 NULL 的语义是"NULL 互不相等"——若允许 NULL，同一平台级
--    (list_type,value_type,value) 可以无限重复插入，唯一键形同虚设。空串参与比较，
--    唯一键才真的唯一。应用层把空串与"平台级"互相翻译（PO appId='' ↔ 视图 null）。
CREATE TABLE risk_list_entry (
  id           BIGINT       NOT NULL COMMENT '雪花ID（SnowflakeIdGenerator，应用侧赋值）',
  list_type    VARCHAR(8)   COLLATE utf8mb4_bin NOT NULL COMMENT '名单类型：BLACK=黑名单（REJECT）/WHITE=白名单（PASS 短路）/GRAY=灰名单（REVIEW）',
  value_type   VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL COMMENT '值维度：USER_ID/DEVICE_ID/IP',
  value        VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '名单值（明文存储，管理面需可读；等值查询；HMAC 化升级路径见 runbook）',
  app_id       VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL DEFAULT '' COMMENT '租户（须真实存在于 open_app）；空串=平台级（对所有租户生效）——NOT NULL 是为让下方唯一键对平台级条目也成立（MySQL 唯一索引 NULL 互不相等）',
  reason       VARCHAR(255) NOT NULL COMMENT '拉黑/放行原因（必填；申诉与复核的第一现场）',
  expires_at   DATETIME     DEFAULT NULL COMMENT '过期时间（NULL=永久）；过期后命中不生效（查询侧过滤，不改行）',
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=生效 / DISABLED=停用（软删，保留申诉与审计现场）',
  created_by   VARCHAR(64)  NOT NULL COMMENT '操作人（管理面会话用户名 / SYSTEM）——不由请求自报',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '停用等变更时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_list_type_value_app (list_type, value_type, value, app_id),
  KEY idx_value_status (value_type, value, status),
  KEY idx_app_status (app_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '风控名单（V6 4A 最小版）：黑/白/灰 × 用户/设备/IP × 平台级/租户级；明文值+软删';

-- ── ② 决策日志（回放的根基）──────────────────────────────────────
--    开关关=零行（不写日志，热路径零额外 IO）；开=每次决策一行，同步写
--    （异步丢日志=回放失真；写失败不挡业务，ERROR+指标，见 RiskDecisionService）。
CREATE TABLE risk_decision_log (
  id            BIGINT       NOT NULL COMMENT '雪花ID（SnowflakeIdGenerator）',
  scene         VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL COMMENT '决策场景：RECEIVE=领券（4A 已接线）/LOCK/CONFIRM/REFUND=预留（刀 2 按场景逐个接）',
  app_id        VARCHAR(32)  COLLATE utf8mb4_bin DEFAULT NULL COMMENT '决策归属租户（领券场景=券模板归属 app；NULL=无租户上下文的内部调用）',
  user_id       BIGINT       DEFAULT NULL COMMENT '业务用户（R_LIST_BLACK 的 USER_ID 维度）',
  device_id     VARCHAR(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '设备号（X-Device-Id 透传，客户端可换——透传维度不是可信指纹，商用指纹 SDK 只留端口）',
  ip            VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP（容器解析的 getRemoteAddr，与 RiskControlFilter 同一提取规则）',
  factors_json  TEXT         COMMENT '因子快照（各被评估规则的输入与结果，LinkedHashMap 序：回放可解释性的来源）',
  matched_rules VARCHAR(255) DEFAULT NULL COMMENT '命中（产出了动作）的规则 ID 逗号分隔，如 R_LIST_BLACK,R_DEVICE_DAILY',
  action        VARCHAR(16)  COLLATE utf8mb4_bin NOT NULL COMMENT '最终动作：PASS/CHALLENGE（本刀无规则产出，挑战 token 占位见 RiskAction）/REVIEW/REJECT',
  degraded      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=因子不完整仍出决策（fail-open）：设备头缺失 / 设备计数 Redis 不可用',
  trace_id      VARCHAR(64)  DEFAULT NULL COMMENT '链路 id（MDC，与响应头 X-Trace-Id 同源——排障入口）',
  request_id    VARCHAR(64)  DEFAULT NULL COMMENT '幂等键（领券=ReceiveRequest.requestId；同一意图重放可按它聚决策）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '决策时间',
  PRIMARY KEY (id),
  KEY idx_scene_created (scene, created_at),
  KEY idx_app_action_created (app_id, action, created_at),
  KEY idx_trace (trace_id),
  KEY idx_request (request_id),
  KEY idx_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin
  COMMENT = '风控决策日志（V6 4A 最小版）：因子快照全量落库；回放端点按快照只读重算当前规则集，不改原行';
