# ADR-0004: 阶段 0 扣库存方案 = 模板行条件更新 + 限领 1 张用唯一索引

- **状态**：部分被取代（2026-09-03 接受；2026-09-05 起**决策 2（限领1张/uk_user_template）与增补 3 的"按(user_id,template_id)查"分类路径已被 [ADR-0006](ADR-0006-redis-lua-stock.md) 取代**——现行：uk_user_seq 限领 N + Redis 计数序号；增补 1 的 SQL 守卫与增补 4/5 仍然有效）
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

## 2026-09-05 增补：扣库存 SQL 扩展与冲突分类（经三路评审后确定）

1. **WHERE 守卫扩展**：条件扣减在 `issued_count < total_count` 之外压入 `publish_status=1 AND approve_status=1`——封住"assertReceivable 校验通过后、扣减前一瞬模板被下线/取消审批"的状态竞态窗口。影响行数=0 时在**同一事务内重读模板分类错误码**：`issued_count>=total_count` → STOCK_OUT(3001)；否则 → TEMPLATE_NOT_RECEIVABLE(2002)，保证错误码语义与真实成因一致（不把"被下线"误报成"售罄"）。
2. **已容忍的时间窗竞态（登记）**：领取期时间条件不压入 WHERE——相对有效期模板（valid_type=2）的领取窗口列阶段 0 未建模（valid_start/valid_end 为 NULL），无法统一压入；容忍"校验后一瞬过期仍领到券"的窗口，阶段 1 补领取窗口列后消除。
3. **唯一索引冲突分类**：撞 DuplicateKeyException 后**不解析异常消息里的索引名**（MySQL 方言依赖，且一次 INSERT 可能同时违反 uk_idem 与 uk_user_template，MySQL 只报其中一个）——改为事务回滚后统一重查分类：先按 (user_id, idempotent_key) 查（命中且 templateId 一致→幂等成功；命中但 templateId 不同→幂等键冲突拒绝），再按 (user_id, template_id) 查（命中→超领）。索引名仅作日志辅助信息。
4. **幂等命中的 templateId 校验**：幂等键绑定的是"一次领取意图"，重放时请求的 templateId 必须与首次一致，不一致视为幂等键冲突（拒绝），绝不当成功返回。
5. **事务边界与编排层纪律**：三写（扣库存/插券/插事件）在独立事务类 CouponIssueTxService（@Transactional）；编排层 ReceiveAppService **严禁再加 @Transactional**——否则内层事务加入外层共享事务，DuplicateKey 将共享事务标记 rollback-only，编排层 catch 后提交抛 UnexpectedRollbackException，幂等重放退化为 500。
