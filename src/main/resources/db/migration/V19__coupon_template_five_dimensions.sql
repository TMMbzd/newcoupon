-- V19: 券模板五维模型（第 2 环 2-1 项）——只加 V9 没有的三个新列
--
-- V9（P2a schema freeze）已加过四个列（当时域模型未使用，现在激活）：
--   exclusion_group VARCHAR(32)  → 互斥组（域模型同名，直接用）
--   scope_type TINYINT DEFAULT 0 → 适用范围（域模型叫 applicableScope，PO 叫 scopeType，编码一致 0/1/2）
--   funder_type TINYINT DEFAULT 1 → 出资方（域模型叫 budgetOwner，PO 叫 funderType，编码 1/2/3）
--   stacking_group VARCHAR(32)   → 叠加组（Ring 2 不用，留位）
--
-- V19 只加三个真正的新列（V9 没有）：
--   scope_ids TEXT      → 范围值（品类/SKU 的 ID 列表，V9 只有 scope_type 没有 ID 列表）
--   stacking_layer TINYINT → 叠加层级（分层计价概念，V9 的 stacking_group 是另一个概念）
--   budget_ratio INT    → 平台承担百分比（V9 的 funder_type=3 联合出资，但没配比例）
--
-- 教训（用户指出"多思考多调研"的直接验证）：写 V19 前应该先 grep 既有迁移有没有同名列。

ALTER TABLE coupon_template
    ADD COLUMN scope_ids TEXT
        COMMENT '范围值: 品类ID或SKU列表, JSON数组如 [1001,1002]; scope_type=0(全场)时为NULL',
    ADD COLUMN stacking_layer TINYINT NOT NULL DEFAULT 0
        COMMENT '叠加层级: 0=SHOP(店铺级) 1=PLATFORM(平台级); 跨层叠加/同层互斥（分层计价）',
    ADD COLUMN budget_ratio INT
        COMMENT '平台承担百分比(1~99), 仅funder_type=3(联合)时必填; 如70=平台70%商家30%';
