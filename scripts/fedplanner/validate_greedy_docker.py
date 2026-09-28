#!/usr/bin/env python3
"""One-shot protected FedFirst/AggLocal runtime + synthetic scaling validation.

Separate from the frozen P5 search-space harness. Uses a pinned local image, no
host Java, no network outside the container, and no retries or selected best run.
The one worker and coordinator share a Docker loopback LAN (not a multi-host benchmark).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Existing local Java 17/Python 3 image; never pulled")
    parser.add_argument("--baseline-root", type=Path, help="Isolated HEAD source build for paired comparison (one warmup + five measured fresh JVMs)")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
    probe = repo / "target/test-classes/org/apache/sysds/hops/fedplanner/placement/selector/PolicyGreedyDockerProbe.class"
    if not jar.is_file() or not probe.is_file():
        raise SystemExit("Build package and test classes first")
    sources = sorted(p for p in (repo / "src/main").rglob("*") if p.is_file()) + [repo / "pom.xml"]
    if jar.stat().st_mtime_ns < max(p.stat().st_mtime_ns for p in sources):
        raise SystemExit("JAR predates production source; rebuild it")
    image = subprocess.check_output(["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    root = repo / "target/fedpolicy-greedy-docker"
    root.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="run-", dir=root))
    repeats = 6 if args.baseline_root else 1
    engines = {"current": repo}
    if args.baseline_root:
        engines = {"baseline": args.baseline_root.resolve(), **engines}
    for engine, source_root in engines.items():
        if not (source_root / "target/systemds-3.4.0-SNAPSHOT.jar").is_file():
            raise SystemExit(f"Missing {engine} JAR")
    order = [(repeat, list(engines) if repeat % 2 == 0 else list(reversed(engines))) for repeat in range(repeats)]
    manifest = {"image": image, "engines": {name: {"root": str(path), "jarSha256": sha(path / "target/systemds-3.4.0-SNAPSHOT.jar")} for name,path in engines.items()},
                "repeats": repeats, "warmupsExcluded": 1 if repeats > 1 else 0, "order": order, "jarSha256": sha(jar), "probeSha256": sha(probe),
                "sourceSha256": {str(p.relative_to(repo)): sha(p) for p in sources},
                "cpus": 2, "memory": "4g", "network": "none (container loopback only)",
                "sourcePrivacy": "private-aggregate", "attempts": 1, "runtimeAudit": True,
                "purpose": "correctness smoke and synthetic selector scaling, not full workload speedup"}
    (run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (run / "X.csv").write_text("1,2,3\n4,5,6\n7,8,9\n10,11,12\n")
    (run / "X.csv.mtd").write_text(json.dumps({"data_type": "matrix", "value_type": "double",
        "rows": 4, "cols": 3, "nnz": 12, "format": "csv", "header": False, "sep": ",",
        "privacy": "private-aggregate"}))
    source = 'X=federated(addresses=list("localhost:13000//evidence/X.csv"),ranges=list(list(0,0),list(4,3)));\n'
    workloads = {
        "nested": ('v=matrix(1,rows=3,cols=1);z=X%*%v;q=t(X)%*%z;print("FEDPOLICY_NUMERIC="+sum(q));', 1926),
        "elementwise": ('Y=X+1;print("FEDPOLICY_NUMERIC="+sum(Y));', 90),
        "loop": ('B=X;i=1;while(i<=2){B=cbind(B,X);i=i+1;}print("FEDPOLICY_NUMERIC="+sum(B));', 234),
    }
    planners = {"FedFirst": "COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS", "AggLocal": "COMPILE_FED_HEURISTIC_SINGLE_PASS"}
    for name, (body, _) in workloads.items():
        (run / f"{name}.dml").write_text(source + body + "\n")
    for name, planner in planners.items():
        (run / f"{name}.xml").write_text(f"<root><sysds.federated.planner>{planner}</sysds.federated.planner>"
            "<sysds.localtmpdir>/tmp/systemds</sysds.localtmpdir><sysds.scratch>/tmp/scratch</sysds.scratch></root>\n")
    commands = ["set -euo pipefail", "cd /evidence", "export HOME=/tmp",
        "export JDK_JAVA_OPTIONS='--add-modules=jdk.incubator.vector --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.ref=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED'",
        "CP='/probe:/engine/systemds-3.4.0-SNAPSHOT.jar:/engine/lib/*'",
        "java -Xmx768m -cp \"$CP\" org.apache.sysds.api.DMLScript -w 13000 >worker-${REPEAT}.log 2>&1 &",
        "worker=$!", "trap 'kill \"$worker\" 2>/dev/null || true; wait \"$worker\" 2>/dev/null || true' EXIT",
        "python3 - <<'PY'\nimport socket,time\nfor i in range(120):\n try:\n  s=socket.create_connection(('localhost',13000),0.5);s.close();break\n except OSError: time.sleep(0.5)\nelse: raise SystemExit('worker did not start')\nPY"]
    for name, planner in planners.items():
        for workload in workloads:
            commands.append(f"timeout 120 java -Xms128m -Xmx1536m -Xss1m -Dsysds.fedplanner.trace=true "
                f"-Dsysds.fedplanner.trace.details=false -Xlog:gc:file={name}-{workload}-${{REPEAT}}.gc.log -cp \"$CP\" "
                f"org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyDockerProbe "
                f"{workload}.dml {name}.xml {planner} >{name}-{workload}-${{REPEAT}}.log 2>&1")
    commands.append("if [[ ${RUN_SCALING:-0} == 1 ]]; then timeout 240 java -Xms128m -Xmx1536m -Xss1m -cp \"$CP\" "
        "org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyDockerProbe scaling >scaling.jsonl 2>scaling.stderr; fi")
    (run / "run.sh").write_text("\n".join(commands) + "\n")
    print(run, flush=True)
    receipt = {"status": "failed", "checks": [], "engines": {}}
    for engine, source_root in engines.items():
        output = run / engine
        output.mkdir()
        for path in run.iterdir():
            if path.is_file():
                shutil.copyfile(path, output / path.name)
        receipt["engines"][engine] = {"exitCode": 0, "runs": []}
    # Alternate engine order at the fixed repeat boundary; never select good samples.
    # A fresh worker per repeat avoids retaining one engine's worker/JIT state longer.
    for repeat, engine_order in order:
        for engine in engine_order:
            source_root, output = engines[engine], run / engine
            argv = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--cpus", "2", "--memory", "4g",
                "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{source_root / 'target'}:/engine:ro",
                "-v", f"{repo / 'target/test-classes'}:/probe:ro", "-v", f"{output}:/evidence:rw",
                "-e", f"REPEAT={repeat}", "-e", f"RUN_SCALING={int(engine == 'current' and repeat == repeats - 1)}",
                "--entrypoint", "bash", image, "/evidence/run.sh"]
            (output / f"command-{repeat}.json").write_text(json.dumps(argv, indent=2) + "\n")
            with (output / f"container-{repeat}.log").open("w") as log:
                result = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=1000)
            receipt["engines"][engine]["runs"].append({"repeat": repeat, "exitCode": result.returncode})
            if result.returncode:
                receipt["engines"][engine]["exitCode"] = result.returncode
    for engine in engines:
        output = run / engine
        for repeat in range(repeats):
            for name, planner in planners.items():
                for workload, (_, expected) in workloads.items():
                    path = output / f"{name}-{workload}-{repeat}.log"
                    text = path.read_text() if path.exists() else ""
                    values = re.findall(r"FEDPOLICY_NUMERIC=([0-9.eE+-]+)", text)
                    ok = len(values) == 1 and abs(float(values[0]) - expected) < 1e-8 and f"FEDPOLICY_PROBE_SUCCESS={planner};runtimeAudit=true" in text
                    timing = re.search(r"CandidateE2EReceipt (.+)", text)
                    phases = {k: int(v) for k,v in re.findall(r"(\w+Nanos)=(\d+)", timing.group(1))} if timing else {}
                    compilation = re.findall(r"Total compilation time:\s*([0-9.]+) sec", text)
                    heap = re.findall(r"FEDPOLICY_HEAP_POOL_PEAK_BYTES=(\d+)", text)
                    receipt["checks"].append({"engine": engine, "repeat": repeat, "planner": name, "workload": workload,
                        "expected": expected, "actual": values, "passed": ok, "phases": phases,
                        "heapPoolPeakBytesUpperBound": int(heap[0]) if heap else None,
                        "compilationSeconds": float(compilation[0]) if compilation else None})
    scaling = run / "current/scaling.jsonl"
    rows = [json.loads(line) for line in scaling.read_text().splitlines()] if scaling.exists() else []
    receipt["scalingRuns"] = len(rows)
    if len(rows) == 12 and all(e["exitCode"] == 0 for e in receipt["engines"].values()) and all(c["passed"] for c in receipt["checks"]):
        receipt["status"] = "passed"
    if repeats > 1 and receipt["status"] == "passed":
        comparisons = []
        for planner in planners:
            for workload in workloads:
                group = [c for c in receipt["checks"] if c["repeat"] > 0 and c["planner"] == planner and c["workload"] == workload]
                medians = {}
                for engine in engines:
                    samples = [c for c in group if c["engine"] == engine]
                    medians[engine] = {"compilationSeconds": statistics.median(c["compilationSeconds"] for c in samples),
                        **{phase: statistics.median(c["phases"][phase] for c in samples) for phase in
                            ("analysisNanos", "otherPlanningNanos", "conversionNanos", "applicationNanos", "totalNanos")}}
                comparisons.append({"planner": planner, "workload": workload, "median": medians,
                    "compilationRatio": medians["current"]["compilationSeconds"] / medians["baseline"]["compilationSeconds"],
                    "totalPlanningRatio": medians["current"]["totalNanos"] / medians["baseline"]["totalNanos"],
                    "selectionAdapterRatio": medians["current"]["otherPlanningNanos"] / medians["baseline"]["otherPlanningNanos"]})
        receipt["comparisons"] = comparisons
        receipt["timingInvestigationRequired"] = any(c["totalPlanningRatio"] > 1.05 for c in comparisons)
    (run / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"status": receipt["status"], "checks": len(receipt["checks"]), "scalingRuns": len(rows),
                      "comparisons": receipt.get("comparisons", [])}, indent=2))
    return 0 if receipt["status"] == "passed" else 1



if __name__ == "__main__":
    raise SystemExit(main())
