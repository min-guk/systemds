#!/usr/bin/env python3
"""Recheck a frozen P capture matrix using stored artifacts only."""
import argparse
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
import hashlib
import json
from pathlib import Path

from run_current_pe_cell import (check_frozen_inputs, frozen_capture_settings,
                                 frozen_compiler_configuration, read, save, sha)
from verify_p_model_artifact import verify as verify_model


def digest(value):
    return isinstance(value, str) and len(value) == 64 and all(
        character in "0123456789abcdef" for character in value)


def verify_frozen_condition(summary, receipt, options, network, binding):
    properties = dict(option[2:].split("=", 1) for option in options)
    if (summary.get("compilerBoundary") != "POST_REWRITE_HOPS_DAG_PRE_PLANNER" or
            summary.get("networkEnvironmentChecked") is not True or
            summary.get("networkEnvironment") != network or
            summary.get("workloadJvmProperties") != properties or
            receipt.get("networkEnvironment") != network or
            receipt.get("jvmOptions") != options):
        raise ValueError("P model or receipt differs from frozen JVM/network condition")
    compiler = frozen_compiler_configuration(binding)
    if compiler is not None:
        for recorded in (summary, receipt):
            if (recorded.get("compilerArgv") != binding["compilerArgv"] or
                    recorded.get("compilerConfiguration") != compiler):
                raise ValueError("P model or receipt differs from frozen compiler condition")


def verify_cell(task):
    cell, listed_status, row, matrix_dir, catalog, evaluation, binding = task
    failures = []
    receipt_path = matrix_dir / cell / "receipt.json"
    if not receipt_path.is_file():
        return None, [{"cell": cell, "reason": "MISSING_RECEIPT"}], 0
    receipt = read(receipt_path)
    status = receipt.get("status")
    try:
        check_frozen_inputs(catalog, evaluation, cell)
    except (OSError, KeyError, TypeError, ValueError) as error:
        failures.append({"cell": cell, "reason": "FROZEN_SOURCE_CHANGED",
                         "detail": str(error)[:300]})
    if (receipt.get("schema") != "current-p-matrix-cell-v1" or
            receipt.get("cell") != cell or status != listed_status or
            receipt.get("sourceTreeSha256") != binding["sourceTreeSha256"] or
            receipt.get("classTreeSha256") != binding["classTreeSha256"] or
            receipt.get("runnerSha256") != binding["runnerSha256"] or
            receipt.get("evaluationRoot") != str(evaluation) or
            receipt.get("catalogSha256") != binding["catalogSha256"] or
            receipt.get("sourceFiles") != row.get("sourceFiles")):
        failures.append({"cell": cell, "reason": "RECEIPT_BINDING_MISMATCH"})
        return status, failures, 0
    if status != "COMPLETE":
        failures.append({"cell": cell, "reason": status})
        return status, failures, 0
    model_path = matrix_dir / cell / "p-model.json.gz"
    if receipt.get("artifactPath") != str(model_path):
        failures.append({"cell": cell, "reason": "ARTIFACT_PATH_MISMATCH"})
        return status, failures, 0
    try:
        _, options, network = frozen_capture_settings(catalog, evaluation, cell, {})
        # Historical v1 captures are diagnostic inputs to this matrix checker.
        result = verify_model(model_path, receipt["artifactSha256"],
                              allow_legacy_v1=True, include_summary=True)
        if result["cell"] != cell:
            raise ValueError("model cell differs")
        summary = result["summary"]
        if (summary.get("conditionSha256") != row["sourceBinding"].get("conditionSha256") or
                summary.get("sourceFiles") != row["sourceFiles"] or
                summary.get("programPath") not in row["sourceFiles"] or
                summary.get("programSha256") != row["sourceFiles"][summary["programPath"]]):
            raise ValueError("model input binding differs")
        verify_frozen_condition(summary, receipt, options, network,
                                row["sourceBinding"])
        return status, failures, 1
    except (OSError, KeyError, TypeError, ValueError) as error:
        failures.append({"cell": cell, "reason": "MODEL_VERIFICATION_ERROR",
                         "detail": str(error)[:300]})
        return status, failures, 0


def verify(matrix_dir, catalog, evaluation, *, expected_conditions=None, expected_cell=None,
           full_in_scope=False, ready_in_scope=False, jobs=1):
    if jobs < 1:
        raise ValueError("verification jobs must be positive")
    if sum((expected_conditions is not None, expected_cell is not None,
            full_in_scope, ready_in_scope)) != 1:
        raise ValueError("exactly one independent expected scope is required")
    if expected_conditions is not None and not expected_conditions:
        raise ValueError("empty expected condition scope")
    manifest_path = matrix_dir / "matrix.json"
    manifest = read(manifest_path)
    if (manifest.get("schema") != "current-p-matrix-capture-v1" or
            manifest.get("catalogSha256") != sha(catalog)):
        raise ValueError("matrix schema or frozen catalog digest differs")
    if manifest.get("evaluationRoot") != str(evaluation):
        raise ValueError("matrix evaluation root differs from independent expectation")
    conditions = manifest.get("selectedConditions")
    selected_cell = manifest.get("selectedCell")
    if (conditions is not None and (not isinstance(conditions, list) or
            not conditions or len(conditions) != len(set(conditions)))):
        raise ValueError("matrix condition selection malformed")
    if (conditions != (sorted(expected_conditions) if expected_conditions is not None else None)
            or selected_cell != expected_cell):
        raise ValueError("matrix selected scope differs from independent expectation")
    if bool(manifest.get("readyOnly", False)) != ready_in_scope:
        raise ValueError("matrix frozen-ready selector differs from independent expectation")
    catalog_rows = read(catalog)["cells"]
    ids = [row["id"] for row in catalog_rows]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate catalog cell ID")
    expected = {row["id"]: row for row in catalog_rows
                if row.get("inventoryStatus") == "IN_SCOPE" and
                (not ready_in_scope or row.get("sourceBinding", {}).get("conditionStatus")
                 == "SNAPSHOT_ONLY") and
                (conditions is None or row.get("conditionId") in conditions) and
                (selected_cell is None or row["id"] == selected_cell)}
    if not expected:
        raise ValueError("expected scope has no in-scope cells")
    if not all(digest(manifest.get(name)) for name in
               ("classTreeSha256", "sourceTreeSha256", "runnerSha256")):
        raise ValueError("matrix has no complete source/classpath/runner binding")
    listed = manifest.get("cells")
    if (not isinstance(listed, list) or len(expected) != manifest.get("cellCount") or
            len(listed) != len(expected) or
            {row.get("cell") for row in listed} != set(expected)):
        raise ValueError("matrix cell frontier is incomplete or duplicated")
    tasks = ((entry["cell"], entry.get("status"), expected[entry["cell"]],
              matrix_dir, catalog, evaluation, manifest)
             for entry in sorted(listed, key=lambda row: row["cell"]))
    if jobs == 1:
        results = map(verify_cell, tasks)
        results = list(results)
    else:
        with ProcessPoolExecutor(max_workers=jobs) as pool:
            results = list(pool.map(verify_cell, tasks))
    counts = Counter()
    failures = []
    verified_complete = 0
    for status, cell_failures, verified in results:
        if status is not None:
            counts[status] += 1
        failures.extend(cell_failures)
        verified_complete += verified
    if dict(counts) != manifest.get("counts"):
        raise ValueError("matrix status counts differ from receipts")
    if manifest.get("status") != ("COMPLETE" if counts.get("COMPLETE", 0) == len(expected)
                                  else "INCOMPLETE"):
        raise ValueError("matrix completion status differs from receipts")
    verdict = {"schema": "current-p-matrix-artifact-verification-v1",
               "claimScope": "P_MODEL_STRUCTURAL_CAPTURE_COVERAGE",
               "status": "PASS" if not failures else "INCOMPLETE",
               "verifierSha256": hashlib.sha256(b"".join(bytes.fromhex(sha(path)) for path in (
                   Path(__file__), Path(__file__).with_name("verify_p_model_artifact.py"),
                   Path(__file__).with_name("run_current_pe_cell.py")))).hexdigest(),
               "matrixSha256": sha(manifest_path), "catalogSha256": sha(catalog),
               "evaluationRoot": str(evaluation),
               "cellCount": len(expected), "counts": dict(sorted(counts.items())),
               "verifiedComplete": verified_complete,
               "failures": failures,
               "acceptance": "NOT_ASSESSED_BY_THIS_CONTRACT",
               "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT"}
    return verdict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix-dir", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=1,
                        help="bounded parallel artifact verification processes")
    scope = parser.add_mutually_exclusive_group(required=True)
    scope.add_argument("--expected-conditions", help="comma-separated condition IDs")
    scope.add_argument("--expected-cell", help="one catalog cell ID")
    scope.add_argument("--full-in-scope", action="store_true")
    scope.add_argument("--frozen-ready", action="store_true",
                       help="recheck exactly the frozen-ready in-scope catalog rows")
    args = parser.parse_args()
    conditions = set(args.expected_conditions.split(",")) if args.expected_conditions else None
    verdict = verify(args.matrix_dir.resolve(), args.catalog.resolve(),
                     args.evaluation_root.resolve(),
                     expected_conditions=conditions, expected_cell=args.expected_cell,
                     full_in_scope=args.full_in_scope, ready_in_scope=args.frozen_ready,
                     jobs=args.jobs)
    save(args.matrix_dir.resolve() / "verification.json", verdict)
    print(json.dumps({key: verdict[key] for key in
                      ("status", "cellCount", "counts", "verifiedComplete", "failures")},
                     sort_keys=True))
    return 0 if verdict["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
