#!/usr/bin/env python3
"""Apply the independently regenerated two-row library receipt to the v1 audit."""

import argparse
import hashlib
import importlib.util
import json
from collections import Counter
from pathlib import Path


HERE = Path(__file__).resolve()
ROOT = HERE.parents[2]
V1_PRODUCER = ROOT / "scripts/fedplanner/audit_current_scope_applicability.py"
CLOSURE_PRODUCER = ROOT / "scripts/fedplanner/certify_scope_library_rows.py"
DEFAULT_RECEIPT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "current-scope-library-closure-v3/receipt.json")
DEFAULT_OUTPUT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "current-scope-applicability-audit-v2-library-closure-v2/ledger.json")
RESOLUTION = "ACTIVE_IMPORTED_FUNCTION_LIBRARY"
RESOLVED_DISCOVERIES = (
    "common:gmm_p1_compat",
    "unclassified:evaluation:planning_study/native/input_templates/common/"
    "code/workloads/sliceline/slicefinder_core.dml",
)
OPTIONAL_GMM_DISCOVERY = "optional:gmm_p1_compat"


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
          evaluation=None, legacy=None, frozen_evaluation=None, build_root=ROOT):
    v1_module = load_module("current_scope_v1", V1_PRODUCER)
    closure = load_module("scope_library_closure", CLOSURE_PRODUCER)
    catalog_path = Path(catalog_path or v1_module.DEFAULT_CATALOG)
    inventory_path = Path(inventory_path or v1_module.DEFAULT_INVENTORY)
    evaluation = Path(evaluation or v1_module.DEFAULT_EVALUATION)
    legacy = Path(legacy or v1_module.DEFAULT_LEGACY)
    frozen_evaluation = Path(frozen_evaluation or v1_module.DEFAULT_FROZEN_EVALUATION)
    build_root = Path(build_root)

    v1 = v1_module.build(catalog_path, inventory_path, evaluation, legacy,
                         frozen_evaluation)
    if (v1.get("schema") != "current-scope-applicability-audit-v1"
            or v1.get("counts", {}).get("records") != 164
            or v1.get("counts", {}).get("resolution") != {"UNRESOLVED": 164}
            or v1["inputs"]["producerScript"]["sha256"] != sha256(V1_PRODUCER)):
        raise ValueError("parent v1 producer or denominator differs")

    receipt_path = Path(receipt_path)
    if not receipt_path.is_file():
        raise ValueError(f"library closure receipt missing: {receipt_path}")
    receipt_text = receipt_path.read_text()
    receipt = json.loads(receipt_text)
    if (receipt.get("schema") != "current-scope-library-closure-v3"
            or receipt.get("status") != "COMPLETE"
            or receipt.get("claimScope") != "EXACTLY_TWO_RESOLVED_SOURCE_DISCOVERIES"):
        raise ValueError("library closure receipt contract differs")
    inputs = receipt.get("inputs") or {}
    producer = inputs.get("producerScript") or {}
    if (Path(producer.get("path", "")).resolve() != CLOSURE_PRODUCER.resolve()
            or producer.get("sha256") != sha256(CLOSURE_PRODUCER)):
        raise ValueError("library closure producer binding differs")
    if ((inputs.get("catalog") or {}).get("sha256") != sha256(catalog_path)
            or Path((inputs.get("catalog") or {}).get("path", "")).resolve()
            != catalog_path.resolve()
            or (inputs.get("inventory") or {}).get("sha256") != sha256(inventory_path)
            or Path((inputs.get("inventory") or {}).get("path", "")).resolve()
            != inventory_path.resolve()
            or Path(inputs.get("evaluationRoot", "")).resolve()
            != frozen_evaluation.resolve()):
        raise ValueError("library closure receipt input binding differs")
    recomputed = closure.build(catalog_path, inventory_path, frozen_evaluation,
                               build_root)
    if closure.render(recomputed) != receipt_text:
        raise ValueError("library closure receipt changed during independent regeneration")

    registrations = receipt.get("registrations")
    if (not isinstance(registrations, list) or len(registrations) != 2
            or {row.get("discoveryId") for row in registrations}
            != set(RESOLVED_DISCOVERIES)
            or any(row.get("resolution") != RESOLUTION for row in registrations)):
        raise ValueError("library closure registration set differs")
    aliases = receipt.get("candidateAliasEvidence")
    if (not isinstance(aliases, list) or len(aliases) != 1
            or aliases[0].get("discoveryId") != OPTIONAL_GMM_DISCOVERY
            or aliases[0].get("resolution") != "UNRESOLVED"):
        raise ValueError("optional gmm candidate alias evidence differs")
    counts = receipt.get("counts") or {}
    bindings = receipt.get("activeCellBindings") or {}
    if (counts.get("resolvedRegistrations") != 2
            or counts.get("unresolvedCandidateAliases") != 1
            or counts.get("p1ActiveCells") != 16
            or counts.get("slicelineActiveCells") != 32
            or len(bindings.get("p1", [])) != 16
            or len(bindings.get("sliceline", [])) != 32):
        raise ValueError("library closure active-cell denominator differs")

    registration_by_id = {row["discoveryId"]: row for row in registrations}
    receipt_sha = sha256(receipt_path)
    records = []
    resolved = set()
    for original in v1["records"]:
        row = dict(original)
        discovery = row["discoveryId"]
        if discovery in registration_by_id:
            registration = registration_by_id[discovery]
            library = registration["library"]
            library_record = receipt["libraries"].get(library)
            if (not isinstance(library_record, dict)
                    or library_record.get("sourcePath") != registration.get("sourcePath")
                    or library_record.get("sourceSha256") != registration.get("sourceSha256")):
                raise ValueError("library closure source record binding differs")
            receipt_root = Path(inputs["evaluationRoot"])
            audit_root = Path(v1["inputs"]["evaluationRoot"])
            try:
                relative = Path(registration["sourcePath"]).relative_to(receipt_root)
            except ValueError as error:
                raise ValueError("library closure source is outside its evaluation root") from error
            matching = []
            for source in row.get("sourceEvidence", []):
                try:
                    audit_relative = Path(source.get("path", "")).relative_to(audit_root)
                except ValueError:
                    continue
                if audit_relative == relative:
                    matching.append(source)
            if (len(matching) != 1
                    or matching[0].get("expectedSha256") != registration["sourceSha256"]
                    or matching[0].get("actualSha256") != registration["sourceSha256"]
                    or matching[0].get("verified") is not True):
                raise ValueError("library closure does not bind the audited source record")
            row["currentApplicability"] = RESOLUTION
            row["resolution"] = RESOLUTION
            row["rationale"] = (
                "A parser-proven function-only source is imported literally by the exact "
                "active-cell denominator recorded in the independently regenerated receipt.")
            row["closureEvidence"] = {
                "receiptPath": str(receipt_path), "receiptSha256": receipt_sha,
                "receiptRecordsSha256": receipt["recordsSha256"],
                "registrationSha256": canonical_sha(registration),
                "library": library, "sourcePath": registration["sourcePath"],
                "sourceSha256": registration["sourceSha256"],
            }
            resolved.add(discovery)
        records.append(row)
    if resolved != set(RESOLVED_DISCOVERIES):
        raise ValueError("library closure discoveries are absent from v1 denominator")
    resolutions = Counter(row["resolution"] for row in records)
    if resolutions != Counter({"UNRESOLVED": 162, RESOLUTION: 2}):
        raise ValueError("v2 applicability resolution denominator differs")

    result = dict(v1)
    result["schema"] = "current-scope-applicability-audit-v2"
    result["status"] = "INCOMPLETE"
    result["inputs"] = dict(v1["inputs"])
    result["inputs"]["producerScript"] = {"path": str(HERE), "sha256": sha256(HERE)}
    result["inputs"]["parentV1ProducerScript"] = v1["inputs"]["producerScript"]
    result["inputs"]["parentV1LedgerSha256"] = hashlib.sha256(
        v1_module.render(v1).encode()).hexdigest()
    result["inputs"]["libraryClosureReceipt"] = {
        "path": str(receipt_path), "sha256": receipt_sha,
        "recordsSha256": receipt["recordsSha256"],
        "producerScriptSha256": producer["sha256"],
    }
    result["counts"] = dict(v1["counts"])
    result["counts"]["resolution"] = dict(sorted(resolutions.items()))
    result["limitations"] = list(v1["limitations"]) + [
        "LIBRARY_RECEIPT_RESOLVES_EXACTLY_TWO_DISCOVERIES",
        "OPTIONAL_GMM_CANDIDATE_ALIAS_REMAINS_UNRESOLVED",
        "REMAINING_162_DISCOVERIES_REQUIRE_INDEPENDENT_APPLICABILITY_EVIDENCE",
    ]
    result["records"] = records
    result["recordsSha256"] = canonical_sha(records)
    result["libraryClosure"] = {
        "status": "COMPLETE", "receiptPath": str(receipt_path),
        "receiptSha256": receipt_sha, "receiptRecordsSha256": receipt["recordsSha256"],
        "resolvedDiscoveries": list(RESOLVED_DISCOVERIES),
        "unresolvedCandidateAliases": [OPTIONAL_GMM_DISCOVERY],
        "p1ActiveCells": 16, "slicelineActiveCells": 32,
    }
    return result


def main():
    v1 = load_module("current_scope_v1_cli", V1_PRODUCER)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    parser.add_argument("--catalog", type=Path, default=v1.DEFAULT_CATALOG)
    parser.add_argument("--inventory", type=Path, default=v1.DEFAULT_INVENTORY)
    parser.add_argument("--evaluation-root", type=Path, default=v1.DEFAULT_EVALUATION)
    parser.add_argument("--legacy-root", type=Path, default=v1.DEFAULT_LEGACY)
    parser.add_argument("--frozen-evaluation-root", type=Path,
                        default=v1.DEFAULT_FROZEN_EVALUATION)
    parser.add_argument("--build-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    ledger = build(args.receipt, args.catalog, args.inventory, args.evaluation_root,
                   args.legacy_root, args.frozen_evaluation_root, args.build_root)
    v1.publish(args.output, render(ledger), args.check)
    print(json.dumps({"output": str(args.output), "status": ledger["status"],
                      "counts": ledger["counts"]}, sort_keys=True))
    if ledger["status"] != "COMPLETE" and not args.check:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
