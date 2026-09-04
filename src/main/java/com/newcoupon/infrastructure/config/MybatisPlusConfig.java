package com.newcoupon.infrastructure.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 *
 * <p>为什么这个配置类必须存在：PO 上的 @Version 注解**只有**注册了 OptimisticLockerInnerInterceptor
 * 才会生效——只加注解不配拦截器，乐观锁静默失效且无任何报错（MyBatis-Plus 最经典的暗坑，
 * 上线后并发更新互相覆盖才发现）。配注解与配拦截器必须成对出现，review 时互相作为检查点。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：updateById(entity) 时自动追加 WHERE version=? 并 SET version=version+1
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 分页插件在需要分页查询时再加（用不到的能力不提前引入）
        return interceptor;
    }
}
