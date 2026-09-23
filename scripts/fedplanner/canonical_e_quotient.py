#!/usr/bin/env python3
"""Exact diagnostic quotient from E provenance atoms to canonical plans.

The input is a saved ``compositional-e-atom-image-diagnostic-v1`` artifact.
Non-sequence atoms already identify nodes, authorities, and binding components.
Action and geometry occurrence atoms are replaced by value presence plus only
the first-occurrence precedence choices that can vary in the accepted image.

This remains a diagnostic: the typed projection is not independently bound to
the Java projector, so neither this module nor its artifacts certify E or P/E.
"""

import argparse
from functools import cmp_to_key
import gzip
import hashlib
import json
from pathlib import Path
import tempfile

from boolean_mdd_relation import (MDDManager, MDDResourceLimitError, Variable,
                                  validate_artifact)
from compositional_e_image import canonical, materialize_atoms


SCHEMA = "canonical-e-physical-quotient-diagnostic-v1"
SOURCE_SCHEMA = "compositional-e-atom-image-diagnostic-v1"


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def _read_gzip_json(path, max_decoded_bytes):
    path = Path(path)
    compressed = path.read_bytes()
    with gzip.open(path, "rb") as stream:
        decoded = stream.read(max_decoded_bytes + 1)
        if len(decoded) > max_decoded_bytes or stream.read(1):
            raise ValueError("artifact decompressed-byte budget exhausted")
    value = json.loads(decoded)
    if decoded != canonical(value) + b"\n":
        raise ValueError("artifact is not canonical JSON gzip")
    return value, hashlib.sha256(compressed).hexdigest()


def _variables(artifact):
    return tuple(Variable(row["name"], row["values"])
                 for row in artifact["variables"])


def _validate_source_binding(source, relation):
    """Fail closed unless the saved source root is exactly atom-supported."""
    if (source.get("rootCommitments") != relation.get("roots") or
            set(relation.get("roots", {})) != {"definitelyAcceptedAtomImage"}):
        raise ValueError("source atom-image root commitment differs")
    dictionary = source.get("atomDictionary")
    conditions = source.get("atomConditions")
    static = source.get("alwaysPresentAtoms")
    dynamic = source.get("atomVariableTokens")
    if (not isinstance(dictionary, dict) or not isinstance(conditions, dict) or
            not isinstance(static, list) or not isinstance(dynamic, list) or
            static != sorted(set(static)) or dynamic != sorted(set(dynamic))):
        raise ValueError("source atom partition is not canonical")
    static_set, dynamic_set = set(static), set(dynamic)
    if (static_set & dynamic_set or set(dictionary) != static_set | dynamic_set or
            set(conditions) != set(dictionary) or
            list(dictionary) != sorted(dictionary) or
            list(conditions) != sorted(conditions)):
        raise ValueError("source atom dictionary/static/dynamic binding differs")
    for token, atom in dictionary.items():
        if token != "a_" + digest(atom):
            raise ValueError("source atom token differs from canonical payload")
    counts = source.get("counts")
    if (not isinstance(counts, dict) or
            counts.get("physicalAtoms") != str(len(dictionary)) or
            counts.get("dynamicPhysicalAtoms") != str(len(dynamic)) or
            counts.get("alwaysPresentPhysicalAtoms") != str(len(static))):
        raise ValueError("source atom partition counts differ")

    variables = relation.get("variables")
    if not isinstance(variables, list):
        raise ValueError("source relation variable dictionary is absent")
    atom_levels = {}
    native_levels = set()
    native_indices = set()
    for level, row in enumerate(variables):
        if not isinstance(row, dict) or set(row) != {"name", "values"}:
            raise ValueError("source relation variable shape differs")
        name, values = row["name"], row["values"]
        if name.startswith("atom:"):
            token = name[5:]
            if values != ["0", "1"] or token in atom_levels:
                raise ValueError("source atom variable name or arity differs")
            atom_levels[token] = level
        elif name.startswith("native:"):
            parts = name.split(":", 2)
            try:
                index = int(parts[1])
            except (IndexError, ValueError) as error:
                raise ValueError("source native variable name differs") from error
            if (len(parts) != 3 or index < 0 or index in native_indices or
                    not isinstance(values, list) or not values or
                    len(values) != len(set(values)) or
                    any(not isinstance(value, str) for value in values)):
                raise ValueError("source native variable name or arity differs")
            native_indices.add(index)
            native_levels.add(level)
        else:
            raise ValueError("source relation contains a non-native/non-atom variable")
    if set(atom_levels) != dynamic_set:
        raise ValueError("source relation atom variables differ from dynamic partition")

    node_rows = {row["id"]: row for row in relation["nodes"]}
    root = relation["roots"]["definitelyAcceptedAtomImage"]
    reachable_levels = set()
    seen = set()
    stack = [root]
    while stack:
        node_id = stack.pop()
        if node_id in ("F", "T") or node_id in seen:
            continue
        seen.add(node_id)
        row = node_rows[node_id]
        reachable_levels.add(row["level"])
        stack.extend(row["children"])
    if reachable_levels & native_levels:
        raise ValueError("source atom-image root retains a native variable")
    expected_levels = {atom_levels[token] for token in dynamic}
    accepted = counts.get("definitelyAcceptedNativeAssignments")
    if accepted == "0":
        if root != "F":
            raise ValueError("zero-acceptance source atom image is not empty")
    elif root == "F" or reachable_levels != expected_levels:
        raise ValueError("source atom-image root support differs from dynamic atoms")


def _import_root(manager, artifact, root_name, level_map=None):
    """Rebuild one validated content-addressed root in ``manager``."""
    nodes = {row["id"]: row for row in artifact["nodes"]}
    root_id = artifact["roots"][root_name]
    handles = {"F": manager.false, "T": manager.true}
    stack = [(root_id, False)]
    while stack:
        node_id, expanded = stack.pop()
        if node_id in handles:
            continue
        row = nodes[node_id]
        if not expanded:
            stack.append((node_id, True))
            stack.extend((child, False) for child in row["children"]
                         if child not in handles)
            continue
        level = row["level"] if level_map is None else level_map[row["level"]]
        handles[node_id] = manager.node(
            level, [handles[child] for child in row["children"]])
    return handles[root_id]


def _or(manager, roots):
    result = manager.false
    for root in roots:
        result = manager.or_(result, root)
    return result


def _and(manager, roots):
    result = manager.true
    for root in roots:
        result = manager.and_(result, root)
    return result


def _coordinate_groups(source, coordinate):
    groups = {}
    for token, atom in source["atomDictionary"].items():
        if atom["coordinate"] != coordinate:
            continue
        value_id = digest(atom["value"])
        group = groups.setdefault(value_id, {"value": atom["value"],
                                             "occurrences": []})
        if group["value"] != atom["value"]:
            raise ValueError("canonical coordinate SHA-256 collision")
        group["occurrences"].append({
            "key": (atom["sourceDomain"], atom["ordinal"]), "token": token})
    for group in groups.values():
        group["occurrences"].sort(key=lambda row: (row["key"], row["token"]))
    return dict(sorted(groups.items()))


class _Formula:
    def __init__(self, manager, source, variable_levels):
        self.manager = manager
        self.static = frozenset(source["alwaysPresentAtoms"])
        self.levels = variable_levels

    def bit(self, token):
        if token in self.static:
            return self.manager.true
        level = self.levels.get(token)
        return self.manager.false if level is None else self.manager.literal(level, (1,))

    def presence(self, group):
        return _or(self.manager, (self.bit(row["token"])
                                  for row in group["occurrences"]))

    def firsts(self, group):
        previous = self.manager.false
        result = []
        for row in group["occurrences"]:
            active = self.bit(row["token"])
            first = self.manager.and_(active, self.manager.not_(previous))
            result.append((row["key"], first))
            previous = self.manager.or_(previous, active)
        return result

    def before(self, left, right):
        left_firsts = self.firsts(left)
        right_firsts = self.firsts(right)
        terms = []
        for left_key, left_first in left_firsts:
            for right_key, right_first in right_firsts:
                if left_key < right_key:
                    terms.append(self.manager.and_(left_first, right_first))
        return _or(self.manager, terms)

    def precedence(self, left, right):
        """Build both directions while sharing first-occurrence work."""
        left_firsts = self.firsts(left)
        right_firsts = self.firsts(right)
        left_terms = []
        right_terms = []
        for left_key, left_first in left_firsts:
            for right_key, right_first in right_firsts:
                term = self.manager.and_(left_first, right_first)
                (left_terms if left_key < right_key else right_terms).append(term)
        return _or(self.manager, left_terms), _or(self.manager, right_terms)


def _classify(source, relation, max_nodes, max_apply_pairs,
              max_coordinate_pairs, max_occurrence_comparisons):
    variables = _variables(relation)
    manager = MDDManager(variables, max_nodes=max_nodes,
                         max_apply_pairs=max_apply_pairs)
    root = _import_root(manager, relation, "definitelyAcceptedAtomImage")
    levels = {variable.name[5:]: index for index, variable in enumerate(variables)
              if variable.name.startswith("atom:")}
    formula = _Formula(manager, source, levels)
    coordinates = {}
    peak = len(manager._nodes)
    coordinate_pairs = 0
    occurrence_comparisons = 0

    def charge_pairs(amount=1):
        nonlocal coordinate_pairs
        coordinate_pairs += amount
        if coordinate_pairs > max_coordinate_pairs:
            raise MDDResourceLimitError("canonical coordinate-pair budget exhausted")

    def charge_occurrences(amount):
        nonlocal occurrence_comparisons
        occurrence_comparisons += amount
        if occurrence_comparisons > max_occurrence_comparisons:
            raise MDDResourceLimitError(
                "canonical occurrence-comparison budget exhausted")

    def possible(predicate):
        nonlocal peak
        candidate = manager.and_(root, predicate)
        peak = max(peak, len(manager._nodes))
        answer = not manager.is_empty(candidate)
        manager.compact(root)
        return answer

    for coordinate in ("actions", "geometry"):
        groups = _coordinate_groups(source, coordinate)
        values = {}
        for value_id, group in groups.items():
            charge_occurrences(2 * len(group["occurrences"]))
            present = possible(formula.presence(group))
            absent = possible(manager.not_(formula.presence(group)))
            state = "DYNAMIC" if present and absent else \
                "ALWAYS_PRESENT" if present else "NEVER_PRESENT"
            values[value_id] = {"value": group["value"], "presence": state,
                                "occurrences": [list(row["key"])
                                                for row in group["occurrences"]]}
        pairs = {}
        value_ids = tuple(groups)
        for offset, left_id in enumerate(value_ids):
            left = groups[left_id]
            left_keys = [row["key"] for row in left["occurrences"]]
            for right_id in value_ids[offset + 1:]:
                charge_pairs()
                right = groups[right_id]
                right_keys = [row["key"] for row in right["occurrences"]]
                pair_id = left_id + ":" + right_id
                if max(left_keys) < min(right_keys):
                    status = "FIXED_LEFT_BEFORE_RIGHT"
                elif max(right_keys) < min(left_keys):
                    status = "FIXED_RIGHT_BEFORE_LEFT"
                else:
                    charge_occurrences(len(left_keys) + len(right_keys) +
                                       2 * len(left_keys) * len(right_keys))
                    left_before_root, right_before_root = formula.precedence(left, right)
                    left_before = not manager.is_empty(
                        manager.and_(root, left_before_root))
                    right_before = not manager.is_empty(
                        manager.and_(root, right_before_root))
                    peak = max(peak, len(manager._nodes))
                    manager.compact(root)
                    status = ("DYNAMIC" if left_before and right_before else
                              "FIXED_LEFT_BEFORE_RIGHT" if left_before else
                              "FIXED_RIGHT_BEFORE_LEFT" if right_before else
                              "NEVER_COOCCURS")
                pairs[pair_id] = {"left": left_id, "right": right_id,
                                  "status": status}
        coordinates[coordinate] = {"values": values, "pairs": pairs}
    return coordinates, peak, coordinate_pairs, occurrence_comparisons


def _canonical_variables(source, relation, coordinates):
    source_variables = _variables(relation)
    atom_levels = {variable.name[5:]: index
                   for index, variable in enumerate(source_variables)
                   if variable.name.startswith("atom:")}
    additions = {level: [] for level in range(len(source_variables))}
    groups_by_coordinate = {coordinate: _coordinate_groups(source, coordinate)
                            for coordinate in ("actions", "geometry")}
    def schedule(name, variable, tokens):
        levels = [atom_levels[token] for token in tokens if token in atom_levels]
        if not levels:
            raise AssertionError("dynamic canonical coordinate has no source bit")
        additions[min(levels)].append((name, variable))

    for coordinate in ("actions", "geometry"):
        spec = coordinates[coordinate]
        groups = groups_by_coordinate[coordinate]
        for value_id, row in spec["values"].items():
            if row["presence"] == "DYNAMIC":
                name = "canonical:%s:presence:%s" % (coordinate, value_id)
                schedule(name, Variable(name, ("0", "1")),
                         [item["token"] for item in groups[value_id]["occurrences"]])
        for pair_id, row in spec["pairs"].items():
            if row["status"] == "DYNAMIC":
                name = "canonical:%s:precedence:%s" % (coordinate, pair_id)
                tokens = [item["token"] for value_id in (row["left"], row["right"])
                          for item in groups[value_id]["occurrences"]]
                schedule(name, Variable(
                    name, ("NOT_BOTH", "LEFT_BEFORE_RIGHT", "RIGHT_BEFORE_LEFT")),
                    tokens)
    variables = []
    output_levels = {}
    level_map = {}
    for old_level, variable in enumerate(source_variables):
        level_map[old_level] = len(variables)
        variables.append(variable)
        for name, added in sorted(additions[old_level]):
            output_levels[name] = len(variables)
            variables.append(added)
    return tuple(variables), output_levels, level_map


def _equivalence(manager, predicate, level):
    present = manager.literal(level, (1,))
    return manager.or_(manager.and_(predicate, present),
                       manager.and_(manager.not_(predicate), manager.not_(present)))


def _build_quotient(source, relation, coordinates, max_nodes, max_apply_pairs):
    variables, output_levels, level_map = _canonical_variables(
        source, relation, coordinates)
    manager = MDDManager(variables, max_nodes=max_nodes,
                         max_apply_pairs=max_apply_pairs)
    root = _import_root(manager, relation, "definitelyAcceptedAtomImage", level_map)
    source_variables = _variables(relation)
    atom_levels = {variable.name[5:]: level_map[index]
                   for index, variable in enumerate(source_variables)
                   if variable.name.startswith("atom:")}
    formula = _Formula(manager, source, atom_levels)
    sequence_tokens = set()
    usage = {"peakLiveNodes": len(manager._nodes), "compactions": 0,
             "nodesReclaimed": 0}

    def compact():
        usage["peakLiveNodes"] = max(usage["peakLiveNodes"], len(manager._nodes))
        usage["nodesReclaimed"] += manager.compact(root)
        usage["compactions"] += 1

    for coordinate in ("actions", "geometry"):
        groups = _coordinate_groups(source, coordinate)
        sequence_tokens.update(row["token"] for group in groups.values()
                               for row in group["occurrences"])
        spec = coordinates[coordinate]
        for value_id, row in spec["values"].items():
            if row["presence"] != "DYNAMIC":
                continue
            name = "canonical:%s:presence:%s" % (coordinate, value_id)
            root = manager.and_(root, _equivalence(
                manager, formula.presence(groups[value_id]), output_levels[name]))
            compact()
        for pair_id, row in spec["pairs"].items():
            if row["status"] != "DYNAMIC":
                continue
            left = groups[row["left"]]
            right = groups[row["right"]]
            left_before, right_before = formula.precedence(left, right)
            both = manager.and_(formula.presence(left), formula.presence(right))
            neither = manager.not_(both)
            name = "canonical:%s:precedence:%s" % (coordinate, pair_id)
            level = output_levels[name]
            constraint = _or(manager, (
                manager.and_(neither, manager.literal(level, (0,))),
                manager.and_(left_before, manager.literal(level, (1,))),
                manager.and_(right_before, manager.literal(level, (2,)))))
            root = manager.and_(root, constraint)
            compact()

    eliminated = sorted(atom_levels[token] for token in sequence_tokens
                        if token in atom_levels)
    root = manager.exists(root, eliminated)
    compact()

    # Rebase to the exact live dictionary. This removes native variables and
    # projected provenance bits instead of leaving count-multiplying don't-cares.
    used = set()
    visited = set()
    stack = [root]
    while stack:
        current = stack.pop()
        if current in (manager.false, manager.true) or current in visited:
            continue
        visited.add(current)
        node = manager._nodes[current]
        if node.level not in used:
            used.add(node.level)
        stack.extend(node.children)
    kept = sorted(used)
    narrowed = MDDManager([variables[level] for level in kept],
                          max_nodes=max_nodes, max_apply_pairs=max_apply_pairs)
    level_map = {old: new for new, old in enumerate(kept)}
    handles = {manager.false: narrowed.false, manager.true: narrowed.true}
    stack = [(root, False)]
    while stack:
        current, expanded = stack.pop()
        if current in handles:
            continue
        node = manager._nodes[current]
        if not expanded:
            stack.append((current, True))
            stack.extend((child, False) for child in node.children
                         if child not in handles)
            continue
        handles[current] = narrowed.node(
            level_map[node.level], [handles[child] for child in node.children])
    narrowed_root = handles[root]
    usage["totalNodesCreated"] = manager._next - 2
    usage["liveNodesBeforeRebase"] = len(manager._nodes)
    usage["liveNodes"] = len(narrowed._nodes)
    return narrowed, narrowed_root, usage


def _blocked(source, source_sha, budgets, blocker, phase, usage=None):
    return {"schema": SCHEMA, "status": "BLOCKED",
            "diagnosticStatus": "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
            "claimScope": "DIAGNOSTIC_CANONICAL_TYPED_PHYSICAL_IMAGE_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "sourceAtomImageSha256": source_sha,
            "modelSha256": source.get("modelSha256"),
            "blockers": [blocker], "resourcePhase": phase,
            "resourceUsage": usage, "budgets": budgets}


def build(atom_image_path, *, max_nodes=250_000, max_apply_pairs=1_000_000,
          max_decoded_bytes=512 * 1024 * 1024,
          max_coordinate_pairs=100_000,
          max_occurrence_comparisons=1_000_000):
    for name, value in (("max_nodes", max_nodes),
                        ("max_apply_pairs", max_apply_pairs),
                        ("max_decoded_bytes", max_decoded_bytes),
                        ("max_coordinate_pairs", max_coordinate_pairs),
                        ("max_occurrence_comparisons", max_occurrence_comparisons)):
        if type(value) is not int or value < 1:
            raise ValueError(name + " must be a positive integer")
    budgets = {"maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
               "maxDecodedBytes": max_decoded_bytes,
               "maxCoordinatePairs": max_coordinate_pairs,
               "maxOccurrenceComparisons": max_occurrence_comparisons}
    source, source_sha = _read_gzip_json(atom_image_path, max_decoded_bytes)
    if (source.get("schema") != SOURCE_SCHEMA or
            source.get("diagnosticStatus") != "DIAGNOSTIC_COMPLETE" or
            source.get("status") != "BLOCKED"):
        raise ValueError("source atom image is not a complete blocked diagnostic")
    relation = source.get("relation")
    validate_artifact(relation)
    _validate_source_binding(source, relation)
    phase = "CANONICAL_COORDINATE_CLASSIFICATION"
    try:
        coordinates, classification_peak, coordinate_pairs, occurrence_comparisons = \
            _classify(source, relation, max_nodes, max_apply_pairs,
                      max_coordinate_pairs, max_occurrence_comparisons)
        phase = "ORDERED_DEDUP_QUOTIENT"
        manager, root, usage = _build_quotient(
            source, relation, coordinates, max_nodes, max_apply_pairs)
        usage["classificationPeakLiveNodes"] = classification_peak
        usage["coordinatePairsClassified"] = coordinate_pairs
        usage["occurrenceComparisons"] = occurrence_comparisons
        output_relation = manager.to_artifact({"canonicalPhysicalImage": root})
    except MDDResourceLimitError as error:
        return _blocked(source, source_sha, budgets,
                        str(error).upper().replace(" ", "_"), phase)

    nonsequence = sorted(token for token, atom in source["atomDictionary"].items()
                         if atom["coordinate"] not in ("actions", "geometry"))
    return {"schema": SCHEMA, "status": "BLOCKED",
            "diagnosticStatus": "DIAGNOSTIC_COMPLETE",
            "claimScope": "DIAGNOSTIC_CANONICAL_TYPED_PHYSICAL_IMAGE_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "blockers": ["INDEPENDENT_TYPED_PROJECTION_SEMANTIC_BINDING_MISSING"],
            "cell": source.get("cell"), "modelSha256": source.get("modelSha256"),
            "sourceAtomImageSha256": source_sha,
            "counts": {"uniqueCanonicalPhysicalPlans": str(manager.count(root)),
                       "actionValues": str(len(coordinates["actions"]["values"])),
                       "geometryValues": str(len(coordinates["geometry"]["values"])),
                       "dynamicPrecedenceVariables": str(sum(
                           row["status"] == "DYNAMIC"
                           for spec in coordinates.values()
                           for row in spec["pairs"].values()))},
            "staticCoordinates": source["staticCoordinates"],
            "atomDictionary": {token: source["atomDictionary"][token]
                               for token in nonsequence},
            "alwaysPresentAtoms": sorted(set(source["alwaysPresentAtoms"]) &
                                         set(nonsequence)),
            "canonicalCoordinates": coordinates,
            "rootCommitments": output_relation["roots"],
            "relation": output_relation, "resourceUsage": usage,
            "budgets": budgets}


def _coordinate_sequence(result, coordinate, values):
    spec = result["canonicalCoordinates"][coordinate]
    present = []
    for value_id, row in spec["values"].items():
        state = row["presence"]
        if state == "ALWAYS_PRESENT" or (state == "DYNAMIC" and
                values["canonical:%s:presence:%s" % (coordinate, value_id)] == 1):
            present.append(value_id)

    def compare(left, right):
        pair_id = min(left, right) + ":" + max(left, right)
        row = spec["pairs"][pair_id]
        status = row["status"]
        if status == "DYNAMIC":
            name = "canonical:%s:precedence:%s" % (coordinate, pair_id)
            status = ("FIXED_LEFT_BEFORE_RIGHT" if values[name] == 1 else
                      "FIXED_RIGHT_BEFORE_LEFT" if values[name] == 2 else
                      "NEVER_COOCCURS")
        if status == "NEVER_COOCCURS":
            raise ValueError("canonical assignment contains a forbidden value pair")
        left_id, right_id = row["left"], row["right"]
        left_first = status == "FIXED_LEFT_BEFORE_RIGHT"
        return -1 if ((left == left_id) == left_first) else 1

    present.sort(key=cmp_to_key(compare))
    return [spec["values"][value_id]["value"] for value_id in present]


def materialize_canonical(result, assignment):
    variables = result["relation"]["variables"]
    if len(assignment) != len(variables):
        raise ValueError("canonical assignment has wrong width")
    values = {row["name"]: value for row, value in zip(variables, assignment)}
    active = {name[5:] for name, value in values.items()
              if name.startswith("atom:") and value == 1}
    shell = {"atomDictionary": result["atomDictionary"],
             "alwaysPresentAtoms": result["alwaysPresentAtoms"],
             "staticCoordinates": result["staticCoordinates"]}
    plan = materialize_atoms(shell, active)
    plan["actions"] = _coordinate_sequence(result, "actions", values)
    plan["geometry"] = _coordinate_sequence(result, "geometry", values)
    return plan


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as raw:
        temporary = Path(raw.name)
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as stream:
            stream.write(canonical(value) + b"\n")
    temporary.replace(path)


def verify_saved(artifact_path, atom_image_path, expected_sha256, *,
                 max_decoded_bytes=512 * 1024 * 1024):
    if (not isinstance(expected_sha256, str) or len(expected_sha256) != 64 or
            any(character not in "0123456789abcdef" for character in expected_sha256)):
        raise ValueError("an external lowercase SHA-256 commitment is required")
    result, actual_sha = _read_gzip_json(artifact_path, max_decoded_bytes)
    if actual_sha != expected_sha256:
        raise ValueError("canonical quotient differs from expected SHA-256")
    budgets = result.get("budgets")
    if not isinstance(budgets, dict) or set(budgets) != {
            "maxNodes", "maxApplyPairs", "maxDecodedBytes",
            "maxCoordinatePairs", "maxOccurrenceComparisons"}:
        raise ValueError("canonical quotient budgets are incomplete")
    rebuilt = build(atom_image_path, max_nodes=budgets["maxNodes"],
                    max_apply_pairs=budgets["maxApplyPairs"],
                    max_decoded_bytes=budgets["maxDecodedBytes"],
                    max_coordinate_pairs=budgets["maxCoordinatePairs"],
                    max_occurrence_comparisons=budgets[
                        "maxOccurrenceComparisons"])
    if canonical(result) != canonical(rebuilt):
        raise ValueError("canonical quotient differs from source-bound rebuild")
    if "relation" in result:
        validate_artifact(result["relation"],
                          expected_variables=_variables(rebuilt["relation"]),
                          expected_roots=rebuilt["relation"]["roots"])
    return {"schema": "canonical-e-quotient-verification-v1", "status": "PASS",
            "claimScope": "SOURCE_BOUND_DIAGNOSTIC_INTEGRITY_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "artifactSha256": expected_sha256,
            "sourceAtomImageSha256": result["sourceAtomImageSha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--atom-image", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--max-nodes", type=int, default=250_000)
    parser.add_argument("--max-apply-pairs", type=int, default=1_000_000)
    parser.add_argument("--max-decoded-bytes", type=int,
                        default=512 * 1024 * 1024)
    parser.add_argument("--max-coordinate-pairs", type=int, default=100_000)
    parser.add_argument("--max-occurrence-comparisons", type=int, default=1_000_000)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    if args.verify:
        result = verify_saved(args.artifact, args.atom_image,
                              args.expected_sha256,
                              max_decoded_bytes=args.max_decoded_bytes)
    else:
        result = build(args.atom_image, max_nodes=args.max_nodes,
                       max_apply_pairs=args.max_apply_pairs,
                       max_decoded_bytes=args.max_decoded_bytes,
                       max_coordinate_pairs=args.max_coordinate_pairs,
                       max_occurrence_comparisons=args.max_occurrence_comparisons)
        publish(args.artifact, result)
    print(json.dumps({key: result.get(key) for key in
                      ("cell", "status", "diagnosticStatus", "counts")},
                     sort_keys=True))


if __name__ == "__main__":
    main()
