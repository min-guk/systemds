#!/usr/bin/env python3
"""Build the authenticated, no-duplicate interim 274-cell result table."""

from __future__ import annotations

import csv
import hashlib
import json
import pathlib
import statistics
import sys


PLANNERS = ("DP", "FedAll", "Heuristic", "MinST")
WORKLOADS = ("kmeans", "pca", "lm", "l2svm", "logreg", "als", "steplm")
PROFILES = ("lan", "wan_light", "wan_mid")


def sha256(path: pathlib.Path) -> str:
	return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: pathlib.Path) -> dict:
	return json.loads(path.read_text())


def parse_cell(cell: str) -> dict[str, str]:
	parts = dict(part.split("=", 1) for part in cell.split("|"))
	if set(parts) != {"workers", "planner", "workload", "profile"}:
		raise ValueError(f"non-canonical cell: {cell}")
	return parts


def verified_row(row: dict, expected_cell: str, expected_response_sha: str) -> None:
	if row["cell"] != expected_cell:
		raise ValueError(f"row/cell mismatch: {row['cell']} != {expected_cell}")
	if row["response_sha256"] != expected_response_sha:
		raise ValueError(f"row/response mismatch: {expected_cell}")
	if row["attempt"] != 1 or not row["oracle_passed"] or row["fallback"]:
		raise ValueError(f"unauthenticated success row: {expected_cell}")


def materialize(row: dict, provenance: dict) -> dict:
	parts = parse_cell(row["cell"])
	return {
		"cell": row["cell"],
		"workers": int(parts["workers"]),
		"planner": parts["planner"],
		"workload": parts["workload"],
		"profile": parts["profile"],
		"execution_seconds": float(row["execution_seconds"]),
		"full_lifecycle_seconds": float(row["full_lifecycle_seconds"]),
		"attempt": int(row["attempt"]),
		"oracle_passed": bool(row["oracle_passed"]),
		"fallback": bool(row["fallback"]),
		"response_sha256": row["response_sha256"],
		**provenance,
	}


def main() -> None:
	if len(sys.argv) != 3:
		raise SystemExit("usage: build_interim_dataset.py CAMPAIGN_ROOT OUTPUT_DIR")
	root = pathlib.Path(sys.argv[1]).resolve()
	out = pathlib.Path(sys.argv[2]).resolve()
	out.mkdir(parents=True, exist_ok=True)
	registry_path = root / "base-completed.json"
	registry = read_json(registry_path)
	rows: list[dict] = []

	for entry in registry["cells"]:
		row_path = pathlib.Path(entry["rows_path"])
		line = row_path.read_text().splitlines()[entry["row_number"] - 1]
		row = json.loads(line)
		response_path = pathlib.Path(entry["response_path"])
		actual_response_sha = sha256(response_path)
		if actual_response_sha != entry["response_sha256"]:
			raise ValueError(f"response hash mismatch: {entry['cell']}")
		verified_row(row, entry["cell"], actual_response_sha)
		response = read_json(response_path)
		if (response.get("success") is not True
				or response.get("teardown_zero_resources") is not True
				or response.get("coordinator_restart_count") != 0
				or response.get("worker_restart_count") != 0):
			raise ValueError(f"response lifecycle proof failed: {entry['cell']}")
		rows.append(materialize(row, {
			"source_kind": entry["source_kind"],
			"campaign_root": entry["campaign_root"],
			"stage": entry["stage"],
			"systemds_commit": entry["systemds_commit"],
			"systemds_jar_sha256": entry["systemds_jar_sha256"],
			"response_path": str(response_path),
		}))

	by_cell = {row["cell"]: row for row in rows}
	if len(rows) != len(by_cell):
		raise ValueError(f"duplicate cell: rows={len(rows)} unique={len(by_cell)}")
	if len(rows) != 274 or registry["completed_cells"] != 274:
		raise ValueError("interim cardinality is not 274")

	rows.sort(key=lambda row: (
		PLANNERS.index(row["planner"]), row["workers"],
		WORKLOADS.index(row["workload"]), PROFILES.index(row["profile"])))
	fieldnames = list(rows[0])
	csv_path = out / "authenticated_rows_274.csv"
	with csv_path.open("w", newline="") as handle:
		writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
		writer.writeheader()
		writer.writerows(rows)

	counts = {planner: sum(row["planner"] == planner for row in rows) for planner in PLANNERS}
	matched: dict[tuple[int, str, str], dict[str, float]] = {}
	for row in rows:
		key = (row["workers"], row["workload"], row["profile"])
		matched.setdefault(key, {})[row["planner"]] = row["execution_seconds"]
	matched = {key: value for key, value in matched.items() if set(value) == set(PLANNERS)}

	def passes(values: dict[str, float], tolerance: float) -> bool:
		return (values["MinST"] <= values["DP"] * (1 + tolerance)
			and values["DP"] <= values["Heuristic"] * (1 + tolerance)
			and values["DP"] <= values["FedAll"] * (1 + tolerance))

	ratios = {
		planner: [values[planner] / values["DP"] for values in matched.values()]
		for planner in PLANNERS
	}
	summary = {
		"schema": "g007-interim-authenticated-performance/v1",
		"status": "interim-incomplete-not-final",
		"completed_unique_cells": len(rows),
		"canonical_total_cells": 336,
		"remaining_cells": 62,
		"counts_by_planner": counts,
		"matched_four_planner_cells": len(matched),
		"ordering_contract": "MinST <= DP and DP <= Heuristic and DP <= FedAll",
		"ordering_exact_passes": sum(passes(values, 0.0) for values in matched.values()),
		"ordering_exact_failures": sum(not passes(values, 0.0) for values in matched.values()),
		"ordering_5pct_passes": sum(passes(values, 0.05) for values in matched.values()),
		"ordering_5pct_failures": sum(not passes(values, 0.05) for values in matched.values()),
		"median_runtime_ratio_to_dp": {
			planner: statistics.median(values) for planner, values in ratios.items()
		},
		"minimum_execution_seconds": min(row["execution_seconds"] for row in rows),
		"maximum_execution_seconds": max(row["execution_seconds"] for row in rows),
		"provenance_warning": (
			"Stitched no-duplicate Docker successes span multiple committed binaries; "
			"this interim view is diagnostic and is not a homogeneous final performance run."
		),
		"base_registry_sha256": sha256(registry_path),
		"base_registry_descriptor_sha256": registry["descriptor_sha256"],
		"csv_sha256": sha256(csv_path),
	}
	(out / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
	print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
	main()
