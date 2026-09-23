#!/usr/bin/env python3
"""Build the validated current P/E corpus manifest without inventing inputs.

The manifest exposes the frozen planning cohort that can be executed now and
keeps every registry-derived candidate whose compiler input contract is still
missing in a separate, explicit unresolved list.
"""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile


HERE = Path(__file__).resolve().parent
AUDIT_PATH = HERE / "audit_condition_expansion.py"
SPEC = importlib.util.spec_from_file_location("condition_expansion_audit", AUDIT_PATH)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stable_candidate_id(candidate):
    identity = {key: candidate.get(key) for key in
                ("kind", "discoveryId", "workers", "networkProfile")}
    return "candidate_" + digest(identity)[:20]


def unresolved_reasons(candidate):
    kind = candidate["kind"]
    common = ["CONDITION_BINDING_NOT_FROZEN", "CONDITION_SHA256_NOT_FROZEN"]
    if kind == "planning-snapshot":
        return common + ["PLANNED_CONDITION_NOT_FROZEN"]
    if kind in ("base-campaign", "ml10-campaign"):
        return common + ["DML_AND_ARGUMENT_BINDING_NOT_FROZEN",
                         "INPUT_METADATA_PRIVACY_PARTITIONS_NOT_FROZEN"]
    if kind == "generated-microbench":
        return common + ["GENERATED_DML_NOT_FROZEN",
                         "WORKER_COUNT_NOT_BOUND", "NETWORK_PROFILE_NOT_BOUND",
                         "INPUT_METADATA_PRIVACY_PARTITIONS_NOT_FROZEN"]
    raise ValueError(f"unknown unresolved candidate kind: {kind}")


def verify_source_files(row, evaluation_root):
    source_files = row.get("sourceFiles")
    if not isinstance(source_files, dict) or not source_files:
        raise ValueError(f"frozen cell has no source files: {row.get('id')}")
    for relative, expected in sorted(source_files.items()):
        path = (evaluation_root / relative).resolve()
        if not path.is_relative_to(evaluation_root) or not path.is_file():
            raise ValueError(f"frozen source absent: {row['id']}:{relative}")
        if file_sha(path) != expected:
            raise ValueError(f"frozen source digest differs: {row['id']}:{relative}")


def validate_frozen_cell(row, evaluation_root):
    binding = row.get("sourceBinding")
    if not isinstance(binding, dict) or binding.get("conditionStatus") != "SNAPSHOT_ONLY":
        raise ValueError(f"cell is not a frozen input condition: {row.get('id')}")
    if binding.get("kind") not in ("planning-snapshot", "base-campaign", "ml10-campaign",
                                    "generated-microbench") or binding.get("discoveryId") != row.get("discoveryId"):
        raise ValueError(f"frozen discovery binding differs: {row.get('id')}")
    planned = binding.get("plannedCondition")
    if not isinstance(planned, dict) or set(planned) != {"workers", "case", "network"}:
        raise ValueError(f"frozen planned condition is incomplete: {row.get('id')}")
    condition = {"discoveryId": row["discoveryId"], "conditionId": row["conditionId"], **planned}
    if digest(condition) != binding.get("conditionSha256"):
        raise ValueError(f"frozen condition digest differs: {row.get('id')}")
    if planned["case"].get("workers") != planned["workers"]:
        raise ValueError(f"worker count differs inside frozen condition: {row.get('id')}")
    current = [pair for pair in row.get("expectedPairs", [])
               if pair.get("role") == "CURRENT_PE"]
    if current != [{"applicability": "REQUIRED", "left": "P_C0",
                    "right": "E_C0", "role": "CURRENT_PE"}]:
        raise ValueError(f"current P/E obligation missing or duplicated: {row.get('id')}")
    verify_source_files(row, evaluation_root)
    result = dict(row)
    result["inputValidation"] = {
        "compileModelReady": True,
        "conditionDigestRecomputed": True,
        "sourceFileCount": len(row["sourceFiles"]),
        "sourceDigestsVerified": True,
        "runtimeDataExecutionAssessed": False,
    }
    return result


def deduplicate_unresolved(candidates, placeholders):
    unique = {}
    for candidate in candidates:
        if candidate["inputStatus"] != "UNRESOLVED":
            continue
        candidate_id = stable_candidate_id(candidate)
        row = {
            "candidateId": candidate_id,
            "placeholderCellId": candidate["placeholder"],
            "discoveryId": candidate["discoveryId"],
            "kind": candidate["kind"],
            "workers": candidate.get("workers"),
            "networkProfile": candidate.get("networkProfile"),
            "sourceFiles": placeholders[candidate["placeholder"]].get("sourceFiles", {}),
            "resolutionStatus": "UNRESOLVED",
            "reasons": unresolved_reasons(candidate),
        }
        previous = unique.get(candidate_id)
        if previous is not None and previous != row:
            raise ValueError(f"candidate identity collision: {candidate_id}")
        unique[candidate_id] = row
    return [unique[key] for key in sorted(unique)]


def build(catalog_path, evaluation_root):
    catalog_path = Path(catalog_path).resolve()
    evaluation_root = Path(evaluation_root).resolve()
    catalog = json.loads(catalog_path.read_text())
    if catalog.get("schema") != "closed-comparison-cases-v1":
        raise ValueError("wrong closed comparison catalog schema")
    rows = catalog.get("cells")
    if not isinstance(rows, list) or len(rows) != len({row.get("id") for row in rows}):
        raise ValueError("catalog cell IDs are missing or duplicated")
    for row in rows:
        if row.get("inventoryStatus") != "IN_SCOPE":
            reason = row.get("inventoryReason")
            if not isinstance(reason, str) or not reason.strip():
                raise ValueError(
                    f"{row.get('inventoryStatus')} catalog row lacks an explicit reason: {row.get('id')}")

    expansion = AUDIT.build(catalog_path, evaluation_root)
    in_scope = [row for row in rows if row.get("inventoryStatus") == "IN_SCOPE"]
    frozen_rows = [row for row in in_scope
                   if row.get("sourceBinding", {}).get("conditionStatus") == "SNAPSHOT_ONLY"]
    frozen = [validate_frozen_cell(row, evaluation_root)
              for row in sorted(frozen_rows, key=lambda item: item["id"])]
    placeholders = {row["id"]: row for row in in_scope
                    if row.get("sourceBinding", {}).get("conditionStatus") == "UNRESOLVED"}
    unresolved = deduplicate_unresolved(expansion["candidates"], placeholders)

    unresolved_by_placeholder = {}
    for item in unresolved:
        unresolved_by_placeholder.setdefault(item["placeholderCellId"], []).append(item["candidateId"])
    registry_coverage = []
    for row in sorted(rows, key=lambda item: item["id"]):
        status = row.get("inventoryStatus")
        if status == "IN_SCOPE" and row["id"] in placeholders:
            decision = "UNRESOLVED"
            mapped = unresolved_by_placeholder.get(row["id"], [])
            reason = "registry axes known; compiler input contract is not frozen"
        elif status == "IN_SCOPE":
            decision = "READY"
            mapped = [row["id"]]
            reason = "frozen planning condition and referenced source digests verified"
        else:
            decision = "OUT_OF_SCOPE"
            mapped = []
            reason = row.get("inventoryReason")
            if not isinstance(reason, str) or not reason.strip():
                raise ValueError(f"{status} catalog row lacks an explicit reason: {row['id']}")
        registry_coverage.append({"catalogCellId": row["id"], "discoveryId": row["discoveryId"],
                                  "inventoryStatus": status, "decision": decision,
                                  "mappedIds": mapped, "reason": reason})

    if len(frozen) != expansion["frozenSnapshotRows"]:
        raise ValueError("frozen expansion count differs from validated cohort")
    if len(unresolved) != expansion["unresolvedCandidates"]:
        raise ValueError("deduplicated unresolved count differs from registry expansion")
    mapped_placeholders = {item["placeholderCellId"] for item in unresolved}
    if mapped_placeholders != set(placeholders):
        raise ValueError("one or more unresolved placeholders have no expanded candidate")

    ready_ids = {row["id"] for row in frozen}
    in_scope_ids = {row["id"] for row in in_scope}
    all_in_scope_frozen = ready_ids == in_scope_ids
    if not unresolved and not all_in_scope_frozen:
        raise ValueError("empty unresolved list does not cover every in-scope catalog cell")
    overall_complete = not unresolved and all_in_scope_frozen
    frozen_kinds = {row["sourceBinding"]["kind"] for row in frozen}
    if overall_complete:
        declared_scope = "FULL_CURRENT"
    elif frozen_kinds <= {"planning-snapshot"}:
        declared_scope = f"PLANNING_COHORT_{len(frozen)}"
    else:
        declared_scope = f"FROZEN_COHORT_{len(frozen)}"
    manifest = {
        "schema": "current-pe-corpus-manifest-v1",
        "status": "COMPLETE" if overall_complete else "INCOMPLETE",
        "scope": declared_scope,
        "claimScope": "FROZEN_COMPILE_MODEL_COHORT_ONLY" if unresolved else "FULL_CURRENT",
        "catalog": {"path": str(catalog_path), "sha256": file_sha(catalog_path)},
        "evaluation": {"path": str(evaluation_root)},
        "auditScriptSha256": file_sha(AUDIT_PATH),
        "producerScriptSha256": file_sha(Path(__file__)),
        "fullCurrentRequirements": {
            "allInScopeCatalogCellsFrozen": all_in_scope_frozen,
            "cellsExactlyMatchInScopeCatalogIds": all_in_scope_frozen,
            "unresolvedCandidatesEmpty": not unresolved,
            "expandedCandidatesRequireAugmentedFrozenCatalogRows": True,
        },
        "counts": {
            "catalogRows": len(rows), "inScopePlaceholders": len(in_scope),
            "readyCells": len(frozen), "unresolvedCandidates": len(unresolved),
            "unsupportedRows": sum(row.get("inventoryStatus") == "UNSUPPORTED" for row in rows),
            "historicalRows": sum(row.get("inventoryStatus") == "HISTORICAL" for row in rows),
        },
        "cells": frozen,
        "unresolved": unresolved,
        "registryCoverage": registry_coverage,
    }
    unresolved_report = {
        "schema": "current-pe-corpus-unresolved-v1",
        "status": "COMPLETE" if not unresolved else "INCOMPLETE",
        "manifestBinding": {"catalogSha256": manifest["catalog"]["sha256"],
                            "evaluationPath": manifest["evaluation"]["path"]},
        "count": len(unresolved), "records": unresolved,
    }
    return manifest, unresolved_report


def render(value):
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"corpus artifact changed: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path, required=True)
    parser.add_argument("--unresolved-output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest, unresolved = build(args.catalog, args.evaluation_root)
    publish(args.manifest_output, render(manifest), args.check)
    publish(args.unresolved_output, render(unresolved), args.check)
    print(json.dumps({"status": manifest["status"], "scope": manifest["scope"],
                      **manifest["counts"], "manifest": str(args.manifest_output),
                      "unresolved": str(args.unresolved_output)}, sort_keys=True))


if __name__ == "__main__":
    main()
