#!/usr/bin/env python3
"""Closed B-01 local-only historical self-equality check.

This deliberately rejects every nonlocal authority/action. It is not the
general historical physical-plan decoder.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def same(left, right, reason):
    if left != right:
        raise ValueError(reason)


def load(receipt_path):
    receipt = json.loads(receipt_path.read_text())
    same(receipt["fixture"], "B-01", "fixture outside local B-01 scope")
    same(receipt["nativeCoverage"], "COMPLETE", "native range incomplete")
    same(receipt["errors"], 0, "native error")
    same(receipt["physicalDecode"], "LEGACY_REPRESENTATION_LIMIT",
         "unexpected prior physical decode claim")
    catalog_path = Path(receipt["sourceCatalogPath"])
    same(sha(catalog_path), receipt["sourceCatalogSha256"], "source catalog digest differs")
    catalog = json.loads(catalog_path.read_text())
    same(catalog["inputDmlSha256"], receipt["inputDmlSha256"], "DML digest differs")
    same(catalog["version"], receipt["version"], "catalog version differs")
    same(catalog["fixture"], receipt["fixture"], "catalog fixture differs")
    if catalog["logicalInputs"] or any(node["anchors"] for node in catalog["nodes"]):
        raise ValueError("nonlocal catalog fact outside B-01 scope")
    rows_path = Path(receipt["rowsPath"])
    same(sha(rows_path), receipt["rowsSha256"], "native rows digest differs")
    opener = gzip.open if rows_path.suffix == ".gz" else open
    accepted = []
    counts = {"EMITTED": 0, "REJECTED": 0, "ERROR": 0}
    with opener(rows_path, "rt", encoding="utf-8") as stream:
        for expected, line in enumerate(stream):
            row = json.loads(line)
            same(int(row["ordinal"]), expected, "native ordinal gap")
            same(row["version"], receipt["version"], "native version drift")
            same(row["inputDmlSha256"], receipt["inputDmlSha256"], "native DML drift")
            counts[row["status"]] += 1
            if row["status"] == "EMITTED":
                accepted.append(row)
    same(counts["EMITTED"], receipt["emitted"], "accepted count differs")
    same(counts["REJECTED"], receipt["rejected"], "rejected count differs")
    same(counts["ERROR"], receipt["errors"], "error count differs")
    same(sum(counts.values()), receipt["rawCount"], "raw coverage differs")
    return receipt, catalog, accepted


def physical_local(row, catalog):
    sources = {canonical(node["occurrence"]): node for node in catalog["nodes"]}
    if len(sources) != len(catalog["nodes"]):
        raise ValueError("duplicate source occurrence")
    choices = {canonical(choice["occurrence"]): choice for choice in row["choices"]}
    same(set(choices), set(sources), "accepted assignment misses a source occurrence")
    if len(choices) != len(row["choices"]):
        raise ValueError("duplicate selected occurrence")
    receipts = {}
    if row["source"] == "P":
        if row["relocationReceipts"]:
            raise ValueError("nonlocal P relocation outside B-01 scope")
        receipts = {canonical(item["rule"]["parentOccurrence"]): item
                    for item in row["candidateReceipts"]}
        same(set(receipts), set(sources), "P candidate authority incomplete")
    result = []
    for key in sorted(sources):
        node, choice = sources[key], choices[key]
        state = choice["state"]
        same(state, {"execType": "CP", "output": "LOUT", "fType": None,
                     "shapeDependent": False}, "nonlocal state outside B-01 scope")
        if node["sourceHop"] is None or not node["emittedWork"]:
            raise ValueError("missing emitted source HOP")
        if row["source"] == "E":
            same(choice["authority"], "CAPTURED_RULE", "E authority outside B-01 scope")
            if choice["anchor"] or choice["relocation"] or choice["derivedFout"]:
                raise ValueError("E nonlocal action outside B-01 scope")
            if any(authority["kind"] != "NATIVE_LOCAL" or
                   authority["relocationAction"] is not None or
                   authority["sourceDecision"] is not None or
                   authority["expectedFType"] is not None or
                   authority["inputPosition"] != position
                   for position, authority in enumerate(choice["inputAuthorities"])):
                raise ValueError("E nonlocal input authority outside B-01 scope")
            realization = choice.get("realization")
            if realization is not None and (realization["layoutKind"] != "LOCAL" or
                                            realization["durableAnchor"] is not None or
                                            realization["nativeLineage"] is not None):
                raise ValueError("E nonlocal realization outside B-01 scope")
            rule = choice["candidateRule"]
            emission = choice["candidateEmission"]
            same(rule["parentOccurrence"], choice["occurrence"], "E rule owner differs")
            same(emission["emissionState"]["placementState"], state,
                 "E emission state differs")
            same(emission["emissionState"]["derivedFedFout"], False,
                 "E derived FOUT outside B-01 scope")
            inputs = rule["orderedInputs"]
            same(len(inputs), len(choice["inputAuthorities"]), "E input count differs")
        else:
            receipt = receipts[key]
            same(receipt["emission"]["emissionState"]["placementState"], state,
                 "P emission state differs")
            same(receipt["emission"]["emissionState"]["derivedFedFout"], False,
                 "P derived FOUT outside B-01 scope")
            if receipt["fallbackMaterializations"]:
                raise ValueError("P nonlocal materialization outside B-01 scope")
            realization = receipt.get("realization")
            if realization is not None and (realization["key"]["layoutKind"] != "LOCAL" or
                                            realization["key"]["durableAnchor"] is not None or
                                            realization["key"]["nativeLineage"] is not None):
                raise ValueError("P nonlocal realization outside B-01 scope")
            inputs = receipt["rule"]["orderedInputs"]
        if any(item != {"presence": "ABSENT_LOCAL", "fType": None} for item in inputs):
            raise ValueError("nonlocal input presence outside B-01 scope")
        result.append({"occurrence": node["occurrence"], "sourceHop": node["sourceHop"],
                       "valueVersion": node["valueVersion"], "state": state,
                       "inputPresence": inputs})
    return canonical({"nodes": result, "orderedInputs": catalog["orderedInputs"],
                      "logicalInputs": catalog["logicalInputs"], "geometry": []})


def compare(e_receipt, p_receipt):
    e, e_catalog, e_rows = load(e_receipt)
    p, p_catalog, p_rows = load(p_receipt)
    same(e["source"], "E", "E receipt source differs")
    same(p["source"], "P", "P receipt source differs")
    same(e["version"], p["version"], "P/E historical revision differs")
    same(e["inputDmlSha256"], p["inputDmlSha256"], "P/E DML differs")
    same(e["sourceCatalogSha256"], p["sourceCatalogSha256"],
         "P/E original source catalogs differ")
    e_set = {physical_local(row, e_catalog) for row in e_rows}
    p_set = {physical_local(row, p_catalog) for row in p_rows}
    return {"version": e["version"], "fixture": "B-01", "scope": "LOCAL_ONLY_B01",
            "eNativeAccepted": len(e_rows), "pNativeAccepted": len(p_rows),
            "ePhysicalCount": len(e_set), "pPhysicalCount": len(p_set),
            "eMinusP": len(e_set - p_set), "pMinusE": len(p_set - e_set),
            "physicalSha256": hashlib.sha256("\n".join(sorted(e_set)).encode()).hexdigest(),
            "status": "SELF_EQUAL" if e_set == p_set else "DIFFERENT"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--e-receipt", type=Path, required=True)
    parser.add_argument("--p-receipt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = compare(args.e_receipt, args.p_receipt)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
    print(json.dumps(report, sort_keys=True))
    if report["status"] != "SELF_EQUAL":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
