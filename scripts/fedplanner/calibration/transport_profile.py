#!/usr/bin/env python3
"""Fit a fixed, no-intercept federated transport profile from archived samples."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
from typing import NamedTuple, Sequence


MIB = 1024 ** 2
MBIT_TO_MIB_PER_SECOND = 1_000_000 / (8 * MIB)
TRAIN_PAYLOAD_MIB = (4, 16)
HELDOUT_PAYLOAD_MIB = (64,)
DIAGNOSTIC_PAYLOAD_MIB = (0.0625,)
BASELINE_CODEC_MIB_PER_SEC = {"C2W": 210.0, "W2C": 14.7}
OPERATIONS = {"C2W": ("broadcast-put", "slice-put"), "W2C": ("get",)}


class Observation(NamedTuple):
	total_codec_mib: float
	observed_seconds: float
	fixed_seconds: float
	payload_mib: float


def _positive(value: float, name: str) -> float:
	value = float(value)
	if not math.isfinite(value) or value <= 0:
		raise ValueError(f"{name} must be finite and positive: {value}")
	return value


def stage_seconds(*, latency_seconds: float, largest_wire_mib: float,
		total_wire_mib: float, worker_leg_mib_per_sec: float,
		coordinator_mib_per_sec: float, total_codec_mib: float,
		codec_mib_per_sec: float) -> float:
	"""Price one directional stage; no fixed/intercept term is present."""
	latency_seconds = float(latency_seconds)
	if not math.isfinite(latency_seconds) or latency_seconds < 0:
		raise ValueError("latency_seconds must be finite and nonnegative")
	for value, name in ((largest_wire_mib, "largest_wire_mib"),
			(total_wire_mib, "total_wire_mib"), (total_codec_mib, "total_codec_mib")):
		if not math.isfinite(value) or value < 0:
			raise ValueError(f"{name} must be finite and nonnegative")
	worker_rate = _positive(worker_leg_mib_per_sec, "worker_leg_mib_per_sec")
	coordinator_rate = _positive(coordinator_mib_per_sec, "coordinator_mib_per_sec")
	codec_rate = _positive(codec_mib_per_sec, "codec_mib_per_sec")
	wire = max(largest_wire_mib / worker_rate, total_wire_mib / coordinator_rate)
	return latency_seconds + wire + total_codec_mib / codec_rate


def fit_codec_through_origin(observations: Sequence[Observation]) -> dict:
	if not observations:
		raise ValueError("at least one observation is required")
	numerator = 0.0
	denominator = 0.0
	negative = 0
	minimum_residual = math.inf
	for observation in observations:
		payload = float(observation.total_codec_mib)
		if not math.isfinite(payload) or payload <= 0:
			raise ValueError("codec payload must be finite and positive")
		residual = observation.observed_seconds - observation.fixed_seconds
		if not math.isfinite(residual):
			raise ValueError("residual must be finite")
		negative += residual < 0
		minimum_residual = min(minimum_residual, residual)
		numerator += payload * residual
		denominator += payload * payload
	seconds_per_mib = max(0.0, numerator / denominator)
	return {
		"model": "residual_seconds = total_codec_MiB * seconds_per_MiB",
		"seconds_per_mib": seconds_per_mib,
		"codec_mib_per_sec": 1.0 / seconds_per_mib if seconds_per_mib > 0 else None,
		"negative_residual_count": negative,
		"minimum_residual_seconds": minimum_residual,
		"samples": len(observations),
	}


def _largest_logical_mib(row: dict) -> float:
	workers = int(row["workers"])
	base_rows = round(float(row["payload_mib"]) * 1024)
	distribution = row["distribution"]
	if distribution == "fixed-per-worker":
		largest_rows = base_rows
	elif distribution == "fixed-total":
		largest_rows = (base_rows + workers - 1) // workers
	elif distribution == "skew":
		first = max(1, math.floor(base_rows * 0.70))
		second = max(1, math.floor(base_rows * 0.15))
		largest_rows = max(first, second, base_rows - first - second)
	else:
		raise ValueError(f"unknown distribution: {distribution}")
	return largest_rows / 1024.0


def _quantities(row: dict) -> tuple[float, float, float]:
	total_codec = int(row["logical_bytes"]) / MIB
	if total_codec <= 0 or int(row["application_frame_bytes"]) <= 0:
		raise ValueError("transport payload quantities must be positive")
	# Primary predictions use the same logical/statistical byte quantities available
	# to the production estimator. Observed encoded/NIC bytes remain diagnostics;
	# using future measured frames here would give calibration an oracle advantage.
	total_wire = total_codec
	largest_wire = _largest_logical_mib(row)
	return largest_wire, total_wire, total_codec


def _fixed_seconds(row: dict, latency_seconds: float, worker_rate: float,
		coordinator_rate: float) -> float:
	largest_wire, total_wire, _ = _quantities(row)
	# Timed GET/PUT waits for a response: one payload stage plus the reverse
	# zero-payload response stage, each with one directional latency.
	return 2 * latency_seconds + max(largest_wire / worker_rate, total_wire / coordinator_rate)


def _relative_errors(rows: Sequence[dict], *, seconds_per_mib: float,
		latency_seconds: float, worker_rate: float, coordinator_rate: float) -> list[float]:
	errors = []
	for row in rows:
		_, _, codec = _quantities(row)
		observed = int(row["elapsed_ns"]) / 1e9
		predicted = _fixed_seconds(row, latency_seconds, worker_rate, coordinator_rate) \
			+ codec * seconds_per_mib
		errors.append(abs(predicted - observed) / observed)
	return errors


def fit_direction(rows: Sequence[dict], *, direction: str, latency_seconds: float,
		worker_leg_mib_per_sec: float, coordinator_mib_per_sec: float,
		baseline_codec_mib_per_sec: float) -> dict:
	if direction not in OPERATIONS:
		raise ValueError(f"unsupported direction: {direction}")
	operations = OPERATIONS[direction]
	selected = [row for row in rows if row["operation"] in operations]
	training = [row for row in selected if row["payload_mib"] in TRAIN_PAYLOAD_MIB]
	heldout = [row for row in selected if row["payload_mib"] in HELDOUT_PAYLOAD_MIB]
	diagnostic = [row for row in selected if row["payload_mib"] in DIAGNOSTIC_PAYLOAD_MIB]
	if not training or not heldout:
		raise ValueError(f"{direction} requires both training and held-out samples")
	observations = []
	for row in training:
		_, _, codec = _quantities(row)
		observations.append(Observation(codec, int(row["elapsed_ns"]) / 1e9,
			_fixed_seconds(row, latency_seconds, worker_leg_mib_per_sec,
				coordinator_mib_per_sec), float(row["payload_mib"])))
	fit = fit_codec_through_origin(observations)
	candidate_rate = fit["codec_mib_per_sec"]
	baseline_rate = _positive(baseline_codec_mib_per_sec, "baseline_codec_mib_per_sec")
	baseline_errors = _relative_errors(heldout, seconds_per_mib=1 / baseline_rate,
		latency_seconds=latency_seconds, worker_rate=worker_leg_mib_per_sec,
		coordinator_rate=coordinator_mib_per_sec)
	candidate_errors = (_relative_errors(heldout, seconds_per_mib=fit["seconds_per_mib"],
		latency_seconds=latency_seconds, worker_rate=worker_leg_mib_per_sec,
		coordinator_rate=coordinator_mib_per_sec) if candidate_rate else None)
	candidate_median = statistics.median(candidate_errors) if candidate_errors is not None else None
	baseline_median = statistics.median(baseline_errors)
	adopted = (fit["negative_residual_count"] == 0 and candidate_rate is not None
		and candidate_median is not None and candidate_median < baseline_median)
	return {
		"direction": direction,
		"operations": list(operations),
		"one_way_latency_seconds": latency_seconds,
		"worker_leg_mib_per_sec": worker_leg_mib_per_sec,
		"coordinator_mib_per_sec": coordinator_mib_per_sec,
		"fit_model": fit["model"],
		"fixed_intercept_seconds": 0.0,
		"candidate_codec_mib_per_sec": candidate_rate,
		"baseline_codec_mib_per_sec": baseline_rate,
		"selected_codec_mib_per_sec": candidate_rate if adopted else baseline_rate,
		"adopted": adopted,
		"adoption_status": "candidate-adopted" if adopted else "baseline-retained",
		"adoption_gate": "candidate held-out median relative error < same-wire baseline; no negative training residual",
		"training_payload_mib": list(TRAIN_PAYLOAD_MIB),
		"heldout_payload_mib": list(HELDOUT_PAYLOAD_MIB),
		"diagnostic_payload_mib": list(DIAGNOSTIC_PAYLOAD_MIB),
		"training_samples": len(training),
		"heldout_samples": len(heldout),
		"diagnostic_samples": len(diagnostic),
		"negative_training_residual_count": fit["negative_residual_count"],
		"minimum_training_residual_seconds": fit["minimum_residual_seconds"],
		"candidate_heldout_median_relative_error": candidate_median,
		"baseline_heldout_median_relative_error": baseline_median,
		"candidate_heldout_relative_errors": candidate_errors,
		"baseline_heldout_relative_errors": baseline_errors,
	}


def _sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as stream:
		for chunk in iter(lambda: stream.read(1024 * 1024), b""):
			digest.update(chunk)
	return digest.hexdigest()


def _link_speed(preflight: dict, host: str) -> tuple[str, float]:
	raw = preflight[host]["host_network"]
	default = re.search(r'"dst":"default"[^\n]*?"dev":"([^"]+)"', raw)
	if not default:
		raise ValueError(f"no default route interface in preflight for {host}")
	interface = default.group(1)
	speeds = dict(re.findall(r"/sys/class/net/([^/]+)/speed (-?\d+)", raw))
	if interface not in speeds or float(speeds[interface]) <= 0:
		raise ValueError(f"no positive routed-link speed in preflight for {host}/{interface}")
	return interface, float(speeds[interface])


def _validate_rows(rows: Sequence[dict], expected_workers: int) -> None:
	for row in rows:
		if row.get("kind") != "transport-calibration" or row.get("correctness") is not True:
			raise ValueError("invalid transport sample identity/correctness")
		if row.get("workers") != expected_workers:
			raise ValueError("sample worker count differs from campaign")
		_quantities(row)


def cost_environment(profile: dict) -> dict[str, str]:
	directions = profile["directions"]
	result = {}
	for direction in ("C2W", "W2C"):
		item = directions[direction]
		result[f"SYSDS_FED_COST_NET_LATENCY_{direction}"] = f'{item["one_way_latency_seconds"]:.6f}'
		result[f"SYSDS_FED_COST_NET_BW_{direction}"] = f'{item["worker_leg_mib_per_sec"]:.6f}'
		result[f"SYSDS_FED_COST_NET_BW_COORD_{direction}"] = f'{item["coordinator_mib_per_sec"]:.6f}'
		result[f"SYSDS_FED_COST_NET_SERDES_BW_{direction}"] = f'{item["selected_codec_mib_per_sec"]:.6f}'
	return result


def build_profile(source: Path, *, expected_rows: int | None = 340) -> dict:
	source = source.resolve(strict=True)
	manifest_path = source / "manifest.json"
	manifest = json.loads(manifest_path.read_text())
	completed = []
	all_rows = []
	for campaign in manifest["campaigns"]:
		profile = campaign["profile"]
		workers = int(campaign["workers"])
		cell = source / f"{profile}-w{workers}"
		if not (cell / "samples.json").is_file():
			continue
		rows = json.loads((cell / "samples.json").read_text())
		_validate_rows(rows, workers)
		preflight_path = cell / "preflight.json"
		preflight = json.loads(preflight_path.read_text())
		spec = campaign["spec"]
		hosts = [spec["coordinator"]["host"]] + [worker["host"] for worker in spec["workers"]]
		links = []
		for host in hosts:
			interface, speed = _link_speed(preflight, host)
			links.append({"host": host, "interface": interface, "speed_mbit_per_sec": speed})
		completed.append({
			"profile": profile, "workers": workers, "rows": len(rows),
			"network": spec["network"], "links": links,
			"samples_sha256": _sha256(cell / "samples.json"),
			"preflight_sha256": _sha256(preflight_path),
		})
		all_rows.extend({**row, "profile": profile} for row in rows)
	if expected_rows is not None and len(all_rows) != expected_rows:
		raise ValueError(f"expected immutable completed R56 corpus of {expected_rows} rows, found {len(all_rows)}")
	profiles = {}
	for profile_name in sorted({item["profile"] for item in completed}):
		cells = [item for item in completed if item["profile"] == profile_name]
		networks = {json.dumps(item["network"], sort_keys=True) for item in cells}
		if len(networks) != 1:
			raise ValueError(f"inconsistent network profile: {profile_name}")
		network = cells[0]["network"]
		coordinator_speeds = {item["links"][0]["speed_mbit_per_sec"] for item in cells}
		worker_speeds = {link["speed_mbit_per_sec"] for item in cells for link in item["links"][1:]}
		if len(coordinator_speeds) != 1 or len(worker_speeds) != 1:
			raise ValueError(f"heterogeneous archived link speeds unsupported: {profile_name}")
		coordinator_nic = next(iter(coordinator_speeds))
		worker_nic = next(iter(worker_speeds))
		latency = float(network["rtt_ms"]) / 2000.0
		capacities = {
			"C2W": (min(worker_nic, float(network["c2w_mbit"])),
				min(coordinator_nic, float(network["c2w_mbit"]))),
			"W2C": (min(worker_nic, float(network["w2c_mbit"])), coordinator_nic),
		}
		profile_rows = [row for row in all_rows if row["profile"] == profile_name]
		frame_ratios = [int(row["application_frame_bytes"]) / int(row["logical_bytes"])
			for row in profile_rows]
		directions = {}
		for direction in ("C2W", "W2C"):
			worker_mbit, coordinator_mbit = capacities[direction]
			directions[direction] = fit_direction(
				profile_rows, direction=direction, latency_seconds=latency,
				worker_leg_mib_per_sec=worker_mbit * MBIT_TO_MIB_PER_SECOND,
				coordinator_mib_per_sec=coordinator_mbit * MBIT_TO_MIB_PER_SECOND,
				baseline_codec_mib_per_sec=BASELINE_CODEC_MIB_PER_SEC[direction])
		profiles[profile_name] = {
			"coverage_workers": sorted(item["workers"] for item in cells),
			"source_rows": sum(item["rows"] for item in cells),
			"observed_frame_to_logical_ratio_diagnostic": {
				"minimum": min(frame_ratios), "median": statistics.median(frame_ratios),
				"maximum": max(frame_ratios),
			},
			"directions": directions,
		}
		profiles[profile_name]["cost_environment"] = cost_environment(profiles[profile_name])
	return {
		"schema": "cofee-federated-transport-cost-profile/v2",
		"stage_model": "one_way_latency + max(largest_wire_MiB/worker_leg_MiBps, total_wire_MiB/coordinator_MiBps) + total_codec_MiB/codec_MiBps",
		"fixed_intercept_seconds": 0.0,
		"units": {"latency": "seconds", "volume": "MiB", "throughput": "MiB/s", "source_link_speed": "Mbit/s"},
		"coefficient_scope": "effective SystemDS RPC/serialization/deserialization payload throughput; not isolated hardware codec",
		"source": {
			"manifest_sha256": _sha256(manifest_path),
			"jar_sha256": manifest["jar_sha256"],
			"image": manifest["image"],
			"image_content_sha256": manifest["image_content_sha256"],
			"topology_sha256": manifest["topology_sha256"],
			"completed_cells": completed,
			"total_rows": len(all_rows),
		},
		"profiles": profiles,
		"limitations": [
			"Only LAN W1/W3 and WAN-Light W1 completed samples are calibrated.",
			"Routed-link sysfs speed is nominal physical capacity, not available-throughput measurement.",
			"Primary fit uses production-available logical/statistical wire quantities; observed encoded-frame bytes are validation diagnostics only.",
			"Small 0.0625 MiB observations are diagnostics and do not create a fixed intercept.",
		],
	}


def parser() -> argparse.ArgumentParser:
	result = argparse.ArgumentParser(description=__doc__)
	result.add_argument("--source", required=True, type=Path, help="immutable R56 calibration root")
	result.add_argument("--output", required=True, type=Path, help="new profile JSON; must not exist")
	return result


def main(argv: Sequence[str] | None = None) -> int:
	args = parser().parse_args(argv)
	output = args.output.resolve()
	if output.exists():
		raise FileExistsError(f"preserve existing profile: {output}")
	profile = build_profile(args.source)
	output.parent.mkdir(parents=True, exist_ok=True)
	output.write_text(json.dumps(profile, indent=2, sort_keys=True) + "\n")
	print(json.dumps({"output": str(output), "profiles": sorted(profile["profiles"]),
		"source_rows": profile["source"]["total_rows"]}, sort_keys=True))
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
