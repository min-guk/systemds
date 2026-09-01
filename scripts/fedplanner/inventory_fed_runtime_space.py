#!/usr/bin/env python3
"""Inventory FED runtime instruction families and aggregate observed audit coverage.

This tool deliberately keeps three evidence layers separate:

* the Java source inventory says which FED instruction classes exist;
* runtime-capability rows are concrete execution witnesses (positive on SUCCESS,
  fixture-local negative evidence on FAILURE); and
* runtime-conversion-frontier rows describe dispatch/conversion only and are not
  execution witnesses.

Candidate-space rows summarize the planner-published P domain, but this report does
not attempt to join P and R across unrelated JVMs.  Use
``compare_forced_attempt_runtime_space.py`` for strict attempt-local joins.
Consequently, an unobserved family is a coverage gap, not proof that the runtime
cannot execute that family, and zero observed gaps is not proof that R is complete.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator


CLASS_RE = re.compile(
    r"\bpublic\s+(?:(abstract|final)\s+)?class\s+(\w+)\s+extends\s+([\w.]+)",
    re.MULTILINE,
)
FTYPE_RE = re.compile(r"(?:FTypes\.)?FType\.([A-Z_]+)")
OUTPUT_RE = re.compile(r"(?:FEDInstruction\.)?FederatedOutput\.([A-Z_]+)")
OPCODE_RE = re.compile(r"Opcodes\.([A-Z0-9_]+)")
PARSE_RE = re.compile(r"\bparseInstruction\s*\(")
PROCESS_RE = re.compile(r"\bvoid\s+processInstruction\s*\(")


@dataclass(frozen=True)
class Family:
    file: str
    class_name: str
    modifier: str
    abstract: bool
    final: bool
    parent: str
    parse_overloads: int
    declares_process_instruction: bool
    ftype_references: str
    output_mode_references: str
    opcode_references: str
    source_lines: int


def csv_join(values: Iterable[Any]) -> str:
    return ";".join(sorted({str(value) for value in values if value not in (None, "")}))


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def write_csv(path: Path, rows: Iterable[dict[str, Any]], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def iter_jsonl(paths: Iterable[Path]) -> Iterator[tuple[Path, dict[str, Any]]]:
    for path in sorted(set(path.resolve() for path in paths)):
        with path.open(encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError(f"{path}:{line_no}: invalid JSONL: {exc}") from exc
                if not isinstance(value, dict):
                    raise ValueError(f"{path}:{line_no}: expected JSON object")
                yield path, value


def inventory_families(source_root: Path) -> list[Family]:
    fed_dir = source_root / "src/main/java/org/apache/sysds/runtime/instructions/fed"
    if not fed_dir.is_dir():
        raise FileNotFoundError(f"FED instruction directory not found: {fed_dir}")
    families: list[Family] = []
    unmatched: list[Path] = []
    # FEDFoutInstruction and FEDRefedInstruction are concrete planner/runtime
    # bridge instructions whose names do not match ``*FEDInstruction.java``.
    # Inventory every instruction class in the dedicated FED package instead of
    # relying on the narrower naming convention.
    for path in sorted(fed_dir.glob("*Instruction.java")):
        text = path.read_text(encoding="utf-8")
        match = CLASS_RE.search(text)
        if not match:
            unmatched.append(path)
            continue
        modifier, class_name, parent = match.groups()
        families.append(Family(
            file=str(path.relative_to(source_root)),
            class_name=class_name,
            modifier=modifier or "",
            abstract=modifier == "abstract",
            final=modifier == "final",
            parent=parent.rsplit(".", 1)[-1],
            parse_overloads=len(PARSE_RE.findall(text)),
            declares_process_instruction=bool(PROCESS_RE.search(text)),
            ftype_references=csv_join(FTYPE_RE.findall(text)),
            output_mode_references=csv_join(OUTPUT_RE.findall(text)),
            opcode_references=csv_join(OPCODE_RE.findall(text)),
            source_lines=len(text.splitlines()),
        ))
    if unmatched:
        joined = ", ".join(str(path) for path in unmatched)
        raise ValueError(f"could not parse public FED instruction class declarations: {joined}")
    return families


def actual_input_signature(row: dict[str, Any]) -> str:
    by_occurrence = row.get("actualInputSignatures") or {}
    if by_occurrence:
        signatures = sorted({tuple(str(value) for value in values)
                             for values in by_occurrence.values()})
        return " || ".join(",".join(signature) for signature in signatures)
    states: list[str] = []
    for value in row.get("inputs") or []:
        if value.get("federated"):
            states.append(f"PRESENT:{value.get('fType') or '-'}")
        elif value.get("present"):
            states.append("ABSENT_LOCAL:-")
        else:
            states.append("ABSENT:-")
    return ",".join(states)


def output_residency(row: dict[str, Any]) -> str:
    output = row.get("output") or {}
    if not output.get("present"):
        return "ABSENT"
    if output.get("federated"):
        return f"FED:{output.get('fType') or '-'}"
    return "LOCAL:-"


def published_states(row: dict[str, Any]) -> str:
    states: list[str] = []
    for emission in row.get("publishedStatesP") or []:
        states.append("/".join([
            str(emission.get("exec") or "?"),
            str(emission.get("output") or "?"),
            str(emission.get("fType") or "-"),
        ]))
    if not states:
        states.extend(str(value).split("/SHAPE_", 1)[0]
                      for value in row.get("publishedNodeStates") or [])
    return csv_join(states)


def candidate_input_signature(row: dict[str, Any]) -> str:
    values = []
    for item in row.get("inputSignature") or []:
        values.append(f"{item.get('presence') or '?'}:{item.get('fType') or '-'}")
    return ",".join(values)


def discover_evidence(roots: list[Path]) -> dict[str, list[Path]]:
    patterns = {
        "capability": "runtime-capability-*.jsonl",
        "frontier": "runtime-conversion-frontier-*.jsonl",
        "candidate": "candidate-space-*.jsonl",
    }
    found: dict[str, list[Path]] = {}
    for kind, pattern in patterns.items():
        found[kind] = sorted({path.resolve() for root in roots for path in root.rglob(pattern)})
    return found


def markdown_table(headers: list[str], rows: list[list[Any]]) -> str:
    def escape(value: Any) -> str:
        return str(value).replace("|", "\\|").replace("\n", " ")
    lines = ["| " + " | ".join(headers) + " |",
             "| " + " | ".join("---" for _ in headers) + " |"]
    lines.extend("| " + " | ".join(escape(value) for value in row) + " |" for row in rows)
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, action="append", required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    source_root = args.source_root.resolve()
    evidence_roots = [path.resolve() for path in args.evidence_root]
    for root in evidence_roots:
        if not root.exists():
            raise FileNotFoundError(f"evidence root not found: {root}")
    args.out_dir.mkdir(parents=True, exist_ok=True)

    families = inventory_families(source_root)
    family_by_name = {family.class_name: family for family in families}
    evidence = discover_evidence(evidence_roots)

    capability_counts: Counter[tuple[str, ...]] = Counter()
    capability_class_outcomes: Counter[tuple[str, str]] = Counter()
    capability_opcodes: dict[str, set[str]] = defaultdict(set)
    capability_input_ftypes: dict[str, set[str]] = defaultdict(set)
    capability_input_signatures: dict[str, set[str]] = defaultdict(set)
    capability_requested_outputs: dict[str, set[str]] = defaultdict(set)
    capability_output_residencies: dict[str, set[str]] = defaultdict(set)
    capability_success_signatures: dict[str, set[tuple[str, ...]]] = defaultdict(set)
    capability_signature_methods: Counter[str] = Counter()
    capability_exact_input_rows: Counter[str] = Counter()
    capability_planned_target_rows: Counter[str] = Counter()
    unknown_capability_classes: Counter[str] = Counter()
    for _, row in iter_jsonl(evidence["capability"]):
        class_name = str(row.get("instructionSimpleClass") or
                         str(row.get("instructionClass") or "UNKNOWN").rsplit(".", 1)[-1])
        outcome = str(row.get("outcome") or "UNKNOWN")
        opcode = str(row.get("opcode") or "")
        key = (
            class_name,
            opcode,
            actual_input_signature(row),
            str(row.get("federatedOutput") or "NONE"),
            output_residency(row),
            outcome,
        )
        capability_counts[key] += 1
        capability_class_outcomes[(class_name, outcome)] += 1
        capability_opcodes[class_name].add(opcode)
        capability_signature_methods[str(
            row.get("actualInputSignatureMethod") or "UNSPECIFIED")] += 1
        if row.get("actualInputSignatures"):
            capability_exact_input_rows[class_name] += 1
        if row.get("plannedTargetStates"):
            capability_planned_target_rows[class_name] += 1
        if outcome == "SUCCESS":
            signature = key[2]
            if signature:
                capability_input_signatures[class_name].add(signature)
                capability_input_ftypes[class_name].update(
                    re.findall(r"PRESENT:([A-Z_]+)", signature))
            capability_input_ftypes[class_name].update(
                str(value.get("fType")) for value in row.get("inputs") or []
                if value.get("federated") and value.get("fType"))
            capability_requested_outputs[class_name].add(key[3])
            capability_output_residencies[class_name].add(key[4])
            capability_success_signatures[class_name].add(key[1:5])
        if class_name not in family_by_name:
            unknown_capability_classes[class_name] += 1

    frontier_counts: Counter[tuple[str, ...]] = Counter()
    frontier_kind_counts: Counter[str] = Counter()
    frontier_result_classes: Counter[str] = Counter()
    frontier_result_class_kinds: Counter[tuple[str, str]] = Counter()
    for _, row in iter_jsonl(evidence["frontier"]):
        kind = str(row.get("frontierKind") or "UNKNOWN")
        source_class = str(row.get("sourceInstructionSimpleClass") or
                           str(row.get("sourceInstructionClass") or "UNKNOWN").rsplit(".", 1)[-1])
        result_class = str(row.get("resultInstructionSimpleClass") or
                           str(row.get("resultInstructionClass") or "").rsplit(".", 1)[-1])
        key = (
            kind,
            source_class,
            str(row.get("sourceOpcode") or ""),
            ",".join(str(value) for value in row.get("sourceInputStates") or []),
            result_class,
            str(row.get("resultOpcode") or ""),
            str(row.get("resultFederatedOutput") or ""),
            ",".join(str(value) for value in row.get("resultInputStates") or []),
        )
        frontier_counts[key] += 1
        frontier_kind_counts[kind] += 1
        if result_class:
            frontier_result_classes[result_class] += 1
            frontier_result_class_kinds[(result_class, kind)] += 1

    candidate_counts: Counter[tuple[str, ...]] = Counter()
    candidate_contexts: dict[tuple[str, ...], set[str]] = defaultdict(set)
    for _, row in iter_jsonl(evidence["candidate"]):
        key = (
            str(row.get("hopClass") or "").rsplit(".", 1)[-1],
            str(row.get("opcode") or ""),
            candidate_input_signature(row),
            str(row.get("privacy") or "UNKNOWN"),
            published_states(row),
        )
        candidate_counts[key] += 1
        context = str(row.get("auditContext") or "")
        if context:
            candidate_contexts[key].add(context)

    family_rows: list[dict[str, Any]] = []
    for family in families:
        success = capability_class_outcomes[(family.class_name, "SUCCESS")]
        failure = sum(count for (name, outcome), count in capability_class_outcomes.items()
                      if name == family.class_name and outcome != "SUCCESS")
        family_rows.append({
            **asdict(family),
            "observed_opcodes": csv_join(capability_opcodes[family.class_name]),
            "observed_input_ftypes": csv_join(capability_input_ftypes[family.class_name]),
            "observed_input_signatures": csv_join(
                capability_input_signatures[family.class_name]),
            "observed_requested_outputs": csv_join(
                capability_requested_outputs[family.class_name]),
            "observed_output_residencies": csv_join(
                capability_output_residencies[family.class_name]),
            "successful_signature_groups": len(
                capability_success_signatures[family.class_name]),
            "exact_actual_input_rows": capability_exact_input_rows[family.class_name],
            "planned_target_rows": capability_planned_target_rows[family.class_name],
            "capability_success_rows": success,
            "capability_failure_rows": failure,
            "frontier_result_rows": frontier_result_classes[family.class_name],
            "frontier_direct_rows": frontier_result_class_kinds[
                (family.class_name, "DIRECT_FED")],
            "frontier_runtime_to_fed_rows": frontier_result_class_kinds[
                (family.class_name, "RUNTIME_TO_FED")],
            "positive_execution_witness": bool(success),
        })

    capability_rows = [{
        "instruction_class": key[0], "opcode": key[1], "actual_input_signature": key[2],
        "requested_output": key[3], "actual_output_residency": key[4],
        "outcome": key[5], "count": count,
    } for key, count in sorted(capability_counts.items())]
    frontier_rows = [{
        "frontier_kind": key[0], "source_instruction_class": key[1],
        "source_opcode": key[2], "source_input_states": key[3],
        "result_instruction_class": key[4], "result_opcode": key[5],
        "result_requested_output": key[6], "result_input_states": key[7],
        "count": count,
    } for key, count in sorted(frontier_counts.items())]
    candidate_rows = [{
        "hop_class": key[0], "opcode": key[1], "input_signature": key[2],
        "privacy": key[3], "published_states_p": key[4],
        "contexts": len(candidate_contexts[key]), "count": count,
    } for key, count in sorted(candidate_counts.items())]

    concrete = [family for family in families if not family.abstract]
    unobserved = [family.class_name for family in concrete
                  if capability_class_outcomes[(family.class_name, "SUCCESS")] == 0]
    summary = {
        "schema": "fed-runtime-space-inventory-v1",
        "sourceRoot": str(source_root),
        "evidenceRoots": [str(root) for root in evidence_roots],
        "source": {
            "fedInstructionFiles": len(families),
            "abstractFamilies": sum(family.abstract for family in families),
            "concreteFamilies": len(concrete),
        },
        "evidenceFiles": {kind: len(paths) for kind, paths in evidence.items()},
        "capability": {
            "rows": sum(capability_counts.values()),
            "successRows": sum(count for key, count in capability_counts.items()
                               if key[5] == "SUCCESS"),
            "failureRows": sum(count for key, count in capability_counts.items()
                               if key[5] != "SUCCESS"),
            "observedClasses": len({key[0] for key in capability_counts}),
            "successSignatureGroups": sum(
                len(signatures) for signatures in capability_success_signatures.values()),
            "exactActualInputRows": sum(capability_exact_input_rows.values()),
            "plannedTargetRows": sum(capability_planned_target_rows.values()),
            "actualInputSignatureMethods": dict(sorted(capability_signature_methods.items())),
            "positiveWitnessedConcreteFamilies": len(concrete) - len(unobserved),
            "unknownClasses": dict(sorted(unknown_capability_classes.items())),
        },
        "frontier": {
            "rows": sum(frontier_counts.values()),
            "kinds": dict(sorted(frontier_kind_counts.items())),
            "resultClasses": len(frontier_result_classes),
        },
        "candidate": {
            "rows": sum(candidate_counts.values()),
            "coverageGroups": len(candidate_counts),
            "hopClasses": len({key[0] for key in candidate_counts}),
            "opcodes": len({key[1] for key in candidate_counts}),
        },
        "unobservedConcreteFamilies": unobserved,
        "limitations": [
            "A SUCCESS capability row is a positive execution witness only for its exact fixture and state.",
            "A FAILURE row is negative evidence only for its exact fixture; it is not a universal impossibility proof.",
            "Conversion-frontier rows prove parser/dispatch behavior, not successful execution.",
            "Unobserved families are coverage gaps, not evidence that the runtime lacks those families.",
            "Candidate and runtime rows from unrelated attempts are not joined by this inventory.",
            "The inventory does not establish exhaustive R and therefore cannot by itself prove Missing or Spurious sets.",
        ],
    }

    family_fields = list(asdict(families[0]).keys()) + [
        "observed_opcodes", "observed_input_ftypes", "observed_input_signatures",
        "observed_requested_outputs", "observed_output_residencies",
        "successful_signature_groups", "exact_actual_input_rows", "planned_target_rows",
        "capability_success_rows",
        "capability_failure_rows", "frontier_result_rows", "frontier_direct_rows",
        "frontier_runtime_to_fed_rows", "positive_execution_witness",
    ] if families else []
    write_csv(args.out_dir / "fed_instruction_families.csv", family_rows, family_fields)
    write_csv(args.out_dir / "runtime_capability_coverage.csv", capability_rows, [
        "instruction_class", "opcode", "actual_input_signature", "requested_output",
        "actual_output_residency", "outcome", "count",
    ])
    write_csv(args.out_dir / "runtime_frontier_coverage.csv", frontier_rows, [
        "frontier_kind", "source_instruction_class", "source_opcode", "source_input_states",
        "result_instruction_class", "result_opcode", "result_requested_output",
        "result_input_states", "count",
    ])
    write_csv(args.out_dir / "candidate_opcode_coverage.csv", candidate_rows, [
        "hop_class", "opcode", "input_signature", "privacy", "published_states_p",
        "contexts", "count",
    ])
    (args.out_dir / "unobserved_concrete_families.txt").write_text(
        "".join(f"{name}\n" for name in unobserved), encoding="utf-8")
    (args.out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    report = [
        "# FED Runtime-space Inventory\n",
        "## Scope and interpretation\n",
        "This is a source inventory plus observational coverage report. It does **not** "
        "treat dispatch-frontier rows as successful runtime witnesses, join unrelated JVM "
        "attempts, or claim exhaustive runtime space $R$.\n",
        "## Summary\n",
        markdown_table(["Measure", "Value"], [
            ["FED source classes", len(families)],
            ["Concrete / abstract", f"{len(concrete)} / {len(families) - len(concrete)}"],
            ["Capability rows (success / failure)",
             f"{summary['capability']['rows']} ({summary['capability']['successRows']} / "
             f"{summary['capability']['failureRows']})"],
            ["Successful runtime signature groups",
             summary["capability"]["successSignatureGroups"]],
            ["Rows with exact actual input signature",
             summary["capability"]["exactActualInputRows"]],
            ["Rows with selected planner target",
             summary["capability"]["plannedTargetRows"]],
            ["Concrete families with positive witnesses",
             f"{summary['capability']['positiveWitnessedConcreteFamilies']} / {len(concrete)}"],
            ["Conversion-frontier rows", summary["frontier"]["rows"]],
            ["Candidate P rows", summary["candidate"]["rows"]],
            ["Candidate coverage groups", summary["candidate"]["coverageGroups"]],
        ]) + "\n",
        "## Source families and observed execution witnesses\n",
        markdown_table(
            ["Class", "Kind", "Parent", "SUCCESS", "Signatures", "Input FTypes", "Outputs",
             "Direct / Dynamic", "Opcodes"],
            [[row["class_name"], "abstract" if row["abstract"] else "concrete", row["parent"],
              row["capability_success_rows"], row["successful_signature_groups"],
              row["observed_input_ftypes"] or "—",
              row["observed_output_residencies"] or "—",
              f"{row['frontier_direct_rows']} / {row['frontier_runtime_to_fed_rows']}",
              row["observed_opcodes"] or "—"]
             for row in family_rows],
        ) + "\n",
        "## Unobserved concrete families\n",
        ("\n".join(f"- `{name}`" for name in unobserved) if unobserved else "- None") + "\n",
        "## Conversion frontier\n",
        markdown_table(["Kind", "Rows"], [[kind, count]
                                            for kind, count in sorted(frontier_kind_counts.items())]) + "\n",
        "## Soundness boundaries\n",
        "\n".join(f"- {item}" for item in summary["limitations"]) + "\n",
        "## Machine-readable outputs\n",
        "- `summary.json`\n"
        "- `fed_instruction_families.csv`\n"
        "- `runtime_capability_coverage.csv`\n"
        "- `runtime_frontier_coverage.csv`\n"
        "- `candidate_opcode_coverage.csv`\n"
        "- `unobserved_concrete_families.txt`\n",
    ]
    (args.out_dir / "REPORT.md").write_text("\n".join(report), encoding="utf-8")

    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
