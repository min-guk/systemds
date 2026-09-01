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
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable, Mapping, Sequence, cast


class CampaignContractError(ValueError):
	"""Raised when evidence cannot satisfy the frozen campaign contract."""


@dataclass(frozen=True)
class ResourceSnapshot:
	free_bytes: int
	free_inodes: int
	remaining_seconds: float


CAMPAIGN_WORKERS = (1, 2, 3, 4)
CAMPAIGN_PLANNERS = ("DP", "FedAll", "Heuristic", "Exact")
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
	files = sorted(
		(path for path in root.rglob("*") if path.is_file()),
		key=lambda path: path.relative_to(root).as_posix(),
	)
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
	scan = _read_json_object(paths["scan.json"], "timeout/error/fallback/resource scan")
	scan_fields = {"timeout", "error", "fallback", "resource_invalid"}
	if set(scan) != scan_fields:
		raise CampaignContractError("phase success scan schema is not exact")
	for marker in scan_fields:
		if type(scan[marker]) is not bool or scan[marker] is not False:
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
	if (
		not isinstance(value, str)
		or re.fullmatch(r"[0-9a-f]{64}", value) is None
		or value == "0" * 64
	):
		raise CampaignContractError(f"{name} must be a lowercase SHA256 digest")
	return value


def _positive_finite(name: str, value: object, *, allow_zero: bool = False) -> float:
	if isinstance(value, bool) or not isinstance(value, (int, float)):
		raise CampaignContractError(f"{name} must be numeric")
	number = float(value)
	if not math.isfinite(number) or number < 0 or (number == 0 and not allow_zero):
		raise CampaignContractError(f"{name} must be {'non-negative' if allow_zero else 'positive'} and finite")
	return number


def _canonical_json_value(name: str, value: object) -> object:
	"""Return an isolated, finite, JSON-compatible value or fail closed."""
	def validate(current: object, location: str) -> None:
		if current is None or isinstance(current, (str, bool, int)):
			return
		if isinstance(current, float):
			if not math.isfinite(current):
				raise CampaignContractError(f"{location} contains a non-finite number")
			return
		if isinstance(current, list):
			for index, item in enumerate(current):
				validate(item, f"{location}[{index}]")
			return
		if isinstance(current, Mapping):
			for key, item in current.items():
				if not isinstance(key, str) or not key or key.strip() != key:
					raise CampaignContractError(f"{location} contains an invalid JSON key")
				validate(item, f"{location}.{key}")
			return
		raise CampaignContractError(f"{location} contains a non-JSON value")
	validate(value, name)
	return json.loads(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True))


def _canonical_sha256(value: object) -> str:
	return hashlib.sha256(
		json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")
	).hexdigest()


_FROZEN_CORE_FIELDS = {
	"source_commit", "image", "artifacts", "network_costs", "privacy_settings", "jvm_settings",
	"thread_settings", "endpoints", "topology", "oracle_policies", "tolerance_version",
}
_CAMPAIGN_CORE_INPUT_FIELDS = {
	"source_commit", "source_tree", "image_id", "image_digest", "wrapper", "jar", "planner_configs",
	"cp_config", "fed_dmls", "cp_dmls", "oracle_files", "oracle_policies", "compose_files", "runner_files",
	"dataset_root", "data_sidecar", "block_ids", "cell_ids", "network_costs", "privacy_settings",
	"jvm_settings", "thread_settings", "resource_settings", "commands", "endpoints", "topology",
	"reference_artifacts", "tolerance_version", "seed_streams",
}
_CONSERVATIVE_BOUND_FIELDS = {
	"remaining_lifecycles", "p95_artifact_bytes", "p95_artifact_inodes", "p95_lifecycle_seconds",
}


def build_campaign_preregistration_manifest(
	*,
	campaign_core_inputs: Mapping[str, object],
	stage_descriptor_sha256: str,
	cp_lifecycle_descriptor_sha256: str,
	reference_manifest_sha256: str,
	conservative_pre_pilot_bounds: Mapping[str, object],
	pilot_schedule_seed: int = 19,
) -> dict[str, object]:
	"""Build immutable pre-pilot manifest P without a post-hoc repeat choice."""
	if set(campaign_core_inputs) != _CAMPAIGN_CORE_INPUT_FIELDS:
		raise CampaignContractError("campaign core input schema is not exact")
	inputs = campaign_core_inputs
	seed_streams = cast(Mapping[str, object], inputs["seed_streams"])
	block_ids = cast(Sequence[str], inputs["block_ids"])
	cell_ids = cast(Sequence[str], inputs["cell_ids"])
	if set(seed_streams) != set(SEED_STREAMS) or any(
		type(value) is not int or cast(int, value) < 0 for value in seed_streams.values()
	):
		raise CampaignContractError("explicit non-negative integer seed streams are required")
	validation_schedule = build_block_counterbalanced_schedule(
		CAMPAIGN_PLANNERS, 3, block_ids, cast(int, seed_streams["schedule"])
	)
	validated = build_campaign_manifest(
		source_commit=cast(str, inputs["source_commit"]), source_tree=Path(cast(Path, inputs["source_tree"])),
		image_id=cast(str, inputs["image_id"]), image_digest=cast(str, inputs["image_digest"]),
		wrapper=Path(cast(Path, inputs["wrapper"])), jar=Path(cast(Path, inputs["jar"])),
		planner_configs=cast(Mapping[str, Path], inputs["planner_configs"]), cp_config=Path(cast(Path, inputs["cp_config"])),
		fed_dmls=cast(Mapping[str, Path], inputs["fed_dmls"]), cp_dmls=cast(Mapping[str, Path], inputs["cp_dmls"]),
		oracle_files=cast(Mapping[str, Path], inputs["oracle_files"]),
		oracle_policies=cast(Mapping[str, Mapping[str, object]], inputs["oracle_policies"]),
		compose_files=cast(Mapping[str, Path], inputs["compose_files"]),
		runner_files=cast(Mapping[str, Path], inputs["runner_files"]),
		dataset_root=Path(cast(Path, inputs["dataset_root"])), data_sidecar=Path(cast(Path, inputs["data_sidecar"])),
		block_ids=block_ids, cell_ids=cell_ids,
		network_costs=cast(Mapping[str, object], inputs["network_costs"]),
		privacy_settings=cast(Mapping[str, object], inputs["privacy_settings"]),
		jvm_settings=cast(Mapping[str, object], inputs["jvm_settings"]),
		thread_settings=cast(Mapping[str, object], inputs["thread_settings"]),
		resource_settings=cast(Mapping[str, object], inputs["resource_settings"]),
		commands=cast(Mapping[str, object], inputs["commands"]), endpoints=cast(Mapping[str, object], inputs["endpoints"]),
		topology=cast(Mapping[str, object], inputs["topology"]), block_schedule=validation_schedule,
		reference_artifacts=cast(Mapping[str, Path], inputs["reference_artifacts"]),
		tolerance_version=cast(str, inputs["tolerance_version"]), seed_streams=seed_streams, repeats=3,
	)
	core = {name: validated[name] for name in _FROZEN_CORE_FIELDS}
	resource_settings = cast(Mapping[str, object], validated["resource_settings"])
	commands = cast(Mapping[str, object], validated["commands"])
	lineage = {
		"stage_descriptor_sha256": _sha256_text("stage descriptor", stage_descriptor_sha256),
		"cp_lifecycle_descriptor_sha256": _sha256_text("CP lifecycle descriptor", cp_lifecycle_descriptor_sha256),
		"reference_manifest_sha256": _sha256_text("reference manifest", reference_manifest_sha256),
	}
	if pilot_schedule_seed != 19:
		raise CampaignContractError("pilot preregistration requires exact schedule seed 19")
	if set(commands) != set(COMMAND_SURFACES) or any(
		not isinstance(argv, list) or not argv or any(
			not isinstance(arg, str) or not arg or arg.strip() != arg or "\x00" in arg for arg in argv
		) for argv in commands.values()
	):
		raise CampaignContractError("commands must contain exact non-empty argv surfaces")
	if set(resource_settings) != {
		"absolute_disk_floor_bytes", "required_free_inodes", "wall_time_seconds", "max_io_utilization",
		"max_combined_io_bps",
	} or resource_settings["absolute_disk_floor_bytes"] != 5 * 1024**3:
		raise CampaignContractError("resource settings schema or 5GiB floor is not exact")
	if type(resource_settings["required_free_inodes"]) is not int or cast(int, resource_settings["required_free_inodes"]) < 1:
		raise CampaignContractError("resource required_free_inodes must be positive")
	_positive_finite("resource wall_time_seconds", resource_settings["wall_time_seconds"])
	io_limit = _positive_finite("resource max_io_utilization", resource_settings["max_io_utilization"])
	if io_limit > 1:
		raise CampaignContractError("resource max_io_utilization must be in (0, 1]")
	_positive_finite("resource max_combined_io_bps", resource_settings["max_combined_io_bps"])
	if set(conservative_pre_pilot_bounds) != _CONSERVATIVE_BOUND_FIELDS:
		raise CampaignContractError("conservative pre-pilot bounds schema is not exact")
	for name in ("remaining_lifecycles", "p95_artifact_bytes", "p95_artifact_inodes"):
		if type(conservative_pre_pilot_bounds[name]) is not int or cast(int, conservative_pre_pilot_bounds[name]) < 1:
			raise CampaignContractError(f"conservative bound {name} must be a positive integer")
	_positive_finite("conservative bound p95_lifecycle_seconds", conservative_pre_pilot_bounds["p95_lifecycle_seconds"])
	blocks, cells = validate_campaign_matrix(block_ids, cell_ids)
	pilot_orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, pilot_schedule_seed)
	manifest: dict[str, object] = {
		"schema": "systemds-federated-docker-campaign-preregistration/v3",
		"execution_surface": "docker-only",
		"lineage": lineage,
		"frozen_core": core,
		"seed_streams": {name: seed_streams[name] for name in SEED_STREAMS},
		"resource_settings": cast(dict[str, object], _canonical_json_value("resource settings", resource_settings)),
		"commands": cast(dict[str, object], _canonical_json_value("commands", commands)),
		"dimensions": {
			"block_ids": list(blocks), "cell_ids": list(cells),
			"planner_major_barriers": [
				{"planner": planner, "start": index * 84, "stop": (index + 1) * 84}
				for index, planner in enumerate(CAMPAIGN_PLANNERS)
			],
		},
		"pilot_preregistration": {
			"row_count": 120, "repeats": 5, "schedule_seed": 19,
			"pilot_classes": list(PILOT_CLASSES), "planners": list(CAMPAIGN_PLANNERS),
			"regimes": [{"workers": workers, "profile": profile} for workers, profile in PILOT_REGIMES],
			"orders": pilot_orders, "representative_workloads": dict(PILOT_REPRESENTATIVE_WORKLOADS),
		},
		"conservative_pre_pilot_bounds": cast(
			dict[str, object], _canonical_json_value("conservative pre-pilot bounds", conservative_pre_pilot_bounds)
		),
	}
	manifest["preregistration_manifest_sha256"] = _canonical_sha256(manifest)
	return manifest


def _validate_frozen_file_record(name: str, value: object) -> dict[str, object]:
	if not isinstance(value, Mapping) or set(value) != {"path", "bytes", "sha256"}:
		raise CampaignContractError(f"{name} frozen file record schema is not exact")
	path, size = value["path"], value["bytes"]
	if type(path) is not str or not path or "\x00" in path or not Path(path).is_absolute():
		raise CampaignContractError(f"{name} frozen file path is invalid")
	if str(Path(path).resolve()) != path:
		raise CampaignContractError(f"{name} frozen file path is not canonical")
	if type(size) is not int or cast(int, size) < 0:
		raise CampaignContractError(f"{name} frozen file bytes must be an exact non-negative integer")
	_sha256_text(f"{name} frozen file", value["sha256"])
	return dict(value)


def _validate_named_frozen_files(name: str, value: object, expected_names: Sequence[str]) -> None:
	if not isinstance(value, Mapping) or set(value) != set(expected_names):
		raise CampaignContractError(f"{name} frozen file set is not exact")
	for key in expected_names:
		_validate_frozen_file_record(f"{name}.{key}", value[key])


def _validate_frozen_tree(name: str, value: object) -> None:
	if not isinstance(value, list) or not value:
		raise CampaignContractError(f"{name} frozen tree is invalid")
	paths: list[str] = []
	for item in value:
		if not isinstance(item, Mapping) or set(item) != {"relative_path", "bytes", "sha256"}:
			raise CampaignContractError(f"{name} frozen tree record schema is not exact")
		path, size = item["relative_path"], item["bytes"]
		if type(path) is not str or not path or path == "." or "\x00" in path or "\\" in path:
			raise CampaignContractError(f"{name} frozen relative path is invalid")
		posix_path = PurePosixPath(path)
		if posix_path.is_absolute() or any(part in ("", ".", "..") for part in posix_path.parts) or posix_path.as_posix() != path:
			raise CampaignContractError(f"{name} frozen relative path is not canonical")
		if type(size) is not int or cast(int, size) < 0:
			raise CampaignContractError(f"{name} frozen bytes must be an exact non-negative integer")
		_sha256_text(f"{name} frozen tree", item["sha256"])
		paths.append(path)
	if paths != sorted(paths) or len(set(paths)) != len(paths):
		raise CampaignContractError(f"{name} frozen tree order is not canonical")


def validate_campaign_preregistration_manifest(value: Mapping[str, object]) -> dict[str, object]:
	"""Semantically revalidate P instead of trusting a correctly recomputed self-hash."""
	manifest = cast(dict[str, object], _canonical_json_value("preregistration manifest", value))
	expected_fields = {
		"schema", "execution_surface", "lineage", "frozen_core", "seed_streams", "resource_settings",
		"commands", "dimensions", "pilot_preregistration", "conservative_pre_pilot_bounds",
		"preregistration_manifest_sha256",
	}
	if set(manifest) != expected_fields or manifest.get("schema") != "systemds-federated-docker-campaign-preregistration/v3":
		raise CampaignContractError("preregistration manifest v3 schema is not exact")
	if manifest.get("execution_surface") != "docker-only":
		raise CampaignContractError("preregistration execution surface must be docker-only")
	digest = _sha256_text("preregistration manifest", manifest["preregistration_manifest_sha256"])
	unsigned = dict(manifest); unsigned.pop("preregistration_manifest_sha256")
	if _canonical_sha256(unsigned) != digest:
		raise CampaignContractError("preregistration manifest self-hash is invalid")
	lineage = manifest["lineage"]
	if not isinstance(lineage, Mapping) or set(lineage) != {
		"stage_descriptor_sha256", "cp_lifecycle_descriptor_sha256", "reference_manifest_sha256",
	}:
		raise CampaignContractError("preregistration lineage schema is not exact")
	for name, item in lineage.items():
		_sha256_text(f"preregistration lineage {name}", item)
	seeds = manifest["seed_streams"]
	if not isinstance(seeds, Mapping) or set(seeds) != set(SEED_STREAMS) or any(
		type(item) is not int or cast(int, item) < 0 for item in seeds.values()
	):
		raise CampaignContractError("preregistration seed streams are not exact")
	core = manifest["frozen_core"]
	if not isinstance(core, Mapping) or set(core) != _FROZEN_CORE_FIELDS:
		raise CampaignContractError("preregistration frozen core schema is not exact")
	if not isinstance(core["source_commit"], str) or re.fullmatch(r"[0-9a-f]{40}", core["source_commit"]) is None:
		raise CampaignContractError("preregistration source commit is invalid")
	image = core["image"]
	if not isinstance(image, Mapping) or set(image) != {"id", "digest", "prebuilt"} or image.get("prebuilt") is not True:
		raise CampaignContractError("preregistration image identity is invalid")
	if not isinstance(image.get("id"), str) or not isinstance(image.get("digest"), str) or re.fullmatch(
		r"sha256:[0-9a-f]{64}", cast(str, image["id"])
	) is None or re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}", cast(str, image["digest"])) is None:
		raise CampaignContractError("preregistration image digest is invalid")
	privacy = core["privacy_settings"]
	if (
		not isinstance(privacy, Mapping)
		or set(privacy) != {"public_tests_ignored", "runtime_fallback_allowed"}
		or privacy["public_tests_ignored"] is not True
		or privacy["runtime_fallback_allowed"] is not False
	):
		raise CampaignContractError("preregistration privacy/runtime-fallback contract is invalid")
	artifacts = core["artifacts"]
	if not isinstance(artifacts, Mapping) or set(artifacts) != {
		"wrapper", "jar", "planner_configs", "cp_config", "fed_dmls", "cp_dmls", "oracle_files",
		"compose_files", "runner_files", "dataset", "data_sidecar", "reference_artifacts", "source_tree",
	}:
		raise CampaignContractError("preregistration frozen artifacts are invalid")
	for name in ("wrapper", "jar", "cp_config", "data_sidecar"):
		_validate_frozen_file_record(f"artifacts.{name}", artifacts[name])
	_validate_named_frozen_files("planner_configs", artifacts["planner_configs"], CAMPAIGN_PLANNERS)
	for name in ("fed_dmls", "cp_dmls", "oracle_files", "reference_artifacts"):
		_validate_named_frozen_files(name, artifacts[name], CAMPAIGN_WORKLOADS)
		records = cast(Mapping[str, Mapping[str, object]], artifacts[name])
		paths = [records[workload]["path"] for workload in CAMPAIGN_WORKLOADS]
		digests = [records[workload]["sha256"] for workload in CAMPAIGN_WORKLOADS]
		if len(set(paths)) != len(CAMPAIGN_WORKLOADS) or len(set(digests)) != len(CAMPAIGN_WORKLOADS):
			raise CampaignContractError(f"{name} must contain distinct workload artifacts")
	_validate_named_frozen_files("compose_files", artifacts["compose_files"], COMPOSE_SURFACES)
	_validate_named_frozen_files("runner_files", artifacts["runner_files"], RUNNER_SURFACES)
	_validate_frozen_tree("dataset", artifacts["dataset"])
	_validate_frozen_tree("source_tree", artifacts["source_tree"])
	network = core["network_costs"]
	if not isinstance(network, Mapping) or set(network) != set(CAMPAIGN_PROFILES):
		raise CampaignContractError("preregistration network profiles are invalid")
	for profile in CAMPAIGN_PROFILES:
		cost = network[profile]
		if not isinstance(cost, Mapping) or set(cost) != {"latency_ms", "bandwidth_mbps"}:
			raise CampaignContractError("preregistration network cost schema is invalid")
		latency = _positive_finite(f"{profile} latency", cost["latency_ms"], allow_zero=True)
		bandwidth = _positive_finite(f"{profile} bandwidth", cost["bandwidth_mbps"])
		if isinstance(cost["latency_ms"], bool) or isinstance(cost["bandwidth_mbps"], bool) or latency < 0 or bandwidth <= 0:
			raise CampaignContractError("preregistration network cost types are invalid")
	topology = core["topology"]
	if not isinstance(topology, Mapping) or set(topology) != {"worker_counts", "profiles", "docker_project"} or topology["worker_counts"] != list(CAMPAIGN_WORKERS) or topology["profiles"] != list(CAMPAIGN_PROFILES):
		raise CampaignContractError("preregistration topology is invalid")
	if any(type(item) is not int for item in cast(list[object], topology["worker_counts"])) or not isinstance(topology["docker_project"], str) or re.fullmatch(r"[a-z0-9][a-z0-9_-]*", cast(str, topology["docker_project"])) is None:
		raise CampaignContractError("preregistration topology types are invalid")
	oracle_policies = core["oracle_policies"]
	if not isinstance(oracle_policies, Mapping) or set(oracle_policies) != set(CAMPAIGN_WORKLOADS):
		raise CampaignContractError("preregistration oracle policies are invalid")
	if type(core["tolerance_version"]) is not str or not cast(str, core["tolerance_version"]) or cast(str, core["tolerance_version"]).strip() != core["tolerance_version"] or "\x00" in cast(str, core["tolerance_version"]):
		raise CampaignContractError("preregistration tolerance version is invalid")
	for workload in CAMPAIGN_WORKLOADS:
		policy = oracle_policies[workload]
		if not isinstance(policy, Mapping) or set(policy) != {
			"version", "policy_sha256", "self_drift_a_sha256", "self_drift_b_sha256",
		} or policy["version"] != core["tolerance_version"]:
			raise CampaignContractError("preregistration oracle policy schema/version is invalid")
		for name in ("policy_sha256", "self_drift_a_sha256", "self_drift_b_sha256"):
			_sha256_text(f"{workload} {name}", policy[name])
		if policy["self_drift_a_sha256"] != policy["self_drift_b_sha256"]:
			raise CampaignContractError("preregistration oracle self-drift is invalid")
		oracle_record = cast(Mapping[str, object], cast(Mapping[str, object], artifacts["oracle_files"])[workload])
		if policy["policy_sha256"] != oracle_record["sha256"]:
			raise CampaignContractError("preregistration oracle artifact binding is invalid")
	jvm = core["jvm_settings"]
	if not isinstance(jvm, Mapping) or set(jvm) != {"java_opts", "heap_bytes", "coordinator_fresh"} or jvm["coordinator_fresh"] is not True or type(jvm["heap_bytes"]) is not int or cast(int, jvm["heap_bytes"]) < 1 or not isinstance(jvm["java_opts"], list) or not jvm["java_opts"] or any(not isinstance(item, str) or not item.strip() for item in jvm["java_opts"]):
		raise CampaignContractError("preregistration JVM settings are invalid")
	threads = core["thread_settings"]
	if not isinstance(threads, Mapping) or set(threads) != {"blas_threads", "omp_threads", "systemds_threads"} or any(type(item) is not int or cast(int, item) < 1 for item in threads.values()):
		raise CampaignContractError("preregistration thread settings are invalid")
	endpoints = core["endpoints"]
	if not isinstance(endpoints, Mapping) or set(endpoints) != set(ENDPOINT_NAMES) or any(not isinstance(item, str) or re.fullmatch(r"[A-Za-z0-9_.-]+:[1-9][0-9]{0,4}", item) is None or int(item.rsplit(":", 1)[1]) > 65535 for item in endpoints.values()) or len(set(endpoints.values())) != len(ENDPOINT_NAMES):
		raise CampaignContractError("preregistration endpoints are invalid")
	dimensions = manifest["dimensions"]
	expected_barriers = [
		{"planner": planner, "start": index * 84, "stop": (index + 1) * 84}
		for index, planner in enumerate(CAMPAIGN_PLANNERS)
	]
	if not isinstance(dimensions, Mapping) or set(dimensions) != {
		"block_ids", "cell_ids", "planner_major_barriers",
	}:
		raise CampaignContractError("preregistration dimensions schema is not exact")
	validate_campaign_matrix(cast(Sequence[str], dimensions["block_ids"]), cast(Sequence[str], dimensions["cell_ids"]))
	barriers = dimensions["planner_major_barriers"]
	if not isinstance(barriers, list) or any(
		not isinstance(item, Mapping) or set(item) != {"planner", "start", "stop"}
		or type(item["start"]) is not int or type(item["stop"]) is not int
		for item in barriers
	):
		raise CampaignContractError("preregistration planner-major barrier types are not exact")
	if dimensions["planner_major_barriers"] != expected_barriers:
		raise CampaignContractError("preregistration planner-major barriers are not exact")
	pilot = manifest["pilot_preregistration"]
	expected_pilot = {
		"row_count": 120, "repeats": 5, "schedule_seed": 19,
		"pilot_classes": list(PILOT_CLASSES), "planners": list(CAMPAIGN_PLANNERS),
		"regimes": [{"workers": workers, "profile": profile} for workers, profile in PILOT_REGIMES],
		"orders": build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19),
		"representative_workloads": dict(PILOT_REPRESENTATIVE_WORKLOADS),
	}
	if not isinstance(pilot, Mapping) or type(pilot.get("row_count")) is not int or type(pilot.get("repeats")) is not int or type(pilot.get("schedule_seed")) is not int:
		raise CampaignContractError("preregistration pilot numeric types are not exact")
	regimes = pilot.get("regimes")
	if not isinstance(regimes, list) or any(
		not isinstance(item, Mapping) or set(item) != {"workers", "profile"} or type(item["workers"]) is not int
		for item in regimes
	):
		raise CampaignContractError("preregistration pilot regime types are not exact")
	if pilot != expected_pilot:
		raise CampaignContractError("preregistration pilot seed/schedule contract is not exact")
	resource = manifest["resource_settings"]
	if not isinstance(resource, Mapping) or set(resource) != {
		"absolute_disk_floor_bytes", "required_free_inodes", "wall_time_seconds", "max_io_utilization",
		"max_combined_io_bps",
	} or resource["absolute_disk_floor_bytes"] != 5 * 1024**3:
		raise CampaignContractError("preregistration resource contract is not exact")
	if type(resource["absolute_disk_floor_bytes"]) is not int or type(resource["required_free_inodes"]) is not int or cast(int, resource["required_free_inodes"]) < 1:
		raise CampaignContractError("preregistration resource integer types are not exact")
	_positive_finite("preregistration wall time", resource["wall_time_seconds"])
	io_limit = _positive_finite("preregistration I/O utilization", resource["max_io_utilization"])
	if io_limit > 1:
		raise CampaignContractError("preregistration I/O utilization is invalid")
	_positive_finite("preregistration combined I/O", resource["max_combined_io_bps"])
	commands = manifest["commands"]
	if not isinstance(commands, Mapping) or set(commands) != set(COMMAND_SURFACES) or any(
		not isinstance(argv, list) or not argv or any(
			type(arg) is not str or not arg or arg.strip() != arg or "\x00" in arg for arg in argv
		)
		for argv in commands.values()
	):
		raise CampaignContractError("preregistration command surfaces are not exact")
	bounds = manifest["conservative_pre_pilot_bounds"]
	if not isinstance(bounds, Mapping) or set(bounds) != _CONSERVATIVE_BOUND_FIELDS:
		raise CampaignContractError("preregistration conservative bounds are invalid")
	for name in ("remaining_lifecycles", "p95_artifact_bytes", "p95_artifact_inodes"):
		if type(bounds[name]) is not int or cast(int, bounds[name]) < 1:
			raise CampaignContractError("preregistration conservative bounds are invalid")
	_positive_finite("preregistration p95 lifecycle", bounds["p95_lifecycle_seconds"])
	return manifest


def build_canonical_discovery_invocation(
	preregistration_manifest: Mapping[str, object], cell: str,
) -> dict[str, object]:
	"""Derive the only campaign discovery invocation identity accepted for one P/cell."""
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	return _canonical_discovery_invocation_from_validated(prereg, cell)


def _canonical_discovery_invocation_from_validated(
	prereg: Mapping[str, object], cell: str,
) -> dict[str, object]:
	if type(cell) is not str or cell not in campaign_cell_ids():
		raise CampaignContractError("discovery invocation cell is not an exact campaign cell")
	commands = cast(Mapping[str, object], prereg["commands"])
	return {
		"schema": "systemds-federated-discovery-invocation/v1",
		"kind": "discovery",
		"cell": cell,
		"preregistration_manifest_sha256": prereg["preregistration_manifest_sha256"],
		"execution_surface": "docker-only",
		"command": _canonical_json_value("discovery campaign command", commands["campaign"]),
	}


def build_canonical_discovery_invocation_hashes(
	preregistration_manifest: Mapping[str, object],
) -> dict[str, str]:
	"""Derive all exact per-cell discovery invocation hashes with one P validation."""
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	return {
		cell: _canonical_sha256(_canonical_discovery_invocation_from_validated(prereg, cell))
		for cell in campaign_cell_ids()
	}


def build_discovery_completion_receipt(
	*, preregistration_manifest: Mapping[str, object], discovery_rows: Sequence[Mapping[str, object]],
	evidence_validator: Callable[[Mapping[str, object]], None],
) -> dict[str, object]:
	"""Create D only after all 336 canonical latest discovery successes revalidate."""
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	if not callable(evidence_validator) or len(discovery_rows) != 336:
		raise CampaignContractError("discovery completion requires 336 revalidated rows")
	expected_cells = list(campaign_cell_ids())
	expected_invocations = {
		cell: _canonical_sha256(_canonical_discovery_invocation_from_validated(prereg, cell))
		for cell in expected_cells
	}
	canonical_rows: list[object] = []
	seen: set[str] = set()
	for expected_cell, row in zip(expected_cells, discovery_rows):
		if not isinstance(row, Mapping) or set(row) != {
			"cell", "identity", "invocation_manifest_sha256", "evidence_status", "evidence_sha256", "evidence_location",
		} or row["cell"] != expected_cell:
			raise CampaignContractError("discovery completion rows are missing, duplicated, or reordered")
		identity = row["identity"]
		if not isinstance(identity, Mapping) or set(identity) != {
			"kind", "cell", "attempt", "run_token", "manifest_hash",
		} or identity.get("kind") != "discovery" or identity.get("cell") != expected_cell:
			raise CampaignContractError("discovery completion identity is not canonical")
		if type(identity.get("attempt")) is not int or cast(int, identity["attempt"]) < 1:
			raise CampaignContractError("discovery completion attempt is invalid")
		if identity.get("manifest_hash") != prereg["preregistration_manifest_sha256"]:
			raise CampaignContractError("discovery completion mixes preregistration lineage")
		expected_invocation_hash = expected_invocations[expected_cell]
		if row["invocation_manifest_sha256"] != expected_invocation_hash:
			raise CampaignContractError("discovery completion invocation does not match P-derived identity")
		digest = _sha256_text("discovery evidence", row["evidence_sha256"])
		if digest in seen or row["evidence_status"] not in ("committed", "archive"):
			raise CampaignContractError("discovery completion evidence is duplicate or unverified")
		seen.add(digest)
		try:
			evidence_validator(row)
		except Exception as error:
			raise CampaignContractError("discovery completion latest-success revalidation failed") from error
		canonical_rows.append(_canonical_json_value("discovery completion row", row))
	receipt: dict[str, object] = {
		"schema": "systemds-federated-discovery-completion/v2",
		"preregistration_manifest_sha256": prereg["preregistration_manifest_sha256"],
		"cell_count": 336,
		"planner_major_barriers": cast(Mapping[str, object], prereg["dimensions"])["planner_major_barriers"],
		"discovery_rows": canonical_rows,
		"discovery_rows_sha256": _canonical_sha256(canonical_rows),
	}
	receipt["discovery_completion_sha256"] = _canonical_sha256(receipt)
	return receipt


def validate_discovery_completion_receipt(
	value: Mapping[str, object], *, evidence_validator: Callable[[Mapping[str, object]], None],
	preregistration_manifest: Mapping[str, object] | None = None,
) -> dict[str, object]:
	"""Validate D semantics and freshly revalidate every latest success."""
	if not callable(evidence_validator):
		raise CampaignContractError("discovery completion validation requires a live evidence validator")
	receipt = cast(dict[str, object], _canonical_json_value("discovery completion", value))
	if set(receipt) != {
		"schema", "preregistration_manifest_sha256", "cell_count", "planner_major_barriers",
		"discovery_rows", "discovery_rows_sha256", "discovery_completion_sha256",
	} or receipt.get("schema") != "systemds-federated-discovery-completion/v2":
		raise CampaignContractError("discovery completion schema is not exact")
	digest = _sha256_text("discovery completion", receipt["discovery_completion_sha256"])
	unsigned = dict(receipt); unsigned.pop("discovery_completion_sha256")
	if _canonical_sha256(unsigned) != digest:
		raise CampaignContractError("discovery completion self-hash is invalid")
	if preregistration_manifest is None:
		prereg_hash = _sha256_text("discovery preregistration", receipt["preregistration_manifest_sha256"])
		expected_barriers = [
			{"planner": planner, "start": index * 84, "stop": (index + 1) * 84}
			for index, planner in enumerate(CAMPAIGN_PLANNERS)
		]
		if receipt["cell_count"] != 336 or receipt["planner_major_barriers"] != expected_barriers:
			raise CampaignContractError("discovery completion cell count is not exact")
		rows = receipt["discovery_rows"]
		if not isinstance(rows, list) or len(rows) != 336:
			raise CampaignContractError("discovery completion rows are not exact")
		if _canonical_sha256(rows) != receipt["discovery_rows_sha256"]:
			raise CampaignContractError("discovery completion row hash is invalid")
		# Minimal synthetic P binding is intentionally not accepted; callers with P use the builder-equivalence path below.
		seen: set[str] = set()
		for row, cell in zip(rows, campaign_cell_ids()):
			if not isinstance(row, Mapping) or set(row) != {
				"cell", "identity", "invocation_manifest_sha256", "evidence_status", "evidence_sha256", "evidence_location",
			} or row.get("cell") != cell:
				raise CampaignContractError("discovery completion canonical order is invalid")
			identity = row.get("identity")
			if not isinstance(identity, Mapping) or set(identity) != {
				"kind", "cell", "attempt", "run_token", "manifest_hash",
			} or identity.get("kind") != "discovery" or identity.get("cell") != cell:
				raise CampaignContractError("discovery completion identity is invalid")
			if type(identity.get("attempt")) is not int or cast(int, identity["attempt"]) < 1 or identity.get("manifest_hash") != prereg_hash:
				raise CampaignContractError("discovery completion lineage is invalid")
			_sha256_text("discovery completion invocation", row["invocation_manifest_sha256"])
			row_digest = _sha256_text("discovery completion evidence", row.get("evidence_sha256"))
			if row_digest in seen or row.get("evidence_status") not in ("committed", "archive"):
				raise CampaignContractError("discovery completion evidence is duplicate or unverified")
			seen.add(row_digest)
			try:
				evidence_validator(row)
			except Exception as error:
				raise CampaignContractError("discovery completion latest-success revalidation failed") from error
		return receipt
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	if receipt["preregistration_manifest_sha256"] != prereg["preregistration_manifest_sha256"]:
		raise CampaignContractError("discovery completion does not bind exact preregistration")
	rebuilt = build_discovery_completion_receipt(
		preregistration_manifest=prereg,
		discovery_rows=cast(Sequence[Mapping[str, object]], receipt["discovery_rows"]),
		evidence_validator=evidence_validator,
	)
	if rebuilt != receipt:
		raise CampaignContractError("discovery completion is not the canonical builder result")
	return receipt


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
	if (
		set(privacy_settings) != {"public_tests_ignored", "runtime_fallback_allowed"}
		or privacy_settings["public_tests_ignored"] is not True
		or privacy_settings["runtime_fallback_allowed"] is not False
	):
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
	*, expected_manifest_hash: str,
	preregistration_manifest: Mapping[str, object], discovery_completion_receipt: Mapping[str, object],
	discovery_evidence_validator: Callable[[Mapping[str, object]], None],
) -> dict[str, object]:
	"""Select one frozen repeat count from the preregistered cross-campaign pilot."""
	required = {
		"pilot_class", "workload", "planner", "workers", "profile", "cell", "pilot_repeat", "warm_seconds",
		"period", "order", "carryover", "host_load", "lifecycle", "evidence_status", "evidence_sha256",
		"identity", "evidence_location", "invocation_manifest_sha256", "resource_evidence",
	}
	if not callable(evidence_validator):
		raise CampaignContractError("campaign pilot requires an evidence revalidation callback")
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	completion = validate_discovery_completion_receipt(
		discovery_completion_receipt, preregistration_manifest=prereg,
		evidence_validator=discovery_evidence_validator,
	)
	_sha256_text("expected campaign manifest", expected_manifest_hash)
	preregistration_sha256 = cast(str, prereg["preregistration_manifest_sha256"])
	if preregistration_sha256 != expected_manifest_hash:
		raise CampaignContractError("pilot manifest identity must equal the preregistration manifest identity")
	if len(rows) != len(PILOT_CLASSES) * len(CAMPAIGN_PLANNERS) * len(PILOT_REGIMES) * 5:
		raise CampaignContractError("campaign pilot requires the exact preregistered 120-row set")
	groups: dict[tuple[str, str, int, str, str], list[Mapping[str, object]]] = {}
	for row in rows:
		if set(row) != required:
			raise CampaignContractError("campaign pilot row schema is not exact")
		pilot_class, workload, planner, workers, profile, cell = (
			row["pilot_class"], row["workload"], row["planner"], row["workers"], row["profile"], row["cell"]
		)
		pilot_repeat, period = row["pilot_repeat"], row["period"]
		if (
			type(workers) is not int or type(pilot_repeat) is not int or pilot_repeat not in range(1, 6)
			or type(period) is not int or period not in range(1, len(CAMPAIGN_PLANNERS) + 1)
			or pilot_class not in PILOT_CLASSES or workload != PILOT_REPRESENTATIVE_WORKLOADS.get(cast(str, pilot_class))
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
	canonical_rows: list[object] = []
	evidence_inventory: list[dict[str, object]] = []
	ordered_group_keys = [
		(
			pilot_class, planner, workers, profile,
			f"pilot_class={pilot_class}|workload={PILOT_REPRESENTATIVE_WORKLOADS[pilot_class]}|planner={planner}|workers={workers}|profile={profile}",
		)
		for pilot_class in PILOT_CLASSES
		for planner in CAMPAIGN_PLANNERS
		for workers, profile in PILOT_REGIMES
	]
	for key in ordered_group_keys:
		group = groups[key]
		ordered = sorted(group, key=lambda row: int(cast(int, row["pilot_repeat"])))
		if [row["pilot_repeat"] for row in ordered] != [1, 2, 3, 4, 5]:
			raise CampaignContractError("each campaign pilot group requires exact repeats 1..5")
		values: list[float] = []
		for repeat_index, row in enumerate(ordered):
			identity_value = row["identity"]
			if not isinstance(identity_value, Mapping) or set(identity_value) != {
				"kind", "cell", "lifecycle_replicate", "period", "order", "manifest_hash", "attempt", "run_token",
			}:
				raise CampaignContractError("campaign pilot identity is invalid")
			if (
				identity_value.get("kind") != "performance"
				or identity_value.get("cell") != row["cell"]
				or type(identity_value.get("attempt")) is not int or cast(int, identity_value["attempt"]) < 1
				or type(identity_value.get("lifecycle_replicate")) is not int
				or identity_value.get("lifecycle_replicate") != row["pilot_repeat"]
				or type(identity_value.get("period")) is not int or identity_value.get("period") != row["period"]
				or identity_value.get("order") != row["order"]
				or not isinstance(identity_value.get("run_token"), str) or not cast(str, identity_value["run_token"])
			):
				raise CampaignContractError("campaign pilot evidence schedule identity is invalid")
			if identity_value.get("manifest_hash") != expected_manifest_hash:
				raise CampaignContractError("campaign pilot mixes frozen campaign manifests")
			expected_invocation = build_canonical_pilot_invocation(
				preregistration_manifest_sha256=preregistration_sha256,
				discovery_completion_sha256=cast(str, completion["discovery_completion_sha256"]),
				pilot_class=key[0], planner=key[1], workers=key[2], profile=key[3],
				pilot_repeat=cast(int, row["pilot_repeat"]),
			)
			if row["invocation_manifest_sha256"] != _canonical_sha256(expected_invocation):
				raise CampaignContractError("campaign pilot invocation is not its exact P/D-derived typed identity")
			if row["evidence_status"] not in ("committed", "archive"):
				raise CampaignContractError("campaign pilot rows require verified evidence")
			location = row["evidence_location"]
			if not isinstance(location, Mapping) or (
				row["evidence_status"] == "committed" and set(location) != {"committed_path"}
			) or (
				row["evidence_status"] == "archive" and set(location) != {"archive_uri", "archive_sha256"}
			):
				raise CampaignContractError("campaign pilot evidence location/status schema is not exact")
			evidence_digest = _sha256_text("campaign pilot evidence", row["evidence_sha256"])
			if evidence_digest in seen_evidence:
				raise CampaignContractError("campaign pilot evidence checksum is duplicated")
			seen_evidence.add(evidence_digest)
			canonical_row = _canonical_json_value("campaign pilot row", row)
			canonical_rows.append(canonical_row)
			evidence_inventory.append({
				"cell": row["cell"], "pilot_repeat": row["pilot_repeat"],
				"evidence_status": row["evidence_status"], "evidence_sha256": evidence_digest,
				"evidence_location": _canonical_json_value("campaign pilot evidence location", row["evidence_location"]),
				"resource_evidence": _validate_pilot_resource_evidence(row["resource_evidence"]),
			})
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
					if diagnostic_name == "lifecycle" and name in ("coordinator_restart_count", "worker_restart_count"):
						continue
					_positive_finite(f"campaign pilot {diagnostic_name}.{name}", diagnostic_value_item, allow_zero=True)
			if cast(Mapping[str, object], row["lifecycle"])["warm_seconds"] != row["warm_seconds"]:
				raise CampaignContractError("campaign pilot lifecycle warm_seconds disagrees with measured timing")
			if any(
				type(cast(Mapping[str, object], row["lifecycle"])[name]) is not int
				or cast(Mapping[str, object], row["lifecycle"])[name] != 0
				for name in ("coordinator_restart_count", "worker_restart_count")
			):
				raise CampaignContractError("successful campaign pilot lifecycle restart counts must be zero")
			expected_order_tuple = preregistered_orders[repeat_index]
			expected_order = ">".join(expected_order_tuple)
			expected_period = expected_order_tuple.index(key[1]) + 1
			expected_carryover = "NONE" if expected_period == 1 else expected_order_tuple[expected_period - 2]
			if row["order"] != expected_order or row["period"] != expected_period or row["carryover"] != expected_carryover:
				raise CampaignContractError("campaign pilot schedule period/order/carryover is not preregistered")
			try:
				evidence_validator(row)
			except Exception as error:
				raise CampaignContractError("campaign pilot evidence revalidation failed") from error
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
	receipt: dict[str, object] = {
		"schema": "systemds-federated-campaign-pilot/v4", "selection_rule": "eta=max(log(1.02),Q95(abs(log(t/median_group))))",
		"preregistration_manifest_sha256": preregistration_sha256,
		"discovery_completion_sha256": completion["discovery_completion_sha256"],
		"preregistration": {
			"row_count": 120, "pilot_classes": list(PILOT_CLASSES), "planners": list(CAMPAIGN_PLANNERS),
			"regimes": [{"workers": workers, "profile": profile} for workers, profile in PILOT_REGIMES],
			"orders": [list(order) for order in preregistered_orders],
			"representative_workloads": dict(PILOT_REPRESENTATIVE_WORKLOADS),
			"manifest_hash": expected_manifest_hash,
			"invocation_contract": "per-row P/D-derived typed identity v1",
		},
		"pilot_rows": canonical_rows, "pilot_rows_sha256": _canonical_sha256(canonical_rows),
		"evidence_digest_inventory": evidence_inventory,
		"q95": q95, "eta": eta, "selected_repeats": selected, "diagnostics": diagnostics,
	}
	receipt["pilot_selection_sha256"] = _canonical_sha256(receipt)
	return receipt


def _validate_pilot_resource_evidence(value: object) -> dict[str, float | int]:
	if not isinstance(value, Mapping) or set(value) != {
		"artifact_bytes", "artifact_inodes", "lifecycle_wall_seconds",
	}:
		raise CampaignContractError("pilot resource evidence schema is not exact")
	result: dict[str, float | int] = {}
	for name in ("artifact_bytes", "artifact_inodes"):
		item = value[name]
		if type(item) is not int or cast(int, item) < 1:
			raise CampaignContractError(f"pilot resource evidence {name} must be a positive integer")
		result[name] = cast(int, item)
	result["lifecycle_wall_seconds"] = _positive_finite(
		"pilot resource evidence lifecycle_wall_seconds", value["lifecycle_wall_seconds"]
	)
	return result


def _validate_campaign_pilot_selection(
	value: Mapping[str, object], *, preregistration_manifest: Mapping[str, object],
	discovery_completion_receipt: Mapping[str, object],
	pilot_evidence_validator: Callable[[Mapping[str, object]], None],
	discovery_evidence_validator: Callable[[Mapping[str, object]], None],
) -> dict[str, object]:
	"""Recompute S from its signed 120 canonical rows and require exact builder equality."""
	if not callable(pilot_evidence_validator) or not callable(discovery_evidence_validator):
		raise CampaignContractError("pilot selection validation requires live evidence validators")
	selection = cast(dict[str, object], _canonical_json_value("pilot selection", value))
	expected_fields = {
		"schema", "selection_rule", "preregistration_manifest_sha256", "discovery_completion_sha256",
		"preregistration", "pilot_rows", "pilot_rows_sha256", "evidence_digest_inventory", "q95", "eta",
		"selected_repeats", "diagnostics", "pilot_selection_sha256",
	}
	if set(selection) != expected_fields or selection.get("schema") != "systemds-federated-campaign-pilot/v4":
		raise CampaignContractError("pilot selection schema is not exact")
	digest = _sha256_text("pilot selection", selection["pilot_selection_sha256"])
	unsigned = dict(selection); unsigned.pop("pilot_selection_sha256")
	if _canonical_sha256(unsigned) != digest:
		raise CampaignContractError("pilot selection self-hash is invalid")
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	completion = validate_discovery_completion_receipt(
		discovery_completion_receipt, preregistration_manifest=prereg,
		evidence_validator=discovery_evidence_validator,
	)
	if (
		selection["preregistration_manifest_sha256"] != prereg["preregistration_manifest_sha256"]
		or selection["discovery_completion_sha256"] != completion["discovery_completion_sha256"]
	):
		raise CampaignContractError("pilot selection lineage is invalid")
	preregistration = selection["preregistration"]
	if not isinstance(preregistration, Mapping):
		raise CampaignContractError("pilot selection preregistration is invalid")
	if preregistration.get("invocation_contract") != "per-row P/D-derived typed identity v1":
		raise CampaignContractError("pilot selection invocation contract is invalid")
	rows = selection["pilot_rows"]
	if not isinstance(rows, list) or len(rows) != 120 or _canonical_sha256(rows) != selection["pilot_rows_sha256"]:
		raise CampaignContractError("pilot selection rows are not exact")
	rebuilt = select_campaign_pilot_repeats(
		cast(Sequence[Mapping[str, object]], rows), pilot_evidence_validator,
		expected_manifest_hash=cast(str, prereg["preregistration_manifest_sha256"]),
		preregistration_manifest=prereg, discovery_completion_receipt=completion,
		discovery_evidence_validator=discovery_evidence_validator,
	)
	if rebuilt != selection:
		raise CampaignContractError("pilot selection is not the canonical builder result")
	return selection


def _build_pilot_resource_reservation(
	*, pilot_selection_receipt: Mapping[str, object], preregistration_manifest: Mapping[str, object],
	discovery_completion_receipt: Mapping[str, object],
	pilot_evidence_validator: Callable[[Mapping[str, object]], None],
	discovery_evidence_validator: Callable[[Mapping[str, object]], None],
) -> dict[str, object]:
	"""Derive R from the exact revalidated evidence inventory committed by selection S."""
	selection = _validate_campaign_pilot_selection(
		pilot_selection_receipt, preregistration_manifest=preregistration_manifest,
		discovery_completion_receipt=discovery_completion_receipt,
		pilot_evidence_validator=pilot_evidence_validator,
		discovery_evidence_validator=discovery_evidence_validator,
	)
	selection_hash = selection["pilot_selection_sha256"]
	prereg_hash = _sha256_text("pilot preregistration", selection.get("preregistration_manifest_sha256"))
	rows_hash = _sha256_text("pilot rows", selection.get("pilot_rows_sha256"))
	inventory = selection.get("evidence_digest_inventory")
	if not isinstance(inventory, list) or len(inventory) != 120:
		raise CampaignContractError("pilot resource reservation requires exact 120-row evidence inventory")
	resources: list[dict[str, float | int]] = []
	for item in inventory:
		if not isinstance(item, Mapping) or set(item) != {
			"cell", "pilot_repeat", "evidence_status", "evidence_sha256", "evidence_location", "resource_evidence",
		}:
			raise CampaignContractError("pilot resource inventory row schema is not exact")
		_sha256_text("pilot resource inventory evidence", item["evidence_sha256"])
		resources.append(_validate_pilot_resource_evidence(item["resource_evidence"]))
	def nearest_rank_p95(name: str) -> float | int:
		values = sorted(resource[name] for resource in resources)
		return values[math.ceil(0.95 * len(values)) - 1]
	reservation: dict[str, object] = {
		"schema": "g007-pilot-resource-reservation/v3",
		"preregistration_manifest_sha256": prereg_hash,
		"discovery_completion_sha256": selection["discovery_completion_sha256"],
		"pilot_selection_sha256": selection_hash,
		"pilot_rows_sha256": rows_hash,
		"evidence_inventory_sha256": _canonical_sha256(inventory),
		"sample_count": 120,
		"p95_artifact_bytes": nearest_rank_p95("artifact_bytes"),
		"p95_artifact_inodes": nearest_rank_p95("artifact_inodes"),
		"p95_lifecycle_seconds": nearest_rank_p95("lifecycle_wall_seconds"),
		"margin": 1.20, "absolute_disk_floor_bytes": 5 * 1024**3,
	}
	reservation["payload_sha256"] = _canonical_sha256(reservation)
	return reservation


def _build_final_campaign_manifest(
	*,
	preregistration_manifest: Mapping[str, object],
	pilot_selection_receipt: Mapping[str, object],
	pilot_resource_reservation: Mapping[str, object],
	discovery_completion_receipt: Mapping[str, object],
	pilot_evidence_validator: Callable[[Mapping[str, object]], None],
	discovery_evidence_validator: Callable[[Mapping[str, object]], None],
) -> dict[str, object]:
	"""Build final manifest F solely from P, its pilot receipt, and measured reservation."""
	prereg = validate_campaign_preregistration_manifest(preregistration_manifest)
	prereg_hash = cast(str, prereg["preregistration_manifest_sha256"])
	completion = validate_discovery_completion_receipt(
		discovery_completion_receipt, preregistration_manifest=prereg,
		evidence_validator=discovery_evidence_validator,
	)
	selection = _validate_campaign_pilot_selection(
		pilot_selection_receipt, preregistration_manifest=prereg,
		discovery_completion_receipt=completion,
		pilot_evidence_validator=pilot_evidence_validator,
		discovery_evidence_validator=discovery_evidence_validator,
	)
	selection_hash = cast(str, selection["pilot_selection_sha256"])
	selected = cast(int, selection["selected_repeats"])
	lineage_value = cast(dict[str, object], prereg["lineage"])

	reservation = cast(dict[str, object], _canonical_json_value("pilot resource reservation", pilot_resource_reservation))
	expected_reservation = _build_pilot_resource_reservation(
		pilot_selection_receipt=selection, preregistration_manifest=prereg,
		discovery_completion_receipt=completion,
		pilot_evidence_validator=pilot_evidence_validator,
		discovery_evidence_validator=discovery_evidence_validator,
	)
	if reservation != expected_reservation:
		raise CampaignContractError("pilot resource reservation is not the exact canonical derivation from S")
	reservation_hash = cast(str, reservation["payload_sha256"])

	dimensions = cast(dict[str, object], prereg["dimensions"])
	blocks = cast(list[str], dimensions["block_ids"])
	seed_streams = cast(dict[str, object], prereg["seed_streams"])
	validated_schedule = build_block_counterbalanced_schedule(
		CAMPAIGN_PLANNERS, cast(int, selected), blocks, cast(int, seed_streams["schedule"])
	)
	final_lineage = {
		"preregistration_manifest_sha256": prereg_hash,
		"pilot_selection_sha256": selection_hash,
		"discovery_completion_sha256": completion["discovery_completion_sha256"],
		"stage_descriptor_sha256": lineage_value["stage_descriptor_sha256"],
		"cp_lifecycle_descriptor_sha256": lineage_value["cp_lifecycle_descriptor_sha256"],
		"reference_manifest_sha256": lineage_value["reference_manifest_sha256"],
		"pilot_resource_reservation_sha256": reservation_hash,
	}
	manifest: dict[str, object] = {
		"schema": "systemds-federated-docker-campaign/v4", "execution_surface": "docker-only",
		"lineage": final_lineage,
		"frozen_core": prereg["frozen_core"], "seed_streams": prereg["seed_streams"],
		"resource_settings": prereg["resource_settings"], "commands": prereg["commands"],
		"dimensions": prereg["dimensions"], "pilot_preregistration": prereg["pilot_preregistration"],
		"conservative_pre_pilot_bounds": prereg["conservative_pre_pilot_bounds"],
		"pilot_resource_reservation": {
			"p95_artifact_bytes": reservation["p95_artifact_bytes"],
			"p95_artifact_inodes": reservation["p95_artifact_inodes"],
			"p95_lifecycle_seconds": reservation["p95_lifecycle_seconds"],
		},
		"selected_repeats": selected, "schedule": validated_schedule,
	}
	manifest["manifest_hash"] = _canonical_sha256(manifest)
	return manifest


def build_canonical_pilot_invocation(
	*, preregistration_manifest_sha256: str, discovery_completion_sha256: str,
	pilot_class: str, planner: str, workers: int, profile: str, pilot_repeat: int,
) -> dict[str, object]:
	"""Derive the complete pilot identity; callers cannot supply a phase manifest."""
	prereg_hash = _sha256_text("pilot preregistration", preregistration_manifest_sha256)
	discovery_hash = _sha256_text("pilot discovery completion", discovery_completion_sha256)
	if type(pilot_class) is not str or pilot_class not in PILOT_CLASSES:
		raise CampaignContractError("pilot class is not canonical")
	if type(planner) is not str or planner not in CAMPAIGN_PLANNERS:
		raise CampaignContractError("pilot planner is not canonical")
	if type(workers) is not int or type(profile) is not str or (workers, profile) not in PILOT_REGIMES:
		raise CampaignContractError("pilot regime is not canonical")
	if type(pilot_repeat) is not int or pilot_repeat not in range(1, 6):
		raise CampaignContractError("pilot repeat must be exact integer 1..5")
	workload = PILOT_REPRESENTATIVE_WORKLOADS[pilot_class]
	order = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)[pilot_repeat - 1]
	return {
		"schema": "systemds-federated-pilot-invocation/v1", "kind": "pilot",
		"preregistration_manifest_sha256": prereg_hash,
		"discovery_completion_sha256": discovery_hash,
		"pilot_class": pilot_class, "workload": workload, "planner": planner,
		"workers": workers, "profile": profile, "pilot_repeat": pilot_repeat,
		"period": order.index(planner) + 1, "order": ">".join(order),
	}


def validate_final_campaign_manifest(value: Mapping[str, object]) -> dict[str, object]:
	"""Semantically validate a self-sealed final manifest before allocating work."""
	manifest = cast(dict[str, object], _canonical_json_value("final campaign manifest", value))
	expected_fields = {
		"schema", "execution_surface", "lineage", "frozen_core", "seed_streams",
		"resource_settings", "commands", "dimensions", "pilot_preregistration",
		"conservative_pre_pilot_bounds", "pilot_resource_reservation", "selected_repeats",
		"schedule", "manifest_hash",
	}
	if set(manifest) != expected_fields or manifest.get("schema") != "systemds-federated-docker-campaign/v4":
		raise CampaignContractError("final campaign manifest v4 schema is not exact")
	if manifest.get("execution_surface") != "docker-only":
		raise CampaignContractError("final campaign execution surface must be docker-only")
	digest = _sha256_text("final campaign manifest", manifest["manifest_hash"])
	unsigned = dict(manifest); unsigned.pop("manifest_hash")
	if _canonical_sha256(unsigned) != digest:
		raise CampaignContractError("final campaign manifest self-hash is invalid")
	lineage = manifest["lineage"]
	lineage_fields = {
		"preregistration_manifest_sha256", "pilot_selection_sha256", "discovery_completion_sha256",
		"stage_descriptor_sha256", "cp_lifecycle_descriptor_sha256", "reference_manifest_sha256",
		"pilot_resource_reservation_sha256",
	}
	if not isinstance(lineage, Mapping) or set(lineage) != lineage_fields:
		raise CampaignContractError("final campaign lineage schema is not exact")
	for name, item in lineage.items():
		_sha256_text(f"final campaign lineage {name}", item)
	preregistration = {
		"schema": "systemds-federated-docker-campaign-preregistration/v3",
		"execution_surface": manifest["execution_surface"],
		"lineage": {name: lineage[name] for name in (
			"stage_descriptor_sha256", "cp_lifecycle_descriptor_sha256", "reference_manifest_sha256",
		)},
		"frozen_core": manifest["frozen_core"], "seed_streams": manifest["seed_streams"],
		"resource_settings": manifest["resource_settings"], "commands": manifest["commands"],
		"dimensions": manifest["dimensions"], "pilot_preregistration": manifest["pilot_preregistration"],
		"conservative_pre_pilot_bounds": manifest["conservative_pre_pilot_bounds"],
		"preregistration_manifest_sha256": lineage["preregistration_manifest_sha256"],
	}
	validate_campaign_preregistration_manifest(preregistration)
	repeats = manifest["selected_repeats"]
	if type(repeats) is not int or repeats not in (3, 5, 7):
		raise CampaignContractError("final campaign repeats must be exact integer 3, 5, or 7")
	dimensions = cast(Mapping[str, object], manifest["dimensions"])
	seeds = cast(Mapping[str, object], manifest["seed_streams"])
	validate_block_counterbalanced_schedule(
		cast(dict[str, object], manifest["schedule"]), CAMPAIGN_PLANNERS, repeats,
		cast(int, seeds["schedule"]), cast(Sequence[str], dimensions["block_ids"]),
	)
	reservation = manifest["pilot_resource_reservation"]
	if not isinstance(reservation, Mapping) or set(reservation) != {
		"p95_artifact_bytes", "p95_artifact_inodes", "p95_lifecycle_seconds",
	}:
		raise CampaignContractError("final campaign resource reservation schema is not exact")
	for name in ("p95_artifact_bytes", "p95_artifact_inodes"):
		if type(reservation[name]) is not int or cast(int, reservation[name]) < 1:
			raise CampaignContractError(f"final campaign resource reservation {name} is invalid")
	_positive_finite("final campaign p95 lifecycle seconds", reservation["p95_lifecycle_seconds"])
	return manifest


def build_canonical_final_invocation(
	final_campaign_manifest: Mapping[str, object], cell: str, lifecycle_replicate: int,
) -> dict[str, object]:
	"""Derive a final-performance invocation from F and its frozen schedule."""
	manifest = validate_final_campaign_manifest(final_campaign_manifest)
	if type(cell) is not str or cell not in campaign_cell_ids():
		raise CampaignContractError("final campaign cell is not canonical")
	if type(lifecycle_replicate) is not int or lifecycle_replicate not in range(1, cast(int, manifest["selected_repeats"]) + 1):
		raise CampaignContractError("final campaign lifecycle replicate is not canonical")
	parts = dict(part.split("=", 1) for part in cell.split("|"))
	block_id = f"workers={parts['workers']}|workload={parts['workload']}|profile={parts['profile']}"
	schedule = cast(Mapping[str, object], manifest["schedule"])
	blocks = cast(Sequence[Mapping[str, object]], schedule["blocks"])
	block = next(item for item in blocks if item["block"] == block_id)
	runs = cast(Sequence[Mapping[str, object]], block["runs"])
	run = next(item for item in runs if item["lifecycle_replicate"] == lifecycle_replicate)
	periods = cast(Sequence[Mapping[str, object]], run["periods"])
	period = next(item["period"] for item in periods if item["planner"] == parts["planner"])
	return {
		"schema": "systemds-federated-final-invocation/v1", "kind": "final_performance",
		"manifest_hash": manifest["manifest_hash"], "cell": cell,
		"lifecycle_replicate": lifecycle_replicate, "period": period, "order": run["order"],
	}
