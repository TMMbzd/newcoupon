# Phase 1: 核心业务闭环 - 详细实施计划

**目标**: 补齐优惠计算引擎，形成"接入→发券→算钱→用券→对账"完整闭环  
**工期**: 5-7 天  
**优先级**: P0（最高，最关键阶段）  
**状态**: 📋 待启动

---

## 一、Phase 1 的战略意义

### 为什么 Phase 1 最关键？

**当前最严重问题**：
- ❌ 优惠金额由调用方传入（`discountAmt` 参数）
- ❌ 平台不算钱，只是"发券工具"
- ❌ 面试必问"怎么算优惠、怎么分摊"——答不上来

**Phase 1 解决**：
- ✅ 补齐优惠计算引擎（责任链 + 策略模式）
- ✅ 形成端到端业务闭环
- ✅ 系统从"技术设施展示"变成"完整可用中台"

**面试价值**：
- 10 分钟能讲清系统核心价值
- "我做了一个优惠券中台，从券模板配置到优惠计算、核销、分账全链路"
- 技术亮点：责任链模式、策略模式、动态规划（Phase 4 增强）

---

## 二、Phase 1 交付物清单

### 2.1 核心交付

| 交付物 | 说明 | 验收标准 |
|--------|------|---------|
| **优惠计算引擎（最小版）** | 责任链 + 策略模式，满减/折扣两种券 | 10 条黄金用例全绿 |
| **试算接口** | `POST /open/v1/calculate` | curl 返回最优方案 |
| **异步模式受理号** | `mode=async` + 受理号查询 | 演示脚本跑通 |
| **结算分账写入** | `coupon_share_detail` 表逻辑 | 数据落库验证 |
| **端到端演示脚本** | 注册→领券→算钱→核销→结算 | 一键跑通全流程 |
| **术语对齐** | 库存对账文档改写 | 文档审查通过 |

### 2.2 保持基线

| 基线项 | 当前状态 | Phase 1 要求 |
|--------|---------|-------------|
| 测试基线 | 442 例零失败 | **保持零失败** |
| S1 开放接入层 | ✅ 已完成 | **不动** |
| S2 流量治理 | ✅ 已完成 | **不动** |
| S3 Sentinel | ✅ 已完成 | **不动** |

---

## 三、优惠计算引擎设计（最小版）

### 3.1 业界标准四层架构

```
Layer 1: 资格校验层
  ↓ 过滤不可用券（时间/库存/用户等级）
Layer 2: 规则计算层
  ↓ 单券计算 + 互斥检测
Layer 3: 组合优化层
  ↓ 求最优组合（Phase 4 动态规划）
Layer 4: 结果校验层
  ↓ 保底价兜底 + 防刷
```

**Phase 1 实现范围**：
- ✅ Layer 1: 资格校验（时间/库存/门槛）
- ✅ Layer 2: 单券计算（满减/折扣）
- ⚠️ Layer 3: 择优单张（Phase 4 升级多张叠加）
- ⚠️ Layer 4: 基础校验（Phase 4 增强）

---

### 3.2 数据模型设计

#### **3.2.1 券规则扩展**

```sql
-- 扩展 coupon_template 表（V15 迁移）
ALTER TABLE coupon_template 
ADD COLUMN discount_type VARCHAR(16) COMMENT '优惠类型：FULL_REDUCTION/DISCOUNT/INSTANT',
ADD COLUMN threshold BIGINT COMMENT '门槛（分）：满 X 元可用',
ADD COLUMN face_value BIGINT COMMENT '面额（分）：减 Y 元或 折扣率',
ADD COLUMN applicable_scope VARCHAR(16) COMMENT '适用范围：ALL/SKU/CATEGORY',
ADD COLUMN scope_value VARCHAR(512) COMMENT '范围值：SKU ID 列表或品类 ID',
ADD COLUMN stacking_group VARCHAR(32) COMMENT '叠加组：同组可叠加',
ADD COLUMN exclusion_group VARCHAR(32) COMMENT '互斥组：同组只能用一张';

-- 示例数据
INSERT INTO coupon_template (..., discount_type, threshold, face_value) VALUES
(..., 'FULL_REDUCTION', 10000, 2000),  -- 满 100 减 20
(..., 'DISCOUNT', 5000, 9000);         -- 满 50 享 9 折
```

#### **3.2.2 计算请求/响应**

```java
// 计算请求
@Data
public class CalculateRequest {
    @NotNull private String appId;
    @NotNull private Long userId;
    @NotNull private List<OrderItem> items;  // 订单商品行
    @NotNull private List<Long> couponIds;   // 用户选择的券
    @NotNull private String requestId;       // 幂等键
}

@Data
public class OrderItem {
    @NotNull private String skuId;
    @NotNull private String categoryId;
    @NotNull private Long price;      // 单价（分）
    @NotNull private Integer quantity; // 数量
}

// 计算响应
@Data
public class CalculateResponse {
    private Long originalPrice;           // 原价
    private Long discountAmount;          // 优惠金额
    private Long finalPrice;              // 最终价
    private List<CouponUsage> usedCoupons; // 使用的券
    private List<CouponReason> unusableCoupons; // 不可用券及原因
}

@Data
public class CouponUsage {
    private Long couponId;
    private String templateName;
    private Long discountAmount;  // 该券优惠金额
}
```

---

### 3.3 核心代码设计

#### **3.3.1 责任链 + 策略模式**

```java
// 责任链节点：资格校验
public interface EligibilityChecker {
    CheckResult check(Coupon coupon, CalculateContext ctx);
}

public class TimeRangeChecker implements EligibilityChecker {
    @Override
    public CheckResult check(Coupon coupon, CalculateContext ctx) {
        LocalDateTime now = ctx.getNow();
        if (now.isBefore(coupon.getValidStart()) || now.isAfter(coupon.getValidEnd())) {
            return CheckResult.fail("券已过期或未到使用时间");
        }
        return CheckResult.ok();
    }
}

public class ThresholdChecker implements EligibilityChecker {
    @Override
    public CheckResult check(Coupon coupon, CalculateContext ctx) {
        if (ctx.getOrderAmount() < coupon.getThreshold()) {
            return CheckResult.fail("订单金额未达门槛");
        }
        return CheckResult.ok();
    }
}

// 策略模式：不同券类型的计算逻辑
public interface DiscountStrategy {
    Long calculate(Coupon coupon, CalculateContext ctx);
}

public class FullReductionStrategy implements DiscountStrategy {
    @Override
    public Long calculate(Coupon coupon, CalculateContext ctx) {
        // 满减：直接返回面额
        return coupon.getFaceValue();
    }
}

public class DiscountStrategy implements DiscountStrategy {
    @Override
    public Long calculate(Coupon coupon, CalculateContext ctx) {
        // 折扣：订单金额 × (1 - 折扣率)
        Long orderAmount = ctx.getOrderAmount();
        Long rate = coupon.getFaceValue(); // 9000 = 9折
        return orderAmount * (10000 - rate) / 10000;
    }
}

// 计算引擎
@Service
public class DiscountCalculator {
    
    private final List<EligibilityChecker> checkers;
    private final Map<DiscountType, DiscountStrategy> strategies;
    
    public CalculateResponse calculate(CalculateRequest req) {
        // 1. 构建上下文
        CalculateContext ctx = buildContext(req);
        
        // 2. 资格校验（责任链）
        List<Coupon> eligibleCoupons = new ArrayList<>();
        List<CouponReason> unusable = new ArrayList<>();
        
        for (Long couponId : req.getCouponIds()) {
            Coupon coupon = couponRepository.findById(couponId);
            CheckResult result = checkEligibility(coupon, ctx);
            if (result.isOk()) {
                eligibleCoupons.add(coupon);
            } else {
                unusable.add(new CouponReason(couponId, result.getReason()));
            }
        }
        
        // 3. 单券计算（策略模式）
        List<CouponWithDiscount> calculated = eligibleCoupons.stream()
            .map(c -> {
                DiscountStrategy strategy = strategies.get(c.getDiscountType());
                Long discount = strategy.calculate(c, ctx);
                return new CouponWithDiscount(c, discount);
            })
            .collect(Collectors.toList());
        
        // 4. 择优单张（Phase 1 最简单逻辑）
        CouponWithDiscount best = calculated.stream()
            .max(Comparator.comparing(CouponWithDiscount::getDiscount))
            .orElse(null);
        
        // 5. 构建响应
        if (best == null) {
            return CalculateResponse.noDiscount(req.getOrderAmount(), unusable);
        }
        
        Long finalPrice = req.getOrderAmount() - best.getDiscount();
        return CalculateResponse.builder()
            .originalPrice(req.getOrderAmount())
            .discountAmount(best.getDiscount())
            .finalPrice(finalPrice)
            .usedCoupons(List.of(new CouponUsage(best.getCoupon(), best.getDiscount())))
            .unusableCoupons(unusable)
            .build();
    }
    
    private CheckResult checkEligibility(Coupon coupon, CalculateContext ctx) {
        for (EligibilityChecker checker : checkers) {
            CheckResult result = checker.check(coupon, ctx);
            if (!result.isOk()) {
                return result; // 短路：任一校验失败则不可用
            }
        }
        return CheckResult.ok();
    }
}
```

---

### 3.4 接口设计

#### **试算接口**

```java
@RestController
@RequestMapping("/open/v1")
public class CalculateController {
    
    @PostMapping("/calculate")
    public CalculateResponse calculate(@RequestBody @Valid CalculateRequest req) {
        // 认证鉴权（复用 S1 开放接入层）
        AppContext app = authService.authenticate(req.getAppId());
        
        // 调用计算引擎
        return discountCalculator.calculate(req);
    }
}
```

**请求示例**：
```bash
curl -X POST http://localhost:8080/open/v1/calculate \
  -H "Content-Type: application/json" \
  -d '{
    "appId": "APP001",
    "userId": 10001,
    "items": [
      {"skuId": "SKU123", "categoryId": "CAT01", "price": 15000, "quantity": 1}
    ],
    "couponIds": [100001, 100002],
    "requestId": "calc-20261009-001"
  }'
```

**响应示例**：
```json
{
  "originalPrice": 15000,
  "discountAmount": 2000,
  "finalPrice": 13000,
  "usedCoupons": [
    {
      "couponId": 100001,
      "templateName": "满100减20",
      "discountAmount": 2000
    }
  ],
  "unusableCoupons": [
    {
      "couponId": 100002,
      "reason": "订单金额未达门槛"
    }
  ]
}
```

---

## 四、异步模式补充

### 4.1 受理号机制

**接口设计**：
```java
// 1. 异步领券返回受理号
POST /open/v1/coupons/receive?mode=async
Response: {
  "receiptId": "RCP2026100912345678",
  "status": "PROCESSING",
  "message": "领券申请已受理，请稍后查询结果"
}

// 2. 查询受理结果
GET /open/v1/coupons/receipts/{receiptId}
Response: {
  "receiptId": "RCP2026100912345678",
  "status": "SUCCESS",  // PROCESSING/SUCCESS/FAILED
  "couponId": 98765,
  "failReason": null
}
```

**数据表**：
```sql
CREATE TABLE coupon_receipt (
  receipt_id VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,  -- PROCESSING/SUCCESS/FAILED
  coupon_id BIGINT,
  fail_reason VARCHAR(128),
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_user (user_id, created_at)
) ENGINE=InnoDB;
```

**实施步骤**（详见 `docs/research-2026-10-09/业界调研/02-异步库存扣减方案.md` §6）：
- Step 1: 接口层（1 天）
- Step 2: 受理表（0.5 天）
- Step 3: MQ 生产者（1 天）
- Step 4: MQ 消费者（1.5 天）
- Step 5: 测试（1 天）

---

## 五、结算分账补充

### 5.1 数据表（已存在）

```sql
-- coupon_share_detail 表已建（STATUS.md 提及但零写入）
CREATE TABLE coupon_share_detail (
  id BIGINT PRIMARY KEY,
  usage_id BIGINT NOT NULL,
  platform_burden BIGINT COMMENT '平台承担金额（分）',
  merchant_burden BIGINT COMMENT '商户承担金额（分）',
  share_ratio INT COMMENT '分摊比例（平台%）',
  refund_no VARCHAR(64) COMMENT '退款单号',
  refund_amt BIGINT COMMENT '退款金额',
  created_at DATETIME NOT NULL,
  INDEX idx_usage (usage_id)
) ENGINE=InnoDB;
```

### 5.2 写入逻辑

```java
@Service
public class SettlementService {
    
    public void recordShare(Long usageId, Long discountAmt, Integer platformRatio) {
        // 计算分摊
        Long platformBurden = discountAmt * platformRatio / 100;
        Long merchantBurden = discountAmt - platformBurden;
        
        // 写入分账明细
        CouponShareDetail detail = new CouponShareDetail();
        detail.setUsageId(usageId);
        detail.setPlatformBurden(platformBurden);
        detail.setMerchantBurden(merchantBurden);
        detail.setShareRatio(platformRatio);
        detail.setCreatedAt(LocalDateTime.now());
        
        shareDetailRepository.insert(detail);
    }
}

// 在核销接口中调用
@Transactional
public void confirm(ConfirmRequest req) {
    // 原有逻辑：核销占用单、更新券状态
    casConfirm(req.getUsageNo(), req.getDiscountAmt(), now);
    
    // 新增：记录分账
    settlementService.recordShare(usageId, req.getDiscountAmt(), 70); // 平台承担 70%
}
```

---

## 六、端到端演示脚本

### 6.1 脚本设计

```bash
#!/bin/bash
# scripts/end-to-end-demo.sh

set -e

BASE_URL="http://localhost:8080"
APP_ID="APP001"
USER_ID=10001

echo "=== 端到端演示：注册→领券→算钱→核销→结算 ==="

# Step 1: 租户注册（管理员操作）
echo "Step 1: 注册租户..."
REGISTER_RESP=$(curl -s -X POST $BASE_URL/api/apps \
  -H "X-Admin-Token: admin-secret" \
  -H "Content-Type: application/json" \
  -d '{
    "appId": "'$APP_ID'",
    "name": "演示商户",
    "qpsLimit": 100,
    "dailyQuota": 10000
  }')
echo "  租户注册成功: $REGISTER_RESP"

# Step 2: 创建券模板
echo "Step 2: 创建券模板（满100减20）..."
TEMPLATE_RESP=$(curl -s -X POST $BASE_URL/open/v1/templates \
  -H "X-App-Id: $APP_ID" \
  -H "X-Signature: ..." \
  -d '{
    "name": "满100减20",
    "discountType": "FULL_REDUCTION",
    "threshold": 10000,
    "faceValue": 2000,
    "quota": 1000
  }')
TEMPLATE_ID=$(echo $TEMPLATE_RESP | jq -r '.templateId')
echo "  模板创建成功: $TEMPLATE_ID"

# Step 3: 发布模板
echo "Step 3: 发布模板..."
curl -s -X POST $BASE_URL/open/v1/templates/$TEMPLATE_ID/publish
echo "  模板发布成功"

# Step 4: 用户领券
echo "Step 4: 用户领券..."
RECEIVE_RESP=$(curl -s -X POST $BASE_URL/open/v1/coupons/receive \
  -d '{
    "userId": '$USER_ID',
    "templateId": '$TEMPLATE_ID',
    "requestId": "demo-receive-001"
  }')
COUPON_ID=$(echo $RECEIVE_RESP | jq -r '.couponId')
echo "  领券成功: $COUPON_ID"

# Step 5: 优惠计算（试算）
echo "Step 5: 优惠计算（订单金额 150 元）..."
CALC_RESP=$(curl -s -X POST $BASE_URL/open/v1/calculate \
  -d '{
    "appId": "'$APP_ID'",
    "userId": '$USER_ID',
    "items": [{"skuId": "SKU123", "price": 15000, "quantity": 1}],
    "couponIds": ['$COUPON_ID'],
    "requestId": "demo-calc-001"
  }')
DISCOUNT_AMT=$(echo $CALC_RESP | jq -r '.discountAmount')
FINAL_PRICE=$(echo $CALC_RESP | jq -r '.finalPrice')
echo "  原价: 150 元，优惠: $(($DISCOUNT_AMT/100)) 元，到手价: $(($FINAL_PRICE/100)) 元"

# Step 6: 锁券
echo "Step 6: 锁券（下单）..."
LOCK_RESP=$(curl -s -X POST $BASE_URL/open/v1/coupons/lock \
  -d '{
    "couponId": '$COUPON_ID',
    "orderId": "ORDER-DEMO-001",
    "userId": '$USER_ID',
    "usageNo": "USG-DEMO-001"
  }')
echo "  锁券成功"

# Step 7: 核销（支付成功）
echo "Step 7: 核销..."
curl -s -X POST $BASE_URL/open/v1/coupons/confirm \
  -d '{
    "usageNo": "USG-DEMO-001",
    "discountAmt": '$DISCOUNT_AMT'
  }'
echo "  核销成功"

# Step 8: 查询分账记录
echo "Step 8: 查询分账记录..."
SHARE_RESP=$(curl -s -X GET "$BASE_URL/api/settlement/shares?usageNo=USG-DEMO-001")
echo "  平台承担: $(echo $SHARE_RESP | jq -r '.platformBurden') 分"
echo "  商户承担: $(echo $SHARE_RESP | jq -r '.merchantBurden') 分"

echo ""
echo "=== 演示完成！ ==="
echo "✅ 端到端流程验证通过"
```

---

## 七、实施步骤与时间分配

### 7.1 Day 1-2: 数据模型 + 计算引擎核心

**任务**：
- [ ] V15 迁移：扩展 `coupon_template` 表字段（0.5 天）
- [ ] 创建 `coupon_receipt` 表（异步模式）（0.5 天）
- [ ] 实现责任链校验器（3 个：时间/门槛/库存）（0.5 天）
- [ ] 实现策略模式计算（2 个：满减/折扣）（0.5 天）
- [ ] 实现计算引擎主逻辑（择优单张）（1 天）
- [ ] 单元测试：10 条黄金用例（0.5 天）

**验收**：单元测试全绿

---

### 7.2 Day 3: 试算接口 + 集成测试

**任务**：
- [ ] 实现 `CalculateController`（0.5 天）
- [ ] 集成 S1 认证鉴权（0.5 天）
- [ ] 集成测试：端到端计算流程（0.5 天）
- [ ] curl 脚本验证（0.5 天）

**验收**：curl 一条订单返回最优方案

---

### 7.3 Day 4: 异步模式

**任务**：
- [ ] 实现受理号接口（0.5 天）
- [ ] MQ 生产者（发送领券消息）（1 天）
- [ ] MQ 消费者（异步发券 + 更新受理号）（1.5 天）
- [ ] 幂等性测试（0.5 天）

**验收**：异步模式测试通过

---

### 7.4 Day 5: 结算分账 + 端到端脚本

**任务**：
- [ ] `SettlementService` 实现（0.5 天）
- [ ] 核销接口集成分账逻辑（0.5 天）
- [ ] 端到端演示脚本（1 天）
- [ ] 脚本实跑验证（0.5 天）

**验收**：演示脚本一键跑通全流程

---

### 7.5 Day 6: 术语对齐 + 文档

**任务**：
- [ ] 改写库存对账文档（世代→批次快照）（1 天）
- [ ] 补充业界术语映射（长款/短款）（0.5 天）
- [ ] 教学文档：`study/v3/S4-优惠计算引擎.md`（1 天）

**验收**：文档审查通过

---

### 7.6 Day 7: 回归测试 + 收尾

**任务**：
- [ ] 全量测试 `mvn clean test`（1 小时）
- [ ] 修复测试失败（如有）（2 小时）
- [ ] 补充缺失的单元测试（2 小时）
- [ ] 代码审查（自查）（1 小时）
- [ ] 提交 git + 更新 STATUS.md（1 小时）

**验收**：442+ 测试零失败

---

## 八、10 条黄金用例

```java
@SpringBootTest
class DiscountCalculatorTest {
    
    @Autowired
    private DiscountCalculator calculator;
    
    // T1: 单张满减券，满足门槛
    @Test
    void singleFullReductionAboveThreshold() {
        // 订单 150 元，券满 100 减 20
        // 预期：优惠 20 元，到手价 130 元
    }
    
    // T2: 单张满减券，不满足门槛
    @Test
    void singleFullReductionBelowThreshold() {
        // 订单 80 元，券满 100 减 20
        // 预期：不可用，原因"订单金额未达门槛"
    }
    
    // T3: 单张折扣券
    @Test
    void singleDiscountCoupon() {
        // 订单 100 元，券 9 折
        // 预期：优惠 10 元，到手价 90 元
    }
    
    // T4: 多张券，择优单张
    @Test
    void multipleCouponsChooseBest() {
        // 订单 150 元，券 A 满 100 减 20、券 B 9 折
        // 预期：选 A（优惠 20 > 15），到手价 130 元
    }
    
    // T5: 券已过期
    @Test
    void expiredCoupon() {
        // 券有效期 2026-10-01 ~ 2026-10-08
        // 当前时间 2026-10-09
        // 预期：不可用，原因"券已过期"
    }
    
    // T6: 券未到使用时间
    @Test
    void notYetValidCoupon() {
        // 券有效期 2026-10-10 ~ 2026-10-20
        // 当前时间 2026-10-09
        // 预期：不可用，原因"券未到使用时间"
    }
    
    // T7: 订单金额为 0
    @Test
    void zeroOrderAmount() {
        // 订单 0 元，任何券
        // 预期：优惠 0 元，到手价 0 元
    }
    
    // T8: 无可用券
    @Test
    void noCouponsAvailable() {
        // 订单 50 元，券满 100 减 20（门槛未达）
        // 预期：优惠 0 元，到手价 50 元
    }
    
    // T9: 折扣后低于 1 元
    @Test
    void discountExceedsOrderAmount() {
        // 订单 15 元，券满 10 减 20
        // 预期：优惠 15 元（封顶），到手价 0 元
        // 或：优惠 14 元，到手价 1 元（保底价）
    }
    
    // T10: 并发计算幂等性
    @Test
    void concurrentCalculateIdempotency() {
        // 同一 requestId 并发 10 次
        // 预期：10 次返回逐字节相同响应
    }
}
```

---

## 九、风险与挑战

### 9.1 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| V15 迁移破坏现有测试 | 测试大面积失败 | 迁移后立即跑全量测试，逐个修复 |
| 计算引擎性能不足 | RT > 100ms | 单元测试加性能断言（< 50ms） |
| 异步模式 MQ 消费失败 | 发券失败率上升 | 死信队列 + 人工补偿 |

### 9.2 时间风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Day 4 异步模式超时 | 整体工期延期 | 可选简化：跳过异步模式，Phase 4 补 |
| Day 6 术语对齐超时 | 文档质量不达标 | 降级：仅改关键术语，完整改写后置 |

---

## 十、验收标准（硬性指标）

### 10.1 功能验收

- [ ] **优惠计算引擎**：10 条黄金用例全绿
- [ ] **试算接口**：curl 返回正确结果
- [ ] **异步模式**：受理号查询返回正确状态
- [ ] **结算分账**：`coupon_share_detail` 表有数据
- [ ] **演示脚本**：一键跑通全流程，无报错

### 10.2 测试基线

- [ ] **全量测试**：`mvn clean test` → **442+ 例零失败**
- [ ] **跨进程验收**（保持）：集群流控 PASS、演练脚本 PASS

### 10.3 文档验收

- [ ] **教学文档**：`study/v3/S4-优惠计算引擎.md`（≥ 500 行）
- [ ] **术语对齐**：库存对账文档已改写（世代→批次快照）
- [ ] **演示脚本**：`scripts/end-to-end-demo.sh` 可跑通

---

## 十一、Phase 1 完成后的状态

### 11.1 能力矩阵更新

| 能力 | Phase 1 前 | Phase 1 后 |
|------|-----------|-----------|
| 优惠计算引擎 | ❌ 未实现 | ✅ 最小版（责任链+策略） |
| 试算接口 | ❌ 未实现 | ✅ POST /calculate |
| 异步发券模式 | ❌ 未实现 | ✅ 受理号机制 |
| 结算分账 | 🔶 表已建但零写入 | ✅ 核销时写入 |
| 端到端闭环 | ❌ 碎片化 | ✅ 完整可演示 |

### 11.2 面试叙事能力

**Phase 1 前**：
> "我做了一个优惠券系统，有库存扣减、限流熔断、对账..."
> 面试官："优惠怎么算？"
> 答："调用方传入优惠金额..."
> 面试官："？？？"

**Phase 1 后**：
> "我做了一个优惠券中台，从券模板配置、优惠计算、核销到分账的全链路。"
> 面试官："优惠怎么算？"
> 答："责任链模式做资格校验、策略模式做单券计算、择优算法选最优方案，返回到手价。"
> 面试官："叠加多张券呢？"
> 答："Phase 1 是单张择优，Phase 4 会升级成动态规划求最优组合。"

---

## 十二、下一步（Phase 2）

Phase 1 完成后，立即进入 Phase 2：运营完整化（3-4 天）

**核心任务**：
1. 活动实体（coupon_activity 表）
2. 批量发放 API
3. 人群包端口
4. 触达通道（RocketMQ + 短信网关）

**详见**：`docs/research-2026-10-09/实施计划/Phase2-运营完整化.md`（待生成）

---

**Phase 1 实施计划版本**: v1.0  
**创建日期**: 2026-10-09  
**预计开始**: 2026-10-09（今天）  
**预计完成**: 2026-10-15（7 天后）
