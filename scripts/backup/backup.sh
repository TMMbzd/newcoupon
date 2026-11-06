#!/usr/bin/env bash
#
# MySQL 逻辑备份（V6 阶段 5B 刀 2 ①）——一次 mysqldump，产出一个带元信息的备份文件。
#
# 备份的是什么：compose 里的 newcoupon 业务库，**逻辑备份**（SQL 文本 + gzip），
# 不是物理文件拷贝。逻辑备份的恢复粒度是"整库重建"，够 MVP 演练，不够"恢复到任意
# binlog 位点"——后者需要 binlog 归档，本轮登记为裁剪项（见 evidence 报告）。
#
# 为什么参数这么选：
#   --single-transaction    InnoDB 一致性快照，不锁表；备份期间业务照常读写（MySQL 官方推荐）
#   --source-data=2         在备份里写一行 CHANGE MASTER TO（注释形式），记录备份时刻的
#                           binlog 位点。本轮没开 binlog 时该行是空位点，但格式保留，
#                           将来开 binlog 归档后恢复点可直接用
#   --set-gtid-purged=OFF   不在 dump 里写 GTID 语句；当前单机没有 GTID 复制，
#                           写了反而让 restore 到同实例新库时受 GTID 约束
#   --databases newcoupon   dump 里带 CREATE DATABASE/USE，恢复时按库名整体重建
#
# 三道纪律（照 scripts/open-api/drill.sh）：
#   · 只打本机容器（远程要让 DOCKER_HOST 显式配置，不提供）
#   · 必须 CONFIRM=yes（备份本身只读，但它会读全库并落盘；统一护栏便于交接）
#   · 幂等重跑：每次产一个新时间戳文件，latest 软链指向最新；失败不落半截正式文件
#
# 输出目录默认 target/backup（target 已在 .gitignore），可用 BACKUP_DIR 覆盖。
#
# 用法：
#   CONFIRM=yes bash scripts/backup/backup.sh
#   BACKUP_DIR=/tmp/nc-backup CONFIRM=yes bash scripts/backup/backup.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
DB_NAME="${DB_NAME:-newcoupon}"
BACKUP_DIR="${BACKUP_DIR:-$REPO_DIR/target/backup}"

# 在容器里跑 mysql 客户端；密码走环境变量 MYSQL_PWD，避免每条命令都出现 -p 警告。
# $1=数据库名（空串表示不指定库，跨库 information_schema 查询用），其余参数透传 mysql。
mysql_exec() {
  local db="${1:-}"; shift || true
  if [[ -n "$db" ]]; then
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" -N -B "$db" "$@"
  else
    docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -u"$MYSQL_USER" -N -B "$@"
  fi
}

# ---------------------------------------------------------------- 护栏
step "0. 前置检查（三道护栏）"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "备份会读全库并落盘。确认请加：CONFIRM=yes"

docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $MYSQL_CONTAINER 不在运行——先 make up"
ok "MySQL 容器在运行：$MYSQL_CONTAINER"

mysql_exec -e "SELECT 1" >/dev/null \
  || fail "连不上 MySQL（用户 $MYSQL_USER，库 $DB_NAME）"
mysql_exec -e "SELECT 1 FROM \`$DB_NAME\`.flyway_schema_history LIMIT 1" >/dev/null \
  || fail "库 $DB_NAME 不存在或没有 flyway_schema_history——库名是否写错？"
ok "业务库可读：$DB_NAME"

# 跨分表合计（元信息与恢复脚本共用口径）。$1=库名 $2=表名前缀。
# 先枚举再拼 UNION ALL，不写死表名——分表布局变化时仍成立。
sum_prefix_sql() {
  local db="$1" prefix="$2" first=1 sql="" t
  for t in $(mysql_exec "" -e \
      "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_name LIKE '${prefix}%' ORDER BY table_name"); do
    [[ $first -eq 1 ]] && first=0 || sql+=" UNION ALL "
    sql+="SELECT COUNT(*) AS c FROM \`$db\`.\`$t\`"
  done
  if [[ -z "$sql" ]]; then
    echo "SELECT 0 WHERE FALSE"
  else
    echo "SELECT SUM(c) FROM ($sql) x"
  fi
}

mkdir -p "$BACKUP_DIR"

# ---------------------------------------------------------------- 元信息（备份前）
step "1. 采集备份前元信息（表数/关键表行数/数据量）"
TABLE_COUNT=$(mysql_exec -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")
DB_MB=$(mysql_exec -e \
  "SELECT ROUND(SUM(data_length+index_length)/1024/1024,2) FROM information_schema.tables WHERE table_schema='$DB_NAME'")
FLYWAY_ROWS=$(mysql_exec -e "SELECT COUNT(*) FROM \`$DB_NAME\`.flyway_schema_history")
TEMPLATE_ROWS=$(mysql_exec -e "SELECT COUNT(*) FROM \`$DB_NAME\`.coupon_template")
STOCK_USED=$(mysql_exec -e "SELECT COALESCE(SUM(used),0) FROM \`$DB_NAME\`.coupon_stock_shard")
AUDIT_ROWS=$(mysql_exec -e "SELECT COUNT(*) FROM \`$DB_NAME\`.audit_log")
TASK_ROWS=$(mysql_exec -e "SELECT COUNT(*) FROM \`$DB_NAME\`.coupon_issue_task")
# GROUP_CONCAT 默认 1024 字节，43 行 flyway 历史会被截断；摘要前显式放大。
GC_PREFIX="SET SESSION group_concat_max_len=1000000; "
FLYWAY_DIGEST=$(mysql_exec "$DB_NAME" -e "${GC_PREFIX}SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', installed_rank, version, description, type, script, checksum, success) ORDER BY installed_rank SEPARATOR '|')) FROM flyway_schema_history")
TEMPLATE_NAME_DIGEST=$(mysql_exec "$DB_NAME" -e "${GC_PREFIX}SELECT MD5(GROUP_CONCAT(CONCAT_WS(':', id, template_code, name, HEX(name)) ORDER BY id SEPARATOR '|')) FROM coupon_template")
TEMPLATE_NONASCII=$(mysql_exec "$DB_NAME" -e \
  "SELECT COUNT(*) FROM coupon_template WHERE HEX(name) REGEXP '(8|9|A|B|C|D|E|F)[0-9A-F]'")
USER_COUPON_ROWS=$(mysql_exec -e "$(sum_prefix_sql "$DB_NAME" 'user_coupon_')")
COUPON_USAGE_ROWS=$(mysql_exec -e "$(sum_prefix_sql "$DB_NAME" 'coupon_usage_')")
EXPIRE_TASK_ROWS=$(mysql_exec -e "$(sum_prefix_sql "$DB_NAME" 'coupon_expire_task_')")
RELEASE_TASK_ROWS=$(mysql_exec -e "$(sum_prefix_sql "$DB_NAME" 'coupon_release_task_')")
ok "表数=$TABLE_COUNT，数据量≈${DB_MB}MB，flyway=$FLYWAY_ROWS 行，模板=$TEMPLATE_ROWS，Σused=$STOCK_USED，audit=$AUDIT_ROWS，异步任务=$TASK_ROWS"
ok "分表合计：user_coupon=$USER_COUPON_ROWS，coupon_usage=$COUPON_USAGE_ROWS，expire_task=$EXPIRE_TASK_ROWS，release_task=$RELEASE_TASK_ROWS"

# ---------------------------------------------------------------- dump
step "2. mysqldump（单事务一致性快照）"
TS=$(date +%Y%m%d-%H%M%S)
OUT="$BACKUP_DIR/newcoupon-$TS.sql.gz"
TMP="$BACKUP_DIR/.newcoupon-$TS.sql.gz.tmp"
trap 'rm -f "$TMP"' EXIT INT TERM

START_MS=$(date +%s%3N)
# 直接管道到 gzip：dump 文本通常比库文件大，先落明文再压会浪费磁盘。
# pipefail 让 mysqldump 失败（例如权限不足）能传出来，而不是只看到 gzip 成功。
docker exec -e MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_CONTAINER" \
  mysqldump -u"$MYSQL_USER" \
    --single-transaction \
    --source-data=2 \
    --set-gtid-purged=OFF \
    --databases "$DB_NAME" \
  | gzip -9 > "$TMP"
END_MS=$(date +%s%3N)
DUMP_MS=$((END_MS - START_MS))

[[ -s "$TMP" ]] || fail "备份文件为空——mysqldump 可能失败了"
mv "$TMP" "$OUT"
trap - EXIT INT TERM
ln -sfn "$(basename "$OUT")" "$BACKUP_DIR/latest.sql.gz"
ok "备份完成：$OUT（$((DUMP_MS / 1000)).$(printf '%03d' $((DUMP_MS % 1000))) 秒）"

# ---------------------------------------------------------------- 元信息（备份后）
step "3. 写元信息（供恢复脚本与报告引用）"
SIZE_BYTES=$(stat -c %s "$OUT")
SHA256=$(sha256sum "$OUT" | cut -d' ' -f1)
# dump 开头的 CHANGE MASTER TO 行 = 备份时刻的 binlog 位点（本轮未开 binlog，通常为空值）
SOURCE_LINE=$(zgrep -m1 -E '^-- CHANGE (MASTER|REPLICATION SOURCE) TO' "$OUT" || echo "-- (no source-data line)")
META="$OUT.meta"
python3 - "$META" "$OUT" "$SIZE_BYTES" "$SHA256" "$DUMP_MS" "$TABLE_COUNT" "$DB_MB" \
  "$FLYWAY_ROWS" "$TEMPLATE_ROWS" "$STOCK_USED" "$AUDIT_ROWS" "$TASK_ROWS" "$SOURCE_LINE" \
  "$DB_NAME" "$FLYWAY_DIGEST" "$TEMPLATE_NAME_DIGEST" "$TEMPLATE_NONASCII" \
  "$USER_COUPON_ROWS" "$COUPON_USAGE_ROWS" "$EXPIRE_TASK_ROWS" "$RELEASE_TASK_ROWS" <<'PY'
import json
import sys
from datetime import datetime, timezone

(meta_path, out, size_bytes, sha256, dump_ms, table_count, db_mb,
 flyway_rows, template_rows, stock_used, audit_rows, task_rows, source_line,
 db_name, flyway_digest, template_name_digest, template_nonascii,
 user_coupon_rows, coupon_usage_rows, expire_task_rows, release_task_rows) = sys.argv[1:]
payload = {
    "file": out,
    "createdAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
    "bytes": int(size_bytes),
    "sha256": sha256,
    "dumpSeconds": round(int(dump_ms) / 1000.0, 3),
    "database": db_name,
    "tableCount": int(table_count),
    "approxSizeMb": float(db_mb),
    "flywayRows": int(flyway_rows),
    "flywayDigest": flyway_digest,
    "couponTemplates": int(template_rows),
    "templateNameDigest": template_name_digest,
    "templateNonAsciiCount": int(template_nonascii),
    "stockUsedSum": int(stock_used),
    "auditLogRows": int(audit_rows),
    "issueTaskRows": int(task_rows),
    "userCouponRows": int(user_coupon_rows),
    "couponUsageRows": int(coupon_usage_rows),
    "couponExpireTaskRows": int(expire_task_rows),
    "couponReleaseTaskRows": int(release_task_rows),
    "sourceDataLine": source_line.strip(),
}
with open(meta_path, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(json.dumps(payload, ensure_ascii=False, indent=2))
PY
ok "元信息：$META"
echo
echo "备份文件：$OUT"
echo "校验：sha256=$SHA256"
