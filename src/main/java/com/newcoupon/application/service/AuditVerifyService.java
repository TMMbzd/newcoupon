package com.newcoupon.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.newcoupon.application.dto.AuditDtos.AuditVerifyResult;
import com.newcoupon.infrastructure.persistence.mapper.AuditChainHeadMapper;
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
 * <p><b>校验内容（逐行两件 + 收尾一件）</b>：
 * <ol>
 *   <li><b>prev_hash 链式衔接</b>（全部行）：本行 prev_hash == 上一行 record_hash，
 *       不等 = 中间有行被删/被插；首条必须等于 GENESIS 常量；</li>
 *   <li><b>record_hash 重算一致</b>（**仅 v2 行**）：v2 口径（含 actor 三列）重算与
 *       存储值比对。**v1 行豁免内容重算**（审查 C1）：v1 时代 createdAt 带纳秒进哈希、
 *       列是秒级 DATETIME（L81），存量 v1 行的哈希从写入那一刻起就不可能从库内数据
 *       重算——对它做内容校验只会把每一行都误报成篡改，并让衔接校验永远执行不到。
 *       豁免的代价要如实说：**v1 行的内容篡改检测不到**（改内容不动哈希，衔接也不破，
 *       纳秒口径下无法自证），这是历史数据的永久盲区，只能靠"应用层只写不改不删 +
 *       衔接校验（删行/插行仍可检出）+ DB 审计日志"兜底；</li>
 *   <li><b>链头对账</b>（收尾，审查 M1）：audit_chain_head.last_hash 必须等于全表
 *       末条的 record_hash（空表则必须为 NULL）。只走链不核头，删掉末尾 N 行并把
 *       head 同步改成新末行的攻击对 verify 完全不可见。</li>
 * </ol>
 * 返回第一个断点就停——哈希链是全序结构，第一个断点之后的"一致性"没有意义
 * （攻击者重算后续整段也能伪造连续，断点定位靠从头重算）。
 *
 * <p><b>成本口径（运维要知道的）</b>：一次 verify = 全表扫描（按 id 升序、500 行一批、
 * 游标推进不 OFFSET）+ 每行一次 SHA-256（v1 行免）。百万行量级约几十秒级、只读不锁写
 * ——内部面一次性运维动作可接受；不是为高频调用设计的（端点有 audit:read 权限闸）。
 */
@Service
public class AuditVerifyService {

    private static final Logger log = LoggerFactory.getLogger(AuditVerifyService.class);

    /** 扫描批大小：一批一批游标推进，等价于流式读（MyBatis 无 ResultSet 级流式口子的最小代价替代）。 */
    static final int BATCH_SIZE = 500;

    /** 断点原因（对外字符串，runbook §5 有解释表）。 */
    public static final String REASON_GENESIS = "GENESIS_MISMATCH";
    public static final String REASON_PREV = "PREV_HASH_MISMATCH";
    public static final String REASON_HASH = "RECORD_HASH_MISMATCH";
    /** 链头对账不符（head 落后/超前/空表非空 head——尾删+改 head 的攻击面，审查 M1）。 */
    public static final String REASON_HEAD = "CHAIN_HEAD_MISMATCH";
    public static final String REASON_HEAD_EMPTY = "CHAIN_HEAD_NON_NULL_ON_EMPTY_CHAIN";

    private final AuditLogMapper auditLogMapper;
    private final AuditChainHeadMapper chainHeadMapper;

    public AuditVerifyService(AuditLogMapper auditLogMapper, AuditChainHeadMapper chainHeadMapper) {
        this.auditLogMapper = auditLogMapper;
        this.chainHeadMapper = chainHeadMapper;
    }

    /**
     * 全链校验。
     *
     * @return ok=true（checked=走过的行数）或第一个断点 {breakAtId, reason}
     */
    public AuditVerifyResult verifyChain() {
        String expectedPrev = AuditService.GENESIS_HASH;
        String lastRecordHash = null;   // 全表末条的 record_hash（空表保持 null，链头对账用）
        long checked = 0;
        long cursor = 0L;
        while (true) {
            List<AuditLogPO> batch = auditLogMapper.selectList(
                    new LambdaQueryWrapper<AuditLogPO>()
                            .gt(AuditLogPO::getId, cursor)
                            .orderByAsc(AuditLogPO::getId)
                            .last("LIMIT " + BATCH_SIZE));
            if (batch.isEmpty()) {
                return verifyHead(lastRecordHash, checked);
            }
            for (AuditLogPO row : batch) {
                // ① 链式衔接（首条对 GENESIS——expectedPrev 的初值就是它）；v1/v2 都做
                if (row.getPrevHash() == null || !row.getPrevHash().equals(expectedPrev)) {
                    return broken(row.getId(), checked, checked == 0 ? REASON_GENESIS : REASON_PREV);
                }
                // ② 内容重算仅 v2：v1 行豁免（L81 纳秒口径，重算必错——见类注释）
                if (isV2(row) && !AuditService.computeHash(row).equals(row.getRecordHash())) {
                    return broken(row.getId(), checked, REASON_HASH);
                }
                expectedPrev = row.getRecordHash();
                lastRecordHash = row.getRecordHash();
                checked++;
            }
            if (batch.size() < BATCH_SIZE) {
                return verifyHead(lastRecordHash, checked);
            }
            cursor = batch.get(batch.size() - 1).getId();
        }
    }

    /**
     * 链头对账：head.last_hash == 末条 record_hash（空表 == NULL）。
     * head 读不加锁（FOR UPDATE 会与写路径互卡，verify 是只读巡检不该挡写入）；
     * 与末次写入之间存在毫秒级竞窗——写事务先 insert 后 update head，交错读最多
     * 看到"head 落后一行"，处置按 runbook §5 先复跑一轮再定性。
     */
    private AuditVerifyResult verifyHead(String lastRecordHash, long checked) {
        String head = chainHeadMapper.selectLastHash();
        if (lastRecordHash == null) {
            // 空链：head 必须还是初始 NULL（非空=有行被删光而 head 残留）
            if (head == null) {
                return new AuditVerifyResult(true, null, null, checked);
            }
            return broken(null, checked, REASON_HEAD_EMPTY);
        }
        if (!lastRecordHash.equals(head)) {
            return broken(null, checked, REASON_HEAD);
        }
        return new AuditVerifyResult(true, null, null, checked);
    }

    /** v2 = hashVersion 非空且 ≥2（null 与 1 都按存量 v1 处理）。 */
    private static boolean isV2(AuditLogPO row) {
        return row.getHashVersion() != null && row.getHashVersion() >= 2;
    }

    private AuditVerifyResult broken(Long breakAtId, long checked, String reason) {
        log.error("AUDIT CHAIN BROKEN: id={} reason={}（定位与处置见 runbook 审计查询.md §5）",
                breakAtId, reason);
        return new AuditVerifyResult(false, breakAtId, reason, checked);
    }
}
