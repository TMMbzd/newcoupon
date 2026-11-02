-- V28（L73，ADR-0019/ADR-0020）：user_coupon 的幂等唯一键补 app_id。
--
-- 旧键 uk_idem(user_id, idempotent_key) 不含租户：两个 app 用同一 user+requestId 领取
-- 会在券表唯一键上冲突（跨租户串数据）。新键为 (user_id, app_id, idempotent_key)。
--
-- 只改 64 张分片表；user_coupon 基表是 V6 的搬迁源、不参与路由，保持不动。
-- 生产环境 64 条 DROP/ADD INDEX 不要直接随应用启动执行：先在预发演练，
-- 用 pt-osc/gh-ost 或低峰窗口逐表执行；本地/Testcontainers 由 Flyway 直接执行。
--
-- 注意：先部署 V28，再部署所有 findByIdempotentKey 已带 appId 的应用版本；
-- 否则旧代码仍按 (user_id, idempotent_key) 查，跨租户重放可能查不到新行。

ALTER TABLE user_coupon_0
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_1
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_2
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_3
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_4
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_5
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_6
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_7
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_8
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_9
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_10
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_11
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_12
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_13
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_14
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_15
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_16
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_17
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_18
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_19
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_20
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_21
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_22
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_23
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_24
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_25
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_26
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_27
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_28
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_29
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_30
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_31
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_32
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_33
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_34
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_35
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_36
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_37
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_38
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_39
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_40
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_41
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_42
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_43
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_44
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_45
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_46
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_47
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_48
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_49
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_50
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_51
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_52
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_53
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_54
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_55
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_56
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_57
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_58
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_59
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_60
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_61
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_62
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);

ALTER TABLE user_coupon_63
  DROP INDEX uk_idem,
  ADD UNIQUE KEY uk_idem (user_id, app_id, idempotent_key);
