#!/usr/bin/env bash
#
# 开放接口脚本公共库（S1）：签名、请求、输出约定。
# demo.sh 与 negative.sh 都 source 它——**签名只有一份实现**，
# 免得两个脚本对"待签串到底几行"给出两种答案。
#
# 这里没有 set -euo pipefail：由调用脚本自己设，避免 source 改变调用者的行为。

BASE="${BASE:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-dev-admin-token}"

# 当前使用的接入方凭据（由 register_app 或调用方设置）
APP_ID="${APP_ID:-}"
APP_SECRET="${APP_SECRET:-}"

# ---------------------------------------------------------------- 输出
# 所有进度输出一律走 stderr：这些函数常被 $(...) 包住捕获响应体，
# 打到 stdout 会把提示文字混进 JSON 里（demo.sh 第一版就是这么坏的）
step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*" >&2; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*" >&2; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*" >&2; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

json_get() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# ---------------------------------------------------------------- 签名
# 待签串 = 7 行，\n 连接，**最后一行后不加换行**（见 docs/open-api/接入指南-v1.md）：
#   方法 / 路径 / 规范化 query / body 的 SHA-256 / appId / 毫秒时间戳 / nonce
#
# $1=方法 $2=路径 $3=query $4=body $5=时间戳 $6=nonce
sign_request() {
  local method="$1" path="$2" query="$3" body="$4" ts="$5" nonce="$6"
  local body_sha canonical
  body_sha=$(printf '%s' "$body" | sha256sum | cut -d' ' -f1)
  canonical=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s' \
      "$method" "$path" "$query" "$body_sha" "$APP_ID" "$ts" "$nonce")
  printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$APP_SECRET" | sed 's/^.*= //'
}

# 发一个带签名的请求，返回响应体。
# $1=方法 $2=完整路径(含 ?query) $3=body(可空) $4=clientToken(可空) $5=时间戳覆盖(可空) $6=nonce覆盖(可空)
#
# ⚠️ **前 6 个位置参数必须传满**（可空也要写 ""），第 7 个起才是透传给 curl 的额外参数。
#    少传一个会把后面的 `-w` 顶到 nonce 位置上——实测报出来的错是 curl 的
#    "Bad hostname"，而真正的原因是 `Nonce=-w`。**参数错位的报错往往指向错误的地方。**
signed_call() {
  local method="$1" full_path="$2" body="${3:-}" client_token="${4:-}"
  local ts_override="${5:-}" nonce_override="${6:-}"
  local path query ts nonce sig
  local extra=("${@:7}")   # 第 7 个起透传给 curl（如 -w 取 HTTP 状态）

  path="${full_path%%\?*}"
  if [[ "$full_path" == *\?* ]]; then query="${full_path#*\?}"; else query=""; fi
  ts="${ts_override:-$(date +%s%3N)}"
  nonce="${nonce_override:-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')}"

  sig=$(sign_request "$method" "$path" "$query" "$body" "$ts" "$nonce")

  local args=(-sS -X "$method" "$BASE$full_path"
      -H "Content-Type: application/json"
      -H "Authorization: NC-HMAC-SHA256 Credential=$APP_ID, Timestamp=$ts, Nonce=$nonce, Signature=$sig")
  [[ -n "$body" ]] && args+=(-d "$body")
  [[ -n "$client_token" ]] && args+=(-H "X-Client-Token: $client_token")
  curl "${args[@]}" "${extra[@]}"
}

# 发请求并断言 code=0，成功则把响应体打到 stdout（进度信息走 stderr）
signed_call_ok() {
  local what="$1"; shift
  local resp; resp=$(signed_call "$@")
  local code; code=$(printf '%s' "$resp" | json_get '["code"]' 2>/dev/null || echo "?")
  [[ "$code" == "0" ]] || fail "$what 失败：$resp"
  ok "$what"
  printf '%s' "$resp"
}

# ---------------------------------------------------------------- 辅助
# 注册一个接入方并切换当前凭据。$1=appId 前缀 $2=qps 上限(0=不限) $3=日配额(0=不限)
register_app() {
  local prefix="$1" qps="${2:-0}" quota="${3:-0}"
  local app_id="${prefix}-$(date +%s%3N)"
  local resp
  resp=$(curl -sS -X POST "$BASE/api/apps" \
      -H "Content-Type: application/json" \
      -H "X-Admin-Token: $ADMIN_TOKEN" \
      -d "{\"appId\":\"$app_id\",\"appName\":\"$prefix\",\"qpsLimit\":$qps,\"dailyQuota\":$quota}")
  APP_ID=$(printf '%s' "$resp" | json_get '["data"]["appId"]' 2>/dev/null) || fail "注册 $prefix 失败：$resp"
  APP_SECRET=$(printf '%s' "$resp" | json_get '["data"]["secret"]')
}

# 断言 HTTP 状态码与业务码。$1=描述 $2=响应体 $3=期望HTTP $4=期望业务码
# （HTTP 状态从 curl 拿不到——用 -w 的方式会污染 body，所以这里只校验业务码 + 用 -o /dev/null 另发一次拿状态）
expect_code() {
  local what="$1" resp="$2" want_code="$3"
  local code; code=$(printf '%s' "$resp" | json_get '["code"]' 2>/dev/null || echo "?")
  [[ "$code" == "$want_code" ]] || fail "$what：期望业务码 $want_code，实际 $code —— $resp"
  ok "$what（code=$code）"
}

# 发请求并同时拿到 HTTP 状态与响应体。$1..$n 同 signed_call（可含额外 curl 参数）
# 输出格式：<body>\n__HTTP__<status>（调用方用 split_response 拆）
signed_call_raw() {
  # 先把前 6 个位置参数补足，再加 -w：**不能写 signed_call "$@" -w ...**——
  # 调用方少传一个（比如只传方法+路径），"-w" 就会被顶到 body 位置上，
  # 结果是 curl 收到 "-d -w" 且根本没有 -w，报出来的却是"没有 __HTTP__ 标记"这种远端的症状
  local method="$1" full_path="$2" body="${3:-}" client="${4:-}" ts="${5:-}" nonce="${6:-}"
  signed_call "$method" "$full_path" "$body" "$client" "$ts" "$nonce" -w '\n__HTTP__%{http_code}'
}

# 拆出状态码，把响应体回填进 RESP_BODY 全局变量
split_response() {
  local raw="$1"
  RESP_STATUS="${raw##*__HTTP__}"
  RESP_BODY="${raw%$'\n'__HTTP__*}"
}

# 管理面写操作（带令牌）。$1=路径 $2=body（可空）。返回响应体。
# **管理面写操作只有这一个实现**——和签名一样，免得几份脚本对"令牌放在哪个头"给出不同答案。
admin_post() {
  local path="$1" body="${2:-}"
  curl -sS -X POST "$BASE$path" \
      -H "Content-Type: application/json" \
      -H "X-Admin-Token: $ADMIN_TOKEN" \
      ${body:+-d "$body"}
}

# 读熔断器状态（S2 第 10 步的只读观测口）。$1=熔断器名（redis / mysql / redis:appId）
breaker_state() {
  curl -sS "$BASE/api/switches/breakers" | python3 -c "
import sys, json
name = sys.argv[1]
for b in json.load(sys.stdin).get('data', []):
    if b['name'] == name:
        print(b['state']); break
" "$1"
}

# 断言 HTTP 状态 + 业务码。$1=描述 $2=期望HTTP $3=期望业务码，其余参数透传给 signed_call
expect_http_and_code() {
  local what="$1" want_http="$2" want_code="$3"; shift 3
  local raw; raw=$(signed_call_raw "$@")
  split_response "$raw"
  local code; code=$(printf '%s' "$RESP_BODY" | json_get '["code"]' 2>/dev/null || echo "?")
  [[ "$RESP_STATUS" == "$want_http" ]] || fail "$what：期望 HTTP $want_http，实际 $RESP_STATUS —— $RESP_BODY"
  [[ "$code" == "$want_code" ]] || fail "$what：期望业务码 $want_code，实际 $code —— $RESP_BODY"
  ok "$what（HTTP $RESP_STATUS / code=$code）"
}
