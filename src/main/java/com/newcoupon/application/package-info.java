/**
 * 应用层：用例编排（领券 / 锁券 / 核销 / 释放 / 过期），事务边界在这里。
 * 阶段0第2周填充：CouponReceiveService、CouponTradeService。
 * 分层铁律：application 只编排领域对象与仓储接口，不直接碰 SQL/Redis 细节。
 */
package com.newcoupon.application;
