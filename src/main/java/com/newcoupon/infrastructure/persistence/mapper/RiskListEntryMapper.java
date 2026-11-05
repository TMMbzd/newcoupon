package com.newcoupon.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.newcoupon.infrastructure.persistence.po.RiskListEntryPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 风控名单 Mapper（V48，V6 4A）。
 *
 * <p>命中查询（{@link #selectActiveHits}）一次等值查询取回"平台级 + 本租户"两层的
 * 全部 ACTIVE 未过期行，名单类型（黑/白/灰）的优先级判定留在规则代码里——
 * SQL 只做"什么是活的"，代码做"活的里面谁说了算"，两者职责不混。
 */
@Mapper
public interface RiskListEntryMapper extends BaseMapper<RiskListEntryPO> {

    /**
     * 按自然键查行（幂等重放判断）：list_type + value_type + value + app_id。
     * app_id 传空串=平台级（PO/存储层口径，见 RiskListEntryPO 类注释）。
     */
    @Select("SELECT * FROM risk_list_entry "
            + "WHERE list_type = #{listType} AND value_type = #{valueType} "
            + "AND value = #{value} AND app_id = #{appId}")
    RiskListEntryPO selectByNaturalKey(@Param("listType") String listType,
                                       @Param("valueType") String valueType,
                                       @Param("value") String value,
                                       @Param("appId") String appId);

    /**
     * 决策时的一次维度命中查询：该值在"平台级（app_id=''）或指定租户"两层的
     * 全部 ACTIVE 且未过期行。过期在 SQL 侧过滤（expires_at IS NULL OR &gt; now）——
     * 过期行不改不删，申诉与审计还要看。
     */
    @Select("SELECT * FROM risk_list_entry "
            + "WHERE value_type = #{valueType} AND value = #{value} "
            + "AND status = 'ACTIVE' "
            + "AND (expires_at IS NULL OR expires_at > #{now}) "
            + "AND (app_id = '' OR app_id = #{appId})")
    List<RiskListEntryPO> selectActiveHits(@Param("valueType") String valueType,
                                           @Param("value") String value,
                                           @Param("appId") String appId,
                                           @Param("now") LocalDateTime now);

    /**
     * 停用（软删）：条件 UPDATE，并发重复停用只有一个能改中；
     * 0 行由服务层重读分类（已停用=幂等成功 / 不存在=1002）。
     */
    @Update("UPDATE risk_list_entry SET status = 'DISABLED' "
            + "WHERE id = #{id} AND status = 'ACTIVE'")
    int disableById(@Param("id") Long id);
}
