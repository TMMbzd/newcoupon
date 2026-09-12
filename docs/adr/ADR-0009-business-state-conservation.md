# ADR-0009: 业务状态分区与库存守恒（四状态模型，取代 ADR-0008 的恒等式形态）

- **状态**：已提出 v3（2026-09-12；v1 经 4 视角红队评审折入 2 P0+7 P1；v2 经四位面试官角色 33 题答辩折入 §11 修订——9 题确认站得住、24 题修正，详见《守恒方案答辩-架构师面试问答-2026-09-12.md》）
- **取代关系**：取代 ADR-0008 的"复式记账恒等式"形态（issued−dbi−pend≡0）；在途显式化、Lua 原子配对、令牌幂等、钉零+标志、T+1 审计全部保留并继承。
- **溯源**：外部替代方案对比（09-09）→ 外部讨论七项对抗核查（09-10）→ 复杂性审计三红队（09-11）→ 用户裁定采纳外部建议（简化版：守恒+常设镜像+T+1+极窄域自动修复）与设计原则 → v1 红队评审（09-12）。

---

## 0. 设计原则（门禁条款）

> **先定义业务状态，再写恒等式；不要为了恒等式创造业务状态。**

落地为可执行门禁：**每新增一个 Redis 键，必须先说出它对应的业务事件；说不出来的，不许加。**

ADR-0008 的教训即违反此原则：`issued`（"Redis 借方"）不是任何一张券的业务状态，是为凑恒等式造的会计科目——它使检验脱离业务库存（stk 不在式内，改低永久少卖无检测），也看不见"三本账整体平移"。

## 1. 四状态分区模型

对齐 Redis 官方 reservation 模型术语（available / reserved / confirmed / expire-release）。**任意时刻，一张券处于且仅处于以下四态之一**：

| 业务状态 | 含义 | Redis 载体 | 行业术语 |
|---|---|---|---|
| 可领 | 可被任何请求预占 | `c:stk:{tpl}`（计数） | available |
| 预占 | 已过闸、DB 未定案（成员=`uid:reqId`，分数=进闸时刻） | `c:pend:{tpl}`（ZSET） | reserved |
| 已发放 | DB 已确认发放 | `c:dbi:{tpl}`（计数） | confirmed |
| 已撤销 | 售罄/下线退役，退出流通 | `c:void:{tpl}`（计数） | withdrawn |

辅助键（策略/凭证，非状态）：`c:so:{tpl}` 停流短路标志（售罄与下线退役均立，见 §2）；`c:idem` 请求去重缓存；`c:ul/uld/dly` 限领计数——**不入守恒体系**，其崩溃无回滚残余登记为 L3（§8.4）。

**时间权威**：令牌分数与清扫阈值均取自 Lua 内 `redis.call('TIME')`（Redis 服务端时钟）——多实例时钟漂移结构性归零（Redis≥5 脚本按效果复制，非确定性命令安全），NTP 降为运行手册建议。

**守恒式（唯一的第一层不变量，仅在已发布域强制）**：

```
stk + ZCARD(pend,清扫后) + dbi + void ≡ total        （期望精确相等，无容忍带）
```

读作业务话术：**剩余 + 在途 + 已发放 + 已撤销 = 总投放量。**

## 2. 状态转移表（全部自动化路径）

| 转移 | 操作 | 守恒效应 |
|---|---|---|
| deduct（预占） | stk−1，ZADD pend（score=Redis TIME） | 和不变 ✓ |
| confirm（定案） | ZREM pend（=0 则 no-op），dbi+1 | 和不变 ✓ |
| cancel·transient | ZREM 门；回滚 ul/uld/dly；**若 so 存在→改走 terminal(soldout)**；否则 `if EXISTS stk then INCR else 不造键`；DEL idem | 和不变 ✓（缺失 stk 时不造键，交由 REBUILD/巡检） |
| cancel·terminal(soldout) | ZREM 门；回滚计数；**void+=1（只退休这张令牌）**；SET so；DEL idem | 和不变 ✓——**不动 stk**：短路由 so 独立承担，被污染的 stk 留给 FORCE 精确还原（v2：v1 的"void+=1+当前stk"会把 stk 污染洗白进 void 且 FORCE 无法修复，P0） |
| cancel·terminal(offline) | ZREM 门；回滚计数；void += 1 + (EXISTS stk ? GET stk : 0)；if EXISTS stk then SET stk 0；SET so（退役标志，防风暴期 transient INCR 重开闸门）；DEL idem | 和不变 ✓；模板随即退出已发布域 |
| 巡检清扫（TTL 过期令牌） | ZREMRANGEBYSCORE（阈值=Redis TIME−TTL） | **故意破缺**——崩溃残留的合法外衣在此被撕掉 |
| REBUILD·NX（-7 懒重建） | 清扫；`if EXISTS stk then 放弃`；否则 SET stk=max(0, total−dbi−ZCARD−void)（dbi 键缺失时 SET dbi:=ic 后同式） | 选择性丢失形态精确；全清库形态近似（误差=未提交在途数，由镜像层收敛，§8.3） |
| REBUILD·FORCE（守卫修复） | 见 §4 白名单与推导规则 | 修复后守恒 ✓ |
| publish / 重新上线（协议路径） | 整本重置：stk=total−ic、dbi:=ic、DEL pend、void:=0、DEL so；落一条 drift_log(RESET) 供审计 | 重置后守恒 ✓ |
| appendStock（M6） | DB 同锁 total+=δ → Redis stk+=δ（在则 INCR，不在则 SET δ）→ DEL so | 和不变 ✓（v2 修正：v1 的"void−=加量"数学上必破守恒且违反单调性） |
| 复活撤销量（M6，独立操作） | void−=x 且 stk+=x（前置 x≤void） | 和不变 ✓（与加量是两件事，不得混写） |

> **表格脚注（实施规格）**：所有 cancel 变体（transient/terminal）都回滚 ul/uld/dly（day 用 cmd.day，见 §6.3）并 DEL idem——限领计数不入守恒式但必须与 deduct 配对回滚；表中"回滚计数"即指此。所有 SET stk 公式统一 `max(0,·)`。所有先读后写的键操作必须在同一 Lua 内（无中间态）。

## 3. 三层检查

1. **守恒巡检（60s，一条 Lua 原子读五键 stk/pend/dbi/void/so，同 slot）**：清扫后验和==total。total/ic 取自 findPublished 行仅作**首轮预筛**；**确认步（进深对账/白名单前）按主键重读新鲜 total/ic 再判一次**（v2：循环可被漂移风暴拉长至分钟级，列表行陈旧会使两轮确认同源误判，P0-①同类）。
2. **方向性镜像（同轮，两级判据）**：
   - **预筛**：dbi > ic_列表 → **按主键重读新鲜 ic（主库直读）再判**；dbi > ic_新鲜 才告警（幻影确认——Bug A 类"抹掉已提交事务"的唯一签名）。零误报论证：confirm 的 dbi+1 严格晚于其提交的 ic+1，新鲜 ic（在 Lua 读 dbi 之后执行）必然包含 dbi 的全部来源提交 ⟹ 诚实态 dbi ≤ ic_新鲜 恒成立（v2 修复 P0-①：陈旧 ic 使此臂流量期每轮假阳）。M4 读写分离后此重读必须钉主库（B1）。
   - **confirm-lost 臂**：ic − dbi > 0 且清扫后 pend==0 才告警/修复（诚实态 ic−dbi ≤ 活跃在途数恒成立，方向性判据消灭流量期告警永动；此臂 ic 陈旧只会漏报幅度不会假阳，下轮补上）。
3. **T+1 审计（每日）**：`issued_count ↔ COUNT(user_coupon)` 单条 SQL 同快照互证（含相关子查询），DB 内部完整性。

**深对账剥离巡检线程**（v2）：deepReconcile 在独立单线程执行器+队列运行，巡检线程每模板只做"一条 Lua+判定"——否则漂移风暴下 COUNT 串行把循环拉长分钟级，放大一切陈旧窗。

## 4. 极窄域自动修复：白名单 + 分支推导 + 总开关

**白名单（同时满足才自动执行）**：
① 两轮确认漂移（守恒或镜像）且确认步用过新鲜读数；② 模板可领（publish=1 AND approve=1 AND 在领取窗口）；③ 在途已清（REBUILD·FORCE Lua 内 ZCARD 门，原子）；④ DB 账实互证（单条 SQL：issued_count==COUNT）；⑤ **双键 CAS 栅栏**：Lua 内 `GET dbi ≠ dbi_observed` 或 `GET void ≠ void_observed` 即中止——dbi_observed/void_observed 是**与修复参数同批快照读得的 Redis 同键值**（≠目标值 ic！confirm-lost 形态下 dbi_observed 恒<ic 属正常，栅栏封的是"读数与执行之间混入 confirm/terminal cancel"）（v2 澄清：v1 表述可被实现成"dbi≠ic"，会让主治愈路径每轮中止）。

**FORCE 按业务状态推导（而非照抄旧值）**（v2 修复 P0-②）：
- 退役态（so 存在 或 ic≥total）：`stk:=0；dbi:=ic；void:=total−ic`（被 terminal cancel 洗白进 void 的污染在此精确归零；so 保留——余量为 0 不清除）。
- 活跃态：`stk:=max(0, total−ic)；dbi:=ic；void:=0`；若修复后 stk>0 → DEL so（B4 清除协议）。

**只告警永不动手**：账实矛盾（④失败，**首条即升级人工**）；同模板 24h 内≥5 次（升级检查统一在 recordDrift 内执行，任意 action 计数；**REPAIRED/ESCALATE 事件豁免限频**，限频只压制 ALARM 噪音——否则真实修复次数被 1h 限频吞掉使升级阈值失真）；total 被调小；活跃态 void>total−ic；**重新上线未走协议**（publish=1 且 stk==0 且无 so 且 dbi+void==total 且 dbi<total → "剩余量被 void 囚禁"告警）；**同模板 transient 取消率 5 分钟内≥N → 熔断告警**（纯 DB 劣化段 N1 的检测者，该段守恒全程绿灯）；DEFERRED 连续≥30 轮（"在途永不清"本身可能是泄漏或 TTL 配错）。

**总开关** `coupon.reconcile.auto-repair.enabled`（默认开）作用面（v2 明确）：**只拦变更写**=FORCE 的 SET 与其 clearSoldout；守恒巡检、镜像告警、守卫④只读互证、drift_log 落表、**-7 懒重建（领券可用性路径，不是修复）全部继续**；开关翻转本身落一条 drift_log(SWITCH_ON/OFF)。

## 5. 脚本与代码整合（净简化）

- Lua 六段→五段：DEDUCT（去 INCR issued，键 8→7）；CANCEL 两变体（transient[含 so 检测与 stk 存在门]/terminal±stk 退休）；CONFIRM 不变；CONSERVATION（原 IDENTITY：清扫+读五键）；REBUILD(NX|FORCE)（合并原 LAZY_REBUILD+REPAIR，FORCE 含 ZCARD 门+双键栅栏+分支推导）。
- 删除 `issued` 键族；V4 迁移：**①`KEY idx_tpl (template_id)`**（守卫④/T+1 的 COUNT 现为全表扫，既是性能尖峰也是秒级陈旧窗来源）；**②stock_drift_log 适配**：加 redis_void 列与修复后读数组（或规定修复=两行：DRIFT_CONFIRMED 前读数+REPAIRED 后读数），redis_issued 置 NULL 并注废弃，delta 注释随守恒式改写。
- recordDrift 修正：lastLogAt 置位移到 insert 成功之后（DB 故障期不烧审计窗）。
- 巡检对离开 findPublished 的模板清理 driftRounds；driftRounds 计数改由 deep 最终结果驱动（REPAIRED/自愈才清零，推迟后下一轮即重试而非隔两轮）。
- 运行手册：遗留 `c:issued:*` 维护窗 SCAN+UNLINK；NTP 建议；Redis maxmemory-policy=noeviction 钉死（日限键可逐出=超发方向漏洞）；人工调低 stk 必须同时立 so 或先关总开关（否则与自动修复对抗）。

## 6. 先行 Bug 修复（P0，先于范式迁移）

1. **confirm 移出 try / 单独 catch 只记日志放行**：现行 catch(RuntimeException)（ReceiveAppService.java:118）会把 confirm 的 Redis 异常接住并 cancel——抹掉已提交事务的记账、静默且阻断响应，违反 ADR-0008 自身承诺。两变体：命令已执行仅超时→状态一致无需动作；未抵达→令牌留 pend，180s 清扫后守恒暴露、FORCE 治愈。
2. **catch(RuntimeException) 先查 uk_idem 再定动作**：探针**命中**→只记日志式 confirm+按幂等命中返回成功；**未命中**→cancel；**探针自身异常→不 cancel，抛原异常**（v2 补：断连风暴大概率同时打断探针，惯性"探针异常→cancel"恰在最需要的场景失效；令牌交 180s 清扫由守恒兜底，残余窗口=「断连且 COMMIT 落盘晚于探针一个 RTT」的联合概率，IO 尖刺时秒级，后果有界 1 张、DB 硬顶零超发、镜像 2-3 分钟自愈——§8.2 认账）。
3. **day 键随命令传入**：DeductCommand 增加 day（编排层算一次），cancel 用 cmd.day——修复跨天失败取消扣错日键（超发方向，RedisStockGateway.java:171/194）。
4. **REPAIR 补 Lua 原子门**：由 §4 白名单③⑤承担。

## 7. 理由

- **守恒式 ⊇ 旧恒等式**（三红队状态层穷举无反例 + v1 四评审复核）：崩溃残留、单键污染（含 stk 与 void——经 FORCE 分支推导，两键都可测）、全清库（旧式 0=0 空真关闭）全抓；检验对象从内部记账自洽换成业务量本身。
- **分层修正**：内部账本/业务库存/DB 历史三个正交平面各配一个不变量，故障类不重叠。
- **复杂度净降**：键族 9（issued→void）、Lua 6→5、变体 3→2、重建路径 2→1。旧"cancel 晚于 TTL 清理少卖 1 张"残余由**守恒巡检**治愈（该形态 ic−dbi=0，镜像双臂静默——v2 修正 v1 的错误归因）。
- **拒绝项**：不回容忍带；不采纳 live-token 满流量修复（静默平移反例）；不采纳"清扫时回滚 ul"（崩溃令牌的 DB 可能已提交，回滚会错——ul 自愈走 L3 的"-2 分支 DB 复核"路线）。

## 8. 后果与认账

1. 正面：检测任意时刻可查、零容忍带；售罄尾段振荡环结构性消失（分类售罄+so 短路+FORCE 归零推导）；崩溃残留分钟级暴露、流量间歇自动找回；修复全程落表、可审计、可一键关停。
2. 认账：纯 DB 劣化段（ic<total）的"过闸→超时→回加"满 QPS 环**并未消失**（v2 收窄 v1 过度声明）——由 transient 取消率熔断告警承担检测，熔断动作本身归 M2；不确定提交残余窗口（§6.2）有界且自愈。
3. 认账：REBUILD·NX 全清库形态为近似（误差=未提交在途数，方向虚高、DB 硬顶、镜像收敛）；"精确还原"仅指选择性丢失形态。重新上线的 DEL pend 会牺牲在途用户的限领名额至 ul TTL（7 天，少领方向有界）。
4. 认账：ul 崩溃无回滚（用户 0 券却被 -2 拒绝至 TTL）——L3 既定债，修法=-2 分支按 uk_user_seq 前缀 COUNT 复核自愈，随 M1 实施。
5. 认账：多实例 driftRounds 仍 JVM 本地（仅影响确认节奏不影响正确性，L16）；日限键自愈独立登记。

## 9. 实施阶段

1. **P0**：§6 四项修复+回归测试（无范式变更）。
2. **守恒迁移**：五段脚本、CONSERVATION 巡检+两级镜像+新鲜读数、deep 剥离线程、白名单+分支推导+双键栅栏、总开关、V4（索引+drift_log 适配）、driftRounds 修位、recordDrift 修序、TIME 时钟、测试矩阵重写。
3. **收尾**：删 issued 遗留键（runbook）、勾稽——ADR-0008 横幅、**study/06e 横幅**（恒等式形态被取代）、**docs/README 的 ADR 索引（补漏更的 0007/0008/0009）**、三类 javadoc 重写（与脚本五段化同一提交，防源码陈旧物）、STATUS/ROADMAP、study/06f 讲解。

## 10. 验证计划（测试矩阵）

单测：守恒判定全分支/镜像两臂（含**陈旧 ic 预筛不告警、新鲜 ic 才告警**）/白名单五条件各自缺失拒绝/FORCE 两分支推导/driftRounds 修位/recordDrift 不烧窗。
集成：①2000 并发后五键守恒；②崩溃→清扫→cancel 迟到 no-op→和=total−1→修复 stk 精确回补；③**下线退役守恒回归**（dbi=60,void=40,stk=0 经 offline 路径构造）+**干净售罄修复后 void==0**（P0-②钉死测试：污染 stk→售罄 terminal→FORCE 归零，无无效循环）；④单键逐出四形态（stk/dbi/pend/void 各一）；⑤stk 改低检测；⑥全清库 REBUILD·NX 断言和==total（非逐键精确）；⑦P0 四 Bug 各一回归；⑧栅栏两用例：a 混入 confirm/terminal→中止，b 纯 confirm-lost（dbi_obs=60,ic=61）→放行且 dbi 终值 61；⑨镜像：幻影（新鲜 ic 下）立即告警/confirm-lost 修复；⑩总开关：关停后巡检/告警/落表/-7 全继续、仅 SET 停；⑪重新上线囚禁告警；⑫多实例时钟无关性（TIME 权威）；⑬transient 取消率熔断告警。

---

## 修订记录

- **v3（2026-09-12，面试答辩后）**：四位面试官角色 33 题质询（详见《守恒方案答辩-架构师面试问答-2026-09-12.md》第 4 部分完整清单），以下为具约束力的增补条款：
  1. **一致性主张（按对象）**：总量零超发=MySQL 单行条件更新对已提交事务的不变性质，与 Redis 无关（Redis 全失效退化为行锁串行，安全性质不变）；五键转移=同分片 Lua 串行 ⟹ **任期内线性一致，跨任期（主从切换丢整效果集）最终一致且破缺有界可检测**；总限领=uk_user_seq 同级硬保证；**日限=仅 Redis 计数，主从切换下概率性超领，敞口=丢失的计数数**——ROADMAP 红线照此改写。
  2. **适用边界与失效条件（本方案保质期）**：M3 分桶触发时守恒域变"模板×桶"，巡检/FORCE/转移表需 ADR 级重设计（触发即新开 ADR）；M5 异步发放启用前在途 TTL 必须重锚定为"MQ 端到端 P99+重试预算"（行锁锚点作废）；binlog 驱动 dbi 为已论证等价演进选项。
  3. **吞吐口径**：撤回 ADR-0006"~2 万 QPS"声明。有效发放上限 = min(Redis 单分片, DB 单行提交速率)；单行 CAS 是已知吞吐漏斗，分段增补（DB 写放大 1/N）vs 全量配对的对比表入 M3 压测决策项；压测前不宣称任何 QPS 数。
  4. **TTL 公式化+启动校验**：TTL > 排队时延 + innodb_lock_wait_timeout（真实默认 50s）+ 事务余量，180s=工程估计（M3 校准）；启动期读锁等待参数，2×锁等待+余量 > TTL 则告警并强制提升。
  5. **在途集合上界与自伤控制**：常态上界≈并发在途（QPS×DB 时延）；退化上界=QPS×TTL（5000×180s≈90 万成员/100MB）——清扫必须分批（每轮限 N 条），ZCARD 超常态 10 倍即告警。
  6. **检查器自身存活（"每层检查必须自带心跳，静默=最危险故障"）**：巡检每轮成功写心跳键（TTL=3×周期，过期即一级告警）；dailyAudit 成功也落当日摘要，连续 2 天无摘要=告警。
  7. **告警分级与过渡通道**：立即叫醒级（GUARD_FAILED/幻影确认/心跳过期）、工单级（长期 DEFERRED/囚禁/内存水位）、周报级（限频后常规漂移）；M2 通道建成前，最小可行告警提前实施（每 10 分钟检查 drift_log 近 1h 有无 REPAIRED/ESCALATE，有即通知）；风暴聚合：单轮确认漂移模板数>5 或>1% 发全局事件 P1 并抑制单模板告警；限频/升级计数全局化至 Redis 键；drift_log 在线 90 天+按月归档。
  8. **自动修复上线序列**：开关落 Redis 全局键（分钟级生效、任一实例关=全员不修）；**影子模式 1-2 周**（推导值落表不执行）后才开真修；连续 2 次 FORCE 后仍漂移→自动熔断修复环；管理端点"从 drift_log 前读数一键恢复"；人工变更唯一入口=维护标记键 c:maint:{tpl}（巡检见键豁免并注明）；drift_log 增处置结果回填字段（真/误报/人工）。
  9. **容量预算**（noeviction 的代价面）：幂等键≈QPS×600s×100B（5000QPS≈300MB，内存第一旋钮）、ul 键=活跃模板×领券用户×7 天、状态键=5 个/模板永久（退役模板补 EXPIRE 7 天）；水位 70%/85% 告警为 M2 第一批；满载预案=批量删幂等键（安全）+扩容脚本，日限键不可牺牲。
  10. **短流修复协议**：持续流量下修复饥饿时，运维可选"SET so 制造秒级静默窗口→FORCE→按 B4 清除"（拿可用性换确定性修复窗口，默认低峰自动）；总开关关闭期间挂起破缺带未结/已结状态。
  11. **收敛 SLA 表**（检测上界×修复上界×期间后果）与读己之写声明（单库期天然成立；M4 后领券响应读主、券包查从=毫秒级最终一致，读己之写需显式路由）随实施成文。
  12. **不变/可砍（投资回报）**：不可砍=守恒巡检+方向镜像（整体平移仅镜像能抓、崩溃残留仅令牌+清扫能精确归位）；第一刀=关自动修复（MTTR 分钟→值班小时）；第二刀=简化 T+1。定位声明：本方案是体验与运营效率机制，**不是资损保险**（资损红线由 DB 双防线独扛）。
  13. **变更纪律**：新增转移/键的检查单（同步六处：Lua/巡检/镜像/FORCE 推导/测试矩阵/drift_log 列）；"新键必须说出业务事件"门禁进化为 CI 键注册表对账；设计冻结三条件=状态空间穷举论证+测试矩阵全绿+故障演练实操（杀进程/清库/逐出）。
  14. **2PC/TCC/Saga 对比成文**：2PC 对 Redis+MySQL 结构性不存在（无预备接口）；本方案=TCC 去 TM（超时默认决策+审计替代 TM 日志）；cancel 非逆性=Saga 语义补偿、显式在途补可观测隔离；协议选择=资源管理器数量×吞吐需求。
  15. **排期纪律**：P0 四 Bug 当天热修独立提交（不等本 ADR 定稿）；实施顺序修正为 P0 热修→L2 券过期→L5 锁定超时→本 ADR 迁移；Redis 整体不可用的降级开关（限流到 DB 单行水位，非全量直扣）与哨兵排序入债表（先降级开关）。

- **v2（2026-09-12）**：4 视角红队评审（转移边界/检查层/P0 修复/场景矩阵，74 次取证）后折入：P0-① 镜像陈旧 ic 假阳（预筛+新鲜重读，§3.2）；P0-② terminal 洗白 void（售罄分支不 retire stk + FORCE 业务状态推导，§2/§4）；P1×7（stk 存在门/appendStock 方向修正/栅栏比较对象与 void 双键/探针失败分支/退役短路标志/升级统一+限频豁免/deep 剥离线程）；P2 若干（TIME 时钟权威/开关作用面/V4 drift_log 适配/囚禁与熔断两类新告警/N1 声明收窄/文档勾稽补全）。确认站得住：转移表常规边守恒精确、崩溃/逐出/迟到/双实例全收敛、DB 双防线零超发兜底、P0 行号引用无误。
