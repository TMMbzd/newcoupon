package com.newcoupon.infrastructure.persistence.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 每人每日已发计数（V27，ADR-0019）。与模板日限计数一样：
 * 条件自增是硬约束，COUNT 只用于回填/对账，不再进入 worker 热路径。
 */
@Mapper
public interface CouponUserDailyCounterMapper {

    /**
     * 确保当天计数行存在，并对重复键行持有排他锁。
     *
     * <p>不能写成 INSERT IGNORE + UPDATE：重复键检查先拿共享锁，后面的 UPDATE
     * 再申请排他锁，会形成 S→X 锁升级死锁。ON DUPLICATE KEY UPDATE used = used
     * 直接拿排他锁。返回值不要当作“是否新插入”判断。
     */
    @Insert("INSERT INTO coupon_user_daily_counter (user_id, template_id, stat_day, used) "
            + "VALUES (#{userId}, #{templateId}, #{day}, 0) "
            + "ON DUPLICATE KEY UPDATE used = used")
    int ensureRow(@Param("userId") long userId,
                  @Param("templateId") long templateId,
                  @Param("day") LocalDate day);

    @Update("UPDATE coupon_user_daily_counter SET used = used + 1 "
            + "WHERE user_id = #{userId} AND template_id = #{templateId} "
            + "AND stat_day = #{day} AND used < #{limit}")
    int tryIncrement(@Param("userId") long userId,
                     @Param("templateId") long templateId,
                     @Param("day") LocalDate day,
                     @Param("limit") long limit);

    @Select("SELECT used FROM coupon_user_daily_counter "
            + "WHERE user_id = #{userId} AND template_id = #{templateId} AND stat_day = #{day}")
    Long selectUsed(@Param("userId") long userId,
                    @Param("templateId") long templateId,
                    @Param("day") LocalDate day);

    @Delete("DELETE FROM coupon_user_daily_counter WHERE stat_day < #{cutoff} LIMIT #{limit}")
    int deleteBefore(@Param("cutoff") LocalDate cutoff, @Param("limit") int limit);
}
