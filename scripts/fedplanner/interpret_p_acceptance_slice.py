#!/usr/bin/env python3
"""Fail-closed interpreter for a small serialized slice of P acceptance.

This diagnostic evaluates one native-domain assignment.  It covers only facts
that can be reconstructed from the v2 P artifact: candidate coordinate
coverage for independently simple rows, selected-realization references,
explicit relocation bindings, selected relocation coverage, and exact
worker-pool equality.  Results remain separate from the P model verifier and
never complete the production Java acceptance predicate.
"""

import argparse
import gzip
import hashlib
import json
import re
from pathlib import Path

try:
    from verify_p_model_artifact import (MAX_DECOMPRESSED_ARTIFACT_BYTES,
                                         INDEPENDENT_ARTIFACT_PREDICATES,
                                         MODEL_SCHEMA_V2,
                                         OPAQUE_ACCEPTANCE_PREDICATES,
                                         _partition_authority_signature,
                                         decode_java_length_fields, verify)
except ModuleNotFoundError:  # package import in unit tests
    from scripts.fedplanner.verify_p_model_artifact import (
        INDEPENDENT_ARTIFACT_PREDICATES, MAX_DECOMPRESSED_ARTIFACT_BYTES,
        MODEL_SCHEMA_V2,
        OPAQUE_ACCEPTANCE_PREDICATES, _partition_authority_signature,
        decode_java_length_fields, verify)


CANDIDATE_RULE = "CANDIDATE_SELECTION_COVERAGE_AND_REALIZATION_COMPATIBILITY"
RELOCATION_RULE = "RELOCATION_SELECTION_COVERAGE_AND_WORKER_POOL_COMPATIBILITY"
ALIGNMENT_RULE = "CANDIDATE_RELOCATION_REALIZATION_ALIGNMENT"
RULES = (CANDIDATE_RULE, RELOCATION_RULE, ALIGNMENT_RULE)
JAVA_ARGUMENT_EXCEPTION = "java.lang.IllegalArgumentException"
VERIFIED_V2_EVIDENCE = frozenset(INDEPENDENT_ARTIFACT_PREDICATES)

if any(rule not in OPAQUE_ACCEPTANCE_PREDICATES for rule in RULES):
    raise RuntimeError("P acceptance diagnostic inventory differs from verifier inventory")


def _result(support, verdict, reasons, *, exception=None, checked=0):
    if support not in {"SUPPORTED", "PARTIAL", "UNKNOWN"} or \
            verdict not in {"ACCEPT", "REJECT", "UNKNOWN"}:
        raise ValueError("invalid diagnostic result state")
    if verdict == "REJECT" and exception != JAVA_ARGUMENT_EXCEPTION:
        raise ValueError("diagnostic rejection lacks Java exception boundary")
    if verdict != "REJECT" and exception is not None:
        raise ValueError("non-rejection carries Java exception boundary")
    return {"support": support, "verdict": verdict,
            "javaExceptionClass": exception, "reasons": sorted(set(reasons)),
            "checkedFacts": checked}


def _assignment(name, values, radices, zero_is_none):
    if not isinstance(values, list) or len(values) != len(radices):
        raise ValueError(name + " assignment arity differs")
    for index, (value, radix) in enumerate(zip(values, radices)):
        upper = radix if zero_is_none else radix - 1
        if type(value) is not int or value < 0 or value > upper:
            raise ValueError(f"{name} assignment index outside domain at {index}")


def _indexes(domain):
    required = ("placementDomains", "candidateDomains", "relocationDomains",
                "candidateReceiptSemanticFacts", "candidateReceiptActivationFacts",
                "candidateRealizationReferenceFacts", "relocationActionFacts",
                "relocationWorkerPoolAuthorities", "logicalCandidateReachability")
    if any(name not in domain for name in required):
        return None
    placements, candidates, relocations = (domain[name] for name in required[:3])
    if not all(isinstance(value, list) for value in (
            placements, candidates, relocations)) or len(placements) != len(candidates):
        raise ValueError("P acceptance diagnostic coordinate contract differs")
    semantics = {}
    for row in domain["candidateReceiptSemanticFacts"]:
        key = (row.get("coordinateIndex"), row.get("candidateIndex")) \
            if isinstance(row, dict) else None
        if key in semantics:
            raise ValueError("candidate semantic coordinate duplicate")
        semantics[key] = row
    activations = {}
    for row in domain["candidateReceiptActivationFacts"]:
        key = (row.get("coordinateIndex"), row.get("candidateIndex")) \
            if isinstance(row, dict) else None
        if key in activations:
            raise ValueError("candidate activation coordinate duplicate")
        activations[key] = row
    expected = {(ci, ai) for ci, coordinate in enumerate(candidates)
                for ai in range(len(coordinate[1]))}
    if set(semantics) != expected or set(activations) != expected:
        raise ValueError("candidate diagnostic fact coverage differs")
    references = domain["candidateRealizationReferenceFacts"]
    if not isinstance(references, list) or any(not isinstance(row, dict) for row in references):
        raise ValueError("candidate reference evidence malformed")
    reference_by_signature = {row.get("reference"): row for row in references}
    if None in reference_by_signature or len(reference_by_signature) != len(references):
        raise ValueError("candidate reference evidence duplicate")
    actions = domain["relocationActionFacts"]
    pools = domain["relocationWorkerPoolAuthorities"]
    if not isinstance(actions, list) or not isinstance(pools, list) or len(actions) != len(pools):
        raise ValueError("relocation action/pool evidence differs")
    action_by_signature = {row.get("action"): (index, row)
                           for index, row in enumerate(actions) if isinstance(row, dict)}
    if len(action_by_signature) != len(actions) or None in action_by_signature:
        raise ValueError("relocation action evidence duplicate")
    demand_by_signature = {}
    for index, coordinate in enumerate(relocations):
        if not isinstance(coordinate, list) or len(coordinate) != 2 or \
                not isinstance(coordinate[0], str) or not isinstance(coordinate[1], list) or \
                coordinate[0] in demand_by_signature:
            raise ValueError("relocation coordinate malformed or duplicate")
        demand_by_signature[coordinate[0]] = index
    logical = domain["logicalCandidateReachability"]
    if not isinstance(logical, dict) or set(logical) != {
            "transient", "function", "boundaries"} or any(
                not isinstance(rows, list) for rows in logical.values()):
        raise ValueError("logical candidate reachability contract differs")
    return {"placements": placements, "candidates": candidates,
            "relocations": relocations, "semantics": semantics,
            "activations": activations, "references": reference_by_signature,
            "actions": actions, "actionBySignature": action_by_signature,
            "pools": pools, "demandBySignature": demand_by_signature,
            "logical": logical}


def _simple_candidate(fact, semantic):
    authority = fact.get("derivedFoutAuthority")
    return semantic.get("independentReachabilityClass") == "SIMPLE_LOCAL" and \
        fact.get("functionCallBoundary") is False and \
        fact.get("specialRuntimeAuthority") == {"kind": "NONE"} and \
        fact.get("unsupportedReasons") == [] and \
        isinstance(authority, dict) and authority.get("required") is False and \
        authority.get("graphActionIndex") == -1 and \
        fact.get("presentInputCount") == 0 and semantic.get("requiredInputSupport") == [] and \
        semantic.get("inputBindings") == [] and semantic.get("derivedFoutAction") == "-" and \
        semantic.get("nativeWorkerPoolWitness") == "-"


def _selected_rows(index, placement_assignment, candidate_assignment):
    selected = {}
    unsupported = []
    checked = 0
    for ci, (placement_coordinate, candidate_coordinate) in enumerate(zip(
            index["placements"], index["candidates"])):
        if placement_coordinate[0] != candidate_coordinate[0]:
            raise ValueError("candidate/placement owner order differs")
        placement = placement_assignment[ci]
        selection = candidate_assignment[ci]
        active = []
        for ai in range(len(candidate_coordinate[1])):
            fact, semantic = index["activations"][(ci, ai)], index["semantics"][(ci, ai)]
            if fact.get("activePlacementAlternativeIndex") == placement:
                active.append((ai, fact, semantic))
        selected_row = None if selection == 0 else next(
            ((ai, fact, semantic) for ai, fact, semantic in active if ai == selection - 1), None)
        if selection and selected_row is None:
            return None, _result("SUPPORTED", "REJECT",
                ["SELECTED_CANDIDATE_FOREIGN_OR_INACTIVE"],
                exception=JAVA_ARGUMENT_EXCEPTION, checked=checked)
        simple_active = [(ai, fact, semantic) for ai, fact, semantic in active
                         if _simple_candidate(fact, semantic)]
        if not selection:
            if simple_active:
                if any(index["logical"].values()):
                    unsupported.append(
                        "ACTIVE_SIMPLE_CANDIDATE_GLOBAL_LOGICAL_FEASIBILITY_OPAQUE")
                else:
                    return None, _result("SUPPORTED", "REJECT",
                        ["ACTIVE_SIMPLE_CANDIDATE_CONSUMER_UNSELECTED"],
                        exception=JAVA_ARGUMENT_EXCEPTION,
                        checked=checked + len(simple_active))
            if active:
                unsupported.append("ACTIVE_CANDIDATE_FEASIBILITY_NOT_INDEPENDENTLY_INTERPRETABLE")
            continue
        ai, fact, semantic = selected_row
        checked += 1
        if not _simple_candidate(fact, semantic):
            unsupported.append("SELECTED_CANDIDATE_FEASIBILITY_NOT_INDEPENDENTLY_INTERPRETABLE")
        owner = semantic.get("owner")
        if not isinstance(owner, str) or owner in selected:
            raise ValueError("selected candidate owner duplicate or malformed")
        selected[owner] = (ai, fact, semantic)
    return selected, None if not unsupported else _result(
        "PARTIAL", "UNKNOWN", unsupported, checked=checked)


def _socket_port(rendered_address):
    if not rendered_address:
        return None
    colon = rendered_address.rfind(":")
    if colon < 0 or colon == len(rendered_address) - 1:
        return None
    token = rendered_address[colon + 1:]
    if re.fullmatch(r"[+-]?[0-9]+", token) is None:
        return None
    port = int(token)
    return port if 0 <= port <= 65535 else None


def _canonical_worker_address(token):
    """Mirror FederationUtils.canonicalFederatedWorkerAddress(String)."""
    addr = token.strip()
    if not addr:
        return None
    slash = addr.find("/")
    if slash >= 0:
        before = addr[:slash]
        after = addr[slash + 1:] if slash < len(addr) - 1 else ""
        rendered_port = _socket_port(after)
        if ":" in before and before:
            addr = before
        elif rendered_port is not None:
            addr = after if not before else f"{before}:{rendered_port}"
        elif before:
            addr = before
    colon = addr.rfind(":")
    host = addr[:colon] if colon > 0 else addr
    port_token = addr[colon + 1:] if 0 < colon < len(addr) - 1 else "4040"
    if not host.strip() or re.fullmatch(r"[+-]?[0-9]+", port_token) is None:
        return None
    port = int(port_token)
    return f"{host}:{port}" if 0 <= port <= 65535 else None


def _pool_key(pool):
    """Project a verified exact authority to Java's physical worker-pool layout."""
    ftype, _ = _partition_authority_signature(pool)
    if ftype not in {"ROW", "COL", "FULL", "BROADCAST"}:
        raise ValueError("relocation worker-pool authority FType is unsupported")
    axis = 0 if ftype == "ROW" else 1 if ftype == "COL" else None
    layout = []
    for partition in pool["partitions"]:
        worker = _canonical_worker_address(partition["worker"])
        if worker is None:
            raise ValueError("relocation worker-pool authority worker is not canonicalizable")
        if axis is None:
            layout.append(worker)
        else:
            if len(partition["begin"]) <= axis or len(partition["end"]) <= axis:
                raise ValueError("relocation worker-pool authority lacks partitioned axis")
            layout.append(f"{worker}|{partition['begin'][axis]}:{partition['end'][axis]}")
    # physicalWorkerPoolLayout sorts all entries and preserves multiplicity.
    return ftype, tuple(sorted(layout))


def _unknown(reason):
    unknown = _result("UNKNOWN", "UNKNOWN", [reason])
    return {"complete": False, "rules": {rule: dict(unknown) for rule in RULES},
            "claimScope": "DIAGNOSTIC_SUBSET_ONLY"}


def _load_verified_v2_artifact(model_path, model_sha256):
    """Load the exact bytes independently accepted by the structural verifier."""
    structure = verify(model_path, model_sha256)
    if structure.get("modelSchema") != MODEL_SCHEMA_V2:
        raise ValueError("P acceptance slice requires v2 serialized evidence")
    with gzip.open(model_path, "rb") as stream:
        plain = stream.read(MAX_DECOMPRESSED_ARTIFACT_BYTES + 1)
    if len(plain) > MAX_DECOMPRESSED_ARTIFACT_BYTES or \
            hashlib.sha256(plain).hexdigest() != model_sha256:
        raise ValueError("P artifact changed after structural verification")
    artifact = json.loads(plain)
    domain = artifact.get("nativeDomain") if isinstance(artifact, dict) else None
    if not isinstance(domain, dict):
        raise ValueError("verified P artifact native domain absent")
    assessed = structure.get("acceptanceCoverage", {}).get("assessedPredicates")
    evidence_complete = isinstance(assessed, list) and \
        VERIFIED_V2_EVIDENCE.issubset(assessed)
    return structure, domain, evidence_complete


def _interpret_verified_assignment(domain, placement_assignment, candidate_assignment,
                                   relocation_assignment):
    index = _indexes(domain)
    if index is None:
        raise ValueError("verified v2 diagnostic evidence incomplete")
    _assignment("placement", placement_assignment,
                [len(row[1]) for row in index["placements"]], False)
    _assignment("candidate", candidate_assignment,
                [len(row[1]) for row in index["candidates"]], True)
    _assignment("relocation", relocation_assignment,
                [len(row[1]) for row in index["relocations"]], True)

    selected, candidate_early = _selected_rows(
        index, placement_assignment, candidate_assignment)
    if candidate_early and candidate_early["verdict"] == "REJECT":
        candidate_result = candidate_early
    elif candidate_early:
        candidate_result = candidate_early
    else:
        logical = index["logical"]
        reasons = []
        if any(logical[kind] for kind in ("transient", "function", "boundaries")):
            reasons.append("LOGICAL_REALIZATION_RELATIONS_REQUIRE_UNSERIALIZED_JAVA_SEMANTICS")
        candidate_result = _result("PARTIAL", "UNKNOWN", reasons, checked=len(selected)) \
            if reasons else _result("SUPPORTED", "ACCEPT", [], checked=len(selected))

    selected_choices = {}
    chosen = []
    for ri, (coordinate, value) in enumerate(zip(index["relocations"], relocation_assignment)):
        if value == 0:
            continue
        receipt = coordinate[1][value - 1]
        demand, action = decode_java_length_fields(receipt, 2)
        if demand != coordinate[0]:
            raise ValueError("relocation choice demand identity differs")
        if demand in selected_choices:
            raise ValueError("relocation assignment selected one demand twice")
        selected_choices[demand] = action
        chosen.append((demand, action))

    explicit_relocations = set()
    if selected is not None:
        for _, _, semantic in selected.values():
            for binding in semantic.get("inputBindings", []):
                if binding.get("kind") == "RELOCATION":
                    explicit_relocations.add((semantic.get("owner"),
                                              binding.get("inputPosition"),
                                              binding.get("relocationAction")))

    pool_by_consumer = {}
    relocation_reject = None
    for demand, action_signature in chosen:
        action_entry = index["actionBySignature"].get(action_signature)
        if action_entry is None:
            relocation_reject = "RELOCATION_CHOICE_FOREIGN_ACTION"
            break
        action_index, _ = action_entry
        demand_fields = decode_java_length_fields(demand, 5)
        consumer, position = demand_fields[1], int(demand_fields[2])
        # Pool incompatibility is decisive only for choices whose activity is
        # named by an exact selected-realization RELOCATION binding.  Other
        # choices still depend on the opaque Java active-demand construction.
        if (consumer, position, action_signature) not in explicit_relocations:
            continue
        key = _pool_key(index["pools"][action_index])
        prior = pool_by_consumer.setdefault(consumer, key)
        if prior != key:
            relocation_reject = "CONSUMER_MIXES_INCOMPATIBLE_WORKER_POOLS"
            break
    if relocation_reject:
        relocation_result = _result("SUPPORTED", "REJECT", [relocation_reject],
            exception=JAVA_ARGUMENT_EXCEPTION, checked=len(chosen))
    else:
        relocation_result = _result("PARTIAL", "UNKNOWN",
            ["ACTIVE_RELOCATION_DEMAND_CONSTRUCTION_REMAINS_OPAQUE"], checked=len(chosen))

    alignment_reject = None
    alignment_unknown = []
    alignment_checked = 0
    if selected is None:
        alignment_unknown.append("CANDIDATE_SELECTION_REJECTED_BEFORE_ALIGNMENT")
    else:
        for _, _, semantic in selected.values():
            owner = semantic["owner"]
            for binding in semantic.get("inputBindings", []):
                kind = binding.get("kind")
                position = binding.get("inputPosition")
                if kind == "LOGICAL_TRANSIENT":
                    continue
                matches = [(demand, action) for demand, action in chosen
                           if decode_java_length_fields(demand, 5)[1:3] == [owner, str(position)]]
                alignment_checked += 1
                if kind == "RELOCATION":
                    if not any(action == binding.get("relocationAction")
                               for _, action in matches):
                        alignment_reject = "RELOCATION_BINDING_HAS_NO_SELECTED_MATCH"
                        break
                elif kind == "DIRECT" and matches:
                    alignment_unknown.append(
                        "DIRECT_BINDING_EXTRA_CHOICE_DEPENDS_ON_OPAQUE_ACTION_ACTIVATION")
                else:
                    if kind not in {"DIRECT", "RELOCATION"}:
                        raise ValueError("candidate binding kind unsupported")
            if alignment_reject:
                break
    if alignment_reject:
        alignment_result = _result("SUPPORTED", "REJECT", [alignment_reject],
            exception=JAVA_ARGUMENT_EXCEPTION, checked=alignment_checked)
    elif alignment_unknown:
        alignment_result = _result("PARTIAL", "UNKNOWN", alignment_unknown,
                                   checked=alignment_checked)
    else:
        alignment_result = _result("SUPPORTED", "ACCEPT", [], checked=alignment_checked)

    return {"complete": False, "claimScope": "DIAGNOSTIC_SUBSET_ONLY",
            "rules": {CANDIDATE_RULE: candidate_result,
                      RELOCATION_RULE: relocation_result,
                      ALIGNMENT_RULE: alignment_result}}


def interpret_assignment(domain, placement_assignment, candidate_assignment,
                         relocation_assignment, *, model_path=None, model_sha256=None):
    """Interpret an assignment only when bound to independently verified v2 bytes.

    Supplying no authority is a supported fail-closed query and returns UNKNOWN.
    Supplying only part of the authority, a digest mismatch, or a domain that is
    not the verified artifact's native domain is malformed and raises ValueError.
    """
    if model_path is None and model_sha256 is None:
        return _unknown("VERIFIED_V2_ARTIFACT_AUTHORITY_ABSENT")
    if model_path is None or model_sha256 is None:
        raise ValueError("P artifact path and SHA-256 authority must be supplied together")
    _, verified_domain, evidence_complete = _load_verified_v2_artifact(
        model_path, model_sha256)
    if domain != verified_domain:
        raise ValueError("P diagnostic domain differs from verified artifact authority")
    if not evidence_complete:
        return _unknown("VERIFIED_V2_SERIALIZED_EVIDENCE_INCOMPLETE")
    return _interpret_verified_assignment(
        verified_domain, placement_assignment, candidate_assignment,
        relocation_assignment)


def _csv(value):
    if value == "":
        return []
    try:
        return [int(item) for item in value.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("assignment must be comma-separated integers") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--placements", type=_csv, required=True)
    parser.add_argument("--candidates", type=_csv, required=True)
    parser.add_argument("--relocations", type=_csv, required=True)
    args = parser.parse_args()
    structure, domain, evidence_complete = _load_verified_v2_artifact(
        args.model, args.model_sha256)
    result = _interpret_verified_assignment(
        domain, args.placements, args.candidates, args.relocations) \
        if evidence_complete else _unknown("VERIFIED_V2_SERIALIZED_EVIDENCE_INCOMPLETE")
    result.update({"schema": "p-acceptance-slice-diagnostic-v1",
                   "status": "DIAGNOSTIC", "cell": structure.get("cell"),
                   "modelSha256": args.model_sha256,
                   "verifierAcceptanceComplete": False})
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
