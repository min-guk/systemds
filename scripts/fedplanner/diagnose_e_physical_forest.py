#!/usr/bin/env python3
"""Bounded factor-forest diagnostic for the typed E physical atom image.

This adapter combines the captured definite-ALLOW hard factors with the typed
physical atoms emitted by :func:`compositional_e_image.compile_atoms`.  Each
atom is represented by an explicit Boolean output variable constrained to be
equivalent to the disjunction of its native-choice conjunctions.  Only native
variables are eliminated; atom variables and their residual factor forest are
retained.

The result is a model-bound diagnostic over provenance atoms.  The typed
projection is not independently bound to Java semantics, and distinct atom
assignments are not claimed to be distinct canonical physical plans.  Every
artifact therefore remains ``status=BLOCKED`` and cannot establish P/E
equality.
"""

import argparse
import gzip
import hashlib
from itertools import combinations, product
import json
from math import prod
from pathlib import Path
import tempfile

from boolean_mdd_relation import MDDResourceLimitError, Variable, validate_artifact
from compositional_e_image import compile_atoms, materialize_atoms
from diagnose_e_factor_forest import (
    DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES,
    DEFAULT_MAX_ARTIFACT_DECODED_BYTES,
    DEFAULT_MAX_MODEL_COMPRESSED_BYTES,
    DEFAULT_MAX_MODEL_DECODED_BYTES,
    translate_model,
)
from exact_e_physical_relation import (
    compose_physical_projection,
    factor_status,
    projection_contract,
    validate_identity,
)
from factor_forest_image import (
    eliminate_factor_forest,
    verify_factor_forest_artifact,
    verify_factor_forest_blocked,
    verify_factor_forest_result,
)


SCHEMA = "e-physical-factor-forest-diagnostic-v2"
CLAIM_SCOPE = "CAPTURED_E_DEFINITE_ALLOW_PROVENANCE_ATOM_FOREST_ONLY"
_READ_CHUNK_BYTES = 1024 * 1024


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _file_sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(_READ_CHUNK_BYTES), b""):
            digest.update(block)
    return digest.hexdigest()


def _positive_budgets(rows):
    for name, value in rows:
        if type(value) is not int or value < 1:
            raise ValueError(name + " must be a positive integer")


def _reduce_terms(rows, radices, native_levels, max_subset_checks):
    """Substitute singletons, canonicalize DNF terms, and remove absorption."""
    reduced = set()
    for row in rows:
        condition = {}
        possible = True
        for domain, alternative in row:
            if (type(domain) is not int or type(alternative) is not int or
                    domain < 0 or domain >= len(radices) or alternative < 0 or
                    alternative >= radices[domain]):
                raise ValueError("atom condition contains an invalid native choice")
            if radices[domain] == 1:
                if alternative != 0:
                    possible = False
                    break
            else:
                level = native_levels[domain]
                previous = condition.setdefault(level, alternative)
                if previous != alternative:
                    possible = False
                    break
        if possible:
            reduced.add(tuple(sorted(condition.items())))
    if () in reduced:
        return ((),), 0

    # Process short terms first.  A retained term absorbs the current term iff
    # it is one of the current term's proper subsets.  Length-indexed hash sets
    # avoid a scan over every retained term, while the explicit lookup budget
    # bounds the remaining combinatorial preparation work.
    retained = []
    retained_by_length = {}
    subset_checks = 0
    for term in sorted(reduced, key=lambda value: (len(value), value)):
        term_set = frozenset(term)
        absorbed = False
        for length, prior_terms in sorted(retained_by_length.items()):
            if length >= len(term):
                break
            for candidate in combinations(term, length):
                subset_checks += 1
                if subset_checks > max_subset_checks:
                    raise MDDResourceLimitError(
                        "atom equivalence check budget exhausted")
                if frozenset(candidate) in prior_terms:
                    absorbed = True
                    break
            if absorbed:
                break
        if absorbed:
            continue
        retained.append(term)
        retained_by_length.setdefault(len(term), set()).add(term_set)
    return tuple(sorted(retained)), subset_checks


def _equivalence_factor(token, terms, atom_level, variables):
    dependencies = sorted({level for row in terms for level, _ in row})
    scope = tuple(dependencies + [atom_level])
    truth = []
    for selected in product(*(range(len(variables[level].values))
                              for level in scope)):
        assignment = dict(zip(scope, selected))
        active = any(all(assignment[level] == alternative
                         for level, alternative in row) for row in terms)
        truth.append(bool(assignment[atom_level]) is active)
    return {"name": "atom-equivalence:" + token,
            "scope": scope, "truth": tuple(truth)}


def translate_physical_forest(
        model_path, *, max_factor_cells=1_000_000,
        max_total_factor_cells=10_000_000, max_atoms=10_000,
        max_terms=100_000, max_equivalence_checks=10_000_000,
        max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
        max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    """Translate one frozen E model into hard and atom-equivalence factors."""
    _positive_budgets((
        ("max_factor_cells", max_factor_cells),
        ("max_total_factor_cells", max_total_factor_cells),
        ("max_atoms", max_atoms), ("max_terms", max_terms),
        ("max_equivalence_checks", max_equivalence_checks),
        ("max_model_decoded_bytes", max_model_decoded_bytes),
        ("max_model_compressed_bytes", max_model_compressed_bytes),
    ))
    translated = translate_model(
        model_path, max_factor_cells=max_factor_cells,
        max_total_factor_cells=max_total_factor_cells,
        max_model_decoded_bytes=max_model_decoded_bytes,
        max_model_compressed_bytes=max_model_compressed_bytes)
    translated["physicalTranslationStatus"] = translated["translationStatus"]
    translated["physicalBlockers"] = list(translated["blockers"])
    translated["atomDictionary"] = None
    translated["atomConditions"] = None
    translated["allVariables"] = None
    translated["allFactors"] = None
    translated["atomFactorCells"] = 0
    translated["atomEquivalenceChecks"] = 0
    if translated["translationStatus"] != "COMPLETE":
        return translated

    model = translated["model"]
    identity, occurrences = validate_identity(model)
    del identity
    occurrence_to_domain = {
        domain["occurrence"]: index
        for index, domain in enumerate(model["domains"])
    }
    contract = projection_contract(
        model, occurrence_to_domain, occurrences, translated["radices"])
    translated["projectionContract"] = contract
    if contract["status"] != "DECODER_STRUCTURAL_ONLY":
        translated["physicalTranslationStatus"] = "BLOCKED_NONCOMPOSITIONAL"
        translated["physicalBlockers"] = list(contract["blockers"])
        return translated

    try:
        atoms, source_terms = compile_atoms(
            model["physicalProjection"], max_atoms, max_terms)
    except MDDResourceLimitError as error:
        translated["physicalTranslationStatus"] = "BLOCKED_RESOURCE_LIMIT"
        translated["physicalBlockers"] = [
            str(error).upper().replace(" ", "_")]
        return translated
    if not atoms:
        translated["physicalTranslationStatus"] = "BLOCKED_EMPTY_ATOM_UNIVERSE"
        translated["physicalBlockers"] = ["EMPTY_PHYSICAL_ATOM_UNIVERSE"]
        return translated

    native_levels = {domain: level
                     for level, domain in enumerate(translated["order"])}
    atom_tokens = tuple(sorted(atoms))
    variables = list(translated["variables"])
    atom_levels = {}
    for token in atom_tokens:
        atom_levels[token] = len(variables)
        variables.append(Variable("atom:" + token, ("0", "1")))

    factors = list(translated["factors"])
    reduced_terms = {}
    total_cells = translated["translatedFactorCells"]
    atom_cells = 0
    equivalence_checks = 0
    reduction_checks = 0
    for token in atom_tokens:
        try:
            terms, checks_used = _reduce_terms(
                source_terms[token], translated["radices"], native_levels,
                max_equivalence_checks - reduction_checks -
                equivalence_checks)
        except MDDResourceLimitError:
            translated["physicalTranslationStatus"] = \
                "BLOCKED_RESOURCE_LIMIT"
            translated["physicalBlockers"] = [
                "ATOM_EQUIVALENCE_CHECK_BUDGET_EXHAUSTED"]
            translated["atomEquivalenceChecks"] = \
                max_equivalence_checks + 1
            return translated
        reduction_checks += checks_used
        if not terms:
            raise ValueError("typed physical atom has no possible native condition")
        reduced_terms[token] = terms
        dependencies = {level for row in terms for level, _ in row}
        cells = 2 * prod(len(variables[level].values)
                         for level in dependencies)
        checks = cells * sum(max(1, len(row)) for row in terms)
        if cells > max_factor_cells or total_cells + cells > \
                max_total_factor_cells:
            translated["physicalTranslationStatus"] = "BLOCKED_RESOURCE_LIMIT"
            translated["physicalBlockers"] = [
                "BOOLEAN_FACTOR_CELL_BUDGET_EXHAUSTED"]
            translated["atomFactorCells"] = atom_cells + cells
            translated["atomEquivalenceChecks"] = \
                reduction_checks + equivalence_checks
            translated["translatedPhysicalFactorCells"] = total_cells + cells
            return translated
        if reduction_checks + equivalence_checks + checks > \
                max_equivalence_checks:
            translated["physicalTranslationStatus"] = "BLOCKED_RESOURCE_LIMIT"
            translated["physicalBlockers"] = [
                "ATOM_EQUIVALENCE_CHECK_BUDGET_EXHAUSTED"]
            translated["atomFactorCells"] = atom_cells
            translated["translatedPhysicalFactorCells"] = total_cells
            translated["atomEquivalenceChecks"] = (
                reduction_checks + equivalence_checks + checks)
            return translated
        factor = _equivalence_factor(
            token, terms, atom_levels[token], variables)
        if len(factor["truth"]) != cells:
            raise AssertionError("atom equivalence factor cell count drift")
        atom_cells += cells
        equivalence_checks += checks
        total_cells += cells
        factors.append(factor)

    translated["atomDictionary"] = atoms
    translated["atomConditions"] = reduced_terms
    translated["atomLevels"] = atom_levels
    translated["allVariables"] = tuple(variables)
    translated["allFactors"] = tuple(factors)
    translated["atomFactorCells"] = atom_cells
    translated["atomEquivalenceChecks"] = (
        reduction_checks + equivalence_checks)
    translated["translatedPhysicalFactorCells"] = total_cells
    translated["physicalTranslationStatus"] = "COMPLETE"
    translated["physicalBlockers"] = []
    return translated


def _translation_wire(translated):
    tokens = tuple(sorted(translated["atomDictionary"] or ()))
    return {
        "status": translated["physicalTranslationStatus"],
        "blockers": translated["physicalBlockers"],
        "nativeDomainOrder": list(translated["order"]),
        "varyingNativeVariableCount": len(translated["order"]),
        "singletonNativeDomainCount": (len(translated["radices"]) -
                                       len(translated["order"])),
        "hardFactorCount": len(translated["tables"]),
        "atomVariableCount": len(tokens),
        "atomFactorCount": len(tokens),
        "hardFactorCells": str(translated["translatedFactorCells"]),
        "atomFactorCells": str(translated["atomFactorCells"]),
        "atomEquivalenceChecks": str(translated["atomEquivalenceChecks"]),
        "translatedPhysicalFactorCells": str(
            translated.get("translatedPhysicalFactorCells",
                           translated["translatedFactorCells"])),
        "singletonSubstitution": "ONLY_ALTERNATIVE_INDEX_ZERO",
        "hardTruthPolicy": "TRUE_IFF_SOURCE_STATUS_ALLOW",
        "sourceStatusCounts": translated.get("sourceStatusCounts"),
        "atomConstraint": "ATOM_IFF_OR_OF_NATIVE_CHOICE_CONJUNCTIONS",
        "eliminationPolicy": "ELIMINATE_VARYING_NATIVE_VARIABLES_ONLY",
    }


def _differential(translated, forest, max_replay_cells):
    if forest is None or forest.get("diagnosticStatus") != "DIAGNOSTIC_COMPLETE":
        return {"status": "NOT_APPLICABLE_BLOCKED_CONSTRUCTION"}
    radices = translated["radices"]
    tokens = tuple(sorted(translated["atomDictionary"]))
    native_cells = prod(radices)
    atom_cells = 1 << len(tokens)
    if max(native_cells, atom_cells) > max_replay_cells:
        return {"status": "SKIPPED_REPLAY_CELL_BUDGET", "limit": max_replay_cells,
                "nativeCells": str(native_cells), "atomCells": str(atom_cells)}

    model = translated["model"]
    wrapper = {
        "atomDictionary": translated["atomDictionary"],
        "alwaysPresentAtoms": [],
        "staticCoordinates": {
            "logicalProgram": model["physicalProjection"]["logicalProgram"],
            "logicalInputs": model["physicalProjection"]["logicalInputs"],
        },
    }
    expected = set()
    accepted_native = 0
    for native in product(*(range(radix) for radix in radices)):
        if all(factor_status(scope, truth, native, radices) == "ALLOW"
               for scope, truth in translated["tables"]):
            accepted_native += 1
            active = frozenset(token for token in tokens if any(
                all(native[translated["order"][level]] == alternative
                    for level, alternative in reduced_term)
                for reduced_term in translated["atomConditions"][token]))
            if materialize_atoms(wrapper, active) != \
                    compose_physical_projection(model, native):
                raise ValueError(
                    "atom materialization differs from physical projection")
            expected.add(tuple(int(token in active) for token in tokens))

    artifact = validate_artifact(
        forest["relation"], translated["allVariables"])
    native_count = len(translated["order"])
    actual = set()
    for bits in product((0, 1), repeat=len(tokens)):
        assignment = (0,) * native_count + bits
        if all(artifact.evaluate(root, assignment) for root in artifact.roots):
            actual.add(bits)
    if actual != expected:
        raise ValueError("physical atom residual forest differential failed")
    return {"status": "PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY",
            "nativeCells": str(native_cells), "atomCells": str(atom_cells),
            "acceptedNativeAssignments": str(accepted_native),
            "acceptedAtomAssignments": str(len(actual))}


def build(model_path, *, max_nodes=250_000, max_apply_pairs=1_000_000,
          max_factor_cells=1_000_000,
          max_total_factor_cells=10_000_000, max_atoms=10_000,
          max_terms=100_000, max_equivalence_checks=10_000_000,
          max_replay_cells=250_000,
          max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
          max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    _positive_budgets((
        ("max_nodes", max_nodes), ("max_apply_pairs", max_apply_pairs),
        ("max_factor_cells", max_factor_cells),
        ("max_total_factor_cells", max_total_factor_cells),
        ("max_atoms", max_atoms), ("max_terms", max_terms),
        ("max_equivalence_checks", max_equivalence_checks),
        ("max_replay_cells", max_replay_cells),
        ("max_model_decoded_bytes", max_model_decoded_bytes),
        ("max_model_compressed_bytes", max_model_compressed_bytes),
    ))
    translated = translate_physical_forest(
        model_path, max_factor_cells=max_factor_cells,
        max_total_factor_cells=max_total_factor_cells,
        max_atoms=max_atoms, max_terms=max_terms,
        max_equivalence_checks=max_equivalence_checks,
        max_model_decoded_bytes=max_model_decoded_bytes,
        max_model_compressed_bytes=max_model_compressed_bytes)
    budgets = {
        "maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
        "maxFactorCells": max_factor_cells,
        "maxTotalFactorCells": max_total_factor_cells,
        "maxAtoms": max_atoms, "maxTerms": max_terms,
        "maxEquivalenceChecks": max_equivalence_checks,
        "maxReplayCells": max_replay_cells,
        "maxModelDecodedBytes": max_model_decoded_bytes,
        "maxModelCompressedBytes": max_model_compressed_bytes,
    }
    blockers = ["INDEPENDENT_JAVA_SEMANTIC_BINDING_UNAVAILABLE",
                "NOT_A_CANONICAL_DISTINCT_PLAN_COUNT",
                "NOT_A_P_E_EQUALITY_CERTIFICATE"]
    forest = None
    if translated["physicalTranslationStatus"] == "COMPLETE":
        native_count = len(translated["order"])
        forest = eliminate_factor_forest(
            translated["allVariables"], translated["allFactors"],
            tuple(range(native_count)), max_nodes=max_nodes,
            max_apply_pairs=max_apply_pairs)
        diagnostic_status = forest["diagnosticStatus"]
        if diagnostic_status != "DIAGNOSTIC_COMPLETE":
            blockers.extend(forest["blockers"])
    else:
        diagnostic_status = (
            "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT"
            if translated["physicalTranslationStatus"] == "BLOCKED_RESOURCE_LIMIT"
            else "DIAGNOSTIC_BLOCKED_INPUT")
        blockers.extend(translated["physicalBlockers"])
    if (translated.get("sourceStatusCounts") or {}).get("UNKNOWN", 0):
        blockers.append("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED")

    differential = _differential(translated, forest, max_replay_cells)
    payload = {
        "schema": SCHEMA, "status": "BLOCKED",
        "diagnosticStatus": diagnostic_status, "claimScope": CLAIM_SCOPE,
        "blockers": sorted(set(blockers)),
        "semanticBindingStatus": "BLOCKED_INDEPENDENT_JAVA_BINDING",
        "cell": translated["model"].get("cell"),
        "modelSha256": translated["modelSha256"],
        "modelFileSha256": translated["modelFileSha256"],
        "modelSchema": translated["model"].get("schema"),
        "programSha256": translated["model"].get("programSha256"),
        "conditionSha256": translated["model"].get("conditionSha256"),
        "projectionContract": translated.get("projectionContract"),
        "translation": _translation_wire(translated),
        "atomDictionary": translated["atomDictionary"],
        "atomConditions": ({token: [[list(pair) for pair in term]
                                     for term in rows]
                            for token, rows in sorted(
                                (translated["atomConditions"] or {}).items())}),
        "atomDictionarySha256": (_digest(translated["atomDictionary"])
                                 if translated["atomDictionary"] is not None
                                 else None),
        "factorForest": forest, "exhaustiveDifferential": differential,
        "budgets": budgets,
    }
    payload["artifactSha256"] = _digest(payload)
    return payload


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as raw:
        temporary = Path(raw.name)
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as stream:
            stream.write(_canonical(value) + b"\n")
    temporary.replace(path)


def read_saved(path, *, max_decoded_bytes=DEFAULT_MAX_ARTIFACT_DECODED_BYTES,
               max_compressed_bytes=DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES):
    _positive_budgets((("max_decoded_bytes", max_decoded_bytes),
                       ("max_compressed_bytes", max_compressed_bytes)))
    path = Path(path)
    if path.stat().st_size > max_compressed_bytes:
        raise ValueError("E physical-forest compressed-byte budget exhausted")
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
                        "E physical-forest decoded-byte budget exhausted")
                target.write(block)
        target.seek(0)
        return json.load(target)


def verify_saved(model_path, artifact_path, expected_file_sha256=None, *,
                 max_artifact_decoded_bytes=DEFAULT_MAX_ARTIFACT_DECODED_BYTES,
                 max_artifact_compressed_bytes=
                 DEFAULT_MAX_ARTIFACT_COMPRESSED_BYTES,
                 max_nodes=250_000, max_apply_pairs=1_000_000,
                 max_factor_cells=1_000_000,
                 max_total_factor_cells=10_000_000, max_atoms=10_000,
                 max_terms=100_000, max_equivalence_checks=10_000_000,
                 max_replay_cells=250_000,
                 max_model_decoded_bytes=DEFAULT_MAX_MODEL_DECODED_BYTES,
                 max_model_compressed_bytes=DEFAULT_MAX_MODEL_COMPRESSED_BYTES):
    caps = {
        "maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
        "maxFactorCells": max_factor_cells,
        "maxTotalFactorCells": max_total_factor_cells,
        "maxAtoms": max_atoms, "maxTerms": max_terms,
        "maxEquivalenceChecks": max_equivalence_checks,
        "maxReplayCells": max_replay_cells,
        "maxModelDecodedBytes": max_model_decoded_bytes,
        "maxModelCompressedBytes": max_model_compressed_bytes,
    }
    _positive_budgets(tuple((key, value) for key, value in caps.items()) + (
        ("max_artifact_decoded_bytes", max_artifact_decoded_bytes),
        ("max_artifact_compressed_bytes", max_artifact_compressed_bytes),
    ))
    saved = read_saved(
        artifact_path, max_decoded_bytes=max_artifact_decoded_bytes,
        max_compressed_bytes=max_artifact_compressed_bytes)
    file_sha = _file_sha(artifact_path)
    if expected_file_sha256 is not None and file_sha != expected_file_sha256:
        raise ValueError("E physical-forest file SHA-256 mismatch")
    if not isinstance(saved, dict) or saved.get("schema") != SCHEMA:
        raise ValueError("saved E physical-forest diagnostic has invalid schema")
    payload = {key: value for key, value in saved.items()
               if key != "artifactSha256"}
    if saved.get("artifactSha256") != _digest(payload):
        raise ValueError("E physical-forest artifact commitment mismatch")
    budgets = saved.get("budgets")
    expected_keys = {
        "maxNodes", "maxApplyPairs", "maxFactorCells",
        "maxTotalFactorCells", "maxAtoms", "maxTerms",
        "maxEquivalenceChecks", "maxReplayCells",
        "maxModelDecodedBytes", "maxModelCompressedBytes",
    }
    if (not isinstance(budgets, dict) or set(budgets) != expected_keys or
            any(type(value) is not int or value < 1
                for value in budgets.values())):
        raise ValueError("E physical-forest budget contract is invalid")
    exceeded = sorted(key for key, cap in caps.items()
                      if budgets[key] > cap)
    if exceeded:
        raise ValueError("saved construction budget exceeds verifier cap: " +
                         ",".join(exceeded))
    rebuilt = build(
        model_path, max_nodes=budgets["maxNodes"],
        max_apply_pairs=budgets["maxApplyPairs"],
        max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_atoms=budgets["maxAtoms"], max_terms=budgets["maxTerms"],
        max_equivalence_checks=budgets["maxEquivalenceChecks"],
        max_replay_cells=budgets["maxReplayCells"],
        max_model_decoded_bytes=budgets["maxModelDecodedBytes"],
        max_model_compressed_bytes=budgets["maxModelCompressedBytes"])
    if rebuilt != saved:
        raise ValueError(
            "saved E physical-forest diagnostic differs from source replay")

    translated = translate_physical_forest(
        model_path, max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_atoms=budgets["maxAtoms"], max_terms=budgets["maxTerms"],
        max_equivalence_checks=budgets["maxEquivalenceChecks"],
        max_model_decoded_bytes=budgets["maxModelDecodedBytes"],
        max_model_compressed_bytes=budgets["maxModelCompressedBytes"])
    forest = saved["factorForest"]
    if forest is None:
        forest_status = "NOT_PRODUCED_TRANSLATION_BLOCKED"
    elif forest["diagnosticStatus"] == "DIAGNOSTIC_COMPLETE":
        verify_factor_forest_artifact(forest, forest["resultSha256"])
        check = verify_factor_forest_result(
            forest, translated["allVariables"], translated["allFactors"],
            tuple(range(len(translated["order"]))),
            max_replay_cells=budgets["maxReplayCells"])
        forest_status = check["semanticReplayStatus"]
    else:
        verify_factor_forest_blocked(forest, forest["diagnosticSha256"])
        forest_status = "VERIFIED_BLOCKED"
    return {"status": "PASS", "cell": saved["cell"],
            "artifactSha256": saved["artifactSha256"],
            "fileSha256": file_sha,
            "sourceReplayStatus": "EXACT_DETERMINISTIC_RECOMPUTATION",
            "forestSemanticReplayStatus": forest_status,
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_JAVA_BINDING"}


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
    parser.add_argument("--max-atoms", type=int, default=10_000)
    parser.add_argument("--max-terms", type=int, default=100_000)
    parser.add_argument("--max-equivalence-checks", type=int,
                        default=10_000_000)
    parser.add_argument("--max-replay-cells", type=int, default=250_000)
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
            max_atoms=args.max_atoms, max_terms=args.max_terms,
            max_equivalence_checks=args.max_equivalence_checks,
            max_replay_cells=args.max_replay_cells,
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
            max_atoms=args.max_atoms, max_terms=args.max_terms,
            max_equivalence_checks=args.max_equivalence_checks,
            max_replay_cells=args.max_replay_cells,
            max_model_decoded_bytes=args.max_model_decoded_bytes,
            max_model_compressed_bytes=args.max_model_compressed_bytes)
    print(json.dumps(output, sort_keys=True))


if __name__ == "__main__":
    main()
