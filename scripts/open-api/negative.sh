#!/usr/bin/env bash
#
# 开放接口反例演示（S1 交付物）——把接入层每一条防线都当场打一遍：
#   ① 缺签名头 ② 签名错 ③ 时间戳超窗 ④ nonce 重放 ⑤ 缺 clientToken
#   ⑥ 越权 ⑦ 超配额
#
# 为什么"反例脚本"要和 demo 一样当成交付物：
#   **安全能力不看"设计得多好"，看"能不能当场证明它拦得住"**。
#   接入方拿这份脚本可以自查自己的实现；评审时它是"五类反例可复现"的验收证据。
#
# 用法：make up && make run（另一个终端），然后 bash scripts/open-api/negative.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

PASS=0
expect() { expect_http_and_code "$@"; PASS=$((PASS + 1)); }

step "0. 探活"
curl -sf "$BASE/actuator/health" >/dev/null || fail "服务不可达：$BASE（先 make up && make run）"
ok "服务在 $BASE"

step "1. 准备：两个接入方（甲、乙）+ 甲的一张券"
register_app "neg-a" 0 0
APP_A="$APP_ID"; SECRET_A="$APP_SECRET"
register_app "neg-b" 0 0
APP_B="$APP_ID"; SECRET_B="$APP_SECRET"

APP_ID="$APP_A"; APP_SECRET="$SECRET_A"
resp=$(signed_call_ok "甲建券" POST /open/v1/templates \
    '{"name":"反例演示券","activityId":1001,"couponType":1,"faceValue":1000,"threshold":10000,"totalCount":100,"perUserLimit":5,"validType":2,"validDays":7}' \
    "neg-create-$(date +%s%3N)")
TPL_A=$(printf '%s' "$resp" | json_get '["data"]["id"]')
signed_call_ok "甲发布" POST "/open/v1/templates/$TPL_A/publish" "" "neg-pub-$(date +%s%3N)" >/dev/null

step "2. 反例①：没有 Authorization 头 → 401 / 6001"
status=$(curl -sS -o /tmp/neg1.json -w '%{http_code}' "$BASE/open/v1/coupons?userId=1")
[[ "$status" == "401" ]] || fail "期望 401，实际 $status"
grep -q '"code":6001' /tmp/neg1.json || fail "期望 6001：$(cat /tmp/neg1.json)"
ok "无名之请被拒（HTTP 401 / code=6001）"; PASS=$((PASS + 1))

step "3. 反例②：签名末位改一个字符 → 401 / 6003"
# 先拿一个合法签名，再改掉它的最后一个字符（保证真的改了）
ts=$(date +%s%3N); nonce=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')
sig=$(sign_request GET /open/v1/coupons "userId=1" "" "$ts" "$nonce")
last="${sig: -1}"; repl=$([[ "$last" == "0" ]] && echo "1" || echo "0")
bad_sig="${sig:0:${#sig}-1}$repl"
status=$(curl -sS -o /tmp/neg2.json -w '%{http_code}' "$BASE/open/v1/coupons?userId=1" \
    -H "Authorization: NC-HMAC-SHA256 Credential=$APP_A, Timestamp=$ts, Nonce=$nonce, Signature=$bad_sig")
[[ "$status" == "401" ]] || fail "期望 401，实际 $status"
grep -q '"code":6003' /tmp/neg2.json || fail "期望 6003：$(cat /tmp/neg2.json)"
ok "篡改签名被拒（HTTP 401 / code=6003）"; PASS=$((PASS + 1))

step "4. 反例③：时间戳超出 ±5 分钟窗口 → 401 / 6004"
expect "过期时间戳被拒" 401 6004 GET /open/v1/coupons "" "" "$(( $(date +%s%3N) - 600000 ))" ""
expect "未来时间戳也被拒" 401 6004 GET /open/v1/coupons "" "" "$(( $(date +%s%3N) + 600000 ))" ""

step "5. 反例④：同一个 nonce 用第二次 → 401 / 6005"
nonce=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')
first=$(signed_call GET "/open/v1/coupons?userId=1" "" "" "" "$nonce")
printf '%s' "$first" | grep -q '"code":0' || fail "首次应当通过：$first"
expect "原样重放被拒" 401 6005 GET /open/v1/coupons "" "" "" "$nonce"

step "6. 反例⑤：写接口不带 clientToken → 400 / 6012"
expect "缺 clientToken 被拒" 400 6012 POST /open/v1/coupons/receive \
    "{\"requestId\":\"neg-notoken\",\"userId\":1,\"templateId\":$TPL_A}" "" "" ""

step "7. 反例⑥：越权——乙读甲的模板 → 403 / 6011"
APP_ID="$APP_B"; APP_SECRET="$SECRET_B"
expect "跨应用读模板被拒" 403 6011 GET "/open/v1/templates/$TPL_A" "" "" "" ""
expect "跨应用发布被拒" 403 6011 POST "/open/v1/templates/$TPL_A/publish" "" "neg-b-pub-$(date +%s%3N)" "" ""
expect "跨应用领券被拒" 403 6011 POST /open/v1/coupons/receive \
    "{\"requestId\":\"neg-b-rcv\",\"userId\":1,\"templateId\":$TPL_A}" "neg-b-rcv-$(date +%s%3N)" "" ""

step "8. 反例⑦：超配额 → 429 / 6006"
register_app "neg-qps" 1 0        # QPS 上限 = 1
# 连发直到出现 429：**不写死"第 2 发必被拒"**——那依赖两发落在同一秒内，
# 是典型的 flaky 写法（本项目的 S1 第 7 步就踩过一次同类问题）
got_429=0
for i in 1 2 3 4 5; do
  raw=$(signed_call_raw GET "/open/v1/coupons?userId=1")
  split_response "$raw"
  if [[ "$RESP_STATUS" == "429" ]]; then
    [[ "$RESP_BODY" == *'"code":6006'* ]] || fail "期望 6006：$RESP_BODY"
    ok "第 $i 发触发 QPS 限流（HTTP 429 / code=6006）"; PASS=$((PASS + 1))
    # 429 必须带 Retry-After（不带的话调用方只能瞎猜）——用带签名的请求再打一次并 dump 响应头
    signed_call GET "/open/v1/coupons?userId=1" "" "" "" "" -D /tmp/neg-qps-headers.txt >/dev/null
    if grep -qi '^retry-after:' /tmp/neg-qps-headers.txt; then
      ok "429 带了 $(grep -i '^retry-after:' /tmp/neg-qps-headers.txt | tr -d '\r')"
    else
      fail "429 响应缺少 Retry-After 头"
    fi
    got_429=1; break
  fi
  sleep 0.1
done
[[ "$got_429" == "1" ]] || fail "连发 5 次都没触发 QPS 限流"

printf '\n\033[1;32m反例全部按预期被拒\033[0m（共 %d 条断言）\n' "$PASS"
printf '这些行为都是**契约**：接入方的重试逻辑应当据此编写（见 docs/open-api/接入指南-v1.md）。\n'
