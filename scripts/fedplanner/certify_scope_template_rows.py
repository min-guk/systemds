#!/usr/bin/env python3
"""Certify 13 exact-path templates used by frozen planning successor cells."""

import argparse
import hashlib
import json
import re
import tempfile
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
BASE = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921")
DEFAULT_CATALOG = BASE / "frozen-capture-cohort-derived-argv-v4/catalog.json"
DEFAULT_FROZEN_EVALUATION = DEFAULT_CATALOG.parent / "evaluation"
DEFAULT_EVALUATION = Path("/home/mchoi/cofee-evaluation")
DEFAULT_INVENTORY = ROOT / "src/test/resources/fedplanner/plan-space/workloads.json"
DEFAULT_PARENT = BASE / "current-scope-applicability-audit-v2-library-closure-v2/ledger.json"
DEFAULT_PARENT_SHA256 = "b162f7b26753b095ebffa5d4cc7bec6698d512a35db0909e7891638b790bf894"
DEFAULT_OUTPUT = BASE / "current-scope-template-closure-v3/receipt.json"

TARGET_NAMES = (
    "als_fed", "pca_fed", "lm_fed", "gmm_fed", "P2_PREP", "sliceline_fed",
    "steplm_fed", "P1_FULL", "kmeans_fed", "logreg_fed", "gnmf_fed",
    "l2svm_fed", "glm_fed",
)
TARGET_DISCOVERIES = tuple("common:" + name for name in TARGET_NAMES)
PROFILES = ("lan", "wan_heavy", "wan_light", "wan_mid")
WORKERS = (1, 3, 5, 7)
HARNESS_ALIAS_DISCOVERIES = tuple(
    "unclassified:evaluation:harness/sigmod2021-exdra-p523/experiments/code/exp/"
    + name + ".dml"
    for name in ("als_fed", "glm_fed", "gmm_fed", "kmeans_fed", "l2svm_fed",
                 "lm_fed", "logreg_fed", "pca_fed", "steplm_fed")
)
RESOLUTION = "ACTIVE_EXACT_PATH_FROZEN_TEMPLATE"
TOKEN = re.compile(r"__[A-Z0-9_]+__")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def render(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _safe_file(root, relative, expected):
    root = Path(root).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or not path.is_file() or sha256(path) != expected:
        raise ValueError(f"source bytes differ: {relative}")
    return path


def _condition_identity(row):
    binding = row["sourceBinding"]
    return {"conditionId": row["conditionId"], "discoveryId": row["discoveryId"],
            **binding["plannedCondition"]}


def _dml_strings(values):
    return "list(" + ", ".join(json.dumps(value) for value in values) + ")"


def _dml_ranges(values):
    return "list(" + ", ".join(f"list({row}, {column})"
                                for row, column in values) + ")"


def _input_binding(partition_manifest, name, workers, metadata):
    entry = partition_manifest.get("partitions", {}).get(name)
    if not isinstance(entry, dict) or entry.get("global_metadata") != metadata:
        raise ValueError(f"worker partition global metadata differs: {name}")
    partitions = entry.get("partitions")
    if not isinstance(partitions, list) or len(partitions) != workers:
        raise ValueError(f"worker partition count differs: {name}")
    addresses, ranges = [], []
    prior_end_row = 0
    for ordinal, partition in enumerate(partitions, 1):
        if (partition.get("worker") != ordinal
                or partition.get("begin") != [prior_end_row, 0]
                or partition.get("end", [None, None])[1] != metadata["cols"]):
            raise ValueError(f"worker partition authority differs: {name}")
        metadata_path = partition.get("metadata")
        if not isinstance(metadata_path, str) or not metadata_path.endswith(".data.mtd"):
            raise ValueError(f"worker partition metadata path differs: {name}")
        data_name = Path(metadata_path).name[:-4]
        addresses.append(f"worker{ordinal}:{8000 + ordinal}/data/{data_name}")
        ranges.extend((partition["begin"], partition["end"]))
        prior_end_row = partition["end"][0]
    if prior_end_row != metadata["rows"]:
        raise ValueError(f"worker partition coverage differs: {name}")
    return {"addresses": addresses, "ranges": ranges}


def _render_program(template_text, inputs, output, rows, columns, seed):
    replacements = {
        "__X_ADDRS__": _dml_strings(inputs[0]["addresses"]),
        "__X_RANGES__": _dml_ranges(inputs[0]["ranges"]),
        "__OUT__": output, "__N__": str(rows), "__D__": str(columns),
        "__KMEANS_SEED__": str(seed), "__ALS_SEED__": str(seed),
        "__GNMF_SEED__": str(seed), "__Y_LOCAL_PATH__": "",
    }
    if len(inputs) == 2:
        replacements.update({
            "__Y_ADDRS__": _dml_strings(inputs[1]["addresses"]),
            "__Y_RANGES__": _dml_ranges(inputs[1]["ranges"]),
        })
    rendered = template_text
    for token, value in replacements.items():
        rendered = rendered.replace(token, value)
    remaining = sorted(set(TOKEN.findall(rendered)))
    if remaining:
        raise ValueError(f"independent template render left tokens: {remaining}")
    return rendered


def _worker_evidence(frozen_evaluation, row, template_path, template_text):
    binding = row["sourceBinding"]
    planned = binding["plannedCondition"]
    case, workers = planned["case"], planned["workers"]
    expected_discovery = f"planning-w{workers}:{case.get('workload')}"
    if (not isinstance(workers, int) or workers not in WORKERS
            or case.get("workers") != workers
            or row.get("discoveryId") != expected_discovery
            or binding.get("discoveryId") != row["discoveryId"]):
        raise ValueError(f"successor worker/discovery authority differs: {row['id']}")
    prefix = Path("planning_study/native")
    context_relative = prefix / f"context-w{workers}.json"
    protocol_relative = prefix / f"protocol-w{workers}.json"
    partition_relative = prefix / f"input_templates/w{workers}/metadata/worker-partitions.json"
    files = row.get("sourceFiles") or {}
    for relative in (context_relative, protocol_relative, partition_relative):
        key = relative.as_posix()
        if key not in files:
            raise ValueError(f"successor authority file binding is absent: {row['id']}")
        _safe_file(frozen_evaluation, key, files[key])
    context = read_json(Path(frozen_evaluation) / context_relative)
    protocol = read_json(Path(frozen_evaluation) / protocol_relative)
    partitions = read_json(Path(frozen_evaluation) / partition_relative)
    if (context.get("workers") != workers or partitions.get("workers") != workers
            or protocol.get("workers") != workers
            or protocol.get("context_sha256") != files[context_relative.as_posix()]):
        raise ValueError(f"successor worker manifest authority differs: {row['id']}")
    matching_cases = [candidate for candidate in context.get("cases", [])
                      if candidate.get("program") == case.get("program")]
    if matching_cases != [case]:
        raise ValueError(f"catalog/context case binding differs: {row['id']}")
    fixed = context.get("fixed_cost_environment") or {}
    seed = protocol.get("systemds_seed")
    if (not isinstance(seed, int)
            or fixed.get("SYSTEMDS_SEED") != str(seed)
            or fixed.get("SYSTEMDS_DEFAULT_SEED") != str(seed)):
        raise ValueError(f"pinned seed evidence differs: {row['id']}")
    mappings = case.get("worker_input_mappings")
    metadata = case.get("metadata") or {}
    expected_roles = ("X", "Y") if case.get("Y_applicable") else ("X",)
    if (not isinstance(mappings, list) or len(mappings) != len(expected_roles)
            or set(metadata) != set(expected_roles)):
        raise ValueError(f"case input role binding differs: {row['id']}")
    inputs = [_input_binding(partitions, name, workers, metadata[role])
              for name, role in zip(mappings, expected_roles)]
    output = case.get("forbidden_output")
    if not isinstance(output, str) or not output.startswith("outputs/"):
        raise ValueError(f"case output binding differs: {row['id']}")
    rendered = _render_program(template_text, inputs, output, metadata["X"]["rows"],
                               metadata["X"]["cols"], seed)
    rendered_sha = hashlib.sha256(rendered.encode()).hexdigest()
    if rendered_sha != case.get("program_sha256"):
        raise ValueError(f"independent rendered program differs: {row['id']}")
    return {
        "contextPath": context_relative.as_posix(),
        "contextSha256": files[context_relative.as_posix()],
        "protocolPath": protocol_relative.as_posix(),
        "protocolSha256": files[protocol_relative.as_posix()],
        "workerPartitionPath": partition_relative.as_posix(),
        "workerPartitionSha256": files[partition_relative.as_posix()],
        "seedEvidence": {
            "protocolSystemdsSeed": seed,
            "contextSystemdsSeed": fixed["SYSTEMDS_SEED"],
            "contextSystemdsDefaultSeed": fixed["SYSTEMDS_DEFAULT_SEED"],
        },
        "inputMappings": mappings, "renderInputs": inputs,
        "matrixDimensions": {role: {"rows": metadata[role]["rows"],
                                    "cols": metadata[role]["cols"],
                                    "privacy": metadata[role]["privacy"]}
                             for role in expected_roles},
        "caseMetadataSha256": canonical_sha(metadata), "output": output,
        "renderedProgramSha256": rendered_sha,
        "renderInputsSha256": canonical_sha(inputs),
        "templatePath": template_path,
    }


def _successor_rows(catalog, frozen_evaluation, template, template_sha, template_text,
                    expected_count):
    rows = []
    for row in catalog["cells"]:
        binding = row.get("sourceBinding") or {}
        planned = binding.get("plannedCondition") or {}
        case = planned.get("case") or {}
        if case.get("template") != template:
            continue
        if (row.get("inventoryStatus") != "IN_SCOPE"
                or binding.get("kind") != "planning-snapshot"
                or binding.get("conditionStatus") != "SNAPSHOT_ONLY"):
            raise ValueError(f"template successor binding differs: {row.get('id')}")
        if binding.get("discoveryId") != row.get("discoveryId"):
            raise ValueError(f"successor discovery authority differs: {row['id']}")
        if case.get("template_sha256") != template_sha:
            raise ValueError(f"template successor SHA differs: {row['id']}")
        if canonical_sha(_condition_identity(row)) != binding.get("conditionSha256"):
            raise ValueError(f"successor condition hash differs: {row['id']}")
        program = case.get("program")
        program_sha = case.get("program_sha256")
        if not isinstance(program, str) or not isinstance(program_sha, str):
            raise ValueError(f"successor program identity is absent: {row['id']}")
        program_relative = ("planning_study/native/input_templates/w"
                            + str(planned.get("workers")) + "/" + program)
        _safe_file(frozen_evaluation, program_relative, program_sha)
        if (row.get("sourceFiles") or {}).get(program_relative) != program_sha:
            raise ValueError(f"successor sourceFiles binding differs: {row['id']}")
        for relative, expected in (row.get("sourceFiles") or {}).items():
            _safe_file(frozen_evaluation, relative, expected)
        render_evidence = _worker_evidence(
            frozen_evaluation, row, template, template_text)
        rows.append({
            "cellId": row["id"], "discoveryId": row["discoveryId"],
            "conditionId": row["conditionId"], "workers": planned.get("workers"),
            "conditionSha256": binding["conditionSha256"],
            "plannedConditionSha256": canonical_sha(planned),
            "programPath": program_relative, "programSha256": program_sha,
            "sourceFilesSha256": canonical_sha(row["sourceFiles"]),
            "independentRender": render_evidence,
        })
    if len(rows) != expected_count or len({row["cellId"] for row in rows}) != expected_count:
        raise ValueError(f"exact template successor count differs: {template}")
    axes = Counter((row["workers"], row["conditionId"]) for row in rows)
    expected_axes = {(worker, profile) for worker in WORKERS for profile in PROFILES}
    if set(axes) != expected_axes:
        raise ValueError(f"template successor axes differ: {template}")
    expected_axis_count = 2 if expected_count == 32 else 1
    if set(axes.values()) != {expected_axis_count}:
        raise ValueError(f"template successor axis multiplicity differs: {template}")
    discovery_counts = Counter(row["discoveryId"] for row in rows)
    expected_discoveries = 8 if expected_count == 32 else 4
    if len(discovery_counts) != expected_discoveries or set(discovery_counts.values()) != {4}:
        raise ValueError(f"template successor discovery partition differs: {template}")
    return sorted(rows, key=lambda row: row["cellId"])


def build(catalog_path=DEFAULT_CATALOG, inventory_path=DEFAULT_INVENTORY,
          evaluation=DEFAULT_EVALUATION, frozen_evaluation=DEFAULT_FROZEN_EVALUATION,
          parent_path=DEFAULT_PARENT, expected_parent_sha256=DEFAULT_PARENT_SHA256):
    catalog_path, inventory_path = Path(catalog_path), Path(inventory_path)
    evaluation, frozen_evaluation = Path(evaluation), Path(frozen_evaluation)
    parent_path = Path(parent_path)
    catalog, inventory, parent = (read_json(catalog_path), read_json(inventory_path),
                                  read_json(parent_path))
    parent_sha = sha256(parent_path)
    if expected_parent_sha256 and parent_sha != expected_parent_sha256:
        raise ValueError("parent v2 ledger bytes differ")
    if (parent.get("schema") != "current-scope-applicability-audit-v2"
            or parent.get("counts", {}).get("records") != 164
            or parent.get("counts", {}).get("resolution")
            != {"ACTIVE_IMPORTED_FUNCTION_LIBRARY": 2, "UNRESOLVED": 162}):
        raise ValueError("parent v2 ledger denominator differs")
    if parent.get("inputs", {}).get("catalog", {}).get("sha256") != sha256(catalog_path):
        raise ValueError("parent v2 ledger catalog binding differs")
    if parent.get("inputs", {}).get("inventory", {}).get("sha256") != sha256(inventory_path):
        raise ValueError("parent v2 ledger inventory binding differs")
    if catalog.get("discoveryInventorySha256") != sha256(inventory_path):
        raise ValueError("catalog inventory binding differs")

    entries = {row["id"]: row for row in inventory["entries"]}
    parent_rows = {row["discoveryId"]: row for row in parent["records"]}
    if set(TARGET_DISCOVERIES) - entries.keys() or set(TARGET_DISCOVERIES) - parent_rows.keys():
        raise ValueError("one of the 13 target template rows is absent")
    if any(parent_rows[discovery].get("resolution") != "UNRESOLVED"
           for discovery in TARGET_DISCOVERIES):
        raise ValueError("target template is not unresolved in parent v2")

    registrations = []
    all_successors = []
    for name in TARGET_NAMES:
        discovery = "common:" + name
        entry = entries[discovery]
        template = "code/exp/" + name + ".dml"
        expected_sha = entry.get("templateSha256")
        if (entry.get("kind") != "unclassified-template"
                or entry.get("status") != "UNSUPPORTED"
                or not isinstance(expected_sha, str)):
            raise ValueError(f"target inventory registration differs: {discovery}")
        live_path = _safe_file(
            evaluation / "planning_study/native/input_templates/common", template, expected_sha)
        source_evidence = parent_rows[discovery].get("sourceEvidence")
        if (source_evidence != [{"path": str(live_path), "expectedSha256": expected_sha,
                                 "actualSha256": expected_sha, "verified": True}]):
            raise ValueError(f"parent target sourceEvidence differs: {discovery}")
        expected_count = 32 if name == "sliceline_fed" else 16
        template_text = live_path.read_text()
        successors = _successor_rows(catalog, frozen_evaluation, template, expected_sha,
                                     template_text, expected_count)
        registrations.append({
            "discoveryId": discovery, "inventoryKind": entry["kind"],
            "inventoryStatus": entry["status"], "resolution": RESOLUTION,
            "templatePath": template, "templateSha256": expected_sha,
            "liveSourcePath": str(live_path),
            "successorCount": len(successors), "successorCellIds": [
                row["cellId"] for row in successors],
            "successorBindingsSha256": canonical_sha(successors),
            "successors": successors,
        })
        all_successors.extend(successors)

    alias_rows = []
    for discovery in HARNESS_ALIAS_DISCOVERIES:
        row = parent_rows.get(discovery)
        if row is None or row.get("resolution") != "UNRESOLVED":
            raise ValueError(f"harness alias is not unresolved in parent v2: {discovery}")
        name = Path(discovery.split(":", 2)[2]).stem
        target = next(item for item in registrations
                      if item["discoveryId"] == "common:" + name)
        evidence = row.get("sourceEvidence") or []
        alias_relative = discovery.split(":", 2)[2]
        alias_path = (evaluation / alias_relative).resolve()
        inventory_alias = entries.get(discovery)
        inventory_source_path = "evaluation:" + alias_relative
        if (not isinstance(inventory_alias, dict)
                or inventory_alias.get("kind") != "unclassified-source"
                or inventory_alias.get("status") != "UNSUPPORTED"
                or inventory_alias.get("sourcePath") != inventory_source_path
                or inventory_alias.get("sourceSha256") != target["templateSha256"]
                or inventory.get("discoveredSourceFiles", {}).get(inventory_source_path)
                != target["templateSha256"]):
            raise ValueError(f"harness alias inventory registration differs: {discovery}")
        expected_evidence = [{"path": str(alias_path),
                              "expectedSha256": target["templateSha256"],
                              "actualSha256": target["templateSha256"], "verified": True}]
        if evidence != expected_evidence or not alias_path.is_file() \
                or sha256(alias_path) != target["templateSha256"]:
            raise ValueError(f"harness alias byte identity differs: {discovery}")
        alias_rows.append({
            "discoveryId": discovery, "candidateTemplateDiscoveryId": target["discoveryId"],
            "sharedSha256": target["templateSha256"], "resolution": "UNRESOLVED",
            "rationale": "BYTE_IDENTITY_DOES_NOT_PROVE_EXACT_PATH_APPLICABILITY",
        })

    result = {
        "schema": "current-scope-template-closure-v3", "status": "COMPLETE",
        "claimScope": "EXACTLY_13_EXACT_PATH_TEMPLATE_DISCOVERIES",
        "inputs": {
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "catalog": {"path": str(catalog_path), "sha256": sha256(catalog_path)},
            "inventory": {"path": str(inventory_path), "sha256": sha256(inventory_path)},
            "parentV2Ledger": {"path": str(parent_path), "sha256": parent_sha},
            "evaluationRoot": str(evaluation.resolve()),
            "frozenEvaluationRoot": str(frozen_evaluation.resolve()),
        },
        "registrations": registrations,
        "candidateHarnessAliases": alias_rows,
        "counts": {"resolvedRegistrations": len(registrations),
                   "successorCells": len(all_successors),
                   "distinctRenderedPrograms": len({
                       row["programSha256"] for row in all_successors}),
                   "sixteenCellRegistrations": sum(row["successorCount"] == 16
                                                    for row in registrations),
                   "thirtyTwoCellRegistrations": sum(row["successorCount"] == 32
                                                      for row in registrations),
                   "unresolvedByteIdenticalHarnessAliases": len(alias_rows)},
        "limitations": [
            "ONLY_THE_13_EXACT_COMMON_TEMPLATE_PATHS_ARE_RESOLVED",
            "BYTE_IDENTICAL_HARNESS_ALIASES_REMAIN_UNRESOLVED",
            "TEMPLATE_BINDING_DOES_NOT_PROVE_RUNTIME_INPUT_DATA_OR_FEDERATION_MAPS",
            "THE_OTHER_PARENT_V2_UNRESOLVED_ROWS_ARE_NOT_RECLASSIFIED",
        ],
    }
    result["recordsSha256"] = canonical_sha({
        "registrations": registrations, "candidateHarnessAliases": alias_rows})
    active = [row for row in catalog["cells"]
              if (row.get("sourceBinding") or {}).get("kind") == "planning-snapshot"
              and (row.get("sourceBinding") or {}).get("conditionStatus") == "SNAPSHOT_ONLY"]
    active_templates = Counter(
        row["sourceBinding"]["plannedCondition"]["case"].get("template") for row in active)
    expected_templates = Counter({"code/exp/" + name + ".dml":
                                  (32 if name == "sliceline_fed" else 16)
                                  for name in TARGET_NAMES})
    all_ids = [cell for registration in registrations
               for cell in registration["successorCellIds"]]
    if active_templates != expected_templates or len(active) != 224:
        raise ValueError("active planning template universe differs")
    if len(all_ids) != 224 or len(set(all_ids)) != 224 \
            or set(all_ids) != {row["id"] for row in active}:
        raise ValueError("global successor cell identity set differs")
    result["activeTemplateUniverse"] = dict(sorted(active_templates.items()))
    result["activeTemplateUniverseSha256"] = canonical_sha(
        result["activeTemplateUniverse"])
    return result


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"scope template receipt changed: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--inventory", type=Path, default=DEFAULT_INVENTORY)
    parser.add_argument("--evaluation-root", type=Path, default=DEFAULT_EVALUATION)
    parser.add_argument("--frozen-evaluation-root", type=Path,
                        default=DEFAULT_FROZEN_EVALUATION)
    parser.add_argument("--parent-v2-ledger", type=Path, default=DEFAULT_PARENT)
    parser.add_argument("--expected-parent-sha256", default=DEFAULT_PARENT_SHA256)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    receipt = build(args.catalog, args.inventory, args.evaluation_root,
                    args.frozen_evaluation_root, args.parent_v2_ledger,
                    args.expected_parent_sha256)
    publish(args.output, render(receipt), args.check)
    print(json.dumps({"output": str(args.output), "status": receipt["status"],
                      "counts": receipt["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
