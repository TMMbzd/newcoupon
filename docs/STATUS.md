# 系统状态总账（唯一权威状态来源）

> **维护纪律**：每次代码提交后同步更新本文件；与代码冲突时以代码为准、当场修本文件。
> 创建：2026-09-05；2026-09-07 经六视角全面审计修正（26 agent，20 项确认发现全部修复，详见 §5）。
> 测试基线：`mvn clean test` 全绿（单测+Testcontainers 集成——**必须 clean**：Maven 增量编译只看时间戳，删方法后旧 .class 运行时才炸，增量编译假绿）。**P1 已收口（2026-09-16）→ P2b 库存批次分片已收口（2026-09-19）**——当前 159 测试（154 全绿 + 5 红灯合同门控类 make red-contract 单独跑亦全绿）；下一项 P2c 模板缓存。

---

## 1. 能力矩阵（当前实现状态）

图例：✅ 最终形态（本路线图范围内不再大改）｜🔶 阶段性实现（有登记的债，见 §3）｜❌ 未实现

| 能力 | 状态 | 现状一句话 | 债编号 |
|---|---|---|---|
| 建券（模板/审批/发布） | ✅ | 双状态机+13条不变量+CAS发布；审批流省略（单人环境，ADR-0005） | L1 |
| 分布式 ID | ✅ | **雪花（ADR-0010 批次二）**：64位/时钟回拨借未来策略/workerId 配置；四个插入路径全换（券/占用单/事件/任务表），模板表保留自增（小配置表不分片） | — |
| 分库分表 | ✅ | **路由先行（ADR-0010 批次二）**：V6 迁移三表×64 物理分表（user_id%64，索引随迁）；ShardingSphere 5.5 驱动模式（YAML 代码内模板动态生成；绑定表；!SINGLE 单表规则——5.5 必须，实测踩坑）；Flyway 手工时序（先建表后建分片数据源，Boot 自动装配有循环依赖）；扩物理库=改配置搬数据，SQL 零改动 | — |
| 领券-库存闸门 | ✅ | **P2b 桶化+世代化（2026-09-19）**：Redis Lua 五重校验前置（含 c:fz 冻结短路 -11→FROZEN 5002）；遍历 c:act 注册表扣减（路由亲和 hash(userId)%16 优先，空桶顺位切换）；令牌固化 (桶,世代)；-10 全桶耗尽→开桶协议（单飞+尾量+差额恢复+复用槽位）；-7→ENSURE 按 DB 行灾后重建 | — |
| 领券-DB 双防线 | ✅ | **P2b 桶行版（A 簇红线落位）**：每笔事务 UPDATE stock_bucket used+1 WHERE used<quota AND status=0——热行从模板行分散到 16 桶行（312 TPS/行<400 饱和），强度不降；issued_count 冻结为迁移快照（V10 回填），真值=SUM(used) | — |
| 领券-通用限领 N | ✅ | seq 序号 + uk_user_seq 唯一索引 + **计数自愈（L3，2026-09-15）**：撞索引时以 COUNT（限领判断）/MAX(seq)（序号自愈）双读数分流——失忆→透明重试一层，真达限→正确拒绝；空洞特征签名落 UL_HOLE 漂移告警 | — |
| 领券-幂等 | ✅ | 入口 Redis（EXISTS→-8→DB 确认）；DB uk_idem 终审 | — |
| 领券-失败补偿 | ✅ | 取消记账三变体（A1' 分类先行：售罄/下线钉零、瞬时回加）；令牌单次使用=幂等根本保障；kill -9 残余靠对账 | — |
| 售罄治理（A1'+B4） | ✅ | 售罄钉零（断 N1 自持环）+ `c:so:{tpl}` 标志 Lua 首行短路 + 显式清除协议 | L17 |
| 库存对账/重建 | ✅ | **守恒式验账（ADR-0009，2026-09-16 迁移）**：守恒式 `stk+pend+dbi+void≡total`（五键读数，四状态分区——issued 键族删除）；60s 守恒巡检两轮确认（**P2b E2 两段式**：Σ全世代四键≡c:alloc + c:alloc≡total——c:gens 全世代集合防重开丢账）+方向性镜像（**P2b 换锚 E3c**：幻影臂 dbi>SUM(used)/confirm-lost 臂方向判据；deep 守卫 COUNT≠SUM(used)——同事务零读偏斜；分代 FORCE 推导锚=该代 used。**2e 欠项（2026-09-21 复核标注）**：E3b 分代配对与 void/dbi 单调差分未实现（现仅 E3c 全局口径，平移定位弱化——资损仍被 DB 桶行封顶）；冻结牌与 Lua 短路已就位但自动置位/解冻三通道未接；巡检仍为两轮 60s 确认（三步当场确认在 2e））+ deep 独立线程白名单修复（ZCARD 门+双键栅栏+业务状态分支推导+影子模式默认）；心跳/风暴聚合/transient 熔断计数/维护豁免/连续 FORCE 熔断；T+1 审计+占用单×券状态交叉核对（L4） | L16 |
| 用券三段（锁/核销/释放） | ✅ | 占用单 usage_no 幂等锚点；已核销不可释放（资损红线）；释放幂等 | — |
| 券包查询 | ✅ | 按用户+状态 | — |
| 券过期处理 | ✅ | **L2 已实施（2026-09-12）**：核销/锁定 SQL 谓词防线（零窗口）+ V5 过期任务表小批量扫描（分钟级状态收敛，退避无失败态+死信）+ 券包惰性判断（不写库）+ EXPIRE_ON_LOCK 收场（占用单第三终态"过期关闭"）+ T+1 漏注册审计；实时触发层留 M5 | — |
| **锁定超时兜底** | ✅ | **L5 已实施（2026-09-15）**：锁券同事务注册超时任务（deadline=占用时间+窗口，默认30min可配置）→ 到窗订单仍沉默则收场：占用单→第四终态"超时关闭(4)"、券→(未过期?待使用:已过期)、LOCK_TIMEOUT(7)事件含订单号；迟到核销 4006 拒绝、迟到释放幂等返回真实状态；双通道扫描+Redis锁；两道守卫（时间不可见+CAS状态裁决）；T+1锁龄审计（idx_occupy） | — |
| 模板缓存 | ❌ | 每次领券 1 次 DB 主键查询（无本地缓存） | L6 |
| 监控/告警/metrics | ❌ | 仅日志（traceId+错误分级），无 metrics、无告警通道 | L7 |
| **调度平台** | ✅ | **XXL-JOB 2.4（ADR-0010 批次一，2026-09-12）**：六个任务双通道（@XxlJob 生产通道+@Scheduled 降级通道，RedisScanLock 仲裁）——库存巡检/库存日审/过期扫描/过期日审/超时扫描/超时审计；扫描类分片广播就绪（shard%16）；执行器条件装配（xxl.job.enabled，测试默认关） | — |
| CI/CD | ❌ | 无流水线、无质量门禁、无制品管理 | L8 |
| 部署形态 | ❌ | 仅 dev compose（MySQL+Redis+应用本机起）；无生产形态 | L8 |
| 压测/QPS 验证 | 🔶 | P2b 已有实测 QPS 报告（P2bQpsReportTest：2000 并发/19.3s=104 QPS，WSL2 同机=形状验证下限非容量）；阶梯加压/拐点/soak/容量评估表仍归 P4 | L9 |
| 分库分表 | ❌ | user_coupon 单表 | L10 |
| MQ/异步链路 | ❌ | 无（同步落库） | — |
| 热点分桶 | ✅ | **P2b 已实施（2026-09-19）**：三层结构（模板 allocated/stock_bucket 桶行/Redis 分代键 c:stk:{tpl}:{b}:{g}）+c:act/c:alloc/c:gens/c:fz 模板级键；开桶 CAS 单调递增；V10 存量回填+断言；故障注入三连（跨代迟到/夹缝自愈/惊群单飞）全绿；hash tag=tpl（Cluster 兼容注记在方案） | — |
| 事件表查询/统计 | ❌ | 只写不读（"计"域未开始） | L11 |
| 登录态/鉴权 | ❌ | userId 由请求携带（登记的阶段简化） | L12 |
| **领取入口风控（IP 频控）** | ✅ | **最小版已实施（2026-09-16，docs/11 §6）**：RiskControlFilter 只拦 POST /receive；IpRateGate 固定窗口（INCR+EXPIRE，默认 60s/30 次，可配置）；代理头提 IP（XFF 首跳→X-Real-IP→remoteAddr）；429+7001（7xxx 风控域新段）；**fail-open**（频控=可用性防线，Redis 故障不误杀全量；资损另有独立防线）；设备维度头透传位已留（M6 人机识别接上） | — |

## 2. 演进史与代码变更记录（防考古）

### 阶段 0（建券→领券→用券，纯 DB）
`4a8df8a`→`b77f433/ff6cd06/9731011`→`afbdaa0/06e6247/19966f6/7b496f2`→`277c215/7a96225/14497cc/b6c9548`
- 建立四层架构、状态机、占用单、三段用券；**三个验收全过**。

### 阶段 1（Redis 闸门，`e49a785`+`ae83f07`+`4d16b49`）
**改造了**：`ReceiveAppService`（两次：①接入闸门+补偿；②幂等改 Redis 优先——评审修正）；`CouponIssueTxService`（收窄为双防线扣账+落券+事件，模板读取前移）；`TemplateAppService`（发布初始化 Redis 额度）；V2 迁移（限领通用化）。
**新增了**：RedisStockGateway、StockReconcileService、阶段1集成验收。
**没动+为什么不用动**：`TradeTxService`/`TradeAppService`（用券三段）——不涉及库存闸门，usage_no 幂等锚点与 Redis 无关；建券/发布 Controller。
**被取代/废弃**：`uk_user_template` 唯一索引→`uk_user_seq`；ADR-0004"限领1张"条款→ADR-0006 通用限领；**"回滚即补偿"特性消失**（Redis 不参与 DB 事务，改为补偿脚本）。
**语义变化（登记）**：并发同 requestId 从"排队后全成功"变为"-8+1001 提示重试"。

### ADR-0009 守恒式迁移（2026-09-16 实施，M1-④ 收口）
红灯双实锤（stk 改低/全清库——旧恒等式全静默）→ 迁移 → 145 全绿。**范式变化**：issued 键族删除；c:void 新增（第四态"已撤销"：售罄/下线退役）；检验对象从"Redis 内部记账自洽"换成"业务量守恒"。
**五段 Lua**：DEDUCT（无 issued）/CANCEL 三态（transient 含 so 检测内部改终态；sold_out=void+1 不动 stk——污染留给 FORCE；offline=void 吸收存量）/CONFIRM 不变/CONSERVATION（清扫+读五键）/REBUILD NX|FORCE|shadow 合并（ZCARD 门+双键栅栏+retired/活跃分支推导）。
**巡检重写**：守恒两轮确认+方向性镜像（幻影臂陈旧 ic 不告警——P0-① 修复；confirm-lost 臂 pend==0 才动——告警永动解药）；deep 独立单线程（v2 P1）；白名单五条件；只告警清单（活跃态才检 void 异常——实施期修正②）；心跳键/风暴聚合/transient 5min 计数（M2 熔断动作）/维护豁免/连续 FORCE≥2 熔断修复环；影子模式默认（v3-8，生产观察后切 force）。
**V8**：idx_tpl×64（守卫 COUNT 从全表扫到索引）+ drift_log 加 redis_void/disposition 列。**A4 作废口径**（ADR 实施增补）：作废不回补（dbi 是"已发放"非"当前有效"；回补=超发）、必落 INVALIDATED 事件（财务核销）、M6 工具按此实现。
**实施后多方评审 8 条全修**（守卫④/日审 TOCTOU 新鲜重读、NX 精确稳态式（dbi:=ic−pend——迟到 confirm 竞态归 confirm-lost 自愈方向）、升级检查归位 recordDrift、publish 落 RESET 审计、心跳检查先观测后续命、修复开关在线路径 c:reconcile:mode）。**实施期三修正**：①跨语言契约 Bug 二进宫（soldout vs sold_out——void 静默失效，测试当场暴露；契约注释写死）②"不可能态"守卫误拦退役修复（改活跃态才检）③shadow 不得 clearSoldout（单测抓）。
**认账**：REBUILD·NX 全清库形态近似（§8.3——清库风暴测试从"精确断言"改为"守恒 FORCE 收敛后精确"）；领取重试 ul 自愈的 L3 已于 2026-09-15 前置实施。

### 安全底册+IP 频控最小版（2026-09-16，用户裁定"文档完整+先做必须且重要的"）
docs/11-安全与风控全景.md：五层防线全景（每层大厂做法/我们状态四档/将来最小路径/触发条件总表 §7）——现在不做的（设备指纹/行为模型/名单/渗透/脱敏/等保）全部留档供将来决策。**落地**：IpRateGate（固定窗口 fail-open）+RiskControlFilter（只拦 /receive，代理头提 IP，429+7001 新风控码段）+配置 coupon.risk.ip-rate.*。测试 IpRateGateIntegrationTest 4 场景（阈值/独立/键 TTL/fail-open；拒绝断言用预填双窗口计数消除与窗口边界的竞跑）。

### 路线图对标大厂审查（2026-09-16，用户全批落地）
8 领域×2 位大厂专家身份（16 审查+8 质证，Workflow 24 agent）审查 ROADMAP/STATUS/源码：75 条提交→51 条确认（high 24）/24 驳回。报告：docs/审查-路线图对标大厂-2026-09-16.md。**落地**：①A1 六 CAS 谓词+锁券查询带 user_id（热路径 64 表广播→单表；usageNo 侧广播登记 L23）②A2 docker/redis/redis.conf（noeviction+AOF+慢日志）落 compose ③ROADMAP v3：M2 升级为监控+止血+降级、对账分级 SLA（§0.5）、M3 拐点锚+造数+染色、M4 跨库边界+比对工具+对账 consumer、M5"钱的后半程"（预算实体/分摊落账/退款冲销/人群包）、M6 变更治理+券码裁决、流量治理 G 组（热Key探测/大Key门禁/L9 数字修正）④新债 L23（usageNo 广播）/L24（退款三矛盾）/L25（券码悬置）⑤演练矩阵+资损注入/压测中故障注入两行。

### L3+L4 计数自愈与交叉核对（2026-09-15 实施，docs/L3L4 技术方案）
工序 v2 全案：4 红灯实锤（失忆后合法领取被永久拒绝的运行时复现 + 交叉核对缺失的双向注入）→ 方案批准 → 实施 → **多方评审（Workflow 四视角×27 agent，13 条发现全部核实为真）→ 四根因修复 → 135 全绿**。
**新增了**：countByUserAndTemplate / maxSeqByUserAndTemplate（同走 uk_user_seq 最左前缀）、selectOccupying / selectOccupyingByCouponId / selectLocked、RedisStockGateway.setUserCount（无条件 SET+7天TTL）、ReceiveAppService 的 driftLog 依赖与 recordUlHoleQuietly。
**改造了**：ReceiveAppService（receive→doReceive(retryOnce) 透明重试一层；classifyAfterRollback 双读数分流：COUNT≥限领→3002、未达限→自愈 MAX(seq)+重试、重试仍撞→UL_HOLE 漂移+DEGRADED）；StockReconcileService.dailyAudit（末尾交叉核对：A 侧占用超10分钟+截断前置早退+候选点查复核+不限频落表）。
**评审根因修复**：A·seq 空洞死循环（COUNT→MAX(seq)）；B·L4 假阳性（年龄+截断早退+点查复核）；C·限频吞告警（专用落表）；D·SET 竞态（DEGRADED 诚实化，登记不加锁）。测试基线 135（+L3 三场景含空洞回归/L4 三场景/单测+2）。

### L5 锁定超时兜底（2026-09-15 实施，docs/L5 技术方案）
静态穷举证明的 Bug（方案 §2）：券 LOCKED 三出口——核销/释放要"有人来调"、锁定中过期要"时间到"——订单沉默+券未过期=三出口同关=永久悬挂。工序 v2 首案（Bug检出→文档先行→批准→实施）。
**新增了**：V7 coupon_release_task（64 分表；**评审修正：唯一键 uk_usage_no 而非 uk_coupon——一张券一生可签多份合同**，任务主体=合同）、CouponReleaseTaskPO/Mapper/Gateway、CouponReleaseService（扫描器+T+1锁龄审计，审计直查 usage 侧配 V7 idx_occupy 索引——方案 D6 原走券表的简化修正）、UsageStatus.TIMEOUT_CLOSED(4)、EventType.LOCK_TIMEOUT(7)、CouponTransition.LOCK_TIMEOUT、CouponEvent.lockTimeout（event_no=LKT:usageNo）、casTimeoutClose（券 IF 分流同 casRelease）/casTimeoutByUsageNo、ErrorCode 4006、coupon.trade.lock-timeout-minutes（默认30）、调度池 3→4。
**改造了**：TradeTxService（lock 同事务注册任务；+timeoutClose 事务方法——收场三件事一个事务，对照 F1 教训）、TradeAppService（lock 快检只认占用中=F2；release 终态幂等+真实状态=L15）、CouponExpireService（F1 补刀分支：券已终态但占用单仍0→幂等补关+补事件）、ShardingDataSourceConfig（第四张绑定表）。
**测试**：LockTimeoutIntegrationTest T1-T10 + T11（T1 打桩跑红后转绿；两道守卫/竞态两向/迟到核销释放/退避死信/F1/F2）。测试基座容器补 TZ=Asia/Shanghai（NOW() 与 JVM 对齐——"到点即扫"断言免疫 8h 错位）。**实施后红队评审**：核心收场事务交错推演无错账；外围 3 项当场修复——死信审计复活断裂（INSERT IGNORE→ON DUPLICATE 复活，回归 T11）、valid_end 边界抢跑误 finish（CAS 输家重读券分流）、退避 off-by-one（SET 左到右求值，第 7 次就死信→改 >=8）。128 测试全绿。运维步骤：XXL 控制台注册 couponReleaseScanJob（分片广播）/couponReleaseAuditJob。

### 批次二：雪花 ID + 分片路由先行（2026-09-13，ADR-0010）
**新增了**：SnowflakeIdGenerator（借未来时钟策略——WSL2 实测时钟回拨 4.3s 逼出来的设计：唯一/单调优先于时钟真实性，ERROR 留痕；Leaf 号段 M6 升级路径）、ShardingDataSourceConfig（YAML 模板动态生成：databaseName 从 URL 解析——5.5 用它定位单表元数据；!SINGLE 规则必须显式声明——5.5 行为变更，不写则所有无规则表"不存在"）、V6 迁移（192 张物理分表+数据搬迁+原表删除）、ShardedIntegrationTestSupport 基座（MySQL 容器不走 ServiceConnection——分片驱动不认 spring.datasource；每类自带 DynamicPropertySource——本 Spring 版本不从父类发现，6.2 才支持）。
**改造了**：四个 PO 主键 AUTO→INPUT+雪花；令牌时间权威改 Redis 服务端 TIME（deduct 打分与清扫阈值同源——WSL2 时钟漂移的第二处根治）；Flyway 改代码内手工触发（自动装配与自定义数据源循环依赖）。
**依赖新增**：shardingsphere-jdbc 5.5.0（排除 test-util——5.5.0 发布缺陷，Central 404）、jaxb-runtime 2.3.9（javax 版，JDK11+ 无内置 JAXB 且 4.x 是 jakarta 不兼容——两级踩坑）。
**认账**：测试容器 URL 时区须 Asia/Shanghai（UTC 时 DATETIME 读回 +8h）；外层 DriverManagerDataSource 无池（真实池在 yaml ds0 的 Hikari）。
115 测试全绿（+5 雪花单测）。

### 终局技术栈定版（ADR-0010，2026-09-12，用户指令"一开始定好技术"）
两类决策两刀切：**不可逆类（数据层：ID/分片路由/schema）立即全部定死**——雪花 ID+ShardingSphere 16库×64表逻辑分片（1物理库路由先行）为批次二下一工作项；**部署形态类终局定死+批次表写死**（XXL-JOB✅/RocketMQ M5/Redis Cluster M4/K8s M7/微服务 M8 触发条件§3 写死/监控 M2）。批次一 XXL-JOB 同日落地：compose 起 xxl-job-admin、xxl-job-core 执行器条件装配、四任务双通道+锁仲裁、过期扫描分片感知（scanDueShard）。110 测试全绿。

### L2 券过期全链路（2026-09-12 实施）
技术方案：docs/L2-券过期全链路-复现与技术方案-2026-09-12.md（docs/06 v3 的 M1 裁剪：三层取二，谓词+任务表扫描，实时触发留 M5）。
**新增了**：V5 coupon_expire_task（窄表待办队列，失败=退避不设失败态，8 次死信）、CouponExpireService（扫描器+T+1 审计）、CouponExpireTaskGateway/Mapper/PO、CouponEvent.expired、UsageStatus.EXPIRED(3,"过期关闭")第三终结态、ErrorCode 4005、调度池 2→3。
**改造了**：UserCouponMapper.casConfirm 补 `valid_end > NOW()` 谓词（资损防线，复现③根因）；TradeAppService.confirm 分类（锁定期间过期→TradeTxService.expireOnLock 收场→4005）；myCoupons 惰性判断（不写库）；CouponIssueTxService.issue 同事务注册任务。
**测试**：ExpiryBugReproductionTest 断言翻转+扩充为 ExpiryIntegrationTest 8 场景（含幂等重跑/控制组）。107 全绿。讲解：study/07a（重写版，围绕真实代码）。

### P0 四 Bug 热修（2026-09-12，独立提交，ADR-0009 §6）
三场外部评审（讨论01七核查/答辩33问）从现行代码挖出的四个正确性 Bug，与范式无关、当天热修：
①confirm 被自身 catch 抹账（DB 已提交却回滚记账，三层对账全盲+违反 ADR-0008 承诺）→ confirmQuietly 单独兜底；
②不确定提交盲补偿（COMMIT 断连赌"没提交"）→ recoverUncertainOutcome 探针（uk_idem）定案三岔口+探针失败不动；
③跨天失败取消扣错日键（日限无 DB 防线，超发方向）→ day 随 DeductCommand 固化；
④对账修复 TOCTOU（守卫①检查与 REPAIR 执行隔着秒级 COUNT）→ 在途门下沉 REPAIR Lua 原子拦截，false=推迟。
附带：cancel 二次故障防线（cancelQuietly——Redis 同挂时保业务语义，令牌交清扫）。99 测试全绿（+7 回归）。讲解：study/06f。

### 对账范式升级（外部方案评审综合，ADR-0008，2026-09-09）
用户裁定五决策点（采纳 A4' 恒等式 / 采纳 A3' Redis 记账 / 保留窄域自动重建 / 采纳 B4 售罄标志 / 维持 M3 压测分桶）后实施。
**改造了**：`RedisStockGateway`（六脚本：deduct 内联复式记账+售罄短路、confirm/cancel 令牌配对、identity 原子快照、条件 SET 懒重建、repair 三本账重置）；`ReceiveAppService`（分类先行+取消三变体+confirm 记账+-7 退避）；`StockReconcileService`（容忍带整体替换为恒等式两层检查+三守卫+T+1 审计）。
**新增了**：V3 `stock_drift_log` 表（漂移留痕/升级依据）、`StockDriftLogGateway`、`UserCouponRepository.countByTemplate`（第二不变式互证）、调度线程池 size=2。
**删除了**：`ReceiveInFlightCounter`（JVM 在途计数器——被 pend ZSET 取代，多实例失配问题随之消失）；ADR-0007 容忍带三件套（RESIDUAL_TOLERANCE/二次复核/方向不对称——期望值从"0+容差"变为"精确0"）。
**实施中抓到**：跨语言契约 Bug（Java `sold_out` vs Lua `soldout` 失配落 INCR 回加）——集成验收⑨钉零断言当场暴露；教训入 ADR-0008。

## 3. 遗留债登记簿

| # | 债 | 风险等级 | 处置计划 | 触发条件 |
|---|---|---|---|---|
| L1 | 审批流省略（创建即 APPROVED） | 资损敞口（多人运营前必须补） | M6 中台化 | 第二个配券账号出现 / M6 |
| L2 | ~~券过期未实现~~ **已实施（2026-09-12）**：谓词防线+任务表扫描+EXPIRE_ON_LOCK+惰性判断+T+1审计；实时触发层留 M5 | 已清 | — |
| L3 | ~~用户计数（ul）无自愈~~ **已实施（2026-09-15）**：撞 uk_user_seq 时 COUNT（限领判断）/MAX(seq)（自愈值）双读数——失忆透明重试、真达限正确拒绝、空洞签名 UL_HOLE 告警；多方评审修掉"COUNT 自愈遇空洞死循环"（§6 根因A） | 已清 | — |
| L4 | ~~对账仅库存维度~~ **已实施（2026-09-15，随 L3 同批）**：日审加占用单×券状态双向交叉核对（三重防假阳性）；登记未修：投影列收窄、findById 广播（均低频路径） | 已清 | — |
| L5 | ~~锁定超时兜底未实现~~ **已实施（2026-09-15）**：超时任务表+双通道扫描+第四终态+4006/幂等释放+T+1审计；F1（expire扫描半收场）、F2（lock快检对终态谎报）随本项修复 | 已清 | — |
| L6 | 模板无缓存 | 性能（每请求1次DB读） | **M3**（压测确认瓶颈后实施，与 ROADMAP M3-② 一致） | M3 压测 |
| L7 | 无 metrics/告警 | 运维盲飞 | M2 | 无条件 |
| L8 | 无 CI/CD、无生产部署形态 | 工程化缺口 | M2 | 无条件 |
| L9 | 无压测、无分桶（**2026-09-17 升级为必做**：DB 热行 ~400 TPS 饱和 vs 背景 5000 QPS——数学已定不等压测；方案=Redis 分桶+DB 批次分片（守恒式兼容两层验证）） | 5000 QPS 下 DB 热行瓶颈 | **M3 必做** | 无条件 |
| L10 | user_coupon 单表 | 容量（千万级将触顶） | M4 | M4 |
| L11 | "计"域（统计/报表/结算）未开始 | 功能缺口 | M5 | M5 |
| L12 | 无登录态/鉴权 | 安全（阶段简化，登记于 ADR） | M6 | M6 |
| L13 | ~~领取窗口竞态~~ **被 L2 吸收（2026-09-12 技术方案 D8 裁决）**：竞态产物"出生即死券"由过期任务表下一轮扫描自动收尾，不为它增列；产品要求领取时刻严格性再立项 | 已清 | — |
| L14 | Redis key 结构偏离 docs/10 §10.2 裁决（实现=String 分 key；规范=Hash+field） | 规范矛盾 | 已裁决：**实现为准**，docs/10 §10.2 更新为 String 方案（每 key 职责单一/分桶友好，Hash 备选） | 本次审计 |
| L15 | ~~release 幂等分支硬编码返回"待使用"~~ **已清偿（2026-09-15 随 L5）**：终态合同（已释放/过期关闭/超时关闭）幂等成功且返回券**真实状态**（fresh 快照） | 已清 | — |
| L16 | ~~多实例无分布式锁~~ **重复执行半边已修（2026-09-12）**：六个调度入口（库存巡检/库存日审/过期扫描/过期日审/超时扫描/超时审计）接入 RedisScanLock（SET NX PX 抢锁+Lua 比对释放，TTL 兜底崩溃自愈）——正确性本由幂等 CAS 保证，锁消重复扫描负载。**剩余**：①全量扫描无分页（M4）；②driftRounds 仍 JVM 本地（仅影响确认节奏不影响正确性）；③**分片广播启用前锁键须按分片号拆**——两个扫描任务的锁现为全局单键（scanlock:0 写死），广播下抢不到锁的执行器在算分片之前即退出、吞吐≈单机（任务不丢，抖动变慢）；改法=先算分片再锁 `scanlock:{index}` 每片一把（M2 双实例演练清单第一项） | 单实例无害；多实例已互斥 | ③M2（演练）；①②M4 | M2/M4 |
| L17 | 运营闭环缺口：模板追加库存 appendStock（需同步回补 Redis **并 DEL 售罄标志——B4 清除协议，漏则模板永久假死**）、模板下线 OFFLINE 均未实现 | 运营功能缺口 | M6（运营工具域） | M6 |
| L18 | **拆债（2026-09-16 大厂审查）**：限流（可用性维度）——M3 最小限流（单机 Bucket4j/Resilience4j）+M6 配额；**风控拦截（资损维度）**——~~M2 最小~~ **IP 频控最小版已提前落地（2026-09-16，docs/11 §6：RateGate+Filter）**，M6 完整（人机识别/名单/规则引擎） | 大流量核心链路裸奔+刷子无前置拦截（IP 维度已挡） | M3（限流）/M6（风控完整） | M3/M6 |
| L19 | Spring Boot 3.3.5 已过 OSS 支持期；无依赖漏洞扫描 | 安全 | M2（升级+OWASP 插件入流水线） | M2 |
| L20 | 敏感信息管理：dev profile 明文 DB 密码；无环境矩阵（仅 dev 一份）；无备份恢复演练 | 安全/运维 | M2（环境矩阵+密钥外部化+备份演练） | M2 |
| L23 | coupon_usage 按 usage_no 的查询/CAS 无路由键→64 表广播（A1 修了 user_coupon 侧；usage 侧 API 契约 ConfirmRequest/ReleaseRequest 只有 usageNo 拿不到 userId） | 核销/释放热路径广播（性能） | M2 演练时评估 API 契约加 userId（订单侧本就持有） | M2 |
| L24 | 退款链路三处文档矛盾（docs/01"USED 终态不回券" vs docs/05 两处"整单退款回可用"）且实现为零 | 核销后退款的三方账（平台多计费用/商家多扣/用户多付）无法处理 | M5 开题 ADR 裁决+退款冲销链路（ROADMAP M5-⑤） | M5 |
| L26 | 合规清单未登记（个保法用户数据/营销禁用词/价格合规）——学习项目定位为"知晓级"：M6 建券多租户时留意禁用词与用户数据边界；不上等保 | 低（无真实用户数据） | M6 留意（不专项） | M6 |
| L27 | publish/offline 管理端点无鉴权（审查重申：G 簇裁决"最小鉴权提前 P2b"未落地；且 P2b 后该端点效力升格为铸造 DB allocated 配额+开桶） | 未授权调用=凭空造额度入口 | X-Admin-Token 校验+审计行——**P2c 开工第一件事** | P2c |
| L28 | 方案承诺的 F 簇探测器缺席：E3b 分代配对（per-gen dbi↔used）与 void/dbi 单调差分告警未实现（现仅 E3c 全局 SUM） | 桶内平移的定位弱化（资损仍被 DB 桶行封顶，检测降级为全局口径） | 巡检后续增强（随 2e 三步当场确认一并） | P2e |
| L29 | 旧代 GC 完全缺失：c:gens 集合与退役代键只增不减，巡检 Lua 成本随开桶次数线性无界增长 | 高频开桶模板（大促）巡检变慢、Redis 内存缓涨 | GC 协议（pend 清零+镜像确认后 SREM+DEL） | P3 |
| L30 | 有机售罄不立 c:so 短路牌：售罄后每请求仍执行开桶锁 SETNX+3 次模板/账本 SELECT | 售罄模板的无效 DB 读放大 | 售罄牌短 TTL 自续期（2e-④ 顺带解决——牌立上即短路） | P2e |
| L31 | ds0 连接池 maximumPoolSize=20 硬编码在代码内 YAML 模板 | 5000 QPS 下 Little 定律（并发=QPS×RT）需 ~250 连接级评估，无法运维调整 | 池参数外部化+按压测配平（P4-⑤ 联动） | P3/P4 |
| L32 | DriftEvent 无 (bucket,generation) 维度；开桶/差额补建只有日志行 | 分桶时代的事后定责证据链缺口（哪一代漂移只能翻日志） | drift 表加列+巡检落维度 | P3 |
| L33 | -10 等待者重试预算(~0.5s)与开桶锁 TTL(5s) 未对齐（本轮已用 DEGRADED 可重试兜底防终态谎报） | 慢开桶窗口内用户见降级码（可重试但体验受损） | exponential backoff 对齐锁 TTL（~5s 级等待） | P3 |
| L34 | 滚动补桶缺失：大盘模板（>槽数×桶额）单桶耗尽不补开，需等全桶耗尽（-10）——尾段并行度从 16×312 逐级衰减至单行 ~312 TPS，尾段请求延迟尖峰（正确性无损） | 大盘模板（20 万张级）尾段秒级~几十秒的吞吐凹陷；99% 模板（发布即分完）不受影响 | tryOpenBucket 增加"单桶空+有余量"触发（DEDUCT 返回区分码或顺延计数）——与桶间水位监控（2e per-bucket gauge）联动实施 | P2e/P4 |
| L25 | 券码体系（docs/04 完整设计）悬置：既无里程碑承接也无"不做"的 ADR | 治理真空（文档承认未实现 vs 路线无排期） | M6 二选一裁决（最小版 or ADR 写死不做） | M6 |
| L22 | ~~补偿脚本非幂等~~ **已修复（2026-09-07，ADR-0006 增补）**：双补偿致库存+1（Redis偏高→过闸被拒）与日计数-1（日限无DB防线）；幂等键门控后重复补偿完全no-op；M5 异步补偿需升级 attempt-UUID | 已清（M5 残留） | — |
| L21 | ~~对账在途竞态~~ **已修复（2026-09-08，ADR-0007）→ 2026-09-09 方案整体被 ADR-0008 恒等式取代**：计数器/复核/不对称只在"测量误差"内打转，恒等式从结构上消灭误差本身（期望精确 0）。多实例口径遗留并入 L16 | 已清 | — |

## 4.5 2026-09-07 六视角审计（修复记录）

26 个审计/核实 agent：源码陈旧物、工程文档漂移、学习资料误导、账本准确性、企业工程差距、测试质量。**20 项确认（3 blocker + 17 major）全部当场修复**，代表性结论：
- 3 个 blocker：ReceiveAppService 类javadoc 仍教"入口DB幂等快查"（与自身行内注释矛盾）；TemplateMapper javadoc 仍教"事务内重读分类"（正是 RR Bug 根源）；findByUserAndTemplate 死端口（引用已 DROP 索引、限领N 下语义已坏）——**全部修复/删除**；
- 文档：docs/00"当前阶段0"标注、旧路线表、ADR 索引缺口、docs/01 头部指引、ADR-0004 被取代条款无标注、ADR-0005 悬空指针——全部修复；
- 新增登记：L13-L20（见 §3）；
- study/ 全部历史快照文档加"对应提交+被取代内容"横幅（见各文档头）。

## 4. 设计文档 vs 实现 漂移表

| 文档 | 状态 |
|---|---|
| docs/01 领域模型 | ✅ 已实现（DDL 偏差见其头部指引；过期章=docs/06 ❌） |
| docs/02 发券链路 | ✅ 阶段1形态实现（异步落库部分 ❌，按路线图 M5 决策） |
| docs/03 库存扣减 | ✅ 实现（分桶/分段增补 ❌ 压测后定） |
| docs/04 ID与券码 | 🔶 Leaf 未实现（当前自增+业务编码）；券码体系 ❌ |
| docs/05 用券核销 | ✅ 三段已实现（优惠计算引擎 ❌ 未开始） |
| docs/06 券过期 | ✅ M1 裁剪版已实现（谓词+任务表扫描+惰性；实时触发层留 M5，见 L2 技术方案） |
| docs/07 存储缓存 | ❌ 分库分表/ES/冷热均未实现（M4） |
| docs/08 中台化 | ❌ 未实现（M6） |
| docs/09 幂等一致性 | ✅ 核心已实现（对账=ADR-0008 恒等式+第二不变式 T+1 互证；占用单×券状态交叉核对在 M1） |
| docs/10 统一规范 | ✅ 作为实现裁决基准持续生效 |
