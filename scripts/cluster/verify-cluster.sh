#!/usr/bin/env bash
#
# 集群流控验收（S3 第 9 步）——**两个真实应用实例 + 一个 Token Server**。
#
# 验的是什么（三条，缺一条都不算验过）：
#   ① **跨实例精确**：租户配额 E 在两个实例上合计只能过 ≈E 个（单机模式下会是 2E）；
#   ② **降级可见**：把 Token Server 停掉 → 合计涨到 ≈2E（回退本地），
#      并且 coupon_sentinel_cluster_token_fallback_total 必须涨——**降级不能是静默的**；
#   ③ 两实例的规则一致（flowId 两端必须对得上，否则服务端会说"没有这条规则"→ 同样静默降级）。
#
# ⚠️ 为什么要三个进程：单 JVM 里两个 Spring 上下文**共享同一份 Sentinel 静态状态**，
#    根本证明不了"跨实例"。这是唯一能把这件事验真的形态。
#
# 资源纪律（本机 WSL 只有 ~6.6G）：两个实例都限 -Xmx256m，跑完**一定**全停
# （退出时 trap 会清，包括 Ctrl-C）。
#
# 用法：
#   make up                          # MySQL/Redis 容器先起来
#   CONFIRM=yes bash scripts/cluster/verify-cluster.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

PORT_A=18081
PORT_B=18082
TOKEN_PORT=18730
QUOTA=10               # 租户配额（集群阈值）
DURATION=3              # 每轮匀速打多少秒（按速率验，见 fire_for_seconds 的注释）

LOGDIR="$REPO_DIR/target/cluster-verify"
mkdir -p "$LOGDIR"

PIDS=()
curl_pids=()
ASSERTIONS=0

cleanup() {
    echo >&2
    warn "清理：停掉所有由本脚本启动的进程"
    for pid in "${PIDS[@]:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
        fi
    done
    # 给 Spring 一点时间做优雅停机，然后确认
    sleep 3
    for pid in "${PIDS[@]:-}"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -9 "$pid" 2>/dev/null || true
        fi
    done
    for p in "$PORT_A" "$PORT_B" "$TOKEN_PORT"; do
        local leaked
        leaked=$(ss -ltnp 2>/dev/null | grep -c ":$p " || true)
        [ "${leaked:-0}" != "0" ] && warn "端口 $p 仍有监听，请手动检查"
    done
    ok "清理完成"
}
trap cleanup EXIT INT TERM

assert_true() {   # $1=描述 $2=条件
    ASSERTIONS=$((ASSERTIONS + 1))
    if [ "$2" = "1" ]; then ok "$1"; else fail "断言失败：$1"; fi
}

# ---------------------------------------------------------------- 护栏
case "${BASE:-http://localhost:$PORT_A}" in *localhost*|*127.0.0.1*) ;; *)
    fail "BASE 必须是本机（这个脚本会起/停进程）" ;; esac
[ "${CONFIRM:-}" = "yes" ] || fail "这会启动 3 个 JVM 进程并改真实租户数据。确认请加 CONFIRM=yes"

# ---------------------------------------------------------------- 准备
MVN="${MVN:-/mnt/c/Development/apache-maven-3.8.8/bin/mvn}"
( cd "$REPO_DIR" && "$MVN" -q -o dependency:build-classpath \
    -Dmdep.outputFile="$REPO_DIR/target/cluster-cp.txt" )

CP="$REPO_DIR/target/classes:$REPO_DIR/target/test-classes:$(cat "$REPO_DIR/target/cluster-cp.txt")"

start_token_server() {
    java -Xmx128m -cp "$CP" com.newcoupon.cluster.TokenServerMain "$TOKEN_PORT" newcoupon \
        > "$LOGDIR/token-server.log" 2>&1 &
    TOKEN_PID=$!
    PIDS+=("$TOKEN_PID")
    for _ in $(seq 1 30); do
        sleep 1
        ss -ltn 2>/dev/null | grep -q ":$TOKEN_PORT " && return 0
    done
    fail "Token Server 没起来（看 $LOGDIR/token-server.log）"
}

start_app() {   # $1=端口 $2=cluster.enabled
    java -Xmx256m -cp "$CP" com.newcoupon.NewCouponApplication \
        "--server.port=$1" \
        "--coupon.sentinel.cluster.enabled=$2" \
        "--coupon.sentinel.cluster.server-host=127.0.0.1" \
        "--coupon.sentinel.cluster.server-port=$TOKEN_PORT" \
        > "$LOGDIR/app-$1.log" 2>&1 &
    PIDS+=($!)
    for _ in $(seq 1 60); do
        sleep 1
        curl -sf "http://localhost:$1/actuator/health" >/dev/null 2>&1 && return 0
    done
    fail "实例 $1 没起来（看 $LOGDIR/app-$1.log）"
}

# 持续 SECONDS 秒**匀速**打请求（两个实例各一个 worker），打印"合计放行数"。
#
# 为什么是"按速率"而不是"打一发并发突发"：
#   · 并发突发时服务端计数会**滞后**（源码：先判 rest 再记账，20 发并发实测可以全部通过）；
#   · 顺序慢打又会跨过 1 秒的窗口边界（相邻两个窗口各放一份）。
# 只有"持续若干秒的匀速流量"才对得上"每秒 N 个"这个语义——
# 集群的结果 ≈ N×秒数，单机的结果 ≈ 2×N×秒数，两者差一倍，断言有余量。
fire_for_seconds() {   # $1=秒数 $2=起始 user 序号 → stdout: "放行数 总请求数"
    local seconds="$1" base="$2" tmp deadline
    tmp=$(mktemp -d)
    deadline=$(( $(date +%s) + seconds ))
    local worker
    for worker in 0 1; do
        (
            local port=$([ "$worker" -eq 0 ] && echo "$PORT_A" || echo "$PORT_B")
            local i=0
            while [ "$(date +%s)" -lt "$deadline" ]; do
                local user=$((base + worker * 10000 + i))
                raw=$(BASE="http://localhost:$port" signed_call_raw \
                    GET "/open/v1/coupons?userId=$user" "" "" "" "" --max-time 15 2>/dev/null)
                split_response "$raw"
                printf '%s\n' "$RESP_STATUS" >> "$tmp/$worker"
                i=$((i + 1))
            done
        ) &
        curl_pids+=($!)
    done
    for cp in "${curl_pids[@]}"; do wait "$cp"; done

    local allowed total
    allowed=$(cat "$tmp"/* 2>/dev/null | grep -c '^200$')
    total=$(cat "$tmp"/* 2>/dev/null | wc -l | tr -d ' ')
    # 状态分布打到 stderr：只有"放行数不对"时，排查要重跑一遍才知道是没连上、没规则还是链路报错
    echo -n "  状态分布: " >&2
    cat "$tmp"/* 2>/dev/null | sort | uniq -c | tr '\n' ' ' >&2
    echo >&2
    rm -rf "$tmp"
    echo "$allowed $total"
}

# 把两侧的集群状态打印出来——**诊断信息要跟着断言一起出来**：
# 失败了却只看到"放行数不对"，还得再手工跑一遍才能知道是没连上、没规则还是真在限流。
dump_cluster_state() {
    local label="$1" p
    for p in "$PORT_A" "$PORT_B"; do
        local state mode dec fb
        state=$(curl -s --max-time 5 "http://localhost:$p/actuator/prometheus" | awk '/^coupon_sentinel_cluster_client_state/ {print $2}')
        mode=$(curl -s --max-time 5 "http://localhost:$p/actuator/prometheus" | awk '/^coupon_sentinel_cluster_mode/ {print $2}')
        dec=$(curl -s --max-time 5 "http://localhost:$p/actuator/prometheus" | awk '/^coupon_sentinel_cluster_decorator_active/ {print $2}')
        fb=$(curl -s --max-time 5 "http://localhost:$p/actuator/prometheus" \
             | awk '/^coupon_sentinel_cluster_token_fallback_total/ {printf "%s=%s ", $1, $2}')
        echo "  [$label] :$p clientState=$state mode=$mode decorator=$dec 降级: ${fb:-无}" >&2
    done
    echo "  [$label] 服务端最近一次装载：$(grep -c '集群规则已装载\|没有装载成功' "$LOGDIR/token-server.log" 2>/dev/null) 轮 / $(grep '集群规则已装载' "$LOGDIR/token-server.log" 2>/dev/null | tail -1 | grep -oP '共 \K[0-9]+') 条" >&2
}

scrape_fallback() {   # 两个实例的降级计数之和
    local total=0 v
    for p in "$PORT_A" "$PORT_B"; do
        v=$(curl -s "http://localhost:$p/actuator/prometheus" \
            | awk '/^coupon_sentinel_cluster_token_fallback_total/ {s+=$2} END {print s+0}')
        total=$((total + ${v%.*}))
    done
    echo "$total"
}

# ---------------------------------------------------------------- 开跑
step "① 起 Token Server + 两个应用实例（集群开启）"
start_token_server
start_app "$PORT_A" true
start_app "$PORT_B" true

step "② 建一个配额为 $QUOTA 的租户"
BASE="http://localhost:$PORT_A"
register_app t-cluster "$QUOTA" 0
ok "租户已建：$APP_ID（qps=$QUOTA）"
sleep 2
# **等 Token Server 追上**：它的规则轮询是 10 秒一轮（设计上的传播上界）。
# 不等的后果实测过：新租户在服务端"还不存在"→ 客户端拿到 no_rule → **回退本地**
# → 那一轮合计就是 2×配额（而且这件事在降级计数上是**看得见**的，这正是第 7 步那条承诺）。
warn "等 Token Server 追上新建的租户（它的规则轮询周期是 10 秒）……"
sleep 11

step "③ 集群开启：持续 ${DURATION}s 匀速打（两个实例各一个 worker）——合计应当 ≈ $((QUOTA * DURATION))，而不是 $((QUOTA * 2 * DURATION))"
read -r ALLOWED TOTAL <<< "$(fire_for_seconds "$DURATION" 1000)"
ok "合计放行=$ALLOWED / $TOTAL（配额 $QUOTA/秒 × ${DURATION}s ≈ $((QUOTA * DURATION))）"
dump_cluster_state "集群开启"
CLUSTER_BOUND=$((QUOTA * DURATION * 8 / 5))     # 1.6 倍余量
assert_true "合计放行不超过 ${CLUSTER_BOUND}（= ${DURATION}s × $QUOTA × 1.6）——超过说明两实例在各自限一份（那会是 $((QUOTA * 2 * DURATION)) 量级）" \
    "$([ "$ALLOWED" -le "$CLUSTER_BOUND" ] && echo 1 || echo 0)"
assert_true "至少放行了 $((QUOTA * DURATION / 2))（否则可能是链路根本没通，不是限流生效）" \
    "$([ "$ALLOWED" -ge $((QUOTA * DURATION / 2)) ] && echo 1 || echo 0)"

step "④ 降级可见：停掉 Token Server，再打一轮——合计会回到 ≈2×配额，**且降级计数必须涨**"
BEFORE=$(scrape_fallback)
# 只杀 Token Server 那一个进程：**按记下来的 PID**（别用 pkill -f——本项目踩过
# "pkill 把执行它的 shell 自己一起杀掉"的坑）
kill "$TOKEN_PID"
PIDS=("${PIDS[@]/$TOKEN_PID}")
sleep 2
read -r ALLOWED_DEGRADED TOTAL_DEG <<< "$(fire_for_seconds "$DURATION" 2000)"
AFTER=$(scrape_fallback)
ok "降级后合计放行=$ALLOWED_DEGRADED / $TOTAL_DEG（降级计数 $BEFORE → $AFTER）"
dump_cluster_state "集群已停"
assert_true "集群断了之后合计明显变大（本地回退生效：两实例各限一份 ≈ $((QUOTA * 2 * DURATION))，而集群是 ≈ $((QUOTA * DURATION))）" \
    "$([ "$ALLOWED_DEGRADED" -gt "$CLUSTER_BOUND" ] && echo 1 || echo 0)"
assert_true "降级计数必须上涨——**降级不能是静默的**（没有这条，集群早退化成单机了也没人知道）" \
    "$([ "$AFTER" -gt "$BEFORE" ] && echo 1 || echo 0)"

echo >&2
printf '\033[1;32mCLUSTER VERIFY PASS assertions=%d\033[0m\n' "$ASSERTIONS" >&2
