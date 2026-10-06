#!/usr/bin/env python3
"""Validate and summarize the multi-cohort pruning ablation campaign.

The original STEP-LM workload is intentionally kept as a failed, incompatible
stratum.  The compatible STEP-LM rerun is a distinct workload and is never
silently substituted for it.
"""

from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
from typing import Any, Iterable


VARIANTS = ("baseline", "dominance", "support", "local", "global")
REPETITIONS = (1, 2, 3)
TIMING_METRICS = (
	"full_initial_planning_seconds",
	"optimizer_seconds",
	"analysis_seconds",
	"compile_seconds",
)
RECEIPT_METRICS = (
	"upper", "lower", "gap", "rawValues", "reducedValues", "rawCells",
	"reducedCells", "childEvaluations", "fullChildEvaluations",
	"infeasibleCuts", "costCuts", "globalConsidered", "globalPruned",
	"globalFactors", "globalPrepNanos", "assignments", "retainedSlots", "merges",
)
WORK_METRICS = ("childEvaluations", "fullChildEvaluations", "assignments", "merges", "reducedValues", "reducedCells")
SHARED_IDENTITY_FIELDS = (
	"jar_sha256", "probe_sha256", "source_sha256", "dependencies_sha256",
	"image", "java", "cpu", "host", "memory", "cost_environment",
	"cost_profile_sha256", "cost_profile_file_sha256", "matrix_runner_sha256",
	"optimizer_time_millis",
)


class AnalysisError(RuntimeError):
	"""Raised when raw campaign evidence is incomplete or inconsistent."""


def canonical_bytes(value: Any) -> bytes:
	return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def sha256_bytes(value: bytes) -> str:
	return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
	hash_object = hashlib.sha256()
	with path.open("rb") as stream:
		for chunk in iter(lambda: stream.read(1024 * 1024), b""):
			hash_object.update(chunk)
	return hash_object.hexdigest()


def load_json(path: Path) -> Any:
	try:
		return json.loads(path.read_text())
	except (OSError, json.JSONDecodeError) as error:
		raise AnalysisError(f"cannot read JSON {path}: {error}") from error


def require(condition: bool, message: str) -> None:
	if not condition:
		raise AnalysisError(message)


def workload_of(sample: dict[str, Any]) -> str:
	try:
		return str(sample["cell"]["workload"])
	except (KeyError, TypeError) as error:
		raise AnalysisError(f"sample has no cell.workload: {sample.get('key', '<unknown>')}") from error


def logical_identity(cohort: str, workload: str, variant: str, repetition: int) -> tuple[str, str, str, int]:
	if cohort == "compatibility":
		require(workload == "steplm", f"compatibility cohort contains non-STEP workload {workload}")
		return ("steplm-compatible", "steplm-compatible", variant, repetition)
	if workload == "steplm":
		require(cohort == "study", "recovery cohort must not replace incompatible STEP-LM")
		return ("original-steplm-incompatible", "steplm-original-incompatible", variant, repetition)
	return ("original-nonstep", workload, variant, repetition)


def top_level_artifact_hashes(attempt_dir: Path) -> dict[str, str]:
	return {
		path.name: sha256_file(path)
		for path in sorted(attempt_dir.iterdir())
		if path.is_file()
	}


def load_cohort(label: str, root: Path) -> dict[str, Any]:
	manifest_path = root / "ablation.json"
	manifest = load_json(manifest_path)
	planned = manifest.get("identity", {}).get("samples")
	require(isinstance(planned, list), f"{label}: manifest identity.samples is missing")
	planned_by_key: dict[str, dict[str, Any]] = {}
	for cell in planned:
		key = cell.get("key")
		require(isinstance(key, str), f"{label}: planned sample has no key")
		require(key not in planned_by_key, f"{label}: duplicate planned key {key}")
		planned_by_key[key] = cell

	attempt_root = root / "attempts"
	require(attempt_root.is_dir(), f"{label}: missing attempts directory")
	attempt_dirs = sorted(path for path in attempt_root.iterdir() if path.is_dir())
	results: list[dict[str, Any]] = []
	seen_keys: set[str] = set()
	for attempt_dir in attempt_dirs:
		result_path = attempt_dir / "result.json"
		require(result_path.is_file(), f"{label}: unfinished attempt without result.json: {attempt_dir.name}")
		result = load_json(result_path)
		key = result.get("key")
		require(key in planned_by_key, f"{label}: unplanned result key {key}")
		require(key not in seen_keys, f"{label}: duplicate result for planned key {key}")
		seen_keys.add(key)
		planned_cell = planned_by_key[key]
		for field in ("variant", "repetition"):
			require(result.get(field) == planned_cell.get(field), f"{label}: {key} has inconsistent {field}")
		require(result.get("cell") == planned_cell.get("cell"), f"{label}: {key} has inconsistent cell")
		status = result.get("status")
		require(status in ("passed", "failed"), f"{label}: {key} has invalid status {status}")
		if status == "passed":
			for metric in TIMING_METRICS:
				require(isinstance(result.get(metric), (int, float)), f"{label}: {key} lacks {metric}")
			pruning = result.get("pruning")
			require(isinstance(pruning, list) and len(pruning) == 1,
				f"{label}: {key} must contain one pruning receipt")
			for field in RECEIPT_METRICS + ("objectiveBits", "planFingerprint", "stop", "globalOutcome"):
				require(field in pruning[0], f"{label}: {key} receipt lacks {field}")
		result["_cohort"] = label
		result["_attempt_dir"] = str(attempt_dir)
		result["_result_path"] = str(result_path)
		result["_result_sha256"] = sha256_file(result_path)
		result["_artifact_sha256"] = top_level_artifact_hashes(attempt_dir)
		results.append(result)

	require(seen_keys == set(planned_by_key),
		f"{label}: manifest/results differ: missing={sorted(set(planned_by_key) - seen_keys)}")
	return {
		"label": label,
		"root": root,
		"manifest": manifest,
		"manifest_path": manifest_path,
		"manifest_sha256": sha256_file(manifest_path),
		"planned_by_key": planned_by_key,
		"results": results,
	}


def validate_shared_identity(cohorts: Iterable[dict[str, Any]]) -> dict[str, Any]:
	cohorts = list(cohorts)
	base = cohorts[0]["manifest"].get("identity", {})
	shared: dict[str, Any] = {}
	for field in SHARED_IDENTITY_FIELDS:
		require(field in base, f"study manifest lacks shared identity field {field}")
		value = base[field]
		for cohort in cohorts[1:]:
			other = cohort["manifest"].get("identity", {}).get(field)
			require(canonical_bytes(other) == canonical_bytes(value),
				f"cross-cohort identity mismatch for {field}: study vs {cohort['label']}")
		shared[field] = value
	return shared


def validate_recovery_lineage(study: dict[str, Any], recovery: dict[str, Any]) -> None:
	study_results = {sample["key"]: sample for sample in study["results"]}
	for key in recovery["planned_by_key"]:
		require(key in study_results, f"recovery key was not planned in original study: {key}")
		original = study_results[key]
		retry = recovery["planned_by_key"][key]
		require(original["status"] == "failed", f"recovery retries non-failed original key: {key}")
		require(workload_of(original) != "steplm", f"recovery must not retry incompatible STEP-LM: {key}")
		for field in ("cell", "variant", "repetition"):
			require(retry.get(field) == original.get(field), f"recovery changes original {field}: {key}")


def validate_compatibility_lineage(study: dict[str, Any], compatibility: dict[str, Any]) -> None:
	study_results = {sample["key"]: sample for sample in study["results"]}
	for key, compatible in compatibility["planned_by_key"].items():
		require(key in study_results, f"compatible STEP-LM key was not in original study: {key}")
		original = study_results[key]
		require(original["status"] == "failed" and workload_of(original) == "steplm",
			f"compatible STEP-LM does not correspond to a failed original STEP-LM cell: {key}")
		# The renderer stage/template may differ, but the experiment cell itself must not.
		for field in ("cell", "variant", "repetition"):
			require(compatible.get(field) == original.get(field), f"compatible STEP-LM changes original {field}: {key}")


def render_identity(sample: dict[str, Any]) -> dict[str, str] | None:
	path = Path(sample["_attempt_dir"]) / "render-contract.json"
	if not path.is_file():
		require(sample["status"] != "passed", f"passed sample lacks render contract: {sample['key']}")
		return None
	contract = load_json(path)
	for field in ("source", "inputs", "dependencies"):
		require(field in contract, f"render contract lacks {field}: {path}")
	return {
		"source_sha256": sha256_bytes(str(contract["source"]).encode()),
		"inputs_sha256": sha256_bytes(canonical_bytes(contract["inputs"])),
		"dependencies_sha256": sha256_bytes(canonical_bytes(contract["dependencies"])),
		"combined_sha256": sha256_bytes(canonical_bytes({
			"source": contract["source"],
			"inputs": contract["inputs"],
			"dependencies": contract["dependencies"],
		})),
	}


def validate_rendered_inputs(samples: Iterable[dict[str, Any]]) -> dict[str, dict[str, str]]:
	by_workload: dict[tuple[str, str], list[dict[str, str]]] = collections.defaultdict(list)
	for sample in samples:
		stratum, workload, _, _ = sample["_logical_identity"]
		identity = render_identity(sample)
		if identity is not None:
			by_workload[stratum, workload].append(identity)
	validated: dict[str, dict[str, str]] = {}
	for (stratum, workload), identities in sorted(by_workload.items()):
		require(identities, f"no rendered evidence for {stratum}/{workload}")
		for field in ("source_sha256", "inputs_sha256", "dependencies_sha256", "combined_sha256"):
			values = {identity[field] for identity in identities}
			require(len(values) == 1,
				f"rendered {field} differs across variants/repetitions for {stratum}/{workload}")
		validated[f"{stratum}/{workload}"] = identities[0]
	return validated


def as_float(value: Any, context: str) -> float:
	try:
		return float(value)
	except (TypeError, ValueError) as error:
		raise AnalysisError(f"{context} is not numeric: {value!r}") from error


def positive_finite_cost(value: Any, context: str) -> float:
	cost = as_float(value, context)
	require(math.isfinite(cost) and cost > 0.0, f"{context} must be positive and finite, got {value!r}")
	return cost


def summarize(values: Iterable[float], prefix: str, row: dict[str, Any]) -> None:
	items = list(values)
	require(items, f"cannot summarize empty metric {prefix}")
	row[prefix] = statistics.median(items)
	row[prefix + "_min"] = min(items)
	row[prefix + "_max"] = max(items)


def geometric_mean(values: Iterable[float]) -> float:
	values = list(values)
	require(values and all(value > 0 and math.isfinite(value) for value in values),
		f"geometric mean requires positive finite values: {values}")
	return math.exp(statistics.mean(math.log(value) for value in values))


def select_final_samples(cohorts: Iterable[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[str]]:
	all_samples = [sample for cohort in cohorts for sample in cohort["results"]]
	for sample in all_samples:
		sample["_logical_identity"] = logical_identity(
			sample["_cohort"], workload_of(sample), str(sample["variant"]), int(sample["repetition"]))

	study_workloads = sorted({workload_of(sample) for sample in all_samples if sample["_cohort"] == "study"})
	nonstep = [workload for workload in study_workloads if workload != "steplm"]
	require(len(nonstep) == 9, f"expected 9 original non-STEP workloads, got {nonstep}")
	require("steplm" in study_workloads, "original study lacks STEP-LM")
	expected = {
		("original-nonstep", workload, variant, repetition)
		for workload in nonstep for variant in VARIANTS for repetition in REPETITIONS
	} | {
		("steplm-compatible", "steplm-compatible", variant, repetition)
		for variant in VARIANTS for repetition in REPETITIONS
	}
	groups: dict[tuple[str, str, str, int], list[dict[str, Any]]] = collections.defaultdict(list)
	for sample in all_samples:
		identity = sample["_logical_identity"]
		if identity[0] != "original-steplm-incompatible":
			groups[identity].append(sample)
	require(set(groups) == expected,
		f"final logical cells differ: missing={sorted(expected - set(groups))}, unexpected={sorted(set(groups) - expected)}")
	selected: list[dict[str, Any]] = []
	for identity in sorted(expected):
		passed = [sample for sample in groups[identity] if sample["status"] == "passed"]
		require(len(passed) == 1,
			f"{identity} has {len(passed)} successful attempts; missing cells and successful retry cherry-picking are forbidden")
		selected.append(passed[0])
	require(len(selected) == 150, f"expected 150 unique passed logical keys, got {len(selected)}")
	failures = [sample for sample in all_samples if sample["status"] != "passed"]
	study_failures = [sample for sample in failures if sample["_cohort"] == "study"]
	require(len(study_failures) == 55, f"expected 55 preserved original failures, got {len(study_failures)}")
	original_step = [sample for sample in study_failures if workload_of(sample) == "steplm"]
	require(len(original_step) == 15, f"expected all 15 original STEP-LM attempts to remain failed, got {len(original_step)}")
	return selected, failures, nonstep


def aggregate_rows(selected: Iterable[dict[str, Any]], all_samples: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
	selected = list(selected)
	selected_groups: dict[tuple[str, str], list[dict[str, Any]]] = collections.defaultdict(list)
	failure_counts: collections.Counter[tuple[str, str]] = collections.Counter()
	for sample in selected:
		_, workload, variant, _ = sample["_logical_identity"]
		selected_groups[workload, variant].append(sample)
	for sample in all_samples:
		stratum, workload, variant, _ = sample["_logical_identity"]
		if sample["status"] != "passed" and stratum != "original-steplm-incompatible":
			failure_counts[workload, variant] += 1

	rows: list[dict[str, Any]] = []
	for (workload, variant), samples in sorted(selected_groups.items()):
		require(len(samples) == 3, f"{workload}/{variant}: expected 3 successful repetitions")
		stratum = samples[0]["_logical_identity"][0]
		row: dict[str, Any] = {
			"stratum": stratum,
			"workload": workload,
			"variant": variant,
			"n": len(samples),
			"failed_attempts": failure_counts[workload, variant],
		}
		for metric in TIMING_METRICS:
			summarize((float(sample[metric]) for sample in samples), metric, row)
		receipts = [sample["pruning"][0] for sample in samples]
		for metric in RECEIPT_METRICS:
			summarize((as_float(receipt[metric], f"{workload}/{variant}/{metric}") for receipt in receipts), metric, row)
		for sample in samples:
			# Every selected upper cost participates in at least one cost ratio below.
			positive_finite_cost(sample["pruning"][0]["upper"],
				f"{workload}/{variant}/r{sample['repetition']}/upper")
		fingerprints = sorted({str(receipt["planFingerprint"]) for receipt in receipts})
		objectives = sorted({str(receipt["objectiveBits"]) for receipt in receipts})
		row.update({
			"deterministic_plan": len(fingerprints) == 1,
			"deterministic_objective": len(objectives) == 1,
			"plan_fingerprints": ",".join(fingerprints),
			"objective_bits": ",".join(objectives),
			"stops": ",".join(sorted({str(receipt["stop"]) for receipt in receipts})),
			"global_outcomes": ",".join(sorted({str(receipt["globalOutcome"]) for receipt in receipts})),
		})
		rows.append(row)

	index = {(row["workload"], row["variant"]): row for row in rows}
	sample_index = {
		(sample["_logical_identity"][1], sample["variant"], int(sample["repetition"])): sample
		for sample in selected
	}
	for row in rows:
		baseline = index[row["workload"], "baseline"]
		local = index[row["workload"], "local"]
		for metric in TIMING_METRICS:
			row[metric + "_ratio_vs_baseline"] = row[metric] / baseline[metric]
		for metric in WORK_METRICS:
			row[metric + "_ratio_vs_baseline"] = row[metric] / baseline[metric] if baseline[metric] else None
		row["cost_ratio_vs_baseline"] = positive_finite_cost(row["upper"], f"{row['workload']}/{row['variant']}/upper median") \
			/ positive_finite_cost(baseline["upper"], f"{row['workload']}/baseline upper median")
		row["cost_change_vs_baseline_pct"] = 100.0 * (row["cost_ratio_vs_baseline"] - 1.0)
		for metric in TIMING_METRICS:
			row[metric + "_paired_ratio_vs_baseline"] = [{
				"repetition": repetition,
				"ratio": float(sample_index[row["workload"], row["variant"], repetition][metric])
					/ float(sample_index[row["workload"], "baseline", repetition][metric]),
			} for repetition in REPETITIONS]
		row["cost_paired_ratio_vs_baseline"] = [{
			"repetition": repetition,
			"ratio": positive_finite_cost(sample_index[row["workload"], row["variant"], repetition]["pruning"][0]["upper"],
				f"{row['workload']}/{row['variant']}/r{repetition} upper")
				/ positive_finite_cost(sample_index[row["workload"], "baseline", repetition]["pruning"][0]["upper"],
					f"{row['workload']}/baseline/r{repetition} upper"),
		} for repetition in REPETITIONS]
		if row["variant"] == "global":
			for metric in TIMING_METRICS:
				row[metric + "_ratio_vs_local"] = row[metric] / local[metric]
			for metric in WORK_METRICS:
				row[metric + "_ratio_vs_local"] = row[metric] / local[metric] if local[metric] else None
			row["cost_ratio_vs_local"] = positive_finite_cost(row["upper"], f"{row['workload']}/global upper median") \
				/ positive_finite_cost(local["upper"], f"{row['workload']}/local upper median")
			row["cost_change_vs_local_pct"] = 100.0 * (row["cost_ratio_vs_local"] - 1.0)
			for metric in TIMING_METRICS:
				row[metric + "_paired_ratio_vs_local"] = [{
					"repetition": repetition,
					"ratio": float(sample_index[row["workload"], "global", repetition][metric])
						/ float(sample_index[row["workload"], "local", repetition][metric]),
				} for repetition in REPETITIONS]
			row["cost_paired_ratio_vs_local"] = [{
				"repetition": repetition,
				"ratio": positive_finite_cost(sample_index[row["workload"], "global", repetition]["pruning"][0]["upper"],
					f"{row['workload']}/global/r{repetition} upper")
					/ positive_finite_cost(sample_index[row["workload"], "local", repetition]["pruning"][0]["upper"],
						f"{row['workload']}/local/r{repetition} upper"),
			} for repetition in REPETITIONS]
			# globalPruned is cumulative and includes values removed before the global phase.
			row["extra_final_reduced_values_vs_local"] = local["reducedValues"] - row["reducedValues"]
			row["extra_final_reduced_values_pct_vs_local"] = (
				100.0 * row["extra_final_reduced_values_vs_local"] / local["reducedValues"]
				if local["reducedValues"] else 0.0
			)
	return rows


def aggregate_effects(rows: list[dict[str, Any]], workloads: list[str]) -> dict[str, Any]:
	index = {(row["workload"], row["variant"]): row for row in rows}
	effects: dict[str, Any] = {}
	for variant in VARIANTS:
		variant_rows = [index[workload, variant] for workload in workloads]
		entry: dict[str, Any] = {"workloads": len(variant_rows)}
		for metric in TIMING_METRICS:
			paired_key = metric + "_paired_ratio_vs_baseline"
			entry[metric + "_paired_ratio_vs_baseline_geomean"] = geometric_mean(
				item["ratio"] for row in variant_rows for item in row[paired_key])
			entry[metric + "_ratio_of_medians_vs_baseline_geomean"] = geometric_mean(
				row[metric + "_ratio_vs_baseline"] for row in variant_rows)
		entry["cost_paired_ratio_vs_baseline_geomean"] = geometric_mean(
			item["ratio"] for row in variant_rows for item in row["cost_paired_ratio_vs_baseline"])
		entry["cost_ratio_of_medians_vs_baseline_geomean"] = geometric_mean(
			row["cost_ratio_vs_baseline"] for row in variant_rows)
		entry["cost_paired_change_vs_baseline_pct_geomean"] = 100.0 * (
			entry["cost_paired_ratio_vs_baseline_geomean"] - 1.0)
		if variant == "global":
			for metric in TIMING_METRICS:
				paired_key = metric + "_paired_ratio_vs_local"
				entry[metric + "_paired_ratio_vs_local_geomean"] = geometric_mean(
					item["ratio"] for row in variant_rows for item in row[paired_key])
				entry[metric + "_ratio_of_medians_vs_local_geomean"] = geometric_mean(
					row[metric + "_ratio_vs_local"] for row in variant_rows)
			entry["cost_paired_ratio_vs_local_geomean"] = geometric_mean(
				item["ratio"] for row in variant_rows for item in row["cost_paired_ratio_vs_local"])
			entry["cost_ratio_of_medians_vs_local_geomean"] = geometric_mean(
				row["cost_ratio_vs_local"] for row in variant_rows)
			entry["cost_paired_change_vs_local_pct_geomean"] = 100.0 * (
				entry["cost_paired_ratio_vs_local_geomean"] - 1.0)
		effects[variant] = entry
	return effects


def json_safe(value: Any) -> Any:
	if isinstance(value, float) and not math.isfinite(value):
		return "Infinity" if value > 0 else "-Infinity"
	if isinstance(value, dict):
		return {str(key): json_safe(item) for key, item in value.items()}
	if isinstance(value, (list, tuple)):
		return [json_safe(item) for item in value]
	return value


def failure_provenance(sample: dict[str, Any]) -> dict[str, Any]:
	return {
		"cohort": sample["_cohort"],
		"key": sample["key"],
		"logical_identity": list(sample["_logical_identity"]),
		"attempt": sample.get("attempt"),
		"errors": sample.get("errors", []),
		"result_path": sample["_result_path"],
		"result_sha256": sample["_result_sha256"],
	}


def attempt_provenance(sample: dict[str, Any]) -> dict[str, Any]:
	return {
		"cohort": sample["_cohort"],
		"key": sample["key"],
		"logical_identity": list(sample["_logical_identity"]),
		"status": sample["status"],
		"attempt": sample.get("attempt"),
		"attempt_directory": sample["_attempt_dir"],
		"result_path": sample["_result_path"],
		"result_sha256": sample["_result_sha256"],
		"artifact_sha256": sample["_artifact_sha256"],
	}


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
	columns = list(dict.fromkeys(key for row in rows for key in row))
	with path.open("w", newline="") as stream:
		writer = csv.DictWriter(stream, fieldnames=columns)
		writer.writeheader()
		for row in rows:
			cleaned = json_safe(row)
			writer.writerow({key: json.dumps(value, sort_keys=True) if isinstance(value, (list, dict)) else value
				for key, value in cleaned.items()})


def change_label(value: float) -> str:
	direction = "increase" if value > 1e-12 else "decrease" if value < -1e-12 else "unchanged"
	return f"{value:+.2f}% ({direction})"


def write_tables(path: Path, rows: list[dict[str, Any]], nonstep: list[str], effects: dict[str, Any]) -> None:
	index = {(row["workload"], row["variant"]): row for row in rows}
	workloads = nonstep + ["steplm-compatible"]
	lines = [
		"# Pruning ablation",
		"",
		"The primary aggregate covers the nine original non-STEP workloads. The compatible STEP-LM run is a separate stratum; it does not recover the failed original STEP-LM workload.",
		"",
		"| Workload | Baseline | +Dominance | +Support | +Local | +Global |",
		"|---|---:|---:|---:|---:|---:|",
	]
	for workload in workloads:
		values = [index[workload, variant]["full_initial_planning_seconds"] for variant in VARIANTS]
		label = workload + ("*" if workload == "steplm-compatible" else "")
		lines.append("| " + label + " | " + " | ".join(f"{value:.3f}" for value in values) + " |")
	lines += ["", "Seconds; median of three successful repetitions.", "", "## Global versus local", "",
		"| Workload | Local upper | Global upper | Signed cost change | Local lower | Global lower | Local values | Global values | Extra final pruning | Stop / gap |",
		"|---|---:|---:|---:|---:|---:|---:|---:|---:|---|",
	]
	for workload in workloads:
		local = index[workload, "local"]
		global_row = index[workload, "global"]
		lines.append(
			f"| {workload} | {local['upper']:.8g} | {global_row['upper']:.8g} | "
			f"{change_label(global_row['cost_change_vs_local_pct'])} | {local['lower']:.8g} | "
			f"{global_row['lower']:.8g} | {local['reducedValues']:.0f} | {global_row['reducedValues']:.0f} | "
			f"{global_row['extra_final_reduced_values_vs_local']:.0f} | {global_row['stops']} / {global_row['gap']:.4g} |"
		)
	lines += ["", "`globalPruned` counts value rejections on original domains, including values already absent after existing reduction; subsequent propagation can further shrink domains. The extra-pruning column therefore uses final `reducedValues` relative to local as the net effect.",
		"", "The same gap policy can accept different selected costs after the global bound changes the search path or reduces resource work. A positive signed cost change is an increase, not a gain.",
		"", "## Geometric-mean ratios", "",
		"| Stratum | Variant | Paired planning / baseline | Ratio-of-medians planning / baseline | Paired optimizer / baseline | Paired cost / baseline | Paired global planning / local | Paired global cost / local |",
		"|---|---|---:|---:|---:|---:|---:|---:|",
	]
	for stratum in ("primary_original_nonstep_9", "optional_with_steplm_compatible_10"):
		for variant in VARIANTS:
			entry = effects[stratum][variant]
			lines.append(
				f"| {stratum} | {variant} | {entry['full_initial_planning_seconds_paired_ratio_vs_baseline_geomean']:.3f} | "
				f"{entry['full_initial_planning_seconds_ratio_of_medians_vs_baseline_geomean']:.3f} | "
				f"{entry['optimizer_seconds_paired_ratio_vs_baseline_geomean']:.3f} | "
				f"{entry['cost_paired_ratio_vs_baseline_geomean']:.3f} | "
				f"{entry.get('full_initial_planning_seconds_paired_ratio_vs_local_geomean', float('nan')):.3f} | "
				f"{entry.get('cost_paired_ratio_vs_local_geomean', float('nan')):.3f} |"
			)
	lines += ["", "Primary ratios pair the same cold-JVM repetition before taking a geometric mean (27 pairs for the nine-workload stratum). Ratios of the three-repetition medians are descriptive only. Ratios below 1 are lower/faster; n=3 is exploratory and no statistical significance is claimed. The ten-workload aggregate includes the separately labelled compatible STEP-LM workload."]
	path.write_text("\n".join(lines) + "\n")


def write_plot(path: Path, rows: list[dict[str, Any]], nonstep: list[str]) -> None:
	del rows, nonstep  # Plot data is the already-written, validated ablation.csv.
	rscript = shutil.which("Rscript")
	require(rscript is not None, "Rscript is required for latency plots; use --no-plots to skip them")
	plot_script = Path(__file__).with_name("plot_pruning_ablation.R")
	require(plot_script.is_file(), f"missing plotting script: {plot_script}")
	try:
		completed = subprocess.run(
			[rscript, str(plot_script), str(path / "ablation.csv"), str(path)],
			check=False, capture_output=True, text=True)
	except OSError as error:
		raise AnalysisError(f"could not run R plotting script: {error}") from error
	require(completed.returncode == 0,
		f"R plotting script failed ({completed.returncode}): {completed.stderr.strip() or completed.stdout.strip()}")
	for output_name in ("latency.png", "latency.svg"):
		require((path / output_name).is_file(), f"R plotting script did not create {output_name}")


def analyze(study_path: Path, recovery_path: Path, compatibility_path: Path, output: Path,
		write_plots: bool = True) -> dict[str, Any]:
	cohorts = [
		load_cohort("study", study_path),
		load_cohort("recovery", recovery_path),
		load_cohort("compatibility", compatibility_path),
	]
	shared_identity = validate_shared_identity(cohorts)
	validate_recovery_lineage(cohorts[0], cohorts[1])
	validate_compatibility_lineage(cohorts[0], cohorts[2])
	selected, failures, nonstep = select_final_samples(cohorts)
	all_samples = [sample for cohort in cohorts for sample in cohort["results"]]
	rendered_inputs = validate_rendered_inputs(all_samples)
	rows = aggregate_rows(selected, all_samples)
	effects = {
		"primary_original_nonstep_9": aggregate_effects(rows, nonstep),
		"optional_with_steplm_compatible_10": aggregate_effects(rows, nonstep + ["steplm-compatible"]),
	}
	cohort_provenance = [{
		"label": cohort["label"],
		"root": str(cohort["root"]),
		"manifest_path": str(cohort["manifest_path"]),
		"manifest_sha256": cohort["manifest_sha256"],
		"planned": len(cohort["planned_by_key"]),
		"passed": sum(sample["status"] == "passed" for sample in cohort["results"]),
		"failed": sum(sample["status"] != "passed" for sample in cohort["results"]),
	} for cohort in cohorts]
	report = {
		"schema_version": 1,
		"scope": {
			"primary": "9 original non-STEP workloads",
			"optional": "9 original non-STEP workloads plus separate steplm-compatible stratum",
			"original_steplm_recovered": False,
			"note": "The compatible STEP-LM workload uses different parameters and does not replace the failed original STEP-LM workload.",
		},
		"totals": {
			"unique_passed_logical_keys": len(selected),
			"raw_attempts": len(all_samples),
			"raw_passed_attempts": sum(sample["status"] == "passed" for sample in all_samples),
			"raw_failed_attempts": len(failures),
			"preserved_original_failures": sum(sample["_cohort"] == "study" for sample in failures),
		},
		"shared_identity": shared_identity,
		"shared_identity_sha256": sha256_bytes(canonical_bytes(shared_identity)),
		"cohorts": cohort_provenance,
		"rendered_inputs": rendered_inputs,
		"effects": effects,
		"rows": rows,
		"failures": [failure_provenance(sample) for sample in failures],
		"attempts": [attempt_provenance(sample) for sample in all_samples],
	}
	output.mkdir(parents=True, exist_ok=True)
	write_csv(output / "ablation.csv", rows)
	(output / "analysis.json").write_text(json.dumps(json_safe(report), indent=2, sort_keys=True, allow_nan=False) + "\n")
	write_tables(output / "tables.md", rows, nonstep, effects)
	if write_plots:
		write_plot(output, rows, nonstep)
	return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--study", required=True, type=Path)
	parser.add_argument("--recovery", required=True, type=Path)
	parser.add_argument("--compatibility", required=True, type=Path)
	parser.add_argument("--output", required=True, type=Path)
	parser.add_argument("--no-plots", action="store_true", help="skip matplotlib outputs (useful for lightweight validation)")
	return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
	args = parse_args(argv)
	try:
		report = analyze(args.study, args.recovery, args.compatibility, args.output, not args.no_plots)
	except AnalysisError as error:
		print(f"analysis rejected: {error}", file=sys.stderr)
		return 2
	print(json.dumps(json_safe({"totals": report["totals"], "effects": report["effects"]}), indent=2, allow_nan=False))
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
