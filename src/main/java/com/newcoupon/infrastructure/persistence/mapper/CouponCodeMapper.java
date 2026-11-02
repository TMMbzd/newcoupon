package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.CouponCodePO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 兑换码 Mapper（V41）。兑换的核心是状态机 CAS：
 * ACTIVE→LOCKED（casLock，1000 并发同码只 1 赢家）、LOCKED→USED（markUsed）、
 * LOCKED→ACTIVE（reactivate，发放失败的码回池）。
 * 所有时间列一律 DB NOW(3) 写入——超时回收的判据也是 DB NOW(3)，同源时钟不混用。
 */
@Mapper
public interface CouponCodeMapper extends BaseMapper<CouponCodePO> {

    /** 按哈希精确定位码行（uk_code_hash 覆盖）；null=码不存在。 */
    @Select("SELECT * FROM coupon_code WHERE code_hash = #{codeHash}")
    CouponCodePO selectByHash(@Param("codeHash") String codeHash);

    /**
     * 兑换抢占 CAS：ACTIVE→LOCKED。返回 1=赢家（持锁去调 receive），
     * 0=输家（码已被并发请求抢走/已终态，统一文案失败）。
     * version 同步自增：留给乐观锁审计，状态本身已是 CAS 条件。
     */
    @Update("UPDATE coupon_code SET status = 1, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 0")
    int casLock(@Param("id") long id);

    /**
     * 兑换成功终态：LOCKED→USED 并回填兑换人/时间。条件放宽到 status IN (0,1)：
     * 正常路径是 1（自己 CAS 的 LOCKED）；覆盖"结算前被超时回收回 ACTIVE"的慢赢家
     * （receive 实际发券成功，码不该停留在 ACTIVE 等人再兑）。
     * 返回 0 行=码已 USED（并发慢赢家撞车，见服务层注释），不回滚已发的券。
     */
    @Update("UPDATE coupon_code SET status = 2, user_id = #{userId}, redeem_time = NOW(3), "
            + "version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status IN (0, 1)")
    int markUsed(@Param("id") long id, @Param("userId") long userId);

    /**
     * 兑换失败回池：LOCKED→ACTIVE（售罄/限领等业务失败后码可再兑）。
     * 条件 status=1：只回收自己 CAS 来的锁；已被超时回收/他人重兑的行不动。
     */
    @Update("UPDATE coupon_code SET status = 0, version = version + 1, update_time = NOW(3) "
            + "WHERE id = #{id} AND status = 1")
    int reactivate(@Param("id") long id);

    /**
     * LOCKED 残留兜底回收：兑换中超过 N 分钟未结算（进程崩溃在 receive 与结算之间）
     * 的码回 ACTIVE。判据两侧都是 DB 时钟（update_time 由 NOW(3) 写入，
     * DATE_SUB(NOW(3)) 计算——同源时钟原则，不与应用钟混用）。
     * 回收安全性：receive 幂等键 redeem-{requestId} 保证——券其实已发时，
     * 同人重兑会命中幂等拿回原券并把码置 USED（自愈）；他人重兑则码归他人
     * （已知边界：先发用户的券不损失，仅码的归因瑕疵，注释见服务层）。
     */
    @Update("UPDATE coupon_code SET status = 0, version = version + 1, update_time = NOW(3) "
            + "WHERE status = 1 AND update_time < DATE_SUB(NOW(3), INTERVAL #{minutes} MINUTE)")
    int reapStaleLocked(@Param("minutes") int minutes);

    /** 导出/对账用：按批次取全部码行，id 升序=生成插入序（与明文暂存列表同序）。 */
    @Select("SELECT * FROM coupon_code WHERE batch_id = #{batchId} ORDER BY id")
    List<CouponCodePO> selectByBatch(@Param("batchId") long batchId);

    /** 建批时批量插入码行（只带哈希——明文永不落库），500 一批防巨型 VALUES。 */
    @Insert("<script>INSERT INTO coupon_code (batch_id, code_hash, status, version, create_time, update_time) VALUES "
            + "<foreach collection='codes' item='c' separator=','>"
            + "(#{c.batchId}, #{c.codeHash}, 0, 0, NOW(3), NOW(3))"
            + "</foreach></script>")
    int insertBatch(@Param("codes") List<CouponCodePO> codes);
}
