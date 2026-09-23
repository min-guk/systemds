#!/usr/bin/env python3
"""Promote frozen campaign and microbenchmark conditions into a capture catalog.

The generated evaluation overlay is self-contained for every source file named
by the augmented catalog.  Promotion does not claim P/E equality or runtime
data availability; it only turns already-frozen compile inputs into the common
native-capture contract.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile


SOURCE = re.compile(r'source\("([^"]+)"\)')
FEDERATED = re.compile(r"(?m)^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*federated\(")
P2_METADATA_RELEASE_OPTION = "-Dsysds.privacy.allowPublicRecodeMetadata=true"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def safe_relative(value):
    value = Path(value)
    if value.is_absolute() or ".." in value.parts or value == Path("."):
        raise ValueError(f"unsafe overlay path: {value}")
    return value


def add_file(files, relative, content, expected=None):
    relative = safe_relative(relative).as_posix()
    content = bytes(content)
    actual = hashlib.sha256(content).hexdigest()
    if expected is not None and actual != expected:
        raise ValueError(f"promoted source digest differs: {relative}")
    prior = files.setdefault(relative, content)
    if prior != content:
        raise ValueError(f"promoted source path collision: {relative}")
    return actual


def privacy(value):
    normalized = str(value).upper().replace("-", "_")
    if normalized not in ("PUBLIC", "PRIVATE_AGGREGATE"):
        raise ValueError(f"unsupported frozen source privacy: {value}")
    return normalized


def campaign_cell(row, cell_id, artifact_sha, files):
    payload = row.get("compileModelInput")
    if row.get("resolutionStatus") != "READY_FOR_NATIVE_CAPTURE" or not isinstance(payload, dict):
        raise ValueError("campaign row is not capture-ready")
    if digest(payload) != row.get("conditionSha256"):
        raise ValueError("campaign condition digest differs")
    program = payload.get("program") or {}
    text = program.get("text")
    if not isinstance(text, str) or hashlib.sha256(text.encode()).hexdigest() != program.get("sha256"):
        raise ValueError("campaign program digest differs")
    inputs = payload.get("inputs")
    names = FEDERATED.findall(text)
    if not isinstance(inputs, list) or len(names) != len(inputs) or len(set(names)) != len(names):
        raise ValueError("campaign federated source/input binding differs")
    prefix = Path("frozen_capture") / cell_id
    program_relative = prefix / "program.dml"
    program_sha = add_file(files, program_relative, text.encode(), program["sha256"])
    imports = {}
    dependencies = payload.get("dependencies", [])
    literals = SOURCE.findall(text)
    if len(literals) != len(dependencies):
        raise ValueError("campaign import/dependency binding differs")
    for index, (literal, dependency) in enumerate(zip(literals, dependencies)):
        source = Path(dependency["path"])
        if not source.is_file() or file_sha(source) != dependency.get("sha256"):
            raise ValueError(f"campaign dependency changed: {source}")
        relative = prefix / "imports" / f"{index:02d}-{source.name}"
        add_file(files, relative, source.read_bytes(), dependency["sha256"])
        imports[literal] = relative.as_posix()
    sources = []
    for name, source in zip(names, inputs):
        addresses = source.get("addresses")
        ranges = source.get("ranges")
        metadata = source.get("globalMetadata") or {}
        if not isinstance(addresses, list) or len(addresses) != payload.get("workers"):
            raise ValueError("campaign source worker count differs")
        if not isinstance(ranges, list) or len(ranges) != 2 * len(addresses) \
                or any(not isinstance(point, list) or len(point) != 2
                       or any(not isinstance(value, int) for value in point)
                       for point in ranges):
            raise ValueError("campaign source range geometry differs")
        sources.append({"variable": name, "origins": addresses,
                        "ranges": ranges,
                        "privacy": privacy(metadata.get("privacy")),
                        "federationType": source.get("partitioning")})
    compiler_argv = payload.get("compilerArgv")
    if not isinstance(compiler_argv, list) or any(not isinstance(value, str)
                                                   for value in compiler_argv):
        raise ValueError("campaign compiler argv is missing or malformed")
    network = payload.get("networkCost")
    if not isinstance(network, dict) or not network:
        raise ValueError("campaign network cost is missing")
    workload_options = payload.get("workloadJvmOptions", [])
    if not isinstance(workload_options, list) or any(
            not isinstance(option, str) or not option.startswith("-D") or "=" not in option[2:]
            for option in workload_options):
        raise ValueError("campaign workload JVM options are malformed")
    if len(workload_options) != len(set(workload_options)):
        raise ValueError("campaign workload JVM options are duplicated")
    if payload.get("workload") == "P2_PREP" \
            and workload_options != [P2_METADATA_RELEASE_OPTION]:
        raise ValueError("P2 campaign condition lacks the frozen metadata release option")
    planned = {"workers": payload["workers"], "network": {"cost_environment": network},
               "case": {"program": program_relative.as_posix(),
                        "program_sha256": program_sha, "arguments": {},
                        "imports": imports, "federatedSources": sources,
                        "compileLocalArguments": {}}}
    return {"kind": "campaign-compile-condition", "discoveryId": row["discoveryId"],
            "conditionStatus": "SNAPSHOT_ONLY",
            "conditionSha256": row["conditionSha256"], "plannedCondition": planned,
            "compilerArgv": compiler_argv,
            "workloadJvmOptions": list(workload_options), "freezeArtifactSha256": artifact_sha}


def micro_cell(row, cell_id, artifact_sha, program_root, evaluation_root, files):
    planned = row.get("plannedCondition")
    identity = {"conditionId": row.get("conditionId"),
                "discoveryId": row.get("discoveryId"), **(planned or {})}
    if row.get("status") != "READY_FOR_COMPILE_MODEL_CAPTURE" \
            or not isinstance(planned, dict) or digest(identity) != row.get("conditionSha256"):
        raise ValueError("microbenchmark condition digest differs")
    case = planned["case"]
    source_program = Path(program_root) / Path(case["program"]).name
    if not source_program.is_file() or file_sha(source_program) != case.get("programSha256"):
        raise ValueError(f"microbenchmark program changed: {source_program}")
    prefix = Path("frozen_capture") / cell_id
    program_relative = prefix / "program.dml"
    add_file(files, program_relative, source_program.read_bytes(), case["programSha256"])
    source_files = {program_relative.as_posix(): case["programSha256"]}
    for relative, expected in sorted(row.get("sourceFiles", {}).items()):
        if relative.startswith("programs/"):
            continue
        source = Path(evaluation_root) / safe_relative(relative)
        if not source.is_file() or file_sha(source) != expected:
            raise ValueError(f"microbenchmark frozen source changed: {relative}")
        target = prefix / "producer" / Path(relative).name
        add_file(files, target, source.read_bytes(), expected)
        source_files[target.as_posix()] = expected
    inputs = case["inputs"]["X"]
    parts = inputs.get("parts")
    if not isinstance(parts, list) or len(parts) != planned.get("workers"):
        raise ValueError("microbenchmark source worker count differs")
    origins = [case["arguments"][part["argument"]] for part in parts]
    sources = [{"variable": "X", "origins": origins,
                "privacy": privacy(inputs["globalMetadata"]["privacy"]),
                "federationType": inputs["federationType"]}]
    properties = case.get("plannerConfiguration", {})
    if not isinstance(properties, dict) or any(not isinstance(k, str) or not isinstance(v, str)
                                               for k, v in properties.items()):
        raise ValueError("microbenchmark planner properties are malformed")
    local_arguments = {}
    local_input = case.get("inputs", {}).get("R")
    if local_input is not None:
        metadata = local_input.get("metadata") or {}
        rows, cols = metadata.get("rows"), metadata.get("cols")
        if not isinstance(rows, int) or not isinstance(cols, int) or rows < 1 or cols < 1:
            raise ValueError("microbenchmark coordinator input dimensions are malformed")
        data_relative = prefix / "compile-local" / "R.csv"
        data = (("0," * (cols - 1) + "0\n") * rows).encode()
        data_sha = add_file(files, data_relative, data)
        metadata_relative = Path(str(data_relative) + ".mtd")
        metadata_bytes = json.dumps(metadata, sort_keys=True).encode() + b"\n"
        metadata_sha = add_file(files, metadata_relative, metadata_bytes)
        source_files[data_relative.as_posix()] = data_sha
        source_files[metadata_relative.as_posix()] = metadata_sha
        local_arguments[local_input["argument"]] = data_relative.as_posix()
    normalized = {"workers": planned["workers"], "network": planned["network"],
                  "case": {"program": program_relative.as_posix(),
                           "program_sha256": case["programSha256"],
                           "arguments": case["arguments"], "imports": {},
                           "federatedSources": sources,
                           "compileLocalArguments": local_arguments}}
    binding = {"kind": "generated-microbench", "discoveryId": row["discoveryId"],
               "conditionStatus": "SNAPSHOT_ONLY",
               "conditionSha256": row["conditionSha256"], "plannedCondition": normalized,
               "workloadJvmOptions": [f"-D{key}={value}" for key, value in sorted(properties.items())],
               "freezeArtifactSha256": artifact_sha}
    return binding, source_files


def build(catalog_path, evaluation_root, campaign_path, micro_path, micro_program_root):
    catalog_path, evaluation_root = Path(catalog_path).resolve(), Path(evaluation_root).resolve()
    campaign_path, micro_path = Path(campaign_path).resolve(), Path(micro_path).resolve()
    catalog, campaign, micro = read(catalog_path), read(campaign_path), read(micro_path)
    if catalog.get("schema") != "closed-comparison-cases-v1":
        raise ValueError("wrong base catalog schema")
    if campaign.get("schema") != "campaign-compile-condition-freeze-v1":
        raise ValueError("wrong campaign freeze schema")
    if micro.get("schema") != "microbench-condition-freeze-v1":
        raise ValueError("wrong microbenchmark freeze schema")
    files = {}
    rows = {row["id"]: json.loads(json.dumps(row)) for row in catalog.get("cells", [])}
    if len(rows) != len(catalog.get("cells", [])):
        raise ValueError("base catalog cell IDs are duplicated")
    for row in catalog.get("cells", []):
        for relative, expected in sorted((row.get("sourceFiles") or {}).items()):
            source = evaluation_root / safe_relative(relative)
            if not source.is_file() or file_sha(source) != expected:
                raise ValueError(f"base catalog source changed: {relative}")
            add_file(files, relative, source.read_bytes(), expected)
    promoted = []
    promoted_rows = []
    for freeze, kind in ((campaign, "campaign"), (micro, "micro")):
        artifact_sha = file_sha(campaign_path if kind == "campaign" else micro_path)
        for condition in freeze.get("conditions", []):
            cell_id = condition.get("placeholderCellId")
            target = rows.get(cell_id)
            if target is None or target.get("discoveryId") != condition.get("discoveryId"):
                raise ValueError(f"frozen condition placeholder differs: {cell_id}")
            if target.get("inventoryStatus") != "IN_SCOPE":
                raise ValueError(f"frozen condition is outside catalog scope: {cell_id}")
            promoted_id = "cell_capture_" + digest({
                "discoveryId": condition["discoveryId"],
                "conditionId": condition["conditionId"],
                "conditionSha256": condition["conditionSha256"]})[:20]
            if promoted_id in rows or promoted_id in promoted:
                raise ValueError(f"promoted capture cell ID collides: {promoted_id}")
            if kind == "campaign":
                binding = campaign_cell(condition, promoted_id, artifact_sha, files)
                source_files = {name: hashlib.sha256(content).hexdigest()
                                for name, content in files.items()
                                if name.startswith(f"frozen_capture/{promoted_id}/")}
            else:
                binding, source_files = micro_cell(condition, promoted_id, artifact_sha,
                                                   micro_program_root, evaluation_root, files)
            promoted_row = json.loads(json.dumps(target))
            promoted_row.update({"id": promoted_id, "conditionId": condition["conditionId"],
                                 "sourceBinding": binding, "sourceFiles": source_files,
                                 "nativeInputCapture": "PENDING",
                                 "promotedFromPlaceholderCellId": cell_id})
            promoted_rows.append(promoted_row)
            promoted.append(promoted_id)
    expected = campaign.get("counts", {}).get("readyForNativeCapture")
    micro_expected = micro.get("counts", {}).get("conditions")
    if not isinstance(expected, int) or not isinstance(micro_expected, int):
        raise ValueError("freeze manifests lack declared ready counts")
    expected += micro_expected
    if len(promoted) != expected or len(set(promoted)) != expected:
        raise ValueError(f"expected {expected} distinct ready conditions, got {len(set(promoted))}")
    result = dict(catalog)
    result["cells"] = [rows[row["id"]] for row in catalog["cells"]] + promoted_rows
    result["promotion"] = {"schema": "frozen-capture-promotion-v1",
                           "baseCatalogSha256": file_sha(catalog_path),
                           "campaignFreezeSha256": file_sha(campaign_path),
                           "microbenchFreezeSha256": file_sha(micro_path),
                           "promotedConditions": expected,
                           "promotedCellIds": promoted,
                           "promotedCandidateIds": sorted(
                               row["candidateId"] for freeze in (campaign, micro)
                               for row in freeze.get("conditions", [])),
                           "claimScope": "FROZEN_COHORT_548_INPUT_CAPTURE_ONLY"}
    return result, files


def build_cohort(catalog, catalog_output, overlay, base_manifest_path):
    base_manifest_path = Path(base_manifest_path).resolve()
    base = read(base_manifest_path)
    if base.get("schema") != "current-pe-corpus-manifest-v1" \
            or base.get("scope") != "PLANNING_COHORT_224" \
            or len(base.get("cells", [])) != 224:
        raise ValueError("base manifest is not the frozen 224-cell planning cohort")
    by_id = {row["id"]: row for row in catalog["cells"]}
    promoted_ids = catalog["promotion"]["promotedCellIds"]
    ready = []
    for row in base["cells"] + [by_id[cell_id] for cell_id in promoted_ids]:
        promoted = row["id"] in promoted_ids
        ready.append({**row, "inputValidation": {
            "compileModelReady": True,
            "conditionDigestRecomputed": True,
            "sourceFileCount": len(row["sourceFiles"]),
            "sourceDigestsVerified": True,
            "runtimeDataExecutionAssessed": False,
            "validatedByPromotion": promoted}})
    promoted_candidates = set(catalog["promotion"]["promotedCandidateIds"])
    unresolved = [row for row in base.get("unresolved", [])
                  if row.get("candidateId") not in promoted_candidates]
    if len(ready) != 548 or len(unresolved) != 64:
        raise ValueError(f"frozen cohort denominator differs: ready={len(ready)} unresolved={len(unresolved)}")
    catalog_content = json.dumps(catalog, indent=2, sort_keys=True).encode() + b"\n"
    return {
        "schema": "current-pe-corpus-manifest-v1", "status": "INCOMPLETE",
        "scope": "FROZEN_COHORT_548", "claimScope": "FROZEN_COMPILE_MODEL_COHORT_ONLY",
        "catalog": {"path": str(Path(catalog_output).resolve()),
                    "sha256": hashlib.sha256(catalog_content).hexdigest()},
        "evaluation": {"path": str(Path(overlay).resolve())},
        "producerScriptSha256": file_sha(Path(__file__)),
        "promotionBinding": {"baseManifestPath": str(base_manifest_path),
                             "baseManifestSha256": file_sha(base_manifest_path),
                             **catalog["promotion"]},
        "fullCurrentRequirements": {"allInScopeCatalogCellsFrozen": False,
                                    "cellsExactlyMatchInScopeCatalogIds": False,
                                    "unresolvedCandidatesEmpty": False,
                                    "expandedCandidatesRequireAugmentedFrozenCatalogRows": True},
        "counts": {"readyCells": 548, "planningCells": 224,
                   "promotedCells": 324, "unresolvedCandidates": 64},
        "cells": sorted(ready, key=lambda row: row["id"]),
        "unresolved": unresolved,
        "limitations": ["64_REGISTRY_CONDITIONS_REMAIN_UNRESOLVED",
                        "RUNTIME_DATA_EXECUTION_NOT_ASSESSED",
                        "FULL_CURRENT_CLAIM_FORBIDDEN"]}


def publish(catalog, files, output, overlay, check=False):
    output, overlay = Path(output), Path(overlay)
    expected = {overlay / name: content for name, content in files.items()}
    expected[output] = json.dumps(catalog, indent=2, sort_keys=True).encode() + b"\n"
    if check:
        changed = [str(path) for path, content in expected.items()
                   if not path.is_file() or path.read_bytes() != content]
        if changed:
            raise ValueError(f"promoted capture artifact differs: {changed[:10]}")
        return
    for path, content in sorted(expected.items(), key=lambda item: str(item[0])):
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
            stream.write(content)
            temporary = Path(stream.name)
        temporary.replace(path)


def publish_json(value, path, check=False):
    path = Path(path)
    content = json.dumps(value, indent=2, sort_keys=True).encode() + b"\n"
    if check:
        if not path.is_file() or path.read_bytes() != content:
            raise ValueError(f"promoted cohort manifest differs: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--campaign-freeze", type=Path, required=True)
    parser.add_argument("--microbench-freeze", type=Path, required=True)
    parser.add_argument("--microbench-program-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--overlay-root", type=Path, required=True)
    parser.add_argument("--base-manifest", type=Path)
    parser.add_argument("--cohort-output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    catalog, files = build(args.catalog, args.evaluation_root, args.campaign_freeze,
                           args.microbench_freeze, args.microbench_program_root)
    publish(catalog, files, args.output, args.overlay_root, args.check)
    if (args.base_manifest is None) != (args.cohort_output is None):
        raise ValueError("base manifest and cohort output must be supplied together")
    if args.base_manifest is not None:
        cohort = build_cohort(catalog, args.output, args.overlay_root, args.base_manifest)
        publish_json(cohort, args.cohort_output, args.check)
    print(json.dumps({"status": "COMPLETE",
                      "promotedConditions": catalog["promotion"]["promotedConditions"],
                      "catalog": str(args.output), "overlay": str(args.overlay_root)},
                     sort_keys=True))


if __name__ == "__main__":
    main()
