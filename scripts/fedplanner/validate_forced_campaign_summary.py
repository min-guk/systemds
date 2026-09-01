#!/usr/bin/env python3
"""Fail-closed validation of an authoritative forced campaign summary."""
import argparse
import json
from pathlib import Path


def validate(summary: dict, allow_needs_triage_diagnostic: bool = False) -> None:
	if summary.get("infrastructureStatus") != "PASS":
		raise ValueError("forced campaign infrastructureStatus is not PASS")
	classification = summary.get("classificationStatus")
	if classification == "ALL_SUCCESS":
		expected = int(summary.get("expectedTargets", -1))
		if expected <= 0:
			raise ValueError("ALL_SUCCESS summary has no expected targets")
		for field in ("missingResultTargetIds", "unexpectedResultTargetIds",
			"missingRuntimeCapabilityTargetIds", "unexpectedRuntimeCapabilityTargetIds"):
			if summary.get(field) != []:
				raise ValueError(f"ALL_SUCCESS summary requires explicit empty {field}")
		if int(summary.get("duplicateResultRows", -1)) != 0:
			raise ValueError("ALL_SUCCESS summary has duplicate result rows")
		if summary.get("resultOutcomes") != {"SUCCESS": expected}:
			raise ValueError("ALL_SUCCESS summary has non-success result outcomes")
		capability_outcomes = summary.get("runtimeCapabilityOutcomes")
		if not isinstance(capability_outcomes, dict) or not capability_outcomes \
			or set(capability_outcomes) != {"SUCCESS"}:
			raise ValueError("ALL_SUCCESS summary lacks exclusively successful runtime capabilities")
		if int(summary.get("targetsWithRuntimeCapability", -1)) != expected:
			raise ValueError("ALL_SUCCESS summary lacks runtime capability for every target")
		return
	if allow_needs_triage_diagnostic and classification == "NEEDS_TRIAGE":
		return
	raise ValueError(
		"authoritative forced campaign classificationStatus is not ALL_SUCCESS")


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("summary", type=Path)
	parser.add_argument("--allow-needs-triage-diagnostic", action="store_true")
	args = parser.parse_args()
	summary = json.loads(args.summary.read_text(encoding="utf-8"))
	validate(summary, args.allow_needs_triage_diagnostic)
	print(json.dumps({"infrastructureStatus": summary.get("infrastructureStatus"),
		"classificationStatus": summary.get("classificationStatus"),
		"diagnosticOverride": args.allow_needs_triage_diagnostic}, sort_keys=True))


if __name__ == "__main__":
	main()
