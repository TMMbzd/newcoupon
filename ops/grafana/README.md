# ops/grafana —— Grafana SLO 盘（V6 5C 生产化模拟刀 B1）

观测栈的"看"这一半：Prometheus 抓应用指标（含 5A 的 SLO recording 规则），Grafana
把规则产出的序列摆成一张 **SLO 盘**。抓取那一半的配置在同目录 `../prometheus/`。

## 文件清单

| 文件 | 作用 |
|---|---|
| `provisioning/datasources/prometheus.yml` | 数据源声明式配置：指向同 profile 的 prometheus，`uid: prometheus` 与大盘 JSON 里的引用对应 |
| `provisioning/dashboards/slo-provider.yml` | 大盘加载器：从 `/var/lib/grafana/dashboards` 读 JSON（compose 把 `slo-dashboard.json` 挂进去） |
| `slo-dashboard.json` | **SLO 盘本体（进 git）**。六面板：四链路可用性（1h/6h）、错误预算剩余（28d）、燃烧率三窗（1h/6h/3d）、SLI 事件速率（journey×outcome）、断流状态（`up`，state-timeline）、延迟 P99（分链路档位） |

面板的口径约束：**查询全部走 `ops/prometheus/` 的 recording 规则名**
（`coupon:sli_availability_ratio:*`、`coupon:sli_error_budget_remaining:ratio`、
`coupon:sli_burn_rate:*`、`coupon_sli_events_total`、`coupon:sli_latency_p99_seconds:5m`），
不在盘上重新写公式——盘与规则同源，口径改动只改规则文件一处，盘自动跟随。
SLO 口径全文见 `docs/operations/SLO.md`。

## 抓哪条 target：单实例 vs 双实例（两种形态别互踩）

| 形态 | target | 说明 |
|---|---|---|
| 单实例（默认，`make run`/压测） | `host.docker.internal:8080` | prometheus.yml 里 `job_name: newcoupon` 启用 |
| 双实例（刀 A 滚动重启演练，18091/18092） | 两个直抓 target | 把 `newcoupon` job 的 targets 注释掉、放开文件尾部注释的 `newcoupon-dual` job |

**双实例为什么必须各抓各的、不能经 nginx:8080 合抓一条**：两个实例的
`coupon_sli_events_total` 是各自 JVM 的计数器，合成一个序列会把"实例重启计数器归零"
误读成流量塌陷，`increase()` 的重置处理也被两实例交错归零搅乱——SLO 失真。分开抓后
`instance` 标签天然区分实例，counter 语义干净（up 面板也会各自一条）。

## 起停（profile `observe`，默认不起）

```bash
# 应用先在宿主机起来（make run 或演练脚本），且启动时显式给了 Basic 凭据：
#   ACTUATOR_AUTH_USER=actuator ACTUATOR_AUTH_PASSWORD=<密码> make run
# （不配则应用的 Basic 密码随机生成，prometheus 永远抓不到——fail-safe，见
#   docs/runbook/管理面认证翻面.md §4。）

# 起观测栈（compose 环境变量 ACTUATOR_AUTH_PASSWORD 缺省回落仓库惯例 dev 凭据
# it-actuator-pass——与演练脚本/SecurityRbacIntegrationTest 同款；真实部署显式传强密码）：
ACTUATOR_AUTH_PASSWORD=<同应用侧密码> docker compose --profile observe up -d prometheus grafana

# 验证（不等 UI，API 直接说真话）：
curl -s http://127.0.0.1:9090/api/v1/targets | python3 -m json.tool | grep -E '"health"|"scrapeUrl"'
curl -s http://127.0.0.1:9090/api/v1/rules | python3 -m json.tool | grep -E '"name"|"state"' | head
# 经 grafana 数据源代理查一个真实序列（admin 凭据可用 GRAFANA_ADMIN_PASSWORD 覆盖）：
curl -s -u admin:admin 'http://127.0.0.1:3000/api/datasources/proxy/uid/prometheus/api/v1/query?query=up'

# 停（还原）——**点名停这两个**，不要 `--profile observe stop`：
# compose 的 stop 不带服务名会停掉项目里所有在跑的服务（连 MySQL/Redis 一起停）。
docker compose stop prometheus grafana
```

UI：`http://127.0.0.1:3000`（admin / 默认 `admin`，本地演练便利值——生产或对外暴露时
用 `GRAFANA_ADMIN_PASSWORD` 覆盖）。大盘在 `SLO` 文件夹，标题 `newcoupon SLO（单机模拟）`。

## 内存预算（WSL2 ~6.6G 纪律）

| 容器 | 限额（compose `mem_limit`） | 2026-11-10 实测稳态（docker stats） |
|---|---:|---:|
| prometheus | 256m | **35 MB**（单目标 15s 抓取 + 15d retention，named volume `prometheus-data`） |
| grafana | 256m | **56 MB**（一张盘无插件） |

两容器合计上限 ~512m、实测 ~91m。**压测/故障演练时按老纪律停掉**
（`docker compose stop prometheus grafana`）——观测栈本身抢的内存会污染容量数字；
观测取证与压测复测分窗口跑（2026-11-10 实操即如此）。取证完即停，不留常驻。

Prometheus retention 15d < 28d 窗口的含义见 `../prometheus/README.md` 边界 1：
`coupon:sli_availability_ratio:28d` 与错误预算面板是"现有数据的近似"。

## 单机模拟声明（不能外推的边界）

- **单实例、非 HA**：大盘上所有序列来自一个应用实例（`up` 只有一条）；多实例后
  availability/burn 的聚合口径要按 SLO.md §6 重述。
- **告警只打标不路由**：四条告警规则在 Prometheus 里评估（`/api/v1/rules` 可见
  state），Alertmanager 未部署——盘上看不到告警通知，属本刀裁剪线（见
  `docs/operations/SLO.md`"不做"节）。
- **断流面板只覆盖抓取面**：`up==0` 是实例没了/抓取失败；"up 正常但某链路序列消失"
  的埋点回归由 `CouponSliStreamMissing`（absent_over_time 10m）执法，不在本面板承诺内。
- 凭据链：`ACTUATOR_AUTH_PASSWORD` 同时给应用与 prometheus（compose 环境变量 →
  容器启动时写出密码文件 → `prometheus.yml` 的 `basic_auth_password_file` 引用，
  密码全程不落 git/配置文件）。compose 缺省回落 dev 凭据（与仓库脚本同款默认），
  是为了让 `--profile observe up` 永远可跑：首跑曾把 default 留空，任何一次没带
  环境变量的 compose up 都会把 prometheus 重建成无凭据崩溃循环（2026-11-10 实测踩到）。
