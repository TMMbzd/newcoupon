package com.newcoupon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 启用定时任务（库存对账等）——运维视角：每个 @Scheduled 都应有监控与幂等
public class NewCouponApplication {

    public static void main(String[] args) {
        SpringApplication.run(NewCouponApplication.class, args);
    }
}
