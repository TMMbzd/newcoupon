# newcoupon 团队章程与执行路线

> 这份文件是团队的持久记忆。新 lead、队友和换班后的会话先读这里，不依赖上一段聊天记录。

## 1. 使命

把 newcoupon 做成一个可对标的优惠券能力平台，用于系统设计学习和项目经历展示。目标态能力按终态设计，不因为本地环境把功能砍成演示版；本地只是缩小规模，不缩小正确性。

## 2. 不可破坏的约束

- Java 17；不写 Java 21 语法。
- 学习资料只放 `study/`，不进 git；工程文档放 `docs/`。
- 未经用户明确要求，不 commit、不 push。
- 库存权威在数据库：`coupon_stock_shard` 的 `used < quota` 条件更新是终审；Redis gate 是准入缓存，不是库存权威。
- 先调研再实施；不重复造轮子。
- 测试串行：同一时间最多一个 Maven 进程；全量测试由 `verifier` 独占。
- WSL2 内存约 7.8 GiB；先 2 个后端 + 1 个验证，评审角色错峰。
- 所有完成声明必须带可核证据：测试方法名、命令、真实输出。

## 3. 当前阶段

库存 Phase 1 收尾，Phase 1.6 的 Redis 修复重设计已完成：

- 请求路径单飞许可 + 一次修复 + 一次重试；
- V36 持久修复队列和后台任务；
- Redis TTL 抖动、INFO 指标、淘汰策略告警；
- 最近全量基线：587 例，586 绿 + 1 例门控跳过，0 失败/0 错误。

**重要仓库状态：** V25–V36 的库存 Phase 1 代码和迁移仍在工作区，未提交；HEAD 还在交易环 3。任何清理工作区、checkout、reset 之前必须先问用户。

## 4. 路线

### 编号说明

- 本文件的 Phase 1/2/3 是**库存专项编号**（ADR-0021），不是产品总路线编号。
- 总体产品路线是 V6 阶段 0~6：库存 Phase 1 = V6 阶段 0；下一步是 **V6 阶段 1 发放域与预算**；V6 阶段 2 是信任边界/租户治理，不是库存 TCC。
- 库存 Phase 2（两用户库 + 控制库 TCC）是**扩容触发项**，不阻塞 V6 阶段 1；单库正常服务不需要 TCC。

### 库存 Phase 1：单库补完 + 多实例无状态（已完成）

本轮已完成项：

1. **运行时配置校验**
   - JVM 时区、MySQL session 时区、账单日是否一致；
   - ShardingSphere 业务连接池 + raw-bulk 连接池是否超过 MySQL `max_connections` 的预算；
   - XXL 端口、日志目录、appname、token 在多实例下是否冲突；
   - dev 宽松告警，prod 严格失败。

2. **缓存跨实例失效**
   - `CachedTemplateReader`、`CachedOpenAppReader`、`CachedTenantConfigService` 目前只失效本实例；
   - 目标：Redis pub-sub 广播 + 版本号/TTL 兜底；
   - 旧 app/secret/租户策略不能在新实例继续被接受。

3. **`layout_version` 与扩片重排**
   - 目前 `stock_layout_version` 只写不读，路由只看 `stock_shard_count`；
   - 目标：库存分片带版本、发券时校验版本、扩片走“冻结 → 排空 → 新版本行 → 校验 Σused/Σquota → 解冻”；
   - 这是 Phase 2 预约单 schema 的前置约束之一。

4. **daily bill / pricing 调度锁**
   - `DailyBillService`、`PricingSnapshotCleanupService` 目前只有 `@Scheduled`，没有锁/XXL/失败指标；
   - 目标：复用 `RedisScanLock` + `@XxlJob` + 跳过/失败指标。

5. **验证缺口**
   - 两个真实进程的修复风暴；
   - Redis 主从切换/重启演练；
   - Slow Log/LATENCY 告警；
   - 真实 Redis Cluster 集成测试。

### 库存 Phase 2：两个用户库 + 控制库的 TCC 证明（按扩容触发延后）

**启动条件**：单库写入/连接/存储/故障域达到扩容触发信号，或明确要把用户券拆到独立物理库。TCC 是扩容升级，不是系统正常提供服务的前置条件；单库 + 多应用实例 + 单主库 HA 不需要 TCC。

- 全局库存/全局模板日限留在控制库；
- 用户券、过期任务、issue guard 在用户库；
- Try / Confirm / Finalize / Cancel + Reaper；
- guard 唯一键裁决 Cancel 与 in-flight Confirm；
- 2 个用户库 + 控制库 + 2 实例，200 并发/50 库存不超发；
- 本地可重建，不背旧数据兼容。

### 库存 Phase 3：16 库 + 5k（后续）

- 16 个用户 schema + 控制 schema；
- 显式 actualDataNodes；逐库 Flyway；
- 控制库热点、日计数、reservation/task 分区与保留；
- 5k 受理 + worker；p99、控制库 CPU、连接数、reaper backlog、迁移/回滚演练。

## 5. 团队角色与文件边界

| 角色 | 职责 | 文件边界 |
|---|---|---|
| Lead | 拆任务、拍板、接口仲裁、集成、最终测试 | `docs/STATUS.md`、`docs/adr/`、`pom.xml`、共享文件 |
| `backend-issue` | 发券/库存/Redis gate/异步 worker/模板生命周期 | `Receive*`、`CouponIssue*`、`Gate*`、`C1*`、`Stock*`、`Template*`、`RedisIssueGate`、issue 迁移 |
| `backend-trade` | 交易/退款/结算/价格快照/开放接入 | `Trade*`、`Refund*`、`Settle*`、`Pricing*`、`DailyBill*`、`CouponRelease*`、`CouponExpire*`、`OpenApp*`、trade 迁移 |
| `verifier` | 集成测试、故障注入、压测、证据链 | `src/test/java/com/newcoupon/integration/`、`scripts/` |
| `architect` | 设计评审/缺口矩阵；只读，评审完退出 | ADR 需 Lead 同意 |
| `frontend` | 有 UI 需求才拉起，独立 `web/` | 当前仓库没有前端目录 |
| `scribe` | 零基础讲解、ADR 证据、STATUS 同步 | `docs/`（STATUS 除外）、`study/` |

一个文件同一时间只能有一个 writer。共享文件（`common/`、`config/`、`ErrorCode`、`domain/repository` 接口、Flyway 版本号）由 Lead 指派；两个后端不能同时改共享文件或抢同一个 Vxx。

## 6. 工作协议

1. 先侦察后实施：第一轮只读，给出方案、风险、验收命令。
2. Lead 合并任务板后，按文件集分配实现。
3. 迁移版本号由 Lead 预分配，避免两个后端撞号。
4. 定向测试由 owner 跑；全量 `mvn clean test` 只由 `verifier` 串行跑。
5. 测试前停应用和闲置容器；后台不留长跑进程。
6. 每个任务带验收命令、预期和实际输出。
7. 空闲队友 shutdown；内存吃紧时优先保留两个后端 + verifier。
8. 不把技术抉择推给用户；Lead 拍板并记录理由。

## 7. 完成定义

一个任务算完成，必须同时满足：

- 代码/迁移已落地，符合现有模式；
- 定向测试先红后绿，或说明无法先红的原因；
- 受影响回归通过；
- `verifier` 全量测试通过，或明确登记失败/跳过；
- `docs/STATUS.md` 与 `study/工作日志.md` 同步；
- 没有 commit/push，除非用户明确要求。

## 8. 权威来源

按顺序读：

1. `docs/STATUS.md` 顶部交接快照（唯一工程状态来源）
2. `docs/team/CHARTER.md`（本文件，路线和团队约定）
3. `docs/team/HANDOFF.md`（当前交接和下一步）
4. `docs/team/TASKS.md`（当前迭代任务板与依赖）
5. `docs/adr/ADR-0019-async-issue-task-wal.md`
6. `docs/adr/ADR-0021-inventory-multi-db-tcc.md`
7. `docs/kernel/README.md`
8. `study/v6/阶段1-库存多实例与Redis错误/`（教学/代码走读）

冲突时以代码和 `docs/STATUS.md` 为准；本文件是目标和团队约定的汇总，不替代代码事实。
