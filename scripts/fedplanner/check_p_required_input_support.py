#!/usr/bin/env python3
"""Check one serialized P required-input-support predicate, not full acceptance.

The check mirrors only CandidateSelections.realizationReferencePossible for the
requiredInputSupport of selected candidate receipts.  It does not classify a
Java exception or decide whether the complete native assignment is feasible.
"""

import argparse
import json
from pathlib import Path

try:
    from interpret_p_acceptance_slice import _load_verified_v2_artifact
except ModuleNotFoundError:  # package import in unit tests and replay tools
    from scripts.fedplanner.interpret_p_acceptance_slice import _load_verified_v2_artifact


SCHEMA = "p-selected-required-input-support-diagnostic-v1"
PREDICATE = "SELECTED_REQUIRED_INPUT_REALIZATION_SUPPORT"
CLAIM_SCOPE = "VERIFIED_P_V2_SINGLE_ACCEPTANCE_LEAF_ONLY"


def _assignment(name, values, coordinates, zero_is_none):
    if not isinstance(values, list) or len(values) != len(coordinates):
        raise ValueError(name + " assignment arity differs")
    for index, (value, coordinate) in enumerate(zip(values, coordinates)):
        if (not isinstance(coordinate, list) or len(coordinate) != 2 or
                not isinstance(coordinate[1], list)):
            raise ValueError(name + " coordinate differs")
        upper = len(coordinate[1]) if zero_is_none else len(coordinate[1]) - 1
        if type(value) is not int or value < 0 or value > upper:
            raise ValueError(name + " assignment index outside domain at " + str(index))


def _result(verdict, reason, checked, selected, total, witness=None):
    if verdict not in ("PASS", "FAIL", "UNKNOWN"):
        raise ValueError("invalid leaf verdict")
    return {
        "schema": SCHEMA, "status": "DIAGNOSTIC", "predicate": PREDICATE,
        "claimScope": "CALLER_ASSERTED_DOMAIN_SINGLE_LEAF_ONLY", "verdict": verdict,
        "reason": reason, "checkedSupportReferences": checked,
        "selectedReceiptCount": selected, "requiredSupportCount": total,
        "witness": witness, "completePAcceptance": False,
        "javaExceptionClass": None,
        "executionProvenance": "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
        "unassessed": ["ALL_OTHER_CANDIDATE_AND_RELOCATION_PREDICATES",
                       "JAVA_EXCEPTION_CLASSIFICATION",
                       "PRODUCER_CARRIED_AUTHORITY_PROVENANCE"],
    }


def evaluate_verified_domain(domain, placements, candidates, *,
                             max_candidate_rows=100_000,
                             max_support_references=1_000_000):
    """Evaluate one leaf over a previously verified v2 native domain.

    Callers must use :func:`check` for authority.  This pure function exists to
    test the rule independently of gzip loading and structural verification.
    """
    if (type(max_candidate_rows) is not int or max_candidate_rows < 1 or
            type(max_support_references) is not int or
            max_support_references < 1):
        raise ValueError("leaf budgets must be positive integers")
    if not isinstance(domain, dict):
        raise ValueError("P native domain is malformed")
    place_rows = domain.get("placementDomains")
    candidate_rows = domain.get("candidateDomains")
    semantics = domain.get("candidateReceiptSemanticFacts")
    references = domain.get("candidateRealizationReferenceFacts")
    clauses = domain.get("candidateRealizationClauseInventory")
    if (not all(isinstance(value, list) for value in
                (place_rows, candidate_rows, semantics, references, clauses)) or
            len(place_rows) != len(candidate_rows)):
        raise ValueError("P required-input-support source inventory differs")
    _assignment("placement", placements, place_rows, False)
    _assignment("candidate", candidates, candidate_rows, True)
    if sum(len(row[1]) for row in candidate_rows) > max_candidate_rows:
        return _result("UNKNOWN", "CANDIDATE_ROW_BUDGET_EXHAUSTED", 0, 0, 0)

    owner_state = {}
    for index, (place_row, candidate_row) in enumerate(zip(place_rows, candidate_rows)):
        owner = place_row[0]
        if (not isinstance(owner, str) or not owner or
                candidate_row[0] != owner or owner in owner_state):
            raise ValueError("candidate/placement owner coordinate differs")
        owner_state[owner] = place_row[1][placements[index]]

    semantic_by_coordinate = {}
    for row in semantics:
        if not isinstance(row, dict):
            raise ValueError("candidate semantic row differs")
        key = (row.get("coordinateIndex"), row.get("candidateIndex"))
        if (len(key) != 2 or any(type(value) is not int for value in key) or
                key in semantic_by_coordinate):
            raise ValueError("candidate semantic coordinate differs")
        semantic_by_coordinate[key] = row
    expected = {(ci, ai) for ci, coordinate in enumerate(candidate_rows)
                for ai in range(len(coordinate[1]))}
    if set(semantic_by_coordinate) != expected:
        raise ValueError("candidate semantic coverage differs")

    reference_by_id = {}
    for row in references:
        if not isinstance(row, dict) or not isinstance(row.get("reference"), str):
            raise ValueError("candidate realization reference differs")
        reference = row["reference"]
        owner = row.get("owner")
        rule = row.get("rule")
        if (not isinstance(owner, str) or not owner or
                not isinstance(rule, str) or
                rule.rpartition("|inputs=[")[0] != owner or
                not rule.endswith("]")):
            raise ValueError("candidate realization rule owner differs")
        if not reference or reference in reference_by_id:
            raise ValueError("candidate realization reference duplicated")
        reference_by_id[reference] = row
    available = {row.get("reference") for row in clauses
                 if isinstance(row, dict)}
    if (len(available) != len(clauses) or None in available or
            available != set(reference_by_id)):
        raise ValueError("AVAILABLE realization inventory differs")

    selected = {}
    selected_count = sum(selection != 0 for selection in candidates)
    required_total = 0
    for ci, selection in enumerate(candidates):
        if selection == 0:
            continue
        row = semantic_by_coordinate[(ci, selection - 1)]
        owner = row.get("owner")
        reference = row.get("reference")
        required = row.get("requiredInputSupport")
        if (owner != candidate_rows[ci][0] or owner in selected or
                not isinstance(reference, str) or reference not in reference_by_id or
                not isinstance(required, list) or
                any(not isinstance(item, str) for item in required)):
            raise ValueError("selected candidate semantic facts differ")
        if row.get("placement") != owner_state[owner]:
            return _result("UNKNOWN", "SELECTED_RECEIPT_PLACEMENT_MISMATCH",
                           0, selected_count, required_total + len(required))
        if reference_by_id[reference].get("owner") != owner or \
                reference_by_id[reference].get("placement") != row["placement"]:
            raise ValueError("selected receipt reference authority differs")
        selected[owner] = reference
        required_total += len(required)
    if required_total > max_support_references:
        return _result("UNKNOWN", "SUPPORT_REFERENCE_BUDGET_EXHAUSTED",
                       0, len(selected), required_total)

    checked = 0
    for ci, selection in enumerate(candidates):
        if selection == 0:
            continue
        receipt = semantic_by_coordinate[(ci, selection - 1)]
        for reference in receipt["requiredInputSupport"]:
            source = reference_by_id.get(reference)
            if source is None or reference not in available:
                raise ValueError("required support reference lacks AVAILABLE authority")
            checked += 1
            owner = source.get("owner")
            if not isinstance(owner, str) or not owner:
                raise ValueError("support owner differs")
            chosen = selected.get(owner)
            if chosen is not None:
                if chosen != reference:
                    return _result("FAIL", "SELECTED_SUPPORT_REFERENCE_DIFFERS",
                                   checked, len(selected), required_total,
                                   {"consumer": receipt["owner"],
                                    "sourceOwner": owner, "required": reference,
                                    "selected": chosen})
            elif owner in owner_state and owner_state[owner] != source.get("placement"):
                return _result("FAIL", "UNSELECTED_SUPPORT_PLACEMENT_DIFFERS",
                               checked, len(selected), required_total,
                               {"consumer": receipt["owner"],
                                "sourceOwner": owner, "required": reference,
                                "assignedPlacement": owner_state[owner],
                                "requiredPlacement": source.get("placement")})
    return _result("PASS", "ALL_SELECTED_REQUIRED_SUPPORTS_POSSIBLE",
                   checked, len(selected), required_total)


def check(model_path, model_sha256, placements, candidates, *,
          max_candidate_rows=100_000, max_support_references=1_000_000):
    """Require verified P v2 bytes before issuing a leaf diagnostic."""
    structure, domain, evidence_complete = _load_verified_v2_artifact(
        Path(model_path), model_sha256)
    if not evidence_complete:
        result = _result("UNKNOWN", "VERIFIED_V2_EVIDENCE_INCOMPLETE", 0, 0, 0)
    else:
        result = evaluate_verified_domain(
            domain, placements, candidates,
            max_candidate_rows=max_candidate_rows,
            max_support_references=max_support_references)
    result.update({"cell": structure.get("cell"), "modelSha256": model_sha256,
                   "verificationStatus": structure.get("status"),
                   "evidenceAuthority": "VERIFIED_P_V2_MODEL_BYTES",
                   "claimScope": CLAIM_SCOPE})
    return result


def _csv(value):
    if value == "":
        return []
    try:
        return [int(item) for item in value.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("assignment indices must be comma-separated integers") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--placements", type=_csv, required=True)
    parser.add_argument("--candidates", type=_csv, required=True)
    parser.add_argument("--max-candidate-rows", type=int, default=100_000)
    parser.add_argument("--max-support-references", type=int, default=1_000_000)
    args = parser.parse_args()
    result = check(args.model, args.model_sha256, args.placements, args.candidates,
                   max_candidate_rows=args.max_candidate_rows,
                   max_support_references=args.max_support_references)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["verdict"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
