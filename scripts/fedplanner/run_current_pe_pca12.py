#!/usr/bin/env python3
"""Run and independently recheck the frozen 12-cell Pca physical comparison.

This is a captured-native physical-set check.  Its successful status is
CAPTURED_EQUAL, because the current P artifact does not independently prove
the complete production acceptance predicate.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
from pathlib import Path
import sys

from run_bounded_pe_matrix import command
from run_current_pe_cell import class_tree_sha, read, save, sha, tree_sha
from run_current_pe_matrix import resource_snapshot


HERE = Path(__file__).resolve().parent
CELL_RUNNER = HERE / "run_current_pe_cell.py"
RUNNER_SHA = sha(__file__)
GROUPS = {"planning-w3:pca", "planning-w5:pca", "planning-w7:pca"}
CONDITIONS = {"lan", "wan_light", "wan_mid", "wan_heavy"}
E_RAW = "2057529600"


def validate_worklist(worklist, catalog, p_matrix_dir, e_matrix_dir, build):
    if worklist.get("schema") != "current-pe-pca12-worklist-v1" or \
            worklist.get("status") != "FROZEN_INPUT_WORKLIST_ONLY" or \
            worklist.get("cellCount") != 12:
        raise ValueError("Pca worklist schema, status, or denominator differs")
    bound = {
        "catalogSha256": sha(catalog),
        "pMatrixSha256": sha(p_matrix_dir / "matrix.json"),
        "eMatrixSha256": sha(e_matrix_dir / "matrix.json"),
        "pVerificationSha256": sha(p_matrix_dir / "verification.json"),
        "eVerificationSha256": sha(e_matrix_dir / "verification.json"),
    }
    if any(worklist.get(key) != value for key, value in bound.items()):
        raise ValueError("Pca worklist model/input binding changed")
    p_matrix = read(p_matrix_dir / "matrix.json")
    e_matrix = read(e_matrix_dir / "matrix.json")
    p_verify = read(p_matrix_dir / "verification.json")
    e_verify = read(e_matrix_dir / "verification.json")
    source = p_matrix.get("sourceTreeSha256")
    classes = p_matrix.get("classTreeSha256")
    if (p_matrix.get("status") != e_matrix.get("status") or
            p_matrix.get("status") != "COMPLETE" or
            p_matrix.get("cellCount") != e_matrix.get("cellCount") or
            p_matrix.get("cellCount") != 612 or
            p_matrix.get("counts") != e_matrix.get("counts") or
            p_matrix.get("counts") != {"COMPLETE": 612} or
            p_verify.get("status") != e_verify.get("status") or
            p_verify.get("status") != "PASS" or
            p_verify.get("cellCount") != 612 or e_verify.get("cellCount") != 612 or
            p_verify.get("matrixSha256") != bound["pMatrixSha256"] or
            e_verify.get("matrixSha256") != bound["eMatrixSha256"] or
            p_verify.get("counts") != p_matrix.get("counts") or
            e_verify.get("counts") != e_matrix.get("counts") or
            p_verify.get("verifiedComplete") != 612 or
            e_verify.get("verifiedKnown") != 612 or
            e_verify.get("unknownFactorCells") or
            p_verify.get("failures") or e_verify.get("failures") or
            e_matrix.get("binding", {}).get("sourceTreeSha256") != source or
            e_matrix.get("binding", {}).get("classTreeSha256") != classes or
            worklist.get("sourceTreeSha256") != source or
            worklist.get("classTreeSha256") != classes or
            (build is not None and
             (tree_sha(build) != source or class_tree_sha(build) != classes))):
        raise ValueError("Pca worklist is not bound to fully verified P/E models")
    rows = worklist.get("cells")
    if not isinstance(rows, list) or len(rows) != 12 or \
            [row.get("cell") for row in rows] != sorted({row.get("cell") for row in rows}):
        raise ValueError("Pca worklist cells are missing, duplicated, or unsorted")
    catalog_rows = {row["id"]: row for row in read(catalog)["cells"]}
    combinations = set()
    for row in rows:
        cell = row["cell"]
        source_row = catalog_rows.get(cell)
        if (source_row is None or source_row.get("inventoryStatus") != "IN_SCOPE" or
                source_row.get("sourceBinding", {}).get("conditionStatus") != "SNAPSHOT_ONLY" or
                source_row.get("discoveryId") != row.get("discoveryId") or
                source_row.get("conditionId") != row.get("conditionId") or
                row.get("eRawCount") != E_RAW):
            raise ValueError("Pca worklist cell differs from frozen catalog")
        pair = (row["discoveryId"], row["conditionId"])
        combinations.add(pair)
        e_receipt = read(e_matrix_dir / cell / "receipt.json")
        p_receipt = read(p_matrix_dir / cell / "receipt.json")
        if (e_receipt.get("status") != p_receipt.get("status") or
                e_receipt.get("status") != "COMPLETE" or
                e_receipt.get("native", {}).get("rawCount") != E_RAW):
            raise ValueError("Pca cell lacks complete model receipts")
    if combinations != {(group, condition) for group in GROUPS for condition in CONDITIONS}:
        raise ValueError("Pca worklist does not cover all worker/network conditions")
    return [row["cell"] for row in rows]


def run_command(command_line, timeout):
    code, payload, error = command(command_line, timeout)
    return code, payload, error


def verify_cell(cell, run_dir, args):
    if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
        raise ValueError("Pca cell run directory escapes artifact root")
    code, verdict, error = run_command(
        [sys.executable, str(CELL_RUNNER), "verify", "--run-dir", str(run_dir),
         "--evaluation-root", str(args.evaluation_root),
         "--verification-root", str(args.verification_root)], args.timeout)
    if (not isinstance(verdict, dict) or verdict.get("cell") != cell or
            verdict.get("status") not in ("PASS", "FAIL") or
            code != (0 if verdict["status"] == "PASS" else 1) or
            verdict.get("claimScope") != "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY" or
            verdict.get("pAcceptanceVerification") != "PRODUCER_RECEIPT_ONLY" or
            verdict.get("pStructureStatus") != "STRUCTURE_VERIFIED" or
            verdict.get("factorStatus") != "INDEPENDENT_FACTOR_TABLE_VERIFIED" or
            (verdict["status"] == "PASS" and
             (verdict.get("pOnly") != 0 or verdict.get("eOnly") != 0 or
              verdict.get("pPhysical") != verdict.get("ePhysical"))) or
            (verdict["status"] == "FAIL" and
             verdict.get("pOnly") == verdict.get("eOnly") == 0)):
        raise ValueError("Pca artifact-only verification failed: " + error[:300])
    return verdict


def run_one(cell, args):
    try:
        command_line = [sys.executable, str(CELL_RUNNER), "run",
                        "--build-root", str(args.build_root),
                        "--catalog", str(args.catalog),
                        "--evaluation-root", str(args.evaluation_root),
                        "--verification-root", str(args.verification_root),
                        "--artifact-root", str(args.artifact_root),
                        "--p-matrix-dir", str(args.p_matrix_dir),
                        "--e-matrix-dir", str(args.e_matrix_dir),
                        "--cell", cell, "--jobs", str(args.shard_jobs),
                        "--state-budget", "128", "--e-raw-budget", E_RAW,
                        "--shard-size", "16", "--compact", "--resume"]
        code, native, error = run_command(command_line, args.timeout)
        if code == 124:
            return {"cell": cell, "status": "INCOMPLETE", "reason": "CELL_TIMEOUT"}
        if code == 2 and isinstance(native, dict) and native.get("status") == "INCOMPLETE":
            return {"cell": cell, "status": "INCOMPLETE",
                    "reason": native.get("reason", "NATIVE_FRONTIER_INCOMPLETE"),
                    "pStateCount": native.get("pStateCount"),
                    "eRawCount": native.get("eRawCount")}
        if (not isinstance(native, dict) or native.get("cell") != cell or
                native.get("status") not in ("PASS", "FAIL") or
                code != (0 if native["status"] == "PASS" else 1)):
            raise ValueError("Pca native physical capture failed: " + error[:300])
        run_dir = Path(native["runDir"]).resolve()
        verdict = verify_cell(cell, run_dir, args)
        if verdict["status"] != native["status"]:
            raise ValueError("Pca native and offline physical verdicts differ")
        return {"cell": cell,
                "status": "CAPTURED_EQUAL" if verdict["status"] == "PASS" else "DIFFERENT",
                "runDir": str(run_dir), "certificateSha256": sha(run_dir / "certificate.json"),
                "pPhysical": verdict["pPhysical"], "ePhysical": verdict["ePhysical"],
                "pOnly": verdict["pOnly"], "eOnly": verdict["eOnly"],
                "pAccepted": verdict["pAccepted"], "eAccepted": verdict["eAccepted"]}
    except Exception as error:
        return {"cell": cell, "status": "ERROR", "reason": str(error)[:1000]}


def verify_one(cell, args):
    try:
        run_dirs = [path.parent for path in (args.artifact_root / cell).glob("*/certificate.json")]
        if len(run_dirs) != 1:
            return {"cell": cell, "status": "INCOMPLETE", "reason": "CERTIFICATE_MISSING_OR_AMBIGUOUS"}
        run_dir = run_dirs[0].resolve()
        verdict = verify_cell(cell, run_dir, args)
        return {"cell": cell,
                "status": "CAPTURED_EQUAL" if verdict["status"] == "PASS" else "DIFFERENT",
                "runDir": str(run_dir), "certificateSha256": sha(run_dir / "certificate.json"),
                "pPhysical": verdict["pPhysical"], "ePhysical": verdict["ePhysical"],
                "pOnly": verdict["pOnly"], "eOnly": verdict["eOnly"],
                "pAccepted": verdict["pAccepted"], "eAccepted": verdict["eAccepted"]}
    except Exception as error:
        return {"cell": cell, "status": "ERROR", "reason": str(error)[:1000]}


def summarize(cells, results, worklist_sha):
    by_cell = {row["cell"]: row for row in results}
    if len(by_cell) != len(results) or set(by_cell) != set(cells):
        raise ValueError("Pca result frontier differs from worklist")
    counts = dict(sorted(Counter(row["status"] for row in results).items()))
    status = ("ERROR" if counts.get("ERROR", 0) else
              "DIFFERENT" if counts.get("DIFFERENT", 0) else
              "CAPTURED_EQUAL" if counts.get("CAPTURED_EQUAL", 0) == len(cells)
              else "INCOMPLETE")
    return {"schema": "current-pe-pca12-summary-v1", "status": status,
            "claimScope": "FROZEN_PLANNING_PCA12_CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
            "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
            "worklistSha256": worklist_sha, "runnerSha256": RUNNER_SHA,
            "cellCount": len(cells), "counts": counts,
            "cells": [by_cell[cell] for cell in cells]}


def input_binding(args):
    worklist = read(args.worklist)
    cells = validate_worklist(worklist, args.catalog, args.p_matrix_dir,
                              args.e_matrix_dir, args.build_root)
    binding = {"worklistSha256": sha(args.worklist),
               "catalogSha256": sha(args.catalog),
               "pMatrixSha256": sha(args.p_matrix_dir / "matrix.json"),
               "eMatrixSha256": sha(args.e_matrix_dir / "matrix.json"),
               "pVerificationSha256": sha(args.p_matrix_dir / "verification.json"),
               "eVerificationSha256": sha(args.e_matrix_dir / "verification.json"),
               "sourceTreeSha256": worklist["sourceTreeSha256"],
               "classTreeSha256": worklist["classTreeSha256"]}
    return cells, binding


def check_result_bindings(results, args, binding):
    for row in results:
        if row["status"] not in ("CAPTURED_EQUAL", "DIFFERENT"):
            continue
        run_dir = Path(row["runDir"])
        meta = read(run_dir / "run-meta.json")
        imports = meta.get("modelImports", {})
        if (meta.get("cell") != row["cell"] or
                meta.get("sourceTreeSha256") != binding["sourceTreeSha256"] or
                meta.get("classTreeSha256") != binding["classTreeSha256"] or
                meta.get("catalogSha256") != binding["catalogSha256"] or
                imports.get("p", {}).get("matrixDir") != str(args.p_matrix_dir) or
                imports.get("e", {}).get("matrixDir") != str(args.e_matrix_dir) or
                imports.get("p", {}).get("matrixManifestSha256") != binding["pMatrixSha256"] or
                imports.get("e", {}).get("matrixManifestSha256") != binding["eMatrixSha256"] or
                imports.get("p", {}).get("matrixVerificationSha256") !=
                binding["pVerificationSha256"] or
                imports.get("e", {}).get("matrixVerificationSha256") !=
                binding["eVerificationSha256"]):
            raise ValueError("Pca cell model import differs from frozen input binding: " + row["cell"])


def recheck_completed(results, args):
    for row in results:
        if row["status"] in ("CAPTURED_EQUAL", "DIFFERENT"):
            if verify_one(row["cell"], args) != row:
                raise ValueError("Pca cell changed after capture: " + row["cell"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    for name in ("worklist", "catalog", "evaluation-root", "verification-root",
                 "p-matrix-dir", "e-matrix-dir", "artifact-root", "result-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--shard-jobs", type=int, default=4)
    parser.add_argument("--max-jvms", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=7200)
    parser.add_argument("--min-free-disk-gib", type=int, default=20)
    args = parser.parse_args()
    for name in ("worklist", "catalog", "evaluation_root", "verification_root",
                 "p_matrix_dir", "e_matrix_dir", "artifact_root", "result_dir", "build_root"):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.resolve())
    if min(args.jobs, args.shard_jobs, args.max_jvms, args.timeout) < 1 or \
            args.min_free_disk_gib < 0:
        parser.error("jobs, JVM limit, and timeout must be positive")
    if args.mode == "run" and args.build_root is None:
        parser.error("run requires --build-root")
    if args.mode == "run":
        resources = resource_snapshot(args.artifact_root)
        if (args.jobs * args.shard_jobs > args.max_jvms or
                args.jobs * args.shard_jobs * 5 > resources["availableRamGiB"] or
                resources["freeDiskGiB"] < args.min_free_disk_gib):
            raise ValueError("Pca global JVM/RAM/disk reservation exceeded")
    cells, binding = input_binding(args)
    args.result_dir.mkdir(parents=True, exist_ok=True)
    handler = run_one if args.mode == "run" else verify_one
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(handler, cell, args): cell for cell in cells}
        results = []
        for future in as_completed(futures):
            row = future.result()
            results.append(row)
            print(json.dumps({"cell": row["cell"], "status": row["status"]},
                             sort_keys=True), flush=True)
    if sha(__file__) != RUNNER_SHA:
        raise ValueError("Pca runner changed during execution")
    final_cells, final_binding = input_binding(args)
    if final_cells != cells or final_binding != binding:
        raise ValueError("Pca input binding changed during execution")
    check_result_bindings(results, args, binding)
    if args.mode == "run":
        recheck_completed(results, args)
        if input_binding(args) != (cells, binding):
            raise ValueError("Pca input binding changed during final artifact recheck")
    summary = summarize(cells, results, binding["worklistSha256"])
    summary["inputBinding"] = binding
    if args.mode == "verify":
        previous = read(args.result_dir / "summary.json")
        if previous != summary:
            raise ValueError("Pca offline summary differs from stored run")
        target = args.result_dir / "summary-verification.json"
    else:
        target = args.result_dir / "summary.json"
    save(target, summary)
    print(json.dumps({"status": summary["status"], "counts": summary["counts"]},
                     sort_keys=True), flush=True)
    return 0 if summary["status"] == "CAPTURED_EQUAL" else \
        1 if summary["status"] in ("ERROR", "DIFFERENT") else 2


if __name__ == "__main__":
    raise SystemExit(main())
