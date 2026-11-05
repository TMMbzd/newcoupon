# ops/prometheus —— SLO 规则文件（V6 阶段 5A）

本目录放 Prometheus 的 recording/alerting 规则与其 promtool 单测，**不新增 prometheus 容器**
（裁剪线：最小可防守——规则 + 文档先立口径，抓取端属于部署侧选择）。
口径全文见 `docs/operations/SLO.md`。

## 文件清单

| 文件 | 作用 |
|---|---|
| `slo-recording.rules.yml` | 可用性比率（1h/6h/3d/28d 四窗）、28d 错误预算剩余、燃烧率（1h/6h/3d） |
| `slo-alerts.rules.yml` | 快烧（page 1h 窗 14.4×）/ 慢烧（ticket 6h 窗 6×）/ SLI 断流（absent_over_time 10m）三条告警 |
| `slo-rules.tests.yml` | `promtool test rules` 用例：三类告警各至少一例 + 记录规则数值抽查 + 低流量保护 |

## 本机校验（promtool 两命令）

本机没有 promtool 二进制，用 prom/prometheus 容器跑（镜像内 `/bin/promtool`）：

```bash
cd ops/prometheus

# 1. 语法/lint 校验（8 条 recording + 3 条 alerting 规则）
docker run --rm -v "$PWD":/rules --entrypoint /bin/promtool \
  prom/prometheus check rules /rules/slo-recording.rules.yml /rules/slo-alerts.rules.yml

# 2. 合成序列触发的行为测试（快烧/慢烧/断流/低流量四组用例）
docker run --rm -v "$PWD":/rules --entrypoint /bin/promtool \
  prom/prometheus test rules /rules/slo-rules.tests.yml
```

两条命令都必须 EXIT=0。改规则后先跑 2 再提交——`check` 只验语法，`test` 才验行为。

## 挂载进已有 Prometheus

应用的指标面在 `/actuator/prometheus`（自 V6 2A-2 起要 `ROLE_ACTUATOR` + Basic，
凭据配置见 `docs/runbook/管理面认证翻面.md`）。在**已有的** Prometheus 上启用本目录规则：

```yaml
# prometheus.yml 增量
rule_files:
  - /etc/prometheus/rules/slo-*.rules.yml

scrape_configs:
  - job_name: newcoupon
    scrape_interval: 15s
    basic_auth:            # 与应用侧 ACTUATOR_AUTH_* 同源
      username: actuator
      password: <凭据>
    static_configs:
      - targets: ['<应用主机>:8080']
```

```yaml
# docker-compose 追加挂载（不新增 prometheus 服务，挂在已有实例上）
volumes:
  - ./ops/prometheus/slo-recording.rules.yml:/etc/prometheus/rules/slo-recording.rules.yml:ro
  - ./ops/prometheus/slo-alerts.rules.yml:/etc/prometheus/rules/slo-alerts.rules.yml:ro
```

改规则后热加载：`kill -HUP <prometheus_pid>` 或 `curl -X POST http://<prometheus>:9090/-/reload`
（需启动参数 `--web.enable-lifecycle`）。

## 两个已知边界

1. **28d 记录 vs retention**：`coupon:sli_availability_ratio:28d` 与错误预算记录要求
   Prometheus retention ≥ 28 天；默认 15 天时这些窗口是"现有数据的近似"（increase 按
   窗口内实际样本计算），短窗告警（1h/6h）不受影响。
2. **告警只打标不路由**：`severity: page|ticket` 分级口径写在 SLO.md §5；
   Alertmanager（按 severity 分路由：page → 值班渠道，ticket → 工单）未部署，
   属本刀裁剪线内，升级路径见 SLO.md"不做"节。
