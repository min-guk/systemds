#!/usr/bin/env python3
"""Compile one canonical, non-PHI physical target into Boolean atom factors.

The result is a model-bound row preimage constraint.  It does not decide
satisfiability and does not establish either E completeness or P/E equality.
"""

import hashlib
import json
from pathlib import Path

from diagnose_e_physical_forest import translate_physical_forest
from physical_coordinate_contract import PhysicalCoordinateCodec


SCHEMA = "e-canonical-target-preimage-factors-v1"
CLAIM_SCOPE = "MODEL_LOCAL_NON_PHI_CANONICAL_TARGET_ROW_PREIMAGE_ONLY"
EXECUTION_PROVENANCE = "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH"
_COORDINATES = ("nodes", "authority", "actions", "bindings", "geometry")
_EXACTLY_ONE = frozenset(("nodes", "authority", "bindings"))


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _has_phi(model):
    projection = model.get("physicalProjection") if isinstance(model, dict) else None
    if not isinstance(projection, dict):
        return False
    try:
        return any(template.get("mode") == "PHI"
                   for variable in projection["variables"]
                   for fragment in variable["alternatives"]
                   for template in fragment["bindings"]
                   if isinstance(template, dict))
    except (KeyError, TypeError):
        return False


def _factor(name, levels, predicate):
    levels = tuple(sorted(levels))
    truth = []
    for bits in range(1 << len(levels)):
        selected = tuple((bits >> (len(levels) - index - 1)) & 1
                         for index in range(len(levels)))
        truth.append(bool(predicate(selected)))
    return {"name": name, "scope": list(levels), "truth": truth}


def _incomplete(blockers, bindings, budgets):
    payload = {
        "schema": SCHEMA, "status": "INCOMPLETE", "claimScope": CLAIM_SCOPE,
        "diagnosticStatus": "DIAGNOSTIC_ONLY",
        "executionProvenance": EXECUTION_PROVENANCE,
        "blockers": sorted(set(blockers)), "bindings": bindings,
        "budgets": budgets, "factorPayload": None,
        "claims": {"satisfiability": "NOT_ASSESSED",
                   "pEqualsE": "NOT_ASSESSED",
                   "executableAttestation": "NOT_ESTABLISHED"},
    }
    payload["artifactSha256"] = _digest(payload)
    return payload


def _complete(bindings, budgets, factor_payload, preimage_status,
              empty_reason=None):
    factor_payload["factorPayloadSha256"] = _digest(factor_payload)
    payload = {
        "schema": SCHEMA, "status": "COMPLETE", "claimScope": CLAIM_SCOPE,
        "diagnosticStatus": "DIAGNOSTIC_ONLY",
        "executionProvenance": EXECUTION_PROVENANCE,
        "preimageStatus": preimage_status, "emptyReason": empty_reason,
        "blockers": [], "bindings": bindings, "budgets": budgets,
        "factorPayload": factor_payload,
        "claims": {"satisfiability": "NOT_ASSESSED",
                   "pEqualsE": "NOT_ASSESSED",
                   "executableAttestation": "NOT_ESTABLISHED"},
    }
    payload["artifactSha256"] = _digest(payload)
    return payload


def _empty_preimage(reason, bindings, budgets, target_rows):
    return _complete(bindings, budgets, {
        "atomVariables": [],
        "targetFactors": [{"name": "empty-preimage:" + reason,
                           "scope": [], "truth": [False]}],
        "targetFactorCells": "1", "targetRowCount": str(target_rows),
        "constraintPolicy": {"emptyPreimage": "CONSTANT_FALSE"},
    }, "EMPTY_PREIMAGE", reason)


def _canonical_static(projection):
    """Apply the pinned verifier's independent static-coordinate ordering."""
    encoded = sorted(_canonical(row) for row in projection["logicalInputs"])
    return {"context": {"logical": projection["logicalProgram"]},
            "logicalInputs": [json.loads(row) for row in encoded]}


def _target_coordinate(codec, target):
    """Return a validated coordinate while preserving verifier-integrity errors."""
    try:
        coordinate = codec.encode(target)
        return coordinate, codec.decode(coordinate)
    except ValueError as error:
        message = str(error)
        if ("physical identity verifier" in message or
                "verifier module changed" in message):
            raise
        return None, None
    except (TypeError, OverflowError):
        return None, None


def _semantic_atom(atom):
    if not isinstance(atom, dict) or atom.get("coordinate") not in _COORDINATES:
        raise ValueError("UNSUPPORTED_ATOM_COORDINATE")
    coordinate = atom["coordinate"]
    if "value" not in atom or not isinstance(atom["value"], dict):
        raise ValueError("UNSUPPORTED_ATOM_ROW_FORM")
    if coordinate == "bindings" and atom.get("part") != "ROW":
        raise ValueError("PHI_ATOM_UNSUPPORTED")
    return coordinate, _canonical(atom["value"])


def compile_target_preimage(
        model_path, target, verification_root, *, max_target_rows=10_000,
        max_target_factor_cells=1_000_000, max_group_atoms=20,
        max_factor_cells=1_000_000, max_total_factor_cells=10_000_000,
        max_atoms=10_000, max_terms=100_000,
        max_equivalence_checks=10_000_000):
    """Return deterministic Boolean factors for exact target-row equality.

    Factor scopes use the global levels returned by
    :func:`translate_physical_forest`.  Callers conjoin ``targetFactors`` with
    that translation's hard and atom-equivalence factors before elimination.
    """
    budgets = {
        "maxTargetRows": max_target_rows,
        "maxTargetFactorCells": max_target_factor_cells,
        "maxGroupAtoms": max_group_atoms,
        "maxFactorCells": max_factor_cells,
        "maxTotalFactorCells": max_total_factor_cells,
        "maxAtoms": max_atoms, "maxTerms": max_terms,
        "maxEquivalenceChecks": max_equivalence_checks,
    }
    if any(type(value) is not int or value < 1 for value in budgets.values()):
        raise ValueError("all preimage budgets must be positive integers")

    bindings = {
        "modelPath": str(Path(model_path).resolve()),
        "modelSha256": None, "modelFileSha256": None,
        "projectionSha256": None, "atomDictionarySha256": None,
        "atomConditionsSha256": None, "allVariablesSha256": None,
        "hardAndAtomFactorsSha256": None,
        "targetCanonicalSha256": None,
        "targetCoordinateSha256": None,
        "identityVerifierSha256": None,
    }
    try:
        translated = translate_physical_forest(
            model_path, max_factor_cells=max_factor_cells,
            max_total_factor_cells=max_total_factor_cells, max_atoms=max_atoms,
            max_terms=max_terms, max_equivalence_checks=max_equivalence_checks)
    except (TypeError, ValueError, OverflowError):
        return _incomplete(["MODEL_TRANSLATION_UNSUPPORTED"], bindings, budgets)
    bindings.update({
        "modelSha256": translated.get("modelSha256"),
        "modelFileSha256": translated.get("modelFileSha256"),
    })
    if translated["physicalTranslationStatus"] != "COMPLETE":
        return _incomplete(translated["physicalBlockers"], bindings, budgets)
    if translated["sourceStatusCounts"].get("UNKNOWN", 0):
        return _incomplete(["SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED"],
                           bindings, budgets)
    if _has_phi(translated.get("model")):
        return _incomplete(["PHI_UNSUPPORTED"], bindings, budgets)

    projection = translated["model"]["physicalProjection"]
    bindings["projectionSha256"] = translated["projectionContract"][
        "projectionSha256"]
    bindings["atomDictionarySha256"] = _digest(translated["atomDictionary"])
    bindings["atomConditionsSha256"] = _digest({
        token: [[list(pair) for pair in term] for term in rows]
        for token, rows in sorted(translated["atomConditions"].items())})
    bindings["allVariablesSha256"] = _digest([
        {"name": variable.name, "values": list(variable.values)}
        for variable in translated["allVariables"]])
    bindings["hardAndAtomFactorsSha256"] = _digest([
        {"name": factor["name"], "scope": list(factor["scope"]),
         "truth": list(factor["truth"])}
        for factor in translated["allFactors"]])

    codec = PhysicalCoordinateCodec(verification_root)
    coordinate, canonical_target = _target_coordinate(codec, target)
    if coordinate is None:
        return _incomplete(["TARGET_UNSUPPORTED_OR_INVALID"], bindings, budgets)
    bindings["identityVerifierSha256"] = _digest(codec.verifier_identity)
    bindings["targetCoordinateSha256"] = hashlib.sha256(coordinate).hexdigest()
    bindings["targetCanonicalSha256"] = hashlib.sha256(
        codec.canonical_plan_bytes(canonical_target)).hexdigest()
    if _canonical(target) != codec.canonical_plan_bytes(canonical_target):
        return _incomplete(["TARGET_NOT_CANONICAL"], bindings, budgets)

    target_rows = sum(len(canonical_target[name]) for name in _COORDINATES)
    if target_rows > max_target_rows:
        return _incomplete(["TARGET_ROW_BUDGET_EXHAUSTED"], bindings, budgets)

    expected_static = _canonical_static(projection)
    if (canonical_target["context"] != expected_static["context"] or
            canonical_target["logicalInputs"] != expected_static["logicalInputs"]):
        return _empty_preimage("STATIC_COORDINATES_DIFFER", bindings, budgets,
                               target_rows)

    groups = {}
    try:
        for token, atom in sorted(translated["atomDictionary"].items()):
            key = _semantic_atom(atom)
            groups.setdefault(key, []).append(translated["atomLevels"][token])
    except (KeyError, TypeError, ValueError) as error:
        blocker = str(error) if str(error).isupper() else "UNSUPPORTED_ATOM_SCHEMA"
        return _incomplete([blocker], bindings, budgets)

    required = []
    for coordinate_name in _COORDINATES:
        for row in canonical_target[coordinate_name]:
            required.append((coordinate_name, _canonical(row)))
    if len(required) != len(set(required)):
        return _incomplete(["AMBIGUOUS_CANONICAL_TARGET_ROWS"], bindings, budgets)
    required = set(required)
    missing = sorted(coordinate + ":" + hashlib.sha256(row).hexdigest()
                     for coordinate, row in required
                     if (coordinate, row) not in groups)
    if missing:
        bindings["missingTargetRowSha256"] = missing
        return _empty_preimage("TARGET_ROW_MISSING_FROM_ATOM_DICTIONARY",
                               bindings, budgets, target_rows)

    factors = []
    factor_cells = 0
    for key, levels in sorted(groups.items(), key=lambda item: item[0]):
        coordinate_name, row = key
        levels = tuple(sorted(levels))
        if len(levels) > max_group_atoms:
            return _incomplete(["TARGET_ATOM_GROUP_BUDGET_EXHAUSTED"],
                               bindings, budgets)
        present = key in required
        cells = 1 << len(levels)
        if factor_cells + cells > max_target_factor_cells:
            return _incomplete(["TARGET_FACTOR_CELL_BUDGET_EXHAUSTED"],
                               bindings, budgets)
        factor_cells += cells
        row_sha = hashlib.sha256(row).hexdigest()
        if not present:
            factors.append(_factor("forbid-extra:%s:%s" %
                                   (coordinate_name, row_sha), levels,
                                   lambda bits: not any(bits)))
        elif coordinate_name in _EXACTLY_ONE:
            factors.append(_factor("require-exactly-one:%s:%s" %
                                   (coordinate_name, row_sha), levels,
                                   lambda bits: sum(bits) == 1))
        else:
            factors.append(_factor("require-deduplicated:%s:%s" %
                                   (coordinate_name, row_sha), levels,
                                   lambda bits: any(bits)))

    variable_rows = [{"level": level, "name": translated["allVariables"][level].name,
                      "values": list(translated["allVariables"][level].values)}
                     for level in sorted(translated["atomLevels"].values())]
    factor_payload = {
        "atomVariables": variable_rows,
        "targetFactors": factors,
        "targetFactorCells": str(factor_cells),
        "targetRowCount": str(target_rows),
        "constraintPolicy": {
            "nodesAuthorityBindings": "EXACTLY_ONE_MATCHING_ATOM",
            "actionsGeometry": "OR_MATCHING_PROVENANCE_ATOMS",
            "nonTargetRows": "ALL_MATCHING_ATOMS_FALSE",
        },
    }
    return _complete(bindings, budgets, factor_payload, "FACTORIZED")
