-- ADR-0021 Phase 1：审计哈希链跨实例串行化。
-- 之前靠 JVM synchronized，两个实例会同时读同一个 last_hash，形成分叉链。
CREATE TABLE audit_chain_head (
    id         TINYINT     NOT NULL COMMENT '固定 1：全局审计链头',
    last_hash  VARCHAR(64) DEFAULT NULL COMMENT '最后一条 audit_log.record_hash；NULL=创世',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计哈希链头（单行 FOR UPDATE 串行）';

INSERT INTO audit_chain_head (id, last_hash) VALUES (1, NULL);

-- 存量库：链头从现有 audit_log 最后一条接上；空表保持 NULL（首条用 GENESIS）。
UPDATE audit_chain_head
SET last_hash = (SELECT record_hash FROM audit_log ORDER BY id DESC LIMIT 1)
WHERE id = 1
  AND EXISTS (SELECT 1 FROM audit_log);
