package com.newcoupon.domain.open;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 租户生命周期状态机（V6 2B）。状态与合法迁移都收敛在这一个类型里——
 * 服务层只按"当前状态 + 动作"查表，不写 if-else 链（新增一条迁移只改这张表，
 * 不会出现"两个服务对同一状态的理解不一致"）。
 *
 * <pre>
 *   DRAFT ──submit_review──▶ PENDING_REVIEW ──approve_sandbox──▶ SANDBOX_ACTIVE
 *                                                              │ activate
 *   ACTIVE ◀──────resume────── SUSPENDED ◀──────suspend───────┘
 *     │                              │
 *     └────────close─────────────────┴───▶ CLOSING ──confirm_close──▶ CLOSED ──delete──▶ DELETED
 * </pre>
 *
 * <p><b>入口态的裁剪裁定</b>：本刀里 {@code POST /api/apps} 建出的应用直接落
 * ACTIVE（PROD）/ SANDBOX_ACTIVE（SANDBOX）——内部面建应用即"平台已审"，
 * 既有 731 例的"建好就能调"语义不变。DRAFT/PENDING_REVIEW 是给后续
 * "租户自助注册 + 人工审核"留的入口态（设计原文的自助端点已裁剪），
 * 运营可用 SQL 置入后由状态机正常走完后续链路。
 *
 * <p><b>各状态的业务效果</b>（接线位置见 OpenApiAuthFilter / EnvironmentGuard）：
 * <ul>
 *   <li>ACTIVE / SANDBOX_ACTIVE——开放面可调（签名链放行）；</li>
 *   <li>SUSPENDED——平台停用（临时冻结）：开放面 6013，数据与密钥保留，resume 可恢复；</li>
 *   <li>CLOSING——注销中：开放面 6013（写入先冻结），等待导出/收尾；</li>
 *   <li>CLOSED——注销完成：撤密钥（旧密钥即时作废）+ closed_at/retention_until 落库；</li>
 *   <li>DELETED——逻辑死：数据保留（不物理删），仅记录终态。</li>
 * </ul>
 */
public enum AppLifecycle {

    DRAFT,
    PENDING_REVIEW,
    SANDBOX_ACTIVE,
    ACTIVE,
    SUSPENDED,
    CLOSING,
    CLOSED,
    DELETED;

    // ---- 动作名（对外 API 的 action 取值；与其写三份字符串常量，不如只写这一份） ----
    public static final String ACTION_SUBMIT_REVIEW = "submit_review";
    public static final String ACTION_APPROVE_SANDBOX = "approve_sandbox";
    public static final String ACTION_ACTIVATE = "activate";
    public static final String ACTION_SUSPEND = "suspend";
    public static final String ACTION_RESUME = "resume";
    public static final String ACTION_CLOSE = "close";
    public static final String ACTION_CONFIRM_CLOSE = "confirm_close";
    public static final String ACTION_DELETE = "delete";
    /** 导出不是状态迁移，但占一个事件动作名——事件行的存在性就是"一次性"闸门的判据 */
    public static final String ACTION_DATA_EXPORT = "data_export";

    /**
     * 显式迁移表：Map&lt;当前状态, Map&lt;动作, 目标状态&gt;&gt;。
     * 之外的任何 (状态, 动作) 组合都是非法迁移（4002）——白名单制，
     * 宁可"运营少一条捷径"也不给"状态被随手改"留口子。
     */
    private static final Map<AppLifecycle, Map<String, AppLifecycle>> TRANSITIONS = Map.of(
            DRAFT, Map.of(ACTION_SUBMIT_REVIEW, PENDING_REVIEW),
            PENDING_REVIEW, Map.of(ACTION_APPROVE_SANDBOX, SANDBOX_ACTIVE),
            SANDBOX_ACTIVE, Map.of(ACTION_ACTIVATE, ACTIVE),
            ACTIVE, Map.of(
                    ACTION_SUSPEND, SUSPENDED,
                    ACTION_CLOSE, CLOSING),
            // SUSPENDED 也允许 close：停用中的租户决定注销时，不必先 resume 再 close
            // （resume 只是为了"恢复营业"，不是为了"换个姿势注销"）
            SUSPENDED, Map.of(
                    ACTION_RESUME, ACTIVE,
                    ACTION_CLOSE, CLOSING),
            CLOSING, Map.of(ACTION_CONFIRM_CLOSE, CLOSED),
            CLOSED, Map.of(ACTION_DELETE, DELETED),
            DELETED, Map.of());

    /** 查迁移表：非法组合返回 empty，由调用方统一抛 4002（带当前状态与合法动作清单） */
    public static Optional<AppLifecycle> targetOf(AppLifecycle from, String action) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(from, Map.of()).get(action));
    }

    /** 某状态下全部合法动作（非法迁移的报错文案用——告诉运营"现在能做什么"） */
    public static Set<String> allowedActions(AppLifecycle from) {
        return TRANSITIONS.getOrDefault(from, Map.of()).keySet();
    }

    /** 开放面签名链放行的状态：只有"营业中"两种。其余（含停用/注销各态）一律 6013 */
    public static boolean callableOnOpenFace(AppLifecycle status) {
        return status == ACTIVE || status == SANDBOX_ACTIVE;
    }

    /** 容错解析：库里的值不在枚举里时返回 empty（fail-closed，由调用方按 6013 拒绝） */
    public static Optional<AppLifecycle> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(AppLifecycle.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
