#!/usr/bin/env python3
"""Compare the two authorized LogReg JFR runs without normalizing semantic evidence."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    data = Path(path).read_bytes()
    return {"path": str(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def delta(before, after):
    return {
        "before": before,
        "after": after,
        "delta": after - before,
        "percent": (after / before - 1.0) * 100.0 if before else None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("before_summary")
    ap.add_argument("after_summary")
    ap.add_argument("before_result")
    ap.add_argument("after_result")
    ap.add_argument("before_csv")
    ap.add_argument("after_csv")
    ap.add_argument("output")
    args = ap.parse_args()

    before = json.loads(Path(args.before_summary).read_text())
    after = json.loads(Path(args.after_summary).read_text())
    br = json.loads(Path(args.before_result).read_text())
    ar = json.loads(Path(args.after_result).read_text())
    bc, ac = br["cases"][0], ar["cases"][0]
    bm, am = bc["memoPlanning"][0], ac["memoPlanning"][0]
    before_support_misses = before["search"]["supportQueries"]
    after_support_misses = after["search"]["supportQueries"]
    after_support_hits = after["search"]["supportCacheHits"]
    after_support_total = after_support_misses + after_support_hits

    tracked = sorted(set(before["jfr"]["trackedInclusiveMethods"]) |
                     set(after["jfr"]["trackedInclusiveMethods"]))
    out = {
        "schema": "logreg-bottleneck-before-after-v1",
        "singleRunPerCondition": True,
        "statusEqual": br["status"] == ar["status"] == "PASSED",
        "semanticParity": {
            "objectiveEqual": bm["estimatedCost"] == am["estimatedCost"],
            "nodesEqual": bm["nodes"] == am["nodes"],
            "nativeRulesEqual": bm["nativeRules"] == am["nativeRules"],
            "suppliesEqual": bm["supplies"] == am["supplies"],
            "fedFingerprintEqual": bc["fedFingerprint"] == ac["fedFingerprint"],
            "runtimeAuditViolationsEqual": bc["runtimeAuditViolations"] == ac["runtimeAuditViolations"],
            "actionDiagnosticsEqual": bc["actionDiagnostics"] == ac["actionDiagnostics"],
            "constructionCountersEqual": before["construction"] == after["construction"],
            "shapeReuseCountersEqual": before["shapeReuse"] == after["shapeReuse"],
            "oraclePlannerTraceEqual": (
                bm.get("oracleHits") == am.get("oracleHits") and
                bm.get("oracleMisses") == am.get("oracleMisses")
            ),
            "fedModelCsv": {"before": digest(args.before_csv), "after": digest(args.after_csv)},
        },
        "timing": {
            key: delta(before["timingSeconds"][key], after["timingSeconds"][key])
            for key in ("planning", "optimization", "compilation", "execution")
        },
        "searchCounters": {
            key: delta(before["search"].get(key, 0), after["search"].get(key, 0))
            for key in sorted(set(before["search"]) | set(after["search"]))
            if isinstance(before["search"].get(key), (int, float)) and isinstance(after["search"].get(key), (int, float))
        },
        "supportCache": {
            "counterDefinition": "supportQueries counts cache misses that execute support DFS in both runs",
            "before": {"misses": before_support_misses, "hits": None, "totalRequests": None,
                       "note": "supportCacheHits was not instrumented in BEFORE"},
            "after": {"misses": after_support_misses, "hits": after_support_hits,
                      "totalRequests": after_support_total,
                      "hitRate": after_support_hits / after_support_total},
            "missDelta": delta(before_support_misses, after_support_misses),
        },
        "constructionCounters": {"before": before["construction"], "after": after["construction"]},
        "shapeReuseCounters": {"before": before["shapeReuse"], "after": after["shapeReuse"]},
        "oraclePlannerTrace": {
            "before": {"hits": bm.get("oracleHits"), "misses": bm.get("oracleMisses")},
            "after": {"hits": am.get("oracleHits"), "misses": am.get("oracleMisses")},
        },
        "jfr": {
            "executionSamples": delta(before["jfr"]["optimizerExecutionSamples"], after["jfr"]["optimizerExecutionSamples"]),
            "allocationWeightBytes": delta(before["jfr"]["optimizerAllocationWeightBytes"], after["jfr"]["optimizerAllocationWeightBytes"]),
            "gcPauseSeconds": delta(before["jfr"]["gc"]["stopTheWorldPauseSeconds"], after["jfr"]["gc"]["stopTheWorldPauseSeconds"]),
            "trackedInclusiveSamples": {
                method: delta(before["jfr"]["trackedInclusiveMethods"].get(method, {}).get("inclusiveSamples", 0),
                              after["jfr"]["trackedInclusiveMethods"].get(method, {}).get("inclusiveSamples", 0))
                for method in tracked
            },
        },
        "processMonitor": {
            key: delta(before["processMonitor"][key], after["processMonitor"][key])
            for key in ("observedSeconds", "cpuSeconds", "maxVmRssKiB", "maxVmHwmKiB")
        },
        "limitations": [
            "one profiled run per condition; wall-clock deltas are observations, not distribution estimates",
            "JFR sample and allocation-weight deltas are statistical",
            "conditional-bound allocation bucket includes minimumTerm and nested cost work, not only cache-key allocation",
            "process CPU covers the monitored coordinator interval, not only Memo optimization",
        ],
    }
    csv_pair = out["semanticParity"]["fedModelCsv"]
    out["semanticParity"]["fedModelCsvByteEqual"] = (
        csv_pair["before"]["size"] == csv_pair["after"]["size"] and
        csv_pair["before"]["sha256"] == csv_pair["after"]["sha256"]
    )
    Path(args.output).write_text(json.dumps(out, indent=2) + "\n")


if __name__ == "__main__":
    main()
