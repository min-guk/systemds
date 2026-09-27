#!/usr/bin/env python3
"""Bounded SAT membership and conditional negative diagnostic for one E model.

The producer MDD is checked by a separate dense bucket-elimination replay.
SAT additionally requires a recovered native assignment that passes the source
E factors and whose typed projection equals the canonical target under the
pinned physical-coordinate codec.  The result is model-local: it does not bind
the captured decoder to Java semantics and is not a P/E equality result.
"""

import argparse
import hashlib
from itertools import product
import json
from math import prod
from pathlib import Path

from boolean_mdd_relation import validate_artifact
from diagnose_e_physical_forest import translate_physical_forest
from e_target_preimage import compile_target_preimage
from e_factorized_membership_checker import (check_dense_certificate,
                                               recover_dense_witness,
                                               verify_residual_roots)
from e_atom_semantics_checker import (certify_atom_semantics,
                                      verify_atom_semantics_certificate)
from exact_e_physical_relation import compose_physical_projection, factor_status
from factor_forest_image import (eliminate_factor_forest,
                                 verify_factor_forest_artifact)
from physical_coordinate_contract import PhysicalCoordinateCodec


SCHEMA = "e-factorized-canonical-membership-query-v2"
VERIFICATION_SCHEMA = "e-factorized-canonical-membership-verification-v2"
CLAIM_SCOPE = "CAPTURED_E_DEFINITE_ALLOW_TYPED_DECODER_MEMBERSHIP_ONLY"
DEFAULT_MAX_TARGET_PLAN_BYTES = 64 * 1024 ** 2
_COORDINATES = ("nodes", "authority", "actions", "bindings", "geometry")
_EXACTLY_ONE = frozenset(("nodes", "authority", "bindings"))


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _positive_budgets(rows):
    for label, value in rows:
        if type(value) is not int or value < 1:
            raise ValueError(label + " must be a positive integer")


def _factor_value(row, assignment, radices):
    offset = 0
    for level in row["scope"]:
        offset = offset * radices[level] + assignment[level]
    return row["truth"][offset]


def _normalize_factors(rows, variables):
    radices = tuple(len(variable.values) for variable in variables)
    result = []
    names = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"name", "scope", "truth"}:
            raise ValueError("factor must contain exactly name, scope, and truth")
        name = row["name"]
        scope = tuple(row["scope"])
        truth = tuple(row["truth"])
        if (not isinstance(name, str) or not name or name in names or
                name.startswith("elim:")):
            raise ValueError("factor names must be unique and non-reserved")
        if (scope != tuple(sorted(set(scope))) or
                any(type(level) is not int or level < 0 or
                    level >= len(variables) for level in scope)):
            raise ValueError("factor scope is invalid")
        cells = prod(radices[level] for level in scope)
        if len(truth) != cells or any(type(value) is not bool for value in truth):
            raise ValueError("factor truth table is invalid")
        names.add(name)
        result.append({"name": name, "scope": scope, "truth": truth})
    if not result:
        result.append({"name": "constant:true", "scope": (),
                       "truth": (True,)})
    return sorted(result, key=lambda row: row["name"])


def _synthetic_name(stage, level, incident_names):
    return "elim:%04d:%04d:%s" % (
        stage, level, _digest(incident_names)[:16])


def _dense_replay(variables, factors, eliminate_levels, *,
                  max_bucket_cells, max_total_cells, max_factor_lookups):
    """Independently replay every bucket and retain reverse arg witnesses."""
    radices = tuple(len(variable.values) for variable in variables)
    forest = _normalize_factors(factors, variables)
    total_cells = 0
    total_lookups = 0
    trace = []
    reverse = []
    for stage, level in enumerate(eliminate_levels):
        incident = sorted((row for row in forest if level in row["scope"]),
                          key=lambda row: row["name"])
        preserved = [row for row in forest if level not in row["scope"]]
        input_scope = tuple(sorted({item for row in incident
                                    for item in row["scope"]}))
        output_scope = tuple(item for item in input_scope if item != level)
        bucket_cells = radices[level] * prod(
            radices[item] for item in output_scope)
        if bucket_cells > max_bucket_cells:
            return None, None, trace, total_cells, \
                "DENSE_BUCKET_CELL_BUDGET_EXHAUSTED"
        if total_cells + bucket_cells > max_total_cells:
            return None, None, trace, total_cells, \
                "DENSE_TOTAL_CELL_BUDGET_EXHAUSTED"
        bucket_lookups = bucket_cells * len(incident)
        if total_lookups + bucket_lookups > max_factor_lookups:
            return None, None, trace, total_cells, \
                "DENSE_FACTOR_LOOKUP_BUDGET_EXHAUSTED"
        total_cells += bucket_cells
        total_lookups += bucket_lookups
        truth = []
        arg_witness = []
        output_values = product(*(range(radices[item])
                                  for item in output_scope))
        for values in output_values:
            assignment = {item: value
                          for item, value in zip(output_scope, values)}
            selected = -1
            for hidden in range(radices[level]):
                assignment[level] = hidden
                if all(_factor_value(row, assignment, radices)
                       for row in incident):
                    selected = hidden
                    break
            truth.append(selected >= 0)
            arg_witness.append(selected)
        names = [row["name"] for row in incident]
        synthetic = _synthetic_name(stage, level, names)
        projected = {"name": synthetic, "scope": output_scope,
                     "truth": tuple(truth)}
        forest = sorted(preserved + [projected], key=lambda row: row["name"])
        witness_digest = _digest({"scope": list(output_scope),
                                  "args": arg_witness})
        trace.append({
            "stage": stage, "eliminatedLevel": level,
            "eliminatedVariable": variables[level].name,
            "incidentFactors": names, "preservedFactors": len(preserved),
            "inputScope": list(input_scope),
            "outputScope": list(output_scope), "resultFactor": synthetic,
            "bucketCells": str(bucket_cells),
            "factorLookupBound": str(bucket_lookups),
            "outputTruth": truth,
            "argWitness": arg_witness,
            "argWitnessSha256": witness_digest,
        })
        reverse.append({"level": level, "scope": output_scope,
                        "args": tuple(arg_witness)})
    return forest, reverse, trace, total_cells, None


def _scope_offset(scope, assignment, radices):
    offset = 0
    for level in scope:
        offset = offset * radices[level] + assignment[level]
    return offset


def _recover_witness(reverse, radices):
    assignment = {}
    for row in reversed(reverse):
        if any(level not in assignment for level in row["scope"]):
            raise ValueError("dense witness dependency is unavailable")
        offset = _scope_offset(row["scope"], assignment, radices)
        selected = row["args"][offset]
        if selected < 0:
            raise ValueError("dense witness table contradicts SAT result")
        assignment[row["level"]] = selected
    if len(assignment) != len(radices):
        raise ValueError("dense witness does not cover every variable")
    return tuple(assignment[level] for level in range(len(radices)))


def _compare_mdd_to_dense(forest_result, dense_forest, variables,
                          eliminate_levels, dense_trace):
    if forest_result.get("diagnosticStatus") != "DIAGNOSTIC_COMPLETE":
        return forest_result.get("blockers") or ["MDD_ELIMINATION_BLOCKED"]
    expected_trace = [{key: row[key] for key in (
        "stage", "eliminatedLevel", "eliminatedVariable", "incidentFactors",
        "preservedFactors", "inputScope", "outputScope", "resultFactor")}
        for row in dense_trace]
    actual_trace = [{key: row[key] for key in expected_trace[0]}
                    for row in forest_result["trace"]] if expected_trace else []
    if actual_trace != expected_trace:
        raise ValueError("producer MDD elimination trace differs from dense replay")
    artifact = validate_artifact(forest_result["relation"], variables)
    residual = {row["name"]: row for row in dense_forest}
    if set(artifact.roots) != set(residual):
        raise ValueError("producer MDD residual roots differ from dense replay")
    radices = tuple(len(variable.values) for variable in variables)
    eliminated = set(eliminate_levels)
    for name, row in residual.items():
        if eliminated.intersection(row["scope"]):
            raise ValueError("dense replay retained an eliminated variable")
        for values in product(*(range(radices[level])
                                for level in row["scope"])):
            assignment = [0] * len(variables)
            for level, value in zip(row["scope"], values):
                assignment[level] = value
            expected = _factor_value(row, assignment, radices)
            if artifact.evaluate(name, assignment) is not expected:
                raise ValueError("producer MDD root differs from dense truth table")
    return []


def _translation_bindings(translated):
    return {
        "modelSha256": translated.get("modelSha256"),
        "modelFileSha256": translated.get("modelFileSha256"),
        "projectionSha256": translated["projectionContract"]["projectionSha256"],
        "atomDictionarySha256": _digest(translated["atomDictionary"]),
        "atomConditionsSha256": _digest({
            token: [[list(pair) for pair in term] for term in rows]
            for token, rows in sorted(translated["atomConditions"].items())}),
        "allVariablesSha256": _digest([
            {"name": variable.name, "values": list(variable.values)}
            for variable in translated["allVariables"]]),
        "hardAndAtomFactorsSha256": _digest([
            {"name": factor["name"], "scope": list(factor["scope"]),
             "truth": list(factor["truth"])}
            for factor in translated["allFactors"]]),
    }


def _independent_target_factors(translated, target_plan, verification_root, *,
                                max_target_rows, max_target_factor_cells,
                                max_group_atoms):
    """Rebuild target factors from typed atoms without the preimage compiler."""
    codec = PhysicalCoordinateCodec(verification_root)
    try:
        canonical = codec.decode(codec.encode(target_plan))
    except (TypeError, ValueError, OverflowError):
        return None, "TARGET_UNSUPPORTED_OR_INVALID"
    if _canonical(canonical) != _canonical(target_plan):
        return None, "TARGET_NOT_CANONICAL"
    projection = translated["model"]["physicalProjection"]
    expected_inputs = [json.loads(row) for row in sorted(
        _canonical(row) for row in projection["logicalInputs"])]
    target_rows = sum(len(canonical[name]) for name in _COORDINATES)
    if target_rows > max_target_rows:
        return None, "TARGET_ROW_BUDGET_EXHAUSTED"
    if (canonical["context"] != {"logical": projection["logicalProgram"]} or
            canonical["logicalInputs"] != expected_inputs):
        return [{"name": "empty-preimage:STATIC_COORDINATES_DIFFER",
                 "scope": [], "truth": [False]}], None
    groups = {}
    for token, atom in sorted(translated["atomDictionary"].items()):
        if (not isinstance(atom, dict) or atom.get("coordinate") not in
                _COORDINATES or not isinstance(atom.get("value"), dict)):
            return None, "UNSUPPORTED_ATOM_SCHEMA"
        if atom["coordinate"] == "bindings" and atom.get("part") != "ROW":
            return None, "PHI_ATOM_UNSUPPORTED"
        key = (atom["coordinate"], _canonical(atom["value"]))
        groups.setdefault(key, []).append(translated["atomLevels"][token])
    required_rows = [(name, _canonical(row)) for name in _COORDINATES
                     for row in canonical[name]]
    if len(required_rows) != len(set(required_rows)):
        return None, "AMBIGUOUS_CANONICAL_TARGET_ROWS"
    required = set(required_rows)
    if any(key not in groups for key in required):
        return [{"name":
                 "empty-preimage:TARGET_ROW_MISSING_FROM_ATOM_DICTIONARY",
                 "scope": [], "truth": [False]}], None
    result = []
    total_cells = 0
    for (coordinate, row), levels in sorted(groups.items()):
        levels = tuple(sorted(levels))
        if len(levels) > max_group_atoms:
            return None, "TARGET_ATOM_GROUP_BUDGET_EXHAUSTED"
        cells = 1 << len(levels)
        if total_cells + cells > max_target_factor_cells:
            return None, "TARGET_FACTOR_CELL_BUDGET_EXHAUSTED"
        total_cells += cells
        present = (coordinate, row) in required
        truth = []
        for bits in product((0, 1), repeat=len(levels)):
            truth.append((sum(bits) == 1) if present and
                         coordinate in _EXACTLY_ONE else
                         (any(bits) if present else not any(bits)))
        row_sha = hashlib.sha256(row).hexdigest()
        prefix = ("require-exactly-one" if coordinate in _EXACTLY_ONE else
                  "require-deduplicated") if present else "forbid-extra"
        result.append({"name": "%s:%s:%s" % (prefix, coordinate, row_sha),
                       "scope": list(levels), "truth": truth})
    return result, None


def _incomplete(bindings, budgets, blockers, preimage=None):
    result = {
        "schema": SCHEMA, "claimScope": CLAIM_SCOPE, "status": "INCOMPLETE",
        "blockers": sorted(set(blockers)), "bindings": bindings,
        "budgets": budgets, "preimageArtifactSha256": (
            preimage.get("artifactSha256") if preimage else None),
        "membership": None, "witness": None,
        "proof": None,
        "diagnosticStatus": "MODEL_LOCAL_MEMBERSHIP_INCOMPLETE",
        "executionProvenance": "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
        "claims": {"executableAttestation": "NOT_ESTABLISHED",
                   "javaPlannerSemantics": "NOT_ESTABLISHED",
                   "pEqualsE": "NOT_ASSESSED"},
        "semanticBoundary": [
            "MODEL_LOCAL_UNATTESTED_EXECUTION_RESULT",
            "CAPTURED_TYPED_DECODER_NOT_INDEPENDENTLY_BOUND_TO_JAVA_SEMANTICS",
            "CAPTURED_HARD_AND_ATOM_FACTOR_TRANSLATION_IS_MODEL_INPUT",
            "NOT_A_P_E_EQUALITY_RESULT",
        ],
    }
    result["artifactSha256"] = _digest(result)
    return result


def query_factorized_membership(
        model_path, target_plan, verification_root, *, max_nodes=250_000,
        max_apply_pairs=1_000_000, max_factor_cells=1_000_000,
        max_total_factor_cells=10_000_000, max_atoms=10_000,
        max_terms=100_000, max_equivalence_checks=10_000_000,
        max_target_rows=10_000, max_target_factor_cells=1_000_000,
        max_group_atoms=20, max_dense_bucket_cells=1_000_000,
        max_dense_total_cells=10_000_000,
        max_dense_factor_lookups=50_000_000,
        max_target_plan_bytes=DEFAULT_MAX_TARGET_PLAN_BYTES):
    """Prove SAT or return an explicitly conditional INCOMPLETE result."""
    budgets = {
        "maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
        "maxFactorCells": max_factor_cells,
        "maxTotalFactorCells": max_total_factor_cells,
        "maxAtoms": max_atoms, "maxTerms": max_terms,
        "maxEquivalenceChecks": max_equivalence_checks,
        "maxTargetRows": max_target_rows,
        "maxTargetFactorCells": max_target_factor_cells,
        "maxGroupAtoms": max_group_atoms,
        "maxDenseBucketCells": max_dense_bucket_cells,
        "maxDenseTotalCells": max_dense_total_cells,
        "maxDenseFactorLookups": max_dense_factor_lookups,
        "maxTargetPlanBytes": max_target_plan_bytes,
    }
    _positive_budgets((key, value) for key, value in budgets.items())
    try:
        target_bytes = _canonical(target_plan)
    except (TypeError, ValueError) as error:
        raise ValueError("target plan is not finite JSON") from error
    if len(target_bytes) > max_target_plan_bytes:
        return _incomplete({}, budgets, ["TARGET_PLAN_BYTE_BUDGET_EXHAUSTED"])

    preimage = compile_target_preimage(
        model_path, target_plan, verification_root,
        max_target_rows=max_target_rows,
        max_target_factor_cells=max_target_factor_cells,
        max_group_atoms=max_group_atoms, max_factor_cells=max_factor_cells,
        max_total_factor_cells=max_total_factor_cells, max_atoms=max_atoms,
        max_terms=max_terms,
        max_equivalence_checks=max_equivalence_checks)
    if preimage.get("artifactSha256") != _digest({
            key: value for key, value in preimage.items()
            if key != "artifactSha256"}):
        raise ValueError("preimage artifact commitment mismatch")
    bindings = dict(preimage.get("bindings") or {})
    if preimage.get("status") != "COMPLETE":
        return _incomplete(bindings, budgets,
                           preimage.get("blockers") or ["PREIMAGE_INCOMPLETE"],
                           preimage)

    translated = translate_physical_forest(
        model_path, max_factor_cells=max_factor_cells,
        max_total_factor_cells=max_total_factor_cells, max_atoms=max_atoms,
        max_terms=max_terms,
        max_equivalence_checks=max_equivalence_checks)
    if translated.get("physicalTranslationStatus") != "COMPLETE":
        raise ValueError("model translation changed after preimage compilation")
    if (translated.get("sourceStatusCounts") or {}).get("UNKNOWN", 0):
        raise ValueError("source UNKNOWN changed after preimage compilation")
    current_bindings = _translation_bindings(translated)
    for key, value in current_bindings.items():
        if bindings.get(key) != value:
            raise ValueError("preimage/translation binding mismatch: " + key)

    atom_semantics = certify_atom_semantics(
        model_path, translated,
        max_semantic_cells=max_equivalence_checks)

    variables = translated["allVariables"]
    target_factors = preimage["factorPayload"]["targetFactors"]
    independent_target, target_blocker = _independent_target_factors(
        translated, target_plan, verification_root,
        max_target_rows=max_target_rows,
        max_target_factor_cells=max_target_factor_cells,
        max_group_atoms=max_group_atoms)
    if target_blocker:
        return _incomplete(bindings, budgets, [target_blocker], preimage)
    if independent_target != target_factors:
        raise ValueError("preimage target factors differ from independent rebuild")
    factors = list(translated["allFactors"]) + list(target_factors)
    native_levels = tuple(range(len(translated["order"])))
    atom_levels = tuple(sorted(translated["atomLevels"].values()))
    if set(native_levels).intersection(atom_levels) or \
            set(native_levels + atom_levels) != set(range(len(variables))):
        raise ValueError("translated variable partition is invalid")
    eliminate_levels = atom_levels + native_levels

    forest_result = eliminate_factor_forest(
        variables, factors, eliminate_levels, max_nodes=max_nodes,
        max_apply_pairs=max_apply_pairs)
    dense_forest, reverse, dense_trace, dense_cells, dense_blocker = \
        _dense_replay(
            variables, factors, eliminate_levels,
            max_bucket_cells=max_dense_bucket_cells,
            max_total_cells=max_dense_total_cells,
            max_factor_lookups=max_dense_factor_lookups)
    if dense_blocker:
        return _incomplete(bindings, budgets, [dense_blocker], preimage)
    if forest_result.get("diagnosticStatus") == "DIAGNOSTIC_COMPLETE":
        verify_factor_forest_artifact(
            forest_result, forest_result.get("resultSha256"))
    mdd_blockers = _compare_mdd_to_dense(
        forest_result, dense_forest, variables, eliminate_levels, dense_trace)
    if mdd_blockers:
        return _incomplete(bindings, budgets, mdd_blockers, preimage)

    if any(row["scope"] for row in dense_forest):
        raise ValueError("complete elimination retained a non-constant factor")
    satisfiable = all(row["truth"] == (True,) for row in dense_forest)
    witness_payload = None
    if satisfiable:
        radices = tuple(len(variable.values) for variable in variables)
        all_assignment = _recover_witness(reverse, radices)
        if not all(_factor_value(row, all_assignment, radices)
                   for row in _normalize_factors(factors, variables)):
            raise ValueError("recovered witness fails an input factor")
        native_assignment = [0] * len(translated["radices"])
        for level, domain in enumerate(translated["order"]):
            native_assignment[domain] = all_assignment[level]
        native_assignment = tuple(native_assignment)
        statuses = [factor_status(scope, truth, native_assignment,
                                  translated["radices"])
                    for scope, truth in translated["tables"]]
        if any(status != "ALLOW" for status in statuses):
            raise ValueError("recovered witness fails source E hard factors")
        projected = compose_physical_projection(
            translated["model"], native_assignment)
        codec = PhysicalCoordinateCodec(verification_root)
        if codec.encode(projected) != codec.encode(target_plan):
            raise ValueError("recovered witness projection differs from target")
        witness_payload = {
            "nativeAssignment": list(native_assignment),
            "varyingAndAtomAssignmentSha256": _digest(list(all_assignment)),
            "sourceAcceptance": "ALLOW",
            "projectionCoordinateSha256": hashlib.sha256(
                codec.encode(projected)).hexdigest(),
        }

    proof = {
        "eliminationOrder": list(eliminate_levels),
        "eliminationPolicy": "ALL_ATOM_LEVELS_THEN_ALL_VARYING_NATIVE_LEVELS",
        "denseReplayStatus": "PASS_COMPLETE",
        "denseReplayCells": str(dense_cells),
        "denseFactorLookupBound": str(sum(
            int(row["factorLookupBound"]) for row in dense_trace)),
        "denseTrace": dense_trace,
        "denseResidualSha256": _digest([
            {"name": row["name"], "scope": list(row["scope"]),
             "truth": list(row["truth"])} for row in dense_forest]),
        "inputFactorsSha256": _digest([
            {"name": row["name"], "scope": list(row["scope"]),
             "truth": list(row["truth"])}
            for row in _normalize_factors(factors, variables)]),
        "targetFactorsSha256": _digest(target_factors),
        "producerMddResultSha256": forest_result["resultSha256"],
        "producerMdd": forest_result,
        "producerMddResidualReplay": "PASS_EVERY_ROOT",
        "finalConjunction": satisfiable,
        "compiledFactorsUnsat": not satisfiable,
        "atomSemantics": atom_semantics,
    }
    negative_decided = (not satisfiable and
                        atom_semantics.get("status") == "COMPLETE")
    semantics_replayed = atom_semantics.get("status") == "COMPLETE"
    result = {
        "schema": SCHEMA, "claimScope": CLAIM_SCOPE,
        "status": "SAT" if satisfiable else
        "UNSAT" if negative_decided else "INCOMPLETE",
        "blockers": [] if satisfiable or negative_decided else
        (atom_semantics.get("blockers") or
         ["ATOM_DECODER_SEMANTICS_NOT_CERTIFIED"]),
        "bindings": bindings, "budgets": budgets,
        "preimageArtifactSha256": preimage["artifactSha256"],
        "membership": True if satisfiable else False if negative_decided else None,
        "witness": witness_payload,
        "proof": proof,
        "diagnosticStatus": ("MODEL_LOCAL_MEMBERSHIP_DECIDED" if satisfiable
                             else "MODEL_LOCAL_NON_PHI_MEMBERSHIP_DECIDED"
                             if negative_decided else
                             "COMPILED_FACTORS_UNSAT_CONDITIONAL"),
        "executionProvenance": preimage["executionProvenance"],
        "claims": {"executableAttestation": "NOT_ESTABLISHED",
                   "javaPlannerSemantics": "NOT_ESTABLISHED",
                   "pEqualsE": "NOT_ASSESSED"},
        "semanticBoundary": [
            "MODEL_LOCAL_UNATTESTED_EXECUTION_RESULT",
            "CAPTURED_TYPED_DECODER_NOT_INDEPENDENTLY_BOUND_TO_JAVA_SEMANTICS",
            ("CAPTURED_NON_PHI_ATOM_AND_HARD_FACTOR_TRANSLATION_REPLAYED"
             if semantics_replayed else
             "CAPTURED_HARD_AND_ATOM_FACTOR_TRANSLATION_IS_MODEL_INPUT"),
            "NOT_A_P_E_EQUALITY_RESULT",
        ],
    }
    result["artifactSha256"] = _digest(result)
    return result


def _verification_incomplete(artifact, blocker):
    result = {
        "schema": VERIFICATION_SCHEMA,
        "status": "INCOMPLETE", "claimScope": CLAIM_SCOPE,
        "blockers": [blocker],
        "verifiedArtifactSha256": artifact.get("artifactSha256"),
        "regeneration": "BLOCKED_BY_CALLER_VERIFICATION_BUDGET",
        "diagnosticStatus": "MODEL_LOCAL_PROOF_REPLAY_INCOMPLETE",
        "executionProvenance": "UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
        "claims": {"executableAttestation": "NOT_ESTABLISHED",
                   "javaPlannerSemantics": "NOT_ESTABLISHED",
                   "pEqualsE": "NOT_ASSESSED"},
    }
    result["verificationSha256"] = _digest(result)
    return result


def verify_factorized_membership(
        artifact, model_path, target_plan, verification_root, *,
        max_proof_bytes=64 * 1024 ** 2, max_bucket_cells=1_000_000,
        max_total_cells=10_000_000, max_factor_lookups=50_000_000,
        max_factor_cells=1_000_000, max_total_factor_cells=10_000_000,
        max_atoms=10_000, max_terms=100_000,
        max_equivalence_checks=10_000_000, max_target_rows=10_000,
        max_target_factor_cells=1_000_000, max_group_atoms=20,
        max_target_plan_bytes=DEFAULT_MAX_TARGET_PLAN_BYTES):
    """Independently replay a stored operation certificate under caller caps."""
    _positive_budgets((
        ("max_proof_bytes", max_proof_bytes),
        ("max_bucket_cells", max_bucket_cells),
        ("max_total_cells", max_total_cells),
        ("max_factor_lookups", max_factor_lookups),
        ("max_factor_cells", max_factor_cells),
        ("max_total_factor_cells", max_total_factor_cells),
        ("max_atoms", max_atoms), ("max_terms", max_terms),
        ("max_equivalence_checks", max_equivalence_checks),
        ("max_target_rows", max_target_rows),
        ("max_target_factor_cells", max_target_factor_cells),
        ("max_group_atoms", max_group_atoms),
        ("max_target_plan_bytes", max_target_plan_bytes),
    ))
    if not isinstance(artifact, dict):
        raise ValueError("membership artifact must be an object")
    if len(_canonical(artifact)) > max_proof_bytes:
        return _verification_incomplete(artifact, "VERIFY_PROOF_BYTE_BUDGET_EXHAUSTED")
    if artifact.get("artifactSha256") != \
            _digest({key: value for key, value in artifact.items()
                     if key != "artifactSha256"}):
        raise ValueError("membership artifact commitment mismatch")
    expected_top_keys = {
        "schema", "claimScope", "status", "blockers", "bindings", "budgets",
        "preimageArtifactSha256", "membership", "witness", "proof",
        "diagnosticStatus", "executionProvenance", "claims",
        "semanticBoundary", "artifactSha256"}
    if set(artifact) != expected_top_keys or artifact.get("schema") != SCHEMA or \
            artifact.get("claimScope") != CLAIM_SCOPE:
        raise ValueError("membership artifact contract mismatch")
    budgets = artifact.get("budgets")
    if not isinstance(budgets, dict):
        raise ValueError("membership artifact budgets are missing")
    required_budgets = {
        "maxNodes", "maxApplyPairs", "maxFactorCells",
        "maxTotalFactorCells", "maxAtoms", "maxTerms",
        "maxEquivalenceChecks", "maxTargetRows", "maxTargetFactorCells",
        "maxGroupAtoms", "maxDenseBucketCells", "maxDenseTotalCells",
        "maxDenseFactorLookups", "maxTargetPlanBytes"}
    if set(budgets) != required_budgets:
        raise ValueError("membership artifact budget contract mismatch")
    if any(type(value) is not int or value < 1 for value in budgets.values()):
        raise ValueError("membership artifact contains an invalid budget")
    try:
        target_size = len(_canonical(target_plan))
    except (TypeError, ValueError) as error:
        raise ValueError("verification target plan is not finite JSON") from error
    if target_size > max_target_plan_bytes:
        return _verification_incomplete(
            artifact, "VERIFY_TARGET_PLAN_BYTE_BUDGET_EXHAUSTED")
    if target_size > budgets["maxTargetPlanBytes"]:
        raise ValueError("target plan exceeds committed construction budget")
    caps = (("maxFactorCells", max_factor_cells),
            ("maxTotalFactorCells", max_total_factor_cells),
            ("maxAtoms", max_atoms), ("maxTerms", max_terms),
            ("maxEquivalenceChecks", max_equivalence_checks),
            ("maxTargetRows", max_target_rows),
            ("maxTargetFactorCells", max_target_factor_cells),
            ("maxGroupAtoms", max_group_atoms),
            ("maxTargetPlanBytes", max_target_plan_bytes))
    for name, cap in caps:
        if type(budgets[name]) is not int or budgets[name] < 1:
            raise ValueError("membership artifact budget is invalid: " + name)
        if budgets[name] > cap:
            return _verification_incomplete(
                artifact, "VERIFY_CONSTRUCTION_BUDGET_CAP_EXCEEDED:" + name)

    preimage = compile_target_preimage(
        model_path, target_plan, verification_root,
        max_target_rows=budgets["maxTargetRows"],
        max_target_factor_cells=budgets["maxTargetFactorCells"],
        max_group_atoms=budgets["maxGroupAtoms"],
        max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_atoms=budgets["maxAtoms"], max_terms=budgets["maxTerms"],
        max_equivalence_checks=budgets["maxEquivalenceChecks"])
    if preimage.get("status") != "COMPLETE" or \
            preimage.get("artifactSha256") != artifact.get(
                "preimageArtifactSha256"):
        raise ValueError("stored membership preimage does not regenerate")
    if artifact.get("bindings") != preimage.get("bindings") or \
            artifact.get("executionProvenance") != preimage.get(
                "executionProvenance"):
        raise ValueError("stored membership preimage bindings differ")
    translated = translate_physical_forest(
        model_path, max_factor_cells=budgets["maxFactorCells"],
        max_total_factor_cells=budgets["maxTotalFactorCells"],
        max_atoms=budgets["maxAtoms"], max_terms=budgets["maxTerms"],
        max_equivalence_checks=budgets["maxEquivalenceChecks"])
    if translated.get("physicalTranslationStatus") != "COMPLETE" or \
            _translation_bindings(translated) != {
                key: artifact["bindings"][key]
                for key in _translation_bindings(translated)}:
        raise ValueError("stored membership model bindings do not regenerate")
    variables = translated["allVariables"]
    target_factors = preimage["factorPayload"]["targetFactors"]
    independent_target, target_blocker = _independent_target_factors(
        translated, target_plan, verification_root,
        max_target_rows=budgets["maxTargetRows"],
        max_target_factor_cells=budgets["maxTargetFactorCells"],
        max_group_atoms=budgets["maxGroupAtoms"])
    if target_blocker:
        raise ValueError("stored target factor rebuild is blocked: " +
                         target_blocker)
    if independent_target != target_factors:
        raise ValueError("stored preimage differs from independent target rebuild")
    factors = list(translated["allFactors"]) + list(target_factors)
    native_levels = tuple(range(len(translated["order"])))
    atom_levels = tuple(sorted(translated["atomLevels"].values()))
    eliminate_levels = atom_levels + native_levels
    proof = artifact.get("proof")
    if not isinstance(proof, dict):
        raise ValueError("stored membership proof is missing")
    expected_proof_keys = {
        "eliminationOrder", "eliminationPolicy", "denseReplayStatus",
        "denseReplayCells", "denseFactorLookupBound", "denseTrace",
        "denseResidualSha256", "inputFactorsSha256",
        "targetFactorsSha256", "producerMddResultSha256", "producerMdd",
        "producerMddResidualReplay", "finalConjunction",
        "compiledFactorsUnsat", "atomSemantics"}
    if set(proof) != expected_proof_keys:
        raise ValueError("stored membership proof shape differs")
    if (proof["eliminationOrder"] != list(eliminate_levels) or
            proof["eliminationPolicy"] !=
            "ALL_ATOM_LEVELS_THEN_ALL_VARYING_NATIVE_LEVELS" or
            proof["denseReplayStatus"] != "PASS_COMPLETE" or
            proof["producerMddResidualReplay"] != "PASS_EVERY_ROOT"):
        raise ValueError("stored membership proof contract differs")
    dense, reverse, cells, lookups, blocker = check_dense_certificate(
        variables, factors, eliminate_levels, proof.get("denseTrace"),
        max_bucket_cells=max_bucket_cells, max_total_cells=max_total_cells,
        max_factor_lookups=max_factor_lookups)
    if blocker:
        return _verification_incomplete(artifact, blocker)
    if proof.get("denseReplayCells") != str(cells) or \
            proof.get("denseFactorLookupBound") != str(lookups):
        raise ValueError("stored membership replay totals differ")
    if proof.get("denseResidualSha256") != _digest([
            {"name": row["name"], "scope": list(row["scope"]),
             "truth": list(row["truth"])} for row in dense]):
        raise ValueError("stored membership dense residual differs")
    normalized = _normalize_factors(factors, variables)
    if proof.get("inputFactorsSha256") != _digest([
            {"name": row["name"], "scope": list(row["scope"]),
             "truth": list(row["truth"])} for row in normalized]):
        raise ValueError("stored membership input factor commitment differs")
    if proof.get("targetFactorsSha256") != _digest(target_factors):
        raise ValueError("stored membership target factor commitment differs")
    producer = proof.get("producerMdd")
    if not isinstance(producer, dict):
        raise ValueError("stored producer MDD proof is missing")
    if producer.get("budgets") != {
            "maxNodes": budgets["maxNodes"],
            "maxApplyPairs": budgets["maxApplyPairs"]}:
        raise ValueError("producer MDD budgets differ from membership budgets")
    recorded_cells = [int(row["bucketCells"])
                      for row in proof["denseTrace"]]
    recorded_lookups = [int(row["factorLookupBound"])
                        for row in proof["denseTrace"]]
    if (any(value > budgets["maxDenseBucketCells"]
            for value in recorded_cells) or
            sum(recorded_cells) > budgets["maxDenseTotalCells"] or
            sum(recorded_lookups) > budgets["maxDenseFactorLookups"] or
            proof["denseReplayCells"] != str(sum(recorded_cells)) or
            proof["denseFactorLookupBound"] != str(sum(recorded_lookups))):
        raise ValueError("dense proof exceeds committed construction budgets")
    verify_factor_forest_artifact(producer, proof.get("producerMddResultSha256"))
    verify_residual_roots(producer["relation"], variables, dense)
    satisfiable = all(row["scope"] == () and row["truth"] == (True,)
                      for row in dense)
    semantic_verification = verify_atom_semantics_certificate(
        proof.get("atomSemantics"), model_path, translated,
        max_semantic_cells=max_equivalence_checks)
    semantic_complete = semantic_verification.get("status") == "PASS"
    negative_decided = not satisfiable and semantic_complete
    expected_status = ("SAT" if satisfiable else
                       "UNSAT" if negative_decided else "INCOMPLETE")
    expected_membership = (True if satisfiable else
                           False if negative_decided else None)
    expected_blockers = ([] if satisfiable or negative_decided else
                         proof["atomSemantics"].get("blockers") or
                         ["ATOM_DECODER_SEMANTICS_NOT_CERTIFIED"])
    expected_diagnostic = ("MODEL_LOCAL_MEMBERSHIP_DECIDED" if satisfiable else
                           "MODEL_LOCAL_NON_PHI_MEMBERSHIP_DECIDED"
                           if negative_decided else
                           "COMPILED_FACTORS_UNSAT_CONDITIONAL")
    expected_claims = {"executableAttestation": "NOT_ESTABLISHED",
                       "javaPlannerSemantics": "NOT_ESTABLISHED",
                       "pEqualsE": "NOT_ASSESSED"}
    expected_boundary = [
        "MODEL_LOCAL_UNATTESTED_EXECUTION_RESULT",
        "CAPTURED_TYPED_DECODER_NOT_INDEPENDENTLY_BOUND_TO_JAVA_SEMANTICS",
        ("CAPTURED_NON_PHI_ATOM_AND_HARD_FACTOR_TRANSLATION_REPLAYED"
         if semantic_complete else
         "CAPTURED_HARD_AND_ATOM_FACTOR_TRANSLATION_IS_MODEL_INPUT"),
        "NOT_A_P_E_EQUALITY_RESULT"]
    if (artifact.get("status") != expected_status or
            artifact.get("membership") is not expected_membership or
            artifact.get("blockers") != expected_blockers or
            artifact.get("diagnosticStatus") != expected_diagnostic or
            artifact.get("claims") != expected_claims or
            artifact.get("semanticBoundary") != expected_boundary or
            proof.get("finalConjunction") is not satisfiable or
            proof.get("compiledFactorsUnsat") is not (not satisfiable)):
        raise ValueError("stored membership verdict differs from dense proof")
    if satisfiable:
        all_assignment = recover_dense_witness(reverse, variables)
        native = [0] * len(translated["radices"])
        for level, domain in enumerate(translated["order"]):
            native[domain] = all_assignment[level]
        native = tuple(native)
        if any(factor_status(scope, truth, native, translated["radices"]) !=
               "ALLOW" for scope, truth in translated["tables"]):
            raise ValueError("verified witness fails source E hard factors")
        codec = PhysicalCoordinateCodec(verification_root)
        projected = compose_physical_projection(translated["model"], native)
        coordinate = codec.encode(projected)
        expected_witness = {
            "nativeAssignment": list(native),
            "varyingAndAtomAssignmentSha256": _digest(list(all_assignment)),
            "sourceAcceptance": "ALLOW",
            "projectionCoordinateSha256": hashlib.sha256(coordinate).hexdigest(),
        }
        if coordinate != codec.encode(target_plan) or \
                artifact.get("witness") != expected_witness:
            raise ValueError("verified witness differs from target or artifact")
    elif artifact.get("witness") is not None:
        raise ValueError("conditional factor UNSAT artifact contract differs")
    result = {
        "schema": VERIFICATION_SCHEMA,
        "status": "PASS", "claimScope": CLAIM_SCOPE,
        "verifiedArtifactSha256": artifact["artifactSha256"],
        "blockers": [],
        "regeneration": "INDEPENDENT_STORED_OPERATION_CERTIFICATE_REPLAY",
        "diagnosticStatus": "MODEL_LOCAL_PROOF_REPLAY_COMPLETE",
        "executionProvenance": artifact["executionProvenance"],
        "claims": dict(artifact["claims"]),
        "semanticBoundary": list(artifact["semanticBoundary"]),
    }
    result["verificationSha256"] = _digest(result)
    return result


def _read_plan(path, max_bytes):
    path = Path(path)
    with path.open("rb") as stream:
        encoded = stream.read(max_bytes + 1)
    if len(encoded) > max_bytes:
        raise ValueError("target plan byte budget exhausted")
    return json.loads(encoded)


def _read_json(path, max_bytes, label):
    path = Path(path)
    with path.open("rb") as stream:
        encoded = stream.read(max_bytes + 1)
    if len(encoded) > max_bytes:
        raise ValueError(label + " byte budget exhausted")
    return json.loads(encoded)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--plan", required=True)
    parser.add_argument("--verification-root", required=True)
    parser.add_argument("--verify-artifact")
    parser.add_argument("--max-nodes", type=int, default=250_000)
    parser.add_argument("--max-apply-pairs", type=int, default=1_000_000)
    parser.add_argument("--max-dense-bucket-cells", type=int,
                        default=1_000_000)
    parser.add_argument("--max-dense-total-cells", type=int,
                        default=10_000_000)
    parser.add_argument("--max-dense-factor-lookups", type=int,
                        default=50_000_000)
    parser.add_argument("--max-proof-bytes", type=int,
                        default=64 * 1024 ** 2)
    parser.add_argument("--max-target-plan-bytes", type=int,
                        default=DEFAULT_MAX_TARGET_PLAN_BYTES)
    args = parser.parse_args(argv)
    target = _read_plan(args.plan, args.max_target_plan_bytes)
    if args.verify_artifact:
        stored = _read_json(
            args.verify_artifact, args.max_proof_bytes, "proof artifact")
        result = verify_factorized_membership(
            stored, args.model, target, args.verification_root,
            max_proof_bytes=args.max_proof_bytes,
            max_bucket_cells=args.max_dense_bucket_cells,
            max_total_cells=args.max_dense_total_cells,
            max_factor_lookups=args.max_dense_factor_lookups,
            max_target_plan_bytes=args.max_target_plan_bytes)
    else:
        result = query_factorized_membership(
            args.model, target, args.verification_root,
            max_nodes=args.max_nodes, max_apply_pairs=args.max_apply_pairs,
            max_dense_bucket_cells=args.max_dense_bucket_cells,
            max_dense_total_cells=args.max_dense_total_cells,
            max_dense_factor_lookups=args.max_dense_factor_lookups,
            max_target_plan_bytes=args.max_target_plan_bytes)
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0 if result["status"] in ("SAT", "UNSAT", "PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
