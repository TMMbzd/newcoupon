package com.newcoupon.application.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预案定义的**启动期校验**（S2 第 10 步的能力，补于第 11 步）。
 *
 * <p><b>为什么需要这个类</b>：{@link SwitchPlan} 里那两条"全量物化"约束写在它的
 * <b>静态初始化块</b>里，而 Java 的静态块只在**类第一次被主动使用时**执行——
 * 不是 Spring 启动时。而 {@code SwitchPlan} 在运行期只被控制器与服务的方法体引用，
 * 也就是说：**一份配错的预案（比如黄预案漏了一个开关）会一直等到有人真的去套用它，
 * 才在那一刻抛异常**——而那通常正是出事的时候。
 *
 * <p>这个组件在容器启动时主动碰一下枚举，把校验提前到"部署就能发现"。
 *
 * <p>（这是文档审查发现的：设计文档写的是"配错直接启动失败"，
 * 而实际行为是"首次使用时才失败"——两者差别很大，尤其对止血工具而言。）
 */
@Component
public class SwitchPlanStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SwitchPlanStartupCheck.class);

    @PostConstruct
    void verifyPlanDefinitions() {
        SwitchPlan[] plans = SwitchPlan.values();   // 触发 SwitchPlan 的静态初始化块
        int entries = 0;
        for (SwitchPlan plan : plans) {
            entries += plan.entries().size();
        }
        log.info("预案定义校验通过：{} 份预案、共 {} 项配置", plans.length, entries);
    }
}
