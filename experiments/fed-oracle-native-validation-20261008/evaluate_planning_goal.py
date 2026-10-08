#!/usr/bin/env python3
"""Fail-closed acceptance gate for the COFEE 50Kx128 20-second planning goal."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re

TARGET_NANOS = 20_000_000_000
RUNS_PER_WORKLOAD = 3
WORKLOAD_CELLS = {
    "logreg": "ml|logreg|lan|w1|DP-local",
    "glm": "ml|glm|lan|w1|DP-local",
}
EXPECTED_CONTAINER = {"cpuset_cpus": "0-7", "memory": "24g", "tmpfs_size": "4g",
                      "user": "10041:5500", "working_dir": "/workspace/experiments"}
EXPECTED_JVM = ("-Xms16g", "-Xmx16g", "-Xmn1600m", "-XX:ActiveProcessorCount=8")
TERMINAL_ANALYSIS = re.compile(
    r"SEARCH_SPACE_LIVE\|[^\n]*terminal=true\|LivePhase\[phase=ANALYSIS, completedCalls=1,")


def _report_module():
    path = Path(__file__).with_name("cofee_large_report.py")
    spec = importlib.util.spec_from_file_location("cofee_large_report", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


REPORT = _report_module()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path):
    try:
        return json.loads(path.read_text()), None
    except (OSError, json.JSONDecodeError) as error:
        return None, f"cannot read {path}: {error}"


def _attempts_from_report(report: dict, origin: str) -> tuple[list[dict], list[str]]:
    attempts, errors = [], []
    pairs = report.get("pairs")
    if not isinstance(pairs, list):
        return [], [f"{origin}: report has no pairs array"]
    for pair in pairs:
        variants = pair.get("variants") if isinstance(pair, dict) else None
        if not isinstance(variants, dict):
            errors.append(f"{origin}: pair has no variants object")
            continue
        for name, variant in variants.items():
            if not isinstance(variant, dict):
                errors.append(f"{origin}: variant {name} is malformed")
                continue
            all_attempts = variant.get("allAttempts")
            if not isinstance(all_attempts, list):
                errors.append(f"{origin}: variant {name} has no authoritative allAttempts")
                continue
            attempts.extend(all_attempts)
    return attempts, errors


def _campaign_report(root: Path) -> tuple[dict | None, str | None]:
    if (root / "runtime-selection.json").is_file():
        selected, error = REPORT._selected_ids(root)
        if error or not selected:
            return None, error or "campaign root has no selected cell"
        cell = next(iter(selected))
        parts = cell.split("|")
        workload = parts[1] if len(parts) > 1 else "unknown"
        variant = REPORT._variant(root, "evidence", workload, 0)
        return {"pairs": [{"variants": {"evidence": variant}}]}, None
    try:
        return REPORT.build_report(root), None
    except (OSError, ValueError) as error:
        return None, f"cannot build COFEE report for {root}: {error}"


def load_evidence(manifest: dict, base: Path) -> tuple[list[dict], list[str]]:
    entries = manifest.get("evidence")
    if not isinstance(entries, list):
        return [], ["evidence must be an array"]
    attempts, errors = [], []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict) or set(entry) not in ({"campaignRoot"}, {"report"}):
            errors.append(f"evidence[{index}] must contain exactly campaignRoot or report")
            continue
        key = next(iter(entry))
        raw = entry[key]
        if not isinstance(raw, str) or not raw:
            errors.append(f"evidence[{index}].{key} must be a non-empty path")
            continue
        path = Path(raw)
        if not path.is_absolute():
            path = (base / path).resolve()
        if key == "report":
            report, error = read_json(path)
        else:
            report, error = _campaign_report(path)
        if error or not isinstance(report, dict):
            errors.append(error or f"{path}: report is malformed")
            continue
        rows, row_errors = _attempts_from_report(report, str(path))
        attempts.extend(rows)
        errors.extend(row_errors)
    return attempts, errors


def _artifact(path: Path, name: str, reasons: list[str]):
    value, error = read_json(path / name)
    if error or not isinstance(value, dict):
        reasons.append(error or f"invalid {name}")
        return {}
    return value


def _input_contract(render: dict) -> str:
    """Hash stable workload inputs while excluding per-attempt output paths."""
    inputs = []
    for item in render.get("inputs", []):
        if isinstance(item, dict):
            shards = []
            for shard in item.get("shards", []):
                if isinstance(shard, dict):
                    shards.append({
                        "metadataSha256": shard.get("metadata_sha256"),
                        "range": shard.get("range"),
                        "stagePath": shard.get("stage_path"),
                        "blocks": [{key: block.get(key) for key in ("path", "bytes", "sha256")}
                                   for block in shard.get("blocks", []) if isinstance(block, dict)],
                    })
            inputs.append({**{key: item.get(key) for key in (
                "role", "rows", "cols", "nnz", "privacy", "metadata_sha256")},
                "shards": shards})
    stable = {
        "templateSha256": (render.get("template") or {}).get("sha256")
            if isinstance(render.get("template"), dict) else render.get("template_sha256"),
        "inputs": sorted(inputs, key=lambda row: str(row.get("role"))),
        "contentVerification": render.get("content_verification"),
        "topology": render.get("topology"),
        "seeds": render.get("seeds"),
    }
    return hashlib.sha256(json.dumps(stable, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _data_contract(render: dict) -> str:
    stable = json.loads(json.dumps({"inputs": []}))
    for item in render.get("inputs", []):
        if isinstance(item, dict):
            stable["inputs"].append({
                "role": item.get("role"), "rows": item.get("rows"), "cols": item.get("cols"),
                "nnz": item.get("nnz"), "privacy": item.get("privacy"),
                "metadataSha256": item.get("metadata_sha256"),
                "blockSha256": [block.get("sha256") for shard in item.get("shards", [])
                                 if isinstance(shard, dict) for block in shard.get("blocks", [])
                                 if isinstance(block, dict)],
            })
    stable["inputs"].sort(key=lambda row: str(row.get("role")))
    return hashlib.sha256(json.dumps(stable, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def evaluate_attempt(summary: dict) -> dict:
    reasons = []
    if not summary.get("eligibleSuccess"):
        reasons.extend(summary.get("exclusionReasons") or ["COFEE authoritative classifier rejected attempt"])
    result_path = Path(summary.get("path", ""))
    attempt = result_path.parent
    result = _artifact(attempt, "result.json", reasons)
    if result:
        classified = REPORT._summarize_attempt(result_path, result)
        if not classified.get("eligibleSuccess"):
            reasons.extend(classified.get("exclusionReasons")
                           or ["live COFEE authoritative classifier rejected attempt"])
    receipt = result.get("receipt") if isinstance(result.get("receipt"), dict) else {}
    render = _artifact(attempt, "render-contract.json", reasons)
    lifecycle = _artifact(attempt, "lifecycle.json", reasons)
    profile = _artifact(attempt, "cost-profile.json", reasons)
    cell = result.get("cell") if isinstance(result.get("cell"), dict) else {}
    workload = cell.get("workload")
    if workload not in WORKLOAD_CELLS or cell.get("id") != WORKLOAD_CELLS.get(workload):
        reasons.append("attempt is not canonical LAN W1 DP-local LogReg/GLM")
    full = receipt.get("planningFullInitialNanos")
    if type(full) is not int or full <= 0:
        reasons.append("positive receipt planningFullInitialNanos is absent")
    elif full > TARGET_NANOS:
        reasons.append("full initial planning exceeds 20 seconds")
    seconds = result.get("full_initial_planning_seconds")
    if (type(seconds) not in (int, float) or not math.isfinite(seconds)
            or type(full) is not int or abs(seconds - full / 1e9) > 1e-9):
        reasons.append("result and receipt full initial planning timers disagree")
    if any(receipt.get(key) is True for key in ("analysisCacheHit", "cachedAnalysis", "analysisReused")):
        reasons.append("cached analysis is forbidden")
    log = attempt / "coordinator.log"
    try:
        if not TERMINAL_ANALYSIS.search(log.read_text(errors="replace")):
            reasons.append("fresh terminal ANALYSIS evidence is absent")
    except OSError as error:
        reasons.append(f"cannot read coordinator.log: {error}")
    inputs = {item.get("role"): item for item in render.get("inputs", []) if isinstance(item, dict)}
    x, y = inputs.get("X", {}), inputs.get("Y", {})
    if (x.get("rows"), x.get("cols"), x.get("nnz"), x.get("privacy")) != (50000, 128, 6400000, "private-aggregate"):
        reasons.append("X is not canonical 50Kx128 PRIVATE_AGGREGATE input")
    if (y.get("rows"), y.get("cols"), y.get("privacy")) != (50000, 1, "public"):
        reasons.append("Y is not canonical 50Kx1 PUBLIC input")
    for role, value in (("X", x), ("Y", y)):
        shards = value.get("shards")
        blocks = [block for shard in shards or [] if isinstance(shard, dict)
                  for block in shard.get("blocks", []) if isinstance(block, dict)]
        if not value.get("metadata_sha256") or not shards or not blocks \
                or any(not block.get("sha256") for block in blocks):
            reasons.append(f"{role} pinned metadata/block hashes are absent")
    if render.get("topology", {}).get("workers") != ["so006"]:
        reasons.append("render topology is not the pinned W1 worker")
    seeds = render.get("seeds")
    if not isinstance(seeds, dict) or seeds.get("SYSTEMDS_SEED") != 1011081480:
        reasons.append("canonical seed evidence is absent")
    if lifecycle.get("container") != EXPECTED_CONTAINER:
        reasons.append("container CPU/memory contract changed")
    coordinator = lifecycle.get("coordinator") if isinstance(lifecycle.get("coordinator"), dict) else {}
    environment = coordinator.get("environment") if isinstance(coordinator.get("environment"), dict) else {}
    options = environment.get("SYSTEMDS_STANDALONE_OPTS", "")
    if any(option not in options.split() for option in EXPECTED_JVM):
        reasons.append("JVM heap/CPU contract changed")
    if environment.get("privacyConstraints") != "private-aggregate" or environment.get("DOCKER_NUM_WORKERS") != "1":
        reasons.append("runtime privacy or worker-count contract changed")
    comparison = result.get("comparison") if isinstance(result.get("comparison"), dict) else {}
    comparison_receipt = comparison.get("receipt") if isinstance(comparison.get("receipt"), dict) else {}
    if comparison_receipt.get("passed") is not True:
        reasons.append("numerical reference comparison did not pass")
    template_sha = (render.get("template") or {}).get("sha256") \
        if isinstance(render.get("template"), dict) else render.get("template_sha256")
    if not template_sha:
        reasons.append("canonical DML template SHA256 is absent")
    if not result.get("jar_sha256"):
        reasons.append("frozen engine JAR SHA256 is absent")
    if not profile.get("profile_sha256") or not isinstance(profile.get("_runtime"), dict) \
            or not profile["_runtime"].get("source_sha256"):
        reasons.append("pinned cost-profile identity/source hashes are absent")
    return {
        "path": str(result_path), "workload": workload, "jarSha256": result.get("jar_sha256"),
        "planningFullInitialNanos": full if type(full) is int else None,
        "profileId": profile.get("profile_sha256"),
        "profileSourceSha256": (profile.get("_runtime") or {}).get("source_sha256")
            if isinstance(profile.get("_runtime"), dict) else None,
        "scriptSha256": receipt.get("scriptSha256"),
        "templateSha256": template_sha,
        "inputContractSha256": _input_contract(render),
        "dataContractSha256": _data_contract(render),
        "jvmOptions": options, "container": lifecycle.get("container"), "seeds": seeds,
        "eligible": not reasons, "reasons": reasons,
    }


def _regression(manifest: dict, base: Path) -> tuple[dict, list[str]]:
    spec = manifest.get("regressionReport")
    if not isinstance(spec, dict) or set(spec) != {"path", "sha256"}:
        return {}, ["regressionReport must contain exactly path and sha256"]
    path = Path(spec["path"])
    if not path.is_absolute():
        path = (base / path).resolve()
    value, error = read_json(path)
    reasons = [error] if error else []
    actual = sha256(path) if path.is_file() else None
    if actual != spec.get("sha256"):
        reasons.append("regression report SHA256 mismatch")
    if not isinstance(value, dict) or value.get("status") != "passed":
        reasons.append("latest regression report did not pass")
    if isinstance(value, dict):
        for field in ("sourceHashMismatches", "frozenClassHashMismatches"):
            if value.get(field, 0) != 0:
                reasons.append(f"regression report has nonzero {field}")
    return {"path": str(path), "expectedSha256": spec.get("sha256"), "actualSha256": actual,
            "status": value.get("status") if isinstance(value, dict) else None,
            "jarSha256": value.get("jarSha256") if isinstance(value, dict) else None}, reasons


def evaluate(path: Path) -> dict:
    manifest, error = read_json(path)
    errors = [error] if error else []
    if not isinstance(manifest, dict) or manifest.get("schema") != "cofee-planning-goal-evidence/v1":
        errors.append("evidence-list schema must be cofee-planning-goal-evidence/v1")
        manifest = manifest if isinstance(manifest, dict) else {}
    attempts, source_errors = load_evidence(manifest, path.parent)
    regression, regression_errors = _regression(manifest, path.parent)
    rows = [evaluate_attempt(row) for row in attempts]
    paths = [row["path"] for row in rows]
    if len(paths) != len(set(paths)):
        errors.append("evidence reuses an attempt path; three distinct runs are required")
    rejected = [row for row in rows if not row["eligible"]]
    accepted = {workload: [row for row in rows if row["eligible"] and row["workload"] == workload]
                for workload in WORKLOAD_CELLS}
    for workload, values in accepted.items():
        if len(values) != RUNS_PER_WORKLOAD:
            errors.append(f"{workload} requires exactly {RUNS_PER_WORKLOAD} clean successes; found {len(values)}")
    all_rows = [row for values in accepted.values() for row in values]
    jars = {row["jarSha256"] for row in all_rows if row.get("jarSha256")}
    if len(jars) != 1 or (all_rows and None in {row.get("jarSha256") for row in all_rows}):
        errors.append("accepted runs do not use one frozen engine")
    jar = next(iter(jars)) if len(jars) == 1 else None
    if regression.get("jarSha256") != jar:
        errors.append("regression report engine does not match runtime evidence")
    for field in ("profileId", "profileSourceSha256", "jvmOptions", "container", "seeds"):
        canonical = {json.dumps(row.get(field), sort_keys=True) for row in all_rows}
        if len(canonical) > 1 or (all_rows and json.dumps(None) in canonical):
            errors.append(f"accepted runs differ in {field}")
    data_contracts = {row.get("dataContractSha256") for row in all_rows}
    if len(data_contracts) > 1 or (all_rows and None in data_contracts):
        errors.append("accepted runs differ in pinned COFEE X/Y data hashes")
    for workload, values in accepted.items():
        if not values:
            continue
        for field in ("templateSha256", "inputContractSha256"):
            canonical = {row.get(field) for row in values}
            if len(canonical) != 1 or None in canonical:
                errors.append(f"{workload} runs differ in {field}")
    errors.extend(source_errors)
    errors.extend(regression_errors)
    if rejected:
        errors.append(f"evidence includes {len(rejected)} rejected attempt(s)")
    return {"schema": "cofee-planning-goal-evaluation/v1", "passed": not errors,
            "targetNanos": TARGET_NANOS, "requiredRunsPerWorkload": RUNS_PER_WORKLOAD,
            "engineJarSha256": jar, "regressionReport": regression,
            "accepted": accepted, "rejected": rejected, "errors": [item for item in errors if item],
            "evidenceList": str(path.resolve())}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-list", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    result = evaluate(args.evidence_list.resolve())
    encoded = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
