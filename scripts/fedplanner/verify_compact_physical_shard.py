#!/usr/bin/env python3
"""Verify a compact physical proof shard and reconstruct its exact plan set.

The producer emits first-seen complete rows in a gzip dictionary and one
ordinal/row-ID reference per accepted native proof. This verifier checks every
reference and compares full canonical physical bytes, not dictionary IDs.
"""

import gzip
import hashlib
import json
from pathlib import Path
import re
import sys


REF = re.compile(rb"(0|[1-9][0-9]*)\t(0|[1-9][0-9]*)\n\Z")


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(receipt_path, verification_root, *, expected_dictionary=None,
           expected_references=None):
    calibration = (Path(verification_root) / "calibration").resolve()
    if not (calibration / "compact_physical_identity.py").is_file():
        raise ValueError("frozen compact physical identity verifier missing")
    for name in ("compact_physical_identity", "closed_physical_identity", "plan_space_verify"):
        loaded = sys.modules.get(name)
        if loaded is not None and Path(loaded.__file__).resolve() != calibration / (name + ".py"):
            raise ValueError("physical identity verification root changed within process")
    sys.path.insert(0, str(calibration))
    from compact_physical_identity import CompactPlanSetBuilder, canonical_plan_set

    receipt_path = Path(receipt_path).resolve()
    receipt = json.loads(receipt_path.read_text())
    if (receipt.get("schema") != "closed-planning-physical-shard-compact-v1" or
            receipt.get("status") != "COMPLETE" or receipt.get("source") not in
            ("P_C0", "E_C0") or receipt.get("unknown") != "0"):
        raise ValueError("compact shard schema/status/unknown invalid")
    dictionary = Path(receipt["rows"])
    references = Path(receipt["references"])
    if (not dictionary.is_absolute() or not references.is_absolute() or
            dictionary.is_symlink() or references.is_symlink() or
            dictionary == references or
            (expected_dictionary is not None and dictionary != Path(expected_dictionary)) or
            (expected_references is not None and references != Path(expected_references)) or
            sha(dictionary) != receipt.get("dictionarySha256") or
            sha(references) != receipt.get("referencesSha256")):
        raise ValueError("compact dictionary/reference file binding differs")
    raw = int(receipt["raw"])
    accepted = int(receipt["accepted"])
    rejected = int(receipt["rejected"])
    if min(raw, accepted, rejected) < 0 or raw != accepted + rejected:
        raise ValueError("compact shard raw coverage differs")
    builder = CompactPlanSetBuilder()
    dictionary_rows = 0
    with gzip.open(dictionary, "rb") as stream:
        for line in stream:
            if not line.endswith(b"\n") or line == b"\n":
                raise ValueError("compact dictionary has malformed line")
            builder.add(json.loads(line))
            dictionary_rows += 1
    if dictionary_rows != receipt.get("dictionaryCount") or dictionary_rows > accepted:
        raise ValueError("compact dictionary count differs")
    # A repeated canonical identity can have two native JSON encodings. The
    # reference IDs still distinguish producer entries, while the physical set
    # correctly merges both after canonicalization.
    seen = set()
    last_ordinal = -1
    seen_ordinals = set() if receipt["source"] == "P_C0" else None
    next_first_id = 0
    ordinal_hash = hashlib.sha256()
    proof_count = 0
    with gzip.open(references, "rb") as stream:
        for line in stream:
            match = REF.fullmatch(line)
            if match is None:
                raise ValueError("compact proof reference malformed")
            ordinal, plan_id = map(int, match.groups())
            if (plan_id >= dictionary_rows or
                    (seen_ordinals is None and ordinal <= last_ordinal) or
                    (seen_ordinals is not None and ordinal in seen_ordinals)):
                raise ValueError("compact proof ordinal/plan reference invalid")
            if seen_ordinals is not None:
                seen_ordinals.add(ordinal)
            if plan_id not in seen:
                if plan_id != next_first_id:
                    raise ValueError("compact dictionary is not first-occurrence ordered")
                next_first_id += 1
                seen.add(plan_id)
            last_ordinal = ordinal
            ordinal_hash.update(str(ordinal).encode("ascii") + b"\n")
            proof_count += 1
    if proof_count != accepted or next_first_id != dictionary_rows or (
            receipt.get("emittedRows") is not None and
            int(receipt["emittedRows"]) != accepted):
        raise ValueError("compact proof coverage differs from receipt")
    if (receipt.get("acceptedOrdinalsSha256") is not None and
            receipt["acceptedOrdinalsSha256"] != ordinal_hash.hexdigest()):
        raise ValueError("compact accepted ordinal digest differs")
    wire = builder.build()
    wire["proofCount"] = proof_count
    identities = canonical_plan_set(wire)
    return {"receipt": receipt, "wire": wire, "identities": identities,
            "proofCount": proof_count, "dictionaryCount": dictionary_rows,
            "physicalCount": len(identities),
            "acceptedOrdinalsSha256": ordinal_hash.hexdigest()}
