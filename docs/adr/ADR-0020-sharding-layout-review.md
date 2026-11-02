# ADR-0020：分库分表布局复核——64 分表是否仍然合理

- **状态**：已评审（2026-10-22）；结论为 reshape，代码未改
- **日期**：2026-10-22
- **关系**：复核 [ADR-0010](ADR-0010-target-tech-stack.md) 的“1 库 × 64 表、未来 16 库”决策；与 [ADR-0018](ADR-0018-stock-model-c1-fixed-shards.md) 的 C1 库存本地事务、[ADR-0019](ADR-0019-async-issue-task-wal.md) 的异步任务表直接相关
- **一句话**：64 是“10 亿张券 + 未来整表搬迁”的容量布局，不是当前吞吐优化；当前单库下它只带来单表体积收益和 64 倍 DDL/广播成本。保留 64，但先修“有 userId 却广播”的路由错配、任务扫描锁和单表无界增长；在 C1 库存与用户分片券的跨库事务方案定稿前，不拆 16 物理库。

---

## 1. 现状

### 1.1 物理布局

- **4 个逻辑表 × 64 张物理表 = 256 张**，全部在 `ds0` 一个 MySQL：
  - `user_coupon_0..63`
  - `coupon_usage_0..63`
  - `coupon_expire_task_0..63`
  - `coupon_release_task_0..63`
- 分片键统一 `user_id`，算法 `user_id % 64`；`bindingTables` 声明四表绑定。
- 其余表通过 `!SINGLE tables: *.*` 全部单表落 `ds0`。
- 当前只有一个物理库，没有 `databaseStrategy`，也没有 `ds1..ds15`。

### 1.2 64 的原始依据

- 目标 10 亿张券 ÷ 64 ≈ **1560 万行/表**，落在“单表千万级是 MySQL 舒适区”的经验区间。
- 未来 16 库时：表号 = `user_id % 64`，库号 = `user_id % 16`；16 整除 64，表号 t 的行只能落库 `t % 16`，所以每个逻辑表是 **16 库 × 4 张物理表 = 64 个 actual node**，不是 16×64=1024 张。
- 扩库时表名不变，但**不是“零迁移”**：要搬 15/16 的数据、加 `databaseStrategy`、决定单表归属、做双写/校验/切流；更关键的是 C1 库存行与用户分片券行可能不在同一库，`扣库存 + 插券` 的本地事务会断裂。

---

## 2. 逐表结论

| 表 | 是否分片 | 理由 | 结论/风险 |
|---|---|---|---|
| `user_coupon` | 64，`user_id % 64` | 10 亿目标券表；“查我的券”是最高频点查；`uk_idem`/`uk_user_seq` 都含 `user_id`，分片内唯一=全局唯一 | **保留**。必修：`findById` 在 26 处调用中广播 64 表，能拿 `userId` 的改 `findByIdForUser`；`uk_idem` 加 `app_id`（L73） |
| `coupon_usage` | 64，`user_id % 64` | 占用单与券同用户维度，核销/退款要和券状态同事务；行数与券同阶 | **保留**。15 个 mapper 方法里 13 个不带 `user_id`；核销一次约 193 条物理 SQL；给 `selectByUsageNo`/CAS 加 `userId` 重载，外部 usageNo-only 入口保留一次广播或路由缓存 |
| `coupon_expire_task` | 64，`user_id % 64` | 每券一行，写入与发券同事务；与券同片保证本地事务 | **暂保留**。扫描谓词是 `status/next_scan_time/shard`，没有 `user_id`，每轮广播 64 表；`finish/backoff` 也广播。先加 `userId` 参数、修扫描锁；分片键是否改时间桶/单表，等扫描成本实测后再定 |
| `coupon_release_task` | 64，`user_id % 64` | 每份占用合同一行，锁定超时兜底；与 usage 同生命周期 | **暂保留**，问题同 expire_task；每任务 64 条 UPDATE，`processOne` 已有 `PO.userId` 却丢掉 |
| `coupon_template` | 单表 | 小配置表，按 id/code 点查；模板数远小于券数 | 正确，不要分 |
| `coupon_stock_shard` | 单表，每模板 N 行 | C1 库存账本；模板内行分片（N 默认 16），不是用户分片 | 正确，不要按 user_id 分 64；热点靠 N/异步，不是表数 |
| `coupon_event` | 单表 | append-only 事实源；按 template/time、activity/time 查询，不按 user 点查 | **最大增长风险**：10 亿券 × 3–6 事件=30–60 亿行，无归档；按 user 分会破坏模板统计。先做 retention/归档和 `(template_id,event_type,occur_time)` 索引 |
| `coupon_outbox` | 单表 | 投递器按 `status,next_scan_time` 全局扫；按 user 分会 64 路广播 | 正确，不要分；已投递行 7–30 天清理，否则随发放无界增长 |
| `coupon_share_detail` | 单表 | 财务按 usage/template/app/time 聚合；按 user 分会广播 | 正确，不要分；按月/时间归档，合并冗余索引 |
| `coupon_refund_order/detail` | 单表 | 退款按 refund_no/usage/template/app/time 查；量小于发券 | 正确，不要分；按时间归档 |
| `coupon_pricing_snapshot` | 单表 | V23 明确非分片；TTL 清理，工作集小 | 不要分；监控过期未删积压，payload 100–300KB |
| `coupon_pricing_usage` | 单表 | 占用单与快照绑定，按 usage/pricing 点查 | **隐蔽无界表**：只有 `selectByUsageNo`，无 delete，`bound_at` 无索引；先加保留期/索引 |
| `coupon_issue_task`（V27 计划） | **单表** | worker 按 `status,next_scan_time` 全局领取；按 user 分会 64 路广播 | 正确，不要 64；按天/id 段清理，索引 `(status,next_scan_time,id)`、`(app_id,status,next_scan_time)` |
| 两张日限计数表（V27 计划） | **单表** | 按 user/template/day 或 template/day 条件更新 | 不要 64；必须 upsert（首行不存在时 `UPDATE` 返回 0 会被误判达限）；补 day 清理索引；模板日限单行热点做分段 |
| `coupon_template`/`coupon_activity`/`audit_log`/`open_*` | 单表 | 小配置/全局审计/租户主数据 | 不要分 |

---

## 3. 关键发现

### 3.1 64 不是吞吐优化

今天 256 张表都在一个 MySQL，拆表不会增加单实例写吞吐。仓库只有 WSL2 104 QPS 的形状验证，没有生产容量数据；5k 的瓶颈是每笔 5 条 DB 写和单 MySQL 总写吞吐，不是库存行锁。**64 是目标态布局假设，不是现状性能结论。**

### 3.2 真正的热点是“有 userId 却广播”

审计统计：

- 一次核销（P0-1/P0-2 当前）：入口归属 `findByUsageNo` 64（外部请求没有 userId 的首次查询，保留）+ 事务内 `findByUsageNo` 64 + `casConfirm` 1 + `findByIdForUser` 1 + 券 CAS 1 ≈ **131 条物理 SQL**；AppService 复算前那次已复用，若再把 userId 传进事务层，可降到约 67；
- 锁券入口：`findById` 64 + `findByUsageNo` 64 + 写入 ≈ **128+ 条 SELECT**；
- 过期扫描单任务 ≈ **129–193 条**；超时收单 ≈ **257 条**。

这些调用方大多已经持有 `userId`（`usage.getUserId()`、`task.getUserId()`、`req.userId()`），只是 mapper 没有带谓词。**先修这个，不需要改 64 张表。**

`coupon_usage` 15 个方法中 13 个无 `user_id`：`selectByUsageNo`、8 个 CAS、`casExpireByCouponId`、3 个状态扫描。`user_coupon` 的 `findById` 有 26 处调用广播，`selectByIdForUser` 已存在却没用上。任务表 `finish/backoff` 也丢掉了 PO 里的 `userId`。

### 3.3 任务表分片键与访问模式相反

`coupon_expire_task`/`coupon_release_task` 是“按时间驱动的跨用户队列”，主查询是 `status + next_scan_time`，不是 `user_id`。按 `user_id` 分片是为了和券/占用单同库同事务，但扫描必然广播 64 表；`shard = coupon_id %16` 只是行过滤，不是物理路由。

同时，扫描锁是全局单键 `c:exp:scanlock:0` / `c:rel:scanlock:0`；XXL-JOB 分片广播时，抢不到锁的实例在算分片前就退出，每轮只有一个作业分片被处理，其余分片饥饿（L16③）。这是比“64 是否合理”更急的吞吐/收敛问题。

### 3.4 单表增长比 64 更急

- `coupon_event`：10 亿券 × 3–6 事件 = 30–60 亿行，无归档；`idx_tpl_time` 不含 `event_type`，`countIssuedByTemplateSince` 是 O(当天事件数) 且被 C1 领券热路径调用。
- `coupon_outbox`：与发放 1:1，`status=1` 不删，无界增长。
- `coupon_share_detail`/`coupon_refund_*`：无归档。
- `coupon_pricing_usage`：无清理，`bound_at` 无索引，可能先于 QPS 把磁盘打满。
- 任务表终态行不清理；V27 `coupon_issue_task` 1k QPS 一天约 8640 万行，必须按天/id 段保留。

### 3.5 16 库会打断 C1 本地事务

`CouponIssueTxService` 在一个 `@Transactional` 里写：

```text
coupon_stock_shard（按 template_id 单表）
user_coupon（按 user_id 分 64 表）
coupon_event / coupon_outbox / coupon_expire_task（单表或 user 分片）
```

今天单库可行。拆 16 库后，同一模板的不同用户会落到不同库，库存行与券行不在同一库，`扣库存 + 插券` 的本地事务不成立。必须先定：

- 库存按库/段分配 quota；
- 或改成最终一致 + 对账；
- 或不拆库。

在这之前，“扩 16 库只改配置、无迁移窗口”的说法必须修正。

### 3.6 唯一键与租户

- `user_coupon.uk_idem(user_id, idempotent_key)` 不含 `app_id`；两个租户同 user+requestId 会撞唯一键（L73）。
- `uk_usage_no`/`uk_coupon` 只在单张物理表内唯一，跨分片全局唯一依赖“同一业务键永远属于同一 user_id”的应用不变式；应写进迁移/修数规范并巡检，**不要为了路由给 `uk_usage_no` 加 user_id**（ShardingSphere 按 SQL 值路由，不按唯一索引前缀路由；加了只会多 64 次 ALTER）。

---

## 4. 决策

### 4.1 保留 64，不缩减、不拆库

- 保留 `user_coupon`、`coupon_usage` 的 `user_id % 64`：10 亿目标下 1560 万/表，算术自洽；改表数 = 重写 256 张表、全量重哈希、改 actualDataNodes/bindingTables/所有 64 次 ALTER，当前不值得。
- `coupon_expire_task`/`coupon_release_task` 暂保留 `user_id % 64`：与券/占用单同库同事务是当前正确性前提；先修 `userId` 谓词和扫描锁，不现在改分片键。
- **不 split 16 物理库**：C1 库存与 user 分片券的跨库事务未解决，当前 104 QPS 也不需要。
- 如果 10 亿目标正式下调到千万级，才考虑 16/32 表；且必须在生产数据量小的时候做，迁移成本高。

### 4.2 新表不再默认 64 分片

- `coupon_event`/`coupon_outbox`/`coupon_share_detail`/`coupon_refund_*`/`coupon_pricing_*` 保持单表，用时间归档/分区/冷存解决增长。
- V27 `coupon_issue_task`、两张日限计数表保持单表，做有界保留和 upsert；不要机械复制 64。
- `coupon_stock_shard` 保持单表，每模板 N 行。

### 4.3 先修路由，不改表数

> **2026-10-23 实施进展**：V27（任务/硬日限计数表，单表）与 V28（L73，64 张 `user_coupon_*` 的 `uk_idem` 补 `app_id`）已落地；**P0-3/P0-4 已实施**（任务 finish/backoff 带 userId、过期扫描 `findByIdForUser`、扫描锁按 shardIndex 拆键）；**P0-5 已实施**：同步 C1 去掉 `checkLimits` 的三次 COUNT，每人总限用 `MAX(seq)`，每人/模板日限收进 `CouponIssueTxService.issueC1` 的 V27 条件计数，同步与异步共用；`C1ReceiveAppServiceTest` 2/2（成功路径不再调用用户 COUNT，并覆盖日限拒绝时幂等回查）、`C1StockIntegrationTest` 14/14（含日限、空洞、回滚与并发用例）；全量 `mvn clean test` 553 例：552 绿 + 1 门控跳过 + 0 失败/0 错误。**P0-6 已实施（2026-10-23）**：新增 `ShardingRouteAssertionTest`，用 `sql-show` 的 `Actual SQL` 计数。`findByIdForUser`、`selectByUsageNoForUpdate`、`selectByOrderNo`、过期任务 `finish/backoff`、释放任务 `finish` 均只命中 1 张物理表；`casConfirm` 等旧 CAS 当时实测仍广播 64 张。**P0-1/P0-2 已实施（2026-10-23）**：`coupon_usage` 的 9 个 CAS 全部补 `user_id`，Trade/Refund/Expiry 链路拿到 usage 后改用 `findByIdForUser`；`casConfirm` 已从 64 降到 1。**P0-1b 已实施（2026-10-24）**：`TradeTxService` 的 `confirm/release/refundConfirm/markConfirmException/expireOnLock` 增加带 `userId` 的重载，`TradeAppService` 把入口广播查到的 `usage.getUserId()` 传下去；事务层、错误收场和退款重放不再做第二次 `coupon_usage` 广播。外部入口只带 `usageNo`，第一次广播不可避免；管理面 `confirmFromException/releaseFromException` 仍只带 `usageNo`，保留低频广播。后台 `scanDueShard` 仍广播 64 张——这是按 `user_id` 分表的扫描代价，暂接受；理由与替代方案见 §4.3.1，不在此处改分片键。V27 编号不再留给 RBAC，RBAC 顺延 V29+。

#### 4.3.1 为什么 `scanDueShard` 保留 64 表广播（P0-1b 后）

- **分片键是 `user_id`**：任务表与 `user_coupon`/`coupon_usage` 同片，领券/锁券时“注册任务”和“写券/占用单”才能落在同一个物理分片、同一个本地事务里。这是当前正确性前提，不能为了扫描方便把任务表改成按 `next_scan_time`/`shard` 分片。
- **扫描谓词没有 `user_id`**：`status=0 AND next_scan_time<=NOW()` 只带时间/状态，ShardingSphere 算不出路由，只能把一条逻辑 SELECT 广播到 64 张物理表。`shard % total = index` 只是把“结果”按作业实例切分，不改变“每个实例仍扫 64 张表”的事实；N 个 XXL-JOB 实例就是 N×64 条扫描 SQL。另需注意：任务行的 `shard` 列取 `coupon_id % 16`，所以 XXL-JOB 分片并行度实际最多 16 路；这是作业分片参数，不是物理表路由键，也不是 64 表广播的原因。当前本地单实例/目标 5k 不需要扩到 16 路以上。
- **成本边界**：扫描是后台每分钟一次、每批 `LIMIT 500`，走 `idx_scan(status, next_scan_time)`；按任务数的 `finish/backoff` 已带 `userId`（P0-3），单任务只命中 1 张表。所以 64 只发生在“发现任务”这一条逻辑 SQL 上，不是每个任务 64 条。当前数据量下，这个固定成本低于一次用户请求的多次点查，也不在用户请求路径上。
- **为什么不现在改**：①换单表全局任务索引，会破坏“注册任务与发券同事务/同分片”，未来 16 库还会变成跨库写；②改时间桶分片，会改变任务表路由键，V6 的 256 张表要重哈希；③按物理表用 hint/裸 JDBC 扫描，单实例仍是 64 条，只有多实例才把每实例降到 64/N，当前本地单实例没有收益，还引入第二条 DB 访问路径。
- **何时重做**：当扫描 p99 或 MySQL CPU 成为可观测瓶颈，或 XXL-JOB 多实例下扫描 SQL 占比明显上升；届时用 `HintManager`/裸表按 `t = shardIndex; t < 64; t += shardTotal` 扫描，或另建全局 due 索引表（接受最终一致），并先出 ADR。

P0 顺序：

1. `CouponUsageMapper` 增加 `selectByUsageNo(usageNo,userId)` 重载；8 个 `usage_no` CAS 加 `user_id`；`casExpireByCouponId` 加 `userId`。`TradeTxService` 的 181/189、421/439/453、479、733/773 传 `usage.getUserId()`；外部 `ConfirmRequest/ReleaseRequest` 只保留第一次广播。
2. `UserCouponRepository.findById` 能拿 `userId` 的 26 处改 `findByIdForUser`；`task.getUserId()` 传下去。
3. `CouponExpireTaskMapper`/`CouponReleaseTaskMapper` 的 `finish/backoff` 加 `userId`；`processOne` 传 PO.userId，把每任务 64 条 UPDATE 降为 1 条。
4. 扫描锁按 `shardIndex` 拆键：`c:exp:scanlock:{index}` / `c:rel:scanlock:{index}`；先算分片再抢锁。
5. 去掉热路径 `countIssuedByTemplateSince`：改 V27 日计数器/Redis；每人总限/日限改 MAX(seq)/计数器；`countByTemplate` 只保留在低频对账，C1 可用 `stock_shard.used` 做锚点。
6. 路由可观测：用 ShardingSphere `sql-show`/指标统计 confirm/lock/scan 的物理 SQL 数，补集成测试断言“带 user_id 的语句只命中 1 张实际表”。
7. V27 schema 修正：task 保留/索引、计数表 upsert/分段/day 索引、L73 `app_id` 入 `uk_idem`。
8. M4 前出事务边界 ADR：明确 C1 库存与 user 分片券在 16 库下如何保持“扣库存+插券同事务”；修正 ADR-0010/V6“扩库只改配置、无迁移窗口”的表述。

---

### 4.4 初期容量目标与扩容门槛（建议默认，按业务校准）

“初期单库/双库”要先区分两种“双库”：

- **主库 + 只读从库**：用于读扩展/报表/高可用，不增加写吞吐；初期可以做，但要注意读己所写延迟。
- **两个写分片库**：按 `user_id` 拆写；会触发 C1 库存单表与用户分片券的跨库事务问题，不能在事务方案定稿前上线。

建议的目标阶梯：

| 阶段 | 物理库 | 容量假设（建议默认） | 说明 |
|---|---|---|---|
| 初期 | **1 写 MySQL + 1 Redis**（可选 1 只读从库） | `user_coupon` ≤ **5000 万行**；事件 ≤ **3 亿行**；峰值 **1k QPS 短时突发**、日常 **100–200 QPS** | 64 逻辑表保留（已建，不重哈希）；初始 5000万/64≈78万行/表，单库压力主要来自写而不是表大小。每笔约 5 条写：1k QPS≈5k 写/s，必须压测；压不住先做异步/批量/计数器，不是先拆库 |
| 增长 | 2 个写分片库（32 张/库） | 1–3 亿行；持续写入约 2–3k 写/s | 表号 `%64` 不变，按 `table_no % 2` 分库，无重哈希；**必须先有 C1 库存与用户分片券的跨库事务方案** |
| 规模 | 4–8 个写分片库 | 3–10 亿行；5k+ 走异步/批量 | 库存按库/段分配 quota，或最终一致；不是“只改配置” |
| 超大规模 | 16 个写分片库 | 10 亿+ | 只在事务边界 ADR、容量压测、运维演练都通过后启用 |

**触发扩容的信号**（任一持续出现，而不是单次抖动）：

- 单库 CPU 持续 >60–70%，或写入 p99/锁等待持续超阈值；
- 持续写入 >2–3k 写/s，或单表 >2000 万行且 DDL/备份窗口不可接受；
- 磁盘使用 >50–60%，或 binlog/备份/恢复时间超出 RTO；
- 读从库延迟影响业务，或报表查询明显干扰交易库。

**决策规则**：

1. 先加**只读从库**解决读压力，不增加写能力；
2. 写压力先靠异步/批量/计数器/短事务，不先拆写库；
3. 确实要拆写库时，先解决 C1 库存与券的跨库事务，再按 2→4→8→16 逐级扩容；
4. 如果业务目标正式下调到千万级，16/32 表更简单；但当前 10 亿目标仍在，且重哈希成本高，**不建议现在缩减 64**；
5. 不再给新表默认开 64 分片；`coupon_event/outbox/share/refund/pricing` 用归档/分区解决增长。

---

## 5. 验收指标

- confirm/lock/scan 的物理 SQL 数：带 userId 的语句应 ≤ 个位数（目标 1–3 条），不再 64 倍。
- 每轮扫描实际表数：当前仍是 64 张/实例（`shard` 只切结果，不切物理表；见 §4.3.1）；目标态才是按物理表降到 64/N，或改全局 due 索引表。
- `coupon_event`/`coupon_outbox`/`coupon_pricing_usage` 的日增长、保留天数、归档积压。
- `coupon_issue_task` PENDING 堆积、最长等待、终态行清理。
- 16 库方案的前置条件清单全部满足后才允许拆库。

> **2026-10-23 P0-6/P0-1/P0-2 实测**：`findByIdForUser`、`selectByUsageNoForUpdate`、`selectByOrderNo`、过期任务 `finish/backoff`、释放任务 `finish` 均只执行 1 条物理 SQL；P0-1/P0-2 给 9 个 `coupon_usage` CAS 补 `user_id` 后，`casConfirm` 也降到 1。后台 `scanDueShard` 仍执行 64 条，按 user_id 分表的扫描代价暂接受，不能宣称“scan 也单表”。**2026-10-24 P0-1b 补测**：`TradeTxServiceTest` 断言带 `userId` 的 `confirm/release/markConfirmException` 只调用 `findByUsageNo(usageNo,userId)`、不再调用单参广播；`TradeAppServiceTest` 断言编排层把入口查到的 `42L` 传给事务层，confirm/release/refund 三条路径都命中新重载。全量 `mvn clean test` **560 例：559 绿 + 1 门控跳过 + 0 失败/0 错误，BUILD SUCCESS，8:17**（[STATUS](../STATUS.md)）。

## 6. 完整调研链接

> 证据等级：A=官方文档/官方基准；B=企业官方工程博客；C=第三方工程文章；D=面经/转述。访问日期：2026-10-22。

### 6.1 官方文档

- [A] Apache ShardingSphere 分片概念（分片键、绑定表、广播表）：https://shardingsphere.apache.org/document/current/en/features/sharding/concept/
- [A] Apache ShardingSphere 分片限制：https://shardingsphere.apache.org/document/current/en/features/sharding/limitation/
- [A] MySQL 8.4 InnoDB 锁：https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html
- [A] MySQL 8.4 在线 DDL：https://dev.mysql.com/doc/refman/8.4/en/innodb-online-ddl.html
- [A] MySQL 8.4 分区：https://dev.mysql.com/doc/refman/8.4/en/partitioning.html

### 6.2 项目内依据

- `docs/adr/ADR-0010-target-tech-stack.md`（64 表/16 库的原始决策）
- `docs/adr/ADR-0018-stock-model-c1-fixed-shards.md`（C1 本地事务、5 条 DB 写、容量）
- `docs/adr/ADR-0019-async-issue-task-wal.md`（V27 任务表/计数表）
- `src/main/java/com/newcoupon/config/ShardingDataSourceConfig.java`
- `src/main/resources/db/migration/V6__shard_by_user.sql`
- `src/main/resources/db/migration/V7__coupon_release_task.sql`
- `src/main/resources/db/migration/V25__c1_stock_shards.sql`
- `src/main/java/com/newcoupon/infrastructure/persistence/mapper/CouponUsageMapper.java`
- `src/main/java/com/newcoupon/infrastructure/persistence/mapper/UserCouponMapper.java`
- `src/main/java/com/newcoupon/infrastructure/persistence/mapper/CouponExpireTaskMapper.java`
- `src/main/java/com/newcoupon/infrastructure/persistence/mapper/CouponReleaseTaskMapper.java`
- `study/08a-分布式ID与分库分表讲解-雪花算法与路由先行.md`（教学口径，需同步修正“扩库零迁移”）

### 6.3 证据口径

- 193/128/129–257 条物理 SQL 是按 mapper SQL 形态估算的放大倍数，不是压测结果；实施后用 ShardingSphere `sql-show`/指标核对。
- 10 亿目标来自 V6/ADR-0010 的容量假设，仓库没有生产容量数据；104 QPS 是 WSL2 形状验证，不是容量结论。
- 16 库每逻辑表 64 个 actual node（16 库 ×4 表），不是 16×64 张；以 `user_id %64`/`%16` 的实际映射为准。

---

## 7. 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-22 | 复核 64 分表布局：保留 64、不扩库；逐表结论；热路径广播/任务扫描/单表增长/16 库事务边界；P0 路由修复与验收指标 |
| v1.1 | 2026-10-22 | 增加初始容量目标与扩容门槛：初期 1 写库+可选从库、≤5000 万券/≤3 亿事件、1k 峰值/100–200 日常；2/4/8/16 写库的触发条件；明确主从双库≠两个写分片 |
| v1.2 | 2026-10-22 | V27（任务/计数表单表）与 V28（L73 的 64 表 uk_idem 补 app_id）已落地；P0 只先做 worker/过期扫描相关项，交易链路 P0-1/P0-2 后置 |
| v1.3 | 2026-10-22 | P0-3（任务 finish/backoff 带 userId、过期扫描 findByIdForUser）与 P0-4（扫描锁按 shardIndex 拆键）已实施；P0-5/P0-6 待做 |
| v1.4 | 2026-10-23 | P0-5 已实施：同步 C1 去掉三次热 COUNT，每人总限用 MAX(seq)，日限收进 issueC1 的 V27 条件计数并与异步共用；P0-6 待做（物理 SQL 路由断言） |
| v1.5 | 2026-10-23 | P0-6 已实施：新增 6 个 sql-show 物理 SQL 断言；user_id 查询 1 张表，casConfirm/scanDueShard 实测 64 张，登记 P0-1/P0-2 后续；全量 550 例 |
| v1.6 | 2026-10-23 | P0-1/P0-2 已实施：coupon_usage 9 个 CAS 补 user_id，有 userId 的 findById 改 findByIdForUser；casConfirm 从 64 降到 1；scanDueShard 的 64 表广播暂接受；全量 553 例 |
| v1.7 | 2026-10-24 | P0-1b 已实施：confirm/release/refund/异常收场把入口查到的 userId 传入事务层，去掉第二次 coupon_usage 广播；新增 §4.3.1 说明 scanDueShard 为何保留 64 表广播及替代方案；全量 560 例：559 绿 + 1 门控跳过 + 0 失败/0 错误，8:17 |
