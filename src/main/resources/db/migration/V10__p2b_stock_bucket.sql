-- V10（P2b 库存批次分片 v2 §8.1，2026-09-18 七簇裁决 A/E 落地）
-- 内容：①coupon_template 补 allocated 列（开桶 CAS 的原子递增目标，单调不减）
--       ②stock_bucket 分桶账本表（超发红线的 DB 承重墙——热行从模板行分散到桶行）
--       ③存量回填（allocated=issued_count + 每存量模板一行"历史代"封账行——保 SUM(used)=COUNT 真值链）
--       ④迁移内断言（allocated>total_count 即 SIGNAL 中止——测试团队 COMP-2/CHAO-7 处置要求）
-- 不回填的后果：存量在售模板 allocated=0 → 开桶协议从零分配 → ic 之上再发全额 = 上线首日超发。

-- ===================================================================
-- ① coupon_template 补 allocated 列
-- ===================================================================
ALTER TABLE coupon_template
  ADD COLUMN allocated BIGINT NOT NULL DEFAULT 0
    COMMENT '已分配到桶的额度（单调不减，只被开桶CAS推进；重发布=对账后开桶，禁止重置——v2 §9）'
    AFTER total_count;

-- ===================================================================
-- ② stock_bucket 分桶账本（A 簇：领券事务内逐笔条件扣的落点）
-- ===================================================================
CREATE TABLE stock_bucket (
  id            BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
  template_id   BIGINT       NOT NULL COMMENT '模板id',
  bucket_no     INT          NOT NULL COMMENT '桶号（槽位制 0..bucket_count-1，hash(userId) 路由）',
  generation    INT          NOT NULL DEFAULT 1 COMMENT '世代（同桶号耗尽重开=新世代新账——v2 §4 世代隔离，迟到操作按令牌世代寻址）',
  quota         BIGINT       NOT NULL COMMENT '本代额度（开桶 CAS：allocated+min(BUCKET_SIZE, total-allocated)）',
  used          BIGINT       NOT NULL DEFAULT 0 COMMENT '本代已用（逐笔条件扣 used<quota——超发红线）',
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0活跃 1封账待GC（旧代） 2已归档',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tpl_bucket_gen (template_id, bucket_no, generation),
  KEY idx_tpl_status (template_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '库存分桶账本（P2b：桶行分散热行；E3b/E3c 分代镜像的 DB 侧锚）';

-- ===================================================================
-- ③ 存量回填：allocated 对齐 + 历史代封账行（generation=0 只此一批，新桶从 1 起）
-- ===================================================================
-- 在售模板（已发布且有未卖余量）：allocated 直接对齐 total（全量分配策略），并补一行
-- **活跃代**承载余量——否则存量模板无 status=0 行，升级后首个请求 -7 死角（ensure 空、
-- 开桶挂在 -10 永不可达、重发布被 CAS 挡）→ 永久 DEGRADED（三视角审查 2026-09-19 P1）
UPDATE coupon_template SET allocated = issued_count;
UPDATE coupon_template SET allocated = total_count
  WHERE publish_status = 1 AND issued_count < total_count;

INSERT INTO stock_bucket (template_id, bucket_no, generation, quota, used, status)
  SELECT id, 0, 0, issued_count, issued_count, 1
  FROM coupon_template WHERE issued_count > 0;
INSERT INTO stock_bucket (template_id, bucket_no, generation, quota, used, status)
  SELECT id, 0, 1, total_count - issued_count, 0, 0
  FROM coupon_template WHERE publish_status = 1 AND issued_count < total_count;

-- ===================================================================
-- ④ 迁移内断言：allocated 不得越过 total_count（数据被污染时中止迁移而非带病上线）
-- ===================================================================
DELIMITER //
CREATE PROCEDURE v10_assert_allocated()
BEGIN
  IF EXISTS (SELECT 1 FROM coupon_template WHERE allocated > total_count) OR
     EXISTS (SELECT 1 FROM coupon_template t WHERE allocated <
             (SELECT COALESCE(SUM(quota),0) FROM stock_bucket b WHERE b.template_id = t.id)) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V10 assertion failed: allocated 越界或与 Σquota 不一致（存量数据被污染，先修数据再迁移）';
  END IF;
END//
CALL v10_assert_allocated()//
DROP PROCEDURE v10_assert_allocated//
DELIMITER ;
