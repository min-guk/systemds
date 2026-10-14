#!/usr/bin/env python3
"""Summarize valid coordinator-JVM /proc samples."""
import csv
import json
import os
import sys
from pathlib import Path


def main():
    source, output = sys.argv[1:3]
    rows = list(csv.DictReader(Path(source).open(), delimiter="\t"))
    if not rows:
        raise SystemExit("no coordinator JVM samples")
    numeric = ("epoch_ms", "host_pid", "utime_ticks", "stime_ticks", "proc_rss_pages",
               "vmrss_kib", "vmhwm_kib")
    for row in rows:
        for key in numeric:
            row[key] = int(row[key])
    first, last = rows[0], rows[-1]
    ticks = os.sysconf("SC_CLK_TCK")
    cpu_ticks = last["utime_ticks"] + last["stime_ticks"] - first["utime_ticks"] - first["stime_ticks"]
    result = {
        "sampleCount": len(rows), "firstEpochMs": first["epoch_ms"], "lastEpochMs": last["epoch_ms"],
        "observedSeconds": (last["epoch_ms"] - first["epoch_ms"]) / 1000.0,
        "hostPid": first["host_pid"], "singlePid": len({row["host_pid"] for row in rows}) == 1,
        "clockTicksPerSecond": ticks, "cpuTicksDelta": cpu_ticks, "cpuSeconds": cpu_ticks / ticks,
        "cpuScope": "whole coordinator JVM during monitored interval, not optimizer-only",
        "maxVmRssKiB": max(row["vmrss_kib"] for row in rows),
        "maxVmHwmKiB": max(row["vmhwm_kib"] for row in rows),
        "lastVmHwmKiB": last["vmhwm_kib"],
    }
    Path(output).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
