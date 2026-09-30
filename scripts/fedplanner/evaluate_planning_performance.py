#!/usr/bin/env python3
"""Read-only 20-second acceptance gate for the complete Docker compile matrix.

The existing watchdog remains 60 seconds. Successful full compilation, rather
than a shortened planner timer or a timeout, must meet this stricter target.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_matrix_campaign as campaign

TARGET_NANOS = 20_000_000_000


def positive_nanos(value):
    return type(value) is int and value > 0


def row_errors(row, cell, jar_sha):
    reasons = []
    for valid, message in (
        (row.get("cell") == cell and row.get("phase") == "compile", "wrong cell or phase"),
        (row.get("status") == "passed" and row.get("returncode") == 0, "compile did not pass"),
        (row.get("errors") == [], "harness/network validation failed"),
        (row.get("cleanup_resolved") is True, "cleanup unresolved"),
        (row.get("timeout_seconds") == 60, "watchdog differs from 60 seconds"),
        (not row.get("diagnostic_only") and not row.get("diagnostic_compact"), "diagnostic measurement"),
        (row.get("jar_sha256") == jar_sha, "mixed engine"),
    ):
        if not valid:
            reasons.append(message)
    receipt = row.get("receipt") or {}
    if (receipt.get("status") != "success" or receipt.get("actualCompileOnly") is not True
            or receipt.get("actualPlannerCanonical") != cell["planner_enum"]
            or receipt.get("runtimeProgramConstructed") is not True
            or not positive_nanos(receipt.get("compilePhasesNanos", {}).get("runtimeProgramNanos"))):
        reasons.append("missing successful full compile receipt")
    if (receipt.get("workloadExecutionStarted") is not False
            or receipt.get("workloadExecutionCompleted") is not False
            or receipt.get("observedRunNanos") != 0 or receipt.get("executionNanos") != 0):
        reasons.append("runtime was not excluded")
    audit = receipt.get("plannerRuntimeAudit") or {}
    planned = audit.get("plannedPhysicalHops")
    if (type(planned) is not int or planned <= 0 or audit.get("loweredPhysicalHops") != planned
            or any(audit.get(key) != 0 for key in ("missingPhysicalHops", "missingSynthetic",
                "mismatches", "runtimeInstructionKinds", "federatedDispatchKinds", "workerFragmentKinds"))):
        reasons.append("incomplete lowering audit")
    compile_ns = receipt.get("compileNanos")
    full_ns = receipt.get("planningFullInitialNanos")
    if not positive_nanos(compile_ns) or not positive_nanos(full_ns):
        reasons.append("missing positive complete planning timers")
    else:
        if compile_ns > TARGET_NANOS:
            reasons.append("compile exceeds 20 seconds")
        if full_ns > TARGET_NANOS:
            reasons.append("full initial planning exceeds 20 seconds")
        if full_ns > compile_ns:
            reasons.append("full planning timer exceeds enclosing compile timer")
    return reasons


def evaluate(root):
    root = Path(root)
    manifest = json.loads((root / "manifest.json").read_text())
    expected = campaign.matrix()
    identity = manifest.get("identity", {})
    measurement = manifest.get("measurement", {})
    manifest_errors = []
    if manifest.get("cells") != expected:
        manifest_errors.append("manifest is not the canonical 896-cell matrix")
    for section in (identity, measurement):
        if section.get("timeout_seconds") != {"compile": 60, "runtime": 60}:
            manifest_errors.append("manifest watchdog contract changed")
    if any(section.get(flag) for section in (measurement, identity.get("diagnostic", {}))
            for flag in ("diagnostic_jfr", "diagnostic_compact")):
        manifest_errors.append("diagnostic campaign cannot certify performance")
    jar = identity.get("jar_sha256")
    if (not isinstance(jar, str) or len(jar) != 64
            or any(character not in "0123456789abcdef" for character in jar)):
        manifest_errors.append("missing frozen engine identity")
    try:
        digest = hashlib.sha256()
        with (root / "overlay/SystemDS.jar").open("rb") as frozen_engine:
            for chunk in iter(lambda: frozen_engine.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != jar:
            manifest_errors.append("frozen engine hash differs from manifest")
    except OSError:
        manifest_errors.append("frozen engine artifact is unreadable")
    rows = campaign.latest(root, "compile")
    unexpected = sorted(set(rows) - {cell["id"] for cell in expected})
    if unexpected:
        manifest_errors.append("unexpected cells: " + ", ".join(unexpected))
    rejected, missing, accepted = [], 0, []
    for cell in expected:
        row = rows.get(cell["id"])
        if row is None:
            missing += 1
            continue
        reasons = row_errors(row, cell, jar)
        if reasons:
            rejected.append({"cell": cell["id"], "attempt": row.get("attempt"), "reasons": reasons})
        else:
            accepted.append(row["receipt"]["compileNanos"])
    return {"schema": "planning-performance-20s-v1", "root": str(root.resolve()),
        "passed": not manifest_errors and not rejected and missing == 0,
        "expected": len(expected), "observed": len(rows), "within_target": len(accepted),
        "missing": missing, "rejected": rejected, "manifest_errors": manifest_errors,
        "target_seconds": 20, "watchdog_seconds": 60, "jar_sha256": jar,
        "maximum_accepted_compile_seconds": max(accepted) / 1e9 if accepted else None}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    result = evaluate(args.root)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered)
    print(rendered, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
