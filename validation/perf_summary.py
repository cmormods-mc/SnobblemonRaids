#!/usr/bin/env python3
"""Digest logs/cobbleraids-perf.csv into the numbers that matter after a busy day.

    python validation/perf_summary.py path/to/logs/cobbleraids-perf.csv [--budget-ms 5]

Prints, per section: runs, total ms, mean ms per run, worst single run, and how much of the
server's 50 ms tick budget it used on average. Then the busiest five-minute windows with the
player count and MSPT beside them, so a spike can be read against what the server was doing.

Reads the long format RaidPerfReporter writes: time,kind,name,count,total_ms,avg_ms,max_ms,value
"""
import argparse
import csv
import sys
from collections import defaultdict

TICK_MS = 50.0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv_path")
    parser.add_argument("--budget-ms", type=float, default=5.0,
                        help="flag a window whose summed section time per tick exceeds this (default 5)")
    args = parser.parse_args()

    sections = defaultdict(lambda: {"count": 0, "total": 0.0, "max": 0.0})
    windows = defaultdict(lambda: {"players": None, "mspt": None, "heap_mb": None, "raids": None, "ms": 0.0})
    try:
        with open(args.csv_path, newline="", encoding="utf-8") as handle:
            rows = list(csv.DictReader(handle))
    except OSError as error:
        print(f"cannot read {args.csv_path}: {error}", file=sys.stderr)
        return 1

    for row in rows:
        stamp = row["time"]
        if row["kind"] == "gauge":
            key = {"players": "players", "mspt": "mspt", "heap_mb": "heap_mb", "active_raids": "raids"}.get(row["name"])
            if key:
                windows[stamp][key] = float(row["value"])
            continue
        count, total, peak = int(row["count"]), float(row["total_ms"]), float(row["max_ms"])
        stat = sections[row["name"]]
        stat["count"] += count
        stat["total"] += total
        stat["max"] = max(stat["max"], peak)
        windows[stamp]["ms"] += total

    if not sections:
        print("no section rows in the file")
        return 1

    print(f"{'section':<28}{'runs':>10}{'total ms':>12}{'mean ms':>10}{'worst ms':>10}")
    for name, stat in sorted(sections.items(), key=lambda item: -item[1]["total"]):
        mean = stat["total"] / stat["count"] if stat["count"] else 0.0
        flag = "  <-- a single run cost a whole tick" if stat["max"] >= TICK_MS else ""
        print(f"{name:<28}{stat['count']:>10}{stat['total']:>12.1f}{mean:>10.3f}{stat['max']:>10.2f}{flag}")

    print("\nbusiest windows (section time per tick = total ms in the window / 6000 ticks)")
    print(f"{'window start (UTC)':<26}{'players':>8}{'raids':>7}{'mspt':>7}{'heap MB':>9}{'ms/tick':>9}")
    ranked = sorted(windows.items(), key=lambda item: -item[1]["ms"])[:5]
    for stamp, window in ranked:
        per_tick = window["ms"] / 6000.0
        def cell(value, spec):
            # A window can lack a gauge (a partial one at shutdown); keep the column aligned.
            width = int(spec.split(".")[0])
            return format(value, spec) if value is not None else "-".rjust(width)
        flag = "  <-- over budget" if per_tick > args.budget_ms else ""
        print(f"{stamp:<26}{cell(window['players'], '8.0f')}{cell(window['raids'], '7.0f')}"
              f"{cell(window['mspt'], '7.1f')}{cell(window['heap_mb'], '9.0f')}{per_tick:>9.3f}{flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
