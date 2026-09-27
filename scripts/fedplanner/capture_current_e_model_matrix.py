#!/usr/bin/env python3
"""Capture and offline-recheck every frozen E hard-factor model without leaf enumeration.

This certifies the stored native factor tables and input binding only. It does
not project canonical physical plans or certify P/E equality.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from capture_current_plan_matrix import quarantine_cell_artifacts, select_cells
from exact_e_factor_count import PackedTruth, read_model, validate_factor_aggregation
from run_current_pe_cell import (check_frozen_inputs, check_frozen_model_settings,
                                 class_tree_sha, frozen_capture_settings,
                                 frozen_compiler_configuration, read, save, sha,
                                 tree_sha)


CLASS = "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPlanningModelCapture"
SCRIPT = Path(__file__).resolve()
GIB = 1024 ** 3


def require_disk_budget(root, jobs, minimum_gib, per_job_gib):
    """Reserve space for all concurrent captures and atomic gzip publication."""
    required = (minimum_gib + jobs * per_job_gib) * GIB
    available = shutil.disk_usage(root).free
    if available < required:
        raise OSError(f"E capture disk reserve: {available} available, {required} required")


def runner_sha():
    digest = hashlib.sha256()
    for path in (SCRIPT, SCRIPT.with_name("run_current_pe_cell.py"),
                 SCRIPT.with_name("capture_current_plan_matrix.py"),
                 SCRIPT.with_name("exact_e_factor_count.py")):
        digest.update(path.name.encode())
        digest.update(bytes.fromhex(sha(path)))
    return digest.hexdigest()


def resume_failure(folder, cell, binding, reason, started):
    prior = quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
    result = {"schema": "current-e-model-matrix-cell-v1", "cell": cell,
              "binding": binding, "status": "ERROR", "error": reason,
              "invalidPriorEvidence": str(prior),
              "elapsedSeconds": round(time.monotonic() - started, 3)}
    save(folder / "receipt.json", result)
    return result


def capture_one(row, args, binding):
    cell = row["id"]
    folder = args.artifact_root / cell
    folder.mkdir(parents=True, exist_ok=True)
    receipt_path = folder / "receipt.json"
    model_path = folder / "e-model.json.gz"
    started = time.monotonic()
    try:
        check_frozen_inputs(args.catalog, args.evaluation_root, cell)
        configuration = frozen_compiler_configuration(row["sourceBinding"])
        environment, options, network = frozen_capture_settings(
            args.catalog, args.evaluation_root, cell, os.environ)
        if args.resume and receipt_path.is_file():
            try:
                old = read(receipt_path)
                if not isinstance(old, dict):
                    raise ValueError("saved E receipt is not an object")
            except (OSError, TypeError, ValueError) as error:
                if not getattr(args, "recapture_invalid", False):
                    return resume_failure(folder, cell, binding,
                        "saved E receipt unreadable: " + str(error), started)
                quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
            else:
                if old.get("binding") != binding:
                    if not getattr(args, "recapture_invalid", False):
                        return resume_failure(folder, cell, binding,
                            "saved E receipt binding differs", started)
                    quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
                else:
                    if (old.get("schema") != "current-e-model-matrix-cell-v1" or
                            old.get("cell") != cell or
                            old.get("status") not in
                            {"COMPLETE", "ERROR", "TIMEOUT", "RESOURCE_LIMIT"}):
                        if not getattr(args, "recapture_invalid", False):
                            return resume_failure(folder, cell, binding,
                                "saved E receipt schema/cell/status invalid", started)
                        quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
                    elif old.get("invalidPriorEvidence"):
                        if not getattr(args, "recapture_invalid", False):
                            return old
                        quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
                    else:
                        invalid = (old.get("status") == "ERROR" and
                                   str(old.get("error", "")).startswith(
                                       "saved COMPLETE E artifact failed resume verification:"))
                        if old.get("status") == "COMPLETE":
                            try:
                                verify_one(old, row, args)
                                return old
                            except (OSError, KeyError, TypeError, ValueError) as error:
                                invalid = True
                                if not getattr(args, "recapture_invalid", False):
                                    result = {"schema": "current-e-model-matrix-cell-v1",
                                              "cell": cell, "binding": binding, "status": "ERROR",
                                              "error": "saved COMPLETE E artifact failed resume verification: "
                                                       + str(error)[:1800],
                                              "elapsedSeconds": round(time.monotonic() - started, 3)}
                                    prior = quarantine_cell_artifacts(folder, ("receipt.json",))
                                    result["invalidPriorEvidence"] = str(prior)
                                    save(receipt_path, result)
                                    return result
                        if invalid:
                            if not getattr(args, "recapture_invalid", False):
                                return old
                            quarantine_cell_artifacts(folder,
                                ("receipt.json", "e-model.json.gz"))
                        elif old.get("status") != "COMPLETE":
                            quarantine_cell_artifacts(folder,
                                ("receipt.json", "e-model.json.gz"))
        try:
            require_disk_budget(folder, args.jobs, args.min_free_disk_gib,
                                args.per_job_disk_gib)
        except OSError as error:
            result = {"schema": "current-e-model-matrix-cell-v1", "cell": cell,
                      "binding": binding, "status": "RESOURCE_LIMIT",
                      "error": str(error),
                      "elapsedSeconds": round(time.monotonic() - started, 3)}
            save(receipt_path, result)
            return result
        command = ["java", *options, "--add-modules", "jdk.incubator.vector",
                   "-Djava.io.tmpdir=" + str(args.artifact_root), "-Xmx4g", "-cp",
                   f"{args.build_root}/target/classes:{args.build_root}/target/test-classes:"
                   f"{args.build_root}/target/lib/*", CLASS, str(args.catalog),
                   str(args.evaluation_root), cell, str(model_path)]
        process = subprocess.Popen(command, cwd=args.build_root, env=environment,
                                   start_new_session=True, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            stdout, stderr = process.communicate(timeout=args.timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate()
            timed_out = True
        lines = [line for line in stdout.splitlines() if line.startswith("{")]
        native = json.loads(lines[-1]) if lines else None
        status = ("TIMEOUT" if timed_out else
                  "COMPLETE" if process.returncode == 0 and native and
                  native.get("status") == "COMPLETE" and model_path.is_file() else "ERROR")
        result = {"schema": "current-e-model-matrix-cell-v1", "cell": cell,
                  "binding": binding, "status": status,
                  "artifactPath": str(model_path) if model_path.is_file() else None,
                  "artifactSha256": native.get("artifactSha256") if native else None,
                  "native": native, "networkEnvironment": network,
                  "jvmOptions": options,
                  "compilerArgv": row["sourceBinding"].get("compilerArgv"),
                  "compilerConfiguration": configuration,
                  "error": None if status == "COMPLETE" else stderr[-2000:],
                  "elapsedSeconds": round(time.monotonic() - started, 3)}
        if status == "COMPLETE":
            verify_one(result, row, args)
        save(receipt_path, result)
        return result
    except Exception as error:
        result = {"schema": "current-e-model-matrix-cell-v1", "cell": cell,
                  "binding": binding, "status": "ERROR",
                  "error": str(error)[:2000],
                  "elapsedSeconds": round(time.monotonic() - started, 3)}
        if args.resume and receipt_path.exists():
            prior = quarantine_cell_artifacts(folder, ("receipt.json", "e-model.json.gz"))
            result["invalidPriorEvidence"] = str(prior)
        save(receipt_path, result)
        return result


def verify_one(receipt, row, args):
    cell = row["id"]
    path = args.artifact_root / cell / "e-model.json.gz"
    if (receipt.get("schema") != "current-e-model-matrix-cell-v1" or
            receipt.get("cell") != cell or receipt.get("status") != "COMPLETE" or
            receipt.get("artifactPath") != str(path)):
        raise ValueError("E matrix receipt path/status differs")
    model, digest, radices, tables = read_model(path, allow_legacy_v1=False)
    aggregation = validate_factor_aggregation(model, radices)
    if digest != receipt.get("artifactSha256"):
        raise ValueError("E model bytes differ from receipt")
    _, options, network = frozen_capture_settings(args.catalog, args.evaluation_root,
                                                    cell, {})
    compiler = frozen_compiler_configuration(row["sourceBinding"])
    condition = row["sourceBinding"]["plannedCondition"]
    program = condition["case"]["program"]
    if row["sourceBinding"].get("kind") == "planning-snapshot":
        program = "planning_study/native/input_templates/w" + str(
            condition["workers"]) + "/" + program
    for name, recorded in (("E model", model), ("E native receipt", receipt["native"])):
        check_frozen_model_settings(recorded, options, network, name,
                                    row["sourceBinding"].get("compilerArgv"), compiler)
    native = receipt["native"]
    if (receipt.get("networkEnvironment") != network or
            receipt.get("jvmOptions") != options or
            receipt.get("compilerConfiguration") != compiler or
            receipt.get("compilerArgv") != row["sourceBinding"].get("compilerArgv") or
            model.get("cell") != cell or
            model.get("sourceFiles") != row["sourceFiles"] or
            model.get("source") != "E_C0" or
            model.get("conditionSha256") != row["sourceBinding"]["conditionSha256"] or
            model.get("programSha256") != row["sourceFiles"].get(program) or
            native.get("schema") != "closed-native-model-capture-v1" or
            native.get("source") != "E_C0" or
            native.get("artifactPath") != str(path) or
            native.get("radices") != radices or native.get("opaqueFactors") != 0 or
            native.get("status") != "COMPLETE" or native.get("cell") != cell or
            native.get("artifactSha256") != digest or
            native.get("domainCount") != len(model["domains"]) or
            native.get("factorCount") != len(model["factors"]) or
            str(math.prod(radices)) != native.get("rawCount")):
        raise ValueError("E model frozen input/domain differs")
    if any(native.get(key) != aggregation[key] for key in (
            "factorAggregation", "nativeFactorCount", "materializedFactorCount",
            "nativeFactorCells", "materializedFactorCells")):
        raise ValueError("E model and native receipt factor aggregation differ")
    return sum(int(truth.wire()["statusCounts"]["UNKNOWN"])
               if isinstance(truth, PackedTruth) else truth.count("UNKNOWN")
               for _, truth in tables)


def verify_matrix(args, cells, binding):
    if (binding.get("runnerSha256") != runner_sha() or
            binding.get("catalogSha256") != sha(args.catalog) or
            binding.get("evaluationRoot") != str(args.evaluation_root)):
        raise ValueError("E matrix supplied binding differs from current verifier/input")
    manifest_path = args.artifact_root / "matrix.json"
    manifest = read(manifest_path)
    if (manifest.get("schema") != "current-e-model-matrix-v1" or
            manifest.get("binding") != binding or
            [item["cell"] for item in manifest.get("cells", [])] !=
            sorted(row["id"] for row in cells)):
        raise ValueError("E matrix manifest binding/frontier differs")
    rows = []
    failures = []
    unknown_cells = []
    for row in cells:
        cell = row["id"]
        check_frozen_inputs(args.catalog, args.evaluation_root, cell)
        receipt = read(args.artifact_root / cell / "receipt.json")
        if receipt.get("binding") != binding or receipt.get("cell") != cell:
            raise ValueError("E matrix cell binding differs: " + cell)
        rows.append(receipt)
        if receipt["status"] == "COMPLETE":
            try:
                unknown = verify_one(receipt, row, args)
                if unknown:
                    unknown_cells.append({"cell": cell, "unknownFactorCells": unknown})
            except (OSError, KeyError, TypeError, ValueError) as error:
                failures.append({"cell": cell, "error": str(error)[:300]})
        else:
            failures.append({"cell": cell, "error": receipt.get("error")})
    counts = dict(sorted(Counter(row["status"] for row in rows).items()))
    if (counts != manifest.get("counts") or
            manifest.get("status") != ("COMPLETE" if counts.get("COMPLETE", 0)
                                       == len(cells) else "INCOMPLETE")):
        raise ValueError("E matrix manifest counts/status differ")
    result = {"schema": "current-e-model-matrix-verification-v1",
              "status": "PASS" if not failures and not unknown_cells else "INCOMPLETE",
              "matrixSha256": sha(manifest_path),
              "catalogSha256": sha(args.catalog),
              "evaluationRoot": str(args.evaluation_root),
              "runnerSha256": runner_sha(),
              "cellCount": len(cells), "verifiedComplete": len(cells) - len(failures),
              "verifiedKnown": len(cells) - len(failures) - len(unknown_cells),
              "counts": counts, "failures": failures,
              "unknownFactorCells": unknown_cells,
              "claimScope": "CAPTURED_E_FACTOR_TABLES_ONLY"}
    save(args.artifact_root / "verification.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    parser.add_argument("--build-root", type=Path)
    for name in ("catalog", "evaluation-root", "artifact-root"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--ready-only", action="store_true")
    parser.add_argument("--cell", help="one in-scope cell ID for a targeted capture")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--timeout", type=int, default=2400)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--recapture-invalid", action="store_true",
                        help="on resume, quarantine unreadable/stale/corrupt artifacts before recapture")
    parser.add_argument("--min-free-disk-gib", type=int, default=20)
    parser.add_argument("--per-job-disk-gib", type=int, default=8)
    args = parser.parse_args()
    if (args.jobs < 1 or args.timeout < 1 or args.min_free_disk_gib < 0 or
            args.per_job_disk_gib < 1):
        parser.error("jobs, timeout, and per-job disk reserve must be positive")
    if args.ready_only and args.cell:
        parser.error("--ready-only selects the entire frozen-ready cohort")
    for name in ("build_root", "catalog", "evaluation_root", "artifact_root"):
        path = getattr(args, name)
        if path is not None:
            setattr(args, name, path.resolve())
    if args.mode == "run" and args.build_root is None:
        parser.error("run requires --build-root")
    cells = select_cells(args.catalog, None, args.cell, args.ready_only)
    if args.mode == "verify" and args.build_root is None:
        binding = read(args.artifact_root / "matrix.json").get("binding", {})
        if (not isinstance(binding, dict) or
                any(not isinstance(binding.get(key), str) or
                    len(binding[key]) != 64 or
                    any(ch not in "0123456789abcdef" for ch in binding[key])
                    for key in ("sourceTreeSha256", "classTreeSha256"))):
            raise ValueError("E matrix lacks frozen source/class hashes")
        if (binding.get("catalogSha256") != sha(args.catalog) or
                binding.get("evaluationRoot") != str(args.evaluation_root) or
                binding.get("runnerSha256") != runner_sha() or
                binding.get("readyOnly") != args.ready_only or
                binding.get("selectedCell") != args.cell):
            raise ValueError("E matrix offline input/runner binding differs")
    else:
        binding = {"sourceTreeSha256": tree_sha(args.build_root),
                   "classTreeSha256": class_tree_sha(args.build_root),
                   "catalogSha256": sha(args.catalog),
                   "evaluationRoot": str(args.evaluation_root),
                   "runnerSha256": runner_sha(), "readyOnly": args.ready_only,
                   "selectedCell": args.cell}
    if args.mode == "verify":
        result = verify_matrix(args, cells, binding)
    else:
        args.artifact_root.mkdir(parents=True, exist_ok=True)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = {pool.submit(capture_one, row, args, binding): row for row in cells}
            receipts = []
            for future in as_completed(futures):
                receipt = future.result()
                receipts.append(receipt)
                print(json.dumps({"cell": receipt["cell"], "status": receipt["status"]}),
                      flush=True)
        if (tree_sha(args.build_root) != binding["sourceTreeSha256"] or
                class_tree_sha(args.build_root) != binding["classTreeSha256"] or
                sha(args.catalog) != binding["catalogSha256"] or
                runner_sha() != binding["runnerSha256"]):
            raise ValueError("E matrix source, classpath, catalog, or runner changed during capture")
        counts = dict(sorted(Counter(row["status"] for row in receipts).items()))
        status = "COMPLETE" if counts.get("COMPLETE", 0) == len(cells) else "INCOMPLETE"
        manifest = {"schema": "current-e-model-matrix-v1", "status": status,
                    "claimScope": "CAPTURED_E_FACTOR_TABLES_ONLY",
                    "binding": binding, "cellCount": len(cells), "counts": counts,
                    "cells": sorted(({"cell": row["cell"], "status": row["status"]}
                                     for row in receipts), key=lambda row: row["cell"])}
        save(args.artifact_root / "matrix.json", manifest)
        result = verify_matrix(args, cells, binding)
    print(json.dumps({"status": result["status"],
                      "verifiedComplete": result["verifiedComplete"],
                      "cellCount": result["cellCount"]}, sort_keys=True))
    return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
