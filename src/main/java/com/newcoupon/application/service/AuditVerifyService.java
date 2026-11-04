package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.newcoupon.application.dto.AuditDtos.AuditVerifyResult;
import com.newcoupon.infrastructure.persistence.mapper.AuditLogMapper;
import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计哈希链全链校验（V6 2C）：一个服务两个入口——运维手动 POST /api/audit/verify
 * 与每日巡检任务（AuditChainVerifyJob）共用同一份重算逻辑，不会出现"端点说好、
 * 任务说坏"的两套口径。
 *
 * <p><b>校验内容（三件事，逐行做）</b>：
 * <ol>
 *   <li><b>record_hash 重算一致</b>：按行的 hashVersion 选算法（v1 不含 actor 列、
 *       v2 含）重算，与存储值不等 = 本行内容被改过；</li>
 *   <li><b>prev_hash 链式衔接</b>：本行 prev_hash == 上一行 record_hash，
 *       不等 = 中间有行被删/被插；</li>
 *   <li><b>首条 prev=GENESIS</b>：链的起点是常量，不是"随便什么哈希"。</li>
 * </ol>
 * 返回第一个断点就停——哈希链是全序结构，第一个断点之后的"一致性"没有意义
 * （攻击者重算后续整段也能伪造连续，断点定位靠从头重算）。
 *
 * <p><b>成本口径（运维要知道的）</b>：一次 verify = 全表扫描（按 id 升序、500 行一批、
 * 游标推进不 OFFSET）+ 每行一次 SHA-256。百万行量级约几十秒级、只读不锁写——
 * 内部面一次性运维动作可接受；不是为高频调用设计的（端点有 audit:read 权限闸）。
 */
@Service
public class AuditVerifyService {

    private static final Logger log = LoggerFactory.getLogger(AuditVerifyService.class);

    /** 扫描批大小：一批一批游标推进，等价于流式读（MyBatis 无 ResultSet 级流式口子的最小代价替代）。 */
    static final int BATCH_SIZE = 500;

    /** 断点原因（对外字符串，runbook 有解释表）。 */
    public static final String REASON_GENESIS = "GENESIS_MISMATCH";
    public static final String REASON_PREV = "PREV_HASH_MISMATCH";
    public static final String REASON_HASH = "RECORD_HASH_MISMATCH";

    private final AuditLogMapper auditLogMapper;

    public AuditVerifyService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 全链重算校验。
     *
     * @return ok=true（checked=走过的行数）或第一个断点 {breakAtId, reason}
     */
    public AuditVerifyResult verifyChain() {
        String expectedPrev = AuditService.GENESIS_HASH;
        long checked = 0;
        long cursor = 0L;
        while (true) {
            List<AuditLogPO> batch = auditLogMapper.selectList(
                    new LambdaQueryWrapper<AuditLogPO>()
                            .gt(AuditLogPO::getId, cursor)
                            .orderByAsc(AuditLogPO::getId)
                            .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                return new AuditVerifyResult(true, null, null, checked);
            }
            for (AuditLogPO row : batch) {
                // ① 链式衔接（首条对 GENESIS——expectedPrev 的初值就是它）
                if (row.getPrevHash() == null || !row.getPrevHash().equals(expectedPrev)) {
                    return broken(row, checked, checked == 0 ? REASON_GENESIS : REASON_PREV);
                }
                // ② 内容哈希重算（按行版本选口径——v1 行用 v1 算法才能对上存量哈希）
                if (!AuditService.computeHash(row).equals(row.getRecordHash())) {
                    return broken(row, checked, REASON_HASH);
                }
                expectedPrev = row.getRecordHash();
                checked++;
            }
            if (batch.size() < BATCH_SIZE) {
                return new AuditVerifyResult(true, null, null, checked);
            }
            cursor = batch.get(batch.size() - 1).getId();
        }
    }

    private AuditVerifyResult broken(AuditLogPO row, long checked, String reason) {
        log.error("AUDIT CHAIN BROKEN: id={} reason={}（定位：该行及运维处置见 runbook 审计查询.md）",
                row.getId(), reason);
        return new AuditVerifyResult(false, row.getId(), reason, checked);
    }
}
