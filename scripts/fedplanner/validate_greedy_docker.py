#!/usr/bin/env python3
"""One-shot protected FedFirst/AggLocal runtime + synthetic scaling validation.

Separate from the frozen P5 search-space harness. Uses a pinned local image, no
host Java, no network outside the container, and no retries or selected best run.
The one worker and coordinator share a Docker loopback LAN (not a multi-host benchmark).
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import tempfile


ALS_ROWS = 50
ALS_COLS = 20
ALS_RANK = 10
ALS_ABSOLUTE_TOLERANCE = 1e-8
ALS_RELATIVE_TOLERANCE = 1e-7


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_als_fixture(run):
    rows = []
    nnz = 0
    for row in range(ALS_ROWS):
        values = [0.0] * ALS_COLS
        # Preserve the fixture's sequential MatrixBlock.set semantics, including collisions.
        values[row % ALS_COLS] = 1.0
        values[(row * 7 + 3) % ALS_COLS] = 0.5
        values[(row * 13 + 5) % ALS_COLS] = 0.25
        rows.append(",".join(format(value, ".17g") for value in values))
        nnz += sum(value != 0 for value in values)
    contents = "\n".join(rows) + "\n"
    local_metadata = {"data_type": "matrix", "value_type": "double", "rows": ALS_ROWS,
        "cols": ALS_COLS, "nnz": nnz, "format": "csv", "header": False, "sep": ","}
    for prefix, privacy in (("cp", None), ("fed", "private-aggregate")):
        (run / f"{prefix}-X.csv").write_text(contents)
        metadata = dict(local_metadata)
        if privacy:
            metadata["privacy"] = privacy
        (run / f"{prefix}-X.csv.mtd").write_text(json.dumps(metadata) + "\n")
    call = ("[U,V]=als(X=X,rank=10,regType=\"L2\",reg=0.000001,maxi=2,"
        "check=FALSE,thr=0.0001,seed=1389632218,verbose=FALSE);\n")
    (run / "cp-als.dml").write_text(
        'X=read("/evidence/cp-X.csv");\n' + call + 'write(V,"/evidence/cp-V.csv",format="csv");\n')
    (run / "fedall-als.dml").write_text(
        'X=federated(addresses=list("localhost:13000//evidence/fed-X.csv"),'
        'ranges=list(list(0,0),list(50,20)));\n' + call
        + 'write(V,"/evidence/fedall-V.csv",format="csv");\n')
    for name, planner in (("cp", "NONE"), ("fedall", "COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS")):
        (run / f"{name}.xml").write_text(
            f"<root><sysds.native.blas>none</sysds.native.blas>"
            f"<sysds.local.spark>true</sysds.local.spark>"
            f"<sysds.federated.planner>{planner}</sysds.federated.planner>"
            f"<sysds.localtmpdir>/evidence/{name}-localtmp</sysds.localtmpdir>"
            f"<sysds.scratch>/evidence/{name}-scratch</sysds.scratch></root>\n")
    return nnz


def read_csv_matrix(path):
    if path.is_dir():
        parts = sorted(item for item in path.iterdir()
            if item.is_file() and not item.name.startswith(('.', '_')) and not item.name.endswith(".mtd"))
    else:
        parts = [path]
    rows = []
    for part in parts:
        for line in part.read_text().splitlines():
            if line.strip():
                rows.append([float(value) if value.strip() else 0.0 for value in line.split(",")])
    return rows, parts


def run_als_only(args, repo, jar, probe, image, sources):
    if args.baseline_root:
        raise SystemExit("--als-only does not accept --baseline-root")
    probe_source = repo / "src/test/java/org/apache/sysds/hops/fedplanner/placement/selector/PolicyGreedyDockerProbe.java"
    fixture_source = repo / "src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllAlsSingleWorkerRuntimeRecompileRedTest.java"
    builtin_sources = [repo / f"scripts/builtin/{name}" for name in ("als.dml", "alsCG.dml", "alsDS.dml")]
    if probe.stat().st_mtime_ns < probe_source.stat().st_mtime_ns:
        raise SystemExit("Probe class predates PolicyGreedyDockerProbe.java; rebuild test classes")
    if jar.stat().st_mtime_ns < max(path.stat().st_mtime_ns for path in builtin_sources):
        raise SystemExit("JAR predates an ALS builtin source; rebuild it")
    root = repo / "target/fedpolicy-greedy-docker"
    root.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="als-run-", dir=root))
    nnz = write_als_fixture(run)
    frozen_jar = run / "engine/systemds-3.4.0-SNAPSHOT.jar"
    frozen_probe = run / "probe/org/apache/sysds/hops/fedplanner/placement/selector/PolicyGreedyDockerProbe.class"
    frozen_jar.parent.mkdir()
    frozen_probe.parent.mkdir(parents=True)
    shutil.copyfile(jar, frozen_jar)
    shutil.copyfile(probe, frozen_probe)
    commands = ["set -euo pipefail", "cd /evidence",
        "export JDK_JAVA_OPTIONS='--add-modules=jdk.incubator.vector --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.ref=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED'",
        "CP='/probe:/engine/systemds-3.4.0-SNAPSHOT.jar:/deps/*'",
        "java -Xmx768m -cp \"$CP\" org.apache.sysds.api.DMLScript -w 13000 >worker.log 2>&1 &",
        "worker=$!", "trap 'kill \"$worker\" 2>/dev/null || true; wait \"$worker\" 2>/dev/null || true' EXIT",
        "python3 - <<'PY'\nimport socket,time\nfor i in range(120):\n try:\n  s=socket.create_connection(('localhost',13000),0.5);s.close();break\n except OSError: time.sleep(0.5)\nelse: raise SystemExit('worker did not start')\nPY",
        "timeout 600 java -Xms128m -Xmx1536m -Xss1m -cp \"$CP\" "
        "org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyDockerProbe "
        "als cp-als.dml cp.xml NONE cp-result.json >cp.log 2>&1",
        "timeout 600 java -Xms128m -Xmx1536m -Xss1m -cp \"$CP\" "
        "org.apache.sysds.hops.fedplanner.placement.selector.PolicyGreedyDockerProbe "
        "als fedall-als.dml fedall.xml COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS fedall-result.json >fedall.log 2>&1"]
    (run / "run.sh").write_text("\n".join(commands) + "\n")
    source_files = sources + builtin_sources + [Path(__file__).resolve(), probe_source, fixture_source]
    fixture_files = [run / name for name in ("cp-X.csv", "cp-X.csv.mtd", "fed-X.csv", "fed-X.csv.mtd",
        "cp-als.dml", "fedall-als.dml", "cp.xml", "fedall.xml", "run.sh")]
    manifest = {"image": image, "jarSha256": sha(frozen_jar), "probeSha256": sha(frozen_probe),
        "frozenRuntimeArtifacts": {"jar": str(frozen_jar.relative_to(run)),
            "probe": str(frozen_probe.relative_to(run))},
        "sourceSha256": {str(path.relative_to(repo)): sha(path) for path in source_files},
        "fixtureSha256": {path.name: sha(path) for path in fixture_files},
        "fixture": {"rows": ALS_ROWS, "cols": ALS_COLS, "nnz": nnz,
            "construction": "three sequential assignments per row, matching CampaignBG014FedAllAlsSingleWorkerRuntimeRecompileRedTest",
            "rank": ALS_RANK, "maxi": 2, "reg": 1e-6, "seed": 1389632218},
        "comparison": {"matrix": "full V", "rows": ALS_RANK, "cols": ALS_COLS,
            "absoluteTolerance": ALS_ABSOLUTE_TOLERANCE, "relativeTolerance": ALS_RELATIVE_TOLERANCE},
        "runs": [{"name": "cp", "planner": "NONE", "input": "local CSV"},
            {"name": "fedall", "planner": "COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS",
                "input": "single-worker PRIVATE_AGGREGATE federated CSV"}],
        "freshCoordinatorJvmPerRun": True, "attempts": 1, "cpus": 2, "memory": "4g",
        "network": "none (container loopback only)", "runtimeAudit": True}
    (run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    argv = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--cpus", "2",
        "--memory", "4g", "--user", f"{os.getuid()}:{os.getgid()}",
        "-v", f"{run / 'engine'}:/engine:ro", "-v", f"{repo / 'target/lib'}:/deps:ro",
        "-v", f"{run / 'probe'}:/probe:ro",
        "-v", f"{run}:/evidence:rw", "--entrypoint", "bash", image, "/evidence/run.sh"]
    (run / "command.json").write_text(json.dumps(argv, indent=2) + "\n")
    print(run, flush=True)
    with (run / "container.log").open("w") as log:
        result = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=1300)
    runtime_artifacts = {"jarSha256": sha(frozen_jar), "probeSha256": sha(frozen_probe)}
    runtime_artifacts_match = (runtime_artifacts["jarSha256"] == manifest["jarSha256"]
        and runtime_artifacts["probeSha256"] == manifest["probeSha256"])
    receipt = {"status": "failed", "containerExitCode": result.returncode,
        "manifestSha256": sha(run / "manifest.json"), "commandSha256": sha(run / "command.json"),
        "runtimeArtifactSha256": runtime_artifacts, "frozenRuntimeArtifactsMatch": runtime_artifacts_match}
    try:
        cp_result = json.loads((run / "cp-result.json").read_text())
        fed_result = json.loads((run / "fedall-result.json").read_text())
        cp_values, cp_parts = read_csv_matrix(run / "cp-V.csv")
        fed_values, fed_parts = read_csv_matrix(run / "fedall-V.csv")
        shape_ok = (len(cp_values) == ALS_RANK and len(fed_values) == ALS_RANK
            and all(len(row) == ALS_COLS for row in cp_values + fed_values))
        finite = shape_ok and all(math.isfinite(value)
            for row in cp_values + fed_values for value in row)
        differences = [abs(left - right) for cp_row, fed_row in zip(cp_values, fed_values)
            for left, right in zip(cp_row, fed_row)] if shape_ok else []
        within_tolerance = shape_ok and all(abs(left - right) <= ALS_ABSOLUTE_TOLERANCE
            + ALS_RELATIVE_TOLERANCE * max(abs(left), abs(right))
            for cp_row, fed_row in zip(cp_values, fed_values) for left, right in zip(cp_row, fed_row))
        cp_fed = cp_result.get("federatedHeavyHitters", {})
        fed_compute = fed_result.get("federatedComputeHeavyHitters", {})
        passed = (result.returncode == 0 and runtime_artifacts_match and cp_result.get("status") == "passed"
            and fed_result.get("status") == "passed" and cp_result.get("runtimeFallbackCount") == 0
            and cp_result.get("runtimeRepairCount") == 0 and fed_result.get("runtimeFallbackCount") == 0
            and fed_result.get("runtimeRepairCount") == 0 and not cp_fed and bool(fed_compute)
            and finite and within_tolerance)
        receipt.update({"status": "passed" if passed else "failed", "cp": cp_result, "fedall": fed_result,
            "matrixComparison": {"shape": [ALS_RANK, ALS_COLS], "shapeValid": shape_ok,
                "allFinite": finite, "entriesCompared": len(differences),
                "absoluteTolerance": ALS_ABSOLUTE_TOLERANCE, "relativeTolerance": ALS_RELATIVE_TOLERANCE,
                "maxAbsoluteDifference": max(differences) if differences else None,
                "withinTolerance": within_tolerance,
                "cpOutputSha256": {part.name: sha(part) for part in cp_parts},
                "fedallOutputSha256": {part.name: sha(part) for part in fed_parts}}})
    except (FileNotFoundError, ValueError, json.JSONDecodeError) as failure:
        receipt["evidenceError"] = str(failure)
    receipt["logSha256"] = {name: sha(run / name) for name in ("container.log", "worker.log", "cp.log", "fedall.log")
        if (run / name).is_file()}
    (run / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"status": receipt["status"], "artifact": str(run),
        "matrixComparison": receipt.get("matrixComparison")}, indent=2))
    return 0 if receipt["status"] == "passed" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Existing local Java 17/Python 3 image; never pulled")
    parser.add_argument("--baseline-root", type=Path, help="Isolated HEAD source build for paired comparison (one warmup + five measured fresh JVMs)")
    parser.add_argument("--als-only", action="store_true", help="Run the single-worker ALS CP/FedAll correctness comparison only")
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
    if args.als_only:
        return run_als_only(args, repo, jar, probe, image, sources)
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
