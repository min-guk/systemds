#!/usr/bin/env python3
"""Bounded exact membership query over a captured E typed projection.

The query answers whether one complete canonical physical plan is produced by
at least one definitely-ALLOW native assignment in the captured E model.  It
uses the materialized hard factors for acceptance, the compiler-owned typed
decoder for projection, and the pinned physical-coordinate verifier for exact
plan equality.

This is deliberately a model-local result.  It does not independently bind the
typed decoder to Java planner semantics and it does not establish P/E equality.
"""

import argparse
import hashlib
from itertools import product
import json
from math import prod
from pathlib import Path

from exact_e_factor_count import read_model
from exact_e_physical_relation import (compose_physical_projection, factor_status,
                                       projection_contract, validate_identity)
from physical_coordinate_contract import PhysicalCoordinateCodec


SCHEMA = "e-canonical-membership-query-v1"
CLAIM_SCOPE = "CAPTURED_E_DEFINITE_ALLOW_TYPED_DECODER_MEMBERSHIP_ONLY"
DEFAULT_MAX_TARGET_PLAN_BYTES = 64 * 1024 ** 2


def _positive_integer(value, label):
    if type(value) is not int or value < 1:
        raise ValueError(label + " must be a positive integer")


def _combined_status(tables, assignment, radices):
    status = "ALLOW"
    for scope, truth in tables:
        current = factor_status(scope, truth, assignment, radices)
        if current == "REJECT":
            return "REJECT"
        if current == "UNKNOWN":
            status = "UNKNOWN"
    return status


def _unsupported_projection_blockers(model):
    """Return typed constructs the bound decoder cannot soundly materialize."""
    projection = model.get("physicalProjection")
    if not isinstance(projection, dict):
        return []
    for variable in projection.get("variables", []):
        if not isinstance(variable, dict):
            continue
        for fragment in variable.get("alternatives", []):
            if not isinstance(fragment, dict):
                continue
            for binding in fragment.get("bindings", []):
                if (isinstance(binding, dict) and binding.get("mode") == "PHI" and
                        binding.get("ftypeFromProducer") is True):
                    return ["PHI_FTYPE_FROM_PRODUCER_DECODER_UNSUPPORTED"]
    return []


def query_membership(model_path, target_plan, verification_root, *,
                     max_assignments=100_000,
                     max_model_decoded_bytes=4 * 1024 ** 3,
                     max_model_compressed_bytes=4 * 1024 ** 3,
                     max_target_plan_bytes=DEFAULT_MAX_TARGET_PLAN_BYTES):
    """Return SAT, UNSAT, or INCOMPLETE for one complete canonical plan.

    ``UNSAT`` is returned only after every native assignment has been visited
    and no UNKNOWN assignment projects to the target.  ``SAT`` requires a
    definitely-ALLOW witness.  Resource exhaustion or a matching UNKNOWN
    witness returns ``INCOMPLETE``.
    """
    for value, label in ((max_assignments, "max_assignments"),
                         (max_model_decoded_bytes, "max_model_decoded_bytes"),
                         (max_model_compressed_bytes,
                          "max_model_compressed_bytes"),
                         (max_target_plan_bytes, "max_target_plan_bytes")):
        _positive_integer(value, label)

    model, model_sha, radices, tables = read_model(
        model_path, max_decoded_bytes=max_model_decoded_bytes,
        max_compressed_bytes=max_model_compressed_bytes)
    identity, occurrences = validate_identity(model)
    del identity
    domain_occurrences = [domain.get("occurrence") for domain in model["domains"]]
    if (any(not isinstance(value, str) or not value
            for value in domain_occurrences) or
            len(set(domain_occurrences)) != len(domain_occurrences)):
        raise ValueError("E model domain occurrences are invalid or duplicated")
    occurrence_to_domain = {value: index
                            for index, value in enumerate(domain_occurrences)}
    contract = projection_contract(
        model, occurrence_to_domain, occurrences, radices)
    codec = PhysicalCoordinateCodec(verification_root)
    try:
        target_plan_bytes = json.dumps(
            target_plan, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False, allow_nan=False).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ValueError("target plan is not finite JSON") from error
    if len(target_plan_bytes) > max_target_plan_bytes:
        raise ValueError("target plan byte budget exhausted")
    target_coordinate = codec.encode(target_plan)
    target_sha = hashlib.sha256(target_coordinate).hexdigest()
    total = prod(radices)
    result = {
        "schema": SCHEMA,
        "claimScope": CLAIM_SCOPE,
        "status": "INCOMPLETE",
        "blockers": [],
        "modelSha256": model_sha,
        "projectionSha256": contract.get("projectionSha256"),
        "targetCoordinateSha256": target_sha,
        "identityVerifier": codec.verifier_identity,
        "nativeAssignmentCount": str(total),
        "visitedAssignments": "0",
        "acceptedAssignmentsVisited": "0",
        "unknownTargetWitnesses": "0",
        "witness": None,
        "budgets": {"maxAssignments": max_assignments,
                    "maxTargetPlanBytes": max_target_plan_bytes},
        "semanticBoundary": [
            "TYPED_DECODER_NOT_INDEPENDENTLY_BOUND_TO_JAVA_SEMANTICS",
            "NOT_A_P_E_EQUALITY_RESULT",
        ],
    }
    if contract["status"] != "DECODER_STRUCTURAL_ONLY":
        result["blockers"] = list(contract["blockers"])
        return result
    unsupported = _unsupported_projection_blockers(model)
    if unsupported:
        result["blockers"] = unsupported
        return result

    visited = 0
    accepted = 0
    unknown_matches = 0
    assignments = product(*(range(radix) for radix in radices))
    for assignment in assignments:
        if visited >= max_assignments:
            break
        visited += 1
        status = _combined_status(tables, assignment, radices)
        if status == "REJECT":
            continue
        try:
            projected = compose_physical_projection(model, assignment)
        except (KeyError, TypeError, ValueError):
            result["visitedAssignments"] = str(visited)
            result["acceptedAssignmentsVisited"] = str(accepted)
            result["unknownTargetWitnesses"] = str(unknown_matches)
            result["blockers"] = ["TYPED_DECODER_OUTPUT_NOT_CANONICAL"]
            result["blockedAssignment"] = list(assignment)
            return result
        try:
            matches = codec.encode(projected) == target_coordinate
        except ValueError as error:
            if str(error).startswith("physical identity verifier"):
                raise
            result["visitedAssignments"] = str(visited)
            result["acceptedAssignmentsVisited"] = str(accepted)
            result["unknownTargetWitnesses"] = str(unknown_matches)
            result["blockers"] = ["TYPED_DECODER_OUTPUT_NOT_CANONICAL"]
            result["blockedAssignment"] = list(assignment)
            return result
        if status == "ALLOW":
            accepted += 1
            if matches:
                result.update(
                    status="SAT", blockers=[],
                    witness={"nativeAssignment": list(assignment),
                             "acceptance": "ALLOW"})
                break
        elif matches:
            unknown_matches += 1

    result["visitedAssignments"] = str(visited)
    result["acceptedAssignmentsVisited"] = str(accepted)
    result["unknownTargetWitnesses"] = str(unknown_matches)
    if result["status"] == "SAT":
        return result
    if visited < total:
        result["blockers"].append("NATIVE_ASSIGNMENT_BUDGET_EXHAUSTED")
    if unknown_matches:
        result["blockers"].append("MATCHING_SOURCE_UNKNOWN_ASSIGNMENT")
    if not result["blockers"]:
        result["status"] = "UNSAT"
    return result


def _read_plan(path, max_bytes=DEFAULT_MAX_TARGET_PLAN_BYTES):
    _positive_integer(max_bytes, "max_target_plan_bytes")
    path = Path(path)
    with path.open("rb") as stream:
        encoded = stream.read(max_bytes + 1)
    if len(encoded) > max_bytes:
        raise ValueError("target plan byte budget exhausted")
    return json.loads(encoded)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--verification-root", required=True)
    parser.add_argument("--max-assignments", type=int, default=100_000)
    parser.add_argument("--max-target-plan-bytes", type=int,
                        default=DEFAULT_MAX_TARGET_PLAN_BYTES)
    arguments = parser.parse_args(argv)
    result = query_membership(
        arguments.model,
        _read_plan(arguments.plan, arguments.max_target_plan_bytes),
        arguments.verification_root, max_assignments=arguments.max_assignments,
        max_target_plan_bytes=arguments.max_target_plan_bytes)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["status"] in ("SAT", "UNSAT") else 2


if __name__ == "__main__":
    raise SystemExit(main())
