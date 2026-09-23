#!/usr/bin/env python3
"""Exact Boolean relations over finite categorical variables.

This module implements a reduced ordered multi-valued decision diagram
(ROMDD).  It deliberately has no knowledge of P, E, plans, or workloads: a
producer must first define an ordered finite variable dictionary and compile
its accepted relation into this representation.

Node handles are local to one :class:`MDDManager`.  Serialized artifacts use
content-addressed node identifiers and can be checked by the independent
``validate_artifact`` reader without constructing an ``MDDManager``.
"""

from dataclasses import dataclass
import hashlib
import json
from math import prod


SCHEMA = "boolean-romdd-relation-v1"
TERMINAL_FALSE = "F"
TERMINAL_TRUE = "T"


def _canonical(value):
    return b"".join(_canonical_chunks(value))


def _canonical_chunks(value):
    encoder = json.JSONEncoder(sort_keys=True, separators=(",", ":"),
                               ensure_ascii=False)
    for chunk in encoder.iterencode(value):
        yield chunk.encode("utf-8")


def _digest(value):
    digest = hashlib.sha256()
    for chunk in _canonical_chunks(value):
        digest.update(chunk)
    return digest.hexdigest()


class MDDResourceLimitError(RuntimeError):
    """A configured exact-construction budget was exhausted."""


@dataclass(frozen=True)
class Variable:
    """One ordered finite categorical variable."""

    name: str
    values: tuple

    def __init__(self, name, values):
        values = tuple(values)
        if not isinstance(name, str) or not name:
            raise ValueError("variable name must be a non-empty string")
        if not values or any(not isinstance(value, str) for value in values):
            raise ValueError("variable values must be non-empty strings")
        if len(values) != len(set(values)):
            raise ValueError("variable values must be unique")
        object.__setattr__(self, "name", name)
        object.__setattr__(self, "values", values)


@dataclass(frozen=True)
class _Node:
    level: int
    children: tuple


@dataclass(frozen=True)
class _Handle:
    """Manager-owned node reference; the owner token prevents cross-use."""

    owner: object
    index: int


def _variables_wire(variables):
    return [{"name": variable.name, "values": list(variable.values)}
            for variable in variables]


def _parse_variables(wire):
    if not isinstance(wire, list):
        raise ValueError("artifact variables must be a list")
    variables = []
    for entry in wire:
        if not isinstance(entry, dict) or set(entry) != {"name", "values"}:
            raise ValueError("artifact variable dictionary has invalid shape")
        variables.append(Variable(entry["name"], entry["values"]))
    if len({variable.name for variable in variables}) != len(variables):
        raise ValueError("variable names must be unique")
    return tuple(variables)


class MDDManager:
    """Canonical ROMDD manager for one fixed variable order."""

    def __init__(self, variables, max_nodes=None, max_apply_pairs=None):
        self.variables = tuple(variable if isinstance(variable, Variable)
                               else Variable(*variable) for variable in variables)
        if len({variable.name for variable in self.variables}) != len(self.variables):
            raise ValueError("variable names must be unique")
        for label, limit in (("max_nodes", max_nodes),
                             ("max_apply_pairs", max_apply_pairs)):
            if limit is not None and (type(limit) is not int or limit < 0):
                raise ValueError(label + " must be a non-negative integer or None")
        self.max_nodes = max_nodes
        self.max_apply_pairs = max_apply_pairs
        self._owner = object()
        self.false = _Handle(self._owner, 0)
        self.true = _Handle(self._owner, 1)
        self._nodes = {}
        self._unique = {}
        self._next = 2
        self._apply_cache = {}
        self._not_cache = {self.false: self.true, self.true: self.false}

    def _check_root(self, root):
        if (not isinstance(root, _Handle) or root.owner is not self._owner or
                (root not in (self.false, self.true) and root not in self._nodes)):
            raise ValueError("node handle does not belong to this manager")

    def _level(self, root):
        return (len(self.variables) if root in (self.false, self.true)
                else self._nodes[root].level)

    def node(self, level, children):
        """Return the unique reduced node for ``(level, children)``."""
        children = tuple(children)
        if type(level) is not int or level < 0 or level >= len(self.variables):
            raise ValueError("node level is outside the variable dictionary")
        if len(children) != len(self.variables[level].values):
            raise ValueError("node arity does not match its variable")
        for child in children:
            self._check_root(child)
            if self._level(child) <= level:
                raise ValueError("child violates the ordered variable invariant")
        if len(set(children)) == 1:
            return children[0]
        key = (level, children)
        existing = self._unique.get(key)
        if existing is not None:
            return existing
        if self.max_nodes is not None and len(self._nodes) >= self.max_nodes:
            raise MDDResourceLimitError("MDD nonterminal node budget exhausted")
        result = _Handle(self._owner, self._next)
        self._next += 1
        self._nodes[result] = _Node(level, children)
        self._unique[key] = result
        return result

    def literal(self, level, accepted_values):
        """Build a predicate accepting the supplied category indices."""
        if type(level) is not int or level < 0 or level >= len(self.variables):
            raise ValueError("literal level is outside the variable dictionary")
        accepted = frozenset(accepted_values)
        radix = len(self.variables[level].values)
        if any(type(value) is not int or value < 0 or value >= radix
               for value in accepted):
            raise ValueError("literal category index is outside its variable")
        return self.node(level, tuple(self.true if value in accepted else self.false
                                      for value in range(radix)))

    def cube(self, assignment):
        """Build one (possibly partial) assignment.

        ``assignment`` maps variable indices to category indices.  Omitted
        variables are unconstrained.
        """
        if not isinstance(assignment, dict):
            assignment = dict(enumerate(assignment))
        if any(type(level) is not int for level in assignment):
            raise ValueError("cube variable levels must be integers")
        result = self.true
        for level in sorted(assignment, reverse=True):
            value = assignment[level]
            if (type(level) is not int or level < 0 or level >= len(self.variables) or
                    type(value) is not int or value < 0 or
                    value >= len(self.variables[level].values)):
                raise ValueError("cube assignment is outside the variable dictionary")
            children = [self.false] * len(self.variables[level].values)
            children[value] = result
            result = self.node(level, children)
        return result

    def from_assignments(self, assignments):
        result = self.false
        for assignment in assignments:
            result = self.or_(result, self.cube(assignment))
        return result

    def from_predicate(self, predicate):
        assignment = [0] * len(self.variables)

        def visit(level):
            if level == len(self.variables):
                return self.true if predicate(tuple(assignment)) else self.false
            children = []
            for value in range(len(self.variables[level].values)):
                assignment[level] = value
                children.append(visit(level + 1))
            return self.node(level, children)

        return visit(0)

    def from_factor(self, scope, truth):
        """Compile a dense mixed-radix Boolean factor.

        ``scope`` must be in manager order.  The last scoped variable changes
        fastest, matching ordinary Cartesian-product table order.
        """
        scope = tuple(scope)
        if (any(type(level) is not int for level in scope) or
                tuple(sorted(set(scope))) != scope or
                any(level < 0 or level >= len(self.variables) for level in scope)):
            raise ValueError("factor scope must contain unique increasing levels")
        truth = tuple(truth)
        expected = prod(len(self.variables[level].values) for level in scope)
        if len(truth) != expected or any(type(value) is not bool for value in truth):
            raise ValueError("factor truth table has invalid size or value")

        def visit(position, offset):
            if position == len(scope):
                return self.true if truth[offset] else self.false
            level = scope[position]
            stride = prod(len(self.variables[index].values)
                          for index in scope[position + 1:])
            return self.node(level, [visit(position + 1, offset + value * stride)
                                     for value in range(len(self.variables[level].values))])

        return visit(0, 0)

    def _cofactor(self, root, level, value):
        if root in (self.false, self.true) or self._nodes[root].level > level:
            return root
        node = self._nodes[root]
        if node.level != level:
            raise AssertionError("ordered MDD traversal skipped backwards")
        return node.children[value]

    def _apply(self, operation, left, right):
        self._check_root(left)
        self._check_root(right)
        if operation not in ("and", "or"):
            raise ValueError("unsupported Boolean operation")

        def normalize(a, b):
            return (b, a) if b.index < a.index else (a, b)

        def immediate(a, b):
            if operation == "and":
                if a == self.false or b == self.false:
                    return self.false
                if a == self.true:
                    return b
                if b == self.true or a == b:
                    return a
            else:
                if a == self.true or b == self.true:
                    return self.true
                if a == self.false:
                    return b
                if b == self.false or a == b:
                    return a
            return None

        def remember(key, result):
            if key not in self._apply_cache:
                if (self.max_apply_pairs is not None and
                        len(self._apply_cache) >= self.max_apply_pairs):
                    raise MDDResourceLimitError("MDD Boolean apply-pair budget exhausted")
                self._apply_cache[key] = result

        left, right = normalize(left, right)
        direct = immediate(left, right)
        if direct is not None:
            return direct
        root_key = (operation, left, right)
        stack = [(left, right, False)]
        while stack:
            current_left, current_right, expanded = stack.pop()
            current_left, current_right = normalize(current_left, current_right)
            key = (operation, current_left, current_right)
            if key in self._apply_cache:
                continue
            direct = immediate(current_left, current_right)
            if direct is not None:
                remember(key, direct)
                continue
            level = min(self._level(current_left), self._level(current_right))
            pairs = [normalize(self._cofactor(current_left, level, value),
                               self._cofactor(current_right, level, value))
                     for value in range(len(self.variables[level].values))]
            if not expanded:
                stack.append((current_left, current_right, True))
                for child_left, child_right in pairs:
                    child_key = (operation, child_left, child_right)
                    if (child_key not in self._apply_cache and
                            immediate(child_left, child_right) is None):
                        stack.append((child_left, child_right, False))
                continue
            children = []
            for child_left, child_right in pairs:
                child_key = (operation, child_left, child_right)
                child = self._apply_cache.get(child_key)
                if child is None:
                    child = immediate(child_left, child_right)
                if child is None:
                    raise AssertionError("iterative Boolean apply missed a dependency")
                children.append(child)
            remember(key, self.node(level, children))
        return self._apply_cache[root_key]

    def and_(self, left, right):
        return self._apply("and", left, right)

    def or_(self, left, right):
        return self._apply("or", left, right)

    def not_(self, root):
        self._check_root(root)
        stack = [(root, False)]
        while stack:
            current, expanded = stack.pop()
            if current in self._not_cache:
                continue
            node = self._nodes[current]
            if not expanded:
                stack.append((current, True))
                stack.extend((child, False) for child in node.children
                             if child not in self._not_cache)
                continue
            result = self.node(node.level,
                               [self._not_cache[child] for child in node.children])
            self._not_cache[current] = result
            self._not_cache[result] = current
        return self._not_cache[root]

    def exists(self, root, levels):
        """Existentially eliminate variables while retaining manager order."""
        self._check_root(root)
        levels = frozenset(levels)
        if any(type(level) is not int or level < 0 or level >= len(self.variables)
               for level in levels):
            raise ValueError("elimination level is outside the variable dictionary")
        cache = {self.false: self.false, self.true: self.true}
        stack = [(root, False)]
        while stack:
            current, expanded = stack.pop()
            if current in cache:
                continue
            node = self._nodes[current]
            if not expanded:
                stack.append((current, True))
                stack.extend((child, False) for child in node.children
                             if child not in cache)
                continue
            children = [cache[child] for child in node.children]
            if node.level in levels:
                result = self.false
                for child in children:
                    result = self.or_(result, child)
            else:
                result = self.node(node.level, children)
            cache[current] = result
        return cache[root]

    def difference(self, left, right):
        """Return the exact directional difference ``left AND NOT right``."""
        return self.and_(left, self.not_(right))

    def directional_difference(self, left, right):
        """Explicitly named alias for :meth:`difference`."""
        return self.difference(left, right)

    def is_empty(self, root):
        self._check_root(root)
        return root == self.false

    def evaluate(self, root, assignment):
        self._check_root(root)
        if len(assignment) != len(self.variables):
            raise ValueError("evaluation assignment has wrong width")
        if any(type(value) is not int or value < 0 or
               value >= len(self.variables[level].values)
               for level, value in enumerate(assignment)):
            raise ValueError("evaluation category index is invalid")
        current = root
        while current not in (self.false, self.true):
            node = self._nodes[current]
            value = assignment[node.level]
            current = node.children[value]
        return current == self.true

    def witness(self, root):
        """Return the lexicographically first satisfying total assignment."""
        self._check_root(root)
        if root == self.false:
            return None
        assignment = [0] * len(self.variables)
        current = root
        while current != self.true:
            node = self._nodes[current]
            for value, child in enumerate(node.children):
                if child != self.false:
                    assignment[node.level] = value
                    current = child
                    break
            else:
                raise AssertionError("reduced MDD contains an empty nonterminal")
        return tuple(assignment)

    def count(self, root):
        """Count satisfying total assignments exactly."""
        self._check_root(root)
        suffix = [1] * (len(self.variables) + 1)
        for level in range(len(self.variables) - 1, -1, -1):
            suffix[level] = suffix[level + 1] * len(self.variables[level].values)
        if root == self.false:
            return 0
        if root == self.true:
            return suffix[0]
        cache = {}
        stack = [(root, False)]
        while stack:
            current, expanded = stack.pop()
            if current in (self.false, self.true) or current in cache:
                continue
            node = self._nodes[current]
            if not expanded:
                stack.append((current, True))
                stack.extend((child, False) for child in node.children
                             if child not in (self.false, self.true) and child not in cache)
                continue
            total = 0
            for child in node.children:
                if child == self.false:
                    continue
                if child == self.true:
                    total += suffix[node.level + 1]
                else:
                    child_level = self._nodes[child].level
                    total += cache[child] * (suffix[node.level + 1] // suffix[child_level])
            cache[current] = total
        root_level = self._nodes[root].level
        return cache[root] * (suffix[0] // suffix[root_level])

    def to_artifact(self, roots):
        """Serialize named roots with deterministic content-addressed nodes."""
        if not isinstance(roots, dict) or not roots or any(
                not isinstance(name, str) or not name for name in roots):
            raise ValueError("roots must be a non-empty string-keyed dictionary")
        for root in roots.values():
            self._check_root(root)
        identifiers = {self.false: TERMINAL_FALSE, self.true: TERMINAL_TRUE}
        for root in roots.values():
            stack = [(root, False)]
            while stack:
                current, expanded = stack.pop()
                if current in identifiers:
                    continue
                node = self._nodes[current]
                if not expanded:
                    stack.append((current, True))
                    stack.extend((child, False) for child in node.children
                                 if child not in identifiers)
                    continue
                children = [identifiers[child] for child in node.children]
                identifiers[current] = "n_" + _digest(
                    {"level": node.level, "children": children})
        root_wire = {name: identifiers[root] for name, root in sorted(roots.items())}
        nodes = []
        for root, node_id in identifiers.items():
            if root in (self.false, self.true):
                continue
            node = self._nodes[root]
            nodes.append({"id": node_id, "level": node.level,
                          "children": [identifiers[child] for child in node.children]})
        nodes.sort(key=lambda node: node["id"])
        payload = {"schema": SCHEMA, "variables": _variables_wire(self.variables),
                   "roots": root_wire, "nodes": nodes}
        return {**payload, "artifactSha256": _digest(payload)}


class ValidatedArtifact:
    """Independent, read-only evaluator for a validated serialized relation."""

    def __init__(self, variables, roots, nodes):
        self.variables = variables
        self.roots = roots
        self._nodes = nodes

    def evaluate(self, root_name, assignment):
        if root_name not in self.roots:
            raise ValueError("unknown artifact root")
        if len(assignment) != len(self.variables):
            raise ValueError("replay assignment has wrong width")
        if any(type(value) is not int or value < 0 or
               value >= len(self.variables[level].values)
               for level, value in enumerate(assignment)):
            raise ValueError("replay category index is invalid")
        current = self.roots[root_name]
        while current not in (TERMINAL_FALSE, TERMINAL_TRUE):
            level, children = self._nodes[current]
            value = assignment[level]
            current = children[value]
        return current == TERMINAL_TRUE


def validate_artifact(artifact, expected_variables=None, replay_cases=None,
                      expected_roots=None):
    """Independently validate structure, content IDs, digest, and replay cases.

    ``replay_cases`` maps every root name to an exhaustive iterable of
    ``(assignment, expected)`` over the complete Cartesian domain. Partial
    replay is rejected because it cannot rule out a consistently re-signed
    semantic substitution. For large relations, ``expected_roots`` must be a
    separately trusted mapping from root names to content IDs. A commitment
    copied from this artifact does not independently prove native semantics.
    """
    if not isinstance(artifact, dict) or set(artifact) != {
            "schema", "variables", "roots", "nodes", "artifactSha256"}:
        raise ValueError("artifact has invalid top-level shape")
    if artifact["schema"] != SCHEMA:
        raise ValueError("artifact schema is unsupported")
    if ((replay_cases is not None or expected_roots is not None) and
            expected_variables is None):
        raise ValueError("semantic certification requires expected_variables")
    payload = {key: artifact[key] for key in
               ("schema", "variables", "roots", "nodes")}
    if artifact["artifactSha256"] != _digest(payload):
        raise ValueError("artifact digest mismatch")
    variables = _parse_variables(artifact["variables"])
    if expected_variables is not None:
        expected = tuple(variable if isinstance(variable, Variable)
                         else Variable(*variable) for variable in expected_variables)
        if variables != expected:
            raise ValueError("artifact variable dictionary drift")
    roots = artifact["roots"]
    if (not isinstance(roots, dict) or not roots or
            any(not isinstance(name, str) or not name or not isinstance(value, str)
                for name, value in roots.items())):
        raise ValueError("artifact roots are invalid")
    if expected_roots is not None and roots != expected_roots:
        raise ValueError("artifact root commitment drift")
    node_rows = artifact["nodes"]
    if not isinstance(node_rows, list):
        raise ValueError("artifact nodes must be a list")
    if any(not isinstance(node, dict) or not isinstance(node.get("id"), str)
           for node in node_rows):
        raise ValueError("artifact node has invalid shape")
    if node_rows != sorted(node_rows, key=lambda node: node["id"]):
        raise ValueError("artifact nodes are not deterministically ordered")
    nodes = {}
    signatures = set()
    for row in node_rows:
        if not isinstance(row, dict) or set(row) != {"id", "level", "children"}:
            raise ValueError("artifact node has invalid shape")
        node_id, level, children = row["id"], row["level"], row["children"]
        if (not isinstance(node_id, str) or not node_id.startswith("n_") or
                type(level) is not int or level < 0 or level >= len(variables) or
                not isinstance(children, list) or
                len(children) != len(variables[level].values) or
                any(not isinstance(child, str) for child in children)):
            raise ValueError("artifact node is malformed")
        if node_id in nodes or len(set(children)) == 1:
            raise ValueError("artifact is not unique and reduced")
        signature = (level, tuple(children))
        if signature in signatures:
            raise ValueError("artifact contains duplicate structural nodes")
        if node_id != "n_" + _digest({"level": level, "children": children}):
            raise ValueError("artifact node content identifier mismatch")
        signatures.add(signature)
        nodes[node_id] = signature
    valid_ids = set(nodes) | {TERMINAL_FALSE, TERMINAL_TRUE}
    if any(root not in valid_ids for root in roots.values()):
        raise ValueError("artifact root is dangling")
    for node_id, (level, children) in nodes.items():
        for child in children:
            if child not in valid_ids:
                raise ValueError("artifact child is dangling")
            if child in nodes and nodes[child][0] <= level:
                raise ValueError("artifact violates ordered variable levels")
    reachable = set()
    stack = list(roots.values())
    while stack:
        current = stack.pop()
        if current in (TERMINAL_FALSE, TERMINAL_TRUE) or current in reachable:
            continue
        reachable.add(current)
        stack.extend(nodes[current][1])
    if reachable != set(nodes):
        raise ValueError("artifact contains unreachable nodes")
    result = ValidatedArtifact(variables, dict(roots), nodes)
    if replay_cases is not None:
        if not isinstance(replay_cases, dict) or set(replay_cases) != set(roots):
            raise ValueError("artifact replay must cover every root")
        domain_size = prod(len(variable.values) for variable in variables)
        for root_name, cases in replay_cases.items():
            seen = set()
            for assignment, expected in cases:
                assignment = tuple(assignment)
                if assignment in seen:
                    raise ValueError("artifact replay contains duplicate assignment")
                # evaluate validates width and every coordinate, including those
                # skipped by a root or a terminal relation.
                seen.add(assignment)
                if type(expected) is not bool or result.evaluate(root_name, assignment) != expected:
                    raise ValueError("artifact replay mismatch")
            if len(seen) != domain_size:
                raise ValueError("artifact replay coverage is incomplete")
    return result


def canonical_artifact_bytes(artifact):
    """Return the stable JSON byte representation used by artifact digests."""
    validate_artifact(artifact)
    return _canonical(artifact)


def write_canonical_artifact(artifact, stream):
    """Validate and stream canonical artifact bytes without one full JSON copy."""
    validate_artifact(artifact)
    for chunk in _canonical_chunks(artifact):
        stream.write(chunk)
