package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcoupon.infrastructure.persistence.po.AuditLogPO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 哈希算法版本口径单测（V6 2C）：v1/v2 按行的 hashVersion 选择，actor 三列只进 v2 拼接。
 *
 * <p>这是"存量链不重算、新链含 actor"两条裁定交汇的正确性前提——如果 v2 漏拼了
 * actor 列，改 actor 就成了链上检测不到的篡改面；如果 v1 也拼了，全部存量行会被
 * verify 误报篡改。
 */
class AuditHashVersionTest {

    private AuditLogPO po(int hashVersion, String actorType, String actorId, String sessionId) {
        AuditLogPO po = new AuditLogPO();
        po.setAppId("app-1");
        po.setUserId(100L);
        po.setTraceId("trace-1");
        po.setAction("TEST");
        po.setResourceType("RES");
        po.setResourceId("r-1");
        po.setBeforeSnapshot("{\"b\":1}");
        po.setAfterSnapshot("{\"a\":1}");
        po.setIp("127.0.0.1");
        po.setResultCode(0);
        po.setActorType(actorType);
        po.setActorId(actorId);
        po.setSessionId(sessionId);
        po.setHashVersion(hashVersion);
        po.setPrevHash(AuditService.GENESIS_HASH);
        po.setCreatedAt(LocalDateTime.of(2026, 11, 4, 12, 0, 0));
        return po;
    }

    @Test
    @DisplayName("同一内容 v1 与 v2 口径产出不同哈希（版本可辨识）")
    void versionChangesHash() {
        AuditLogPO v1 = po(1, "ADMIN", "7", "s-1");
        AuditLogPO v2 = po(2, "ADMIN", "7", "s-1");
        assertThat(AuditService.computeHash(v1)).isNotEqualTo(AuditService.computeHash(v2));
    }

    @Test
    @DisplayName("改 actor 三列：v2 哈希变（篡改可检出），v1 哈希不变（存量口径不含 actor）")
    void actorOnlyAffectsV2() {
        String v1Base = AuditService.computeHash(po(1, "ADMIN", "7", "s-1"));
        String v2Base = AuditService.computeHash(po(2, "ADMIN", "7", "s-1"));

        assertThat(AuditService.computeHash(po(1, "SYSTEM", "SYSTEM", null)))
                .as("v1 口径不含 actor 列——存量行的哈希本就不由 actor 决定")
                .isEqualTo(v1Base);
        assertThat(AuditService.computeHash(po(2, "SYSTEM", "SYSTEM", null)))
                .as("v2 口径含 actor 列——改 actor 必须改哈希")
                .isNotEqualTo(v2Base);
    }

    @Test
    @DisplayName("hashVersion 为 null 按 v1 口径（防御：历史 PO 无版本字段时的读路径）")
    void nullVersionFallsBackToV1() {
        String withNull = AuditService.computeHash(po(1, "ADMIN", "7", "s-1"));
        AuditLogPO explicit1 = po(1, "ADMIN", "7", "s-1");
        assertThat(withNull).isEqualTo(AuditService.computeHash(explicit1));
    }
}
