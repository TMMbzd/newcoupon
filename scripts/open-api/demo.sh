#!/usr/bin/env bash
#
# 开放接口全链路演示（S1 交付物）——一条命令跑通：
#   注册应用 → 建券 → 发布 → 领券 → 锁券 → 核销 → 再领一张 → 锁券 → 释放 → 查券包
#
# 为什么用 bash + openssl 而不是写个 Java 客户端：
#   ① 零构建依赖，clone 下来就能跑；
#   ② **逼迫签名规范精确到字节**——bash 拼字符串最容易暴露"多一个换行"这类问题，
#      如果这份脚本能跑通，说明文档里的待签串规则是可复现的；
#   ③ 接入方用任何语言实现时，这份脚本就是**参考实现**。
#
# 用法：
#   make up && make run          # 另开一个终端
#   bash scripts/open-api/demo.sh
#
# 环境变量（都有默认值）：
#   BASE=         服务地址，默认 http://localhost:8080
#   ADMIN_TOKEN=  管理面令牌，默认 dev-admin-token（= application-dev.yml 的值）
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"

# 每次跑用一个新 appId：应用注册是不可重复的（uk_app_id），脚本要能反复运行
APP_ID="demo-$(date +%s)"
CLIENT_TOKEN_PREFIX="demo-$(date +%s)-"

step()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
# 注意：进度输出一律走 stderr——这些函数会被 $(...) 捕获响应体时调用，
# 打到 stdout 会把提示文字混进 JSON（第一版就是这么坏的）
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*" >&2; }
fail()  { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

# 从 JSON 里取字段：json_get '["data"]["appId"]'
json_get() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# ---------------------------------------------------------------- 签名
# 待签串 = 7 行，\n 连接，**最后一行后不加换行**（与 docs/open-api/接入指南-v1.md 一致）
#   方法 / 路径 / 规范化 query / body 的 SHA-256 / appId / 时间戳(ms) / nonce
sign_request() {
  local method="$1" path="$2" query="$3" body="$4" ts="$5" nonce="$6"
  local body_sha canonical
  body_sha=$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)
  canonical=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s' \
      "$method" "$path" "$query" "$body_sha" "$APP_ID" "$ts" "$nonce")
  printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$APP_SECRET" | sed 's/^.*= //'
}

# 发一个带签名的请求。$1=方法 $2=路径(含 query 的完整路径) $3=body $4=clientToken(可空)
signed_call() {
  local method="$1" full_path="$2" body="${3:-}" client_token="${4:-}"
  local path query ts nonce sig
  path="${full_path%%\?*}"
  if [[ "$full_path" == *\?* ]]; then query="${full_path#*\?}"; else query=""; fi
  ts=$(date +%s%3N)
  nonce=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')

  sig=$(sign_request "$method" "$path" "$query" "$body" "$ts" "$nonce")

  local args=(-sS -X "$method" "$BASE$full_path"
      -H "Content-Type: application/json"
      -H "Authorization: NC-HMAC-SHA256 Credential=$APP_ID, Timestamp=$ts, Nonce=$nonce, Signature=$sig")
  [[ -n "$body" ]] && args+=(-d "$body")
  [[ -n "$client_token" ]] && args+=(-H "X-Client-Token: $client_token")
  curl "${args[@]}"
}

# 发一个请求并断言 code=0；返回响应体
signed_call_ok() {
  local what="$1"; shift
  local resp
  resp=$(signed_call "$@")
  local code; code=$(printf '%s' "$resp" | json_get '["code"]')
  [[ "$code" == "0" ]] || fail "$what 失败：$resp"
  ok "$what"
  printf '%s' "$resp"
}

# ---------------------------------------------------------------- 开始
step "0. 探活"
curl -sf "$BASE/actuator/health" >/dev/null || fail "服务不可达：$BASE（先 make up && make run）"
ok "服务在 $BASE"

step "1. 注册接入方（管理面，X-Admin-Token）"
resp=$(curl -sS -X POST "$BASE/api/apps" \
    -H "Content-Type: application/json" \
    -H "X-Admin-Token: $ADMIN_TOKEN" \
    -d "{\"appId\":\"$APP_ID\",\"appName\":\"演示接入方\",\"qpsLimit\":100,\"dailyQuota\":100000}")
APP_SECRET=$(printf '%s' "$resp" | json_get '["data"]["secret"]' 2>/dev/null) \
    || fail "注册失败：$resp"
ok "appId=$APP_ID"
printf '  \033[33mSK=%s（只显示这一次，请立即保存）\033[0m\n' "$APP_SECRET"

step "2. 建券（开放面，签名 + clientToken）"
resp=$(signed_call_ok "建券" POST /open/v1/templates \
    '{"name":"演示券","activityId":1001,"couponType":1,"faceValue":1000,"threshold":10000,"totalCount":100,"perUserLimit":5,"validType":2,"validDays":7}' \
    "${CLIENT_TOKEN_PREFIX}create")
TEMPLATE_ID=$(printf '%s' "$resp" | json_get '["data"]["id"]')
ok "templateId=$TEMPLATE_ID"

step "3. 发布"
signed_call_ok "发布" POST "/open/v1/templates/$TEMPLATE_ID/publish" "" "${CLIENT_TOKEN_PREFIX}publish" >/dev/null

USER_ID=$(( RANDOM + 100000 ))

step "4. 领券（用户 $USER_ID）"
resp=$(signed_call_ok "领券" POST /open/v1/coupons/receive \
    "{\"requestId\":\"demo-rcv-1\",\"userId\":$USER_ID,\"templateId\":$TEMPLATE_ID}" \
    "${CLIENT_TOKEN_PREFIX}rcv1")
COUPON_ID=$(printf '%s' "$resp" | json_get '["data"]["couponId"]')
ok "couponId=$COUPON_ID"

step "5. 幂等：同一 clientToken 重放（应返回同一结果且不多发券）"
replay=$(signed_call POST /open/v1/coupons/receive \
    "{\"requestId\":\"demo-rcv-1\",\"userId\":$USER_ID,\"templateId\":$TEMPLATE_ID}" \
    "${CLIENT_TOKEN_PREFIX}rcv1")
[[ "$replay" == "$resp" ]] || fail "重放结果与首次不一致：$replay"
ok "重放返回逐字节相同的结果"

step "6. 锁券"
ORDER_NO="demo-order-$USER_ID"
resp=$(signed_call_ok "锁券" POST /open/v1/coupons/lock \
    "{\"orderNo\":\"$ORDER_NO\",\"userId\":$USER_ID,\"couponId\":$COUPON_ID}" \
    "${CLIENT_TOKEN_PREFIX}lock1")
USAGE_NO=$(printf '%s' "$resp" | json_get '["data"]["usageNo"]')
ok "usageNo=$USAGE_NO"

step "7. 核销"
signed_call_ok "核销" POST /open/v1/coupons/confirm \
    "{\"usageNo\":\"$USAGE_NO\",\"discountAmt\":1000}" "${CLIENT_TOKEN_PREFIX}confirm1" >/dev/null

step "8. 再领一张 → 锁定 → 释放（演示释放路径）"
resp=$(signed_call_ok "再领一张" POST /open/v1/coupons/receive \
    "{\"requestId\":\"demo-rcv-2\",\"userId\":$USER_ID,\"templateId\":$TEMPLATE_ID}" \
    "${CLIENT_TOKEN_PREFIX}rcv2")
COUPON2=$(printf '%s' "$resp" | json_get '["data"]["couponId"]')
resp=$(signed_call_ok "再锁一张" POST /open/v1/coupons/lock \
    "{\"orderNo\":\"$ORDER_NO-2\",\"userId\":$USER_ID,\"couponId\":$COUPON2}" \
    "${CLIENT_TOKEN_PREFIX}lock2")
USAGE2=$(printf '%s' "$resp" | json_get '["data"]["usageNo"]')
signed_call_ok "释放" POST /open/v1/coupons/release \
    "{\"usageNo\":\"$USAGE2\",\"reason\":\"演示释放\"}" "${CLIENT_TOKEN_PREFIX}release2" >/dev/null

step "9. 查券包"
resp=$(signed_call_ok "查券包" GET "/open/v1/coupons?userId=$USER_ID" "" "")
printf '%s' "$resp" | python3 -c '
import sys, json
coupons = json.load(sys.stdin)["data"]["coupons"]
print(f"  共 {len(coupons)} 张：")
for c in coupons:
    print(f"    - id={c['"'"'id'"'"']} 面额={c['"'"'faceValue'"'"']}分 状态={c['"'"'status'"'"']}")
'

printf '\n\033[1;32m全链路演示通过\033[0m（appId=%s）\n' "$APP_ID"
printf '提示：演示数据已落库；appId 每次运行都不同，多次运行不会互相干扰。\n'
