#!/usr/bin/env python3
"""Evaluate candidate02 against candidate01 while preserving the primary baseline gates."""
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
	ap.add_argument("--candidate01-summary", required=True)
	ap.add_argument("--candidate02-summary", required=True)
	ap.add_argument("--candidate01-profile", required=True)
	ap.add_argument("--candidate02-profile", required=True)
	ap.add_argument("--candidate01-hotspots", required=True)
	ap.add_argument("--candidate02-hotspots", required=True)
	ap.add_argument("--output", required=True)
	args = ap.parse_args()

	primary = json.loads(Path(args.primary_evaluation).read_text())
	c1 = json.loads(Path(args.candidate01_summary).read_text())
	c2 = json.loads(Path(args.candidate02_summary).read_text())
	p1 = json.loads(Path(args.candidate01_profile).read_text())
	p2 = json.loads(Path(args.candidate02_profile).read_text())
	h1 = json.loads(Path(args.candidate01_hotspots).read_text())
	h2 = json.loads(Path(args.candidate02_hotspots).read_text())

	nodes1 = c1["solution"]["nodes"]
	nodes2 = c2["solution"]["nodes"]
	gates = {
		"primaryEvaluationPassed": primary.get("passed") is True,
		"searchNodesEqualCandidate01": nodes2 == nodes1,
	}
	phase_names = sorted(set(c1["phaseSeconds"]) | set(c2["phaseSeconds"]))
	result = {
		"schema": "logreg-under10-candidate02-differential-v1",
		"passed": all(gates.values()),
		"gates": gates,
		"nodeGateRationale": (
			"Candidate02 changes solver hot paths while preserving candidate01 search semantics; "
			"a node-count change requires an explicit deliberate heuristic change."
		),
		"primaryBaselinePlanningSeconds": primary["planning"]["before"],
		"candidate01ToCandidate02": {
			"planningSeconds": delta(c1["planningSeconds"], c2["planningSeconds"]),
			"optimizationSeconds": delta(c1["phaseSeconds"]["optimization"],
				c2["phaseSeconds"]["optimization"]),
			"compilationSeconds": delta(c1["compilationSeconds"], c2["compilationSeconds"]),
			"executionSeconds": delta(c1["executionSeconds"], c2["executionSeconds"]),
			"phaseSeconds": {
				name: delta(c1["phaseSeconds"].get(name, 0.0), c2["phaseSeconds"].get(name, 0.0))
				for name in phase_names
			},
			"nodes": delta(nodes1, nodes2),
			"optimizerExecutionSamples": delta(h1["optimizerExecutionSamples"],
				h2["optimizerExecutionSamples"]),
			"optimizerAllocationWeightBytes": delta(h1["optimizerAllocationWeightBytes"],
				h2["optimizerAllocationWeightBytes"]),
			"gcPauseSeconds": delta(p1["gc"]["stopTheWorldPauseSeconds"],
				p2["gc"]["stopTheWorldPauseSeconds"]),
		},
		"candidate02Residual": {
			"exclusiveNearestMemoBuckets": h2["exclusiveNearestMemoBuckets"],
			"trackedInclusiveMethods": h2["trackedInclusiveMethods"],
		},
		"limitations": [
			"one JFR run per candidate; timing is descriptive, not a confidence interval",
			"JFR samples and allocation weights are statistical",
		],
	}
	Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
	print(json.dumps({
		"passed": result["passed"],
		"gates": gates,
		"planning": result["candidate01ToCandidate02"]["planningSeconds"],
		"optimization": result["candidate01ToCandidate02"]["optimizationSeconds"],
		"nodes": result["candidate01ToCandidate02"]["nodes"],
	}, indent=2))


if __name__ == "__main__":
	main()
