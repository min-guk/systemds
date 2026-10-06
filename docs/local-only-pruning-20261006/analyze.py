#!/usr/bin/env python3
"""Validate and summarize the paired baseline/local-only/local production study."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import struct
import sys


VARIANTS = ("baseline", "local_only", "local")
REPETITIONS = (1, 2, 3)
TIMINGS = ("full_initial_planning_seconds", "optimizer_seconds", "analysis_seconds")
COUNTERS = ("rawValues", "rawCells", "reducedValues", "reducedCells",
	"fullChildEvaluations", "childEvaluations", "infeasibleCuts", "costCuts",
	"assignments", "retainedSlots", "merges")
CANONICAL_WORKLOADS = ("logreg", "l2svm", "pca", "als", "kmeans",
	"lm", "glm", "gnmf", "gmm", "steplm")
COMPARISONS = (("local_only", "baseline"), ("local", "baseline"), ("local_only", "local"))


class AnalysisError(RuntimeError):
	pass


def require(condition: bool, message: str) -> None:
	if not condition:
		raise AnalysisError(message)


def load_json(path: Path):
	try:
		return json.loads(path.read_text())
	except (OSError, json.JSONDecodeError) as error:
		raise AnalysisError(f"cannot read {path}: {error}") from error


def sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as stream:
		for chunk in iter(lambda: stream.read(1024 * 1024), b""):
			digest.update(chunk)
	return digest.hexdigest()


def validate_planned(planned: list[dict]) -> None:
	expected = {(workload, variant, repetition)
		for workload in CANONICAL_WORKLOADS for variant in VARIANTS for repetition in REPETITIONS}
	observed = set()
	for row in planned:
		workload, variant, repetition = row.get("cell", {}).get("workload"), row.get("variant"), row.get("repetition")
		cell = {"id": f"ml|{workload}|wan_mid|w3|DP-local", "suite": "ml", "workload": workload,
			"profile": "wan_mid", "workers": 3, "planner": "DP-local",
			"planner_enum": "COMPILE_COST_BASED"}
		require(row.get("cell") == cell, f"non-canonical planned cell: {row.get('key')}")
		require(row.get("key") == f"{cell['id']}|{variant}|r{repetition}",
			f"non-canonical planned key: {row.get('key')}")
		triple = (workload, variant, repetition)
		require(triple not in observed, f"duplicate planned triple: {triple}")
		observed.add(triple)
	require(observed == expected,
		f"planned Cartesian product mismatch: missing={sorted(expected - observed)}, unexpected={sorted(observed - expected)}")


def validate_receipt(sample: dict, receipt: dict) -> None:
	require(receipt.get("variant") == sample["variant"], f"variant receipt mismatch: {sample['key']}")
	require(re.fullmatch(r"[0-9a-f]{64}", receipt.get("planFingerprint", "")) is not None,
		f"invalid plan fingerprint: {sample['key']}")
	for field in COUNTERS:
		require(type(receipt.get(field)) is int and receipt[field] >= 0,
			f"invalid {field}: {sample['key']}")
	for field in ("lower", "upper"):
		value = float(receipt[field])
		require(math.isfinite(value) and value >= 0, f"invalid {field}: {sample['key']}")
	gap = float(receipt["gap"])
	require(not math.isnan(gap) and gap >= 0, f"invalid gap: {sample['key']}")
	require(float(receipt["lower"]) <= float(receipt["upper"]), f"inverted bounds: {sample['key']}")
	objective_bits = int(receipt["objectiveBits"])
	require(0 <= objective_bits < 2**64 and
		struct.unpack("!d", objective_bits.to_bytes(8, "big"))[0] == float(receipt["upper"]),
		f"objective bits do not encode upper bound: {sample['key']}")
	require(receipt["childEvaluations"] <= receipt["fullChildEvaluations"],
		f"invalid child work: {sample['key']}")
	for field in ("objectiveBits", "initialUpperBits", "globalInitialUpperBits"):
		encoded = receipt.get(field)
		require(isinstance(encoded, str) and re.fullmatch(r"[0-9]+", encoded) is not None
			and int(encoded) < 2**64, f"invalid unsigned 64-bit {field}: {sample['key']}")
	for field in ("globalConsidered", "globalPruned", "globalFactors", "globalPrepNanos"):
		require(type(receipt.get(field)) is int and receipt[field] == 0,
			f"local study unexpectedly used global pruning {field}: {sample['key']}")
	require(receipt.get("globalOutcome") == "DISABLED" and receipt.get("globalInitialUpperBits") == "0",
		f"local study global gate was not disabled: {sample['key']}")


def validate_compile(sample: dict) -> None:
	receipt = sample.get("receipt") or {}
	audit = receipt.get("plannerRuntimeAudit") or {}
	require(sample.get("status") == "passed" and sample.get("returncode") == 0
		and sample.get("cleanup_resolved") is True and not sample.get("errors"),
		f"sample did not pass cleanly: {sample.get('key')}")
	require(receipt.get("status") == "success" and receipt.get("schema") == "matrix-campaign-probe-v1",
		f"invalid probe receipt: {sample['key']}")
	require(receipt.get("actualCompileOnly") is True and receipt.get("configuredCompileOnly") is True
		and receipt.get("runtimeProgramConstructed") is True and receipt.get("runtimeAuditEnabled") is True,
		f"production compile/audit evidence missing: {sample['key']}")
	require(receipt.get("actualPlannerCanonical") == "COMPILE_COST_BASED"
		and receipt.get("candidateE2E", {}).get("calls") == 1
		and receipt.get("candidateE2E", {}).get("exactPhaseCalls") == 1,
		f"wrong planner or optimizer call count: {sample['key']}")
	require(receipt.get("workloadExecutionStarted") is False
		and receipt.get("workloadExecutionCompleted") is False
		and receipt.get("executionNanos") == 0 and receipt.get("observedRunNanos") == 0,
		f"compile-only boundary violated: {sample['key']}")
	for field in ("mismatches", "missingPhysicalHops", "missingSynthetic"):
		require(audit.get(field) == 0, f"runtime lowering audit {field}: {sample['key']}")
	require(audit.get("plannedPhysicalHops") == audit.get("loweredPhysicalHops"),
		f"runtime lowering coverage mismatch: {sample['key']}")
	require(len(sample.get("pruning", [])) == 1, f"expected one pruning receipt: {sample['key']}")
	validate_receipt(sample, sample["pruning"][0])
	for field in TIMINGS:
		value = sample.get(field)
		require(type(value) in (int, float) and math.isfinite(value) and value > 0,
			f"invalid positive finite timing {field}: {sample['key']}")
	require(sample["full_initial_planning_seconds"] == receipt["planningFullInitialNanos"] / 1e9
		and sample["optimizer_seconds"] == receipt["candidateE2E"]["optimizerNanos"] / 1e9
		and sample["analysis_seconds"] == receipt["analysisNanos"] / 1e9,
		f"timing/probe mismatch: {sample['key']}")


def load_study(root: Path):
	manifest_path = root / "ablation.json"
	manifest = load_json(manifest_path)
	planned = manifest.get("identity", {}).get("samples", [])
	require(isinstance(planned, list) and len(planned) == 90,
		"manifest must contain exactly 90 planned samples")
	validate_planned(planned)
	measurement = manifest.get("measurement", {})
	require(measurement.get("full_production_compile") is True
		and measurement.get("compile_only") is True and measurement.get("fresh_jvm") is True
		and measurement.get("concurrent_timed_samples") == 1 and measurement.get("workers_started") == 0
		and measurement.get("runtime_lowering_audit") is True,
		"manifest does not describe the required isolated production compile study")
	planned_by_key = {row["key"]: row for row in planned}
	attempts = sorted(path for path in (root / "attempts").iterdir() if path.is_dir())
	require(len(attempts) == 90, f"expected exactly 90 raw attempts, got {len(attempts)}")
	samples, provenance = [], []
	for attempt in attempts:
		result_path = attempt / "result.json"
		require(result_path.is_file(), f"unfinished attempt: {attempt}")
		sample = load_json(result_path)
		key = sample.get("key")
		require(key in planned_by_key and all(sample.get(field) == value
			for field, value in planned_by_key[key].items()), f"unplanned/mismatched result: {key}")
		validate_compile(sample)
		samples.append(sample)
		provenance.append({"key": key, "attempt": str(attempt), "result": str(result_path),
			"result_sha256": sha256(result_path)})
	require(len({sample["key"] for sample in samples}) == 90, "duplicate or missing result keys")
	return manifest, samples, {"manifest": str(manifest_path),
		"manifest_sha256": sha256(manifest_path), "results": provenance}


def summary(values):
	return {"median": statistics.median(values), "min": min(values), "max": max(values)}


def geometric_mean(values):
	require(values and all(math.isfinite(value) and value > 0 for value in values),
		"geometric mean requires positive finite ratios")
	return math.exp(statistics.mean(math.log(value) for value in values))


def json_safe(value):
	if isinstance(value, float) and not math.isfinite(value):
		return "Infinity" if value > 0 else "-Infinity"
	if isinstance(value, dict):
		return {key: json_safe(item) for key, item in value.items()}
	if isinstance(value, list):
		return [json_safe(item) for item in value]
	return value


def aggregate(samples: list[dict]):
	index = {(sample["cell"]["workload"], sample["variant"], sample["repetition"]): sample
		for sample in samples}
	stats_rows = []
	for workload in CANONICAL_WORKLOADS:
		for variant in VARIANTS:
			group = [index[workload, variant, repetition] for repetition in REPETITIONS]
			row = {"workload": "steplm-compatible" if workload == "steplm" else workload,
				"variant": variant, "n": 3}
			for field in TIMINGS:
				for name, value in summary([sample[field] for sample in group]).items():
					row[f"{field}_{name}"] = value
			for field in COUNTERS + ("lower", "upper", "gap"):
				for name, value in summary([float(sample["pruning"][0][field]) for sample in group]).items():
					row[f"{field}_{name}"] = value
			row["objective_bits"] = ",".join(sorted({sample["pruning"][0]["objectiveBits"] for sample in group}))
			row["plan_fingerprints"] = ",".join(sorted({sample["pruning"][0]["planFingerprint"] for sample in group}))
			row["stops"] = ",".join(sorted({sample["pruning"][0]["stop"] for sample in group}))
			stats_rows.append(row)

	pairs = []
	for workload in CANONICAL_WORKLOADS:
		for numerator, denominator in COMPARISONS:
			for repetition in REPETITIONS:
				left, right = index[workload, numerator, repetition], index[workload, denominator, repetition]
				lp, rp = left["pruning"][0], right["pruning"][0]
				left_cost, right_cost = float(lp["upper"]), float(rp["upper"])
				require(left_cost > 0 and right_cost > 0,
					f"paired cost ratios require positive costs: {workload}/r{repetition}")
				pair = {"workload": "steplm-compatible" if workload == "steplm" else workload,
					"comparison": f"{numerator}_vs_{denominator}", "repetition": repetition,
					"objective_equal": lp["objectiveBits"] == rp["objectiveBits"],
					"plan_equal": lp["planFingerprint"] == rp["planFingerprint"],
					"stop_equal": lp["stop"] == rp["stop"],
					"bounds_equal": lp["lower"] == rp["lower"] and lp["upper"] == rp["upper"],
					"cost_ratio": left_cost / right_cost,
					"cost_change_pct": 100 * (left_cost / right_cost - 1)}
				for field in TIMINGS:
					pair[field + "_ratio"] = left[field] / right[field]
				pairs.append(pair)
	return stats_rows, pairs


def effects(pairs: list[dict], workloads: set[str]):
	result = {}
	for comparison in (f"{left}_vs_{right}" for left, right in COMPARISONS):
		selected = [pair for pair in pairs if pair["comparison"] == comparison and pair["workload"] in workloads]
		entry = {"pairs": len(selected)}
		for field in TIMINGS:
			ratios = [pair[field + "_ratio"] for pair in selected]
			entry[field + "_paired_mean_log_ratio"] = statistics.mean(math.log(value) for value in ratios)
			entry[field + "_paired_geomean_ratio"] = geometric_mean(ratios)
		cost_ratios = [pair["cost_ratio"] for pair in selected]
		entry["cost_paired_mean_log_ratio"] = statistics.mean(math.log(value) for value in cost_ratios)
		entry["cost_paired_geomean_ratio"] = geometric_mean(cost_ratios)
		entry["cost_paired_geomean_change_pct"] = 100 * (entry["cost_paired_geomean_ratio"] - 1)
		for field in ("objective_equal", "plan_equal", "stop_equal", "bounds_equal"):
			entry[field + "_pairs"] = sum(pair[field] for pair in selected)
		result[comparison] = entry
	return result


def write_outputs(output: Path, manifest, stats_rows, pairs, provenance):
	output.mkdir(parents=True, exist_ok=True)
	primary = set(CANONICAL_WORKLOADS[:-1])
	primary_effects = effects(pairs, primary)
	all_effects = effects(pairs, primary | {"steplm-compatible"})
	step_effects = effects(pairs, {"steplm-compatible"})
	report = {"scope": {"primary": "nine original non-STEP workloads",
		"optional": "primary plus separately labelled compatible STEP-LM", "repetitions": 3,
		"note": "Cold-JVM exploratory paired study; no statistical significance claim."},
		"totals": {"planned": 90, "passed": 90, "failed": 0},
		"identity": manifest["identity"], "provenance": provenance,
		"primary_effects": primary_effects, "optional_effects": all_effects,
		"step_compatible_effects": step_effects,
		"stats": stats_rows, "paired_samples": pairs}
	(output / "analysis.json").write_text(json.dumps(json_safe(report), indent=2,
		sort_keys=True, allow_nan=False) + "\n")
	columns = list(dict.fromkeys(key for row in stats_rows for key in row))
	with (output / "stats.csv").open("w", newline="") as stream:
		writer = csv.DictWriter(stream, fieldnames=columns); writer.writeheader(); writer.writerows(stats_rows)
	by_stats = {(row["workload"], row["variant"]): row for row in stats_rows}
	lines = ["# Local-only pruning study", "",
		"Primary aggregate: nine original non-STEP workloads. Compatible STEP-LM is reported separately.", "",
		"| Comparison | Paired planning ratio | Paired optimizer ratio | Paired analysis ratio | Paired cost change | Objective / plan / stop / bounds equal |",
		"|---|---:|---:|---:|---:|---:|"]
	for comparison, entry in primary_effects.items():
		quality = " / ".join(str(entry[field + "_pairs"]) + "/27" for field in
			("objective_equal", "plan_equal", "stop_equal", "bounds_equal"))
		lines.append(f"| {comparison} | {entry['full_initial_planning_seconds_paired_geomean_ratio']:.4f} | "
			f"{entry['optimizer_seconds_paired_geomean_ratio']:.4f} | {entry['analysis_seconds_paired_geomean_ratio']:.4f} | "
			f"{entry['cost_paired_geomean_change_pct']:+.3f}% | {quality} |")
	lines += ["", "Ratios below 1 are faster/lower. Cost changes are signed and positive values are increases.", "",
		"| Workload | Variant | Planning median [min,max] | Optimizer median [min,max] | Reduced values / cells | Child eval / full | Infeasible / cost cuts | Upper [min,max] |",
		"|---|---|---:|---:|---:|---:|---:|---:|"]
	for workload in list(CANONICAL_WORKLOADS[:-1]) + ["steplm-compatible"]:
		for variant in VARIANTS:
			row = by_stats[workload, variant]
			lines.append(f"| {workload} | {variant} | {row['full_initial_planning_seconds_median']:.3f} "
				f"[{row['full_initial_planning_seconds_min']:.3f},{row['full_initial_planning_seconds_max']:.3f}] | "
				f"{row['optimizer_seconds_median']:.3f} [{row['optimizer_seconds_min']:.3f},{row['optimizer_seconds_max']:.3f}] | "
				f"{row['reducedValues_median']:.0f} / {row['reducedCells_median']:.0f} | "
				f"{row['childEvaluations_median']:.0f} / {row['fullChildEvaluations_median']:.0f} | "
				f"{row['infeasibleCuts_median']:.0f} / {row['costCuts_median']:.0f} | "
				f"{row['upper_median']:.7g} [{row['upper_min']:.7g},{row['upper_max']:.7g}] |")
	(output / "tables.md").write_text("\n".join(lines) + "\n")


def main(argv=None) -> int:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--root", required=True, type=Path)
	parser.add_argument("--output", required=True, type=Path)
	args = parser.parse_args(argv)
	try:
		manifest, samples, provenance = load_study(args.root.resolve())
		stats_rows, pairs = aggregate(samples)
		write_outputs(args.output.resolve(), manifest, stats_rows, pairs, provenance)
	except AnalysisError as error:
		print(f"analysis rejected: {error}", file=sys.stderr)
		return 2
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
