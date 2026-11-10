# scripts/backup —— 备份体系（全量基线 + binlog 增量 + PITR 演练）

一套脚本两层备份 + 一个恢复演练，口径与报告见
[docs/evidence/2026-11-06-备份恢复演练.md](../../docs/evidence/2026-11-06-备份恢复演练.md)（全量/恢复基线）
与 [docs/evidence/2026-11-10-PITR演练.md](../../docs/evidence/2026-11-10-PITR演练.md)（binlog/PITR）。

| 脚本 | 角色 | 一句话 |
|---|---|---|
| `backup.sh` | **全量基线** | mysqldump 单事务快照 + `.meta`（dump 时刻的 binlog 位点=重放起点、行数/摘要=恢复校验基准） |
| `binlog-archive.sh` | **增量** | FLUSH BINARY LOGS 轮转后，把已关闭的 `mysql-bin.*` 复制+gzip 到归档目录，写 manifest（手动/定时都行） |
| `restore.sh` | 全量恢复 | 恢复到同实例 `newcoupon_restore`，20 条断言对齐 `.meta`（不覆盖源库） |
| `pitr-drill.sh` | **PITR 演练** | 全量恢复到沙箱容器 + mysqlbinlog 从 dump 位点重放到任意时间点 T，断言"T 前在、T 后不在" |

两层怎么配合：**dump=基线（整库重建的起点），binlog=基线之后的每一笔行级变更**。
恢复到时间点 T = restore 全量 + 重放 dump 位点→T 位点之间的 binlog——`pitr-drill.sh`
把这条链路整个跑了一遍并逐项断言。

## 前提：compose 的 binlog 参数

`docker-compose.yml` 的 mysql 服务带 `--log-bin=mysql-bin --binlog-format=ROW
--server-id=1 --sync-binlog=1`（V6 5C）。**command 变更意味着容器重建而不是原地升级**：
数据卷 `mysql-data` 不受影响（2026-11-10 实测 force-recreate 前后表数/行数一致），
但旧基名的 `binlog.*` 文件留在卷里、不进新的 `mysql-bin.*` 序列（新序列从 000001 起）。

## RPO 口径（本仓库统一口径，报告同文）

- **mysqld 进程崩溃**：`sync-binlog=1` 保证事务提交即 fsync binlog——已提交事件不丢，RPO=0。
- **容器/卷整体丢失**（单机的真实风险场景）：损失窗口 = 上一次归档以来的写入。按归档周期 P
  排班，最坏窗口 = **P + 单轮归档耗时**（写入恰好在上一轮归档后落地，要等下一轮 FLUSH+拷贝）。
  实测单轮归档 3.3~7.4s、写入→已归档即时窗口 4.1s（见 PITR 报告）。
  - P=5min：最坏 ≈ 5min+数秒。满足本地 MVP RPO≤15min（5C 计划），**不严格满足
    "生产单机 RPO≤5min"**（5min+ε>5min）——要严格达标需 P=4min 排班或半同步复制。
  - RPO≈0 只能靠同步复制，单机做不到（下节）。

## 与生产的差距（如实登记，不冒充）

| 项 | 本机模拟 | 生产（MySQL 官方/大厂惯例） |
|---|---|---|
| GTID | 未开（server-id=1 最简配置） | GTID 开启：重放/重搭从库按 gtid_set 自动定位，不用手工对位点 |
| 复制 | 无（单机） | 半同步/同步复制到从库——RPO≈0 由"从库确认"保证，不靠归档周期 |
| binlog 归档 | 本地目录 gzip，定时 FLUSH+拷贝 | 近实时上传对象存储/异地（云 RDS 默认秒级），配合 3-2-1 原则 |
| 恢复目标 | 一次性沙箱容器（演练形态） | 独立实例恢复→核对→切流；跨机网络/账号/连接串切换未在本机演练 |
| 库范围 | 只 `newcoupon`（xxl_job 未含，5B 已登记） | 业务库+调度库同一次一致性快照 |
| 备份加密/异地 | 无（本地文件） | 服务端加密+生命周期+异地副本 |

## 用法

```bash
docker compose up -d mysql          # binlog 参数已进 compose
CONFIRM=yes bash scripts/backup/backup.sh          # 全量
CONFIRM=yes bash scripts/backup/binlog-archive.sh  # 增量归档（cron 每 5 分钟一次即可）
CONFIRM=yes bash scripts/backup/restore.sh         # 全量恢复演练（同实例新库）
CONFIRM=yes bash scripts/backup/pitr-drill.sh      # PITR 全链路演练（沙箱容器）
```

PITR 沙箱镜像用 `mysql:8.0-debian`（默认 `mysql:8.0` oracle 底**不带 mysqlbinlog**，
2026-11-10 实测踩到；`PITR_IMAGE` 环境变量可换任何带全套客户端的镜像）。
