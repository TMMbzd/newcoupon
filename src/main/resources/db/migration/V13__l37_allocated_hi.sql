-- V13（L37，2026-09-27）：开桶行加"额度段上界"列 + 唯一键——同一段额度只能建一行
--
-- 要解决的问题：开桶分两步——先在 coupon_template 上把额度加进 allocated（一条条件 UPDATE），
-- 再往 stock_bucket 插行。两步之间如果执行线程被暂停（长 GC、数据库阻塞、虚拟机挂起）
-- 超过开桶锁的有效期（5 秒），锁会被别的请求接管；那个请求会把这笔"已划出、还没建行"的
-- 额度补建一行（差额恢复）；被暂停的线程恢复后又会建一行——同一段额度建了两行。
-- 多出来的额度可以真实卖出去（超发），而且对账的三条等式看不出来（各合计同步变大）。
--
-- 修法：每行记下它覆盖的是 allocated 的哪一段的末端位置（allocated_hi = 建行前已建行额度合计 + 本行 quota）；
-- 同一模板内同一个末端位置只允许一行——重复建行会在唯一键上失败，由应用层挡下。

ALTER TABLE stock_bucket
  ADD COLUMN allocated_hi BIGINT NOT NULL DEFAULT 0
    COMMENT '额度段上界：本行覆盖 allocated 的哪一段的末端（建行时 = 已建行额度合计 + 本行 quota）'
    AFTER generation;

-- 回填：历史行的精确段位置无法还原，按模板内固定顺序（桶号、世代、id）累加 quota 作为段末端。
-- 回填值只要求两条：同模板内互不相同；最大值恰好等于当前已建行额度合计。
-- 这样以后新建行的段末端（= 当时的已建行额度合计 + 新额度）必然大于全部历史值，不会误撞。
UPDATE stock_bucket b
  JOIN (
    SELECT id,
           SUM(quota) OVER (PARTITION BY template_id ORDER BY bucket_no, generation, id
                            ROWS UNBOUNDED PRECEDING) AS hi
    FROM stock_bucket
  ) t ON t.id = b.id
SET b.allocated_hi = t.hi;

-- 加唯一键：存量若已有重复的段末端（历史上曾发生过重复建行），这里会失败并中止迁移——本身就是一条断言
ALTER TABLE stock_bucket
  ADD UNIQUE KEY uk_tpl_allocated_hi (template_id, allocated_hi);
