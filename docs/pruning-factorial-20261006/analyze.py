#!/usr/bin/env python3
"""Analyze L/DL/SL/DSL with reused L/DSL and a fresh DSL time-cohort control."""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import statistics
import sys


DOCS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("pruning_receipts",
	DOCS / "local-only-pruning-20261006/analyze.py")
receipt = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(receipt)
require = receipt.require
WORKLOADS = receipt.CANONICAL_WORKLOADS
TIMINGS = receipt.TIMINGS
COUNTERS = receipt.COUNTERS
REPETITIONS = (1, 2, 3)
ARMS = ("old_L", "old_DSL", "new_DL", "new_SL", "new_DSL")
ARM_VARIANT = {"old_L": "local_only", "old_DSL": "local",
	"new_DL": "dominance_local", "new_SL": "support_local", "new_DSL": "local"}
COMPARISONS = {
	"D_given_S": ("new_DSL", "new_SL", "same_new_cohort"),
	"S_given_D": ("new_DSL", "new_DL", "same_new_cohort"),
	"DL_vs_SL": ("new_DL", "new_SL", "same_new_cohort"),
	"DSL_vs_L_reused": ("old_DSL", "old_L", "same_old_cohort"),
	"D_without_S_raw": ("new_DL", "old_L", "different_time_cohorts"),
	"S_without_D_raw": ("new_SL", "old_L", "different_time_cohorts"),
	"DSL_control_shift": ("new_DSL", "old_DSL", "same_variant_different_time_cohorts"),
}
SHARED_IDENTITY = ("image", "probe_sha256", "dependencies_sha256", "host", "java", "cpu",
	"memory", "cost_environment", "cost_profile_sha256", "cost_profile_file_sha256",
	"optimizer_time_millis", "renderer_sha256", "stage_sha256", "matrix_runner_sha256")


def canonical_sha(value):
	return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
		ensure_ascii=False).encode()).hexdigest()


def load_cohort(root: Path, variants: tuple[str, ...]):
	manifest_path = root / "ablation.json"
	manifest = receipt.load_json(manifest_path)
	expected = {}
	for workload in WORKLOADS:
		cell = dict(id=f"ml|{workload}|wan_mid|w3|DP-local", suite="ml", workload=workload,
			profile="wan_mid", workers=3, planner="DP-local", planner_enum="COMPILE_COST_BASED")
		for variant in variants:
			for repetition in REPETITIONS:
				key = f"{cell['id']}|{variant}|r{repetition}"
				expected[key] = dict(cell=cell, variant=variant, repetition=repetition, key=key)
	planned = manifest["identity"]["samples"]
	require(len(planned) == len(expected) and len({p["key"] for p in planned}) == len(expected)
		and all(expected.get(p["key"]) == p for p in planned), f"invalid sample matrix: {root}")
	measurement = manifest["measurement"]
	for key, value in dict(full_production_compile=True, compile_only=True, fresh_jvm=True,
		concurrent_timed_samples=1, workers_started=0, runtime_lowering_audit=True,
		network="none", workload_execution=False, repetitions=3).items():
		require(measurement.get(key) == value, f"invalid measurement {key}: {root}")
	attempts = sorted(p for p in (root / "attempts").iterdir() if p.is_dir())
	require(len(attempts) == len(expected), f"missing/extra attempts: {root}")
	rows, evidence, rendered = {}, [], {}
	for attempt in attempts:
		path = attempt / "result.json"
		sample = receipt.load_json(path)
		key = sample.get("key")
		require(key in expected and key not in rows
			and all(sample.get(k) == v for k, v in expected[key].items()), f"unplanned/duplicate sample: {key}")
		receipt.validate_compile(sample)
		contract = receipt.load_json(attempt / "render-contract.json")
		require(contract["cell"] == sample["cell"]
			and (attempt / "tmp/cell.dml").read_text() == contract["source"], f"rendered cell mismatch: {key}")
		workload = sample["cell"]["workload"]
		digest = canonical_sha({k: contract[k] for k in ("source", "inputs", "dependencies")})
		require(workload not in rendered or rendered[workload] == digest, f"inconsistent input: {key}")
		rendered[workload] = digest
		rows[key] = sample
		evidence.append(dict(key=key, result=str(path), sha256=receipt.sha256(path)))
	return manifest, list(rows.values()), dict(manifest=str(manifest_path),
		manifest_sha256=receipt.sha256(manifest_path), results=evidence, rendered=rendered)


def same_cost_plan(left, right):
	a, b = left["pruning"][0], right["pruning"][0]
	left_cost, right_cost = float(a["upper"]), float(b["upper"])
	require(left_cost > 0 and right_cost > 0, "matched cost ratios require positive costs")
	return dict(objective_equal=a["objectiveBits"] == b["objectiveBits"],
		plan_equal=a["planFingerprint"] == b["planFingerprint"], stop_equal=a["stop"] == b["stop"],
		bounds_equal=a["lower"] == b["lower"] and a["upper"] == b["upper"],
		cost_ratio=left_cost / right_cost, cost_change_pct=100 * (left_cost / right_cost - 1))


def analyze(old_root: Path, new_root: Path):
	old, old_rows, old_evidence = load_cohort(old_root, ("baseline", "local_only", "local"))
	new, new_rows, new_evidence = load_cohort(new_root, ("dominance_local", "support_local", "local"))
	for field in SHARED_IDENTITY:
		require(old["identity"][field] == new["identity"][field], f"cohort configuration differs: {field}")
	require(old_evidence["rendered"] == new_evidence["rendered"], "cohort workload inputs differ")
	old_sources, new_sources = old["identity"]["source_sha256"], new["identity"]["source_sha256"]
	require(old_sources.keys() == new_sources.keys(), "production source file set differs")
	changed = [p for p in old_sources if old_sources[p] != new_sources[p]]
	require(changed == ["src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/PruningAblation.java"],
		f"unexpected production source changes: {changed}")
	index = {}
	for arm in ARMS:
		for row in old_rows if arm.startswith("old_") else new_rows:
			if row["variant"] == ARM_VARIANT[arm]:
				index[row["cell"]["workload"], arm, row["repetition"]] = row
	require(len(index) == 150, "expected 60 reused + 60 new treatments + 30 new controls")
	stats, pairs, balanced = [], [], []
	for workload in WORKLOADS:
		for arm in ARMS:
			group = [index[workload, arm, r] for r in REPETITIONS]
			row = dict(workload=workload, arm=arm, variant=ARM_VARIANT[arm], n=3)
			for field in TIMINGS + COUNTERS + ("lower", "upper", "gap"):
				values = [float(s[field] if field in TIMINGS else s["pruning"][0][field]) for s in group]
				for name, value in receipt.summary(values).items():
					row[f"{field}_{name}"] = value
			for field in ("objectiveBits", "planFingerprint", "stop"):
				row[field] = sorted({s["pruning"][0][field] for s in group})
			stats.append(row)
		for name, (numerator, denominator, scope) in COMPARISONS.items():
			for repetition in REPETITIONS:
				a, b = index[workload, numerator, repetition], index[workload, denominator, repetition]
				row = dict(workload=workload, repetition=repetition, comparison=name, scope=scope,
					**same_cost_plan(a, b))
				for field in TIMINGS:
					row[field + "_ratio"] = a[field] / b[field]
				pairs.append(row)
		for repetition in REPETITIONS:
			values = {arm: index[workload, arm, repetition] for arm in ARMS}
			row = dict(workload=workload, repetition=repetition)
			for field in TIMINGS:
				l, dl, sl, dsl = (values[a][field] for a in ("old_L", "new_DL", "new_SL", "old_DSL"))
				row[field + "_D_main_ratio"] = math.sqrt((dl / l) * (dsl / sl))
				row[field + "_S_main_ratio"] = math.sqrt((sl / l) * (dsl / dl))
				row[field + "_raw_interaction_ratio"] = (dsl / dl) * (l / sl)
			balanced.append(row)
	return dict(totals=dict(reused=60, new_treatments=60, new_controls=30, selected=150,
		validated_raw=180, new_passed=90, new_failed=0), stats=stats, comparisons=pairs,
		balanced_effect_samples=balanced, provenance=dict(old=old_evidence, new=new_evidence),
		identity=dict(shared={k:new["identity"][k] for k in SHARED_IDENTITY},
			old_jar=old["identity"]["jar_sha256"], new_jar=new["identity"]["jar_sha256"],
			production_source_changes=changed), scope=dict(primary="ML9 excluding STEP-LM",
			compatibility="STEP-LM compatible maxi=20, no max_features; reported separately",
			primary_metric="full_initial_planning_seconds", secondary_metric="optimizer_seconds",
			cohort_policy="Reused L/DSL and new DL/SL are different time cohorts; new DSL is a contemporaneous control.",
			main_effect_assumption="Balanced D/S log main effects use old L/DSL and new DL/SL; a common multiplicative cohort shift cancels.",
			interaction_warning="Raw interaction is confounded with time cohort and is not a causal interaction estimate.",
			statistics="Three cold-JVM repetitions on a shared host; no significance claim."))


def effects(report, workloads):
	result = {}
	for name in COMPARISONS:
		rows = [r for r in report["comparisons"] if r["comparison"] == name and r["workload"] in workloads]
		entry = dict(n=len(rows), scope=COMPARISONS[name][2])
		for field in TIMINGS:
			entry[field + "_geomean_ratio"] = receipt.geometric_mean([r[field + "_ratio"] for r in rows])
		for field in ("objective_equal", "plan_equal", "stop_equal", "bounds_equal"):
			entry[field] = sum(r[field] for r in rows)
		entry["cost_geomean_ratio"] = receipt.geometric_mean([r["cost_ratio"] for r in rows])
		entry["cost_change_pct"] = 100 * (entry["cost_geomean_ratio"] - 1)
		result[name] = entry
	rows = [r for r in report["balanced_effect_samples"] if r["workload"] in workloads]
	result["balanced_main_effects"] = {key: receipt.geometric_mean([r[key] for r in rows])
		for key in rows[0] if key.endswith("_ratio")}
	return result


def write_outputs(report, output: Path):
	output.mkdir(parents=True, exist_ok=True)
	primary = set(WORKLOADS) - {"steplm"}
	report["primary_effects"] = effects(report, primary)
	report["step_compatible_effects"] = effects(report, {"steplm"})
	report["workload_effects"] = {w: effects(report, {w}) for w in WORKLOADS}
	(output / "analysis.json").write_text(json.dumps(receipt.json_safe(report), indent=2,
		sort_keys=True, allow_nan=False) + "\n")
	with (output / "stats.csv").open("w", newline="") as stream:
		writer = csv.DictWriter(stream, fieldnames=list(report["stats"][0]))
		writer.writeheader();writer.writerows(report["stats"])
	lines = ["# Local-fixed pruning factorial", "",
		"Reused: L and DSL (60). New: DL and SL (60), plus DSL time-cohort controls (30).", "",
		"ML9 primary; STEP-LM compatibility is separate. Ratios < 1 mean less time.", "",
		"| Comparison | Scope | Full planning ratio | Optimizer ratio | Analysis ratio | Cost change | Equal cost / plan / stop / bounds |",
		"|---|---|---:|---:|---:|---:|---|"]
	for name in COMPARISONS:
		entry = report["primary_effects"][name]
		quality = " / ".join(f"{entry[k]}/{entry['n']}" for k in
			("objective_equal", "plan_equal", "stop_equal", "bounds_equal"))
		lines.append(f"| {name} | {entry['scope']} | " + " | ".join(
			f"{entry[f + '_geomean_ratio']:.4f}" for f in TIMINGS) +
			f" | {entry['cost_change_pct']:+.3f}% | {quality} |")
	lines += ["", "Balanced D/S main effects assume a common multiplicative time-cohort shift; raw interaction is confounded.", "",
		"| Workload | Arm | Full planning median [min,max] | Optimizer median [min,max] | Reduced values / cells | Child / full lookups |",
		"|---|---|---:|---:|---:|---:|"]
	for row in report["stats"]:
		w = "steplm-compatible" if row["workload"] == "steplm" else row["workload"]
		values = [f"{row[f + '_median']:.3f} [{row[f + '_min']:.3f},{row[f + '_max']:.3f}]"
			for f in TIMINGS[:2]]
		lines.append(f"| {w} | {row['arm']} | " + " | ".join(values) +
			f" | {row['reducedValues_median']:.0f} / {row['reducedCells_median']:.0f} | " +
			f"{row['childEvaluations_median']:.0f} / {row['fullChildEvaluations_median']:.0f} |")
	(output / "tables.md").write_text("\n".join(lines) + "\n")


def main(argv=None):
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--reused-root", type=Path, required=True)
	parser.add_argument("--new-root", type=Path, required=True)
	parser.add_argument("--output", type=Path, required=True)
	args = parser.parse_args(argv)
	try:
		write_outputs(analyze(args.reused_root.resolve(), args.new_root.resolve()), args.output.resolve())
	except receipt.AnalysisError as error:
		print(f"analysis rejected: {error}", file=sys.stderr)
		return 2
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
