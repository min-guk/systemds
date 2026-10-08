#!/usr/bin/env python3
"""Run the pinned FedPlanner Docker harness and sample its container memory."""

import json
import pathlib
import re
import subprocess
import sys
import time


def memory_bytes(value):
	match = re.fullmatch(r"([0-9.]+)([KMGTP]?i?B)", value.strip())
	if not match:
		return None
	scale = {
		"B": 1,
		"KB": 1000,
		"MB": 1000**2,
		"GB": 1000**3,
		"TB": 1000**4,
		"KiB": 1024,
		"MiB": 1024**2,
		"GiB": 1024**3,
		"TiB": 1024**4,
	}[match.group(2)]
	return int(float(match.group(1)) * scale)


def containers():
	result = subprocess.run(
		["docker", "ps", "--format", "{{.ID}}\t{{.Names}}"],
		check=True, capture_output=True, text=True)
	return dict(line.split("\t", 1) for line in result.stdout.splitlines() if line)


def main():
	if len(sys.argv) < 3 or sys.argv[1] != "--output-dir":
		raise SystemExit("usage: monitor_docker_run.py --output-dir DIR COMMAND...")
	out = pathlib.Path(sys.argv[2]).resolve()
	command = sys.argv[3:]
	if not command:
		raise SystemExit("missing command")
	out.mkdir(parents=True, exist_ok=True)
	before = containers()
	(out / "command.json").write_text(json.dumps(command, indent=2) + "\n")
	started = time.time()
	with (out / "run.log").open("w") as log:
		process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, text=True)
		samples = []
		while process.poll() is None:
			try:
				for container_id, name in containers().items():
					if container_id in before or not name.startswith("systemds-joint-boundary-"):
						continue
					stats = subprocess.run([
						"docker", "stats", "--no-stream", "--format",
						"{{json .}}", container_id], capture_output=True, text=True)
					if stats.returncode or not stats.stdout.strip():
						continue
					row = json.loads(stats.stdout)
					used = row.get("MemUsage", "").split(" / ", 1)[0]
					samples.append({
						"elapsedSeconds": round(time.time() - started, 3),
						"containerId": container_id,
						"containerName": name,
						"memoryUsage": row.get("MemUsage"),
						"memoryBytes": memory_bytes(used),
						"cpuPercent": row.get("CPUPerc"),
						"pids": row.get("PIDs"),
					})
			except (OSError, subprocess.SubprocessError, ValueError, json.JSONDecodeError):
				pass
			time.sleep(0.2)
	return_code = process.wait()
	with (out / "docker-stats.jsonl").open("w") as stream:
		for sample in samples:
			stream.write(json.dumps(sample, sort_keys=True) + "\n")
	peak = max((sample["memoryBytes"] for sample in samples
		if sample["memoryBytes"] is not None), default=None)
	summary = {
		"returnCode": return_code,
		"elapsedSeconds": round(time.time() - started, 3),
		"requestedPostSampleSleepSeconds": 0.2,
		"effectiveMeanSampleIntervalSeconds": (
			round((samples[-1]["elapsedSeconds"] - samples[0]["elapsedSeconds"]) / (len(samples) - 1), 3)
			if len(samples) > 1 else None),
		"sampleCount": len(samples),
		"peakSampledContainerMemoryBytes": peak,
		"scope": "whole one-container harness; sampled Docker cgroup memory usage",
	}
	(out / "monitor-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
	return return_code


if __name__ == "__main__":
	raise SystemExit(main())
