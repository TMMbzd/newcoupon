package com.newcoupon.application.service;

import com.newcoupon.infrastructure.persistence.mapper.CouponCodeBatchMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponCodeRedeemLogMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodeBatchPO;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import com.newcoupon.infrastructure.persistence.po.CouponCodeRedeemLogPO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兑换码域的事务内部分（V6 阶段 1B）。拆分理由与 {@link GrantTaskTxService} 相同：
 * 编排（{@link CouponCodeService}）不持事务，每个事务边界在这里显式声明。
 *
 * <p>锚点规则：码状态 CAS 的返回值决定同事务的伴随写——markUsed 打中 1 行
 * 才 addUsed（批次 used 计数），否则 used 会越过 CHECK 上限 used&lt;=total；
 * reactivate 打中才记失败流水（码没回池说明已被回收/重兑，这次失败没有账可挂）。
 */
@Service
public class CouponCodeTxService {

    private final CouponCodeBatchMapper batchMapper;
    private final CouponCodeMapper codeMapper;
    private final CouponCodeRedeemLogMapper logMapper;

    public CouponCodeTxService(CouponCodeBatchMapper batchMapper,
                               CouponCodeMapper codeMapper,
                               CouponCodeRedeemLogMapper logMapper) {
        this.batchMapper = batchMapper;
        this.codeMapper = codeMapper;
        this.logMapper = logMapper;
    }

    /**
     * 建批单事务：批次行 + 全部码行一起落库（要么全有要么全无——不存在
     * "批次有了但码缺一半"的中间态；缺一半的批次无法对账 total）。
     * 码行分批 500 插入，防止一条 10 万行 VALUES 的巨型 SQL。
     */
    @Transactional
    public long createBatchInTx(CouponCodeBatchPO batch, List<CouponCodePO> codes) {
        batchMapper.insert(batch);
        for (CouponCodePO code : codes) {
            code.setBatchId(batch.getId());
        }
        for (int i = 0; i < codes.size(); i += 500) {
            codeMapper.insertBatch(codes.subList(i, Math.min(codes.size(), i + 500)));
        }
        return batch.getId();
    }

    /**
     * 兑换成功结算单事务：码 → USED（回填兑换人/时间）+ 批次 used+1 + 成功流水。
     * markUsed 打不中（0 行）也照记成功流水：券已真实发出（receive 已提交），
     * 打不中只说明码已被并发路径结算（慢赢家撞车，见 CouponCodeMapper.markUsed 注释）——
     * 此时不再 addUsed（used 计数只跟第一次 USED 迁移走，不重复计）。
     */
    @Transactional
    public void markRedeemedWithLog(long codeId, long batchId, String appId, long userId,
                                    String requestId, long couponId) {
        boolean settled = codeMapper.markUsed(codeId, userId) == 1;
        if (settled) {
            batchMapper.addUsed(batchId);
        }
        CouponCodeRedeemLogPO row = new CouponCodeRedeemLogPO();
        row.setBatchId(batchId);
        row.setCodeId(codeId);
        row.setAppId(appId);
        row.setUserId(userId);
        row.setRequestId(requestId);
        row.setResult(CouponCodeRedeemLogPO.RESULT_SUCCESS);
        // fail_reason 复用为"结算方式"备注：couponId 便于流水直接对到券（成功行不带失败语义）
        row.setFailReason("couponId=" + couponId + (settled ? "" : ";code已并发结算"));
        logMapper.insert(row);
    }

    /**
     * 兑换失败回池单事务：码 LOCKED→ACTIVE + 失败流水。两写同事务：
     * 码回池却没记失败（或反过来）都会让"这个码发生过什么"对不上账。
     */
    @Transactional
    public void reactivateWithLog(long codeId, long batchId, String appId, long userId,
                                  String requestId, int failCode, String failMsg) {
        codeMapper.reactivate(codeId);
        insertFailLog(batchId, codeId, appId, userId, requestId,
                "code=" + failCode + ";msg=" + failMsg);
    }

    /** 单条失败流水（未解析出码的失败——限流/不存在/形态非法——只有流水可记）。 */
    public void insertFailLog(Long batchId, Long codeId, String appId, long userId,
                              String requestId, String failReason) {
        CouponCodeRedeemLogPO row = new CouponCodeRedeemLogPO();
        row.setBatchId(batchId);
        row.setCodeId(codeId);
        row.setAppId(appId);
        row.setUserId(userId);
        row.setRequestId(requestId);
        row.setResult(CouponCodeRedeemLogPO.RESULT_FAIL);
        row.setFailReason(clip(failReason));
        logMapper.insert(row);
    }

    /** 失败原因截断到列宽（255），超长信息不进 DB。 */
    private static String clip(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 255 ? msg.substring(0, 255) : msg;
    }
}
