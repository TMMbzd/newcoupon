package com.newcoupon.controller;

import com.newcoupon.application.dto.TradeDtos.ConfirmRequest;
import com.newcoupon.application.dto.TradeDtos.LockRequest;
import com.newcoupon.application.dto.TradeDtos.LockResult;
import com.newcoupon.application.dto.TradeDtos.MyCouponList;
import com.newcoupon.application.dto.TradeDtos.ReleaseRequest;
import com.newcoupon.application.dto.TradeDtos.ReleaseResult;
import com.newcoupon.application.dto.TradeDtos.ConfirmResult;
import com.newcoupon.application.service.TradeAppService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import com.newcoupon.domain.coupon.CouponStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用券接口。lock 由订单服务在下单时调用；confirm 由支付回调触发；release 由取消/超时触发。
 * duplicated=true 表示消息重放（结果与首次一致），调用方视为成功。
 *
 * <p>两个前缀两种契约（S1 D1）：{@code /api/coupons} 内部面 / {@code /open/v1/coupons} 开放面（签名）。
 */
@RestController
@RequestMapping({"/api/coupons", "/open/v1/coupons"})
public class TradeController {

    private final TradeAppService tradeAppService;

    public TradeController(TradeAppService tradeAppService) {
        this.tradeAppService = tradeAppService;
    }

    /** 下单锁券 */
    @PostMapping("/lock")
    @OpenResource("coupons/lock")
    public Result<LockResult> lock(@Valid @RequestBody LockRequest req) {
        return Result.success(tradeAppService.lock(req));
    }

    /** 支付成功核销 */
    @PostMapping("/confirm")
    @OpenResource("coupons/confirm")
    public Result<ConfirmResult> confirm(@Valid @RequestBody ConfirmRequest req) {
        return Result.success(tradeAppService.confirm(req));
    }

    /** 订单取消/超时释放 */
    @PostMapping("/release")
    @OpenResource("coupons/release")
    public Result<ReleaseResult> release(@Valid @RequestBody ReleaseRequest req) {
        return Result.success(tradeAppService.release(req));
    }

    /** 我的券包（status 可选：0待使用 1已锁定 2已核销 3已过期 4已作废） */
    @GetMapping
    @OpenResource("coupons/query")
    public Result<MyCouponList> myCoupons(@RequestParam long userId,
                                          @RequestParam(required = false) Integer status) {
        CouponStatus filter = status == null ? null : CouponStatus.of(status);
        return Result.success(tradeAppService.myCoupons(userId, filter));
    }
}
