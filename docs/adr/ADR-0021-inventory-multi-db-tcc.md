# ADR-0021：库存多实例/多库扩展——全局库存 + TCC guard fence

- **状态**：已接受（方案）；Phase 1.1 gate 补偿、Phase 1.2 对账/生命周期、Phase 1.3 多实例基础、Phase 1.5 Redis 错误专项、Phase 1.6 单飞修复 + V36 持久修复队列已实施；Phase 2 TCC 待做
- **日期**：2026-10-24
- **关系**：补充 [ADR-0018](ADR-0018-stock-model-c1-fixed-shards.md)（C1 单库库存）、[ADR-0019](ADR-0019-async-issue-task-wal.md)（异步任务 WAL）、[ADR-0020](ADR-0020-sharding-layout-review.md)（16 库事务边界待定）
- **一句话**：单库继续用 C1 本地事务；多库时库存/模板日限留在控制库，用户券在用户库，用 TCC（Try/Confirm/Cancel）+ 用户库 guard 唯一键做严格 fence，应用实例保持无状态。

## 1. 问题

C1 的正确性前提是“库存扣减 + 券/事件/outbox/过期任务在同一个本地事务”。拆 16 物理库后：

- `coupon_stock_shard`、两张日计数、`coupon_issue_task`、事件/outbox 是单表；
- `user_coupon`/`coupon_expire_task` 按 `user_id % 64` 分表，未来落到 `user_id % 16` 的库；
- 同一模板的不同用户落在不同库，`扣库存 + 插券` 不再同库；
- ShardingSphere 默认 LOCAL 事务会在跨库时各连接独立提交，网络/硬件失败不保证回滚，且不一定报错。

用户明确选择：**全局库存/全局模板日限 + 跨库预占/确认（TCC）**，不做“每库独立库存配额”（会让某库先售罄）。

## 2. 决策

### 2.1 表归属

| 数据 | 归属 |
|---|---|
| `coupon_template`、`coupon_stock_shard`（`quota/used/reserved/status/layout_version`）、`coupon_stock_adjust_log` | 控制库 |
| `coupon_stock_reservation`、`coupon_daily_counter`、`coupon_user_daily_counter`（`used/reserved`） | 控制库 |
| `coupon_issue_task`、`coupon_event`、`coupon_outbox` | 控制库 |
| `user_coupon`、`coupon_expire_task`、`coupon_release_task`、`coupon_issue_guard` | 用户库（`user_id % N` 分库、`user_id % 64` 分表；guard 与券同分片） |
| `coupon_usage`、分摊/退款/账单等交易表 | 暂留单库；多库切换前另出交易域 ADR |

控制表单表通过 ShardingSphere `!SINGLE`/`defaultDataSource` 或独立 control datasource 承载；用户表通过 `actualDataNodes` 显式映射，避免无效的 DB×表叉积。

### 2.2 预约/guard 状态机

控制库 `coupon_stock_reservation`：

- `status`: `TRYING → CONFIRMING → CONFIRMED`；`TRYING/CONFIRMING → CANCELLED`；异常 → `STUCK`
- 唯一 `(app_id,user_id,request_id)`；记录 `shard_no`、`layout_version`、`stat_day`、`expires_at`、`finalized`、lease
- `cutoff`/reaper 使用 `FOR UPDATE SKIP LOCKED`

用户库 `coupon_issue_guard`：

- 唯一 `(app_id,user_id,request_id)`，`state ∈ CONFIRMED|CANCELLED|SUSPENDED`
- 必须与 `user_coupon` 同库同分片；`CONFIRMED` 和券在同一个用户库本地事务提交
- 保留期 ≥ 请求幂等窗口（≥7 天）

### 2.3 流程

**Try（控制库本地事务）**

1. 幂等查 reservation；存在返回原状态。
2. 用户日计数/模板日计数 `reserved++ WHERE used+reserved<limit`。
3. 库存分片 `reserved++ WHERE used+reserved<quota AND status=0`，0 行换片/售罄。
4. 插 reservation `TRYING`，提交。锁顺序：用户日 → 模板日 → 库存 → reservation。

**Confirm（用户库本地事务；控制库先 `CONFIRMING`）**

1. 控制库 CAS `TRYING→CONFIRMING`，失败即中止。
2. 用户库同一事务：`INSERT coupon_issue_guard(CONFIRMED)`；唯一键冲突时 `CONFIRMED` 幂等、`CANCELLED/SUSPENDED` 中止。
3. 同事务按现有 `MAX(seq)+1`/空洞逻辑分配 seq，插 `user_coupon` + `coupon_expire_task`；超每人总限回滚，转 Cancel。

**Finalize（控制库本地事务）**

- `CONFIRMING→CONFIRMED`；`reserved--, used++`（库存/日计数）；任务 SUCCESS；写事件/outbox（唯一 `event_no`）；`finalized=1`。
- Finalize 失败时券已存在，由 finalizer 重试；Sync 可在 Finalize 前返回成功。

**Cancel（先用户库 guard，后控制库释放）**

- 用户库 `INSERT coupon_issue_guard(CANCELLED)`；若已有 `CONFIRMED` 不能取消，转 Finalize；`CANCELLED/SUSPENDED` 幂等。
- 只有 guard CANCELLED 抢到，才控制库 `TRYING/CONFIRMING→CANCELLED`，`reserved--`、日计数 `reserved--`。
- 晚到的 Confirm 在 guard 唯一键上失败，**不可能在 Cancel 释放库存后再插券**；这是严格 no-oversell 的关键。

**Reaper/Finalizer**

- Reaper `FOR UPDATE SKIP LOCKED` 到期 TRYING/CONFIRMING；先查 guard：
  - `CANCELLED/SUSPENDED` → 控制库 Cancel；
  - `CONFIRMED` 且有券 → Finalize；
  - 无 guard/无券 → 先抢 `INSERT guard CANCELLED`，抢到才释放；抢不到转 Finalize；
  - 用户库不可用 → `STUCK`/退避/告警，绝不盲放。
- Finalizer 扫 `CONFIRMED AND finalized=0` 补 Finalize/事件/outbox/任务状态，全部幂等。

**异步受理与 Redis gate**

- 受理仍走 Redis gate 预扣 + 任务 WAL 202；worker 再执行 Try/Confirm/Finalize。
- Redis gate 不是库存权威：Phase 1.5 把 seq 与限领计数解耦，gate/`ul/uld/dly` 可按 DB+在途任务重建；畸形值/缺失自愈；限领拒绝先做 DB 二次确认。
- Redis 连接故障默认返回可重试 5001；`coupon.issue.gate.redis-down-policy=db-fallback` 时跳过 gate 只写 WAL，worker 由 DB 终审。Redis 错只会假拒绝/多打 DB/入口暂时不可用，不会超发。
- **修复不再在请求内循环 3 次**（2026-10-25 修订）。请求路径只做一次单飞修复 + 一次重试：同一个修复作用域（模板或模板+用户）用 Redis `SET NX` 许可，TTL 2 秒，值为随机 owner，释放用 compare-and-delete；没抢到许可的请求立即返回当前错误，修复失败也不释放许可，让 TTL 充当短冷却。理由：AWS/Google SRE/Microsoft 的重试实践都要求有界重试、退避/预算和单飞，数据库重建不适合在同步请求里连续做 3 次。
- **持久修复队列 V36**：请求路径没修成或没抢到许可时，把 `(scope, template, user, statDay, errorCode)` upsert 到 `coupon_issue_gate_repair`（数据库，不是 Redis——Redis 正是可能损坏的组件）。`CouponIssueGateRepairJob` 每 5 秒用 `FOR UPDATE SKIP LOCKED` + lease 领取，复用同一修复许可，失败按 2 的幂退避、最多 8 次后进死信并写 `stock_drift_log`。这样持久坏 key 不依赖“还有请求进来”才恢复。
- **Redis 维护面**：所有多 key Lua 使用 `{templateId}` hash tag（Cluster 同槽）；TTL 加 ±10% 抖动；`RedisInfoMetrics` 采集内存/淘汰/命中率/连接/复制状态；启动/采集时对 `allkeys-*`/`volatile-*` 淘汰策略告警（gate/lock 键可能被淘汰，应使用 `noeviction` 或独立 Redis）。Lua 脚本保持参数化、不动态生成，利用客户端脚本缓存；Redis 重启后脚本缓存丢失由客户端 `EVAL`/`EVALSHA` 回退处理。

### 2.4 单库模式

Phase 1 保持 `CouponIssueTxService.issueC1` 本地事务；Phase 2 引入 `coupon.stock.mode=local|reserved` 开关，local 只在所有数据仍单库时有效。交易/退款/结算域不在本 ADR 的多库范围；Phase 3 切换前需交易域 ADR。

## 3. 为什么不选其他方案

- **每库独立库存配额**：实现简单、保留本地事务，但某库先售罄、小活动/小日限不友好，违背用户要求的全局语义。
- **XA/Seata 2PC**：能跨库强一致，但恢复语义/性能/中间件成本高；且不解决 Cancel/Confirm 业务竞态，guard 仍是必需的。
- **纯 Redis 库存权威**：丢写/主从切换会超发，C1 已把双账本删掉，不重新引入。
- **全局库存库 + 无 guard 的 TCC**：Cancel 与 in-flight Confirm 存在“取消已释放、用户库随后提交”的窗口；guard 唯一键把二者变成单赢家，是本方案的核心。

## 4. 失败窗口

| 窗口 | 归宿 |
|---|---|
| Try 后崩溃 | reservation TRYING；reaper 抢 guard CANCELLED 后释放，或 Confirm 赢后 Finalize |
| Confirm 用户库提交后、Finalize 前崩溃 | guard CONFIRMED + 券存在；finalizer 补 Finalize |
| Cancel 用户库 guard 提交后、控制库释放前崩溃 | guard CANCELLED 是证据；reaper 释放 |
| 用户库不可用 | `STUCK`，库存保持 `reserved`，告警/人工；不超发 |
| 事件/outbox 失败 | 券已存在；finalizer 重试，事件/outbox 最终一致 |
| 重复 requestId | guard/`uk_idem`/reservation 唯一键幂等 |

## 5. 验收

- 严格不变量：`used + reserved <= quota`；`Σused = COUNT(active user_coupon)`；`Σreserved = COUNT(TRYING/CONFIRMING/STUCK reservation)`；Cancel 赢则无券，Confirm 赢则券存在。
- 2 用户库 + 控制库 Testcontainers：Try/Confirm/Finalize/Cancel、guard race、崩溃重放、reaper、批量、200 并发/50 库存、2 实例。
- 16 库/5k 另立 Phase 3；控制库到顶时按 template_id 分片控制库，不退回每库独立库存。

## 6. 调研与证据（访问日期 2026-10-24）

> 证据等级：A=官方文档/论文；B=开源项目/工程文档；C=第三方文章/样例。

- [A] ShardingSphere 5.5.0 Transaction Limitations（LOCAL 不保证跨库网络/硬件故障回滚；XA 有恢复/性能约束）：https://shardingsphere.apache.org/document/5.5.0/en/features/transaction/limitations/
- [A] ShardingSphere 5.5.1 Transaction YAML（`defaultType: LOCAL|XA|BASE`）：https://shardingsphere.apache.org/document/5.5.1/en/user-manual/shardingsphere-jdbc/yaml-config/rules/transaction/
- [A] ShardingSphere 5.5.2 Sharding YAML（`actualDataNodes`/`databaseStrategy`/`bindingTables`）：https://shardingsphere.apache.org/document/5.5.2/en/user-manual/shardingsphere-jdbc/yaml-config/rules/sharding/
- [A] ShardingSphere 5.5.3 Single Table rule：https://shardingsphere.apache.org/document/5.5.3/en/user-manual/shardingsphere-jdbc/yaml-config/rules/single/
- [A] Pat Helland, *Life beyond Distributed Transactions*：https://dl.acm.org/doi/fullHtml/10.1145/3009826
- [A] Garcia-Molina & Salem, *Sagas*：https://dl.acm.org/doi/abs/10.1145/38713.38742
- [A] Apache Seata TCC：https://seata.apache.org/docs/v2.5/user/mode/tcc/
- [B] Seata TCC Fence（幂等/空回滚/悬挂，guard 表直接先例）：https://seata.apache.org/blog/seata-tcc-fence/
- [B] DTM TCC 实践：https://en.dtm.pub/practice/tcc.html
- [A] MySQL Locking Reads（`FOR UPDATE SKIP LOCKED`）：https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html
- [A] Redis Pub/Sub（缓存失效，TTL 兜底）：https://redis.io/docs/latest/develop/pubsub/
- [C] 库存与用户券分片键不一致、无法同库本地事务：https://cloud.it168.com/a2024/1108/6868/000006868946.shtml
- [C] 单库本地事务 + 必要时 XA 的优惠券平台样例：https://github.com/xierui167/smart-coupon-merchant-platform

**Redis 修复与运维（2026-10-25 追加，访问日期 2026-10-25）**

- [A] AWS Builders' Library: Timeouts, retries, and backoff with jitter（重试会放大负载；限制次数、单一层重试、退避+抖动、令牌桶；有副作用接口必须幂等）：https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
- [A] Google SRE: Handling Overload（每请求重试预算最多 3 次、每客户端重试比例 10%、只在紧邻故障层重试）：https://sre.google/sre-book/handling-overload/
- [A] Google SRE: Addressing Cascading Failures（不重试永久错误、重试预算、deadline）：https://sre.google/sre-book/addressing-cascading-failures/
- [A] Microsoft Azure Architecture Center: Retry pattern（只重试瞬态故障；非瞬态快速失败；激进短间隔重试会加剧繁忙服务）：https://learn.microsoft.com/en-us/azure/architecture/patterns/retry
- [A] Microsoft Azure Architecture Center: Circuit Breaker pattern：https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker
- [A] Resilience4j Retry（默认 maxAttempts=3 含首次调用，默认等待 500ms，可配置重试异常）：https://resilience4j.readme.io/docs/retry
- [A] Kubernetes Controllers（level-triggered 收敛循环、限速队列、指数退避、幂等 reconcile）：https://kubernetes.io/docs/concepts/architecture/controller/
- [B] Go singleflight（同一 key 的并发重建合并为一个调用者）：https://pkg.go.dev/golang.org/x/sync/singleflight
- [A] Redis: Scripting with Lua（脚本原子执行、脚本缓存易失、EVALSHA/NOSCRIPT、参数化脚本）：https://redis.io/docs/latest/develop/programmability/eval-intro/
- [A] Redis: Diagnosing latency issues（慢命令、fork、交换、过期集中、LATENCY/SLOWLOG）：https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/latency/
- [A] Redis: Memory optimization（maxmemory-policy、noeviction、峰值内存、碎片率）：https://redis.io/docs/latest/operate/oss_and_stack/management/optimization/memory-optimization/
- [A] Redis: Observability（LATENCY、SLOWLOG、INFO 指标）：https://redis.io/tutorials/operate/redis-at-scale/observability/
- [A] Redis Cluster specification（hash tag、同槽多 key、CROSSSLOT）：https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/
- [A] AWS ElastiCache Well-Architected Lens（Cluster Mode、Multi-AZ、2 replicas、Slow Log、Evictions、ReplicationLag）：https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/ReliabilityPillar.html
- [A] Google Cloud Memorystore for Redis best practices（Standard Tier、RDB、主线程 CPU、SCAN 替代 KEYS、指数退避）：https://cloud.google.com/memorystore/docs/redis/general-best-practices
- [A] Azure Cache for Redis best practices（单 ConnectionMultiplexer、长连接、hash tag、避免大 key、监控）：https://learn.microsoft.com/en-us/azure/azure-cache-for-redis/cache-best-practices-development
- [A] Alibaba Cloud Redis: Large keys and hot keys（Top Key、RDB 分析、bigkeys/hotkeys、拆分/压缩/多级缓存）：https://www.alibabacloud.com/help/en/redis/user-guide/identify-and-handle-large-keys-and-hotkeys/
- [A] Tencent Cloud Redis 热点 Key/大 Key/淘汰策略（热点分片、本地缓存、`allkeys-lru` vs `noeviction`、TTL 抖动）：https://www.tencentcloud.com/zh/document/product/239/83633
- [C] 阿里云：优惠券库存扣减与核销状态机的并发一致性实践（条件更新、状态机、唯一约束、对账补偿）：https://developer.aliyun.com/article/1764311
- [C] 腾讯云：万级 TPS 优惠券系统设计与实践（Redis+Lua、库存分段增补、本地事务表+最大努力通知）：https://cloud.tencent.com/developer/article/2465372

> ShardingSphere 官方页在本次环境被网络策略拦截，链接来自检索索引；实施前需再次打开核对原文。
