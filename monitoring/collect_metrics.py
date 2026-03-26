#!/usr/bin/env python3
"""
Metrics Collection Script for consumer-v3 load tests.

Usage:
  # One-shot: start before load test, Ctrl+C when done to print results
  python3 collect_metrics.py --host <ec2-ip> --port 8081

  # Poll every 5 min during endurance test (Test 3)
  python3 collect_metrics.py --host <ec2-ip> --port 8081 --interval 300

  # Save raw JSON snapshots to disk
  python3 collect_metrics.py --host <ec2-ip> --port 8081 --interval 300 --save
"""

import argparse
import json
import signal
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def fetch(url, timeout=5):
    """Return parsed JSON from url, or None on error."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        print(f"  [WARN] {url} → {e}")
        return None


def collect_all(base_url):
    """Fetch all three endpoints and return a merged snapshot dict."""
    metrics    = fetch(f"{base_url}/app/metrics")
    analytics  = fetch(f"{base_url}/app/metrics/analytics")
    circuit_br = fetch(f"{base_url}/app/metrics/circuit-breaker")
    return {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "metrics":   metrics,
        "analytics": analytics,
        "circuit_breaker": circuit_br,
    }


# ---------------------------------------------------------------------------
# Formatting helpers
# ---------------------------------------------------------------------------

def _v(d, *keys, default="N/A"):
    """Safe nested dict lookup."""
    cur = d
    for k in keys:
        if not isinstance(cur, dict):
            return default
        cur = cur.get(k, None)
        if cur is None:
            return default
    return cur


def fmt_num(v):
    if isinstance(v, (int, float)):
        return f"{v:,.0f}"
    return str(v)


def print_snapshot(snap, duration_s=None, prev_processed=None, prev_ts=None):
    m  = snap.get("metrics")   or {}
    an = snap.get("analytics") or {}
    cb = snap.get("circuit_breaker") or {}
    report = an.get("analytics") or {}

    processed = m.get("messagesProcessed", 0) or 0

    # Throughput
    if prev_processed is not None and prev_ts is not None:
        elapsed = time.time() - prev_ts
        throughput = (processed - prev_processed) / elapsed if elapsed > 0 else 0
    elif duration_s and duration_s > 0:
        throughput = processed / duration_s
    else:
        throughput = None

    print(f"\n{'='*60}")
    print(f"  Metrics Snapshot  [{snap['timestamp']}]")
    if duration_s:
        mins, secs = divmod(int(duration_s), 60)
        print(f"  Test Duration: {mins} min {secs} sec")
    print(f"{'='*60}")

    print("\n[Consumer Counters]")
    fields = [
        ("messagesProcessed",     "messagesProcessed"),
        ("messagesFailed",        "messagesFailed"),
        ("messagesSkippedDup",    "messagesSkippedDuplicate"),
        ("dbWritesSuccess",       "dbWritesSuccess"),
        ("dbWritesFailed",        "dbWritesFailed"),
        ("writeBehindDropped",    "writeBehindDropped"),
        ("dlqTotalDropped",       "dlqTotalDropped"),
        ("dlqSize",               "dlqSize"),
        ("writeQueueSize",        "writeQueueSize"),
        ("statsQueueSize",        "statsQueueSize"),
    ]
    for label, key in fields:
        val = m.get(key, "N/A")
        print(f"  {label:<26}: {fmt_num(val)}")

    if throughput is not None:
        print(f"  {'Avg Write Throughput':<26}: {throughput:,.0f} msg/s")

    print("\n[Circuit Breaker]")
    for name in ("messages", "stats"):
        cb_info = cb.get(name) or {}
        state   = cb_info.get("state", "N/A")
        rate    = cb_info.get("failureRate", "N/A")
        failed  = cb_info.get("failedCalls", "N/A")
        rate_str = f"{rate:.2f}%" if isinstance(rate, float) else str(rate)
        print(f"  {name:<10}  state={state:<10}  failureRate={rate_str}  failedCalls={fmt_num(failed)}")

    print("\n[Analytics]")
    analytics_fields = [
        ("totalMessages",     "totalMessages"),
        ("uniqueUsers",       "uniqueUsers"),
        ("avgMessagesPerRoom","avgMessagesPerRoom"),
        ("avgMessagesPerUser","avgMessagesPerUser"),
        ("messagesPerMinute", "messagesPerMinute"),
    ]
    for label, key in analytics_fields:
        val = report.get(key, "N/A")
        if isinstance(val, float):
            print(f"  {label:<26}: {val:,.2f}")
        else:
            print(f"  {label:<26}: {fmt_num(val)}")

    top_rooms = report.get("topRooms") or []
    if top_rooms:
        print("\n[Top Rooms]")
        for r in top_rooms:
            print(f"  {r.get('roomId','?'):<12}  msgs={fmt_num(r.get('messageCount',0))}  "
                  f"users={r.get('uniqueUserCount',0)}  avgLen={r.get('avgMessageLength',0):.1f}")

    top_users = report.get("topUsers") or []
    if top_users:
        print("\n[Top Users]")
        for u in top_users[:5]:
            print(f"  {u.get('userId','?')} ({u.get('username','?')})  msgs={fmt_num(u.get('messageCount',0))}")

def print_poll_line(snap, elapsed_s, prev_processed, prev_ts):
    m  = snap.get("metrics")  or {}
    cb = snap.get("circuit_breaker") or {}

    processed = m.get("messagesProcessed", 0) or 0
    db_success = m.get("dbWritesSuccess", 0) or 0
    wq  = m.get("writeQueueSize", 0)
    sq  = m.get("statsQueueSize", 0)
    msg_state   = _v(cb, "messages", "state", default="?")
    stats_state = _v(cb, "stats",    "state", default="?")

    if prev_processed is not None and prev_ts is not None:
        dt = time.time() - prev_ts
        throughput = (processed - prev_processed) / dt if dt > 0 else 0
        tput_str = f"{throughput:>6,.0f} msg/s"
    else:
        tput_str = "     ? msg/s"

    mins = int(elapsed_s) // 60
    ts_str = snap["timestamp"].split("T")[1]
    print(f"[{ts_str}] t={mins:2d}min  processed={fmt_num(processed):>10}  "
          f"dbSuccess={fmt_num(db_success):>10}  {tput_str}  "
          f"queue={wq:>5}/{sq:>5}  CB={msg_state}/{stats_state}")


def print_queue_table(history):
    """Print stability table from poll history for endurance test."""
    print("\n--- Queue Depth Over Time (paste into load-test-results.md) ---")
    print(f"| {'Time (min)':<12} | {'writeQueueSize':>14} | {'statsQueueSize':>14} |")
    print(f"| {'-'*12} | {'-'*14} | {'-'*14} |")
    for entry in history:
        m = entry["snap"].get("metrics") or {}
        mins = int(entry["elapsed_s"]) // 60
        label = "End" if entry is history[-1] else str(mins)
        wq = m.get("writeQueueSize", "N/A")
        sq = m.get("statsQueueSize", "N/A")
        print(f"| {label:<12} | {fmt_num(wq):>14} | {fmt_num(sq):>14} |")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Collect consumer-v3 metrics")
    parser.add_argument("--host",     default="localhost", help="EC2 host or IP")
    parser.add_argument("--port",     default=8081,  type=int)
    parser.add_argument("--interval", default=0,     type=int,
                        help="Poll interval in seconds (0 = one-shot)")
    parser.add_argument("--save",     action="store_true",
                        help="Save raw JSON snapshots to metrics_<timestamp>.json")
    args = parser.parse_args()

    base_url = f"http://{args.host}:{args.port}"
    t0 = time.time()
    t0_dt = datetime.now().strftime("%Y%m%d_%H%M%S")

    print(f"Collecting metrics from {base_url}")
    print(f"Started at {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    if args.interval > 0:
        print(f"Poll interval: {args.interval}s  |  Press Ctrl+C to stop and print final tables")
    else:
        print("Mode: one-shot  |  Press Ctrl+C at any time to print final snapshot")
    print()

    history = []          # for endurance table
    prev_processed = None
    prev_ts = None
    snapshots_to_save = []

    def finish(snap):
        duration_s = time.time() - t0
        print_snapshot(snap, duration_s=duration_s,
                       prev_processed=None, prev_ts=None)
        if history:
            print_queue_table(history)
        if args.save and snapshots_to_save:
            fname = f"metrics_{t0_dt}.json"
            with open(fname, "w") as f:
                json.dump(snapshots_to_save, f, indent=2)
            print(f"\nRaw snapshots saved to {fname}")

    def handle_sigint(sig, frame):
        print("\n\n[Ctrl+C — printing final results]")
        snap = collect_all(base_url)
        finish(snap)
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_sigint)

    # One-shot mode
    if args.interval == 0:
        print("Waiting... (run your load test now, press Ctrl+C when done)\n")
        try:
            while True:
                time.sleep(1)
        except SystemExit:
            pass
        return

    # Poll mode
    snap = collect_all(base_url)
    elapsed = time.time() - t0
    history.append({"snap": snap, "elapsed_s": elapsed})
    if args.save:
        snapshots_to_save.append(snap)
    print_poll_line(snap, elapsed, None, None)
    prev_processed = (snap.get("metrics") or {}).get("messagesProcessed", 0)
    prev_ts = time.time()

    while True:
        time.sleep(args.interval)
        snap = collect_all(base_url)
        elapsed = time.time() - t0
        history.append({"snap": snap, "elapsed_s": elapsed})
        if args.save:
            snapshots_to_save.append(snap)
        print_poll_line(snap, elapsed, prev_processed, prev_ts)
        prev_processed = (snap.get("metrics") or {}).get("messagesProcessed", 0)
        prev_ts = time.time()


if __name__ == "__main__":
    main()
