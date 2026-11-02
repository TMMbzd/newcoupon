package com.newcoupon.application.service;

import java.util.List;
import java.util.OptionalInt;

/**
 * 每人总限的冷路径补齐：当 {@code MAX(seq)+1 > perUserLimit} 时，判断是真领满还是序号空洞。
 *
 * <p>为什么会有空洞：异步受理在 Redis gate 预分配 seq，任务后来可能被拒绝（库存满/模板下线/
 * 重试耗尽），但预分配的 seq 不会回收到数据库。例如用户最终只有 seq=2 的券，MAX=2；
 * 如果 perUserLimit=2，直接看 MAX+1 会误判“已领满”，用户永久拿不到第二个名额。
 *
 * <p>这里只在拒绝路径调用，读取该用户已占用的 seq（最多 99 个），选最小空闲序号；
 * 正常领取仍然只读 MAX(seq)，不回到 COUNT。
 */
final class UserSeqAllocator {

    private UserSeqAllocator() {
    }

    /**
     * @param usedSeqs 已占用序号，必须按升序（SQL ORDER BY seq）
     * @param limit    每人总限领，1~99
     * @return 最小空闲序号；已领满返回 empty
     */
    static OptionalInt firstFree(List<Integer> usedSeqs, int limit) {
        if (limit < 1) {
            return OptionalInt.empty();
        }
        int candidate = 1;
        for (Integer seq : usedSeqs) {
            if (seq == null || seq < candidate) {
                continue;
            }
            if (seq > candidate) {
                break;
            }
            candidate++;
            if (candidate > limit) {
                return OptionalInt.empty();
            }
        }
        return candidate <= limit ? OptionalInt.of(candidate) : OptionalInt.empty();
    }
}
