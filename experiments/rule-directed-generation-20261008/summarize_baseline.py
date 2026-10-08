#!/usr/bin/env python3
"""Normalize a joint-boundary run into comparison-ready planner metrics."""

import argparse
import glob
import json
import pathlib
import re
import subprocess


RECEIPT = re.compile(r"CandidateE2EReceipt (.*)")


def receipt(path):
	for line in path.read_text().splitlines():
		match = RECEIPT.search(line)
		if match:
			return {key: int(value) if value.isdigit() else value
				for key, value in (field.split("=", 1) for field in match.group(1).split())}
	raise ValueError(f"missing CandidateE2EReceipt in {path}")


def heap_summary(path):
	process = subprocess.run(
		["jfr", "print", "--json", "--events", "jdk.GCHeapSummary", str(path)],
		check=True, capture_output=True, text=True)
	events = json.loads(process.stdout)["recording"]["events"]
	values = [event["values"] for event in events]
	return {
		"eventCount": len(values),
		"maxObservedHeapUsedBytes": max((value["heapUsed"] for value in values), default=None),
		"maxObservedHeapCommittedBytes": max(
			(value["heapSpace"]["committedSize"] for value in values), default=None),
		"semantics": "maximum at GC heap-summary event boundaries; coordinator JVM only",
	}


def candidate_work(run, case):
	paths = glob.glob(str(run / "audit" / f"{case}-fed" / "candidate-space-*.jsonl"))
	if len(paths) != 1:
		raise ValueError(f"expected one candidate audit for {case}, got {paths}")
	rows = [json.loads(line) for line in open(paths[0])]
	return {
		"auditRows": len(rows),
		"uniqueHopIds": len({row.get("hopId") for row in rows}),
		"availableRules": sum(row.get("publishedRule", {}).get("status") == "AVAILABLE" for row in rows),
		"prePrivacyStates": sum(len(row.get("prePrivacyNodeStates", [])) for row in rows),
		"publishedStates": sum(len(row.get("publishedStatesP", [])) for row in rows),
		"publishedExclusions": sum(len(row.get("publishedExclusions", [])) for row in rows),
		"analysisFingerprints": sorted({row.get("analysisFingerprint") for row in rows}),
		"semantics": "published candidate-space audit; does not include internal oracle call counts",
	}


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument("--run", type=pathlib.Path, required=True)
	parser.add_argument("--monitor-summary", type=pathlib.Path, required=True)
	parser.add_argument("--output", type=pathlib.Path, required=True)
	args = parser.parse_args()
	result = json.loads((args.run / "result.json").read_text())
	manifest = json.loads((args.run / "manifest.json").read_text())
	monitor = json.loads(args.monitor_summary.read_text())
	output = {
		"schema": "fedplanner-rule-directed-baseline-v1",
		"status": result["status"],
		"planner": result["expectedNormalizedPlanner"],
		"configuredPlanner": result["configuredPlanner"],
		"image": result["image"],
		"engineInventoryDigests": manifest["artifactInventoryDigests"],
		"runtimeHarness": {
			"cpus": 4,
			"containerMemoryLimitBytes": 8 * 1024**3,
			"coordinatorHeapLimitBytes": 3 * 1024**3,
			"network": manifest["network"],
			"profileJfr": result["recordProfile"],
		},
		"wholeRun": monitor,
		"cases": [],
	}
	for case_result in result["cases"]:
		case = case_result["case"]
		proof = json.loads((args.run / "cases" / case / "fed-canonical-proof.json").read_text())
		canonical = proof["canonicalProof"]
		output["cases"].append({
			"case": case,
			"passed": case_result["passed"],
			"outputFingerprint": case_result["fedFingerprint"],
			"outputMatchesCp": case_result["fedFingerprint"] == case_result["cpFingerprint"],
			"fedCompilationSeconds": case_result["fedStatistics"]["compilationSeconds"],
			"candidatePlanning": receipt(args.run / "cases" / case / "fed.log"),
			"candidateWork": candidate_work(args.run, case),
			"requiredWeightedKernels": case_result.get("requiredWeightedKernels", {}),
			"analysisFingerprint": proof["analysisFingerprint"],
			"normalizedPlanFingerprint": proof["normalizedPlanFingerprint"],
			"costSurfaceFingerprint": canonical["reconstructedCostSurfaceFingerprint"],
			"canonicalObjectiveBits": canonical["canonicalObjectiveBits"],
			"canonicalObjectiveMillis": canonical["canonicalObjectiveMillis"],
			"objectiveMatchesCertificate": canonical["objectiveMatches"],
			"modelCounts": canonical["modelCounts"],
			"coordinatorMemoryReceipt": proof["coordinatorMemory"],
			"coordinatorGcCount": proof["coordinatorGcCount"],
			"coordinatorGcMillis": proof["coordinatorGcMillis"],
			"jfrHeap": heap_summary(args.run / "cases" / case / "fed.jfr"),
		})
	args.output.parent.mkdir(parents=True, exist_ok=True)
	args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
	main()
