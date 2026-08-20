#!/usr/bin/env python3
"""Render authenticated partial-campaign runtime and compile-time grids."""

from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import time
from typing import Any


PROFILES = ("lan", "wan_light", "wan_mid")
WORKLOADS = ("pca", "lm", "kmeans", "l2svm", "logreg", "als", "steplm")
PLANNERS = ("FedAll", "Heuristic", "DP", "Exact")
WORKERS = (1, 2, 3, 4)
COMPILATION_PATTERN = re.compile(
    r"^Total compilation time:\s*([0-9]+(?:\.[0-9]+)?) sec\.$", re.MULTILINE
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def validate_row(row: dict[str, Any], manifest_hash: str) -> dict[str, Any]:
    profile = str(row["profile"])
    workload = str(row["workload"])
    planner = str(row["planner"])
    workers = int(row["workers"])
    if profile not in PROFILES or workload not in WORKLOADS:
        raise RuntimeError("unexpected profile/workload")
    if planner not in PLANNERS or workers not in WORKERS:
        raise RuntimeError("unexpected planner/workers")
    cell = f"workers={workers}|planner={planner}|workload={workload}|profile={profile}"
    if row.get("cell") != cell or row.get("manifest_sha256") != manifest_hash:
        raise RuntimeError("cell identity or manifest link mismatch")
    trace_counts = row.get("planning_evidence", {}).get("planner_trace_stage_counts", {})
    if not (
        row.get("oracle_passed") is True
        and row.get("runtime_scan_clean") is True
        and row.get("teardown_zero_resources") is True
        and row.get("fallback") is False
        and row.get("primary_metric_phase") == "warm-fresh-coordinator-jvm"
        and row.get("runtime_plan_sha256") == row.get("cold_runtime_plan_sha256")
        and row.get("instruction_fingerprint") == row.get("cold_instruction_fingerprint")
        and trace_counts.get("Planner-Invoke") == 1
        and trace_counts.get("Planner-Complete") == 1
        and trace_counts.get("Emission-Summary") == 1
    ):
        raise RuntimeError("authenticated successful-row contract failed")

    execution_seconds = float(row["execution_seconds"])
    warm_seconds = float(row["warm_evidence"]["seconds"])
    if not math.isfinite(execution_seconds) or execution_seconds <= 0:
        raise RuntimeError("invalid execution time")
    if not math.isclose(execution_seconds, warm_seconds, rel_tol=0, abs_tol=1e-9):
        raise RuntimeError("warm execution metric mismatch")

    warm_log = Path(row["warm_bundle"]) / "raw_coordinator.log"
    if not warm_log.is_file():
        raise RuntimeError("missing warm raw coordinator log")
    warm_hash = sha256_file(warm_log)
    if warm_hash != row["warm_evidence"]["raw_coordinator_sha256"]:
        raise RuntimeError("warm raw coordinator hash mismatch")
    matches = COMPILATION_PATTERN.findall(
        warm_log.read_text(encoding="utf-8", errors="replace")
    )
    if len(matches) != 1:
        raise RuntimeError(f"expected one compilation metric, found {len(matches)}")
    compilation_seconds = float(matches[0])
    if not math.isfinite(compilation_seconds) or compilation_seconds < 0:
        raise RuntimeError("invalid compilation time")

    return {
        "profile": profile,
        "workload": workload,
        "workers": workers,
        "planner": planner,
        "execution_seconds": execution_seconds,
        "total_compilation_seconds": compilation_seconds,
        "attempt": int(row.get("attempt", 0)),
        "cell": cell,
        "warm_bundle": str(row["warm_bundle"]),
        "warm_raw_coordinator_sha256": warm_hash,
        "runtime_plan_sha256": str(row["runtime_plan_sha256"]),
    }


def read_stable_rows(root: Path) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    manifest = load_json(root / "manifest.json")
    manifest_hash = str(manifest["manifest_sha256"])
    rows_path = root / "rows.jsonl"
    last_error = "campaign did not become snapshot-stable"
    for _ in range(40):
        before = load_json(root / "progress.json")
        raw_rows = []
        try:
            with rows_path.open(encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, start=1):
                    if line.strip():
                        raw_rows.append((line_number, json.loads(line)))
        except json.JSONDecodeError as error:
            last_error = f"rows.jsonl changed during read: {error}"
            time.sleep(0.25)
            continue
        after = load_json(root / "progress.json")
        if before != after or before.get("completed") != len(raw_rows):
            last_error = (
                f"unstable progress/row count: before={before.get('completed')} "
                f"rows={len(raw_rows)} after={after.get('completed')}"
            )
            time.sleep(0.25)
            continue

        selected: dict[str, tuple[int, dict[str, Any]]] = {}
        for line_number, raw in raw_rows:
            validated = validate_row(raw, manifest_hash)
            prior = selected.get(validated["cell"])
            rank = (validated["attempt"], line_number)
            if prior is None or rank > (prior[1]["attempt"], prior[0]):
                selected[validated["cell"]] = (line_number, validated)
        rows = [item[1] for item in selected.values()]
        if len(rows) != int(before["completed"]):
            raise RuntimeError("duplicate canonical successful cells in immutable campaign")
        return sorted(rows, key=lambda row: row["cell"]), before, manifest
    raise RuntimeError(last_error)


def runtime_separation(rows: list[dict[str, Any]], workload: str) -> float:
    spreads = []
    for profile in PROFILES:
        for workers in WORKERS:
            values = {
                row["planner"]: float(row["execution_seconds"])
                for row in rows
                if row["profile"] == profile
                and row["workload"] == workload
                and row["workers"] == workers
            }
            if set(values) == set(PLANNERS):
                spreads.append(max(values.values()) - min(values.values()))
    if not spreads:
        return math.inf
    return max(spreads)


def write_snapshot(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = (
        "profile",
        "workload",
        "workers",
        "planner",
        "execution_seconds",
        "total_compilation_seconds",
        "attempt",
        "cell",
        "warm_bundle",
        "warm_raw_coordinator_sha256",
        "runtime_plan_sha256",
    )
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def render(
    plotter: Path,
    snapshot: Path,
    metric: str,
    ylabel: str,
    title: str,
    subtitle: str,
    footer: str,
    column_order: list[str],
    total: int,
    png: Path,
    svg: Path,
) -> None:
    command = [
        "Rscript",
        str(plotter),
        str(snapshot),
        metric,
        ylabel,
        title,
        subtitle,
        footer,
        ",".join(column_order),
        str(total),
        str(png),
        str(svg),
    ]
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"R plot failed:\n{result.stdout}\n{result.stderr}")
    if not png.is_file() or not svg.is_file():
        raise RuntimeError("plot command returned without both output files")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result_root", type=Path)
    args = parser.parse_args()
    root = args.result_root.resolve()
    rows, progress, manifest = read_stable_rows(root)
    completed = len(rows)
    total = int(progress["total"])
    plots = root / "plots"
    plots.mkdir(parents=True, exist_ok=True)
    snapshot = plots / f"fixed_stage_snapshot_{completed}of{total}.csv"
    write_snapshot(snapshot, rows)

    canonical_rank = {workload: index for index, workload in enumerate(WORKLOADS)}
    separation = {workload: runtime_separation(rows, workload) for workload in WORKLOADS}
    column_order = sorted(
        WORKLOADS,
        key=lambda workload: (separation[workload], canonical_rank[workload]),
    )
    generated_at = datetime.now().astimezone().isoformat(timespec="seconds")
    subtitle = (
        "Rows: LAN -> WAN-Light -> WAN-Mid; columns: increasing max runtime spread "
        "over complete 4-planner blocks; missing cells are not interpolated"
    )
    footer = (
        f"source {str(manifest['systemds_commit'])[:7]} | harness "
        f"{str(manifest['harness_commit'])[:7]} | fixed seed {manifest['campaign_seed']} | "
        "warm fresh-coordinator JVM"
    )
    plotter = Path(__file__).with_suffix(".R")
    prefix = f"{completed}of{total}"
    outputs = {
        "runtime_png": plots / f"runtime_grid_3x7_fixed_stage_{prefix}.png",
        "runtime_svg": plots / f"runtime_grid_3x7_fixed_stage_{prefix}.svg",
        "compile_png": plots / f"compile_time_grid_3x7_fixed_stage_{prefix}.png",
        "compile_svg": plots / f"compile_time_grid_3x7_fixed_stage_{prefix}.svg",
    }
    render(
        plotter,
        snapshot,
        "execution_seconds",
        "Execution time (seconds)",
        f"Fixed-stage Docker runtime — {completed}/{total} completed cells",
        subtitle,
        footer,
        column_order,
        total,
        outputs["runtime_png"],
        outputs["runtime_svg"],
    )
    render(
        plotter,
        snapshot,
        "total_compilation_seconds",
        "Compilation time (seconds)",
        f"Fixed-stage Docker compilation — {completed}/{total} completed cells",
        subtitle,
        footer,
        column_order,
        total,
        outputs["compile_png"],
        outputs["compile_svg"],
    )

    latest = {
        "runtime_latest_png": plots / "runtime_grid_3x7_fixed_stage_latest.png",
        "runtime_latest_svg": plots / "runtime_grid_3x7_fixed_stage_latest.svg",
        "compile_latest_png": plots / "compile_time_grid_3x7_fixed_stage_latest.png",
        "compile_latest_svg": plots / "compile_time_grid_3x7_fixed_stage_latest.svg",
    }
    shutil.copyfile(outputs["runtime_png"], latest["runtime_latest_png"])
    shutil.copyfile(outputs["runtime_svg"], latest["runtime_latest_svg"])
    shutil.copyfile(outputs["compile_png"], latest["compile_latest_png"])
    shutil.copyfile(outputs["compile_svg"], latest["compile_latest_svg"])

    profile_counts = Counter(row["profile"] for row in rows)
    planner_counts = Counter(row["planner"] for row in rows)
    workload_counts = Counter(row["workload"] for row in rows)
    paths = {**outputs, **latest, "snapshot_csv": snapshot}
    receipt = {
        "schema": "g014-fixed-stage-plot/v2",
        "generated_at": generated_at,
        "active_output": str(root),
        "stage_id": manifest["stage_id"],
        "systemds_commit": manifest["systemds_commit"],
        "harness_commit": manifest["harness_commit"],
        "systemds_jar_sha256": manifest["systemds_jar_sha256"],
        "manifest_sha256": manifest["manifest_sha256"],
        "progress_snapshot": progress,
        "row_count": completed,
        "profile_counts": dict(sorted(profile_counts.items())),
        "planner_counts": dict(sorted(planner_counts.items())),
        "workload_counts": dict(sorted(workload_counts.items())),
        "column_order": column_order,
        "column_runtime_separation_seconds": {
            workload: None if math.isinf(separation[workload]) else separation[workload]
            for workload in column_order
        },
        "runtime_metric": "execution_seconds from warm-fresh-coordinator-jvm",
        "compile_metric": "Total compilation time from the same warm raw coordinator log",
        "selection_policy": (
            "latest authenticated successful canonical row from the current immutable "
            "stage only; snapshot progress and rows must be stable and equal"
        ),
        "separation_policy": (
            "maximum runtime max-minus-min across profile/worker blocks containing all "
            "four planners; ascending; canonical workload order breaks ties"
        ),
        "y_axis_policy": (
            "zero lower bound; common maximum per workload column across environment rows"
        ),
        "outputs": {name: str(path) for name, path in paths.items()},
        "sha256": {name: sha256_file(path) for name, path in paths.items()},
    }
    receipt_path = plots / "latest_fixed_stage_plot_receipt.json"
    receipt_path.write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(json.dumps(receipt, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
