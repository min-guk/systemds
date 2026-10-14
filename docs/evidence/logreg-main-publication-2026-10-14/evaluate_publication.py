#!/usr/bin/env python3
"""Evaluate the final integrated-main LogReg run against frozen references."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(path):
	data = Path(path).read_bytes()
	return {"path": str(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def audit_rows(directory):
	rows = []
	artifacts = []
	for path in sorted(Path(directory).glob("*.jsonl")):
		artifacts.append(sha(path))
		for line in path.read_text().splitlines():
			row = json.loads(line)
			row.pop("pid", None)
			rows.append(json.dumps(row, sort_keys=True, separators=(",", ":")))
	return sorted(rows), artifacts


def delta(before, after):
	return {"before": before, "after": after, "delta": after - before,
		"percent": (after / before - 1.0) * 100 if before else None}


def case(result):
	return result["cases"][0]


def planning(result):
	return case(result)["memoPlanning"][0]


def main():
	ap = argparse.ArgumentParser()
	for name in ("primary-result", "candidate03-result", "publication-result",
		"primary-csv", "candidate03-csv", "publication-csv", "primary-audit-dir",
		"candidate03-audit-dir", "publication-audit-dir", "candidate03-profile",
		"publication-profile", "candidate03-hotspots", "publication-hotspots",
		"freeze-verification", "forest-tail", "output"):
		ap.add_argument("--" + name, required=True)
	args = ap.parse_args()

	primary = json.loads(Path(args.primary_result).read_text())
	c03 = json.loads(Path(args.candidate03_result).read_text())
	pub = json.loads(Path(args.publication_result).read_text())
	pc, c3c, uc = case(primary), case(c03), case(pub)
	pp, c3p, up = planning(primary), planning(c03), planning(pub)
	c3prof = json.loads(Path(args.candidate03_profile).read_text())
	uprof = json.loads(Path(args.publication_profile).read_text())
	c3hot = json.loads(Path(args.candidate03_hotspots).read_text())
	uhot = json.loads(Path(args.publication_hotspots).read_text())
	freeze = json.loads(Path(args.freeze_verification).read_text())
	forest = json.loads(Path(args.forest_tail).read_text())
	pa, pa_files = audit_rows(args.primary_audit_dir)
	c3a, c3a_files = audit_rows(args.candidate03_audit_dir)
	ua, ua_files = audit_rows(args.publication_audit_dir)
	pcsv, c3csv, ucsv = sha(args.primary_csv), sha(args.candidate03_csv), sha(args.publication_csv)

	def same_reference(getter):
		value = getter(uc, up)
		return value == getter(pc, pp) == getter(c3c, c3p)

	gates = {
		"runPassed": pub.get("status") == "PASSED" and uc.get("passed") is True,
		"planningUnder10Seconds": up["totalSeconds"] < 10.0,
		"optimizerOptimal": up["status"] == "OPTIMAL",
		"exactObjectiveEqualBothReferences": same_reference(lambda c, p: p["estimatedCost"]),
		"csvByteEqualBothReferences": (pcsv["size"], pcsv["sha256"])
			== (c3csv["size"], c3csv["sha256"]) == (ucsv["size"], ucsv["sha256"]),
		"fedFingerprintEqualBothReferences": same_reference(lambda c, p: c["fedFingerprint"]),
		"actionDiagnosticsEqualBothReferences": same_reference(lambda c, p: c["actionDiagnostics"]),
		"runtimeAuditNormalizedEqualBothReferences": pa == c3a == ua,
		"runtimeAuditClean": uc["runtimeAuditViolations"] == [],
		"oracleTraceEqualBothReferences": same_reference(
			lambda c, p: (p.get("oracleHits"), p.get("oracleMisses"))),
		"nativeUniverseEqualBothReferences": same_reference(
			lambda c, p: c["memoConstruction"]["nativeGeneration"]),
		"nativeRequestsEqualBothReferences": same_reference(
			lambda c, p: c["memoConstruction"]["nativeRequests"]),
		"workerCountEqual": same_reference(lambda c, p: p["workers"]),
		"nativeRulesEqual": same_reference(lambda c, p: p["nativeRules"]),
		"supplyCountEqual": same_reference(lambda c, p: p["supplies"]),
		"jfrParsedWithoutLoss": uc["jfrProfilePassed"] is True and len(uprof["dataLoss"]) == 0,
		"frozenEngineAndRunInputsVerified": freeze.get("passed") is True,
		"forestTailTraceValidAndExercised": forest.get("passed") is True,
	}
	result = {
		"schema": "logreg-main-publication-evaluation-v1",
		"passed": all(gates.values()),
		"gates": gates,
		"counterPolicy": (
			"Privacy, shape-reuse, movement-reuse, forest-tail, search and other request counters "
			"are reported but are not equality gates for the integrated source."
		),
		"planning": {
			"primaryToPublication": delta(pp["totalSeconds"], up["totalSeconds"]),
			"candidate03ToPublication": delta(c3p["totalSeconds"], up["totalSeconds"]),
		},
		"optimization": {
			"primaryToPublication": delta(pp["phaseSeconds"]["optimization"],
				up["phaseSeconds"]["optimization"]),
			"candidate03ToPublication": delta(c3p["phaseSeconds"]["optimization"],
				up["phaseSeconds"]["optimization"]),
		},
		"nodes": {
			"primaryToPublication": delta(pp["nodes"], up["nodes"]),
			"candidate03ToPublication": delta(c3p["nodes"], up["nodes"]),
		},
		"solution": {"objective": up["estimatedCost"], "csv": ucsv,
			"fedFingerprint": uc["fedFingerprint"]},
		"audit": {"primary": pa_files, "candidate03": c3a_files, "publication": ua_files},
		"forestTail": forest,
		"jfrCandidate03ToPublication": {
			"optimizerExecutionSamples": delta(c3hot["optimizerExecutionSamples"],
				uhot["optimizerExecutionSamples"]),
			"optimizerAllocationWeightBytes": delta(c3hot["optimizerAllocationWeightBytes"],
				uhot["optimizerAllocationWeightBytes"]),
			"gcPauseSeconds": delta(c3prof["gc"]["stopTheWorldPauseSeconds"],
				uprof["gc"]["stopTheWorldPauseSeconds"]),
		},
		"publicationResidualProfile": {
			"exclusiveNearestMemoBuckets": uhot["exclusiveNearestMemoBuckets"],
			"trackedInclusiveMethods": uhot["trackedInclusiveMethods"],
		},
		"limitations": ["one final integrated JFR run", "JFR samples are statistical"],
	}
	Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
	print(json.dumps({"passed": result["passed"], "gates": gates,
		"planning": result["planning"], "optimization": result["optimization"],
		"nodes": result["nodes"]}, indent=2))


if __name__ == "__main__":
	main()
