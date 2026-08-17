#!/usr/bin/env python3
"""Extract compiler phase and federated-planner timers from Docker campaign logs."""

from __future__ import annotations

import argparse
import csv
import json
import mmap
import re
import statistics
from collections import defaultdict
from pathlib import Path


SECONDS = {
	"total_compile": re.compile(r"^Total compilation time:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"parse": re.compile(r"^Compile Phase Parse:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"hops_build": re.compile(r"^Compile Phase HopsBuild:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"hops_rewrite": re.compile(r"^Compile Phase HopsRewrite:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"lops_build": re.compile(r"^Compile Phase LopsBuild:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"lops_rewrite": re.compile(r"^Compile Phase LopsRewrite:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"runtime_program": re.compile(r"^Compile Phase RuntimeProgram:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"fed_planner": re.compile(r"^Compile Phase FedPlanner:\s+([0-9.]+) sec\.$", re.MULTILINE),
	"execution": re.compile(r"^Total execution time:\s+([0-9.]+) sec\.$", re.MULTILINE),
}
HOPS = re.compile(r"^Compile Observed HOPs:\s+([0-9]+)\.$", re.MULTILINE)
AUDIT_MISMATCHES = re.compile(r"\bmismatches=([0-9]+)\b")
PLANNING_RECEIPT = re.compile(r"^PLANNING_RECEIPT=(.+\.json)$", re.MULTILINE)
MINST_OPTIMIZE = re.compile(
	r"\[PlannerTrace\]\[MinST-PhysicalOptimize\].*?variables=([0-9]+)"
	r"\s+hardFactors=([0-9]+)\s+costFactors=([0-9]+)\s+transfers=([0-9]+)"
	r"\s+inducedWidth=([0-9]+)\s+maximumFactorCells=([0-9]+)"
	r"\s+materializedFactorCells=([0-9]+)\s+maximumEliminationAssignments=([0-9]+)"
	r"\s+eliminationAssignments=([0-9]+)"
)
EXACT_SEARCH_COMPLETE = re.compile(
	r"\[PlannerTrace\]\[Exact-Search-Complete\]\s+decisions=([0-9]+)\s+groups=([0-9]+)"
	r"\s+prefixes=([0-9]+)\s+explored=([0-9]+)\s+pruned=([0-9]+)"
)
CANDIDATE_SEARCH_COMPLETE = re.compile(
	r"\[PlannerTrace\]\[Candidate-Search-Complete\].*?leaves=([0-9]+)"
	r"\s+physical=([0-9]+).*?incumbents=([0-9]+)"
)
PREFIXES = {
	"total_compile": b"Total compilation time:",
	"parse": b"Compile Phase Parse:",
	"hops_build": b"Compile Phase HopsBuild:",
	"hops_rewrite": b"Compile Phase HopsRewrite:",
	"lops_build": b"Compile Phase LopsBuild:",
	"lops_rewrite": b"Compile Phase LopsRewrite:",
	"runtime_program": b"Compile Phase RuntimeProgram:",
	"fed_planner": b"Compile Phase FedPlanner:",
	"execution": b"Total execution time:",
}


def parse_args() -> argparse.Namespace:
	parser = argparse.ArgumentParser()
	parser.add_argument("results_root", type=Path)
	parser.add_argument("--phase", default="warm-fresh-coordinator-jvm")
	return parser.parse_args()


def parse_compile_header(log_path: Path) -> tuple[dict[str, float | None], int | str, int | str]:
	"""Search timer records without copying runtime-audit logs into Python memory."""
	values: dict[str, float | None] = {name: None for name in SECONDS}
	hop_count: int | str = ""
	audit_mismatches: int | str = ""
	with log_path.open("rb") as handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
		def line_for(prefix: bytes) -> str | None:
			start = mapped.find(prefix)
			if start < 0:
				return None
			end = mapped.find(b"\n", start)
			if end < 0:
				end = len(mapped)
			return mapped[start:end].decode(errors="replace")

		for name, prefix in PREFIXES.items():
			line = line_for(prefix)
			match = SECONDS[name].match(line) if line is not None else None
			if match:
				values[name] = float(match.group(1))
		hop_line = line_for(b"Compile Observed HOPs:")
		hop_match = HOPS.match(hop_line) if hop_line is not None else None
		if hop_match:
			hop_count = int(hop_match.group(1))
		audit_line = line_for(b"[PlannerRuntimeAudit][Summary]")
		audit_match = AUDIT_MISMATCHES.search(audit_line) if audit_line is not None else None
		if audit_match:
			audit_mismatches = int(audit_match.group(1))
	return values, hop_count, audit_mismatches


def planning_receipt_path(cell_dir: Path) -> Path | None:
	stdout = cell_dir / "runner.stdout.log"
	if not stdout.is_file():
		return None
	matches = PLANNING_RECEIPT.findall(stdout.read_text(errors="replace"))
	if not matches:
		return None
	path = Path(matches[-1])
	return path if path.is_file() else None


def planner_trace_metrics(log_path: Path | None) -> dict[str, int | str]:
	metrics: dict[str, int | str] = {
		"exact_search_calls": 0,
		"exact_search_sum_prefixes": 0,
		"exact_search_sum_explored": 0,
		"exact_search_sum_pruned": 0,
		"exact_search_max_decisions": 0,
		"exact_search_max_groups": 0,
		"exact_search_max_prefixes": 0,
		"candidate_search_calls": 0,
		"candidate_search_sum_leaves": 0,
		"candidate_search_sum_physical": 0,
		"candidate_search_sum_incumbents": 0,
		"minst_variables": "",
		"minst_hard_factors": "",
		"minst_cost_factors": "",
		"minst_transfers": "",
		"minst_induced_width": "",
		"minst_maximum_factor_cells": "",
		"minst_materialized_factor_cells": "",
		"minst_maximum_elimination_assignments": "",
		"minst_elimination_assignments": "",
	}
	if log_path is None or not log_path.is_file():
		return metrics
	with log_path.open(errors="replace") as handle:
		for line in handle:
			exact = EXACT_SEARCH_COMPLETE.search(line)
			if exact:
				decisions, groups, prefixes, explored, pruned = map(int, exact.groups())
				metrics["exact_search_calls"] = int(metrics["exact_search_calls"]) + 1
				metrics["exact_search_sum_prefixes"] = int(metrics["exact_search_sum_prefixes"]) + prefixes
				metrics["exact_search_sum_explored"] = int(metrics["exact_search_sum_explored"]) + explored
				metrics["exact_search_sum_pruned"] = int(metrics["exact_search_sum_pruned"]) + pruned
				metrics["exact_search_max_decisions"] = max(
					int(metrics["exact_search_max_decisions"]), decisions)
				metrics["exact_search_max_groups"] = max(int(metrics["exact_search_max_groups"]), groups)
				metrics["exact_search_max_prefixes"] = max(int(metrics["exact_search_max_prefixes"]), prefixes)
			candidate = CANDIDATE_SEARCH_COMPLETE.search(line)
			if candidate:
				leaves, physical, incumbents = map(int, candidate.groups())
				metrics["candidate_search_calls"] = int(metrics["candidate_search_calls"]) + 1
				metrics["candidate_search_sum_leaves"] = int(metrics["candidate_search_sum_leaves"]) + leaves
				metrics["candidate_search_sum_physical"] = int(metrics["candidate_search_sum_physical"]) + physical
				metrics["candidate_search_sum_incumbents"] = (
					int(metrics["candidate_search_sum_incumbents"]) + incumbents)
			minst = MINST_OPTIMIZE.search(line)
			if minst:
				keys = (
					"minst_variables", "minst_hard_factors", "minst_cost_factors", "minst_transfers",
					"minst_induced_width", "minst_maximum_factor_cells",
					"minst_materialized_factor_cells", "minst_maximum_elimination_assignments",
					"minst_elimination_assignments",
				)
				metrics.update(dict(zip(keys, map(int, minst.groups()))))
			if "[PlannerTrace][Planner-Complete]" in line:
				break
	return metrics


def planning_metrics(cell_dir: Path) -> dict[str, object]:
	path = planning_receipt_path(cell_dir)
	if path is None:
		return {"planning_receipt": ""}
	receipt = json.loads(path.read_text())
	stage_counts = receipt.get("planner_trace_stage_counts", {})
	def count(stage: str) -> int:
		return int(stage_counts.get(stage, 0))
	result: dict[str, object] = {
		"planning_receipt": str(path),
		"emission_compiled_occurrences": int(receipt.get("emission_compiledOccurrences", 0)),
		"emission_decisions": int(receipt.get("emission_decisions", 0)),
		"emission_synthetic_decisions": int(receipt.get("emission_syntheticDecisions", 0)),
		"emission_selected_fed": int(receipt.get("emission_selectedFED", 0)),
		"emission_selected_fout": int(receipt.get("emission_selectedFOUT", 0)),
		"emission_selected_derived_fout": int(receipt.get("emission_selectedDerivedFOUT", 0)),
		"emission_relocations": int(receipt.get("emission_relocations", 0)),
		"emission_local_materializations": int(receipt.get("emission_localMaterializations", 0)),
		"planner_trace_count": int(receipt.get("planner_trace_count", 0)),
		"neutral_physical_closure": count("Neutral-PhysicalClosure"),
		"neutral_transient_replay": count("Neutral-TransientReplay"),
		"planner_recompile_state_repeat": count("PlannerRecompileState-Repeat"),
		"dp_candidate_evaluations": count("DP-Candidate"),
		"dp_boundary_share_evaluations": count("DP-BoundaryShare"),
		"dp_output_decision_entries": count("DP-OutputDecision-Entry"),
		"dp_decision_map_scores": count("DP-DecisionMap-Score"),
		"dp_parent_variant_candidates": count("DP-ParentVariantCandidate"),
		"dp_component_decisions": count("DP-ComponentDecision"),
		"dp_required_output_closures": count("DP-RequiredOutputClosure"),
		"runtime_federated_instruction_count": sum(
			int(token.rsplit(":", 1)[1]) for token in receipt.get("runtime_plan_fed_fingerprint", "").split(";")
			if ":" in token and token.rsplit(":", 1)[1].isdigit()
		),
	}
	coordinator_log = receipt.get("coordinator_log")
	result.update(planner_trace_metrics(Path(coordinator_log) if coordinator_log else None))
	return result


def read_rows(root: Path, phase: str) -> list[dict[str, object]]:
	latest: dict[str, tuple[int, float, dict[str, object]]] = {}
	for response_path in sorted((root / "cells").glob("*/response.json")):
		response = json.loads(response_path.read_text())
		if not response.get("success"):
			continue
		cell_dir = response_path.parent
		request = json.loads((cell_dir / "request.json").read_text())
		log_path = cell_dir / "phases" / "cell-1" / phase / "raw_coordinator.log"
		if not log_path.is_file():
			continue
		values, hop_count, audit_mismatches = parse_compile_header(log_path)
		if any(value is None for value in values.values()):
			continue
		dim = response["dimensions"]
		total_compile = float(values["total_compile"])
		fed_planner = float(values["fed_planner"])
		lops_build = float(values["lops_build"])
		execution = float(values["execution"])
		other_compile = total_compile - sum(
			float(values[name])
			for name in (
				"parse",
				"hops_build",
				"hops_rewrite",
				"lops_build",
				"lops_rewrite",
				"runtime_program",
			)
		)
		row: dict[str, object] = {
			"profile": dim["profile"],
			"workload": dim["workload"],
			"planner": dim["planner"],
			"workers": int(dim["workers"]),
			"phase": phase,
			"hop_count": hop_count,
			**values,
			# FedPlanner is nested inside LopsBuild; these values must not be added.
			"lops_build_excluding_fed_planner": lops_build - fed_planner,
			"other_compile": other_compile,
			"planner_compile_ratio": fed_planner / total_compile if total_compile else "",
			"compile_runtime_ratio": total_compile / execution if execution else "",
			"audit_mismatches": audit_mismatches,
			"attempt": int(request.get("identity", {}).get("attempt", 0)),
			"cell": dim["canonical_cell"],
			"log_path": str(log_path),
			**planning_metrics(cell_dir),
		}
		key = str(dim["canonical_cell"])
		candidate = (int(row["attempt"]), response_path.stat().st_mtime, row)
		if key not in latest or candidate[:2] > latest[key][:2]:
			latest[key] = candidate
	return sorted(
		(item[2] for item in latest.values()),
		key=lambda row: (str(row["profile"]), str(row["workload"]), str(row["planner"]), int(row["workers"])),
	)


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
	path.parent.mkdir(parents=True, exist_ok=True)
	if not rows:
		path.write_text("")
		return
	with path.open("w", newline="") as handle:
		writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
		writer.writeheader()
		writer.writerows(rows)


def summarize(rows: list[dict[str, object]]) -> list[dict[str, object]]:
	groups: dict[tuple[str, str, str], list[dict[str, object]]] = defaultdict(list)
	for row in rows:
		groups[(str(row["profile"]), str(row["workload"]), str(row["planner"]))].append(row)
	result: list[dict[str, object]] = []
	for (profile, workload, planner), group in sorted(groups.items()):
		def mean(field: str) -> float:
			return statistics.fmean(float(row[field]) for row in group)

		result.append({
			"profile": profile,
			"workload": workload,
			"planner": planner,
			"cells": len(group),
			"mean_hop_count": mean("hop_count"),
			"mean_total_compile": mean("total_compile"),
			"mean_hops_rewrite": mean("hops_rewrite"),
			"mean_lops_build": mean("lops_build"),
			"mean_fed_planner": mean("fed_planner"),
			"mean_lops_build_excluding_fed_planner": mean("lops_build_excluding_fed_planner"),
			"mean_runtime_program": mean("runtime_program"),
			"mean_other_compile": mean("other_compile"),
			"mean_execution": mean("execution"),
			"mean_planner_compile_ratio": mean("planner_compile_ratio"),
			"mean_compile_runtime_ratio": mean("compile_runtime_ratio"),
			"compile_exceeds_runtime_cells": sum(
				float(row["total_compile"]) > float(row["execution"]) for row in group
			),
			"audit_mismatch_cells": sum(int(row["audit_mismatches"] or 0) > 0 for row in group),
		})
	return result


def summarize_complexity(rows: list[dict[str, object]]) -> list[dict[str, object]]:
	groups: dict[tuple[str, str, str], list[dict[str, object]]] = defaultdict(list)
	for row in rows:
		groups[(str(row["profile"]), str(row["workload"]), str(row["planner"]))].append(row)
	fields = (
		"hop_count", "emission_compiled_occurrences", "emission_decisions", "planner_trace_count",
		"neutral_physical_closure", "neutral_transient_replay", "planner_recompile_state_repeat",
		"dp_candidate_evaluations", "dp_boundary_share_evaluations", "dp_output_decision_entries",
		"dp_decision_map_scores", "dp_parent_variant_candidates", "dp_component_decisions",
		"dp_required_output_closures", "exact_search_calls", "exact_search_sum_prefixes",
		"exact_search_sum_explored", "exact_search_sum_pruned", "exact_search_max_decisions",
		"exact_search_max_groups", "exact_search_max_prefixes", "candidate_search_calls",
		"candidate_search_sum_leaves", "candidate_search_sum_physical", "minst_variables",
		"minst_hard_factors", "minst_cost_factors", "minst_transfers", "minst_induced_width",
		"minst_maximum_factor_cells", "minst_materialized_factor_cells",
		"minst_maximum_elimination_assignments", "minst_elimination_assignments",
		"emission_selected_fed", "emission_selected_fout", "emission_selected_derived_fout",
		"emission_relocations", "emission_local_materializations", "runtime_federated_instruction_count",
	)
	result: list[dict[str, object]] = []
	for (profile, workload, planner), group in sorted(groups.items()):
		row: dict[str, object] = {
			"profile": profile,
			"workload": workload,
			"planner": planner,
			"cells": len(group),
			"mean_fed_planner": statistics.fmean(float(value["fed_planner"]) for value in group),
		}
		for field in fields:
			values = [float(value[field]) for value in group if value.get(field, "") != ""]
			row["mean_" + field] = statistics.fmean(values) if values else ""
		result.append(row)
	return result


def main() -> None:
	args = parse_args()
	rows = read_rows(args.results_root, args.phase)
	if not rows:
		raise SystemExit("no complete timer rows found")
	analysis = args.results_root / "analysis"
	write_csv(analysis / "compile_breakdown_latest.csv", rows)
	write_csv(analysis / "compile_breakdown_summary_latest.csv", summarize(rows))
	write_csv(analysis / "planner_complexity_summary_latest.csv", summarize_complexity(rows))
	write_csv(
		analysis / "compile_exceeds_runtime_latest.csv",
		[row for row in rows if float(row["total_compile"]) > float(row["execution"])],
	)
	print(f"wrote {len(rows)} latest successful {args.phase} rows to {analysis}")


if __name__ == "__main__":
	main()
