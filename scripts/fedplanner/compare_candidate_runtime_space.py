#!/usr/bin/env python3
"""Compare selector-visible placement candidates with witnessed FED runtime capabilities.

The tool is deliberately conservative:
* a successful physical execution is a positive R witness;
* a published state without a witness is UNTESTED, not automatically spurious;
* a failed attempt is reported for triage, not called spurious without root-cause evidence;
* Missing is confirmed only when an exact planner occurrence/runtime-audit identity exists.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable


def jsonl(paths: Iterable[Path]) -> list[dict[str, Any]]:
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


def physical_candidate(emission: dict[str, Any]) -> str:
	return "/".join(
		[
			str(emission.get("exec", "?")),
			str(emission.get("output", "?")),
			str(emission.get("fType") or "-"),
		]
	)


def candidate_opcodes(row: dict[str, Any]) -> set[str]:
	values = {str(row.get("opcode") or "").lower()}
	for phase in ("prePrivacyRule", "publishedRule"):
		capability = (row.get(phase) or {}).get("capability") or {}
		values.add(str(capability.get("opcode") or "").lower())
	return {value for value in values if value}


def runtime_state(row: dict[str, Any]) -> str:
	output = row.get("output") or {}
	if output.get("present"):
		if output.get("federated"):
			return f"FED/FOUT/{output.get('fType') or '-'}"
		# A collected result has no resident federated layout.  The worker-side
		# execution layout is therefore not independently observable from the
		# output object and must not be compared as if '-' were an exact FType.
		return "FED/LOUT/*"
	requested = str(row.get("federatedOutput") or "NONE")
	if requested == "FOUT":
		return f"FED/FOUT/{row.get('syntheticFType') or '?'}"
	if requested == "LOUT":
		return "FED/LOUT/*"
	return "FED/NONE/-"


def state_compatible(published_state: str, runtime_witness_state: str) -> bool:
	"""Whether one successful runtime observation witnesses a published state.

	FType is physical result residency for FOUT.  For LOUT the result is local,
	so runtime output inspection cannot distinguish the planner's worker-side
	execution layout.  Exact input signatures still distinguish ROW/COL/FULL
	input domains; treating the LOUT result FType as a wildcard avoids inventing
	a false negative from information the runtime object cannot carry.
	"""
	published_parts = published_state.split("/", 2)
	runtime_parts = runtime_witness_state.split("/", 2)
	if len(published_parts) != 3 or len(runtime_parts) != 3:
		return published_state == runtime_witness_state
	if published_parts[:2] != runtime_parts[:2]:
		return False
	if runtime_parts[1] == "LOUT":
		return True
	return runtime_parts[2] in {"*", "?"} or published_parts[2] == runtime_parts[2]


def candidate_input_signature(row: dict[str, Any]) -> tuple[tuple[str, str], ...]:
	return tuple(
		(str(value.get("presence")), str(value.get("fType") or "-"))
		for value in row.get("inputSignature", [])
	)


def parse_normalized_input_signature(values: Iterable[Any]) -> tuple[tuple[str, str], ...]:
	out: list[tuple[str, str]] = []
	for value in values:
		presence, _, ftype = str(value).partition(":")
		out.append((presence, ftype or "-"))
	return tuple(out)


def runtime_input_signature(
	row: dict[str, Any], occurrence: str | None = None
) -> tuple[tuple[str, str], ...]:
	if occurrence is not None:
		values = (row.get("actualInputSignatures") or {}).get(str(occurrence))
		if values is not None:
			return parse_normalized_input_signature(values)
	values: list[tuple[str, str]] = []
	for value in row.get("inputs", []):
		if value.get("federated"):
			values.append(("PRESENT", str(value.get("fType") or "?")))
		else:
			values.append(("ABSENT_LOCAL", "-"))
	return tuple(values)


def selected_input_signature(row: dict[str, Any], occurrence: str) -> tuple[tuple[str, str], ...] | None:
	values = (row.get("plannedInputSignatures") or {}).get(str(occurrence))
	if values is None:
		return None
	return parse_normalized_input_signature(values)


def concrete_instruction_classes(source_root: Path) -> list[str]:
	fed = source_root / "src/main/java/org/apache/sysds/runtime/instructions/fed"
	parents: dict[str, str] = {}
	abstract: set[str] = set()
	for path in sorted(fed.glob("*.java")):
		name = path.stem
		text = path.read_text(encoding="utf-8", errors="replace")
		match = re.search(
			rf"public\s+(abstract\s+)?(?:final\s+)?class\s+{re.escape(name)}\s+extends\s+(\w+)",
			text,
		)
		if not match:
			continue
		parents[name] = match.group(2)
		if match.group(1):
			abstract.add(name)

	def is_fed_instruction(name: str) -> bool:
		seen: set[str] = set()
		while name in parents and name not in seen:
			seen.add(name)
			parent = parents[name]
			if parent == "FEDInstruction":
				return True
			name = parent
		return False

	return sorted(name for name in parents if name not in abstract and is_fed_instruction(name))


def write_csv(path: Path, rows: list[dict[str, Any]], fields: list[str]) -> None:
	with path.open("w", newline="", encoding="utf-8") as handle:
		writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
		writer.writeheader()
		writer.writerows(rows)


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--candidate-dir", type=Path, required=True)
	parser.add_argument("--runtime-dir", type=Path, required=True)
	parser.add_argument("--source-root", type=Path, required=True)
	parser.add_argument("--out-dir", type=Path, required=True)
	args = parser.parse_args()

	candidates = jsonl(args.candidate_dir.glob("*candidate-space-*.jsonl"))
	runtime = jsonl(args.runtime_dir.glob("*runtime-capability-*.jsonl"))
	args.out_dir.mkdir(parents=True, exist_ok=True)

	by_occurrence: dict[str, list[dict[str, Any]]] = defaultdict(list)
	by_opcode_input: dict[tuple[str, tuple[tuple[str, str], ...]], list[dict[str, Any]]] = defaultdict(list)
	for row in candidates:
		by_occurrence[str(row["occurrenceKeyHash"])].append(row)
		for opcode in candidate_opcodes(row):
			by_opcode_input[(opcode, candidate_input_signature(row))].append(row)

	published: set[tuple[str, str, tuple[tuple[str, str], ...], str]] = set()
	for row in candidates:
		for emission in row.get("publishedStatesP", []):
			published.add(
				(
					str(row["analysisFingerprint"]),
					str(row["occurrenceKeyHash"]),
					candidate_input_signature(row),
					physical_candidate(emission),
				)
			)

	witnessed_p: set[tuple[str, str, tuple[tuple[str, str], ...], str]] = set()
	missing: list[dict[str, Any]] = []
	failed_published: list[dict[str, Any]] = []
	input_divergence: list[dict[str, Any]] = []
	unmatched_runtime: list[dict[str, Any]] = []
	generalized_missing: list[dict[str, Any]] = []

	for witness in runtime:
		state = runtime_state(witness)
		fallback_input = runtime_input_signature(witness)
		occurrences = witness.get("occurrenceKeyHashes") or []
		if not occurrences:
			generalized = by_opcode_input.get(
				(str(witness.get("opcode") or "").lower(), fallback_input), []
			)
			generalized_states = {
				physical_candidate(emission)
				for row in generalized
				for emission in row.get("publishedStatesP", [])
			}
			if witness.get("outcome") == "SUCCESS" and generalized and not any(
				state_compatible(published_state, state) for published_state in generalized_states
			):
				generalized_missing.append(
					{
						"classification": "GENERALIZED_RUNTIME_WITNESS_NOT_EXPOSED",
						"instructionClass": witness.get("instructionSimpleClass"),
						"fedType": witness.get("fedType"),
						"opcode": witness.get("opcode"),
						"runtimeInputSignature": repr(fallback_input),
						"state": state,
						"candidateOccurrences": len({row.get("occurrenceKeyHash") for row in generalized}),
					}
				)
			unmatched_runtime.append(
				{
					"instructionClass": witness.get("instructionSimpleClass"),
					"opcode": witness.get("opcode"),
					"state": state,
					"outcome": witness.get("outcome"),
					"reason": "NO_EXACT_OCCURRENCE_IDENTITY",
				}
			)
			continue
		for occurrence in occurrences:
			r_input = runtime_input_signature(witness, str(occurrence))
			exact_runtime_input = str(occurrence) in (witness.get("actualInputSignatures") or {})
			planned_input = selected_input_signature(witness, str(occurrence))
			rows = by_occurrence.get(str(occurrence), [])
			if not rows:
				unmatched_runtime.append(
					{
						"instructionClass": witness.get("instructionSimpleClass"),
						"opcode": witness.get("opcode"),
						"state": state,
						"outcome": witness.get("outcome"),
						"occurrenceKeyHash": occurrence,
						"reason": "OCCURRENCE_NOT_IN_CANDIDATE_CAPTURE",
					}
				)
				continue
			matched = [row for row in rows if candidate_input_signature(row) == r_input]
			input_match = bool(matched)
			all_occurrence_states = {
				physical_candidate(emission)
				for row in rows
				for emission in row.get("publishedStatesP", [])
			}
			if not matched:
				# Preserve the exact occurrence result, but do not pretend the heterogeneous
				# runtime operand reflection proved HOP input-position equivalence.
				matched = rows
			published_states = {
				physical_candidate(emission)
				for row in matched
				for emission in row.get("publishedStatesP", [])
			}
			base = {
				"instructionClass": witness.get("instructionSimpleClass"),
				"fedType": witness.get("fedType"),
				"opcode": witness.get("opcode"),
				"occurrenceKeyHash": occurrence,
				"runtimeInputSignature": repr(r_input),
				"plannedInputSignature": repr(planned_input) if planned_input is not None else "",
				"exactInputMatch": input_match and exact_runtime_input,
				"actualInputSignatureExact": exact_runtime_input,
				"state": state,
				"privacy": ",".join(sorted({str(row.get("privacy")) for row in matched})),
				"failureClass": witness.get("failureClass"),
				"failureMessage": witness.get("failureMessage"),
			}
			proven_input_divergence = (
				exact_runtime_input and planned_input is not None and planned_input != r_input
			)
			compatible_published_states = {
				published_state for published_state in published_states
				if state_compatible(published_state, state)
			}
			compatible_occurrence_states = {
				published_state for published_state in all_occurrence_states
				if state_compatible(published_state, state)
			}
			if proven_input_divergence:
				base["classification"] = (
					"SUCCESSFUL_SELECTED_INPUT_SIGNATURE_DIVERGENCE"
					if witness.get("outcome") == "SUCCESS"
					else "FAILED_SELECTED_INPUT_SIGNATURE_DIVERGENCE"
				)
				input_divergence.append(base)
			elif witness.get("outcome") == "SUCCESS":
				if compatible_published_states:
					for row in matched:
						for emission in row.get("publishedStatesP", []):
							published_state = physical_candidate(emission)
							if published_state not in compatible_published_states:
								continue
							key = (
								str(row["analysisFingerprint"]),
								str(occurrence),
								candidate_input_signature(row),
								published_state,
							)
							if key in published:
								witnessed_p.add(key)
				elif exact_runtime_input and input_match and compatible_occurrence_states:
					base["classification"] = "SUCCESSFUL_SELECTED_INPUT_SIGNATURE_DIVERGENCE"
					input_divergence.append(base)
				else:
					base["classification"] = (
						"CONFIRMED_MISSING"
						if exact_runtime_input and input_match
						else "POTENTIAL_MISSING_INPUT_UNMATCHED"
					)
					missing.append(base)
			elif compatible_published_states:
				base["classification"] = "FAILED_PUBLISHED_ATTEMPT_REQUIRES_ROOT_CAUSE"
				failed_published.append(base)
			elif exact_runtime_input and input_match and compatible_occurrence_states:
				base["classification"] = "FAILED_SELECTED_INPUT_SIGNATURE_DIVERGENCE"
				input_divergence.append(base)

	unwitnessed = sorted(published - witnessed_p, key=repr)
	all_classes = concrete_instruction_classes(args.source_root)
	success_classes = sorted(
		{
			str(row.get("instructionSimpleClass"))
			for row in runtime
			if row.get("outcome") == "SUCCESS"
		}
	)
	failure_classes = sorted(
		{
			str(row.get("instructionSimpleClass"))
			for row in runtime
			if row.get("outcome") == "FAILURE"
		}
	)
	uncovered_classes = sorted(set(all_classes) - set(success_classes))
	capability_matrix: dict[tuple[Any, ...], int] = defaultdict(int)
	for row in runtime:
		key = (
			row.get("instructionSimpleClass"),
			row.get("fedType"),
			row.get("opcode"),
			repr(runtime_input_signature(row)),
			row.get("federatedOutput"),
			runtime_state(row),
			row.get("outcome"),
		)
		capability_matrix[key] += 1

	summary = {
		"candidateRows": len(candidates),
		"runtimeWitnessRows": len(runtime),
		"runtimeSuccessRows": sum(row.get("outcome") == "SUCCESS" for row in runtime),
		"runtimeFailureRows": sum(row.get("outcome") == "FAILURE" for row in runtime),
		"uniquePublishedPStates": len(published),
		"witnessedPublishedPStates": len(witnessed_p),
		"unwitnessedPublishedPStates": len(unwitnessed),
		"confirmedMissing": sum(row["classification"] == "CONFIRMED_MISSING" for row in missing),
		"potentialMissingInputUnmatched": sum(
			row["classification"] == "POTENTIAL_MISSING_INPUT_UNMATCHED" for row in missing
		),
		"generalizedRuntimeWitnessesNotExposed": len(generalized_missing),
		"failedPublishedAttempts": len(failed_published),
		"selectedInputSignatureDivergences": len(input_divergence),
		"unmatchedRuntimeRows": len(unmatched_runtime),
		"concreteFedInstructionClasses": all_classes,
		"successfulFedInstructionClasses": success_classes,
		"failedFedInstructionClasses": failure_classes,
		"uncoveredFedInstructionClasses": uncovered_classes,
		"coverageComplete": not uncovered_classes and not unwitnessed and not unmatched_runtime,
	}

	(args.out_dir / "summary.json").write_text(
		json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
	)
	fields = [
		"classification",
		"instructionClass",
		"fedType",
		"opcode",
		"occurrenceKeyHash",
		"runtimeInputSignature",
		"plannedInputSignature",
		"exactInputMatch",
		"actualInputSignatureExact",
		"state",
		"privacy",
		"failureClass",
		"failureMessage",
	]
	write_csv(args.out_dir / "missing.csv", missing, fields)
	write_csv(args.out_dir / "failed_published_attempts.csv", failed_published, fields)
	write_csv(args.out_dir / "selected_input_signature_divergence.csv", input_divergence, fields)
	write_csv(
		args.out_dir / "generalized_missing_hypotheses.csv",
		generalized_missing,
		[
			"classification",
			"instructionClass",
			"fedType",
			"opcode",
			"runtimeInputSignature",
			"state",
			"candidateOccurrences",
		],
	)
	with (args.out_dir / "runtime_capability_matrix.csv").open("w", newline="", encoding="utf-8") as handle:
		writer = csv.writer(handle)
		writer.writerow(
			[
				"instructionClass",
				"fedType",
				"opcode",
				"inputSignature",
				"requestedOutput",
				"actualState",
				"outcome",
				"count",
			]
		)
		for key, count in sorted(capability_matrix.items(), key=lambda item: repr(item[0])):
			writer.writerow([*key, count])
	write_csv(
		args.out_dir / "unmatched_runtime.csv",
		unmatched_runtime,
		["instructionClass", "opcode", "state", "outcome", "occurrenceKeyHash", "reason"],
	)
	with (args.out_dir / "unwitnessed_published.csv").open("w", newline="", encoding="utf-8") as handle:
		writer = csv.writer(handle)
		writer.writerow(["analysisFingerprint", "occurrenceKeyHash", "inputSignature", "state"])
		for analysis, occurrence, inputs, state in unwitnessed:
			writer.writerow([analysis, occurrence, repr(inputs), state])

	report = [
		"# FED Planner P/L/R Differential Audit",
		"",
		"## Result",
		"",
		f"- Candidate rows: **{summary['candidateRows']}**",
		f"- Unique published P states: **{summary['uniquePublishedPStates']}**",
		f"- Successful runtime R witnesses: **{summary['runtimeSuccessRows']}**",
		f"- Confirmed Missing `(R∩L)-P`: **{summary['confirmedMissing']}**",
		f"- Failed published attempts requiring root-cause analysis: **{summary['failedPublishedAttempts']}**",
		f"- Selected/runtime input-signature divergences: **{summary['selectedInputSignatureDivergences']}**",
		f"- Generalized R witnesses not exposed by a matching candidate rule: **{summary['generalizedRuntimeWitnessesNotExposed']}**",
		f"- Concrete FED instruction classes covered successfully: **{len(success_classes)}/{len(all_classes)}**",
		"",
		"## Coverage gaps",
		"",
		f"- Uncovered instruction classes: `{', '.join(uncovered_classes) if uncovered_classes else 'none'}`",
		f"- Published P states without a successful runtime witness: **{len(unwitnessed)}**",
		f"- Runtime rows without exact occurrence identity: **{len(unmatched_runtime)}**",
		"",
		"An unwitnessed P state is **not** classified as Spurious. A failed attempt is also kept",
		"separate until its failure is shown to arise from physical infeasibility rather than test data,",
		"network, or an unrelated runtime error. Therefore a zero-finding report is conclusive only",
		"when `coverageComplete` is true.",
		"",
	]
	(args.out_dir / "REPORT.md").write_text("\n".join(report), encoding="utf-8")


if __name__ == "__main__":
	main()
