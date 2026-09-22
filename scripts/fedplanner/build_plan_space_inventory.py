#!/usr/bin/env python3
"""Freeze the discovered federated workload registry without claiming certification.

This is a discovery manifest, distinct from the executable verifier's cell manifest.
Changing a registry or template makes --check fail until the inventory is reviewed.
"""

import argparse
import ast
import hashlib
import json
from pathlib import Path


HERE = Path(__file__).resolve()
SOURCE = HERE.parents[2]
DEFAULT_EVALUATION = SOURCE.parent / "cofee-evaluation"
DEFAULT_LEGACY = SOURCE.parent / "COFEE-Experiment"
DEFAULT_PLANNING_STAGE = SOURCE.parent / "ml-p1p2-sliceline-fedplanning-w4-once-20260921"
DEFAULT_OUTPUT = SOURCE / "src/test/resources/fedplanner/plan-space/workloads.json"


def sha256(path):
	return hashlib.sha256(path.read_bytes()).hexdigest()


def assignment(path, name):
	module = ast.parse(path.read_text())
	for node in module.body:
		if isinstance(node, (ast.Assign, ast.AnnAssign)):
			targets = node.targets if isinstance(node, ast.Assign) else [node.target]
			if any(isinstance(target, ast.Name) and target.id == name for target in targets):
				return ast.literal_eval(node.value)
	raise ValueError(f"missing literal {name} in {path}")


def build(evaluation, legacy=DEFAULT_LEGACY, planning_stage=DEFAULT_PLANNING_STAGE):
	driver = evaluation / "driver/run_multihost_campaign_network_quality_v2.py"
	ml10 = evaluation / "campaign/run_ml10_campaign.py"
	micro = evaluation / "microbench/generate.py"
	planning = evaluation / "planning_study/native"
	protocols = [planning / f"protocol-w{workers}.json" for workers in (1, 3, 5, 7)]
	contexts = [planning / f"context-w{workers}.json" for workers in (1, 3, 5, 7)]
	contract = evaluation / "config/SOURCE_STAGE_CONTRACT.json"
	harness_parameters = evaluation / "harness/sigmod2021-exdra-p523/experiments/parameters.sh"
	legacy_parameters = legacy / "experiments/parameters.sh"
	for path in (driver, ml10, micro, *protocols, *contexts, contract,
		harness_parameters, legacy_parameters):
		if not path.is_file():
			raise FileNotFoundError(path)
	registry = assignment(driver, "WORKLOADS_BY_SUITE")
	ml = assignment(ml10, "WORKLOADS")
	scales = assignment(micro, "SCALES")
	families = assignment(micro, "FAMILIES")
	variants = assignment(micro, "REUSE_VARIANTS")
	assert len(scales) == 4 and len(families) == 6 and len(variants) == 2

	sources = (driver, ml10, micro, *protocols, *contexts, contract,
		harness_parameters, legacy_parameters)
	source_hashes = {str(path.relative_to(evaluation)) if path.is_relative_to(evaluation)
		else "legacy:" + str(path.relative_to(legacy)): sha256(path) for path in sources}
	entries = []
	for suite, workloads in registry.items():
		for workload in workloads:
			name = f"sliceline-{workload.lower()}" if suite == "sliceline" else workload
			entries.append({"id": f"base:{suite}:{name}", "name": name,
				"kind": "base-campaign", "status": "IN_SCOPE",
				"verificationStatus": "NOT_RUN", "registry": "driver/run_multihost_campaign_network_quality_v2.py",
				"conditionStatus": "UNRESOLVED"})
	for workload in ml:
		entries.append({"id": f"ml10:{workload}", "name": workload,
			"kind": "ml10-campaign", "status": "IN_SCOPE",
			"verificationStatus": "NOT_RUN", "registry": "campaign/run_ml10_campaign.py",
			"conditionStatus": "UNRESOLVED"})
	template_hashes = {}
	planned_conditions = []
	for workers in (1, 3, 5, 7):
		programs = planning / f"input_templates/w{workers}/programs"
		program_files = sorted(programs.glob("*.dml"))
		if not program_files:
			raise ValueError(f"no frozen w{workers} planning templates found")
		context = json.loads((planning / f"context-w{workers}.json").read_text())
		profile = json.loads((planning / f"protocol-w{workers}.json").read_text())
		cases = {case["workload"]: case for case in context["cases"]}
		if len(cases) != len(context["cases"]) or set(profile["profiles"]) != set(context["profiles"]):
			raise ValueError(f"w{workers} case/profile identities are inconsistent")
		if set(cases) - {path.stem for path in program_files}:
			raise ValueError(f"w{workers} context case has no frozen program")
		for path in program_files:
			template_key = str(path.relative_to(planning))
			template_hashes[template_key] = sha256(path)
			case = cases.get(path.stem)
			if case is not None:
				if (case["program"] != f"programs/{path.name}" or
					case["program_sha256"] != template_hashes[template_key] or
					case["workers"] != workers):
					raise ValueError(f"w{workers} case no longer matches {path.name}")
				for network in profile["profiles"]:
					payload = {"workers": workers, "case": case,
						"network": context["profiles"][network]}
					condition = {"discoveryId": f"planning-w{workers}:{path.stem}",
						"conditionId": network, **payload}
					planned_conditions.append({"discoveryId": condition["discoveryId"],
						"conditionId": network, "payload": payload,
						"conditionSha256": hashlib.sha256(json.dumps(condition,
							sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()})
			entries.append({"id": f"planning-w{workers}:{path.stem}", "name": path.stem,
				"kind": "planning-snapshot", "status": "IN_SCOPE",
				"verificationStatus": "NOT_RUN", "workers": workers,
				"template": template_key, "templateSha256": template_hashes[template_key],
				"conditionStatus": "SNAPSHOT_ONLY" if case else "UNRESOLVED"})
	for family in families:
		for variant in (variants if family == "reuse_update" else (None,)):
			for scale in scales:
				name = f"{family}-{variant}-k{scale}" if variant else f"{family}-k{scale}"
				entries.append({"id": f"microbench:{name}", "name": name,
					"kind": "generated-microbench", "status": "IN_SCOPE",
					"verificationStatus": "NOT_RUN", "generator": "microbench/generate.py",
					"family": family, "variant": variant, "scale": scale,
					"conditionStatus": "UNRESOLVED"})

	# These appear in the network harness or snapshot but are not connected to
	# the current packaged experiment launcher. Keep them visible in the denominator.
	optional = ("alsCG", "naiveBayes", "msvm", "FNN", "CNN", "P2_FFN",
		"FFN", "gmm_p1_compat", "P1_ALS", "P1_KMEANS", "P1_L2SVM", "P1_LM",
		"P1_LOGREG", "P1_PCA", "P1_STEPLM", "P2_ALS", "P2_KMEANS",
		"P2_L2SVM", "P2_LM", "P2_LOGREG", "P2_PCA", "P2_STEPLM")
	for name in optional:
		entries.append({"id": "optional:" + name, "name": name,
			"kind": "optional-template", "status": "UNSUPPORTED",
			"verificationStatus": "NOT_RUN",
			"reason": "Template/entrypoint and frozen input conditions not yet connected to the official launcher"})
	common = planning / "input_templates/common/code/exp"
	common_templates = {}
	for path in sorted(common.glob("*.dml")):
		common_templates[path.name] = sha256(path)
		entries.append({"id": "common:" + path.stem, "name": path.stem,
			"kind": "unclassified-template", "status": "UNSUPPORTED",
			"verificationStatus": "NOT_RUN",
			"reason": "Snapshot template revision exists but its workload/helper role and frozen conditions are not classified",
			"templateSha256": common_templates[path.name]})
	archive = legacy / "experiments/archive/submitted_results/code/exp"
	archive_templates = {}
	for path in sorted(archive.glob("*.dml")):
		archive_templates[path.name] = sha256(path)
		entries.append({"id": "archive:" + path.stem, "kind": "historical-template",
			"status": "HISTORICAL", "verificationStatus": "NOT_RUN",
			"reason": "Archived revision; needs explicit equivalence review before exclusion from current certification",
			"templateSha256": archive_templates[path.name]})
	# Discover every DML byte revision in the two external workload trees. A
	# source file can be a helper rather than an executable workload; until its
	# role is reviewed it stays visible and blocks a full-corpus PASS.
	known_evaluation = {
		path.relative_to(evaluation) for workers in (1, 3, 5, 7)
		for path in (planning / f"input_templates/w{workers}/programs").glob("*.dml")}
	known_evaluation.update(path.relative_to(evaluation) for path in common.glob("*.dml"))
	known_legacy = {path.relative_to(legacy) for path in archive.glob("*.dml")}
	discovered_files = {}
	for label, root, known in (("evaluation", evaluation, known_evaluation),
			("legacy", legacy, known_legacy)):
		for path in sorted(root.rglob("*.dml")):
			if not path.is_file():
				continue
			relative = path.relative_to(root)
			key = f"{label}:{relative.as_posix()}"
			discovered_files[key] = sha256(path)
			if relative not in known:
				entries.append({"id": "unclassified:" + key, "name": path.stem,
					"kind": "unclassified-source", "status": "UNSUPPORTED",
					"verificationStatus": "NOT_RUN", "sourcePath": key,
					"sourceSha256": discovered_files[key],
					"reason": "DML source discovered, but its workload/helper role and frozen execution conditions are not classified"})
	entries.sort(key=lambda row: (row["kind"], row["id"]))
	if len({row['id'] for row in entries}) != len(entries):
		raise ValueError("source-qualified discovery identities collided")
	stage_launcher = planning_stage / "experiments/run_LAN_docker.sh"
	return {"schemaVersion": 1, "purpose": "discovery-only; never a plan-space PASS receipt",
		"sources": source_hashes, "frozenW1Templates": template_hashes,
		"additionalSnapshotTemplates": common_templates,
		"historicalTemplates": archive_templates,
		"discoveredSourceFiles": discovered_files,
		"plannedConditions": sorted(planned_conditions,
			key=lambda row: (row["discoveryId"], row["conditionId"])),
		"executionBlockers": {
			"officialCampaignLauncherPresent":
				(evaluation / "harness/sigmod2021-exdra-p523/experiments/run_LAN_docker.sh").is_file(),
			"separatePlanningValidationLauncher": str(stage_launcher) if stage_launcher.is_file() else None,
			"separatePlanningValidationLauncherSha256": sha256(stage_launcher)
				if stage_launcher.is_file() else None,
			"separatePlanningValidationScope": "protected 14-workload, worker=4, LAN only",
			"frozenNativeSnapshotIncludesJarAndData": False,
			"fullRuleCoverageEstablished": False,
			"independentFullUniverseEstablished": False,
		},
		"entries": entries}


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--evaluation-root", type=Path, default=DEFAULT_EVALUATION)
	parser.add_argument("--legacy-root", type=Path, default=DEFAULT_LEGACY)
	parser.add_argument("--planning-stage", type=Path, default=DEFAULT_PLANNING_STAGE)
	parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
	parser.add_argument("--check", action="store_true")
	args = parser.parse_args()
	content = json.dumps(build(args.evaluation_root, args.legacy_root, args.planning_stage),
		indent=2, ensure_ascii=False) + "\n"
	if args.check:
		if not args.output.is_file() or args.output.read_text() != content:
			parser.error(f"inventory drift: regenerate and review {args.output}")
		print(f"inventory matches {args.output}")
	else:
		args.output.parent.mkdir(parents=True, exist_ok=True)
		args.output.write_text(content)
		print(f"wrote {args.output}")


if __name__ == "__main__":
	main()
