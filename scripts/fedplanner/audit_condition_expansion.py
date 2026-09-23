#!/usr/bin/env python3
"""Expand discovered placeholders into registry-backed condition candidates.

This is an inventory audit. Candidate rows are not frozen native inputs and
must not be counted as completed P/E comparisons.
"""

import argparse
import ast
import hashlib
import itertools
import json
from pathlib import Path
import tempfile


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def constants(path, names):
    tree = ast.parse(Path(path).read_text(), filename=str(path))
    found = {}
    for statement in tree.body:
        if isinstance(statement, ast.Assign) and len(statement.targets) == 1:
            target = statement.targets[0]
            if isinstance(target, ast.Name) and target.id in names:
                found[target.id] = ast.literal_eval(statement.value)
    if set(found) != set(names):
        raise ValueError(f"registry constants missing in {path}: {set(names) - set(found)}")
    return found


def unique(values, label):
    values = tuple(values)
    if not values or len(values) != len(set(values)):
        raise ValueError(f"empty or duplicated {label}")
    return values


def expected_microbench_ids(registry):
    scales = unique(registry["SCALES"], "microbench scales")
    families = unique(registry["FAMILIES"], "microbench families")
    variants = unique(registry["REUSE_VARIANTS"], "microbench variants")
    if "reuse_update" not in families:
        raise ValueError("microbench reuse_update family missing")
    return {f"microbench:{family}-k{scale}"
            for family in families if family != "reuse_update" for scale in scales} | {
            f"microbench:reuse_update-{variant}-k{scale}"
            for variant in variants for scale in scales}


def build(catalog_path, evaluation_root):
    catalog_path = Path(catalog_path).resolve()
    evaluation_root = Path(evaluation_root).resolve()
    catalog = json.loads(catalog_path.read_text())
    if catalog.get("schema") != "closed-comparison-cases-v1":
        raise ValueError("wrong closed comparison catalog schema")
    in_scope = [row for row in catalog["cells"] if row["inventoryStatus"] == "IN_SCOPE"]
    if len({row["id"] for row in in_scope}) != len(in_scope):
        raise ValueError("duplicate in-scope placeholder")
    frozen = [row for row in in_scope if row["sourceBinding"]["conditionStatus"] == "SNAPSHOT_ONLY"]
    unresolved = [row for row in in_scope if row["sourceBinding"]["conditionStatus"] == "UNRESOLVED"]
    if len(frozen) + len(unresolved) != len(in_scope):
        raise ValueError("unknown in-scope condition status")
    profiles = unique(sorted({row["conditionId"] for row in frozen}), "frozen profiles")
    source_hashes = {}
    for row in unresolved:
        binding = row["sourceBinding"]
        if binding.get("discoveryId") != row["discoveryId"]:
            raise ValueError("placeholder discovery binding differs")
        for relative, digest in row["sourceFiles"].items():
            path = (evaluation_root / relative).resolve()
            if not path.is_relative_to(evaluation_root) or not path.is_file() or sha(path) != digest:
                raise ValueError(f"placeholder source missing or changed: {relative}")
            if relative in source_hashes and source_hashes[relative] != digest:
                raise ValueError(f"conflicting source digest: {relative}")
            source_hashes[relative] = digest
    base = constants(evaluation_root / "driver/run_multihost_campaign_network_quality_v2.py",
                     ("WORKLOADS_BY_SUITE", "PROFILES", "WORKERS"))
    ml10 = constants(evaluation_root / "campaign/run_ml10_campaign.py",
                     ("WORKLOADS", "PROFILES", "WORKERS"))
    micro = constants(evaluation_root / "microbench/generate.py",
                      ("SCALES", "FAMILIES", "REUSE_VARIANTS"))
    base_profiles = unique(base["PROFILES"], "base profiles")
    base_workers = unique(base["WORKERS"], "base workers")
    ml10_profiles = unique(ml10["PROFILES"], "ml10 profiles")
    ml10_workers = unique(ml10["WORKERS"], "ml10 workers")
    for suite, workloads in base["WORKLOADS_BY_SUITE"].items():
        unique(workloads, f"base {suite} workloads")
    unique(ml10["WORKLOADS"], "ml10 workloads")
    micro_ids = expected_microbench_ids(micro)
    observed_micro_ids = {row["discoveryId"] for row in in_scope
                          if row["sourceBinding"]["kind"] == "generated-microbench"}
    if micro_ids != observed_micro_ids:
        raise ValueError("discovered microbench variants differ from generator registry")

    candidates = []
    for row in frozen:
        candidates.append({"key": row["id"], "placeholder": row["id"],
                           "discoveryId": row["discoveryId"],
                           "kind": "planning-snapshot", "conditionId": row["conditionId"],
                           "inputStatus": "SNAPSHOT_ONLY"})
    for row in unresolved:
        kind = row["sourceBinding"]["kind"]
        discovery = row["discoveryId"]
        if kind == "base-campaign":
            prefix, suite, workload = discovery.split(":", 2)
            if prefix != "base":
                raise ValueError("invalid base discovery id")
            registry_workload = workload.removeprefix("sliceline-").upper() if suite == "sliceline" else workload
            if registry_workload not in base["WORKLOADS_BY_SUITE"].get(suite, ()):
                raise ValueError(f"base workload absent from registry: {discovery}")
            axes = itertools.product(base_workers, base_profiles)
        elif kind == "ml10-campaign":
            prefix, workload = discovery.split(":", 1)
            if prefix != "ml10" or workload not in ml10["WORKLOADS"]:
                raise ValueError(f"ml10 workload absent from registry: {discovery}")
            axes = itertools.product(ml10_workers, ml10_profiles)
        elif kind == "planning-snapshot":
            prefix, _ = discovery.split(":", 1)
            if not prefix.startswith("planning-w"):
                raise ValueError("invalid planning discovery id")
            workers = int(prefix.removeprefix("planning-w"))
            frozen_workers = {int(item["discoveryId"].split(":", 1)[0].removeprefix("planning-w"))
                              for item in frozen}
            if workers not in frozen_workers:
                raise ValueError("planning worker count absent from frozen profiles")
            axes = ((workers, profile) for profile in profiles)
        elif kind == "generated-microbench":
            axes = ((None, None),)
        else:
            raise ValueError(f"unknown placeholder kind: {kind}")
        for workers, profile in axes:
            candidates.append({"key": f"{row['id']}:{workers}:{profile}",
                               "placeholder": row["id"], "discoveryId": discovery,
                               "kind": kind, "workers": workers, "networkProfile": profile,
                               "inputStatus": "UNRESOLVED"})
    keys = [item["key"] for item in candidates]
    if len(keys) != len(set(keys)):
        raise ValueError("candidate key collision")
    candidates.sort(key=lambda item: item["key"])
    return {"schema": "closed-condition-candidate-audit-v1", "status": "INCOMPLETE",
            "claimScope": "REGISTRY_AXIS_INVENTORY_ONLY",
            "catalogSha256": sha(catalog_path), "sourceSha256": source_hashes,
            "frozenSnapshotRows": len(frozen), "unresolvedPlaceholders": len(unresolved),
            "unresolvedCandidates": len(candidates) - len(frozen),
            "candidateRowsBeforeDedupAndInputValidation": len(candidates),
            "candidates": candidates}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = build(args.catalog, args.evaluation_root)
    content = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text() != content:
            raise SystemExit("condition candidate audit changed")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile("w", dir=args.output.parent, delete=False) as stream:
            stream.write(content)
            temporary = Path(stream.name)
        temporary.replace(args.output)
    print(json.dumps({key: result[key] for key in
                      ("status", "claimScope", "frozenSnapshotRows",
                       "unresolvedPlaceholders", "unresolvedCandidates",
                       "candidateRowsBeforeDedupAndInputValidation")}, sort_keys=True))


if __name__ == "__main__":
    main()
