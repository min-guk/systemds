#!/usr/bin/env python3
"""Join P and successful FED runtime witnesses inside the same forced-run attempt.

Unlike the historical broad comparator, this tool never places candidate rows from
unrelated JVMs or replay attempts into one occurrence map.  A formal Missing witness
requires all of the following receipts from the same attempt:

* exact planner analysis fingerprint and audit context;
* exact occurrence and ordered actual input signature;
* selector target state and concrete lowering state;
* successful runtime execution of that same direct physical state;
* PUBLIC privacy (restricted states remain legality-review items); and
* absence of the target state from the captured post-privacy P domain.

Legacy runtime rows without the newly added target/physical receipts are retained as
hypotheses only.  The script is observational: zero findings do not establish complete
R coverage.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable


def read_jsonl(paths: Iterable[Path]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for path in sorted(paths):
        with path.open(encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as exc:
                    raise ValueError(f"{path}:{line_no}: invalid JSONL: {exc}") from exc
    return rows


def canonical_state(value: str | None) -> str | None:
    if value is None:
        return None
    parts = str(value).split("/")
    if len(parts) == 2:
        parts.append("-")
    return "/".join(parts[:3]) if len(parts) >= 3 else str(value)


def emitted_state(emission: dict[str, Any]) -> str:
    return canonical_state("/".join([
        str(emission.get("exec", "?")),
        str(emission.get("output", "?")),
        str(emission.get("fType") or "-"),
    ])) or "?/?/-"


def state_compatible(published: str, observed: str) -> bool:
    p = (canonical_state(published) or published).split("/", 2)
    o = (canonical_state(observed) or observed).split("/", 2)
    if len(p) != 3 or len(o) != 3 or p[:2] != o[:2]:
        return canonical_state(published) == canonical_state(observed)
    if o[1] == "LOUT":
        return True
    return o[2] in {"*", "?"} or p[2] in {"*", "?"} or p[2] == o[2]


def runtime_state(row: dict[str, Any]) -> str:
    output = row.get("output") or {}
    if output.get("present"):
        if output.get("federated"):
            return canonical_state(
                f"FED/FOUT/{output.get('fType') or '-'}") or "FED/FOUT/-"
        return "FED/LOUT/*"
    requested = str(row.get("federatedOutput") or "NONE")
    if requested == "FOUT":
        return canonical_state(
            f"FED/FOUT/{row.get('syntheticFType') or '?'}") or "FED/FOUT/?"
    if requested == "LOUT":
        return "FED/LOUT/*"
    return "FED/NONE/-"


def candidate_input(row: dict[str, Any]) -> tuple[tuple[str, str], ...]:
    return tuple((str(item.get("presence")), str(item.get("fType") or "-"))
                 for item in row.get("inputSignature", []))


def normalized_input(values: Iterable[Any]) -> tuple[tuple[str, str], ...]:
    out: list[tuple[str, str]] = []
    for value in values:
        presence, _, ftype = str(value).partition(":")
        out.append((presence, ftype or "-"))
    return tuple(out)


def planned_state(row: dict[str, Any], field: str, occurrence: str) -> str | None:
    return canonical_state((row.get(field) or {}).get(occurrence))


def public_privacy(rows: list[dict[str, Any]]) -> bool:
    values = {str(row.get("privacy")) for row in rows}
    return values == {"PUBLIC"}


def attempt_label(path: Path, roots: list[Path]) -> str:
    for root in roots:
        try:
            return f"{root.name}/{path.relative_to(root)}"
        except ValueError:
            pass
    return str(path)


def attempt_target_id(path: Path) -> str:
    parts = path.parts
    return parts[parts.index("targets") + 1] if "targets" in parts else ""


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = [
        "classification", "evidenceReason", "attempt", "targetId", "pid",
        "instructionClass", "opcode", "occurrenceKeyHash", "auditContext",
        "plannerAnalysisFingerprint", "candidateAnalysisFingerprints", "privacy",
        "actualInputSignature", "plannedInputSignature", "publishedStatesP",
        "plannedTargetState", "plannedPhysicalState", "actualRuntimeState",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--campaign-root", type=Path, action="append", required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    roots = [root.resolve() for root in args.campaign_root]
    args.out_dir.mkdir(parents=True, exist_ok=True)

    attempts = sorted({path.resolve() for root in roots for path in root.rglob("attempt-*")
                       if path.is_dir()})
    counts: Counter[str] = Counter()
    classifications: Counter[str] = Counter()
    frontier_kinds: Counter[str] = Counter()
    evidence: list[dict[str, Any]] = []
    divergence_keys: set[tuple[Any, ...]] = set()

    for attempt in attempts:
        candidate_files = list(attempt.glob("candidate-space-*.jsonl"))
        runtime_files = list(attempt.glob("runtime-capability-*.jsonl"))
        frontier_files = list(attempt.glob("runtime-conversion-frontier-*.jsonl"))
        counts["attempts"] += 1
        counts["candidateFiles"] += len(candidate_files)
        counts["runtimeFiles"] += len(runtime_files)
        counts["runtimeFrontierFiles"] += len(frontier_files)
        if frontier_files:
            frontiers = read_jsonl(frontier_files)
            counts["runtimeFrontierRows"] += len(frontiers)
            frontier_kinds.update(str(row.get("frontierKind") or "UNKNOWN")
                                  for row in frontiers)
        if not candidate_files:
            counts["attemptsWithoutCandidate"] += 1
            continue
        candidates = read_jsonl(candidate_files)
        counts["candidateRows"] += len(candidates)
        if not runtime_files:
            counts["attemptsWithoutRuntime"] += 1
            continue
        runtime = read_jsonl(runtime_files)
        counts["runtimeRows"] += len(runtime)
        by_occurrence: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in candidates:
            by_occurrence[str(row.get("occurrenceKeyHash"))].append(row)

        for witness in runtime:
            if witness.get("outcome") != "SUCCESS":
                counts["runtimeFailureRows"] += 1
                continue
            counts["runtimeSuccessRows"] += 1
            occurrences = [str(value) for value in witness.get("occurrenceKeyHashes") or []]
            if len(occurrences) != 1:
                classification = "NO_EXACT_SINGLE_OCCURRENCE"
                classifications[classification] += 1
                continue
            occurrence = occurrences[0]
            actual_values = (witness.get("actualInputSignatures") or {}).get(occurrence)
            if actual_values is None:
                classification = "NO_EXACT_ACTUAL_INPUT_SIGNATURE"
                classifications[classification] += 1
                continue
            actual_input = normalized_input(actual_values)
            planned_values = (witness.get("plannedInputSignatures") or {}).get(occurrence)
            planned_input = normalized_input(planned_values) if planned_values is not None else None
            if planned_input is not None and planned_input != actual_input:
                classification = "SELECTED_RUNTIME_INPUT_DIVERGENCE"
                classifications[classification] += 1
                divergence_keys.add((witness.get("auditContext"), occurrence,
                                     planned_input, actual_input))
                evidence.append({
                    "classification": classification,
                    "evidenceReason": "runtime operand residency differs from selected candidate input",
                    "attempt": attempt_label(attempt, roots),
                    "targetId": attempt_target_id(attempt),
                    "pid": witness.get("pid"),
                    "instructionClass": witness.get("instructionSimpleClass"),
                    "opcode": witness.get("opcode"),
                    "occurrenceKeyHash": occurrence,
                    "auditContext": witness.get("auditContext") or "",
                    "plannerAnalysisFingerprint": witness.get("plannerAnalysisFingerprint") or "",
                    "candidateAnalysisFingerprints": "",
                    "privacy": "",
                    "actualInputSignature": repr(actual_input),
                    "plannedInputSignature": repr(planned_input),
                    "publishedStatesP": "",
                    "plannedTargetState": planned_state(
                        witness, "plannedTargetStates", occurrence) or "",
                    "plannedPhysicalState": planned_state(
                        witness, "plannedPhysicalStates", occurrence) or "",
                    "actualRuntimeState": runtime_state(witness),
                })
                continue

            rows = by_occurrence.get(occurrence, [])
            if not rows:
                classification = "OCCURRENCE_NOT_IN_SAME_ATTEMPT_P"
                classifications[classification] += 1
                continue

            analysis = witness.get("plannerAnalysisFingerprint")
            context = witness.get("auditContext")
            analysis_exact = bool(analysis)
            context_exact = bool(context)
            if analysis:
                rows = [row for row in rows if row.get("analysisFingerprint") == analysis]
                if not rows:
                    classification = "ANALYSIS_NOT_IN_SAME_ATTEMPT_P"
                    classifications[classification] += 1
                    continue
            if context:
                rows = [row for row in rows if row.get("auditContext") == context]
                if not rows:
                    classification = "CONTEXT_NOT_IN_SAME_ATTEMPT_P"
                    classifications[classification] += 1
                    continue
            matched = [row for row in rows if candidate_input(row) == actual_input]
            if not matched:
                classification = "INPUT_SIGNATURE_NOT_IN_SAME_ATTEMPT_P"
                classifications[classification] += 1
                continue

            published = sorted({emitted_state(emission) for row in matched
                                for emission in row.get("publishedStatesP", [])})
            target = planned_state(witness, "plannedTargetStates", occurrence)
            physical = planned_state(witness, "plannedPhysicalStates", occurrence)
            actual = runtime_state(witness)
            target_in_p = bool(target) and any(state_compatible(state, target) for state in published)
            actual_in_p = any(state_compatible(state, actual) for state in published)
            physical_matches_actual = bool(physical) and state_compatible(physical, actual)
            direct_target = bool(target and physical and canonical_state(target) == canonical_state(physical))

            if target:
                counts["runtimeRowsWithPlannedTarget"] += 1
                if target_in_p and physical_matches_actual:
                    classification = "EXACT_PLANNED_TARGET_IN_P"
                    reason = "same analysis/context/occurrence/input; lowering and runtime agree"
                elif not physical_matches_actual:
                    classification = "PLANNED_RUNTIME_PHYSICAL_DIVERGENCE"
                    reason = "runtime physical state does not match recorded lowering state"
                elif target_in_p:
                    classification = "EXACT_PLANNED_TARGET_IN_P_DERIVED_PHYSICAL"
                    reason = "target is published; concrete physical step differs from target"
                elif (analysis_exact and context_exact and direct_target
                      and public_privacy(matched)):
                    classification = "CONFIRMED_MISSING_PUBLIC_DIRECT"
                    reason = "successful direct target is legal under PUBLIC privacy but absent from P"
                else:
                    classification = "PLANNED_TARGET_OUTSIDE_P_NEEDS_REVIEW"
                    reason = "missing exact legality or direct-target proof"
            else:
                counts["legacyRuntimeRowsWithoutPlannedTarget"] += 1
                if actual_in_p:
                    classification = "LEGACY_ACTUAL_STATE_COMPATIBLE_WITH_P"
                    reason = "legacy receipt has no selector target; actual state happens to match P"
                else:
                    classification = "LEGACY_ACTUAL_STATE_OUTSIDE_P_UNQUALIFIED"
                    reason = "physical runtime state cannot be promoted to a selector target"

            classifications[classification] += 1
            if classification != "EXACT_PLANNED_TARGET_IN_P":
                evidence.append({
                    "classification": classification,
                    "evidenceReason": reason,
                    "attempt": attempt_label(attempt, roots),
                    "targetId": attempt_target_id(attempt),
                    "pid": witness.get("pid"),
                    "instructionClass": witness.get("instructionSimpleClass"),
                    "opcode": witness.get("opcode"),
                    "occurrenceKeyHash": occurrence,
                    "auditContext": context or "",
                    "plannerAnalysisFingerprint": analysis or "",
                    "candidateAnalysisFingerprints": ";".join(sorted({
                        str(row.get("analysisFingerprint")) for row in matched})),
                    "privacy": ";".join(sorted({str(row.get("privacy")) for row in matched})),
                    "actualInputSignature": repr(actual_input),
                    "plannedInputSignature": repr(planned_input) if planned_input is not None else "",
                    "publishedStatesP": ";".join(published),
                    "plannedTargetState": target or "",
                    "plannedPhysicalState": physical or "",
                    "actualRuntimeState": actual,
                })

    summary = {
        "schema": "fedplanner-attempt-local-p-r-join-v1",
        **dict(sorted(counts.items())),
        "classifications": dict(sorted(classifications.items())),
        "runtimeFrontierKinds": dict(sorted(frontier_kinds.items())),
        "confirmedMissing": classifications["CONFIRMED_MISSING_PUBLIC_DIRECT"],
        "uniqueSelectedRuntimeInputDivergences": len(divergence_keys),
        "coverageComplete": False,
        "coverageNote": "Observed successful FED instructions only; not an exhaustive R enumeration.",
        "campaignRoots": [str(root) for root in roots],
    }
    (args.out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    write_csv(args.out_dir / "nonbaseline_evidence.csv", evidence)
    report = [
        "# Attempt-local FED Planner P/R Join",
        "",
        f"- Attempts scanned: **{counts['attempts']}**",
        f"- Candidate rows: **{counts['candidateRows']}**",
        f"- Successful runtime rows: **{counts['runtimeSuccessRows']}**",
        f"- Runtime rows with explicit planned target: **{counts['runtimeRowsWithPlannedTarget']}**",
        f"- Legacy rows without planned target: **{counts['legacyRuntimeRowsWithoutPlannedTarget']}**",
        f"- Runtime conversion-frontier rows: **{counts['runtimeFrontierRows']}**",
        f"- Confirmed public/direct Missing witnesses: **{classifications['CONFIRMED_MISSING_PUBLIC_DIRECT']}**",
        "",
        "## Classifications",
        "",
    ]
    report.extend(f"- `{name}`: **{count}**" for name, count in sorted(classifications.items()))
    report += ["", "## Runtime conversion frontier", ""]
    report.extend(f"- `{name}`: **{count}**" for name, count in sorted(frontier_kinds.items()))
    report += [
        "",
        "This join is exact within each replay attempt and never mixes unrelated JVMs.",
        "A zero count is not a global completeness proof because the observed runtime rows do not",
        "enumerate every physically supported state in R.",
        "",
    ]
    (args.out_dir / "REPORT.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
    main()
