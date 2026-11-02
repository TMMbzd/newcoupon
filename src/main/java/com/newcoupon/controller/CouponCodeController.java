package com.newcoupon.controller;

import com.newcoupon.application.dto.CouponCodeBatchCreateRequest;
import com.newcoupon.application.dto.CouponCodeBatchCreateResult;
import com.newcoupon.application.dto.CouponCodeRedeemRequest;
import com.newcoupon.application.dto.CouponCodeRedeemResult;
import com.newcoupon.application.service.CouponCodeService;
import com.newcoupon.common.Result;
import com.newcoupon.common.context.OpenResource;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兑换码接口（V6 阶段 1B）。Controller 只做协议转换，照 {@link GrantTaskController}
 * 的双前缀模式：{@code /api/codes} 走管理令牌，{@code /open/v1/codes} 走签名。
 * 归属校验放服务层（模板/批次加载之后），过滤器阶段不知道批次属于谁。
 *
 * <p>明文出口两个：建批响应（Result JSON，一次性）与 CSV 导出
 * （{@code ResponseEntity<byte[]>}，export_state CAS 一次性）——
 * 与 failures CSV 同款"非 Result 载荷"理由：文件就是给表格软件/程序直接消费的。
 */
@RestController
@RequestMapping({"/api/codes", "/open/v1/codes"})
public class CouponCodeController {

    private final CouponCodeService couponCodeService;

    public CouponCodeController(CouponCodeService couponCodeService) {
        this.couponCodeService = couponCodeService;
    }

    /**
     * 建批：生成 count 个码（明文只在本次响应出现，调用方必须当场持久化）。
     * 幂等重放返回批次现状但不回明文。
     */
    @PostMapping("/batches")
    @OpenResource("code/batch")
    public Result<CouponCodeBatchCreateResult> createBatch(
            @Valid @RequestBody CouponCodeBatchCreateRequest req) {
        return Result.success(couponCodeService.createBatch(req));
    }

    /**
     * 明文一次性 CSV 导出：BOM+表头 code,user,status,redeemTime；二次请求 4002。
     * 用 POST 而非 GET 是**安全判据要求**：此端点非只读（CAS export_state+删暂存）且出口是
     * 全量明文码——AdminTokenFilter 的判据反转是"非 GET 默认拒绝"，GET 会被匿名拉走
     * 全部码明文并烧掉属主的一次性导出资格（对抗审查 critical，batchNo 雪花可推算）。
     */
    @PostMapping("/batches/{batchNo}/codes")
    @OpenResource("code/batch/export")
    public ResponseEntity<byte[]> exportCodes(@PathVariable String batchNo) {
        byte[] csv = couponCodeService.exportCodesCsv(batchNo);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=codes-" + batchNo + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    /** 兑换：成功返回券；失败统一文案防枚举（真实原因在兑换流水）。 */
    @PostMapping("/redeem")
    @OpenResource("code/redeem")
    public Result<CouponCodeRedeemResult> redeem(
            @Valid @RequestBody CouponCodeRedeemRequest req) {
        return Result.success(couponCodeService.redeem(req));
    }
}
