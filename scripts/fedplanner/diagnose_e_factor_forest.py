#!/usr/bin/env python3
"""Bounded factor-forest diagnostic for one frozen E hard-factor model.

The adapter validates the E artifact with ``exact_e_factor_count.read_model``,
substitutes singleton native domains, maps only ``ALLOW`` cells to Boolean
true, and projects every varying native domain with ``factor_forest_image``.
It diagnoses the captured E hard-factor relation only.  It does not construct
physical plans or establish P/E equality or planner completeness.
"""

import argparse
import gzip
import hashlib
from itertools import product
import json
from math import prod
from pathlib import Path
import tempfile

from boolean_mdd_relation import Variable
from exact_e_factor_count import (DEFAULT_MAX_MODEL_COMPRESSED_BYTES,
                                  DEFAULT_MAX_MODEL_DECODED_BYTES, read_model)
from factor_forest_image import (eliminate_factor_forest,
                                 verify_factor_forest_artifact,
                                 verify_factor_forest_blocked,
                                 verify_factor_forest_result)


SCHEMA = "e-factor-forest-diagnostic-v1"
CLAIM_SCOPE = "CAPTURED_E_DEFINITE_ALLOW_HARD_FACTOR_RELATION_ONLY"
ORDER_POLICY = "MIN_FILL_THEN_BAG_CELLS_THEN_SOURCE_DOMAIN_V1"
DEFAULT_MAX_ARTIFACT_DECODED_BYTES = 2 * 1024 ** 3
DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES = 512 * 1024 ** 2
_READ_CHUNK_BYTES = 1024 * 1024


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def select_elimination_order(radices, tables):
    """Choose deterministic structural min-fill order over varying domains."""
    varying = {index for index, radix in enumerate(radices) if radix > 1}
    scopes = [frozenset(index for index in scope if index in varying)
              for scope, _ in tables]
    scopes = [scope for scope in scopes if scope]
    order = []
    peak_bag_cells = 1
    while varying:
        neighbors = {domain: set() for domain in varying}
        for scope in scopes:
            for domain in scope:
                neighbors[domain].update(scope - {domain})

        def score(domain):
            adjacent = sorted(neighbors[domain])
            fill = sum(right not in neighbors[left]
                       for position, left in enumerate(adjacent)
                       for right in adjacent[position + 1:])
            cells = radices[domain] * prod(radices[item] for item in adjacent)
            return fill, cells, domain

        selected = min(varying, key=score)
        incident = [scope for scope in scopes if selected in scope]
        scopes = [scope for scope in scopes if selected not in scope]
        bag = frozenset().union(*incident, {selected})
        peak_bag_cells = max(
            peak_bag_cells, prod(radices[index] for index in bag))
        output = bag - {selected}
        if output:
            scopes.append(output)
        varying.remove(selected)
        order.append(selected)
    return tuple(order), peak_bag_cells


def translate_model(model_path, *, max_factor_cells=1_000_000,
                    max_total_factor_cells=10_000_000,
                    max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
                    max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    """Return exact Boolean inputs and source commitments for the kernel."""
    for label, value in (("max_factor_cells", max_factor_cells),
                         ("max_total_factor_cells", max_total_factor_cells)):
        if type(value) is not int or value < 1:
            raise ValueError(label + " must be a positive integer")
    model_path = Path(model_path)
    model, model_sha, radices, tables = read_model(
        model_path, max_decoded_bytes=max_model_decoded_bytes,
        max_compressed_bytes=max_model_compressed_bytes)
    order, peak_bag_cells = select_elimination_order(radices, tables)
    position = {domain: level for level, domain in enumerate(order)}
    variables = tuple(Variable(
        "native:%d:%s" % (domain, model["domains"][domain]["occurrence"]),
        tuple(alternative["signature"]
              for alternative in model["domains"][domain]["alternatives"]))
        for domain in order)
    total_cells = 0
    source_status_counts = {"ALLOW": 0, "REJECT": 0, "UNKNOWN": 0}
    factors = []
    for factor_index, (source_scope, truth) in enumerate(tables):
        varying_scope = tuple(index for index in source_scope
                              if radices[index] > 1)
        ordered_domains = tuple(sorted(varying_scope, key=position.__getitem__))
        target_scope = tuple(position[index] for index in ordered_domains)
        cells = prod(radices[index] for index in ordered_domains)
        total_cells += cells
        if cells > max_factor_cells or total_cells > max_total_factor_cells:
            return {
                "model": model, "modelSha256": model_sha,
                "modelFileSha256": _file_sha(model_path),
                "radices": radices, "tables": tables, "order": order,
                "variables": variables, "factors": None,
                "translationStatus": "BLOCKED_RESOURCE_LIMIT",
                "blockers": ["BOOLEAN_FACTOR_CELL_BUDGET_EXHAUSTED"],
                "peakStructuralBagCells": peak_bag_cells,
                "translatedFactorCells": total_cells,
            }
        values = []
        assignment = {index: 0 for index in source_scope}
        for selected in product(*(range(radices[index])
                                  for index in ordered_domains)):
            assignment.update(zip(ordered_domains, selected))
            offset = 0
            for index in source_scope:
                offset = offset * radices[index] + assignment[index]
            status = truth[offset]
            source_status_counts[status] += 1
            values.append(status == "ALLOW")
        factors.append({"name": "e-factor:%06d" % factor_index,
                        "scope": target_scope, "truth": tuple(values)})
    return {
        "model": model, "modelSha256": model_sha,
        "modelFileSha256": _file_sha(model_path),
        "radices": radices, "tables": tables, "order": order,
        "variables": variables, "factors": tuple(factors),
        "translationStatus": "COMPLETE", "blockers": [],
        "peakStructuralBagCells": peak_bag_cells,
        "translatedFactorCells": total_cells,
        "sourceStatusCounts": source_status_counts,
    }


def _translation_wire(translated):
    order = translated["order"]
    return {
        "status": translated["translationStatus"],
        "orderPolicy": ORDER_POLICY,
        "sourceDomainCount": len(translated["radices"]),
        "varyingDomainCount": len(order),
        "singletonDomainCount": len(translated["radices"]) - len(order),
        "sourceFactorCount": len(translated["tables"]),
        "translatedFactorCells": str(translated["translatedFactorCells"]),
        "peakStructuralBagCells": str(translated["peakStructuralBagCells"]),
        "sourceDomainOrder": list(order),
        "sourceStatusCounts": translated.get("sourceStatusCounts"),
        "singletonSubstitution": "ONLY_ALTERNATIVE_INDEX_ZERO",
        "truthPolicy": "TRUE_IFF_SOURCE_STATUS_ALLOW",
        "zeroFactorNormalization":
            "EMPTY_CONJUNCTION_TO_CONSTANT_TRUE_FACTOR",
    }


def build(model_path, *, max_nodes=250_000, max_apply_pairs=1_000_000,
          max_factor_cells=1_000_000, max_total_factor_cells=10_000_000,
          max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
          max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    for label, value in (
            ("max_nodes", max_nodes), ("max_apply_pairs", max_apply_pairs),
            ("max_factor_cells", max_factor_cells),
            ("max_total_factor_cells", max_total_factor_cells),
            ("max_model_decoded_bytes", max_model_decoded_bytes),
            ("max_model_compressed_bytes", max_model_compressed_bytes)):
        if type(value) is not int or value < 1:
            raise ValueError(label + " must be a positive integer")
    translated = translate_model(
        model_path, max_factor_cells=max_factor_cells,
        max_total_factor_cells=max_total_factor_cells,
        max_model_decoded_bytes=max_model_decoded_bytes,
        max_model_compressed_bytes=max_model_compressed_bytes)
    budgets = {"maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
               "maxFactorCells": max_factor_cells,
               "maxTotalFactorCells": max_total_factor_cells,
               "maxModelDecodedBytes": max_model_decoded_bytes,
               "maxModelCompressedBytes": max_model_compressed_bytes}
    blockers = ["NOT_A_PHYSICAL_PLAN_RELATION", "NOT_A_P_E_EQUALITY_CERTIFICATE"]
    forest = None
    if translated["translationStatus"] == "COMPLETE":
        forest = eliminate_factor_forest(
            translated["variables"], translated["factors"],
            tuple(range(len(translated["variables"]))), max_nodes=max_nodes,
            max_apply_pairs=max_apply_pairs)
        diagnostic_status = forest["diagnosticStatus"]
        if diagnostic_status != "DIAGNOSTIC_COMPLETE":
            blockers.extend(forest["blockers"])
        if translated["sourceStatusCounts"]["UNKNOWN"]:
            blockers.append("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED")
    else:
        diagnostic_status = "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT"
        blockers.extend(translated["blockers"])
    payload = {
        "schema": SCHEMA, "status": "BLOCKED",
        "diagnosticStatus": diagnostic_status, "claimScope": CLAIM_SCOPE,
        "blockers": blockers, "cell": translated["model"].get("cell"),
        "modelSha256": translated["modelSha256"],
        "modelFileSha256": translated["modelFileSha256"],
        "modelSchema": translated["model"].get("schema"),
        "programSha256": translated["model"].get("programSha256"),
        "conditionSha256": translated["model"].get("conditionSha256"),
        "translation": _translation_wire(translated), "budgets": budgets,
        "factorForest": forest,
    }
    payload["artifactSha256"] = _digest(payload)
    return payload


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
        with gzip.GzipFile(filename="", fileobj=stream, mode="wb",
                           mtime=0) as compressed:
            compressed.write(_canonical(value) + b"\n")
        temporary = Path(stream.name)
    temporary.replace(path)


def read_saved(path, *,
               max_decoded_bytes=DEFAULT_MAX_ARTIFACT_DECODED_BYTES,
               max_compressed_bytes=DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES):
    """Read one gzip artifact under explicit compressed and decoded limits."""
    if (type(max_decoded_bytes) is not int or max_decoded_bytes < 1 or
            type(max_compressed_bytes) is not int or max_compressed_bytes < 1):
        raise ValueError("artifact byte limits must be positive integers")
    path = Path(path)
    if path.stat().st_size > max_compressed_bytes:
        raise ValueError("E factor-forest compressed-byte budget exhausted")
    decoded = 0
    with tempfile.SpooledTemporaryFile(
            max_size=8 * 1024 * 1024, mode="w+b", dir=path.parent) as target:
        with gzip.open(path, "rb") as source:
            while True:
                block = source.read(_READ_CHUNK_BYTES)
                if not block:
                    break
                decoded += len(block)
                if decoded > max_decoded_bytes:
                    raise ValueError(
                        "E factor-forest decoded-byte budget exhausted")
                target.write(block)
        target.seek(0)
        return json.load(target)


def verify_saved(model_path, artifact_path, expected_file_sha256=None, *,
                 max_artifact_decoded_bytes=DEFAULT_MAX_ARTIFACT_DECODED_BYTES,
                 max_artifact_compressed_bytes=
                 DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES,
                 max_nodes=250_000, max_apply_pairs=1_000_000,
                 max_factor_cells=1_000_000,
                 max_total_factor_cells=10_000_000,
                 max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
                 max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    artifact_path = Path(artifact_path)
    if (type(max_artifact_decoded_bytes) is not int or
            max_artifact_decoded_bytes < 1 or
            type(max_artifact_compressed_bytes) is not int or
            max_artifact_compressed_bytes < 1):
        raise ValueError("artifact byte limits must be positive integers")
    verifier_caps = {
        "maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
        "maxFactorCells": max_factor_cells,
        "maxTotalFactorCells": max_total_factor_cells,
        "maxModelDecodedBytes": max_model_decoded_bytes,
        "maxModelCompressedBytes": max_model_compressed_bytes}
    if any(type(value) is not int or value < 1
           for value in verifier_caps.values()):
        raise ValueError("verifier construction caps must be positive integers")
    # Reject oversized input before hashing it.  The subsequent SHA read is
    # therefore bounded by the same compressed-byte limit as decompression.
    if artifact_path.stat().st_size > max_artifact_compressed_bytes:
        raise ValueError("E factor-forest compressed-byte budget exhausted")
    file_sha = _file_sha(artifact_path)
    if expected_file_sha256 is not None and file_sha != expected_file_sha256:
        raise ValueError("E factor-forest file SHA-256 mismatch")
    saved = read_saved(
        artifact_path, max_decoded_bytes=max_artifact_decoded_bytes,
        max_compressed_bytes=max_artifact_compressed_bytes)
    if not isinstance(saved, dict) or saved.get("schema") != SCHEMA:
        raise ValueError("saved E factor-forest diagnostic has invalid schema")
    payload = {key: value for key, value in saved.items()
               if key != "artifactSha256"}
    if saved.get("artifactSha256") != _digest(payload):
        raise ValueError("E factor-forest artifact commitment mismatch")
    budgets = saved.get("budgets")
    expected_budget_keys = {
        "maxNodes", "maxApplyPairs", "maxFactorCells", "maxTotalFactorCells",
        "maxModelDecodedBytes", "maxModelCompressedBytes"}
    if not isinstance(budgets, dict) or set(budgets) != expected_budget_keys:
        raise ValueError("E factor-forest budget contract is invalid")
    if any(type(value) is not int or value < 1 for value in budgets.values()):
        raise ValueError("E factor-forest stored budgets are invalid")
    exceeded = sorted(key for key, cap in verifier_caps.items()
                      if budgets[key] > cap)
    if exceeded:
        raise ValueError("saved construction budget exceeds verifier cap: " +
                         ",".join(exceeded))
    rebuilt = build(
        model_path, max_nodes=budgets["maxNodes"],
        max_apply_pairs=budgets["maxApplyPairs"],
        max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_model_decoded_bytes=budgets["maxModelDecodedBytes"],
        max_model_compressed_bytes=budgets["maxModelCompressedBytes"])
    if rebuilt != saved:
        raise ValueError("saved E factor-forest diagnostic differs from source replay")
    translated = translate_model(
        model_path, max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_model_decoded_bytes=budgets["maxModelDecodedBytes"],
        max_model_compressed_bytes=budgets["maxModelCompressedBytes"])
    forest = saved["factorForest"]
    if forest is None:
        forest_status = "NOT_PRODUCED_TRANSLATION_BLOCKED"
    elif forest["diagnosticStatus"] == "DIAGNOSTIC_COMPLETE":
        verify_factor_forest_artifact(forest, forest["resultSha256"])
        source_check = verify_factor_forest_result(
            forest, translated["variables"], translated["factors"],
            tuple(range(len(translated["variables"]))), max_replay_cells=1)
        forest_status = source_check["semanticReplayStatus"]
    else:
        verify_factor_forest_blocked(forest, forest["diagnosticSha256"])
        forest_status = "VERIFIED_BLOCKED"
    return {"status": "PASS", "cell": saved["cell"],
            "artifactSha256": saved["artifactSha256"],
            "fileSha256": file_sha,
            "sourceReplayStatus": "EXACT_DETERMINISTIC_RECOMPUTATION",
            "forestSemanticReplayStatus": forest_status}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--expected-file-sha256")
    parser.add_argument("--max-nodes", type=int, default=250_000)
    parser.add_argument("--max-apply-pairs", type=int, default=1_000_000)
    parser.add_argument("--max-factor-cells", type=int, default=1_000_000)
    parser.add_argument("--max-total-factor-cells", type=int, default=10_000_000)
    parser.add_argument("--max-artifact-decoded-bytes", type=int,
                        default=DEFAULT_MAX_ARTIFACT_DECODED_BYTES)
    parser.add_argument("--max-artifact-compressed-bytes", type=int,
                        default=DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES)
    parser.add_argument("--max-model-decoded-bytes", type=int,
                        default=DEFAULT_MAX_MODEL_DECODED_BYTES)
    parser.add_argument("--max-model-compressed-bytes", type=int,
                        default=DEFAULT_MAX_MODEL_COMPRESSED_BYTES)
    args = parser.parse_args()
    if args.mode == "run":
        result = build(
            args.model, max_nodes=args.max_nodes,
            max_apply_pairs=args.max_apply_pairs,
            max_factor_cells=args.max_factor_cells,
            max_total_factor_cells=args.max_total_factor_cells,
            max_model_decoded_bytes=args.max_model_decoded_bytes,
            max_model_compressed_bytes=args.max_model_compressed_bytes)
        publish(args.artifact, result)
        output = {"status": result["status"],
                  "diagnosticStatus": result["diagnosticStatus"],
                  "cell": result["cell"],
                  "artifactSha256": result["artifactSha256"],
                  "fileSha256": _file_sha(args.artifact)}
    else:
        output = verify_saved(
            args.model, args.artifact, args.expected_file_sha256,
            max_artifact_decoded_bytes=args.max_artifact_decoded_bytes,
            max_artifact_compressed_bytes=args.max_artifact_compressed_bytes,
            max_nodes=args.max_nodes, max_apply_pairs=args.max_apply_pairs,
            max_factor_cells=args.max_factor_cells,
            max_total_factor_cells=args.max_total_factor_cells,
            max_model_decoded_bytes=args.max_model_decoded_bytes,
            max_model_compressed_bytes=args.max_model_compressed_bytes)
    print(json.dumps(output, sort_keys=True))


if __name__ == "__main__":
    main()
