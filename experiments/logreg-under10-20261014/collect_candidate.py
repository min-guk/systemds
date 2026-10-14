#!/usr/bin/env python3
"""Collect one candidate's timers, counters, movement trace, profile and process data."""
import argparse
import json
from pathlib import Path


def trace_values(lines, prefix):
    result = []
    for line in lines:
        if not line.startswith(prefix):
            continue
        values = {}
        for token in line.split()[1:]:
            if "=" not in token:
                continue
            key, value = token.split("=", 1)
            try:
                values[key] = int(value)
            except ValueError:
                values[key] = value
        result.append(values)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--result", required=True)
    ap.add_argument("--fed-log", required=True)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--hotspots", required=True)
    ap.add_argument("--proc-summary", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()
    result = json.loads(Path(args.result).read_text())
    case = result["cases"][0]
    planning = case["memoPlanning"][0]
    profile = json.loads(Path(args.profile).read_text())
    hotspots = json.loads(Path(args.hotspots).read_text())
    lines = Path(args.fed_log).read_text().splitlines()
    movement = trace_values(lines, "[MemoMovementReuse]")
    shape = trace_values(lines, "[MemoShapeReuse]")
    if len(movement) != 1:
        raise SystemExit(f"expected exactly one MemoMovementReuse trace, got {len(movement)}")
    if len(shape) != 1:
        raise SystemExit(f"expected exactly one MemoShapeReuse trace, got {len(shape)}")
    out = {
        "schema": "logreg-under10-candidate-summary-v1",
        "run": result["run"], "status": result["status"],
        "phaseSeconds": planning["phaseSeconds"], "planningSeconds": planning["totalSeconds"],
        "compilationSeconds": case["fedStatistics"]["compilationSeconds"],
        "executionSeconds": case["fedStatistics"]["executionSeconds"],
        "solution": {"status": planning["status"], "objective": planning["estimatedCost"],
                     "nodes": planning["nodes"], "nativeRules": planning["nativeRules"],
                     "supplies": planning["supplies"], "workers": planning["workers"],
                     "fedFingerprint": case["fedFingerprint"],
                     "maxAbsDifference": case["modelComparison"]["maxAbsDifference"]},
        "oracle": {"hits": planning.get("oracleHits"), "misses": planning.get("oracleMisses")},
        "search": case["memoSearch"][0], "construction": case["memoConstruction"],
        "movementReuse": movement[0], "shapeReuse": shape[0],
        "actionDiagnostics": case["actionDiagnostics"],
        "runtimeAudit": {"rows": case["auditRows"], "schemas": case["auditSchemas"],
                         "violations": case["runtimeAuditViolations"]},
        "jfr": {"artifact": case["jfrProfile"], "dataLossEvents": len(profile["dataLoss"]),
                "optimizerExecutionSamples": hotspots["optimizerExecutionSamples"],
                "optimizerAllocationWeightBytes": hotspots["optimizerAllocationWeightBytes"],
                "gc": profile["gc"], "exclusiveNearestMemoBuckets": hotspots["exclusiveNearestMemoBuckets"],
                "trackedInclusiveMethods": hotspots["trackedInclusiveMethods"]},
        "processMonitor": json.loads(Path(args.proc_summary).read_text()),
    }
    Path(args.output).write_text(json.dumps(out, indent=2) + "\n")
    print(json.dumps({"status": out["status"], "planningSeconds": out["planningSeconds"],
                      "phaseSeconds": out["phaseSeconds"], "oracle": out["oracle"],
                      "movementReuse": out["movementReuse"], "processMonitor": out["processMonitor"]}, indent=2))


if __name__ == "__main__":
    main()
