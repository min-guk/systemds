#!/usr/bin/env python3
"""Diagnostic compositional Boolean-MDD image of typed E physical atoms.

The compiler-owned typed projection is decomposed into reconstructible physical
atoms.  Each atom is tied to the native alternatives that produce it; hard E
factors are conjoined symbolically and native variables are eliminated.  The
construction path never enumerates accepted native assignments. Bounded
verification intentionally enumerates tiny models.

The typed projection is not independently bound to the Java projector.  Every
result therefore remains ``status=BLOCKED`` and cannot certify E or P/E equality.
Distinct provenance atom sets can materialize the same physical plan because
the reference decoder deduplicates actions and geometry.
"""

import argparse
import gzip
import hashlib
import heapq
from itertools import product
import json
from math import prod
from pathlib import Path
import tempfile

from boolean_mdd_relation import MDDManager, MDDResourceLimitError, Variable
from boolean_mdd_relation import validate_artifact
from cutset_conditioning import (condition_factor, condition_terms,
                                 cutset_assignments, normalize_cutset)
from exact_e_factor_count import count_relation, read_model
from exact_e_physical_relation import (factor_status, projection_contract,
                                       validate_identity, compose_physical_projection)
from terminal_aware_order import (TerminalOrderResourceLimitError,
                                  plan_terminal_aware_order)


SCHEMA = "compositional-e-atom-image-diagnostic-v1"


class CutsetStructureError(RuntimeError):
    """The requested cutset has no supported exact branch composition."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def _token(prefix, value, dictionary):
    token = prefix + digest(value)
    previous = dictionary.setdefault(token, value)
    if previous != value:
        raise ValueError("canonical atom SHA-256 collision")
    return token


def _ordered_union(rows):
    result = []
    seen = set()
    for row in rows:
        key = canonical(row)
        if key not in seen:
            seen.add(key)
            result.append(row)
    return result


def _merge_condition(*entries):
    result = {}
    for domain, alternative in entries:
        previous = result.setdefault(domain, alternative)
        if previous != alternative:
            return None
    return tuple(sorted(result.items()))


def _add_atom(atoms, terms, term_counter, atom, condition, max_atoms, max_terms):
    if condition is None:
        return
    token = _token("a_", atom, atoms)
    rows = terms.setdefault(token, [])
    if condition not in rows:
        rows.append(condition)
        term_counter[0] += 1
        if term_counter[0] > max_terms:
            raise MDDResourceLimitError("atom condition-term budget exhausted")
    if len(atoms) > max_atoms:
        raise MDDResourceLimitError("physical atom budget exhausted")


def compile_atoms(projection, max_atoms, max_terms):
    """Compile reconstructible atoms and their partial native assignments."""
    atoms = {}
    terms = {}
    term_counter = [0]
    variables = projection["variables"]
    node_position = {domain: position
                     for position, domain in enumerate(projection["nodeOrder"])}
    binding_position = {domain: position for position, domain in enumerate(
        projection["bindingDomainOrder"])}

    for domain, variable in enumerate(variables):
        for alternative, fragment in enumerate(variable["alternatives"]):
            own = _merge_condition((domain, alternative))
            if domain in node_position:
                _add_atom(atoms, terms, term_counter, {
                    "coordinate": "nodes", "position": node_position[domain],
                    "value": fragment["node"]}, own, max_atoms, max_terms)
            _add_atom(atoms, terms, term_counter, {
                "coordinate": "authority", "position": domain,
                "value": fragment["authority"]}, own, max_atoms, max_terms)
            for ordinal, row in enumerate(fragment["actions"]):
                _add_atom(atoms, terms, term_counter, {
                    "coordinate": "actions", "sourceDomain": domain,
                    "ordinal": ordinal, "value": row}, own, max_atoms, max_terms)
            for ordinal, row in enumerate(fragment["geometry"]):
                _add_atom(atoms, terms, term_counter, {
                    "coordinate": "geometry", "sourceDomain": domain,
                    "ordinal": ordinal, "value": row}, own, max_atoms, max_terms)

    for consumer_domain in projection["bindingDomainOrder"]:
        order = binding_position[consumer_domain]
        for consumer_alternative, fragment in enumerate(
                variables[consumer_domain]["alternatives"]):
            for ordinal, template in enumerate(fragment["bindings"]):
                base = {key: template[key]
                        for key in ("consumer", "inputPosition", "presence")}
                mode = template["mode"]
                consumer_choice = (consumer_domain, consumer_alternative)
                if mode == "PHI":
                    if "ftype" not in template:
                        raise ValueError("PHI atom lacks explicit FType")
                    header = dict(base, ftype=template["ftype"],
                                  producer={"kind": "PHI_JOIN_PORT",
                                            "owner": template["consumer"]},
                                  inputAuthority="PHI")
                    _add_atom(atoms, terms, term_counter, {
                        "coordinate": "bindings", "bindingOrder": order,
                        "ordinal": ordinal, "part": "PHI_HEADER", "value": header},
                        _merge_condition(consumer_choice), max_atoms, max_terms)
                    for producer_ordinal, producer_template in enumerate(
                            template["producerAlternatives"]):
                        producer_domain = producer_template["producerDomain"]
                        for producer_alternative, producer in enumerate(
                                variables[producer_domain]["alternatives"]):
                            value = {
                                "producer": producer["node"]["occurrence"],
                                "controlArm": producer_template["controlArm"],
                                "sourceAuthorityRef": producer["authority"]["id"],
                            }
                            _add_atom(atoms, terms, term_counter, {
                                "coordinate": "bindings", "bindingOrder": order,
                                "ordinal": ordinal, "part": "PHI_ALTERNATIVE",
                                "producerOrdinal": producer_ordinal, "value": value},
                                _merge_condition(
                                    consumer_choice,
                                    (producer_domain, producer_alternative)),
                                max_atoms, max_terms)
                    continue

                producer_domain = template["producerDomain"]
                for producer_alternative, producer in enumerate(
                        variables[producer_domain]["alternatives"]):
                    row = dict(base)
                    row["ftype"] = (template["ftype"] if "ftype" in template else
                                    producer["node"]["ftype"])
                    row["producer"] = producer["node"]["occurrence"]
                    row["sourceAuthorityRef"] = producer["authority"]["id"]
                    row["inputAuthority"] = (
                        "DIRECT_FOUT" if mode == "DIRECT_OR_FOUT" and
                        producer["node"]["output"] == "FOUT" else
                        "DIRECT" if mode == "DIRECT_OR_FOUT" else mode)
                    if mode == "RELOCATION":
                        row["actionRef"] = template["actionRef"]
                    _add_atom(atoms, terms, term_counter, {
                        "coordinate": "bindings", "bindingOrder": order,
                        "ordinal": ordinal, "part": "ROW", "value": row},
                        _merge_condition(
                            consumer_choice,
                            (producer_domain, producer_alternative)),
                        max_atoms, max_terms)
    return dict(sorted(atoms.items())), {
        token: sorted(rows) for token, rows in sorted(terms.items())}


def materialize_atoms(result, active_tokens):
    """Reconstruct one canonical physical row from an active atom set."""
    dictionary = result["atomDictionary"]
    active_tokens = set(active_tokens) | set(result.get("alwaysPresentAtoms", ()))
    if not active_tokens <= set(dictionary):
        raise ValueError("active atom token is absent from dictionary")
    active = [dictionary[token] for token in active_tokens]
    nodes = [row["value"] for row in sorted(
        (row for row in active if row["coordinate"] == "nodes"),
        key=lambda row: row["position"])]
    authority = [row["value"] for row in sorted(
        (row for row in active if row["coordinate"] == "authority"),
        key=lambda row: row["position"])]
    actions = _ordered_union(row["value"] for row in sorted(
        (row for row in active if row["coordinate"] == "actions"),
        key=lambda row: (row["sourceDomain"], row["ordinal"])))
    geometry = _ordered_union(row["value"] for row in sorted(
        (row for row in active if row["coordinate"] == "geometry"),
        key=lambda row: (row["sourceDomain"], row["ordinal"])))
    binding_atoms = [row for row in active if row["coordinate"] == "bindings"]
    bindings = []
    keys = sorted({(row["bindingOrder"], row["ordinal"])
                   for row in binding_atoms})
    for key in keys:
        group = [row for row in binding_atoms
                 if (row["bindingOrder"], row["ordinal"]) == key]
        direct = [row for row in group if row["part"] == "ROW"]
        headers = [row for row in group if row["part"] == "PHI_HEADER"]
        if len(direct) == 1 and not headers:
            bindings.append(direct[0]["value"])
        elif len(headers) == 1 and not direct:
            row = dict(headers[0]["value"])
            alternatives = sorted(
                (item for item in group if item["part"] == "PHI_ALTERNATIVE"),
                key=lambda item: item["producerOrdinal"])
            row["producerAlternatives"] = [item["value"] for item in alternatives]
            bindings.append(row)
        else:
            raise ValueError("active binding atoms are incomplete or ambiguous")
    static = result["staticCoordinates"]
    return {"schema": "physical-plan-v1",
            "context": {"logical": static["logicalProgram"]},
            "nodes": nodes, "authority": authority, "actions": actions,
            "bindings": bindings, "logicalInputs": static["logicalInputs"],
            "geometry": geometry}


def _normalized_factor_truth(scope, truth, radices):
    ordered = tuple(sorted(scope))
    values = []
    assignment = [0] * len(radices)
    for selected in product(*(range(radices[index]) for index in ordered)):
        for index, value in zip(ordered, selected):
            assignment[index] = value
        values.append(factor_status(scope, truth, assignment, radices) == "ALLOW")
    return ordered, values


def _reduced_terms(rows, radices):
    """Substitute singleton native domains in one atom's DNF."""
    reduced = set()
    for row in rows:
        term = []
        possible = True
        for domain, alternative in row:
            if radices[domain] == 1:
                possible = possible and alternative == 0
            else:
                term.append((domain, alternative))
        if possible:
            reduced.add(tuple(term))
    return tuple(sorted(reduced))


def _condition_is_total(rows, radices, max_cells):
    """Fold a tautology only when its scope fits the exact check budget.

    Declining this optimization keeps the output atom dynamic and is exact.
    """
    scope = tuple(sorted({domain for row in rows for domain, _ in row}))
    cells = prod(radices[domain] for domain in scope)
    # Each cell may inspect every DNF term. Bound the actual comparisons,
    # including the cost of visiting empty terms, rather than cells alone.
    checks_per_cell = sum(max(1, len(row)) for row in rows)
    if cells * checks_per_cell > max_cells:
        return False
    positions = {domain: position for position, domain in enumerate(scope)}
    return all(any(all(selected[positions[domain]] == alternative
                       for domain, alternative in row)
                   for row in rows)
               for selected in product(*(range(radices[domain]) for domain in scope)))


def _compile_native_factor(manager, scope, truth, radices, native_levels):
    """Compile a hard factor after substituting singleton domains."""
    varying = tuple(index for index in scope if radices[index] > 1)
    ordered = tuple(sorted(varying, key=native_levels.__getitem__))
    levels = tuple(native_levels[index] for index in ordered)
    values = []
    assignment = [0] * len(radices)
    for selected in product(*(range(radices[index]) for index in ordered)):
        for index, value in zip(ordered, selected):
            assignment[index] = value
        values.append(factor_status(scope, truth, assignment, radices) == "ALLOW")
    return manager.from_factor(levels, values)


def _compile_condition(manager, rows, native_levels):
    condition = manager.false
    for row in rows:
        condition = manager.or_(condition, manager.cube({
            native_levels[domain]: alternative for domain, alternative in row}))
    return condition


def _partitioned_image(manager, radices, tables, atoms, terms, native_levels,
                       atom_levels, elimination_order, max_nodes, progress=None):
    """Build the exact atom image by bucketed conjunction and early exists."""
    position = {domain: index for index, domain in enumerate(elimination_order)}
    buckets = [[] for _ in elimination_order]
    constant_constraints = []

    for scope, truth in tables:
        dependencies = [index for index in scope if radices[index] > 1]
        entry = ("factor", scope, truth)
        if dependencies:
            buckets[min(position[index] for index in dependencies)].append(entry)
        else:
            constant_constraints.append(entry)
    for token in atom_levels:
        dependencies = sorted({domain for row in terms[token]
                               for domain, _ in row})
        if not dependencies:
            raise AssertionError("dynamic atom condition has no native dependency")
        buckets[min(position[index] for index in dependencies)].append(
            ("atom", token, terms[token]))

    usage = {"peakLiveNodes": 0, "peakApplyPairsCached": 0,
             "compactions": 0, "nodesReclaimed": 0}
    progress = progress if progress is not None else {}
    threshold = min(120_000, max_nodes)

    def sample():
        usage["peakLiveNodes"] = max(usage["peakLiveNodes"], len(manager._nodes))
        usage["peakApplyPairsCached"] = max(
            usage["peakApplyPairsCached"], len(manager._apply_cache))

    def compact(root):
        sample()
        usage["nodesReclaimed"] += manager.compact(root)
        usage["compactions"] += 1
        sample()

    root = manager.true
    for _, scope, truth in constant_constraints:
        progress.update(stage=-1, itemKind="factor", itemKey=str(scope),
                        operation="COMPILE_CONSTANT")
        constraint = _compile_native_factor(
            manager, scope, truth, radices, native_levels)
        progress["operation"] = "CONJOIN_CONSTANT"
        root = manager.and_(root, constraint)
    compact(root)

    for stage, domain in enumerate(elimination_order):
        for kind, key, payload in sorted(
                buckets[stage], key=lambda row: (row[0], str(row[1]))):
            progress.update(stage=stage, nativeDomain=domain, itemKind=kind,
                            itemKey=str(key), operation="COMPILE")
            if kind == "factor":
                constraint = _compile_native_factor(
                    manager, key, payload, radices, native_levels)
            else:
                condition = _compile_condition(manager, payload, native_levels)
                present = manager.literal(atom_levels[key], (1,))
                constraint = manager.or_(manager.and_(condition, present),
                                         manager.and_(manager.not_(condition),
                                                      manager.not_(present)))
            progress["operation"] = "CONJOIN"
            root = manager.and_(root, constraint)
            sample()
            if len(manager._nodes) >= threshold:
                compact(root)
        progress.update(stage=stage, nativeDomain=domain,
                        itemKind="native", itemKey=str(domain), operation="ELIMINATE")
        root = manager.exists(root, (native_levels[domain],))
        compact(root)
    sample()
    usage["totalNodesCreated"] = manager._next - 2
    usage["liveNodes"] = len(manager._nodes)
    return manager, root, usage


def _native_components(radices, tables, terms, elimination_order):
    """Partition the exact native/output dependency graph into disjoint blocks."""
    varying = set(elimination_order)
    neighbors = {domain: set() for domain in varying}

    def connect(scope):
        scope = set(scope) & varying
        for domain in scope:
            neighbors[domain].update(scope - {domain})

    for scope, _ in tables:
        connect(scope)
    for rows in terms.values():
        connect(domain for row in rows for domain, _ in row)

    remaining = set(varying)
    groups = []
    position = {domain: index for index, domain in enumerate(elimination_order)}
    while remaining:
        pending = [min(remaining)]
        group = set()
        while pending:
            domain = pending.pop()
            if domain in group:
                continue
            group.add(domain)
            pending.extend(neighbors[domain] - group)
        remaining -= group
        groups.append(tuple(sorted(group, key=position.__getitem__)))
    groups.sort(key=lambda group: position[group[0]])
    ownership = {domain: index for index, group in enumerate(groups)
                 for domain in group}
    factor_groups = [[] for _ in groups]
    constant_factors = []
    for scope, truth in tables:
        dependencies = set(scope) & varying
        if not dependencies:
            constant_factors.append((scope, truth))
            continue
        owners = {ownership[domain] for domain in dependencies}
        if len(owners) != 1:
            raise AssertionError("hard factor crosses native components")
        factor_groups[owners.pop()].append((scope, truth))
    atom_groups = [{} for _ in groups]
    for token, rows in terms.items():
        dependencies = {domain for row in rows for domain, _ in row} & varying
        owners = {ownership[domain] for domain in dependencies}
        if len(owners) != 1:
            raise AssertionError("dynamic atom crosses native components")
        atom_groups[owners.pop()][token] = rows
    return groups, factor_groups, atom_groups, constant_factors


def _import_projected_root(source, root, target, atom_levels):
    """Copy an atom-only local root into a disjoint global atom dictionary."""
    handles = {source.false: target.false, source.true: target.true}
    stack = [(root, False)]
    while stack:
        current, expanded = stack.pop()
        if current in handles:
            continue
        node = source._nodes[current]
        if not expanded:
            stack.append((current, True))
            stack.extend((child, False) for child in node.children
                         if child not in handles)
            continue
        name = source.variables[node.level].name
        if not name.startswith("atom:") or name[5:] not in atom_levels:
            raise AssertionError("component image retained a native dependency")
        handles[current] = target.node(
            atom_levels[name[5:]], [handles[child] for child in node.children])
    return handles[root]


def _decomposed_image(radices, tables, atoms, terms, native_values,
                      elimination_order, max_nodes, max_apply_pairs, progress,
                      terminal_aware=False, max_order_operations=1_000_000,
                      max_order_scope_symbols=10_000):
    groups, factor_groups, atom_groups, constants = _native_components(
        radices, tables, terms, elimination_order)
    all_zero = [0] * len(radices)
    constants_allow = all(factor_status(scope, truth, all_zero, radices) == "ALLOW"
                          for scope, truth in constants)
    local_specs = []
    global_tokens = []
    order_plans = []
    for component, group in enumerate(groups):
        if terminal_aware:
            plan = plan_terminal_aware_order(
                radices,
                [tuple(domain for domain in scope if radices[domain] > 1)
                 for scope, _ in factor_groups[component]],
                {token: tuple(sorted({domain for row in rows
                                      for domain, _ in row}))
                 for token, rows in atom_groups[component].items()}, group,
                max_operations=max_order_operations,
                max_scope_symbols=max_order_scope_symbols)
            group = tuple(plan["order"])
            groups[component] = group
            order_plans.append(plan)
        positions = {domain: index for index, domain in enumerate(group)}
        after = {domain: [] for domain in group}
        for token, rows in atom_groups[component].items():
            dependencies = {domain for row in rows for domain, _ in row}
            first = min(dependencies, key=positions.__getitem__)
            after[first].append(token)
        variables = []
        native_levels = {}
        atom_levels = {}
        for domain in group:
            native_levels[domain] = len(variables)
            variables.append(Variable("native:%d" % domain, native_values[domain]))
            for token in sorted(after[domain]):
                atom_levels[token] = len(variables)
                variables.append(Variable("atom:" + token, ("0", "1")))
                global_tokens.append(token)
        local_specs.append((variables, native_levels, atom_levels))

    global_levels = {token: index for index, token in enumerate(global_tokens)}
    combined = MDDManager([Variable("atom:" + token, ("0", "1"))
                           for token in global_tokens], max_nodes=max_nodes,
                          max_apply_pairs=max_apply_pairs)
    root = combined.true if constants_allow else combined.false
    usage = {"components": len(groups), "maxComponentNative": max(
                 (len(group) for group in groups), default=0),
             "maxComponentAtoms": max((len(group) for group in atom_groups),
                                      default=0),
             "peakLiveNodes": 0, "peakApplyPairsCached": 0,
             "compactions": 0, "nodesReclaimed": 0,
             "totalNodesCreated": 0}
    schedules = []
    for component, group in enumerate(groups):
        variables, native_levels, atom_levels = local_specs[component]
        progress.update(component=component, componentNative=len(group),
                        componentAtoms=len(atom_levels))
        combined_before_nodes = len(combined._nodes)
        combined_before_pairs = len(combined._apply_cache)
        local = MDDManager(variables, max_nodes=max_nodes,
                           max_apply_pairs=max_apply_pairs)
        try:
            local, local_root, local_usage = _partitioned_image(
                local, radices, factor_groups[component], atoms,
                atom_groups[component], native_levels, atom_levels, group,
                max_nodes, progress)
        except MDDResourceLimitError:
            progress.update(localLiveNodes=len(local._nodes),
                            localApplyPairsCached=len(local._apply_cache),
                            combinedLiveNodes=combined_before_nodes,
                            combinedApplyPairsCached=combined_before_pairs)
            raise
        imported = _import_projected_root(local, local_root, combined,
                                          global_levels)
        root = combined.and_(root, imported)
        usage["peakLiveNodes"] = max(
            usage["peakLiveNodes"],
            combined_before_nodes + local_usage["peakLiveNodes"],
            len(local._nodes) + len(combined._nodes))
        usage["peakApplyPairsCached"] = max(
            usage["peakApplyPairsCached"],
            combined_before_pairs + local_usage["peakApplyPairsCached"],
            len(local._apply_cache) + len(combined._apply_cache))
        usage["compactions"] += local_usage["compactions"] + 1
        usage["nodesReclaimed"] += local_usage["nodesReclaimed"]
        usage["nodesReclaimed"] += combined.compact(root)
        usage["peakLiveNodes"] = max(
            usage["peakLiveNodes"], len(local._nodes) + len(combined._nodes))
        usage["peakApplyPairsCached"] = max(
            usage["peakApplyPairsCached"],
            len(local._apply_cache) + len(combined._apply_cache))
        usage["totalNodesCreated"] += local_usage["totalNodesCreated"]
        schedules.append({"nativeOrder": list(group),
                          "atomVariables": sorted(atom_groups[component]),
                          "hardFactors": len(factor_groups[component]),
                          "localLiveNodes": local_usage["liveNodes"]})
        if terminal_aware:
            schedules[-1]["terminalOrderOperations"] = \
                order_plans[component]["operations"]
            schedules[-1]["terminalOrderPeakScopeSymbols"] = \
                order_plans[component]["peakScopeSymbols"]
    usage["totalNodesCreated"] += combined._next - 2
    usage["liveNodes"] = len(combined._nodes)
    return combined, root, tuple(global_tokens), usage, schedules


def _cutset_conditioned_image(radices, tables, atoms, terms, native_values,
                              elimination_order, cutset, max_cutset_assignments,
                              max_nodes, max_apply_pairs, progress):
    """Build an exact atom image by conditioning an explicit native cutset."""
    cutset, assignment_count = normalize_cutset(
        cutset, radices, elimination_order, max_cutset_assignments)
    cutset_set = frozenset(cutset)
    residual_order = tuple(domain for domain in elimination_order
                           if domain not in cutset_set)

    conditioned_scopes = [
        (tuple(domain for domain in scope if domain not in cutset_set), ())
        for scope, _ in tables]
    branch_plans = []
    successors = {token: set() for token in terms}
    indegree = {token: 0 for token in terms}
    for fixed in cutset_assignments(cutset, radices):
        dynamic_terms = {}
        forced_true = []
        forced_false = []
        for token in sorted(terms):
            status, rows = condition_terms(terms[token], fixed)
            if status == "TRUE":
                forced_true.append(token)
            elif status == "FALSE":
                forced_false.append(token)
            else:
                dynamic_terms[token] = rows
        groups, _, atom_groups, _ = _native_components(
            radices, conditioned_scopes, dynamic_terms, residual_order)
        ownership = {domain: component
                     for component, group in enumerate(groups)
                     for domain in group}
        factor_group_indices = [[] for _ in groups]
        constant_factor_indices = []
        for index, (scope, _) in enumerate(conditioned_scopes):
            dependencies = {domain for domain in scope if radices[domain] > 1}
            if not dependencies:
                constant_factor_indices.append(index)
                continue
            owners = {ownership[domain] for domain in dependencies}
            if len(owners) != 1:
                raise AssertionError("conditioned hard factor crosses components")
            factor_group_indices[owners.pop()].append(index)
        component_orders = []
        branch_order = []
        for component, group in enumerate(groups):
            position = {domain: index for index, domain in enumerate(group)}
            after = {domain: [] for domain in group}
            for token, rows in atom_groups[component].items():
                dependencies = {domain for row in rows for domain, _ in row}
                if not dependencies <= set(group):
                    raise AssertionError("conditioned atom crosses components")
                after[min(dependencies, key=position.__getitem__)].append(token)
            ordered = []
            for domain in group:
                ordered.extend(sorted(after[domain]))
            component_orders.append((after, tuple(ordered)))
            branch_order.extend(ordered)
        for left, right in zip(branch_order, branch_order[1:]):
            if right not in successors[left]:
                successors[left].add(right)
                indegree[right] += 1
        branch_plans.append({
            "fixed": fixed, "terms": dynamic_terms,
            "forcedTrue": forced_true, "forcedFalse": forced_false,
            "groups": groups, "atomGroups": atom_groups,
            "factorGroupIndices": factor_group_indices,
            "constantFactorIndices": constant_factor_indices,
            "componentOrders": component_orders})
    ready = [token for token, degree in indegree.items() if degree == 0]
    heapq.heapify(ready)
    output_tokens = []
    while ready:
        token = heapq.heappop(ready)
        output_tokens.append(token)
        for following in sorted(successors[token]):
            indegree[following] -= 1
            if indegree[following] == 0:
                heapq.heappush(ready, following)
    if len(output_tokens) != len(terms):
        raise CutsetStructureError(
            "cutset branch atom orders have no common exact MDD order")
    output_tokens = tuple(output_tokens)
    global_levels = {token: index for index, token in enumerate(output_tokens)}
    combined = MDDManager(
        [Variable("atom:" + token, ("0", "1")) for token in output_tokens],
        max_nodes=max_nodes, max_apply_pairs=max_apply_pairs)
    image = combined.false
    usage = {"cutsetAssignments": assignment_count, "branchesCompleted": 0,
             "feasibleBranches": 0, "components": 0,
             "maxComponentNative": 0, "maxComponentAtoms": 0,
             "peakLiveNodes": 0, "peakApplyPairsCached": 0,
             "compactions": 0, "nodesReclaimed": 0,
             "totalNodesCreated": 0}
    schedules = []

    def sample(local=None):
        local_nodes = len(local._nodes) if local is not None else 0
        local_pairs = len(local._apply_cache) if local is not None else 0
        usage["peakLiveNodes"] = max(
            usage["peakLiveNodes"], len(combined._nodes) + local_nodes)
        usage["peakApplyPairsCached"] = max(
            usage["peakApplyPairsCached"],
            len(combined._apply_cache) + local_pairs)

    for branch_index, plan in enumerate(branch_plans):
        fixed = plan["fixed"]
        progress.update(branch=branch_index, cutsetAssignment=dict(sorted(fixed.items())),
                        operation="CONDITION")
        conditioned_tables = [condition_factor(scope, truth, fixed, radices)
                              for scope, truth in tables]
        groups = plan["groups"]
        conditioned_atom_groups = plan["atomGroups"]
        factor_groups = [[conditioned_tables[index] for index in indices]
                         for indices in plan["factorGroupIndices"]]
        constants = [conditioned_tables[index]
                     for index in plan["constantFactorIndices"]]
        forced_true = plan["forcedTrue"]
        forced_false = plan["forcedFalse"]
        if (sum(len(group) for group in factor_groups) + len(constants) !=
                len(conditioned_tables) or
                sum(len(group) for group in conditioned_atom_groups) +
                len(forced_true) + len(forced_false) != len(terms)):
            raise AssertionError("conditioned dependency coverage is incomplete")
        all_zero = [0] * len(radices)
        constants_allow = all(
            factor_status(scope, truth, all_zero, radices) == "ALLOW"
            for scope, truth in constants)
        branch = combined.true if constants_allow else combined.false
        for token in forced_true:
            branch = combined.and_(branch,
                                   combined.literal(global_levels[token], (1,)))
        for token in forced_false:
            branch = combined.and_(branch,
                                   combined.literal(global_levels[token], (0,)))

        component_schedules = []
        for component, group in enumerate(groups):
            after, structural_tokens = plan["componentOrders"][component]
            combined_before_nodes = len(combined._nodes)
            combined_before_pairs = len(combined._apply_cache)
            variables = []
            native_levels = {}
            atom_levels = {}
            for domain in group:
                native_levels[domain] = len(variables)
                variables.append(Variable("native:%d" % domain,
                                          native_values[domain]))
                for token in sorted(after[domain]):
                    if token in conditioned_atom_groups[component]:
                        atom_levels[token] = len(variables)
                    variables.append(Variable("atom:" + token, ("0", "1")))
            local = MDDManager(variables, max_nodes=max_nodes,
                               max_apply_pairs=max_apply_pairs)
            progress.update(component=component, componentNative=len(group),
                            componentAtoms=len(atom_levels),
                            operation="BUILD_COMPONENT")
            try:
                local, local_root, local_usage = _partitioned_image(
                    local, radices, factor_groups[component], atoms,
                    conditioned_atom_groups[component], native_levels,
                    atom_levels, group, max_nodes, progress)
            except MDDResourceLimitError:
                sample(local)
                progress.update(localLiveNodes=len(local._nodes),
                                localApplyPairsCached=len(local._apply_cache),
                                combinedLiveNodes=len(combined._nodes),
                                combinedApplyPairsCached=len(combined._apply_cache))
                raise
            imported = _import_projected_root(local, local_root, combined,
                                              global_levels)
            branch = combined.and_(branch, imported)
            sample(local)
            usage["peakLiveNodes"] = max(
                usage["peakLiveNodes"],
                combined_before_nodes + local_usage["peakLiveNodes"])
            usage["peakApplyPairsCached"] = max(
                usage["peakApplyPairsCached"],
                combined_before_pairs + local_usage["peakApplyPairsCached"])
            usage["components"] += 1
            usage["maxComponentNative"] = max(
                usage["maxComponentNative"], len(group))
            usage["maxComponentAtoms"] = max(
                usage["maxComponentAtoms"], len(atom_levels))
            usage["compactions"] += local_usage["compactions"]
            usage["nodesReclaimed"] += local_usage["nodesReclaimed"]
            usage["totalNodesCreated"] += local_usage["totalNodesCreated"]
            component_schedules.append({
                "nativeOrder": list(group),
                "structuralAtomVariables": list(structural_tokens),
                "dynamicAtomVariables": list(atom_levels),
                "hardFactors": len(factor_groups[component]),
                "localLiveNodes": local_usage["liveNodes"]})

        image = combined.or_(image, branch)
        sample()
        usage["nodesReclaimed"] += combined.compact(image)
        usage["compactions"] += 1
        usage["branchesCompleted"] += 1
        if branch != combined.false:
            usage["feasibleBranches"] += 1
        schedules.append({
            "assignment": [fixed[domain] for domain in cutset],
            "constantHardFactors": len(constants),
            "forcedPresentAtoms": forced_true,
            "forcedAbsentAtoms": forced_false,
            "residualComponents": component_schedules,
            "branchFeasible": branch != combined.false})

    sample()
    usage["totalNodesCreated"] += combined._next - 2
    usage["liveNodes"] = len(combined._nodes)
    return combined, image, output_tokens, usage, schedules, cutset


def _assert_native_projected(relation, native_levels):
    """The serialized image may contain output atom levels only."""
    native = set(native_levels.values())
    if any(row["level"] in native for row in relation["nodes"]):
        raise AssertionError("atom image retained a native dependency")


def _blocked(model_sha, cell, blockers, budgets,
             diagnostic="DIAGNOSTIC_BLOCKED_INPUT", phase=None, usage=None,
             image_strategy="partitioned", native_cutset=()):
    result = {"schema": SCHEMA, "status": "BLOCKED",
            "diagnosticStatus": diagnostic,
            "claimScope": "DIAGNOSTIC_COMPOSITIONAL_TYPED_ATOM_IMAGE_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "modelSha256": model_sha, "cell": cell,
            "blockers": sorted(set(blockers)), "budgets": budgets,
            "resourcePhase": phase, "resourceUsage": usage}
    if image_strategy != "partitioned":
        result["imageStrategy"] = image_strategy
        result["resourceBudgetScope"] = "PER_MDD_MANAGER"
    if image_strategy == "cutset-conditioned-components":
        result["nativeCutset"] = list(native_cutset)
    return result


def build(model_path, *, max_nodes=250_000, max_apply_pairs=1_000_000,
          max_bag_cells=1_000_000, max_factor_cells=1_000_000,
          max_atoms=10_000, max_terms=100_000,
          image_strategy="partitioned", max_order_operations=1_000_000,
          max_order_scope_symbols=10_000, native_cutset=(),
          max_cutset_assignments=1_024):
    for name, value in (("max_nodes", max_nodes),
                        ("max_apply_pairs", max_apply_pairs),
                        ("max_bag_cells", max_bag_cells),
                        ("max_factor_cells", max_factor_cells),
                        ("max_atoms", max_atoms), ("max_terms", max_terms),
                        ("max_order_operations", max_order_operations),
                        ("max_order_scope_symbols", max_order_scope_symbols),
                        ("max_cutset_assignments", max_cutset_assignments)):
        if type(value) is not int or value < 1:
            raise ValueError(name + " must be a positive integer")
    if image_strategy not in ("partitioned", "decomposed-components",
                             "terminal-aware-components",
                             "cutset-conditioned-components"):
        raise ValueError("unsupported exact atom-image strategy")
    if (not isinstance(native_cutset, (tuple, list)) or
            any(type(domain) is not int for domain in native_cutset)):
        raise ValueError("native_cutset must be a sequence of integers")
    native_cutset = tuple(native_cutset)
    if image_strategy == "cutset-conditioned-components" and not native_cutset:
        raise ValueError("cutset-conditioned strategy requires a native cutset")
    if image_strategy != "cutset-conditioned-components" and native_cutset:
        raise ValueError("native cutset requires cutset-conditioned strategy")
    budgets = {"maxNodes": max_nodes, "maxApplyPairs": max_apply_pairs,
               "maxBagCells": max_bag_cells,
               "maxFactorCells": max_factor_cells,
               "maxAtoms": max_atoms, "maxTerms": max_terms}
    if image_strategy == "terminal-aware-components":
        budgets["maxOrderOperations"] = max_order_operations
        budgets["maxOrderScopeSymbols"] = max_order_scope_symbols
    if image_strategy == "cutset-conditioned-components":
        budgets["maxCutsetAssignments"] = max_cutset_assignments
    model, model_sha, radices, tables = read_model(model_path)
    cell = model.get("cell")
    _, occurrences = validate_identity(model)
    occurrence_to_domain = {domain["occurrence"]: index
                            for index, domain in enumerate(model["domains"])}
    contract = projection_contract(model, occurrence_to_domain, occurrences, radices)
    if contract["status"] != "DECODER_STRUCTURAL_ONLY":
        return _blocked(model_sha, cell,
                        contract["blockers"] or ["TYPED_PROJECTION_UNAVAILABLE"],
                        budgets, image_strategy=image_strategy,
                        native_cutset=native_cutset)
    # The current typed decoder also cannot resolve a PHI FType from multiple
    # producers. This schema needs an explicit, unambiguous source first.
    if any(template.get("mode") == "PHI" and "ftype" not in template
           for variable in model["physicalProjection"]["variables"]
           for fragment in variable["alternatives"]
           for template in fragment["bindings"]):
        return _blocked(model_sha, cell, ["PHI_PRODUCER_FTYPE_NOT_COMPOSITIONAL"],
                        budgets, image_strategy=image_strategy,
                        native_cutset=native_cutset)
    if any(prod(radices[index] for index in scope) > max_factor_cells
           for scope, _ in tables):
        return _blocked(model_sha, cell, ["FACTOR_CELL_BUDGET_EXHAUSTED"], budgets,
                        "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                        image_strategy=image_strategy,
                        native_cutset=native_cutset)
    try:
        accepted, _, accept_trace = count_relation(
            radices, tables, frozenset(("ALLOW",)), max_bag_cells)
        nonrejected, _, _ = count_relation(
            radices, tables, frozenset(("ALLOW", "UNKNOWN")), max_bag_cells)
    except ValueError as error:
        if "bag exceeds exact cell limit" not in str(error):
            raise
        return _blocked(model_sha, cell,
                        ["FACTOR_ELIMINATION_BAG_BUDGET_EXHAUSTED"], budgets,
                        "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                        image_strategy=image_strategy,
                        native_cutset=native_cutset)
    try:
        atoms, terms = compile_atoms(model["physicalProjection"], max_atoms, max_terms)
    except MDDResourceLimitError as error:
        return _blocked(model_sha, cell, [str(error).upper().replace(" ", "_")], budgets,
                        "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                        image_strategy=image_strategy,
                        native_cutset=native_cutset)
    if not atoms:
        return _blocked(model_sha, cell, ["EMPTY_PHYSICAL_ATOM_UNIVERSE"], budgets,
                        image_strategy=image_strategy,
                        native_cutset=native_cutset)

    native_dictionary = {}
    native_values = {}
    for index, domain in enumerate(model["domains"]):
        values = tuple(_token("n_", alternative, native_dictionary)
                       for alternative in domain["alternatives"])
        native_values[index] = values

    reduced_terms = {token: _reduced_terms(rows, radices)
                     for token, rows in terms.items()}
    if any(not rows for rows in reduced_terms.values()):
        raise AssertionError("typed physical atom has an impossible condition")
    static_tokens = tuple(token for token, rows in reduced_terms.items()
                          if _condition_is_total(rows, radices, max_factor_cells))
    dynamic_tokens = tuple(token for token in atoms if token not in static_tokens)
    elimination_order = tuple(row["eliminate"] for row in accept_trace
                              if radices[row["eliminate"]] > 1)
    if set(elimination_order) != {index for index, radix in enumerate(radices)
                                  if radix > 1}:
        raise AssertionError("factor count did not provide a complete native order")
    if image_strategy == "cutset-conditioned-components":
        try:
            native_cutset, _ = normalize_cutset(
                native_cutset, radices, elimination_order,
                max_cutset_assignments)
        except ValueError as error:
            if "assignment budget exhausted" not in str(error):
                raise
            return _blocked(
                model_sha, cell, ["NATIVE_CUTSET_ASSIGNMENT_BUDGET_EXHAUSTED"],
                budgets, "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                image_strategy=image_strategy, native_cutset=native_cutset)
    position = {domain: index for index, domain in enumerate(elimination_order)}
    atoms_after = {index: [] for index in range(len(elimination_order))}
    for token in dynamic_tokens:
        dependencies = {domain for row in reduced_terms[token] for domain, _ in row}
        # The earliest dependency to be eliminated is the condition's last
        # possible native-use stage. Keep its output atom immediately beside
        # that bucket so the projected frontier stays narrow.
        atoms_after[min(position[domain] for domain in dependencies)].append(token)
    variables = []
    native_levels = {}
    atom_levels = {}
    for order, domain in enumerate(elimination_order):
        native_levels[domain] = len(variables)
        variables.append(Variable("native:%d:%s" %
                                  (domain, model["domains"][domain]["occurrence"]),
                                  native_values[domain]))
        for token in sorted(atoms_after[order]):
            atom_levels[token] = len(variables)
            variables.append(Variable("atom:" + token, ("0", "1")))
    factor_stages = {order: [] for order in range(len(elimination_order))}
    constant_factors = []
    for index, (scope, _) in enumerate(tables):
        dependencies = [domain for domain in scope if radices[domain] > 1]
        if dependencies:
            factor_stages[min(position[domain] for domain in dependencies)].append(index)
        else:
            constant_factors.append(index)

    phase = ("CUTSET_CONDITIONED_COMPONENT_ELIMINATION"
             if image_strategy == "cutset-conditioned-components" else
             "DECOMPOSED_COMPONENT_ELIMINATION" if image_strategy !=
             "partitioned" else "PARTITIONED_FACTOR_ATOM_ELIMINATION")
    manager = MDDManager(variables, max_nodes=max_nodes,
                         max_apply_pairs=max_apply_pairs)
    progress = {}
    try:
        if image_strategy == "cutset-conditioned-components":
            manager, image, output_tokens, usage, component_schedules, \
                native_cutset = _cutset_conditioned_image(
                    radices, tables, atoms,
                    {token: reduced_terms[token] for token in dynamic_tokens},
                    native_values, elimination_order, native_cutset,
                    max_cutset_assignments, max_nodes, max_apply_pairs, progress)
            native_levels = {}
        elif image_strategy != "partitioned":
            manager, image, output_tokens, usage, component_schedules = \
                _decomposed_image(radices, tables, atoms,
                                  {token: reduced_terms[token]
                                   for token in dynamic_tokens}, native_values,
                                  elimination_order, max_nodes,
                                  max_apply_pairs, progress,
                                  terminal_aware=image_strategy ==
                                  "terminal-aware-components",
                                  max_order_operations=max_order_operations,
                                  max_order_scope_symbols=max_order_scope_symbols)
            native_levels = {}
        else:
            manager, image, usage = _partitioned_image(
                manager, radices, tables, atoms, reduced_terms, native_levels,
                atom_levels, elimination_order, max_nodes, progress)
            output_tokens = dynamic_tokens
        roots = {"definitelyAcceptedAtomImage": image}
        phase = "RELATION_SERIALIZATION"
        relation = manager.to_artifact(roots)
        _assert_native_projected(relation, native_levels)
        image_total = manager.count(image)
        if image_strategy == "partitioned":
            raw = prod(radices)
            if image_total % raw:
                raise AssertionError("atom image count lacks native product factor")
            image_total //= raw
    except CutsetStructureError as error:
        return _blocked(
            model_sha, cell, [str(error).upper().replace(" ", "_")], budgets,
            "DIAGNOSTIC_BLOCKED_INPUT", phase, {"atFailure": progress},
            image_strategy=image_strategy, native_cutset=native_cutset)
    except (MDDResourceLimitError, TerminalOrderResourceLimitError) as error:
        blocked = _blocked(model_sha, cell,
                        [str(error).upper().replace(" ", "_")],
                        budgets, "DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT", phase,
                        {"liveNodes": progress.get(
                             "localLiveNodes", len(manager._nodes)),
                         "applyPairsCached": progress.get(
                             "localApplyPairsCached", len(manager._apply_cache)),
                         "atFailure": progress if phase !=
                         "RELATION_SERIALIZATION" else None},
                        image_strategy=image_strategy,
                        native_cutset=native_cutset)
        return blocked

    projection = model["physicalProjection"]
    schedule = ({
        "schema": "cutset-conditioned-e-atom-image-schedule-v1",
        "nativeOrder": list(elimination_order),
        "nativeCutset": list(native_cutset),
        "cutsetAssignments": prod(radices[domain] for domain in native_cutset),
        "branches": component_schedules,
        "alwaysPresentAtoms": list(static_tokens),
        "conditionedDependencyCoverage": "COMPLETE",
        "independentNativeProjection": True,
    } if image_strategy == "cutset-conditioned-components" else {
        "schema": ("terminal-aware-decomposed-e-atom-image-schedule-v1"
                   if image_strategy == "terminal-aware-components" else
                   "decomposed-e-atom-image-schedule-v1"),
        "nativeOrder": list(elimination_order),
        "components": component_schedules,
        "constantHardFactors": constant_factors,
        "alwaysPresentAtoms": list(static_tokens),
        "independentNativeProjection": True,
    } if image_strategy != "partitioned" else {
        "schema": "partitioned-e-atom-image-schedule-v1",
        "nativeOrder": list(elimination_order),
        "constantHardFactors": constant_factors,
        "stages": [
            {"native": elimination_order[order],
             "hardFactors": factor_stages[order],
             "atomVariables": sorted(atoms_after[order])}
            for order in range(len(elimination_order))],
        "alwaysPresentAtoms": list(static_tokens),
        "earlyNativeExists": True,
        "stageEndCompaction": True,
        "intermediateCompactionLiveNodeThreshold": 120_000})
    result = {"schema": SCHEMA, "status": "BLOCKED",
            "diagnosticStatus": "DIAGNOSTIC_COMPLETE",
            "claimScope": "DIAGNOSTIC_COMPOSITIONAL_TYPED_ATOM_IMAGE_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "blockers": ["INDEPENDENT_TYPED_PROJECTION_SEMANTIC_BINDING_MISSING"],
            "cell": cell, "modelSha256": model_sha,
            "nativeAcceptanceStatus": ("COMPLETE" if nonrejected == accepted
                                       else "INCOMPLETE_UNKNOWN"),
            "counts": {"rawNativeAssignments": str(prod(radices)),
                       "definitelyAcceptedNativeAssignments": str(accepted),
                       "unknownNativeAssignments": str(nonrejected - accepted),
                       "physicalAtoms": str(len(atoms)),
                       "dynamicPhysicalAtoms": str(len(dynamic_tokens)),
                       "alwaysPresentPhysicalAtoms": str(len(static_tokens)),
                       "atomConditionTerms": str(sum(len(rows) for rows in terms.values())),
                       "uniqueDefinitelyAcceptedProvenanceAtomSets":
                           str(image_total)},
            "staticCoordinates": {
                "logicalProgram": projection["logicalProgram"],
                "logicalInputs": projection["logicalInputs"]},
            "nativeDictionary": dict(sorted(native_dictionary.items())),
            "atomDictionary": atoms, "atomConditions": terms,
            "alwaysPresentAtoms": list(static_tokens),
            "atomVariableTokens": list(output_tokens),
            "nativeEliminationOrder": list(elimination_order),
            "constructionSchedule": schedule,
            "rootCommitments": relation["roots"],
            "relation": relation,
            "budgets": budgets, "resourceUsage": usage}
    if image_strategy != "partitioned":
        result["imageStrategy"] = image_strategy
        result["resourceBudgetScope"] = "PER_MDD_MANAGER"
    if image_strategy == "cutset-conditioned-components":
        result["nativeCutset"] = list(native_cutset)
    return result


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as raw:
        temporary = Path(raw.name)
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as stream:
            stream.write(canonical(value) + b"\n")
    temporary.replace(path)


def _bounded_replay(model, tables, result, validated, max_replay_cells):
    """Use the separate typed decoder for exhaustive tiny-model diagnostics."""
    if result["diagnosticStatus"] != "DIAGNOSTIC_COMPLETE":
        return "NOT_APPLICABLE_BLOCKED_CONSTRUCTION"
    radices = [len(row["alternatives"]) for row in model["domains"]]
    tokens = tuple(result["atomVariableTokens"])
    static_tokens = frozenset(result["alwaysPresentAtoms"])
    if prod(radices) * (1 << len(tokens)) > max_replay_cells:
        return "SKIPPED_EXHAUSTIVE_REPLAY_BUDGET"
    native_assignments = product(*(range(radix) for radix in radices))
    expected_images = set()

    def active_condition(token, native):
        return any(all(native[domain] == alternative for domain, alternative in row)
                   for row in result["atomConditions"][token])

    def relation_assignment(native, bits):
        atom_bits = dict(zip(tokens, bits))
        assignment = []
        for variable in validated.variables:
            if variable.name.startswith("native:"):
                domain = int(variable.name.split(":", 2)[1])
                assignment.append(native[domain])
            elif variable.name.startswith("atom:"):
                assignment.append(atom_bits[variable.name[5:]])
            else:
                raise ValueError("diagnostic relation has an unknown variable")
        return tuple(assignment)

    for native in native_assignments:
        active = static_tokens | frozenset(
            token for token in tokens if active_condition(token, native))
        bits = tuple(int(token in active) for token in tokens)
        expected_plan = compose_physical_projection(model, native)
        if materialize_atoms(result, active) != expected_plan:
            raise ValueError("typed atom reconstruction differs from reference decoder")
        accepted = all(factor_status(scope, truth, native, radices) == "ALLOW"
                       for scope, truth in tables)
        if accepted:
            expected_images.add(bits)
    for native in product(*(range(radix) for radix in radices)):
        for atom_bits in product((0, 1), repeat=len(tokens)):
            assignment = relation_assignment(native, atom_bits)
            if validated.evaluate("definitelyAcceptedAtomImage", assignment) != \
                    (atom_bits in expected_images):
                raise ValueError("accepted atom image replay differs")
    return "PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY"


def verify_saved(artifact_path, model_path, expected_sha256, *,
                 max_replay_cells=250_000, max_decoded_bytes=512 * 1024 * 1024):
    """Verify an externally committed diagnostic against its frozen E model.

    This checks artifact integrity and reproduces the diagnostic only. It does
    not establish independent Java projection semantics or P/E equality.
    """
    if (not isinstance(expected_sha256, str) or len(expected_sha256) != 64 or
            any(character not in "0123456789abcdef" for character in expected_sha256)):
        raise ValueError("an external lowercase SHA-256 commitment is required")
    if (type(max_replay_cells) is not int or max_replay_cells < 1 or
            type(max_decoded_bytes) is not int or max_decoded_bytes < 1):
        raise ValueError("verification budgets must be positive integers")
    artifact_path = Path(artifact_path)
    actual_hash = hashlib.sha256()
    with artifact_path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            actual_hash.update(block)
    if actual_hash.hexdigest() != expected_sha256:
        raise ValueError("diagnostic artifact differs from expected SHA-256")
    with gzip.open(artifact_path, "rb") as source:
        decoded = source.read(max_decoded_bytes + 1)
        if len(decoded) > max_decoded_bytes or source.read(1):
            raise ValueError("diagnostic decompressed-byte budget exhausted")
    result = json.loads(decoded)
    if decoded != canonical(result) + b"\n" or result.get("schema") != SCHEMA:
        raise ValueError("diagnostic artifact encoding or schema differs")
    budgets = result.get("budgets")
    expected_budget_keys = {"maxNodes", "maxApplyPairs", "maxBagCells",
                            "maxFactorCells", "maxAtoms", "maxTerms"}
    strategy = result.get("imageStrategy", "partitioned")
    if strategy == "terminal-aware-components":
        expected_budget_keys.update(("maxOrderOperations",
                                     "maxOrderScopeSymbols"))
    if strategy == "cutset-conditioned-components":
        expected_budget_keys.add("maxCutsetAssignments")
    if (not isinstance(budgets, dict) or set(budgets) != expected_budget_keys or
            any(type(value) is not int or value < 1 for value in budgets.values())):
        raise ValueError("diagnostic budgets are incomplete")
    rebuilt = build(model_path, max_nodes=budgets["maxNodes"],
                    max_apply_pairs=budgets["maxApplyPairs"],
                    max_bag_cells=budgets["maxBagCells"],
                    max_factor_cells=budgets["maxFactorCells"],
                    max_atoms=budgets["maxAtoms"], max_terms=budgets["maxTerms"],
                    image_strategy=strategy,
                    max_order_operations=budgets.get("maxOrderOperations", 1_000_000),
                    max_order_scope_symbols=budgets.get(
                        "maxOrderScopeSymbols", 10_000),
                    native_cutset=tuple(result.get("nativeCutset", ())),
                    max_cutset_assignments=budgets.get(
                        "maxCutsetAssignments", 1_024))
    if canonical(result) != canonical(rebuilt):
        raise ValueError("diagnostic differs from model-bound rebuild")
    replay = "NOT_APPLICABLE_BLOCKED_CONSTRUCTION"
    if "relation" in result:
        relation = result["relation"]
        validated = validate_artifact(
            relation, expected_variables=[(row["name"], row["values"])
                for row in rebuilt["relation"]["variables"]],
            expected_roots=rebuilt["relation"]["roots"])
        model, _, _, tables = read_model(model_path)
        replay = _bounded_replay(model, tables, result, validated, max_replay_cells)
    return {"schema": "compositional-e-atom-image-verification-v1",
            "status": "PASS", "claimScope": "MODEL_BOUND_DIAGNOSTIC_INTEGRITY_ONLY",
            "semanticBindingStatus": "BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
            "artifactSha256": expected_sha256, "modelSha256": result["modelSha256"],
            "replayStatus": replay}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--max-nodes", type=int, default=250_000)
    parser.add_argument("--max-apply-pairs", type=int, default=1_000_000)
    parser.add_argument("--max-bag-cells", type=int, default=1_000_000)
    parser.add_argument("--max-factor-cells", type=int, default=1_000_000)
    parser.add_argument("--max-atoms", type=int, default=10_000)
    parser.add_argument("--max-terms", type=int, default=100_000)
    parser.add_argument("--max-order-operations", type=int, default=1_000_000)
    parser.add_argument("--max-order-scope-symbols", type=int, default=10_000)
    parser.add_argument("--max-cutset-assignments", type=int, default=1_024)
    parser.add_argument("--native-cutset", type=int, nargs="+", default=())
    parser.add_argument("--image-strategy", choices=("partitioned",
                        "decomposed-components", "terminal-aware-components",
                        "cutset-conditioned-components"),
                        default="partitioned")
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--expected-sha256")
    args = parser.parse_args()
    if args.verify:
        print(json.dumps(verify_saved(args.artifact, args.model,
                                      args.expected_sha256), sort_keys=True))
        return
    result = build(args.model, max_nodes=args.max_nodes,
                   max_apply_pairs=args.max_apply_pairs,
                   max_bag_cells=args.max_bag_cells,
                   max_factor_cells=args.max_factor_cells,
                   max_atoms=args.max_atoms, max_terms=args.max_terms,
                   image_strategy=args.image_strategy,
                   max_order_operations=args.max_order_operations,
                   max_order_scope_symbols=args.max_order_scope_symbols,
                   native_cutset=args.native_cutset,
                   max_cutset_assignments=args.max_cutset_assignments)
    publish(args.artifact, result)
    print(json.dumps({key: result.get(key) for key in
                      ("cell", "status", "diagnosticStatus",
                       "nativeAcceptanceStatus", "counts")}, sort_keys=True))


if __name__ == "__main__":
    main()
