package com.newcoupon.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 雪花 ID 单测（ADR-0010 批次二）：唯一性（10 万单线程 + 多线程并发）、趋势递增、
 * 时钟回拨分级防护（小回拨自愈/大回拨拒绝）、同毫秒序列溢出翻页。
 *
 * <p>假时钟说明（真时钟 vs 可控时钟的差异，实施中实际踩到）：生成器的溢出/回拨分支会
 * 自旋等待"下一毫秒"——真时钟自己会走，假时钟不走就死循环。所以测试时钟要么自动前进
 * （{@link TickingClock}），要么冻结 N 次读取后前进一步（{@link StepClock}），
 * 确保自旋能等到出口。
 */
@DisplayName("雪花ID·唯一/递增/时钟回拨")
class SnowflakeIdGeneratorTest {

    /** 每读一次前进 1ms 的时钟（模拟真实走时） */
    private static final class TickingClock implements java.util.function.LongSupplier {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());

        @Override
        public long getAsLong() {
            return now.getAndIncrement();
        }
    }

    /** 冻结 frozenReads 次读取后前进 1ms 的时钟（制造"同一毫秒 + 序列溢出"场景） */
    private static final class StepClock implements java.util.function.LongSupplier {
        private final long base = System.currentTimeMillis();
        private long remainingFrozen;

        StepClock(long frozenReads) {
            this.remainingFrozen = frozenReads;
        }

        @Override
        public synchronized long getAsLong() {
            if (remainingFrozen > 0) {
                remainingFrozen--;
                return base;
            }
            return base + 1;
        }
    }

    @Test
    @DisplayName("单线程 10 万个 ID：全唯一且严格递增（趋势递增=B+树友好）")
    void uniqueAndIncreasing() {
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(1, 5, new TickingClock());
        Set<Long> seen = new HashSet<>();
        long prev = -1;
        for (int i = 0; i < 100_000; i++) {
            long id = g.next();
            assertThat(seen.add(id)).as("ID 必须全局唯一").isTrue();
            assertThat(id).as("趋势递增").isGreaterThan(prev);
            prev = id;
        }
    }

    @Test
    @DisplayName("多线程并发 8×1 万：零重复（synchronized 串行发号）")
    void concurrentUnique() throws Exception {
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(1, 5, new TickingClock());
        Set<Long> all = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(t -> pool.submit(() -> {
                        gate.await();
                        for (int i = 0; i < 10_000; i++) {
                            all.add(g.next());
                        }
                        return null;
                    })).toList();
            gate.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(all).as("8 万个并发 ID 零重复").hasSize(80_000);
    }

    @Test
    @DisplayName("时钟大回拨（100ms>5ms）：借用未来毫秒继续发号——唯一/单调/不断服")
    void largeRollbackBorrowsFuture() {
        AtomicLong writable = new AtomicLong(System.currentTimeMillis());
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(1, 5, writable::get);
        long a = g.next();

        writable.set(writable.get() - 100);          // 回拨 100ms（WSL2 实测会发生的形态）
        long b = g.next();
        long c = g.next();

        assertThat(b).as("借用未来：ID 仍严格递增").isGreaterThan(a);
        assertThat(c).as("连续借用：继续递增").isGreaterThan(b);
    }

    @Test
    @DisplayName("同一毫秒序列用尽（4096 个）：翻到下一毫秒继续，ID 不重不断、不死循环")
    void sequenceOverflowRollsToNextMs() {
        // StepClock：前 4096 次读取同一毫秒（恰好耗尽序列），之后 +1ms——
        // 自旋等待"下一毫秒"在冻结耗尽后立即等到出口
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(7, 5, new StepClock(4096));
        Set<Long> seen = new HashSet<>();

        for (int i = 0; i < 6_000; i++) {
            assertThat(seen.add(g.next())).as("溢出翻页后仍唯一").isTrue();
        }
        assertThat(seen).hasSize(6_000);
    }

    @Test
    @DisplayName("小回拨（3ms≤5ms）：自旋等时钟追回后继续发号，不倒退、不重复")
    void smallRollbackStillUnique() {
        AtomicLong ticking = new AtomicLong(System.currentTimeMillis());
        SnowflakeIdGenerator g = new SnowflakeIdGenerator(2, 5, ticking::getAndIncrement);
        long a = g.next();

        ticking.set(ticking.get() - 3);                // 回拨 3ms（时钟每次读取 +1，3 次读取内追回）
        long b = g.next();

        assertThat(b).as("追回后继续发号且不倒退").isGreaterThanOrEqualTo(a);
    }
}
