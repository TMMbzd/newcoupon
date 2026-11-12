# newcoupon · 优惠券系统中台

对标字节/美团体量的优惠券系统中台。**设计文档集在 [docs/](docs/README.md)（架构总览 + 9 个领域分册 + 统一规范 + ADR），实现严格按文档推进。**

## 当前状态：V6 阶段 6 收官（裁剪版）——七职责落地 + L75 清偿 + 最终证据包

> **能力总览**：券模板与六种发放（单用户/批量/定向/兑换码/定时/异步）+ 库存/限领/幂等/预算闸门；
> 五维计价、两层叠加、价格快照（试算价=核销价）；锁券/核销/释放/过期/超时收口；行级分账与按行退款；
> T+1 日账单/主体对账/结算冻结；开放接入（签名/幂等/配额/归属/审计）+ 租户生命周期/RBAC/API 版本/契约门禁；
> 风控（名单/设备日限/预算信号/退款冻结）+ SLO/告警规则/故障演练/备份恢复。
> 裁剪项与触发条件见 [收官-2026-11](docs/收官-2026-11.md) §3。
>
> **最终证据索引**：[docs/evidence/README.md](docs/evidence/README.md)——压测、故障演练、备份恢复、双实例冒烟、
> OpenAPI 契约基线、promtool 规则、审计哈希链、风控攻击脚本，每条带关键数字与复跑入口。
>
> 系统现状：[docs/STATUS.md](docs/STATUS.md)（能力矩阵/遗留债——唯一权威状态来源）
> 收官映射：[docs/收官-2026-11.md](docs/收官-2026-11.md)（简历七职责 → 代码/测试/证据）
> 当前计划：[实施计划 V6](docs/research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md)（从券交易内核到券能力中台，全量约 39 个工作会话；最小简历闭环约 30~33）
> 库存决策：[ADR-0018 库存模型 C1](docs/adr/ADR-0018-stock-model-c1-fixed-shards.md)（DB 固定分片条件更新，Redis 退出库存权威；含实施进展、未来演化与完整调研链接）
> 异步发放设计：[ADR-0019 异步发放任务表 WAL](docs/adr/ADR-0019-async-issue-task-wal.md)（Vivo 方案 2 适配：Redis 预扣 + 同步任务表 WAL + 异步 worker；202 + requestId 查询；硬日限）
> 分片复核：[ADR-0020 分库分表布局复核](docs/adr/ADR-0020-sharding-layout-review.md)（保留 64/不拆库；逐表理由、广播路由、任务扫描、单表增长、16 库事务边界；P0-1/P0-2/P0-5/P0-6 已落，casConfirm 已单表，后台 scanDueShard 仍广播 64 张暂接受）
> 配套调研：[六域对标与选型](docs/research-2026-10-21/00-调研-六域对标与选型.md)
> C1 现状：C1 是唯一库存模型；V25/V26、领券闭环、库存调整、最小对账已落地；旧 `StockBucketRouter`/`StockReconcileService`/`RedisStockGateway`/legacy 测试已删除，V29 删除 `stock_bucket`，V30 删除 `stock_model` 列。不再做 0C 存量迁移/0D 兼容退役。
> 异步发放：V27 任务/硬日限计数表、V28（L73，`uk_idem` 补 `app_id`）、`RedisIssueGate`、`POST /receive-async` 202、`GET /receive-async/{requestId}`、worker（SKIP LOCKED + lease + C1 落库 + 硬日限 + 清理）已落地；本地 dev 默认打开 async+worker（`*` allowlist），测试由 Surefire 属性关闭。**2026-10-23 压测**：1k/2k 并发受理全部 202；1k 观测 711–1382 req/s、2k 观测 1050–2122 req/s（p99 0.74–1.78s，WSL 同机有波动）；4k 请求打 3k 库存最终 success=3000 / 售罄=1000、无超发无重复；硬日限与每人日限均按 DB 条件更新生效。worker 从 32 task/s 优化到裸 JDBC 物理表批量写约 1.29k–1.6k task/s（4k 隔离约 3.1s 清空）；裸写只适配当前 1 物理库 × 64 表，拆 16 库前必须按库路由或设置 `raw-bulk-enabled=false`（关闭分支已补单元/真实库集成回归）。详见[压测报告](docs/evidence/2026-10-23-异步发放1k2k压测报告.md)与 [ADR-0019](docs/adr/ADR-0019-async-issue-task-wal.md)。
> 已完成：阶段0（纯DB三验收全过）+ 阶段1（Redis Lua 五重校验闸门/DB双防线/通用限领N）+ 算钱域 + 行级退款 + 结算主体/价格快照/结算-lite；2026-09-07 六视角审计后 20 项陈旧物已清理（STATUS §4.5）。

## 快速开始

```bash
# 1. 启动本地依赖（需要 Docker；见下方环境说明）
make up

# 2. 跑单测（不依赖数据库，秒级）
make test

# 3. 启动应用（Flyway 会在启动时自动建表）
make run

# 4. 冒烟
curl http://localhost:8080/api/ping
curl http://localhost:8080/actuator/health
```

环境要求：JDK 17+、Maven 3.8+、Docker（WSL2 需在 Docker Desktop 开启发行版集成）。

## 工程结构（DDD-lite 四层）

```
src/main/java/com/newcoupon/
├── common/            # 共享内核：统一响应体 Result、错误码 ErrorCode、业务异常
├── controller/        # 接入层：REST 适配，只做协议转换，不写业务
├── application/       # 应用层：用例编排（领券/锁券/核销...），事务边界
├── domain/            # 领域层：聚合、状态机、领域枚举 —— 不依赖任何框架
└── infrastructure/    # 基础设施层：仓储实现、Web 过滤器、外部适配
```

依赖方向：`controller → application → domain ← infrastructure`（domain 是内核，谁都不许反向依赖它）。

## 工程规范（对标大厂，每条的"为什么"见提交说明与 docs/）

| 规范 | 内容 |
|---|---|
| SQL 管理 | 一切建表/变更走 `src/main/resources/db/migration/` 的 Flyway 脚本，**禁止手工改库** |
| 建表 | InnoDB + utf8mb4；每表/每列必须有 COMMENT；必备 id/create_time/update_time；**禁用外键与级联**（一致性在应用层保证） |
| 接口契约 | 统一 `Result<T>` 响应体；错误码分段（0 成功 / 1xxx 参数 / 2xxx 模板域 / 3xxx 领券域 / 4xxx 用券域 / 5xxx 系统）；入参一律 JSR-303 校验 |
| 异常 | 业务异常抛 `BizException`，由全局处理器兜底；**堆栈只进日志，不出响应** |
| 日志 | 每个请求自动注入 traceId（MDC + 响应头 X-Trace-Id），一条日志能用 traceId 串起全链路；X-Trace-Id 是**业务关联号**（报障对账用），与 Micrometer Tracing 的全链路 span 树（`coupon.tracing.enabled` 开关，runbook/Tracing与E2E.md）分工不同、各自独立 |
| 单测 | 秒级、相互独立、不依赖 Spring 容器与真实中间件；状态机用迁移表驱动测试 |
| 架构决策 | 重大取舍写 ADR（`docs/adr/`），记录背景/决策/后果，防止半年后没人记得为什么 |
| Git | 分支 main；提交信息 `type: 中文描述`（feat/fix/docs/refactor/test/chore） |
| 配置 | 环境差异走 profile（application-dev.yml）；敏感信息不入库（application-local.yml 已 ignore） |
| 优雅停机 | `server.shutdown=graceful`，发版时不切断在途请求 |
