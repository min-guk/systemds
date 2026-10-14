#!/usr/bin/env python3
"""Compare candidate03 with candidate02 without constraining intentional search changes."""
import argparse
import json
from pathlib import Path


def delta(before, after):
	return {
		"before": before,
		"after": after,
		"delta": after - before,
		"percent": (after / before - 1.0) * 100 if before else None,
	}


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--primary-evaluation", required=True)
	ap.add_argument("--candidate02-summary", required=True)
	ap.add_argument("--candidate03-summary", required=True)
	ap.add_argument("--candidate02-profile", required=True)
	ap.add_argument("--candidate03-profile", required=True)
	ap.add_argument("--candidate02-hotspots", required=True)
	ap.add_argument("--candidate03-hotspots", required=True)
	ap.add_argument("--forest-tail", required=True)
	ap.add_argument("--output", required=True)
	args = ap.parse_args()

	primary = json.loads(Path(args.primary_evaluation).read_text())
	c2 = json.loads(Path(args.candidate02_summary).read_text())
	c3 = json.loads(Path(args.candidate03_summary).read_text())
	p2 = json.loads(Path(args.candidate02_profile).read_text())
	p3 = json.loads(Path(args.candidate03_profile).read_text())
	h2 = json.loads(Path(args.candidate02_hotspots).read_text())
	h3 = json.loads(Path(args.candidate03_hotspots).read_text())
	forest = json.loads(Path(args.forest_tail).read_text())
	phase_names = sorted(set(c2["phaseSeconds"]) | set(c3["phaseSeconds"]))

	gates = {
		"primaryEvaluationPassed": primary.get("passed") is True,
		"forestTailTraceValid": forest.get("passed") is True,
		"forestTailFeatureExercised": forest.get("gates", {}).get("featureExercised") is True,
	}
	result = {
		"schema": "logreg-under10-candidate03-differential-v1",
		"passed": all(gates.values()),
		"gates": gates,
		"primaryBaselinePlanningSeconds": primary["planning"]["before"],
		"nodePolicy": (
			"Observed, not gated: the exact conditional-forest tail deliberately changes search shape. "
			"Exact objective and all semantic gates remain mandatory in the primary evaluation."
		),
		"candidate02ToCandidate03": {
			"planningSeconds": delta(c2["planningSeconds"], c3["planningSeconds"]),
			"optimizationSeconds": delta(c2["phaseSeconds"]["optimization"],
				c3["phaseSeconds"]["optimization"]),
			"compilationSeconds": delta(c2["compilationSeconds"], c3["compilationSeconds"]),
			"executionSeconds": delta(c2["executionSeconds"], c3["executionSeconds"]),
			"phaseSeconds": {
				name: delta(c2["phaseSeconds"].get(name, 0.0), c3["phaseSeconds"].get(name, 0.0))
				for name in phase_names
			},
			"nodes": delta(c2["solution"]["nodes"], c3["solution"]["nodes"]),
			"optimizerExecutionSamples": delta(h2["optimizerExecutionSamples"],
				h3["optimizerExecutionSamples"]),
			"optimizerAllocationWeightBytes": delta(h2["optimizerAllocationWeightBytes"],
				h3["optimizerAllocationWeightBytes"]),
			"gcPauseSeconds": delta(p2["gc"]["stopTheWorldPauseSeconds"],
				p3["gc"]["stopTheWorldPauseSeconds"]),
		},
		"candidate03Residual": {
			"exclusiveNearestMemoBuckets": h3["exclusiveNearestMemoBuckets"],
			"trackedInclusiveMethods": h3["trackedInclusiveMethods"],
		},
		"forestTail": forest,
		"limitations": [
			"one JFR run per candidate; timing is descriptive, not a confidence interval",
			"JFR samples and allocation weights are statistical",
		],
	}
	Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
	print(json.dumps({
		"passed": result["passed"],
		"gates": gates,
		"planning": result["candidate02ToCandidate03"]["planningSeconds"],
		"optimization": result["candidate02ToCandidate03"]["optimizationSeconds"],
		"nodesObserved": result["candidate02ToCandidate03"]["nodes"],
	}, indent=2))


if __name__ == "__main__":
	main()
