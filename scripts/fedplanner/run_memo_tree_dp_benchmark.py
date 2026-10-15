#!/usr/bin/env python3
"""Run certified Memo Tree-DP comparisons in reproducible Docker cells.

The source-fullcompile phase runs without networking and compares independently
selected Memo optimizers on identical rendered training programs. An optional
runtime phase is deliberately separate: it runs the existing Memo federated-worker
integration suite in a container and therefore measures execution correctness
rather than full-compile planning performance.
"""
from __future__ import annotations

import argparse
import csv
from decimal import Decimal, InvalidOperation
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
from typing import Any
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = REPO / "experiments" / "memo-tree-dp-20261014" / "results"
PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
DEFAULT_ALGORITHMS = ("EXACT", "TREE_DP_GLOBAL", "TREE_DP_LOCAL")
ALGORITHMS = DEFAULT_ALGORITHMS + ("MEMO_GLOBAL", "MEMO_LOCAL")
WORKLOADS = ("logreg", "glm", "gnmf")
SYNTHETIC_CASES = ("adversarial-shared-wide", "tree-32", "cycle-16", "diamond-8", "shared-wide-8",
                   "epsilon-boundary-3")
FIELD = re.compile(r"(\w+)=([^\s]+)")
MEMORY = re.compile(r"^([0-9.]+)([KMGTP]?i?B)$")
OUT_OF_MEMORY = re.compile(r"OutOfMemoryError|Java heap space|GC overhead limit exceeded|"
                           r"Could not reserve enough space",re.IGNORECASE)


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_matrix_module():
    import importlib.util
    path = REPO / "scripts" / "fedplanner" / "run_memo_planning_matrix.py"
    spec = importlib.util.spec_from_file_location("memo_tree_dp_matrix", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load renderer adapter: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def configuration(planner: str, algorithm: str | None,
                  dual: dict[str, Any] | None = None) -> str:
    dual = dual or {}
    values = {
        "sysds.native.blas": "none",
        "sysds.local.spark": "true",
        "sysds.federated.planner": planner,
        "sysds.federated.timeout": "-1",
        "sysds.benchmark.compile_only": "true",
        "sysds.codegen.enabled": "false",
        "sysds.localtmpdir": "/evidence/tmp/local",
        "sysds.scratch": "/evidence/tmp/scratch",
        "sysds.fedplanner.memo.local.messages":
            str(dual.get("localMessages", True)).lower(),
        "sysds.fedplanner.memo.local.improveSeed":
            str(dual.get("localImproveSeed", True)).lower(),
    }
    if algorithm is not None:
        values["sysds.fedplanner.memo.algorithm"] = algorithm
    root = ET.Element("root")
    for name, value in values.items():
        ET.SubElement(root, name).text = value
    return ET.tostring(root, encoding="unicode") + "\n"


def parse_decimal(value: str | None) -> Decimal | None:
    try:
        parsed = Decimal(value) if value is not None else None
    except InvalidOperation:
        return None
    return parsed if parsed is not None and parsed.is_finite() else None


def within_epsilon(lower: Decimal, upper: Decimal) -> bool:
    """Check (UB-LB)/LB <= 1/20 without Decimal context rounding."""
    low, high = Fraction(lower), Fraction(upper)
    return 20 * (high - low) <= low


def parse_tree_trace(text: str) -> list[dict[str, Any]]:
    records = []
    current = None
    for marker, line in re.findall(r"(?m)^\[(MemoTreeDP|MemoTreeDPBound)\] (.*)$", text):
        fields = dict(FIELD.findall(line))
        if marker == "MemoTreeDP":
            lower, upper = parse_decimal(fields.get("lowerBound")), parse_decimal(fields.get("upperBound"))
            gap = float(Fraction(upper - lower) / Fraction(lower)) \
                if lower is not None and upper is not None and lower > 0 else None
            statistics_match = re.search(r"\bstatistics=(.*)$", line)
            statistics = statistics_match.group(1) if statistics_match else ""
            counters = {name: int(value) for name, value in
                        re.findall(r"(\w+)=(-?\d+)", statistics)}
            current = {"status": fields.get("status"),
                "certified": fields.get("certified", "false").lower() == "true",
                "lowerBound": fields.get("lowerBound"), "upperBound": fields.get("upperBound"),
                "relativeGap": repr(gap) if gap is not None else None,
                "statistics": counters, "statisticsRaw": statistics, "progression": []}
            records.append(current)
        elif current is not None:
            current["progression"].append({
                "nodes": int(fields["nodes"]) if fields.get("nodes", "").isdigit() else None,
                "lowerBound": fields.get("lowerBound"), "upperBound": fields.get("upperBound")})
    return records


def parse_dual_trace(text: str) -> list[dict[str, Any]]:
    """Attach streamed bounds to the following final MemoDual certificate."""
    records, progression = [], []
    for marker, line in re.findall(r"(?m)^\[(MemoDual|MemoDualBound)\] (.*)$", text):
        fields = dict(FIELD.findall(line))
        if marker == "MemoDualBound":
            progression.append({
                "nodes": int(fields["nodes"]) if fields.get("nodes", "").isdigit() else None,
                "lowerBound": fields.get("lowerBound"), "upperBound": fields.get("upperBound")})
            continue
        lower, upper = parse_decimal(fields.get("lowerBound")), parse_decimal(fields.get("upperBound"))
        gap = float(Fraction(upper - lower) / Fraction(lower)) \
            if lower is not None and upper is not None and lower > 0 else None
        statistics_match = re.search(r"\bstatistics=(.*?)(?:\s+reason=|$)", line)
        statistics = statistics_match.group(1) if statistics_match else ""
        reason = re.search(r"\sreason=(.*)$", line)
        counters = {name: int(value) for name, value in
                    re.findall(r"(\w+)=(-?\d+)", statistics)}
        route = re.search(r"\broute=([A-Z_]+)", statistics)
        records.append({"status": fields.get("status"),
            "certified": fields.get("certified", "false").lower() == "true",
            "lowerBound": fields.get("lowerBound"), "upperBound": fields.get("upperBound"),
            "relativeGap": repr(gap) if gap is not None else None,
            "statistics": counters, "statisticsRaw": statistics,
            "route": route.group(1) if route else None,
            "reason": reason.group(1) if reason else fields.get("reason"),
            "progression": progression})
        progression = []
    return records


def parse_memo_plans(text: str) -> list[dict[str, Any]]:
    plans = []
    phases = ("facts", "rules", "model", "optimization", "projection")
    for line in re.findall(r"(?m)^\[MemoPlanner\] (.*)$", text):
        fields = dict(FIELD.findall(line))
        nanos = {phase: int(fields[phase + "Ns"]) for phase in phases
                 if fields.get(phase + "Ns", "").isdigit()}
        plans.append({"status": fields.get("status"), "algorithm": fields.get("algorithm"),
            "cost": fields.get("cost"), "phaseNanos": nanos,
            "planningNanos": sum(nanos.values()) if len(nanos) == len(phases) else None})
    return plans


def memory_bytes(value: str) -> int | None:
    match = MEMORY.fullmatch(value.strip())
    if not match:
        return None
    powers = {"B": 0, "KB": 1, "KiB": 1, "MB": 2, "MiB": 2,
              "GB": 3, "GiB": 3, "TB": 4, "TiB": 4}
    return int(float(match.group(1)) * (1024 ** powers[match.group(2)]))


def absolute_output_root(path: Path) -> Path:
    """Return a Docker-bind-safe absolute output path."""
    return path.resolve()


def reproduction_command(args: argparse.Namespace, workloads: list[str],
                         algorithms: list[str]) -> str:
    command = [str(REPO / "scripts" / "fedplanner" / "run_LAN_docker.sh"),
        "--memo-tree-dp-benchmark", "--engine", str(args.engine.resolve()),
        "--sealed-stage", str(args.sealed_stage.resolve()), "--output-root", str(args.output_root),
        "--workloads", ",".join(workloads), "--workers", str(args.workers),
        "--algorithms", ",".join(algorithms), "--watchdog-seconds", str(args.watchdog_seconds),
        "--heap-mib", str(args.heap_mib),
        "--search-nodes", str(args.search_nodes), "--frontier-nodes", str(args.frontier_nodes),
        "--factor-cells", str(args.factor_cells), "--cache-entries", str(args.cache_entries),
        "--dual-direct-exact", str(args.dual_direct_exact).lower(),
        "--dual-seed-work", str(args.dual_seed_work),
        "--dual-factor-cells", str(args.dual_factor_cells),
        "--dual-direct-cells", str(args.dual_direct_cells),
        "--dual-cluster-cells", str(args.dual_cluster_cells),
        "--dual-improve-seed", str(args.dual_improve_seed).lower(),
        "--local-messages", str(getattr(args, "local_messages", True)).lower(),
        "--local-improve-seed", str(getattr(args, "local_improve_seed", True)).lower(),
        "--factor-diagnostics", str(args.factor_diagnostics).lower(),
        "--factor-diagnostics-max-factors", str(args.factor_diagnostics_max_factors),
        "--dual-diagnostics", str(args.dual_diagnostics).lower(),
        "--dual-memory-bytes", str(args.dual_memory_bytes),
        "--dual-diagnostic-nodes", str(args.dual_diagnostic_nodes),
        "--synthetic-repetitions", str(args.synthetic_repetitions)]
    if args.skip_synthetic:
        command.append("--skip-synthetic")
    if args.runtime_smoke:
        command.append("--runtime-smoke")
    return shlex.join(command)


def run_docker(command: list[str], container: str, timeout: int) -> tuple[int | None, str, float, int, bool]:
    started = time.monotonic()
    peak, timed_out = 0, False
    # A file spool is drained by the kernel continuously. PIPE would deadlock once
    # verbose planner traces fill its buffer while this process polls Docker stats.
    with tempfile.TemporaryFile() as spool:
        process = subprocess.Popen(command, stdout=spool, stderr=subprocess.STDOUT)
        while process.poll() is None:
            if time.monotonic() - started >= timeout:
                timed_out = True
                subprocess.run(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, check=False)
                break
            stats = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", container],
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, check=False)
            if stats.returncode == 0 and stats.stdout.strip():
                used = memory_bytes(stats.stdout.split("/", 1)[0].strip())
                peak = max(peak, used or 0)
            time.sleep(0.2)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        spool.seek(0)
        output = spool.read().decode("utf-8", errors="replace")
    return (None if timed_out else process.returncode, output,
            time.monotonic() - started, peak, timed_out)


def certify(algorithm: str, returncode: int | None, timed_out: bool,
            plans: list[dict[str, Any]], trees: list[dict[str, Any]],
            duals: list[dict[str, Any]] | None = None) -> tuple[str, str | None]:
    if timed_out:
        return "failed", "external_watchdog_no_certificate"
    certificates = (duals or []) if algorithm.startswith("MEMO_") else trees
    if algorithm != "EXACT" and any(certificate.get("status") == "RESOURCE_EXHAUSTED"
                                    for certificate in certificates):
        return "failed", "deterministic_resource_exhaustion"
    if algorithm.startswith("MEMO_"):
        failed = next((certificate.get("status") for certificate in certificates
                       if certificate.get("status") not in {"OPTIMAL", "EPSILON_OPTIMAL"}), None)
        if failed is not None:
            return "failed", "dual_status_" + str(failed).lower()
    if returncode != 0:
        return "failed", "container_failure"
    if not plans:
        return "failed", "missing_memo_plan_trace"
    allowed_plan_statuses = {"OPTIMAL", "EPSILON_OPTIMAL"} \
        if algorithm in {"TREE_DP_LOCAL", "MEMO_LOCAL"} else {"OPTIMAL"}
    if any(plan["status"] not in allowed_plan_statuses for plan in plans):
        return "failed", "memo_plan_not_optimal"
    if algorithm == "EXACT":
        return "passed", None
    if len(certificates) != len(plans):
        return "failed", "missing_dual_certificate" if algorithm.startswith("MEMO_") \
            else "missing_tree_certificate"
    for certificate in certificates:
        lower = parse_decimal(certificate["lowerBound"])
        upper = parse_decimal(certificate["upperBound"])
        if not certificate["certified"] or lower is None or upper is None or lower > upper:
            return "failed", "invalid_dual_certificate" if algorithm.startswith("MEMO_") \
                else "invalid_tree_certificate"
        if algorithm in {"TREE_DP_GLOBAL", "MEMO_GLOBAL"}:
            if certificate["status"] != "OPTIMAL" or lower != upper:
                return "failed", "global_optimality_not_proven"
        elif algorithm == "MEMO_LOCAL" and certificate["status"] == "OPTIMAL":
            if lower != upper:
                return "failed", "local_optimality_not_proven"
        elif algorithm == "MEMO_LOCAL" and lower <= 0:
            return "failed", "epsilon_requires_positive_lower_bound"
        elif lower > 0:
            if not within_epsilon(lower, upper):
                return "failed", "epsilon_gap_not_proven"
        elif lower != upper:
            return "failed", "nonpositive_lower_bound_not_exact"
    return "passed", None


def container_script(algorithm: str, limits: dict[str, int], dual: dict[str, Any],
                     has_dependencies: bool = False, heap_mib: int = 3_072) -> str:
    workdir = ("/workspace/experiments/harness/sigmod2021-exdra-p523/experiments"
               if has_dependencies else "/workspace/experiments")
    return "\n".join(("#!/usr/bin/env bash", "set -euo pipefail", "cd " + workdir,
        "mkdir -p /evidence/tmp/local /evidence/tmp/scratch", "CP=/engine/classes:/deps/*",
        f"exec java --add-modules jdk.incubator.vector -Xmx{heap_mib}m -XX:ActiveProcessorCount=4 "
        "-Dsysds.fedplanner.memo.trace=true "
        f"-Dsysds.fedplanner.memo.algorithm={algorithm} "
        f"-Dsysds.fedplanner.memo.tree.searchNodes={limits['searchNodes']} "
        f"-Dsysds.fedplanner.memo.tree.factorCells={limits['factorCells']} "
        f"-Dsysds.fedplanner.memo.tree.cacheEntries={limits['cacheEntries']} "
        f"-Dsysds.fedplanner.memo.tree.frontierNodes={limits['frontierNodes']} "
        f"-Dsysds.fedplanner.memo.dual.directExact={str(dual['directExact']).lower()} "
        f"-Dsysds.fedplanner.memo.dual.seedWork={dual['seedWork']} "
        f"-Dsysds.fedplanner.memo.dual.factorCells={dual['factorCells']} "
        f"-Dsysds.fedplanner.memo.dual.directCells={dual['directCells']} "
        f"-Dsysds.fedplanner.memo.dual.clusterCells={dual.get('clusterCells', 4096)} "
        f"-Dsysds.fedplanner.memo.dual.improveSeed={str(dual.get('improveSeed', False)).lower()} "
        f"-Dsysds.fedplanner.memo.local.messages={str(dual.get('localMessages', True)).lower()} "
        f"-Dsysds.fedplanner.memo.local.improveSeed={str(dual.get('localImproveSeed', True)).lower()} "
        f"-Dsysds.fedplanner.memo.factorDiagnostics={str(dual.get('factorDiagnostics', False)).lower()} "
        f"-Dsysds.fedplanner.memo.factorDiagnostics.maxFactors={dual.get('factorDiagnosticsMaxFactors', 10000)} "
        f"-Dsysds.fedplanner.memo.dual.diagnostics={str(dual.get('diagnostics', False)).lower()} "
        f"-Dsysds.fedplanner.memo.dual.memoryBytes={dual['memoryBytes']} "
        f"-Dsysds.fedplanner.memo.dual.diagnosticNodes={dual['diagnosticNodes']} "
        "-cp \"$CP\" org.apache.sysds.api.DMLScript -f /evidence/cell.dml "
        "-config /evidence/execution.xml -exec singlenode -seed 1011081480 "
        "-noFedRuntimeConversion -stats 100 -explain runtime", ""))


def docker_command(attempt: Path, engine: Path, stage: Path, container: str) -> list[str]:
    return ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--name", container,
        "--cpus", "4", "--memory", "8g", "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{attempt}:/evidence:rw", "--volume", f"{stage}:/workspace/experiments:ro",
        "--volume", f"{engine / 'classes'}:/engine/classes:ro",
        "--volume", f"{engine / 'lib'}:/deps:ro", "--entrypoint", "bash", PINNED_IMAGE,
        "/evidence/container-run.sh"]


def render_workload(matrix: Any, renderer: Any, workload: str, workers: int) -> tuple[str, dict[str, Any]]:
    value = matrix.cell("ml", workload, workers, "DP-global")
    source, _, receipt, adaptation = matrix.render_artifacts(renderer, value)
    expected_ports = list(range(8001, 8001 + workers))
    for input_value in receipt["inputs"]:
        shards = input_value["shards"]
        if len(shards) != workers or [row["worker"]["port"] for row in shards] != expected_ports:
            raise ValueError("rendered input topology differs from the requested ordered worker prefix")
    return source, {"renderReceipt": receipt, "adaptationReceipt": adaptation}


def run_compile_cell(output: Path, engine: Path, stage: Path, workload: str, workers: int,
                     algorithm: str, source: str, rendered: dict[str, Any], timeout: int,
                     limits: dict[str, int], dual: dict[str, Any], heap_mib: int,
                     matrix: Any) -> dict[str, Any]:
    attempt = output / "compile" / f"{workload}-w{workers}-{algorithm.lower()}"
    attempt.mkdir(parents=True, exist_ok=False)
    (attempt / "cell.dml").write_text(source, encoding="utf-8")
    (attempt / "execution.xml").write_text(configuration("compile_memo", algorithm, dual), encoding="utf-8")
    has_dependencies = bool(rendered["renderReceipt"].get("dependencies"))
    (attempt / "container-run.sh").write_text(
        container_script(algorithm, limits, dual, has_dependencies, heap_mib), encoding="utf-8")
    (attempt / "container-run.sh").chmod(0o755)
    atomic_json(attempt / "render.json", rendered)
    container = "sysds-tree-dp-" + hashlib.sha256(str(attempt).encode()).hexdigest()[:16]
    command = docker_command(attempt, engine, stage, container)
    atomic_json(attempt / "command.json", {"argv": command, "shell": shlex.join(command)})
    returncode, log, wall, peak, timed_out = run_docker(command, container, timeout)
    (attempt / "coordinator.log").write_text(log, encoding="utf-8")
    plans, trees = parse_memo_plans(log), parse_tree_trace(log)
    duals = parse_dual_trace(log)
    status, failure = certify(algorithm, returncode, timed_out, plans, trees, duals)
    if status == "failed" and not timed_out and OUT_OF_MEMORY.search(log):
        failure = "jvm_out_of_memory"
    compile_result = matrix.compile_evidence(log)
    if status == "passed" and not compile_result["verified"]:
        status, failure = "failed", "full_compile_evidence_missing"
    result = {"schema": "systemds-memo-tree-dp-attempt/v1", "phase": "source-fullcompile",
        "workload": workload, "workers": workers, "algorithm": algorithm, "status": status,
        "failureCategory": failure, "returncode": returncode, "externalWatchdogSeconds": timeout,
        "deterministicTreeLimits": limits,
        "dualOptions": dual,
        "compileHeapMiB": heap_mib,
        "timedOut": timed_out, "wallSeconds": wall, "peakContainerMemoryUsageBytes": peak or None,
        "compile": compile_result, "memoPlans": plans, "treeCertificates": trees,
        "dualCertificates": duals,
        "sourceSha256": sha256_file(attempt / "cell.dml"),
        "comparisonGroup": f"ml:{workload}:w{workers}:source-fullcompile"}
    atomic_json(attempt / "result.json", result)
    return result


def synthetic_script(repetitions: int) -> str:
    return "\n".join(("#!/usr/bin/env bash", "set -euo pipefail", "CP=/engine/test-classes:/engine/classes:/deps/*",
        "java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 "
        "-cp \"$CP\" org.apache.sysds.hops.fedplanner.memo.MemoTreeDpBenchmark "
        f"{repetitions}",
        "exec java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 "
        "-cp \"$CP\" org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.MemoTreeDpRegionalBenchmark", ""))


def has_required_synthetic_rows(rows: list[dict[str, Any]]) -> bool:
    observed = {(row.get("case"), row.get("algorithm")) for row in rows}
    required = {(case, algorithm) for case in SYNTHETIC_CASES for algorithm in ALGORITHMS}
    return required.issubset(observed)


def run_synthetic(output: Path, engine: Path, repetitions: int, timeout: int) -> dict[str, Any]:
    attempt = output / "synthetic-same-model"
    attempt.mkdir(parents=True, exist_ok=False)
    (attempt / "container-run.sh").write_text(synthetic_script(repetitions), encoding="utf-8")
    (attempt / "container-run.sh").chmod(0o755)
    container = "sysds-tree-synthetic-" + hashlib.sha256(str(attempt).encode()).hexdigest()[:16]
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--name", container,
        "--cpus", "4", "--memory", "8g", "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{attempt}:/evidence:rw", "--volume", f"{engine / 'classes'}:/engine/classes:ro",
        "--volume", f"{engine / 'test-classes'}:/engine/test-classes:ro",
        "--volume", f"{engine / 'lib'}:/deps:ro", "--entrypoint", "bash", PINNED_IMAGE,
        "/evidence/container-run.sh"]
    atomic_json(attempt / "command.json", {"argv": command, "shell": shlex.join(command)})
    returncode, log, wall, peak, timed_out = run_docker(command, container, timeout)
    (attempt / "benchmark.log").write_text(log, encoding="utf-8")
    rows = []
    for line in re.findall(r"(?m)^TREE_DP_BENCHMARK\|(.*)$", log):
        fields = dict(item.split("=", 1) for item in line.split("|") if "=" in item)
        rows.append(fields)
    regional = [dict(item.split("=", 1) for item in line.split("|") if "=" in item)
                for line in re.findall(r"(?m)^TREE_DP_REGIONAL_BENCHMARK\|(.*)$", log)]
    passed = (returncode == 0 and not timed_out and "TREE_DP_BENCHMARK_OK" in log
              and "TREE_DP_REGIONAL_BENCHMARK_OK" in log
              and has_required_synthetic_rows(rows)
              and len(regional) == 1)
    result = {"schema": "systemds-memo-tree-dp-synthetic/v1", "phase": "synthetic-same-model",
        "status": "passed" if passed else "failed", "failureCategory": None if passed else
            ("external_watchdog" if timed_out else "benchmark_failure"), "returncode": returncode,
        "wallSeconds": wall, "peakContainerMemoryUsageBytes": peak or None, "rows": rows,
        "legacyRegionalCompared": True, "legacyRegional": regional,
        "memorySemantics": {"peakContainerMemoryUsageBytes":
            "high-water of Docker MemUsage samples; includes charged cache and is not RSS",
            "peakPoolBytes": "per-process JVM pool high-water; sequential algorithms retain prior heap state"}}
    atomic_json(attempt / "result.json", result)
    return result


def runtime_script(limits: dict[str, int]) -> str:
    return "\n".join(("#!/usr/bin/env bash", "set -euo pipefail", "cd /workspace",
        "CP=/engine/test-classes:/engine/classes:/deps/*",
        "exec java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 "
        "-Dsysds.fedplanner.memo.trace=true "
        "-Dsysds.fedplanner.memo.algorithm=EXACT "
        f"-Dsysds.fedplanner.memo.tree.searchNodes={limits['searchNodes']} "
        f"-Dsysds.fedplanner.memo.tree.factorCells={limits['factorCells']} "
        f"-Dsysds.fedplanner.memo.tree.cacheEntries={limits['cacheEntries']} "
        f"-Dsysds.fedplanner.memo.tree.frontierNodes={limits['frontierNodes']} "
        "-cp \"$CP\" org.junit.runner.JUnitCore "
        "org.apache.sysds.hops.fedplanner.memo.MemoPlannerFederatedRuntimeTest "
        "org.apache.sysds.hops.fedplanner.memo.MemoTreeDpTrainingRuntimeTest "
        "org.apache.sysds.hops.fedplanner.memo.MemoSharedSupplyRuntimeTest "
        "org.apache.sysds.hops.fedplanner.memo.MemoMultiOutputBuiltinTest", ""))


def run_runtime_cell(output: Path, engine: Path, timeout: int,
                     limits: dict[str, int]) -> dict[str, Any]:
    attempt = output / "runtime" / "all-algorithms"
    attempt.mkdir(parents=True, exist_ok=False)
    (attempt / "container-run.sh").write_text(runtime_script(limits), encoding="utf-8")
    (attempt / "container-run.sh").chmod(0o755)
    container = "sysds-tree-runtime-" + hashlib.sha256(str(attempt).encode()).hexdigest()[:16]
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "bridge", "--name", container,
        "--cpus", "4", "--memory", "8g", "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{attempt}:/evidence:rw", "--volume", f"{REPO}:/workspace:ro",
        "--volume", f"{engine / 'classes'}:/engine/classes:ro",
        "--volume", f"{engine / 'test-classes'}:/engine/test-classes:ro",
        "--volume", f"{engine / 'lib'}:/deps:ro", "--entrypoint", "bash", PINNED_IMAGE,
        "/evidence/container-run.sh"]
    atomic_json(attempt / "command.json", {"argv": command, "shell": shlex.join(command)})
    returncode, log, wall, peak, timed_out = run_docker(command, container, timeout)
    (attempt / "runtime.log").write_text(log, encoding="utf-8")
    junit = re.findall(r"(?m)^OK \((\d+) tests?\)$", log)
    passed = returncode == 0 and not timed_out and len(junit) == 1
    result = {"schema": "systemds-memo-tree-dp-runtime/v1", "phase": "federated-runtime-smoke",
        "network": "bridge", "compileOnly": False, "algorithm": "ALL_ALGORITHMS",
        "status": "passed" if passed else "failed",
        "failureCategory": None if passed else
            ("external_watchdog" if timed_out else "runtime_test_failure"),
        "tests": int(junit[0]) if len(junit) == 1 else None, "returncode": returncode,
        "wallSeconds": wall, "peakContainerMemoryUsageBytes": peak or None,
        "deterministicTreeLimits": limits}
    atomic_json(attempt / "result.json", result)
    return result


def compare_group(rows: list[dict[str, Any]]) -> dict[str, Any]:
    by_algorithm = {row["algorithm"]: row for row in rows}
    if "EXACT" not in by_algorithm:
        return {"status": "not-evaluated", "reason": "EXACT baseline not selected"}
    exact = by_algorithm["EXACT"]
    exact_costs = [parse_decimal(plan.get("cost")) for plan in exact["memoPlans"]]
    if not exact_costs or any(cost is None for cost in exact_costs):
        return {"status": "failed", "reason": "EXACT baseline costs missing"}
    guarantees_established = True
    for algorithm in ALGORITHMS:
        if algorithm == "EXACT":
            continue
        row = by_algorithm.get(algorithm)
        if row is None:
            continue
        certificates = row.get("dualCertificates", []) if algorithm.startswith("MEMO_") \
            else row.get("treeCertificates", [])
        if len(certificates) != len(exact_costs):
            return {"status": "failed", "validBounds": False,
                "requestedGuaranteeEstablished": False,
                "reason": f"{algorithm} invocation count differs from EXACT"}
        for optimum, certificate in zip(exact_costs, certificates):
            bounds = [certificate] + certificate.get("progression", [])
            for bound in bounds:
                lower = parse_decimal(bound.get("lowerBound"))
                upper = parse_decimal(bound.get("upperBound"))
                if lower is None or lower > optimum or (upper is not None and
                        (lower > upper or optimum > upper)):
                    return {"status": "failed", "validBounds": False,
                        "requestedGuaranteeEstablished": False,
                        "reason": f"{algorithm} bounds exclude EXACT optimum"}
            if row.get("status") == "passed":
                upper = parse_decimal(certificate.get("upperBound"))
                if algorithm in {"TREE_DP_GLOBAL", "MEMO_GLOBAL"} and upper != optimum:
                    return {"status": "failed", "validBounds": True,
                        "requestedGuaranteeEstablished": False,
                        "reason": f"{algorithm} certified result differs from EXACT optimum"}
                if algorithm in {"TREE_DP_LOCAL", "MEMO_LOCAL"} and (upper is None or
                        (optimum > 0 and not within_epsilon(optimum, upper)) or
                        (optimum <= 0 and upper != optimum)):
                    return {"status": "failed", "validBounds": True,
                        "requestedGuaranteeEstablished": False,
                        "reason": f"{algorithm} certified result exceeds its requested guarantee"}
            else:
                guarantees_established = False
    return {"status": "passed", "validBounds": True,
            "requestedGuaranteeEstablished": guarantees_established, "reason": None,
            "independentOptimumSource": "Memo EXACT on identical frozen model"}


def summarize(output: Path, synthetic: dict[str, Any] | None, compile_rows: list[dict[str, Any]],
              runtime_rows: list[dict[str, Any]]) -> bool:
    groups: dict[str, dict[str, Any]] = {}
    for row in compile_rows:
        group = groups.setdefault(row["comparisonGroup"], {"sourceSha256": row["sourceSha256"], "rows": []})
        if group["sourceSha256"] != row["sourceSha256"]:
            raise AssertionError("comparison group contains unequal DML source")
        group["rows"].append(row)
    for group in groups.values():
        group["exactComparison"] = compare_group(group["rows"])
    comparisons_pass = all(group["exactComparison"]["status"] in {"passed", "not-evaluated"}
                           for group in groups.values())
    complete = (bool(compile_rows) and comparisons_pass
        and (synthetic is None or synthetic["status"] == "passed")
        and all(row["status"] == "passed" for row in compile_rows)
        and all(row["status"] == "passed" for row in runtime_rows))
    summary = {"schema": "systemds-memo-tree-dp-benchmark/v1",
        "complete": complete,
        "comparisonContract": "identical DML bytes, image, CPU, memory, cost defaults, and compile-only mode",
        "regionalComparison": {"performed": False,
            "reason": "real-workload Regional model is not proven equivalent; equal-factor synthetic comparison is in synthetic"},
        "synthetic": synthetic, "compileRows": compile_rows, "runtimeRows": runtime_rows, "groups": groups}
    atomic_json(output / "summary.json", summary)
    fields = ("phase", "workload", "workers", "algorithm", "status", "failureCategory",
              "wallSeconds", "peakContainerMemoryUsageBytes")
    with (output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in compile_rows + runtime_rows:
            writer.writerow({name: row.get(name) for name in fields})
    write_report(output, complete, synthetic, compile_rows, runtime_rows, groups)
    return complete


def write_report(output: Path, complete: bool, synthetic: dict[str, Any] | None,
                 compile_rows: list[dict[str, Any]], runtime_rows: list[dict[str, Any]],
                 groups: dict[str, dict[str, Any]]) -> None:
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    lines = ["# Memo Tree-DP benchmark report", "",
        f"Overall result: **{'PASS' if complete else 'INCOMPLETE/FAILED'}**.", "",
        "Reproduction command:", "", f"```sh\n{manifest['reproductionCommand']}\n```", "",
        "The selected Memo algorithms use exact structural kernels or certified conflict search over the same "
        "immutable factors. Deferred cyclic, wide, shared-movement, and legality factors remain represented in "
        "the certified bound. Every reported upper bound is a fully validated assignment.", "",
        "Global succeeds only with equal lower and upper bounds. Local succeeds only with a positive lower "
        "bound and `(UB-LB)/LB <= 0.05`, or with exact equality for a non-positive lower bound. Deterministic "
        "resource exhaustion and the external watchdog are failures, never approximate-success conditions.", "",
        "## Source full-compile results", "",
        "| Workload | Algorithm | Heap MiB | Status | Planner seconds | DP seconds | Conflict seconds | Wall seconds | Full compile seconds | Peak container usage bytes | Final LB | Final UB | Failure |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for row in compile_rows:
        planning_nanos = [plan["planningNanos"] for plan in row["memoPlans"]
                          if plan.get("planningNanos") is not None]
        planning = f"{sum(planning_nanos) / 1e9:.6f}" if planning_nanos else "unavailable"
        certificates = row.get("dualCertificates", []) if row["algorithm"].startswith("MEMO_") \
            else row["treeCertificates"]
        statistics = certificates[-1].get("statistics", {}) if certificates else {}
        dp = f"{statistics['dpNanos'] / 1e9:.6f}" if "dpNanos" in statistics else "unavailable"
        conflict = (f"{statistics['conflictNanos'] / 1e9:.6f}"
                    if "conflictNanos" in statistics else "unavailable")
        lower = certificates[-1]["lowerBound"] if certificates else ""
        upper = certificates[-1]["upperBound"] if certificates else (
            row["memoPlans"][-1]["cost"] if row["memoPlans"] else "")
        lines.append(f"| {row['workload']} | {row['algorithm']} | {row['compileHeapMiB']} | "
            f"{row['status']} | {planning} | "
            f"{dp} | {conflict} | {row['wallSeconds']:.6f} | "
            f"{row['compile'].get('fullCompileSeconds') or ''} | {row.get('peakContainerMemoryUsageBytes') or ''} | "
            f"{lower} | {upper} | {row.get('failureCategory') or ''} |")
    lines += ["", "Each workload group used byte-identical source. Memo EXACT was executed independently on the "
        "same frozen model and checked against every reported certificate interval:", ""]
    for name, group in groups.items():
        comparison = group["exactComparison"]
        lines.append(f"- `{name}`: {comparison['status']}"
            + (f"; valid bounds={str(comparison['validBounds']).lower()}, requested guarantee established="
               f"{str(comparison['requestedGuaranteeEstablished']).lower()}"
               if "validBounds" in comparison else "")
            + (f" ({comparison['reason']})" if comparison.get("reason") else ""))
    lines += ["", "## Synthetic and Regional comparison", ""]
    if synthetic is None:
        lines.append("Synthetic comparison was explicitly skipped.")
    else:
        lines.append(f"Synthetic same-model benchmark: **{synthetic['status']}**. The Regional row uses an "
            "identical categorical variable/factor model; no Regional comparison is claimed for real workloads.")
        for row in synthetic.get("rows", []):
            lines.append(f"- {row.get('case')}: {row.get('algorithm')} mean={row.get('meanNanos')} ns, "
                f"nodes={row.get('meanNodes')}, conflicts={row.get('meanConflicts')}, "
                f"LB={row.get('lowerBound')}, UB={row.get('upperBound')}")
    lines += ["", "## Runtime evidence", ""]
    if runtime_rows:
        for row in runtime_rows:
            lines.append(f"- Federated training/runtime suite: {row['status']}, tests={row.get('tests')}, "
                f"wall={row['wallSeconds']:.3f}s, peak container usage="
                f"{row.get('peakContainerMemoryUsageBytes')} bytes.")
    else:
        lines.append("Runtime smoke was not requested; the table above is compile-only evidence.")
    lines += ["", "Machine-readable bounds, full progression, counters, commands, and failure categories are in "
        "`summary.json` and the per-attempt directories.", ""]
    (output / "report.md").write_text("\n".join(lines), encoding="utf-8")


def parse_list(value: str, allowed: tuple[str, ...], option: str) -> tuple[str, ...]:
    result = tuple(value.split(","))
    if not result or len(set(result)) != len(result) or any(item not in allowed for item in result):
        raise ValueError(f"{option} must be a unique comma list drawn from {','.join(allowed)}")
    return result


def parse_boolean(value: str) -> bool:
    normalized = value.lower()
    if normalized not in {"true", "false"}:
        raise argparse.ArgumentTypeError("expected true or false")
    return normalized == "true"


def argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, type=Path,
                        help="frozen engine containing classes/, lib/, and optionally test-classes/")
    parser.add_argument("--sealed-stage", required=True, type=Path)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--workloads", default=",".join(WORKLOADS))
    parser.add_argument("--workers", type=int, default=3, choices=(1, 3, 5, 7))
    parser.add_argument("--algorithms", default=",".join(DEFAULT_ALGORITHMS))
    parser.add_argument("--watchdog-seconds", type=int, default=900)
    parser.add_argument("--heap-mib", type=int, default=3_072,
                        help="compile-cell JVM maximum heap in MiB; synthetic/runtime remain at 3072 MiB")
    parser.add_argument("--search-nodes", type=int, default=20_000)
    parser.add_argument("--frontier-nodes", type=int, default=10_000)
    parser.add_argument("--factor-cells", type=int, default=256)
    parser.add_argument("--cache-entries", type=int, default=4_096)
    parser.add_argument("--dual-direct-exact", type=parse_boolean, default=True)
    parser.add_argument("--dual-seed-work", type=int, default=100_000)
    parser.add_argument("--dual-factor-cells", type=int, default=256)
    parser.add_argument("--dual-direct-cells", type=int, default=2_000_000)
    parser.add_argument("--dual-cluster-cells", type=int, default=4096)
    parser.add_argument("--dual-improve-seed", type=parse_boolean, default=False)
    parser.add_argument("--local-messages", type=parse_boolean, default=True)
    parser.add_argument("--local-improve-seed", type=parse_boolean, default=True)
    parser.add_argument("--factor-diagnostics", type=parse_boolean, default=False)
    parser.add_argument("--factor-diagnostics-max-factors", type=int, default=10000)
    parser.add_argument("--dual-diagnostics", type=parse_boolean, default=False)
    parser.add_argument("--dual-memory-bytes", type=int, default=9_223_372_036_854_775_807)
    parser.add_argument("--dual-diagnostic-nodes", type=int, default=9_223_372_036_854_775_807)
    parser.add_argument("--skip-synthetic", action="store_true")
    parser.add_argument("--synthetic-repetitions", type=int, default=7)
    parser.add_argument("--runtime-smoke", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = argument_parser().parse_args(argv)
    args.output_root = absolute_output_root(args.output_root)
    workloads = parse_list(args.workloads, WORKLOADS, "--workloads")
    algorithms = parse_list(args.algorithms, ALGORITHMS, "--algorithms")
    limits = {"searchNodes": args.search_nodes, "frontierNodes": args.frontier_nodes,
              "factorCells": args.factor_cells, "cacheEntries": args.cache_entries}
    dual = {"directExact": args.dual_direct_exact, "seedWork": args.dual_seed_work,
            "factorCells": args.dual_factor_cells, "directCells": args.dual_direct_cells,
            "memoryBytes": args.dual_memory_bytes, "clusterCells": args.dual_cluster_cells,
            "improveSeed": args.dual_improve_seed, "factorDiagnostics": args.factor_diagnostics,
            "localMessages": args.local_messages, "localImproveSeed": args.local_improve_seed,
            "factorDiagnosticsMaxFactors": args.factor_diagnostics_max_factors,
            "diagnostics": args.dual_diagnostics,
            "diagnosticNodes": args.dual_diagnostic_nodes}
    if (args.watchdog_seconds < 1 or args.heap_mib < 1 or args.synthetic_repetitions < 1
            or args.search_nodes < 1 or args.frontier_nodes < 1 or args.factor_cells < 1
            or args.cache_entries < 0 or args.dual_seed_work < 0 or args.dual_factor_cells < 1
            or args.dual_direct_cells < 0 or args.dual_cluster_cells < 0 or args.factor_diagnostics_max_factors < 0
            or args.dual_memory_bytes < 0 or args.dual_diagnostic_nodes < 1):
        raise ValueError("watchdog, repetition, and deterministic resource limits must be positive")
    if args.output_root.exists():
        raise ValueError("--output-root must not already exist")
    for required in (args.engine / "classes", args.engine / "lib", args.sealed_stage):
        if not required.exists():
            raise ValueError(f"required input is missing: {required}")
    if (args.runtime_smoke or not args.skip_synthetic) and not (args.engine / "test-classes").is_dir():
        raise ValueError("synthetic and runtime phases require engine/test-classes (or use --skip-synthetic)")
    matrix = load_matrix_module()
    engine_receipt = matrix.validate_engine(args.engine.resolve())
    stage = matrix.validate_sealed_stage(args.sealed_stage.resolve())
    cost_receipt = matrix.validate_cost_contract()
    renderer = matrix.renderer_module().Renderer(stage)
    args.output_root.mkdir(parents=True)
    atomic_json(args.output_root / "manifest.json", {"schema": "systemds-memo-tree-dp-manifest/v1",
        "repository": str(REPO), "repositoryCommit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
        "engine": str(args.engine.resolve()), "stage": str(args.sealed_stage.resolve()),
        "engineValidation": engine_receipt,
        "reproductionCommand": reproduction_command(args, workloads, algorithms),
        "stageValidation": {"manifestSha256": matrix.sha256_file(stage / "W1357_STAGE.json")},
        "costContract": cost_receipt,
        "image": PINNED_IMAGE, "workloads": workloads, "workers": args.workers,
        "algorithms": algorithms, "watchdogSeconds": args.watchdog_seconds,
        "compileHeapMiB": args.heap_mib,
        "heapConfiguration": {"compileMiB": args.heap_mib, "syntheticMiB": 3072,
                              "runtimeMiB": 3072},
        "deterministicTreeLimits": limits,
        "dualOptions": dual,
        "watchdogSemantics": "failure only; never an optimization success condition",
        "compileNetwork": "none", "runtimeNetwork": "bridge" if args.runtime_smoke else None})
    synthetic = None if args.skip_synthetic else run_synthetic(args.output_root,
        args.engine.resolve(), args.synthetic_repetitions, args.watchdog_seconds)
    compile_rows = []
    for workload in workloads:
        source, rendered = render_workload(matrix, renderer, workload, args.workers)
        for algorithm in algorithms:
            compile_rows.append(run_compile_cell(args.output_root, args.engine.resolve(),
                args.sealed_stage.resolve(), workload, args.workers, algorithm, source, rendered,
                args.watchdog_seconds, limits, dual, args.heap_mib, matrix))
    runtime_rows = []
    if args.runtime_smoke:
        runtime_rows.append(run_runtime_cell(args.output_root, args.engine.resolve(),
                                             args.watchdog_seconds, limits))
    return 0 if summarize(args.output_root, synthetic, compile_rows, runtime_rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
