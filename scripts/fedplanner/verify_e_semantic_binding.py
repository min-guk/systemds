#!/usr/bin/env python3
"""Independently bind Java E rows to the Python typed physical decoder.

This verifier is deliberately limited to bounded, exhaustive fixtures.  A pass
does not certify model-domain completeness or any full workload.
"""

import argparse
import gzip
import hashlib
import json
from math import prod
from pathlib import Path

from exact_e_factor_count import decode_factor_truth
from exact_e_physical_relation import (canonical, compose_physical_projection, digest,
                                       factor_status)


SCHEMA = "exact-e-java-physical-semantic-oracle-v1"
RESULT_SCHEMA = "exact-e-java-python-semantic-binding-v1"
MAX_DECODED_BYTES = 512 * 1024 * 1024
REQUIRED_COVERAGE = frozenset({
    "acceptance:EMITTED", "acceptance:REJECTED",
    "authorityLayout:BOUNDARY", "authorityLayout:LOCAL",
    "authorityLayout:SOURCE_LINEAGE", "authorityLayout:DURABLE_MAP",
    "authorityLayout:NATIVE_LINEAGE", "authority:externalSource",
    "authority:exactAnchor", "authority:workerResidency",
    "binding:PHI", "binding:LOGICAL_TRANSIENT", "binding:RELOCATION",
    "binding:ABSENT_LOCAL", "binding:DIRECT", "binding:DIRECT_FOUT",
    "bindingFType:EXPLICIT", "bindingFType:FROM_PRODUCER",
    "action:RELOCATION", "action:DERIVED_FOUT", "binding:actionRef",
    "coordinate:geometry", "coordinate:logicalInputs",
    "orderedDedup:actions", "orderedDedup:geometry",
    "node:shapeDependent", "node:executionFType",
})


def _read_gzip(path, max_decoded_bytes=MAX_DECODED_BYTES):
    with gzip.open(path, "rb") as stream:
        raw = stream.read(max_decoded_bytes + 1)
        if len(raw) > max_decoded_bytes or stream.read(1):
            raise ValueError("semantic-binding artifact decoded-byte budget exhausted")
    value = json.loads(raw)
    if raw != canonical(value):
        raise ValueError("semantic-binding artifact is not canonical JSON")
    return value, hashlib.sha256(raw).hexdigest()


def _read_model(path, max_decoded_bytes):
    model, model_sha = _read_gzip(path, max_decoded_bytes)
    if (model.get("schema") != "closed-e-native-model-artifact-v1" or
            model.get("acceptance") != "MATERIALIZED_FACTOR_TABLES"):
        raise ValueError("E model has no complete materialized hard factors")
    domains, factors = model.get("domains"), model.get("factors")
    if not isinstance(domains, list) or not domains or not isinstance(factors, list):
        raise ValueError("E model lacks finite domains/factors")
    radices = []
    for index, domain in enumerate(domains):
        if not isinstance(domain, dict):
            raise ValueError("E domain index or alternatives invalid")
        alternatives = domain.get("alternatives")
        if (domain.get("index") != index or not isinstance(alternatives, list) or
                not alternatives or any(not isinstance(item, dict) for item in alternatives) or
                len({item.get("signature") for item in alternatives}) != len(alternatives)):
            raise ValueError("E domain index or alternatives invalid")
        radices.append(len(alternatives))
    tables = []
    for factor in factors:
        scope = factor.get("scope") if isinstance(factor, dict) else None
        if (not isinstance(scope, list) or len(scope) != len(set(scope)) or
                any(type(index) is not int or index < 0 or index >= len(radices)
                    for index in scope)):
            raise ValueError("E factor scope/truth invalid")
        cells = prod(radices[index] for index in scope)
        if factor.get("cells") != str(cells):
            raise ValueError("E factor table cardinality/status invalid")
        tables.append((tuple(scope), decode_factor_truth(factor.get("truth"), cells)))
    return model, model_sha, radices, tables


def _assignment(ordinal, radices):
    values = [0] * len(radices)
    for index in range(len(radices) - 1, -1, -1):
        ordinal, values[index] = divmod(ordinal, radices[index])
    if ordinal:
        raise ValueError("oracle ordinal exceeds model domains")
    return values


def _status(tables, assignment, radices):
    statuses = [factor_status(scope, truth, assignment, radices)
                for scope, truth in tables]
    if "REJECT" in statuses:
        return "REJECTED"
    if "UNKNOWN" in statuses:
        return "UNKNOWN"
    return "EMITTED"


def _ordered_duplicate(rows):
    keys = [canonical(row) for row in rows]
    return len(keys) != len(set(keys))


def _coverage(model, assignments, plans):
    coverage = set()
    projection = model["physicalProjection"]
    for assignment, plan in zip(assignments, plans):
        selected = [projection["variables"][index]["alternatives"][value]
                    for index, value in enumerate(assignment)]
        flat_actions = [row for fragment in selected for row in fragment["actions"]]
        flat_geometry = [row for fragment in selected for row in fragment["geometry"]]
        if _ordered_duplicate(flat_actions):
            coverage.add("orderedDedup:actions")
        if _ordered_duplicate(flat_geometry):
            coverage.add("orderedDedup:geometry")
        for fragment in selected:
            identity = fragment["authority"]["id"]
            coverage.add("authorityLayout:" + identity["layout"])
            for key in ("externalSource", "anchor", "workerResidency"):
                if key in identity:
                    coverage.add("authority:" + ("exactAnchor" if key == "anchor" else key))
            node = fragment["node"]
            if node["shapeDependent"]:
                coverage.add("node:shapeDependent")
            if node["executionFType"] != "NONE":
                coverage.add("node:executionFType")
            for template in fragment["bindings"]:
                coverage.add("bindingFType:" +
                             ("EXPLICIT" if "ftype" in template else "FROM_PRODUCER"))
        for action in plan["actions"]:
            coverage.add("action:" + action["kind"])
        for binding in plan["bindings"]:
            coverage.add("binding:" + binding["inputAuthority"])
            if "actionRef" in binding:
                coverage.add("binding:actionRef")
        if plan["geometry"]:
            coverage.add("coordinate:geometry")
        if plan["logicalInputs"]:
            coverage.add("coordinate:logicalInputs")
    return coverage


def verify_case(model_path, oracle_path, expected_oracle_sha256, *,
                max_decoded_bytes=MAX_DECODED_BYTES):
    if (not isinstance(expected_oracle_sha256, str) or
            len(expected_oracle_sha256) != 64 or
            any(character not in "0123456789abcdef"
                for character in expected_oracle_sha256)):
        raise ValueError("external lowercase oracle SHA-256 commitment is required")
    if type(max_decoded_bytes) is not int or max_decoded_bytes < 1:
        raise ValueError("decoded-byte budget must be a positive integer")
    oracle, oracle_sha = _read_gzip(oracle_path, max_decoded_bytes)
    if oracle_sha != expected_oracle_sha256:
        raise ValueError("Java oracle SHA-256 differs from external commitment")
    if (oracle.get("schema") != SCHEMA or
            oracle.get("status") != "BLOCKED_PENDING_INDEPENDENT_PYTHON_VERIFICATION" or
            oracle.get("claimScope") !=
            "TINY_FIXTURE_FULL_PHYSICAL_IDENTITY_DIFFERENTIAL_ONLY" or
            not isinstance(oracle.get("fixture"), str) or not oracle["fixture"]):
        raise ValueError("unsupported Java semantic oracle schema")
    model, model_sha, radices, tables = _read_model(model_path, max_decoded_bytes)
    if model_sha != oracle.get("modelSha256") or model.get("cell") != oracle["fixture"]:
        raise ValueError("Java oracle model commitment differs")
    if (oracle.get("programSha256") != model.get("programSha256") or
            oracle.get("sourceIdentitySha256") != digest(model["sourceIdentity"]) or
            oracle.get("projectionSha256") != digest(model["physicalProjection"])):
        raise ValueError("Java oracle source/projection commitment differs")
    signatures = [[alternative["signature"] for alternative in domain["alternatives"]]
                  for domain in model["domains"]]
    if oracle.get("domainCommitmentSha256") != digest(signatures):
        raise ValueError("Java oracle domain commitment differs")
    raw_count = prod(radices)
    rows = oracle.get("rows")
    if (oracle.get("rawAssignments") != str(raw_count) or
            not isinstance(rows, list) or len(rows) != raw_count):
        raise ValueError("Java oracle does not exhaust the raw assignment product")
    if any(template.get("mode") == "PHI" and "ftype" not in template
           for variable in model["physicalProjection"]["variables"]
           for fragment in variable["alternatives"]
           for template in fragment["bindings"]):
        raise ValueError("unsupported PHI producer-derived FType in semantic oracle")

    accepted_assignments = []
    accepted_plans = []
    status_counts = {"EMITTED": 0, "REJECTED": 0, "UNKNOWN": 0}
    for ordinal, row in enumerate(rows):
        assignment = _assignment(ordinal, radices)
        if (not isinstance(row, dict) or not isinstance(row.get("reason"), str) or
                row.get("ordinal") != str(ordinal) or row.get("assignment") != assignment or
                row.get("alternativeSignatures") !=
                [signatures[index][value] for index, value in enumerate(assignment)]):
            raise ValueError("Java oracle assignment identity or order differs")
        status = _status(tables, assignment, radices)
        if row.get("status") != status:
            raise ValueError("Java/Python assignment acceptance differs")
        status_counts[status] += 1
        if status == "UNKNOWN":
            raise ValueError("semantic-binding fixture contains UNKNOWN acceptance")
        if status == "EMITTED":
            expected = compose_physical_projection(model, assignment)
            if row.get("physicalPlan") != expected:
                raise ValueError("Java direct physical identity differs from Python composition")
            accepted_assignments.append(assignment)
            accepted_plans.append(expected)
        elif row.get("physicalPlan") is not None:
            raise ValueError("rejected Java oracle row carries a physical identity")
    if not accepted_plans:
        raise ValueError("semantic-binding fixture has no accepted physical identity")
    coverage = _coverage(model, accepted_assignments, accepted_plans)
    coverage.update("acceptance:" + status for status, count in status_counts.items()
                    if count)
    return {"fixture": oracle.get("fixture"), "modelSha256": model_sha,
            "oracleSha256": oracle_sha, "rawAssignments": str(raw_count),
            "statusCounts": {key: str(value) for key, value in status_counts.items()},
            "coverage": sorted(coverage),
            "comparisonStatus": "PASS_EXHAUSTIVE_FULL_PHYSICAL_IDENTITY"}


def verify_cases(cases, *, max_decoded_bytes=MAX_DECODED_BYTES):
    results = [verify_case(*case, max_decoded_bytes=max_decoded_bytes) for case in cases]
    coverage = set().union(*(result["coverage"] for result in results))
    missing = sorted(REQUIRED_COVERAGE - coverage)
    return {"schema": RESULT_SCHEMA, "status": "PASS" if not missing else "BLOCKED",
            "claimScope": "TINY_FIXTURE_FULL_PHYSICAL_IDENTITY_DIFFERENTIAL_ONLY",
            "semanticBindingStatus": ("PASS_EXHAUSTIVE_TINY_DIFFERENTIAL"
                                      if not missing else
                                      "BLOCKED_MISSING_REQUIRED_COORDINATE_COVERAGE"),
            "fullWorkloadCertification": "NOT_CLAIMED",
            "cases": results, "coverage": sorted(coverage),
            "missingRequiredCoverage": missing}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", nargs=3, action="append", required=True,
                        metavar=("MODEL", "ORACLE", "ORACLE_SHA256"))
    parser.add_argument("--result", type=Path)
    parser.add_argument("--max-decoded-bytes", type=int, default=MAX_DECODED_BYTES)
    args = parser.parse_args()
    result = verify_cases([(Path(model), Path(oracle), sha)
                           for model, oracle, sha in args.case],
                          max_decoded_bytes=args.max_decoded_bytes)
    wire = canonical(result)
    if args.result:
        args.result.parent.mkdir(parents=True, exist_ok=True)
        with gzip.GzipFile(filename=str(args.result), mode="wb", mtime=0) as stream:
            stream.write(wire)
    print(wire.decode())


if __name__ == "__main__":
    main()
