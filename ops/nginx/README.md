# nginx 负载均衡入口（V6 5B 生产化模拟刀 A）

滚动重启演练（`scripts/stability/lb-rolling-drill.sh`）的流量入口：nginx 在容器里，
两个应用实例跑在**宿主机**上（`java -Xmx256m`，18091/18092），所有演练流量经
nginx 8080 进来——这是本刀补的生产形态：摘流量（drain）→ LB 出池 → SIGTERM 优雅停机
→ 重启 → 回池，全程 LB 视角零 5xx、零在途 reset。

## 拓扑（文字版）

```
                      ┌─→ 宿主 java 实例 A :18091 ─┐
客户端（演练脚本/      │   （drain → health 503）    │   共享
压测客户端）──→ nginx :8080（容器，profile lbe2e）  ├─→ MySQL / Redis（compose 容器）
                      │                            │
                      └─→ 宿主 java 实例 B :18092 ─┘

  - nginx 容器内 upstream = host.docker.internal:1809x（host-gateway 指向宿主）
  - 直连口 18091/18092 只给演练脚本用：drain/undrain、健康轮询、SIGTERM
```

**单机模拟声明**：这是一台 WSL2 上的"伪集群"——nginx 与实例之间没有真实跨机网络，
实例故障/重启的语义也只覆盖 SIGTERM（优雅）路径，不覆盖宿主机断电/网络分区。
它验证的是**编排逻辑**（drain → 出池 → 停 → 起 → 回池的序列与断言），不是机房级高可用。

## 起停

```bash
# 起（默认 compose 不带它；演练脚本会自己起停）
docker compose --profile lbe2e up -d nginx

# 停（还原）
docker compose --profile lbe2e stop nginx

# 手工验证一个请求实际由哪个实例服务
curl -sD - -o /dev/null http://127.0.0.1:8080/actuator/health | grep -i x-served-by
# X-Served-By: 192.168.65.254:18091, 192.168.65.254:18092
# （重试时是逗号分隔的尝试列表，最后一项才是真正服务的实例）
```

## 关键配置与为什么（nginx.conf 有逐条注释，这里只列结论）

| 配置 | 值 | 为什么 |
|---|---|---|
| upstream 地址 | `host.docker.internal:1809x` | Linux Docker 没有这个自动解析，compose 里 `extra_hosts: host.docker.internal:host-gateway` 把它指到宿主网关（2026-11-10 实测容器→宿主 java 可达） |
| 被动健康摘除 | `max_fails=1 fail_timeout=30s` | 开源 nginx 无主动探测；drain 后实例 health 503，演练脚本每秒经 LB 打一次 health（模拟主动探测），失败一次即出池 30s。30s 与"drain→确认→SIGTERM→重启完成"整段窗口对齐 |
| **upstream keepalive** | `keepalive 16` + `Connection ""` | 生产最佳实践；在本机（WSL2 + Docker Desktop）另有硬理由，见下节"本机实测坑" |
| `proxy_connect_timeout` | 10s | 见下节"本机实测坑"——吸收 host-gateway 的短暂停滞；死实例检测不受拖累（端口关闭是 RST 秒拒，且编排里 SIGTERM 前已 drain 摘除） |
| `proxy_next_upstream` | `error timeout http_502 http_503 http_504`，**不开** `non_idempotent` | POST（领券）已发给后端就不重试——LB 层无从知道后端是否已受理，盲目重试造成双发；幂等性交给应用层 requestId 幂等键（有意识取舍）。连接阶段失败（未发出）仍换台，不构成双发 |
| 读写超时 | 31s | 略大于应用 graceful 30s：LB 比应用先超时会把"还在处理"的请求判死并重试——双发入口 |
| 故障转移总预算 | `proxy_next_upstream_timeout 25s` | 盖住最坏路径"连接超时 10s × 两台"，同时小于客户端 30s 请求超时（客户端先放弃会把在途请求记成 reset） |
| `X-Served-By` 头 | `$upstream_addr` | 演练断言"被摘实例不再出现在成功分布"的依据（重试时是逗号分隔的尝试列表，末项才是实际服务者） |

## 本机实测坑（WSL2 + Docker Desktop，2026-11-10）

容器→宿主唯一通道是 Docker Desktop 的 host-gateway 用户态代理（192.168.65.254），
实测它对**新建连接**有约 0.25% 的随机停滞率（隔离环境 2500 次新建连接 6 次停滞 >12s，
与 Java/nginx 无关）。演练前几轮的失败全源于此：每请求新建连接 = 每请求抽签，
抽中就是一次 connect 超时 → 被动摘除把好实例误伤出池 → "no live upstreams" 502 洪峰。

两道对策（都在 nginx.conf 里，注释完整）：
1. **upstream keepalive**——建连接从每请求一次降到池化复用，抽签次数趋近于零
   （生产上这也是该有的配置，不只是本机补丁）；
2. **connect_timeout 10s**——停滞多为几秒级，预算给够就"慢而不败"。

容器访问宿主的 eth0 直连路由（172.23.97.x）在 Docker Desktop 下不通（SYN 被丢），
别试。若换到原生 Linux Docker（无用户态代理），这两条仍是无害的生产配置。

## 与演练脚本的关系

- `lb-rolling-drill.sh`：起 A/B 两实例 → 起本 nginx → 经 8080 打流量与探测 →
  滚动 drain/SIGTERM/重启 → 停 nginx 还原。
- 摘除探测方式（选型说明）：脚本每 1 秒经 nginx GET `/actuator/health`
  （模拟 K8s/HAProxy 的主动探测），nginx 的被动摘除靠这些探测打到 503 才触发；
  "已被 LB 摘除"的断言 = 经 nginx 连发 20 个带令牌的 GET `/api/ping`，
  `X-Served-By` 里被摘实例的端口一次都不出现。
- 回池等待 = 轮询直到 `X-Served-By` 重新出现该实例端口。

## 与既有演练的关系

`scripts/stability/dual-instance-smoke.sh`（两端口直连 + 客户端自行 failover）保留：
它验证的是**客户端侧**故障转移与 graceful shutdown 本身；本刀的 nginx 演练验证的是
**LB 侧**生产形态。两者互补，都被 `CONFIRM=yes` 护栏保护、不在 CI 里跑。
