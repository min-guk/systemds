#!/usr/bin/env python3
"""Run every cell of an explicit current P/E campaign; never silently select a subset.

The campaign's own scope decides whether a complete equality result can be
published. A planning cohort can be checked, but cannot become FULL_CURRENT.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
from types import SimpleNamespace

from capture_current_e_model_matrix import verify_matrix as verify_e_matrix
from run_bounded_pe_matrix import command
from run_current_pe_cell import (class_tree_sha, read, save, sha, tree_sha,
                                 verifier_sha)
from verify_current_p_matrix import verify as verify_p_matrix


SCRIPT = Path(__file__).resolve()
CELL_RUNNER = SCRIPT.with_name("run_current_pe_cell.py")
FULL_P_ACCEPTANCE = "INDEPENDENT_FULL_ACCEPTANCE_VERIFIED"
JVM_RESERVATION_GIB = 5  # The cell runner uses -Xmx4g plus JVM/native overhead.


def resource_snapshot(artifact_root):
    available_kib = None
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            available_kib = int(line.split()[1])
            break
    if available_kib is None:
        raise ValueError('host available memory is unavailable')
    disk_root = artifact_root
    while not disk_root.exists():
        disk_root = disk_root.parent
    return {'cpuCount': os.cpu_count(),
            'availableRamGiB': available_kib / 1024 / 1024,
            'freeDiskGiB': shutil.disk_usage(disk_root).free / 1024**3}


def check_resources(args):
    concurrent_jvms = args.jobs * args.shard_jobs
    max_jvms = getattr(args, 'max_jvms', concurrent_jvms)
    ram_budget = getattr(args, 'ram_budget_gib', 0)
    disk_reserve = getattr(args, 'min_free_disk_gib', 20)
    snapshot = resource_snapshot(args.artifact_root)
    if concurrent_jvms > max_jvms:
        raise ValueError('cell jobs times shard jobs exceeds global JVM limit')
    if concurrent_jvms * JVM_RESERVATION_GIB > (ram_budget or snapshot['availableRamGiB']):
        raise ValueError('global JVM reservation exceeds available RAM budget')
    if snapshot['freeDiskGiB'] < disk_reserve:
        raise ValueError('artifact filesystem is below free-disk reserve')
    return {'schema': 'current-pe-resource-preflight-v1',
            'binding': {'jobs': args.jobs, 'shardJobs': args.shard_jobs,
                        'maxJvms': max_jvms, 'ramBudgetGiB': ram_budget,
                        'minFreeDiskGiB': disk_reserve,
                        'jvmReservationGiB': JVM_RESERVATION_GIB},
            'measured': snapshot}


def campaign_cells(manifest, catalog, evaluation):
    if manifest.get("schema") != "current-pe-corpus-manifest-v1":
        raise ValueError("wrong current P/E campaign schema")
    if (manifest.get("catalog", {}).get("path") != str(catalog) or
            manifest["catalog"].get("sha256") != sha(catalog) or
            manifest.get("evaluation", {}).get("path") != str(evaluation)):
        raise ValueError("campaign frozen catalog/evaluation binding differs")
    cells = manifest.get("cells")
    if not isinstance(cells, list) or not cells:
        raise ValueError("campaign has no concrete cells")
    names = [row.get("id") for row in cells]
    if (any(not isinstance(name, str) or not name for name in names) or
            names != sorted(set(names))):
        raise ValueError("campaign cells must have unique sorted IDs")
    frozen_rows = read(catalog)["cells"]
    catalog_rows = {row["id"]: row for row in frozen_rows}
    if len(catalog_rows) != len(frozen_rows):
        raise ValueError("frozen catalog has duplicate cell IDs")
    for row in cells:
        source = catalog_rows.get(row["id"])
        if source is None or source.get("inventoryStatus") != "IN_SCOPE":
            raise ValueError("campaign cell absent from frozen in-scope catalog")
        for field in ("discoveryId", "conditionId", "sourceBinding", "sourceFiles"):
            if row.get(field) != source.get(field):
                raise ValueError("campaign cell differs from frozen catalog: " + field)
        validation = row.get("inputValidation", {})
        if (validation.get("compileModelReady") is not True or
                validation.get("conditionDigestRecomputed") is not True or
                validation.get("sourceDigestsVerified") is not True):
            raise ValueError("campaign cell has unverified input")
    if manifest.get("scope") == "FULL_CURRENT" and (
            manifest.get("status") != "COMPLETE" or manifest.get("unresolved")):
        raise ValueError("full campaign still has unresolved registry conditions")
    if manifest.get("scope") == "FULL_CURRENT":
        required = {row["id"] for row in frozen_rows
                    if row.get("inventoryStatus") == "IN_SCOPE"}
        coverage = manifest.get("registryCoverage")
        covered = ({entry.get("catalogCellId"): entry for entry in coverage}
                   if isinstance(coverage, list) and
                   all(isinstance(entry, dict) for entry in coverage) else {})
        if (set(names) != required or len(covered) != len(frozen_rows) or
                set(covered) != set(catalog_rows) or
                any((covered[cell].get("decision") != "READY" if cell in required
                     else covered[cell].get("decision") != "OUT_OF_SCOPE")
                    for cell in catalog_rows) or
                any(covered[cell].get("mappedIds") != [cell] for cell in required)):
            raise ValueError("full campaign does not cover the entire frozen registry")
    scope = manifest.get("scope")
    frozen_cohort = re.fullmatch(r"FROZEN_COHORT_(?:DERIVED_(?:ARGV_)?)?([1-9][0-9]*)", str(scope))
    if frozen_cohort and int(frozen_cohort.group(1)) != len(names):
        raise ValueError("frozen cohort scope count differs from campaign cells")
    if scope not in ("FULL_CURRENT", "PLANNING_COHORT_224") and not frozen_cohort:
        raise ValueError("unsupported campaign scope")
    return names


def matrix_binding(matrix_dir, cells, catalog, evaluation, build=None):
    matrix = read(matrix_dir / "matrix.json")
    if (matrix.get("schema") != "current-p-matrix-capture-v1" or
            matrix.get("catalogSha256") != sha(catalog) or
            matrix.get("evaluationRoot") != str(evaluation) or
            matrix.get("status") != "COMPLETE" or
            sorted(row.get("cell") for row in matrix.get("cells", [])) != cells or
            any(row.get("status") != "COMPLETE" for row in matrix["cells"])):
        raise ValueError("P matrix does not cover exactly all campaign cells")
    if build is not None and (matrix.get("sourceTreeSha256") != tree_sha(build) or
                              matrix.get("classTreeSha256") != class_tree_sha(build)):
        raise ValueError("P matrix source/class differs from running build")
    if len(matrix["cells"]) != len(cells):
        raise ValueError("P matrix has duplicate or missing cells")
    return matrix


def e_matrix_binding(matrix_dir, cells, catalog, evaluation, build=None,
                     expected_source=None, expected_classes=None):
    matrix = read(matrix_dir / "matrix.json")
    matrix_input = matrix.get("binding")
    if (matrix.get("schema") != "current-e-model-matrix-v1" or
            not isinstance(matrix_input, dict) or
            matrix.get("status") != "COMPLETE" or
            matrix.get("cellCount") != len(cells) or
            sorted(row.get("cell") for row in matrix.get("cells", [])) != cells or
            any(row.get("status") != "COMPLETE" for row in matrix["cells"]) or
            matrix_input.get("catalogSha256") != sha(catalog) or
            matrix_input.get("evaluationRoot") != str(evaluation)):
        raise ValueError("E matrix does not cover exactly all campaign cells")
    if len(matrix["cells"]) != len(cells):
        raise ValueError("E matrix has duplicate or missing cells")
    source = matrix_input.get("sourceTreeSha256")
    classes = matrix_input.get("classTreeSha256")
    if ((expected_source is not None and source != expected_source) or
            (expected_classes is not None and classes != expected_classes)):
        raise ValueError("P/E matrix source/class bindings differ")
    if build is not None and (source != tree_sha(build) or classes != class_tree_sha(build)):
        raise ValueError("E matrix source/class differs from running build")
    return matrix


def binding(args, campaign, matrix, e_matrix):
    return {"campaignSha256": sha(args.campaign),
            "catalogSha256": sha(args.catalog),
            "pMatrixSha256": sha(args.p_matrix_dir / "matrix.json"),
            "eMatrixSha256": sha(args.e_matrix_dir / "matrix.json"),
            "evaluationRoot": str(args.evaluation_root),
            "verificationRoot": str(args.verification_root),
            "sourceTreeSha256": matrix["sourceTreeSha256"],
            "classTreeSha256": matrix["classTreeSha256"],
            "runnerSha256": sha(SCRIPT), "cellRunnerSha256": sha(CELL_RUNNER),
            "pMatrixVerifierSha256": sha(SCRIPT.with_name("verify_current_p_matrix.py")),
            "verifierSha256": verifier_sha(args.verification_root, args.compact),
            "scope": campaign["scope"], "stateBudget": args.state_budget,
            "eRawBudget": args.e_raw_budget, "shardSize": args.shard_size,
            "jobs": args.jobs, "shardJobs": args.shard_jobs,
            "maxJvms": getattr(args, 'max_jvms', args.jobs * args.shard_jobs),
            "ramBudgetGiB": getattr(args, 'ram_budget_gib', 0),
            "minFreeDiskGiB": getattr(args, 'min_free_disk_gib', 20),
            "physicalFormat": "compact-v1" if args.compact else "legacy-v1"}


def offline_verify(run_dir, args, cell):
    code, result, error = command(
        [sys.executable, str(CELL_RUNNER), "verify", "--run-dir", str(run_dir),
         "--evaluation-root", str(args.evaluation_root),
         "--verification-root", str(args.verification_root)], args.cell_timeout)
    if (result is None or result.get("cell") != cell or
            result.get("status") not in ("PASS", "FAIL") or
            (code == 0) != (result["status"] == "PASS")):
        raise ValueError("artifact-only cell verification failed: " + error[:1000])
    if (result["status"] == "PASS" and
            (result.get("pOnly") != 0 or result.get("eOnly") != 0)):
        raise ValueError("cell PASS has a nonempty physical difference")
    if (result["status"] == "FAIL" and
            result.get("pOnly") == result.get("eOnly") == 0):
        raise ValueError("cell FAIL has no physical difference")
    return result


def check_matrix_p_identity(matrix_dir, run_dir, cell):
    """Bind the independently checked matrix model to this P/E cell run."""
    matrix_p = read(matrix_dir / cell / "receipt.json")
    captured_p = read(run_dir / "p-model.receipt.json")
    if (matrix_p.get("status") != "COMPLETE" or
            captured_p.get("status") != "COMPLETE" or
            not matrix_p.get("artifactSha256") or
            captured_p.get("artifactSha256") != matrix_p["artifactSha256"]):
        raise ValueError("cell P model differs from verified P matrix")


def check_matrix_e_identity(matrix_dir, run_dir, cell):
    """Bind the independently checked E matrix model to this P/E cell run."""
    matrix_e = read(matrix_dir / cell / "receipt.json")
    captured_e = read(run_dir / "e-model.receipt.json")
    if (matrix_e.get("status") != "COMPLETE" or
            captured_e.get("status") != "COMPLETE" or
            not matrix_e.get("artifactSha256") or
            captured_e.get("artifactSha256") != matrix_e["artifactSha256"]):
        raise ValueError("cell E model differs from verified E matrix")


def run_cell(cell, args, run_binding):
    target = args.result_dir / cell / "receipt.json"
    started = time.monotonic()
    try:
        if resource_snapshot(args.artifact_root)['freeDiskGiB'] < getattr(
                args, 'min_free_disk_gib', 20):
            return publish(target, cell, run_binding,
                           {'status': 'INCOMPLETE', 'reason': 'DISK_RESERVE'}, started)
        old = read(target) if args.resume and target.is_file() else None
        reusable = (old is not None and old.get("binding") == run_binding and
                    old.get("status") in ("EQUAL", "CAPTURED_EQUAL", "DIFFERENT"))
        if reusable:
            run_dir = Path(old["runDir"]).resolve()
        else:
            e_native = read(args.e_matrix_dir / cell / "receipt.json").get("native", {})
            e_raw = int(e_native["rawCount"])
            if e_raw > args.e_raw_budget:
                return publish(target, cell, run_binding,
                               {"status": "INCOMPLETE", "reason": "E_RAW_BUDGET",
                                "eRawCount": str(e_raw),
                                "eRawBudget": args.e_raw_budget}, started)
            command_line = [sys.executable, str(CELL_RUNNER), "run",
                            "--build-root", str(args.build_root),
                            "--catalog", str(args.catalog),
                            "--evaluation-root", str(args.evaluation_root),
                            "--verification-root", str(args.verification_root),
                            "--artifact-root", str(args.artifact_root),
                            "--p-matrix-dir", str(args.p_matrix_dir),
                            "--e-matrix-dir", str(args.e_matrix_dir),
                            "--cell", cell, "--state-budget", str(args.state_budget),
                            "--e-raw-budget", str(args.e_raw_budget),
                            "--shard-size", str(args.shard_size),
                            "--jobs", str(args.shard_jobs), "--resume"]
            if args.compact:
                command_line.append("--compact")
            code, native, error = command(command_line, args.cell_timeout)
            if code == 124:
                return publish(target, cell, run_binding,
                               {"status": "INCOMPLETE", "reason": "CELL_TIMEOUT",
                                "detail": error[:1000]}, started)
            if code == 2 and native and native.get("status") == "INCOMPLETE":
                result = {"status": "INCOMPLETE", "reason": native.get("reason"),
                          "native": native}
                return publish(target, cell, run_binding, result, started)
            if (native is None or code not in (0, 1) or native.get("status") not in
                    ("PASS", "FAIL") or native.get("cell") != cell):
                raise ValueError("native cell run failed: " + (error or str(native))[:1000])
            run_dir = Path(native["runDir"]).resolve()
        if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
            raise ValueError("cell artifact path escapes campaign root")
        check_matrix_p_identity(args.p_matrix_dir, run_dir, cell)
        check_matrix_e_identity(args.e_matrix_dir, run_dir, cell)
        verdict = offline_verify(run_dir, args, cell)
        result = {"status": ("EQUAL" if verdict["status"] == "PASS" and
                             verdict.get("pAcceptanceVerification") == FULL_P_ACCEPTANCE
                             else "CAPTURED_EQUAL" if verdict["status"] == "PASS"
                             else "DIFFERENT"),
                  "runDir": str(run_dir), "verification": verdict}
        return publish(target, cell, run_binding, result, started)
    except Exception as error:
        return publish(target, cell, run_binding,
                       {"status": "ERROR", "error": str(error)[:2000]}, started)


def publish(target, cell, run_binding, payload, started):
    result = {"schema": "current-pe-matrix-cell-v1", "cell": cell,
              "binding": run_binding, **payload,
              "elapsedSeconds": round(time.monotonic() - started, 3)}
    save(target, result)
    return result


def summarize(args, campaign, cells, run_binding, results, *, write=True):
    by_cell = {row["cell"]: row for row in results}
    if set(by_cell) != set(cells) or len(by_cell) != len(results):
        raise ValueError("result frontier differs from campaign")
    for row in results:
        if row["status"] == "EQUAL" and (
                row.get("verification", {}).get("status") != "PASS" or
                row["verification"].get("pAcceptanceVerification") != FULL_P_ACCEPTANCE or
                row["verification"].get("pOnly") != 0 or
                row["verification"].get("eOnly") != 0):
            raise ValueError("cell EQUAL exceeds independently verified evidence")
    counts = dict(sorted(Counter(row["status"] for row in results).items()))
    complete_equal = counts.get("EQUAL", 0) == len(cells)
    status = ("EQUAL" if complete_equal and campaign["scope"] == "FULL_CURRENT" and
              not campaign.get("unresolved")
              else "ERROR" if counts.get("ERROR", 0)
              else "DIFFERENT" if counts.get("DIFFERENT", 0)
              else "INCOMPLETE")
    summary = {"schema": "current-pe-matrix-summary-v1", "claimScope": campaign["scope"],
               "status": status, "binding": run_binding, "cellCount": len(cells),
               "counts": counts, "cells": [{"cell": cell, "status": by_cell[cell]["status"]}
                                           for cell in cells],
               "unresolvedCampaignCount": len(campaign.get("unresolved", [])),
               "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT"}
    if write:
        save(args.result_dir / "summary.json", summary)
    return summary


def verify_planning_matrix(campaign, cells, matrix_dir, catalog, evaluation):
    if campaign["scope"] == "PLANNING_COHORT_224":
        rows = read(catalog)["cells"]
        selected = set(cells)
        conditions = {row["conditionId"] for row in rows if row["id"] in selected}
        result = verify_p_matrix(matrix_dir, catalog, evaluation,
                                 expected_conditions=conditions)
    elif campaign["scope"] == "FULL_CURRENT":
        result = verify_p_matrix(matrix_dir, catalog, evaluation, full_in_scope=True)
    else:
        result = verify_p_matrix(matrix_dir, catalog, evaluation, ready_in_scope=True)
    if result["status"] != "PASS" or result["verifiedComplete"] != len(cells):
        raise ValueError("P matrix offline verification incomplete")
    save(matrix_dir / "verification.json", result)


def verify_exact_matrix(cells, matrix_dir, catalog, evaluation, matrix):
    rows = {row["id"]: row for row in read(catalog)["cells"]}
    if any(cell not in rows for cell in cells):
        raise ValueError("E matrix cell absent from frozen catalog")
    args = SimpleNamespace(artifact_root=matrix_dir, catalog=catalog,
                           evaluation_root=evaluation)
    result = verify_e_matrix(args, [rows[cell] for cell in cells], matrix["binding"])
    if (result["status"] != "PASS" or result["verifiedComplete"] != len(cells) or
            result["verifiedKnown"] != len(cells)):
        raise ValueError("E matrix offline verification incomplete or unknown")


def run(args):
    campaign = read(args.campaign)
    cells = campaign_cells(campaign, args.catalog, args.evaluation_root)
    matrix = matrix_binding(args.p_matrix_dir, cells, args.catalog,
                            args.evaluation_root, args.build_root)
    e_matrix = e_matrix_binding(
        args.e_matrix_dir, cells, args.catalog, args.evaluation_root, args.build_root,
        matrix["sourceTreeSha256"], matrix["classTreeSha256"])
    verify_planning_matrix(campaign, cells, args.p_matrix_dir, args.catalog,
                           args.evaluation_root)
    verify_exact_matrix(cells, args.e_matrix_dir, args.catalog,
                        args.evaluation_root, e_matrix)
    resources = check_resources(args)
    run_binding = binding(args, campaign, matrix, e_matrix)
    args.result_dir.mkdir(parents=True, exist_ok=True)
    save(args.result_dir / 'resources.json', resources)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(run_cell, cell, args, run_binding): cell for cell in cells}
        results = []
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            print(json.dumps({"cell": result["cell"], "status": result["status"]}),
                  flush=True)
    summary = summarize(args, campaign, cells, run_binding, results)
    print(json.dumps({"status": summary["status"], "counts": summary["counts"]},
                     sort_keys=True))
    return 0 if summary["status"] == "EQUAL" else 2 if summary["status"] == "INCOMPLETE" else 1


def verify(args):
    campaign = read(args.campaign)
    cells = campaign_cells(campaign, args.catalog, args.evaluation_root)
    matrix = matrix_binding(args.p_matrix_dir, cells, args.catalog, args.evaluation_root)
    e_matrix = e_matrix_binding(
        args.e_matrix_dir, cells, args.catalog, args.evaluation_root,
        expected_source=matrix["sourceTreeSha256"],
        expected_classes=matrix["classTreeSha256"])
    verify_planning_matrix(campaign, cells, args.p_matrix_dir, args.catalog,
                           args.evaluation_root)
    verify_exact_matrix(cells, args.e_matrix_dir, args.catalog,
                        args.evaluation_root, e_matrix)
    expected_binding = binding(args, campaign, matrix, e_matrix)
    summary = read(args.result_dir / "summary.json")
    if (summary.get("schema") != "current-pe-matrix-summary-v1" or
            summary.get("binding") != expected_binding or
            [row.get("cell") for row in summary.get("cells", [])] != cells):
        raise ValueError("matrix summary binding or cell frontier differs")
    results = []
    for cell in cells:
        receipt = read(args.result_dir / cell / "receipt.json")
        if (receipt.get("schema") != "current-pe-matrix-cell-v1" or
                receipt.get("cell") != cell or receipt.get("binding") != expected_binding):
            raise ValueError("cell receipt binding differs: " + cell)
        if receipt["status"] in ("EQUAL", "CAPTURED_EQUAL", "DIFFERENT"):
            run_dir = Path(receipt["runDir"]).resolve()
            if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
                raise ValueError("cell artifact path escapes campaign root")
            check_matrix_p_identity(args.p_matrix_dir, run_dir, cell)
            check_matrix_e_identity(args.e_matrix_dir, run_dir, cell)
            verdict = offline_verify(run_dir, args, cell)
            if verdict != receipt["verification"]:
                raise ValueError("offline verdict changed: " + cell)
            expected_status = ("EQUAL" if verdict["status"] == "PASS" and
                               verdict.get("pAcceptanceVerification") == FULL_P_ACCEPTANCE
                               else "CAPTURED_EQUAL" if verdict["status"] == "PASS"
                               else "DIFFERENT")
            if receipt["status"] != expected_status:
                raise ValueError("cell status exceeds offline evidence: " + cell)
        results.append(receipt)
    rebuilt = summarize(args, campaign, cells, expected_binding, results, write=False)
    if summary != rebuilt:
        raise ValueError("matrix summary differs from saved receipts")
    print(json.dumps({"status": rebuilt["status"], "counts": rebuilt["counts"]},
                     sort_keys=True))
    return 0 if rebuilt["status"] == "EQUAL" else 2 if rebuilt["status"] == "INCOMPLETE" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    for name in ("campaign", "catalog", "evaluation-root", "verification-root",
                 "p-matrix-dir", "e-matrix-dir", "artifact-root", "result-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--shard-jobs", type=int, default=2)
    parser.add_argument("--state-budget", type=int, default=1000)
    parser.add_argument("--e-raw-budget", type=int, default=1000000)
    parser.add_argument("--shard-size", type=int, default=16)
    parser.add_argument("--cell-timeout", type=int, default=1800)
    parser.add_argument("--max-jvms", type=int)
    parser.add_argument("--ram-budget-gib", type=int, default=0,
                        help="0 uses currently available host RAM")
    parser.add_argument("--min-free-disk-gib", type=int, default=20)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--compact", action="store_true")
    args = parser.parse_args()
    for name in ("campaign", "catalog", "evaluation_root", "verification_root",
                 "p_matrix_dir", "e_matrix_dir", "artifact_root", "result_dir",
                 "build_root"):
        path = getattr(args, name)
        if path is not None:
            setattr(args, name, path.resolve())
    if min(args.jobs, args.shard_jobs, args.state_budget, args.e_raw_budget,
           args.shard_size, args.cell_timeout) < 1:
        parser.error("jobs, budgets, shard size, and timeout must be positive")
    if args.max_jvms is None:
        args.max_jvms = args.jobs * args.shard_jobs
    if args.max_jvms < 1 or args.ram_budget_gib < 0 or args.min_free_disk_gib < 0:
        parser.error('resource limits must be nonnegative and max-jvms positive')
    if args.mode == "run":
        if args.build_root is None:
            parser.error("run requires --build-root")
        return run(args)
    return verify(args)


if __name__ == "__main__":
    raise SystemExit(main())
