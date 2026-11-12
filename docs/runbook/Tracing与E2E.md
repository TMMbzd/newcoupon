# 运维手册：链路追踪（Tracing）与控制台 E2E（V6 生产化模拟刀 C）

> ⚠️ **2026-11-12 深夜更新：Tracing 已整体回退，本文 Tracing 部分仅作历史记录**
> micrometer-tracing 的**任何桥**（bridge-otel / bridge-brave，无论是否带 exporter）进
> classpath 都会使本项目的 Spring 上下文在 **lettuce 连接 Redis 时永久挂死**（main 线程
> WAITING 在 DefaultConnectionFuture.get，二分取证见 Tracing取证报告与债 L84）。纯依赖
> 零代码即复现，与刀 C 的实现代码无关——因此 tracing 依赖与全部接线已 revert，E2E 部分
> 不受影响继续有效。**重试触发条件**：Boot 3.3.6+/micrometer-tracing 升级或上游 issue
> 有解时，恢复 12bd724（git revert ed18539 的逆操作）并重跑 SecurityRbacIntegrationTest
> 做卡死回归探针。

> 面向部署值班/后端开发。覆盖两件交付：C2 Micrometer Tracing + OTLP + Jaeger 的开关与
> 起停，C1 控制台 Playwright 浏览器 E2E 的触发方式。设计出处：V6 计划 §5B 分节
> （docs/research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md）；
> 实跑取证：docs/evidence/2026-11-12-Tracing取证.md、docs/evidence/2026-11-12-E2E取证.md。
> 环境：WSL2 单机（~6.6G 内存）；应用跑宿主机，MySQL/Redis/Jaeger 跑 compose。

## 1. Tracing：开关与依赖

**一句话**：`coupon.tracing.enabled`（环境变量 `TRACING_ENABLED`，默认 **false**）是唯一
总开关。关 = 零导出零记录零业务开销（行为与刀 C 之前完全一致）；开 = OTel bridge +
OTLP 导出 + 异步线程传播 + 业务里程碑 span。

配置接线（application.yml，语义唯一来源在 `coupon.tracing` 命名空间）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `coupon.tracing.enabled`（`TRACING_ENABLED`） | false | 总开关；`management.tracing.enabled` 直接映射它 |
| `management.tracing.sampling.probability`（`TRACING_SAMPLING`） | 1.0 | 开关开时的采样率；dev/单机模拟全采，**生产建议 0.05~0.1** |
| `management.otlp.tracing.endpoint`（`TRACING_OTLP_ENDPOINT`） | `http://127.0.0.1:4318/v1/traces` | OTLP/HTTP 收集端点（Jaeger 4318）；开关关时此值不被使用 |

依赖（pom.xml，版本全部由 Boot BOM 3.3.5 纳管，无显式版本号）：
`io.micrometer:micrometer-tracing-bridge-otel` + `io.opentelemetry:opentelemetry-exporter-otlp`。

**Boot 3.3 开关语义缺口与补法（2026-11-12 实测钉住，TracingSwitchIntegrationTest 看守）**：
`management.tracing.enabled=false` 只拦导出器（`@ConditionalOnEnabledTracing` 标注的 bean），
OtelTracer/Sampler 仍会装配、span 对象仍会被创建（落不了后端但开销在）——所以"关"时由
`TracingOffConfig`（infrastructure/tracing/）主动提供 `Tracer.NOOP`，Boot 的真实 OtelTracer
是 `@ConditionalOnMissingBean`、让位。关的三条承诺（测试逐条钉住）：注入点拿 NoopTracer
（业务代码**零分支**）；上下文无任何 `SpanExporter` bean（零导出）；`nextSpan()` 返回
空 traceId 的无效 span（零记录零开销）。

## 2. Tracing：Jaeger 起停与查证

```bash
# 起观测栈（与 prometheus/grafana 同 profile，默认不起）
# 端口只绑回环（审查 m3）：16686=无认证 UI/API（可读全部 trace）、4318=无认证 OTLP 写入口
# （可灌假 span）——不对外暴露，应用与取证 curl 都在本机
docker compose --profile observe up -d jaeger
# UI / REST API：http://127.0.0.1:16686（服务名 newcoupon-server）
# OTLP 接收口：127.0.0.1:4318（应用的 TRACING_OTLP_ENDPOINT 默认即指这里）

# 起应用（开关开；其他环境变量照常）
TRACING_ENABLED=true ACTUATOR_AUTH_PASSWORD=it-actuator-pass \
  java -Xmx512m -jar target/*.jar --spring.profiles.active=dev

# API 查 trace（取证同路径；UI 与它同源）
curl -s 'http://127.0.0.1:16686/api/traces?service=newcoupon-server&limit=20&lookback=1h'

# 用完就停（内存纪律：all-in-one ~300-500MB，取证完释放）
docker compose --profile observe stop jaeger
```

**内存预算**：应用 512m（-Xmx）+ MySQL/Redis 常驻 + Jaeger mem_limit 512m——串行分阶段
起，WSL ~6.6G 下不与其他重活并行。取证完 `stop` 还原。

## 3. Tracing：打进代码里的三个观测点（谁在哪能看见什么）

| 观测点 | 位置 | 开关关时 |
|---|---|---|
| HTTP server span | Spring MVC 自动 instrument（2026-11-12 实测命名：OTel 语义 **小写方法 + 路由模板**，如 `http post /api/templates/{id}/publish`、`http get /api/templates`——参数路由是 `{id}` 占位，低基数由框架保证） | 无效 span，零记录 |
| Spring Security span | 框架自动：`security filterchain before/after`、`authorize request`、`secured request`——登录/管理面请求的安全段落在 trace 里可见 | 同上 |
| @Scheduled task span | 框架自动：`task <bean>.<method>` 根 span（如 `task outbox-dispatcher.dispatch`）。**注意量级**：issue worker 每 200ms 一轮 → dev 全采下 ~5 span/s，生产采样率必须压低（§5） | 同上 |
| 业务里程碑 `coupon-issue-persist` | `CouponIssueTxService.issueC1`（同步领券/逐任务路径的落库咽喉；tags: milestone/template-id。注意 dev 的 worker 默认走 raw-bulk 批量快路径、不经 issueC1——worker trace 里没有该 span 属预期） | NoopTracer，零开销 |
| 批量 worker 轮 `issue-worker-round` | `CouponIssueTaskWorker.runOnce`（@Scheduled 与 XXL-JOB 双入口同一处；空轮不建 span；tags: claimed/owner/thread） | 同上 |
| 审计异步 `audit-write` | `AuditService.record`（在 audit-writer 线程上创建——异步传播是否成立就看它挂不挂回父 trace） | 同上 |

**业务里程碑进 trace 而不是只进日志的价值**（示例）：用户说"领券没到账"。日志时代：拿
X-Trace-Id 逐服务 grep 时间拼对齐；trace 时代：Jaeger 里找到该请求 → `coupon-issue-persist`
存在即落库成（无此 span = 没走到落库）、耗时多少、与准入/审计 span 在同一时间线——
结论 30 秒内给出。"哪一轮 worker 发的这批、哪块失败"（`issue-worker-round`）同理。

**异步传播（TaskDecorator 纪律）**：自建线程池必须包
`ContextPropagatingExecutors.wrap(...)`（infrastructure/tracing/）——**两层包装缺一不可**
（实测结论）：OTel `Context.current().wrap(task)` 过 bridge 的 span 上下文 +
Micrometer `ContextSnapshot` 过 Observation/MDC。已包（2026-11-12 审查 M1 后全量清点）：
audit-writer、audit-export-writer、issue-task-worker 池（以上两处 worker 链）、
**issue-task-shard-group**（CouponIssueTaskProcessor 懒建的分片组池——批量事务失败
退回"按分片拆组"降级路径的事务线程，漏包时恰在故障排查场景裂 trace，
TracingGroupPoolPropagationIntegrationTest 钉住）。@Async/@Scheduled 由 Boot 自动挂
同源传播，不经本类。未包登记：`CachedTemplateReader` 的 Caffeine 刷新池
（缓存刷新不产生业务 span，包了无收益）。

**X-Trace-Id 与 Micrometer traceId 的分工**：现有 `TraceIdFilter` 的 X-Trace-Id 是**业务
关联号**（报障对账、用户拿号查日志），保持不动、默认常开；Micrometer traceId 是**全链路
span 树**（本开关控制）。两套 ID 各自独立生成，互不替代——docs/operations/SLO.md §1、
README 工程规范表同口径。

## 4. E2E：触发方式与前置

用例本体 `console/e2e/console.spec.ts`（五步与 runbook/控制台最小页.md §6 一一对应）；
驱动者 `ConsoleE2eIntegrationTest`（起真实应用 RANDOM_PORT + Testcontainers，再用
ProcessBuilder 拉起 `npx playwright test`，凭据经环境变量传入——Node 崩溃/超时不会拖垮
测试 JVM）。

```bash
# 一次性前置（浏览器 ~170MB 下载到 ~/.cache/ms-playwright；本 WSL 无需 install-deps）
cd console && npm install --no-fund --no-audit && npx playwright install chromium && cd ..

# 触发（默认被 surefire 排除；置空排除组放行）
mvn test -Dtest=ConsoleE2eIntegrationTest -De2e.excludedGroups= -B

# 验证默认排除（应显示 Tests run: 0）
mvn test -Dtest=ConsoleE2eIntegrationTest -B
```

**运行纪律**：@Tag("e2e") + surefire `excludedGroups=e2e`（pom 属性 `e2e.excludedGroups`，
可置空放行）——全量 `mvn test` 与 CI（.github/workflows/ci.yml 跑 `mvn clean test`）都
**不含它**：E2E 重（Spring 上下文 + Testcontainers + chromium 短时 ~2.5G 内存峰值）。
列表面板截图落在 `target/e2e/console-list.png`（每次运行覆盖）。

**架构选择说明（为什么 Playwright 是独立进程而不是 JVM 内嵌库）**：浏览器栈（Node/
chromium 版本矩阵）与 JVM 构建解耦——浏览器坏了只是子进程退出码非 0，测试 JVM 与
Testcontainers 生命周期不受牵连；前端侧工具链（npm 工程在 console/）保持内聚。

## 5. 单机模拟声明与裁剪登记（没做什么、什么时候补）

单机声明：Tracing 与 E2E 的验证都在**单实例、宿主机应用 + compose 依赖**形态下完成；
多实例下的 trace 跨 JVM 传播（HTTP 客户端注入 traceparent）未在本刀演示——MVC 服务端
自动消费 traceparent 已由框架保证，客户端注入在出现第二个服务时才有意义。

| 裁剪项 | 理由 | 升级路径 |
|---|---|---|
| 生产采样率 5~10% | dev/单机模拟要看得全（100%）；生产采样是流量与成本的权衡，不应拍脑袋定 | 上线前按 QPS×span 均值×保留期估算，改 `TRACING_SAMPLING` 一个环境变量 |
| tail sampling（只留错误/慢请求） | 需要独立 Collector（OTel Collector）做流式采样决策，单机模拟引它是纯运维成本 | 部署 OTel Collector：`tail_sampling` 策略（错误全留 + 慢请求 + 基础比例），应用端点改指 Collector |
| Jaeger 后端存储 | all-in-one 内存态（重启即空），够单机取证 | ES/Cassandra 后端 + collector/query 分离；或按 V6 计划改 Grafana Tempo（复用现有 Grafana） |
| E2E 进 CI | E2E 重（chromium + 上下文 ~2.5G），CI 每 push 跑一遍性价比为负 | 控制台高频迭代期再考虑夜间/手动 workflow 触发 |
| JDBC/Redis 客户端 span | 需要 OTel javaagent 或手动埋点；当前里程碑 span（落库/审计/worker）已覆盖定位诉求 | 需要语句级追踪时评估 javaagent 侧车方式（不改代码） |
| open/v1 客户端注入 traceparent | 单实例无第二跳，注入无对照面 | 出现第二个服务/网关时由 RestTemplate/WebClient 自动 instrument 补 |

## 6. 故障排查速查

| 症状 | 先查什么 |
|---|---|
| Jaeger UI 空 | ① `TRACING_ENABLED=true` 是否传到应用（启动日志 grep tracing）② Jaeger 容器起没起（`docker compose --profile observe ps`）③ 应用→4318 连通（宿主机 `curl -s http://127.0.0.1:16686/api/services`） |
| 按 `POST /path` 搜不到 HTTP span | 命名是小写 OTel 语义：`http post /path`（Jaeger 检索框输 `http post` 就能列出） |
| 测试启动炸 `Unable to obtain inputstream for resource: db/migration/V10__*.sql`（V1~V9 明明正常） | **不是代码问题**：VSCode Java 语言服务（JDT/redhat.java）在 pom/源码变更后并发重写 target/classes，与正在跑的 maven 读迁移文件竞争——文件被替换的瞬间读到空（2026-11-12 刀 C 实测，取证 docs/evidence/2026-11-12-E2E取证/ `c1-red2`）。处置：改完 Java/pom 等 JDT 静默（`until [ "$(find target/classes -newermt '20 seconds ago' | wc -l)" -eq 0 ]; do sleep 5; done`）再跑 maven；重跑即可 |
| trace 里没有 audit-write span | audit-writer 池是否还包着 `ContextPropagatingExecutors.wrap`（少了 OTel 层=裂新 trace，TracingAsyncPropagationIntegrationTest 红） |
| 开关关了但想确认零导出 | `TracingSwitchIntegrationTest`（bean 级）+ 取证文档的关开关对照（运行级） |
| E2E 报 Playwright 工程不存在 | 在仓库根跑（surefire 工作目录=模块根），或 console/ 被改动——见 console/README.md |
| E2E 报浏览器没装 | `cd console && npx playwright install chromium`（~/.cache/ms-playwright 缺失） |
