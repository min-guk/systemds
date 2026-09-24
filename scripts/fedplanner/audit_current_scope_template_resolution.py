#!/usr/bin/env python3
"""Apply the independently regenerated 13-row template receipt to parent v2."""

import argparse
import hashlib
import importlib.util
import json
import tempfile
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
CLOSURE_PRODUCER = ROOT / "scripts/fedplanner/certify_scope_template_rows.py"
BASE = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921")
DEFAULT_RECEIPT = BASE / "current-scope-template-closure-v3/receipt.json"
DEFAULT_OUTPUT = BASE / "current-scope-applicability-audit-v5-template-closure/ledger.json"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"),
                         ensure_ascii=False).encode()
    return hashlib.sha256(encoded).hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def build(receipt_path=DEFAULT_RECEIPT, catalog_path=None, inventory_path=None,
          evaluation=None, frozen_evaluation=None, parent_path=None,
          expected_parent_sha256=None):
    closure = load_module("scope_template_closure", CLOSURE_PRODUCER)
    catalog_path = Path(catalog_path or closure.DEFAULT_CATALOG)
    inventory_path = Path(inventory_path or closure.DEFAULT_INVENTORY)
    evaluation = Path(evaluation or closure.DEFAULT_EVALUATION)
    frozen_evaluation = Path(frozen_evaluation or closure.DEFAULT_FROZEN_EVALUATION)
    parent_path = Path(parent_path or closure.DEFAULT_PARENT)
    expected_parent_sha256 = (expected_parent_sha256
                              if expected_parent_sha256 is not None
                              else closure.DEFAULT_PARENT_SHA256)
    receipt_path = Path(receipt_path)
    receipt = json.loads(receipt_path.read_text())
    regenerated = closure.build(catalog_path, inventory_path, evaluation,
                                frozen_evaluation, parent_path, expected_parent_sha256)
    if receipt != regenerated:
        raise ValueError("template receipt differs from independent regeneration")
    if (receipt.get("schema") != "current-scope-template-closure-v3"
            or receipt.get("status") != "COMPLETE"
            or receipt.get("counts", {}).get("resolvedRegistrations") != 13):
        raise ValueError("template receipt contract differs")

    parent = json.loads(parent_path.read_text())
    registrations = {row["discoveryId"]: row for row in receipt["registrations"]}
    aliases = {row["discoveryId"]: row for row in receipt["candidateHarnessAliases"]}
    records = []
    for row in parent["records"]:
        discovery = row["discoveryId"]
        updated = dict(row)
        if discovery in registrations:
            registration = registrations[discovery]
            if row.get("resolution") != "UNRESOLVED":
                raise ValueError("template target is no longer unresolved in parent v2")
            updated.update({
                "resolution": closure.RESOLUTION,
                "currentApplicability": "ACTIVE_IN_FROZEN_PLANNING_COHORT",
                "rationale": ("Exact inventory path and source SHA match every bound frozen "
                              "planning successor condition."),
                "templateClosureEvidence": {
                    "templatePath": registration["templatePath"],
                    "templateSha256": registration["templateSha256"],
                    "successorCount": registration["successorCount"],
                    "successorCellIds": registration["successorCellIds"],
                    "successorBindingsSha256": registration["successorBindingsSha256"],
                    "receiptRecordsSha256": receipt["recordsSha256"],
                },
            })
        elif discovery in aliases:
            if row.get("resolution") != "UNRESOLVED":
                raise ValueError("harness alias is no longer unresolved in parent v2")
            updated["templateClosureCandidateEvidence"] = aliases[discovery]
        records.append(updated)
    if len(records) != 164 or len({row["discoveryId"] for row in records}) != 164:
        raise ValueError("v3 scope denominator differs")
    counts = Counter(row["resolution"] for row in records)
    expected = {"ACTIVE_IMPORTED_FUNCTION_LIBRARY": 2,
                closure.RESOLUTION: 13, "UNRESOLVED": 149}
    if dict(sorted(counts.items())) != dict(sorted(expected.items())):
        raise ValueError("v3 resolution counts differ")
    unresolved = {row["discoveryId"] for row in records
                  if row["resolution"] == "UNRESOLVED"}
    if not set(aliases).issubset(unresolved):
        raise ValueError("byte-identical harness alias was promoted")

    result = {
        "schema": "current-scope-applicability-audit-v5", "status": "INCOMPLETE",
        "claimScope": "PARENT_V2_PLUS_EXACTLY_13_TEMPLATE_PATH_RESOLUTIONS",
        "inputs": {
            **parent["inputs"],
            "producerScript": {"path": str(HERE), "sha256": sha256(HERE)},
            "parentV2Ledger": {"path": str(parent_path), "sha256": sha256(parent_path)},
            "templateClosureReceipt": {
                "path": str(receipt_path), "sha256": sha256(receipt_path),
                "producerScriptSha256": sha256(CLOSURE_PRODUCER),
                "recordsSha256": receipt["recordsSha256"],
            },
        },
        "counts": {"records": len(records), "resolution": dict(sorted(counts.items())),
                   "remainingUnresolved": len(unresolved),
                   "unresolvedByteIdenticalHarnessAliases": len(aliases)},
        "records": records,
        "recordsSha256": canonical_sha(records),
        "limitations": list(parent.get("limitations", [])) + [
            "ONLY_13_EXACT_PATH_TEMPLATE_ROWS_ARE_NEWLY_RESOLVED",
            "149_ROWS_REMAIN_UNRESOLVED",
            "NINE_BYTE_IDENTICAL_HARNESS_ALIASES_REMAIN_UNRESOLVED",
            "TEMPLATE_BINDING_DOES_NOT_PROVE_RUNTIME_INPUT_DATA_OR_FEDERATION_MAPS",
        ],
    }
    return result


def publish(path, content, check):
    path = Path(path)
    if check:
        if not path.is_file() or path.read_text() != content:
            raise SystemExit(f"scope template ledger changed: {path}")
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
    parser.add_argument("--parent-v2-ledger", type=Path)
    parser.add_argument("--expected-parent-sha256")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    ledger = build(args.receipt, args.catalog, args.inventory, args.evaluation_root,
                   args.frozen_evaluation_root, args.parent_v2_ledger,
                   args.expected_parent_sha256)
    publish(args.output, render(ledger), args.check)
    print(json.dumps({"output": str(args.output), "status": ledger["status"],
                      "counts": ledger["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
