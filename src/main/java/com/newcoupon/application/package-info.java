/**
 * 应用层：用例编排与事务边界。现状：
 *   ReceiveAppService(领券编排,无事务) + CouponIssueTxService(领券DB事务)
 *   TradeAppService(用券编排,无事务)   + TradeTxService(用券三段DB事务)
 *   TemplateAppService(建券/发布) + StockReconcileService(库存对账)
 * 分层铁律：编排层严禁 @Transactional（rollback-only 退化，见 study/04a）；
 * 事务收在独立 TxService（防自调用失效）；application 只编排领域对象与仓储端口。
 */
package com.newcoupon.application;
