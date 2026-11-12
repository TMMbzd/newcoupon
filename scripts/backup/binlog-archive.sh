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
#      binlog 文件关闭后不可变，同名文件 sha256 必须一致）；
#   3. 写本轮 manifest（时间戳/当前位点/文件清单+sha256）——PITR 按 manifest 找文件，
#      并在重放前用 manifest 的 sha256 校验归档文件完整性（消费方闭环）。
#
# 为什么不直接拷贝活跃文件：活跃文件还在被写，拷出来可能截断在半个事件上，
# mysqlbinlog 解析报错；FLUSH 后关闭的文件是完整且不可变的。
#
# 数据卷世代切换（docker compose down -v / 换卷后）：新卷的 binlog 从 mysql-bin.000001
# 重新编号，与旧归档同名但内容不同。已关闭文件在同一卷内不可变，"同名且序号不超前、
# sha 却不同"只可能是换了卷——此时旧归档整体移入 superseded-<时间戳>/ 子目录留档，
# 本轮继续归档新卷文件。不卡死（排班被一个 fail 永久堵死 = RPO 保护静默失效，比丢旧档
# 更糟），不覆盖（旧世代归档仍可单独用于旧卷的 PITR 回溯）。
#
# 并发护栏：flock 排他锁——同轮 cron 重叠会交错写 .gz 与 manifest（半截文件+清单错乱），
# 第二个实例拿到不到锁直接退出（exit 1，让 cron 告警），绝不并行写。
#
# RPO 口径（写进演练报告的同一句话）：归档周期 P 下，一笔写入最坏要等
# P + 本轮归档耗时 才进入归档目录——这就是"归档粒度下的最坏丢失窗口"。
# MySQL 侧另有 sync-binlog=1 保证事务提交即落盘（mysqld 崩溃不丢已提交事件；
# 整机/整卷丢失才依赖归档窗口）。
#
# 单轮成本（定界）：FLUSH 恒定 + docker cp 新关闭文件 + **仅对新文件**算 sha256
#（已归档条目从上一轮 manifest 增量携带，不重哈希）——单轮成本 ∝ 本轮新增 binlog 量，
# 不随归档总量增长；归档目录本身的清理由 binlog 保留策略/对象存储生命周期负责（本刀裁剪）。
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
DB_NAME="${DB_NAME:-newcoupon}"
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

# 并发护栏（flock）：排他锁拿不到 = 上一轮还没跑完（cron 重叠/手工撞车）。
# 直接退出让调度侧看见失败，绝不并行写归档目录。
LOCK_FILE="$ARCHIVE_DIR/.archive.lock"
exec 9>"$LOCK_FILE"
flock -n 9 \
  || fail "另一轮归档正在运行（$LOCK_FILE 被占用）——本轮退出，未写任何文件"
ok "归档锁已取得（$LOCK_FILE）"

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
step "2. 复制 + gzip（同名已归档且 sha 一致则跳过；同名不同内容=卷世代切换，见下）"
TS=$(date +%Y%m%d-%H%M%S)
ARCHIVED=(); SKIPPED=(); SUPERSEDED_TO=""
for f in "${CLOSED_FILES[@]}"; do
  name="${f##*/}"                       # mysql-bin.000007
  dest="$ARCHIVE_DIR/$name.gz"
  if [[ -f "$dest" ]]; then
    # 已归档：核对 sha256（容器内原文件 vs 解压后的归档）——不可变文件重跑必须一致
    container_sha=$(docker exec "$MYSQL_CONTAINER" sh -c "sha256sum '$f'" | awk '{print $1}')
    local_sha=$(gzip -dc "$dest" | sha256sum | awk '{print $1}')
    if [[ "$container_sha" == "$local_sha" ]]; then
      SKIPPED+=("$name"); continue
    fi
    # 同名不同内容：卷世代切换（down -v 后 binlog 从 000001 重排）。判据是充分的——
    # 同一卷内已关闭文件不可变且序号单调，同名同序号不可能长出不同内容。
    STAMP=$(date +%Y%m%d-%H%M%S)
    SUPERSEDED_TO="superseded-$STAMP"
    warn "检测到数据卷世代切换：$name 与旧归档 sha256 不一致（新卷 binlog 从头编号）"
    warn "旧世代归档整体移入 $ARCHIVE_DIR/$SUPERSEDED_TO/ 留档，本轮继续归档新卷文件"
    mkdir -p "$ARCHIVE_DIR/$SUPERSEDED_TO"
    mv "$ARCHIVE_DIR"/*.gz "$ARCHIVE_DIR/$SUPERSEDED_TO"/ 2>/dev/null || true
    mv "$ARCHIVE_DIR/manifests" "$ARCHIVE_DIR/$SUPERSEDED_TO/manifests" 2>/dev/null || true
    mkdir -p "$ARCHIVE_DIR/manifests"
    # 世代切换前的本轮统计作废（那些文件属于旧世代的账）
    ARCHIVED=(); SKIPPED=()
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
step "3. 写 manifest（PITR 靠它找文件与位点；重放前按 sha256 校验=消费方闭环）"
MANIFEST="$ARCHIVE_DIR/manifests/run-$TS.manifest.json"
# 单轮成本定界：只哈希**本轮新增**文件；历史条目从最近一份 manifest 增量携带
#（它们入库当轮已算过 sha）。目录里已不存在的条目（世代切换搬走/人工清理）自然淘汰。
ARCHIVED_LIST="${ARCHIVED[*]}" SKIPPED_LIST="${SKIPPED[*]}" \
DB_NAME="$DB_NAME" SUPERSEDED_TO="${SUPERSEDED_TO:-}" \
python3 - "$MANIFEST" "$TS" "$ACTIVE_FILE" "$ACTIVE_POS" "$(date -Is)" <<'PY'
import gzip, hashlib, json, os, sys

manifest_path, ts, active_file, active_pos, now = sys.argv[1:6]
db_name = os.environ["DB_NAME"]
superseded_to = os.environ.get("SUPERSEDED_TO", "")
archive_dir = os.path.dirname(os.path.dirname(os.path.abspath(manifest_path)))

# 找最近一份 manifest 携带历史条目（文件名含时间戳，字典序=时间序）
manifests_dir = os.path.join(archive_dir, "manifests")
prev_entries = {}
runs = sorted(f for f in os.listdir(manifests_dir) if f.endswith(".json"))
if runs:
    try:
        prev = json.load(open(os.path.join(manifests_dir, runs[-1]), encoding="utf-8"))
        prev_entries = {e["file"]: e for e in prev.get("files", [])}
    except Exception:
        prev_entries = {}   # 上一份坏了就全量重算，不猜

files = sorted(f for f in os.listdir(archive_dir) if f.endswith(".gz"))
entries = []
for f in files:
    name = f[:-3]
    path = os.path.join(archive_dir, f)
    size = os.path.getsize(path)
    e = dict(prev_entries.get(name, {}))
    if e.get("bytesCompressed") == size and "sha256" in e:
        e["bytesCompressed"] = size          # 尺寸一致 → 携带旧 sha（入库当轮已算）
    else:                                    # 新文件/尺寸变了 → 现算
        raw = gzip.open(path, "rb").read()
        e = {"file": name, "bytesCompressed": size,
             "sha256": hashlib.sha256(raw).hexdigest()}
    e["file"] = name
    entries.append(e)
payload = {
    "run": ts,
    "createdAt": now,
    "activeFile": active_file,
    "activePosition": int(active_pos),
    "database": db_name,
    "archivedThisRun": os.environ["ARCHIVED_LIST"].split(),
    "skippedThisRun": os.environ["SKIPPED_LIST"].split(),
    "files": entries,
}
if superseded_to:
    payload["superseded"] = {"movedTo": superseded_to,
                             "reason": "binlog 序号回退+sha 不一致：数据卷世代切换（down -v/换卷）"}
with open(manifest_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\n")
print(json.dumps(payload, ensure_ascii=False, indent=2))
PY
ok "manifest：$MANIFEST"
echo
echo "本轮归档 ${#ARCHIVED[@]} 个，跳过（已归档）${#SKIPPED[@]} 个${SUPERSEDED_TO:+，旧世代已移入 $SUPERSEDED_TO/}"
