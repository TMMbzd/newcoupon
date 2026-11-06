# SLO 与错误预算（V6 阶段 5A）

> 术语约定（首现三件套）：
> **SLI**（Service Level Indicator，服务等级指标）——衡量"服务做得好不好"的那个数，
> 本项目是四条链路的可用性比率与延迟分位；**SLO**（Service Level Objective，服务等级目标）
> ——给 SLI 定的目标线，达成即"用户满意度不掉"；**错误预算**（error budget）——
> 1 减去 SLO 得到的"允许失败的量"，烧完即冻结非关键发布，是 SRE 用数据换研发速度的机制。

## 1. 定位与范围

本文是四条核心链路 SLO 的**唯一口径文档**：SLI 怎么测、目标定多少、预算怎么花、告警怎么响。
配套实现三处，改口径必须四处同步（本文 / `SliMetrics.java` / `ops/prometheus/*.rules.yml` /
`ops/prometheus/slo-rules.tests.yml`）。

- **谁怎么用**：值班/on-call 看 §5 告警处置；发布决策看 §4 预算政策；
  面试/评审用 §2+§3 讲清"为什么不用 HTTP 200 当 SLI"。
- **测量口径**：单实例、非 HA（§6）——多实例部署后本文口径要重述，不能直接沿用。

## 2. SLO 目标（28 天滚动窗口）

| 链路（journey） | 入口（接线位） | 可用性目标 | 延迟目标 | 错误预算（1−SLO） |
|---|---|---|---|---|
| receive 领券 | `ReceiveAppService.receive` | 99.9% | P99 ≤ 300ms | 0.1%（0.001） |
| calculate 试算 | `CalculateAppService.calculate` | 99.9% | P99 ≤ 150ms | 0.1%（0.001） |
| confirm 核销 | `TradeAppService.confirm` | 99.95% | P99 ≤ 500ms | 0.05%（0.0005） |
| refund 退款 | `TradeAppService.refund` | 99.95% | P99 ≤ 500ms | 0.05%（0.0005） |

目标值取自 V6 实施计划 §2.5 的建议值（核销/退款是动钱链路，目标收紧一档——
失败一单的资损与客诉代价高于领券）。锁券/释放（lock/release）**不是独立 SLI**：
它们是核销链路的中间步骤，单独测会把一次用户旅程拆成两半各背一半预算。
延迟目标同样有执法：P99 超档位持续 10 分钟触发 ticket 告警（§5、ops/prometheus/），
不是只有测量没有告警。

## 3. SLI 口径

### 3.1 测量点：服务边界，不是 HTTP 层

SLI 在 **AppService 公开方法**（含异常出口）测量，由 `SliAspect` 挂 `@SliJourney` 注解包裹。
**为什么不用 HTTP 200 / `TradeMetrics.failed` 当 SLI**：HTTP 层只回答"传输是否成功"，
业务拒绝（售罄/参数错）在 HTTP 层可能表现为 4xx 甚至 200+业务码；`TradeMetrics` 只覆盖
核销/退款两域且把业务拒绝记成 failed。用它们当 SLI，"库存售罄的正常业务终点"会被算成
服务失败——错误预算在售罄风暴里被无辜烧光，真正的事故反而没了预算可用。

四条链路的异步变体（异步受理 202）不在本刀埋点范围：异步受理的 202 响应是**受理成功**
（good 口径），受理被风控 7003 拒绝是 excluded——该口径在接入异步链路埋点时按此执行。

**覆盖矩阵（审查 minor 修正后登记的盲区）**——receive journey 只覆盖同步领券 API：

| 发券入口 | 是否进 SLI | 说明 |
|---|---|---|
| `POST /api/coupons/receive`（同步领券，ReceiveAppService 门面） | **是** | 本刀四埋点之一 |
| 兑换码同步兑换（`CouponCodeService.redeem`） | **否（盲区）** | 直调 C1ReceiveAppService，不经挂注解的门面——**兑换码链路事故既不烧 receive 预算也不触发任何 SLO 告警**，对监控不可见 |
| 批量发放 worker（`GrantTaskWorker`）、异步受理（`ReceiveAsyncAppService`） | **否（盲区）** | 同上，直调 C1；异步受理另有任务表/补偿指标可观测，但不在 SLI 口径内 |

升级路径：各自补 journey（如 `redeem`/`grant`）或收敛到统一门面再挂注解——按流量形态
决定（兑换码是用户侧入口，优先级高于批量 worker）。

### 3.2 判定表（good / bad / excluded）

`coupon_sli_events_total{journey, outcome}` 的 outcome 判定规则，按全局错误码分段
（`ErrorCode` 的分段规范）落桶：

| outcome | 判定 | 错误码 | 语义 |
|---|---|---|---|
| **good** | 业务码 0（正常返回） | SUCCESS(0) | 服务做对了。**幂等命中（duplicated=true）也返回业务码 0 → good**——重放拿回旧结果就是服务的正确行为，不是失败 |
| **bad** | 5xxx 段 + 未捕获异常 | SYSTEM_ERROR(5000)、DEGRADED(5001)、FROZEN(5002)、REQUEST_BUDGET_EXHAUSTED(5003)、非 BizException 的未捕获异常 | 服务没做到：系统错误、依赖降级/熔断快速失败、时间预算耗尽（超时族）。**FROZEN 刻意归 bad**：冻结是防护动作触发的不提供，不是正常业务终点——冻结期间用户确实领不到券，烧预算才能让它在监控上可见（与售罄相反） |
| **excluded** | 其余全部 | 1xxx 参数/通用（含 REQUEST_DUPLICATED 1001——同 requestId 用到别的模板是调用方错误）、2xxx 模板态、3xxx 领券域业务拒绝（售罄 3001、限领 3002、每日发放上限 3003、预算耗尽 3009…）、4xxx 用券域状态冲突、6xxx 接入层、7003 风控拒绝、7010-7012 管理面 | 服务**正确地拒绝了**——不是服务失败，不进可用性分母 |

两点边界说明：

- **6xxx（接入层）实际到不了测量点**：签名/配额/nonce 在过滤器层就被拒，请求根本不进
  AppService。判定表把它归 excluded 只是为了对全码段闭合——新增业务码自动落 excluded 桶
  （新码几乎总是业务语义），新增 5xxx 自动落 bad（新码属于系统与依赖段）。
- **服务端视角**：调用方等不及主动断连时，服务端仍会完整执行并记 good——这是所有
  服务端 SLI 的共同盲区，客户端体验监控不在本刀范围。

判定表的三份拷贝必须一致：本文、`SliMetrics.classify`（代码）、`SliMetricsTest`（码表驱动单测）。

### 3.3 可用性公式：excluded 不进分母

```
availability = good / (good + bad)        # excluded 从分子分母同时剔除
```

**为什么剔除 excluded**：售罄风暴里每小时百万次 STOCK_OUT 是库存模型的**正确答案**，
不是服务失败——把它算进分母，可用性瞬间跌穿 SLO，错误预算被"业务太火爆"烧光，
然后全公司 freeze 发布去修一个没坏的东西。excluded 的量级另有
`coupon_receive_total{result=...}` 等域指标观察，不需要 SLO 承载。

### 3.4 延迟口径：全事件 P99（与计划的一处偏差）

```
latency = histogram_quantile(0.99, sum by (journey, le) (rate(coupon_sli_duration_seconds_bucket[1h])))
```

`coupon_sli_duration_seconds{journey}` 计时覆盖**全部**事件（good/bad/excluded 都停表），
延迟 SLI 因此是"全事件 P99"而非计划文中字面的"good 事件 P99"。刻意偏差，两个理由：

1. **bad 事件恰恰是最慢的事件**（超时族 5003 就是延迟爆炸本身）——把 bad 从延迟 SLI 里
   剔掉等于把病历上最重的病人划走，延迟 SLO 永远达标直到彻底不可用；
2. excluded（售罄判定）是正常完成的快速响应，其耗时本来就是链路延迟的一部分。

指标侧只有 journey 一个标签（设计裁定 5A-§A），真要 good-only 分位需给 Timer 加 outcome
标签，直方图序列数翻三倍——收益不抵成本，不做。

### 3.5 低流量保护

样本量不足时**不产出** availability 序列（空值，而不是 0 或噪声比值）：
1h 窗 < 10 事件、6h 窗 < 30、3d/28d 窗 < 100。下游燃烧率/预算记录与告警随之无数据。
理由：零星样本下的比率没有统计意义（1 个 bad 事件在 3 个样本里就是 33% 错误率，
会 page 全公司）——宁可不判，不可误判。副作用：极低流量环境（如本地 dev）SLO 告警
整体静默，这是接受的口径。

### 3.6 序列预建

12 条 events 计数序列（4 journey × 3 outcome）在应用启动时即注册（值恒 0），
与 `GovernanceMetrics`"首次 increment 才建序列"刻意相反。两个理由：

1. **断流告警的语义成立**（§5 CouponSliStreamMissing）：序列从启动就存在，
   "消失 10 分钟"才能可靠等价于"实例没了/抓取断了"，而不是"这会儿没流量"；
2. **全 bad 场景可计算**：good 序列存在（值 0）时可用性算出 0；序列不存在时
   PromQL 的 good 项为空向量，可用性直接无数据——最惨的时刻反而失明。

## 4. 错误预算政策（28 天滚动）

- **预算烧 > 100%**（`coupon:sli_error_budget_remaining:ratio < 0` 持续）：
  冻结非关键发布（功能迭代让位于稳定性修复），直到预算回正；
- **单日烧 > 20%**（28 天预算的 1/5 在一天内烧掉）：触发 postmortem（复盘），
  产出根因与改进项——单日 20% 意味着按此速率 5 天烧完全年缓冲；
- **演练窗口的 bad 事件**：故障演练（5C 范围）会故意制造 5xxx，属于**已知的、
  主动的**预算消耗。排除流程（手动）：演练前记下 `increase(coupon_sli_events_total{outcome="bad"})`
  基线，演练后把窗口增量记入演练报告并在预算复核时人工剔除——不在指标侧打
  drill 标签（那要给 Counter 加演练维度，基数换不来对等的防御力）。

## 5. 告警（severity 分级口径）

| 告警 | 条件 | 级别 | 含义与首步动作 |
|---|---|---|---|
| CouponSloFastBurn | 1h 燃烧率 > 14.4×，持续 2m | **page**（立即处理/值班） | 约 2%/小时在烧预算——定位 bad 来源（5xxx 分布/未捕获异常日志），必要时降级止血 |
| CouponSloSlowBurn | 6h 燃烧率 > 6×，持续 15m | **ticket**（工作时段建单） | 6 小时烧了约 5% 预算——查趋势根因，不急着叫醒任何人 |
| CouponSloLatencyBudgetTicket | 链路 P99（5m 窗）超其档位（300/150/500ms），持续 10m | **ticket** | 延迟劣化是趋势性问题（慢查询/缓存失效/GC）——建单查慢源；真雪崩会先由快烧 page 兜住 |
| CouponSliStreamMissing | 任一 journey 的 events 指标 10 分钟无样本 | **page** | SLI 失明：实例宕机或 Prometheus 抓取失败——先恢复可见性，失明期间 SLO 全部不可判。**边界**：@SliJourney 注解被误删时序列仍暴露（恒 0），本告警不响——埋点回归由 `SliMetricsIntegrationTest` 的四链路接线用例防，不在本告警承诺内 |

阈值依据 Google SRE 工作簿多窗燃烧率法（28 天窗口）。标准做法里快烧还配 5m 短窗
二次确认（双窗 AND）防单点抖动，本刀按裁剪线用单窗 + `for` 持续时间替代（登记在 §7）。

## 6. 单实例声明

`coupon_deployment_info{topology="single",replicas="1", ha="false"}` 恒 1（Info 风格）。
本 SLO 的全部测量都在**单实例、非 HA** 口径下：实例挂了 = 服务 100% 不可用，
可用性 SLO 实际测的是"活着期间的软件质量"，不含故障转移（本来也没有）。
上多实例（5B）时：SLI 聚合天然跨实例（Prometheus 按 journey 求和），但"单实例口径"
的声明与容量结论（单机结果只代表形状/下限）必须同步重述。

## 7. 不做（本刀裁剪登记）与升级路径

| 不做 | 理由 | 升级路径 |
|---|---|---|
| Grafana 大盘 JSON | 可用性/预算/燃烧率/延迟四类口径都已有 recording + alerting 执法（ops/prometheus/），大盘是呈现层不产生新口径 | 5B/后续：按 SLO.md 的公式直配面板；provisioning JSON 进 Git |
| Tracing 全家 | 刀 2（5B）登记总口径：Micrometer Tracing + OTel bridge + OTLP，与 SLI 分开交付 | 见 V6 计划 5B 分节 |
| 告警路由分级实现（Alertmanager） | 规则里已写 severity: page/ticket（§5 口径），路由是部署侧组件；本地无告警通道可接 | 部署 Alertmanager：page → 值班渠道（电话/IM 加急），ticket → 工单系统；route 按 severity 分组 |
| 快烧双窗确认（1h AND 5m） | 单窗 + `for: 2m` 已挡记录评估边沿抖动；双窗的边际收益在当前流量形态下不显著 | 流量上来后把 expr 改为 `burn:1h > 14.4 and burn:5m > 14.4`（需补 5m 窗记录） |
| good-only 延迟分位 | 见 §3.4——bad 事件正是延迟最差的事件，剔除等于藏病 | 如确需：Timer 加 outcome 标签（序列 ×3），另立 good_p99 记录 |

## 8. 验证与运维命令

```bash
# promtool 校验（本机用容器跑，见 ops/prometheus/README.md）
cd ops/prometheus
docker run --rm -v "$PWD":/rules --entrypoint /bin/promtool prom/prometheus \
  check rules /rules/slo-recording.rules.yml /rules/slo-alerts.rules.yml
docker run --rm -v "$PWD":/rules --entrypoint /bin/promtool prom/prometheus \
  test rules /rules/slo-rules.tests.yml

# 常用即时查询（Prometheus）
histogram_quantile(0.99, sum by (journey, le) (rate(coupon_sli_duration_seconds_bucket[1h])))
sum by (journey, outcome) (rate(coupon_sli_events_total[5m]))
```

测试侧对应：`SliMetricsTest`（判定表码表驱动）、`SliMetricsIntegrationTest`
（三 outcome 计数 + 四链路接线 + 抓取暴露 + 基数防线不误杀内建 HTTP 指标）、
`MetricsCardinalityConfigTest`（基数防线，含"拒新不烧总量预算"语义）。
