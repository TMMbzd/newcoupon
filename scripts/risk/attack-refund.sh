#!/usr/bin/env bash
#
# 风控攻击演练 ②：拉黑用户退款——合规红线可演示取证（V6 4B）
#
# 场景：用户已核销后因风控被拉黑，其退款请求的风控决策为 REJECT。
# <b>红线语义（不是演练出来的结论，是要证明代码真的这么做了）</b>：
#   ① 退款照做成功（REJECT 一律不拒绝退款——消费者退款权>平台风控）；
#   ② 决策日志如实记 action=REJECT（打标留痕，matched_rules=R_LIST_BLACK）；
#   ③ 结算冻结自动落账：settlement_hold 出现 RISK-{decisionId} 行
#      （source=RISK_RULE、主体级、金额=本单退款额）——"打标/结算调整"的降级处置。
#
# 前置（脚本自备）：本机 dev 环境（make up && make run）；租户级开闸。
#
# 用法：
#   CONFIRM=yes bash scripts/risk/attack-refund.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

PASS=0

step "0. 前置检查"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "演练会注册租户并写入风控配置/名单。确认请加：CONFIRM=yes"
case "$BASE" in
  http://localhost:*|http://127.0.0.1:*) ok "目标是本机：$BASE" ;;
  *) fail "BASE=$BASE 不是本机——攻击演练只打本机 dev 环境" ;;
esac
curl -sf "$BASE/actuator/health" >/dev/null || fail "服务不可达：$BASE（先 make up && make run）"
ok "服务可达"

step "1. 准备租户与被攻击的券"
register_app "atk-rf" 0 0
ATK_APP="$APP_ID"
admin_put "/api/apps/$ATK_APP/config" \
    '{"switch.risk.decision.enabled":"true"}' >/dev/null
ok "租户 $ATK_APP 的风控决策开关已开"

resp=$(signed_call_ok "建券（满10000减2000）" POST /open/v1/templates \
    '{"name":"退款红线演练券","activityId":1001,"couponType":1,"faceValue":2000,"threshold":10000,"totalCount":1000,"perUserLimit":5,"validType":2,"validDays":7}' \
    "atk-rf-tpl-$(date +%s%3N)")
TPL=$(printf '%s' "$resp" | json_get '["data"]["id"]')
signed_call_ok "发布" POST "/open/v1/templates/$TPL/publish" "" "atk-rf-pub-$(date +%s%3N)" >/dev/null

USER=$(( ($(date +%s) % 100000) * 100 + 9800000 ))
STAMP="$(date +%s%3N)"

step "2. 正常链路：领券 → 锁券 → 核销（两行订单，优惠 2000 分）"
resp=$(signed_call_ok "领券" POST /open/v1/coupons/receive \
    "{\"requestId\":\"atk-rf-rcv-$STAMP\",\"userId\":$USER,\"templateId\":$TPL}" "atk-rf-ct-rcv")
COUPON=$(printf '%s' "$resp" | json_get '["data"]["couponId"]')

resp=$(signed_call_ok "锁券" POST /open/v1/coupons/lock \
    "{\"orderNo\":\"ATK-RF-ORD-$STAMP\",\"userId\":$USER,\"couponId\":$COUPON}" "atk-rf-ct-lock")
USAGE_NO=$(printf '%s' "$resp" | json_get '["data"]["usageNo"]')

signed_call_ok "核销" POST /open/v1/coupons/confirm \
    "{\"usageNo\":\"$USAGE_NO\",\"items\":[{\"skuId\":\"SKU_A\",\"categoryId\":\"CAT1\",\"price\":6000,\"quantity\":2,\"lineNo\":\"L1\"},{\"skuId\":\"SKU_B\",\"categoryId\":\"CAT1\",\"price\":4000,\"quantity\":2,\"lineNo\":\"L2\"}]}" \
    "atk-rf-ct-confirm" >/dev/null
ok "已核销：usageNo=$USAGE_NO"

step "3. 拉黑该用户 → 退款（红线时刻）"
admin_post /api/risk/lists \
    "{\"listType\":\"BLACK\",\"valueType\":\"USER_ID\",\"value\":\"$USER\",\"reason\":\"attack-refund 演练拉黑\"}" >/dev/null
ok "userId=$USER 已进黑名单（平台级）"

# 红线断言 ①：退款必须照做成功——业务码 0，不是 7003
resp=$(signed_call_ok "拉黑后的退款照做成功（合规红线：REJECT 不拒绝退款）" \
    POST /open/v1/coupons/refund \
    "{\"usageNo\":\"$USAGE_NO\",\"refundNo\":\"ATK-RF-REF-$STAMP\"}" "atk-rf-ct-refund")
REFUND_AMT=$(printf '%s' "$resp" | json_get '["data"]["discountAmt"]')
PASS=$((PASS + 1))

step "4. 取证：决策日志 REJECT + 结算冻结 RISK-{decisionId}"
resp=$(admin_post /api/risk/decisions/query \
    "{\"appId\":\"$ATK_APP\",\"scene\":\"REFUND\",\"userId\":$USER,\"limit\":5}")
# JSON 走 argv 不走管道：heredoc（python 代码本体）会抢占 stdin
python3 - "$REFUND_AMT" "$ATK_APP" "$TPL" "$BASE" "${ADMIN_TOKEN:-dev-admin-token}" "$resp" <<'PY'
import json, sys, urllib.request

refund_amt, atk_app, tpl, base, admin_token = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
data = json.loads(sys.argv[6])
items = data.get("data", {}).get("items", [])
assert items, f"退款场景没有决策日志行：{data}"
row = items[0]
assert row["action"] == "REJECT", f"决策动作必须如实记 REJECT：{row}"
assert "R_LIST_BLACK" in (row.get("matchedRules") or []), f"命中规则不对：{row}"
print(f"  ✓ 决策日志：action=REJECT matchedRules={row['matchedRules']} decisionId={row['id']}"
      f"（退款照做+打标，不是拒绝）")

# 结算冻结：RISK-{decisionId} 模板级 hold（管理面查询端点取证）。
# 模板级（审查 M1）=只扣被退券模板行的应付；主体级会把同主体其它模板行也全额扣减
req = urllib.request.Request(
    base + "/api/settlement/holds/query",
    data=json.dumps({"appId": atk_app, "limit": 50}).encode(),
    headers={"Content-Type": "application/json", "X-Admin-Token": admin_token},
    method="POST")
holds = json.load(urllib.request.urlopen(req)).get("data", {}).get("items", [])
hold_no = f"RISK-{row['id']}"
hit = [h for h in holds if h["holdNo"] == hold_no]
assert hit, f"settlement_hold 没有 {hold_no} 行：已建 holds={[(h['holdNo'], h['status']) for h in holds]}"
hold = hit[0]
assert hold["status"] == "ACTIVE" and hold["source"] == "RISK_RULE", f"hold 状态/来源不对：{hold}"
assert hold["templateId"] == tpl, f"必须是模板级冻结（templateId={tpl}）：{hold}"
assert hold["amount"] == refund_amt, f"冻结金额应=本单退款额 {refund_amt}，实际 {hold['amount']}"
assert hold["settlePartyId"] == "__default__", f"主体口径：{hold['settlePartyId']}"
print(f"  ✓ 结算冻结：holdNo={hold_no} amount={hold['amount']} 分 source={hold['source']}"
      f" status={hold['status']}（模板级 templateId={tpl}，payable=分账-退款-hold）")
PY
PASS=$((PASS + 1))

ok "汇总：PASS assertions=$PASS（退款成功 + REJECT 留痕 + RISK-{decisionId} 冻结）——红线三段全部可演示"
printf '\n\033[32mDRILL PASS assertions=%d\033[0m\n' "$PASS" >&2
