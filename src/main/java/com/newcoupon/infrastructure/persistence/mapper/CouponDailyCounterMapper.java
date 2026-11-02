package com.newcoupon.infrastructure.persistence.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 模板日限硬计数（V27，ADR-0019）。热路径只有 {@link #tryIncrement}：
 * 条件与自增在同一条 UPDATE 里完成，0 行就是达限。
 *
 * <p>当天第一行不能用纯 UPDATE（0 行会被误判达限）：调用方按
 * {@code ensureRow -> tryIncrement} 两步走；仍 0 行才是真达限。
 * {@code limit} 由调用方传入，0 表示不限时不要调用本 mapper。
 */
@Mapper
public interface CouponDailyCounterMapper {

    /**
     * 确保当天计数行存在，并对重复键行持有排他锁。
     *
     * <p>不能写成 INSERT IGNORE + UPDATE：INSERT IGNORE 命中重复键时先拿共享锁，
     * 后面的 UPDATE 再申请排他锁，两个并发事务会各自持共享锁等对方释放，形成死锁。
     * ON DUPLICATE KEY UPDATE used = used 直接拿排他锁，再 UPDATE 就不会升级锁。
     * 返回值不要当作“是否新插入”判断（驱动 affected/found rows 语义不一致）。
     */
    @Insert("INSERT INTO coupon_daily_counter (template_id, stat_day, used) "
            + "VALUES (#{templateId}, #{day}, 0) ON DUPLICATE KEY UPDATE used = used")
    int ensureRow(@Param("templateId") long templateId, @Param("day") LocalDate day);

    @Update("UPDATE coupon_daily_counter SET used = used + 1 "
            + "WHERE template_id = #{templateId} AND stat_day = #{day} AND used < #{limit}")
    int tryIncrement(@Param("templateId") long templateId,
                     @Param("day") LocalDate day,
                     @Param("limit") long limit);

    @Select("SELECT used FROM coupon_daily_counter WHERE template_id = #{templateId} AND stat_day = #{day}")
    Long selectUsed(@Param("templateId") long templateId, @Param("day") LocalDate day);

    @Delete("DELETE FROM coupon_daily_counter WHERE stat_day < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDate cutoff, @Param("limit") int limit);
}
