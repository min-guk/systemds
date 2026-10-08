#!/usr/bin/env python3
"""Read-only summary for paired COFEE 50K x 128 validation artifacts."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
from typing import Any


RUN_RE = re.compile(r"^(baseline|candidate)-(.+)-pair(\d+)$")
CGROUP_FILE_RE = re.compile(r"^FILE=(\S+)\s*$")


def _phase_parser():
    path = Path(__file__).with_name("analysis_phase_report.py")
    spec = importlib.util.spec_from_file_location("cofee_analysis_phase_report", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.parse_log


PARSE_PHASE_LOG = _phase_parser()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_json(path: Path) -> tuple[Any | None, str | None]:
    if not path.is_file():
        return None, f"missing {path.name}"
    try:
        return json.loads(path.read_text()), None
    except (OSError, json.JSONDecodeError) as error:
        return None, f"invalid {path.name}: {error}"


def _selected_ids(root: Path) -> tuple[set[str] | None, str | None]:
    value, error = _read_json(root / "runtime-selection.json")
    if error:
        return None, error
    ids = value.get("selected_cell_ids") if isinstance(value, dict) else None
    if not isinstance(ids, list) or not ids or any(not isinstance(item, str) for item in ids):
        return None, "runtime-selection.json has no non-empty selected_cell_ids"
    return set(ids), None


def _attempt_kind(row: dict) -> str:
    if row.get("diagnostic_only") or row.get("diagnostic_runtime_cell"):
        return "diagnostic"
    if row.get("phase") != "runtime":
        return "calibration-or-non-runtime"
    return "production-runtime"


def _valid_attempt(row: dict) -> tuple[bool, list[str]]:
    reasons = []
    if _attempt_kind(row) != "production-runtime":
        reasons.append(f"attempt kind is {_attempt_kind(row)}")
    if row.get("status") != "passed":
        reasons.append(f"result status is {row.get('status')!r}")
    if row.get("errors"):
        reasons.append("result contains errors")
    receipt = row.get("receipt")
    if not isinstance(receipt, dict) or receipt.get("status") != "success":
        reasons.append("successful receipt is absent")
    elif (receipt.get("workloadExecutionCompleted") is not True
          or receipt.get("workloadExecutionStarted") is not True):
        reasons.append("receipt does not prove completed workload execution")
    comparison = row.get("comparison")
    comparison_receipt = comparison.get("receipt") if isinstance(comparison, dict) else None
    if not isinstance(comparison_receipt, dict) or comparison_receipt.get("passed") is not True:
        reasons.append("passing numerical comparison receipt is absent")
    for field in ("compile_seconds", "runtime_seconds"):
        value = row.get(field)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            reasons.append(f"valid {field} is absent")
    return not reasons, reasons


def _parse_cgroup_values(raw: str) -> dict[str, int]:
    values: dict[str, int] = {}
    current = None
    for line in raw.splitlines():
        match = CGROUP_FILE_RE.match(line)
        if match:
            current = match.group(1)
            continue
        if current and line.strip().isdigit():
            values[current] = int(line.strip())
            current = None
    return values


def _peak_memory(attempt_dir: Path) -> dict:
    nodes = []
    for path in sorted(attempt_dir.glob("*-container-health.json")):
        value, error = _read_json(path)
        if error or not isinstance(value, dict):
            nodes.append({"file": str(path), "peakBytes": None, "reason": error})
            continue
        raw = value.get("memory_cgroup_raw")
        parsed = _parse_cgroup_values(raw) if isinstance(raw, str) else {}
        peak = parsed.get("/sys/fs/cgroup/memory.peak")
        source = "/sys/fs/cgroup/memory.peak"
        if peak is None:
            peak = parsed.get("/sys/fs/cgroup/memory/memory.max_usage_in_bytes")
            source = "/sys/fs/cgroup/memory/memory.max_usage_in_bytes"
        nodes.append({"file": str(path), "peakBytes": peak, "source": source if peak is not None else None,
                      "reason": None if peak is not None else "container cgroup peak was not captured"})
    peaks = [row["peakBytes"] for row in nodes if isinstance(row.get("peakBytes"), int)]
    return {
        "maxContainerPeakBytes": max(peaks) if peaks else None,
        "nodes": nodes,
        "definition": "maximum captured container cgroup peak across coordinator and workers; not JVM live heap or allocation volume",
        "reason": None if peaks else "no captured container cgroup peak evidence",
    }


def _candidate_timing(row: dict) -> dict:
    receipt = row.get("receipt", {})
    phases = receipt.get("candidateE2E") if isinstance(receipt, dict) else None
    if not isinstance(phases, dict):
        phases = row.get("candidate_phases_ns")
    phases = phases if isinstance(phases, dict) else {}
    names = {
        "analysis": "analysisNanos",
        "model": "modelNanos",
        "costSurface": "costSurfaceNanos",
        "optimizer": "optimizerNanos",
        "candidateE2ETotal": "totalNanos",
    }
    reasons = {}
    def seconds(field):
        value = row.get(field)
        if type(value) in (int, float) and math.isfinite(value) and value >= 0:
            return value
        reasons[field] = "missing, boolean, non-finite, or negative elapsed value"
        return None
    converted = {}
    for name, key in names.items():
        value = phases.get(key)
        if type(value) is int and value >= 0:
            converted[name + "Seconds"] = value / 1e9
        else:
            converted[name + "Seconds"] = None
            reasons[key] = "missing, boolean, or negative nanosecond value"
    return {
        "compilationElapsedSeconds": seconds("compile_seconds"),
        "runtimeElapsedSeconds": seconds("runtime_seconds"),
        "fullInitialPlanningSeconds": seconds("full_initial_planning_seconds"),
        **converted,
        "rawCandidateE2ENanos": phases or None,
        "unmeasuredReasons": reasons,
    }


def _profile(attempt_dir: Path, row: dict) -> dict:
    value, error = _read_json(attempt_dir / "cost-profile.json")
    if error or not isinstance(value, dict):
        return {"profileId": row.get("cost_profile_sha256"), "documentSha256": None,
                "resources": None, "reason": error}
    identity = value.get("identity") if isinstance(value.get("identity"), dict) else {}
    runtime = value.get("_runtime") if isinstance(value.get("_runtime"), dict) else {}
    return {
        "profileId": value.get("profile_sha256"),
        "documentSha256": _sha256(attempt_dir / "cost-profile.json"),
        "sourceSha256": runtime.get("source_sha256"),
        "source": runtime.get("source"),
        "resources": identity.get("resources"),
        "threads": identity.get("threads"),
        "coordinator": identity.get("coordinator"),
        "workers": identity.get("workers"),
        "topology": {"coordinator": identity.get("coordinator"),
                     "workers": identity.get("workers")},
        "jarSha256": identity.get("jar_sha256"),
        "reason": None,
    }


def _equivalence_evidence(row: dict, attempt_dir: Path) -> dict:
    receipt = row.get("receipt") if isinstance(row.get("receipt"), dict) else {}
    comparison = row.get("comparison") if isinstance(row.get("comparison"), dict) else {}
    comparison_receipt = comparison.get("receipt") if isinstance(comparison.get("receipt"), dict) else {}
    audit = receipt.get("plannerRuntimeAudit") if isinstance(receipt.get("plannerRuntimeAudit"), dict) else {}
    return {
        "numericalComparisonPassed": comparison_receipt.get("passed"),
        "numericalComparison": comparison_receipt or None,
        "outputIdentity": row.get("output_identity"),
        "receiptSha256": _sha256(attempt_dir / "receipt.json") if (attempt_dir / "receipt.json").is_file() else None,
        "comparisonSha256": _sha256(attempt_dir / "comparison.json") if (attempt_dir / "comparison.json").is_file() else None,
        "initialSelectionFingerprint": receipt.get("initialSelectionFingerprint"),
        "finalSelectionFingerprint": receipt.get("finalSelectionFingerprint"),
        "analysisFingerprint": receipt.get("analysisFingerprint"),
        "auditPlanFingerprint": audit.get("plan"),
        "plannerRuntimeAudit": audit or None,
        "selectedCandidateSelections": receipt.get("selectedCandidateSelections"),
    }


def _phase_evidence(attempt_dir: Path) -> dict:
    log = attempt_dir / "coordinator.log"
    if not log.is_file():
        return {"terminalAnalyses": [], "reason": "coordinator.log is absent"}
    try:
        parsed = PARSE_PHASE_LOG(log)
        return {"terminalAnalyses": parsed["terminalAnalyses"],
                "reason": None if parsed["terminalAnalyses"] else "no terminal ANALYSIS snapshot was emitted"}
    except ValueError as error:
        return {"terminalAnalyses": [], "reason": str(error)}


def _shape_evidence(attempt_dir: Path) -> dict:
    path = attempt_dir / "render-contract.json"
    value, error = _read_json(path)
    if error or not isinstance(value, dict):
        return {"inputs": None, "documentSha256": None, "reason": error}
    inputs = []
    for item in value.get("inputs", []):
        if isinstance(item, dict):
            inputs.append({key: item.get(key) for key in
                           ("role", "rows", "cols", "nnz", "privacy", "metadata_sha256")})
    return {"inputs": inputs, "documentSha256": _sha256(path),
            "contentVerification": value.get("content_verification"), "reason": None}


def _summarize_attempt(path: Path, row: dict) -> dict:
    valid, reasons = _valid_attempt(row)
    diagnostic = (path.parent / "diagnostic-conversion.json").exists()
    superseded = (path.parent / "superseded-validation.json").exists()
    if diagnostic:
        valid = False
        reasons.append("attempt converted to diagnostic execution")
    if superseded:
        valid = False
        reasons.append("attempt superseded by a newer validation engine")
    base = {
        "path": str(path), "attempt": row.get("attempt"), "cell": row.get("cell"),
        "kind": "superseded" if superseded else "diagnostic" if diagnostic else _attempt_kind(row),
        "status": row.get("status"), "eligibleSuccess": valid,
        "exclusionReasons": reasons,
    }
    if not valid:
        return {**base, "timing": None, "peakMemory": None, "profile": None,
                "phaseEvidence": None, "shapeEvidence": None, "equivalenceEvidence": None}
    attempt_dir = path.parent
    return {**base, "timing": _candidate_timing(row), "peakMemory": _peak_memory(attempt_dir),
            "profile": _profile(attempt_dir, row), "phaseEvidence": _phase_evidence(attempt_dir),
            "shapeEvidence": _shape_evidence(attempt_dir),
            "equivalenceEvidence": _equivalence_evidence(row, attempt_dir)}


def _variant(root: Path, variant: str, workload: str, pair: int) -> dict:
    selected, selection_error = _selected_ids(root)
    attempts = []
    latest: dict[str, dict] = {}
    malformed = []
    for path in sorted((root / "attempts" / "runtime").glob("*/result.json")):
        row, error = _read_json(path)
        if error or not isinstance(row, dict):
            malformed.append({"path": str(path), "reason": error or "result is not an object"})
            continue
        item = _summarize_attempt(path, row)
        attempts.append(item)
        cell_id = row.get("cell", {}).get("id") if isinstance(row.get("cell"), dict) else None
        if isinstance(cell_id, str) and item["kind"] == "production-runtime":
            latest[cell_id] = item
    missing = sorted(selected - set(latest)) if selected is not None else []
    invalid = [latest[cell] for cell in sorted(latest) if selected and cell in selected
               and not latest[cell]["eligibleSuccess"]]
    if selection_error or malformed:
        status = "invalid"
    elif missing:
        status = "incomplete"
    elif invalid:
        status = "failed"
    elif selected is not None and selected and set(latest) >= selected:
        status = "passed"
    else:
        status = "incomplete"
    successful = [latest[cell] for cell in sorted(selected or ())
                  if cell in latest and latest[cell]["eligibleSuccess"]]
    manifest, manifest_error = _read_json(root / "manifest.json")
    return {
        "variant": variant, "workload": workload, "pair": pair, "root": str(root),
        "status": status, "selectedCellIds": sorted(selected) if selected else [],
        "missingCellIds": missing, "invalidSelectedAttempts": invalid,
        "malformedResults": malformed, "selectionError": selection_error,
        "manifest": {
            "sha256": _sha256(root / "manifest.json") if manifest_error is None else None,
            "schema": manifest.get("schema") if isinstance(manifest, dict) else None,
            "identity": manifest.get("identity") if isinstance(manifest, dict) else None,
            "reason": manifest_error,
        },
        "successfulAttempts": successful,
        "allAttempts": attempts,
    }


def _tri_equal(left: Any, right: Any, missing_reason: str) -> dict:
    if left is None or right is None:
        return {"equal": None, "reason": missing_reason, "baseline": left, "candidate": right}
    return {"equal": left == right, "reason": None, "baseline": left, "candidate": right}


def _all_tri(rows: list[dict], field: str) -> bool | None:
    values = [row[field]["equal"] for row in rows]
    if any(value is False for value in values):
        return False
    return True if values and all(value is True for value in values) else None


def _manifest_live_metrics(variant: dict) -> Any:
    manifest = variant.get("manifest")
    identity = manifest.get("identity") if isinstance(manifest, dict) else None
    return identity.get("validation_live_metrics") if isinstance(identity, dict) else None


def _semantic_pair_comparison(baseline: dict, candidate: dict) -> dict:
    if baseline["status"] != "passed" or candidate["status"] != "passed":
        return {"comparable": False, "reason": "both variants must pass before equivalence is evaluated"}
    if baseline["selectedCellIds"] != candidate["selectedCellIds"]:
        return {"comparable": False, "reason": "selectedCellIds differ",
                "baselineSelectedCellIds": baseline["selectedCellIds"],
                "candidateSelectedCellIds": candidate["selectedCellIds"]}
    left = {row.get("cell", {}).get("id"): row for row in baseline["successfulAttempts"]}
    right = {row.get("cell", {}).get("id"): row for row in candidate["successfulAttempts"]}
    if set(left) != set(right) or set(left) != set(baseline["selectedCellIds"]):
        return {"comparable": False, "reason": "successful attempt cell IDs do not exactly match selection",
                "baselineAttemptCellIds": sorted(left), "candidateAttemptCellIds": sorted(right)}
    rows = []
    for cell_id in baseline["selectedCellIds"]:
        first, second = left[cell_id], right[cell_id]
        a = first["equivalenceEvidence"]
        b = second["equivalenceEvidence"]
        output_a, output_b = a.get("outputIdentity"), b.get("outputIdentity")
        initial_a, initial_b = a.get("initialSelectionFingerprint"), b.get("initialSelectionFingerprint")
        final_a, final_b = a.get("finalSelectionFingerprint"), b.get("finalSelectionFingerprint")
        audit_a, audit_b = a.get("auditPlanFingerprint"), b.get("auditPlanFingerprint")
        selected_a, selected_b = a.get("selectedCandidateSelections"), b.get("selectedCandidateSelections")
        pa, pb = first.get("profile") or {}, second.get("profile") or {}
        sa, sb = first.get("shapeEvidence") or {}, second.get("shapeEvidence") or {}
        profile = {
            "profileId": _tri_equal(pa.get("profileId"), pb.get("profileId"), "profile ID unavailable"),
            "sourceSha256": _tri_equal(pa.get("sourceSha256"), pb.get("sourceSha256"), "profile source SHA unavailable"),
            "resources": _tri_equal(pa.get("resources"), pb.get("resources"), "profile resources unavailable"),
            "threads": _tri_equal(pa.get("threads"), pb.get("threads"), "profile thread count unavailable"),
            "topology": _tri_equal(pa.get("topology"), pb.get("topology"), "profile topology unavailable"),
            "liveMetrics": _tri_equal(_manifest_live_metrics(baseline), _manifest_live_metrics(candidate),
                                      "live-metrics setting unavailable from campaign manifest"),
            "raw": {"baseline": pa, "candidate": pb},
        }
        rows.append({
            "cellId": cell_id,
            "numericalOutputs": _tri_equal(output_a, output_b, "output identity unavailable"),
            "initialPlan": _tri_equal(initial_a, initial_b, "initial selection fingerprint unavailable"),
            "finalPlan": _tri_equal(final_a, final_b, "final selection fingerprint unavailable"),
            "auditPlan": _tri_equal(audit_a, audit_b, "audit plan fingerprint unavailable"),
            "selectedReceipts": _tri_equal(selected_a, selected_b,
                                            "producer does not emit selectedCandidateSelections; semantic receipt equivalence is unmeasured"),
            "inputShapes": _tri_equal(sa.get("inputs"), sb.get("inputs"),
                                      "render-contract input shape evidence unavailable"),
            "profile": profile,
            "shapeEvidence": {"baseline": sa, "candidate": sb},
            "baseline": a, "candidate": b,
        })
    profile_fields = ("profileId", "sourceSha256", "resources", "threads", "topology", "liveMetrics")
    profile_summary = {field: _all_tri([{"value": row["profile"][field]} for row in rows], "value")
                       for field in profile_fields}
    return {"comparable": True, "rows": rows,
            "allNumericalOutputsEqual": _all_tri(rows, "numericalOutputs"),
            "allFinalPlansEqual": _all_tri(rows, "finalPlan"),
            "allSelectedReceiptsEqual": _all_tri(rows, "selectedReceipts"),
            "profileEquality": profile_summary,
            "limitations": ["selectedCandidateSelections is not emitted by the current probe",
                            "full receipt semantic equivalence and optimizer objective raw bits are unmeasured"]}


def build_report(campaign: Path) -> dict:
    grouped: dict[tuple[str, int], dict[str, dict]] = {}
    ignored = []
    for root in sorted(path for path in campaign.iterdir() if path.is_dir()):
        match = RUN_RE.match(root.name)
        if not match:
            ignored.append(str(root))
            continue
        variant, workload, pair = match.group(1), match.group(2), int(match.group(3))
        grouped.setdefault((workload, pair), {})[variant] = _variant(root, variant, workload, pair)
    pairs = []
    timing_totals: dict[str, float] = {}
    passed_variants = 0
    for (workload, pair), variants in sorted(grouped.items()):
        for variant in ("baseline", "candidate"):
            if variant not in variants:
                variants[variant] = {"variant": variant, "workload": workload, "pair": pair,
                    "root": None, "status": "incomplete", "selectedCellIds": [],
                    "missingCellIds": [], "invalidSelectedAttempts": [], "malformedResults": [],
                    "selectionError": "variant directory is absent", "manifest": None,
                    "successfulAttempts": [], "allAttempts": []}
            if variants[variant]["status"] == "passed":
                passed_variants += 1
                for attempt in variants[variant]["successfulAttempts"]:
                    for key, value in attempt["timing"].items():
                        if key.endswith("Seconds") and isinstance(value, (int, float)):
                            timing_totals[key] = timing_totals.get(key, 0.0) + value
        pairs.append({"workload": workload, "pair": pair, "variants": variants,
                      "equivalence": _semantic_pair_comparison(variants["baseline"], variants["candidate"])})
    return {
        "schema": "cofee-50k128-large-report/v1", "campaign": str(campaign),
        "semantics": {
            "successfulTiming": "only selected production-runtime attempts with passed result, successful completed-execution receipt, passing numerical comparison, and valid elapsed fields",
            "excluded": "incomplete, failed, malformed, superseded, diagnostic, calibration, and non-runtime attempts never contribute successful timing",
            "peakMemory": "maximum captured container cgroup peak across coordinator and workers; not JVM live heap or allocation volume",
            "analysisAllocation": "SEARCH_SPACE allocation fields are thread-allocation counters, not peak memory",
            "fullInitialPlanning": "receipt planningFullInitialNanos/result full_initial_planning_seconds covers DMLScript initial planning from parse start through runtime-program construction",
            "candidateE2E": "CandidateE2E totalNanos is the narrower FedPlanner candidate-preparation through receipt-handoff interval and is reported separately from compilation and full initial planning",
            "compilationElapsed": "result compile_seconds is the separate SystemDS total compilation timer",
            "runtimeElapsed": "result runtime_seconds is receipt executionNanos and excludes compilation",
            "equivalence": "pair fields are compared only after both variants pass; emitted output identities, selection fingerprints, audit plan, and optional receipt fields are retained with tri-state results",
            "equivalenceLimit": "this report does not establish full receipt semantic equivalence and does not have optimizer objective raw bits",
            "nulls": "null means unavailable or unmeasured; the adjacent reason field explains absence where applicable",
        },
        "summary": {"pairCount": len(pairs), "passedVariantCount": passed_variants,
                    "successfulAttemptTimingTotals": timing_totals},
        "ignoredDirectories": ignored, "pairs": pairs,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("campaign", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = build_report(args.campaign.resolve())
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
