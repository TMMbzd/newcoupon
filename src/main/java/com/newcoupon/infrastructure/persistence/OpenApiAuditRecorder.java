package com.newcoupon.infrastructure.persistence;

import com.newcoupon.infrastructure.persistence.mapper.OpenApiAuditMapper;
import com.newcoupon.infrastructure.persistence.po.OpenApiAuditPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 开放接口调用审计（S1 第 10 步）——每次 {@code /open/**} 调用一行。
 *
 * <p><b>成功与失败都要落</b>：审计一半的价值在失败调用上（谁在敲门、敲了多少次、
 * 是签名错还是配额超）。所以调用点在过滤器的 finally 里——**无论请求从哪个分支返回都会执行**。
 *
 * <p><b>写失败绝不能影响业务</b>：它是旁路记录，不是交易的一部分。写挂了记 ERROR 日志，
 * 请求照常返回——**用"审计不可用"去换"用户拿不到券"是荒谬的交换**。
 * 代价是审计可能丢行，所以它是"观测数据"而不是"账"（账由库存与资金的对账体系保证）。
 *
 * <p><b>S1 同步落表</b>（登记 L48）：简单、可信、可测。异步化（有界队列 + 批量 flush）
 * 的触发条件是 S3 压测显示审计写入成为瓶颈——**没到那个量级之前，多引入一个组件就是
 * 多一处可能出错的地方**。
 *
 * <p><b>不记请求/响应体</b>：可能含用户隐私；要查内容用 traceId 去应用日志找（日志有脱敏规范）。
 */
@Component
public class OpenApiAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuditRecorder.class);

    private final OpenApiAuditMapper mapper;

    public OpenApiAuditRecorder(OpenApiAuditMapper mapper) {
        this.mapper = mapper;
    }

    public void record(AuditEntry e) {
        try {
            OpenApiAuditPO po = new OpenApiAuditPO();
            po.setTraceId(e.traceId());
            po.setAppId(e.appId());
            po.setVerified(e.verified() ? 1 : 0);
            po.setResource(e.resource());
            po.setMethod(e.method());
            po.setPath(e.path());
            po.setHttpStatus(e.httpStatus());
            po.setCode(e.code());
            po.setCostMs(e.costMs());
            po.setClientIp(e.clientIp());
            po.setUserId(e.userId());
            mapper.insert(po);
        } catch (RuntimeException ex) {
            log.error("开放接口审计写入失败（不影响业务返回）：appId={} path={}",
                    e.appId(), e.path(), ex);
        }
    }

    /**
     * @param appId     接入方。<b>未验签通过时是"声明值"</b>（头里写的），不可信——由 verified 区分
     * @param verified  是否通过验签：区分"谁在敲门"和"谁进了门"
     * @param resource  资源名（含维度）；未走到 Controller 时为 null
     * @param code      业务码；未进入业务（被接入层拦下）时是接入层错误码
     */
    public record AuditEntry(String traceId,
                             String appId,
                             boolean verified,
                             String resource,
                             String method,
                             String path,
                             int httpStatus,
                             Integer code,
                             int costMs,
                             String clientIp,
                             Long userId) {
    }
}
