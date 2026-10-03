-- V15（S2 租户策略，2026-10-04）：租户配置表。
-- 设计依据：docs/S2-流量治理与租户策略-需求与设计-2026-10-04.md §4.1。
-- 为什么是"键值对"而不是"每个配置一列"：配置项会不断增加（限流算法、预热时长、熔断阈值……），
-- 每加一项就改表结构不现实。代价是类型错误只能在读取时暴露 —— 所以读取服务必须
-- "解析失败回落默认值并记 WARN"，绝不让一个写错的配置把请求打挂。

CREATE TABLE open_app_config (
  id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id       VARCHAR(32)  NOT NULL COMMENT '归属接入方（租户）',
  config_key   VARCHAR(64)  NOT NULL COMMENT '配置键，如 rate_limit.qps（词表见 TenantConfigKey）',
  config_value VARCHAR(256) NOT NULL COMMENT '配置值（统一按字符串存，读取时按类型解析）',
  update_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_key (app_id, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户策略配置（S2：把租户从"身份"变成"可运营的策略"）';

-- 配置变更审计（S2 第 2 步）：租户配置直接决定"这个租户能被限多少、超时多久"，
-- 改错了就是一次线上事故，必须能回答"谁在什么时候把哪一项从什么改成了什么"。
CREATE TABLE open_app_config_log (
  id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  app_id       VARCHAR(32)  NOT NULL COMMENT '被改的租户',
  config_key   VARCHAR(64)  NOT NULL COMMENT '被改的配置键',
  old_value    VARCHAR(256) DEFAULT NULL COMMENT '变更前的值（NULL=原先没有这一项）',
  new_value    VARCHAR(256) DEFAULT NULL COMMENT '变更后的值（NULL=删除该项）',
  operator     VARCHAR(64)  NOT NULL COMMENT '操作者（管理面令牌标识 + 来源 IP）',
  create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_app_time (app_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户配置变更审计（S2）';
