#!/usr/bin/env python3
"""Bounded exact variable elimination over an explicit forest of MDD factors.

This diagnostic kernel keeps every constraint as a separate factor.  To
eliminate a native variable it conjoins only factors whose declared scope
contains that variable, existentially quantifies the variable, and puts the
result back into the forest.  Factors unrelated to the variable, including
output-only factors, remain untouched.

The kernel is exact for any finite factor graph; ``forest`` describes the
maintained collection of factor roots, not an acyclicity assumption.  A
resource-limit result contains no relation and therefore fails closed.  This
module is a diagnostic primitive, not a P/E equality certificate.
"""

from dataclasses import dataclass
import hashlib
import json
from math import prod

from boolean_mdd_relation import (MDDManager, MDDResourceLimitError, Variable,
                                  validate_artifact)


SCHEMA = "factor-forest-mdd-image-diagnostic-v1"
_SYNTHETIC_PREFIX = "elim:"
_CLAIM_SCOPE = "DIAGNOSTIC_EXACT_FACTOR_PROJECTION_ONLY"
_CERTIFICATE_BLOCKER = "NOT_A_P_E_EQUALITY_CERTIFICATE"
_COMPLETE_KEYS = {
    "schema", "status", "diagnosticStatus", "claimScope", "blockers",
    "semanticBindingStatus", "inputSha256", "eliminateLevels",
    "inputFactorScopes", "remainingSupport", "residualFactors", "trace",
    "relation", "budgets", "resourceUsage", "resultSha256",
}
_BLOCKED_KEYS = {
    "schema", "status", "diagnosticStatus", "claimScope", "blockers",
    "semanticBindingStatus", "inputSha256", "budgets", "resourceUsage",
    "atFailure", "trace", "diagnosticSha256",
}


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


@dataclass(frozen=True)
class _Factor:
    name: str
    scope: frozenset
    root: object


def _variables(variables):
    result = tuple(variable if isinstance(variable, Variable)
                   else Variable(*variable) for variable in variables)
    if len({variable.name for variable in result}) != len(result):
        raise ValueError("factor-forest variable names must be unique")
    return result


def _factor_rows(rows, variables):
    if not isinstance(rows, (tuple, list)):
        raise ValueError("factor forest factors must be a sequence")
    if not rows:
        # The identity of conjunction is true.  Normalizing it to one explicit
        # constant factor keeps the serialized multi-root forest non-empty and
        # binds that semantic choice into the ordinary input commitment.
        return ({"name": "constant:true", "scope": (), "truth": (True,)},)
    normalized = []
    names = set()
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"name", "scope", "truth"}:
            raise ValueError("factor must contain exactly name, scope, and truth")
        name, scope, truth = row["name"], tuple(row["scope"]), tuple(row["truth"])
        if not isinstance(name, str) or not name or name in names:
            raise ValueError("factor names must be non-empty and unique")
        if name.startswith(_SYNTHETIC_PREFIX):
            raise ValueError("factor name uses reserved elimination namespace")
        names.add(name)
        if (any(type(level) is not int for level in scope) or
                tuple(sorted(set(scope))) != scope or
                any(level < 0 or level >= len(variables) for level in scope)):
            raise ValueError("factor scope must contain unique increasing levels")
        cells = prod(len(variables[level].values) for level in scope)
        if len(truth) != cells or any(type(value) is not bool for value in truth):
            raise ValueError("factor truth table does not cover its explicit scope")
        normalized.append({"name": name, "scope": scope, "truth": truth})
    return tuple(sorted(normalized, key=lambda row: row["name"]))


def _root_supports(relation):
    """Return reachable variable levels for every serialized relation root."""
    by_id = {row["id"]: row for row in relation["nodes"]}
    result = {}
    for name, root in relation["roots"].items():
        support = set()
        pending = [root]
        visited = set()
        while pending:
            node_id = pending.pop()
            if node_id in ("F", "T") or node_id in visited:
                continue
            visited.add(node_id)
            row = by_id[node_id]
            support.add(row["level"])
            pending.extend(row["children"])
        result[name] = support
    return result


def _input_commitment(variables, factors, eliminate_levels):
    return _digest({
        "variables": [{"name": variable.name, "values": list(variable.values)}
                      for variable in variables],
        "factors": [{"name": row["name"], "scope": list(row["scope"]),
                     "truth": list(row["truth"])} for row in factors],
        "eliminateLevels": list(eliminate_levels),
    })


def eliminate_factor_forest(variables, factors, eliminate_levels, *,
                            max_nodes=250_000, max_apply_pairs=1_000_000,
                            compact_each_stage=True):
    """Project ``eliminate_levels`` from dense Boolean factors exactly.

    The returned ``relation`` has one root per residual factor.  It deliberately
    does not conjoin those roots or count their global image.  Every root support
    excludes eliminated levels.  A resource limit produces
    ``diagnosticStatus=DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT`` and omits the relation.
    """
    variables = _variables(variables)
    factors = _factor_rows(factors, variables)
    eliminate_levels = tuple(eliminate_levels)
    if (any(type(level) is not int for level in eliminate_levels) or
            len(set(eliminate_levels)) != len(eliminate_levels) or
            any(level < 0 or level >= len(variables)
                for level in eliminate_levels)):
        raise ValueError("elimination levels must be unique valid integers")
    for label, value in (("max_nodes", max_nodes),
                         ("max_apply_pairs", max_apply_pairs)):
        if type(value) is not int or value < 1:
            raise ValueError(label + " must be a positive integer")
    if type(compact_each_stage) is not bool:
        raise ValueError("compact_each_stage must be Boolean")

    commitment = _input_commitment(variables, factors, eliminate_levels)
    budgets = {"maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs}
    manager = MDDManager(variables, max_nodes=max_nodes,
                         max_apply_pairs=max_apply_pairs)
    forest = []
    trace = []
    progress = {"phase": "COMPILE", "factor": None}
    peak_nodes = 0
    peak_pairs = 0

    def sample():
        nonlocal peak_nodes, peak_pairs
        peak_nodes = max(peak_nodes, len(manager._nodes))
        peak_pairs = max(peak_pairs, len(manager._apply_cache))

    try:
        for row in factors:
            progress.update(phase="COMPILE", factor=row["name"])
            root = manager.from_factor(row["scope"], row["truth"])
            forest.append(_Factor(row["name"], frozenset(row["scope"]), root))
            sample()

        for stage, level in enumerate(eliminate_levels):
            progress.update(phase="ELIMINATE", stage=stage, level=level,
                            factor=None)
            incident = sorted((factor for factor in forest if level in factor.scope),
                              key=lambda factor: factor.name)
            preserved = [factor for factor in forest if level not in factor.scope]
            merged = manager.true
            for factor in incident:
                progress["factor"] = factor.name
                merged = manager.and_(merged, factor.root)
                sample()
            progress["factor"] = None
            projected = manager.exists(merged, (level,))
            output_scope = frozenset().union(
                *(factor.scope for factor in incident)) - {level}
            synthetic = _SYNTHETIC_PREFIX + "%04d:%04d:%s" % (
                stage, level, _digest([factor.name for factor in incident])[:16])
            forest = preserved + [_Factor(synthetic, output_scope, projected)]
            forest.sort(key=lambda factor: factor.name)
            sample()
            reclaimed = 0
            if compact_each_stage:
                reclaimed = manager.compact([factor.root for factor in forest])
                sample()
            trace.append({
                "stage": stage, "eliminatedLevel": level,
                "eliminatedVariable": variables[level].name,
                "incidentFactors": [factor.name for factor in incident],
                "preservedFactors": len(preserved),
                "inputScope": sorted(frozenset().union(
                    *(factor.scope for factor in incident))),
                "outputScope": sorted(output_scope),
                "resultFactor": synthetic, "nodesReclaimed": reclaimed,
                "liveNodes": len(manager._nodes),
            })

        progress.update(phase="SERIALIZE", factor=None)
        roots = {factor.name: factor.root for factor in forest}
        relation = manager.to_artifact(roots)
        supports = _root_supports(relation)
        remaining_support = set().union(*supports.values())
        leaked = sorted(set(eliminate_levels) & remaining_support)
        if leaked:
            raise AssertionError("eliminated variables remain in relation support: %s" %
                                 leaked)
        for factor in forest:
            if not supports[factor.name] <= factor.scope:
                raise AssertionError("residual root support exceeds declared scope")
        sample()
    except MDDResourceLimitError as error:
        sample()
        blocked = {
            "schema": SCHEMA, "status": "BLOCKED",
            "diagnosticStatus": "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
            "claimScope": _CLAIM_SCOPE,
            "blockers": [str(error).upper().replace(" ", "_")],
            "semanticBindingStatus": "NOT_PRODUCED_RESOURCE_LIMIT",
            "inputSha256": commitment, "budgets": budgets,
            "resourceUsage": {"peakLiveNodes": peak_nodes,
                              "peakApplyPairsCached": peak_pairs,
                              "liveNodesAtFailure": len(manager._nodes)},
            "atFailure": dict(progress), "trace": trace,
        }
        blocked["diagnosticSha256"] = _digest(blocked)
        return blocked

    residual = [{"name": factor.name, "scope": sorted(factor.scope),
                 "root": relation["roots"][factor.name]}
                for factor in sorted(forest, key=lambda factor: factor.name)]
    payload = {
        "schema": SCHEMA, "status": "BLOCKED",
        "diagnosticStatus": "DIAGNOSTIC_COMPLETE",
        "claimScope": _CLAIM_SCOPE,
        "blockers": [_CERTIFICATE_BLOCKER],
        "semanticBindingStatus": "UNVERIFIED_INPUT_FACTOR_SEMANTICS",
        "inputSha256": commitment, "eliminateLevels": list(eliminate_levels),
        "inputFactorScopes": [
            {"name": row["name"], "scope": list(row["scope"])}
            for row in factors],
        "remainingSupport": sorted(remaining_support),
        "residualFactors": residual,
        "trace": trace, "relation": relation, "budgets": budgets,
        "resourceUsage": {
            "peakLiveNodes": peak_nodes,
            "peakApplyPairsCached": peak_pairs,
            "liveNodes": len(manager._nodes),
            "totalNodesCreated": manager._next - 2,
        },
    }
    payload["resultSha256"] = _digest(payload)
    return payload


def _replay_scopes(factors, eliminate_levels, variable_names):
    """Independently replay factor names/scopes without constructing an MDD."""
    forest = [(row["name"], frozenset(row["scope"])) for row in factors]
    trace = []
    for stage, level in enumerate(eliminate_levels):
        incident = sorted((row for row in forest if level in row[1]))
        preserved = [row for row in forest if level not in row[1]]
        names = [name for name, _ in incident]
        input_scope = frozenset().union(*(scope for _, scope in incident))
        output_scope = input_scope - {level}
        synthetic = _SYNTHETIC_PREFIX + "%04d:%04d:%s" % (
            stage, level, _digest(names)[:16])
        forest = sorted(preserved + [(synthetic, output_scope)])
        trace.append({"stage": stage, "eliminatedLevel": level,
                      "eliminatedVariable": variable_names[level],
                      "incidentFactors": names,
                      "preservedFactors": len(preserved),
                      "inputScope": sorted(input_scope),
                      "outputScope": sorted(output_scope),
                      "resultFactor": synthetic})
    return forest, trace


def materialize_factor_forest(result, *, max_cells=100_000):
    """Enumerate a small residual forest for testing; never used by construction."""
    if type(max_cells) is not int or max_cells < 1:
        raise ValueError("max_cells must be a positive integer")
    if result.get("diagnosticStatus") != "DIAGNOSTIC_COMPLETE":
        raise ValueError("only a complete factor forest can be materialized")
    artifact = validate_artifact(result["relation"])
    cells = prod(len(variable.values) for variable in artifact.variables)
    if cells > max_cells:
        raise MDDResourceLimitError("factor-forest replay cell budget exhausted")
    eliminated = set(result["eliminateLevels"])
    kept = tuple(level for level in range(len(artifact.variables))
                 if level not in eliminated)
    accepted = set()
    from itertools import product as cartesian_product
    for assignment in cartesian_product(
            *(range(len(variable.values)) for variable in artifact.variables)):
        if all(artifact.evaluate(name, assignment)
               for name in artifact.roots):
            accepted.add(tuple(assignment[level] for level in kept))
    return {"keptLevels": kept, "acceptedAssignments": accepted,
            "replayCells": cells}


def verify_factor_forest_artifact(result, expected_result_sha256=None):
    """Artifact-only structural, commitment, scope, and trace verification.

    Factor truth tables are intentionally not duplicated in the artifact, so
    this check explicitly leaves producer semantics unverified.  Call
    :func:`verify_factor_forest_result` with source factors for bounded semantic
    replay.
    """
    if not isinstance(result, dict) or set(result) != _COMPLETE_KEYS:
        raise ValueError("complete factor-forest result has invalid top-level shape")
    if (result["schema"] != SCHEMA or result["status"] != "BLOCKED" or
            result["diagnosticStatus"] != "DIAGNOSTIC_COMPLETE" or
            result["claimScope"] != _CLAIM_SCOPE or
            result["blockers"] != [_CERTIFICATE_BLOCKER] or
            result["semanticBindingStatus"] !=
            "UNVERIFIED_INPUT_FACTOR_SEMANTICS"):
        raise ValueError("complete factor-forest diagnostic contract mismatch")
    payload = {key: value for key, value in result.items()
               if key != "resultSha256"}
    observed_sha = _digest(payload)
    if result.get("resultSha256") != observed_sha:
        raise ValueError("factor-forest result commitment mismatch")
    if (expected_result_sha256 is not None and
            expected_result_sha256 != observed_sha):
        raise ValueError("factor-forest expected commitment mismatch")
    artifact = validate_artifact(result["relation"])
    scopes = result.get("inputFactorScopes")
    if (not isinstance(scopes, list) or not scopes or any(
            not isinstance(row, dict) or set(row) != {"name", "scope"}
            for row in scopes)):
        raise ValueError("input factor scope inventory is invalid")
    names = [row["name"] for row in scopes]
    if (any(not isinstance(name, str) or not name for name in names) or
            len(names) != len(set(names))):
        raise ValueError("input factor scope names are invalid")
    if any(name.startswith(_SYNTHETIC_PREFIX) for name in names):
        raise ValueError("input factor name uses reserved elimination namespace")
    width = len(artifact.variables)
    for row in scopes:
        scope = row["scope"]
        if (not isinstance(scope, list) or
                any(type(level) is not int for level in scope) or
                scope != sorted(set(scope)) or
                any(level < 0 or level >= width for level in scope)):
            raise ValueError("input factor scope inventory is invalid")
    eliminate_levels = result.get("eliminateLevels")
    if (not isinstance(eliminate_levels, list) or
            any(type(level) is not int for level in eliminate_levels) or
            len(eliminate_levels) != len(set(eliminate_levels)) or
            any(level < 0 or level >= width for level in eliminate_levels)):
        raise ValueError("elimination level inventory is invalid")
    residual, trace = _replay_scopes(
        scopes, eliminate_levels,
        [variable.name for variable in artifact.variables])
    if set(artifact.roots) != {name for name, _ in residual}:
        raise ValueError("serialized roots do not cover residual factor forest")
    expected_residual = [{"name": name, "scope": sorted(scope),
                          "root": artifact.roots[name]}
                         for name, scope in residual]
    if result.get("residualFactors") != expected_residual:
        raise ValueError("residual factor roots or scopes mismatch")
    trace_rows = result.get("trace")
    trace_keys = ("stage", "eliminatedLevel", "eliminatedVariable",
                  "incidentFactors", "preservedFactors", "inputScope",
                  "outputScope", "resultFactor")
    if (not isinstance(trace_rows, list) or any(
            not isinstance(row, dict) or any(key not in row for key in trace_keys)
            for row in trace_rows)):
        raise ValueError("factor elimination trace has invalid shape")
    observed_trace = [{key: row[key] for key in trace_keys}
                      for row in trace_rows]
    if observed_trace != trace:
        raise ValueError("factor elimination trace replay mismatch")
    supports = _root_supports(result["relation"])
    residual_scopes = dict(residual)
    if any(not support <= residual_scopes[name]
           for name, support in supports.items()):
        raise ValueError("serialized root support exceeds residual factor scope")
    union = set().union(*supports.values())
    if sorted(union) != result.get("remainingSupport"):
        raise ValueError("remaining support commitment mismatch")
    if union & set(eliminate_levels):
        raise ValueError("eliminated variable remains in serialized root support")
    return {"status": "PASS", "structuralStatus": "VERIFIED",
            "traceReplayStatus": "VERIFIED",
            "semanticReplayStatus": "UNVERIFIED_INPUT_FACTOR_SEMANTICS",
            "resourceMetricsStatus": "UNVERIFIED_PRODUCER_REPORTED",
            "resultSha256": observed_sha}


def verify_factor_forest_blocked(result, expected_diagnostic_sha256=None):
    """Artifact-only validation for a resource-blocked diagnostic."""
    if not isinstance(result, dict) or set(result) != _BLOCKED_KEYS:
        raise ValueError("blocked factor-forest result has invalid top-level shape")
    if (result["schema"] != SCHEMA or result["status"] != "BLOCKED" or
            result["diagnosticStatus"] !=
            "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT" or
            result["claimScope"] != _CLAIM_SCOPE or
            result["semanticBindingStatus"] !=
            "NOT_PRODUCED_RESOURCE_LIMIT"):
        raise ValueError("blocked factor-forest diagnostic contract mismatch")
    if (not isinstance(result["blockers"], list) or not result["blockers"] or
            any(not isinstance(row, str) or not row for row in result["blockers"])):
        raise ValueError("blocked factor-forest blockers are invalid")
    if (not isinstance(result["inputSha256"], str) or
            len(result["inputSha256"]) != 64 or
            not isinstance(result["budgets"], dict) or
            set(result["budgets"]) != {"maxNodes", "maxApplyPairs"} or
            any(type(value) is not int or value < 1
                for value in result["budgets"].values()) or
            not isinstance(result["resourceUsage"], dict) or
            set(result["resourceUsage"]) != {
                "peakLiveNodes", "peakApplyPairsCached", "liveNodesAtFailure"} or
            any(type(value) is not int or value < 0
                for value in result["resourceUsage"].values()) or
            not isinstance(result["atFailure"], dict) or
            not isinstance(result["trace"], list)):
        raise ValueError("blocked factor-forest diagnostic payload is invalid")
    payload = {key: value for key, value in result.items()
               if key != "diagnosticSha256"}
    observed = _digest(payload)
    if result["diagnosticSha256"] != observed:
        raise ValueError("blocked factor-forest diagnostic commitment mismatch")
    if (expected_diagnostic_sha256 is not None and
            expected_diagnostic_sha256 != observed):
        raise ValueError("blocked factor-forest expected commitment mismatch")
    return {"status": "PASS", "diagnosticStatus": "VERIFIED_BLOCKED",
            "resourceMetricsStatus": "UNVERIFIED_PRODUCER_REPORTED",
            "diagnosticSha256": observed}


def verify_factor_forest_result(result, variables, factors, eliminate_levels, *,
                                max_replay_cells=100_000):
    """Validate commitments, scope trace, roots, and bounded source semantics."""
    variables = _variables(variables)
    factors = _factor_rows(factors, variables)
    eliminate_levels = tuple(eliminate_levels)
    if type(max_replay_cells) is not int or max_replay_cells < 1:
        raise ValueError("max_replay_cells must be a positive integer")
    verify_factor_forest_artifact(result)
    expected_input = _input_commitment(variables, factors, eliminate_levels)
    if result.get("inputSha256") != expected_input:
        raise ValueError("factor-forest input commitment mismatch")
    artifact = validate_artifact(result["relation"], variables)
    expected_scopes = [{"name": row["name"], "scope": list(row["scope"])}
                       for row in factors]
    if result.get("inputFactorScopes") != expected_scopes:
        raise ValueError("input factor scope inventory mismatch")

    cells = prod(len(variable.values) for variable in variables)
    if cells > max_replay_cells:
        return {"status": "INCOMPLETE", "structuralStatus": "VERIFIED",
                "semanticReplayStatus":
                    "BLOCKED_REPLAY_CELL_BUDGET_EXHAUSTED",
                "blockers": ["SOURCE_SEMANTIC_REPLAY_CELL_BUDGET_EXHAUSTED"],
                "resourceMetricsStatus": "UNVERIFIED_PRODUCER_REPORTED",
                "replayCells": cells}
    materialized = materialize_factor_forest(result, max_cells=max_replay_cells)
    eliminated = set(eliminate_levels)
    kept = materialized["keptLevels"]
    expected = set()
    from itertools import product as cartesian_product
    radices = [len(variable.values) for variable in variables]
    for assignment in cartesian_product(*(range(radix) for radix in radices)):
        allowed = True
        for row in factors:
            offset = 0
            for level in row["scope"]:
                offset = offset * radices[level] + assignment[level]
            allowed = allowed and row["truth"][offset]
        if allowed:
            expected.add(tuple(assignment[level] for level in kept))
    if materialized["acceptedAssignments"] != expected:
        raise ValueError("bounded source semantic replay mismatch")
    return {"status": "PASS", "structuralStatus": "VERIFIED",
            "semanticReplayStatus": "VERIFIED_EXHAUSTIVE",
            "resourceMetricsStatus": "UNVERIFIED_PRODUCER_REPORTED",
            "replayCells": cells,
            "projectedAssignmentCount": len(expected)}
