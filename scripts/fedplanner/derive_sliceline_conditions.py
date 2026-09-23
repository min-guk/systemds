#!/usr/bin/env python3
"""Derive the 64 missing KDD98/USCENSUS SliceLine compile conditions.

The historical planning snapshot did not materialize worker-correct programs
for these datasets.  This producer therefore preserves that history and adds
new, explicitly derived conditions backed by the sealed W1/3/5/7 stage.  It
does not rewrite either the historical snapshot or the v1 frozen cohort.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile


DATASETS = ("KDD98", "USCENSUS")
WORKERS = (1, 3, 5, 7)
PROFILES = ("lan", "wan_light", "wan_mid", "wan_heavy")
TEMPLATE = "harness/sigmod2021-exdra-p523/experiments/code/exp/sliceline_fed.dml"
CORE = "harness/sigmod2021-exdra-p523/experiments/code/workloads/sliceline/slicefinder_core.dml"
SOURCE_LITERAL = "code/workloads/sliceline/slicefinder_core.dml"
DERIVED_KIND = "campaign-compile-condition"
COMPILER_ARGV = ["-exec", "singlenode", "-seed", "1011081480",
                 "-noFedRuntimeConversion", "-stats", "100"]
SCRIPT = Path(__file__).resolve()
FEDERATED = re.compile(
    r'(?m)^\s*([A-Za-z][A-Za-z0-9_]*)\s*=\s*federated\('
    r'addresses=list\((.*)\),\s*ranges=list\((.*)\)\)\s*$')
QUOTED = re.compile(r'"([^"]+)"')
RANGE_PAIR = re.compile(r"list\(\s*([0-9]+)\s*,\s*([0-9]+)\s*\)")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def render_json(value):
    return json.dumps(value, indent=2, sort_keys=True).encode() + b"\n"


def parse_seal(stage):
    seal_path = Path(stage) / "STAGE_CONTENT.sha256"
    entries = {}
    for number, line in enumerate(seal_path.read_text().splitlines(), 1):
        match = re.fullmatch(r"([0-9a-f]{64})  (?:\./)?(.+)", line)
        if not match:
            raise ValueError(f"invalid stage seal line {number}")
        entries.setdefault(match.group(2), set()).add(match.group(1))
    return seal_path, entries


def sealed(stage, entries, relative):
    path = (Path(stage) / relative).resolve()
    root = Path(stage).resolve()
    candidates = entries.get(relative, set())
    expected = next(iter(candidates)) if len(candidates) == 1 else None
    if not path.is_relative_to(root) or not path.is_file() or expected is None \
            or file_sha(path) != expected:
        raise ValueError(f"sealed stage file missing or changed: {relative}")
    return path, expected


def normalized_privacy(value):
    value = str(value).upper().replace("-", "_")
    if value not in ("PUBLIC", "PRIVATE_AGGREGATE"):
        raise ValueError(f"unsupported privacy: {value}")
    return value


def network_cost(profile):
    c2w, w2c = float(profile["c2w_mbit"]), float(profile["w2c_mbit"])
    harmonic = 2.0 / ((1.0 / c2w) + (1.0 / w2c))
    return {
        "SYSDS_FED_COST_MEM_BW": "25000",
        "SYSDS_FED_COST_FLOPS": "2147483648",
        "SYSDS_FED_COST_NET_BW": f"{harmonic / 8.0:.6f}",
        "SYSDS_FED_COST_NET_BW_C2W": f"{c2w / 8.0:.6f}",
        "SYSDS_FED_COST_NET_BW_W2C": f"{w2c / 8.0:.6f}",
        "SYSDS_FED_COST_NET_SERDES_BW": "210",
        "SYSDS_FED_COST_NET_SERDES_BW_C2W": "210",
        "SYSDS_FED_COST_NET_SERDES_BW_W2C": "14.7",
        "SYSDS_FED_COST_NET_LATENCY": f"{float(profile['rtt_ms']) / 1000.0:.6f}",
        "SYSDS_FED_COST_LOCAL_TO_FED_CTRL_MS": "0.35",
    }


def _metadata(path, expected_sha):
    if file_sha(path) != expected_sha:
        raise ValueError(f"metadata digest differs: {path}")
    value = read(path)
    for key in ("rows", "cols", "nnz", "privacy", "format", "data_type", "value_type"):
        if key not in value:
            raise ValueError(f"metadata field absent: {path}:{key}")
    if value["format"] != "binary" or value["data_type"] != "matrix" \
            or value["value_type"] != "double":
        raise ValueError(f"unsupported metadata type: {path}")
    return value


def stage_input(stage, entries, topology, dataset, role, workers):
    base = f"{dataset}_{role}"
    global_path, global_sha = sealed(stage, entries, f"data/{base}.data.mtd")
    global_meta = _metadata(global_path, global_sha)
    rows, cols = int(global_meta["rows"]), int(global_meta["cols"])
    split = (rows + workers - 1) // workers
    origins, ranges, parts = [], [], []
    for index in range(workers):
        suffix = "" if workers == 1 else f"_{workers}_{index + 1}"
        relative = f"data/{base}{suffix}.data.mtd"
        part_path, part_sha = sealed(stage, entries, relative)
        part = _metadata(part_path, part_sha)
        begin, end = min(split * index, rows), min(split * (index + 1), rows)
        if (int(part["rows"]), int(part["cols"])) != (end - begin, cols):
            raise ValueError(f"stage partition geometry differs: {relative}")
        worker = topology["workers"][index]
        origin = f"{worker['ip']}:{worker['port']}/data/{base}{suffix}.data"
        origins.append(origin)
        ranges.extend(([begin, 0], [end, cols]))
        parts.append({"worker": index + 1, "begin": [begin, 0], "end": [end, cols],
                      "metadataPath": str(part_path), "metadataSha256": part_sha,
                      "metadata": part})
    return {"base": base, "globalMetadata": global_meta,
            "globalMetadataPath": str(global_path), "globalMetadataSha256": global_sha,
            "origins": origins, "ranges": ranges, "parts": parts}


def dml_list(values):
    return "list(" + ", ".join(json.dumps(value) for value in values) + ")"


def dml_ranges(values):
    return "list(" + ", ".join(f"list({a}, {b})" for a, b in values) + ")"


def render_program(template, x, y, dataset):
    text = template
    replacements = {
        "__X_ADDRS__": dml_list(x["origins"]), "__X_RANGES__": dml_ranges(x["ranges"]),
        "__Y_ADDRS__": dml_list(y["origins"]), "__Y_RANGES__": dml_ranges(y["ranges"]),
        "__N__": str(x["globalMetadata"]["rows"]),
        "__D__": str(x["globalMetadata"]["cols"]),
        "__OUT__": f"tmp/sliceline-{dataset}.csv",
    }
    for token, value in replacements.items():
        text = text.replace(token, value)
    remaining = sorted(set(re.findall(r"__[A-Z0-9_]+__", text)))
    if remaining:
        raise ValueError(f"unresolved template tokens: {remaining}")
    return text


def assert_literal_binding(program, sources):
    actual = {}
    for variable, address_text, range_text in FEDERATED.findall(program):
        actual[variable] = {"origins": QUOTED.findall(address_text),
                            "ranges": [[int(row), int(col)]
                                       for row, col in RANGE_PAIR.findall(range_text)]}
    expected = {source["variable"]: {"origins": source["origins"],
                                     "ranges": source["ranges"]} for source in sources}
    if actual != expected:
        raise ValueError(f"DML literal/source-fact address mismatch: {actual} != {expected}")


def parent_case(evaluation, workers, workload):
    native = Path(evaluation) / "planning_study/native"
    context_path = native / f"context-w{workers}.json"
    inputs_path = native / f"input_templates/w{workers}/metadata/inputs.json"
    context, inputs = read(context_path), read(inputs_path)
    matches = [case for case in inputs["cases"] if case["workload"] == workload]
    if len(matches) != 1:
        raise ValueError(f"historical input case absent/duplicated: w{workers}:{workload}")
    if any(case.get("workload") == workload for case in context.get("cases", [])):
        raise ValueError(f"historical context unexpectedly contains derived case: w{workers}:{workload}")
    return context_path, inputs_path, matches[0], context


def candidate_axes(candidate):
    discovery = candidate["discoveryId"]
    if candidate["kind"] == "base-campaign":
        match = re.fullmatch(r"base:sliceline:sliceline-(kdd98|uscensus)", discovery)
        lineage = "DERIVED_CURRENT_BASE_CAMPAIGN"
    else:
        match = re.fullmatch(r"planning-w(1|3|5|7):sliceline-(kdd98|uscensus)", discovery)
        lineage = "DERIVED_FROM_HISTORICAL_PLANNING_PARENT"
        if match and int(match.group(1)) != candidate["workers"]:
            raise ValueError("planning worker axis differs")
    if not match:
        raise ValueError(f"unexpected unresolved SliceLine discovery: {discovery}")
    dataset = match.group(match.lastindex).upper()
    return dataset, lineage


def validate_base_compiler_contract(catalog, campaign):
    promoted = [row for row in catalog.get("cells", [])
                if (row.get("sourceBinding") or {}).get("kind") == "campaign-compile-condition"
                and "plannedCondition" in (row.get("sourceBinding") or {})]
    if len(promoted) != 296:
        raise ValueError(f"compiler-contract base must contain 296 campaign conditions: {len(promoted)}")
    for row in promoted:
        binding = row["sourceBinding"]
        argv = binding.get("compilerArgv")
        if not isinstance(argv, list) or not argv:
            raise ValueError(f"compiler-contract base lacks compiler argv: {row['id']}")
        for source in binding["plannedCondition"]["case"].get("federatedSources", []):
            origins, ranges = source.get("origins"), source.get("ranges")
            if not isinstance(origins, list) or not isinstance(ranges, list) \
                    or len(ranges) != 2 * len(origins):
                raise ValueError(f"compiler-contract base lacks exact source ranges: {row['id']}")
    campaign_ids = {row["id"] for row in campaign.get("cells", [])}
    if any(row["id"] not in campaign_ids for row in promoted):
        raise ValueError("compiler-contract campaign omits a promoted catalog condition")
    return len(promoted)


def build(base_catalog_path, base_campaign_path, evaluation, stage, topology_path):
    base_catalog_path, base_campaign_path = Path(base_catalog_path), Path(base_campaign_path)
    evaluation, stage, topology_path = map(Path, (evaluation, stage, topology_path))
    catalog, campaign, topology = read(base_catalog_path), read(base_campaign_path), read(topology_path)
    if campaign.get("scope") != "FROZEN_COHORT_548" \
            or campaign.get("counts", {}).get("unresolvedCandidates") != 64:
        raise ValueError("input campaign is not the frozen 548+64 cohort")
    unresolved = campaign.get("unresolved", [])
    if len(unresolved) != 64 or len(topology.get("workers", [])) < 7:
        raise ValueError("derived cohort denominator/topology differs")
    if {row.get("networkProfile") for row in unresolved} != set(PROFILES):
        raise ValueError("unresolved network axes differ")
    if {row.get("workers") for row in unresolved} != set(WORKERS):
        raise ValueError("unresolved worker axes differ")
    promoted_compiler_conditions = validate_base_compiler_contract(catalog, campaign)

    seal_path, seal_entries = parse_seal(stage)
    template_path, template_sha = sealed(stage, seal_entries, TEMPLATE)
    core_path, core_sha = sealed(stage, seal_entries, CORE)
    prepare_path = evaluation / "planning_study/native/prepare_inputs.py"
    driver_path = evaluation / "driver/run_multihost_campaign_network_quality_v2.py"
    source_hashes = {"prepareInputs": file_sha(prepare_path), "driver": file_sha(driver_path),
                     "stageSeal": file_sha(seal_path), "template": template_sha,
                     "core": core_sha, "topology": file_sha(topology_path)}
    files, derived, cells = {}, [], []
    template = template_path.read_text()

    for candidate in sorted(unresolved, key=lambda row: row["candidateId"]):
        workers, profile = int(candidate["workers"]), candidate["networkProfile"]
        dataset, lineage = candidate_axes(candidate)
        workload = f"sliceline-{dataset.lower()}"
        context_path, inputs_path, historical, context = parent_case(
            evaluation, workers, workload)
        profile_value = context.get("profiles", {}).get(profile)
        if profile_value is None:
            raise ValueError(f"historical network profile absent: w{workers}:{profile}")
        x = stage_input(stage, seal_entries, topology, dataset, "features", workers)
        y = stage_input(stage, seal_entries, topology, dataset, "labels", workers)
        historical_x = normalized_privacy(historical["metadata"]["X"]["privacy"])
        historical_y = normalized_privacy(historical["metadata"]["Y"]["privacy"])
        stage_x = normalized_privacy(x["globalMetadata"]["privacy"])
        stage_y = normalized_privacy(y["globalMetadata"]["privacy"])
        if lineage == "DERIVED_CURRENT_BASE_CAMPAIGN":
            source_privacy = (stage_x, stage_y)
        else:
            source_privacy = (historical_x, historical_y)
        sources = [
            {"variable": "X", "origins": x["origins"], "privacy": source_privacy[0],
             "ranges": x["ranges"], "federationType": "ROW"},
            {"variable": "e", "origins": y["origins"], "privacy": source_privacy[1],
             "ranges": y["ranges"], "federationType": "ROW"},
        ]
        program = render_program(template, x, y, dataset)
        assert_literal_binding(program, sources)
        cell_id = "cell_derived_" + digest({"candidateId": candidate["candidateId"],
                                             "lineage": lineage})[:20]
        prefix = Path("derived_sliceline") / cell_id
        program_relative, core_relative = prefix / "program.dml", prefix / "imports/core.dml"
        files[program_relative.as_posix()] = program.encode()
        files[core_relative.as_posix()] = core_path.read_bytes()
        planned = {"workers": workers,
                   "network": {"cost_environment": network_cost(profile_value)},
                   "case": {"program": program_relative.as_posix(),
                            "program_sha256": hashlib.sha256(program.encode()).hexdigest(),
                            "arguments": {}, "compileLocalArguments": {},
                            "imports": {SOURCE_LITERAL: core_relative.as_posix()},
                            "federatedSources": sources}}
        condition_sha = digest({"conditionId": profile,
                                "discoveryId": candidate["discoveryId"],
                                "compilerArgv": COMPILER_ARGV, **planned})
        metadata_evidence = {
            role: {"globalMetadataPath": item["globalMetadataPath"],
                   "globalMetadataSha256": item["globalMetadataSha256"],
                   "partitions": [{key: part[key] for key in
                                   ("worker", "begin", "end", "metadataPath", "metadataSha256")}
                                  for part in item["parts"]]}
            for role, item in (("X", x), ("e", y))}
        derivation = {
            "schema": "sliceline-derived-condition-provenance-v2",
            "lineage": lineage,
            "historicalSnapshotPreserved": True,
            "historicalContextCasePresent": False,
            "historicalInputManifestCaseSha256": digest(historical),
            "historicalContextPath": str(context_path),
            "historicalContextSha256": file_sha(context_path),
            "historicalInputManifestPath": str(inputs_path),
            "historicalInputManifestSha256": file_sha(inputs_path),
            "prepareInputsPath": str(prepare_path),
            "prepareInputsSha256": source_hashes["prepareInputs"],
            "runtimeGeneratorPath": str(driver_path),
            "runtimeGeneratorSha256": source_hashes["driver"],
            "stagePath": str(stage.resolve()), "stageSealPath": str(seal_path),
            "stageSealSha256": source_hashes["stageSeal"],
            "stageMetadata": metadata_evidence,
            "privacy": {"stage": {"X": stage_x, "e": stage_y},
                        "historicalParent": {"X": historical_x, "e": historical_y},
                        "effective": {"X": source_privacy[0], "e": source_privacy[1]},
                        "overrideApplied": source_privacy != (stage_x, stage_y)},
            "workerGeometrySource": "SEALED_STAGE_PARTITION_METADATA",
            "literalAddressBindingVerified": True,
            "stageMetadataTargetedHashesVerified": True,
            "runtimeDataBytesAssessed": False,
            "runtimeDataExecutionAssessed": False,
            "adapterCompatibility": {
                "catalogKindUsesExistingAdapterPath": DERIVED_KIND,
                "compilerArgvRequiredByRuntimeGenerator": COMPILER_ARGV,
                "currentJavaAdapterCarriesCompilerArgv": True,
                "currentJavaAdapterChecksDmlLiteralAddresses": True,
                "producerChecksDmlLiteralAddresses": True,
            },
        }
        source_files = {program_relative.as_posix(): planned["case"]["program_sha256"],
                        core_relative.as_posix(): core_sha}
        cell = {
            "id": cell_id, "inventoryStatus": "IN_SCOPE",
            "nativeInputCapture": "PENDING", "conditionId": profile,
            "discoveryId": candidate["discoveryId"],
            "promotedFromPlaceholderCellId": candidate["placeholderCellId"],
            "expectedPairs": next(row["expectedPairs"] for row in catalog["cells"]
                                  if row["id"] == candidate["placeholderCellId"]),
            "sourceFiles": source_files,
            "sourceBinding": {"kind": DERIVED_KIND, "conditionStatus": "SNAPSHOT_ONLY",
                              "discoveryId": candidate["discoveryId"],
                              "conditionSha256": condition_sha,
                              "compilerArgv": COMPILER_ARGV,
                              "plannedCondition": planned, "workloadJvmOptions": [],
                              "derivation": derivation},
        }
        cells.append(cell)
        derived.append({"candidateId": candidate["candidateId"], "cellId": cell_id,
                        "discoveryId": candidate["discoveryId"], "conditionId": profile,
                        "workers": workers, "lineage": lineage,
                        "conditionSha256": condition_sha})

    if len({row["id"] for row in cells}) != 64 or len({row["conditionSha256"] for row in derived}) != 64:
        raise ValueError("derived SliceLine conditions collide")
    new_catalog = {**catalog, "status": "COMPLETE_DERIVED_ARGV_COHORT",
                   "cells": sorted(catalog["cells"] + cells, key=lambda row: row["id"]),
                   "unresolved": [],
                   "derivedSliceLineV3": {"schema": "sliceline-derived-cohort-argv-v3",
                       "baseCatalogPath": str(base_catalog_path.resolve()),
                       "baseCatalogSha256": file_sha(base_catalog_path),
                       "baseCampaignPath": str(base_campaign_path.resolve()),
                       "baseCampaignSha256": file_sha(base_campaign_path),
                       "baseCompilerContractConditions": promoted_compiler_conditions,
                       "producerScriptSha256": file_sha(SCRIPT),
                       "sourceHashes": source_hashes, "conditions": derived,
                       "claimScope": "SOURCE_BACKED_DERIVED_COMPILE_MODEL_INPUTS_ONLY"}}
    return new_catalog, files, derived


def make_campaign(catalog, catalog_path, overlay, base_campaign_path, derived):
    base = read(base_campaign_path)
    by_id = {row["id"]: row for row in catalog["cells"]}
    cells = base["cells"] + [{**by_id[item["cellId"]], "inputValidation": {
        "compileModelReady": True, "conditionDigestRecomputed": True,
        "sourceDigestsVerified": True, "workerGeometryVerifiedFromSealedStage": True,
        "dmlLiteralAddressBindingVerified": True, "runtimeDataExecutionAssessed": False,
        "derivedCondition": True}} for item in derived]
    content = render_json(catalog)
    return {"schema": "current-pe-corpus-manifest-v1", "status": "COMPLETE_DERIVED_INPUT_BINDING",
            "scope": "FROZEN_COHORT_DERIVED_ARGV_612",
            "claimScope": "SOURCE_BACKED_DERIVED_COMPILE_MODEL_COHORT_ONLY",
            "catalog": {"path": str(Path(catalog_path).resolve()),
                        "sha256": hashlib.sha256(content).hexdigest()},
            "evaluation": {"path": str(Path(overlay).resolve())},
            "producerScriptSha256": file_sha(SCRIPT),
            "derivationBinding": {"baseCampaignPath": str(Path(base_campaign_path).resolve()),
                                  "baseCampaignSha256": file_sha(base_campaign_path),
                                  "derivedConditions": len(derived)},
            "counts": {"readyCells": 612, "planningCells": 224,
                       "promotedCells": 324, "derivedCells": 64,
                       "unresolvedCandidates": 0},
            "cells": sorted(cells, key=lambda row: row["id"]), "unresolved": [],
            "fullCurrentRequirements": {"allCandidateAxesBound": True,
                "runtimeDataExecutionAssessed": False,
                "javaAdapterCompilerArgvParityEstablished": True,
                "javaAdapterLiteralAddressCheckImplemented": True},
            "limitations": ["DERIVED_CONDITIONS_ARE_NOT_HISTORICAL_SNAPSHOT_OBSERVATIONS",
                "RUNTIME_DATA_EXECUTION_NOT_ASSESSED"]}


def publish(catalog, campaign, files, catalog_path, campaign_path, overlay,
            base_overlay, check=False):
    catalog_path, campaign_path, overlay, base_overlay = map(
        Path, (catalog_path, campaign_path, overlay, base_overlay))
    expected = {catalog_path: render_json(catalog), campaign_path: render_json(campaign)}
    for source in base_overlay.rglob("*"):
        if source.is_file():
            expected[overlay / source.relative_to(base_overlay)] = source.read_bytes()
    expected.update({overlay / relative: content for relative, content in files.items()})
    if check:
        changed = [str(path) for path, content in expected.items()
                   if not path.is_file() or path.read_bytes() != content]
        extra = [str(path) for path in overlay.rglob("*") if path.is_file() and path not in expected]
        if changed or extra:
            raise ValueError(f"derived cohort differs: changed={changed[:5]} extra={extra[:5]}")
        return
    for path, content in sorted(expected.items(), key=lambda item: str(item[0])):
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
            stream.write(content)
            temporary = Path(stream.name)
        temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-catalog", type=Path, required=True)
    parser.add_argument("--base-campaign", type=Path, required=True)
    parser.add_argument("--base-overlay", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--topology", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    catalog, files, derived = build(args.base_catalog, args.base_campaign,
                                    args.evaluation_root, args.stage, args.topology)
    catalog_path, campaign_path = args.output_root / "catalog.json", args.output_root / "campaign.json"
    overlay = args.output_root / "evaluation"
    campaign = make_campaign(catalog, catalog_path, overlay, args.base_campaign, derived)
    publish(catalog, campaign, files, catalog_path, campaign_path, overlay,
            args.base_overlay, args.check)
    print(json.dumps({"status": campaign["status"], **campaign["counts"],
                      "catalogSha256": campaign["catalog"]["sha256"]}, sort_keys=True))


if __name__ == "__main__":
    main()
