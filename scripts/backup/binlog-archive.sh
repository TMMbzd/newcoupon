#!/usr/bin/env bash
#
# binlog 归档（V6 5C 刀 B2）——把 MySQL 已关闭的 binlog 文件复制+压缩到归档目录。
#
# 与 backup.sh 的关系（一套备份体系的两层）：
#   backup.sh        全量逻辑备份（mysqldump）＝基线。恢复时整库重建，粒度=dump 时刻。
#   binlog-archive   增量＝dump 之后的行级变更。PITR = 全量恢复 + 本脚本归档的
#                    binlog 重放到目标时间点（scripts/backup/pitr-drill.sh 演练的就是它）。
#
# 怎么归档（MySQL 官方"FLUSH LOGS 轮转后备份 binlog 即增量"的标准做法）：
#   1. FLUSH BINARY LOGS —— 关闭当前活跃文件（此后不再写它），新开一个；
#   2. 归档所有**已关闭**的 mysql-bin.NNNNNN 中尚未归档过的（按文件名去重；
#      binlog 文件关闭后不可变，同名文件 sha256 必须一致，否则报错不覆盖）；
#   3. 写本轮 manifest（时间戳/当前位点/文件清单+sha256）——PITR 按 manifest 找文件。
#
# 为什么不直接拷贝活跃文件：活跃文件还在被写，拷出来可能截断在半个事件上，
# mysqlbinlog 解析报错；FLUSH 后关闭的文件是完整且不可变的。
#
# RPO 口径（写进演练报告的同一句话）：归档周期 P 下，一笔写入最坏要等
# P + 本轮归档耗时 才进入归档目录——这就是"归档粒度下的最坏丢失窗口"。
# MySQL 侧另有 sync-binlog=1 保证事务提交即落盘（mysqld 崩溃不丢已提交事件；
# 整机/整卷丢失才依赖归档窗口）。
#
# 手动/定时触发都可：cron 每 5 分钟一行即
#   CONFIRM=yes /path/to/binlog-archive.sh
#
# 三道纪律（照 backup.sh）：只打本机容器；CONFIRM=yes（归档只读+FLUSH 轮转，
# 本身无破坏，但 FLUSH 会切 binlog 文件，统一护栏便于交接）；幂等重跑（已归档
# 文件跳过，每轮产新 manifest）。
#
# 用法：
#   CONFIRM=yes bash scripts/backup/binlog-archive.sh
#   BINLOG_ARCHIVE_DIR=/data/binlog CONFIRM=yes bash scripts/backup/binlog-archive.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../open-api/lib.sh
source "$SCRIPT_DIR/../open-api/lib.sh"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-newcoupon-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123456}"
BINLOG_DIR="${BINLOG_DIR:-/var/lib/mysql}"          # 容器内 datadir
BINLOG_BASENAME="${BINLOG_BASENAME:-mysql-bin}"     # compose --log-bin 的基名
ARCHIVE_DIR="${BINLOG_ARCHIVE_DIR:-$REPO_DIR/target/binlog-archive}"

mysql_exec() {   # $1=数据库名（可空）；其余透传 mysql
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
step "0. 前置检查"
[[ "${CONFIRM:-}" == "yes" ]] \
  || fail "归档会 FLUSH BINARY LOGS（切换 binlog 文件）。确认请加：CONFIRM=yes"
docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null | grep -q true \
  || fail "容器 $MYSQL_CONTAINER 不在运行——先 make up"
LOG_BIN=$(mysql_exec -e "SHOW VARIABLES LIKE 'log_bin'" | awk '{print $2}')
[[ "$LOG_BIN" == "ON" ]] || fail "log_bin=OFF——compose 的 mysql 服务没带 --log-bin 参数？"
mkdir -p "$ARCHIVE_DIR/manifests"
ok "binlog 已开启（log_bin=ON），归档目录：$ARCHIVE_DIR"

# ---------------------------------------------------------------- 轮转 + 清点
step "1. FLUSH BINARY LOGS 并清点已关闭文件"
mysql_exec -e "FLUSH BINARY LOGS" || fail "FLUSH BINARY LOGS 失败"
# 轮转后 SHOW MASTER STATUS 指向新活跃文件；datadir 里除此之外的都是已关闭文件
ACTIVE_FILE=$(mysql_exec -e "SHOW MASTER STATUS" | awk '{print $1}')
ACTIVE_POS=$(mysql_exec -e "SHOW MASTER STATUS" | awk '{print $2}')
ok "轮转完成，当前活跃文件：$ACTIVE_FILE 位点 $ACTIVE_POS"

mapfile -t CLOSED_FILES < <(docker exec "$MYSQL_CONTAINER" \
  sh -c "ls $BINLOG_DIR/${BINLOG_BASENAME}.[0-9]* 2>/dev/null | grep -v '${ACTIVE_FILE}$' | sort" || true)
[[ ${#CLOSED_FILES[@]} -gt 0 ]] || fail "找不到已关闭的 ${BINLOG_BASENAME}.* 文件（基名对吗？）"
ok "已关闭文件 ${#CLOSED_FILES[@]} 个：$(printf '%s ' "${CLOSED_FILES[@]##*/}")"

# ---------------------------------------------------------------- 归档
step "2. 复制 + gzip（同名已归档且 sha 一致则跳过——文件不可变，重压无意义）"
TS=$(date +%Y%m%d-%H%M%S)
ARCHIVED=(); SKIPPED=()
for f in "${CLOSED_FILES[@]}"; do
  name="${f##*/}"                       # mysql-bin.000007
  dest="$ARCHIVE_DIR/$name.gz"
  if [[ -f "$dest" ]]; then
    # 已归档：核对 sha256（解压后与容器内文件比）——不可变文件重跑必须一致
    container_sha=$(docker exec "$MYSQL_CONTAINER" sh -c "sha256sum '$f'" | awk '{print $1}')
    local_sha=$(gzip -dc "$dest" | sha256sum | awk '{print $1}')
    if [[ "$container_sha" == "$local_sha" ]]; then
      SKIPPED+=("$name"); continue
    fi
    fail "$name 与已归档副本 sha256 不一致——binlog 文件关闭后不该变化，拒绝覆盖（人工排查）"
  fi
  tmp="$dest.tmp"
  # docker cp 走 docker 的文件通道（不经 shell 管道读二进制），再本地 gzip
  docker cp "$MYSQL_CONTAINER:$f" "$tmp" >/dev/null \
    || fail "docker cp 失败：$f"
  gzip -9 -c "$tmp" > "$dest"
  rm -f "$tmp"
  ARCHIVED+=("$name")
  ok "归档 $name（$(du -h "$dest" | cut -f1)）"
done
[[ ${#ARCHIVED[@]} -gt 0 ]] || true   # 全部跳过也是合法轮次（定时任务空转）

# ---------------------------------------------------------------- manifest
step "3. 写 manifest（PITR 靠它找文件与位点）"
MANIFEST="$ARCHIVE_DIR/manifests/run-$TS.manifest.json"
# 归档清单以**目录里实际存在的文件**为准（本轮新归档/历史轮次一并登记），
# 本轮动作（archived/skipped）经环境变量传入展示字段——名字只含 [a-z0-9.-]，空格分隔安全
ARCHIVED_LIST="${ARCHIVED[*]}" SKIPPED_LIST="${SKIPPED[*]}" \
python3 - "$MANIFEST" "$TS" "$ACTIVE_FILE" "$ACTIVE_POS" "$(date -Is)" <<'PY'
import gzip, hashlib, json, os, sys

manifest_path, ts, active_file, active_pos, now = sys.argv[1:6]
archived = os.environ["ARCHIVED_LIST"].split()
skipped = os.environ["SKIPPED_LIST"].split()
archive_dir = os.path.dirname(os.path.dirname(os.path.abspath(manifest_path)))
files = sorted(f for f in os.listdir(archive_dir) if f.endswith(".gz"))
entries = []
for f in files:
    path = os.path.join(archive_dir, f)
    raw = gzip.open(path, "rb").read()
    entries.append({
        "file": f[:-3],                    # mysql-bin.000007
        "bytesCompressed": os.path.getsize(path),
        "sha256": hashlib.sha256(raw).hexdigest(),
    })
payload = {
    "run": ts,
    "createdAt": now,
    "activeFile": active_file,
    "activePosition": int(active_pos),
    "database": "newcoupon",
    "archivedThisRun": archived,
    "skippedThisRun": skipped,
    "files": entries,
}
with open(manifest_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print(json.dumps(payload, ensure_ascii=False, indent=2))
PY
ok "manifest：$MANIFEST"
echo
echo "本轮归档 ${#ARCHIVED[@]} 个，跳过（已归档）${#SKIPPED[@]} 个"
