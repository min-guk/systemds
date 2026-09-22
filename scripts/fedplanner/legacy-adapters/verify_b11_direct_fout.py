#!/usr/bin/env python3
"""B-11 historical direct-FOUT physical comparison with a closed profile guard."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def key(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def load(receipt_path):
    receipt = json.loads(receipt_path.read_text())
    require(receipt["fixture"] == "B-11" and receipt["nativeCoverage"] == "COMPLETE"
            and receipt["errors"] == 0 and receipt["start"] == 0
            and receipt["stop"] == receipt["rawCount"], "B-11 native coverage incomplete")
    rows_path = Path(receipt["rowsPath"])
    catalog_path = Path(receipt["sourceCatalogPath"])
    require(sha(rows_path) == receipt["rowsSha256"], "B-11 native rows digest differs")
    require(sha(catalog_path) == receipt["sourceCatalogSha256"],
            "B-11 source catalog digest differs")
    catalog = json.loads(catalog_path.read_text())
    require(catalog["version"] == receipt["version"] and
            catalog["inputDmlSha256"] == receipt["inputDmlSha256"] and
            catalog["fixture"] == "B-11", "B-11 source catalog provenance differs")
    accepted = []
    counts = {"EMITTED": 0, "REJECTED": 0, "ERROR": 0}
    opener = gzip.open if rows_path.suffix == ".gz" else open
    with opener(rows_path, "rt", encoding="utf-8") as stream:
        for ordinal, line in enumerate(stream):
            row = json.loads(line)
            require(int(row["ordinal"]) == ordinal and row["version"] == receipt["version"]
                    and row["inputDmlSha256"] == receipt["inputDmlSha256"],
                    "B-11 ordinal or provenance drift")
            counts[row["status"]] += 1
            if row["status"] == "EMITTED":
                accepted.append(row)
    require(sum(counts.values()) == receipt["rawCount"] and
            counts["EMITTED"] == receipt["emitted"] and
            counts["REJECTED"] == receipt["rejected"] and counts["ERROR"] == 0,
            "B-11 row coverage differs")
    return receipt, catalog, accepted


def project_b1(row, catalog):
    nodes = {key(node["occurrence"]): node for node in catalog["nodes"]}
    choices = {key(choice["occurrence"]): choice for choice in row["choices"]}
    require(len(nodes) == len(catalog["nodes"]) and len(choices) == len(row["choices"])
            and set(nodes) == set(choices), "B-11 occurrence domain incomplete")
    edges = {(key(edge["consumer"]), edge["inputPosition"]): edge["producer"]
             for edge in catalog["orderedInputs"]}
    require(len(edges) == len(catalog["orderedInputs"]), "B-11 duplicate source edge")
    require(not catalog["logicalInputs"], "B-11 logical boundary outside profile")
    receipts = {}
    if row["source"] == "P":
        require(not row["emittedRelocationActions"], "B-11 P emitted relocation")
        receipts = {key(item["rule"]["parentOccurrence"]): item
                    for item in row["candidateReceipts"]}
        require(len(receipts) == len(row["candidateReceipts"]) and set(receipts) == set(nodes),
                "B-11 P candidate authority incomplete")
    physical = []
    for owner in sorted(nodes):
        node = nodes[owner]
        choice = choices[owner]
        state = choice["state"]
        require(node["emittedWork"] and node["sourceHop"] is not None,
                "B-11 source occurrence unresolved")
        if row["source"] == "E":
            require(choice["authority"] == "CAPTURED_RULE" and
                    choice["relocation"] is None and choice["derivedFout"] is None,
                    "B-11 E authority/action outside profile")
            realization = choice["realization"]
            emission = choice["candidateEmission"]
            inputs = choice["orderedInputs"]
            authorities = choice["inputAuthorities"]
            require(choice["candidateRule"]["parentOccurrence"] == choice["occurrence"]
                    and len(inputs) == len(authorities), "B-11 E candidate owner/input differs")
        else:
            receipt = receipts[owner]
            require(not receipt["fallbackMaterializations"],
                    "B-11 P fallback action outside profile")
            realization = receipt["realization"]["key"] if receipt["realization"] else None
            emission = receipt["emission"]
            inputs = receipt["rule"]["orderedInputs"]
            authorities = None
        require(realization is not None and
                realization["layoutKind"] in {"LOCAL", "DURABLE_MAP"} and
                realization["nativeLineage"] is None and
                emission["emissionState"]["placementState"] == state and
                not emission["emissionState"]["derivedFedFout"],
                "B-11 realization or emission outside profile")
        require((realization["layoutKind"] == "DURABLE_MAP") ==
                (realization["durableAnchor"] is not None),
                "B-11 durable geometry incomplete")
        bindings = []
        for position, input_state in enumerate(inputs):
            source = edges.get((owner, position))
            if input_state == {"presence": "ABSENT_LOCAL", "fType": None}:
                kind = "ABSENT_LOCAL"
                require(source is None, "B-11 absent input has physical edge")
            else:
                require(input_state == {"presence": "PRESENT", "fType": "ROW"}
                        and source is not None, "B-11 input outside direct-FOUT profile")
                source_choice = choices.get(key(source))
                require(source_choice is not None and
                        source_choice["state"]["output"] == "FOUT" and
                        source_choice["state"]["fType"] == "ROW",
                        "B-11 direct source is not FOUT ROW")
                kind = "DIRECT_FOUT"
            if authorities is not None:
                authority = authorities[position]
                require(authority["inputPosition"] == position and
                        authority["kind"] == ("NATIVE_LOCAL" if kind == "ABSENT_LOCAL"
                                              else "DIRECT_FOUT") and
                        authority["relocationAction"] is None and
                        authority["sourceDecision"] == source,
                        "B-11 E input authority differs from source edge")
            bindings.append({"position": position, "kind": kind, "source": source,
                             "ftype": input_state["fType"]})
        physical.append({"occurrence": node["occurrence"], "sourceHop": node["sourceHop"],
                         "valueVersion": node["valueVersion"], "state": state,
                         "realization": realization, "bindings": bindings})
    return key({"nodes": physical, "actions": []})


def compare(e_receipt_path, p_receipt_path):
    e, e_catalog, e_rows = load(e_receipt_path)
    p, p_catalog, p_rows = load(p_receipt_path)
    require(e["source"] == "E" and p["source"] == "P" and
            e["version"] == p["version"] and
            e["sourceCatalogSha256"] == p["sourceCatalogSha256"],
            "B-11 P/E revision or source catalog differs")
    result = {"fixture": "B-11", "version": e["version"],
              "scope": "DIRECT_FOUT_NO_EMITTED_ACTION_B11",
              "eNativeAccepted": len(e_rows), "pNativeAccepted": len(p_rows)}
    if e["version"].startswith("ffb7be5"):
        result.update(status="UNRESOLVED_B0_REALIZATION",
                      reason="B0 candidate receipts and captured E alternatives do not expose "
                             "a selected realization/layout key; E also accepts a DURABLE_ANCHOR "
                             "source alternative absent from P's selected candidate receipt",
                      ambiguousOccurrence="root-0/input-0/input-0/input-0",
                      eSourceAuthorityKinds=sorted({choice["authority"] for row in e_rows
                                                   for choice in row["choices"] if
                                                   choice["occurrence"]["emittedHopInstance"] ==
                                                   "root-0/input-0/input-0/input-0"}))
        return result
    e_set = {project_b1(row, e_catalog) for row in e_rows}
    p_set = {project_b1(row, p_catalog) for row in p_rows}
    result.update(ePhysicalCount=len(e_set), pPhysicalCount=len(p_set),
                  eMinusP=len(e_set - p_set), pMinusE=len(p_set - e_set),
                  physicalSha256=hashlib.sha256("\n".join(sorted(e_set)).encode()).hexdigest(),
                  status="SELF_EQUAL_B11_PROFILE" if e_set == p_set else "DIFFERENT")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--e-receipt", type=Path, required=True)
    parser.add_argument("--p-receipt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.e_receipt, args.p_receipt)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))
    if result["status"] == "DIFFERENT":
        raise SystemExit(2)


if __name__ == "__main__":
    main()
