package com.newcoupon.application.service;

import com.newcoupon.application.dto.FinanceDtos.BillQueryRequest;
import com.newcoupon.application.dto.FinanceDtos.PageResult;
import com.newcoupon.application.dto.FinanceDtos.RefundDetail;
import com.newcoupon.application.dto.FinanceDtos.RefundLine;
import com.newcoupon.application.dto.FinanceDtos.RefundQueryRequest;
import com.newcoupon.application.dto.FinanceDtos.RefundSummary;
import com.newcoupon.application.dto.FinanceDtos.SettlePartyBillView;
import com.newcoupon.application.dto.FinanceDtos.TemplateBillView;
import com.newcoupon.common.BizException;
import com.newcoupon.common.ErrorCode;
import com.newcoupon.infrastructure.persistence.mapper.CouponDailyBillMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundDetailMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponRefundOrderMapper;
import com.newcoupon.infrastructure.persistence.mapper.CouponSettlePartyDailyBillMapper;
import com.newcoupon.infrastructure.persistence.po.CouponRefundDetailPO;
import com.newcoupon.infrastructure.persistence.po.CouponRefundOrderPO;
import com.newcoupon.infrastructure.persistence.po.CouponSettlePartyDailyBillPO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 财务/客服查询（环 3，仅内部面）。
 *
 * <p><b>为什么只开 POST</b>：`LegacyAdminTokenAuthenticationFilter`（旧名 AdminTokenFilter）只保护非 GET 的 `/api/**`；
 * 财务数据不能走无鉴权的 GET。POST 查询天然落入默认拒绝，不需要登记业务白名单。
 *
 * <p><b>分页</b>：项目没有 MyBatis 分页插件，这里用 `COUNT + LIMIT/OFFSET`；
 * 日期范围最多 31 天、size 最多 200，防全表扫描和深分页。
 *
 * <p><b>主体口径</b>：退款单本身没有 settle_party_id，按主体过滤走退款明细的 EXISTS；
 * `__default__` 是“未归属/无商家概念”的哨兵，财务不能用它向真实商家结算。
 */
@Service
public class FinanceQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_DATE_RANGE_DAYS = 31;

    private final CouponRefundOrderMapper refundOrderMapper;
    private final CouponRefundDetailMapper refundDetailMapper;
    private final CouponSettlePartyDailyBillMapper settlePartyBillMapper;
    private final CouponDailyBillMapper dailyBillMapper;

    public FinanceQueryService(CouponRefundOrderMapper refundOrderMapper,
                               CouponRefundDetailMapper refundDetailMapper,
                               CouponSettlePartyDailyBillMapper settlePartyBillMapper,
                               CouponDailyBillMapper dailyBillMapper) {
        this.refundOrderMapper = refundOrderMapper;
        this.refundDetailMapper = refundDetailMapper;
        this.settlePartyBillMapper = settlePartyBillMapper;
        this.dailyBillMapper = dailyBillMapper;
    }

    public PageResult<RefundSummary> queryRefunds(RefundQueryRequest req) {
        String usageNo = blankToNull(req.usageNo());
        String refundNo = blankToNull(req.refundNo());
        String orderNo = blankToNull(req.orderNo());
        String appId = blankToNull(req.appId());
        String settlePartyId = blankToNull(req.settlePartyId());

        boolean hasDate = req.fromDate() != null || req.toDate() != null;
        if (settlePartyId != null && appId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "按主体查询必须同时提供 appId（主体 ID 是租户内语义）");
        }
        if (usageNo == null && refundNo == null && orderNo == null && !hasDate) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "退款查询至少提供 usageNo/refundNo/orderNo/日期范围 之一");
        }
        if ((req.fromDate() == null) != (req.toDate() == null)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "fromDate 与 toDate 必须同时提供");
        }
        LocalDateTime fromTime = null;
        LocalDateTime toTime = null;
        if (hasDate) {
            if (req.toDate().isBefore(req.fromDate())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "toDate 不能早于 fromDate");
            }
            if (ChronoUnit.DAYS.between(req.fromDate(), req.toDate()) > MAX_DATE_RANGE_DAYS) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "日期范围最多 " + MAX_DATE_RANGE_DAYS + " 天");
            }
            fromTime = req.fromDate().atStartOfDay();
            toTime = req.toDate().plusDays(1).atStartOfDay();
        }

        int page = normalizePage(req.page());
        int size = normalizeSize(req.size());
        long offset = (long) (page - 1) * size;

        long total = refundOrderMapper.countByFilters(usageNo, refundNo, orderNo, appId,
                settlePartyId, fromTime, toTime);
        List<RefundSummary> items = refundOrderMapper.selectByFilters(usageNo, refundNo, orderNo,
                        appId, settlePartyId, fromTime, toTime, size, offset).stream()
                .map(this::toSummary)
                .toList();
        return new PageResult<>(total, page, size, items);
    }

    public RefundDetail refundDetail(String refundNo) {
        String normalized = blankToNull(refundNo);
        if (normalized == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "退款单号不能为空");
        }
        CouponRefundOrderPO order = refundOrderMapper.selectByRefundNo(normalized);
        if (order == null) {
            throw new BizException(ErrorCode.REFUND_NOT_FOUND, normalized);
        }
        List<RefundLine> lines = refundDetailMapper.selectByRefundNo(normalized).stream()
                .map(this::toLine)
                .toList();
        return new RefundDetail(toSummary(order), lines);
    }

    public PageResult<SettlePartyBillView> querySettlePartyBills(BillQueryRequest req) {
        int page = normalizePage(req.page());
        int size = normalizeSize(req.size());
        long offset = (long) (page - 1) * size;
        String appId = blankToNull(req.appId());
        String settlePartyId = blankToNull(req.settlePartyId());
        if (settlePartyId != null && appId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "按主体查询必须同时提供 appId（主体 ID 是租户内语义）");
        }

        long total = settlePartyBillMapper.countByFilters(req.billDate(), appId,
                settlePartyId, req.templateId());
        List<SettlePartyBillView> items = settlePartyBillMapper.selectByFilters(
                        req.billDate(), appId, settlePartyId, req.templateId(), size, offset)
                .stream()
                .map(this::toSettlePartyView)
                .toList();
        return new PageResult<>(total, page, size, items);
    }

    public PageResult<TemplateBillView> queryTemplateBills(BillQueryRequest req) {
        int page = normalizePage(req.page());
        int size = normalizeSize(req.size());
        long offset = (long) (page - 1) * size;
        String appId = blankToNull(req.appId());

        long total = dailyBillMapper.countByFilters(req.billDate(), appId, req.templateId());
        List<TemplateBillView> items = dailyBillMapper.selectByFilters(req.billDate(), appId,
                        req.templateId(), size, offset).stream()
                .map(this::toTemplateView)
                .toList();
        return new PageResult<>(total, page, size, items);
    }

    // ------------------------------------------------------------------

    private RefundSummary toSummary(CouponRefundOrderPO o) {
        return new RefundSummary(o.getRefundNo(), o.getUsageNo(), o.getOrderNo(),
                o.getUserId(), o.getUserCouponId(), o.getTemplateId(), o.getAppId(),
                o.getRefundType(), o.getRefundDiscountAmt(), o.getRefundPlatformAmt(),
                o.getRefundMerchantAmt(), o.getItemCount(), o.getCouponReturned(),
                o.getFundingKnown() == null ? null : o.getFundingKnown() == 1,
                o.getCouponStatus(), o.getUsageStatus(), o.getCreateTime());
    }

    private RefundLine toLine(CouponRefundDetailPO d) {
        return new RefundLine(d.getOrderLineNo(), d.getRefundQty(), d.getRefundDiscountAmt(),
                d.getRefundPlatformAmt(), d.getRefundMerchantAmt(), d.getAppId(),
                d.getSettlePartyId(), d.getCreateTime());
    }

    private SettlePartyBillView toSettlePartyView(CouponSettlePartyDailyBillPO p) {
        return new SettlePartyBillView(p.getBillDate(), p.getAppId(), p.getSettlePartyId(),
                p.getTemplateId(), p.getConfirmCount(), p.getRefundCount(),
                p.getDiscountTotal(), p.getPlatformBurden(), p.getMerchantBurden(),
                p.getHoldAmount(), p.getPayableAmount(),
                p.getIneligibleShareCount(), p.getIneligibleRefundCount());
    }

    private TemplateBillView toTemplateView(CouponDailyBillMapper.TemplateBillRow r) {
        return new TemplateBillView(r.getBillDate(), r.getTemplateId(), r.getTemplateName(),
                r.getAppId(), r.getConfirmCount(), r.getRefundCount(), r.getDiscountTotal(),
                r.getPlatformBurden(), r.getMerchantBurden());
    }

    private static int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return Math.min(page, 500);
    }

    private static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
