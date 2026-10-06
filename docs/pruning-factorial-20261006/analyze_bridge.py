#!/usr/bin/env python3
"""Validate the final, contemporaneous L/SL bridge after GLOBAL removal."""
import argparse
import csv
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import analyze as factorial

require = factorial.require


def analyze(root, factorial_root):
    manifest = factorial.receipt.load_json(root / "ablation.json")
    previous, previous_rows, previous_evidence = factorial.load_cohort(
        factorial_root, ("dominance_local", "support_local", "local"))
    identity = manifest["identity"]
    for key in factorial.SHARED_IDENTITY:
        require(identity[key] == previous["identity"][key], f"changed bridge configuration: {key}")
    sources, old_sources = identity["source_sha256"], previous["identity"]["source_sha256"]
    require(sources.keys() == old_sources.keys(), "production file set changed")
    changed = [p for p in sources if sources[p] != old_sources[p]]
    prefix = "src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/"
    require(set(changed) == {prefix + p for p in ("PruningAblation.java", "RegionalSearchProblem.java",
        "LocalPhysicalOptimizer.java")}, f"unexpected production changes: {changed}")
    imports = factorial.receipt.load_json(root / "frozen-harness/import-dependencies.json")
    require(set(imports) == {"matrix_runtime_compare.py", "matrix_lifecycle.py", "matrix_continuation.py"},
        "unexpected matrix import set")
    for name, record in imports.items():
        require(factorial.receipt.sha256(root / "frozen-harness" / name) == record["sha256"],
            f"matrix import changed: {name}")
    sys.path.insert(0, str(root / "frozen-harness"))
    spec = importlib.util.spec_from_file_location("bridge_runner", root / "frozen-harness/run_pruning_ablation.py")
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    matrix = runner.matrix
    require(matrix.sha(root / "frozen-harness/run_pruning_ablation.py") == identity["runner_sha256"], "runner changed")
    require(matrix.sha(root / "frozen-harness/run_matrix_campaign.py") == identity["matrix_runner_sha256"], "matrix runner changed")
    require(runner.tree_hashes(root / "overlay") == manifest["overlay_sha256"], "overlay changed")
    require(matrix.sha(root / "overlay/SystemDS.jar") == identity["jar_sha256"], "binary changed")
    require(set(runner.WORKLOADS) == set(factorial.WORKLOADS), "workload set changed")
    expected = runner.schedule(list(runner.WORKLOADS), ["local_only", "support_local"], 1, 3, "wan_mid")
    require(identity["samples"] == expected, "unexpected bridge sample matrix")
    for key, value in dict(full_production_compile=True, compile_only=True, network="none",
            workers_started=0, workload_execution=False, runtime_lowering_audit=True,
            concurrent_timed_samples=1, fresh_jvm=True, repetitions=1).items():
        require(manifest["measurement"].get(key) == value, f"invalid measurement: {key}")
    expected = {row["key"]: row for row in expected}
    attempts = sorted((root / "attempts").iterdir())
    require(len(attempts) == len(expected), "missing or extra attempts")
    references = {}
    for row in previous_rows:
        if row["variant"] == "support_local" and row["repetition"] == 1:
            references[row["cell"]["workload"]] = (row, factorial.receipt.load_json(
                factorial_root / row["attempt"] / "render-contract.json"))
    require(set(references) == set(factorial.WORKLOADS), "missing cross-cohort references")
    samples, provenance, intervals = {}, [], []
    for attempt in attempts:
        path = attempt / "result.json"
        row = factorial.receipt.load_json(path)
        key = row["key"]
        require(key in expected and key not in samples and all(row[k] == v for k, v in expected[key].items()),
            f"unplanned or duplicate sample: {key}")
        require(row["status"] == "passed" and not row["errors"] and row["returncode"] == 0
            and row["cleanup_resolved"], f"failed sample: {key}")
        log = (attempt / "coordinator.log").read_text()
        parsed = matrix.timing(log)
        probe = factorial.receipt.load_json(attempt / "tmp/receipt.json")
        require(probe == row["receipt"], f"probe/result mismatch: {key}")
        matrix.validate_probe_receipt(probe, row["cell"], "compile", parsed)
        require(runner.pruning_receipts(log, row["variant"], probe["candidateE2E"]["calls"]) == row["pruning"],
            f"pruning receipt mismatch: {key}")
        require(all(row[k] == v for k, v in parsed.items())
            and row["full_initial_planning_seconds"] == probe["planningFullInitialNanos"] / 1e9
            and row["optimizer_seconds"] == parsed["candidate_phases_ns"]["optimizerNanos"] / 1e9,
            f"timing mismatch: {key}")
        args = SimpleNamespace(root=root, stage=Path(identity["stage"]), cpus=identity["cpu"])
        _, command = runner.docker_command(args, manifest, row, attempt)
        require(command == factorial.receipt.load_json(attempt / "command.json"), f"command mismatch: {key}")
        container = factorial.receipt.load_json(attempt / "container.json")
        state, host, config = (container[k] for k in ("State", "HostConfig", "Config"))
        require(state["ExitCode"] == 0 and not state["OOMKilled"] and not state["Running"], f"container failed: {key}")
        require(container["Image"] == identity["image"]["image_id"]
            and host["CpusetCpus"] == identity["cpu"] and host["Memory"] == 24 * 1024**3
            and host["NetworkMode"] == "none" and host["Tmpfs"] == {"/tmp": "rw,size=4g"}, f"container config mismatch: {key}")
        require(config["Cmd"] == command[command.index(matrix.IMAGE) + 1:], f"JVM config mismatch: {key}")
        environment = dict(v.split("=", 1) for v in config["Env"])
        require(all(environment[k] == str(v) for k, v in identity["cost_environment"].items()), f"cost environment mismatch: {key}")
        intervals.append((state["StartedAt"], state["FinishedAt"]))
        contract = factorial.receipt.load_json(attempt / "render-contract.json")
        reference, old_contract = references[row["cell"]["workload"]]
        require(contract["cell"] == row["cell"] and contract["source"] == (attempt / "tmp/cell.dml").read_text()
            and all(contract[k] == old_contract[k] for k in ("source", "inputs", "dependencies")), f"workload changed: {key}")
        quality = factorial.same_cost_plan(row, reference)
        require(all(quality[k] for k in ("objective_equal", "plan_equal", "stop_equal")), f"cross-cohort plan/cost changed: {key}")
        samples[key] = row
        provenance.append(dict(key=key, result=str(path), sha256=matrix.sha(path)))
    intervals.sort()
    require(all(a[1] <= b[0] for a, b in zip(intervals, intervals[1:])), "overlapping timed containers")
    pairs = []
    for workload in factorial.WORKLOADS:
        l, sl = (next(r for r in samples.values() if r["cell"]["workload"] == workload and r["variant"] == variant)
            for variant in ("local_only", "support_local"))
        entry = dict(workload=workload, **factorial.same_cost_plan(sl, l))
        for metric in factorial.TIMINGS:
            entry[metric + "_L"] = l[metric]
            entry[metric + "_SL"] = sl[metric]
            entry[metric + "_ratio"] = sl[metric] / l[metric]
        for field in ("reducedValues", "reducedCells", "childEvaluations"):
            entry[field + "_L"] = l["pruning"][0][field]
            entry[field + "_SL"] = sl["pruning"][0][field]
        pairs.append(entry)
    effects = {}
    for name, subset in (("primary_ML9", [p for p in pairs if p["workload"] != "steplm"]),
            ("STEP_compatible", [p for p in pairs if p["workload"] == "steplm"])):
        effects[name] = dict(n=len(subset))
        for metric in factorial.TIMINGS:
            ratio = factorial.receipt.geometric_mean([p[metric + "_ratio"] for p in subset])
            effects[name][metric + "_change_pct"] = 100 * (ratio - 1)
            effects[name][metric + "_improved"] = sum(p[metric + "_ratio"] < 1 for p in subset)
    return dict(passed=len(samples), pairs=pairs, effects=effects, provenance=provenance,
        manifest_sha256=matrix.sha(root / "ablation.json"), jar_sha256=identity["jar_sha256"],
        previous_manifest_sha256=previous_evidence["manifest_sha256"], production_source_changes=changed,
        frozen_import_dependencies=imports,
        container_overlap=False, comparison="SL/L; same cohort, GLOBAL removed, one repetition",
        limitation="One contemporaneous cold-JVM repetition on a shared host; no reliable full-planning benefit or significance claim.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--factorial-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = analyze(args.root.resolve(), args.factorial_root.resolve())
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "bridge-analysis.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    with (args.output / "bridge-pairs.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(report["pairs"][0]))
        writer.writeheader()
        writer.writerows(report["pairs"])
    print(json.dumps(report["effects"], indent=2))


if __name__ == "__main__":
    main()
