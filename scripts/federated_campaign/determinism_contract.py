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
import re
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


class CampaignContractError(ValueError):
	"""Raised when evidence cannot satisfy the frozen campaign contract."""


@dataclass(frozen=True)
class ResourceSnapshot:
	free_bytes: int
	free_inodes: int
	remaining_seconds: float


CAMPAIGN_WORKERS = (1, 2, 3, 4)
CAMPAIGN_PLANNERS = ("DP", "FedAll", "Heuristic", "MinST")
CAMPAIGN_WORKLOADS = ("kmeans", "pca", "lm", "l2svm", "logreg", "als", "steplm")
CAMPAIGN_PROFILES = ("lan", "wan_light", "wan_mid")


def campaign_block_ids() -> tuple[str, ...]:
	"""Return the frozen 84 block identities in matrix order (planner excluded)."""
	return tuple(
		f"workers={workers}|workload={workload}|profile={profile}"
		for workers in CAMPAIGN_WORKERS
		for workload in CAMPAIGN_WORKLOADS
		for profile in CAMPAIGN_PROFILES
	)


def campaign_cell_ids() -> tuple[str, ...]:
	"""Return the frozen 336 cell identities in harness product order."""
	return tuple(
		f"workers={workers}|planner={planner}|workload={workload}|profile={profile}"
		for workers in CAMPAIGN_WORKERS
		for planner in CAMPAIGN_PLANNERS
		for workload in CAMPAIGN_WORKLOADS
		for profile in CAMPAIGN_PROFILES
	)


def validate_campaign_matrix(block_ids: Sequence[str], cell_ids: Sequence[str]) -> tuple[tuple[str, ...], tuple[str, ...]]:
	"""Reject any missing, extra, duplicated, or reordered campaign identity."""
	blocks = _require_nonempty_unique("campaign block ids", block_ids)
	cells = _require_nonempty_unique("campaign cell ids", cell_ids)
	if blocks != campaign_block_ids():
		raise CampaignContractError("campaign block ids do not match the exact ordered 84-block contract")
	if cells != campaign_cell_ids():
		raise CampaignContractError("campaign cell ids do not match the exact ordered 336-cell contract")
	return blocks, cells


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
	if not normalized or any(
		not isinstance(value, str) or not value or value.strip() != value for value in normalized
	):
		raise CampaignContractError(f"{name} must contain normalized non-empty strings")
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
	block_schedule: dict[str, object] | None = None,
	expected_block_order: Sequence[str] | None = None,
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
	if block_schedule is not None:
		if expected_block_order is None:
			raise CampaignContractError("an independent expected block order is required")
		block_schedule = validate_block_counterbalanced_schedule(
			block_schedule, planners, measured_warm_runs, seed, expected_block_order
		)
	elif expected_block_order is not None:
		raise CampaignContractError("expected block order requires a block schedule")

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
		"block_order": list(expected_block_order) if expected_block_order is not None else None,
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
		"block_schedule": json.loads(json.dumps(block_schedule, sort_keys=True)) if block_schedule is not None else None,
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


def _williams_rows(planners: Sequence[str], seed: int) -> list[list[str]]:
	labels = list(_require_nonempty_unique("planners", planners))
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
	return rows


def build_counterbalanced_schedule(planners: Sequence[str], replicates: int, seed: int) -> list[list[str]]:
	"""Return seeded Williams rows; partial cycles are not claimed as balanced."""
	if isinstance(replicates, bool) or not isinstance(replicates, int) or replicates < 1:
		raise CampaignContractError("replicates must be a positive integer")
	rows = _williams_rows(planners, seed)
	return [list(rows[index % len(rows)]) for index in range(replicates)]


def build_block_counterbalanced_schedule(
	planners: Sequence[str], repeats: int, block_ids: Sequence[str], seed: int
) -> dict[str, object]:
	"""Rotate partial Williams cycles across blocks and persist exact order facts."""
	if repeats not in (3, 5, 7):
		raise CampaignContractError("block schedule repeats must be exactly 3, 5, or 7")
	blocks = _require_nonempty_unique("block_ids", block_ids)
	rows = _williams_rows(planners, seed)
	labels = tuple(rows[0])
	period_counts = {str(period + 1): {planner: 0 for planner in labels} for period in range(len(labels))}
	carryover_counts = {f"{left}>{right}": 0 for left in labels for right in labels if left != right}
	persisted_blocks = []
	for block_index, block_id in enumerate(blocks):
		runs = []
		block_period_counts = {str(period + 1): {planner: 0 for planner in labels} for period in range(len(labels))}
		for replicate in range(repeats):
			row_index = (block_index + replicate) % len(rows)
			order = list(rows[row_index])
			periods = []
			for period, planner in enumerate(order, start=1):
				periods.append({"period": period, "planner": planner})
				period_counts[str(period)][planner] += 1
				block_period_counts[str(period)][planner] += 1
			for left, right in zip(order, order[1:]):
				carryover_counts[f"{left}>{right}"] += 1
			runs.append(
				{
					"lifecycle_replicate": replicate + 1,
					"williams_row": row_index,
					"periods": periods,
					"order": ">".join(order),
				}
			)
		persisted_blocks.append(
			{
				"block": block_id,
				"rotation_start_row": block_index % len(rows),
				"within_cell_fully_balanced": all(
					len(set(counts.values())) == 1 for counts in block_period_counts.values()
				),
				"runs": runs,
			}
		)
	return {
		"schema": "systemds-federated-block-schedule/v1",
		"seed": seed,
		"repeats": repeats,
		"planners": list(labels),
		"blocks": persisted_blocks,
		"aggregate_period_counts": period_counts,
		"aggregate_directed_carryover_counts": carryover_counts,
		"aggregate_fully_balanced": (
			all(len(set(counts.values())) == 1 for counts in period_counts.values())
			and len(set(carryover_counts.values())) == 1
		),
	}


def validate_block_counterbalanced_schedule(
	schedule: object,
	planners: Sequence[str],
	repeats: int,
	seed: int,
	expected_block_order: Sequence[str],
) -> dict[str, object]:
	"""Recompute every persisted schedule fact and reject claimed-only balance."""
	if not isinstance(schedule, dict):
		raise CampaignContractError("block schedule must be a JSON object")
	blocks = schedule.get("blocks")
	if not isinstance(blocks, list) or not blocks:
		raise CampaignContractError("block schedule blocks are missing")
	try:
		block_ids = tuple(block["block"] for block in blocks if isinstance(block, dict))
	except (KeyError, TypeError) as error:
		raise CampaignContractError("block schedule block identity is invalid") from error
	if len(block_ids) != len(blocks):
		raise CampaignContractError("block schedule block structure is invalid")
	expected_blocks = _require_nonempty_unique("expected block order", expected_block_order)
	if block_ids != expected_blocks:
		raise CampaignContractError("block schedule does not match the independent frozen block order")
	expected = build_block_counterbalanced_schedule(planners, repeats, expected_blocks, seed)
	if schedule != expected:
		raise CampaignContractError("block schedule does not match recomputed Williams rotation")
	if expected["aggregate_fully_balanced"] is not True:
		raise CampaignContractError("block schedule is not aggregate-balanced")
	return json.loads(json.dumps(expected, sort_keys=True))


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
	if expected_metric_kind == "systemds_total_execution_time":
		raw_log = paths["raw_coordinator.log"].read_text(encoding="utf-8")
		matches = re.findall(
			r"^Total execution time:\s+([0-9]+(?:\.[0-9]+)?)\s+sec\.\s*$", raw_log, re.MULTILINE
		)
		if len(matches) != 1:
			raise CampaignContractError("warm phase requires exactly one strict SystemDS Total execution time")
		if not math.isclose(float(matches[0]), float(seconds), rel_tol=0.0, abs_tol=1e-12):
			raise CampaignContractError("warm metric record does not match raw SystemDS execution time")
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


def _named_file_records(name: str, files: Mapping[str, Path], expected_names: Sequence[str] | None = None) -> dict[str, dict[str, object]]:
	if expected_names is not None and set(files) != set(expected_names):
		raise CampaignContractError(f"{name} must contain the exact names {tuple(expected_names)!r}")
	if not files:
		raise CampaignContractError(f"{name} must not be empty")
	labels = tuple(expected_names) if expected_names is not None else tuple(sorted(files))
	return {label: _file_record(Path(files[label])) for label in labels}


def build_campaign_manifest(
	*,
	source_commit: str,
	image_id: str,
	image_digest: str,
	wrapper: Path,
	jar: Path,
	planner_configs: Mapping[str, Path],
	cp_config: Path,
	fed_dmls: Mapping[str, Path],
	cp_dmls: Mapping[str, Path],
	oracle_files: Mapping[str, Path],
	compose_files: Mapping[str, Path],
	runner_files: Mapping[str, Path],
	dataset_root: Path,
	data_sidecar: Path,
	block_ids: Sequence[str],
	cell_ids: Sequence[str],
	network_costs: Mapping[str, object],
	privacy_settings: Mapping[str, object],
	jvm_settings: Mapping[str, object],
	thread_settings: Mapping[str, object],
	resource_settings: Mapping[str, object],
	block_schedule: dict[str, object],
	reference_artifacts: Mapping[str, Path],
	tolerance_version: str,
	seed: int,
	repeats: int,
) -> dict[str, object]:
	"""Freeze every campaign-wide input into one canonical v2 manifest."""
	if not isinstance(source_commit, str) or not re.fullmatch(r"[0-9a-f]{40}", source_commit):
		raise CampaignContractError("source_commit must be an exact 40-character lowercase Git commit")
	if not image_id.startswith("sha256:") or "@sha256:" not in image_digest:
		raise CampaignContractError("image ID and digest must be immutable sha256 identities")
	if isinstance(seed, bool) or not isinstance(seed, int) or seed < 0:
		raise CampaignContractError("an explicit non-negative integer seed is required")
	if repeats not in (3, 5, 7):
		raise CampaignContractError("repeats must be exactly 3, 5, or 7")
	blocks, cells = validate_campaign_matrix(block_ids, cell_ids)
	validated_schedule = validate_block_counterbalanced_schedule(
		block_schedule, CAMPAIGN_PLANNERS, repeats, seed, blocks
	)
	for name, value in (
		("network_costs", network_costs),
		("privacy_settings", privacy_settings),
		("jvm_settings", jvm_settings),
		("thread_settings", thread_settings),
		("resource_settings", resource_settings),
	):
		if not isinstance(value, Mapping) or not value:
			raise CampaignContractError(f"{name} must be a non-empty frozen mapping")
	_require_nonempty_unique("tolerance_version", (tolerance_version,))
	artifacts = {
		"wrapper": _file_record(Path(wrapper)),
		"jar": _file_record(Path(jar)),
		"planner_configs": _named_file_records("planner_configs", planner_configs, CAMPAIGN_PLANNERS),
		"cp_config": _file_record(Path(cp_config)),
		"fed_dmls": _named_file_records("fed_dmls", fed_dmls, CAMPAIGN_WORKLOADS),
		"cp_dmls": _named_file_records("cp_dmls", cp_dmls, CAMPAIGN_WORKLOADS),
		"oracle_files": _named_file_records("oracle_files", oracle_files),
		"compose_files": _named_file_records("compose_files", compose_files),
		"runner_files": _named_file_records("runner_files", runner_files),
		"dataset": _dataset_records(Path(dataset_root)),
		"data_sidecar": _file_record(Path(data_sidecar)),
		"reference_artifacts": _named_file_records("reference_artifacts", reference_artifacts),
	}
	manifest: dict[str, object] = {
		"schema": "systemds-federated-docker-campaign/v2",
		"execution_surface": "docker-only",
		"source_commit": source_commit,
		"image": {"id": image_id, "digest": image_digest, "prebuilt": True},
		"artifacts": artifacts,
		"dimensions": {
			"workers": list(CAMPAIGN_WORKERS),
			"planners": list(CAMPAIGN_PLANNERS),
			"workloads": list(CAMPAIGN_WORKLOADS),
			"profiles": list(CAMPAIGN_PROFILES),
			"block_ids": list(blocks),
			"cell_ids": list(cells),
		},
		"network_costs": dict(network_costs),
		"privacy_settings": dict(privacy_settings),
		"jvm_settings": dict(jvm_settings),
		"thread_settings": dict(thread_settings),
		"resource_settings": dict(resource_settings),
		"schedule": validated_schedule,
		"seed": seed,
		"repeats": repeats,
		"tolerance_version": tolerance_version,
	}
	canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
	manifest["manifest_hash"] = hashlib.sha256(canonical).hexdigest()
	return manifest


def select_pilot_repeats(rows: Sequence[Mapping[str, object]]) -> dict[str, object]:
	"""Freeze 3/5/7 repeats from exactly five preregistered verified pilot rows."""
	if len(rows) != 5:
		raise CampaignContractError("pilot selector requires exactly five preregistered rows")
	cell: str | None = None
	values: list[float] = []
	for expected_repeat, row in enumerate(rows, start=1):
		if not isinstance(row, Mapping) or set(row) != {
			"cell", "pilot_repeat", "warm_seconds", "evidence_status", "evidence_sha256"
		}:
			raise CampaignContractError("pilot row schema is not exact")
		if row["pilot_repeat"] != expected_repeat:
			raise CampaignContractError("pilot rows contain a gap, duplicate, or reorder")
		if row["evidence_status"] not in ("committed", "archive"):
			raise CampaignContractError("pilot rows require valid committed or archive evidence")
		digest = row["evidence_sha256"]
		if not isinstance(digest, str) or re.fullmatch(r"[0-9a-f]{64}", digest) is None:
			raise CampaignContractError("pilot row evidence checksum is invalid")
		row_cell = row["cell"]
		if not isinstance(row_cell, str) or not row_cell:
			raise CampaignContractError("pilot row cell is invalid")
		if cell is None:
			cell = row_cell
		elif cell != row_cell:
			raise CampaignContractError("pilot rows must describe one exact cell")
		seconds = row["warm_seconds"]
		if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(float(seconds)) or float(seconds) <= 0:
			raise CampaignContractError("pilot row warm_seconds is invalid")
		values.append(float(seconds))
	median = statistics.median(values)
	q = max(abs(math.log(value / median)) for value in values)
	repeats = 3 if q <= math.log(1.02) else 5 if q <= math.log(1.05) else 7
	return {
		"schema": "systemds-federated-pilot-selection/v1",
		"cell": cell,
		"preregistered_repeats": 5,
		"q": q,
		"thresholds": {"three": math.log(1.02), "five": math.log(1.05)},
		"selected_repeats": repeats,
		"row_evidence_sha256": [str(row["evidence_sha256"]) for row in rows],
	}
