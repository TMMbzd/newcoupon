-- V5（L2 券过期全链路，docs/06 v2 兜底层 + docs/L2 技术方案 D3）：过期任务表。
-- 为什么是窄表而非扫主表：user_coupon 千万级，扫它+批量改=大事务长锁（后台批处理卡死前台交易）；
-- 本表只存"哪张券、该何时处理、处理到哪了"三要素，走精确索引，每批几百条小事务，中断后按状态位续跑。
-- 无"处理失败"状态：失败只推进 next_scan_time（指数退避 min(2^retry, 30min)），
-- 超过 8 次置死信(3)等人工——扫描条件永远只有 status=0 一种值，不会漏扫组合。

CREATE TABLE coupon_expire_task (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    coupon_id      BIGINT      NOT NULL COMMENT 'user_coupon.id',
    user_id        BIGINT      NOT NULL COMMENT '冗余用户id（M4 分片路由预留，与主表同规则）',
    shard          INT         NOT NULL DEFAULT 0 COMMENT '扫描分片号 = coupon_id % 16（分片广播预留）',
    next_scan_time DATETIME    NOT NULL COMMENT '下次应扫描时间=券有效期终点；失败退避只推进此字段',
    status         TINYINT     NOT NULL DEFAULT 0 COMMENT '0待处理 1已终结 3死信(重试超限,人工)',
    retry_count    INT         NOT NULL DEFAULT 0,
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_coupon (coupon_id),
    KEY idx_scan (status, next_scan_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '过期任务表=持久化待办队列（时间驱动的状态修复）';

-- 占用单状态扩展（无 DDL，代码枚举增值）：coupon_usage.status 新增 3=过期关闭。
-- 这是占用单的第三种终结方式：非核销（无补贴）、非释放（券不回可用）——锁定期间券过期的收场，
-- 财务对账需可分辨三种死法。
