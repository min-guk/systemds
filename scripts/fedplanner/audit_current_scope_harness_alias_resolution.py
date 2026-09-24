#!/usr/bin/env python3
"""Attach nine unresolved harness render candidates to frozen parent v5."""

import argparse
import hashlib
import importlib.util
import json
import tempfile
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
CLOSURE_PRODUCER = ROOT / "scripts/fedplanner/certify_scope_harness_alias_rows.py"
BASE = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921")
DEFAULT_RECEIPT = BASE / "current-scope-harness-alias-render-candidates-v2/receipt.json"
DEFAULT_OUTPUT = BASE / "current-scope-applicability-audit-v6-harness-alias-candidates/ledger.json"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    data = json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def build(receipt_path=DEFAULT_RECEIPT, catalog_path=None, inventory_path=None,
          evaluation=None, frozen_evaluation=None, source_manifest_path=None,
          expected_source_manifest_sha256=None, parent_path=None,
          expected_parent_sha256=None):
    closure = load_module("scope_harness_alias_closure", CLOSURE_PRODUCER)
    catalog_path = Path(catalog_path or closure.DEFAULT_CATALOG)
    inventory_path = Path(inventory_path or closure.DEFAULT_INVENTORY)
    evaluation = Path(evaluation or closure.DEFAULT_EVALUATION)
    frozen_evaluation = Path(frozen_evaluation or closure.DEFAULT_FROZEN_EVALUATION)
    source_manifest_path = Path(source_manifest_path or closure.DEFAULT_SOURCE_MANIFEST)
    expected_source_manifest_sha256 = (expected_source_manifest_sha256
                                       if expected_source_manifest_sha256 is not None
                                       else closure.DEFAULT_SOURCE_MANIFEST_SHA256)
    parent_path = Path(parent_path or closure.DEFAULT_PARENT)
    expected_parent_sha256 = (expected_parent_sha256 if expected_parent_sha256 is not None
                              else closure.DEFAULT_PARENT_SHA256)
    receipt_path = Path(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    regenerated = closure.build(
        catalog_path, inventory_path, evaluation, frozen_evaluation,
        source_manifest_path, expected_source_manifest_sha256, parent_path,
        expected_parent_sha256)
    if receipt != regenerated:
        raise ValueError("harness alias receipt differs from independent regeneration")
    if (receipt.get("schema") != "current-scope-harness-alias-render-candidates-v2"
            or receipt.get("status") != "COMPLETE"
            or receipt.get("counts", {}).get("candidateRegistrations") != 9
            or receipt.get("counts", {}).get("successorCells") != 144):
        raise ValueError("harness alias receipt contract differs")

    parent = json.loads(parent_path.read_text())
    registrations = {row["discoveryId"]: row for row in receipt["registrations"]}
    records = []
    for row in parent["records"]:
        updated = dict(row)
        registration = registrations.get(row["discoveryId"])
        if registration is not None:
            if row.get("resolution") != "UNRESOLVED":
                raise ValueError("harness alias target is no longer unresolved in parent v5")
            if row.get("currentApplicability") != "UNRESOLVED":
                raise ValueError("harness alias applicability is no longer unresolved")
            updated["harnessAliasRenderCandidateEvidence"] = {
                    "roleCandidate": registration["roleCandidate"],
                    "aliasPath": registration["aliasPath"],
                    "aliasSha256": registration["aliasSha256"],
                    "successorCount": registration["successorCount"],
                    "successorCellIds": registration["successorCellIds"],
                    "successorBindingsSha256": registration["successorBindingsSha256"],
                    "receiptRecordsSha256": receipt["recordsSha256"],
                    "repositoryToStageAncestryProven": False,
                    "activeSelectionOrReferenceProven": False,
                    "resolution": "UNRESOLVED",
                }
        records.append(updated)
    if len(records) != 164 or len({row["discoveryId"] for row in records}) != 164:
        raise ValueError("v6 scope denominator differs")
    counts = Counter(row["resolution"] for row in records)
    expected = {
        "ACTIVE_EXACT_PATH_FROZEN_TEMPLATE": 13,
        "ACTIVE_IMPORTED_FUNCTION_LIBRARY": 2,
        "UNRESOLVED": 149,
    }
    if dict(sorted(counts.items())) != dict(sorted(expected.items())):
        raise ValueError("v6 resolution counts differ")
    unresolved = {row["discoveryId"] for row in records
                  if row["resolution"] == "UNRESOLVED"}
    if closure.EXCLUDED_GNMF not in unresolved or not set(registrations).issubset(unresolved):
        raise ValueError("v6 harness alias resolution set differs")

    return {
        "schema": "current-scope-applicability-audit-v6", "status": "INCOMPLETE",
        "claimScope": "PARENT_V5_PLUS_EXACTLY_9_UNRESOLVED_RENDER_CANDIDATE_EVIDENCE_ROWS",
        "inputs": {
            **parent["inputs"],
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "parentV5Ledger": {"path": str(parent_path), "sha256": sha256(parent_path)},
            "harnessAliasClosureReceipt": {
                "path": str(receipt_path), "sha256": sha256(receipt_path),
                "producerScriptSha256": sha256(CLOSURE_PRODUCER),
                "recordsSha256": receipt["recordsSha256"],
            },
        },
        "counts": {"records": len(records), "resolution": dict(sorted(counts.items())),
                   "remainingUnresolved": len(unresolved),
                   "unresolvedHarnessRenderCandidates": len(registrations),
                   "shaDivergentHarnessAliases": 1},
        "records": records,
        "recordsSha256": canonical_sha(records),
        "limitations": list(parent.get("limitations", [])) + [
            "NO_HARNESS_ALIAS_IS_NEWLY_RESOLVED",
            "149_ROWS_REMAIN_UNRESOLVED",
            "ACTIVE_SELECTION_OR_REFERENCE_BY_THE_FROZEN_PLANNING_COHORT_IS_UNPROVEN",
            "GNMF_HARNESS_SOURCE_REMAINS_UNRESOLVED_DUE_TO_SHA_DIVERGENCE",
            "SOURCE_MANIFEST_STAGE_BYTE_IDENTITY_DOES_NOT_PROVE_REPOSITORY_TO_STAGE_ANCESTRY",
            "RUNTIME_DATA_EXECUTION_IS_OUTSIDE_THIS_STATIC_RENDER_CERTIFICATE",
        ],
    }


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"scope harness alias candidate ledger changed: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--inventory", type=Path)
    parser.add_argument("--evaluation-root", type=Path)
    parser.add_argument("--frozen-evaluation-root", type=Path)
    parser.add_argument("--source-manifest", type=Path)
    parser.add_argument("--expected-source-manifest-sha256")
    parser.add_argument("--parent-v5-ledger", type=Path)
    parser.add_argument("--expected-parent-sha256")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    ledger = build(args.receipt, args.catalog, args.inventory, args.evaluation_root,
                   args.frozen_evaluation_root, args.source_manifest,
                   args.expected_source_manifest_sha256, args.parent_v5_ledger,
                   args.expected_parent_sha256)
    publish(args.output, render(ledger), args.check)
    print(json.dumps({"output": str(args.output), "status": ledger["status"],
                      "counts": ledger["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
