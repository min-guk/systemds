#!/usr/bin/env python3
"""Fail closed when a factorized receipt loses selected input/proof authority."""

import argparse
import json
import pathlib


class AuthorityRegression(RuntimeError):
    pass


def require_field(row: dict, field: str, expected_type: type, context: str):
    if field not in row:
        raise AuthorityRegression(f"{context}: required field {field} is absent")
    value = row[field]
    if not isinstance(value, expected_type):
        raise AuthorityRegression(
            f"{context}: required field {field} has invalid type {type(value).__name__}")
    return value


def selected_by_opcode(receipt: dict, opcodes: tuple[str, ...]) -> dict[str, dict]:
    selected: dict[str, dict] = {}
    for row in receipt.get("selectedOccurrences", []):
        opcode = row.get("opcode")
        if opcode not in opcodes:
            continue
        if opcode in selected:
            raise AuthorityRegression(f"duplicate selected opcode: {opcode}")
        selected[opcode] = row
    missing = sorted(set(opcodes) - selected.keys())
    if missing:
        raise AuthorityRegression(f"missing selected opcodes: {missing}")
    return selected


def candidate_support_by_opcode(receipt: dict, opcodes: tuple[str, ...]) -> dict[str, dict]:
    rows = receipt.get("selectedCandidateSupport")
    if not isinstance(rows, list):
        raise AuthorityRegression("structured selectedCandidateSupport inventory is absent")
    selected: dict[str, dict] = {}
    for opcode in opcodes:
        matches = [row for row in rows if opcode in str(row.get("exactRule", ""))]
        if len(matches) != 1:
            raise AuthorityRegression(
                f"{opcode}: expected one selected candidate support row, found {len(matches)}")
        selected[opcode] = matches[0]
    return selected


def compare_receipts(baseline: dict, candidate: dict, opcodes: tuple[str, ...],
                     required_source: str) -> dict:
    before = selected_by_opcode(baseline, opcodes)
    after = selected_by_opcode(candidate, opcodes)
    before_support = candidate_support_by_opcode(baseline, opcodes)
    after_support = candidate_support_by_opcode(candidate, opcodes)
    comparisons = []
    for opcode in opcodes:
        left = before[opcode]
        right = after[opcode]
        left_authorities = require_field(
            left, "inputAuthorities", list, f"baseline {opcode}")
        right_authorities = require_field(
            right, "inputAuthorities", list, f"candidate {opcode}")
        if left.get("occurrence") != right.get("occurrence"):
            raise AuthorityRegression(f"{opcode}: selected occurrence changed")
        if left.get("physicalState") != right.get("physicalState"):
            raise AuthorityRegression(f"{opcode}: selected physical state changed")
        if left_authorities != right_authorities:
            raise AuthorityRegression(f"{opcode}: exact input authority changed")
        for field, expected_type in (("exactRule", str), ("emission", str),
                                     ("realization", str), ("support", str),
                                     ("proofKeys", list), ("inputBindings", list)):
            baseline_value = require_field(
                before_support[opcode], field, expected_type,
                f"baseline selectedCandidateSupport {opcode}")
            candidate_value = require_field(
                after_support[opcode], field, expected_type,
                f"candidate selectedCandidateSupport {opcode}")
            if baseline_value != candidate_value:
                raise AuthorityRegression(
                    f"{opcode}: selected candidate {field} changed")
        left_support = left.get("supportClause")
        right_support = right.get("supportClause")
        if not isinstance(left_support, dict) or not isinstance(right_support, dict):
            raise AuthorityRegression(f"{opcode}: selected support clause is absent")
        left_proofs = left_support.get("proofDependencies")
        right_proofs = right_support.get("proofDependencies")
        left_bindings = left_support.get("inputBindings")
        right_bindings = right_support.get("inputBindings")
        if not isinstance(left_proofs, list) or not isinstance(right_proofs, list):
            raise AuthorityRegression(f"{opcode}: proof dependency inventory is absent")
        if not isinstance(left_bindings, list) or not isinstance(right_bindings, list):
            raise AuthorityRegression(f"{opcode}: input binding inventory is absent")
        if left_proofs != right_proofs:
            raise AuthorityRegression(f"{opcode}: proof dependencies changed")
        if left_bindings != right_bindings:
            raise AuthorityRegression(f"{opcode}: exact support input bindings changed")
        direct = [binding for binding in right_bindings
                  if binding.get("inputPosition") == 0 and binding.get("kind") == "DIRECT"]
        if len(direct) != 1:
            raise AuthorityRegression(
                f"{opcode}: expected one DIRECT support binding at input 0, found {len(direct)}")
        source_text = "|".join(str(direct[0].get(field, ""))
                               for field in ("sourceRule", "sourceOwner", "sourceRealization"))
        if required_source not in source_text:
            raise AuthorityRegression(
                f"{opcode}: DIRECT input 0 does not retain source {required_source}")
        comparisons.append({
            "opcode": opcode,
            "physicalState": right["physicalState"],
            "baselineAuthorityRepresentation": left.get("authority"),
            "candidateAuthorityRepresentation": right.get("authority"),
            "proofDependencyCount": len(right_proofs),
            "inputBindingCount": len(right_bindings),
            "directInput0SourceOwner": direct[0]["sourceOwner"],
            "selectedCandidateSupport": after_support[opcode],
        })
    return {"status": "PASSED", "requiredSource": required_source,
            "comparisons": comparisons}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=pathlib.Path)
    parser.add_argument("candidate", type=pathlib.Path)
    parser.add_argument("--opcode", action="append", dest="opcodes",
                        default=["q(wsloss)", "q(wcemm)"])
    parser.add_argument("--required-source", default="X_PROTECTED")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    try:
        result = compare_receipts(
            json.loads(args.baseline.read_text(encoding="utf-8")),
            json.loads(args.candidate.read_text(encoding="utf-8")),
            tuple(args.opcodes), args.required_source)
        returncode = 0
    except AuthorityRegression as failure:
        result = {"status": "FAILED", "error": str(failure)}
        returncode = 1
    payload = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
    return returncode


if __name__ == "__main__":
    raise SystemExit(main())
