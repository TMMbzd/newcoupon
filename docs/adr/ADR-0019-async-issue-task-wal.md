# ADR-0019：异步发放任务表 WAL 与限领缓存

- **状态**：已接受（2026-10-22）；V27/V28 schema、Redis 准入、202 受理、状态查询、worker、硬日限与 1k/2k 压测已完成（2026-10-23）；单实例 worker 已通过裸 JDBC 物理表批量写达到 1k+；`raw-bulk-enabled=false` 回退已有单元/真实库集成回归，见 §10.2
- **日期**：2026-10-22
- **关系**：在 [ADR-0018](ADR-0018-stock-model-c1-fixed-shards.md) 的 C1 DB 分片账本之上增加异步受理层；不取代 C1，也不把库存权威交回 Redis
- **已确认的产品语义**：
  1. 受理返回 `202 + requestId`，最终结果用 `GET .../{requestId}` 查询；
  2. 日限是**硬约束**：最终成功发券数不超过 `dailyLimit`，但 `202` 只表示“已受理”，worker 后置可能返回 `REJECTED`
- **一句话**：Redis 做预扣和准入缓存，同步落一张 `coupon_issue_task` 任务表作为 WAL，异步 worker 再按 C1 的 `used<quota` 条件更新落库；DB 仍是最终账本，任务表保证异步不丢。

---

## 1. 背景

C1 同步领券一笔大约 10 条 SQL：

```text
幂等 SELECT 1
每人总限 COUNT 1
每人日限 COUNT 1
模板日限 COUNT 1
MAX(seq) 1
库存 UPDATE 1
user_coupon / coupon_event / coupon_outbox / coupon_expire_task INSERT 4
```

问题不在某一条 SQL，而在：

1. 三个 COUNT 随历史/当天数据量增长，尤其模板日限的 `COUNT(coupon_event)` 是 O(当天事件数)；
2. 同步路径要等 5 条业务写完成，1k–2k QPS 下 DB 瞬时压力和尾延迟都高；
3. 大促/售罄洪峰中，大量请求打到 DB 做注定失败的库存更新；
4. 模板、限领、幂等这些读/准入判断本可以缓存或原子计数，不应每请求查库。

目标不是“把库存权威搬回 Redis”，而是把**请求受理**与**最终落库**解耦：Redis 挡住大部分无效流量，任务表承接已受理请求，worker 批量落 C1 账本。

---

## 2. 为什么选 Vivo 方案 2，而不是纯 Redis 异步

vivo 的公开文章给出两种缓存扣减后的落库方案：

- 方案一：Redis 数据直接异步更新数据库；简单，但 Redis 宕机/故障可能丢缓存库存。
- 方案二：Redis 扣减时，**同步在数据库插入库存任务表**，后续异步任务从任务表同步到业务库存表；任务表是防丢的 WAL。

vivo 线上秒杀采用方案二，并配合前置限流控制流量（来源见 §12）。

我们采用方案二的思路，但做两点适配：

1. **任务表不是新的库存账本**：它只记录“已受理的领券意图”；库存最终仍由 C1 `coupon_stock_shard` 的条件更新裁决。
2. **不引入旧版 16 桶/世代/开桶/守恒**：Redis 只做一个模板级的 gate 计数和限领/幂等计数；DB 分片仍是唯一库存账本。

纯 Redis 异步（Redis 作为库存权威）吞吐更高，但 Redis AOF everysec/主从切换存在丢写窗口，需要双账本对账和更复杂的补偿。C1 刚把这类复杂度删掉，不在 1k–5k 目标上重新引入。

---

## 3. 请求路径（同步受理）

```text
① 本地模板缓存读模板（0 次 DB）
② Redis Lua：幂等 + 每人总限/日限 + 模板日限 + 库存 gate 预扣
   - 命中幂等 -> 直接返回已有任务/结果
   - 限领/售罄 -> 直接拒绝，不落任务
③ 同一 DB 事务插入 coupon_issue_task（WAL）
   - 唯一键 app_id + user_id + request_id 防重
   - 失败 -> 补偿 Redis 预扣，返回可重试错误
④ 返回 202 + requestId（status=ACCEPTED）
⑤ worker 异步领取任务，执行 C1 条件更新 + 落券/事件/outbox/过期任务
⑥ 客户端用 GET .../{requestId} 查询 ACCEPTED/PROCESSING/SUCCESS/REJECTED
```

关键点：

- **`202` 不是发券成功**，只是“任务已持久化，等待 worker 处理”；
- Redis 预扣与任务插入不是跨系统原子事务。任务插入失败时，用同一个 `requestId` 做 Redis 补偿；任务插入成功时，Redis 丢失不影响任务执行；
- 任务表是异步链路的幂等锚点，worker 失败可以重试，不依赖 MQ 消息不丢。

---

## 4. 数据模型

### 4.1 `coupon_issue_task`

```sql
CREATE TABLE coupon_issue_task (
  id             BIGINT UNSIGNED AUTO_INCREMENT,
  app_id         VARCHAR(64)  NOT NULL,
  user_id        BIGINT       NOT NULL,
  template_id    BIGINT       NOT NULL,
  request_id     VARCHAR(64)  NOT NULL,
  stat_day       DATE         NOT NULL COMMENT '受理日；worker 的日限归属日，不能用执行日的 NOW() 重取',
  seq            INT          NOT NULL COMMENT 'Redis 预分配的发放序号；DB uk_user_seq 终审',
  source         VARCHAR(32)  DEFAULT NULL,
  status         TINYINT      NOT NULL DEFAULT 0
                 COMMENT '0=PENDING 1=PROCESSING 2=SUCCESS 3=REJECTED 4=DEAD',
  retry_count    INT          NOT NULL DEFAULT 0,
  next_scan_time DATETIME(3)  NOT NULL,
  lease_owner    VARCHAR(64)  DEFAULT NULL,
  lease_until    DATETIME(3)  DEFAULT NULL,
  coupon_id      BIGINT       DEFAULT NULL,
  error_code     INT          DEFAULT NULL,
  error_msg      VARCHAR(255) DEFAULT NULL,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_request (app_id, user_id, request_id),
  KEY idx_status_scan (status, next_scan_time),
  KEY idx_tpl_status (template_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '异步发放任务（WAL；不是库存账本）';
```

- `uk_app_user_request`：同一租户/用户/requestId 只有一个任务；跨租户同 requestId 不互相撞。
- `seq` 来自 Redis 预分配；DB `user_coupon.uk_user_seq` 仍是限领终审。
- `status/lease` 支持 worker 崩溃后的租约回收；`idx_status_scan` 服务 `SELECT ... FOR UPDATE SKIP LOCKED`。
- `coupon_id` 成功后回填；`error_code` 记录 `3001/3002/3003/5001` 等。

### 4.2 硬日限计数表

```sql
CREATE TABLE coupon_user_daily_counter (
  user_id     BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  stat_day    DATE   NOT NULL,
  used        BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, template_id, stat_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '每人每日已发计数（硬日限）';

CREATE TABLE coupon_daily_counter (
  template_id BIGINT NOT NULL,
  stat_day    DATE   NOT NULL,
  used        BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (template_id, stat_day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '模板每日已发计数（硬日限）';
```

硬日限的判定在 worker 的同一个 DB 事务里：

```sql
UPDATE coupon_daily_counter
SET used = used + 1
WHERE template_id = ? AND stat_day = ? AND used < ?;   -- 0 行 = 达限
```

实现列名用 `stat_day`，避免与 MySQL 的 `DAY()` 关键字混淆；语义就是按本地日期统计。每人日限同理，只是条件多 `user_id`。影响 0 行就把任务标记 `REJECTED`，不写券、不扣库存成功。

**计数表为什么不加 `app_id`**：`template_id` 全局唯一且只属于一个 `app`；同一 `user_id` 在不同 app 下使用不同模板，`(user_id, template_id, stat_day)` 不会跨租户碰撞。任务表需要 `app_id`，是因为 `request_id` 只在租户内唯一，且任务要按 app 查询；计数表不需要重复这个维度。若未来允许模板跨租户共享/迁移，再引入 `app_id` 并迁移计数键。

模板日限是单行热点。先按单行压测；如果单行超过约 300–615 TPS（公开基准，硬件相关），改成**日额度分段**：

```text
coupon_daily_segment(template_id, day, next_start, limit)
  - DB 只按段分配（如一次 1000）
  - 实例/Redis 消费段内额度
  - DB next_start 只按段更新，热行被打散
  - 极端情况下可能少发一个段的余量，不会超发
```

### 4.3 Redis 键

| 键 | 作用 | 丢失时 |
|---|---|---|
| `c:issue:gate:{tpl}` | 模板库存预扣 gate（一个模板一个计数，不再分 16 桶/世代） | 从 C1 分片/任务表重建；DB 仍是最终裁决 |
| `c:ul:{tpl}:{user}` | 每人总限计数/seq 快路径 | DB `MAX(seq)` + `uk_user_seq` 兜底 |
| `c:uld:{tpl}:{user}:{day}` | 每人日限快路径 | DB 日计数行兜底 |
| `c:dly:{tpl}:{day}` | 模板日限快路径 | DB 日计数行兜底 |
| `c:idem:{app}:{requestId}` | 幂等快路径/PENDING | DB 任务唯一键 + `uk_idem` 兜底 |
| `c:so:{tpl}` | 售罄提示（短 TTL） | 不影响正确性，最多多打 DB |

Redis 只做准入/缓存，不是库存账本；任何 Redis 键丢失都不能让最终发券数超过库存或日限。

---

## 5. Worker

```text
循环：
① SELECT ... FROM coupon_issue_task
   WHERE status IN (0,1) AND next_scan_time <= NOW()
     AND (lease_until IS NULL OR lease_until < NOW())
   ORDER BY id LIMIT N
   FOR UPDATE SKIP LOCKED;
② 标记 PROCESSING + lease_owner/lease_until
③ 对每条任务（或按分片分组）执行：
   - C1：UPDATE coupon_stock_shard ... WHERE used<quota
   - 硬日限：UPDATE coupon_*_daily_counter ... WHERE used<limit
   - INSERT user_coupon（uk_idem/uk_user_seq 终审）
   - INSERT coupon_event / coupon_outbox / coupon_expire_task
   - 成功：status=SUCCESS, coupon_id=...
   - 业务拒绝：status=REJECTED, error_code=...
   - 瞬时失败：retry_count+1, next_scan_time 退避；超过上限 status=DEAD
④ 批量提交；lease 到期由其他 worker 回收
```

实现选择：

- 第一阶段：**每条任务一个事务**，先保证语义正确；任务领取批量、落库逐条。
- 第二阶段：**按 user_id 分片分组批量插入**（`user_coupon` 64 表、`coupon_expire_task` 64 表），`coupon_event/coupon_outbox` 单表批量插入；MySQL JDBC 开启 `rewriteBatchedStatements=true`。
- 库存扣减先逐任务执行 `used<quota`；不要为了批量把多条任务合并成一条 `used+n` 后无法区分哪条成功/失败。后续可以按分片预检 + 逐条确认。
- worker 用 XXL-JOB + `@Scheduled` 双通道；任务表行锁 + `SKIP LOCKED` 做多实例竞争，不依赖 Redis 锁保证正确性。
- 事务内固定锁顺序：**每人日计数 → 模板日计数 → 库存分片 → user_coupon 插入**。所有 worker 按同一顺序拿锁，避免“一个锁用户日计数等模板、另一个反过来”的死锁；没有日限时对应步骤跳过。

### 5.1 批量插入注意

- `user_coupon` 按 `user_id % 64` 分表，批量前先按分片分组，不能把不同分片塞进一条 INSERT。
- `coupon_expire_task` 同样按 user_id 分表；`coupon_event`、`coupon_outbox`、`coupon_issue_task` 是单表。
- 批量大小按 50–200 行压测；太大增加事务时长和锁范围，太小没有摊薄效果。
- 批量插入失败时按 `uk_idem/uk_user_seq` 逐条重放或标记；不能整批静默放弃。

---

## 6. 硬日限与“受理后拒绝”

硬日限的准确语义是：

> **最终成功发券数不超过 `dailyLimit`。**

不是“`202` 一定成功”。异步链路里可能发生：

1. Redis 预检查通过、任务插入成功，返回 `202`；
2. worker 执行时 DB 日计数 `used < limit` 返回 0 行；
3. 任务标记 `REJECTED`，返回 `3003`，不发券。

这是异步受理的正常语义。要减少“受理后拒绝”，可以在受理时同步预留 DB 日限名额（任务插入事务里同时 `UPDATE daily_counter`），代价是同步路径多一次 DB 写/行锁。默认先用 Redis 预检查 + worker DB 硬计数，压测后决定是否把日限预留下沉到受理事务。

**不会发生**的是：worker 成功数超过 `dailyLimit`。因为最终判定在 DB 条件更新；Redis 计数只影响受理率，不影响最终正确性。

### 6.1 跨天归属：以“受理日”为准

异步链路有一个同步路径没有的问题：请求在 23:59:59 受理，worker 可能在 00:00:01 才执行。如果 worker 用执行时的 `LocalDate.now()` 取日期，就会把上一受理的请求算到新一天：

```text
23:59:59 受理：Redis 检查的是 10-22 的日限
00:00:01 worker：如果重新取日期，会检查 10-23 的日限
```

结果可能是：10-22 已经发满，但 worker 用 10-23 的空额度又发了一张；或者反过来，10-23 的请求被旧任务挤占。

因此：

1. **受理时确定 `stat_day`**：`POST /receive-async` 用一次 `LocalDate.now()` 算出日期，同时传给 Redis gate 和任务表；
2. **任务表持久化 `stat_day`**：`coupon_issue_task.stat_day` 是这次请求的日限归属日；
3. **worker 只读任务上的 `stat_day`**：不重新取 `LocalDate.now()`，重试/跨天执行都归属原受理日；
4. **日计数表按这个日期更新**：`coupon_daily_counter(template_id, stat_day)` 和 `coupon_user_daily_counter(user_id, template_id, stat_day)`。

这样，23:59:59 受理、00:00:01 执行的任务仍然消耗 10-22 的额度；10-23 的新请求使用 10-23 的额度。**硬日限的“日”是受理日，不是 worker 执行日。**

代价是：如果 10-22 的额度已满，23:59:59 受理的任务可能在 00:00:01 被 `REJECTED`。这是 `202` 语义允许的“受理后拒绝”，产品上要接受；不能为了体验把日期改成执行日，否则会突破原受理日的上限。

---

## 7. 失败补偿

| 失败点 | 处理 |
|---|---|
| Redis 预扣成功、任务插入失败 | 用同一 requestId 补偿 Redis gate/计数；返回可重试错误，不留下无任务预扣 |
| 任务插入成功、返回 202 后进程崩溃 | 任务表已持久化；worker 按 `status/next_scan_time` 重试 |
| worker 执行中崩溃 | lease 到期回收；任务重新领取；`uk_idem` 保证不重复发券 |
| 库存分片满 | 任务 `REJECTED(3001)`；释放 Redis gate/日限预占（若有） |
| 日限达上限 | 任务 `REJECTED(3003)`；不写券；释放 Redis 计数 |
| 用户限领达上限 | 任务 `REJECTED(3002)`；`uk_user_seq` 终审 |
| 事件/发件箱投递失败 | 与券同事务写 outbox；投递器退避/死信 |
| 过期任务写失败 | 与券同事务；失败整体回滚/重试 |
| Redis 整体丢失 | 任务表仍在；worker 继续执行；Redis gate/计数从 DB/任务重建，不影响最终账本 |
| 对账差异 | `Σquota=total`、`Σused=COUNT(user_coupon)`、日计数 vs 事件数；只告警 + 人工修复 |

Redis 预扣的补偿不能盲目 `DECR`：必须用 `requestId` 做幂等标记，只有确认这次预扣存在且未被任务消费时才回退；回退失败不阻断主流程，交给 gate 重建/对账。重建的权威来源是 C1 分片行、任务表和 `user_coupon`，不是另一个 Redis 计数。

> ✅ **2026-10-25 Phase 1.1 已实现**：`V31` 给任务加 `gate_release_state/gate_next_retry_time/gate_max_per_user/gate_per_user_daily/gate_daily_limit`；`RedisIssueGate.releaseTerminal` 按 idem 状态幂等释放，`CouponIssueGateCompensationJob`（30s + XXL + Redis 锁）扫终态任务。
> - `SUCCESS → markIssued`，永不释放；
> - `STOCK_OUT(3001)` 保留库存 gate，只释放日限计数；
> - 其他 `REJECTED` 回加库存 gate + 释放计数；
> - `DEAD` 先按幂等键探 `user_coupon`：有券修复成 SUCCESS，无券才释放；
> - 重复受理（Redis idem 丢失）用 `AcceptResult.created=false` 补偿；`rebuildGate` 扣除在途任务；`STOCK_GATE_EMPTY` 允许限频 DB 重建一次，避免永久假售罄。
>
> `IssueGateCompensationIntegrationTest`/`AsyncIssueWorkerIntegrationTest` 已覆盖。Phase 2 的 TCC 预约/guard 仍会在此基础上判断 `reserved`，不要在有多库预约时盲目回加 gate。
>
> **2026-10-25 Phase 1.5（Redis 错误专项）**：保留 gate 的每人/日限预扣，但计数可由 DB+在途任务重建：
> - `seq` 独立为 `c:issue:seq:{tpl}:{user}`，不再用 `ul+1`，修复 `ul` 不会让序号倒退；
> - `GATE_MISSING` 重建 gate + `ul/uld/dly`；限领拒绝先查 DB 目标（已发 + 在途），目标仍低于 limit 才修计数并重试；
> - `pcall/tonumber` 区分 gate 非数字/类型错、计数非数字/类型错/缺失，分别修复后重试；
> - Redis 连接/超时默认 `reject`（5001），`coupon.issue.gate.redis-down-policy=db-fallback` 时只写 WAL 返回 202，worker DB 终审，任务 source 标记后终态失效 gate；
> - `c:so` 保留 10s 有界假售罄；测试覆盖 gate 非数字/错类型、计数偏高/缺失/错类型、gate 偏高不超发、db-fallback。
>
> 结论仍是：Redis 错只会造成假拒绝/多打 DB/异步入口暂时不可用，不会超发；DB `used<quota` 和 V27 计数是终审。

---

## 8. API 契约

### 8.1 受理

```http
POST /api/coupons/receive-async
Content-Type: application/json

{"requestId":"req-20261022-001","userId":1001,"templateId":2001}
```

成功受理：

```json
{
  "code": 0,
  "data": {
    "requestId": "req-20261022-001",
    "taskId": 123456,
    "status": "ACCEPTED"
  }
}
```

HTTP 状态：`202 Accepted`。`202` 只表示任务已持久化，不表示已发券。

### 8.2 查询

```http
GET /api/coupons/receive-async/{requestId}
```

```json
{
  "code": 0,
  "data": {
    "requestId": "req-20261022-001",
    "status": "SUCCESS",
    "couponId": 987654,
    "errorCode": null
  }
}
```

状态：`ACCEPTED / PROCESSING / SUCCESS / REJECTED`。开放面走签名 + `X-Client-Token`，内部面走管理鉴权之外的用户态；`requestId` 查询必须校验归属。

保留同步 C1 端点作为灰度/低延迟路径；异步端点按租户/模板开关逐步启用，不在同一路径上混用两种返回语义。

---

## 9. 性能模型

同步受理路径：

```text
模板本地缓存：0 DB
Redis Lua：1 次网络往返
coupon_issue_task INSERT：1 条 DB 写
返回 202
```

worker 每任务仍然写 5 行左右（库存、券、事件、outbox、过期任务），异步/批量不会把行数变少；它减少的是：

- 请求线程等待；
- DB 瞬时峰值；
- SQL 语句数和网络往返（批量）；
- 售罄/限领洪峰对 DB 的无效冲击（Redis gate 挡住）。

1k–2k 目标先按“每任务一事务 + 任务批量领取”压测；如果 DB 写吞吐成为瓶颈，再做按分片分组的多行 INSERT。5k 仍受单 MySQL 写吞吐约束，需要分库/分片或日额度分段，不能只靠“异步”两个字。

---

## 10. 灰度与迁移

1. 新增 V27：`coupon_issue_task`、`coupon_user_daily_counter`、`coupon_daily_counter`；旧表不动。**已实施**；同日 V28 完成 L73（64 张 `user_coupon_*` 的 `uk_idem` 补 `app_id`），所有 `findByIdempotentKey` 调用已带租户。**V27 只建表、不回填当天已有计数**；本项目旧数据已废弃、新库从 0 开始，因此不需要回填；只有保留旧券数据的环境才需要先按当天 `coupon_event`/`user_coupon` 回填两张计数表（只增不减、幂等）。
2. 新开关 `coupon.issue.async-enabled=false`；先内部面/单租户灰度。**已实施**：`ReceiveAsyncAppService` 校验开关 + `async-template-ids` allowlist（支持 `*` 仅测试/内部灰度），allowlist 为空时拒绝启动异步入口。
3. 保留同步 C1；异步只处理开关命中的模板/租户。**已实施**：`POST /receive-async` 返回 202，`GET /receive-async/{requestId}` 按 `appId+userId+requestId` 查询；同步 `/receive` 不变。
4. 对账：任务数、成功数、拒绝数、`SUM(used)`、`COUNT(user_coupon)`、日计数 vs 事件数。**任务数/状态查询已可用；成功/拒绝要等 worker。**
5. 稳定后再把异步设为默认；同步路径保留为降级/回退。**未开始。**

### 10.1 实施进展（2026-10-22）

- `RedisIssueGate`：Lua 原子完成幂等、每人总限/日限、模板日限、库存 gate 预扣；所有 key 带 `{templateId}` hash tag；gate 丢失从 `Σquota-Σused` 重建；任务插入成功后把 idem 从 `PENDING` 改 `ACCEPTED`，失败补偿只回退 `PENDING`。
- `CouponIssueTaskService`：同步插入 WAL；唯一键冲突返回已有任务；`stat_day` 在受理时写入。
- `ReceiveAsyncAppService`：模板/归属/可领性/预算校验、C1 限定、202 受理、状态查询；状态查询强制 `appId+userId+requestId`。
- `ReceiveController`：`POST /api/coupons/receive-async`（202）与 `GET /api/coupons/receive-async/{requestId}`；HTTP 202 由 `ReceiveControllerAsyncTest` 验证。
- `CouponIssueTaskClaimService`：`FOR UPDATE SKIP LOCKED` 领取 + lease；`CouponIssueTaskTxService` 单任务事务内完成“每人日计数 → 模板日计数 → C1 库存 → 券/事件/outbox/过期任务 → 任务 SUCCESS”；`CouponIssueTaskStatusService` 在事务外落 REJECTED/RETRY/DEAD；`CouponIssueTaskWorker` 双通道（XXL-JOB + @Scheduled），默认关闭。
- `CouponIssueTaskCleanupJob`：只删 SUCCESS/REJECTED/DEAD 且超过保留期的任务；PENDING/PROCESSING 永不删；计数表按 `stat_day` 保留 90 天。
- 测试：`AsyncIssueSchemaIntegrationTest` 4/4、`AsyncIssueAcceptanceIntegrationTest` 6/6、`ReceiveControllerAsyncTest` 1/1、`AsyncIssueWorkerIntegrationTest` 4/4（成功落券/模板日限/每人日限/清理）；`ExpiryIntegrationTest` 9/9、`LockTimeoutIntegrationTest` 11/11、`SchedulerLockIntegrationTest` 3/3（P0-3/P0-4 回归）。2026-10-23 补充：`AsyncIssueWorkerIntegrationTest` 扩到 9/9（混模板/批量售罄回退/租约回收/崩溃窗口幂等/裸写真实物理表）；新增 `CouponIssueTaskBatchTxServiceTest` 10/10 与 `AsyncIssueWorkerRawBulkDisabledIntegrationTest` 1/1，覆盖 `raw-bulk-enabled=false` 回退与每人日限守卫。
- 当时未做：1k/2k 压测、P0-5（C1 同步路径仍用 COUNT）、P0-6（物理 SQL 指标断言）。本项目新库从 0 开始，不需要当天计数回填。

### 10.2 1k/2k 压测结果与实施修订（2026-10-23）

完整报告：[2026-10-23 异步发放 1k/2k 压测报告](../evidence/2026-10-23-异步发放1k2k压测报告.md)。

**受理面**（预连接 + 栅栏同时发，MySQL 默认持久化）：

| 场景 | 结果 | 吞吐 | p99 |
|---|---|---:|---:|
| 1000 并发 | 1000×202，业务码 0 | 711–1382 req/s | 744–1464 ms |
| 2000 并发 | 2000×202，业务码 0 | 1050–2122 req/s | 737–1777 ms |

**正确性**：4k 请求打 3k 库存，3000 受理 + 1000 售罄，最终 success=3000、`Σused=3000`、券行=3000、重复任务=0；500 个任务打 `dailyLimit=300`，恰好 300 success / 200 rejected(3003)，计数行=300；同一用户 3 任务打 `perUserDailyLimit=1`，1 success / 2 rejected(3002)。

**worker 吞吐演进**：单线程逐任务约 32 task/s → 8 线程约 111 → 按库存分片分组约 364–500 → **裸 JDBC 物理表批量写约 1.29k–1.6k task/s**。最终态隔离 4k 积压约 3.1 s 清空（两块 2000 任务分别约 1.9 s / 1.3 s）。已实施的加速：

1. `CouponIssueTaskProcessor`：同模板、无日限、批内用户不重复时走批量；批量失败退回按库存分片拆组，再失败逐条走 ShardingSphere。先按模板分组再切块，修正跨模板混批串券。
2. **`CouponIssueTaskRawBulkWriter`**：worker 已算好 `user_id % 64` 与库存分片，批量路径用裸 JDBC 直写物理表（独立懒加载小池）：库存按分片一条条件 UPDATE、券/过期任务按用户分表多行 INSERT、event/outbox 各一条多行 INSERT、任务成功一条 CASE 更新。整批一个本地事务，失败回滚并回退单任务路径。
3. **不再逐条 `MAX(seq)`**：直接使用受理 WAL 里的 `task.seq`；seq 失效由 `uk_user_seq` 兜底。
4. `CouponOutboxMapper.markDispatchedBatch`：投递器 100 次 autocommit 变 1 次；`SnowflakeIdGenerator` 回拨日志按秒限流。
5. **崩溃窗口幂等修复**：券已提交、任务状态未提交时重跑，先按 `(user_id, appId, requestId)` 查已有券，命中同模板补 SUCCESS，不再误判 3002。

**代价/边界**：裸写只适配当前“1 物理库 × 64 表”，拆 16 物理库前必须按库路由或设置 `coupon.issue.worker.raw-bulk-enabled=false`；日限/重复用户仍走单任务路径；批量失败回退 ShardingSphere，不是唯一写通道。

**回退开关回归（2026-10-23 补）**：`raw-bulk-enabled=false` 之前只有“批量失败回退”的覆盖，没有证明配置关闭后完全不调用裸写。补充两层测试：

- `CouponIssueTaskBatchTxServiceTest` 7 例（Mockito）：关闭时 `processAll`/`process` 逐条调用 `CouponIssueTaskTxService.process`、裸写零调用；打开且满足条件时整批一次 `write`；单分片组在模板日限/批内用户重复时逐条回退；`processAll` 收到不满足资格的批次时抛守卫异常。
- `AsyncIssueWorkerRawBulkDisabledIntegrationTest` 1 例（真实 MySQL/Redis）：`@SpringBootTest` 属性关闭开关，worker 处理 5 条任务，断言全部 SUCCESS、`Σused=5`、券/事件/outbox 各 5、每用户过期任务 1；用 `@SpyBean` 断言 `CouponIssueTaskRawBulkWriter.write` 从未调用。
- 同时修正日志：开关关闭时不再打印 `whole-batch committed`，改为 `batch completed`，协调器额外记录逐条事务路径。

全量 `mvn clean test` **530 例：529 绿 + 1 例门控跳过 + 0 失败/0 错误**，BUILD SUCCESS，8:00；首次定向集成用例耗时 159.7 s，成本主要是第二个 Spring 上下文与容器启动。

**对抗审查补强（2026-10-23）**：审查发现“测试通过但没证明快路径被执行”的风险，并已修复：

- `AsyncIssueWorkerIntegrationTest` 增加 `rawBulkWriterWritesPhysicalTables`：直接调用裸写器，用独立裸库连接核对 `user_coupon_<uid%64>`、`coupon_expire_task_<uid%64>`、`coupon_event`、`coupon_outbox` 和任务 SUCCESS；该类固定 `raw-bulk-enabled=true`，避免环境变量把批量测试整体切到逐条路径。
- 两个 worker 测试的清理改为保留 `coupon_stock_shard`：模板、券、库存行必须一起自洽，否则后续 `C1StockIntegrationTest.auditAll()` 会扫描到 `Σquota=0` 的已发布模板。
- `rawBulkDataSource` 改用无参 `HikariDataSource` + setter：构造时不启动池，第一次 `getConnection()` 才启动；避免开关关闭或 worker 禁用时仍建立 `raw-bulk` 连接池。关闭开关用例断言池未运行。
- 禁用用例增加 `@SpyBean CouponIssueTaskBatchTxService`，断言 `processAll` 被调用；只断言裸写零调用不足以证明进入了读开关的分支。
- `bulkEligible` 的 `Long` 引用比较改为 `.equals`；补同 id 不同实例的单元用例。
- 补每人日限的整批守卫和单分片组回退用例。
- 启动时 INFO 打印 `issue task raw bulk enabled=...`；分片组日志改为 `shard group finished`，不再把逐条回退说成批量提交。

**P0-5 同步/异步日限归一（2026-10-23）**：原先 `CouponIssueTaskTxService.process` 自己递增日计数，同步 `C1ReceiveAppService.checkLimits` 又用三次 COUNT；两条路径各维护一份逻辑。现在日计数收进 `CouponIssueTxService.issueC1`：同步传请求开始日，异步传 `task.stat_day`，锁顺序统一为“用户日计数 → 模板日计数 → 库存分片”，计数与库存/券同事务回滚。同步路径每人总限改用 `MAX(seq)+1`，不再 COUNT。审查后又补了三处：计数行 `ensureRow` 改为 `ON DUPLICATE KEY UPDATE`，避免 `INSERT IGNORE` 的 S→X 锁升级死锁；`MAX+1` 超限时走冷路径查已占用 seq 找空洞，避免异步拒绝任务留下的空洞永久误判 3002；日限拒绝/库存满前回查同 requestId 的已发券，避免把已成功的请求改判 3002/3003/3001。测试：`C1StockIntegrationTest` 14/14、`C1ReceiveAppServiceTest` 2/2、`CouponEventTest` 1/1、worker 14/14；全量 544 例。

**待做**：5k 未压；裸写物理库适配；P0-6 物理 SQL 路由断言未做。多实例压测必须让各实例 `coupon.id.worker-id` 不同且 worker 同时启动。

---

## 11. 风险与待定

| 风险/待定 | 处理 |
|---|---|
| `202` 受理后拒绝的体验 | Redis 预检查降低概率；产品明确“受理≠成功”；需要时把日限预留下沉到受理事务 |
| 模板日限单行热点 | 先单行压测；超过约 300–615 TPS 改日额度分段 |
| Redis 预扣与任务插入边界 | requestId 幂等 + 失败补偿；任务表是 WAL |
| 任务表膨胀 | 成功任务按天归档/删除；`status+next_scan_time` 索引；监控 PENDING 堆积 |
| worker 批量插入跨分片 | 按 user_id 分组；不能一条 INSERT 跨 64 表 |
| 过期任务每券一行 | 先批量插入；若成为写瓶颈，再评估按 `user_coupon(status,valid_end)` 扫描或时间桶 |
| 多实例任务领取 | `FOR UPDATE SKIP LOCKED` + lease；不依赖 Redis 锁正确性 |
| JVM 时钟回拨/多实例时钟偏差 | `next_scan_time` 由应用 `LocalDateTime.now()` 写入；回拨期间任务可能短暂不可领取。生产需保证 NTP 平滑/多实例时钟同步，并监控 PENDING 堆积；测试用例用固定过去时间避免环境时钟回拨误伤（2026-10-23 全量实测 WSL 回拨约 18 秒） |
| 异步 gate 预扣与 DB 日计数分叉 | **Phase 1.1/1.5 已缓解**：终态补偿按错误码幂等释放；重复受理用 `AcceptResult.created` 补偿；`rebuildGate` 扣在途任务；`STOCK_GATE_EMPTY` 限频 DB 重建；Phase 1.5 把 seq 与 `ul` 解耦，`GATE_MISSING`/限领拒绝/畸形计数都会按 DB+在途任务重建，Redis down 可选 `db-fallback`。剩余风险在 Phase 2 多库预约：有 `reserved` 的预约未决时不能回加 gate；`STUCK` 需告警/人工；`c:so` 仍是有界 10s 假售罄。 |
| 日计数表清理预算 | P0-5 后同步领取也写两张计数表；清理任务已改为不依赖 worker 开关注册，但默认每次 100×1000 行/表的预算在高流量下可能不足。高容量部署需提高预算或按 `stat_day` 分区 |
| 裸 JDBC 批量写绕过 ShardingSphere | 只适配当前 1 物理库 × 64 表；拆 16 库前按库路由或设置 `coupon.issue.worker.raw-bulk-enabled=false`；失败回退单任务 ShardingSphere 路径；关闭分支已有 10 个单测 + 1 个真实库集成回归，裸写器另有真实物理表正向用例；`raw-bulk` 池改为首次连接才启动（2026-10-23） |
| 日限硬约束与性能 | DB 日计数行/分段，不每请求 COUNT；COUNT 只做对账 |
| 跨租户同 requestId | 任务表唯一键是 `(app_id,user_id,request_id)`；V28 已把 64 张 `user_coupon_*` 的 `uk_idem` 改为 `(user_id,app_id,idempotent_key)`，所有查询已带 appId。上线顺序仍必须是“先 V28 + 新应用，再开异步”，否则旧代码按旧键查会漏掉跨租户行 |

---

## 12. 完整调研链接

> 证据等级：A=官方文档/官方基准；B=企业官方工程博客；C=企业自述/第三方工程文章；D=面经/二手转述/推断。访问日期：2026-10-22。

### 12.1 官方文档/官方基准

- [A] MySQL 8.4 Locking Reads（`FOR UPDATE SKIP LOCKED`）：https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html
- [A] MySQL 8.4 `INSERT ... ON DUPLICATE KEY UPDATE`：https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html
- [A] MySQL 8.4 `ROW_COUNT()`：https://dev.mysql.com/doc/refman/8.4/en/information-functions.html#function_row-count
- [A] MySQL 8.4 InnoDB 锁：https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html
- [A] Redis INCR：https://redis.io/docs/latest/commands/incr/
- [A] Redis Lua 脚本：https://redis.io/docs/latest/develop/programmability/eval-intro/
- [A] Redis 持久化：https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
- [A] Redis 主从复制：https://redis.io/docs/latest/operate/oss_and_stack/management/replication/

### 12.2 企业工程/公开分享

- [B] vivo 全球商城库存系统架构与实践（Redis 预扣 + 同步任务表 WAL + 异步同步库存）：https://www.cnblogs.com/vivotech/p/17202501.html
- [C] 字节支付发券实践（企业自述：异步发券 + Redis 分片 + 双层本地队列）：https://blog.csdn.net/bytedancetech/article/details/125419247
- [C] 美团民宿大促面经（Redis+Lua+MQ，8k QPS；**D：未找到美团官方原文，数字仅转述，不作为选型依据**）：https://www.nowcoder.com/discuss/759716167412879360
- [A] 阿里云 PolarDB-X 热点行更新：https://help.aliyun.com/zh/polardb/polardb-for-xscale/update-hot-rows
- [B] 腾讯云数据库库存分桶实践：https://developer.cloud.tencent.com/article/2623908
- [B] Percona MySQL 最大写吞吐：https://www.percona.com/blog/maximal-write-througput-in-mysql/

### 12.3 项目内资料

- `docs/adr/ADR-0018-stock-model-c1-fixed-shards.md`（C1 DB 分片账本、失败补偿、完整库存调研）
- `docs/research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md`（V6 阶段 1 发放域与预算）
- `docs/STATUS.md`
- `src/main/java/com/newcoupon/application/service/C1ReceiveAppService.java`
- `src/main/java/com/newcoupon/application/service/CouponIssueTxService.java`
- `src/main/java/com/newcoupon/application/service/ReceiveAsyncAppService.java`
- `src/main/java/com/newcoupon/application/service/CouponIssueTaskService.java`
- `src/main/java/com/newcoupon/infrastructure/redis/RedisIssueGate.java`
- `src/main/resources/db/migration/V25__c1_stock_shards.sql`
- `src/main/resources/db/migration/V27__async_issue_task_and_daily_counter.sql`

### 12.4 证据口径

- vivo 方案二是公开文章中的描述；具体线上实现细节、任务表字段、worker 批量大小未公开，本文是按公开原则做的适配设计。
- 300–615 TPS 是公开基准/厂商口径，硬件相关；不是本机实测。
- 1k–2k 已在 2026-10-23 用本机 WSL2 + MySQL 8/Redis 7 压测校准：受理面 1k 观测 711–1382 req/s、2k 观测 1050–2122 req/s（全部 202）；裸 JDBC 批量写 worker 单实例约 1.29k–1.6k task/s。5k 未压，不能把 1k/2k 结果外推到 5k；裸写仅适配当前单物理库布局。

---

## 13. 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-22 | 接受 Vivo 方案 2 适配：Redis 预扣 + 同步任务表 WAL + 异步 worker；确认 202 + requestId 查询；确认硬日限；补限领缓存、批量插入、失败补偿与完整链接 |
| v1.1 | 2026-10-22 | 实施 V27（任务/计数表）与 V28（L73 幂等键补 app_id）；`findByIdempotentKey` 全量带 appId；新增 `AsyncIssueSchemaIntegrationTest` 4/4；受理/worker 仍待实施 |
| v1.2 | 2026-10-22 | 任务表补 `stat_day`：日限按受理日归属，worker 不重新取执行日；明确计数表不加 `app_id` 的理由（`template_id` 全局唯一且单租户） |
| v1.3 | 2026-10-22 | 实施 Redis 准入门、202 受理、状态查询与失败补偿；新增 `AsyncIssueAcceptanceIntegrationTest` 6/6、`ReceiveControllerAsyncTest` 1/1；worker/压测待做 |
| v1.4 | 2026-10-22 | 实施 worker（SKIP LOCKED + lease、单任务事务、C1 落库、硬日限、REJECTED/RETRY/DEAD）与清理任务；新增 `AsyncIssueWorkerIntegrationTest` 4/4；P0-3/P0-4 回归；回填/压测待做 |
| v1.5 | 2026-10-23 | C1-only 收口后旧链路删除；新库无需回填；全量 clean 513 例 0 失败；压测待做 |
| v1.6 | 2026-10-23 | 完成 1k/2k 压测：受理 1k/2k 全部 202，吞吐 1109–1382 / 1050–2122 req/s；4k→3k 库存不超发、硬日限 300/500、每人日限 1/3、requestId 幂等 100→1；worker 按库存分片分组并行 + outbox 批量标记 + Snowflake 日志限流；单实例持续 1k 未达 |
| v1.7 | 2026-10-23 | worker 用 task.seq 替代逐条 MAX(seq)、裸 JDBC 物理表批量写、任务 CASE 批量回写；单实例约 1.29k–1.6k task/s；修复“券已提交、任务未成功”崩溃窗口的幂等校验；新增对应集成测试 |
| v1.8 | 2026-10-23 | 裸 JDBC 批量写增加 `coupon.issue.worker.raw-bulk-enabled` 开关（默认 true）；拆物理库时可置 false 回退 ShardingSphere |
| v1.9 | 2026-10-23 | 补 `raw-bulk-enabled=false` 回归：协调器 7 个单测 + 真实 MySQL/Redis 集成 1 例；修正开关关闭时误报 `whole-batch committed` 的日志；测试显式构造可领取时间，避免 WSL 时钟回拨误伤；登记 JVM 时钟回拨风险 |
| v1.10 | 2026-10-23 | 对抗审查补强：裸写真实物理表正向用例；清理保留库存行避免污染 C1 审计；raw-bulk 池改为首次连接才启动；关闭分支断言 `processAll` 被调用；补每人日限守卫单测；修复 `Long` 引用比较；启动打印生效开关 |
| v1.11 | 2026-10-23 | P0-5：日计数收进 `CouponIssueTxService.issueC1`，同步与异步 worker 共用；同步 C1 去掉三次热 COUNT（每人总限 `MAX(seq)`，每人/模板日限 V27 条件计数） |
| v1.12 | 2026-10-23 | P0-5 审查修复：计数行改 `ON DUPLICATE KEY UPDATE` 避免锁升级死锁；MAX+1 超限时冷路径找序号空洞；日限拒绝/库存满前回查幂等券；补同步/异步混合日限、跨天 stat_day、计数回滚、日限并发测试；清理任务不再依赖 worker 开关；跨租户发放事件编号补 appId；全量 544 例 |
| v1.13 | 2026-10-25 | Phase 1.1：V31 gate 终态补偿（releaseTerminal/补偿任务/DEAD 探券/重复受理补偿/在途重建/假售罄重建）；`AsyncIssueWorkerIntegrationTest` 20/20；ADR-0021 多库 TCC 方案立项；全量 569 例：568 绿 + 1 门控跳过 + 0 失败/0 错误 |
| v1.14 | 2026-10-25 | Phase 1.5 Redis 错误专项：seq 与 ul 解耦；gate/计数 pcall 畸形/缺失码；按 DB+在途任务重建；限领拒绝 DB 二次确认；Redis down `reject`/`db-fallback`；worker fallback 终态失效 gate；新增 `ReceiveAsyncRedisDownTest` 2/2、worker gate 故障 6 例；全量 577 例：576 绿 + 1 门控跳过 + 0 失败/0 错误，BUILD SUCCESS 7:53 |
| v1.15 | 2026-10-25 | 修复重试重设计（调研 AWS/Google SRE/Microsoft/Resilience4j/K8s/Redis 后）：请求路径去掉 3 轮循环，改为单飞许可 + 一次修复 + 一次重试 + 2 秒冷却；新增 V36 `coupon_issue_gate_repair` 持久队列与 `CouponIssueGateRepairJob`（SKIP LOCKED + lease、指数退避、死信+漂移日志）；Redis 键 TTL 加抖动；新增 `RedisInfoMetrics`（内存/淘汰/命中/连接/复制）；测试新增单飞单测 5 例、修复队列 4 例、30 并发修复风暴 1 例；全量 587 例：586 绿 + 1 门控跳过 + 0 失败/0 错误，BUILD SUCCESS 7:45 |
