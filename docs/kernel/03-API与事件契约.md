# 03 · API 与事件契约

> **状态**：v0.1（2026-10-18，待评审）。
> **读者**：产品接入方、内核开发、测试。
> **前置阅读**：[00 定位与能力边界](00-定位与能力边界.md)、[01 内核能力契约](01-内核能力契约.md)、[02 领域模型与数据契约](02-领域模型与数据契约.md)。
> **这份文档回答**：有哪些接口、每个接口的字段与语义、怎么认证/幂等/重试、事件长什么样、版本怎么兼容、哪些还没实现。
> **不是这份文档的内容**：内部表结构（02）、非功能能力地图（07）、实施排期（05）。

**状态标记**：✅ 当前可用；◐ 部分；○ 目标态，未实现。

---

## 0. 通用约定

| 项 | 约定 |
|---|---|
| 协议 | HTTPS + JSON（`application/json; charset=UTF-8`） |
| 开放面前缀 | `/open/v1`（appId + HMAC 签名） |
| 内部面前缀 | `/api`（平台运营面，管理令牌/业务写白名单；**不构成多租户隔离**） |
| 金额单位 | 分（整数）；不使用浮点 |
| 时间 | ISO-8601 字符串；时区 `Asia/Shanghai`；日切 00:00 |
| 响应体 | 统一 `{ "code": int, "message": string, "data": any }`；`code=0` 成功 |
| HTTP 状态 | 认证/限流/签名等接入层错误用真实 HTTP（401/403/409/429/400）；业务错误通常 HTTP 200 + 业务码 |
| 请求大小 | 商品行最多 100；试算券最多 10；请求体大小上限属入口/网关（目标） |
| 字符集 | UTF-8；字段长度按 DTO 校验 |

### 0.1 认证（开放面）

请求头：

```http
Authorization: NC-HMAC-SHA256 Credential=<appId>, Timestamp=<epochMillis>, Nonce=<8-64位>, Signature=<64位hex>
```

- `Timestamp`：毫秒；服务端允许 ±5 分钟；
- `Nonce`：8~64 位字母/数字/下划线/连字符；同一 appId 下不可重放；
- `Signature`：对规范化字符串做 HMAC-SHA256，输出 64 位十六进制；
- 规范化字符串（每行一个字段，`\n` 分隔）：

```text
METHOD
PATH
CANONICAL_QUERY
BODY_SHA256_HEX
APP_ID
TIMESTAMP
NONCE
```

- `CANONICAL_QUERY`：按 `&` 切分后整段字典序排序；不做 URL 解码；
- `BODY_SHA256_HEX`：请求体的 SHA-256（空体按空数组）；
- 密钥：每个 appId 一份，由开放平台发放；轮换策略见 07；
- 未知签名头字段忽略（向前兼容）；任一必需字段缺失/格式错 → 6001。

### 0.2 幂等（开放面写接口）

- 请求头 `X-Client-Token: [A-Za-z0-9_-]{8,64}`；
- 同 token + 同参数 → 返回首次响应，响应头 `X-Idempotent-Replay: true`；
- 同 token + 不同参数 → `409 / 6008`；
- 同 token 处理中 → `409 / 6009`，用同一 token 稍后重试；
- 快照保留 24 小时；处理中占位 60 秒；
- **只对 `/open/v1` 的 POST 生效**；`/api` 写接口不校验 token；
- Redis 不可用时按设计 fail-open：幂等缓存不可用，请求会再次执行；业务层幂等键（`usageNo`/`refundNo`/领取 `requestId`）仍生效，但模板创建没有业务幂等键。

### 0.3 错误响应

```json
{
  "code": 4004,
  "message": "占用单状态冲突: 占用单状态=已核销，仅占用中可核销",
  "data": null
}
```

- 错误码家族与重试性见 01 §6；本文每个接口只列“常见错误”；
- 不要在 `message` 里放内部堆栈/SQL/密钥；
- 业务错误通常 HTTP 200；认证/限流/签名错误用真实 HTTP 状态。

---

## 1. 接口总表

| # | 方法 | 路径 | 作用 | 开放面 | 内部面 | 幂等键 | 状态 |
|---|---|---|---|---|---|---|---|
| 1 | POST | `/templates` | 建券模板 | ✅ | ✅（管理令牌） | `X-Client-Token`（开放面） | ✅ |
| 2 | POST | `/templates/{id}/publish` | 发布模板 | ✅ | ✅（管理令牌） | 同 token 回放；重复发布 2003 | ✅ |
| 3 | GET | `/templates/{id}` | 查模板 | ✅ | ✅ | 只读 | ✅ |
| 4 | POST | `/coupons/receive` | 领券 | ✅ | ✅（白名单） | `requestId` + token | ✅ |
| 5 | POST | `/calculate` | 试算 | ✅ | ❌（无 `/api` 路由） | 只读 | ✅ |
| 6 | POST | `/coupons/lock` | 锁券 | ✅ | ✅（白名单） | `(orderNo,couponId)` | ✅ |
| 7 | POST | `/coupons/confirm` | 核销 | ✅ | ✅（白名单） | `usageNo` | ✅ |
| 8 | POST | `/coupons/release` | 释放 | ✅ | ✅（白名单） | `usageNo` | ✅ |
| 9 | POST | `/coupons/refund` | 退款（整单/按行） | ✅ | ✅（白名单） | `refundNo` | ✅ |
| 10 | GET | `/coupons?userId=&status=` | 查券包 | ✅ | ✅ | 只读 | ✅ |
| 11 | GET | `/coupons/refunds?refundNo=|usageNo=` | 查退款明细 | — | — | 只读 | ○ |
| 12 | GET | `/coupons/bills?date=&appId=` | 查日账单/主体汇总 | — | — | 只读 | ○ |
| 13 | POST | `/webhooks` | 注册事件 Webhook | — | — | — | ○ |

> 内部面路径 = 把 `/open/v1` 换成 `/api`（如 `/api/coupons/lock`）；`/api` 不是多租户隔离面，只用于平台运营。

---

## 2. 模板接口

### 2.1 建模板 `POST /open/v1/templates`

**请求字段**（`TemplateCreateRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string(128) | 是 | 券名称 |
| `activityId` | long | 是 | 活动 ID（平台预算/活动维度） |
| `couponType` | int | 是 | 1 满减 / 2 满折 / 3 无门槛 |
| `faceValue` | long | 是 | 满减/无门槛=分；满折=基点（8500=85折） |
| `discountCap` | long | 满折必填 | 满折优惠上限（分）；其他类型必须不传 |
| `threshold` | long | 满减必填 | 门槛（分）；无门槛=0 |
| `totalCount` | long | 是 | 总库存 |
| `perUserLimit` | int | 是 | 每人限领 |
| `dailyLimit` | long | 否 | 批次日限（0/空=不限） |
| `perUserDailyLimit` | int | 否 | 每人日限（0/空=不限） |
| `validType` | int | 是 | 1 固定区间 / 2 领取后 N 天 |
| `validStart`/`validEnd` | datetime | validType=1 必填 | 固定有效期 |
| `validDays` | int | validType=2 必填 | 领取后 N 天 |
| `applicableScope` | int | 否 | 0 全场 / 1 品类 / 2 单品（空=全场） |
| `scopeIds` | string(512) | 非全场必填 | JSON 数组文本，如 `["CAT_FOOD"]` |
| `stackingLayer` | int | 否 | 0 店铺级 / 1 平台级（空=店铺级） |
| `exclusionGroup` | string(32) | 否 | 互斥组名；空=默认互斥 |
| `budgetOwner` | int | 否 | 1 平台 / 2 商家 / 3 共担（空=平台） |
| `budgetRatio` | int | 共担必填 | 平台承担百分比 1~99 |
| `settlePartyId` | string(64) | ○ 目标 | 结算主体；商家出资/共担必填；平台券可空 |

**响应**（`CreatedTemplate`）：

```json
{ "code": 0, "message": "成功", "data": { "id": 123, "templateCode": "T123" } }
```

**常见错误**：1000 参数/领域校验；2003 状态不允许；5001 降级。

**说明**：

- 发布后面额/门槛/范围/出资/结算主体/有效期不可直接改；要改走新模板/新版本；
- 建模板不是业务幂等：换新 token 重复建会建出两张模板；开放面同 token 重放才返回首次响应。

### 2.2 发布 `POST /open/v1/templates/{id}/publish`

- 请求：路径参数 `id`；无 body；
- 响应：`{ "code": 0, "message": "成功", "data": null }`；
- 重复发布：`2003`（模板状态不允许）或 `4002`（并发 CAS），不是幂等成功；
- 开放面同 `X-Client-Token` 重放可返回首次响应；内部面不保证。

### 2.3 查模板 `GET /open/v1/templates/{id}`

**响应字段**（`TemplateResponse`）：`id`、`templateCode`、`name`、`couponType`、`faceValue`、`discountCap`、`threshold`、`totalCount`、`issuedCount`、`perUserLimit`、`dailyLimit`、`perUserDailyLimit`、`validType`、`validStart`、`validEnd`、`validDays`、`approveStatus`、`publishStatus`、`createTime`、`applicableScope`、`scopeIds`、`stackingLayer`、`exclusionGroup`、`budgetOwner`、`budgetRatio`。目标新增 `settlePartyId`。

> `createTime` 当前可能为 null（查询场景未从 PO 带出）；不要依赖它做业务判断。

---

## 3. 领券 `POST /open/v1/coupons/receive`

**请求**（`ReceiveRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `requestId` | string(64) | 是 | 一次领取意图的幂等键；重试复用同一个；仅字母/数字/下划线/连字符 |
| `userId` | long | 是 | 终端用户 ID（**内核不认证终端用户，见 01 §9**） |
| `templateId` | long | 是 | 模板 ID |

**响应**（`ReceiveResult`）：`couponId`、`templateCode`、`faceValue`、`validEnd`、`duplicated`。

**幂等**：`(userId, requestId)` 唯一；同键重放返回第一次的券，`duplicated=true`；`1001` 可能表示“处理中”，不要当成功，拿同一 requestId 重试。

**常见错误**：2001/2002/3001/3002/3003/5001。

---

## 4. 试算 `POST /open/v1/calculate`

**请求**（`CalculateRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | long | 是 | 终端用户 |
| `items` | array | 是 | 商品行，最多 100 |
| `couponIds` | array<long> | 是 | 候选券，最多 10 |

**OrderItem**：`skuId`、`categoryId`、`price`（分）、`quantity`、`lineNo`（可选，≤64；不传生成 `L1/L2...`）。目标新增 `merchantId`/`settlePartyId`。

**响应**（`CalculateResponse`）：

| 字段 | 说明 |
|---|---|
| `originalPrice` | 订单原价（分） |
| `discountAmount` | 总优惠（分） |
| `finalPrice` | 到手价（分） |
| `usedCoupons` | 生效券：`couponId`、`templateName`、`layer`、`discountAmount` |
| `allocations` | 每行分摊：`lineNo`、`skuId`、`lineAmount`、`allocatedDiscount`、`couponShares`（每券金额） |
| `unusableCoupons` | 不可用券及原因 |
| `pricingId` | ○ 目标：本次试算的价格快照 ID |

**说明**：

- 试算只读，不锁券/不扣库存；
- 同层同互斥组最多 1 张中选，不同组可叠加；每层按剩余金额递进；
- `allocations` 合计 = `discountAmount`；
- 目标 `pricingId` 一次试算一个快照，覆盖整单券集合；带它核销时价格冻结。

**常见错误**：1000 参数；券不可用放在 `unusableCoupons`，不是整体失败。

---

## 5. 锁券 `POST /open/v1/coupons/lock`

**请求**（`LockRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `orderNo` | string(64) | 是 | 订单号 |
| `userId` | long | 是 | 终端用户 |
| `couponId` | long | 是 | 用户券 ID |
| `pricingId` | string(64) | ○ 目标 | 价格快照 ID |

**响应**（`LockResult`）：`usageNo`、`couponId`、`couponStatus`、`duplicated`。

**幂等**：业务键 `(orderNo,couponId)`；同一订单重复锁同一券、仍占用中 → `duplicated=true`；已终结 → 4002。**不要拿 `usageNo` 作为首次锁券的重试键**（首次响应丢失时调用方还没有它）。

**常见错误**：4001/4002/3004/5001。

---

## 6. 核销 `POST /open/v1/coupons/confirm`

**请求**（`ConfirmRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `usageNo` | string(64) | 是 | 锁券返回的占用单号 |
| `discountAmt` | long | 二选一 | 老式金额（过渡兼容）；与 items 二者必须提供其一 |
| `items` | array | 二选一 | 商品行（≤100）；平台算钱/行级分账走这个 |
| `pricingId` | string(64) | ○ 目标 | 价格快照 ID |

**OrderItem**：同试算。

**响应**（`ConfirmResult`）：`usageNo`、`couponStatus`、`discountAmt`、`duplicated`。

**语义**：

- 带 `items`：平台按订单级整栈复算，写行级分账；
- 不带 `items`：老式路径，按 `discountAmt` 记账，**不写行级分账、不做门槛/范围/占比复算、不进日账单**；
- 已核销重放返回首次金额；其他终态 4004/4006；
- 带 `pricingId` 时按快照（目标）；快照过期/归属不符/券集合不一致 fail-closed。

**常见错误**：1000/3004/4002/4003/4004/4005/4006/5001。

---

## 7. 释放 `POST /open/v1/coupons/release`

**请求**（`ReleaseRequest`）：`usageNo`、`reason`（≤128，可选）。
**响应**（`ReleaseResult`）：`usageNo`、`couponStatus`、`duplicated`。

**幂等**：已释放/过期关闭/超时关闭 → 幂等成功 + 真实券状态；已核销/退款态 → 4004。

**常见错误**：4003/4004/5001。

---

## 8. 退款 `POST /open/v1/coupons/refund`

**请求**（`RefundRequest`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `usageNo` | string(64) | 是 | 占用单号 |
| `refundNo` | string(64) | 新接入方必须传 | 退款幂等键；不传生成 `AUTO:<usageNo>`（旧调用方兼容，只适合整单退一次）；全局唯一 |
| `items` | array | 否 | 退哪些行/数量；空=退剩余全部 |

**RefundItem**：`lineNo`（≤64）、`quantity`（≥1）。

**响应**（`RefundResult`）：`usageNo`、`refundNo`、`refundType`（PARTIAL/FULL）、`couponStatus`、`usageStatus`、`discountAmt`（**本次**冲回）、`duplicated`。

**语义**：

- 读核销时的行级分摊快照，不按当前模板重算；
- 同号同参重放返回首次结果；同号异参 1000；同号跨单拒绝；
- 累计数量/金额不超原分账；同一占用单最多 50 次；
- 部分退款券保持 USED；整单退完且券在有效期内回 USABLE，已过期转 EXPIRED；一券限退 3 次；
- 老式核销（无 items）只能整单退，只写退款单、无退款明细。

**常见错误**：1000/4003/4004/5001。

---

## 9. 查券包 `GET /open/v1/coupons?userId=&status=`

**参数**：`userId`（必填）、`status`（可选：0 待使用/1 已锁定/2 已核销/3 已过期/4 已作废）。
**响应**（`MyCouponList`）：`coupons[]`，每项 `id`、`templateCode`、`faceValue`、`threshold`、`discountCap`、`validEnd`、`status`。

**说明**：这是当前唯一的查询接口；按订单/占用单/退款明细/账单的查询是目标态（见 §1 #11/#12）。

---

## 10. 事件与 Webhook

### 10.1 当前状态

- 内部事件表 `coupon_event` 记录发放/锁定/核销/释放/过期/锁定超时/退款；
- 发件箱 `coupon_outbox` 按事件类型投递 RocketMQ（`coupon-event-<type>`）；
- **没有对外 Webhook 注册/签名/重试/死信接口**；对外事件是目标态。

### 10.2 事件类型

| 类型 | 触发 | 金额 | 当前 |
|---|---|---|---|
| ISSUED | 发券 | null | ✅ 内部 |
| LOCKED | 锁券 | null | ✅ 内部 |
| USED | 核销 | 实际优惠 | ✅ 内部 |
| RELEASED | 释放 | null | ✅ 内部 |
| EXPIRED | 过期 | null | ✅ 内部 |
| INVALIDATED | 作废 | null | ◐ 事件类型有，产生点少 |
| LOCK_TIMEOUT | 锁定超时收场 | null | ✅ 内部 |
| REFUNDED | 退款冲销 | 本次冲回 | ✅ 内部 |

### 10.3 目标事件信封

```json
{
  "eventId": "evt_20261018_000001",
  "eventType": "USED",
  "eventVersion": 1,
  "occurredAt": "2026-10-18T12:00:00+08:00",
  "appId": "app-A",
  "userId": 1001,
  "couponId": 9001,
  "usageNo": "ORD-1:9001",
  "orderNo": "ORD-1",
  "amount": 2000,
  "data": {
    "settlePartyId": "__default__",
    "refundNo": null
  }
}
```

**投递语义（目标）**：

- 至少一次；消费方按 `eventId` 幂等去重；
- 不保证顺序；需要权威状态时回调查询接口；
- 失败重试 + 退避 + 死信；Webhook 用 HMAC 签名 + 时间窗 + nonce；
- `eventVersion` 用于 Schema 演进；新增字段可选，不删不改已有字段；
- 事件不是资金事实源：金额/状态以查询接口/分账/退款明细为准。

---

## 11. 版本与兼容

- 开放面 `/open/v1`；破坏性变更出 `/open/v2`；
- 字段只增不改；新增字段可选；客户端忽略未知字段；
- 错误码只增不改；
- 废弃：公告 → 双版本并行 → 迁移期 → 下线；
- 契约测试：提供方维护；接入方接入前跑；
- 沙箱：目标态；外部产品先在沙箱跑通试算/锁/核销/退款；
- `pricingId`、`settlePartyId` 等目标字段在未启用前不要发送/依赖。

---

## 12. 限流、配额与超时

| 项 | 当前/默认 | 错误 |
|---|---|---|
| 租户 QPS | 按 appId 配置 | 6006（429） |
| 租户日配额 | 按 appId 配置 | 6007（429） |
| 单次试算券数 | 10 张候选 | 1000 |
| 单次商品行 | 试算/核销 100 行 | 1000 |
| 锁券窗口 | 30 分钟 | 超时后核销 4006 |
| 退款次数 | 50 次/占用单 | 4004（上限） |
| 退券次数 | 3 次/券 | 不再退券，不报错 |
| 核销速率告警 | 600 次/分钟/appId | 只告警不拦截（单机内存，仅带 items 的核销） |
| 请求时间预算 | `X-Timeout-Ms`，默认 3000ms | 5003 |
| 请求体大小 | 入口/网关限制（目标） | 413/400（目标） |

**入口开关**：平台可按租户/全站临时关闭领券、锁券、核销、释放、退款、模板发布等入口；关闭返回 `5001`（可重试的临时降级），不是永久下线。永久下线/接口退役用 `6002/6010`，不要用开关假装永久下线。

---

## 13. 安全与信任边界

- 开放面只认证应用（appId）；**不认证终端用户**；`userId` 由调用方提供并信任，调用方必须保证真实性和归属；
- `Authorization` 使用 HMAC-SHA256；密钥按 appId 发放/轮换；
- `nonce` 防重放；时间窗 ±5 分钟；
- `/api` 用管理令牌/业务写白名单，是平台运营面，不承诺多租户隔离；
- 错误信息不泄露内部堆栈/SQL/密钥；日志不打印敏感明文；
- TLS 由入口/网关终止；WAF/DDoS 属入口层（目标/运维）。

---

## 14. 示例

### 14.1 试算

```json
POST /open/v1/calculate
{
  "userId": 1001,
  "items": [
    {"skuId": "SKU_A", "categoryId": "CAT_FOOD", "price": 6000, "quantity": 2, "lineNo": "L1"},
    {"skuId": "SKU_B", "categoryId": "CAT_FOOD", "price": 4000, "quantity": 2, "lineNo": "L2"}
  ],
  "couponIds": [9001]
}
```

响应（节选）：

```json
{
  "code": 0,
  "message": "成功",
  "data": {
    "originalPrice": 20000,
    "discountAmount": 2000,
    "finalPrice": 18000,
    "usedCoupons": [{"couponId": 9001, "templateName": "满100减20", "layer": "SHOP", "discountAmount": 2000}],
    "allocations": [
      {"lineNo": "L1", "skuId": "SKU_A", "lineAmount": 12000, "allocatedDiscount": 1200,
       "couponShares": [{"couponId": 9001, "amount": 1200}]},
      {"lineNo": "L2", "skuId": "SKU_B", "lineAmount": 8000, "allocatedDiscount": 800,
       "couponShares": [{"couponId": 9001, "amount": 800}]}
    ],
    "unusableCoupons": []
  }
}
```

### 14.2 锁券 / 核销 / 退款

```json
POST /open/v1/coupons/lock
{"orderNo": "ORD-1", "userId": 1001, "couponId": 9001}
→ {"usageNo": "ORD-1:9001", "couponId": 9001, "couponStatus": "已锁定", "duplicated": false}

POST /open/v1/coupons/confirm
{"usageNo": "ORD-1:9001", "items": [{"skuId": "SKU_A", "categoryId": "CAT_FOOD", "price": 6000, "quantity": 2, "lineNo": "L1"}, ...]}
→ {"usageNo": "ORD-1:9001", "couponStatus": "已核销", "discountAmt": 2000, "duplicated": false}

POST /open/v1/coupons/refund
{"usageNo": "ORD-1:9001", "refundNo": "RF-1", "items": [{"lineNo": "L1", "quantity": 1}]}
→ {"usageNo": "ORD-1:9001", "refundNo": "RF-1", "refundType": "PARTIAL",
   "couponStatus": "已核销", "usageStatus": "部分退款", "discountAmt": 600, "duplicated": false}
```

### 14.3 事件（目标）

见 §10.3。

---

## 15. 当前 vs 目标接口矩阵

| 能力 | 当前 | 目标 |
|---|---|---|
| 建模板/发布/查模板 | ✅ | 加 `settlePartyId`、模板版本 |
| 领券 | ✅ | 批量/定向 |
| 试算 | ✅ | `pricingId`、`merchantId`/`settlePartyId` |
| 锁/核销/释放 | ✅ | `pricingId` 核销、结算主体归属 |
| 退款 | ✅ 整单/按行 | 租户级政策、结算主体明细 |
| 查券包 | ✅ | 按订单/占用单/退款/账单查询 |
| 日账单/主体汇总 | ◐ 模板日账单只有表/定时任务，无 HTTP 查询 | 查询 API/导出/主体汇总 |
| 事件 | ◐ 内部事件/发件箱 | 对外 Webhook、至少一次、死信 |
| 沙箱/契约测试 | ❌ | ✅ |
| `/api` 多租户隔离 | ❌ | 平台运营面或补内部租户身份 |

---

## 16. 待确认与变更记录

### 16.1 待确认

1. `pricingId` 是否在 `calculate` 响应/`lock`/`confirm` 请求中第一版就加？字段名/长度？
2. `settlePartyId` 字段名与是否与 `merchantId` 并存？
3. 对外事件第一版提供哪些类型？Webhook 签名/重试/死信策略？
4. 查询 API 第一版做哪些：退款明细、日账单、主体汇总？
5. `/api` 长期定位：平台运营面，还是补内部租户身份？
6. 沙箱/契约测试的落地方式（独立 appId？独立环境？）。

### 16.2 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-18 | 初稿：通用协议、接口总表、逐接口字段、错误/幂等、事件目标、版本、限流、安全、示例 |
