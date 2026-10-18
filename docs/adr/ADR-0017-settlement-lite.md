# ADR-0017：结算-lite、租户退款政策与内部查询

- **状态**：已接受并实施（环 3，2026-10-18；V24，主体账单开关 `coupon.billing.settle-party.enabled` 默认开）
- **实施状态**：已实施
- **关联**：01 G5/G10、02 §2.12、03 §1/§7、04 §4.1、05 环 3、ADR-0012、ADR-0014、ADR-0015

## 背景

环 1 把 `settle_party_id` 落到了模板/分账/退款明细；环 2 把试算价格冻结成快照。
但财务仍然只能按**模板**看日账，不能按**结算主体**看；客服只能查库，不能按退款单/订单查明细；
退款次数/退券次数仍是全局环境变量，多租户无法差异化。

环 3 的目标是“财务能按主体看账、客服能查退款、租户能配退款政策”，**不做**打款状态、
完整结算引擎、门店/业务单元、结算时点/方式。

## 决策

### 1. 主体日账单独立成表

新增 `coupon_settle_party_daily_bill`：

```text
bill_date + app_id + settle_party_id + template_id
  → confirm_count / refund_count / discount_total / platform_burden / merchant_burden
```

- 唯一键 `(bill_date, app_id, settle_party_id, template_id)`，重跑 upsert 幂等；
- 旧 `coupon_daily_bill` 不改字段/唯一键，继续按模板出账；
- 主体账单与模板账单**同源**：正分账行 − 退款明细 − 旧负分账行；旧负行用 `-ABS(...)`（原写法会把负数翻正、虚增账单）；
- `app_id`/`settle_party_id` 用 `utf8mb4_bin`：`A` 与 `a` 是两个主体，不能合并；
- 生成后 `reconcile(date)` 对账：主体合计必须等于模板账单；不一致 ERROR 留现场。

**为什么保留 `template_id`**：验收“同一模板多主体相加 = 模板账单”需要按模板聚合核对；
财务也要能定位“哪个模板在哪个主体上不一致”。

### 2. 两张账单各自事务，不互相拖累

模板账单和主体账单分别提交：

- 新表生成失败不回滚旧模板账单（05 要求“旧模板账单不动、新表独立可回滚”）；
- 两张账单同源 append-only，正常无并发写时守恒；两段之间恰有退款提交时，`reconcile` 留下差异日志，重跑收敛；
- `coupon.billing.settle-party.enabled=false` 时只出模板账单，主体账单可整体停用；
- 仍使用 `@Scheduled`（每天 00:30，Asia/Shanghai），多实例重复执行由唯一键 upsert 保证正确性；
  未加 Redis 锁/XXL-JOB/任务失败指标（后续可加，不影响正确性）。

### 3. 租户级退款政策

三个键放 `open_app_config`，未配置/配错回落全站/默认：

| 键 | 全站属性 | 默认 | 范围 | 语义 |
|---|---|---|---|---|
| `trade.refund.max_times` | `coupon.trade.max-refund-times` | 50 | 1~1000 | 单个占用单的退款次数；重放不计数 |
| `trade.refund.max_return_times` | `coupon.trade.max-return-times` | 3 | 0~100 | 一张券累计退券次数；0=不退 |
| `trade.refund.full_return_coupon` | `coupon.trade.full-refund-return-coupon` | true | bool | 整单退完且券有效时是否退回待使用 |

- **按券的 `app_id` 解析**，不是请求上下文：内部面没有 appId，政策必须跟着券走；
- 保留既有语义：部分退款不退券；整单退完且券有效才可能退券；门槛不追回；一券限退 N 次；
- 全局布尔也做合法性校验：`flase` 不能静默变成 false；
- `CachedTenantConfigService.put/remove` 增加 afterCommit 二次失效，避免并发读者在提交前把旧值重新缓存。

### 4. 查询只开内部面，且只允许 POST

新增：

```text
POST /api/refunds/query          退款单列表（usageNo/refundNo/orderNo/appId/主体/日期）
POST /api/refunds/detail         退款单头 + 行级冲回明细
POST /api/bills/settle-party/query  主体日账单
POST /api/bills/template/query      模板日账单
```

- 全部 POST：`AdminTokenFilter` 只保护非 GET 的 `/api/**`；GET 会在现有过滤器下裸奔；
- 不在 `BUSINESS_WRITE_PATHS` 里，自动要求 `X-Admin-Token`；
- 日期范围 ≤31 天、page ≤500、size ≤200；退款查询至少一个过滤条件；
- 按主体查询必须同时给 `appId`：主体 ID 是租户内语义，跨租户同名主体不能合并；
- 没有开放面版本：租户自助查询需要额外隔离/脱敏/限流，留环 4；
- 返回业务标识（userId/userCouponId）供客服定位，不含姓名/手机号/银行卡/支付信息。

### 5. 最小指标

`TradeMetrics`（Micrometer Counter）：

```text
coupon_confirm_total{result=success|duplicated|failed}
coupon_confirm_amount_total
coupon_refund_total{result=success|duplicated|failed}
coupon_refund_amount_total
```

- 标签只有固定枚举 `result`；不把 appId/主体/单号/模板放进标签（无界基数）；
- 重放不重复计金额；指标调用失败不影响业务（`safeMetric` 吞掉并 WARN）。

### 6. 历史数据与老式链路

- V22 前退款明细的 `settle_party_id` 统一是 `__default__`；V24 按 `(usage_no, order_line_no)` 从原分账行回填，只有原行有真实主体才复制；
- 仍为 `__default__` 的历史行无法恢复真实商家，**不能用于真实结算**；平台券的合法“无主体”也用这个哨兵，当前没有 `data_quality` 列区分；
- 历史 `app_id` 匹配不到模板时保持默认 `'1'`，会混入内置应用；
- 老式无 items 核销/退款没有分账/退款明细，不进两张账单；退款单 `funding_known=0` 标注 platform/merchant=0 不可用于结算；
- `refund_no/usage_no` 列仍是大小写不敏感 collation，与 Java 精确匹配不一致；改列需要大表 DDL，登记为已知边界。

## 理由

1. **财务可信**：主体账单与模板账单同源、可对账；净额/负数/跨日口径与现有模板账单一致；
2. **租户可运营**：退款政策从全局变成租户级，缺省回落保证旧租户行为不变；
3. **客服可查**：按退款单/订单/主体/日期查明细，不再查库；
4. **安全默认**：内部面 + POST + 管理令牌，不把财务数据放到无鉴权 GET；
5. **可回滚**：旧表不动；主体账单可整表停用；退款政策未配置回落全局。

## 后果

### 正面

- 财务能按主体看日账，且主体合计与模板账单可自动对账；
- 两个租户可以有不同的退款/退券政策；
- 退款/核销成功、失败、重放、金额在 Prometheus 可见；
- 老式退款有 `funding_known=0`，不会被误当真实出资。

### 代价与边界

- 主体账单逐行 upsert，大租户可能几千行；当前单事务可接受，规模化需批处理/`INSERT...SELECT`；
- 账单任务无 Redis 锁/XXL-JOB/失败指标；多实例重复执行幂等但会重复负载；
- DB session 时区是部署前置条件（cron/账单日固定 Asia/Shanghai，`CURRENT_TIMESTAMP` 的 session 时区需运维保证）；
- 历史 `__default__`/`'1'` 的归属不可信，未加质量列；
- 查询 `COUNT + LIMIT/OFFSET`，大表深层分页仍可能慢；
- 退款列表按退款单 `create_time`，账单按退款明细 `create_time`，跨午夜可能落在不同天。

## 备选方案（已否决）

1. **给 `coupon_daily_bill` 加 `app_id/settle_party_id` 列**：会改旧表/唯一键/历史数据，违背“旧表不动”；否决。
2. **模板级退款政策**：更细，但要动 `coupon_template`、建券/查询链路和存量回填；标准版先租户级；否决（后续可评估）。
3. **查询用 GET**：现有 `AdminTokenFilter` 不保护 GET，财务数据会裸奔；否决。
4. **两张账单同一事务**：新表失败会回滚旧模板账单，违背独立回滚；改为各自事务 + 对账；否决单事务。
5. **主体账单只按 appId+主体、不含 template_id**：无法与模板账单对账、无法定位差异；否决。
6. **直接用退款单 `create_time` 归日**：老式退款无明细时没有主体；账单必须以明细为准；否决。

## 验收

- ✅ 同一模板多主体金额相加 = 模板日账单（S1 + `reconcile`）；
- ✅ 跨日退款记退款日、允许负数（S2）；
- ✅ 两个租户可配不同退款次数/退券次数/整单退券（P1/P2/P4/P5）；
- ✅ 按订单/退款单能查明细（Q2/Q3）；
- ✅ 重跑账单结果不变（S1/S4）；
- ✅ 旧负行 `-ABS` 不虚增（S3）；主体大小写敏感（S5）；
- ✅ 老式退款 `funding_known=false`（T2 扩展）；并发同号退款不 500（R10）。

## 测试证据

- `SettlePartyDailyBillIntegrationTest` 5/5；
- `RefundPolicyIntegrationTest` 5/5；
- `FinanceQueryIntegrationTest` 8/8；
- `RefundIntegrationTest` 10/10、`ConfirmByPlatformIntegrationTest` 4/4、`DailyBillIntegrationTest` 2/2；
- 单元：`RefundPolicyServiceTest` 5/5、`TradeMetricsTest` 3/3、`TenantConfigKeyTest` 5/5、`TradeAppServiceTest` 5/5、`TradeTxServiceTest` 9/9。
