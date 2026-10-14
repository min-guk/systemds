#!/usr/bin/env python3
"""Run the sealed W1/3/5/7 workload matrix as Memo compile-only Docker cells.

This is deliberately a small, single-host adapter. The sealed renderer remains
the authority for workload bytes and validation. Compile-only planning consumes
the sealed local metadata without changing endpoints or starting workers.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from typing import Any
import xml.etree.ElementTree as ET


REPO = Path(__file__).resolve().parents[2]
RENDERER_PATH = Path("/home/mchoi/cofee-evaluation/campaign/render_w1357_workload.py")
PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
PINNED_RENDERER_SHA256 = "145d6295b0d0471630328bfcba2d20dfeefdd93767953f89c4bd7fbd41de1c70"
PINNED_STAGE_MANIFEST_SHA256 = "e3544c65da20e46b3318c02a8d984aff108bf32435bf7a18b66e65d15bfd6a1e"
C26_RUNTIME_PROVENANCE = {
    "manifest": ("/grid/3/cofee-lm-sweep-mchoi-20260914/memo-under3-20261014/"
                 "memo-under3-candidate26-logreg/manifest.json",
                 "a290c393564426b8255a5d4135a9821b849ed81b79c0d0b881ed14076a1f089f"),
    "config": ("/grid/3/cofee-lm-sweep-mchoi-20260914/memo-under3-20261014/"
               "memo-under3-candidate26-logreg/config.xml",
               "ce35c4d5baca3ce572581c1966120c614f9bef67f7cda57023e360fa610224c8"),
    "containerScript": ("/grid/3/cofee-lm-sweep-mchoi-20260914/memo-under3-20261014/"
                         "memo-under3-candidate26-logreg/container-run.sh",
                         "e54fd1b00b9f2fe4f3a958c64fa0f329e94ae6d02bbe4d5440be2ea0f4542d10"),
}

WORKERS = (1, 3, 5, 7)
WORKLOADS = (
    *(("ml", name) for name in
      ("pca", "als", "kmeans", "lm", "logreg", "l2svm", "steplm", "glm", "gnmf", "gmm")),
    ("p1", "P1_FULL"), ("p2", "P2_PREP"),
    ("sliceline", "ADULT"), ("sliceline", "COVTYPE"),
)
SAFE_FILTER = re.compile(r"^[A-Za-z0-9_:-]+$")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def renderer_module():
    if not RENDERER_PATH.is_file():
        raise ValueError(f"sealed renderer unavailable: {RENDERER_PATH}")
    if sha256_file(RENDERER_PATH) != PINNED_RENDERER_SHA256:
        raise ValueError("sealed renderer hash mismatch")
    return load_module("sealed_w1357_renderer", RENDERER_PATH)


def statistics_module():
    return load_module("memo_joint_statistics", REPO / "scripts/fedplanner/run_joint_boundary_e2e.py")


def cell(suite: str, workload: str, workers: int, planner: str = "DP-global") -> dict[str, Any]:
    return {
        "id": f"{suite}|{workload}|lan|w{workers}|{planner}",
        "suite": suite, "workload": workload, "profile": "lan", "workers": workers,
        "planner": planner,
        "planner_enum": "COMPILE_EXACT" if planner == "DP-global" else "COMPILE_COST_BASED",
    }


def cells(workloads: tuple[tuple[str, str], ...] = WORKLOADS,
          workers: tuple[int, ...] = WORKERS) -> list[dict[str, Any]]:
    result = [cell(suite, workload, count) for suite, workload in workloads for count in workers]
    if len(result) != len(workloads) * len(workers) or len({row["id"] for row in result}) != len(result):
        raise AssertionError("Memo planning matrix cells are not unique")
    return result


def execution_cell(render_cell: dict[str, Any]) -> dict[str, Any]:
    return {"id": (f"{render_cell['suite']}|{render_cell['workload']}|lan|"
                   f"w{render_cell['workers']}|Memo"),
            "suite": render_cell["suite"], "workload": render_cell["workload"],
            "profile": "lan", "workers": render_cell["workers"], "planner": "Memo",
            "planner_enum": "COMPILE_MEMO"}


def failed_attempt(render_cell: dict[str, Any], category: str,
                   error: Exception | None = None) -> dict[str, Any]:
    result = {"schema": "systemds-memo-planning-attempt/v1",
        "cell": execution_cell(render_cell), "renderCell": render_cell,
        "executionPlanner": "COMPILE_MEMO", "rendererAuthorityPlanner": "COMPILE_EXACT",
        "status": "failed", "failureCategory": category, "returncode": None}
    if error is not None:
        result.update(errorClass=type(error).__name__, errorMessage=str(error))
    return result


def planner_neutral_source(rendered: dict[str, Any]) -> str:
    source = rendered["source"]
    for output in rendered.get("outputs", []):
        path = output["path"]
        if source.count(path) != 1:
            raise ValueError("rendered output path occurrence changed")
        source = source.replace(path, f"__PLANNER_NEUTRAL_OUTPUT_{output['role']}__")
    return source


def adapt_rendered(rendered: dict[str, Any], independent_source: str) -> tuple[str, dict[str, Any]]:
    """Prove planner independence and preserve sealed DML byte-for-byte."""
    original = rendered["source"]
    neutral = planner_neutral_source(rendered)
    if neutral != independent_source:
        raise ValueError("sealed DML depends on the renderer planner field")
    digest = sha256_bytes(original.encode())
    return original, {
        "schema": "systemds-memo-planning-input-adaptation/v1",
        "reason": "compile_only uses sealed local metadata and performs no worker RPC",
        "plannerInput": rendered["cell"]["planner"],
        "plannerInputEnum": rendered["cell"]["planner_enum"],
        "executionPlanner": "COMPILE_MEMO",
        "dmlPlannerIndependenceVerifiedAgainst": "DP-local",
        "plannerNeutralSha256": sha256_bytes(neutral.encode()),
        "originalSha256": digest, "adaptedSha256": digest,
        "transformations": [], "sealedEndpointsPreserved": True,
    }


def configuration(suite: str) -> str:
    values = {
        "sysds.native.blas": "none", "sysds.local.spark": "true",
        "sysds.federated.planner": "compile_memo", "sysds.federated.timeout": "-1",
        "sysds.benchmark.compile_only": "true", "sysds.codegen.enabled": "false",
        "sysds.localtmpdir": "/evidence/tmp/local", "sysds.scratch": "/evidence/tmp/scratch",
    }
    root = ET.Element("root")
    for name, value in values.items():
        ET.SubElement(root, name).text = value
    return ET.tostring(root, encoding="unicode") + "\n"


def validate_cost_contract() -> dict[str, Any]:
    sources = {}
    for name, (raw_path, expected) in C26_RUNTIME_PROVENANCE.items():
        path = Path(raw_path)
        if not path.is_file() or sha256_file(path) != expected:
            raise ValueError(f"published C26 runtime provenance mismatch: {name}")
        sources[name] = {"path": str(path), "sha256": expected}
    ambient = sorted(name for name in os.environ if name.startswith("SYSDS_FED_COST_"))
    if ambient:
        raise ValueError("ambient federated cost override is forbidden: " + ",".join(ambient))
    return {"semantics": "published-C26-production-default-costs-no-overrides",
            "nativeBlas": "none", "codegen": False, "costEnvironment": {},
            "sources": sources}


def validate_engine(engine: Path) -> dict[str, Any]:
    manifest = engine.parent / (engine.name + "-sha256.json")
    if not manifest.is_file():
        raise ValueError(f"frozen engine manifest is missing: {manifest}")
    value = json.loads(manifest.read_text(encoding="utf-8"))
    entries = value.get("files", value)
    if not isinstance(entries, dict) or not entries:
        raise ValueError("frozen engine manifest has no file map")
    actual = {}
    for relative, expected in entries.items():
        if not isinstance(relative, str) or not isinstance(expected, str):
            raise ValueError("invalid frozen engine manifest entry")
        path = engine / relative
        if not path.is_file() or sha256_file(path) != expected:
            raise ValueError(f"frozen engine verification failed: {relative}")
        actual[relative] = expected
    actual_files = {path.relative_to(engine).as_posix() for path in engine.rglob("*") if path.is_file()}
    if actual_files != set(actual):
        raise ValueError("frozen engine actual file set differs from manifest")
    required = ("classes/org/apache/sysds/api/DMLScript.class",)
    if any(name not in actual for name in required):
        raise ValueError("frozen engine manifest lacks required production classes")
    return {"manifest": str(manifest), "manifestSha256": sha256_file(manifest),
            "verifiedFiles": len(actual)}


def container_script(p2: bool, has_dependencies: bool = False) -> str:
    p2_property = " -Dsysds.privacy.allowPublicRecodeMetadata=true" if p2 else ""
    # Sealed source() literals use code/... relative to the original experiment directory.
    workdir = ("/workspace/experiments/harness/sigmod2021-exdra-p523/experiments"
               if has_dependencies else "/workspace/experiments")
    return "\n".join([
        "#!/usr/bin/env bash", "set -euo pipefail", "cd " + workdir,
        "mkdir -p /evidence/tmp/local /evidence/tmp/scratch", "CP=/engine/classes:/deps/*",
        "touch /evidence/setup-complete", "set +e",
        "timeout --signal=TERM --kill-after=15 300 java --add-modules jdk.incubator.vector "
        "-Xmx3g -XX:ActiveProcessorCount=4 -Dsysds.fedplanner.memo.trace=true" + p2_property
        + " -cp \"$CP\" org.apache.sysds.api.DMLScript -f /evidence/cell.dml "
        "-config /evidence/execution.xml -exec singlenode -seed 1011081480 "
        "-noFedRuntimeConversion -stats 100 -explain runtime > /evidence/coordinator.log 2>&1",
        "rc=$?", "printf '%s\\n' \"$rc\" > /evidence/coordinator.rc", "exit \"$rc\"", "",
    ])


def docker_command(attempt: Path, engine: Path, stage: Path,
                   rendered: dict[str, Any], container_name: str) -> list[str]:
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none",
               "--name", container_name, "--cpus", "4", "--memory", "8g",
               "--user", f"{os.getuid()}:{os.getgid()}"]
    command += ["--volume", f"{attempt}:/evidence:rw", "--volume", f"{stage}:/workspace/experiments:ro",
                "--volume", f"{engine / 'classes'}:/engine/classes:ro",
                "--volume", f"{engine / 'lib'}:/deps:ro", "--entrypoint", "bash",
                PINNED_IMAGE, "/evidence/container-run.sh"]
    expected = rendered["cell"]["workers"]
    expected_ports = list(range(8001, 8001 + expected))
    for value in rendered["inputs"]:
        if len(value["shards"]) != expected:
            raise ValueError("renderer reported a different worker count for an input")
        if [row["worker"]["port"] for row in value["shards"]] != expected_ports:
            raise ValueError("renderer input topology is not the ordered worker prefix")
    return command


def failure_category(returncode: int | None, attempt: Path, plans: list[dict[str, Any]],
                     searches: list[dict[str, Any]], *, harness_timeout: bool = False) -> str | None:
    if not (attempt / "setup-complete").is_file():
        return "setup_failure"
    if harness_timeout:
        return "harness_timeout_not_proven"
    if returncode == 124:
        return "timeout_not_proven"
    if returncode == 137:
        return "killed_not_proven"
    if any(row.get("status") != "OPTIMAL" for row in searches):
        return "planner_failure"
    if returncode != 0 and plans:
        return "planning_succeeded_compile_failed"
    if returncode != 0:
        return "compilation_failure"
    return None


def compile_evidence(text: str) -> dict[str, Any]:
    compile_values = re.findall(r"(?m)^Total compilation time:\s*([0-9.]+) sec\.?$", text)
    initial_values = re.findall(
        r"(?m)^PlanningFullInitialReceipt schema=planning-full-initial-v1 "
        r"Tplanning_full_initial_nanos=(\d+)$", text)
    runtime_values = re.findall(r"(?m)^Compile Phase RuntimeProgram:\s*([0-9.]+) sec\.?$", text)
    execution_values = re.findall(r"(?m)^Total execution time:\s*([0-9.]+) sec\.?$", text)
    verified = (len(compile_values) == len(initial_values) == len(runtime_values) == 1
        and float(compile_values[0]) > 0 and int(initial_values[0]) > 0
        and float(runtime_values[0]) > 0
        and (not execution_values or all(float(item) == 0 for item in execution_values)))
    return {"verified": verified,
        "fullCompileSeconds": float(compile_values[0]) if len(compile_values) == 1 else None,
        "fullInitialPlanningSeconds": int(initial_values[0]) / 1e9 if len(initial_values) == 1 else None,
        "runtimeProgramSeconds": float(runtime_values[0]) if len(runtime_values) == 1 else None,
        "executionSeconds": float(execution_values[0]) if len(execution_values) == 1 else None}


def summarize(output: Path, matrix: list[dict[str, Any]]) -> None:
    rows = []
    for value in matrix:
        path = output / "attempts" / safe_id(value) / "result.json"
        if path.is_file():
            rows.append(json.loads(path.read_text(encoding="utf-8")))
    atomic_json(output / "summary.json", {
        "schema": "systemds-memo-planning-matrix-summary/v1", "expectedCells": len(matrix),
        "attemptedCells": len(rows), "successfulCells": sum(row["status"] == "passed" for row in rows),
        "complete": len(rows) == len(matrix), "rows": rows,
    })
    fields = ("cellId", "suite", "workload", "workers", "executionPlanner",
              "rendererAuthorityPlanner", "status", "failureCategory",
              "memoPlanningSeconds", "fullCompileSeconds", "fullInitialPlanningSeconds", "harnessWallSeconds")
    temporary = output / "summary.csv.tmp"
    with temporary.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            metrics = row.get("timing", {})
            writer.writerow({"cellId": row["cell"]["id"], "suite": row["cell"]["suite"],
                "workload": row["cell"]["workload"], "workers": row["cell"]["workers"],
                "executionPlanner": row.get("executionPlanner"),
                "rendererAuthorityPlanner": row.get("rendererAuthorityPlanner"),
                "status": row["status"], "failureCategory": row.get("failureCategory"),
                "memoPlanningSeconds": metrics.get("memoPlanningSeconds"),
                "fullCompileSeconds": metrics.get("fullCompileSeconds"),
                "fullInitialPlanningSeconds": metrics.get("fullInitialPlanningSeconds"),
                "harnessWallSeconds": metrics.get("harnessWallSeconds")})
    os.replace(temporary, output / "summary.csv")


def safe_id(value: dict[str, Any]) -> str:
    return f"{value['suite']}-{value['workload']}-w{value['workers']}"


def parse_filters(args: argparse.Namespace) -> tuple[tuple[tuple[str, str], ...], tuple[int, ...]]:
    selected_workers = tuple(int(item) for item in args.workers.split(","))
    if not selected_workers or len(set(selected_workers)) != len(selected_workers) or any(
            item not in WORKERS for item in selected_workers):
        raise ValueError("--workers must be a unique comma list drawn from 1,3,5,7")
    if args.workloads == "all":
        selected_workloads = WORKLOADS
    else:
        tokens = args.workloads.split(",")
        if any(not SAFE_FILTER.fullmatch(item) for item in tokens):
            raise ValueError("unsafe workload filter")
        selected_workloads = tuple(tuple(item.split(":", 1)) for item in tokens)
        if not selected_workloads or len(set(selected_workloads)) != len(selected_workloads) or any(
                item not in WORKLOADS for item in selected_workloads):
            raise ValueError("--workloads contains an unsupported or duplicate suite:name")
    return selected_workloads, selected_workers


def run_cell(value: dict[str, Any], output: Path, engine: Path, stage: Path,
             renderer: Any, stats: Any, render_only: bool) -> dict[str, Any]:
    attempt = output / "attempts" / safe_id(value)
    attempt.mkdir(parents=True, exist_ok=False)
    rendered = renderer.render(value)
    comparison = renderer.render(cell(value["suite"], value["workload"], value["workers"], "DP-local"))
    source, adaptation = adapt_rendered(rendered, planner_neutral_source(comparison))
    config = configuration(value["suite"])
    (attempt / "cell.dml").write_text(source, encoding="utf-8")
    (attempt / "execution.xml").write_text(config, encoding="utf-8")
    atomic_json(attempt / "render-receipt.json", {key: item for key, item in rendered.items() if key != "source"})
    atomic_json(attempt / "adaptation-receipt.json", adaptation)
    (attempt / "container-run.sh").write_text(container_script(value["suite"] == "p2", bool(rendered.get("dependencies"))), encoding="utf-8")
    (attempt / "container-run.sh").chmod(0o755)
    name = "sysds-memo-plan-" + hashlib.sha256(value["id"].encode()).hexdigest()[:16]
    command = docker_command(attempt, engine, stage, rendered, name)
    atomic_json(attempt / "command.json", {"argv": command, "shell": shlex.join(command)})
    base = {"schema": "systemds-memo-planning-attempt/v1", "cell": execution_cell(value),
            "renderCell": value, "executionPlanner": "COMPILE_MEMO",
            "rendererAuthorityPlanner": "COMPILE_EXACT", "compileOnly": True,
            "p2PublicRecodeMetadataJvmProperty": value["suite"] == "p2",
            "costContract": validate_cost_contract(),
            "image": PINNED_IMAGE, "containerResources": {"cpus": 4, "memory": "8g",
                "coordinatorHeap": "3g", "workerProcesses": 0,
                "workerRationale": "sealed local sidecars satisfy planning metadata; compile_only performs no RPC"},
            "status": "rendered" if render_only else "failed", "failureCategory": None,
            "provenance": {"rendererPath": str(RENDERER_PATH), "rendererSha256": sha256_file(RENDERER_PATH),
                "stage": str(stage), "stageManifestSha256": rendered["content_verification"]["stage_manifest_sha256"],
                "engine": str(engine), "repositoryCommit": git_commit(),
                "scriptSha256": sha256_file(Path(__file__)),
                "dmlSha256": sha256_file(attempt / "cell.dml"),
                "configSha256": sha256_file(attempt / "execution.xml")}}
    if render_only:
        atomic_json(attempt / "result.json", base)
        return base
    started = time.monotonic()
    harness_timeout = False
    try:
        process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, check=False, timeout=335)
        returncode, harness_log = process.returncode, process.stdout
    except subprocess.TimeoutExpired as error:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=False)
        returncode = None
        harness_timeout = True
        captured = error.stdout or ""
        if isinstance(captured, bytes):
            captured = captured.decode("utf-8", errors="replace")
        harness_log = captured + "\nHARNESS_TIMEOUT\n"
    wall = time.monotonic() - started
    (attempt / "docker.log").write_text(harness_log, encoding="utf-8")
    coordinator = (attempt / "coordinator.log").read_text(encoding="utf-8", errors="replace") \
        if (attempt / "coordinator.log").is_file() else ""
    plans = stats.memo_planning_statistics(coordinator)
    searches = stats.memo_search_statistics(coordinator)
    construction = stats.memo_construction_statistics(coordinator)
    category = failure_category(returncode, attempt, plans, searches,
                                harness_timeout=harness_timeout)
    workers_match = bool(plans) and all(plan.get("workers") == value["workers"] for plan in plans)
    compile_metrics = compile_evidence(coordinator)
    compile_verified = compile_metrics.pop("verified")
    success = returncode == 0 and category is None and compile_verified and workers_match \
        and bool(searches) and all(row.get("status") == "OPTIMAL" for row in searches)
    if category is None and not success:
        category = "evidence_validation_failure"
    memo_seconds = sum(plan["totalSeconds"] for plan in plans) if plans else None
    result = {**base, "status": "passed" if success else "failed", "failureCategory": category,
        "returncode": returncode, "harnessTimeout": harness_timeout,
        "compileOnlyVerified": compile_verified,
        "reportedWorkerCountVerified": workers_match, "memoPlanning": plans,
        "memoSearch": searches, "memoConstruction": construction,
        "timing": {"memoPlanningSeconds": memo_seconds, **compile_metrics,
            "harnessWallSeconds": wall}}
    atomic_json(attempt / "result.json", result)
    return result


def git_commit() -> str | None:
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    return result.stdout.strip() if result.returncode == 0 else None


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--sealed-stage", required=True, type=Path)
    parser.add_argument("--workers", default="1,3,5,7")
    parser.add_argument("--workloads", default="all",
                        help="all or comma-separated suite:name values")
    parser.add_argument("--render-only", action="store_true",
                        help="validate/render and persist receipts without invoking Docker")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    engine, stage, output = args.engine.resolve(), args.sealed_stage.resolve(), args.output_root.resolve()
    stage_manifest = stage / "W1357_STAGE.json"
    if not stage_manifest.is_file() or sha256_file(stage_manifest) != PINNED_STAGE_MANIFEST_SHA256:
        raise ValueError("sealed stage manifest hash mismatch")
    engine_preflight = validate_engine(engine)
    cost_contract = validate_cost_contract()
    workloads, workers = parse_filters(args)
    matrix = cells(workloads, workers)
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": "systemds-memo-planning-matrix/v1",
        "renderCells": matrix, "executionCells": [execution_cell(value) for value in matrix],
        "executionPlanner": "COMPILE_MEMO", "rendererAuthorityPlanner": "COMPILE_EXACT",
        "image": PINNED_IMAGE, "engine": str(engine), "stage": str(stage),
        "enginePreflight": engine_preflight, "costContract": cost_contract,
        "rendererSha256": sha256_file(RENDERER_PATH), "runnerSha256": sha256_file(Path(__file__)),
        "stageManifestSha256": sha256_file(stage_manifest),
        "repositoryCommit": git_commit(), "renderOnly": args.render_only}
    manifest_path = output / "manifest.json"
    if manifest_path.exists():
        if json.loads(manifest_path.read_text(encoding="utf-8")) != manifest:
            raise ValueError("existing output manifest differs; refusing mixed resume")
    else:
        unexpected = [path for path in output.iterdir()]
        if unexpected:
            raise ValueError("new output root is not empty")
        atomic_json(manifest_path, manifest)
    render = renderer_module().Renderer(stage)
    stats = statistics_module()
    for value in matrix:
        result_path = output / "attempts" / safe_id(value) / "result.json"
        if result_path.is_file():
            prior = json.loads(result_path.read_text(encoding="utf-8"))
            if (prior.get("schema") != "systemds-memo-planning-attempt/v1"
                    or prior.get("renderCell") != value
                    or prior.get("cell") != execution_cell(value)):
                raise ValueError("resumed result schema/cell differs from expected")
            timing = prior.get("timing", {})
            print(f"MEMO_MATRIX cell={execution_cell(value)['id']} rendererCell={value['id']} "
                  f"status={prior.get('status')} resume=skip "
                  f"memoSeconds={timing.get('memoPlanningSeconds')} "
                  f"compileSeconds={timing.get('fullCompileSeconds')}", flush=True)
            continue
        # An interrupted directory is an attempted cell; close it explicitly rather than retrying.
        attempt = result_path.parent
        if attempt.exists():
            atomic_json(result_path, failed_attempt(value, "interrupted_attempt"))
        else:
            try:
                run_cell(value, output, engine, stage, render, stats, args.render_only)
            except Exception as error:
                attempt.mkdir(parents=True, exist_ok=True)
                atomic_json(result_path, failed_attempt(value, "setup_failure", error))
        summarize(output, matrix)
        current = json.loads(result_path.read_text(encoding="utf-8"))
        timing = current.get("timing", {})
        print(f"MEMO_MATRIX cell={execution_cell(value)['id']} rendererCell={value['id']} "
              f"status={current.get('status')} resume=no "
              f"memoSeconds={timing.get('memoPlanningSeconds')} "
              f"compileSeconds={timing.get('fullCompileSeconds')}", flush=True)
    summarize(output, matrix)
    summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    if args.render_only:
        return 0 if (summary["attemptedCells"] == summary["expectedCells"]
                     and all(row["status"] == "rendered" for row in summary["rows"])) else 1
    return 0 if summary["successfulCells"] == summary["expectedCells"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
