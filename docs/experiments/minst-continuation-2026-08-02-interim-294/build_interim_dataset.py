#!/usr/bin/env python3
"""Build an authenticated, no-duplicate interim result table from a frozen snapshot."""
from __future__ import annotations

import csv
import hashlib
import json
import math
import pathlib
import statistics
import sys


PLANNERS = ("DP", "FedAll", "Heuristic", "MinST")
WORKLOADS = ("kmeans", "pca", "lm", "l2svm", "logreg", "als", "steplm")
PROFILES = ("lan", "wan_light", "wan_mid")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(pathlib.Path(path).read_text())


def parse(cell):
    parts = dict(piece.split("=", 1) for piece in cell.split("|"))
    if set(parts) != {"workers", "planner", "workload", "profile"}:
        raise ValueError(f"non-canonical cell: {cell}")
    return parts


def verify_descriptor(path, schema):
    descriptor = read(path)
    if descriptor.get("schema") != schema:
        raise ValueError(f"schema mismatch: {path}")
    claimed = descriptor.pop("descriptor_sha256", None)
    if claimed != hashlib.sha256(canonical(descriptor)).hexdigest():
        raise ValueError(f"descriptor mismatch: {path}")
    descriptor["descriptor_sha256"] = claimed
    return descriptor


def verify_row(row, cell, response_sha):
    if row["cell"] != cell or row["response_sha256"] != response_sha:
        raise ValueError(f"row binding mismatch: {cell}")
    if row["attempt"] != 1 or row["oracle_passed"] is not True or row["fallback"] is not False:
        raise ValueError(f"row contract: {cell}")


def verify_lifecycle(row, response_path):
    response = read(response_path)
    claimed = response.pop("descriptor_sha256", None)
    if claimed != hashlib.sha256(canonical(response)).hexdigest():
        raise ValueError(f"response descriptor: {row['cell']}")
    bundle = pathlib.Path(row["bundle"])
    metric = read(bundle / "metric.json")
    oracle = read(bundle / "semantic_oracle.json")
    scan = read(bundle / "scan.json")
    seconds = metric.get("seconds")
    if (
        response.get("success") is not True
        or response.get("teardown_zero_resources") is not True
        or response.get("coordinator_restart_count") != 0
        or response.get("worker_restart_count") != 0
        or oracle.get("passed") is not True
        or any(scan.get(key) is not False for key in ("error", "fallback", "resource_invalid", "timeout"))
        or isinstance(seconds, bool)
        or not isinstance(seconds, (int, float))
        or not math.isfinite(seconds)
        or seconds <= 0
        or seconds != row["execution_seconds"]
    ):
        raise ValueError(f"lifecycle proof: {row['cell']}")


def materialize(row, provenance):
    parts = parse(row["cell"])
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


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: build_interim_dataset.py CAMPAIGN_ROOT OUTPUT_DIR")
    root = pathlib.Path(sys.argv[1]).resolve()
    out = pathlib.Path(sys.argv[2]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    registry_path = root / "base-completed.json"
    registry = verify_descriptor(registry_path, "g007-completed-cell-registry/v1")
    manifest = read(root / "planners/MinST/manifest.json")
    launch = root / "control/launch-receipt.json"
    snapshot = out / "continuation_rows_snapshot.jsonl"

    rows = []
    for entry in registry["cells"]:
        source_rows = pathlib.Path(entry["rows_path"]).read_text().splitlines()
        row = json.loads(source_rows[entry["row_number"] - 1])
        response = pathlib.Path(entry["response_path"])
        actual = sha(response)
        if actual != entry["response_sha256"]:
            raise ValueError(f"response hash: {entry['cell']}")
        verify_row(row, entry["cell"], actual)
        verify_lifecycle(row, response)
        rows.append(
            materialize(
                row,
                {
                    "source_kind": entry["source_kind"],
                    "campaign_root": entry["campaign_root"],
                    "stage": entry["stage"],
                    "systemds_commit": entry["systemds_commit"],
                    "systemds_jar_sha256": entry["systemds_jar_sha256"],
                    "response_path": str(response),
                },
            )
        )

    continuation = [json.loads(line) for line in snapshot.read_text().splitlines() if line]
    if not continuation:
        raise ValueError("continuation snapshot is empty")
    for row in continuation:
        bundle = pathlib.Path(row["bundle"])
        response = bundle.parents[2] / "response.json"
        actual = sha(response)
        verify_row(row, row["cell"], actual)
        verify_lifecycle(row, response)
        rows.append(
            materialize(
                row,
                {
                    "source_kind": "e18d326-unfinished-only-continuation-success-prefix",
                    "campaign_root": str(root),
                    "stage": manifest["stage_descriptor"].removesuffix("/stage-descriptor.json"),
                    "systemds_commit": manifest["systemds_commit"],
                    "systemds_jar_sha256": manifest["systemds_jar_sha256"],
                    "response_path": str(response),
                },
            )
        )

    by_cell = {row["cell"]: row for row in rows}
    expected = registry["completed_cells"] + len(continuation)
    if len(rows) != expected or len(by_cell) != expected:
        raise ValueError(f"cardinality rows={len(rows)} unique={len(by_cell)} expected={expected}")
    rows.sort(
        key=lambda row: (
            PLANNERS.index(row["planner"]),
            row["workers"],
            WORKLOADS.index(row["workload"]),
            PROFILES.index(row["profile"]),
        )
    )
    csv_path = out / f"authenticated_rows_{expected}.csv"
    with csv_path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

    counts = {planner: sum(row["planner"] == planner for row in rows) for planner in PLANNERS}
    matched = {}
    for row in rows:
        key = (row["workers"], row["workload"], row["profile"])
        matched.setdefault(key, {})[row["planner"]] = row["execution_seconds"]
    matched = {key: value for key, value in matched.items() if set(value) == set(PLANNERS)}

    def ordered(values, tolerance):
        return (
            values["MinST"] <= values["DP"] * (1 + tolerance)
            and values["DP"] <= values["Heuristic"] * (1 + tolerance)
            and values["DP"] <= values["FedAll"] * (1 + tolerance)
        )

    ratios = {planner: [values[planner] / values["DP"] for values in matched.values()] for planner in PLANNERS}
    summary = {
        "schema": "g007-interim-authenticated-performance/v1",
        "status": "interim-incomplete-not-final" if expected < 336 else "complete",
        "completed_unique_cells": expected,
        "canonical_total_cells": 336,
        "remaining_cells": 336 - expected,
        "counts_by_planner": counts,
        "matched_four_planner_cells": len(matched),
        "ordering_contract": "MinST <= DP and DP <= Heuristic and DP <= FedAll",
        "ordering_exact_passes": sum(ordered(values, 0) for values in matched.values()),
        "ordering_exact_failures": sum(not ordered(values, 0) for values in matched.values()),
        "ordering_5pct_passes": sum(ordered(values, 0.05) for values in matched.values()),
        "ordering_5pct_failures": sum(not ordered(values, 0.05) for values in matched.values()),
        "median_runtime_ratio_to_dp": {
            planner: statistics.median(values) for planner, values in ratios.items()
        },
        "minimum_execution_seconds": min(row["execution_seconds"] for row in rows),
        "maximum_execution_seconds": max(row["execution_seconds"] for row in rows),
        "provenance_warning": (
            "Stitched no-duplicate Docker successes span multiple committed binaries; "
            "this interim view is diagnostic and is not a homogeneous final performance run."
        ),
        "base_registry_sha256": sha(registry_path),
        "base_registry_descriptor_sha256": registry["descriptor_sha256"],
        "continuation_snapshot_rows": len(continuation),
        "continuation_snapshot_sha256": sha(snapshot),
        "launch_receipt_sha256": sha(launch),
        "csv_sha256": sha(csv_path),
    }
    (out / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
