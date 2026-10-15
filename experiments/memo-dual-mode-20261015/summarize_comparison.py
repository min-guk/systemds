#!/usr/bin/env python3
"""Summarize independent compile runs; failed runs remain visible."""
import argparse
from collections import defaultdict
from decimal import Decimal, localcontext
import json
from pathlib import Path
import statistics

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    grouped = defaultdict(list)
    summaries = sorted(args.root.glob("repeat-*/summary.json"))
    for path in summaries:
        data = json.loads(path.read_text())
        for row in data["compileRows"]:
            certificates = row.get("dualCertificates", []) or row.get("treeCertificates", [])
            record = {"repeat": path.parent.name, "status": row["status"],
                "failure": row.get("failureCategory"), "wallSeconds": row["wallSeconds"],
                "fullCompileSeconds": row["compile"].get("fullCompileSeconds"),
                "containerPeakBytes": row.get("peakContainerMemoryUsageBytes"),
                "sourceSha256": row["sourceSha256"],
                "costs": [p["cost"] for p in row["memoPlans"]],
                "certificates": [{k:c.get(k) for k in ("status","certified","lowerBound","upperBound","relativeGap","statistics")}
                    for c in certificates]}
            if row["status"] == "passed":
                record["optimizationSeconds"] = sum(p["phaseNanos"]["optimization"] for p in row["memoPlans"]) / 1e9
            grouped[(row["workload"], row["algorithm"])].append(record)
    rows = []
    for (workload, algorithm), records in sorted(grouped.items()):
        times = [r["optimizationSeconds"] for r in records if "optimizationSeconds" in r]
        row = {"workload": workload, "algorithm": algorithm, "attempts": len(records),
            "passed": len(times), "failed": len(records)-len(times), "runs": records}
        if times:
            row.update(medianOptimizationSeconds=statistics.median(times),
                minOptimizationSeconds=min(times), maxOptimizationSeconds=max(times),
                medianFullCompileSeconds=statistics.median(r["fullCompileSeconds"] for r in records if "optimizationSeconds" in r),
                medianWallSeconds=statistics.median(r["wallSeconds"] for r in records if "optimizationSeconds" in r))
        peaks = [r["containerPeakBytes"] for r in records if r["containerPeakBytes"]]
        if peaks: row["maxContainerPeakBytes"] = max(peaks)
        rows.append(row)
    baselines = {r["workload"]:r for r in rows if r["algorithm"] == "EXACT"}
    for row in rows:
        exact = baselines.get(row["workload"])
        if exact and "medianOptimizationSeconds" in row:
            row["optimizationMedianRatioToExact"] = row["medianOptimizationSeconds"]/exact["medianOptimizationSeconds"]
    # Independently recheck every published interval against its own exact cold-run group.
    bound_points = 0
    for path in summaries:
        data = json.loads(path.read_text())
        exact = {r["workload"]:r for r in data["compileRows"] if r["algorithm"] == "EXACT"}
        for row in data["compileRows"]:
            baseline = exact[row["workload"]]
            assert row["sourceSha256"] == baseline["sourceSha256"]
            certificates = row.get("dualCertificates", []) or row.get("treeCertificates", [])
            with localcontext() as ctx:
                ctx.prec = 300
                for index, c in enumerate(certificates):
                    optimum = Decimal(baseline["memoPlans"][index]["cost"])
                    previous_lb = previous_ub = None
                    for point in c.get("progression", []):
                        point_lb = None if point.get("lowerBound") in (None,"null") else Decimal(point["lowerBound"])
                        point_ub = None if point.get("upperBound") in (None,"null") else Decimal(point["upperBound"])
                        if point_lb is not None:
                            assert point_lb <= optimum
                            if previous_lb is not None: assert point_lb >= previous_lb
                            previous_lb = point_lb
                        if point_ub is not None:
                            assert optimum <= point_ub
                            if previous_ub is not None: assert point_ub <= previous_ub
                            previous_ub = point_ub
                        bound_points += 1
                    lb = None if c.get("lowerBound") in (None,"null") else Decimal(c["lowerBound"])
                    ub = None if c.get("upperBound") in (None,"null") else Decimal(c["upperBound"])
                    if lb is not None: assert lb <= optimum
                    if ub is not None: assert optimum <= ub
                    if c["certified"]:
                        assert lb is not None and ub is not None
                        if c["status"] == "OPTIMAL": assert lb == ub == optimum
                        else: assert lb > 0 and ub <= Decimal("1.05") * lb
    result = {"schema":"memo-dual-comparison-summary/v1", "repetitions":len(summaries),
        "timing":"independent cold JVM/process; randomized order seed20261015", "rows":rows,
        "publishedBoundPointsChecked":bound_points,
        "allRecordedBoundsContainSameModelExactOptimum":True}
    output = args.root / "aggregate.json"
    output.write_text(json.dumps(result, indent=2) + "\n")
    for r in rows:
        print(r["workload"],r["algorithm"],f'{r["passed"]}/{r["attempts"]}',
            r.get("medianOptimizationSeconds"),r.get("optimizationMedianRatioToExact"))
    print(output)

if __name__ == "__main__":
    main()
