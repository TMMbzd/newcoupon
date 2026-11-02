package com.newcoupon.application.service;

import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantChunkMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantItemMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponGrantTaskMapper;
import com.newcoupon.infrastructure.persistence.po.CouponGrantChunkPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantItemPO;
import com.newcoupon.infrastructure.persistence.po.CouponGrantTaskPO;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量发放的事务内部分（V6 阶段 1A）。拆分理由与
 * {@link StockReshardService}/{@link StockReshardTxService} 相同：
 * 编排（{@link GrantTaskService}/{@link GrantTaskWorker}）不持事务，
 * 每个事务边界在这里显式声明——建任务一个事务、领取一个短事务、
 * item 记账/收尾各一个短事务。事务短，lease/退避才不会被长事务卡住。
 *
 * <p>守恒不变量的实现锚点在本类：item 状态 CAS（"AND status = 0"）成功
 * 才在同一事务里累加 chunk/task 计数——两个 worker 重放同一 item 时
 * 只有一个赢家能计数，total = success+fail+skip+pending 在任何时刻成立。
 *
 * <p><b>预算结算在本类（V6 阶段 1C）</b>：chunk 终态迁移（markDone/markCanceled）
 * 与预算收口（{@link BudgetService#settleChunk}）在同一事务——分开会出现
 * "chunk 已 DONE、预占未结算"的崩溃窗口，僵尸回收把已成功的预算也放回池子。
 * 这里调用的是同层 {@link BudgetService}（它自身不持事务，落到
 * {@link BudgetTxService} 的 @Transactional REQUIRED 会加入本事务）。
 */
@Service
public class GrantTaskTxService {

    private static final Logger log = LoggerFactory.getLogger(GrantTaskTxService.class);

    private final CouponGrantTaskMapper taskMapper;
    private final CouponGrantChunkMapper chunkMapper;
    private final CouponGrantItemMapper itemMapper;
    private final BudgetService budgetService;
    private final BudgetMetrics budgetMetrics;

    public GrantTaskTxService(CouponGrantTaskMapper taskMapper,
                              CouponGrantChunkMapper chunkMapper,
                              CouponGrantItemMapper itemMapper,
                              BudgetService budgetService,
                              BudgetMetrics budgetMetrics) {
        this.taskMapper = taskMapper;
        this.chunkMapper = chunkMapper;
        this.itemMapper = itemMapper;
        this.budgetService = budgetService;
        this.budgetMetrics = budgetMetrics;
    }

    /**
     * 建任务单事务：task + chunks + items 一起落库（要么全有要么全无，
     * 不存在"任务建了但没有明细"的中间态）。task.id 由自增回填后供子表引用。
     */
    @Transactional
    public long createTaskInTx(CouponGrantTaskPO task,
                               List<CouponGrantChunkPO> chunks,
                               List<CouponGrantItemPO> items) {
        taskMapper.insert(task);
        for (CouponGrantChunkPO chunk : chunks) {
            chunk.setTaskId(task.getId());
        }
        for (CouponGrantItemPO item : items) {
            item.setTaskId(task.getId());
        }
        // 分批插入：一条万行 VALUES 会拼出巨型 SQL（解析慢、可能顶到 max_allowed_packet），
        // 500 一批在"单事务原子性"与"SQL 规模"之间取平衡
        for (List<CouponGrantChunkPO> batch : partition(chunks, 500)) {
            chunkMapper.insertBatch(batch);
        }
        for (List<CouponGrantItemPO> batch : partition(items, 500)) {
            itemMapper.insertBatch(batch);
        }
        return task.getId();
    }

    /**
     * worker 领取一个短事务：`FOR UPDATE SKIP LOCKED` 取一条到期 chunk →
     * CAS 打 lease → 任务 PENDING→RUNNING。行锁只保持到本方法提交，
     * 之后 chunk 由 worker 的记账事务处理；崩溃靠 lease_until 回收。
     *
     * @return 领到的 chunk；无到期 chunk 或被抢先返回 null
     */
    @Transactional
    public CouponGrantChunkPO claimChunk(String owner, Duration lease) {
        LocalDateTime now = LocalDateTime.now();
        CouponGrantChunkPO chunk = chunkMapper.selectClaimable(now);
        if (chunk == null) {
            return null;
        }
        int updated = chunkMapper.markRunning(chunk.getId(), owner, now.plus(lease), now);
        if (updated != 1) {
            // SKIP LOCKED 下仍可能被并发事务改了状态：本轮放弃，下一轮再领
            return null;
        }
        taskMapper.markRunning(chunk.getTaskId());
        chunk.setStatus(CouponGrantChunkPO.STATUS_RUNNING);
        chunk.setLeaseOwner(owner);
        chunk.setLeaseUntil(now.plus(lease));
        return chunk;
    }

    /**
     * item 成功记账：CAS 迁移 item → SUCCESS，赢家同事务累加 chunk/task 的
     * success 并扣 pending。输家（重放撞已终态）什么都不做——幂等，
     * 这也是"计数守恒"在崩溃重放场景下不破的原因。
     */
    @Transactional
    public void completeItemSuccess(CouponGrantChunkPO chunk, long taskId,
                                    long itemId, long couponId) {
        if (itemMapper.markSuccess(itemId, couponId) != 1) {
            return;
        }
        chunkMapper.addSuccess(chunk.getId());
        taskMapper.addSuccess(taskId);
    }

    /** item 终态失败记账（售罄/日限/模板不可领/重试耗尽），同上 CAS+计数。 */
    @Transactional
    public void completeItemFail(CouponGrantChunkPO chunk, long taskId,
                                 long itemId, int failCode, String failMsg) {
        if (itemMapper.markFail(itemId, failCode, clipMsg(failMsg)) != 1) {
            return;
        }
        chunkMapper.addFail(chunk.getId());
        taskMapper.addFail(taskId);
    }

    /** item 跳过记账（3002 已限领：未发放），同上 CAS+计数。 */
    @Transactional
    public void completeItemSkip(CouponGrantChunkPO chunk, long taskId,
                                 long itemId, int code, String msg) {
        if (itemMapper.markSkip(itemId, code, clipMsg(msg)) != 1) {
            return;
        }
        chunkMapper.addSkip(chunk.getId(), 1);
        taskMapper.addSkip(taskId, 1);
    }

    /**
     * item 可重试记账：保持 PENDING、attempt+1。不计数（pending 不变），
     * 退避写在 chunk 上（backoffUntil），由 {@link #finishChunkPass} 落库。
     */
    @Transactional
    public void retryItem(long itemId, int failCode, String failMsg) {
        itemMapper.markRetry(itemId, failCode, clipMsg(failMsg));
    }

    /**
     * chunk 一轮执行的收尾：
     * 无 PENDING item → DONE + 预算结算 + 尝试收尾任务（全部 chunk 终态时迁移任务状态）；
     * 仍有 PENDING（可重试类）→ 保持 RUNNING、释放租约、next_retry_at 退避
     * （预算预占继续开口——可重试 item 下一轮可能真实消耗）。
     */
    @Transactional
    public void finishChunkPass(CouponGrantChunkPO chunk, CouponGrantTaskPO task,
                                String owner, LocalDateTime backoffUntil) {
        long pending = itemMapper.countPendingByChunk(chunk.getTaskId(), chunk.getChunkNo());
        if (pending == 0) {
            // markDone 打不中=租约已被别人回收（崩溃竞态），接手方自己会收尾
            if (chunkMapper.markDone(chunk.getId(), owner) == 1) {
                // 同事务结算预算：确认实际成功张数×单张成本、释放失败/跳过部分的预占
                budgetService.settleChunk(task, chunk);
                finalizeTaskIfDone(chunk.getTaskId());
            }
        } else {
            chunkMapper.backoff(chunk.getId(), owner, backoffUntil);
        }
    }

    /**
     * 释放租约但可立即重领（时间盒耗尽/执行异常中断）：lease 置为过去、
     * 保持 RUNNING——区别于退避（backoff 会同时推 next_retry_at）。
     */
    @Transactional
    public void releaseChunk(long chunkId, String owner) {
        chunkMapper.releaseLease(chunkId, owner, LocalDateTime.now().minusSeconds(1));
    }

    /**
     * retry-failed 单事务：FAIL item 批量重置（行数 n）→ 重开含 PENDING item 的
     * DONE chunk → 任务计数迁移（fail-n、pending+n）并把状态打回 RUNNING。
     * 三步必须同事务：任何一步失败整体回滚，否则会出现"item 已重置但任务仍
     * SUCCESS"的裂缝——pending&gt;0 的完成态任务会让守恒不变量与状态机同时失真。
     *
     * @return 重置的 item 数 n；0=当前没有 FAIL item（幂等返回，不抛错）
     * @throws BizException STATE_CONFLICT 任务状态 CAS 未打中（并发 retry 输家，
     *                       本事务连同 item 重置一起回滚）
     */
    @Transactional
    public int retryFailedInTx(long taskId) {
        int n = itemMapper.resetFailedToPending(taskId);
        if (n == 0) {
            return 0;
        }
        // next_retry_at 用应用时钟传入：领取判据（selectClaimable/markRunning）比的是应用时间，
        // 混用 DB NOW(3) 在两钟失步的部署下会让重开的 chunk 短暂"未到期"领不走
        chunkMapper.reopenDoneWithPendingItems(taskId, LocalDateTime.now());
        if (taskMapper.reopenFailed(taskId, n) != 1) {
            // CAS 输家=另一个 retry-failed 先一步把任务重开成 RUNNING：抛业务冲突
            // 让整笔回滚——若只回滚计数不回滚 item 重置，n 张 item 会凭空消失在账外
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "任务已被并发重试，请查看进度；重试请携带新的 X-Client-Token（接入层幂等会快照同令牌的本次响应）");
        }
        log.info("grant retry-failed reset {} item(s): taskId={}", n, taskId);
        return n;
    }

    /**
     * 取消收尾（任务 CANCELING/CANCELED 时被领取的 chunk 走这里）：
     * chunk CAS → FAILED，PENDING item 批量置 CANCELED（数量 n），
     * skip+n、pending-n，预算结算（已成功部分确认、其余释放），然后尝试收尾任务。
     * **不调用 receive**——取消语义是"阻止还没发的"，已发的券不受影响。
     */
    @Transactional
    public void cancelChunk(CouponGrantChunkPO chunk, CouponGrantTaskPO task, String owner) {
        if (chunkMapper.markCanceled(chunk.getId(), owner) != 1) {
            return;   // 已被收尾/被其它 worker 接手：幂等让路
        }
        int canceled = itemMapper.cancelPendingByChunk(chunk.getTaskId(), chunk.getChunkNo());
        if (canceled > 0) {
            chunkMapper.addSkip(chunk.getId(), canceled);
            taskMapper.addSkip(chunk.getTaskId(), canceled);
        }
        // 取消也是终态：预算按"已成功的张数"确认、剩余开口全部释放（未执行=未消耗）。
        // task 为 null 只发生在任务行不存在的理论不可达分支——没有预算账可结
        if (task != null) {
            budgetService.settleChunk(task, chunk);
        }
        finalizeTaskIfDone(chunk.getTaskId());
    }

    /**
     * 任务收尾判据：非终态 chunk 清零时——CANCELING → CANCELED（含任务级
     * PENDING item 兜底清扫），否则 PENDING/RUNNING → SUCCESS。
     * 只被本类其它事务方法在**同一事务内**调用；countNonTerminalByTask 是
     * 快照读，两个 worker 在重叠事务里收尾最后两个 chunk 时可能**双双**让路
     * （各自看不到对方未提交的 chunk 终态迁移）——任务状态迁移零次而不是一次。
     * 这个窗口由 {@link #reapStuckTasks()} 每轮兜底收尸闭合；finalize SQL
     * 自带状态守卫，重复补收尾幂等。
     */
    private void finalizeTaskIfDone(long taskId) {
        if (chunkMapper.countNonTerminalByTask(taskId) > 0) {
            return;
        }
        // 兜底清扫：全部 chunk 终态后理论上无 PENDING item（DONE=无 PENDING，
        // FAILED=取消时已清扫）；防御性再扫一次，防悬挂破坏守恒
        int swept = itemMapper.cancelPendingByTask(taskId);
        if (swept > 0) {
            taskMapper.addSkip(taskId, swept);
        }
        if (taskMapper.finalizeCanceled(taskId) == 1) {
            budgetMetrics.recordGrantTaskStatus("canceled");
        }
        if (taskMapper.finalizeSuccess(taskId) == 1) {
            budgetMetrics.recordGrantTaskStatus("success");
        }
    }


    /**
     * 收尾兜底：捞回"全部 chunk 已终态但任务未收尾"的任务（快照读让路窗口、
     * cancel 与收尾交错窗口的产物），重复 finalize——SQL 状态守卫保证幂等。
     * worker 每轮领取前调用；两个实例同时扫也无害（守卫 CAS 只让一个生效）。
     */
    @Transactional
    public int reapStuckTasks() {
        java.util.List<Long> stuck = taskMapper.selectStuckTaskIds();
        int finalized = 0;
        for (Long taskId : stuck) {
            if (chunkMapper.countNonTerminalByTask(taskId) > 0) {
                continue;   // 双保险：扫描到计数之间理论上不该出现新 chunk
            }
            int swept = itemMapper.cancelPendingByTask(taskId);
            if (swept > 0) {
                taskMapper.addSkip(taskId, swept);
            }
            if (taskMapper.finalizeCanceled(taskId) == 1) {
                budgetMetrics.recordGrantTaskStatus("canceled");
            }
            if (taskMapper.finalizeSuccess(taskId) == 1) {
                budgetMetrics.recordGrantTaskStatus("success");
            }
            finalized++;
            log.warn("grant task finalize reaped: taskId={}（收尾竞态让路，兜底补收尾）", taskId);
        }
        return finalized;
    }

    /** 失败原因截断到列宽（255），超长堆栈信息不进 DB。 */
    private static String clipMsg(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 255 ? msg.substring(0, 255) : msg;
    }

    /** 顺序切批（等价 Lists.partition，本地实现避免引依赖）。 */
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return batches;
    }
}
