#!/usr/bin/env python3
"""Summarize paired cold-JVM timings and unchanged model outcomes; not a certificate auditor."""
import argparse
import json
from pathlib import Path
import re
from statistics import median
import subprocess
import sys


HERE = Path(__file__).resolve().parent


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=HERE / "evaluation")
    args = parser.parse_args()
    if (args.root / "evaluation").is_dir():
        args.root = args.root / "evaluation"
    subprocess.run([sys.executable, str(HERE / "validate_evaluation.py"),
                    "--root", str(args.root)], check=True)
    subprocess.run([sys.executable, str(HERE.parent / "memo-local-certificate-first-20261015" /
                                       "summarize_evaluation.py"), "--root", str(args.root)], check=True)
    data = json.loads((args.root / "report-data.json").read_text())
    rows = [row for row in data["rows"] if row["suite"] == "comparison"]
    indexed = {}
    for row in rows:
        key = (row["version"], row["run"].rsplit("-", 1)[1], row["workload"], row["algorithm"])
        require(key not in indexed, f"Duplicate comparison row: {key}")
        indexed[key] = row
    expected = {(version, f"{repeat:02d}", workload, algorithm)
                for version in ("before", "after") for repeat in range(1, 8)
                for workload in ("logreg", "glm", "gnmf")
                for algorithm in ("EXACT", "MEMO_GLOBAL", "MEMO_LOCAL")}
    require(set(indexed) == expected, "Comparison must contain all 126 unique paired cells")
    fields = ("optimizerSeconds", "compileSeconds", "containerMiB", "seedSeconds", "messageSeconds",
              "propagationSeconds", "firstUBSeconds", "certificateSeconds")
    pairs, groups = [], []
    for (version, repeat, workload, algorithm), after in sorted(indexed.items()):
        if version != "after":
            continue
        before = indexed["before", repeat, workload, algorithm]
        require(before["status"] == after["status"] == "passed", (repeat, workload, algorithm))
        require(isinstance(before["sourceSha256"], str) and
                re.fullmatch(r"[0-9a-f]{64}", before["sourceSha256"]) is not None and
                before["sourceSha256"] == after["sourceSha256"], (repeat, workload, algorithm))
        if algorithm != "EXACT":
            for key in ("result", "lowerBound", "upperBound", "route"):
                require(before[key] is not None and before[key] == after[key],
                        (repeat, workload, algorithm, key))
        if algorithm == "MEMO_LOCAL":
            for key in ("completedMessages", "messageCells", "nodes", "seedEvaluations",
                        "variables", "originalFactors"):
                require(key in before and key in after and before[key] is not None and
                        before[key] == after[key], (repeat, workload, key))
        pair = {"repeat": int(repeat), "workload": workload, "algorithm": algorithm}
        for field in fields:
            old, new = before.get(field), after.get(field)
            pair[field] = {"before": old, "after": new,
                           "speedup": old / new if old is not None and new else None}
        pairs.append(pair)
    for workload, algorithm in sorted({(p["workload"], p["algorithm"]) for p in pairs}):
        selected = [p for p in pairs if (p["workload"], p["algorithm"]) == (workload, algorithm)]
        group = {"workload": workload, "algorithm": algorithm, "pairs": len(selected)}
        for field in fields:
            before = [p[field]["before"] for p in selected if p[field]["before"] is not None]
            after = [p[field]["after"] for p in selected if p[field]["after"] is not None]
            ratios = [p[field]["speedup"] for p in selected if p[field]["speedup"] is not None]
            group[field] = {"beforeMedian": median(before), "afterMedian": median(after),
                            "ratioOfMedians": median(before) / median(after) if median(after) else None,
                            "pairedSpeedupMedian": median(ratios) if ratios else None,
                            "pairedSpeedupMin": min(ratios) if ratios else None,
                            "pairedSpeedupMax": max(ratios) if ratios else None} if before and after else None
        groups.append(group)
    output = {"note": "Positive speedup >1 means faster after. Same source, final bounds and selected Local "
                       "structural counters checked; "
                       "timings do not establish a certificate or statistical significance.",
              "pairs": pairs, "groups": groups}
    (args.root / "paired-efficiency.json").write_text(json.dumps(output, indent=2) + "\n")
    lines = ["| Workload | Algorithm | Pairs | Before median s | After median s | Speedup (medians) | Paired speedup median [min,max] |",
             "|---|---|---:|---:|---:|---:|---:|"]
    for group in groups:
        value = group["optimizerSeconds"]
        lines.append(f"| {group['workload']} | {group['algorithm']} | {group['pairs']} | "
                     f"{value['beforeMedian']:.4f} | {value['afterMedian']:.4f} | {value['ratioOfMedians']:.3f} | "
                     f"{value['pairedSpeedupMedian']:.3f} [{value['pairedSpeedupMin']:.3f},{value['pairedSpeedupMax']:.3f}] |")
    (args.root / "paired-efficiency.md").write_text("\n".join(lines) + "\n")
    print(f"paired rows={len(pairs)}; same-source/bounds/selected-structural-counter checks passed")


if __name__ == "__main__":
    main()
