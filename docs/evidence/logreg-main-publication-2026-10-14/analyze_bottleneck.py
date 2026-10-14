#!/usr/bin/env python3
"""Focused, read-only aggregation of Memo optimizer JFR samples."""
import argparse
import collections
import json
from pathlib import Path

P = "org.apache.sysds.hops.fedplanner.memo."
OPTIMIZE = P + "MemoProgramModel.optimize"

TRACKED = [
    P + "MemoOptimizer.support",
    P + "MemoOptimizer.supportDfs",
    P + "MemoOptimizer.propagate",
    P + "MemoOptimizer.computeConditionalCostBound",
    P + "MemoOptimizer.currentTermBound",
    P + "MemoOptimizer.refreshTermBound",
    P + "MemoOptimizer$ReducedSearch.coreLowerBound",
    P + "MemoOptimizer.cached",
    P + "MemoOptimizer.cachedDomains",
    P + "MemoOptimizer.tupleHash",
    P + "MemoOptimizer.scopedDomainHash",
    P + "MemoOptimizer$ScopedDomainIdentity.sameDomains",
    P + "MemoOptimizer.stamp",
    P + "MemoProgramModel.movementGroupCost",
    P + "MemoProgramModel.visitBindingCostContexts",
    P + "MemoBindingResolver.visitJoint",
    P + "MemoBindingResolver.enumerate",
    P + "MemoShapes.resolvedBindingShape",
    P + "MemoShapes.cloneView",
    P + "MemoShapes$PhaseContextKey.projectGuards",
    P + "MemoShapes$PhaseContextKey.projectLoops",
    P + "MemoShapes$PhaseContextKey.projectCardinalities",
    P + "MemoProgramFacts.canonicalGuard",
]


def method(frame):
    return frame.rsplit(":", 1)[0]


def bucket(nearest):
    if nearest.startswith(P + "MemoOptimizer.support"):
        return "solver-support"
    if nearest == P + "MemoOptimizer.propagate":
        return "solver-propagate"
    if nearest in {
        P + "MemoOptimizer.computeConditionalCostBound",
        P + "MemoOptimizer.currentTermBound",
        P + "MemoOptimizer.refreshTermBound",
        P + "MemoOptimizer.minimumTerm",
        P + "MemoOptimizer.minimum",
        P + "MemoOptimizer$ReducedSearch.coreLowerBound",
    }:
        return "conditional-cost-bound"
    if nearest.startswith(P + "MemoProgramModel.movementGroupCost") or nearest == P + "MemoProgramModel.visitBindingCostContexts":
        return "movement-cost-context"
    if nearest.startswith(P + "MemoBindingResolver.") or nearest.startswith(P + "MemoReachableContexts."):
        return "binding-context-resolution"
    if nearest.startswith(P + "MemoShapes.") or nearest.startswith(P + "MemoShapes$") or nearest.startswith(P + "MemoProgramFacts.guard") or nearest == P + "MemoProgramFacts.canonicalGuard":
        return "shape-key-guard-cost"
    if nearest in {
        P + "MemoOptimizer.cached",
        P + "MemoOptimizer.cachedDomains",
        P + "MemoOptimizer.tupleHash",
        P + "MemoOptimizer.scopedDomainHash",
        P + "MemoOptimizer.stamp",
        P + "MemoOptimizer$TupleKey.<init>",
        P + "MemoOptimizer$TupleIdentity.sameTuple",
        P + "MemoOptimizer$TupleProbe.equals",
        P + "MemoOptimizer$ScopedDomainIdentity.sameDomains",
        P + "MemoOptimizer$ScopedDomainProbe.equals",
        P + "MemoOptimizer$ScopedDomainKey.equals",
    }:
        return "search-cache-key-domain"
    if nearest.startswith(P + "MemoOptimizer$ReducedSearch."):
        return "reduced-search-control"
    if nearest.startswith(P + "MemoOptimizer."):
        return "solver-other"
    if nearest.startswith(P + "MemoProgramModel."):
        return "model-cost-other"
    return "optimizer-other"


def top(counter, n=40):
    return [{"name": name, "value": value} for name, value in counter.most_common(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("events")
    ap.add_argument("output")
    args = ap.parse_args()

    samples = 0
    allocation_weight = 0
    inclusive = collections.Counter()
    nearest = collections.Counter()
    leaf = collections.Counter()
    leaf_nearest = collections.Counter()
    sample_buckets = collections.Counter()
    allocation_buckets = collections.Counter()
    allocated_classes = collections.Counter()

    for line in Path(args.events).open():
        event = json.loads(line)
        stack = [method(f) for f in event.get("stack", [])]
        if OPTIMIZE not in stack:
            continue
        memo = [f for f in stack if f.startswith(P)]
        nearest_memo = memo[0] if memo else "<none>"
        if event["type"] == "jdk.ExecutionSample":
            samples += 1
            inclusive.update(set(stack))
            nearest[nearest_memo] += 1
            leaf_name = stack[0] if stack else "<none>"
            leaf[leaf_name] += 1
            leaf_nearest[(leaf_name, nearest_memo)] += 1
            sample_buckets[bucket(nearest_memo)] += 1
        elif event["type"] == "jdk.ObjectAllocationSample":
            weight = event["weight"]
            allocation_weight += weight
            allocation_buckets[bucket(nearest_memo)] += weight
            allocated_classes[event.get("objectClass", "<unknown>")] += weight

    tracked = {}
    for name in TRACKED:
        count = inclusive[name]
        tracked[name] = {
            "inclusiveSamples": count,
            "percentOfOptimizerSamples": 100.0 * count / samples if samples else 0.0,
        }
    out = {
        "method": "JFR sampling; counts are samples, allocation weights are JFR estimates, inclusive methods overlap",
        "optimizerExecutionSamples": samples,
        "optimizerAllocationWeightBytes": allocation_weight,
        "trackedInclusiveMethods": tracked,
        "exclusiveNearestMemoBuckets": {
            name: {"samples": count, "percent": 100.0 * count / samples if samples else 0.0}
            for name, count in sample_buckets.most_common()
        },
        "allocationNearestMemoBuckets": {
            name: {"estimatedBytes": count, "percent": 100.0 * count / allocation_weight if allocation_weight else 0.0}
            for name, count in allocation_buckets.most_common()
        },
        "topNearestMemoMethods": top(nearest),
        "topLeafFrames": top(leaf),
        "topLeafToNearestMemo": [
            {"leaf": pair[0], "nearestMemo": pair[1], "samples": count}
            for pair, count in leaf_nearest.most_common(80)
        ],
        "topAllocatedClasses": top(allocated_classes, 30),
    }
    Path(args.output).write_text(json.dumps(out, indent=2) + "\n")


if __name__ == "__main__":
    main()
