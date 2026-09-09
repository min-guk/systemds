#!/usr/bin/env python3
"""Join exploratory planner contexts to exact, passing JUnit leaf identities.

Exploratory candidate rows are used only as a method-level inventory signal.
They are never copied into an authoritative manifest.  Surefire XML expands
each selected method to its exact parameterized JUnit leaves, which are then
rediscovered one leaf per fresh test JVM by run_exact_candidate_discovery.sh.
"""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path


RUNNER_CLASSES = {
	"org.apache.sysds.test.functions.federated.FederatedForcedStateAuditRunnerTest",
	"org.apache.sysds.test.functions.federated.FederatedPlannerAuditDiscoveryRunnerTest",
}


def candidate_contexts(directories: list[Path]) -> set[str]:
	contexts: set[str] = set()
	paths = sorted(path for directory in directories
		for path in directory.rglob("*candidate-space-*.jsonl"))
	if not paths:
		raise ValueError("exploratory discovery produced zero candidate files")
	for path in paths:
		for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
			if not line.strip():
				continue
			try:
				row = json.loads(line)
			except json.JSONDecodeError as exc:
				raise ValueError(f"{path}:{line_no}: invalid JSONL: {exc}") from exc
			context = row.get("auditContext")
			if isinstance(context, str) and context.startswith("org.apache.sysds.test.") \
				and "#" in context:
				contexts.add(context)
	if not contexts:
		raise ValueError("exploratory candidate files contain zero JUnit audit contexts")
	return contexts


def exact_passing_leaves(report_directories: list[Path], contexts: set[str]) -> list[str]:
	outcomes: dict[str, list[str]] = {}
	for directory in report_directories:
		for path in sorted(directory.rglob("TEST-*.xml")):
			root = ET.parse(path).getroot()
			for case in root.iter("testcase"):
				class_name = case.get("classname")
				method_name = case.get("name")
				if not class_name or not method_name or class_name in RUNNER_CLASSES:
					continue
				base = method_name.split("[", 1)[0]
				context = f"{class_name}#{base}"
				if context not in contexts:
					continue
				invocation = f"{class_name}#{method_name}"
				status = "FAIL" if (case.find("failure") is not None
					or case.find("error") is not None) else (
					"SKIP" if case.find("skipped") is not None else "PASS")
				outcomes.setdefault(invocation, []).append(status)
	missing = sorted(contexts - {invocation.split("[", 1)[0] for invocation in outcomes})
	if missing:
		raise ValueError(f"candidate contexts missing from Surefire reports: {missing}")
	failing = sorted(invocation for invocation, values in outcomes.items()
		if any(value != "PASS" for value in values))
	if failing:
		raise ValueError(f"candidate-producing JUnit leaves did not all pass: {failing}")
	leaves = sorted(outcomes)
	if not leaves:
		raise ValueError("zero exact passing JUnit leaves selected for isolated discovery")
	return leaves


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--candidate-dir", type=Path, action="append", required=True)
	parser.add_argument("--surefire-dir", type=Path, action="append", required=True)
	parser.add_argument("--output", type=Path, required=True)
	args = parser.parse_args()
	contexts = candidate_contexts(args.candidate_dir)
	leaves = exact_passing_leaves(args.surefire_dir, contexts)
	args.output.parent.mkdir(parents=True, exist_ok=True)
	args.output.write_text("".join(json.dumps({"invocation": leaf}, sort_keys=True) + "\n"
		for leaf in leaves), encoding="utf-8")
	print(json.dumps({"exploratoryContexts": len(contexts),
		"exactPassingLeaves": len(leaves), "output": str(args.output)}, sort_keys=True))


if __name__ == "__main__":
	main()
