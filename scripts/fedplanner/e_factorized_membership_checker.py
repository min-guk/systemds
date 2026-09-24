"""Independent dense operation-certificate checker for E membership proofs."""

import hashlib
from itertools import product
import json
from math import prod

from boolean_mdd_relation import validate_artifact


def _bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _sha(value):
    return hashlib.sha256(_bytes(value)).hexdigest()


def _checked_factors(rows, variables):
    radices = tuple(len(variable.values) for variable in variables)
    result = []
    names = set()
    for source in rows:
        if not isinstance(source, dict) or set(source) != {
                "name", "scope", "truth"}:
            raise ValueError("checker factor shape is invalid")
        name = source["name"]
        scope = tuple(source["scope"])
        truth = tuple(source["truth"])
        if (not isinstance(name, str) or not name or name in names or
                name.startswith("elim:")):
            raise ValueError("checker factor name is invalid")
        if (scope != tuple(sorted(set(scope))) or
                any(type(level) is not int or level < 0 or
                    level >= len(variables) for level in scope)):
            raise ValueError("checker factor scope is invalid")
        if (len(truth) != prod(radices[level] for level in scope) or
                any(type(value) is not bool for value in truth)):
            raise ValueError("checker factor truth table is invalid")
        names.add(name)
        result.append({"name": name, "scope": scope, "truth": truth})
    if not result:
        result = [{"name": "constant:true", "scope": (), "truth": (True,)}]
    return sorted(result, key=lambda row: row["name"])


def _lookup(row, assignment, radices):
    index = 0
    multiplier = 1
    for level in reversed(row["scope"]):
        index += assignment[level] * multiplier
        multiplier *= radices[level]
    return row["truth"][index]


def _name(stage, level, names):
    return "elim:%04d:%04d:%s" % (stage, level, _sha(names)[:16])


def check_dense_certificate(variables, factors, eliminate_levels, certificate,
                            *, max_bucket_cells, max_total_cells,
                            max_factor_lookups):
    if not isinstance(certificate, list) or \
            len(certificate) != len(eliminate_levels):
        raise ValueError("dense certificate stage count mismatch")
    radices = tuple(len(variable.values) for variable in variables)
    forest = _checked_factors(factors, variables)
    reverse = []
    total_cells = 0
    total_lookups = 0
    for stage, level in enumerate(eliminate_levels):
        recorded = certificate[stage]
        incident = sorted((row for row in forest if level in row["scope"]),
                          key=lambda row: row["name"])
        preserved = [row for row in forest if level not in row["scope"]]
        input_scope = tuple(sorted({item for row in incident
                                    for item in row["scope"]}))
        output_scope = tuple(item for item in input_scope if item != level)
        cells = radices[level] * prod(radices[item] for item in output_scope)
        lookups = cells * len(incident)
        if cells > max_bucket_cells:
            return None, None, total_cells, total_lookups, \
                "VERIFY_BUCKET_CELL_BUDGET_EXHAUSTED"
        if total_cells + cells > max_total_cells:
            return None, None, total_cells, total_lookups, \
                "VERIFY_TOTAL_CELL_BUDGET_EXHAUSTED"
        if total_lookups + lookups > max_factor_lookups:
            return None, None, total_cells, total_lookups, \
                "VERIFY_FACTOR_LOOKUP_BUDGET_EXHAUSTED"
        total_cells += cells
        total_lookups += lookups
        truth = []
        args = []
        for output in product(*(range(radices[item])
                                for item in output_scope)):
            assignment = dict(zip(output_scope, output))
            chosen = -1
            for value in range(radices[level]):
                assignment[level] = value
                if all(_lookup(row, assignment, radices) for row in incident):
                    chosen = value
                    break
            truth.append(chosen != -1)
            args.append(chosen)
        names = [row["name"] for row in incident]
        synthetic = _name(stage, level, names)
        expected = {
            "stage": stage, "eliminatedLevel": level,
            "eliminatedVariable": variables[level].name,
            "incidentFactors": names, "preservedFactors": len(preserved),
            "inputScope": list(input_scope), "outputScope": list(output_scope),
            "resultFactor": synthetic, "bucketCells": str(cells),
            "factorLookupBound": str(lookups), "outputTruth": truth,
            "argWitness": args,
            "argWitnessSha256": _sha({"scope": list(output_scope),
                                       "args": args}),
        }
        if recorded != expected:
            raise ValueError("dense operation certificate mismatch at stage %d" %
                             stage)
        projected = {"name": synthetic, "scope": output_scope,
                     "truth": tuple(truth)}
        forest = sorted(preserved + [projected], key=lambda row: row["name"])
        reverse.append({"level": level, "scope": output_scope,
                        "args": tuple(args)})
    return forest, reverse, total_cells, total_lookups, None


def recover_dense_witness(reverse, variables):
    radices = tuple(len(variable.values) for variable in variables)
    assignment = {}
    for row in reversed(reverse):
        if any(level not in assignment for level in row["scope"]):
            raise ValueError("checker witness dependency is unavailable")
        index = 0
        multiplier = 1
        for level in reversed(row["scope"]):
            index += assignment[level] * multiplier
            multiplier *= radices[level]
        chosen = row["args"][index]
        if chosen < 0:
            raise ValueError("checker witness table contradicts SAT")
        assignment[row["level"]] = chosen
    if len(assignment) != len(variables):
        raise ValueError("checker witness does not cover every variable")
    return tuple(assignment[level] for level in range(len(variables)))


def verify_residual_roots(relation, variables, dense_forest):
    artifact = validate_artifact(relation, variables)
    expected = {row["name"]: row for row in dense_forest}
    if set(artifact.roots) != set(expected):
        raise ValueError("checker residual MDD roots differ")
    radices = tuple(len(variable.values) for variable in variables)
    for name, row in expected.items():
        for values in product(*(range(radices[level])
                                for level in row["scope"])):
            assignment = [0] * len(variables)
            for level, value in zip(row["scope"], values):
                assignment[level] = value
            if artifact.evaluate(name, assignment) is not \
                    _lookup(row, assignment, radices):
                raise ValueError("checker residual MDD truth differs")
