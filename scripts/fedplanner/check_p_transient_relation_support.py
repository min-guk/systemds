#!/usr/bin/env python3
"""Check the stored P transient-realization relation predicate only.

This mirrors the transient-relation loop in
CandidateSelections.realizationsCanStillBeCompatible.  It proves only that
every serialized relation has one serialized source/reader pair whose two
references remain possible.  The capture does not serialize the Java proof
objects that establish the edge inventory, so this is not a full P acceptance
decision or an independent completeness proof for the relation itself.
"""

import argparse
import json
from pathlib import Path

try:
    from interpret_p_acceptance_slice import _load_verified_v2_artifact
except ModuleNotFoundError:  # package import in tests and replay tools
    from scripts.fedplanner.interpret_p_acceptance_slice import _load_verified_v2_artifact


SCHEMA = "p-transient-relation-support-diagnostic-v1"
PREDICATE = "STORED_TRANSIENT_REALIZATION_RELATION_SUPPORT"
CLAIM_SCOPE = "VERIFIED_P_V2_STORED_RELATION_SINGLE_LEAF_ONLY"


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


def _result(verdict, reason, relation_count, edge_count, checked_edges,
            witness=None):
    if verdict not in ("PASS", "FAIL", "UNKNOWN"):
        raise ValueError("invalid leaf verdict")
    return {
        "schema": SCHEMA, "status": "DIAGNOSTIC", "predicate": PREDICATE,
        "claimScope": "CALLER_ASSERTED_DOMAIN_SINGLE_LEAF_ONLY",
        "verdict": verdict, "reason": reason,
        "relationCount": relation_count, "storedEdgeCount": edge_count,
        "checkedEdges": checked_edges, "witness": witness,
        "completePAcceptance": False, "javaExceptionClass": None,
        "executionProvenance": "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
        "unassessed": [
            "TRANSIENT_COMPATIBILITY_PROOF_ANCHORS_AND_DEPENDENCIES",
            "PRODUCER_CARRIED_EDGE_INVENTORY_COMPLETENESS",
            "ALL_OTHER_CANDIDATE_AND_RELOCATION_PREDICATES",
            "JAVA_EXCEPTION_CLASSIFICATION",
        ],
    }


def evaluate_verified_domain(domain, placements, candidates, *,
                             max_candidate_rows=100_000,
                             max_relations=100_000,
                             max_relation_edges=1_000_000):
    """Evaluate the stored-relation leaf over an already verified P v2 domain."""
    for name, value in (("candidate row", max_candidate_rows),
                        ("relation", max_relations),
                        ("relation edge", max_relation_edges)):
        if type(value) is not int or value < 1:
            raise ValueError(name + " budget must be a positive integer")
    if not isinstance(domain, dict):
        raise ValueError("P native domain is malformed")
    place_rows = domain.get("placementDomains")
    candidate_rows = domain.get("candidateDomains")
    semantics = domain.get("candidateReceiptSemanticFacts")
    references = domain.get("candidateRealizationReferenceFacts")
    clauses = domain.get("candidateRealizationClauseInventory")
    logical = domain.get("logicalCandidateReachability")
    if (not all(isinstance(value, list) for value in
                (place_rows, candidate_rows, semantics, references, clauses)) or
            len(place_rows) != len(candidate_rows) or
            not isinstance(logical, dict) or
            not isinstance(logical.get("transient"), list)):
        raise ValueError("P transient-relation source inventory differs")
    _assignment("placement", placements, place_rows, False)
    _assignment("candidate", candidates, candidate_rows, True)
    if sum(len(row[1]) for row in candidate_rows) > max_candidate_rows:
        return _result("UNKNOWN", "CANDIDATE_ROW_BUDGET_EXHAUSTED", 0, 0, 0)

    owner_state = {}
    owner_coordinate = {}
    for index, (place_row, candidate_row) in enumerate(zip(place_rows, candidate_rows)):
        owner = place_row[0]
        if (not isinstance(owner, str) or not owner or candidate_row[0] != owner or
                owner in owner_state):
            raise ValueError("candidate/placement owner coordinate differs")
        owner_state[owner] = place_row[1][placements[index]]
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
    expected = {(ci, ai) for ci, coordinate in enumerate(candidate_rows)
                for ai in range(len(coordinate[1]))}
    if set(semantic_by_coordinate) != expected:
        raise ValueError("candidate semantic coverage differs")

    reference_by_id = {}
    available_by_owner = {}
    for row in references:
        if not isinstance(row, dict) or not isinstance(row.get("reference"), str):
            raise ValueError("candidate realization reference differs")
        reference = row["reference"]
        owner = row.get("owner")
        rule = row.get("rule")
        placement = row.get("placement")
        if (not reference or reference in reference_by_id or
                not isinstance(owner, str) or owner not in owner_state or
                not isinstance(rule, str) or
                rule.rpartition("|inputs=[")[0] != owner or
                not rule.endswith("]") or
                not isinstance(placement, str) or placement not in
                place_rows[owner_coordinate[owner]][1]):
            raise ValueError("candidate realization rule/owner authority differs")
        reference_by_id[reference] = row
        available_by_owner.setdefault(owner, set()).add(reference)
    available = {row.get("reference") for row in clauses
                 if isinstance(row, dict)}
    if (len(available) != len(clauses) or None in available or
            available != set(reference_by_id)):
        raise ValueError("AVAILABLE realization inventory differs")

    selected = {}
    for ci, selection in enumerate(candidates):
        if selection == 0:
            continue
        row = semantic_by_coordinate[(ci, selection - 1)]
        owner = row.get("owner")
        reference = row.get("reference")
        if (owner != candidate_rows[ci][0] or owner in selected or
                not isinstance(reference, str) or reference not in reference_by_id):
            raise ValueError("selected candidate semantic facts differ")
        if row.get("placement") != owner_state[owner]:
            return _result("UNKNOWN", "SELECTED_RECEIPT_PLACEMENT_MISMATCH",
                           0, 0, 0)
        authority = reference_by_id[reference]
        if authority["owner"] != owner or authority["placement"] != row["placement"]:
            raise ValueError("selected receipt reference authority differs")
        selected[owner] = reference

    relations = logical["transient"]
    if len(relations) > max_relations:
        return _result("UNKNOWN", "RELATION_BUDGET_EXHAUSTED",
                       len(relations), 0, 0)
    edge_total = 0
    slots = set()
    relation_fields = {"source", "target", "inputPosition", "compatibility"}
    edge_fields = {"sourceRealization", "readerRealization"}
    # Preflight only relation shells and list lengths before inspecting or
    # retaining any individual edge.  This makes max_relation_edges a real
    # bound on detailed edge work even for hostile verified-domain callers.
    for relation in relations:
        if (not isinstance(relation, dict) or set(relation) != relation_fields or
                not isinstance(relation["source"], str) or
                not isinstance(relation["target"], str) or
                type(relation["inputPosition"]) is not int or
                relation["inputPosition"] != 0 or
                not isinstance(relation["compatibility"], list) or
                not relation["compatibility"]):
            raise ValueError("stored transient relation malformed")
        slot = (relation["source"], relation["target"], relation["inputPosition"])
        if slot in slots:
            raise ValueError("stored transient relation slot duplicated")
        slots.add(slot)
        if relation["source"] not in owner_state or relation["target"] not in owner_state:
            raise ValueError("stored transient relation endpoint owner absent")
        edge_total += len(relation["compatibility"])
    if edge_total > max_relation_edges:
        return _result("UNKNOWN", "RELATION_EDGE_BUDGET_EXHAUSTED",
                       len(relations), edge_total, 0)

    normalized = []
    for relation_index, relation in enumerate(relations):
        slot = (relation["source"], relation["target"], relation["inputPosition"])
        edges = []
        reader_universe = set()
        for edge in relation["compatibility"]:
            if (not isinstance(edge, dict) or set(edge) != edge_fields or
                    any(not isinstance(edge[field], str) or not edge[field]
                        for field in edge_fields)):
                raise ValueError("stored transient compatibility edge malformed")
            source = reference_by_id.get(edge["sourceRealization"])
            reader = reference_by_id.get(edge["readerRealization"])
            if source is None or reader is None:
                raise ValueError("stored transient edge reference absent")
            if source["owner"] != relation["source"] or \
                    reader["owner"] != relation["target"]:
                raise ValueError("stored transient edge endpoint owner differs")
            edges.append((edge["sourceRealization"], edge["readerRealization"]))
            reader_universe.add(edge["readerRealization"])
        if reader_universe != available_by_owner.get(relation["target"], set()):
            raise ValueError("stored transient reader realization universe differs")
        # Duplicate projected endpoint pairs are retained as independent
        # stored edges because the omitted Java proof fields may differ.
        normalized.append((relation_index, slot, edges))

    def possible(reference):
        row = reference_by_id[reference]
        chosen = selected.get(row["owner"])
        if chosen is not None:
            return chosen == reference
        return owner_state[row["owner"]] == row["placement"]

    checked = 0
    for relation_index, slot, edges in normalized:
        supported = False
        for source_reference, reader_reference in edges:
            checked += 1
            if possible(source_reference) and possible(reader_reference):
                supported = True
                break
        if not supported:
            return _result("FAIL", "STORED_TRANSIENT_RELATION_UNSUPPORTED",
                           len(relations), edge_total, checked,
                           {"relationIndex": relation_index,
                            "source": slot[0], "target": slot[1],
                            "inputPosition": slot[2]})
    return _result("PASS", "ALL_STORED_TRANSIENT_RELATIONS_SUPPORTED",
                   len(relations), edge_total, checked)


def check(model_path, model_sha256, placements, candidates, *,
          max_candidate_rows=100_000, max_relations=100_000,
          max_relation_edges=1_000_000):
    """Require verified P v2 bytes before issuing a leaf diagnostic."""
    structure, domain, evidence_complete = _load_verified_v2_artifact(
        Path(model_path), model_sha256)
    if not evidence_complete:
        result = _result("UNKNOWN", "VERIFIED_V2_EVIDENCE_INCOMPLETE", 0, 0, 0)
    else:
        result = evaluate_verified_domain(
            domain, placements, candidates,
            max_candidate_rows=max_candidate_rows,
            max_relations=max_relations,
            max_relation_edges=max_relation_edges)
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
    parser.add_argument("--max-relations", type=int, default=100_000)
    parser.add_argument("--max-relation-edges", type=int, default=1_000_000)
    args = parser.parse_args()
    result = check(args.model, args.model_sha256, args.placements, args.candidates,
                   max_candidate_rows=args.max_candidate_rows,
                   max_relations=args.max_relations,
                   max_relation_edges=args.max_relation_edges)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["verdict"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
