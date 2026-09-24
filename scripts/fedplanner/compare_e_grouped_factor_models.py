#!/usr/bin/env python3
"""Exhaustively compare E hard-factor tables across capture versions.

This checks one captured pair of hard-factor relations. It does not certify
planner completeness, physical projection, or P/E set equality.
"""

import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path

from exact_e_factor_count import MODEL_SCHEMA_V1, MODEL_SCHEMA_V2, read_model


SCHEMA = "current-pe-e-factor-group-differential-v1"
CODE = {"ALLOW": 0, "REJECT": 1, "UNKNOWN": 2}
STATIC_FIELDS = ("cell", "programSha256", "sourceIdentity", "domains")
MAX_FACTOR_CELLS = 40_000_000
MAX_GROUPED_CELLS = 210_000_000


def _bits(truth, cells):
    if isinstance(truth, list):
        data = bytearray((cells + 3) // 4)
        for index, status in enumerate(truth):
            data[index >> 2] |= CODE[status] << ((index & 3) * 2)
    else:
        data = base64.b64decode(truth["data"], validate=True)
    return int.from_bytes(data, "little")


def _conjoin(left, right, low_mask):
    """Conjoin four-state-free packed cells: REJECT > UNKNOWN > ALLOW."""
    combined = left | right
    rejected = combined & low_mask
    unknown = (combined & (low_mask << 1)) & ~(rejected << 1)
    return rejected | unknown


def _source_identity_same_ignoring_logical_input_order(left, right):
    if not isinstance(left, dict) or not isinstance(right, dict):
        return False
    if ({key: value for key, value in left.items() if key != "logicalInputs"} !=
            {key: value for key, value in right.items() if key != "logicalInputs"}):
        return False
    left_inputs, right_inputs = left.get("logicalInputs"), right.get("logicalInputs")
    if not isinstance(left_inputs, list) or not isinstance(right_inputs, list):
        return False
    canonical = lambda row: json.dumps(row, sort_keys=True, separators=(",", ":"))
    return Counter(map(canonical, left_inputs)) == Counter(map(canonical, right_inputs))


def compare(old, new):
    if old.get("schema") not in (MODEL_SCHEMA_V1, MODEL_SCHEMA_V2) or new.get("schema") != MODEL_SCHEMA_V2:
        raise ValueError("expected legacy-v1 or grouped-v2 and grouped-v2 E model pair")
    for field in STATIC_FIELDS:
        if old.get(field) != new.get(field):
            if (field == "sourceIdentity" and old["schema"] == MODEL_SCHEMA_V2 and
                    _source_identity_same_ignoring_logical_input_order(
                        old.get(field), new.get(field))):
                continue
            raise ValueError("E models have different static " + field)
    if old["schema"] == MODEL_SCHEMA_V2:
        for field in ("sourceFactorScopes", "nativeFactorCount", "materializedFactorCount"):
            if old.get(field) != new.get(field):
                raise ValueError("grouped E models have different " + field)
        left_groups, right_groups = old["factors"], new["factors"]
        if len(left_groups) != len(right_groups):
            raise ValueError("grouped E factor count differs")
        checked = 0
        for index, (left, right) in enumerate(zip(left_groups, right_groups)):
            for field in ("scope", "cells", "sourceFactorIndices"):
                if left.get(field) != right.get(field):
                    raise ValueError("grouped E factor metadata differs at factor " + str(index))
            cells = int(left["cells"])
            if cells < 1 or cells > MAX_FACTOR_CELLS or checked + cells > MAX_GROUPED_CELLS:
                raise ValueError("grouped factor differential resource budget exhausted")
            if _bits(left["truth"], cells) != _bits(right["truth"], cells):
                raise ValueError("grouped E factor truth differs at factor " + str(index))
            checked += cells
        native_count = old["nativeFactorCount"]
    else:
        native_count, checked = _compare_legacy_to_grouped(old, new)
    left_projection = old.get("physicalProjection")
    right_projection = new.get("physicalProjection")
    if not isinstance(left_projection, dict) or not isinstance(right_projection, dict):
        raise ValueError("E physical projection metadata missing")
    projection_keys = set(left_projection) | set(right_projection)
    result = {"nativeFactors": native_count, "groupedFactors": len(new["factors"]),
            "groupedTruthCellsChecked": str(checked),
            "physicalProjectionContractSame": old.get("physicalProjectionContract") ==
                new.get("physicalProjectionContract"),
            "physicalProjectionSame": left_projection == right_projection,
            "physicalProjectionDifferentKeys": sorted(key for key in projection_keys
                                                       if left_projection.get(key) !=
                                                          right_projection.get(key))}
    if old["schema"] == MODEL_SCHEMA_V2:
        result["sourceIdentityLogicalInputOrderSame"] = old["sourceIdentity"] == new["sourceIdentity"]
    return result


def _compare_legacy_to_grouped(old, new):
    native = old["factors"]
    groups = new["factors"]
    scopes = [factor["scope"] for factor in native]
    if (new.get("sourceFactorScopes") != scopes or
            new.get("nativeFactorCount") != len(native) or
            new.get("materializedFactorCount") != len(groups)):
        raise ValueError("grouped source factor inventory differs from legacy model")
    seen = set()
    checked = 0
    for index, group in enumerate(groups):
        sources = group.get("sourceFactorIndices")
        if (not isinstance(sources, list) or not sources or
                any(type(source) is not int or source < 0 or source >= len(native)
                    for source in sources) or
                sources != sorted(set(sources)) or
                any(source in seen for source in sources)):
            raise ValueError("grouped source factor partition invalid")
        seen.update(sources)
        if any(scopes[source] != group["scope"] for source in sources):
            raise ValueError("grouped factor scope differs from native source")
        cells = int(group["cells"])
        if cells < 1 or cells > MAX_FACTOR_CELLS or checked + cells > MAX_GROUPED_CELLS:
            raise ValueError("grouped factor differential resource budget exhausted")
        low_mask = (1 << (2 * cells)) // 3
        expected = 0
        for source in sources:
            expected = _conjoin(expected,
                                _bits(native[source]["truth"], cells), low_mask)
        actual = _bits(group["truth"], cells)
        if expected != actual:
            raise ValueError("grouped factor truth differs at factor " + str(index))
        checked += cells
    if seen != set(range(len(native))):
        raise ValueError("grouped factor partition omits native factors")
    return len(native), checked


def _file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def run(old_path, new_path):
    old, old_sha, _, _ = read_model(old_path, allow_legacy_v1=True)
    new, new_sha, _, _ = read_model(new_path)
    result = compare(old, new)
    return {"schema": SCHEMA, "status": "PASS", "claim":
            "ONE_PAIR_EXACT_FACTOR_RELATION_DIFFERENTIAL_ONLY",
            "toolSha256": _file_sha256(Path(__file__)),
            "cell": old["cell"], "oldModelPath": str(old_path.resolve()),
            "newModelPath": str(new_path.resolve()),
            "oldModelSha256": old_sha, "newModelSha256": new_sha,
            "oldGzipSha256": _file_sha256(old_path),
            "newGzipSha256": _file_sha256(new_path),
            **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old", type=Path, required=True)
    parser.add_argument("--new", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--verify", action="store_true",
                        help="recompute and compare an existing receipt without rewriting it")
    args = parser.parse_args()
    result = run(args.old, args.new)
    if args.verify:
        if json.loads(args.receipt.read_text()) != result:
            raise ValueError("stored factor differential receipt differs from replay")
    else:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
