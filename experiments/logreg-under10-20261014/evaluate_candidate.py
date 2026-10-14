#!/usr/bin/env python3
"""Evaluate one authorized LogReg candidate against the frozen 26.8 s reference."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
    data = Path(path).read_bytes()
    return {"path": str(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def audit_rows(directory):
    rows = []
    artifacts = []
    for path in sorted(Path(directory).glob("*.jsonl")):
        artifacts.append(sha(path))
        for line in path.read_text().splitlines():
            row = json.loads(line)
            row.pop("pid", None)  # run-local process identity, not planner/runtime semantics
            rows.append(row)
    return sorted(json.dumps(row, sort_keys=True, separators=(",", ":")) for row in rows), artifacts


def delta(before, after):
    return {"before": before, "after": after, "delta": after - before,
            "percent": (after / before - 1.0) * 100 if before else None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline-result", required=True)
    ap.add_argument("--candidate-result", required=True)
    ap.add_argument("--baseline-csv", required=True)
    ap.add_argument("--candidate-csv", required=True)
    ap.add_argument("--baseline-audit-dir", required=True)
    ap.add_argument("--candidate-audit-dir", required=True)
    ap.add_argument("--candidate-profile", required=True)
    ap.add_argument("--candidate-hotspots", required=True)
    ap.add_argument("--baseline-profile", required=True)
    ap.add_argument("--baseline-hotspots", required=True)
    ap.add_argument("--freeze-verification", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    br = json.loads(Path(args.baseline_result).read_text())
    cr = json.loads(Path(args.candidate_result).read_text())
    bc, cc = br["cases"][0], cr["cases"][0]
    bp, cp = bc["memoPlanning"][0], cc["memoPlanning"][0]
    bprof = json.loads(Path(args.baseline_profile).read_text())
    cprof = json.loads(Path(args.candidate_profile).read_text())
    bhot = json.loads(Path(args.baseline_hotspots).read_text())
    chot = json.loads(Path(args.candidate_hotspots).read_text())
    freeze = json.loads(Path(args.freeze_verification).read_text())
    ba, ba_files = audit_rows(args.baseline_audit_dir)
    ca, ca_files = audit_rows(args.candidate_audit_dir)
    bcsv, ccsv = sha(args.baseline_csv), sha(args.candidate_csv)

    gates = {
        "runPassed": cr.get("status") == "PASSED" and cc.get("passed") is True,
        "planningUnder10Seconds": cp["totalSeconds"] < 10.0,
        "optimizerOptimal": cp["status"] == "OPTIMAL",
        "exactObjectiveEqual": cp["estimatedCost"] == bp["estimatedCost"],
        "csvByteEqual": (bcsv["size"], bcsv["sha256"]) == (ccsv["size"], ccsv["sha256"]),
        "fedFingerprintEqual": cc["fedFingerprint"] == bc["fedFingerprint"],
        "actionDiagnosticsEqual": cc["actionDiagnostics"] == bc["actionDiagnostics"],
        "runtimeAuditNormalizedEqual": ca == ba,
        "runtimeAuditClean": cc["runtimeAuditViolations"] == [],
        "oracleTraceEqual": (cp.get("oracleHits"), cp.get("oracleMisses")) ==
            (bp.get("oracleHits"), bp.get("oracleMisses")),
        "nativeUniverseEqual": cc["memoConstruction"]["nativeGeneration"] ==
            bc["memoConstruction"]["nativeGeneration"],
        "nativeRequestsEqual": cc["memoConstruction"]["nativeRequests"] ==
            bc["memoConstruction"]["nativeRequests"],
        "workerCountEqual": cp["workers"] == bp["workers"] == 3,
        "nativeRulesEqual": cp["nativeRules"] == bp["nativeRules"],
        "supplyCountEqual": cp["supplies"] == bp["supplies"],
        "jfrParsedWithoutLoss": cc["jfrProfilePassed"] is True and len(cprof["dataLoss"]) == 0,
        "frozenEngineAndRunInputsVerified": freeze.get("passed") is True,
    }
    result = {
        "schema": "logreg-under10-candidate-evaluation-v1",
        "passed": all(gates.values()),
        "gates": gates,
        "note": "Search-node equality is intentionally not a gate; stronger exact bounds should change it.",
        "planning": delta(bp["totalSeconds"], cp["totalSeconds"]),
        "optimization": delta(bp["phaseSeconds"]["optimization"], cp["phaseSeconds"]["optimization"]),
        "search": {"baseline": bc["memoSearch"][0], "candidate": cc["memoSearch"][0],
                   "nodes": delta(bp["nodes"], cp["nodes"])},
        "solution": {"baselineObjective": bp["estimatedCost"], "candidateObjective": cp["estimatedCost"],
                     "baselineFingerprint": bc["fedFingerprint"], "candidateFingerprint": cc["fedFingerprint"],
                     "baselineCsv": bcsv, "candidateCsv": ccsv},
        "oracle": {"baseline": {"hits": bp.get("oracleHits"), "misses": bp.get("oracleMisses")},
                   "candidate": {"hits": cp.get("oracleHits"), "misses": cp.get("oracleMisses")}},
        "runtimeAudit": {"baselineArtifacts": ba_files, "candidateArtifacts": ca_files,
                         "baselineRows": len(ba), "candidateRows": len(ca)},
        "jfr": {
            "optimizerExecutionSamples": delta(bhot["optimizerExecutionSamples"], chot["optimizerExecutionSamples"]),
            "optimizerAllocationWeightBytes": delta(bhot["optimizerAllocationWeightBytes"], chot["optimizerAllocationWeightBytes"]),
            "gcPauseSeconds": delta(bprof["gc"]["stopTheWorldPauseSeconds"], cprof["gc"]["stopTheWorldPauseSeconds"]),
            "candidateExclusiveBuckets": chot["exclusiveNearestMemoBuckets"],
            "candidateTrackedInclusiveMethods": chot["trackedInclusiveMethods"],
        },
        "freezeVerification": freeze,
        "limitations": ["one JFR run per candidate", "JFR samples and allocation weights are statistical"],
    }
    Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"passed": result["passed"], "gates": gates,
                      "planning": result["planning"], "nodes": result["search"]["nodes"]}, indent=2))


if __name__ == "__main__":
    main()
