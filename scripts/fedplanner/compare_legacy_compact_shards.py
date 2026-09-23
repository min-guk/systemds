#!/usr/bin/env python3
"""Compare every legacy P row with compact dictionary rows, shard by shard.

Both exporters write the same canonical physical-plan-v1 JSON line. Comparing
the full line bytes avoids reparsing hundreds of gigabytes of repeated plans.
The compact reference stream is checked separately by its artifact verifier.
"""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys

from run_current_pe_cell import read, save, sha
from verify_compact_physical_shard import verify as verify_compact


SHARD = re.compile(r"p-state-(\d+)-(\d+)\.receipt\.json")
IDENTITY_FIELDS = ("cell", "source", "programSha256", "conditionSha256",
                   "sourceFiles", "raw", "accepted", "rejected", "unknown")


def compare_one(old_dir, compact_dir, receipt_name, verification_root, output_dir,
                resume=False, *, e=False):
    old_receipt_path = old_dir / receipt_name
    compact_receipt_path = compact_dir / ("e-receipt.json" if e else receipt_name)
    old_receipt = read(old_receipt_path)
    compact_receipt = read(compact_receipt_path)
    if (old_receipt.get("status") != "COMPLETE" or
            compact_receipt.get("status") != "COMPLETE" or
            any(old_receipt.get(field) != compact_receipt.get(field)
                for field in IDENTITY_FIELDS) or
            (not e and any(old_receipt.get(field) != compact_receipt.get(field)
                           for field in ("start", "stop", "emittedRows"))) or
            (e and (old_receipt.get("accepted") != compact_receipt.get("emittedRows") or
                    old_receipt.get("acceptedOrdinalsSha256") !=
                    compact_receipt.get("acceptedOrdinalsSha256")))):
        raise ValueError("old/compact native shard receipts differ: " + receipt_name)
    name = receipt_name.removesuffix(".receipt.json")
    old_rows = old_dir / ("e-rows.jsonl.gz" if e else name + ".jsonl.gz")
    dictionary = compact_dir / ("e-dictionary.jsonl.gz" if e else
                                name + "-dictionary.jsonl.gz")
    references = compact_dir / ("e-refs.tsv.gz" if e else name + "-refs.tsv.gz")
    paths = (old_rows, dictionary, references, old_receipt_path, compact_receipt_path)
    binding = {path.name: sha(path) for path in paths}
    binding["verifierSha256"] = sha(Path(__file__).with_name(
        "verify_compact_physical_shard.py"))
    binding["runnerSha256"] = sha(__file__)
    result_path = output_dir / receipt_name
    if resume and result_path.is_file():
        previous = read(result_path)
        if previous.get("binding") == binding and previous.get("status") == "EQUAL":
            return previous
    if binding[old_rows.name] != old_receipt.get("rowsSha256") or \
            binding[dictionary.name] != compact_receipt.get("dictionarySha256") or \
            binding[references.name] != compact_receipt.get("referencesSha256"):
        raise ValueError("stored row or reference digest differs: " + receipt_name)
    compact_result = verify_compact(compact_receipt_path, verification_root,
                                    expected_dictionary=dictionary,
                                    expected_references=references)
    if compact_result["proofCount"] != int(compact_receipt["accepted"]):
        raise ValueError("compact shard proof count differs: " + receipt_name)
    with gzip.open(dictionary, "rb") as source:
        lines = {line[:-1] for line in source}
    if len(lines) != compact_receipt["dictionaryCount"]:
        raise ValueError("compact dictionary contains duplicate rows: " + receipt_name)
    unseen = lines.copy()
    old_count = 0
    extra = None
    with gzip.open(old_rows, "rb") as source:
        for line in source:
            old_count += 1
            if not line.endswith(b"\n") or line == b"\n":
                raise ValueError("legacy row has malformed line: " + receipt_name)
            row = line[:-1]
            if row in lines:
                unseen.discard(row)
            elif extra is None:
                extra = hashlib.sha256(row).hexdigest()
    if old_count != int(old_receipt["accepted" if e else "emittedRows"]):
        raise ValueError("legacy row count differs from receipt: " + receipt_name)
    result = {"schema": "legacy-compact-physical-shard-difference-v1",
              "cell": old_receipt["cell"], "shard": name, "binding": binding,
              "status": "EQUAL" if extra is None and not unseen else "DIFFERENT",
              "legacyRows": old_count, "compactDictionaryRows": len(lines),
              "firstLegacyOnlySha256": extra,
              "firstCompactOnlySha256": (hashlib.sha256(min(unseen)).hexdigest()
                                         if unseen else None)}
    save(result_path, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-dir", type=Path, required=True)
    parser.add_argument("--compact-dir", type=Path, required=True)
    parser.add_argument("--verification-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--include-e", action="store_true",
                        help="also compare the complete legacy E rows with compact E")
    args = parser.parse_args()
    legacy = args.legacy_dir.resolve()
    compact = args.compact_dir.resolve()
    names = sorted(path.name for path in legacy.iterdir() if SHARD.fullmatch(path.name))
    if not names or names != sorted(path.name for path in compact.iterdir()
                                 if SHARD.fullmatch(path.name)):
        raise ValueError("legacy/compact shard frontier differs")
    output = args.output_dir.resolve()
    results = []
    for name in names:
        result = compare_one(legacy, compact, name,
                             args.verification_root.resolve(), output, args.resume)
        results.append(result)
        print(json.dumps({"shard": result["shard"], "status": result["status"],
                          "legacyRows": result["legacyRows"]}, sort_keys=True), flush=True)
    if args.include_e:
        result = compare_one(legacy, compact, "e-rows.receipt.json",
                             args.verification_root.resolve(), output, args.resume, e=True)
        results.append(result)
        print(json.dumps({"shard": result["shard"], "status": result["status"],
                          "legacyRows": result["legacyRows"]}, sort_keys=True), flush=True)
    summary = {"schema": "legacy-compact-physical-matrix-difference-v1",
               "cell": results[0]["cell"], "status": "EQUAL" if all(
                   result["status"] == "EQUAL" for result in results) else "DIFFERENT",
               "shards": len(results), "legacyRows": sum(result["legacyRows"] for result in results),
               "compactDictionaryRows": sum(result["compactDictionaryRows"] for result in results)}
    save(output / "summary.json", summary)
    print(json.dumps(summary, sort_keys=True))
    return 0 if summary["status"] == "EQUAL" else 1


if __name__ == "__main__":
    sys.exit(main())
