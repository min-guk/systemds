#!/usr/bin/env python3
"""Pure contract tests for stage/JAR-pinned current workload references."""

from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import matrix_current_reference as current


class MatrixCurrentReferenceTest(unittest.TestCase):
	def _documents(self, selector="ml:steplm"):
		suite, workload = selector.split(":")
		root = "/home/mchoi/w1357-reference-attempt"
		proof = "d" * 64
		identity = {
			"stage_seal_sha256": "a" * 64,
			"jar_sha256": "b" * 64,
			"image_sha256": "c" * 64,
			"source_sha256": {"program": "e" * 64},
			"seeds": {"seed": 7},
			"environment": {"threads": 8},
			"correctness_contract_sha256": "f" * 64,
			"decoder_source_sha256": "1" * 64,
			"runtime_config_sha256": "2" * 64,
		}
		targets = [{"role": role, "data_type": data_type, "format": fmt,
			"path": f"{root}/{role}"}
			for role, (data_type, fmt) in current.OUTPUTS[selector].items()]
		program_sha = "3" * 64
		plan = {
			"schema": current.old.REFERENCE_PLAN_SCHEMA,
			"host": current.old.REFERENCE_HOST,
			"workload_selector": selector,
			"output_root": root,
			"planner": "none",
			"execution_mode": "singlenode",
			"contracts": [{"suite": suite, "workload": workload, "outputs": targets,
				"provenance": {"derived_dml_sha256": program_sha}}],
			**identity,
		}
		result = {
			"schema": current.old.REFERENCE_RESULT_SCHEMA,
			"status": "READY", "ready": True, "errors": [], "verification_gaps": [],
			"output_root": root,
			"provenance": {**identity, "planner": "none", "execution_mode": "singlenode",
				"workload_selector": selector},
			"workloads": [{"suite": suite, "workload": workload,
				"program_sha256": program_sha,
				"status": {"returncode": 0, "executions": 1,
					"dmlscript_fatal_marker": False},
				"outputs": [{**target, "metadata_sha256": proof,
					"tree": {"tree_sha256": proof}, "decoded": {"sha256": proof}}
					for target in targets]}],
		}
		pin = {
			"schema": current.SCHEMA,
			"source_host": current.old.REFERENCE_HOST,
			"source_root": root,
			"workload_selector": selector,
			"expected": identity,
		}
		return result, plan, pin

	def test_both_supported_workloads_require_complete_typed_evidence(self):
		for selector in current.OUTPUTS:
			with self.subTest(selector=selector):
				current.validate(*self._documents(selector))

	def test_identity_execution_and_output_mutations_fail_closed(self):
		mutations = (
			lambda result, plan, pin: result["provenance"].update(jar_sha256="9" * 64),
			lambda result, plan, pin: result["workloads"][0]["status"].update(returncode=1),
			lambda result, plan, pin: result["workloads"][0].update(program_sha256="8" * 64),
			lambda result, plan, pin: result["workloads"][0]["outputs"][0].pop(
				"metadata_sha256"),
			lambda result, plan, pin: plan["contracts"][0]["outputs"][0].update(format="text"),
		)
		for mutate in mutations:
			result, plan, pin = copy.deepcopy(self._documents())
			mutate(result, plan, pin)
			with self.subTest(mutate=mutate), self.assertRaises(
					current.old.RuntimeComparisonError):
				current.validate(result, plan, pin)

	def test_load_binds_exact_local_paths_and_hashes(self):
		result, plan, pin = self._documents()
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			result_path = root / "result-manifest.json"
			plan_path = root / "generation-plan.json"
			result_raw = json.dumps(result, sort_keys=True).encode()
			plan_raw = json.dumps(plan, sort_keys=True).encode()
			result_path.write_bytes(result_raw)
			plan_path.write_bytes(plan_raw)
			pin["result_manifest"] = {"local_path": str(result_path),
				"remote_path": pin["source_root"] + "/result-manifest.json",
				"sha256": hashlib.sha256(result_raw).hexdigest()}
			pin["generation_plan"] = {"local_path": str(plan_path),
				"remote_path": pin["source_root"] + "/generation-plan.json",
				"sha256": hashlib.sha256(plan_raw).hexdigest()}
			(root / "reference-pin.json").write_text(json.dumps(pin))
			loaded = current.load(result_path)
			self.assertEqual(pin, loaded[-1])
			pin["result_manifest"]["sha256"] = "0" * 64
			(root / "reference-pin.json").write_text(json.dumps(pin))
			with self.assertRaisesRegex(current.old.RuntimeComparisonError,
					"current reference SHA mismatch"):
				current.load(result_path)


if __name__ == "__main__":
	unittest.main()
