#!/usr/bin/env python3
"""Recheck a compressed UNKNOWN fixture export; this is not a plan certificate."""

import argparse
import gzip
import hashlib
import itertools
import json
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[2]


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def recheck(folder):
    folder = Path(folder).resolve()
    receipt = json.loads((folder / "receipt.json").read_text())
    packed = json.loads((folder / "pack.json").read_text())
    manifest = json.loads((folder.parent / "manifest.json").read_text())
    if (receipt.get("schema") != "raw-fixture-export-v1" or receipt.get("status") != "UNKNOWN"
            or packed.get("schema") != "plan-space-raw-fixture-pack-v1"
            or packed.get("status") != "UNKNOWN" or manifest.get("status") != "UNKNOWN"):
        raise ValueError("fixture archive must retain explicit UNKNOWN coverage")
    matching = [item for item in manifest.get("exports", [])
                if item.get("packedFiles") == str(folder / "pack.json")]
    if len(matching) != 1 or matching[0].get("receipt") != str(folder / "receipt.json"):
        raise ValueError("fixture manifest does not bind this archive")
    if (matching[0].get("rawDomainSize") != receipt.get("rawDomainSize")
            or matching[0].get("range") != [int(receipt["start"]), int(receipt["stop"]) ]):
        raise ValueError("fixture manifest range/domain changed")
    for name, expected in manifest.get("sourceSha256", {}).items():
        source = (SOURCE / name).resolve()
        if not source.is_relative_to(SOURCE) or sha256(source) != expected:
            raise ValueError("fixture adapter source drift: " + name)
    streams = []
    for name, key in (("rows.jsonl", "rowsSha256"), ("audit.jsonl", "auditSha256")):
        entry = packed.get("files", {}).get(name)
        if not isinstance(entry, dict) or entry.get("rawSha256") != receipt.get(key):
            raise ValueError("fixture raw hash binding changed: " + name)
        archive = folder / entry.get("archive", "")
        if archive.resolve().parent != folder or sha256(archive) != entry.get("archiveSha256"):
            raise ValueError("fixture archive hash changed: " + name)
        streams.append((archive, entry))
    count = 0
    digests = [hashlib.sha256(), hashlib.sha256()]
    sizes = [0, 0]
    with gzip.open(streams[0][0], "rb") as rows, gzip.open(streams[1][0], "rb") as audits:
        for row_line, audit_line in itertools.zip_longest(rows, audits):
            if row_line is None or audit_line is None:
                raise ValueError("fixture row/audit length mismatch")
            for index, line in enumerate((row_line, audit_line)):
                digests[index].update(line)
                sizes[index] += len(line)
            row, audit = json.loads(row_line), json.loads(audit_line)
            if (row.get("schema") != "raw-fixture-export-v1" or row.get("globalCoverageStatus") != "UNKNOWN"
                    or row.get("ordinal") != int(receipt["start"]) + count
                    or audit.get("index") != int(receipt["start"]) + count
                    or audit.get("status") != "UNKNOWN" or audit.get("raw") != row):
                raise ValueError("fixture ordinal or UNKNOWN audit changed")
            count += 1
    for index, (_, entry) in enumerate(streams):
        if digests[index].hexdigest() != entry["rawSha256"] or sizes[index] != entry["rawBytes"]:
            raise ValueError("decompressed fixture bytes changed")
    if count != int(receipt["processed"]) or count != int(receipt["stop"]) - int(receipt["start"]):
        raise ValueError("fixture range not fully covered")
    return {"integrity": "PASS", "planCoverage": "UNKNOWN", "kind": receipt["kind"],
            "fixture": receipt["fixture"], "rawRows": count}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = recheck(args.folder)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        result = {"integrity": "FAIL", "reason": str(error)}
    print(json.dumps(result, sort_keys=True))
    return 0 if result["integrity"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
