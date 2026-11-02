# 团队任务板（2026-10-25）

> 状态：`待办` / `设计完成` / `实现中` / `待验证` / `完成` / `阻塞`。
> 规则：一个文件同一时间只有一个 writer；共享文件由 Lead 指派。

## 当前迭代：Phase 1 收尾

| # | 任务 | Owner | 状态 | 文件边界 | 验收 |
|---|---|---|---|---|---|
| 1 | daily bill / pricing 调度锁 | backend-trade / verifier | 完成（单测 11/11 + 集成 4/4） | `DailyBillService`、`PricingSnapshotCleanupService`、两个新单测、`SchedulerLockIntegrationTest` | 两个单测绿；锁被占用时跳过；XXL 参数可回填 |
| 2 | 运行时配置校验 | backend-issue | 完成（单测 10/10 + 集成 1/1） | `config/RuntimeConfigValidator`、`application.yml`；不碰 `DailyBillService` | 时区/连接池/XXL 负向测试；dev WARN、prod fail-fast |
| 3 | 缓存跨实例 pub-sub | backend-issue | 完成（定向 88 例 + 全量 628 例） | `common/cache/CacheInvalidationPort`、`infrastructure/redis/CacheInvalidationBus`、三个 reader、`OpenAppService`、`application.yml` | 第二实例在 TTL 前看到禁用/轮换/租户变更；pub/sub 丢失由版本轮询兜底 |
| 4 | `layout_version` V37 + 热路径 fence（Slice A） | backend-issue | 完成（定向 73 例 + 全量 639 例绿） | `CouponStockShard`、mapper、`issueC1`、async/raw-bulk、publish/reconcile/adjust/lifecycle、V37 | 版本 fence；旧版本不扣；全局 Σquota/Σused 不变 |
| 4b | 离线 reshard + rollback（Slice B） | backend-issue | 完成（定向 82 + 全量 648 例绿） | `StockReshardService`、adjust-log、mapper FOR UPDATE、V38 | N→M 保总量；rollback；旧索引门禁 |
| 4c | reshard 管理端点/运维 | backend-issue | 延后（设计明确 Slice B 后评估） | controller/DTO/审计 | 不阻塞 Phase 1 核心完成 |
| 5 | 两进程修复风暴 + Redis 演练 | verifier | 完成：two-process 24 / restart 29 / failover 32 assertions PASS | `scripts/cluster/lib-gate-repair.sh`、`verify-gate-repair.sh` | 两进程只修一次、无超发；failover/restart 有 pass/fail 证据 |
| 6 | 全量回归 + 证据 | verifier | 完成（648 例：647 绿 + 1 跳过，8:32） | 全量 `mvn clean test` | 0 失败/0 错误；跳过项登记 |
| 7 | Redis failover 分类修复 | backend-issue | 完成（单测 5/5；failover 32 assertions PASS） | `ReceiveAsyncAppService`、`ReceiveAsyncRedisDownTest` | 连接重置包在 RedisSystemException 时走 redis_down_* 策略；failover 重跑绿 |
| 8 | failover 脚本 RDB 临时目录清理 | verifier | 完成（failover 32 assertions PASS + `mvn clean` exit 0） | `scripts/cluster/lib-gate-repair.sh` / `verify-gate-repair.sh` | 清理后 `mvn clean` 不再被 root/redis-owned 目录卡住 |

## 依赖顺序

1. #1 先落地（改 `DailyBillService`，不碰 `application.yml`）。
2. #2 在 #1 完成后写 `RuntimeConfigValidator`、`XxlJobConfig`、`application.yml`；如果必须改 `DailyBillService` 的 bill zone，等 #1 完成。
3. #3 在 #2 的 `application.yml` 合并后接线；Lead 先写共享 port/bus，再由 backend-issue 接三个 reader。
4. #4 由 architect 先出评审，再决定是否本轮实现；它是 Phase 2 预约单 schema 的前置约束之一。
5. #5 与 #1~#3 并行设计，实现在实现项合并后跑。
6. #6 最后由 verifier 串行跑全量。

## 下一阶段（V6 阶段 1：发放域与预算）

库存 Phase 1 已完成；按总体路线进入 V6 阶段 1：

- 1A 批量发放：task/chunk/item、XXL-JOB、`RedisScanLock`、分片消费、限速、进度/取消/重试；
- 1B 定时/兑换码/异步：`schedule_time` + `misfire_policy`、码批次/哈希/CAS 状态、防撞库/枚举；
- 1C 预算账本与发放对账：硬顶、reserve/confirm/release、僵尸预占回收、对账。

库存 Phase 2 TCC 不在此列；它只在单库容量/故障域触发时启动。

## 共享文件仲裁

- `application.yml`：Lead 单人写；#2 和 #3 不能同时改。
- `common/`、`config/`、`ErrorCode`、`domain/repository` 接口：Lead 指派单人。
- `DailyBillService`：backend-trade 当前 owner；#2 需要改时等 #1 完成并转交。
- Flyway 版本：Lead 预分配；本轮设计没有新增迁移。

## 证据要求

每个任务完成时附：

- 改动文件列表；
- 定向测试命令和实际结果；
- 受影响回归；
- 风险/未覆盖边界；
- 不 commit/push。
