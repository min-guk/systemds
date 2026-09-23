#!/usr/bin/env python3
"""Re-run the budget-bounded current P/E subset with offline cell verification.

This runner never promotes cells outside the selected finite frontier to PASS.
The complete corpus gate remains run_plan_space_comparison.sh.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from run_current_pe_cell import class_tree_sha, read, save, sha, tree_sha, verifier_sha
from verify_current_p_matrix import verify as verify_p_matrix


def select_cells(matrix_dir, manifest, state_budget):
    selected = []
    for row in manifest["cells"]:
        if row["status"] != "COMPLETE":
            raise ValueError("bounded P/E input contains incomplete P model")
        path = matrix_dir / row["cell"] / "p-model.json.gz"
        model = json.loads(gzip.decompress(path.read_bytes()))
        states = math.prod(len(domain[1]) for domain in
                           model["nativeDomain"]["placementDomains"])
        if states <= state_budget:
            selected.append({"cell": row["cell"], "pStateCount": str(states)})
    return sorted(selected, key=lambda item: item["cell"])


def command(args, timeout):
    process = subprocess.Popen(args, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=True,
                               env={key: value for key, value in os.environ.items()
                                    if key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")})
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        stdout, stderr = process.communicate()
        return 124, None, stderr[-3000:]
    try:
        payload = json.loads(stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        payload = None
    return process.returncode, payload, stderr[-3000:]


def run_one(item, args, binding):
    cell = item["cell"]
    started = time.monotonic()
    script = Path(__file__).with_name("run_current_pe_cell.py")
    base = ["python3", str(script)]
    old = args.result_dir / cell / "receipt.json"
    run_dir = None
    if args.resume and old.is_file():
        previous = read(old)
        if previous.get("binding") == binding and previous.get("status") == "PASS":
            run_dir = previous.get("runDir")
    if run_dir is None:
        run_command = base + ["run", "--build-root", str(args.build_root),
                              "--catalog", str(args.catalog),
                              "--evaluation-root", str(args.evaluation_root),
                              "--verification-root", str(args.verification_root),
                              "--artifact-root", str(args.artifact_root),
                              "--cell", cell, "--state-budget", str(args.state_budget),
                              "--e-raw-budget", str(args.e_raw_budget),
                              "--shard-size", str(args.shard_size),
                              "--jobs", str(args.shard_jobs), "--resume"]
        code, native, error = command(run_command, args.cell_timeout)
        if code == 2 and native and native.get("status") == "INCOMPLETE":
            result = {"schema": "bounded-pe-matrix-cell-v1", "binding": binding,
                      "cell": cell, "pStateCount": item["pStateCount"],
                      "status": "INCOMPLETE", "reason": native.get("reason"),
                      "eRawCount": native.get("eRawCount"),
                      "elapsedSeconds": round(time.monotonic() - started, 3)}
            save(old, result)
            return result
        if code != 0 or not native or native.get("cell") != cell or native.get("status") != "PASS":
            result = {"schema": "bounded-pe-matrix-cell-v1", "binding": binding,
                      "cell": cell, "status": "ERROR", "exitCode": code,
                      "error": error or str(native)[:3000]}
            save(old, result)
            return result
        run_dir = native.get("runDir")
    run_dir = Path(run_dir).resolve()
    if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
        raise ValueError("saved P/E run directory differs from selected cell")
    matrix_p = read(args.p_matrix_dir / cell / "receipt.json")
    captured_p = read(run_dir / "p-model.receipt.json")
    if (matrix_p.get("status") != "COMPLETE" or captured_p.get("status") != "COMPLETE" or
            captured_p.get("artifactSha256") != matrix_p.get("artifactSha256")):
        raise ValueError("P/E P model differs from verified matrix model")
    code, offline, error = command(base + ["verify", "--run-dir", str(run_dir),
                                    "--evaluation-root", str(args.evaluation_root),
                                    "--verification-root", str(args.verification_root)],
                                   args.cell_timeout)
    status = "PASS" if (code == 0 and offline and offline.get("cell") == cell and
                        offline.get("status") == "PASS" and offline.get("pOnly") == 0 and
                        offline.get("eOnly") == 0) else "ERROR"
    result = {"schema": "bounded-pe-matrix-cell-v1", "binding": binding,
              "cell": cell, "pStateCount": item["pStateCount"], "status": status,
              "runDir": str(run_dir), "offline": offline,
              "offlineExitCode": code, "error": error if status == "ERROR" else None,
              "elapsedSeconds": round(time.monotonic() - started, 3)}
    save(old, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("build-root", "catalog", "evaluation-root", "verification-root",
                 "p-matrix-dir", "artifact-root", "result-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--expected-conditions", required=True)
    parser.add_argument("--state-budget", type=int, default=1000)
    parser.add_argument("--e-raw-budget", type=int, default=1000000)
    parser.add_argument("--shard-size", type=int, default=16)
    parser.add_argument("--shard-jobs", type=int, default=2)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--cell-timeout", type=int, default=1800)
    parser.add_argument("--cell", help="restrict the bounded run to one selected cell")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    for name in ("build_root", "catalog", "evaluation_root", "verification_root",
                 "p_matrix_dir", "artifact_root", "result_dir"):
        setattr(args, name, getattr(args, name).resolve())
    if min(args.state_budget, args.e_raw_budget, args.shard_size, args.shard_jobs,
           args.jobs, args.cell_timeout) < 1:
        parser.error("budgets, jobs, shard size, and timeout must be positive")
    conditions = args.expected_conditions.split(",")
    if not conditions or any(not value for value in conditions) or len(conditions) != len(set(conditions)):
        parser.error("expected conditions are empty or duplicated")
    p_verdict = verify_p_matrix(args.p_matrix_dir, args.catalog, args.evaluation_root,
                                expected_conditions=set(conditions))
    if p_verdict["status"] != "PASS":
        raise ValueError("P matrix has unverified or incomplete cells")
    manifest = read(args.p_matrix_dir / "matrix.json")
    if (tree_sha(args.build_root) != manifest["sourceTreeSha256"] or
            class_tree_sha(args.build_root) != manifest["classTreeSha256"]):
        raise ValueError("P matrix source/class bytes differ from P/E build")
    selected = select_cells(args.p_matrix_dir, manifest, args.state_budget)
    if args.cell:
        selected = [item for item in selected if item["cell"] == args.cell]
        if not selected:
            parser.error("cell is outside the verified bounded selection")
    if not selected:
        raise ValueError("no cells lie within the bounded P state frontier")
    args.result_dir.mkdir(parents=True, exist_ok=True)
    binding = {"sourceTreeSha256": manifest["sourceTreeSha256"],
               "classTreeSha256": manifest["classTreeSha256"],
               "catalogSha256": sha(args.catalog), "pMatrixSha256": sha(args.p_matrix_dir / "matrix.json"),
               "evaluationRoot": str(args.evaluation_root),
               "verificationRoot": str(args.verification_root),
               "runnerSha256": sha(__file__),
               "cellRunnerSha256": sha(Path(__file__).with_name("run_current_pe_cell.py")),
               "verifierSha256": verifier_sha(args.verification_root),
               "expectedConditions": sorted(conditions),
               "stateBudget": args.state_budget, "eRawBudget": args.e_raw_budget}
    save(args.result_dir / "selection.json", {"schema": "bounded-pe-matrix-selection-v1",
                                              "binding": binding, "cells": selected})
    if args.dry_run:
        print(json.dumps({"status": "SELECTION_ONLY", "selected": len(selected)}, sort_keys=True))
        return 0
    results = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = {executor.submit(run_one, item, args, binding): item for item in selected}
        for future in as_completed(futures):
            try:
                result = future.result()
            except Exception as error:
                item = futures[future]
                result = {"schema": "bounded-pe-matrix-cell-v1", "binding": binding,
                          "cell": item["cell"], "pStateCount": item["pStateCount"],
                          "status": "ERROR", "error": str(error)[:3000]}
                save(args.result_dir / item["cell"] / "receipt.json", result)
            results.append(result)
            print(json.dumps({"cell": result["cell"], "status": result["status"]},
                             sort_keys=True), flush=True)
    counts = {status: sum(row["status"] == status for row in results)
              for status in sorted({row["status"] for row in results})}
    summary = {"schema": "bounded-pe-matrix-summary-v1",
               "claimScope": "SELECTED_NATIVE_PHYSICAL_SET_EQUALITY_ONLY",
               "status": "PASS_BOUNDED" if counts.get("PASS", 0) == len(selected) else
                         "ERROR" if counts.get("ERROR", 0) else "INCOMPLETE",
               "binding": binding, "selected": len(selected), "counts": counts,
               "cells": sorted(({"cell": row["cell"], "status": row["status"]}
                                for row in results), key=lambda row: row["cell"]),
               "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT"}
    save(args.result_dir / "summary.json", summary)
    print(json.dumps({"status": summary["status"], "selected": len(selected),
                      "counts": counts}, sort_keys=True))
    return 0 if summary["status"] == "PASS_BOUNDED" else 2 if summary["status"] == "INCOMPLETE" else 1


if __name__ == "__main__":
    sys.exit(main())
