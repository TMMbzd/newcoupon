#!/usr/bin/env python3
"""双实例滚动重启冒烟客户端（V6 阶段 5B 刀 2 ④）。

它做一件事：向两个应用实例**轮流**发同步领券请求，直到 stop 文件出现。
每个请求用唯一 userId/requestId，所以正常业务码只可能是 0。

为什么要专门的客户端而不是 curl 循环：
  · 需要区分"连不上（实例已停，负载均衡应切到另一台）"与"请求已发出却被 reset
    （优雅停机不该发生）"——两者都算 curl 失败，但结论完全不同；
  · 需要按实例统计成功数，证明流量真的在两个实例上都跑过；
  · 需要记录 HTTP 5xx / 业务失败 / 在途 reset 三类错误，滚动重启的验收判据是
    "三类全为 0"。

失败分类：
  · connect_error_after_stop：连接阶段失败 → 视为实例已下线，切另一实例重试，
    计入 failovers，不计错误；
  · midflight_reset：连接已建立、请求已发出、没拿到响应 → **即使切另一台重试成功，
    也计入错误**（说明实例在途请求被 reset，优雅停机没兜住）；
  · http_5xx：收到 5xx；
  · biz_error：HTTP 2xx 但业务码非 0（除非 stop 文件已出现后的收尾请求）。

输出 JSON 到 stdout（--output 同时落文件）。
"""
import argparse
import http.client
import json
import os
import time
import urllib.parse


class ConnectFailed(Exception):
    """在 connect() 阶段失败：请求还没发出去，视为实例已下线，可安全切另一台。"""


class MidflightFailed(Exception):
    """连接已建立后 request/getresponse 失败：请求可能已到服务端，属于在途失败。"""


def parse_endpoint(raw):
    parsed = urllib.parse.urlparse(raw)
    return parsed.hostname or "127.0.0.1", parsed.port or 80, raw


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoints", required=True,
                        help="逗号分隔，如 http://127.0.0.1:18091,http://127.0.0.1:18092")
    parser.add_argument("--template-id", type=int, required=True)
    parser.add_argument("--stop-file", required=True)
    parser.add_argument("--user-base", type=int, default=980_000_000_000)
    parser.add_argument("--request-prefix", default="dual-smoke")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--max-seconds", type=float, default=300.0)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    endpoints = [parse_endpoint(e.strip()) for e in args.endpoints.split(",") if e.strip()]
    if len(endpoints) < 2:
        raise SystemExit("至少需要两个实例地址")

    counters = {
        "requests": 0,
        "success": 0,
        "failovers": 0,
        "midflight_resets": 0,
        "http_5xx": 0,
        "biz_errors": 0,
        "connect_errors_both_down": 0,
        "perEndpointSuccess": {hostport: 0 for _, _, hostport in endpoints},
    }
    biz_codes = {}
    http_codes = {}
    latencies = []
    started = time.perf_counter()
    next_index = 0
    seq = 0
    stop_seen = False

    def send_once(host, port, payload, headers):
        """连上并发出请求；返回 (http_status, body)。

        连接阶段失败与"请求已发出的失败"分别抛 ConnectFailed / MidflightFailed——
        http.client 的 RemoteDisconnected 同时是 OSError 和 HTTPException，按异常类型
        分不清阶段，必须在代码结构上把 connect() 和 request() 分开。
        """
        conn = http.client.HTTPConnection(host, port, timeout=args.timeout)
        try:
            try:
                conn.connect()
            except (OSError, http.client.HTTPException) as e:
                raise ConnectFailed from e
            try:
                conn.request("POST", "/api/coupons/receive", body=payload, headers=headers)
                resp = conn.getresponse()
                return resp.status, resp.read()
            except (OSError, http.client.HTTPException) as e:
                raise MidflightFailed from e
        finally:
            try:
                conn.close()
            except Exception:
                pass

    while True:
        if os.path.exists(args.stop_file):
            stop_seen = True
            break
        if time.perf_counter() - started > args.max_seconds:
            break

        seq += 1
        user_id = args.user_base + seq
        request_id = f"{args.request_prefix}-{seq:08d}"
        payload = json.dumps({
            "requestId": request_id,
            "userId": user_id,
            "templateId": args.template_id,
        }).encode()
        headers = {"Content-Type": "application/json",
                   "Content-Length": str(len(payload))}

        order = [next_index, 1 - next_index]
        next_index = 1 - next_index     # 下次先打另一台，保证两边都有流量
        counters["requests"] += 1
        result = None
        midflight = False
        connect_failures = 0

        for attempt, idx in enumerate(order):
            host, port, label = endpoints[idx]
            t0 = time.perf_counter()
            try:
                status, raw = send_once(host, port, payload, headers)
                elapsed_ms = (time.perf_counter() - t0) * 1000.0
                latencies.append(elapsed_ms)
                try:
                    body = json.loads(raw or b"{}")
                    biz = body.get("code")
                except Exception:
                    biz = "BAD_JSON"
                result = (status, biz, label)
                if biz != 0 and not stop_seen:
                    counters["biz_errors"] += 1
                break
            except ConnectFailed:
                if attempt == 0:
                    connect_failures += 1
                    continue        # 换另一台
                counters["connect_errors_both_down"] += 1
                break
            except MidflightFailed:
                # 已建立连接、请求已发出但没拿到完整响应：这是一次在途请求失败，
                # 不是"实例已下线"。即使切另一台重试成功，错误也已经记账。
                midflight = True
                if attempt == 0:
                    continue
                counters["connect_errors_both_down"] += 1
                break

        if midflight:
            counters["midflight_resets"] += 1
        if connect_failures and result is not None:
            counters["failovers"] += 1

        if result is None:
            continue

        status, biz, label = result
        if status >= 500:
            counters["http_5xx"] += 1
        if status == 200 and biz == 0:
            counters["success"] += 1
            counters["perEndpointSuccess"][label] += 1
        http_codes[str(status)] = http_codes.get(str(status), 0) + 1
        biz_codes[str(biz)] = biz_codes.get(str(biz), 0) + 1

    elapsed = time.perf_counter() - started
    latencies.sort()

    def pct(p):
        if not latencies:
            return None
        idx = max(0, min(len(latencies) - 1,
                         int((p / 100.0) * len(latencies) + 0.999999) - 1))
        return round(latencies[idx], 1)

    report = {
        "endpoints": [label for _, _, label in endpoints],
        "requests": counters["requests"],
        "success": counters["success"],
        "wallSeconds": round(elapsed, 3),
        "throughputRps": round(counters["requests"] / elapsed, 1) if elapsed > 0 else None,
        "failovers": counters["failovers"],
        "midflightResets": counters["midflight_resets"],
        "http5xx": counters["http_5xx"],
        "bizErrors": counters["biz_errors"],
        "bothEndpointsDown": counters["connect_errors_both_down"],
        "perEndpointSuccess": counters["perEndpointSuccess"],
        "http": http_codes,
        "bizCode": biz_codes,
        "latencyMs": {
            "p50": pct(50),
            "p95": pct(95),
            "p99": pct(99),
            "max": round(latencies[-1], 1) if latencies else None,
        },
        "stopFileSeen": stop_seen,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
