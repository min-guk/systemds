#!/usr/bin/env python3
"""Record render-equivalence evidence for nine unresolved ML10 harness aliases."""

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
DEFAULT_SOURCE_MANIFEST = DEFAULT_EVALUATION / "source-manifest.json"
DEFAULT_SOURCE_MANIFEST_SHA256 = "b4001cfb4e49b22ba7b9ecb0589d694534063f9c314004fef4bf13ae6efbf343"
DEFAULT_PARENT = BASE / "current-scope-applicability-audit-v5-template-closure/ledger.json"
DEFAULT_PARENT_SHA256 = "cce670d6c863b2d0910c87b88e6048a3f46784957ac47efa6129da50e373b001"
DEFAULT_OUTPUT = BASE / "current-scope-harness-alias-render-candidates-v2/receipt.json"

TARGET_NAMES = ("als_fed", "glm_fed", "gmm_fed", "kmeans_fed", "l2svm_fed",
                "lm_fed", "logreg_fed", "pca_fed", "steplm_fed")
TARGET_DISCOVERIES = tuple(
    "unclassified:evaluation:harness/sigmod2021-exdra-p523/experiments/code/exp/"
    + name + ".dml" for name in TARGET_NAMES)
EXCLUDED_GNMF = (
    "unclassified:evaluation:harness/sigmod2021-exdra-p523/experiments/code/exp/"
    "gnmf_fed.dml")
PROFILES = ("lan", "wan_heavy", "wan_light", "wan_mid")
WORKERS = (1, 3, 5, 7)
RESOLUTION = "UNRESOLVED"
TOKEN = re.compile(r"__[A-Z0-9_]+__")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    data = json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()


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
    return {"conditionId": row["conditionId"], "discoveryId": row["discoveryId"],
            **row["sourceBinding"]["plannedCondition"]}


def _dml_strings(values):
    return "list(" + ", ".join(json.dumps(value) for value in values) + ")"


def _dml_ranges(values):
    return "list(" + ", ".join(f"list({row}, {column})"
                                for row, column in values) + ")"


def _input(partitions, name, workers, metadata):
    entry = partitions.get("partitions", {}).get(name)
    if not isinstance(entry, dict) or entry.get("global_metadata") != metadata:
        raise ValueError(f"input metadata differs: {name}")
    shards = entry.get("partitions")
    if not isinstance(shards, list) or len(shards) != workers:
        raise ValueError(f"input worker count differs: {name}")
    addresses, ranges, end = [], [], 0
    for ordinal, shard in enumerate(shards, 1):
        if (shard.get("worker") != ordinal or shard.get("begin") != [end, 0]
                or shard.get("end", [None, None])[1] != metadata["cols"]):
            raise ValueError(f"input partition authority differs: {name}")
        metadata_path = shard.get("metadata")
        if not isinstance(metadata_path, str) or not metadata_path.endswith(".data.mtd"):
            raise ValueError(f"input metadata path differs: {name}")
        data_name = Path(metadata_path).name[:-4]
        addresses.append(f"worker{ordinal}:{8000 + ordinal}/data/{data_name}")
        ranges.extend((shard["begin"], shard["end"]))
        end = shard["end"][0]
    if end != metadata["rows"]:
        raise ValueError(f"input row coverage differs: {name}")
    return {"role": None, "mapping": name, "addresses": addresses,
            "ranges": ranges, "metadata": metadata}


def _render(template, inputs, output, rows, columns, seed):
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
    for token, value in replacements.items():
        template = template.replace(token, value)
    remaining = sorted(set(TOKEN.findall(template)))
    if remaining:
        raise ValueError(f"independent alias render left tokens: {remaining}")
    return template


def _successor(catalog_row, name, alias_text, alias_sha, frozen_evaluation):
    binding = catalog_row.get("sourceBinding") or {}
    planned = binding.get("plannedCondition") or {}
    case = planned.get("case") or {}
    workers = planned.get("workers")
    workload = name[:-4]
    if name == "gmm_fed":
        workload = "gmm-vvi"
    if (catalog_row.get("inventoryStatus") != "IN_SCOPE"
            or binding.get("kind") != "planning-snapshot"
            or binding.get("conditionStatus") != "SNAPSHOT_ONLY"
            or workers not in WORKERS or case.get("workers") != workers
            or catalog_row.get("discoveryId") != f"planning-w{workers}:{workload}"
            or binding.get("discoveryId") != catalog_row.get("discoveryId")
            or case.get("template") != f"code/exp/{name}.dml"
            or case.get("template_sha256") != alias_sha
            or case.get("workload") != workload):
        raise ValueError(f"successor role authority differs: {catalog_row.get('id')}")
    if canonical_sha(_condition_identity(catalog_row)) != binding.get("conditionSha256"):
        raise ValueError(f"successor condition hash differs: {catalog_row['id']}")
    # These planning snapshots intentionally carry no extra compiler flags.  Bind that
    # empty argv contract explicitly so later additions cannot be silently inherited.
    if binding.get("compilerArgv", []) != []:
        raise ValueError(f"successor compiler argv differs: {catalog_row['id']}")

    prefix = Path("planning_study/native")
    context_relative = prefix / f"context-w{workers}.json"
    protocol_relative = prefix / f"protocol-w{workers}.json"
    inputs_relative = prefix / f"input_templates/w{workers}/metadata/inputs.json"
    partitions_relative = prefix / f"input_templates/w{workers}/metadata/worker-partitions.json"
    files = catalog_row.get("sourceFiles") or {}
    for relative, expected in files.items():
        _safe_file(frozen_evaluation, relative, expected)
    for relative in (context_relative, protocol_relative, inputs_relative,
                     partitions_relative):
        if relative.as_posix() not in files:
            raise ValueError(f"successor authority file absent: {catalog_row['id']}")
    context = read_json(Path(frozen_evaluation) / context_relative)
    protocol = read_json(Path(frozen_evaluation) / protocol_relative)
    partitions = read_json(Path(frozen_evaluation) / partitions_relative)
    matching = [row for row in context.get("cases", [])
                if row.get("program") == case.get("program")]
    if matching != [case]:
        raise ValueError(f"catalog/context full case differs: {catalog_row['id']}")
    fixed = context.get("fixed_cost_environment") or {}
    seed = protocol.get("systemds_seed")
    if (context.get("workers") != workers or protocol.get("workers") != workers
            or partitions.get("workers") != workers
            or protocol.get("context_sha256") != files[context_relative.as_posix()]
            or context.get("input_manifest_sha256") != files[inputs_relative.as_posix()]
            or context.get("worker_partition_manifest_sha256")
            != files[partitions_relative.as_posix()]
            or context.get("profiles", {}).get(catalog_row["conditionId"])
            != planned.get("network")
            or (context.get("privacy_experiment") or {}).get("name")
            != case.get("privacy_experiment")
            or not isinstance(seed, int) or fixed.get("SYSTEMDS_SEED") != str(seed)
            or fixed.get("SYSTEMDS_DEFAULT_SEED") != str(seed)):
        raise ValueError(f"successor protocol authority differs: {catalog_row['id']}")

    roles = ("X", "Y") if case.get("Y_applicable") else ("X",)
    mappings, metadata = case.get("worker_input_mappings"), case.get("metadata") or {}
    if (not isinstance(mappings, list) or len(mappings) != len(roles)
            or set(metadata) != set(roles)):
        raise ValueError(f"successor input role differs: {catalog_row['id']}")
    inputs = []
    for role, mapping in zip(roles, mappings):
        value = _input(partitions, mapping, workers, metadata[role])
        value["role"] = role
        inputs.append(value)
    output = case.get("forbidden_output")
    rendered = _render(alias_text, inputs, output, metadata["X"]["rows"],
                       metadata["X"]["cols"], seed)
    rendered_sha = hashlib.sha256(rendered.encode()).hexdigest()
    program_relative = prefix / f"input_templates/w{workers}" / case["program"]
    program_sha = case.get("program_sha256")
    program_path = _safe_file(frozen_evaluation, program_relative, program_sha)
    if rendered.encode() != program_path.read_bytes():
        raise ValueError(f"alias render bytes differ: {catalog_row['id']}")
    if files.get(program_relative.as_posix()) != program_sha:
        raise ValueError(f"successor program source binding differs: {catalog_row['id']}")

    network = planned.get("network")
    privacy = {role: metadata[role].get("privacy") for role in roles}
    context_privacy = context.get("privacy_experiment") or {}
    if (case.get("privacy_experiment") != "mixed"
            or privacy.get("X") != "private-aggregate"
            or any(value not in ("private-aggregate", "public")
                   for value in privacy.values())
            or any(context_privacy.get(role) != value
                   for role, value in privacy.items())
            or not isinstance(network, dict) or set(network) != {
                "c2w_mbit", "cost_environment", "rtt_ms", "w2c_mbit"}):
        raise ValueError(f"successor privacy/network differs: {catalog_row['id']}")
    evidence = {
        "cellId": catalog_row["id"], "discoveryId": catalog_row["discoveryId"],
        "conditionId": catalog_row["conditionId"], "workers": workers,
        "conditionSha256": binding["conditionSha256"],
        "fullPlannedConditionSha256": canonical_sha(planned),
        "programPath": program_relative.as_posix(), "programSha256": program_sha,
        "independentRenderedProgramSha256": rendered_sha,
        "inputs": inputs, "inputsSha256": canonical_sha(inputs),
        "compilerArgv": [], "compilerArgvSha256": canonical_sha([]),
        "privacyExperiment": case["privacy_experiment"], "privacyByRole": privacy,
        "privacySha256": canonical_sha(privacy),
        "network": network, "networkSha256": canonical_sha(network),
        "output": output, "sourceFilesSha256": canonical_sha(files),
        "frozenInputsManifestPath": inputs_relative.as_posix(),
        "frozenInputsManifestSha256": files[inputs_relative.as_posix()],
    }
    return evidence


def build(catalog_path=DEFAULT_CATALOG, inventory_path=DEFAULT_INVENTORY,
          evaluation=DEFAULT_EVALUATION, frozen_evaluation=DEFAULT_FROZEN_EVALUATION,
          source_manifest_path=DEFAULT_SOURCE_MANIFEST,
          expected_source_manifest_sha256=DEFAULT_SOURCE_MANIFEST_SHA256,
          parent_path=DEFAULT_PARENT, expected_parent_sha256=DEFAULT_PARENT_SHA256):
    catalog_path, inventory_path = Path(catalog_path), Path(inventory_path)
    evaluation, frozen_evaluation = Path(evaluation), Path(frozen_evaluation)
    source_manifest_path, parent_path = Path(source_manifest_path), Path(parent_path)
    if expected_source_manifest_sha256 and sha256(source_manifest_path) != expected_source_manifest_sha256:
        raise ValueError("source manifest bytes differ")
    if expected_parent_sha256 and sha256(parent_path) != expected_parent_sha256:
        raise ValueError("parent v5 ledger bytes differ")
    catalog, inventory = read_json(catalog_path), read_json(inventory_path)
    manifest, parent = read_json(source_manifest_path), read_json(parent_path)
    if manifest.get("schema") != "cofee-evaluation-source-manifest/v1":
        raise ValueError("source manifest schema differs")
    if (parent.get("schema") != "current-scope-applicability-audit-v5"
            or parent.get("counts", {}).get("remainingUnresolved") != 149
            or parent.get("inputs", {}).get("catalog", {}).get("sha256") != sha256(catalog_path)
            or parent.get("inputs", {}).get("inventory", {}).get("sha256") != sha256(inventory_path)):
        raise ValueError("parent v5 ledger contract differs")
    if catalog.get("discoveryInventorySha256") != sha256(inventory_path):
        raise ValueError("catalog inventory binding differs")
    entries = {row["id"]: row for row in inventory["entries"]}
    parent_rows = {row["discoveryId"]: row for row in parent["records"]}
    manifest_rows = {row["path"]: row for row in manifest.get("files", [])}
    if (len(entries) != len(inventory["entries"])
            or len(parent_rows) != len(parent["records"])
            or len(manifest_rows) != len(manifest.get("files", []))):
        raise ValueError("scope input identities are duplicated")
    if set(TARGET_DISCOVERIES) - parent_rows.keys() or set(TARGET_DISCOVERIES) - entries.keys():
        raise ValueError("one of the nine target aliases is absent")
    if parent_rows.get(EXCLUDED_GNMF, {}).get("resolution") != "UNRESOLVED":
        raise ValueError("GNMF exclusion denominator differs")

    registrations, all_successors = [], []
    for name, discovery in zip(TARGET_NAMES, TARGET_DISCOVERIES):
        relative = "harness/sigmod2021-exdra-p523/experiments/code/exp/" + name + ".dml"
        entry, parent_row, manifest_row = entries[discovery], parent_rows[discovery], manifest_rows.get(relative)
        expected_sha = entry.get("sourceSha256")
        alias_path = _safe_file(evaluation, relative, expected_sha)
        if (entry.get("kind") != "unclassified-source" or entry.get("status") != "UNSUPPORTED"
                or entry.get("sourcePath") != "evaluation:" + relative
                or parent_row.get("resolution") != "UNRESOLVED"):
            raise ValueError(f"alias inventory/parent role differs: {discovery}")
        candidate = parent_row.get("templateClosureCandidateEvidence") or {}
        if (candidate.get("discoveryId") != discovery
                or candidate.get("candidateTemplateDiscoveryId") != "common:" + name
                or candidate.get("sharedSha256") != expected_sha
                or candidate.get("resolution") != "UNRESOLVED"):
            raise ValueError(f"alias frozen role candidate differs: {discovery}")
        if (not isinstance(manifest_row, dict) or manifest_row.get("path") != relative
                or manifest_row.get("sha256") != expected_sha
                or manifest_row.get("source_sha256") != expected_sha
                or manifest_row.get("adapted") is not False):
            raise ValueError(f"alias source manifest differs: {discovery}")
        source_path = Path(manifest_row.get("source_path", ""))
        expected_source_path = (Path(manifest.get("source_roots", {}).get(
            "active_stage_experiments", "")) / "code/exp" / (name + ".dml")).resolve()
        if (source_path.resolve() != expected_source_path or not source_path.is_file()
                or sha256(source_path) != expected_sha):
            raise ValueError(f"alias ML10 source bytes differ: {discovery}")
        expected_evidence = [{"path": str(alias_path.resolve()),
                              "expectedSha256": expected_sha,
                              "actualSha256": expected_sha, "verified": True}]
        if parent_row.get("sourceEvidence") != expected_evidence:
            raise ValueError(f"alias parent source evidence differs: {discovery}")
        template = "code/exp/" + name + ".dml"
        successor_rows = [row for row in catalog["cells"]
                          if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                          .get("case", {}).get("template") == template]
        workload = "gmm-vvi" if name == "gmm_fed" else name[:-4]
        role_rows = []
        for row in catalog["cells"]:
            planned = (row.get("sourceBinding") or {}).get("plannedCondition") or {}
            expected_discovery = f"planning-w{planned.get('workers')}:{workload}"
            if row.get("discoveryId") == expected_discovery:
                role_rows.append(row)
        if {row["id"] for row in successor_rows} != {row["id"] for row in role_rows}:
            raise ValueError(f"alias full role successor coverage differs: {discovery}")
        successors = sorted((_successor(row, name, alias_path.read_text(), expected_sha,
                                        frozen_evaluation)
                             for row in successor_rows), key=lambda row: row["cellId"])
        axes = Counter((row["workers"], row["conditionId"]) for row in successors)
        if len(successors) != 16 or set(axes) != {
                (worker, profile) for worker in WORKERS for profile in PROFILES} \
                or set(axes.values()) != {1}:
            raise ValueError(f"alias successor coverage differs: {discovery}")
        registration = {
            "discoveryId": discovery, "resolution": RESOLUTION,
            "roleCandidate": "FULL_CONDITION_RENDER_EQUIVALENT_TEMPLATE_CANDIDATE",
            "aliasPath": relative, "aliasSha256": expected_sha,
            "sourceManifestEntry": manifest_row,
            "sourceStageByteIdentityOnly": True,
            "repositoryToStageAncestryProven": False,
            "successorCount": len(successors),
            "successorCellIds": [row["cellId"] for row in successors],
            "successorBindingsSha256": canonical_sha(successors),
            "successors": successors,
        }
        registrations.append(registration)
        all_successors.extend(successors)

    if (len(all_successors) != 144
            or len({row["cellId"] for row in all_successors}) != 144):
        raise ValueError("global alias successor identity set differs")

    gnmf_entry = entries.get(EXCLUDED_GNMF) or {}
    gnmf_relative = EXCLUDED_GNMF.split(":", 2)[2]
    gnmf_manifest = manifest_rows.get(gnmf_relative) or {}
    gnmf_frozen_shas = {
        ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
        .get("case", {}).get("template_sha256") for row in catalog["cells"]
        if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
        .get("case", {}).get("template") == "code/exp/gnmf_fed.dml"
    }
    gnmf_alias_sha = gnmf_entry.get("sourceSha256")
    if (len(gnmf_frozen_shas) != 1 or not isinstance(gnmf_alias_sha, str)
            or gnmf_alias_sha in gnmf_frozen_shas
            or gnmf_manifest.get("sha256") != gnmf_alias_sha
            or gnmf_manifest.get("source_sha256") != gnmf_alias_sha
            or gnmf_manifest.get("adapted") is not False
            or sha256(_safe_file(evaluation, gnmf_relative, gnmf_alias_sha)) != gnmf_alias_sha):
        raise ValueError("GNMF SHA-divergence exclusion evidence differs")
    gnmf_frozen_sha = next(iter(gnmf_frozen_shas))

    result = {
        "schema": "current-scope-harness-alias-render-candidates-v2", "status": "COMPLETE",
        "claimScope": "EXACTLY_9_UNRESOLVED_FULL_CONDITION_RENDER_EQUIVALENCE_CANDIDATES",
        "inputs": {
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "catalog": {"path": str(catalog_path), "sha256": sha256(catalog_path)},
            "inventory": {"path": str(inventory_path), "sha256": sha256(inventory_path)},
            "sourceManifest": {"path": str(source_manifest_path),
                               "sha256": sha256(source_manifest_path)},
            "parentV5Ledger": {"path": str(parent_path), "sha256": sha256(parent_path)},
            "evaluationRoot": str(evaluation.resolve()),
            "frozenEvaluationRoot": str(frozen_evaluation.resolve()),
        },
        "registrations": registrations,
        "excluded": [{"discoveryId": EXCLUDED_GNMF,
                      "reason": "SOURCE_SHA_DIVERGES_FROM_FROZEN_PLANNING_TEMPLATE",
                      "aliasSha256": gnmf_alias_sha,
                      "frozenTemplateSha256": gnmf_frozen_sha}],
        "counts": {"candidateRegistrations": len(registrations),
                   "successorCells": len(all_successors),
                   "distinctRenderedPrograms": len({row["programSha256"]
                                                     for row in all_successors}),
                   "excludedShaDivergentAliases": 1},
        "limitations": [
            "NO_HARNESS_ALIAS_IS_RESOLVED_BY_RENDER_EQUIVALENCE",
            "ACTIVE_SELECTION_OR_REFERENCE_BY_THE_FROZEN_PLANNING_COHORT_IS_UNPROVEN",
            "SOURCE_MANIFEST_STAGE_BYTE_IDENTITY_DOES_NOT_PROVE_REPOSITORY_TO_STAGE_ANCESTRY",
            "GNMF_HARNESS_SOURCE_REMAINS_UNRESOLVED_DUE_TO_SHA_DIVERGENCE",
            "RUNTIME_DATA_EXECUTION_IS_OUTSIDE_THIS_STATIC_RENDER_CERTIFICATE",
            "ALL_PARENT_V5_RESOLUTIONS_AND_149_UNRESOLVED_ROWS_ARE_PRESERVED",
        ],
    }
    result["recordsSha256"] = canonical_sha(registrations)
    return result


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"scope harness alias candidate receipt changed: {path}")
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
    parser.add_argument("--frozen-evaluation-root", type=Path, default=DEFAULT_FROZEN_EVALUATION)
    parser.add_argument("--source-manifest", type=Path, default=DEFAULT_SOURCE_MANIFEST)
    parser.add_argument("--expected-source-manifest-sha256", default=DEFAULT_SOURCE_MANIFEST_SHA256)
    parser.add_argument("--parent-v5-ledger", type=Path, default=DEFAULT_PARENT)
    parser.add_argument("--expected-parent-sha256", default=DEFAULT_PARENT_SHA256)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    receipt = build(args.catalog, args.inventory, args.evaluation_root,
                    args.frozen_evaluation_root, args.source_manifest,
                    args.expected_source_manifest_sha256, args.parent_v5_ledger,
                    args.expected_parent_sha256)
    publish(args.output, render(receipt), args.check)
    print(json.dumps({"output": str(args.output), "status": receipt["status"],
                      "counts": receipt["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
