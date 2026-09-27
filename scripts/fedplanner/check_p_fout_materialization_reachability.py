#!/usr/bin/env python3
"""Check only selected-candidate FOUT materialization reachability in P.

This mirrors ``CandidateSelections.foutMaterializationActionReachable`` for a
complete placement assignment (``allowUnassignedOwner=false``).  It is a
single-leaf diagnostic: the verified artifact still does not make the other
candidate, relocation, or Java-exception predicates independently decidable.
"""

import argparse
import hashlib
import json
from pathlib import Path

try:
    from interpret_p_acceptance_slice import _load_verified_v2_artifact
except ModuleNotFoundError:  # package import in tests and replay tools
    from scripts.fedplanner.interpret_p_acceptance_slice import _load_verified_v2_artifact


SCHEMA = "p-fout-materialization-reachability-diagnostic-v1"
PREDICATE = "SELECTED_FOUT_MATERIALIZATION_REACHABILITY"
CLAIM_SCOPE = "VERIFIED_P_V2_SINGLE_ACCEPTANCE_LEAF_ONLY"


def _decode_fields(value, count):
    if not isinstance(value, str):
        raise ValueError("length-field value differs")
    fields = []
    cursor = 0
    for field_index in range(count):
        colon = value.find(":", cursor)
        if colon <= cursor or not value[cursor:colon].isdigit():
            raise ValueError("length-field prefix malformed")
        length = int(value[cursor:colon])
        start, end = colon + 1, colon + 1 + length
        if end > len(value):
            raise ValueError("length-field payload truncated")
        fields.append(value[start:end])
        cursor = end
        if field_index + 1 < count:
            if cursor >= len(value) or value[cursor] != "|":
                raise ValueError("length-field separator malformed")
            cursor += 1
    if cursor != len(value):
        raise ValueError("length-field trailing payload")
    return fields


def _placement(value):
    if not isinstance(value, str):
        raise ValueError("placement differs")
    parts = value.split("/")
    if len(parts) != 4 or parts[0] not in {"CP", "FED"} or \
            parts[1] not in {"LOUT", "FOUT"} or not parts[2]:
        raise ValueError("placement identity malformed")
    return parts


def _assignment(name, values, coordinates, zero_is_none):
    if not isinstance(values, list) or len(values) != len(coordinates):
        raise ValueError(name + " assignment arity differs")
    for index, (value, coordinate) in enumerate(zip(values, coordinates)):
        if (not isinstance(coordinate, list) or len(coordinate) != 2 or
                not isinstance(coordinate[1], list) or
                (not zero_is_none and not coordinate[1])):
            raise ValueError(name + " coordinate differs")
        upper = len(coordinate[1]) if zero_is_none else len(coordinate[1]) - 1
        if type(value) is not int or value < 0 or value > upper:
            raise ValueError(name + " assignment index outside domain at " + str(index))


def _result(verdict, reason, selected, required, checked, witness=None):
    if verdict not in {"PASS", "FAIL", "UNKNOWN"}:
        raise ValueError("invalid leaf verdict")
    return {
        "schema": SCHEMA, "status": "DIAGNOSTIC", "predicate": PREDICATE,
        "claimScope": "CALLER_ASSERTED_DOMAIN_SINGLE_LEAF_ONLY",
        "verdict": verdict, "reason": reason,
        "selectedReceiptCount": selected,
        "requiredMaterializationCount": required,
        "checkedMaterializationCount": checked,
        "assignmentSemantics": "COMPLETE_PLACEMENT_ASSIGNMENT_OWNER_ALWAYS_ASSIGNED",
        "witness": witness, "completePAcceptance": False,
        "javaExceptionClass": None,
        "executionProvenance": "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
        "unassessed": [
            "PRODUCER_CARRIED_DERIVED_FOUT_INVENTORY_COMPLETENESS",
            "ALL_OTHER_CANDIDATE_AND_RELOCATION_PREDICATES",
            "JAVA_EXCEPTION_CLASSIFICATION",
        ],
    }


def evaluate_verified_domain(domain, placements, candidates, *,
                             max_candidate_rows=100_000,
                             max_action_records=1_000_000,
                             max_rule_emissions=1_000_000,
                             max_rule_rows=100_000):
    """Evaluate this one leaf over an already verified P v2 native domain."""
    if (type(max_candidate_rows) is not int or max_candidate_rows < 1 or
            type(max_action_records) is not int or max_action_records < 1 or
            type(max_rule_emissions) is not int or max_rule_emissions < 1 or
            type(max_rule_rows) is not int or max_rule_rows < 1):
        raise ValueError("leaf budgets must be positive integers")
    if not isinstance(domain, dict):
        raise ValueError("P native domain is malformed")
    place_rows = domain.get("placementDomains")
    candidate_rows = domain.get("candidateDomains")
    semantics = domain.get("candidateReceiptSemanticFacts")
    references = domain.get("candidateRealizationReferenceFacts")
    clauses = domain.get("candidateRealizationClauseInventory")
    rules = domain.get("candidateRuleFactInventory")
    actions = domain.get("derivedFoutActions")
    bindings = domain.get("derivedFoutOwnershipBindings")
    values = (place_rows, candidate_rows, semantics, references, clauses,
              rules, actions, bindings)
    if (not all(isinstance(value, list) for value in values) or
            len(place_rows) != len(candidate_rows)):
        raise ValueError("P FOUT materialization source inventory differs")
    _assignment("placement", placements, place_rows, False)
    _assignment("candidate", candidates, candidate_rows, True)

    candidate_count = sum(len(row[1]) for row in candidate_rows
                          if isinstance(row, list) and len(row) == 2 and
                          isinstance(row[1], list))
    if candidate_count > max_candidate_rows:
        return _result("UNKNOWN", "CANDIDATE_ROW_BUDGET_EXHAUSTED", 0, 0, 0)
    if len(actions) + len(bindings) > max_action_records:
        return _result("UNKNOWN", "ACTION_RECORD_BUDGET_EXHAUSTED", 0, 0, 0)
    if len(rules) > max_rule_rows:
        return _result("UNKNOWN", "RULE_ROW_BUDGET_EXHAUSTED", 0, 0, 0)
    emission_count = 0
    for rule in rules:
        emissions = rule.get("emissions") if isinstance(rule, dict) else None
        if not isinstance(emissions, list):
            raise ValueError("candidate rule emission inventory differs")
        emission_count += len(emissions)
    if emission_count > max_rule_emissions:
        return _result("UNKNOWN", "RULE_EMISSION_BUDGET_EXHAUSTED", 0, 0, 0)

    owner_state = {}
    owner_coordinate = {}
    for index, (place_row, candidate_row) in enumerate(zip(place_rows, candidate_rows)):
        owner = place_row[0]
        alternatives = place_row[1]
        if (not isinstance(owner, str) or not owner or owner in owner_state or
                candidate_row[0] != owner or len(alternatives) != len(set(alternatives)) or
                any(not isinstance(state, str) for state in alternatives)):
            raise ValueError("candidate/placement owner coordinate differs")
        owner_state[owner] = alternatives[placements[index]]
        owner_coordinate[owner] = index

    semantic_by_coordinate = {}
    for row in semantics:
        if not isinstance(row, dict):
            raise ValueError("candidate semantic row differs")
        key = (row.get("coordinateIndex"), row.get("candidateIndex"))
        if (any(type(value) is not int for value in key) or
                key in semantic_by_coordinate):
            raise ValueError("candidate semantic coordinate differs")
        semantic_by_coordinate[key] = row
    expected_coordinates = {(ci, ai) for ci, coordinate in enumerate(candidate_rows)
                            for ai in range(len(coordinate[1]))}
    if set(semantic_by_coordinate) != expected_coordinates:
        raise ValueError("candidate semantic coverage differs")

    reference_ids = set()
    for row in references:
        if not isinstance(row, dict) or not isinstance(row.get("reference"), str) or \
                not row["reference"] or row["reference"] in reference_ids:
            raise ValueError("candidate realization reference differs")
        reference_ids.add(row["reference"])
    available = {row.get("reference") for row in clauses if isinstance(row, dict)}
    if len(available) != len(clauses) or None in available or available != reference_ids:
        raise ValueError("AVAILABLE realization inventory differs")

    available_sources_by_rule = {}
    seen_rule_hashes = set()
    rule_fields = {"ruleSignature", "status", "failure", "capabilityPresent",
                   "profileAvailable", "emissions"}
    for rule in rules:
        if (not isinstance(rule, dict) or set(rule) != rule_fields or
                not isinstance(rule["ruleSignature"], str) or
                not rule["ruleSignature"] or
                rule["ruleSignature"] in seen_rule_hashes):
            raise ValueError("candidate rule inventory identity differs")
        seen_rule_hashes.add(rule["ruleSignature"])
        action_free_sources = set()
        for emission in rule["emissions"]:
            if (not isinstance(emission, dict) or
                    set(emission) != {"selection", "realizations"} or
                    not isinstance(emission["selection"], str) or
                    not isinstance(emission["realizations"], list)):
                raise ValueError("candidate rule emission inventory malformed")
            selection = emission["selection"]
            prefix, marker, action = selection.rpartition("|derivedAction=")
            if not marker or not action:
                raise ValueError("candidate rule emission action identity differs")
            placement, derived_marker, _ = prefix.partition("|derivedFedFout=")
            if not derived_marker:
                raise ValueError("candidate rule emission placement identity differs")
            _placement(placement)
            if action == "-":
                action_free_sources.add(placement)
        if rule["status"] == "AVAILABLE":
            available_sources_by_rule[rule["ruleSignature"]] = action_free_sources

    action_index = {}
    action_parts = {}
    for index, action in enumerate(actions):
        if not isinstance(action, str) or not action or action in action_index:
            raise ValueError("derived FOUT graph action inventory differs")
        parts = _decode_fields(action, 10)
        source, target = _placement(parts[3]), _placement(parts[4])
        if (source[1] != "LOUT" or target[1] != "FOUT" or
                source[0] != target[0] or source[0] not in {"CP", "FED"} or
                target[2] != parts[8]):
            raise ValueError("derived FOUT action placement identity differs")
        action_index[action] = index
        action_parts[action] = parts

    expected_bindings = []
    for ci, ai in sorted(expected_coordinates):
        row = semantic_by_coordinate[(ci, ai)]
        owner = row.get("owner")
        placement = row.get("placement")
        rule = row.get("rule")
        emission = row.get("emission")
        reference = row.get("reference")
        action = row.get("derivedFoutAction")
        if (owner != candidate_rows[ci][0] or owner not in owner_state or
                not isinstance(placement, str) or
                placement not in place_rows[owner_coordinate[owner]][1] or
                not isinstance(rule, str) or rule.rpartition("|inputs=[")[0] != owner or
                not rule.endswith("]") or not isinstance(emission, str) or
                not isinstance(reference, str) or reference not in available or
                not isinstance(action, str)):
            raise ValueError("candidate FOUT semantic authority differs")
        _, action_marker, emission_action = emission.rpartition("|derivedAction=")
        if (not emission.startswith(placement + "|derivedFedFout=") or
                not action_marker or emission_action != action):
            raise ValueError("candidate FOUT emission/action identity differs")
        if action != "-":
            index = action_index.get(action)
            if index is None:
                raise ValueError("candidate derived FOUT action is not graph-owned")
            expected_bindings.append([ci, ai, index])
    if bindings != expected_bindings:
        raise ValueError("derived FOUT action ownership binding differs")

    selected_count = sum(selection != 0 for selection in candidates)
    required_count = 0
    checked = 0
    for ci, selection in enumerate(candidates):
        if selection == 0:
            continue
        ai = selection - 1
        row = semantic_by_coordinate[(ci, ai)]
        owner = row["owner"]
        assigned = owner_state[owner]
        if row["placement"] != assigned:
            return _result("UNKNOWN", "SELECTED_RECEIPT_PLACEMENT_MISMATCH",
                           selected_count, required_count, checked,
                           {"owner": owner, "selectedPlacement": row["placement"],
                            "assignedPlacement": assigned})
        marker = assigned + "|derivedFedFout="
        if not row["emission"].startswith(marker):
            raise ValueError("selected emission placement identity differs")
        suffix = row["emission"][len(marker):]
        if suffix.startswith("true|"):
            derived = True
        elif suffix.startswith("false|"):
            derived = False
        else:
            raise ValueError("selected derived FOUT flag differs")
        placement_parts = _placement(assigned)
        required = derived or placement_parts[:2] == ["CP", "FOUT"]
        action = row["derivedFoutAction"]
        if not required:
            if action != "-":
                return _result("FAIL", "UNREQUIRED_MATERIALIZATION_ACTION_PRESENT",
                               selected_count, required_count, checked,
                               {"owner": owner, "action": action})
            continue
        required_count += 1
        if action == "-":
            return _result("FAIL", "REQUIRED_MATERIALIZATION_ACTION_ABSENT",
                           selected_count, required_count, checked,
                           {"owner": owner})
        checked += 1
        parts = action_parts[action]
        producer, rule, source, target = parts[0], parts[2], parts[3], parts[4]
        anchor_owner, anchor_ftype = parts[6], parts[7]
        if producer != owner or rule != row["rule"] or target != assigned:
            return _result("FAIL", "MATERIALIZATION_ACTION_IDENTITY_DIFFERS",
                           selected_count, required_count, checked,
                           {"owner": owner, "producer": producer,
                            "ruleMatches": rule == row["rule"],
                            "targetPlacement": target})
        rule_hash = hashlib.sha256(rule.encode("utf-8")).hexdigest()
        if source not in available_sources_by_rule.get(rule_hash, set()):
            return _result("FAIL", "ACTION_SOURCE_EMISSION_UNAVAILABLE",
                           selected_count, required_count, checked,
                           {"owner": owner, "sourcePlacement": source})
        anchor_state = owner_state.get(anchor_owner)
        if anchor_state is None:
            raise ValueError("derived FOUT anchor owner absent")
        anchor_parts = _placement(anchor_state)
        if anchor_parts[1] != "FOUT" or anchor_parts[2] != anchor_ftype:
            return _result("FAIL", "ANCHOR_OWNER_ASSIGNMENT_INCOMPATIBLE",
                           selected_count, required_count, checked,
                           {"owner": owner, "anchorOwner": anchor_owner,
                            "assignedPlacement": anchor_state,
                            "requiredFType": anchor_ftype})
    return _result("PASS", "ALL_SELECTED_FOUT_MATERIALIZATIONS_REACHABLE",
                   selected_count, required_count, checked)


def check(model_path, model_sha256, placements, candidates, *,
          max_candidate_rows=100_000, max_action_records=1_000_000,
          max_rule_emissions=1_000_000, max_rule_rows=100_000):
    """Require verified P v2 bytes before issuing a leaf diagnostic."""
    structure, domain, evidence_complete = _load_verified_v2_artifact(
        Path(model_path), model_sha256)
    if not evidence_complete:
        result = _result("UNKNOWN", "VERIFIED_V2_EVIDENCE_INCOMPLETE", 0, 0, 0)
    else:
        result = evaluate_verified_domain(
            domain, placements, candidates,
            max_candidate_rows=max_candidate_rows,
            max_action_records=max_action_records,
            max_rule_emissions=max_rule_emissions,
            max_rule_rows=max_rule_rows)
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
        raise argparse.ArgumentTypeError(
            "assignment indices must be comma-separated integers") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--placements", type=_csv, required=True)
    parser.add_argument("--candidates", type=_csv, required=True)
    parser.add_argument("--max-candidate-rows", type=int, default=100_000)
    parser.add_argument("--max-action-records", type=int, default=1_000_000)
    parser.add_argument("--max-rule-emissions", type=int, default=1_000_000)
    parser.add_argument("--max-rule-rows", type=int, default=100_000)
    args = parser.parse_args()
    result = check(args.model, args.model_sha256, args.placements, args.candidates,
                   max_candidate_rows=args.max_candidate_rows,
                   max_action_records=args.max_action_records,
                   max_rule_emissions=args.max_rule_emissions,
                   max_rule_rows=args.max_rule_rows)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["verdict"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
