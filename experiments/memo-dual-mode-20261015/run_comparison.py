#!/usr/bin/env python3
"""Reproduce seven randomized cold-process Docker comparisons without overwriting evidence."""
import argparse
import json
from pathlib import Path
import random
import subprocess

REPO = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage", type=Path, default=Path("/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z"))
    parser.add_argument("--repetitions", type=int, default=7)
    args = parser.parse_args()
    if args.repetitions < 1:
        raise ValueError("repetitions must be positive")
    args.output.mkdir(parents=True, exist_ok=False)
    rng = random.Random(20261015)
    receipts = []
    for repeat in range(args.repetitions):
        algorithms = ["EXACT", "MEMO_GLOBAL", "MEMO_LOCAL"]
        if repeat == 0:
            algorithms += ["TREE_DP_GLOBAL", "TREE_DP_LOCAL"]
        rng.shuffle(algorithms)
        workloads = ["logreg", "glm", "gnmf"]
        rng.shuffle(workloads)
        output = args.output / f"repeat-{repeat + 1:02}"
        command = [str(REPO / "scripts/fedplanner/run_LAN_docker.sh"), "--memo-tree-dp-benchmark",
            "--engine", str(args.engine.resolve()), "--sealed-stage", str(args.stage.resolve()),
            "--output-root", str(output.resolve()), "--workers", "3", "--search-nodes", "2000",
            "--workloads", ",".join(workloads), "--algorithms", ",".join(algorithms)]
        if repeat == 0:
            command += ["--runtime-smoke", "--synthetic-repetitions", "7"]
        else:
            command += ["--skip-synthetic"]
        with (args.output / f"repeat-{repeat + 1:02}.log").open("w") as log:
            result = subprocess.run(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        summary = output / "summary.json"
        if not summary.is_file():
            raise RuntimeError(f"runner did not produce evidence: {output}")
        record = {"repeat": repeat + 1, "argv": command, "returncode": result.returncode,
            "summary": str(summary.resolve())}
        receipts.append(record)
        (args.output / "repetitions.json").write_text(json.dumps({"randomSeed": 20261015,
            "timing": "independent cold JVM/process per algorithm", "runs": receipts}, indent=2) + "\n")
        print(f"repeat {repeat + 1}/{args.repetitions}: returncode={result.returncode}; {summary}", flush=True)

if __name__ == "__main__":
    main()
