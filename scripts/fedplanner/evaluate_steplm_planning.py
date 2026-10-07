#!/usr/bin/env python3
"""Run and strictly evaluate the bounded StepLM planning performance gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
DISPATCH = REPO_ROOT / "scripts/fedplanner/run_LAN_docker.sh"
REFERENCE_RESULT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/steplm-joint-compact-validation-20261007/"
    "steplm-joint-publication-04/result.json")
CASE = "ml_steplm_local_matrix"
RUN_COUNT = 3
MAX_COMPILATION_SECONDS = 20.0
MAX_OBJECTIVE = 37040.115993804146
EXPECTED_SELECTION = [3, 1, 5]
REQUIRED_AUDIT_SCHEMAS = {
    "fed-runtime-capability-v1",
    "fed-runtime-conversion-frontier-v1",
    "fedplanner-candidate-space-v1",
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path,
                        help="parent for the three harness result directories")
    parser.add_argument("--stage-root", type=Path,
                        help="Docker-visible staging parent used by the official harness")
    parser.add_argument("--run-id", help="prefix for fresh run IDs and the evaluation report")
    parser.add_argument("--reference-result", type=Path, default=REFERENCE_RESULT)
    parser.add_argument("--existing-result", type=Path, action="append", default=[],
                        help="evaluate an existing result.json; repeat exactly three times")
    return parser.parse_args(argv)


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def git_provenance() -> dict[str, Any]:
    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True).strip()
    diff = subprocess.check_output(
        ["git", "diff", "--binary", "HEAD"], cwd=REPO_ROOT)
    status = subprocess.check_output(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=REPO_ROOT)
    changed_files: dict[str, str | None] = {}
    for line in status.decode("utf-8", errors="replace").splitlines():
        relative = line[3:].split(" -> ")[-1]
        path = REPO_ROOT / relative
        changed_files[relative] = (sha256_bytes(path.read_bytes()) if path.is_file() else None)
    return {
        "gitHead": head,
        "gitDiffSha256": sha256_bytes(diff),
        "gitDiffBytes": len(diff),
        "gitDiff": diff.decode("utf-8", errors="replace"),
        "gitStatusSha256": sha256_bytes(status),
        "gitStatus": status.decode("utf-8", errors="replace").splitlines(),
        "changedFileSha256": changed_files,
    }


def option(argv: list[str], name: str) -> str | None:
    try:
        return argv[argv.index(name) + 1]
    except (ValueError, IndexError):
        return None


def docker_environment(manifest: dict[str, Any]) -> dict[str, Any]:
    argv = manifest.get("dockerArgv")
    argv = argv if isinstance(argv, list) and all(isinstance(item, str) for item in argv) else []
    image = manifest.get("image")
    return {
        "image": image,
        "pull": option(argv, "--pull"),
        "network": option(argv, "--network"),
        "cpus": option(argv, "--cpus"),
        "memory": option(argv, "--memory"),
        "entrypoint": option(argv, "--entrypoint"),
        "manifestNetwork": manifest.get("network"),
        "planner": manifest.get("planner"),
        "configuredPlanner": manifest.get("configuredPlanner"),
        "noFedRuntimeConversion": manifest.get("noFedRuntimeConversion"),
        "caseTimeoutSeconds": manifest.get("caseTimeoutSeconds"),
        "jfrEnabled": (manifest.get("jfrProfile") or {}).get("enabled")
        if isinstance(manifest.get("jfrProfile"), dict) else None,
    }


def workload_fingerprint(manifest: dict[str, Any]) -> dict[str, Any]:
    raw_inputs = manifest.get("inputSha256")
    inputs = raw_inputs if isinstance(raw_inputs, dict) else {}
    fixtures = manifest.get("fixtureSha256")
    fixtures = fixtures if isinstance(fixtures, dict) else {}
    inventories = manifest.get("artifactInventoryDigests")
    inventories = inventories if isinstance(inventories, dict) else {}
    return {
        "inputManifestPresent": isinstance(raw_inputs, dict),
        "selectedInputSha256": inputs.get(CASE),
        "caseFixtureSha256": fixtures.get(CASE),
        "configFixtureSha256": fixtures.get("config"),
        "dependenciesSha256": inventories.get("dependencies"),
    }


def jvm_resource_fingerprint(script: Path) -> dict[str, Any]:
    if not script.is_file():
        return {"workers": None, "caseProcesses": None}
    workers: list[dict[str, str]] = []
    cases: list[dict[str, str]] = []
    for line in script.read_text(encoding="utf-8").splitlines():
        worker = re.search(r"\bjava\b.*?(-Xmx\S+).*?\s-w\s+(1300[0-2])\b", line)
        if worker:
            workers.append({"heap": worker.group(1), "port": worker.group(2)})
        case = re.search(
            rf"\bjava\b.*?(-Xmx\S+).*?-XX:ActiveProcessorCount=(\d+).*?"
            rf"/cases/{CASE}/(cp|fed)\.dml\b", line)
        if case:
            cases.append({"heap": case.group(1), "activeProcessors": case.group(2),
                          "mode": case.group(3)})
    return {"workers": sorted(workers, key=lambda item: item["port"]),
            "caseProcesses": sorted(cases, key=lambda item: item["mode"])}


def one_case(result: dict[str, Any], errors: list[str]) -> dict[str, Any]:
    cases = result.get("cases")
    if not isinstance(cases, list):
        errors.append("result.cases is missing or is not a list")
        return {}
    matching = [item for item in cases
                if isinstance(item, dict) and item.get("case") == CASE]
    if len(matching) != 1:
        errors.append(f"result must contain exactly one {CASE} case; found {len(matching)}")
        return matching[0] if matching else {}
    return matching[0]


def finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def require(errors: list[str], condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


def evaluate_result(result_path: Path, reference: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    if result_path.is_file():
        result = read_json(result_path)
    else:
        result = {}
        errors.append(f"missing result: {result_path}")
    manifest_path = result_path.with_name("manifest.json")
    if not manifest_path.is_file():
        manifest: dict[str, Any] = {}
        errors.append(f"missing manifest: {manifest_path}")
    else:
        manifest = read_json(manifest_path)
    case = one_case(result, errors)
    environment = docker_environment(manifest)
    workload = workload_fingerprint(manifest)
    jvm_resources = jvm_resource_fingerprint(result_path.with_name("container-run.sh"))
    require(errors, environment == reference["environment"],
            f"environment mismatch: actual={environment!r} reference={reference['environment']!r}")
    require(errors, workload == reference["workload"],
            f"workload fingerprint mismatch: actual={workload!r} reference={reference['workload']!r}")
    require(errors, jvm_resources == reference["jvmResources"],
            f"JVM resource mismatch: actual={jvm_resources!r} reference={reference['jvmResources']!r}")
    require(errors, result.get("status") == "PASSED", "result.status is not PASSED")
    require(errors, result.get("containerReturncode") == 0, "container return code is not zero")
    require(errors, result.get("requestedCases") == [CASE],
            f"result.requestedCases must be [{CASE!r}]")
    require(errors, manifest.get("requestedCases") == [CASE],
            f"manifest.requestedCases must be [{CASE!r}]")
    require(errors, result.get("classPreflightPassed") is True,
            "class preflight did not pass")
    require(errors, result.get("overlayPreflightPassed") is True,
            "overlay preflight did not pass")
    require(errors, result.get("modelProofPassed") is True,
            "model proof did not pass")
    require(errors, result.get("runtimeConversionViolations") == [],
            "runtime conversion violations are present or missing")
    require(errors, case.get("passed") is True, "StepLM harness case did not pass")
    require(errors, case.get("cpReturncode") == 0, "CP return code is not zero")
    require(errors, case.get("fedReturncode") == 0, "FED return code is not zero")
    require(errors, case.get("auditErrors") == [], "audit errors are present or missing")
    require(errors, case.get("runtimeAuditViolations") == [],
            "runtime audit violations are present or missing")
    require(errors, isinstance(case.get("auditRows"), int) and case.get("auditRows", 0) > 0,
            "audit row count is missing or empty")
    schemas = case.get("auditSchemas")
    require(errors, isinstance(schemas, list) and REQUIRED_AUDIT_SCHEMAS.issubset(set(schemas)),
            "required audit schemas are missing")
    model = case.get("modelComparison")
    model = model if isinstance(model, dict) else {}
    require(errors, model.get("matched") is True, "five-coefficient model does not match")
    require(errors, model.get("finite") is True, "model coefficients are not finite")
    require(errors, model.get("nonzero") is True, "model coefficients are all zero")
    require(errors, model.get("entries") == 5 and model.get("actualEntries") == 5,
            "model comparison does not contain exactly five coefficients")
    require(errors, model.get("shapeMatched") is True and model.get("actualShape") == [5, 1],
            "model shape is not an exactly matched 5x1")
    require(errors, model.get("maxAbsDifference") == 0,
            "CP/FED coefficient maximum absolute difference is not zero")
    require(errors, model.get("errors") == [], "model comparison errors are present or missing")
    selection = case.get("selectionComparison")
    selection = selection if isinstance(selection, dict) else {}
    require(errors, selection.get("matched") is True, "selection does not match CP")
    require(errors, selection.get("integral") is True, "selection is not integral")
    require(errors, selection.get("actual") == EXPECTED_SELECTION,
            f"FED selection is not {EXPECTED_SELECTION}")
    require(errors, selection.get("reference") == EXPECTED_SELECTION,
            f"CP selection is not {EXPECTED_SELECTION}")
    require(errors, case.get("plannerTraceRequired") is True,
            "planner trace was not required")
    require(errors, case.get("plannerTraceComplete") is True,
            "planner trace is incomplete")

    statistics = case.get("fedStatistics")
    statistics = statistics if isinstance(statistics, dict) else {}
    compilation_seconds = statistics.get("compilationSeconds")
    require(errors, finite_number(compilation_seconds),
            "FED compilationSeconds is missing or non-finite")
    if finite_number(compilation_seconds):
        require(errors, 0 <= compilation_seconds <= MAX_COMPILATION_SECONDS,
                f"FED compilationSeconds {compilation_seconds} is outside "
                f"[0, {MAX_COMPILATION_SECONDS}]")
    summary = case.get("plannerCheckpointSummary")
    summary = summary if isinstance(summary, dict) else {}
    final = summary.get("final")
    final = final if isinstance(final, dict) else {}
    objective = final.get("upper")
    require(errors, finite_number(objective),
            "selected canonical objective (final.upper) is missing or non-finite")
    if finite_number(objective):
        require(errors, objective <= MAX_OBJECTIVE,
                f"selected objective {objective} is worse than {MAX_OBJECTIVE}")

    inventories = manifest.get("artifactInventoryDigests")
    inventories = inventories if isinstance(inventories, dict) else {}
    for name in ("mainClasses", "testClasses", "mainSources", "testSources", "dependencies"):
        require(errors, isinstance(inventories.get(name), str) and len(inventories[name]) == 64,
                f"artifact inventory digest {name} is missing")
    source_hashes = manifest.get("sourceSha256")
    source_hashes = source_hashes if isinstance(source_hashes, dict) else {}
    for name in ("runner", "dispatch"):
        require(errors, isinstance(source_hashes.get(name), str) and len(source_hashes[name]) == 64,
                f"harness source digest {name} is missing")
    overlay = result.get("overlayPreflight")
    overlay = overlay if isinstance(overlay, dict) else {}
    overlay_actual = overlay.get("actual")
    overlay_actual = overlay_actual if isinstance(overlay_actual, dict) else {}
    require(errors, overlay.get("status") == "PASSED" and bool(overlay_actual),
            "class overlay receipt is missing or failed")
    require(errors, all(isinstance(item, dict) and item.get("matched") is True
                        and isinstance(item.get("actualSha256"), str)
                        for item in overlay_actual.values()),
            "class overlay digests are incomplete or mismatched")
    return {
        "result": str(result_path),
        "passed": not errors,
        "errors": errors,
        "compilationSeconds": compilation_seconds,
        "selectedObjective": objective,
        "selection": selection.get("actual"),
        "modelCoefficients": model.get("actualEntries"),
        "auditRows": case.get("auditRows"),
        "environment": environment,
        "workloadFingerprint": workload,
        "jvmResourceFingerprint": jvm_resources,
        "manifestContainer": manifest.get("container"),
        "manifestRun": manifest.get("run"),
        "artifactInventoryDigests": inventories,
        "harnessSourceSha256": source_hashes,
        "classOverlayDigests": {name: item.get("actualSha256")
                                for name, item in overlay_actual.items()
                                if isinstance(item, dict)},
    }


def reference_contract(reference_result: Path) -> dict[str, Any]:
    manifest_path = reference_result.with_name("manifest.json")
    if not reference_result.is_file() or not manifest_path.is_file():
        raise ValueError("reference result and adjacent manifest are required")
    reference = read_json(reference_result)
    case_errors: list[str] = []
    case = one_case(reference, case_errors)
    final = ((case.get("plannerCheckpointSummary") or {}).get("final") or {})
    if final.get("upper") != MAX_OBJECTIVE:
        raise ValueError("reference canonical objective does not match the pinned contract")
    manifest = read_json(manifest_path)
    environment = docker_environment(manifest)
    if any(value is None for value in environment.values()):
        raise ValueError(f"reference environment fingerprint is incomplete: {environment!r}")
    workload = workload_fingerprint(manifest)
    if (not isinstance(workload["caseFixtureSha256"], dict)
            or not isinstance(workload["configFixtureSha256"], dict)
            or not isinstance(workload["dependenciesSha256"], str)):
        raise ValueError(f"reference workload fingerprint is incomplete: {workload!r}")
    resources = jvm_resource_fingerprint(reference_result.with_name("container-run.sh"))
    if len(resources.get("workers") or []) != 3 or len(resources.get("caseProcesses") or []) != 2:
        raise ValueError(f"reference JVM resource fingerprint is incomplete: {resources!r}")
    return {"environment": environment, "workload": workload, "jvmResources": resources}


def harness_command(output_root: Path, stage_root: Path, run_id: str) -> list[str]:
    return [
        str(DISPATCH), "--joint-boundary-e2e",
        "--case", CASE,
        "--output-root", str(output_root),
        "--stage-root", str(stage_root),
        "--run-id", run_id,
        "--case-timeout-seconds", "600",
        "--planner", "local",
    ]


def run_fresh(args: argparse.Namespace) -> tuple[list[Path], dict[str, int]]:
    if args.output_root is None or args.stage_root is None or not args.run_id:
        raise ValueError("--output-root, --stage-root, and --run-id are required for fresh runs")
    results: list[Path] = []
    returncodes: dict[str, int] = {}
    for ordinal in range(1, RUN_COUNT + 1):
        run_id = f"{args.run_id}-{ordinal:02d}"
        run = args.output_root / run_id
        stage = args.stage_root / run_id
        if run.exists() or stage.exists():
            raise ValueError(f"fresh run path already exists: {run if run.exists() else stage}")
        completed = subprocess.run(
            harness_command(args.output_root, args.stage_root, run_id),
            cwd=REPO_ROOT, check=False)
        result = run / "result.json"
        results.append(result)
        returncodes[str(result)] = completed.returncode
    return results, returncodes


def write_report(args: argparse.Namespace, result_paths: list[Path],
                 reference: dict[str, Any], provenance_before: dict[str, Any],
                 provenance_after: dict[str, Any],
                 harness_returncodes: dict[str, int] | None = None) -> dict[str, Any]:
    runs = [evaluate_result(path, reference) for path in result_paths]
    for run in runs:
        run["harnessProcessReturncode"] = (harness_returncodes or {}).get(run["result"])
        if run["harnessProcessReturncode"] not in (None, 0):
            run["errors"].append(
                f"harness process return code is {run['harnessProcessReturncode']}")
            run["passed"] = False
    digest_sets = [run["artifactInventoryDigests"] for run in runs]
    consistent_artifacts = bool(digest_sets) and all(value == digest_sets[0] for value in digest_sets)
    errors: list[str] = []
    resolved_results = [str(path.resolve()) for path in result_paths]
    manifest_containers = [run["manifestContainer"] for run in runs]
    manifest_runs = [run["manifestRun"] for run in runs]
    if not consistent_artifacts:
        errors.append("artifact inventory digests differ across runs")
    if len(runs) != RUN_COUNT:
        errors.append(f"expected exactly {RUN_COUNT} runs, found {len(runs)}")
    if len(set(resolved_results)) != len(resolved_results):
        errors.append("result paths are not distinct")
    if any(value is None for value in manifest_containers) or len(set(manifest_containers)) != len(runs):
        errors.append("manifest container identities are missing or not distinct")
    if any(value is None for value in manifest_runs) or len(set(manifest_runs)) != len(runs):
        errors.append("manifest run identities are missing or not distinct")
    if provenance_before != provenance_after:
        errors.append("repository provenance changed during evaluation")
    report = {
        "schema": "systemds-steplm-planning-evaluation-v1",
        "status": "PASSED" if not errors and all(run["passed"] for run in runs) else "FAILED",
        "case": CASE,
        "runCount": len(runs),
        "requiredRunCount": RUN_COUNT,
        "maxCompilationSeconds": MAX_COMPILATION_SECONDS,
        "maxSelectedObjective": MAX_OBJECTIVE,
        "expectedSelection": EXPECTED_SELECTION,
        "referenceResult": str(args.reference_result),
        "referenceContract": reference,
        "gitProvenanceBefore": provenance_before,
        "gitProvenanceAfter": provenance_after,
        "provenanceStable": provenance_before == provenance_after,
        "artifactDigestsConsistent": consistent_artifacts,
        "errors": errors,
        "runs": runs,
    }
    if args.output_root is not None and args.run_id:
        args.output_root.mkdir(parents=True, exist_ok=True)
        report_path = args.output_root / f"{args.run_id}-evaluation.json"
        report["report"] = str(report_path)
        report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.existing_result and len(args.existing_result) != RUN_COUNT:
        raise ValueError(f"--existing-result must be supplied exactly {RUN_COUNT} times")
    reference = reference_contract(args.reference_result)
    provenance_before = git_provenance()
    if args.existing_result:
        result_paths, returncodes = args.existing_result, {}
    else:
        result_paths, returncodes = run_fresh(args)
    provenance_after = git_provenance()
    report = write_report(args, result_paths, reference,
                          provenance_before, provenance_after, returncodes)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)
