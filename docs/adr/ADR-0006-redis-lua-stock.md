# ADR-0006: 阶段 1 库存架构——Redis Lua 闸门 + DB 双防线 + 对账重建

- **状态**：已接受（2026-09-05）
- **背景**：阶段 0（ADR-0004）的纯 DB 方案把全部并发排在 coupon_template 单行上（行锁串行），单模板吞吐天花板数百 QPS。阶段目标为单批次 5000+ QPS 零超发（docs/00 阶段 1 验收）。

## 决策

1. **Redis Lua 闸门**：幂等(EXISTS)→售罄(GET stock)→批次日限→用户总限→用户日限五重校验**先全部只读检查，通过后一次性提交写入**（INCR 计数 ×N + DECR 库存 + SETEX 幂等键），脚本内无回滚分支（check-all-then-commit，比"边写边回滚"更不易错）。校验顺序与错误码语义遵循 docs/10 第 10.3 节。
2. **限领通用化（替代 ADR-0004 的"限领1张"条款）**：
   - Redis 用户计数 `c:ul:{tpl}:{uid}` 即"同用户第几张"，**该序号作为 seq 写入 user_coupon**；
   - V2 迁移：删除 uk_user_template，新增唯一索引 `uk_user_seq (user_id, template_id, seq)`——**DB 层面强制"每人至多 N 张"**（seq 只可能是 1..N，Redis 计数丢失导致序号重复时被 DB 撞掉）。Redis 提供速度，DB 仍是最后防线；
   - per_user_limit 开放 1~99；新增批次日限/每人日限列（0=不限）。
3. **DB 条件更新保留（双防线）**：`UPDATE ... SET issued_count=issued_count+1 WHERE issued_count<total_count` 仍在事务内执行——即使 Redis 初始化错误（偏大），DB 仍保证零超发。Redis=速度闸门，DB CAS=正确性最后防线，两者同步扣减保持配对。
4. **同步落库不变**（docs/10 第 10.4 节"默认同步"）：Redis 扣减成功 → DB 事务（CAS 扣账 + 插券(含seq) + 事件）→ 失败则执行 **Lua 补偿脚本**（DEL 幂等键 + INCR 库存 + DECR 各计数），补偿与对账双兜底。
5. **对账与重建**：Lua 返回 -7（key 不存在）时懒重建（Redis 库存 = total_count - issued_count）；每小时定时任务比对 Redis 余量 vs (total-issued)，漂移告警并重建。进程被 kill -9 的残余漂移由对账收口。

## 后果

- 正面：单模板吞吐升至 Redis 单分片量级（~2万 QPS）；限领开放为通用 N；DB 防线完整保留。
- 代价（认账）：Redis 不参与 DB 事务，"回滚即补偿"的优雅消失，补偿脚本+对账成为必需品；Redis 主从切换极端场景的残余风险按 docs/03 3.6-5 接受（对账+运营兜底）。
- 二级缓存（模板本地缓存）与压测（QPS 实测）在阶段 1 第二弹交付。
