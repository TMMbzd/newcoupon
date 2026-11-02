#!/usr/bin/env python3
"""异步领券受理压测客户端（ADR-0019）。

只压 HTTP 受理面：每个请求一个唯一用户/requestId，全部连接**预先建好**后在栅栏后同时发出。
预连接很重要：2000 个线程各自在栅栏后新建 TCP，会把 SYN/accept 队列打爆，
测出来的是客户端连接风暴，不是服务端受理能力（2026-10-23 首轮实测踩到）。
worker 的落库吞吐由编排脚本按任务表状态单独统计，避免把“受理快”误当成“发券快”。

输出：HTTP 状态、业务码分布、wall time、吞吐、p50/p95/p99/max。
不依赖第三方包，wsl 自带 Python 即可运行。
"""
import argparse
import http.client
import json
import statistics
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor


def pct(values, p):
    if not values:
        return None
    ordered = sorted(values)
    # nearest-rank，避免插值口径争议
    idx = max(0, min(len(ordered) - 1, int((p / 100.0) * len(ordered) + 0.999999) - 1))
    return ordered[idx]


def main():
    # 数千个线程默认 8MB 栈只是虚拟地址，但 WSL 上 6000 线程仍会 OSError；
    # 512KB 足够本脚本（只做 HTTP + json），让 6k 并发能稳定建立预连接。
    threading.stack_size(512 * 1024)

    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--template-id", type=int, required=True)
    parser.add_argument("--total", type=int, required=True)
    parser.add_argument("--concurrency", type=int, default=None,
                        help="默认等于 total（全并发）；小于 total 时按线程池排队")
    parser.add_argument("--preconnect-concurrency", type=int, default=50,
                        help="预建 TCP 的并发上限：避免 SYN 风暴污染压测结果")
    parser.add_argument("--user-base", type=int, default=1_000_000_000)
    parser.add_argument("--request-prefix", default="load")
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--output", default=None)
    args = parser.parse_args()

    concurrency = args.concurrency or args.total
    if concurrency < 1 or concurrency > args.total:
        raise SystemExit("concurrency 必须在 [1, total]")
    parsed = urllib.parse.urlparse(args.base_url)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    path = (parsed.path.rstrip("/") if parsed.path else "") + "/api/coupons/receive-async"
    print(f"[load] total={args.total} concurrency={concurrency} template={args.template_id} "
          f"target={args.base_url}")

    # 主线程也进栅栏：最后一个参与者释放时记录共同起点，避免把连接建立时间算进 wall。
    barrier = threading.Barrier(concurrency + 1)
    connect_gate = threading.Semaphore(max(1, args.preconnect_concurrency))
    results = []
    lock = threading.Lock()

    def run(i):
        user_id = args.user_base + i
        request_id = f"{args.request_prefix}-{args.template_id}-{i:08d}"
        conn = None
        conn_error = None
        try:
            with connect_gate:
                conn = http.client.HTTPConnection(host, port, timeout=args.timeout)
                conn.connect()
        except Exception as e:
            conn_error = type(e).__name__
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    pass
                conn = None

        try:
            barrier.wait(timeout=240)
        except threading.BrokenBarrierError:
            with lock:
                results.append({"http": None, "biz": None, "status": None, "ms": 0.0,
                                "error": "BarrierBroken"})
            return

        if conn is None:
            with lock:
                results.append({"http": None, "biz": None, "status": None, "ms": 0.0,
                                "error": "Preconnect:" + str(conn_error)})
            return

        payload = json.dumps({
            "requestId": request_id,
            "userId": user_id,
            "templateId": args.template_id,
        }).encode()
        started = time.perf_counter()
        try:
            conn.request("POST", path, body=payload,
                         headers={"Content-Type": "application/json",
                                  "Content-Length": str(len(payload))})
            resp = conn.getresponse()
            raw = resp.read()
            elapsed = time.perf_counter() - started
            try:
                body = json.loads(raw or b"{}")
                biz = body.get("code")
                task_status = (body.get("data", {}).get("status")
                               if isinstance(body.get("data"), dict) else None)
            except Exception:
                biz, task_status = "BAD_JSON", None
            with lock:
                results.append({"http": resp.status, "biz": biz, "status": task_status,
                                "ms": elapsed * 1000.0, "error": None})
        except Exception as e:
            elapsed = time.perf_counter() - started
            with lock:
                results.append({"http": None, "biz": None, "status": None,
                                "ms": elapsed * 1000.0, "error": type(e).__name__})
        finally:
            try:
                conn.close()
            except Exception:
                pass

    pool = ThreadPoolExecutor(max_workers=concurrency)
    for i in range(args.total):
        pool.submit(run, i)

    try:
        barrier.wait(timeout=300)
        started = time.perf_counter()
    except threading.BrokenBarrierError:
        print("[load] WARN barrier broken before release; results may be partial")
        started = time.perf_counter()

    pool.shutdown(wait=True)
    finished = time.perf_counter()
    wall = finished - started

    http_counts, biz_counts, err_counts = {}, {}, {}
    for r in results:
        key = str(r["http"]) if r["http"] is not None else "NO_HTTP"
        http_counts[key] = http_counts.get(key, 0) + 1
        if r["error"]:
            err_counts[r["error"]] = err_counts.get(r["error"], 0) + 1
        bkey = "null" if r["biz"] is None else str(r["biz"])
        biz_counts[bkey] = biz_counts.get(bkey, 0) + 1

    latencies = [r["ms"] for r in results]
    report = {
        "templateId": args.template_id,
        "total": args.total,
        "concurrency": concurrency,
        "completed": len(results),
        "wallSeconds": round(wall, 3),
        "throughputRps": round(len(results) / wall, 1) if wall > 0 else None,
        "http": http_counts,
        "bizCode": biz_counts,
        "errors": err_counts,
        "latencyMs": {
            "min": round(min(latencies), 1) if latencies else None,
            "p50": round(pct(latencies, 50), 1) if latencies else None,
            "p95": round(pct(latencies, 95), 1) if latencies else None,
            "p99": round(pct(latencies, 99), 1) if latencies else None,
            "max": round(max(latencies), 1) if latencies else None,
            "mean": round(statistics.fmean(latencies), 1) if latencies else None,
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
    # 只有“全部 202 且业务码 0”才算受理面全成功；其它情况由调用方决定是否继续查错误分布
    return 0 if (len(results) == args.total and http_counts.get("202") == args.total
                 and biz_counts.get("0") == args.total) else 2


if __name__ == "__main__":
    raise SystemExit(main())
