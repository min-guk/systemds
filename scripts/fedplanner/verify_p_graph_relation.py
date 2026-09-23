#!/usr/bin/env python3
"""Certify only the stored P model's finite graph-constraint placement relation.

Candidate, relocation, privacy, and production acceptance predicates are outside
this graph certificate. Artifact-level evidence may independently cover additional
predicates. The model is bound by its uncompressed SHA-256 digest. The checker is
independent of Java planning and re-enumerates unpruned local products; it shares
this module's model decoder and graph predicate with the creator.
"""
import argparse
import gzip
import hashlib
import itertools
import json
from math import prod
from pathlib import Path

if __package__:
    from .verify_p_model_artifact import (
        acceptance_coverage, artifact_assessed_predicates, canonical, verify as verify_model,
    )
else:
    from verify_p_model_artifact import (
        acceptance_coverage, artifact_assessed_predicates, canonical, verify as verify_model,
    )

SCHEMA = "p-graph-placement-relation-v1"
ACTIVE = {"SAME_PLACEMENT", "SAME_VALUE_PLACEMENT", "SAME_FTYPE", "CONJUNCTIVE"}
PASSIVE = {"DOMINATES", "DISTINCT_CONTEXT", "SAME_ORIGIN"}


def load_model(path, digest):
    verify_model(path, digest)
    plain = gzip.decompress(Path(path).read_bytes())
    if hashlib.sha256(plain).hexdigest() != digest:
        raise ValueError("P artifact digest differs after structural verification")
    return json.loads(plain)["nativeDomain"]


def state(value):
    if not isinstance(value, str) or len(value.split("/")) != 4:
        raise ValueError("unsupported placement state")
    site, output, ftype, shape = value.split("/")
    if site not in ("CP", "CP_FILE", "SPARK", "GPU", "FED", "OOC", "INVALID") or \
            output not in ("FOUT", "LOUT", "NONE") or \
            ftype not in ("-", "ROW", "COL", "FULL", "BROADCAST", "PART", "OTHER") or \
            shape not in ("SHAPE_INDEPENDENT", "SHAPE_DEPENDENT"):
        raise ValueError("unsupported placement state")
    return output, None if ftype == "-" else ftype


def predicate(kind, evidence, left, right):
    if kind in PASSIVE:
        return True
    left_output, left_ftype = state(left)
    right_output, right_ftype = state(right)
    if kind == "SAME_PLACEMENT":
        return left == right
    if kind == "SAME_VALUE_PLACEMENT":
        return left_output == right_output and (left_output != "FOUT" or left_ftype == right_ftype)
    if kind == "SAME_FTYPE":
        return left_ftype == right_ftype
    if kind == "CONJUNCTIVE":
        if evidence.startswith("forbid-pair:"):
            pair = evidence[len("forbid-pair:"):].split("=>")
            if len(pair) != 2:
                raise ValueError("invalid forbid-pair constraint")
            return left != pair[0] or right != pair[1]
        return right_output != "FOUT" or (left_output == "FOUT" and left_ftype == right_ftype)
    raise ValueError("unsupported active constraint")


def compile_relation(domain):
    placements = domain.get("placementDomains")
    constraints = domain.get("constraints")
    nodes = domain.get("nodes")
    if not isinstance(placements, list) or not isinstance(constraints, list) or not isinstance(nodes, list):
        raise ValueError("missing relation inputs")
    owners = [row[0] for row in placements]
    if len(owners) != len(set(owners)):
        raise ValueError("duplicate placement owner")
    owner_index = {owner: index for index, owner in enumerate(owners)}
    node_domains = {}
    for row in nodes:
        if not isinstance(row, list) or len(row) != 5 or not isinstance(row[0], str) or \
                not isinstance(row[3], list) or row[0] in node_domains:
            raise ValueError("malformed or duplicate graph node")
        node_domains[row[0]] = row[3]
    node_owners = set(node_domains)
    if {owner for owner, values in node_domains.items() if values} != set(owners):
        raise ValueError("node and placement decision domains differ")
    domains = []
    for row in placements:
        if not isinstance(row, list) or len(row) != 2 or not isinstance(row[1], list) or not row[1]:
            raise ValueError("invalid placement domain")
        if len(row[1]) != len(set(row[1])):
            raise ValueError("duplicate placement state")
        for value in row[1]:
            state(value)
        if node_domains[row[0]] != row[1]:
            raise ValueError("node and placement alternatives differ")
        domains.append(row[1])
    for values in node_domains.values():
        for value in values:
            state(value)
    edges = []
    for constraint in constraints:
        if not isinstance(constraint, dict) or set(constraint) != \
                {"kind", "left", "right", "inputPosition", "evidence", "signature"}:
            raise ValueError("unsupported constraint record")
        kind = constraint["kind"]
        if kind not in ACTIVE | PASSIVE:
            raise ValueError("unsupported constraint kind")
        left, right = constraint["left"], constraint["right"]
        if not isinstance(left, str) or not isinstance(right, str) or \
                not isinstance(constraint["evidence"], str) or \
                not isinstance(constraint["signature"], str) or \
                type(constraint["inputPosition"]) is not int:
            raise ValueError("malformed constraint value")
        if kind == "CONJUNCTIVE" and constraint["evidence"].startswith("forbid-pair:"):
            pair = constraint["evidence"][len("forbid-pair:"):].split("=>")
            if len(pair) != 2:
                raise ValueError("invalid forbid-pair constraint")
        if left not in node_owners or right not in node_owners:
            raise ValueError("constraint endpoint absent from nodes")
        if kind in ACTIVE and left in owner_index and right in owner_index:
            edges.append((owner_index[left], owner_index[right], kind, constraint["evidence"]))
    parent = list(range(len(domains)))

    def root(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    for left, right, _, _ in edges:
        parent[root(left)] = root(right)
    groups = {}
    for index in range(len(domains)):
        groups.setdefault(root(index), []).append(index)
    components = sorted((sorted(group) for group in groups.values()), key=lambda group: group[0])
    return domains, edges, components


def nondecision_ids(domain):
    """Record constraints Java skips when either endpoint has no decision digit."""
    owners = {row[0] for row in domain["placementDomains"]}
    return [hashlib.sha256(canonical(row)).hexdigest() for row in domain["constraints"]
            if row["left"] not in owners or row["right"] not in owners]


def satisfies(indices, choices, domains, edges):
    chosen = dict(zip(indices, choices))
    return all(predicate(kind, evidence, domains[left][chosen[left]], domains[right][chosen[right]])
               for left, right, kind, evidence in edges if left in chosen and right in chosen)


def create_certificate(model_path, model_sha):
    domain = load_model(model_path, model_sha)
    domains, edges, components = compile_relation(domain)
    rows = []
    for indices in components:
        local_edges = [edge for edge in edges if edge[0] in indices and edge[1] in indices]
        tuples = [list(choice) for choice in itertools.product(*(range(len(domains[i])) for i in indices))
                  if satisfies(indices, choice, domains, local_edges)]
        rows.append({"coordinates": indices, "tuples": tuples})
    return {"schema": SCHEMA, "modelSha256": model_sha,
            "scope": "GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN",
            "placementCoordinates": len(domains), "components": rows,
            "choiceComponents": sum(any(len(domains[i]) > 1 for i in indices) for indices in components),
            "nonDecisionConstraintIds": nondecision_ids(domain),
            "graphValidPlacementCount": str(prod(len(row["tuples"]) for row in rows))}


def verify_certificate(model_path, model_sha, certificate_path):
    payload = gzip.decompress(Path(certificate_path).read_bytes())
    certificate = json.loads(payload)
    if payload != canonical(certificate):
        raise ValueError("certificate not canonical")
    if set(certificate) != {"schema", "modelSha256", "scope", "placementCoordinates",
                            "components", "choiceComponents", "nonDecisionConstraintIds",
                            "graphValidPlacementCount"} or \
            certificate.get("schema") != SCHEMA or certificate.get("modelSha256") != model_sha or \
            certificate.get("scope") != "GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN":
        raise ValueError("certificate contract or model hash differs")
    domain = load_model(model_path, model_sha)
    domains, edges, components = compile_relation(domain)
    if certificate.get("nonDecisionConstraintIds") != nondecision_ids(domain) or \
            certificate.get("choiceComponents") != sum(
                any(len(domains[i]) > 1 for i in indices) for indices in components):
        raise ValueError("nondecision constraints or choice components differ")
    rows = certificate.get("components")
    if not isinstance(rows, list) or len(rows) != len(components) or \
            certificate.get("placementCoordinates") != len(domains):
        raise ValueError("missing component or coordinate")
    count = 1
    for row, indices in zip(rows, components):
        if not isinstance(row, dict) or set(row) != {"coordinates", "tuples"} or row["coordinates"] != indices:
            raise ValueError("component coordinates differ")
        stored = row["tuples"]
        if not isinstance(stored, list):
            raise ValueError("component tuples absent")
        expected = itertools.product(*(range(len(domains[i])) for i in indices))
        active = [edge for edge in edges if edge[0] in indices and edge[1] in indices]
        cursor = iter(stored)
        accepted = 0
        for assignment in expected:
            # Independent recheck: stream the full Cartesian product and require
            # one canonical stored tuple for each and only each satisfying row.
            values = {index: domains[index][choice] for index, choice in zip(indices, assignment)}
            valid = True
            for left, right, kind, evidence in active:
                if not predicate(kind, evidence, values[left], values[right]):
                    valid = False
                    break
            if valid:
                if next(cursor, None) != list(assignment):
                    raise ValueError("missing, corrupt, or unordered tuple")
                accepted += 1
        if next(cursor, None) is not None:
            raise ValueError("extra tuple")
        count *= accepted
    if str(count) != certificate.get("graphValidPlacementCount"):
        raise ValueError("graph-valid count differs")
    return {"status": "GRAPH_RELATION_VERIFIED", "scope": certificate["scope"],
            "modelSha256": model_sha, "certificateSha256": hashlib.sha256(payload).hexdigest(),
            "components": len(components), "placementCoordinates": len(domains),
            "choiceComponents": certificate["choiceComponents"],
            "nonDecisionConstraints": len(certificate["nonDecisionConstraintIds"]),
            "acceptanceCoverage": acceptance_coverage(domain, (
                "DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS",
                *artifact_assessed_predicates(domain),
            )),
            "graphValidPlacementCount": str(count)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("create", "verify"))
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    args = parser.parse_args()
    if args.mode == "create":
        receipt = create_certificate(args.model, args.model_sha256)
        args.certificate.parent.mkdir(parents=True, exist_ok=True)
        with args.certificate.open("wb") as handle:
            with gzip.GzipFile(fileobj=handle, mode="wb", filename="", mtime=0, compresslevel=9) as packed:
                packed.write(canonical(receipt))
        print(json.dumps({"status": "CREATED", "certificate": str(args.certificate),
                          "components": len(receipt["components"]),
                          "graphValidPlacementCount": receipt["graphValidPlacementCount"]}, sort_keys=True))
    else:
        print(json.dumps(verify_certificate(args.model, args.model_sha256, args.certificate), sort_keys=True))


if __name__ == "__main__":
    main()
