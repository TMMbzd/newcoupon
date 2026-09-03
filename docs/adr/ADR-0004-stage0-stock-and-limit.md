# ADR-0004: 阶段 0 扣库存方案 = 模板行条件更新 + 限领 1 张用唯一索引

- **状态**：已接受（2026-09-03）
- **背景**：docs/01 的最终形态里库存是"Redis Lua 五重校验 + DB 分段账本"，但阶段 0 无 Redis（ADR-0001）。需要一个纯 DB 的正确方案。
- **决策**：
  1. `coupon_template` 增加 `issued_count` 列；扣库存用**条件更新**：
     `UPDATE coupon_template SET issued_count = issued_count + 1 WHERE id = ? AND issued_count < total_count`
     ——影响行数 = 0 即售罄。原子性由 DB 单语句保证，不存在 check-then-act 空窗。
  2. 每人限领：阶段 0 仅支持 1 张，由 `user_coupon` 上的唯一索引 `uk_user_template (user_id, template_id)` 强制——并发插入撞索引即拒绝，数据库层面不可穿透。
  3. 幂等：`uk_idem (user_id, idempotent_key)` 唯一索引终审，DuplicateKey 当成功返回（docs/01a Q9/Q10）。
- **理由**：
  1. 这是 docs/03 第 3.2 节 v1→v2 演进的起点方案（加了守卫的版本），正确性立得住，验收"零超发"可达标；
  2. 唯一索引是"并发下唯一不可穿透的防线"（vivo 论证），阶段 0 把它做成肌肉记忆。
- **后果**：
  - 已知局限（明确接受，写入验收预期）：模板行是热点行，单模板吞吐约几百 QPS——阶段 1 换 Redis+Lua 后消除；
  - `per_user_limit > 1` 的通用限领阶段 0 不支持（模板校验拒绝配置），阶段 1 由 Redis 用户计数解决；
  - `issued_count` 与事件数对账：每笔发放两处都 +1，对账任务（第 3 周）核对恒等式。
