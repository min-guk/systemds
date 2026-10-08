#!/usr/bin/env python3
"""Run the pinned workload harness and sample its one-container cgroup usage."""

import argparse
import json
import pathlib
import re
import subprocess
import time


def memory_bytes(value: str) -> int:
    match = re.fullmatch(r"([0-9.]+)([KMG]iB)", value.strip())
    if not match:
        raise ValueError(f"unexpected Docker memory value: {value!r}")
    scale = {"KiB": 1024, "MiB": 1024**2, "GiB": 1024**3}[match.group(2)]
    return round(float(match.group(1)) * scale)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=pathlib.Path, required=True)
    parser.add_argument("--evidence-dir", type=pathlib.Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required after --")
    args.evidence_dir.mkdir(parents=True, exist_ok=False)
    (args.evidence_dir / "command.json").write_text(
        json.dumps(command, indent=2) + "\n", encoding="utf-8")
    samples: list[dict[str, object]] = []
    started = time.monotonic()
    with (args.evidence_dir / "run.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, text=True)
        container = None
        while process.poll() is None:
            manifest = args.run_dir / "manifest.json"
            if container is None and manifest.is_file():
                try:
                    container = json.loads(manifest.read_text(encoding="utf-8"))["container"]
                except (OSError, KeyError, json.JSONDecodeError):
                    container = None
            if container:
                observed = subprocess.run(
                    ["docker", "stats", "--no-stream", "--format", "{{json .}}", container],
                    capture_output=True, text=True, check=False)
                if observed.returncode == 0 and observed.stdout.strip():
                    row = json.loads(observed.stdout)
                    usage = row["MemUsage"].split("/", 1)[0].strip()
                    samples.append({
                        "elapsedSeconds": round(time.monotonic() - started, 3),
                        "containerId": row.get("ID"),
                        "containerName": row.get("Name"),
                        "memoryBytes": memory_bytes(usage),
                        "memoryUsage": row.get("MemUsage"),
                        "cpuPercent": row.get("CPUPerc"),
                        "pids": row.get("PIDs"),
                    })
            time.sleep(1)
        returncode = process.wait()
    with (args.evidence_dir / "docker-stats.jsonl").open("w", encoding="utf-8") as stream:
        for sample in samples:
            stream.write(json.dumps(sample, sort_keys=True) + "\n")
    summary = {
        "returncode": returncode,
        "sampleCount": len(samples),
        "peakSampledContainerMemoryBytes": max(
            (int(row["memoryBytes"]) for row in samples), default=None),
        "elapsedSeconds": round(time.monotonic() - started, 3),
        "scope": "whole one-container harness; sampled Docker cgroup memory usage",
    }
    (args.evidence_dir / "monitor-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(summary, sort_keys=True))
    return returncode


if __name__ == "__main__":
    raise SystemExit(main())
