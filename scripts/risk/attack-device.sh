#!/usr/bin/env bash
#
# 风控攻击演练 ①：多账号同设备刷券（V6 4B，R_DEVICE_DAILY 取证）
#
# 攻击形态：一台设备（同一 X-Device-Id）换 11 个不同 userId 连续领券——
# 库存/限领只防"每个用户领几张"，不防"一台设备来多少个用户"；设备日限（阈值 10）
# 是唯一自动挡这条路的规则。断言：前 10 次成功、第 11 次 7003、决策日志可解释
# （matched=R_DEVICE_DAILY、因子含当时计数）——"攻击被拦"要有取证形态。
#
# 前置（脚本自备，不需要手工改库）：
#   · 本机 dev 环境：make up && make run（另开终端）
#   · 脚本内建租户（admin API 注册）+ 租户级开闸 switch.risk.decision.enabled=true
#   · 设备日限阈值用部署默认 10（RISK_DEVICE_DAILY_MAX_PER_DAY）
#
# 用法：
#   CONFIRM=yes bash scripts/risk/attack-device.sh
#
# 护栏（照 drill.sh）：CONFIRM=yes 显式确认（脚本会建真实租户/名单条目）；
# 只打本机（BASE 检查）；幂等可重复跑（每轮全部用新 appId/userId/deviceId）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

PASS=0
expect() { expect_http_and_code "$@"; PASS=$((PASS + 1)); }

step "0. 前置检查"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "演练会注册租户并写入风控配置/名单。确认请加：CONFIRM=yes"
case "$BASE" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机：$BASE" ;;
  *) fail "BASE=$BASE 不是本机——攻击演练只打本机 dev 环境" ;;
esac
curl -sf "$BASE/actuator/health" >/dev/null || fail "服务不可达：$BASE（先 make up && make run）"
ok "服务可达"

step "1. 准备攻击目标租户（注册 + 开风控闸 + 建券发布）"
register_app "atk-dev" 0 0
ATK_APP="$APP_ID"
admin_put "/api/apps/$ATK_APP/config" \
    '{"switch.risk.decision.enabled":"true"}' >/dev/null
ok "租户 $ATK_APP 的风控决策开关已开（租户级灰度，不动全站默认关）"

resp=$(signed_call_ok "建券（满10000减2000）" POST /open/v1/templates \
    '{"name":"设备攻击演练券","activityId":1001,"couponType":1,"faceValue":2000,"threshold":10000,"totalCount":1000,"perUserLimit":5,"validType":2,"validDays":7}' \
    "atk-dev-tpl-$(date +%s%3N)")
TPL=$(printf '%s' "$resp" | json_get '["data"]["id"]')
signed_call_ok "发布" POST "/open/v1/templates/$TPL/publish" "" "atk-dev-pub-$(date +%s%3N)" >/dev/null

# 攻击载荷：同设备 × 11 个 userId（时间戳打底，跨轮次不串计数——日限键带 appId，
# 新租户必然从 0 计；userId 也全新，避免撞别的租户的限领/幂等）
DEVICE="ATK-DEV-$(date +%s%3N)"
USER_BASE=$(( ($(date +%s) % 100000) * 100 + 9700000 ))
export RISK_DEVICE_ID="$DEVICE"

step "2. 攻击：同设备 $DEVICE 连续领 11 次（换 userId）"
for i in 1 2 3 4 5 6 7 8 9 10; do
  expect "第 $i 次领券（userId=$((USER_BASE + i))）应放行" 200 0 \
      POST /open/v1/coupons/receive \
      "{\"requestId\":\"atk-dev-$i-$(date +%s%3N)\",\"userId\":$((USER_BASE + i)),\"templateId\":$TPL}" \
      "atk-dev-ct-$i"
done
expect "第 11 次领券应被设备日限拦截（阈值 10）" 403 7003 \
    POST /open/v1/coupons/receive \
    "{\"requestId\":\"atk-dev-11-$(date +%s%3N)\",\"userId\":$((USER_BASE + 11)),\"templateId\":$TPL}" \
    "atk-dev-ct-11"
unset RISK_DEVICE_ID

step "3. 取证：决策日志可解释（哪条规则、什么输入、拦的是谁）"
resp=$(admin_post /api/risk/decisions/query \
    "{\"appId\":\"$ATK_APP\",\"scene\":\"RECEIVE\",\"action\":\"REJECT\",\"limit\":5}")
# JSON 走 argv 不走管道：heredoc（python 代码本体）会抢占 stdin
python3 - "$DEVICE" "$resp" <<'PY'
import json, sys
device = sys.argv[1]
data = json.loads(sys.argv[2])
items = data.get("data", {}).get("items", [])
hits = [i for i in items if i.get("deviceId") == device]
assert hits, f"决策日志里找不到该设备的 REJECT 行：{data}"
row = hits[0]
assert "R_DEVICE_DAILY" in (row.get("matchedRules") or []), f"命中规则不对：{row}"
assert "device.count" in (row.get("factorsJson") or ""), f"因子缺当时计数：{row}"
assert row.get("requestId", "").startswith("atk-dev-11-"), f"拦的不是第 11 次：{row}"
print(f"  ✓ 决策日志可解释：matchedRules={row['matchedRules']} action={row['action']}"
      f" degraded={row['degraded']}（指标：coupon_risk_decision_total{{action=\"REJECT\"}}）")
PY
PASS=$((PASS + 1))

ok "汇总：PASS assertions=$PASS（10 放行 + 1 拦截 + 日志取证）——设备刷券攻击被拦且有据可查"
printf '\n\033[32mDRILL PASS assertions=%d\033[0m\n' "$PASS" >&2
