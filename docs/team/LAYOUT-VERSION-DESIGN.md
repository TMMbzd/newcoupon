# 库存 layout_version 与扩片设计（V37 起）

> 来源：architect 只读设计（2026-10-25），Lead 采纳。实现分两片：
> A = V37 + 热路径版本 fence + 发布/对账/调整接线；B = 离线 reshard 工具 + V38 旧唯一键退役。
> 本文件是团队实现契约，不替代代码事实。

## 0. 决策

版本化不可变布局（versioned immutable layout）：

- 每个 `coupon_stock_shard` 行属于一个 `layout_version`；
- 只有 `coupon_template.stock_layout_version` 指向的版本可卖；
- 退役版本保留只读，`quota = used`，剩余额度搬到新版本；
- 不搬 `used`，只搬剩余额度，避免在版本之间移动已售数量。

全局不变量：

- `Σquota(all versions) = coupon_template.total_count`
- `Σused(all versions) = COUNT(user_coupon)`
- 每行 `used + reserved <= quota`；退役行 `status=1, quota=used`。

这样 `GateRebuildService` 的 `Σquota−Σused−inFlight` 公式不变，回滚/审计确定。

## 1. V37 schema

`src/main/resources/db/migration/V37__stock_layout_version.sql`：

```sql
ALTER TABLE coupon_stock_shard
  ADD COLUMN layout_version INT NOT NULL DEFAULT 1
    COMMENT '库存布局版本；仅 template.stock_layout_version 对应版本可扣减' AFTER template_id,
  ADD COLUMN reserved BIGINT NOT NULL DEFAULT 0
    COMMENT 'Phase2 TCC 预占；Phase1 恒 0',
  ADD UNIQUE KEY uk_tpl_layout_shard (template_id, layout_version, shard_no),
  ADD KEY idx_tpl_layout_status (template_id, layout_version, status);
-- 保留 uk_tpl_shard(template_id, shard_no) 一个发布周期
```

- `DEFAULT 1` 回填所有现有行；现有模板的 `stock_layout_version` 都是 1。
- 保留 `uk_tpl_shard`，V37-aware 代码上线前旧代码仍可按 `(template_id, shard_no)` 工作。
- 第一次 reshard 前执行 `V38__drop_legacy_shard_unique.sql`：删除 `uk_tpl_shard`；`StockReshardService` 必须检查 `information_schema.statistics`，旧索引还在就拒绝 reshard。
- `reserved` 现在加，Phase 1 恒 0；Phase 2 不再二次 ALTER。

## 2. 热路径版本 fence

`CouponIssueTxService.issueC1` 增加 `layoutVersion`、`shardNo` 入参。事务内重新读 DB 模板后、任何计数/库存更新前：

```java
if (layoutVersion != current.getStockLayoutVersion()) {
    throw new StockLayoutChangedException(...);
}
if (shardNo < 0 || shardNo >= current.getStockShardCount()) {
    throw new BizException(ErrorCode.DEGRADED, "库存布局已变化，请刷新后重试");
}
```

`CouponStockShardMapper` 的所有按分片操作增加 `layout_version` 条件：

- `casDeduct(templateId, layoutVersion, shardNo)`
- `casAddQuota` / `casReduceQuota` / `updateStatusAll` / `selectByTemplateVersion` / `selectByTemplateVersionForUpdate`
- `sumQuota/sumUsed` 保持全局（所有版本），供 gate 重建；新增 `sumQuotaVersion/sumUsedVersion`、`countActive`。

`C1ReceiveAppService` 从同一模板快照计算 `effective` 和 `layoutVersion`，传给 issue；捕获 `StockLayoutChangedException` 后失效模板缓存、重读、重路由一次；再变则返回 5001。冻结/reshard 窗口 `countActive==0` 仍返回 `FROZEN` 5002。

异步路径：`CouponIssueTaskProcessor` 执行时重读模板，派生 `layoutVersion`/`effective`；`CouponIssueTaskTxService`、`CouponIssueTaskBatchTxService.BatchItem`、`CouponIssueTaskRawBulkWriter` 都携带版本。raw-bulk SQL：

```sql
UPDATE coupon_stock_shard SET used = used + ?, version = version + 1
WHERE template_id = ? AND layout_version = ? AND shard_no = ?
  AND status = 0 AND used + ? <= quota
```

处理器遇到 `StockLayoutChangedException` 时 `retry` 到 PENDING（不是 REJECTED），不扣库存；耗尽后 DEAD，由现有 gate 补偿释放。

## 3. In-flight

- 同步读到 v1、reshard 提交 v2：issue 在计数/扣库存前抛 `StockLayoutChangedException`，不扣；重试 v2 或 5001。
- 异步 PENDING：`seq` 与布局无关；冻结期间处理器应 retry，不标 `STOCK_OUT`；解冻后在 v2 执行。
- 异步 PROCESSING：reshard 事务对 active 行 `FOR UPDATE`，等待本地事务提交/回滚后读最终 `used`；迟到的旧版本 `casDeduct` 命中退役行（`status=1, quota=used`）返回 0 行并回滚。软 drain（PROCESSING 清零，最多 30s）只用于干净日志，正确性不依赖它。
- reshard 后 `issueGate.invalidate(templateId)`；全局 `Σquota−Σused` 不变，gate 重建仍扣 in-flight。

## 4. 离线 reshard（Slice B）

`StockReshardService.reshard(templateId, targetShardCount, requestId, operator)`：

1. `c:lock:stock-reshard:{templateId}`，TTL 30 分钟；`coupon_stock_adjust_log(template_id, request_id)` 幂等。
2. 只冻结 active version：`updateStatusAll(templateId, activeV, 1)`；afterCommit 失效模板缓存、`c:so`、gate。
3. 软等 PROCESSING 清零（最多 30s），记录计数；不等待 PENDING。
4. 事务：
   - 读模板，要求已发布；`activeV = stockLayoutVersion`。
   - `SELECT ... FOR UPDATE` 锁 active 行（按 shard_no 排序）；读 `Q=Σquota, U=Σused`。
   - 两段式 `U == COUNT(user_coupon)`；不一致 sleep 200ms 重读，第二次仍不一致则 abort。
   - `remaining = Q-U`；0 则拒绝。
   - `newN = min(target, remaining)`；按 `StockShardPlan.quotas(newN, remaining)` 分配。
   - CAS 模板：`casBumpLayoutVersion(id, from=activeV, to=activeV+1, count=newN, expectedTotal=Q)`；0 行回滚。
   - 退役旧行：`status=1, quota=used, version=version+1`；CHECK `used<=quota`。
   - 插入 v+1 行，`status=0, used=0`。
   - 断言全局 `Σquota=Q`、`Σused=U`、active 行数=newN。
   - 写 adjust-log，reason 含 `RESHARD vN->vN+1 countX->Y`，before/after JSON 带 version。
5. 提交；afterCommit 失效缓存/gate/售罄标志。
6. 模板仍发布则 `updateStatusAll(newV, 0)`；失败则保持冻结（安全）并重试。

回滚：

- 提交前：事务回滚 + 解冻旧版本。
- 提交后、v2 尚未出券：同锁下冻结 newV，断言 `Σused(newV)=0`，删新行，`UPDATE old rows SET quota=used+remainingNew, status=0`，CAS 模板回旧版本，审计。
- v2 已出券后拒绝回滚，只能 forward-fix 到 v3。

## 5. 发布/计数/对账

- `stock_shard_count` 是 active layout 的 N_eff，不是不可变配置；发布读取 `stockLayoutVersion`（默认 1）建 v1 行。
- 新模板 version=1；旧模板 V37 默认 1，reshard 前不变。
- reshard 在同一 CAS 改 version + count。
- `C1StockReconcileService`：全局 `Σquota==total`、`Σused==COUNT(user_coupon)`；active 行数 `max(1, stockShardCount)`、状态合法、每行 `used<=quota`；退役行 `status=1 && quota==used`；drift 详情带 `layoutVersion`。
- `StockShardAdjustService` 只调整 active version；全局 `Σquota==target`、`target>=Σused`；审计带 version。
- `TemplateLifecycleService.freeze/unfreeze/offline/reonline` 只动 active version。
- `GateRebuildService` 全局 sums 不变。

## 6. Phase 2 预约 fence

Phase 2 的 `coupon_stock_reservation` 必须有：

- `shard_no INT NOT NULL`
- `layout_version INT NOT NULL`
- unique `(app_id, user_id, request_id)`
- index `(template_id, layout_version, shard_no, status)`

Try 在同一控制库事务读 active version，`UPDATE ... WHERE template_id=? AND layout_version=? AND shard_no=? AND status=0 AND used+reserved+1<=quota`，reservation 写入该 version。Confirm/Finalize/Cancel 的库存更新都带 reservation 的 version/shard；0 行 → `STUCK`，绝不盲重试新版本。Reshard 前要求 `Σreserved(active)==0`；退役 `quota=used` 只在 `reserved=0` 时成立。

## 7. 测试

单元：`StockLayoutVersionTest`（stale version 不扣、退役行 quota=used、全局 quota 不变量）。

集成：`StockReshardIntegrationTest`（publish v1、stale version 不扣、N→M 保总量、PENDING 存活、200 并发不超卖、rollback、per-version reconcile、adjust/freeze 只动 active）。

回归：`C1StockIntegrationTest` 17 例、`AsyncIssueWorkerIntegrationTest`、`AsyncIssueAcceptanceIntegrationTest`、`SchedulerLockIntegrationTest`、`PricingSnapshotIntegrationTest`、`DailyBillIntegrationTest`。

全量只由 verifier 跑。

## 8. 风险/延后

- V38 删除 `uk_tpl_shard` 是硬门；旧实例还在跑会破坏旧查询。旧索引存在时 reshard 必须拒绝。
- 退役行不能删，除非以后有聚合账本。
- raw-bulk SQL 必须带 version，否则 reshard 后批量误报售罄。
- `used` 与 `user_coupon` 两次核对仍不一致要 abort。
- 延后：管理 HTTP 端点、自动 reshard、旧版本归档、独立 `configured_shard_count`。
- 不延后：V37 列 + 热路径 fence + 全局不变量 + Phase 2 reservation 字段。
