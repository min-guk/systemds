#!/usr/bin/env python3
"""Paired ML10 pruning ablation: fresh, network-isolated Docker full compilation.

Reuse the sealed campaign renderer, cost profile, configuration, production probe
and receipt validation. No workers or workload instructions execute. The network
profile is a frozen cost-model input, not a network measurement in this experiment.
"""
from __future__ import annotations

import argparse
import csv
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import struct
import subprocess
import time
import types
import uuid

import run_matrix_campaign as matrix

VARIANTS = ("baseline", "local_only")
WORKLOADS = tuple(w for suite, w in matrix.WORKLOADS if suite == "ml")
PROPERTY = "sysds.fedplanner.pruning.ablation"
MEASURES = ("full_initial_planning_seconds", "compile_seconds", "optimizer_seconds",
            "analysis_seconds", "selection_adapter_seconds")
COUNTERS = ("rawValues", "rawCells", "reducedValues", "reducedCells", "fullChildEvaluations",
            "childEvaluations", "infeasibleCuts", "costCuts", "assignments", "retainedSlots", "merges")


def tree_hashes(root):
    return {str(p.relative_to(root)): matrix.sha(p) for p in root.rglob("*") if p.is_file()}


def schedule(workloads, variants, repetitions, workers, profile, first_repetition=1):
    """Rotate variant order across paired blocks; each sample has a fresh JVM."""
    rows = []
    for repetition in range(first_repetition, first_repetition + repetitions):
        for workload in workloads:
            index = WORKLOADS.index(workload)
            offset = (index + repetition - 1) % len(variants)
            for variant in variants[offset:] + variants[:offset]:
                cell = dict(id=f"ml|{workload}|{profile}|w{workers}|DP-local", suite="ml",
                            workload=workload, profile=profile, workers=workers,
                            planner="DP-local", planner_enum=matrix.ENUMS["DP-local"])
                rows.append(dict(cell=cell, variant=variant, repetition=repetition,
                                 key=f"{cell['id']}|{variant}|r{repetition}"))
    return rows


def image_identity():
    record = json.loads(matrix.run(["docker", "image", "inspect", matrix.IMAGE]))[0]
    identity = {key: record.get(key) for key in ("Architecture", "Os", "Created", "Config")}
    identity["RootFSLayers"] = record.get("RootFS", {}).get("Layers")
    digest = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":"),
                                      ensure_ascii=True).encode()).hexdigest()
    if matrix.IMAGE != "cofee-experiment:content-" + digest:
        raise RuntimeError("Docker image content differs from pinned tag")
    return {"image_id": record["Id"], "content_sha256": digest}


def prepare(args, base, renderer_source):
    root = args.root
    jar = matrix.REPO / "target/systemds-3.4.0-SNAPSHOT.jar"
    sources = sorted(p for p in (matrix.REPO / "src/main").rglob("*") if p.is_file())
    sources.append(matrix.REPO / "pom.xml")
    if not jar.is_file() or jar.stat().st_mtime_ns < max(p.stat().st_mtime_ns for p in sources):
        raise RuntimeError("Production JAR missing/stale; build current source first")
    profile = base._cost_profiles().load_profile(args.cost_profile)
    # Verify the profile's content and the modeled topology, not the host on which
    # this isolated compiler microbenchmark happens to execute.
    binding = base.network_cost_binding(args.profile, base.load_topology().select(args.workers),
                                        matrix.IMAGE, cost_profile=profile)
    seal = json.loads((args.stage / "W1357_STAGE.json").read_text())
    dependency_prefix = "systemds/target/lib/"
    dependencies = tree_hashes(args.stage / "systemds/target/lib")
    expected_dependencies = {k.removeprefix(dependency_prefix): v for k, v in seal["input_files"].items()
                             if k.startswith(dependency_prefix)}
    if not dependencies or dependencies != expected_dependencies:
        raise RuntimeError("Mounted dependency JARs differ from stage seal")
    rows = schedule(args.workloads, args.variants, args.repetitions, args.workers, args.profile,
                    args.first_repetition)
    identity = dict(jar_sha256=matrix.sha(jar), image=image_identity(),
                    source_sha256={str(p.relative_to(matrix.REPO)): matrix.sha(p) for p in sources},
                    probe_sha256=matrix.sha(matrix.PROBE_SOURCE),
                    runner_sha256=matrix.sha(Path(__file__)),
                    matrix_runner_sha256=matrix.sha(Path(matrix.__file__)),
                    renderer_sha256=hashlib.sha256(renderer_source).hexdigest(),
                    renderer_commit=args.renderer_commit,
                    evaluation_root=str(matrix.EVALUATION),
                    evaluation_sha256=tree_hashes(matrix.EVALUATION),
                    stage=str(args.stage), stage_sha256=matrix.sha(args.stage / "W1357_STAGE.json"),
                    dependencies_sha256=dependencies,
                    cost_profile_sha256=binding["profile_sha256"],
                    cost_profile_file_sha256=matrix.sha(args.cost_profile),
                    cost_environment=binding["cost_environment"],
                    host=platform.node(), cpu=args.cpus, memory="24g", java=list(matrix.JAVA),
                    optimizer_time_millis=0, samples=rows)
    manifest_path = root / "ablation.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest["identity"] != identity:
            raise RuntimeError("Ablation identity changed; use a new root")
        if tree_hashes(root / "overlay") != manifest["overlay_sha256"]:
            raise RuntimeError("Frozen overlay changed")
        return manifest
    root.mkdir(parents=True, exist_ok=True)
    classes = root / "overlay/probe/classes"
    classes.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(jar, root / "overlay/SystemDS.jar")
    (root / "probe-javac.log").write_text(matrix.run([
        "javac", "-encoding", "UTF-8", "-cp", f"{jar}:{matrix.REPO}/target/lib/*",
        "-d", str(classes), str(matrix.PROBE_SOURCE)], timeout=None))
    shutil.copyfile(args.cost_profile, root / "cost-profile.json")
    (root / "renderer-source.py").write_bytes(renderer_source)
    manifest = dict(schema="ml10-pruning-ablation/v1", identity=identity,
                    head=matrix.run(["git", "-C", str(matrix.REPO), "rev-parse", "HEAD"]).strip(),
                    git_status=matrix.run(["git", "-C", str(matrix.REPO), "status", "--short"]),
                    study_id=uuid.uuid4().hex[:16],
                    measurement=dict(full_production_compile=True, compile_only=True,
                        network="none", workers_started=0, workload_execution=False,
                        runtime_lowering_audit=True, concurrent_timed_samples=1,
                        fresh_jvm=True, warmups=0, cold_jvm_latency=True,
                        repetitions=args.repetitions, timeout_seconds=None,
                        timer_policy="wall-time optimizer stop disabled; gap and resource caps unchanged"),
                    overlay_sha256=tree_hashes(root / "overlay"))
    matrix.dump(manifest_path, manifest)
    return manifest


def docker_command(args, manifest, row, attempt):
    cell = row["cell"]
    env = dict(manifest["identity"]["cost_environment"])
    env.update(OMP_DYNAMIC="FALSE", MKL_DYNAMIC="FALSE", OMP_NUM_THREADS="8",
               MKL_NUM_THREADS="8", OPENBLAS_NUM_THREADS="8", BENCHMARK_COMPILE_ONLY="1",
               COFEE_COST_PROFILE_MODE="frozen-ablation-input",
               COFEE_COST_PROFILE_SHA256=manifest["identity"]["cost_profile_sha256"])
    name = "pruning-ablation-" + manifest["study_id"] + "-" + attempt.name
    command = ["docker", "run", "--name", name, "--init", "--network", "none",
               "--label", "cofee.pruning.study=" + manifest["study_id"],
               "--user", f"{os.getuid()}:{os.getgid()}", "--cpuset-cpus", args.cpus,
               "--memory", "24g", "--workdir", "/workspace/experiments",
               "--tmpfs", "/tmp:rw,size=4g", "--stop-timeout", "60"]
    mounts = ((args.stage / "harness/sigmod2021-exdra-p523", "/workspace", True),
              (args.stage / "data", "/workspace/experiments/data", True),
              (args.stage / "references", "/workspace/experiments/references", True),
              (args.stage / "systemds", "/opt/systemds", True),
              (args.root / "overlay", "/candidate", True),
              (attempt / "tmp", "/workspace/experiments/tmp", False),
              (attempt / "results", "/workspace/experiments/results", False))
    for source, target, readonly in mounts:
        command += ["--mount", f"type=bind,src={source},dst={target}" + (",readonly" if readonly else "")]
    for key, value in sorted(env.items()):
        command += ["--env", f"{key}={value}"]
    java = matrix.coordinator_java(cell, "compile")
    # Insert before the classpath/main class. Every variant has identical
    # accounting and resource/gap limits; only the named pruning switches vary.
    java[1:1] = [f"-D{PROPERTY}={row['variant']}",
                 "-Dsysds.fedplanner.regional.incremental.timeMillis=0"]
    return name, command + [matrix.IMAGE, *java]


def pruning_receipts(log, variant, expected_calls=1):
    rows = [json.loads(line) for line in re.findall(r"^DP-PruningAblationReceipt (.+)$", log, re.M)]
    if len(rows) != expected_calls or any(row.get("variant") != variant for row in rows):
        raise ValueError("Missing/mismatched pruning ablation receipt")
    for row in rows:
        for field in COUNTERS:
            if type(row.get(field)) is not int or row[field] < 0:
                raise ValueError("Invalid pruning counter: " + field)
        if not re.fullmatch(r"[0-9a-f]{64}", row.get("planFingerprint", "")):
            raise ValueError("Missing pruning plan fingerprint")
        if not row.get("stop"):
            raise ValueError("Missing pruning outcome")
        for field in ("objectiveBits", "initialUpperBits"):
            value = int(row[field])
            if not 0 <= value < 2**64:
                raise ValueError("Invalid objective bits")
        objective = struct.unpack("!d", int(row["objectiveBits"]).to_bytes(8, "big"))[0]
        lower, upper, gap = (float(row[field]) for field in ("lower", "upper", "gap"))
        if (not all(math.isfinite(v) and v >= 0 for v in (lower, upper, objective))
                or math.isnan(gap) or gap < 0 or upper != objective or lower > upper):
            raise ValueError("Invalid pruning cost/bound evidence")
        if row["childEvaluations"] > row["fullChildEvaluations"]:
            raise ValueError("Invalid pruning work accounting")
    return rows


def execute(args, manifest, row, renderer):
    attempt = args.root / "attempts" / (f"{time.time_ns():020d}-" + uuid.uuid4().hex[:6])
    for subdir in ("tmp", "results"):
        (attempt / subdir).mkdir(parents=True)
    result = dict(row, status="failed", errors=[], attempt=str(attempt.relative_to(args.root)))
    name = None
    launched = False
    print(json.dumps(dict(event="start", key=row["key"], attempt=result["attempt"])), flush=True)
    try:
        rendered = renderer.render(row["cell"])
        matrix.dump(attempt / "render-contract.json", rendered)
        (attempt / "tmp/cell.dml").write_text(rendered["source"])
        (attempt / "tmp/execution.xml").write_text(matrix.config(row["cell"], "compile"))
        name, command = docker_command(args, manifest, row, attempt)
        matrix.dump(attempt / "command.json", command)
        with (attempt / "coordinator.log").open("x") as output:
            started = time.monotonic()
            launched = True
            completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=None)
        result["process_seconds"] = time.monotonic() - started
        result["returncode"] = completed.returncode
        health = json.loads(matrix.run(["docker", "inspect", name]))[0]
        matrix.dump(attempt / "container.json", health)
        if health["State"]["OOMKilled"]:
            raise RuntimeError("Container OOM")
        receipt_path = attempt / "tmp/receipt.json"
        receipt = json.loads(receipt_path.read_text()) if receipt_path.exists() else None
        result["receipt"] = receipt
        if completed.returncode or not receipt or receipt.get("status") != "success":
            raise RuntimeError(f"Full compilation failed, rc={completed.returncode}")
        log = (attempt / "coordinator.log").read_text()
        parsed = matrix.timing(log)
        matrix.validate_probe_receipt(receipt, row["cell"], "compile", parsed)
        result.update(parsed)
        result["full_initial_planning_seconds"] = receipt["planningFullInitialNanos"] / 1e9
        result["optimizer_seconds"] = parsed["candidate_phases_ns"]["optimizerNanos"] / 1e9
        result["pruning"] = pruning_receipts(log, row["variant"], receipt["candidateE2E"]["calls"])
        result["status"] = "passed"
    except Exception as error:
        result["errors"].append(str(error))
    finally:
        # This unique name is created by this attempt, never a shared worker.
        removed = subprocess.run(["docker", "rm", name], capture_output=True, text=True) if launched else None
        result["cleanup_resolved"] = removed is None or removed.returncode == 0
        if not result["cleanup_resolved"]:
            result["errors"].append("Container cleanup failed: " + removed.stderr[-1000:])
            result["status"] = "failed"
        matrix.dump(attempt / "result.json", result)
    print(json.dumps({k: result.get(k) for k in ("key", "status", *MEASURES, "errors")}), flush=True)
    return result


def summarize(root, manifest):
    rows = []
    planned = {row["key"]: row for row in manifest["identity"]["samples"]}
    seen = set()
    for attempt in sorted((root / "attempts").glob("*")):
        path = attempt / "result.json"
        if not path.is_file():
            raise RuntimeError("Incomplete attempt requires recovery before resume: " + str(attempt))
        row = json.loads(path.read_text())
        key = row["key"]
        if key in seen or key not in planned or any(row.get(k) != v for k, v in planned[key].items()):
            raise RuntimeError("Duplicate/unplanned/mismatched sample: " + key)
        seen.add(key)
        rows.append(row)
    groups = {}
    for row in rows:
        groups.setdefault((row["cell"]["workload"], row["variant"]), []).append(row)
    aggregate = []
    for (workload, variant), samples in groups.items():
        passed = [r for r in samples if r["status"] == "passed"]
        item = dict(workload=workload, variant=variant, passed=len(passed), failed=len(samples)-len(passed))
        for metric in MEASURES:
            values = [r[metric] for r in passed]
            if values:
                item[metric] = dict(median=statistics.median(values), min=min(values), max=max(values))
        item["pruning_receipts"] = [r["pruning"] for r in passed]
        aggregate.append(item)
    summary = dict(planned=len(manifest["identity"]["samples"]), attempted=len(rows),
                   passed=sum(r["status"] == "passed" for r in rows),
                   failed=sum(r["status"] != "passed" for r in rows), results=aggregate)
    pairs = []
    declared = {row["variant"] for row in planned.values()}
    reference_variant = next((variant for variant in VARIANTS
        if variant in declared), None)
    references = {(r["cell"]["id"], r["repetition"]): r for r in rows
                  if r["variant"] == reference_variant and r["status"] == "passed"}
    for row in rows:
        reference = references.get((row["cell"]["id"], row["repetition"]))
        if row["status"] != "passed" or reference is None or row["variant"] == reference_variant:
            continue
        compare = lambda field: [v[field] for v in row["pruning"]] == [v[field] for v in reference["pruning"]]
        pairs.append(dict(key=row["key"], reference_variant=reference_variant, objective_equal=compare("objectiveBits"),
                          plan_equal=compare("planFingerprint"), stop_equal=compare("stop"),
                          bounds_equal=compare("lower") and compare("upper"),
                          planning_speedup=reference["full_initial_planning_seconds"] / row["full_initial_planning_seconds"]))
    summary["reference_variant"] = reference_variant
    summary["paired_semantics"] = pairs
    matrix.dump(root / "summary.json", summary)
    columns = ["workload", "variant", "repetition", "status", *MEASURES, "attempt", "errors"]
    with (root / "samples.csv").open("w") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row["cell"]["workload"] if k == "workload" else row.get(k, "") for k in columns})
    return rows, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--cost-profile", type=Path, required=True)
    parser.add_argument("--stage", type=Path, default=matrix.STAGE)
    parser.add_argument("--workloads", nargs="+", choices=WORKLOADS, default=list(WORKLOADS))
    parser.add_argument("--variants", nargs="+", choices=VARIANTS, default=list(VARIANTS))
    parser.add_argument("--workers", type=int, choices=matrix.WORKERS, default=3)
    parser.add_argument("--profile", choices=matrix.PROFILES, default="wan_mid")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--first-repetition", type=int, default=1,
                        help="First repetition number, preserving canonical variant rotation during recovery")
    parser.add_argument("--cpus", default="8-15")
    parser.add_argument("--evaluation-root", type=Path, default=matrix.EVALUATION,
                        help="Private snapshot of the campaign, driver, calibration and config dependencies")
    renderer_options = parser.add_mutually_exclusive_group()
    renderer_options.add_argument("--renderer-commit", help="Exact Git revision for a matching legacy sealed stage")
    renderer_options.add_argument("--renderer-file", type=Path, help="Frozen renderer source for a matching sealed stage")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args(argv)
    if (min(args.repetitions, args.first_repetition) < 1 or len(set(args.variants)) != len(args.variants)
            or len(set(args.workloads)) != len(args.workloads)):
        parser.error("Positive repetitions and unique workloads/variants required")
    if args.renderer_commit is not None and not re.fullmatch(r"[0-9a-f]{40}", args.renderer_commit):
        parser.error("renderer-commit must be an exact 40-hex Git revision")
    args.root = args.root.resolve()
    matrix.EVALUATION = args.evaluation_root.resolve()
    _, base, renderer_module = matrix.dependencies()
    renderer_path = matrix.EVALUATION / "campaign/render_w1357_workload.py"
    if args.renderer_commit or args.renderer_file:
        renderer_source = (args.renderer_file.read_bytes() if args.renderer_file else
            subprocess.check_output(["git", "-C", str(matrix.EVALUATION), "show",
                args.renderer_commit + ":campaign/render_w1357_workload.py"]))
        renderer_module = types.ModuleType("pruning_frozen_renderer")
        renderer_module.__file__ = str(renderer_path)
        exec(compile(renderer_source, str(renderer_path), "exec"), renderer_module.__dict__)
    else:
        renderer_source = renderer_path.read_bytes()
    manifest = prepare(args, base, renderer_source)
    if args.prepare_only:
        print(json.dumps(dict(prepared=True, root=str(args.root), samples=len(manifest["identity"]["samples"]))))
        return 0
    # Independent compile lane: no shared runtime worker, port or netem state.
    lock = Path.home() / ".cache" / f"pruning-compile-{os.getuid()}-{args.cpus}.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("a+") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        rows, summary = summarize(args.root, manifest)
        leftovers = matrix.run(["docker", "ps", "-a", "--filter",
                                "label=cofee.pruning.study=" + manifest["study_id"], "--format", "{{.Names}}"])
        if leftovers.strip():
            raise RuntimeError("Unreconciled study containers: " + leftovers.strip())
        done = {row["key"] for row in rows}
        if any(not row.get("cleanup_resolved") for row in rows):
            raise RuntimeError("Unresolved previous container cleanup")
        renderer = renderer_module.Renderer(args.stage)
        for row in manifest["identity"]["samples"]:
            if row["key"] in done:
                continue
            result = execute(args, manifest, row, renderer)
            _, summary = summarize(args.root, manifest)
            if not result["cleanup_resolved"]:
                return 2
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
