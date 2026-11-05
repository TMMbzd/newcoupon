package com.newcoupon.domain.risk;

/**
 * 名单值的维度（risk_list_entry.value_type，V6 4A）。
 *
 * <p>USER_ID/DEVICE_ID 三者是领券链路上已有的身份维度；IP 与 IpRateGate 的
 * 频控共用同一份提取规则（容器解析的 getRemoteAddr——见 RiskControlFilter.clientIp，
 * 应用层不读代理头）。设备号是 **X-Device-Id 透传值**：客户端可换，挡的是
 * "不换设备头的脚本"，不是可信指纹——商用指纹 SDK 只留端口（runbook §裁剪登记）。
 */
public enum RiskValueType {

    /** 业务用户 id（risk_list_entry.value 存十进制字符串） */
    USER_ID,

    /** 设备号（X-Device-Id 透传，≤128 字符） */
    DEVICE_ID,

    /** 客户端 IP（与 RiskControlFilter 同一提取规则） */
    IP
}
