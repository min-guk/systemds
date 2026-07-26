# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

"""Fail-closed determinism prerequisites for Docker federated campaigns.

This module deliberately does not launch Docker.  It defines the immutable
identity, lifecycle, ordering, resource, and pilot-variance contracts that a
Docker-only runner must satisfy before starting a measured lifecycle.
"""

from __future__ import annotations

import hashlib
import json
import math
import random
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


class CampaignContractError(ValueError):
	"""Raised when evidence cannot satisfy the frozen campaign contract."""


@dataclass(frozen=True)
class ResourceSnapshot:
	free_bytes: int
	free_inodes: int
	remaining_seconds: float


_PHASE_FILES = (
	"raw_coordinator.log",
	"output.bin",
	"semantic_oracle.json",
	"return_code.txt",
	"scan.json",
	"metric.json",
)


def _sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as handle:
		for block in iter(lambda: handle.read(1024 * 1024), b""):
			digest.update(block)
	return digest.hexdigest()


def _file_record(path: Path) -> dict[str, object]:
	if not path.is_file():
		raise CampaignContractError(f"required frozen file is missing: {path}")
	return {"path": str(path.resolve()), "bytes": path.stat().st_size, "sha256": _sha256(path)}


def _dataset_records(root: Path) -> list[dict[str, object]]:
	if not root.is_dir():
		raise CampaignContractError(f"dataset root is missing: {root}")
	files = sorted(path for path in root.rglob("*") if path.is_file())
	if not files:
		raise CampaignContractError("dataset root contains no files")
	return [
		{"relative_path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": _sha256(path)}
		for path in files
	]


def _require_nonempty_unique(name: str, values: Sequence[str]) -> tuple[str, ...]:
	normalized = tuple(values)
	if not normalized or any(not value for value in normalized):
		raise CampaignContractError(f"{name} must be non-empty")
	if len(set(normalized)) != len(normalized):
		raise CampaignContractError(f"{name} must not contain duplicates")
	return normalized


def build_frozen_manifest(
	*,
	jar: Path,
	image_id: str,
	image_digest: str,
	config: Path,
	dml: Path,
	dataset_root: Path,
	worker_mapping: Sequence[str],
	planner_order: Sequence[str],
	seed: int | None,
	warmup_runs: int,
	measured_warm_runs: int,
) -> dict[str, object]:
	"""Build a canonical manifest whose hash changes on any frozen input drift."""
	if seed is None or isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
		raise CampaignContractError("an explicit non-negative integer seed is required")
	if not image_id or not image_digest:
		raise CampaignContractError("a prebuilt Docker image ID and digest are required")
	workers = _require_nonempty_unique("worker_mapping", worker_mapping)
	planners = _require_nonempty_unique("planner_order", planner_order)
	if warmup_runs != 1:
		raise CampaignContractError("the frozen lifecycle requires exactly one cold warm-up run")
	if measured_warm_runs not in (3, 5, 7):
		raise CampaignContractError("measured_warm_runs must be exactly 3, 5, or 7")

	manifest: dict[str, object] = {
		"schema": "systemds-federated-docker-campaign/v1",
		"execution_surface": "docker-only",
		"artifacts": {
			"jar": _file_record(Path(jar)),
			"config": _file_record(Path(config)),
			"dml": _file_record(Path(dml)),
			"dataset": _dataset_records(Path(dataset_root)),
		},
		"image": {"id": image_id, "digest": image_digest, "prebuilt": True},
		"worker_mapping": list(workers),
		"planner_order": list(planners),
		"seed": seed,
		"lifecycle": {
			"compose": "fresh_per_replicate",
			"warmup_runs": warmup_runs,
			"measured_warm_runs": measured_warm_runs,
			"cold_metric": "docker_e2e",
			"warm_metric": "systemds_total_execution_time",
			"cold_coordinator_jvm": "fresh",
			"warm_coordinator_jvm": "fresh",
			"warm_worker_containers": "reused_from_cold",
			"warm_cache_state": "worker_container_page_cache_warm",
		},
		"required_phase_bundle": [
			"raw_coordinator_log",
			"output_artifact",
			"semantic_oracle",
			"return_code",
			"timeout_error_fallback_scan",
			"metric_record",
			"checksum_manifest",
		],
	}
	canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
	manifest["manifest_hash"] = hashlib.sha256(canonical).hexdigest()
	return manifest


def build_counterbalanced_schedule(planners: Sequence[str], replicates: int, seed: int) -> list[list[str]]:
	"""Return a seeded Williams crossover schedule with balanced carryover."""
	labels = list(_require_nonempty_unique("planners", planners))
	if isinstance(replicates, bool) or not isinstance(replicates, int) or replicates < 1:
		raise CampaignContractError("replicates must be a positive integer")
	if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
		raise CampaignContractError("schedule seed must be a non-negative integer")
	random.Random(seed).shuffle(labels)
	count = len(labels)
	base_indices = [0]
	for index in range(1, count):
		base_indices.append((index + 1) // 2 if index % 2 else count - index // 2)
	rows = [
		[labels[(base_index + offset) % count] for base_index in base_indices]
		for offset in range(count)
	]
	if count % 2:
		rows.extend([list(reversed(row)) for row in rows])
	return [list(rows[index % len(rows)]) for index in range(replicates)]


def check_resource_budget(
	*,
	remaining_lifecycles: int,
	p95_artifact_bytes: int,
	p95_lifecycle_seconds: float,
	absolute_disk_floor_bytes: int,
	snapshot: ResourceSnapshot,
	p95_artifact_inodes: int = 1,
) -> dict[str, float | int]:
	"""Fail closed unless disk, inode, and wall-time P95 reservations are met."""
	values = (remaining_lifecycles, p95_artifact_bytes, absolute_disk_floor_bytes, p95_artifact_inodes)
	if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in values):
		raise CampaignContractError("resource estimates must be non-negative integers")
	if remaining_lifecycles < 1 or p95_artifact_bytes < 1 or p95_artifact_inodes < 1:
		raise CampaignContractError("resource estimates must describe at least one lifecycle artifact")
	if not math.isfinite(p95_lifecycle_seconds) or p95_lifecycle_seconds <= 0:
		raise CampaignContractError("P95 lifecycle time must be finite and positive")

	required_bytes = math.ceil(remaining_lifecycles * p95_artifact_bytes * 1.20) + absolute_disk_floor_bytes
	required_inodes = math.ceil(remaining_lifecycles * p95_artifact_inodes * 1.20)
	required_seconds = remaining_lifecycles * p95_lifecycle_seconds * 1.20
	if snapshot.free_bytes < required_bytes:
		raise CampaignContractError(f"disk budget is unsafe: {snapshot.free_bytes} < {required_bytes} bytes")
	if snapshot.free_inodes < required_inodes:
		raise CampaignContractError(f"inode budget is unsafe: {snapshot.free_inodes} < {required_inodes}")
	if not math.isfinite(snapshot.remaining_seconds) or snapshot.remaining_seconds < required_seconds:
		raise CampaignContractError(
			f"time budget is unsafe: {snapshot.remaining_seconds} < {required_seconds:.3f} seconds"
		)
	return {
		"required_bytes": required_bytes,
		"required_inodes": required_inodes,
		"required_seconds": required_seconds,
	}


def _read_json_object(path: Path, label: str) -> dict[str, object]:
	try:
		value = json.loads(path.read_text(encoding="utf-8"))
	except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
		raise CampaignContractError(f"invalid {label}: {path}") from error
	if not isinstance(value, dict):
		raise CampaignContractError(f"invalid {label}: expected a JSON object")
	return value


def validate_phase_bundle(phase_dir: Path, expected_metric_kind: str) -> dict[str, object]:
	"""Validate one cold or warm bundle without metric or artifact fallback."""
	phase = Path(phase_dir)
	if not phase.is_dir():
		raise CampaignContractError(f"phase bundle is missing: {phase}")
	if expected_metric_kind not in ("docker_e2e", "systemds_total_execution_time"):
		raise CampaignContractError("unknown expected metric kind")

	paths = {name: phase / name for name in _PHASE_FILES}
	checksums_path = phase / "checksums.json"
	for name, path in (*paths.items(), ("checksums.json", checksums_path)):
		if path.is_symlink() or not path.is_file():
			raise CampaignContractError(f"required phase artifact is missing or unsafe: {name}")

	checksums = _read_json_object(checksums_path, "checksum manifest")
	if set(checksums) != set(_PHASE_FILES):
		raise CampaignContractError("checksum manifest must cover exactly the required phase artifacts")
	for name, path in paths.items():
		expected = checksums[name]
		if not isinstance(expected, str) or len(expected) != 64 or _sha256(path) != expected:
			raise CampaignContractError(f"checksum mismatch for phase artifact: {name}")

	try:
		return_code = int(paths["return_code.txt"].read_text(encoding="ascii").strip())
	except (OSError, UnicodeDecodeError, ValueError) as error:
		raise CampaignContractError("invalid phase return code") from error
	if return_code != 0:
		raise CampaignContractError(f"phase return code is non-zero: {return_code}")

	oracle = _read_json_object(paths["semantic_oracle.json"], "semantic oracle")
	if oracle.get("passed") is not True:
		raise CampaignContractError("semantic oracle did not pass")
	scan = _read_json_object(paths["scan.json"], "timeout/error/fallback scan")
	for marker in ("timeout", "error", "fallback"):
		if scan.get(marker) is not False:
			raise CampaignContractError(f"phase scan is missing or reports {marker}")

	metric = _read_json_object(paths["metric.json"], "metric record")
	if metric.get("kind") != expected_metric_kind:
		raise CampaignContractError(
			f"metric kind mismatch: expected {expected_metric_kind}, found {metric.get('kind')!r}"
		)
	seconds = metric.get("seconds")
	if isinstance(seconds, bool) or not isinstance(seconds, (int, float)):
		raise CampaignContractError("metric seconds must be numeric")
	if not math.isfinite(float(seconds)) or float(seconds) <= 0:
		raise CampaignContractError("metric seconds must be finite and positive")
	return {"kind": expected_metric_kind, "seconds": float(seconds), "return_code": return_code}


def summarize_variance_pilot(warm_execution_seconds: Iterable[float]) -> dict[str, float | int]:
	"""Summarize enough valid warm repeats to estimate within-cell variance."""
	values = [float(value) for value in warm_execution_seconds]
	if len(values) < 5:
		raise CampaignContractError("variance pilot requires at least five valid warm repeats")
	if any(not math.isfinite(value) or value <= 0 for value in values):
		raise CampaignContractError("pilot timings must be finite and positive")
	median = statistics.median(values)
	mad = statistics.median(abs(value - median) for value in values)
	mean = statistics.fmean(values)
	return {
		"valid_repeats": len(values),
		"median": median,
		"mad": mad,
		"cv": statistics.pstdev(values) / mean,
	}
