#!/usr/bin/env python3
"""Aggregate repeated normalized weighted-workload summaries."""

import argparse
import json
import pathlib
import statistics


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument("--summary", action="append", type=pathlib.Path, required=True)
	parser.add_argument("--output", type=pathlib.Path, required=True)
	args = parser.parse_args()
	runs = []
	for ordinal, path in enumerate(args.summary, 1):
		result = json.loads(path.read_text())
		case = result["cases"][0]
		runs.append({
			"run": ordinal,
			"status": result["status"],
			"compileSeconds": case["fedCompilationSeconds"],
			"planningSeconds": case["candidatePlanning"]["totalNanos"] / 1e9,
			"analysisSeconds": case["candidatePlanning"]["analysisNanos"] / 1e9,
			"auditRows": case["candidateWork"]["auditRows"],
			"prePrivacyStates": case["candidateWork"]["prePrivacyStates"],
			"publishedStates": case["candidateWork"]["publishedStates"],
			"alternatives": case["modelCounts"]["totalAlternativeCount"],
			"decisions": case["modelCounts"]["decisionCount"],
			"weightedKernelEvidence": case["requiredWeightedKernels"],
			"objectiveBits": case["canonicalObjectiveBits"],
			"objectiveMillis": case["canonicalObjectiveMillis"],
			"analysisFingerprint": case["analysisFingerprint"],
			"planFingerprint": case["normalizedPlanFingerprint"],
			"costFingerprint": case["costSurfaceFingerprint"],
			"jfrHeapBytes": case["jfrHeap"]["maxObservedHeapUsedBytes"],
			"containerMemoryBytes": result["wholeRun"]["peakSampledContainerMemoryBytes"],
			"outputFingerprint": case["outputFingerprint"],
		})
	metrics = ("compileSeconds", "planningSeconds", "analysisSeconds",
		"jfrHeapBytes", "containerMemoryBytes")
	aggregate = {key: {
		"median": statistics.median(run[key] for run in runs),
		"min": min(run[key] for run in runs),
		"max": max(run[key] for run in runs),
	} for key in metrics}
	output = {
		"schema": "fedplanner-weighted-private-aggregate-repeats-v1",
		"fixture": "weighted_quaternary_with_private_aggregate",
		"weightedOperandPrivacy": "PUBLIC",
		"separatePrivacyBranch": "PRIVATE_AGGREGATE_TO_PUBLIC",
		"runs": runs,
		"aggregate": aggregate,
		"stable": {
			"allPassed": all(run["status"] == "PASSED" for run in runs),
			"weightedEvidence": all(all(
				item["runtimeHeavyHitter"] and item["availableCandidateAudit"]
				for item in run["weightedKernelEvidence"].values()) for run in runs),
			"objectiveBits": len({run["objectiveBits"] for run in runs}) == 1,
			"analysisFingerprint": len({run["analysisFingerprint"] for run in runs}) == 1,
			"planFingerprint": len({run["planFingerprint"] for run in runs}) == 1,
			"costFingerprint": len({run["costFingerprint"] for run in runs}) == 1,
			"outputFingerprint": len({json.dumps(run["outputFingerprint"], sort_keys=True)
				for run in runs}) == 1,
		},
	}
	args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
	main()
