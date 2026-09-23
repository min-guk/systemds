#!/usr/bin/env python3
"""Deterministic terminal-aware native variable elimination ordering.

Physical output atoms are modeled as binary terminal variables that are never
eliminated.  At each step the heuristic exactly unions every current factor
containing a candidate native variable and scores the resulting scope.  This
prices the output frontier that ordinary native-only min-fill ignores.
"""

from math import prod


SCHEMA = "terminal-aware-native-order-v1"


class TerminalOrderResourceLimitError(RuntimeError):
    """The bounded structural ordering computation exhausted its budget."""


def _native(domain):
    return ("native", domain)


def _terminal(token):
    return ("terminal", token)


def _symbol_key(symbol):
    return (0, symbol[1]) if symbol[0] == "native" else (1, symbol[1])


def _scope_key(scope):
    return tuple(sorted(scope, key=_symbol_key))


def plan_terminal_aware_order(radices, factor_scopes, atom_scopes, base_order,
                              *, max_operations=1_000_000,
                              max_scope_symbols=10_000):
    """Return a replayable bounded ordering plan for one native component.

    ``factor_scopes`` contains hard-factor native scopes. ``atom_scopes`` maps
    each dynamic output atom token to all varying native domains used by its
    condition.  Every supplied scope must stay inside ``base_order``; callers
    must split disconnected components before invoking this function.
    """
    radices = tuple(radices)
    base_order = tuple(base_order)
    if (not radices or any(type(radix) is not int or radix < 1
                           for radix in radices)):
        raise ValueError("radices must be positive integers")
    if (not base_order or len(base_order) != len(set(base_order)) or
            any(type(domain) is not int or domain < 0 or domain >= len(radices)
                or radices[domain] <= 1 for domain in base_order)):
        raise ValueError("base order must uniquely cover varying native domains")
    if (type(max_operations) is not int or max_operations < 1 or
            type(max_scope_symbols) is not int or max_scope_symbols < 1):
        raise ValueError("heuristic budgets must be positive integers")
    component = frozenset(base_order)
    if not isinstance(atom_scopes, dict) or any(
            not isinstance(token, str) or not token for token in atom_scopes):
        raise ValueError("atom scopes must be a non-empty-token dictionary")

    operations = 0

    def charge(amount=1):
        nonlocal operations
        operations += amount
        if operations > max_operations:
            raise TerminalOrderResourceLimitError(
                "terminal-aware operation budget exhausted")

    def canonicalize(rows):
        """Deduplicate/sort scopes while charging their hashing/comparison walk."""
        charge(len(rows))
        charge(sum(len(scope) for scope in rows))
        unique = set(rows)
        charge(len(unique) * max(1, len(unique).bit_length()))
        return sorted(unique, key=_scope_key)

    factors = []
    for raw_scope in factor_scopes:
        scope = tuple(raw_scope)
        charge(1 + len(scope))
        if (len(scope) != len(set(scope)) or any(type(domain) is not int
                or domain not in component for domain in scope)):
            raise ValueError("hard factor scope leaves the native component")
        if scope:
            factors.append(frozenset(_native(domain) for domain in scope))
    for token, raw_scope in sorted(atom_scopes.items()):
        scope = tuple(raw_scope)
        charge(1 + len(scope))
        if (not scope or len(scope) != len(set(scope)) or
                any(type(domain) is not int or domain not in component
                    for domain in scope)):
            raise ValueError("atom scope must be non-empty and component-local")
        factors.append(frozenset([_terminal(token)] +
                                 [_native(domain) for domain in scope]))
    factors = canonicalize(factors)
    if any(len(scope) > max_scope_symbols for scope in factors):
        raise TerminalOrderResourceLimitError(
            "terminal-aware input scope-symbol budget exhausted")

    peak_scope = max((len(scope) for scope in factors), default=0)

    remaining = set(base_order)
    base_position = {domain: position for position, domain in enumerate(base_order)}
    order = []
    trace = []
    while remaining:
        # This graph is identical for every candidate at the current stage.
        # Compute it once and charge all scope/native/pair traversal.
        existing_pairs = set()
        for scope in factors:
            charge(len(scope))
            natives = [item[1] for item in scope if item[0] == "native"]
            charge(len(natives) * max(1, len(natives).bit_length()))
            natives.sort()
            for offset, left in enumerate(natives):
                for right in natives[offset + 1:]:
                    charge()
                    existing_pairs.add((left, right))
        best = None
        candidates = sorted(remaining, key=base_position.__getitem__)
        charge(len(candidates) * max(1, len(candidates).bit_length()))
        for domain in candidates:
            symbol = _native(domain)
            selected_indices = []
            union = {symbol}
            for index, scope in enumerate(factors):
                charge()
                if symbol in scope:
                    selected_indices.append(index)
                    charge(len(scope))
                    union.update(scope)
            output = frozenset(union - {symbol})
            if len(output) > max_scope_symbols:
                raise TerminalOrderResourceLimitError(
                    "terminal-aware induced scope-symbol budget exhausted")
            charge(len(output))
            native_neighbors = [item[1] for item in output
                                if item[0] == "native"]
            terminal_count = len(output) - len(native_neighbors)
            charge(len(native_neighbors) * max(1, len(native_neighbors).bit_length()))
            native_neighbors.sort()
            fill = 0
            for offset, left in enumerate(native_neighbors):
                for right in native_neighbors[offset + 1:]:
                    charge()
                    fill += (left, right) not in existing_pairs
            charge(len(native_neighbors))
            bag_cells = (1 << terminal_count) * prod(
                radices[neighbor] for neighbor in native_neighbors)
            score = (bag_cells, terminal_count, len(native_neighbors), fill,
                     base_position[domain])
            candidate = (score, domain, selected_indices, output,
                         native_neighbors, terminal_count)
            if best is None or candidate[0] < best[0]:
                best = candidate

        score, domain, selected_indices, output, native_neighbors, \
            terminal_count = best
        peak_scope = max(peak_scope, len(output))
        selected = set(selected_indices)
        charge(len(factors))
        factors = [scope for index, scope in enumerate(factors)
                   if index not in selected]
        if output:
            factors.append(output)
        factors = canonicalize(factors)
        remaining.remove(domain)
        order.append(domain)
        trace.append({
            "stage": len(order) - 1, "eliminate": domain,
            "selectedFactorCount": len(selected_indices),
            "outputNativeDomains": native_neighbors,
            "outputTerminalCount": terminal_count,
            "outputScopeSymbols": len(output), "bagCells": str(score[0]),
            "nativeFill": score[3]})

    if set(order) != component or len(order) != len(component):
        raise AssertionError("terminal-aware order lost native coverage")
    return {"schema": SCHEMA, "order": order, "trace": trace,
            "operations": operations, "peakScopeSymbols": peak_scope,
            "maxOperations": max_operations,
            "maxScopeSymbols": max_scope_symbols}


def terminal_aware_order(radices, factor_scopes, atom_scopes, base_order,
                         **budgets):
    """Convenience wrapper returning only the deterministic native order."""
    return tuple(plan_terminal_aware_order(
        radices, factor_scopes, atom_scopes, base_order, **budgets)["order"])
