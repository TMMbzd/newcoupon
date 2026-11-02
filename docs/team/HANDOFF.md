# 当前交接（2026-10-25）

> 新 lead 先读 `docs/team/CHARTER.md`，再读本文件。团队延续靠这两份文档，不靠上一段聊天记录。

## 1. 团队现在要做到哪

当前主线是库存 Phase 1 收尾，然后 Phase 2 的两个用户库 + 控制库 TCC 证明。Phase 3 是 16 库/5k。

Phase 1 的四个剩余项按风险排序：

1. 运行时配置校验（时区/连接池/XXL）——先做，成本低，避免后面误诊。
2. 缓存跨实例 pub-sub 失效——安全（旧 app/secret 在别的实例仍可用）和策略一致性风险最高。
3. `layout_version` / 扩片重排——Phase 2 预约单 schema 的前置约束之一。
4. daily bill / pricing 调度锁——正确性风险低，运维多实例重复执行风险中等。

另有验证缺口：两个真实进程的修复风暴、Redis 主从切换/重启演练、Slow Log/LATENCY 告警、真实 Redis Cluster 集成测试。

## 2. 当前代码状态

- 最近全量基线：**648 例，647 绿 + 1 例门控跳过，0 失败/0 错误，BUILD SUCCESS 8:32**。`GovernanceMetricsIntegrationTest` 首跑曾超时、单跑 5/5、重跑全量绿，登记为负载敏感。
- Phase 1.6 已完成：请求路径单飞 + 一次修复 + 一次重试；V36 持久修复队列；Redis TTL 抖动、INFO 指标、淘汰策略告警。
- **调度锁已完成**：`DailyBillService` / `PricingSnapshotCleanupService` 加 `RedisScanLock`、双 `@Scheduled`/`@XxlJob`、跳过/降级/失败指标；单测 11/11、`SchedulerLockIntegrationTest` 4/4。
- **运行时配置校验已完成**：`RuntimeConfigValidator` + `application.yml` 的 `coupon.runtime.*`；单测 10/10、集成 1/1。
- **缓存跨实例 pub-sub 已完成**：`CacheInvalidationPort` + `CacheInvalidationBus` + 三个 reader + `OpenAppService` 提交后广播；定向 88 例 + 全量 628 例绿。
- **两进程修复风暴/Redis 演练**：`two-process` PASS（24 assertions，跨进程 repair=1）、`restart` PASS（29 assertions，uptime 重置 + AOF）、`failover` PASS（32 assertions，`redis_down_reject 0→1`，跨进程 repair=1）。
- **failover 分类修复已完成**：`ReceiveAsyncAppService` 对 `RedisSystemException` 的 cause chain 识别连接重置/超时，改走 `handleRedisDown`（尊重 reject/db-fallback）；`ReceiveAsyncRedisDownTest` 5/5 绿。
- **failover 脚本清理已修**：RDB 临时目录通过受限 root 容器删除；failover 重跑 32 assertions PASS，`mvn -q -o -DskipTests clean` exit 0。
- **layout_version Slice A 已完成**：V37 列/唯一键、sync/async/raw-bulk 版本 fence、publish/reconcile/adjust/lifecycle 接线；定向 73 例 + 全量 639 例绿（8:14）。
- **layout_version Slice B 已完成**：V38 删旧唯一键；`StockReshardService`/`StockReshardTxService` 冻结、软排空、两段式守恒、CAS 版本/片数、退役旧行、rollback；`StockReshardIntegrationTest` 7/7、`StockReshardServiceTest` 2/2；全量 648 例绿（8:32）。
- **Phase 1 核心完成**：配置校验、缓存 pub-sub、调度锁、两进程/restart/failover 演练、layout_version Slice A/B 均完成。reshard 管理 HTTP 端点/指标/运维手册按设计延后。
- **下一步**：按总体路线 [V6 实施计划](../research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md) 进入 **阶段 1 发放域与预算**（批量/定向/兑换码/定时/预算账本/发放对账）；库存 Phase 2 TCC **只在扩容触发时启动**，不阻塞阶段 1。当前可先做容量观测、备份/HA、reshard 管理端点和学习文档。
- **V25–V36 的库存 Phase 1 代码和迁移仍在工作区，未提交。** HEAD 还在交易环 3。
- 不要在未询问用户的情况下执行 `git clean`、`git checkout`、`git reset` 或任何会丢工作区的操作。
- 未 commit/push。

## 3. 团队启动方式

### 稳定路径：终端 tmux

1. 在 Windows Terminal 或 VSCode 终端运行：

```bash
cd /home/tmm/java-pro/newcoupon
bash scripts/team/team-session.sh
```

2. 在 lead 里粘贴 `docs/team/KICKOFF.md` 的内容；它会让 lead 读章程和交接，然后创建命名队友。
3. 队友 pane 直接在同一个 tmux 窗口里拆出来。这个模式不需要 `attach`。

### VSCode 里

- 原生 Claude 面板使用 stream-json 非交互启动，**不能 spawn teammate**；它只能跑普通 subagent/agent map。
- 要真 teammate/pane，必须用 VSCode 终端或 Windows Terminal 的交互式 lead。
- 任务 `team: start VSCode terminal lead` 是“在 VSCode 终端里跑 lead”，不是原生面板。
- 任务 `team: attach teammate panes` 只用于 external swarm（lead 在 tmux 外）；stable tmux 模式不需要。
- `team: resume this conversation (2026-10-25) in stable tmux` 会用显式 session id 复制当前对话；`--continue` 不可靠，它可能选到别的会话。

## 4. 已经发现的高风险

- **配置校验缺失**：JVM 时区与 MySQL session 时区没有启动校验；账单日可能错位。ShardingSphere 连接池与 raw-bulk 池没有和 MySQL `max_connections` 做预算校验。XXL 端口/日志目录多实例可能冲突。
- **缓存只失效本实例**：`CachedTemplateReader`、`CachedOpenAppReader`、`CachedTenantConfigService` 没有 Redis pub-sub/版本键；旧 app/secret/租户策略可在别的实例继续被接受，最长到 TTL。
- **`layout_version` 只写不读**：路由只看 `stock_shard_count`；扩片/重排没有工具；Phase 2 预约单需要版本 fence。
- **调度任务无锁**：`DailyBillService`、`PricingSnapshotCleanupService` 只有 `@Scheduled`，多实例重复执行且没有失败指标。
- **STATUS 有过期描述**：`docs/kernel/README.md` 头部说 Phase 1.1/1.2 实施中，和正文的 1.1–1.6 完成不一致；`docs/STATUS.md` 里“扩物理库=改配置搬数据，SQL 零改动”对 raw-bulk 物理表写不成立。

## 5. 第一轮建议

1. `architect`：只读复核上述排序和 `layout_version` fence 设计，输出一页评审；完成后退出。
2. `backend-issue`：配置校验 + 缓存 pub-sub 的设计；确认共享文件改动范围。
3. `backend-trade`：daily bill/pricing 调度锁设计；确认不碰 issue 代码。
4. `verifier`：两个真实进程修复风暴 + Redis 主从切换/重启演练的最小方案。
5. Lead 合并后分配实现；第一个实现项建议是配置校验（共享 `config/` 由 Lead 或指定单人写）。

## 6. 不要做

- 不要让两个后端同时改 `common/`、`config/`、`ErrorCode`、repository 接口或同一个 Flyway 版本。
- 不要在原生 Claude 面板里期待 teammate pane。
- 不要把 `--continue` 当作“续接当前对话”的可靠方式；同目录有多个会话时会选错。
- 不要在未确认的情况下 commit/push。
- 不要把本地简化说成生产级完成；缺什么要登记。
