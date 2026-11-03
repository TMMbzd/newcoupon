-- V43（V6 阶段 1D 验收 #7）：发券事件补业务上下文列——app_id / biz_type / biz_id。
--
-- coupon_event 未分片（V6 决策：模板维度查询为主，M4 配 ES 宽表），单表直接 ALTER，
-- 不需要像 V9 对 coupon_usage 那样逐物理分表执行。
--
-- 与任务描述"加五列"的偏差（查 V1/V6/V9 后裁定）：activity_id、source 两列 V9
-- 已建（"写时不采即永久丢失"的占位），但代码从未写入——本迁移不重复加列
-- （重复 ADD COLUMN 会让 Flyway 直接报错），只补缺的三列，并顺手把 source 的
-- 注释修成实际口径（SYNC/ASYNC/GRANT/CODE，V9 注释里的"APP/活动/定向/客服/
-- 开放API"从未使用过）。列全可空：这是可观测性字段，不参与任何唯一键与行为判断。
--
-- 归因取值口径（谁填什么）：
--   app_id      发放时从模板行冗余——事件自带租户归属，跨租户统计不必 join 模板表
--   activity_id 发放时从模板行冗余（预算/活动维度归因；idx_activity_time V9 已建）
--   source      发放链路：SYNC 同步领取 / ASYNC 异步任务 / GRANT 批量发放 / CODE 兑换码
--   biz_type    单据背景类型：GRANT_TASK 批量任务 / CODE 兑换码批次（NULL=无单据背景）
--   biz_id      单据号：GRANT_TASK→task_no、CODE→batch_no（运营排障的跳转锚点，用
--               业务号而不是内部 id——人能直接拿去后台搜索）
--
-- idx_biz (biz_type, biz_id)：服务于"这批任务/这批码各发出了哪些券"的排障查询
-- （等值在前，与 idx_tpl_time 同构）。

ALTER TABLE coupon_event
  ADD COLUMN app_id    VARCHAR(32) DEFAULT NULL COMMENT '归属接入方（发放时从模板行冗余；跨租户事件口径，NULL=存量事件）',
  ADD COLUMN biz_type  VARCHAR(16) DEFAULT NULL COMMENT '单据背景类型:GRANT_TASK批量任务/CODE兑换码（NULL=无单据背景的领取）',
  ADD COLUMN biz_id    VARCHAR(64) DEFAULT NULL COMMENT '单据号：批量任务=task_no、兑换码=batch_no（排障跳转锚点，NULL=无）',
  ADD KEY idx_biz (biz_type, biz_id),
  MODIFY COLUMN source VARCHAR(32) DEFAULT NULL COMMENT '发放链路:SYNC同步领取/ASYNC异步任务/GRANT批量发放/CODE兑换码（逐事件事实，不可追补；V9 建列时的注释口径从未启用）';
