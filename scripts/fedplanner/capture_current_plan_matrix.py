#!/usr/bin/env python3
"""Capture frozen current P models with bounded workers and resumable receipts."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

from run_current_pe_cell import (check_frozen_inputs, class_tree_sha,
                                 frozen_capture_settings, frozen_compiler_configuration,
                                 read, save, sha, tree_sha)

CLASS = "org.apache.sysds.test.component.federated.placement.shadow.PlanningNativeModelCapture"


def runner_sha():
    """Bind orchestration and its imported frozen-input checker together."""
    return hashlib.sha256(bytes.fromhex(sha(__file__)) +
        bytes.fromhex(sha(Path(__file__).with_name("run_current_pe_cell.py")))).hexdigest()


def select_cells(catalog, conditions, cell_id, ready_only=False):
    cells = [row for row in read(catalog)["cells"]
             if row.get("inventoryStatus") == "IN_SCOPE" and
             (not ready_only or row.get("sourceBinding", {}).get("conditionStatus")
              == "SNAPSHOT_ONLY") and
             (conditions is None or row.get("conditionId") in conditions) and
             (cell_id is None or row["id"] == cell_id)]
    if not cells:
        raise ValueError("no in-scope cells match selected conditions")
    if len({row["id"] for row in cells}) != len(cells):
        raise ValueError("duplicate cell in frozen catalog")
    return cells


def quarantine_cell_artifacts(cell_dir, names):
    """Keep the exact prior evidence before an explicit recapture or retry."""
    quarantine = cell_dir / ("prior-capture-" + str(time.time_ns()))
    quarantine.mkdir(exist_ok=False)
    for name in names:
        path = cell_dir / name
        if path.exists():
            path.replace(quarantine / name)
    return quarantine


def resume_failure(cell_dir, row, reason, source_sha, class_sha, catalog_sha,
                   runner_digest, evaluation):
    prior = quarantine_cell_artifacts(cell_dir,
                                      ("receipt.json", "capture.json", "p-model.json.gz"))
    result = {"schema": "current-p-matrix-cell-v1", "cell": row["id"],
              "status": "ERROR", "error": reason,
              "invalidPriorEvidence": str(prior),
              "elapsedSeconds": 0.0, "sourceTreeSha256": source_sha,
              "classTreeSha256": class_sha, "catalogSha256": catalog_sha,
              "runnerSha256": runner_digest, "evaluationRoot": str(evaluation),
              "sourceFiles": row.get("sourceFiles")}
    save(cell_dir / "receipt.json", result)
    return result


def capture_one(cell, catalog, build, evaluation, output, timeout, source_sha, class_tree_sha256,
                catalog_sha, runner_sha, resume, retry_errors, recapture_invalid=False):
    cell_dir = output / cell["id"]
    cell_dir.mkdir(parents=True, exist_ok=True)
    receipt_path = cell_dir / "receipt.json"
    model_path = cell_dir / "p-model.json.gz"
    started = time.monotonic()
    try:
        check_frozen_inputs(catalog, evaluation, cell["id"])
        configuration = frozen_compiler_configuration(cell.get("sourceBinding", {}))
        environment, options, network = frozen_capture_settings(
            catalog, evaluation, cell["id"], os.environ)
    except Exception as error:
        result = {"schema": "current-p-matrix-cell-v1", "cell": cell["id"],
                  "status": "INPUT_ERROR", "error": str(error), "elapsedSeconds": 0.0,
                  "sourceTreeSha256": source_sha, "classTreeSha256": class_tree_sha256,
                  "catalogSha256": catalog_sha, "runnerSha256": runner_sha,
                  "evaluationRoot": str(evaluation), "sourceFiles": cell.get("sourceFiles")}
        if resume and receipt_path.exists():
            prior = quarantine_cell_artifacts(cell_dir,
                ("receipt.json", "capture.json", "p-model.json.gz"))
            result["invalidPriorEvidence"] = str(prior)
        save(receipt_path, result)
        return result
    if resume and receipt_path.exists():
        try:
            old = read(receipt_path)
            if not isinstance(old, dict):
                raise ValueError("saved P receipt is not an object")
        except (OSError, ValueError, TypeError) as error:
            if not recapture_invalid:
                return resume_failure(cell_dir, cell,
                    "saved P receipt unreadable: " + str(error), source_sha,
                    class_tree_sha256, catalog_sha, runner_sha, evaluation)
            quarantine_cell_artifacts(cell_dir,
                                      ("receipt.json", "capture.json", "p-model.json.gz"))
        else:
            bound = (old.get("schema") == "current-p-matrix-cell-v1" and
                     old.get("sourceTreeSha256") == source_sha and
                     old.get("classTreeSha256") == class_tree_sha256 and
                     old.get("catalogSha256") == catalog_sha and
                     old.get("runnerSha256") == runner_sha and
                     old.get("evaluationRoot") == str(evaluation) and
                     old.get("cell") == cell["id"] and
                     old.get("sourceFiles") == cell.get("sourceFiles"))
            invalid = not bound
            reason = "saved P receipt binding differs"
            if bound and old.get("status") == "COMPLETE":
                try:
                    native = read(cell_dir / "capture.json")
                    if (old.get("artifactPath") != str(model_path) or
                            old.get("jvmOptions") != options or
                            old.get("networkEnvironment") != network or
                            (configuration is not None and
                             (old.get("compilerArgv") != cell["sourceBinding"]["compilerArgv"] or
                              old.get("compilerConfiguration") != configuration)) or
                            not model_path.is_file() or native.get("status") != "COMPLETE" or
                            old.get("artifactSha256") != native.get("artifactSha256")):
                        raise ValueError("capture/model receipt differs")
                    digest = hashlib.sha256()
                    with gzip.open(model_path, "rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            digest.update(chunk)
                    if digest.hexdigest() != native["artifactSha256"]:
                        raise ValueError("P model bytes differ")
                    return old
                except (OSError, KeyError, TypeError, ValueError) as error:
                    invalid = True
                    reason = "saved COMPLETE P artifact failed resume verification: " + str(error)
            elif bound:
                if old.get("invalidPriorEvidence") and not recapture_invalid:
                    return old
                if old.get("status") not in {"ERROR", "NO_CAPTURE", "TIMEOUT"} or \
                        str(old.get("error", "")).startswith("saved ") or \
                        "invalidPriorEvidence" in old:
                    invalid = True
                    reason = "saved P non-COMPLETE receipt is not retryable producer failure"
                elif not retry_errors:
                    return old
            if invalid and not recapture_invalid:
                return resume_failure(cell_dir, cell, reason, source_sha,
                    class_tree_sha256, catalog_sha, runner_sha, evaluation)
            quarantine_cell_artifacts(cell_dir,
                                      ("receipt.json", "capture.json", "p-model.json.gz"))
    command = ["java", *options, "--add-modules", "jdk.incubator.vector",
               "-Djava.io.tmpdir=" + str(output), "-Xmx4g", "-cp",
               f"{build}/target/classes:{build}/target/test-classes:{build}/target/lib/*",
               CLASS, str(catalog), str(evaluation), cell["id"], str(model_path)]
    process = subprocess.Popen(command, cwd=build, env=environment,
                               start_new_session=True, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    timed_out = False
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(process.pid, signal.SIGKILL)
        stdout, stderr = process.communicate()
    lines = [line for line in stdout.splitlines() if line.startswith("{")]
    native = json.loads(lines[-1]) if lines else None
    if native is not None:
        save(cell_dir / "capture.json", native)
    status = ("TIMEOUT" if timed_out else
              native.get("status", "ERROR") if native is not None else "NO_CAPTURE")
    if status == "COMPLETE" and (process.returncode or not model_path.is_file()):
        status = "ERROR"
    result = {
        "schema": "current-p-matrix-cell-v1", "cell": cell["id"],
        "discoveryId": cell["discoveryId"], "conditionId": cell["conditionId"],
        "status": status, "elapsedSeconds": round(time.monotonic() - started, 3),
        "exitCode": process.returncode, "sourceTreeSha256": source_sha,
        "classTreeSha256": class_tree_sha256, "catalogSha256": catalog_sha,
        "runnerSha256": runner_sha, "evaluationRoot": str(evaluation),
        "sourceFiles": cell["sourceFiles"], "jvmOptions": options,
        "networkEnvironment": network, "artifactPath": str(model_path) if model_path.is_file() else None,
        "compilerArgv": native.get("compilerArgv") if native else None,
        "compilerConfiguration": native.get("compilerConfiguration") if native else None,
        "artifactSha256": native.get("artifactSha256") if native else None,
        "error": native.get("error") if native and native.get("status") == "ERROR"
                 else stderr[-2000:] if status != "COMPLETE" else None,
        "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT",
    }
    save(receipt_path, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--conditions", help="comma-separated frozen condition IDs")
    parser.add_argument("--cell", help="one in-scope cell ID for a targeted run")
    parser.add_argument("--ready-only", action="store_true",
                        help="capture exactly the frozen-ready in-scope catalog rows")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--retry-errors", action="store_true")
    parser.add_argument("--recapture-invalid", action="store_true")
    args = parser.parse_args()
    if args.jobs < 1 or args.timeout < 1:
        parser.error("jobs and timeout must be positive")
    if args.ready_only and (args.conditions is not None or args.cell is not None):
        parser.error("--ready-only selects the entire frozen-ready cohort")
    catalog = args.catalog.resolve()
    build = args.build_root.resolve()
    evaluation = args.evaluation_root.resolve()
    output = args.artifact_root.resolve()
    output.mkdir(parents=True, exist_ok=True)
    conditions = set(args.conditions.split(",")) if args.conditions else None
    cells = select_cells(catalog, conditions, args.cell, args.ready_only)
    source_sha = tree_sha(build)
    classes_sha = class_tree_sha(build)
    catalog_sha = sha(catalog)
    runner_digest = runner_sha()
    results = []
    with ThreadPoolExecutor(max_workers=args.jobs) as executor:
        futures = [executor.submit(capture_one, cell, catalog, build, evaluation, output,
                                   args.timeout, source_sha, classes_sha, catalog_sha, runner_digest,
                                   args.resume, args.retry_errors, args.recapture_invalid)
                   for cell in cells]
        for future in as_completed(futures):
            result = future.result()
            results.append(result)
            print(json.dumps({"cell": result["cell"], "status": result["status"],
                              "elapsedSeconds": result["elapsedSeconds"]}, sort_keys=True),
                  flush=True)
    counts = {status: sum(result["status"] == status for result in results)
              for status in sorted({result["status"] for result in results})}
    if (source_sha != tree_sha(build) or classes_sha != class_tree_sha(build) or
            catalog_sha != sha(catalog) or runner_digest != runner_sha()):
        raise ValueError("matrix source, classpath, catalog, or runner changed during capture")
    manifest = {
        "schema": "current-p-matrix-capture-v1", "status": "COMPLETE" if
        counts.get("COMPLETE", 0) == len(cells) else "INCOMPLETE",
        "sourceTreeSha256": source_sha, "classTreeSha256": classes_sha,
        "runnerSha256": runner_digest, "evaluationRoot": str(evaluation),
        "catalogSha256": catalog_sha, "selectedConditions": sorted(conditions) if conditions else None,
        "selectedCell": args.cell, "readyOnly": args.ready_only,
        "cellCount": len(cells), "counts": counts,
        "cells": sorted(({"cell": row["cell"], "status": row["status"]} for row in results),
                        key=lambda row: row["cell"]),
        "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT",
    }
    save(output / "matrix.json", manifest)
    print(json.dumps({key: manifest[key] for key in ("status", "cellCount", "counts")},
                     sort_keys=True), flush=True)
    return 0 if manifest["status"] == "COMPLETE" else 2


if __name__ == "__main__":
    sys.exit(main())
