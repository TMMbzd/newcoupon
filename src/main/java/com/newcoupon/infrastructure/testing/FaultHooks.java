package com.newcoupon.infrastructure.testing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 故障注入钩子（P2b 红灯测试基建——测试大纲 §5：测试总监判定"不建则半数红灯是纸面文章"）。
 *
 * <p>这是大厂混沌测试的标准形态：在生产代码的关键接缝埋"可控故障点"，名字全局注册，
 * 平时零开销，演练/测试时按名点火。toxiproxy（网络层故障）P4 再上，本类先覆盖应用层接缝。
 *
 * <p><b>生产零开销承诺</b>：{@code ENABLED} 默认 false（仅当系统属性
 * {@code -Dcoupon.faults.enabled=true} 时开启），{@link #fire} 首行布尔短路——
 * 生产环境既不 arm 也不 enable，钩子表永远为空。
 *
 * <p>已埋接缝（P2b 逐步补齐，见各调用处）：
 * <pre>
 * issue.suspend       领券 DB 事务挂起——构造"迟到 confirm/cancel 跨桶重开窗口"（CONC-4/CHAO-2 用例）
 * redis.cmd.delay     Redis 命令延迟——模拟主从切换/网络分区（P4 换 toxiproxy 前的桩）
 * openbucket.seam     开桶夹缝（DB 提交后/Redis EVAL 前）——第 4 步 StockBucketRouter 落地时加缝
 * </pre>
 *
 * <p>测试用法：@BeforeAll 置 {@code ENABLED=true}，@BeforeEach {@link #arm}，
 * @AfterEach {@link #disarmAll} 并复位 ENABLED——静态状态必须清理，防跨类污染。
 */
public final class FaultHooks {

    /** 领券 DB 事务挂起（参数：templateId） */
    public static final String ISSUE_SUSPEND = "issue.suspend";
    /** Redis 命令延迟（参数：命令名, templateId） */
    public static final String REDIS_CMD_DELAY = "redis.cmd.delay";
    /** 开桶夹缝（参数：templateId）——DB 提交后、Redis 开桶 Lua 前 */
    public static final String OPEN_BUCKET_SEAM = "openbucket.seam";

    private static volatile boolean enabled = Boolean.getBoolean("coupon.faults.enabled");

    private static final Map<String, Consumer<Object[]>> HOOKS = new ConcurrentHashMap<>();

    private FaultHooks() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 仅测试装配可开——生产无调用方 */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** 装配一个命名钩子（同名覆盖） */
    public static void arm(String name, Consumer<Object[]> action) {
        HOOKS.put(name, action);
    }

    public static void disarm(String name) {
        HOOKS.remove(name);
    }

    public static void disarmAll() {
        HOOKS.clear();
    }

    /** 接缝点火：未启用/未装配时零动作。args 携带接缝上下文（如 templateId）供钩子过滤 */
    public static void fire(String name, Object... args) {
        if (!enabled) {
            return;
        }
        Consumer<Object[]> hook = HOOKS.get(name);
        if (hook != null) {
            hook.accept(args);
        }
    }
}
