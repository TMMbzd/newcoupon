# ADR-0018：库存模型 C1——DB 固定分片条件更新，Redis 退出库存权威

- **状态**：已接受并完成运行时收口（2026-10-23）；C1 是唯一库存模型，旧 Redis 桶/世代链路已删除；不再做 0C 存量迁移/0D 兼容退役
- **实施状态**：V25/V26 已落；旧 `StockBucketRouter`/`StockReconcileService`/`RedisStockGateway`/`legacy` 测试已删；V29 删除 `stock_bucket`；本地库按新模型重建
- **取代**：ADR-0006（Redis Lua 库存）、ADR-0007（在途对账）、ADR-0008（恒等式对账）、ADR-0009（业务状态守恒）中**库存运行时的实现选择**；旧 ADR 保留为历史，不删除
- **关联**：V6 阶段 0、STATUS、`docs/P2b-库存批次分片-技术方案-2026-09-17.md`、`docs/03-库存扣减与防超发.md`、`docs/research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md`

> **本文是设计决策，不是实现代码。** 所有 SQL 形态、表名、字段名为设计草案；实施前按项目纪律做 3 视角审查、先红后绿、迁移演练。本文的“业界数字”均标注口径，不是本机实测。

---

## 1. 背景：当前模型为什么复杂

当前库存不是“扣一个计数”，而是“三层账本 + 一套自愈系统”：

| 层 | 部件 | 复杂度 |
|---|---|---|
| DB | `coupon_template(total_count/allocated/issued_count)` + `stock_bucket(quota/used/status/generation/allocated_hi)` | 三列语义分叉；桶行/世代/段唯一键/四种求和口径 |
| Redis | 每（桶,世代）四态键 `c:stk/c:pend/c:dbi/c:void` + `c:act/c:alloc/c:gens/c:so/c:fz/c:opening` | 14 类键；四态语义；世代隔离；TTL 清扫 |
| 协议 | DEDUCT/CONFIRM/CANCEL/OPEN_BUCKET/ENSURE/REBUILD/CONSERVATION 7 个 Lua | 9 种返回码；五重校验；开桶单飞/差额恢复/封账/世代回退 |
| 对账 | 60s 守恒巡检、方向性镜像、E1/E2a/E2b/E3a/E3c、deep 异步修复、冻结/解冻、drift 表 | 四条等式、三套求和口径、两轮确认、白名单/熔断/shadow |

库存专用代码约 2,100 行（`RedisStockGateway` 676、`StockBucketRouter` 465、`StockReconcileService` 870），约 40~45 个结构性测试钉住当前形状。

**复杂度分成两条独立轴：**

- **A 轴 = 高并发/热行分散**：16 桶、路由亲和、开桶、批量重开、镜像注册表。为 5000 QPS 目标设计。
- **B 轴 = 防资损/一致性**：DB 行封顶、令牌单次使用、世代寻址、售罄钉零、E1/E2/E3、冻结/deep。为“Redis 是库存权威 + 桶会重开”配套。

**关键事实：**

1. 当前唯一实测是 WSL2 2000 并发/19.3s≈**104 QPS**（形状验证，不是容量）；16 桶的 312 TPS/行数学是为未验证的 5000 QPS 设的。
2. 16 桶并不提升 Redis 吞吐：所有库存键都带 `{tpl}` hash tag，落同一个 slot/节点/线程；桶数实际是 `min(16, ceil(total/10000))`，1 万库存只有 1 个桶。
3. 5k QPS 的真瓶颈不是库存行锁，而是每成功发一张券写 5 次 DB（`stock_bucket`、`user_coupon`、`event`、`outbox`、`expire_task`）；5k≈2.5 万条写语句/秒，单实例 MySQL 总写吞吐先到顶。桶化解决不了。
4. 历史缺陷大多来自桶/世代/开桶协议的边界（L37 重复建行、L38 封账/看门狗、L47 假售罄、L44 镜像偏大、L46 deep 护栏、L62 高负载偶发），不是“DB 条件更新”本身。

**结论**：A 轴大部分对现有证据是过度设计；B 轴不能整段删，但可以通过**消灭触发条件**（固定分片、永不重开、Redis 不再做库存权威）让世代/封账/差额恢复/allocated_hi/守恒/deep 一起消失。

---

## 2. 业界调研结论

> 证据分级：**A**=官方文档/官方基准；**B**=企业官方工程博客；**C**=产品规则/第三方工程文章；**D**=面经/二手转述/推断。完整链接见 §10。

| 平台/来源 | 公开做法 | 对我们的含义 |
|---|---|---|
| **阿里 PolarDB-X / RDS** | 单行热点更新用 Inventory Hint/组提交；公开基准普通 UPDATE 约 615 TPS，热点组提交约 23,262 TPS；应用层合并扣减有 1.4w→3.2w TPS 分享 | DB 内核能解决热点，但我们是普通 MySQL，不能直接套 23k；固定分片是应用层可行替代 |
| **腾讯云** | 公开工程文章：DB 固定分桶计数行（约 10 行），端到端约 12k→15k QPS，超卖率 0.7%→0 | 与 C1 同构：固定 N 行 + `used<quota` 条件更新；固定分片是成熟方案 |
| **美团** | 公开面经/分享：Redis + Lua 原子扣减、MQ 削峰；民宿大促约 8k QPS、Redis Cluster 6 主 6 从（**D：未找到官方原文，数字仅转述**） | 这是“Redis 预扣 + 异步落库”路线；高吞吐靠 Redis/MQ，不靠库存表分代 |
| **字节** | 企业自述：支付发券约 10 万 TPS，异步发券 + Redis 分片 + 双层本地队列 + 分布式限流（**C：企业自述，未独立验证**） | 高吞吐的关键是异步/削峰/分片，不是库存模型本身 |
| **vivo** | 公开工程博客：DB 乐观锁 → Redis + Lua → 热点分段；分段方案有分享但并非全部上线 | 演进路径是“先简单、再分段”，不是一开始就上最复杂模型 |
| **京东** | 公开分享：优惠券任务表按门店拆队列，500 万任务/10 分钟；这是批量发放任务，不是库存扣减 | 批量发放用“任务表 + 分片 worker”；库存账本另算 |
| **有赞/Shopify/Stripe** | 产品规则/官方文档：拍下减/付款减、10 分钟预占；draft order reservation；Idempotency-Key | 预占/幂等是业务语义；可与 C1 组合，不需要 Redis 库存权威 |
| **Redis / MySQL 官方** | Redis 单 key Lua 高吞吐，但 AOF everysec 最多丢 1 秒、主从切换有丢写窗口；MySQL 单行条件 UPDATE 是正确性原语，单行约 300–615 TPS（公开基准，硬件相关） | Redis 不能做唯一库存权威；DB 条件更新必须是最后防线 |

**调研结论：**

1. 业界方案分四类：DB 热点行/组提交（阿里）、DB 固定分片计数行（腾讯）、Redis+Lua+MQ 削峰（美团/字节）、预占/幂等语义（有赞/Shopify/Stripe）。
2. 我们的现状是单 MySQL + 单 Redis + 104 QPS 实测；最匹配第 2 类：**C1 固定分片 + DB 条件更新**。
3. 5k 的瓶颈是“每笔 5 写 + 实例总写吞吐”，不是库存行锁；5k 必须异步/批量落库或分库，不应靠增加库存分桶/世代解决。
4. 当前 16 桶+世代+开桶+守恒不是业界主流的最小形态；它解决的是“Redis 权威 + 桶重开”带来的第二本账问题。C1 让 Redis 退出库存权威，这些问题失去存在土壤。

---

## 3. 决策：C1

**库存唯一权威回到 DB：每模板固定 N 行分片计数，每笔领券一条 `used<quota` 条件更新，与 `user_coupon`/`event`/`outbox`/`expire_task` 同一本地事务。Redis 只做模板缓存、限流、幂等快路径、短 TTL 售罄提示，不再存库存。**

### 3.1 数据模型

新增：

```text
coupon_stock_shard (
  id            BIGINT UNSIGNED PRIMARY KEY,
  template_id   BIGINT NOT NULL,
  shard_no      INT NOT NULL,
  quota         BIGINT NOT NULL,          -- 该片总容量
  used          BIGINT NOT NULL DEFAULT 0,-- 该片已发
  status        TINYINT NOT NULL DEFAULT 0, -- 0 可发 / 1 停发（下线/冻结/审批回退）
  version       INT NOT NULL DEFAULT 0,
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tpl_shard (template_id, shard_no),
  KEY idx_tpl_status (template_id, status),
  CONSTRAINT ck_stock_shard_used CHECK (used <= quota)   -- MySQL 8.0.16+ 强制；旧版本用应用断言
) COMMENT='库存固定分片计数行（C1：DB 唯一权威，无世代/无开桶）';
```

`coupon_template` 增加：

```text
stock_model       TINYINT NOT NULL DEFAULT 0,  -- 0=legacy（旧桶/世代）；1=C1；2=迁移中（停发）
stock_shard_count INT     NOT NULL DEFAULT 16, -- C1 固定分片数；发布后不轻易改
stock_layout_version INT  NOT NULL DEFAULT 1   -- 布局版本；改 N 才需要新版本
```

保留但改变语义：

- `total_count`：审批总库存；默认只增；库存调整可减，但 `target >= SUM(used)`，绝不把已发券“减掉”。
- `allocated`：legacy 专用；C1 不再写，不再作为额度镜像。
- `issued_count`：legacy 快照/报表字段；C1 的已发数以 `SUM(coupon_stock_shard.used)` 为准，不再做热路径活计数。

`stock_bucket` 在迁移期保留（回滚/旧模板），C1 不再读写；稳定一个发布周期后归档/删除。

### 3.2 分片布局

- 默认 `N = 16`，可按模板配置 `stock_shard_count`；`N_eff = min(N, total_count)`（total 很小就少建几行，避免 0 配额行）。
- 发布时一次性建行：
  - `base = total_count / N_eff`，前 `total_count % N_eff` 行各 `+1`；
  - 断言 `Σquota = total_count`、`used = 0`、`status = 0`。
- N 一旦发布不轻易改；需要改 N 时走 `stock_layout_version` 迁移（冻结→抽干→新布局），不在热路径做。
- 领券路由：`shard = Math.floorMod(userId, N_eff)`；该片满则按 `(shard+1) % N_eff` 顺序探测，最多 N 次；全满=售罄。
- 不随机跳片：随机探测在售罄时放大 N 倍 DB 尝试；顺序探测 + 短 TTL 售罄缓存足够。

### 3.3 领券热路径

```text
① 读模板缓存/DB：审批通过、已发布、在领取期、stock_model=1
② 幂等/限领快路径（Redis/DB 唯一索引，保留现有语义）
③ 选片 shard
④ 本地事务：
    UPDATE coupon_stock_shard
       SET used = used + 1, version = version + 1
     WHERE template_id = ?
       AND shard_no = ?
       AND status = 0
       AND used < quota;
    -- 0 行：换下一片；N 片都 0 行：售罄（可写短 TTL c:so）
    INSERT user_coupon (...);       -- uk_idem / uk_user_seq 终审
    INSERT coupon_event (...);
    INSERT coupon_outbox (...);
    INSERT coupon_expire_task (...);
   COMMIT
⑤ 返回领券结果；事件投递走 outbox
```

要点：

- **不在热路径 join `coupon_template`**：模板状态通过 `stock_shard.status` 表达；下线/冻结/审批回退时把该模板所有分片 `status=1`（N 行，低频管理操作），避免模板行成为新的热点/锁点。
- `UPDATE` 放在事务内、但尽量靠后（先做幂等/限领校验，再更新库存，再插券/事件）；事务内禁止 RPC/远程调用。
- `affected=0` 必须区分“该片真满”“模板停发/缺行”；前者换片，后者返回明确错误。
- 幂等：`uk_idem(user_id, app_id, idempotent_key)` 终审；重复插入捕获 `DuplicateKeyException` 后回查并重放。限领：`uk_user_seq(user_id, template_id, seq)` 终审。
- 失败=事务回滚，不存在“库存扣了、券没落”的半截状态；不需要 Redis confirm/cancel。

### 3.3.1 限领/日限：热路径不要 COUNT

0A/0B 的实现为了先把正确性跑通，`C1ReceiveAppService.checkLimits` 每个请求做三次数据库计数（每人总限、每人日限、模板日限）。这不是目标性能形态：1k QPS 下光限领就多 3k 次查询，其中模板日限的 `COUNT(coupon_event ... occur_time>=dayStart)` 会随当天发放量增长，是 O(当天事件数) 的扫描，不能放在领券热路径。

目标做法：

| 限制 | 热路径做法 | 为什么不是 COUNT |
|---|---|---|
| 每人总限 `perUserLimit` | 只取 `MAX(seq)`（`uk_user_seq` 最左前缀），`MAX(seq)+1 > limit` 就拒绝；最终由 `uk_user_seq` 唯一索引兜底 | `MAX` 是一次索引定位；COUNT 会把该用户该模板的行数一遍。序号有空洞时按保守方向拒绝，不超发 |
| 每人日限 `perUserDailyLimit` | 新增 `coupon_user_daily_counter(user_id, template_id, day, used)`，事务内一条条件 upsert：`used < limit` 才加 1，影响 0 行=达限 | 行数按用户分散；一条主键/唯一键更新是 O(1)，且是硬限制 |
| 模板日限 `dailyLimit` | 新增 `coupon_daily_counter(template_id, day, used)`，事务内一条 `UPDATE ... SET used=used+1 WHERE used<limit`；`dailyLimit=0` 时完全跳过 | 不要用 `COUNT(coupon_event)`；索引只能减少扫描范围，不能把“数当天所有行”变成 O(1) |

`dailyLimit` 的计数器是**单模板单日一行**，高 QPS 时它本身会成为热点。两条演化路径：

1. 先按 DB 单行计数上线，压测确认是否成为瓶颈；
2. 若单行超过约 300–615 TPS（公开基准，硬件相关），改成**日额度分段**：DB 行只负责按段分配（如每次 1000 个），实例/Redis 消费段内额度；DB 的 `next_start` 只按段更新，热行被打散。代价是极端情况下可能少发一个段的余量，但不会超发。

`COUNT` 只保留在**对账/修复**：`C1StockReconcileService` 的 `SUM(used)` vs `COUNT(user_coupon)`、日审、迁移快照。热路径不允许出现按模板/按天的 COUNT。

实施状态（2026-10-22）：0A/0B 还是上面的“三次 COUNT”版本，属于正确性优先的临时实现；限领/日限 fast path 与 `coupon_*_counter` 表列入阶段 0 后续/阶段 1。**2026-10-23 补充**：异步受理面的 1k/2k 压测已完成（见[压测报告](../evidence/2026-10-23-异步发放1k2k压测报告.md)），但**同步 C1 路径本身尚未做 1k/2k 压测**；是否需要日额度分段仍要看同步 C1 的实测结果，不能拿异步受理数字代替。

### 3.4 Redis 的职责（以及缓存风险）

先纠正一个容易写错的点：**现有 `CachedTemplateReader` 是 JVM 本地 Caffeine，不是 Redis。** C1 不把模板缓存搬到 Redis；Redis 在 C1 中只承担跨实例共享的协调状态：

- 模板元数据：本地 Caffeine（现有 `CachedTemplateReader`），只服务领券热路径；三兄弟防护已实现并有单测；
- 限流/配额（现有 Sentinel/Bucket4j）；
- 幂等快路径（现有 `c:open:idem:*`，DB `uk_idem` 终审）；
- 用户限领/日限快路径（现有 `c:ul/c:uld/c:dly`，DB `uk_user_seq` 终审；日限若要硬约束，后续补 DB 日计数行）；
- 短 TTL 售罄提示 `c:so:{tpl}`（只做保护，不作为账本；库存调整/重建后清除）。

**库存正确性不依赖以上任何缓存**：`c:so` 丢失/过期/误写，最多多打一次 DB 条件更新；模板缓存丢失/过期，最多多打一次主键读。Redis 整体宕机时，领券仍由 DB 条件更新裁决，不会超发。

#### 3.4.1 穿透/击穿/雪崩：分层处理，不搞一套万能锁

| 缓存 | 穿透 | 击穿 | 雪崩 | 一致性/故障 |
|---|---|---|---|---|
| 模板元数据（本地 Caffeine） | 负缓存 `Optional.empty()`，60s+抖动；先校验 ID 格式、租户归属、限流 | `refreshAfterWrite`：越线后单线程回源，其余拿旧值 | TTL ±20% 抖动；`maximumSize=10k` | create/publish 主动 `invalidate`；多实例只失效本机，TTL 是上界 |
| `c:so:{tpl}` 售罄提示 | 只对已存在模板写；不存在的模板先被模板负缓存挡住 | TTL 5–10s+抖动；热点模板可加 1s 本地标记/单飞 | 多模板 TTL 抖动；Redis 故障直接回 DB | 库存调整/重上线后 `DEL`；误写只造成短暂假售罄，DB 终审 |
| `c:open:idem:*` 幂等快照 | 不是缓存；token 8–64 字符、每租户限流，键基数有界 | 不适用；PENDING 60s | 不适用；Redis 故障 fail-open | DB `uk_idem` 终审；快照 24h、单条≤64KB |
| 限流/日配额 | 不适用 | 不适用 | 不适用 | Redis 故障 fail-open + 指标；本地 Sentinel 规则仍在；不能把限流当库存闸门 |

**明确不做**：

- 不因为“缓存三兄弟”就给模板 ID 上 Bloom filter：负缓存 + 鉴权 + 限流已经把随机 ID 探测的 DB 压力压到可接受；Bloom filter 的误判/重建/新模板可见性成本更高。
- 不在每次 miss 时抢分布式锁：本地 `refreshAfterWrite` 已消掉单实例惊群；跨实例的模板回源量 = 实例数，不是请求数。需要跨实例单飞时再加 Redis 锁，且锁必须有超时/降级。
- 不缓存 `stock_shard.used` 作为扣减依据；不把 `c:so` 当账本；“缓存说没货”只能快速失败，不能作为最终事实。
- 不给负缓存永久 TTL；模板创建/发布/库存调整后要主动失效。
- Redis 作为缓存/协调存储时设置 `maxmemory` + `volatile-lru`（所有键都有 TTL）；库存键已退出，Redis 内存不再随库存规模增长。

**多实例的已知缺口**：当前 `CachedTemplateReader.invalidate` 只清本实例；多实例下线/发布最多延迟一个 TTL（默认 5 分钟）才在所有实例生效。现在单实例不是问题；V6 阶段 5B 已排期用 Redis pub/sub + 版本号 + TTL 兜底补齐（见实施计划 5B），在此之前不宣称“多实例发布立即生效”。

删除：`c:stk/c:pend/c:dbi/c:void/c:act/c:alloc/c:gens/c:opening` 的库存语义；7 个 Lua 中的 DEDUCT/CONFIRM/CANCEL/OPEN_BUCKET/ENSURE/REBUILD/CONSERVATION 全部退役；保留的 Lua 只服务幂等/限领/售罄缓存（如需要）。

### 3.5 售罄、冻结、下线、重上线

- **售罄**：所有分片 `used>=quota`；可写短 TTL `c:so`；DB 仍是权威，缓存丢失只多打 DB。
- **冻结**：管理面把分片 `status=1`；热路径 `status=0` 自然拒绝；解冻改回 0。冻结不烧库存、不改 `used`。
- **下线**：模板 `publish_status=OFFLINE` + 分片 `status=1`；已发券不受影响；在途请求按事务结果处理；不做 Redis void。
- **重上线**：审批/发布通过后分片 `status=0`；剩余库存继续用；**不重置 `used`、不建世代**。要补库存走 3.6。
- 如果业务要求“下线烧掉剩余库存”，这是显式的 `released/void` 语义，必须新增字段并重定义对账；C1 默认不做，避免把“停发”误当“库存作废”。

### 3.6 调整库存（append/缩减）

`appendStock` 和“重新上线补库存”是确定需求，C1 用**调整 quota** 实现，不需要开桶/世代。

建议接口（管理面，`X-Admin-Token`/RBAC + 审计）：

```text
POST /api/templates/{id}/stock-adjust
{
  "requestId": "stock-adj-20261022-001",
  "expectedTotalCount": 100000,   // 乐观并发
  "targetTotalCount": 120000,     // 目标总量（增/减都走它）
  "reason": "双十一补库存"
}
```

规则：

1. `expectedTotalCount` 必须等于当前 `total_count`（CAS/版本），否则 409/状态冲突；`requestId` 幂等。
2. `target < SUM(used)` → 拒绝：已发券不能被“减库存”抹掉；要收回走召回/作废/退款流程。
3. `target > current`：`delta` 加到分片 quota（优先加到剩余最多的片/轮转），`total_count=target`；断言 `Σquota=total`。
4. `target < current`：只从 `quota-used` 足够的片里减，保证每片 `quota>=used`；断言 `Σquota=total`；拒绝减到低于已发。
5. 同一事务更新 `coupon_template.total_count/version` + 分片 quota + 写审计；提交后清 `c:so`；失败整体回滚。
6. 大调整可短暂把分片 `status=1` 冻结发券（可选），但调整本身是低频管理操作，不需要停整个模板。
7. 审计六要素：操作人、时间、原因、before/after total、各片 quota 变化、requestId。

**为什么不需要 generation**：C1 的分片行是固定布局，追加只是改同一行 `quota`；已发 `used` 不动；没有“旧代/新代”需要隔离。这正是当前模型复杂度的主要来源被消灭的地方。

### 3.7 失败、幂等、补偿

“失败补偿”不是指某一个反向 SQL，也不是引入分布式事务；它是指**每条失败路径都有明确归宿**。

**目标态语义（简历口径）**：失败补偿是能力承诺，不随某一版实现缩小。库存、限领/幂等、预算、事件/任务、不确定提交、对账六类失败都必须有归宿。C1 只是把“库存”这一类从“Redis 预扣 + confirm/cancel”改成“DB 同事务回滚”；预算 reservation 释放、ledger 冲正、outbox 重试/死信、任务重试、对账告警继续按目标态建设。当前未实现的预算释放是待办，不是语义边界。

按场景拆开：

| 失败场景 | 旧链路（legacy） | C1 |
|---|---|---|
| Redis 预扣成功、DB 事务失败 | `cancel(TRANSIENT/SOLD_OUT/OFFLINE)` 回滚 Redis 库存、限领计数、幂等令牌 | **不存在这个半截状态**：库存扣减与落券在同一个 DB 事务，失败整体回滚，无 Redis 库存补偿 |
| DB COMMIT 结果不确定（断连） | Redis confirm/cancel + 事务外探针 | 同一 `requestId` 重试，按 `uk_idem` 回查；券在=重放，券不在=可重试 |
| 重复 `requestId` | Redis -8 + DB `uk_idem` 回查 | 先查 DB `uk_idem`，命中直接返回原券；并发撞唯一键后回查 |
| 同用户 `seq` 冲突 | Redis 用户计数 + `uk_user_seq` + 计数自愈 | 重新取 `MAX(seq)+1` 有界重试；达到限领则拒绝 |
| 事件投递失败 | outbox 退避/重试/死信 | 同左，事件与券同事务，不丢事实 |
| 过期/释放任务失败 | 任务表扫描 + 重试 | 同左，任务与券同事务注册，扫描重试 |
| 预算占用后落库失败 | 释放 reservation、ledger 冲正（阶段 1） | 同左；预算以 DB 台账为准 |
| 限领/日限计数泄漏 | Redis 计数虚高时用 DB 行数复核自愈 | C1 直接以 DB 行数/事件数为准，不需要 Redis 计数回滚 |
| 对账发现库存差异 | E1/E2/E3 + deep 自动修复/冻结 | `Σquota=total`、`Σused=COUNT(user_coupon)` 只告警 + 人工修复，不自动刷库存 |

**一句话**：C1 改变的是“库存补偿”的实现方式——从 Redis 库存 confirm/cancel 改为 DB 同事务回滚；**失败补偿能力本身没有缩小**，仍包括 DB 事务回滚、幂等重放、不确定提交回查、outbox 重试/死信、预算占用释放、任务重试和对账告警。面试表达先讲目标态六类补偿，再讲 C1 的实现变化；不要用当前未完成项反推词义。

其他细节：

- 任何 DB 失败 = 事务回滚；库存与券一起回滚，无 Redis 补偿。
- 事件/发件箱/过期任务与券同事务；失败一起回滚，由调用方重试；outbox 投递失败走现有退避/死信。
- 售罄缓存可能短暂过期/错误；DB 条件更新是最终裁决，缓存只影响性能。

### 3.8 对账（最小集）

C1 不再需要 E1/E2/E3 五键守恒；保留“能发现”能力：

1. **发布/调整断言**：`Σquota=total_count`、每片 `used<=quota`、行数=N_eff、`status` 合法。
2. **每日/小时对账**：`SUM(used)` vs `COUNT(user_coupon)`（可按模板/租户抽样或分批；64 张分表 count 成本高时用事件计数交叉校验）、`SUM(quota)` vs `total_count`；差异只告警，不自动改库存。
3. **漂移日志**：沿用 `stock_drift_log`（降级为告警/迁移日志）；不做 deep 自动修复。
4. **人工修复**：必要时按 `COUNT(user_coupon)`/事件重算某模板的 `used`，走管理工具 + 审计；不允许自动刷库存。
5. 监控：`stock_deduct_total{result}`、`stock_shard_used{template_id?}`（注意基数，模板 ID 不进标签或只做低基数聚合）、`stock_sold_out_total`、`stock_adjust_total`。

### 3.9 性能与容量

> 以下为公开基准/项目内估算，不是本机容量承诺；实施后必须用独立 MySQL/Redis 压测校准。

- 单行条件 UPDATE 公开基准约 300–615 TPS（硬件相关）；N=16 时 1k QPS 约 62 TPS/片，行锁不是瓶颈；2k 约 125 TPS/片；5k 约 312 TPS/片，接近单行上限，且每笔 5 条写，**5k 同步不现实**。
- 1k QPS：每笔 5 条写 ≈ 5k 语句/s；2k ≈ 10k；5k ≈ 25k。单实例 MySQL 的总写吞吐/redo/binlog/fsync 先到顶。
- 连接池：当前 dev 池 20，1k 需要约 50~100，2k 约 100~200；按 Little 定律压测配平，不用大池掩盖行锁；`innodb_lock_wait_timeout` 建议 500ms~1s，事务内禁止 RPC。
- 分片选择/回退：顺序探测最多 N 次；售罄时用短 TTL `c:so` 挡住重复扫描；`stock_shard` 用 `(template_id, shard_no)` 唯一键，热 SQL 走唯一索引。
- 5k 的出路：**异步受理 + 批量落库**（L2），或分库/号段（L3/L4），不是增加库存分桶/世代。V6 阶段 1 的发放任务/异步链路就是承载。

---

## 4. 删除与保留

### 4.1 保留（红线/必需）

- DB 条件扣减 `used<quota` 作为唯一库存权威；所有发券路径（单发/批量/码/后台补发）必须走同一入口。
- 同一本地事务写 `user_coupon + coupon_event + coupon_outbox + coupon_expire_task`；失败整体回滚。
- `uk_idem` 幂等终审、`uk_user_seq` 限领终审、重复请求重放。
- 不确定提交探针（提交断连按幂等键回查）。
- 最小对账：布局断言 + 每日 `SUM(used)=COUNT(user_coupon)` + `Σquota=total`；只告警，不自动改库存。
- 手工冻结/停发、短 TTL 售罄缓存（可选）、审计。
- 64 张 `user_coupon`/`coupon_usage` 分表不动；库存表本来就不在 64 分表内。

### 4.2 删除（当前模型专属）

- `stock_bucket` 的 `generation/status/allocated_hi`、动态开桶协议（`c:opening`、allocated CAS、尾量、差额恢复、孤儿重放、封账、批量 refill、世代回退、L37 段唯一键）。
- `coupon_template.allocated` 的活语义；`issued_count` 从活计数降为报表快照。
- Redis 每（桶,世代）四态键 `c:stk/c:pend/c:dbi/c:void` 及 `c:act/c:alloc/c:gens` 的账本语义；`c:so` 降级为短 TTL 缓存；`c:fz` 只保留手工冻结。
- 7 个 Lua（DEDUCT/CONFIRM/CANCEL/OPEN_BUCKET/ENSURE/REBUILD/CONSERVATION）及 9 种返回码/五重校验/令牌固化。
- `pend` ZSET + confirm/cancel 配对 + 180s 清扫（DB 事务原子性替代）。
- 世代寻址/跨代取消判定。
- E1/E2a/E2b/E3a/E3b/E3c、五键守恒、方向性镜像、两轮/三步确认、200ms 二次快照、初判后新鲜重读。
- deep 异步修复、REBUILD force/shadow、白名单、连续 FORCE 熔断、resetAlloc、`c:reconcile:mode`、巡检心跳/风暴聚合等运维件（保留简单告警）。
- 自动冻结/自动解冻；保留人工冻结。
- `StockBucketRouter`（465 行）、`RedisStockGateway`（676 行）的分代/开桶/守恒部分、`StockReconcileService`（870 行）的等式/deep 部分、`StockBucketMapper`（96 行）的桶行部分。
- 依赖旧形状的约 40~45 个结构性测试；保留并改写零超发/限领/幂等/回滚/outbox 行为契约。
- L28（E3b 未实现）、L29（c:gens 无 GC）这类“越用越重”的结构性成本随世代/名册删除一并消失。

> **不是删保护，而是删掉需要这些保护的对象**：Redis 不再是库存权威、分片不再重开，世代/守恒/deep 就没有存在土壤。

---

## 5. 未来演化

C1 不是终点；它把库存账本收敛成“固定分片行 + 条件更新”，后续升级是加法，不是换账本。

| 档位 | 形态 | 适用 | 触发条件（估算，需压测校准） |
|---|---|---|---|
| **L0** | C1，N=1（单行） | 当前 104 QPS / 小规模 | 单模板 <300 TPS、p99 锁等待 <10ms |
| **L1（当前目标）** | C1，N=16/32 固定分片 | 1k–2k 同步 | 单模板 >300 TPS、p99 锁等待 >10ms、连接池 pending >0、分片利用率 >70% |
| **L2** | C1 账本 + 异步受理/批量落库（请求返回“受理中”，worker 执行同一 DB 条件更新） | 5k 同步不可行时 | 单模板 >2k、每笔 5 写打到实例写吞吐 70%、产品接受三态 |
| **L3** | C1 账本 + Redis 预闸门（只挡售罄/热点，DB 仍是最终权威）或 C2 号段 | 5k–20k | 单模板 >5k、Redis/DB 压测显示 DB 行锁/连接池成为瓶颈、需要削峰 |
| **L4** | 物理分库/多库、号段/分区 | >10k–20k 或单库容量到顶 | 单库写吞吐/容量瓶颈、需要多地域/多租户物理隔离 |

### 5.1 L2：异步受理/批量落库（5k 的主要出路）

- 请求只做鉴权、参数、模板/活动粗检、`requestId` 幂等，写 `coupon_grant_task/item`（V6 阶段 1 已有）后返回“受理中”。
- worker 按用户分片批量执行：每条仍是 `UPDATE stock_shard ... WHERE used<quota` + 插入券/事件/outbox/过期任务；DB 条件更新仍是最终闸门。
- 查询接口返回受理/成功/失败；失败可重试；取消不回滚已发券。
- 批量写入可按分片/用户归集，减少事务数；但不得绕过 `used<quota`。
- 这是 V6 阶段 1 的发放任务模型与 C1 的结合，不需要库存世代。
- **详细设计见 [ADR-0019](ADR-0019-async-issue-task-wal.md)**：在任务表前加 Redis 预扣/准入缓存（Vivo 方案 2），但 Redis 仍不是库存权威，DB `used<quota` 仍是终审。这与 §5.2 的“纯 Redis 预闸门/C2 号段”不同：ADR-0019 服务 1k–5k 的异步受理，L3 是为更高吞吐/更复杂分片准备的演化。

### 5.2 L3：Redis 预闸门或 C2 号段

- **Redis 预闸门**：只缓存“售罄/余量提示”，不做库存权威；DB 仍是唯一账本。适合挡住售罄洪峰，不引入双账本对账。
- **C2 号段/区间分配**：DB 账户按段分配给实例，实例本地发放，消费者批量落库；`account.available` 条件扣 + segment 唯一键保证不超发。适合 5k–50k、批量 10 万+/天、产品接受“受理中→最终结果”三态。
- C2 的代价：account/segment/instance/issue_request 四表 + 本地 buffer + 预取 + 分配 + MQ/WAL + 消费者 + reaper + 三态 + epoch 围栏，约 6–8 个组件；段滞留/已受理未落库/租约脑裂是新的风险。当前 1k 规模不选。

### 5.3 为什么不是 A/B

- **A（Redis 单键 + DB 模板行）**：保留双账本；`issued_count` 当前是 V10 冻结快照，迁移要逐模板重算；混合模式旧网关会绕过守卫；迁移风险四案最高；5k 逃生门等于二次迁移到 B。
- **B（固定 N 分片 + Redis 每片闸门）**：去掉世代/开桶/封账/差额恢复，但保留每片 stk/pend、逐片重建、stale 清扫、双账本；复杂度只降一半；N=16 时每模板约 32 个库存键，N=64 时约 128 个，并不比现状少。
- **C2（号段/异步）**：是换赛道，不是简化；当前 1k 规模净增复杂度。
- **C1**：一个账本、一条条件更新、一个布局断言、一个日对账；升级路径 N=1→16/32、同步→异步、5k+ 再加 Redis 闸门/分库。

---

## 6. 迁移

### 6.1 原则

- **不做库存双写**；只做“旧模型冻结 + 快照 + 新模型初始化 + 影子读对账”。
- 按模板切，不按流量百分比切；同一模板绝不长期两套账本。
- 旧表/旧代码保留一个发布周期，保证可回滚；稳定后归档/删除。
- 迁移前先补 ADR/设计（本文）与 3 个红灯用例；不要先改代码再论证。

### 6.2 阶段

1. **阶段 0-A（设计/基线）**：本文评审；建立 C1 行为契约测试；记录当前 594 例基线、104 QPS 基线；准备独立 MySQL/Redis 压测环境。
2. **阶段 0-B（加法迁移）**：V25 新增 `coupon_stock_shard`、`coupon_template.stock_model/stock_shard_count/stock_layout_version`、`coupon_stock_adjust_log`（可选）；旧表/旧代码不动。
3. **阶段 0-C（混合守卫）**：所有实例（含旧实例）读 DB `stock_model`；`stock_model=1` 时旧网关拒绝写 `stock_bucket`；Redis `c:model` 只做缓存，丢失去 DB 读，未知 fail-closed。必须先于第一个模板迁移全量生效。
4. **阶段 0-D（新模板 C1）**：新模板默认 `stock_model=1`，发布时建 N 行、`Σquota=total`、`used=0`；旧模板继续 legacy。
5. **阶段 0-E（存量逐模板迁移）**：
   - 冻结：模板 `stock_model=2`（停发，返回 5001，不返回假售罄）；
   - 排空：等旧全世代 `pend=0`/清扫，确认无超过窗口的长事务；
   - 快照：`issued_old = SUM(stock_bucket.used)`（单表）；可选 `COUNT(user_coupon)` 交叉校验；
   - 建分片：`Σquota=total_count`，按 quota 比例分配 `used`，断言 `Σused=issued_old`、`used_i<=quota_i`；
   - 同事务写分片行 + `stock_model=1`；清旧 Redis 键；
   - 观察一个日对账周期，确认 `SUM(used)=COUNT(user_coupon)`、无超卖。
6. **阶段 0-F（清理）**：稳定一个发布周期/季度后归档 `stock_bucket`、删除旧 Lua/Router/Reconcile/deep 代码与约 40~45 个结构性测试；保留行为契约测试；更新 STATUS/文档/ADR。

### 6.3 回滚

- 回滚是 break-glass，优先向前修；必须回滚时：
  1. 冻结新模型；
  2. 计算 `Δ = SUM(stock_shard.used) - 切换时 issued_old`；
  3. 把 Δ 分摊回旧活跃桶行 `used`（或补新代/新行），处理 `allocated_hi` 唯一键冲突；
  4. 重建旧 Redis 键（ENSURE/REBUILD 临时保留）；
  5. 翻回 `stock_model=0`，解冻。
- 预发环境演练一次；生产回滚必须有人工审批与审计。

### 6.4 验收

- 新模型行为契约：1 万并发抢 1 万库存零超发；`Redis FLUSHALL` 不影响库存；DB 超时/不确定提交可恢复；重复 `requestId` 只发一张；限领/日限；库存调整增/减；下线/重上线；outbox 事件一致。
- 迁移：逐模板快照 parity（`Σused=issued_old`）、`Σquota=total`、迁移后 24h 无漂移。
- 容量：1k/2k 同步压测；5k 异步设计验证；用独立 MySQL/Redis，不用 WSL2 数字对外承诺。
- 全量测试保持 590+：删除结构性测试的同时补充 C1 行为测试（并发/故障/迁移/调整），不降低净覆盖。

---

### 6.5 实施进展（2026-10-23，C1-only 收口）

已完成：

- **V25/V26**：`coupon_stock_shard`、`coupon_stock_adjust_log`；`coupon_template.stock_model/stock_shard_count/stock_layout_version`。
- **C1 唯一模型**：新建模板一律 C1；发布事务内 CAS 发布状态 + 建 N_eff 行（`Σquota=total`）；领券走 `C1ReceiveAppService`，每个分片一个短事务执行 `used<quota` 条件更新，成功后同事务写 `user_coupon/coupon_event/coupon_outbox/coupon_expire_task`。
- **旧链路删除**：`RedisStockGateway`、`StockBucketRouter`、`StockReconcileService`、`StockBucketMapper/PO`、`StockDeductRejectedException` 与 13 个 legacy 测试已删除；`ReceiveAppService` 只保留 C1 包装器。
- **V29/V30**：删除 `stock_bucket` 表和 `coupon_template.stock_model` 列；代码不再区分 legacy/C1。
- **库存调整/最小对账**：`StockShardAdjustService` 调整 quota；`C1StockReconcileService` 校验 `Σquota=total_count`、`Σused=COUNT(user_coupon)`，差异只告警。
- **测试**：`mvn clean test` 518 例，517 绿 + 1 门控跳过 + 0 失败/0 错误；`C1StockIntegrationTest` 8/8、`AsyncIssueWorkerIntegrationTest` 8/8、`ExpiryIntegrationTest` 9/9、`LockTimeoutIntegrationTest` 11/11、`SchedulerLockIntegrationTest` 3/3。异步受理面 1k/2k 压测见[压测报告](../evidence/2026-10-23-异步发放1k2k压测报告.md)；同步 C1 压测仍未做。

不再做：

- 0C 存量迁移：旧数据已废弃，本地新库从 0 开始。
- 0D 兼容退役：旧代码/测试已直接删除，不保留回退路径。

仍待做：

- **同步 C1** 路径的 1k/2k 压测；异步受理面已于 2026-10-23 压过（1k=1109 req/s、2k=2122 req/s），但两者不是同一条路径，不能互相代替；
- C1 同步路径去 `COUNT`（P0-5）；
- 物理 SQL 断言（P0-6）。

---

## 7. 风险与待定

| 风险/待定 | 说明 | 处理 |
|---|---|---|
| 目标 QPS | 104 / 1k–2k / 5k 决定 N、同步/异步、是否加 Redis 闸门 | 需要业务拍板；当前按 1k–2k 设计，5k 走 L2 |
| DB 单行/分片行热点 | 1k–2k 可控；5k 同步不现实 | 压测 + 连接池 + 短事务 + L2 异步 |
| 旁路超发 | 手工 SQL/导入/旧网关绕过条件更新 | 所有发券走同一入口；混合守卫；每日对账 |
| 迁移期旧事务 | 快照时旧事务未提交，迟到券不在 Σused | freeze + pend 排空 + 长事务屏障 + 迁移后对账 |
| 日限硬约束 | 当前只有 Redis 日键 | 若要硬约束，补 DB 日计数行/日序号唯一键 |
| 多实例模板缓存失效 | Caffeine 本地失效只清本机；下线/发布最多 TTL 后在其他实例生效（默认 5 分钟） | 单实例阶段接受；V6 阶段 5B 加 Redis pub/sub + 版本号 + TTL 兜底；在此之前不宣称多实例发布立即生效 |
| 售罄缓存假阳性 | `c:so` 误写/残留可能短暂拒绝领券 | 5–10s TTL + 抖动；库存调整/重上线主动 `DEL`；DB 条件更新终审；不把缓存当账本 |
| append 语义 | 目标总量可能低于已发 | 拒绝；收回走召回/作废/退款，不减 `used` |
| 测试数量 | 删 40~45 个结构性测试可能低于 590 | 同步补 C1 测试；保持 590+；不把“删测试”当简化 |
| 文档债 | kernel 01/02 未写 total/allocated/issued 与 E1/E2/E3 | 本文 + 后续 kernel 更新；库存模型唯一权威文档化 |

---

## 8. 附：C1 与当前模型的关键差异

| 维度 | 当前 | C1 |
|---|---|---|
| 库存权威 | Redis 预扣 + DB 桶行 | **DB 分片行** |
| Redis 库存键 | 14 类 | 0（模板缓存仍在 JVM 本地；Redis 只做限流/幂等/售罄提示） |
| 分片 | 16 桶 + 世代 + 动态开桶 | 固定 N 行，无世代/无开桶 |
| 令牌/confirm/cancel | pend/dbi/void + 两阶段 | 无；DB 事务原子性 |
| 对账 | E1/E2/E3 + deep 自动修复 | 布局断言 + 日对账 + 告警 |
| 代码量 | 约 2,100 行库存专用 | 约 300–500 行 |
| 1k–2k 同步 | 可 | 可（N=16/32） |
| 5k 同步 | 不现实（5 写/笔） | 不现实；走 L2 异步 |
| 调整库存 | 开桶/世代/差额恢复 | 直接加/减 quota |
| 故障语义 | Redis/DB 双账本补偿 | 事务回滚，无补偿 |

---

## 9. 验收清单（实施后）

- [ ] `stock_shard` 布局断言：行数、`Σquota=total`、`used<=quota`、`status`。
- [ ] 1 万并发抢 1 万库存：成功数=库存、无超发、无重复。
- [ ] `Redis FLUSHALL`：库存不受影响；DB 条件更新仍正确；模板读回源 DB；`c:so` 丢失不超发。
- [ ] 模板缓存穿透：随机不存在 ID 连续 1000 次，DB 回源次数有界（负缓存生效）。
- [ ] 模板缓存击穿：热点模板 20 并发越过刷新线，回源 ≤2 次（保留 `CachedTemplateReaderTest` 回归）。
- [ ] `c:so` 假阳性：售罄后调整库存，`c:so` 被清除且下一请求可领；误写只造成 ≤TTL 的短暂假售罄。
- [ ] Redis 故障：幂等/限流 fail-open 有指标；库存仍由 DB 条件更新裁决，不超发。
- [ ] 多实例：本阶段若仍单实例，记录“模板失效仅本机”的已知缺口；多实例验收前补 pub/sub 广播。
- [ ] DB 超时/COMMIT 断连：按 `uk_idem` 回查，不重复发券/不丢券。
- [ ] 重复 `requestId`：只发一张，重放同一结果。
- [ ] 限领/日限：DB 唯一索引终审；Redis 丢失不超限。
- [ ] 库存调整：增/减/目标低于已发拒绝/审计/幂等。
- [ ] 下线/重上线：停发/恢复，不重置 `used`、不建世代。
- [ ] 迁移 parity：`Σused=issued_old`、`Σquota=total`、24h 无漂移。
- [ ] 全量测试 590+、独立 MySQL/Redis 1k/2k 压测报告、回滚演练。

---

## 10. 完整调研链接

> **链接规范（长期）**：以后每次调研/ADR/设计文档都在文末附完整 URL（不是只写域名），并标注证据等级与访问日期；内部文档用仓库路径。本文为第一份按此规范执行的文档。证据等级：A=官方文档/官方基准；B=企业官方工程博客；C=产品规则/第三方工程文章；D=面经/二手转述/推断。

### 10.1 官方文档/官方基准（访问日期：2026-10-22）

- [A] MySQL 8.4 InnoDB 锁：https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html
- [A] MySQL 8.4 `innodb_lock_wait_timeout`：https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_lock_wait_timeout
- [A] MySQL 8.0 Locking Reads：https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html
- [A] Redis Lua 脚本：https://redis.io/docs/latest/develop/programmability/eval-intro/
- [A] Redis 持久化：https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
- [A] Redis 主从复制：https://redis.io/docs/latest/operate/oss_and_stack/management/replication/
- [A] Redis WAIT：https://redis.io/docs/latest/commands/wait/
- [A] Redis Cluster 规范：https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/
- [A] Redis 内存淘汰：https://redis.io/docs/latest/develop/reference/eviction/
- [A] Redis Pub/Sub（多实例缓存失效广播）：https://redis.io/docs/latest/develop/pubsub/
- [A] Redis INCR（计数器原子自增）：https://redis.io/docs/latest/commands/incr/
- [A] MySQL INSERT ... ON DUPLICATE KEY UPDATE：https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html
- [A] MySQL ROW_COUNT()（判断 upsert 是插入/更新/未变）：https://dev.mysql.com/doc/refman/8.4/en/information-functions.html#function_row-count
- [A] Caffeine `refreshAfterWrite`（防击穿/单飞回源）：https://github.com/ben-manes/caffeine/wiki/Refresh
- [A] Caffeine 缓存填充/负缓存模式：https://github.com/ben-manes/caffeine/wiki/Population
- [A] Stripe 幂等请求：https://docs.stripe.com/api/idempotent_requests
- [A] Shopify Inventory Management：https://shopify.dev/docs/apps/build/orders-fulfillment/inventory-management-apps
- [A] Shopify DraftOrder：https://shopify.dev/docs/api/admin-graphql/2025-10/payloads/DraftOrderCreatePayload

### 10.2 企业工程/公开分享（访问日期：2026-10-22）

- [B] AWS Builders' Library：缓存挑战与策略（穿透/击穿/雪崩的工程综述）：https://aws.amazon.com/builders-library/caching-challenges-and-strategies/
- [A] 阿里云 PolarDB-X 热点行更新：https://help.aliyun.com/zh/polardb/polardb-for-xscale/update-hot-rows
- [A] 阿里云 RDS Inventory Hint：https://www.alibabacloud.com/help/zh/rds/apsaradb-rds-for-mysql/inventory-hint
- [B] 阿里云库存合并扣减/热点优化：https://developer.aliyun.com/article/1704859
- [B] 腾讯云数据库库存分桶实践：https://developer.cloud.tencent.com/article/2623908
- [B] vivo 库存扣减方案：https://www.cnblogs.com/vivotech/p/17202501.html
- [C] 字节支付发券实践（企业自述，未独立验证）：https://blog.csdn.net/bytedancetech/article/details/125419247
- [B] 京东到家优惠券任务表（批量发放，不是库存）：https://mp.weixin.qq.com/s/FPhPfkntX-9v_X14tuZ1KA
- [A] 阿里云 Redis 秒杀场景：https://www.alibabacloud.com/help/en/redis/use-cases/use-apsaradb-for-redis-to-build-a-business-system-that-can-handle-flash-sales
- [B] Percona MySQL 最大写吞吐：https://www.percona.com/blog/maximal-write-througput-in-mysql/
- [C] 美团民宿大促面经（Redis+Lua+MQ，8k QPS；**D：未找到美团官方原文，数字仅转述，不作为选型依据**）：https://www.nowcoder.com/discuss/759716167412879360

### 10.3 产品规则/预占/幂等（访问日期：2026-10-22）

- [C] 有赞拍下减/付款减/10 分钟预占：https://help.youzan.com/displaylist/detail_4_4-2-40761
- [C] 有赞订单结算/入账规则：https://help.youzan.com/displaylist/detail_4_4-2-26701
- [A] 微信支付预存 code（一次一码/批量导入）：https://pay.weixin.qq.com/docs/merchant/apis/merchant-exclusive-coupon/busi-favor/upload-coupon-code.html
- [A] 微信支付修改批次预算（乐观并发/目标不小于已发）：https://pay.weixin.qq.com/doc/v3/merchant/4012465739

### 10.4 项目内文档/代码（迁移时对照，访问日期：2026-10-22）

- `docs/STATUS.md`
- `docs/P2b-库存批次分片-技术方案-2026-09-17.md`
- `docs/03-库存扣减与防超发.md`
- `docs/10-统一规范与决策对齐.md`
- `docs/adr/ADR-0006-redis-lua-stock.md`
- `docs/adr/ADR-0007-reconcile-inflight.md`
- `docs/adr/ADR-0008-identity-reconcile.md`
- `docs/adr/ADR-0009-business-state-conservation.md`
- `docs/research-2026-10-21/00-调研-六域对标与选型.md`
- `docs/research-2026-10-21/01-实施计划-V6-从券交易内核到券能力中台.md`
- `src/main/java/com/newcoupon/infrastructure/redis/RedisStockGateway.java`
- `src/main/java/com/newcoupon/application/service/StockBucketRouter.java`
- `src/main/java/com/newcoupon/application/service/StockReconcileService.java`
- `src/main/java/com/newcoupon/application/service/ReceiveAppService.java`
- `src/main/java/com/newcoupon/application/service/C1ReceiveAppService.java`
- `src/main/java/com/newcoupon/application/service/CouponIssueTxService.java`
- `src/main/java/com/newcoupon/application/service/StockShardLayoutService.java`
- `src/main/java/com/newcoupon/application/service/TemplatePublishTxService.java`
- `src/main/java/com/newcoupon/application/service/StockShardAdjustService.java`
- `src/main/java/com/newcoupon/application/service/C1StockReconcileService.java`
- `src/main/java/com/newcoupon/application/service/StockShardPlan.java`
- `src/main/java/com/newcoupon/infrastructure/persistence/mapper/CouponStockShardMapper.java`
- `src/main/resources/db/migration/V25__c1_stock_shards.sql`
- `src/main/resources/db/migration/V26__c1_stock_adjust_log.sql`
- `src/test/java/com/newcoupon/integration/C1StockIntegrationTest.java`
- `src/test/java/com/newcoupon/application/service/StockShardPlanTest.java`
- `src/main/java/com/newcoupon/application/service/CachedTemplateReader.java`
- `src/main/java/com/newcoupon/common/cache/CacheTtl.java`
- `src/main/java/com/newcoupon/infrastructure/web/OpenApiIdempotentFilter.java`
- `src/test/java/com/newcoupon/application/service/CachedTemplateReaderTest.java`
- `src/main/resources/db/migration/V10__p2b_stock_bucket.sql`
- `src/main/resources/db/migration/V13__l37_allocated_hi.sql`

### 10.5 证据口径

- 104 QPS：本机 WSL2 2000 并发/19.3s，形状验证，不是容量承诺。
- 300/615/23,262 TPS：公开基准/厂商口径，硬件相关；不是本机实测。
- 1k/2k/5k：按“每笔 5 条 DB 写”的估算，实施后用独立 MySQL/Redis 压测校准。
- Redis AOF everysec 1s/主从丢写/WAIT 非 CP：官方文档语义。
- “美团 8k QPS/300 TPS”等：公开面经/转述，未找到官方原文，不作为选型依据。

---

## 11. 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-22 | 接受 C1；数据模型/热路径/调整库存/对账/演化/迁移/完整调研链接 |
| v1.1 | 2026-10-22 | 澄清模板缓存是 JVM 本地 Caffeine 而非 Redis；补 §3.4.1 穿透/击穿/雪崩分层处理、多实例失效缺口、缓存验收项与链接 |
| v1.2 | 2026-10-22 | 阶段 0A/0B 实施进展：V25/V26、C1 新模板闭环、库存调整、最小对账、混合守卫、定向测试与全量基线；0C/0D 待做 |
| v1.3 | 2026-10-22 | 澄清“失败补偿”的边界：按失败场景列出旧链路/C1 的做法，明确 C1 删除 Redis 库存 confirm/cancel，保留事务回滚/幂等重放/不确定提交回查/outbox/预算释放/对账告警 |
| v1.4 | 2026-10-22 | 按用户口径修正：简历定义目标语义，不用当前进度反推词义；失败补偿是六类能力的总称，C1 只改变库存补偿实现，能力不缩小 |
| v1.5 | 2026-10-22 | 补 §3.3.1：限领/日限热路径禁止 COUNT；每人总限用 MAX(seq)，每人日限/模板日限用 DB 日计数行，COUNT 只用于对账；标注 0A/0B 的三次 COUNT 是待优化的临时实现 |
| v1.6 | 2026-10-22 | §5.1 指向 ADR-0019：Redis 预扣 + 任务表 WAL 的异步受理层；明确 Redis 仍不是库存权威，DB `used<quota` 终审 |
| v1.7 | 2026-10-23 | C1-only 收口：旧 Redis 桶/世代/对账/legacy 测试删除，V29 删除 stock_bucket；0C/0D 不再做；全量 513 例 0 失败 |
| v1.8 | 2026-10-23 | V30 删除 coupon_template.stock_model 列；代码不再区分 legacy/C1 |
