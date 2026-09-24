#!/usr/bin/env python3
"""Exact substitution helpers for conditioned finite-domain relations."""

from itertools import product
from math import prod

from exact_e_physical_relation import factor_status


def normalize_cutset(cutset, radices, native_order, max_assignments):
    """Validate and canonically order an explicit varying-native cutset."""
    if not isinstance(cutset, (tuple, list)) or not cutset:
        raise ValueError("cutset-conditioned strategy requires a native cutset")
    if (type(max_assignments) is not int or max_assignments < 1 or
            any(type(domain) is not int for domain in cutset)):
        raise ValueError("cutset configuration is invalid")
    if len(cutset) != len(set(cutset)):
        raise ValueError("native cutset contains duplicate domains")
    position = {domain: index for index, domain in enumerate(native_order)}
    if any(domain not in position or domain < 0 or domain >= len(radices) or
           radices[domain] <= 1 for domain in cutset):
        raise ValueError("native cutset must contain varying domains in native order")
    ordered = tuple(sorted(cutset, key=position.__getitem__))
    assignments = prod(radices[domain] for domain in ordered)
    if assignments > max_assignments:
        raise ValueError("native cutset assignment budget exhausted")
    return ordered, assignments


def cutset_assignments(cutset, radices):
    """Yield deterministic dictionaries for every mixed-radix cutset row."""
    for values in product(*(range(radices[domain]) for domain in cutset)):
        yield dict(zip(cutset, values))


def condition_factor(scope, truth, cutset_assignment, radices):
    """Substitute one cutset row into a dense factor without approximation."""
    residual = tuple(domain for domain in scope
                     if domain not in cutset_assignment)
    full = [0] * len(radices)
    for domain, value in cutset_assignment.items():
        full[domain] = value
    conditioned = []
    for values in product(*(range(radices[domain]) for domain in residual)):
        for domain, value in zip(residual, values):
            full[domain] = value
        conditioned.append(factor_status(scope, truth, full, radices))
    return residual, tuple(conditioned)


def condition_terms(rows, cutset_assignment):
    """Substitute a cutset row into an atom DNF.

    The first result is ``TRUE``, ``FALSE``, or ``DYNAMIC``.  Dynamic rows are
    canonical, duplicate-free residual conjunctions.
    """
    residual = set()
    for row in rows:
        if any(domain in cutset_assignment and
               cutset_assignment[domain] != alternative
               for domain, alternative in row):
            continue
        reduced = tuple((domain, alternative) for domain, alternative in row
                        if domain not in cutset_assignment)
        if not reduced:
            return "TRUE", ()
        residual.add(reduced)
    if not residual:
        return "FALSE", ()
    return "DYNAMIC", tuple(sorted(residual))
