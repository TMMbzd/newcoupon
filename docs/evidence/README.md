# 证据索引（2026-11-06 收官）

本目录是 V6 全部"可复跑证据"的入口。每条给出：**文件路径 + 一句话 + 关键数字**。
口径统一：WSL2 单机、MySQL 8 / Redis 7 / Testcontainers；吞吐与延迟是**下限观测**，
不是生产 SLA。所有脚本都必须能按各分报告里的命令复跑。

> 状态权威仍是 [docs/STATUS.md](../STATUS.md)；七职责→实现→证据的最终映射见
> [docs/收官-2026-11.md](../收官-2026-11.md)。

---

## 1. 压测

| # | 证据件 | 一句话 | 关键数字 |
|---|---|---|---|
| 1 | [2026-10-23-异步发放1k2k压测报告.md](2026-10-23-异步发放1k2k压测报告.md) | 异步发放受理面与 worker 吞吐的原始压测 | 1k/2k 并发**全部 HTTP 202 + 业务码 0**；观测吞吐 **711–1382 / 1050–2122 req/s**，p99 **744–1464 / 737–1777 ms**；4k 打 3k 库存：**success=3000、售罄=1000、无重复**；worker 裸 JDBC 批量写 **1.27k–1.6k task/s** |
| 2 | [2026-11-06-压测复用.md](2026-11-06-压测复用.md) | 5B 复跑 1k 受理与 worker 清空，并实锤修复 L78 | 1k 受理 **478.4 / 717.5 / 610.9 / 422.6 req/s**（1000×202/0）；raw-bulk **1001 条 / 1.202 s ≈ 833 task/s**；日限模板 **3001 条 / 56.003 s**；`dly_reconcile_fix` **delta=+1 两行实锤**；L78 单测先红 **13/1** 后绿 **13/0** |

复跑入口：`scripts/load/async_issue_load.py`（客户端）；服务端参数见两份报告 §2。

## 2. 故障演练与恢复

| # | 证据件 | 一句话 | 关键数字 |
|---|---|---|---|
| 3 | [2026-11-06-MySQL故障演练.md](2026-11-06-MySQL故障演练.md) | MySQL 硬故障（`docker stop`）+ 恢复自愈 + Redis 复跑 | MySQL **DRILL PASS assertions=21**：故障期同步/异步/核销 5000、熔断 OPEN、恢复 code=0、10 请求零落券零任务、库存守恒；Redis **DRILL PASS assertions=14**：异步 5001、故障期同步 2/2 成功、fail-open/reject 计数可见 |
| 4 | [2026-11-06-双实例冒烟.md](2026-11-06-双实例冒烟.md) | 两实例共享 MySQL/Redis，滚动重启 A/B | **DRILL PASS assertions=18**；**1056/1056 成功**、failover **444**、**5xx 0 / 在途 reset 0 / 业务失败 0**、`stopFileSeen=true`；A/B 优雅停机 **311 ms / 311 ms**；库存守恒 |
| 5 | [2026-11-06-备份恢复演练.md](2026-11-06-备份恢复演练.md) | 逻辑备份 + 恢复到 `newcoupon_restore` + 20 条断言 | dump **1.918 s**（305 表 / 24.78 MB / 14876 张券）；restore **44.784 s**；verify **5.613 s**；**RTO 50.397 s**；**RESTORE PASS assertions=20**（基准=dump 时刻 `.meta`，不做源库对比；导入前断言 dump 内嵌库名防误伤源库） |
| 6 | [2026-11-06-运维实证汇总.md](2026-11-06-运维实证汇总.md) | 四件套的汇总索引 + 裁剪登记 + 审查反驳核实 | 四件证据索引、脚本清单、11 项裁剪/未认证项、5 项审查反驳核实结论 |

复跑入口：`scripts/stability/mysql-outage-drill.sh`、`scripts/open-api/drill.sh`（Redis）、
`scripts/stability/dual-instance-smoke.sh`、`scripts/backup/{backup,restore}.sh`。

## 3. 契约与门禁

| # | 证据件 | 一句话 | 关键数字 |
|---|---|---|---|
| 7 | [../open-api/openapi-v1.json](../open-api/openapi-v1.json) | OpenAPI v1 黄金基线（机器生成；C4 定向端点已再生成） | **28 条 path**；只含 `/open/v1/**`（泄漏防守用例钉住） |
| 8 | [OpenApiContractIntegrationTest](../../src/test/java/com/newcoupon/integration/OpenApiContractIntegrationTest.java) + [OpenApiDriftComparator](../../src/test/java/com/newcoupon/integration/OpenApiDriftComparator.java) | "只增不改"契约门禁：基线再生成前先 diff，破坏性变更拒绝写文件 | **10 例**契约测试；比较器 **18 条破坏性规则**；自检 **33 条破坏变异逐条命中 + 17 条 additive 放行**；匿名拉 spec 401；默认组端点 404 |
| 9 | [ops/prometheus](../../ops/prometheus/)（[README](../../ops/prometheus/README.md)） | SLO recording/alerting 规则 + promtool 行为测试 | **9 条 recording + 4 条 alerting**；`promtool check rules` 与 `promtool test rules` 两条命令均 **EXIT=0**（快烧/慢烧/断流/低流量/延迟劣化五组用例） |
| 10 | [docs/operations/SLO.md](../operations/SLO.md) | 四链路 SLI/SLO 口径、错误预算政策与"不做"清单 | receive/calculate/confirm/refund 四链路；28 天滚动预算；>100% 冻结发布、单日 >20% postmortem；单实例声明 `ha="false"` |

## 4. 审计与安全

| # | 证据件 | 一句话 | 关键数字 |
|---|---|---|---|
| 11 | [../runbook/审计查询.md](../runbook/审计查询.md) | 审计查询/导出/哈希链巡检的运维口径 | `hash_version=2`（含 actor/session 列）；链头 `audit_chain_head` 单行 FOR UPDATE 串行；`/api/audit/verify` 全链重算 + 链头对账；导出 manifest SHA-256 |
| 12 | [AuditQueryIntegrationTest](../../src/test/java/com/newcoupon/integration/AuditQueryIntegrationTest.java) + [AuditHashVersionTest](../../src/test/java/com/newcoupon/application/service/AuditHashVersionTest.java) | 审计链/查询/导出/巡检的自动化证据 | **15 + 3 例**；覆盖篡改行检出、链头尾删对账、游标分页、导出一次性下载 |
| 13 | [scripts/risk/attack-device.sh](../../scripts/risk/attack-device.sh) | 设备日限风控攻击脚本（dev 实跑 PASS） | **12 条断言**；第 **11** 个连发请求返回 **7003** |
| 14 | [scripts/risk/attack-refund.sh](../../scripts/risk/attack-refund.sh) | 退款风控合规红线攻击脚本（dev 实跑 PASS） | 退款**照做** + REJECT 决策留痕 + **模板级 hold** 落账（不按主体级放大） |
| 15 | [环0-证据链-全记录.txt](环0-证据链-全记录.txt) | 地基阶段（447 例时代）的红/绿与根因全记录 | 旧代码 **5 ERROR** 根因=裸 `@SpringBootTest` 依赖本机 Redis；修复后逐步基线 461→464 |

## 5. 阶段 6 最终修复（L75）

| # | 证据件 | 一句话 | 关键数字 |
|---|---|---|---|
| 16 | [L75ConfirmToctouIntegrationTest](../../src/test/java/com/newcoupon/integration/L75ConfirmToctouIntegrationTest.java) | confirm 事务内 TOCTOU + 审查整改：窗口注入、两线程行锁、事务层幂等、跨租户、部分退款 | **10 例**；主体修复前 **4 例红**（T1/T2/T5/T6），修复后 6/6；审查后 T7 两线程行锁（摘 `FOR UPDATE` 变异红）、T8 事务层幂等（duplicated 后移变异红）、T9 部分退款（摘分支变异红）、T10 跨租户；最终 **10/10 绿**；指定回归面 **55/55**，其余 confirm 相关 **80/80** |
| 17 | [2026-11-06-L75红绿取证.md](2026-11-06-L75红绿取证.md) | L75 主体 + 25-agent 审查整改（C1/C2/C3/C5/C6）的红/绿与变异自检全记录 | 红：**6 例 / 4 失败（EXIT=1）**；绿：**6/6**；审查后 **10/10**；变异自检 3 处（摘 `FOR UPDATE` / 后移 duplicated / 摘 PARTIALLY_REFUNDED）均先红后绿；回归 **55/55 + 80/80 + 22/22** |
| 18 | [GrantTaskIntegrationTest](../../src/test/java/com/newcoupon/integration/GrantTaskIntegrationTest.java) + [BudgetIntegrationTest](../../src/test/java/com/newcoupon/integration/BudgetIntegrationTest.java) | C4 定向发放（grant_type=2）全链证据：发 N 张/幂等/越权/端点/预算 | 定向 4 例 + 预算 1 例；GrantTask 全类 **16/16**、Budget **11/11**；基线 25→**28 path**（仅新增 3 条定向路径） |

---

## 6. 复跑纪律

1. **一次一个 Maven**：WSL2 只有约 6–7 GB 内存，集成测试各带一套 Spring 上下文；串行跑。
2. **跑前停应用与闲置容器**：Testcontainers 自己起 MySQL/Redis；dev 容器叠加会压垮 WSL。
3. **EXIT 码即结论**：脚本/RESTORE/DRILL 都打印 `PASS assertions=N` 且以 EXIT=0 收尾；
   只贴日志里某一行不算证据。
4. **先红后绿**：修复类证据必须同时保留"修复前失败输出"和"修复后通过输出"；
   本文与各分报告引用的数字均来自实际命令输出。
5. **不把本机数字外推为生产 SLA**：RTO/吞吐/延迟都标注了单机口径与归因（例如 1k 受理
   吞吐受对账任务并行影响）。
