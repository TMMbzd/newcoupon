package com.newcoupon.infrastructure.id;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花 ID 生成器（ADR-0010 批次二，2026-09-13）。
 *
 * <p>64 位结构：1 符号位（恒 0）+ 41 位毫秒时间戳（自定义纪元起，可用 69 年）+
 * 10 位机器号（0-1023）+ 12 位序列号（同一毫秒内 0-4095）。
 * 三件事一次解决：全局唯一（分库后自增 ID 跨库重复）、趋势递增（时间在高位——
 * InnoDB 主键永远右插，B+ 树不发生页分裂，对比 UUID 的随机落点）、无中心发号单点。
 *
 * <p><b>时钟回拨（面试必考）</b>：时间戳取自机器时钟，NTP 校时回跳会造成
 * "同机器+同'时间'+序列重数"=重复 ID——重复主键入库是资损级事故。策略分级：
 * 回拨 ≤ {@code maxBackoffMs}（默认 5ms）→ 自旋等待时钟追平（校时的正常抖动）；
 * 超过 → <b>借用未来毫秒</b>（以 lastTimestamp+1 继续发号）：唯一性与单调性优先于
 * "ID 时间=真实墙钟"——ID 内的时间会领先真实时钟最多一个回拨幅度（实测 WSL2 宿主机
 * 有秒级时钟步进，抛异常会让整个发券链路宕掉；借时方案保发号不中断）+ error 告警留痕。
 * 终极方案是号段模式（美团 Leaf：中心一次发一千个号本地缓存，时钟无关）——M6 升级路径。
 *
 * <p><b>workerId 分配纪律</b>：多实例必须互不相同（相同=可能撞 ID）。当前单实例用配置
 * {@code coupon.id.worker-id}（环境变量 WORKER_ID）；M2 双实例时用启动参数区分；
 * K8s 时代用 StatefulSet 序号或 DB 注册表抢占。
 *
 * <p>时间源构造器注入（LongSupplier）：生产=System.currentTimeMillis，测试=可控时钟
 * （复现回拨场景，不需要真等 NTP）。
 */
@Component
public class SnowflakeIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdGenerator.class);

    /** 自定义纪元 2026-01-01T00:00:00Z——41 位可用到 2096 年，且让生成的 ID 数值不至过长 */
    public static final long EPOCH = 1767225600000L;

    private static final int WORKER_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;      // 1023
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;  // 4095
    private static final int TIMESTAMP_SHIFT = WORKER_BITS + SEQUENCE_BITS;   // 22
    private static final int WORKER_SHIFT = SEQUENCE_BITS;                    // 12

    private final long workerId;
    private final long maxBackoffMs;
    private final java.util.function.LongSupplier clock;

    /** 上一次发号所在的毫秒与该毫秒内已用的序列号——synchronized 保护的双状态 */
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @org.springframework.beans.factory.annotation.Autowired   // 双构造器（另一个是测试用），指明主构造
    public SnowflakeIdGenerator(@Value("${coupon.id.worker-id:1}") long workerId) {
        this(workerId, 5L, System::currentTimeMillis);
    }

    SnowflakeIdGenerator(long workerId, long maxBackoffMs, java.util.function.LongSupplier clock) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId 必须在 0-" + MAX_WORKER + "，当前=" + workerId);
        }
        this.workerId = workerId;
        this.maxBackoffMs = maxBackoffMs;
        this.clock = clock;
    }

    /** 生成下一个全局唯一、趋势递增的 ID（线程安全：发号是全局串行的微秒级操作，锁开销可忽略） */
    public synchronized long next() {
        long now = clock.getAsLong();
        if (now < lastTimestamp) {
            long backoff = lastTimestamp - now;
            if (backoff <= maxBackoffMs) {
                now = spinUntil(lastTimestamp);           // 小回拨：等时钟追回到上次发号时刻
            } else {
                // 大回拨：借用未来毫秒（lastTimestamp+1 起继续）——保唯一/单调/可用，
                // 牺牲的是 ID 内时间领先真实时钟（幅度=回拨量，罕见且有界）。ERROR 留痕供追查。
                log.error("SNOWFLAKE CLOCK ROLLED BACK {}ms (>{}ms) — borrowing future timestamp "
                        + "to keep ids unique; check NTP/host clock (WSL2 known issue)", backoff, maxBackoffMs);
                now = lastTimestamp + 1;
            }
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {                          // 本毫秒 4096 个用尽：等到下一毫秒
                now = spinUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    /** 自旋等待时钟到达 target（单机发号场景忙等时长是微秒级） */
    private long spinUntil(long target) {
        long now = clock.getAsLong();
        while (now < target) {
            now = clock.getAsLong();
        }
        return now;
    }
}
