-- V16（S2 第 10 步，2026-10-05）：预案套用记录。
-- 设计依据：docs/S2-第10步-租户级开关与三级预案-修正设计-2026-10-05.md §5.3。
--
-- 为什么需要**预案级**记录（键级审计 open_app_config_log 已经有了）：
-- 键级日志能回答"这个键被谁改成什么"，但回答不了：
--   · 我当时套的是哪一份预案？
--   · 这份预案**应该**改哪几个键（全量物化清单）？
--   · 每个键的**原值**是什么（恢复要靠它）？
-- 恢复（快照补偿回滚）与事后复盘都需要这个上下文，所以单开一张表。
--
-- 一处刻意的设计：snapshot 用 TEXT 存 JSON 而不是 MySQL 的 JSON 类型。
-- 理由：这张表的读取只有"取出快照按原样回滚"一种用途，不需要在 SQL 里查 JSON 内部字段；
-- 用 TEXT 少一层类型耦合，也让本项目的迁移脚本保持"纯 DDL、无方言特性"的一致风格。

CREATE TABLE open_app_plan_apply (
  id          BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id      VARCHAR(32)   NOT NULL COMMENT '被套用预案的租户',
  plan_level  VARCHAR(16)   NOT NULL COMMENT '预案级别：BLUE / YELLOW / RED',
  operator    VARCHAR(64)   NOT NULL COMMENT '操作者（管理面令牌标识 + 来源 IP；演练脚本会带 drill 标记）',
  reason      VARCHAR(128)  DEFAULT NULL COMMENT '备注（演练与人工操作靠它区分）',
  snapshot    TEXT          NOT NULL COMMENT '套用前快照（JSON：{"配置键": 旧值 或 null}）——恢复的依据',
  applied_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reverted_at DATETIME(3)   DEFAULT NULL COMMENT '恢复时刻（NULL = 仍在生效）',
  status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=生效中 2=已恢复 3=被新的套用取代',
  PRIMARY KEY (id),
  KEY idx_app_status (app_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预案套用记录（S2 第 10 步：快照补偿回滚的依据）';
