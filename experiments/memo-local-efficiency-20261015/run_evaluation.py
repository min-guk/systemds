#!/usr/bin/env python3
"""Run sequential cold-JVM Docker comparisons for incremental-work reuse in Memo Local."""

import argparse
import json
from pathlib import Path
import random
import subprocess


REPO = Path(__file__).resolve().parents[2]
ROOT = Path(__file__).resolve().parent
SEALED_STAGE = Path("/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=("pilot", "comparison", "ablation", "runtime", "memory"),
                        required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--engine", type=Path, default=ROOT / "engine-v1")
    parser.add_argument("--baseline-engine", type=Path,
                        default=REPO / "experiments/memo-local-certificate-first-20261015/engine-v8")
    parser.add_argument("--repetitions", type=int, default=7)
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.suite in ("pilot", "runtime", "memory"):
        args.repetitions = 1  # Fixed one job per configured cell in these suites.
    args.output.mkdir(parents=True, exist_ok=False)

    rng = random.Random(20261015)
    jobs = make_jobs(args, rng)
    receipts = []
    for name, engine, extra in jobs:
        if "--local-improve-seed" not in extra:
            extra = extra + ["--local-improve-seed", "false"]
        command = [str(REPO / "scripts/fedplanner/run_LAN_docker.sh"),
                   "--memo-tree-dp-benchmark", "--engine", str(engine.resolve()),
                   "--sealed-stage", str(SEALED_STAGE),
                   "--output-root", str((args.output / name).resolve()),
                   "--workers", "3", "--search-nodes", "2000"] + extra
        if args.suite != "runtime":
            command.append("--skip-synthetic")
        with (args.output / f"{name}.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=REPO, stdout=log,
                                       stderr=subprocess.STDOUT, check=False)
        summary = args.output / name / "summary.json"
        if not summary.is_file():
            raise RuntimeError(f"missing evidence: {summary}")
        receipts.append({"name": name, "engine": str(engine.resolve()), "argv": command,
                         "returncode": completed.returncode,
                         "summary": str(summary.resolve())})
        atomic_json(args.output / "receipts.json", {
            "schema": "memo-local-certificate-first-receipts/v1",
            "seed": 20261015, "suite": args.suite,
            "repetitions": args.repetitions, "jobs": receipts,
        })
        print(name, completed.returncode, flush=True)
    return int(any(job["returncode"] != 0 for job in receipts))


def make_jobs(args, rng):
    if args.suite == "pilot":
        return [("new-pilot", args.engine,
                 ["--workloads", "logreg,glm,gnmf",
                  "--algorithms", "EXACT,MEMO_GLOBAL,MEMO_LOCAL",
                  "--dual-diagnostics", "true"])]
    if args.suite == "comparison":
        jobs = []
        for repeat in range(1, args.repetitions + 1):
            engines = [("before", args.baseline_engine), ("after", args.engine)]
            rng.shuffle(engines)
            for version, engine in engines:
                algorithms = ["EXACT", "MEMO_GLOBAL", "MEMO_LOCAL"]
                workloads = ["logreg", "glm", "gnmf"]
                rng.shuffle(algorithms)
                rng.shuffle(workloads)
                jobs.append((f"{version}-{repeat:02d}", engine,
                             ["--workloads", ",".join(workloads),
                              "--algorithms", ",".join(algorithms)]))
        return jobs
    if args.suite == "ablation":
        jobs = []
        variants = [
            ("messages-off-seed-off", ["--local-messages", "false",
                                        "--local-improve-seed", "false"]),
            ("messages-off-seed-on", ["--local-messages", "false",
                                       "--local-improve-seed", "true"]),
            ("messages-on-seed-off", ["--local-messages", "true",
                                       "--local-improve-seed", "false"]),
            ("messages-on-seed-on", ["--local-messages", "true",
                                      "--local-improve-seed", "true"]),
        ]
        for repeat in range(1, args.repetitions + 1):
            shuffled = list(variants)
            rng.shuffle(shuffled)
            for name, extra in shuffled:
                jobs.append((f"{name}-{repeat:02d}", args.engine,
                             ["--workloads", "gnmf", "--algorithms", "EXACT,MEMO_LOCAL",
                              "--dual-diagnostics", "true"] + extra))
        return jobs
    if args.suite == "memory":
        return [(f"heap-{heap}", args.engine,
                 ["--workloads", "logreg,glm,gnmf", "--algorithms", "EXACT,MEMO_LOCAL",
                  "--heap-mib", str(heap)]) for heap in (1024, 1536)]
    return [("runtime-and-regional", args.engine,
             ["--workloads", "gnmf", "--algorithms", "EXACT,MEMO_GLOBAL,MEMO_LOCAL",
              "--runtime-smoke", "--synthetic-repetitions", "7"])]


def atomic_json(path: Path, value) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


if __name__ == "__main__":
    raise SystemExit(main())
