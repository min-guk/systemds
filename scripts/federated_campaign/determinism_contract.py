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
from typing import Callable, Iterable, Mapping, Sequence, cast


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
COMPOSE_SURFACES = ("base", "campaign")
RUNNER_SURFACES = ("campaign", "docker", "snapshot", "data_prep")
COMMAND_SURFACES = ("compose", "campaign", "docker_lifecycle", "systemds_snapshot", "data_prep")
ENDPOINT_NAMES = ("coordinator", "worker_1", "worker_2", "worker_3", "worker_4")
PILOT_CLASSES = ("cheap", "medium", "heavy")
PILOT_REGIMES = ((1, "lan"), (4, "wan_mid"))
PILOT_REPRESENTATIVE_WORKLOADS = {"cheap": "kmeans", "medium": "logreg", "heavy": "als"}
SEED_STREAMS = ("schedule", "data_generation", "workload_random")


def campaign_block_ids() -> tuple[str, ...]:
	"""Return the frozen 84 block identities in matrix order (planner excluded)."""
	return tuple(
		f"workers={workers}|workload={workload}|profile={profile}"
		for workers in CAMPAIGN_WORKERS
		for workload in CAMPAIGN_WORKLOADS
		for profile in CAMPAIGN_PROFILES
	)


def campaign_cell_ids() -> tuple[str, ...]:
	"""Return four global 84-cell planner barriers in execution order."""
	return tuple(
		f"workers={workers}|planner={planner}|workload={workload}|profile={profile}"
		for planner in CAMPAIGN_PLANNERS
		for workers in CAMPAIGN_WORKERS
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
	if expected_metric_kind not in ("discovery_correctness", "docker_e2e", "systemds_total_execution_time"):
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


def _distinct_named_file_records(name: str, files: Mapping[str, Path], expected_names: Sequence[str]) -> dict[str, dict[str, object]]:
	records = _named_file_records(name, files, expected_names)
	paths = [str(record["path"]) for record in records.values()]
	digests = [str(record["sha256"]) for record in records.values()]
	if len(set(paths)) != len(expected_names) or len(set(digests)) != len(expected_names):
		raise CampaignContractError(f"{name} must contain {len(expected_names)} distinct workload artifacts")
	return records


def _sha256_text(name: str, value: object) -> str:
	if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
		raise CampaignContractError(f"{name} must be a lowercase SHA256 digest")
	return value


def _positive_finite(name: str, value: object, *, allow_zero: bool = False) -> float:
	if isinstance(value, bool) or not isinstance(value, (int, float)):
		raise CampaignContractError(f"{name} must be numeric")
	number = float(value)
	if not math.isfinite(number) or number < 0 or (number == 0 and not allow_zero):
		raise CampaignContractError(f"{name} must be {'non-negative' if allow_zero else 'positive'} and finite")
	return number


def build_campaign_manifest(
	*,
	source_commit: str,
	source_tree: Path,
	image_id: str,
	image_digest: str,
	wrapper: Path,
	jar: Path,
	planner_configs: Mapping[str, Path],
	cp_config: Path,
	fed_dmls: Mapping[str, Path],
	cp_dmls: Mapping[str, Path],
	oracle_files: Mapping[str, Path],
	oracle_policies: Mapping[str, Mapping[str, object]],
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
	commands: Mapping[str, object],
	endpoints: Mapping[str, object],
	topology: Mapping[str, object],
	block_schedule: dict[str, object],
	reference_artifacts: Mapping[str, Path],
	tolerance_version: str,
	seed_streams: Mapping[str, object],
	repeats: int,
) -> dict[str, object]:
	"""Freeze every campaign-wide input into one canonical v2 manifest."""
	if not isinstance(source_commit, str) or not re.fullmatch(r"[0-9a-f]{40}", source_commit):
		raise CampaignContractError("source_commit must be an exact 40-character lowercase Git commit")
	if re.fullmatch(r"sha256:[0-9a-f]{64}", image_id) is None or re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", image_digest) is None:
		raise CampaignContractError("image ID and digest must be immutable sha256 identities")
	if set(seed_streams) != set(SEED_STREAMS) or any(
		isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in seed_streams.values()
	):
		raise CampaignContractError("explicit non-negative integer seed streams are required")
	schedule_seed = cast(int, seed_streams["schedule"])
	if repeats not in (3, 5, 7):
		raise CampaignContractError("repeats must be exactly 3, 5, or 7")
	blocks, cells = validate_campaign_matrix(block_ids, cell_ids)
	validated_schedule = validate_block_counterbalanced_schedule(
		block_schedule, CAMPAIGN_PLANNERS, repeats, schedule_seed, blocks
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
	if set(commands) != set(COMMAND_SURFACES) or any(
		not isinstance(value, list) or not value or any(
			not isinstance(arg, str) or not arg or arg.strip() != arg or "\x00" in arg for arg in value
		)
		for value in commands.values()
	):
		raise CampaignContractError("commands must contain exact non-empty argv surfaces")
	if set(endpoints) != set(ENDPOINT_NAMES) or any(
		not isinstance(value, str) or re.fullmatch(r"[A-Za-z0-9_.-]+:[1-9][0-9]{0,4}", value) is None
		for value in endpoints.values()
	):
		raise CampaignContractError("endpoints must contain exact coordinator/worker endpoints")
	_require_nonempty_unique("endpoints", tuple(cast(str, endpoints[name]) for name in ENDPOINT_NAMES))
	if any(int(cast(str, endpoints[name]).rsplit(":", 1)[1]) > 65535 for name in ENDPOINT_NAMES):
		raise CampaignContractError("endpoint port is outside the valid range")
	if set(topology) != {"worker_counts", "profiles", "docker_project"}:
		raise CampaignContractError("topology schema is not exact")
	if topology["worker_counts"] != list(CAMPAIGN_WORKERS) or topology["profiles"] != list(CAMPAIGN_PROFILES):
		raise CampaignContractError("topology dimensions are not exact")
	_require_nonempty_unique("docker project", (cast(str, topology["docker_project"]),))
	if re.fullmatch(r"[a-z0-9][a-z0-9_-]*", cast(str, topology["docker_project"])) is None:
		raise CampaignContractError("docker project is invalid")
	if set(network_costs) != set(CAMPAIGN_PROFILES) or any(not isinstance(network_costs[name], Mapping) for name in CAMPAIGN_PROFILES):
		raise CampaignContractError("network costs must define every exact profile")
	if set(resource_settings) != {"absolute_disk_floor_bytes", "required_free_inodes", "wall_time_seconds", "max_io_utilization", "max_combined_io_bps"}:
		raise CampaignContractError("resource settings schema is not exact")
	if resource_settings["absolute_disk_floor_bytes"] != 5 * 1024**3:
		raise CampaignContractError("resource settings must freeze the 5GiB absolute floor")
	if privacy_settings != {"public_tests_ignored": True, "runtime_fallback_allowed": False}:
		raise CampaignContractError("privacy settings must freeze public-test exclusion and forbid runtime fallback")
	if set(jvm_settings) != {"java_opts", "heap_bytes", "coordinator_fresh"} or jvm_settings["coordinator_fresh"] is not True:
		raise CampaignContractError("JVM settings schema is not exact")
	if (
		not isinstance(jvm_settings["java_opts"], list)
		or not jvm_settings["java_opts"]
		or any(not isinstance(option, str) or not option.strip() for option in cast(list[object], jvm_settings["java_opts"]))
		or isinstance(jvm_settings["heap_bytes"], bool)
		or not isinstance(jvm_settings["heap_bytes"], int)
		or cast(int, jvm_settings["heap_bytes"]) <= 0
	):
		raise CampaignContractError("JVM values are invalid")
	if set(thread_settings) != {"blas_threads", "omp_threads", "systemds_threads"}:
		raise CampaignContractError("thread settings schema is not exact")
	if any(isinstance(value, bool) or not isinstance(value, int) or value < 1 for value in thread_settings.values()):
		raise CampaignContractError("thread settings must be positive integers")
	for profile in CAMPAIGN_PROFILES:
		cost = network_costs[profile]
		if not isinstance(cost, Mapping) or set(cost) != {"latency_ms", "bandwidth_mbps"}:
			raise CampaignContractError(f"network cost schema is not exact for {profile}")
		latency, bandwidth = cost["latency_ms"], cost["bandwidth_mbps"]
		if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in (latency, bandwidth)):
			raise CampaignContractError(f"network costs are invalid for {profile}")
		latency_value, bandwidth_value = float(cast(int | float, latency)), float(cast(int | float, bandwidth))
		if not math.isfinite(latency_value) or not math.isfinite(bandwidth_value) or latency_value < 0 or bandwidth_value <= 0:
			raise CampaignContractError(f"network costs are invalid for {profile}")
	inodes = resource_settings["required_free_inodes"]
	if isinstance(inodes, bool) or not isinstance(inodes, int) or inodes <= 0:
		raise CampaignContractError("resource setting required_free_inodes must be a positive integer")
	_positive_finite("resource setting wall_time_seconds", resource_settings["wall_time_seconds"])
	io_limit = _positive_finite("resource setting max_io_utilization", resource_settings["max_io_utilization"])
	if io_limit > 1:
		raise CampaignContractError("resource setting max_io_utilization must be in (0, 1]")
	_positive_finite("resource setting max_combined_io_bps", resource_settings["max_combined_io_bps"])
	if set(oracle_policies) != set(CAMPAIGN_WORKLOADS):
		raise CampaignContractError("oracle_policies must cover every exact workload")
	validated_policies: dict[str, dict[str, object]] = {}
	for workload in CAMPAIGN_WORKLOADS:
		policy = oracle_policies[workload]
		if set(policy) != {"version", "policy_sha256", "self_drift_a_sha256", "self_drift_b_sha256"}:
			raise CampaignContractError(f"oracle policy schema is not exact for {workload}")
		version = policy["version"]
		if not isinstance(version, str) or not version.strip():
			raise CampaignContractError(f"oracle policy version is invalid for {workload}")
		if version != tolerance_version:
			raise CampaignContractError(f"oracle policy version disagrees with tolerance version for {workload}")
		policy_hash = _sha256_text(f"oracle policy hash for {workload}", policy["policy_sha256"])
		drift_a = _sha256_text(f"self-drift A for {workload}", policy["self_drift_a_sha256"])
		drift_b = _sha256_text(f"self-drift B for {workload}", policy["self_drift_b_sha256"])
		if drift_a != drift_b:
			raise CampaignContractError(f"oracle self-drift A/B disagreement for {workload}")
		validated_policies[workload] = {"version": version, "policy_sha256": policy_hash, "self_drift_a_sha256": drift_a, "self_drift_b_sha256": drift_b}
	artifacts = {
		"wrapper": _file_record(Path(wrapper)),
		"jar": _file_record(Path(jar)),
		"planner_configs": _named_file_records("planner_configs", planner_configs, CAMPAIGN_PLANNERS),
		"cp_config": _file_record(Path(cp_config)),
		"fed_dmls": _distinct_named_file_records("fed_dmls", fed_dmls, CAMPAIGN_WORKLOADS),
		"cp_dmls": _distinct_named_file_records("cp_dmls", cp_dmls, CAMPAIGN_WORKLOADS),
		"oracle_files": _distinct_named_file_records("oracle_files", oracle_files, CAMPAIGN_WORKLOADS),
		"compose_files": _named_file_records("compose_files", compose_files, COMPOSE_SURFACES),
		"runner_files": _named_file_records("runner_files", runner_files, RUNNER_SURFACES),
		"dataset": _dataset_records(Path(dataset_root)),
		"data_sidecar": _file_record(Path(data_sidecar)),
		"reference_artifacts": _distinct_named_file_records("reference_artifacts", reference_artifacts, CAMPAIGN_WORKLOADS),
		"source_tree": _dataset_records(Path(source_tree)),
	}
	for workload in CAMPAIGN_WORKLOADS:
		oracle_record = cast(dict[str, object], cast(dict[str, object], artifacts["oracle_files"])[workload])
		if validated_policies[workload]["policy_sha256"] != oracle_record["sha256"]:
			raise CampaignContractError(f"oracle policy hash does not match oracle file for {workload}")
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
		"commands": dict(commands),
		"endpoints": dict(endpoints),
		"topology": dict(topology),
		"oracle_policies": validated_policies,
		"schedule": validated_schedule,
		"seed_streams": {name: seed_streams[name] for name in SEED_STREAMS},
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


def select_campaign_pilot_repeats(
	rows: Sequence[Mapping[str, object]], evidence_validator: Callable[[Mapping[str, object]], None],
	*, expected_manifest_hash: str, expected_invocation_manifest_sha256: str,
) -> dict[str, object]:
	"""Select one frozen repeat count from the preregistered cross-campaign pilot."""
	required = {
		"pilot_class", "workload", "planner", "workers", "profile", "cell", "pilot_repeat", "warm_seconds",
		"period", "order", "carryover", "host_load", "lifecycle", "evidence_status", "evidence_sha256",
		"identity", "evidence_location", "invocation_manifest_sha256",
	}
	if not callable(evidence_validator):
		raise CampaignContractError("campaign pilot requires an evidence revalidation callback")
	_sha256_text("expected campaign manifest", expected_manifest_hash)
	_sha256_text("expected pilot invocation manifest", expected_invocation_manifest_sha256)
	if len(rows) != len(PILOT_CLASSES) * len(CAMPAIGN_PLANNERS) * len(PILOT_REGIMES) * 5:
		raise CampaignContractError("campaign pilot requires the exact preregistered 120-row set")
	groups: dict[tuple[str, str, int, str, str], list[Mapping[str, object]]] = {}
	for row in rows:
		if set(row) != required:
			raise CampaignContractError("campaign pilot row schema is not exact")
		pilot_class, workload, planner, workers, profile, cell = (
			row["pilot_class"], row["workload"], row["planner"], row["workers"], row["profile"], row["cell"]
		)
		if (
			pilot_class not in PILOT_CLASSES or workload != PILOT_REPRESENTATIVE_WORKLOADS.get(cast(str, pilot_class))
			or planner not in CAMPAIGN_PLANNERS or (workers, profile) not in PILOT_REGIMES
		):
			raise CampaignContractError("campaign pilot contains a non-preregistered class/planner/regime")
		expected_cell = f"pilot_class={pilot_class}|workload={workload}|planner={planner}|workers={workers}|profile={profile}"
		if cell != expected_cell:
			raise CampaignContractError("campaign pilot cell identity is not canonical")
		key = (cast(str, pilot_class), cast(str, planner), cast(int, workers), cast(str, profile), cast(str, cell))
		groups.setdefault(key, []).append(row)
	expected_groups = {
		(pilot_class, planner, workers, profile, f"pilot_class={pilot_class}|workload={PILOT_REPRESENTATIVE_WORKLOADS[pilot_class]}|planner={planner}|workers={workers}|profile={profile}")
		for pilot_class in PILOT_CLASSES for planner in CAMPAIGN_PLANNERS for workers, profile in PILOT_REGIMES
	}
	if set(groups) != expected_groups:
		raise CampaignContractError("campaign pilot groups do not match the exact preregistration")
	preregistered_orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)
	diagnostics: list[dict[str, object]] = []
	deviations: list[float] = []
	seen_evidence: set[str] = set()
	for key in sorted(expected_groups):
		group = groups[key]
		ordered = sorted(group, key=lambda row: int(cast(int, row["pilot_repeat"])))
		if [row["pilot_repeat"] for row in ordered] != [1, 2, 3, 4, 5]:
			raise CampaignContractError("each campaign pilot group requires exact repeats 1..5")
		values: list[float] = []
		for repeat_index, row in enumerate(ordered):
			identity_value = row["identity"]
			if not isinstance(identity_value, Mapping):
				raise CampaignContractError("campaign pilot identity is invalid")
			if identity_value.get("manifest_hash") != expected_manifest_hash:
				raise CampaignContractError("campaign pilot mixes frozen campaign manifests")
			if row["invocation_manifest_sha256"] != expected_invocation_manifest_sha256:
				raise CampaignContractError("campaign pilot mixes invocation manifests")
			try:
				evidence_validator(row)
			except Exception as error:
				raise CampaignContractError("campaign pilot evidence revalidation failed") from error
			if row["evidence_status"] not in ("committed", "archive"):
				raise CampaignContractError("campaign pilot rows require verified evidence")
			evidence_digest = _sha256_text("campaign pilot evidence", row["evidence_sha256"])
			if evidence_digest in seen_evidence:
				raise CampaignContractError("campaign pilot evidence checksum is duplicated")
			seen_evidence.add(evidence_digest)
			value = row["warm_seconds"]
			values.append(_positive_finite("campaign pilot timing", value))
			diagnostic_schemas = {
				"host_load": {"io_utilization", "read_bytes_per_second", "write_bytes_per_second"},
				"lifecycle": {"cold_seconds", "warm_seconds", "coordinator_restart_count", "worker_restart_count"},
			}
			for diagnostic_name, diagnostic_fields in diagnostic_schemas.items():
				diagnostic_value = row[diagnostic_name]
				if not isinstance(diagnostic_value, Mapping) or set(diagnostic_value) != diagnostic_fields:
					raise CampaignContractError(f"campaign pilot {diagnostic_name} schema is not exact")
				for name, diagnostic_value_item in diagnostic_value.items():
					_positive_finite(f"campaign pilot {diagnostic_name}.{name}", diagnostic_value_item, allow_zero=True)
			if cast(Mapping[str, object], row["lifecycle"])["warm_seconds"] != row["warm_seconds"]:
				raise CampaignContractError("campaign pilot lifecycle warm_seconds disagrees with measured timing")
			if any(
				cast(Mapping[str, object], row["lifecycle"])[name] != 0
				for name in ("coordinator_restart_count", "worker_restart_count")
			):
				raise CampaignContractError("successful campaign pilot lifecycle restart counts must be zero")
			expected_order_tuple = preregistered_orders[repeat_index]
			expected_order = ">".join(expected_order_tuple)
			expected_period = expected_order_tuple.index(key[1]) + 1
			expected_carryover = "NONE" if expected_period == 1 else expected_order_tuple[expected_period - 2]
			if row["order"] != expected_order or row["period"] != expected_period or row["carryover"] != expected_carryover:
				raise CampaignContractError("campaign pilot schedule period/order/carryover is not preregistered")
		median = statistics.median(values)
		group_deviations = [abs(math.log(value / median)) for value in values]
		deviations.extend(group_deviations)
		log_values = [math.log(value) for value in values]
		overall_log = statistics.fmean(log_values)
		def grouped_log_effect(field_name: str) -> dict[str, float]:
			grouped: dict[str, list[float]] = {}
			for index, row in enumerate(ordered):
				grouped.setdefault(str(row[field_name]), []).append(log_values[index] - overall_log)
			return {name: statistics.fmean(effects) for name, effects in grouped.items()}
		diagnostic_effects: dict[str, dict[str, dict[str, float]]] = {}
		for diagnostic_name in ("host_load", "lifecycle"):
			field_names = sorted(cast(Mapping[str, object], ordered[0][diagnostic_name]))
			diagnostic_effects[diagnostic_name] = {}
			for field_name in field_names:
				field_values = [
					_positive_finite(
						f"campaign pilot {diagnostic_name}.{field_name}",
						cast(Mapping[str, object], row[diagnostic_name])[field_name], allow_zero=True,
					)
					for row in ordered
				]
				diagnostic_effects[diagnostic_name][field_name] = {
					"median": statistics.median(field_values),
					"first_run_delta": field_values[0] - statistics.median(field_values[1:]),
				}
		diagnostics.append({
			"pilot_class": key[0], "planner": key[1], "workers": key[2], "profile": key[3], "cell": key[4],
			"median": median, "mad": statistics.median(abs(value - median) for value in values),
			"cv": statistics.pstdev(values) / statistics.fmean(values),
			"first_run_ratio": values[0] / statistics.median(values[1:]),
			"periods": [row["period"] for row in ordered], "orders": [row["order"] for row in ordered],
			"carryover": [row["carryover"] for row in ordered],
			"host_load": [row["host_load"] for row in ordered], "lifecycle": [row["lifecycle"] for row in ordered],
			"effects": {
				"first_run_log_effect": log_values[0] - statistics.fmean(log_values[1:]),
				"period_log_effect": grouped_log_effect("period"),
				"order_log_effect": grouped_log_effect("order"),
				"carryover_log_effect": grouped_log_effect("carryover"),
			},
			"diagnostic_effects": diagnostic_effects,
		})
	ordered_deviations = sorted(deviations)
	index = max(0, math.ceil(0.95 * len(ordered_deviations)) - 1)
	q95 = ordered_deviations[index]
	eta = max(math.log(1.02), q95)
	selected = 3 if eta <= math.log(1.02) else 5 if eta <= math.log(1.05) else 7
	return {
		"schema": "systemds-federated-campaign-pilot/v2", "selection_rule": "eta=max(log(1.02),Q95(abs(log(t/median_group))))",
		"preregistration": {
			"row_count": 120, "pilot_classes": list(PILOT_CLASSES), "planners": list(CAMPAIGN_PLANNERS),
			"regimes": [{"workers": workers, "profile": profile} for workers, profile in PILOT_REGIMES],
			"orders": [list(order) for order in preregistered_orders],
			"representative_workloads": dict(PILOT_REPRESENTATIVE_WORKLOADS),
			"manifest_hash": expected_manifest_hash,
			"invocation_manifest_sha256": expected_invocation_manifest_sha256,
		},
		"q95": q95, "eta": eta, "selected_repeats": selected, "diagnostics": diagnostics,
	}
