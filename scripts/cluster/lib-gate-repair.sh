#!/usr/bin/env bash
#
# gate 修复跨进程验证的公共函数（被 verify-gate-repair.sh source）。
#
# 设计约束（与 verify-cluster.sh 保持一致）：
#   · 只由主脚本记录 PID / 容器名，清理时按名单停，**绝不 pkill -f**；
#   · 专用 schema + 专用 Redis 端口，不碰 dev 库与 dev Redis；
#   · 断言失败一律 fail()，由主脚本的 EXIT trap 统一清理并打印 FAIL 行；
#   · 跨进程证据 = 两个实例各自 /actuator/prometheus 上
#     issue_gate_result_total{result="repair_gate_*"} 的增量之和。
#
# 依赖：调用方先 source scripts/open-api/lib.sh（ok/warn/fail/step/json_get）。

: "${GR_PORT_A:=18091}"
: "${GR_PORT_B:=18092}"
: "${GR_REDIS_PORT:=6380}"
: "${GR_REDIS_REPLICA_PORT:=6381}"
: "${GR_REDIS_MASTER:=newcoupon-gate-redis}"
: "${GR_REDIS_REPLICA:=newcoupon-gate-redis-replica}"
: "${GR_NET:=newcoupon-gate-verify}"
: "${GR_MYSQL_CONTAINER:=newcoupon-mysql}"
: "${GR_MYSQL_PASSWORD:=root123456}"
: "${GR_QUOTA:=30}"
: "${GR_USER_SHARDS:=64}"

ASSERTIONS=0
GR_CONTAINERS=()
GR_TMP_DIRS=()
GR_LAST_HTTP=""
GR_LAST_CODE=""
GR_STORM_HTTP202=0
GR_STORM_HTTP200=0
GR_STORM_HTTP_OTHER=0
GR_STORM_OK=0
GR_STORM_REJECTED=0
GR_STORM_SOLDOUT=0
GR_STORM_OTHER=0

gr_num() {
  local v
  v=$(printf '%s' "${1:-}" | tr -dc '0-9')
  printf '%s' "${v:-0}"
}

gr_require() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令：$1"
}

gr_note() {
  if [ -n "${GR_RUN_DIR:-}" ]; then
    printf '%s %s\n' "$(date -Iseconds)" "$*" >> "$GR_RUN_DIR/evidence.txt"
  fi
}

gr_assert() {  # $1=描述 $2=1/0
  ASSERTIONS=$((ASSERTIONS + 1))
  if [ "$2" = "1" ]; then ok "$1"; else fail "断言失败：$1"; fi
}

gr_check_port_free() {
  local p=$1
  if ss -ltn 2>/dev/null | awk -v p=":$p" 'NR > 1 && $4 ~ p "$" { found = 1 } END { exit(found ? 0 : 1) }'; then
    fail "端口 $p 已被占用（先停掉占用进程/容器）"
  fi
}

gr_wait_health() {  # $1=端口 $2=超时秒
  local port=$1 timeout=${2:-120} i
  for ((i = 1; i <= timeout; i++)); do
    if curl -sf --max-time 3 "http://127.0.0.1:${port}/actuator/health" 2>/dev/null \
        | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# ---------------------------------------------------------------- 应用进程
gr_start_app() {  # $1=端口 $2=日志文件
  local port=$1 log=$2
  # WORKER_ID= 清空环境里的显式 workerId，强制两个实例各抢一个 Redis 租约；
  # 否则 shell 里残留 WORKER_ID 会让第二个实例启动失败（冲突 fail-fast）。
  WORKER_ID= java -Xms128m -Xmx256m -XX:MaxMetaspaceSize=256m \
    -Duser.timezone=Asia/Shanghai \
    -cp "$GR_CP" com.newcoupon.NewCouponApplication \
    --server.port="$port" \
    --spring.profiles.active=dev \
    --spring.datasource.url="jdbc:mysql://127.0.0.1:3306/$GR_SCHEMA?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai" \
    --spring.datasource.username=root \
    --spring.datasource.password="$GR_MYSQL_PASSWORD" \
    --spring.datasource.hikari.maximum-pool-size=5 \
    --coupon.sharding.url="jdbc:mysql://127.0.0.1:3306/$GR_SCHEMA?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai" \
    --coupon.sharding.username=root \
    --coupon.sharding.password="$GR_MYSQL_PASSWORD" \
    --coupon.admin.token="${ADMIN_TOKEN:-dev-admin-token}" \
    --coupon.sharding.pool.max-size=8 \
    --coupon.sharding.pool.min-idle=1 \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port="$GR_REDIS_PORT" \
    --coupon.issue.async-enabled=true \
    --coupon.issue.async-template-ids='*' \
    --coupon.issue.worker.enabled=true \
    --coupon.issue.worker.parallelism=2 \
    --coupon.issue.worker.group-parallelism=2 \
    --coupon.issue.worker.batch-tx-size=10 \
    --coupon.issue.worker.raw-bulk-pool-size=2 \
    --coupon.issue.gate-compensation.enabled=false \
    --coupon.issue.gate-repair.initial-delay-ms=3600000 \
    --coupon.issue.gate-repair.fixed-delay-ms=3600000 \
    --coupon.risk.ip-rate.enabled=false \
    --coupon.circuit-breaker.min-calls=10000 \
    --coupon.circuit-breaker.window-size=10000 \
    --coupon.redis.info-interval-ms=5000 \
    --coupon.redis.info-initial-delay-ms=1000 \
    --xxl.job.enabled=false \
    --logging.level.com.newcoupon=INFO \
    >> "$log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  printf '%s' "$pid" > "$GR_RUN_DIR/app-$port.pid"
}

gr_stop_apps() {
  local pid
  for pid in "${PIDS[@]:-}"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  sleep 3
  for pid in "${PIDS[@]:-}"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
  PIDS=()
}

# ---------------------------------------------------------------- 容器
gr_remove_stale() {
  local c
  for c in "$GR_REDIS_MASTER" "$GR_REDIS_REPLICA"; do
    if docker inspect "$c" >/dev/null 2>&1; then
      warn "移除上次残留容器：$c"
      docker rm -f "$c" >/dev/null 2>&1 || true
    fi
  done
  docker network rm "$GR_NET" >/dev/null 2>&1 || true
}

gr_remove_containers() {
  local c
  for c in "${GR_CONTAINERS[@]:-}"; do
    if [ -n "$c" ]; then
      docker rm -f "$c" >/dev/null 2>&1 || true
    fi
  done
}

# failover 重建 master 时把 $GR_RUN_DIR/failover-rdb.* 挂到容器的 /data；
# 官方 redis 镜像 entrypoint 会把挂载目录 chown 给容器内 redis 用户（宿主机上
# 可能是 newcoupon:…、mode 700），普通 rm -rf 会静默失败，随后 mvn clean 删不掉 target。
# 这里先普通删除；仍存在时只对本次 run 目录下的 failover-rdb.* 用 root 一次性容器删。
gr_remove_temp_dir() {
  local dir=$1 base parent
  [ -n "$dir" ] || return 0
  rm -rf -- "$dir" 2>/dev/null || true
  [ -e "$dir" ] || return 0

  # 安全闸：只处理本次 run 目录下、由 mktemp 生成的 failover-rdb.*。
  case "$dir" in
    "${GR_RUN_DIR:-/nonexistent}"/failover-rdb.*) ;;
    *)
      warn "临时目录普通删除失败（非 failover-rdb.*，跳过 root 清理）：$dir"
      return 0
      ;;
  esac
  base=$(basename "$dir")
  case "$base" in
    failover-rdb.[A-Za-z0-9]*) ;;
    *)
      warn "临时目录名不符合 failover-rdb.*（跳过 root 清理）：$dir"
      return 0
      ;;
  esac

  parent=$(dirname "$dir")
  if command -v docker >/dev/null 2>&1; then
    # 只删 /x/$base 这一个路径；挂载父目录，容器内 root 可删除被 chown 的子目录。
    docker run --rm --network none -v "$parent:/x" --entrypoint sh redis:7-alpine \
      -c "rm -rf -- '/x/$base'" >/dev/null 2>&1 || true
  fi
  if [ -e "$dir" ]; then
    rmdir -- "$dir" 2>/dev/null || true
  fi
  if [ -e "$dir" ]; then
    warn "临时目录仍存在（Docker 不可用或镜像缺失，留待人工清理）：$dir"
  fi
  return 0
}

gr_start_redis_master() {
  local conf="$REPO_DIR/docker/redis/redis.conf"
  [ -f "$conf" ] || fail "找不到 Redis 配置：$conf"
  docker network create "$GR_NET" >/dev/null 2>&1 || true
  docker network inspect "$GR_NET" >/dev/null 2>&1 \
    || fail "无法创建 Docker 网络 $GR_NET"
  docker run -d --name "$GR_REDIS_MASTER" --network "$GR_NET" \
    -p "${GR_REDIS_PORT}:6379" \
    -v "$conf:/usr/local/etc/redis/redis.conf:ro" \
    redis:7-alpine redis-server /usr/local/etc/redis/redis.conf >/dev/null \
    || fail "启动专用 Redis master 失败"
  GR_CONTAINERS+=("$GR_REDIS_MASTER")
}

gr_start_redis_replica() {
  docker run -d --name "$GR_REDIS_REPLICA" --network "$GR_NET" \
    -p "${GR_REDIS_REPLICA_PORT}:6379" \
    redis:7-alpine redis-server --replicaof "$GR_REDIS_MASTER" 6379 >/dev/null \
    || fail "启动专用 Redis replica 失败"
  GR_CONTAINERS+=("$GR_REDIS_REPLICA")
}

gr_start_failover_master() {  # $1=含 dump.rdb 的目录
  local datadir=$1
  # 必须挂目录而不是只读单文件：官方 redis 镜像 entrypoint 会对 /data 做 chown，
  # 只读文件挂载会让 chown 失败、容器起不来。
  docker run -d --name "$GR_REDIS_MASTER" --network "$GR_NET" \
    -p "${GR_REDIS_PORT}:6379" \
    -v "$datadir:/data" \
    redis:7-alpine redis-server --appendonly no --save "" >/dev/null \
    || fail "用 RDB 重建 Redis master 失败"
  GR_CONTAINERS+=("$GR_REDIS_MASTER")
}

# ---------------------------------------------------------------- Redis
gr_redis_container() {  # $1=容器名 $2..=redis-cli 参数
  local c=$1
  shift
  docker exec "$c" redis-cli --raw "$@" 2>/dev/null || true
}

gr_redis() {
  gr_redis_container "$GR_REDIS_MASTER" "$@"
}

gr_redis_wait_ping() {  # $1=容器名 $2=超时秒
  local c=$1 timeout=${2:-30} i
  for ((i = 0; i < timeout; i++)); do
    if [ "$(gr_redis_container "$c" PING)" = "PONG" ]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

gr_redis_uptime() {
  gr_num "$(gr_redis_container "$GR_REDIS_MASTER" INFO server \
    | awk -F: '/^uptime_in_seconds:/{gsub(/\r/, "", $2); print $2}')"
}

gr_redis_aof_enabled() {
  gr_num "$(gr_redis_container "$GR_REDIS_MASTER" INFO persistence \
    | awk -F: '/^aof_enabled:/{gsub(/\r/, "", $2); print $2}')"
}

gr_redis_role() {  # $1=容器名
  gr_redis_container "$1" INFO replication \
    | awk -F: '/^role:/{gsub(/\r/, "", $2); print $2}'
}

gr_redis_wait_link_up() {  # $1=超时秒
  local timeout=${1:-60} i
  for ((i = 0; i < timeout; i++)); do
    if gr_redis_container "$GR_REDIS_REPLICA" INFO replication \
        | grep -q 'master_link_status:up'; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# ---------------------------------------------------------------- gate
gr_gate_key() {
  printf 'c:issue:gate:{%s}' "$1"
}

gr_gate_get() {
  gr_redis GET "$(gr_gate_key "$1")"
}

gr_gate_is_numeric() {
  [ -n "${1:-}" ] || return 1
  case "$1" in *[!0-9]*) return 1 ;; *) return 0 ;; esac
}

gr_corrupt_gate() {
  local tpl=$1 key val
  key=$(gr_gate_key "$tpl")
  gr_redis SET "$key" "not-a-number" >/dev/null
  val=$(gr_gate_get "$tpl")
  [ "$val" = "not-a-number" ] || fail "损坏 gate 失败：GET=$val"
}

# ---------------------------------------------------------------- MySQL
gr_mysql_admin() {
  docker exec -e MYSQL_PWD="$GR_MYSQL_PASSWORD" "$GR_MYSQL_CONTAINER" \
    mysql -uroot -N -B -e "$1" 2>/dev/null || true
}

gr_mysql() {  # $1=schema $2=SQL
  docker exec -e MYSQL_PWD="$GR_MYSQL_PASSWORD" "$GR_MYSQL_CONTAINER" \
    mysql -uroot -N -B -e "$2" "$1" 2>/dev/null || true
}

gr_create_schema() {
  local schema=$1
  [[ "$schema" =~ ^[A-Za-z0-9_]+$ ]] || fail "非法 schema 名：$schema"
  gr_mysql_admin "CREATE DATABASE IF NOT EXISTS \`$schema\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" >/dev/null
  [ -n "$(gr_mysql_admin "SHOW DATABASES LIKE '$schema'")" ] || fail "创建 schema 失败：$schema"
  gr_note "schema created: $schema"
}

gr_drop_schema() {
  local schema=$1
  [ -n "$schema" ] || return 0
  gr_mysql_admin "DROP DATABASE IF EXISTS \`$schema\`" >/dev/null 2>&1 || true
}

gr_task_inflight() {
  local raw
  raw=$(gr_mysql "$GR_SCHEMA" \
    "SELECT COUNT(*) FROM coupon_issue_task WHERE template_id=$GR_TEMPLATE_ID AND status IN (0,1)")
  case "$raw" in
    ''|*[!0-9]*) printf '255' ;;
    *) printf '%s' "$raw" ;;
  esac
}

gr_sql_num() {  # SQL 失败返回 255，避免被 0 静默掩盖
  local raw
  raw=$(gr_mysql "$GR_SCHEMA" "$1")
  case "$raw" in
    ''|*[!0-9]*) printf '255' ;;
    *) printf '%s' "$raw" ;;
  esac
}

gr_task_success() {
  gr_sql_num "SELECT COUNT(*) FROM coupon_issue_task WHERE template_id=$GR_TEMPLATE_ID AND status=2"
}

gr_stock_used() {
  gr_sql_num "SELECT IFNULL(SUM(used),0) FROM coupon_stock_shard WHERE template_id=$GR_TEMPLATE_ID"
}

gr_stock_quota() {
  gr_sql_num "SELECT IFNULL(SUM(quota),0) FROM coupon_stock_shard WHERE template_id=$GR_TEMPLATE_ID"
}

gr_user_coupon_count() {
  local parts="" i
  for ((i = 0; i < GR_USER_SHARDS; i++)); do
    parts="${parts}${parts:+ UNION ALL }SELECT COUNT(*) AS c FROM \`user_coupon_${i}\` WHERE template_id=$GR_TEMPLATE_ID"
  done
  gr_sql_num "SELECT IFNULL(SUM(c),0) FROM ($parts) x"
}

gr_wait_tasks_terminal() {  # $1=超时秒
  local timeout=${1:-90} i n
  for ((i = 0; i < timeout; i++)); do
    n=$(gr_task_inflight)
    if [ "$n" = "0" ]; then return 0; fi
    sleep 1
  done
  return 1
}

# ---------------------------------------------------------------- HTTP
gr_receive() {  # $1=base $2=userId $3=requestId $4=响应体落盘
  local base=$1 uid=$2 rid=$3 bodyfile=$4 http code
  if ! http=$(curl -sS --max-time 20 -o "$bodyfile" -w '%{http_code}' \
      -X POST "$base/api/coupons/receive-async" \
      -H 'Content-Type: application/json' \
      -d "{\"requestId\":\"$rid\",\"userId\":$uid,\"templateId\":$GR_TEMPLATE_ID}" 2>/dev/null); then
    http="000"
  fi
  code=$(grep -o '"code":[-0-9]*' "$bodyfile" 2>/dev/null | head -1 | cut -d: -f2 || true)
  GR_LAST_HTTP=$http
  GR_LAST_CODE=${code:-?}
}

gr_create_template() {  # $1=base；结果写入 GR_TEMPLATE_ID
  local base=$1 resp id
  resp=$(curl -sS --max-time 10 -X POST "$base/api/templates" \
    -H 'Content-Type: application/json' \
    -H "X-Admin-Token: ${ADMIN_TOKEN:-dev-admin-token}" \
    -d "{\"name\":\"gate-repair-verify-$RUN_ID\",\"activityId\":1,\"couponType\":1,\"faceValue\":1000,\"threshold\":10000,\"totalCount\":$GR_QUOTA,\"perUserLimit\":1,\"validType\":2,\"validDays\":30,\"dailyLimit\":0,\"perUserDailyLimit\":0}")
  id=$(printf '%s' "$resp" | json_get '["data"]["id"]' 2>/dev/null || true)
  case "$id" in
    ''|None|null) fail "建券失败：$resp" ;;
  esac
  GR_TEMPLATE_ID=$id
  gr_note "template=$id quota=$GR_QUOTA"
}

gr_publish_template() {  # $1=base
  local base=$1 resp code
  resp=$(curl -sS --max-time 10 -X POST "$base/api/templates/$GR_TEMPLATE_ID/publish" \
    -H "X-Admin-Token: ${ADMIN_TOKEN:-dev-admin-token}")
  code=$(printf '%s' "$resp" | json_get '["code"]' 2>/dev/null || true)
  [ "$code" = "0" ] || fail "发布模板失败：$resp"
  gr_note "template published: $GR_TEMPLATE_ID"
}

# ---------------------------------------------------------------- 指标
gr_scrape() {
  curl -sS --max-time 5 "http://127.0.0.1:$1/actuator/prometheus" 2>/dev/null || true
}

gr_counter() {  # $1=端口 $2=result 标签值
  gr_scrape "$1" | awk -v want="$2" '
    $1 ~ /^issue_gate_result_total\{/ && index($0, "result=\"" want "\"") { s += $NF }
    END { printf "%.0f\n", s + 0 }'
}

gr_gate_scope_counter() {  # $1=端口：gate 类修复（missing/corrupt/empty）
  local p=$1
  echo $(( $(gr_counter "$p" repair_gate_corrupt) \
          + $(gr_counter "$p" repair_gate_missing) \
          + $(gr_counter "$p" repair_gate_empty) ))
}

gr_sum_counter() {
  echo $(( $(gr_counter "$GR_PORT_A" "$1") + $(gr_counter "$GR_PORT_B" "$1") ))
}

gr_sum_gate_scope() {
  echo $(( $(gr_gate_scope_counter "$GR_PORT_A") + $(gr_gate_scope_counter "$GR_PORT_B") ))
}

gr_save_metrics() {  # $1=标签
  local label=$1
  [ -n "${GR_RUN_DIR:-}" ] || return 0
  gr_scrape "$GR_PORT_A" > "$GR_RUN_DIR/metrics-$label-a.txt"
  gr_scrape "$GR_PORT_B" > "$GR_RUN_DIR/metrics-$label-b.txt"
  gr_note "metrics[$label] corrupt=$(gr_sum_counter repair_gate_corrupt) busy=$(gr_sum_counter repair_busy) gate_scope=$(gr_sum_gate_scope) redis_down=$(gr_sum_counter redis_down_reject)"
}

# ---------------------------------------------------------------- 并发风暴
gr_storm() {  # $1=请求数 $2=用户偏移（默认 0） $3=requestId 前缀（默认 storm）
  # 用户号从 GR_USER_BASE+100 起：+1/+2 留给预热，+900 留给故障探针，
  # 第二波用 offset=1000 避免与第一波撞「每人限领」。
  local count=$1 offset=${2:-0} tag=${3:-storm} tmp start_ms i port uid rid pids=() p
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/gr-storm.XXXXXX")
  GR_TMP_DIRS+=("$tmp")
  start_ms=$(( $(date +%s%3N) + 2500 ))

  for ((i = 1; i <= count; i++)); do
    if (( i % 2 == 1 )); then port=$GR_PORT_A; else port=$GR_PORT_B; fi
    uid=$(( GR_USER_BASE + 100 + offset + i ))
    rid="${tag}-$RUN_ID-$i"
    (
      while [ "$(date +%s%3N)" -lt "$start_ms" ]; do sleep 0.01; done
      if ! http=$(curl -sS --max-time 20 -o "$tmp/body.$i" -w '%{http_code}' \
          -X POST "http://127.0.0.1:$port/api/coupons/receive-async" \
          -H 'Content-Type: application/json' \
          -d "{\"requestId\":\"$rid\",\"userId\":$uid,\"templateId\":$GR_TEMPLATE_ID}" \
          2>"$tmp/err.$i"); then
        http="000"
      fi
      printf '%s' "$http" > "$tmp/http.$i"
    ) &
    pids+=($!)
  done
  for p in "${pids[@]}"; do wait "$p" || true; done

  GR_STORM_HTTP202=0
  GR_STORM_HTTP200=0
  GR_STORM_HTTP_OTHER=0
  GR_STORM_OK=0
  GR_STORM_REJECTED=0
  GR_STORM_SOLDOUT=0
  GR_STORM_OTHER=0
  local http code
  for ((i = 1; i <= count; i++)); do
    http=$(tr -d ' \r\n' < "$tmp/http.$i" 2>/dev/null || true)
    [ -n "$http" ] || http="000"
    code=$(grep -o '"code":[-0-9]*' "$tmp/body.$i" 2>/dev/null | head -1 | cut -d: -f2 || true)
    [ -n "$code" ] || code="?"
    case "$http" in
      202) GR_STORM_HTTP202=$((GR_STORM_HTTP202 + 1)) ;;
      200) GR_STORM_HTTP200=$((GR_STORM_HTTP200 + 1)) ;;
      *)   GR_STORM_HTTP_OTHER=$((GR_STORM_HTTP_OTHER + 1)) ;;
    esac
    case "$code" in
      0)    GR_STORM_OK=$((GR_STORM_OK + 1)) ;;
      5001) GR_STORM_REJECTED=$((GR_STORM_REJECTED + 1)) ;;
      3001) GR_STORM_SOLDOUT=$((GR_STORM_SOLDOUT + 1)) ;;
      *)    GR_STORM_OTHER=$((GR_STORM_OTHER + 1)) ;;
    esac
  done
  gr_note "storm n=$count offset=$offset http202=$GR_STORM_HTTP202 http200=$GR_STORM_HTTP200 httpOther=$GR_STORM_HTTP_OTHER code0=$GR_STORM_OK code5001=$GR_STORM_REJECTED code3001=$GR_STORM_SOLDOUT codeOther=$GR_STORM_OTHER"
  ok "风暴：HTTP 202=$GR_STORM_HTTP202 200=$GR_STORM_HTTP200 其他=$GR_STORM_HTTP_OTHER；业务码 0=$GR_STORM_OK 5001=$GR_STORM_REJECTED 3001(售罄)=$GR_STORM_SOLDOUT 其他=$GR_STORM_OTHER"
}

gr_assert_storm_codes() {
  # 3001 是 gate 被合法耗尽后的售罄；它说明第二波已经打到库存边界，不是修复风暴。
  gr_assert "风暴 HTTP 状态只有 202/200（其他=$GR_STORM_HTTP_OTHER）" \
    "$([ "$GR_STORM_HTTP_OTHER" -eq 0 ] && echo 1 || echo 0)"
  gr_assert "风暴业务码只有 0/5001/3001（其他=$GR_STORM_OTHER）" \
    "$([ "$GR_STORM_OTHER" -eq 0 ] && echo 1 || echo 0)"
  gr_assert "修复后至少 1 个请求受理成功（OK=$GR_STORM_OK）" \
    "$([ "$GR_STORM_OK" -ge 1 ] && echo 1 || echo 0)"
  gr_assert "风暴放行数不超过预热后余额（$GR_STORM_OK <= $((GR_QUOTA - 2))）" \
    "$([ "$GR_STORM_OK" -le $((GR_QUOTA - 2)) ] && echo 1 || echo 0)"
}

gr_assert_no_oversell() {
  local success inflight used quota coupons gate
  gr_wait_tasks_terminal 90 || fail "任务未在 90s 内全部终态"
  success=$(gr_task_success)
  inflight=$(gr_task_inflight)
  used=$(gr_stock_used)
  quota=$(gr_stock_quota)
  coupons=$(gr_user_coupon_count)
  gate=$(gr_gate_get "$GR_TEMPLATE_ID")

  gr_assert "模板 quota=$quota 等于预期 $GR_QUOTA" \
    "$([ "$quota" -eq "$GR_QUOTA" ] && echo 1 || echo 0)"
  gr_assert "任务终态：SUCCESS=$success <= quota=$quota" \
    "$([ "$success" -le "$quota" ] && echo 1 || echo 0)"
  gr_assert "C1 库存：used=$used <= quota=$quota" \
    "$([ "$used" -le "$quota" ] && echo 1 || echo 0)"
  gr_assert "SUCCESS+在途=$((success + inflight)) <= quota=$quota" \
    "$([ $((success + inflight)) -le "$quota" ] && echo 1 || echo 0)"
  gr_assert "user_coupon 行数=$coupons <= quota=$quota" \
    "$([ "$coupons" -le "$quota" ] && echo 1 || echo 0)"
  gr_assert "used=$used 与 SUCCESS=$success 一致" \
    "$([ "$used" -eq "$success" ] && echo 1 || echo 0)"
  gr_assert "风暴后 gate 可读且为数字（实际 ${gate:-<missing>}）" \
    "$(gr_gate_is_numeric "$gate" && echo 1 || echo 0)"
  gr_note "final success=$success inflight=$inflight used=$used quota=$quota coupons=$coupons gate=$gate"
}
