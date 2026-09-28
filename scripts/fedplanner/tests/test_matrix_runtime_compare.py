import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import matrix_runtime_compare as compare_adapter


class MatrixRuntimeCompareTest(unittest.TestCase):
	def _reference(self, rows):
		result = json.dumps({
			"schema": compare_adapter.REFERENCE_RESULT_SCHEMA,
			"output_root": str(compare_adapter.REFERENCE_ROOT),
			"status": "FAILED", "workloads": rows,
		}, sort_keys=True).encode()
		plan = json.dumps({
			"schema": compare_adapter.REFERENCE_PLAN_SCHEMA,
			"host": compare_adapter.REFERENCE_HOST,
		}, sort_keys=True).encode()
		return result, plan

	def _campaign(self, receipt):
		state = {"cleanup_ids": [], "published_request": None}

		def make_plan(cell, attempt, stage, run_root, reference, image):
			self.assertTrue(reference.is_file())
			return {
				"host": "so007", "name": f"compare-{attempt}",
				"create_argv": ["docker", "container", "create", image],
				"request": {
					"schema": compare_adapter.REQUEST_SCHEMA,
					"reference_manifest_path": "/mnt/result-manifest.json",
					"reference_manifest_sha256": "stale",
					"actual_artifacts": {"result": {"path": "/actual/result"}},
				},
			}

		def publish(plan):
			state["published_request"] = plan["request"]
			return {"files": {"request.json": "a" * 64}}

		def cleanup(plan, container_id):
			state["cleanup_ids"].append(container_id)
			return {"resolved": True, "container_id": container_id}

		campaign = SimpleNamespace(comparator_container_plan=make_plan,
			_publish_comparator_inputs=publish,
			_cleanup_comparator_container=cleanup)
		return campaign, state

	def _patch_reference_hashes(self, result, plan):
		return patch.multiple(compare_adapter,
			REFERENCE_RESULT_SHA256=hashlib.sha256(result).hexdigest(),
			REFERENCE_PLAN_SHA256=hashlib.sha256(plan).hexdigest())

	def _p2_reference(self, *, fatal=False):
		sha = "d" * 64
		outputs = [
			{"role": "result", "data_type": "matrix", "format": "binary",
				"path": "/mnt/w1357-v1/p2/P2_PREP/result"},
			{"role": "transform_metadata", "data_type": "frame", "format": "binary",
				"path": "/mnt/w1357-v1/p2/P2_PREP/transform_metadata"},
		]
		source = {
			"generator": compare_adapter._sha256_file(compare_adapter.GENERATOR),
			"reference_builder": compare_adapter._sha256_file(compare_adapter.REFERENCE_BUILDER),
		}
		identity = {
			"stage_seal_sha256": "ecf77f4ef2362bfbcf9ca41cbc40d2844493d6b128b08feb4df90e711c095838",
			"jar_sha256": "1dc2482d37d4d7b06e50bcd21f54a2c136b7a023108b8c375de9fa6644b992f2",
			"correctness_contract_sha256": "79bb0bf4e0a2a2f63fd71fd176c7cc40b3e6c2ae7031dccba6e7ef84aaf10149",
			"decoder_source_sha256": "ad0616ab9e3ba792efa205f8e76f567ef05ea1480bf7dc591fb7d21c4409951b",
		}
		plan = {"schema": compare_adapter.REFERENCE_PLAN_SCHEMA, "host": "so007",
			"attempt": compare_adapter.P2_ATTEMPT,
			"workload_selector": compare_adapter.P2_SELECTOR,
			"output_root": str(compare_adapter.P2_REFERENCE_ROOT),
			"planner": "none", "execution_mode": "singlenode", "source_sha256": source,
			"contracts": [{"suite": "p2", "workload": "P2_PREP", "outputs": outputs}],
			**identity}
		row_outputs = [{**output, "metadata_sha256": sha,
			"decoded": {"path": output["role"] + ".json", "sha256": sha},
			"tree": {"tree_sha256": sha}} for output in outputs]
		provenance = {**identity, "planner": "none", "execution_mode": "singlenode",
			"source_sha256": source}
		result = {"schema": compare_adapter.REFERENCE_RESULT_SCHEMA,
			"output_root": str(compare_adapter.P2_REFERENCE_ROOT), "status": "READY",
			"ready": True, "errors": [], "verification_gaps": [],
			"workloads": [{"suite": "p2", "workload": "P2_PREP",
				"status": {"returncode": 0, "executions": 1,
					"dmlscript_fatal_marker": fatal}, "outputs": row_outputs}],
			"provenance": provenance}
		return json.dumps(result, sort_keys=True).encode(), json.dumps(plan, sort_keys=True).encode()

	def test_uses_host_aware_reference_and_verifies_raw_hash_bindings(self):
		result, plan = self._reference([{
			"suite": "ml", "workload": "logreg",
			"status": {"returncode": 0, "executions": 1},
		}])
		sha = "b" * 64
		receipt = {"schema": "receipt-v1", "passed": True, "status": "verified",
			"correctness": "verified", "metrics": {"artifact_bindings": {"actual": {
				"result": {"tree_sha256": sha, "decoded_sha256": sha,
					"metadata_sha256": sha, "campaign_sha256": sha}}}}}
		campaign, state = self._campaign(receipt)
		processes = [SimpleNamespace(returncode=0, stdout="a" * 64, stderr=""),
			SimpleNamespace(returncode=0, stdout=json.dumps(receipt), stderr="")]
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter, "_reference_bytes", return_value=(result, plan)), \
				self._patch_reference_hashes(result, plan), \
				patch.object(compare_adapter.subprocess, "run", side_effect=processes):
			output = compare_adapter.compare(
				campaign, {"id": "cell", "suite": "ml", "workload": "logreg"},
				"attempt-1", Path("/stage"), Path("/remote/run"),
				Path(temporary) / "receipt.json", None, "image")
		self.assertTrue(output["passed"])
		self.assertTrue(output["raw_output_hashes_verified"])
		self.assertTrue(output["cleanup"]["resolved"])
		self.assertEqual(state["cleanup_ids"], ["a" * 64])
		request = state["published_request"]
		self.assertNotIn("reference_manifest_path", request)
		self.assertNotIn("reference_manifest_sha256", request)
		self.assertEqual(request["reference_source"]["source_host"], "so007")
		self.assertEqual(request["reference_source"]["runtime_root"], "/mnt")

	def test_missing_p2_reference_is_blocked_without_container_or_publication(self):
		result, plan = self._reference([{
			"suite": "ml", "workload": "logreg",
			"status": {"returncode": 0, "executions": 1},
		}])
		campaign, state = self._campaign({})
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter, "_reference_bytes", return_value=(result, plan)), \
				self._patch_reference_hashes(result, plan), \
				patch.object(compare_adapter.subprocess, "run") as run:
			output = compare_adapter.compare(
				campaign, {"id": "p2", "suite": "p2", "workload": "P2_PREP"},
				"attempt-p2", Path("/stage"), Path("/remote/run"),
				Path(temporary) / "receipt.json", None, "image")
		self.assertFalse(output["receipt"]["passed"])
		self.assertEqual(output["receipt"]["status"], "blocked")
		self.assertIn("absent", output["receipt"]["reason"])
		self.assertIsNone(state["published_request"])
		self.assertEqual(state["cleanup_ids"], [None])
		run.assert_not_called()

	def test_malformed_success_binding_fails_after_exact_cleanup(self):
		result, plan = self._reference([{
			"suite": "ml", "workload": "logreg",
			"status": {"returncode": 0, "executions": 1},
		}])
		receipt = {"passed": True, "metrics": {"artifact_bindings": {"actual": {}}}}
		campaign, state = self._campaign(receipt)
		processes = [SimpleNamespace(returncode=0, stdout="c" * 64, stderr=""),
			SimpleNamespace(returncode=0, stdout=json.dumps(receipt), stderr="")]
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter, "_reference_bytes", return_value=(result, plan)), \
				self._patch_reference_hashes(result, plan), \
				patch.object(compare_adapter.subprocess, "run", side_effect=processes):
			with self.assertRaisesRegex(compare_adapter.RuntimeComparisonError,
					"artifact binding set mismatch"):
				compare_adapter.compare(
					campaign, {"id": "cell", "suite": "ml", "workload": "logreg"},
					"attempt-2", Path("/stage"), Path("/remote/run"),
					Path(temporary) / "receipt.json", None, "image")
		self.assertEqual(state["cleanup_ids"], ["c" * 64])

	def test_reference_pin_is_append_only_and_verified_on_resume(self):
		result, plan = self._reference([{
			"suite": "ml", "workload": "logreg",
			"status": {"returncode": 0, "executions": 1},
		}])
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter, "_fetch_remote_reference", return_value=(result, plan)) as source, \
				self._patch_reference_hashes(result, plan):
			root = Path(temporary).resolve()
			first = compare_adapter.pin_reference(root)
			second = compare_adapter.pin_reference(root)
			self.assertEqual(first, second)
			self.assertEqual(source.call_count, 1)
			self.assertEqual(Path(first["result_manifest"]["local_path"]).read_bytes(), result)
			Path(first["generation_plan"]["local_path"]).write_text("rewritten")
			with self.assertRaisesRegex(compare_adapter.RuntimeComparisonError,
					"generation plan SHA-256 mismatch"):
				compare_adapter.pin_reference(root)

	def test_p2_prepare_rejects_before_any_execution_when_compile_gate_is_closed(self):
		with patch.object(compare_adapter.subprocess, "run") as run, \
				patch.object(compare_adapter, "_fetch_remote_reference") as fetch:
			with self.assertRaisesRegex(compare_adapter.RuntimeComparisonError,
					"compile gate has not passed"):
				compare_adapter.prepare_workload_reference(
					Path("/campaign"), Path("/stage"),
					{"suite": "p2", "workload": "P2_PREP"}, False)
		run.assert_not_called()
		fetch.assert_not_called()

	def test_p2_prepare_pins_ready_singleton_and_resumes_without_generator(self):
		result, plan = self._p2_reference()
		generated = SimpleNamespace(returncode=0,
			stdout=json.dumps({"status": "READY", "ready": True}), stderr="")
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter.subprocess, "run", return_value=generated) as run, \
				patch.object(compare_adapter, "_fetch_remote_reference",
					return_value=(result, plan)) as fetch:
			root = Path(temporary).resolve()
			first = compare_adapter.prepare_workload_reference(
				root, Path("/stage"), {"suite": "p2", "workload": "P2_PREP"}, True)
			second = compare_adapter.prepare_workload_reference(
				root, Path("/stage"), {"suite": "p2", "workload": "P2_PREP"}, True)
		self.assertEqual(first, second)
		self.assertEqual(run.call_count, 1)
		self.assertEqual(fetch.call_count, 1)
		self.assertEqual(first["workload_selector"], "p2:P2_PREP")
		self.assertEqual(first["source_root"], str(compare_adapter.P2_REFERENCE_ROOT))
		self.assertIn("--workload", first["provenance"]["command"])

	def test_p2_prepare_rejects_fatal_marker_and_does_not_publish_pin(self):
		result, plan = self._p2_reference(fatal=True)
		generated = SimpleNamespace(returncode=0,
			stdout=json.dumps({"status": "READY", "ready": True}), stderr="")
		with tempfile.TemporaryDirectory() as temporary, \
				patch.object(compare_adapter.subprocess, "run", return_value=generated), \
				patch.object(compare_adapter, "_fetch_remote_reference",
					return_value=(result, plan)):
			root = Path(temporary).resolve()
			with self.assertRaisesRegex(compare_adapter.RuntimeComparisonError,
					"execution is not clean"):
				compare_adapter.prepare_workload_reference(
					root, Path("/stage"), {"suite": "p2", "workload": "P2_PREP"}, True)
			self.assertFalse((root / "reference-p2ref1").exists())

	def test_compare_uses_workload_scoped_p2_pin_instead_of_cpref5(self):
		result, plan = self._p2_reference()
		generated = SimpleNamespace(returncode=0,
			stdout=json.dumps({"status": "READY", "ready": True}), stderr="")
		sha = "e" * 64
		receipt = {"passed": True, "status": "verified", "correctness": "verified",
			"metrics": {"artifact_bindings": {"actual": {"result": {
				"tree_sha256": sha, "decoded_sha256": sha, "metadata_sha256": sha,
				"campaign_sha256": sha}}}}}
		campaign, state = self._campaign(receipt)
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary).resolve()
			with patch.object(compare_adapter.subprocess, "run", return_value=generated), \
					patch.object(compare_adapter, "_fetch_remote_reference",
						return_value=(result, plan)):
				pin = compare_adapter.prepare_workload_reference(
					root, Path("/stage"), {"suite": "p2", "workload": "P2_PREP"}, True)
			processes = [SimpleNamespace(returncode=0, stdout="f" * 64, stderr=""),
				SimpleNamespace(returncode=0, stdout=json.dumps(receipt), stderr="")]
			with patch.object(compare_adapter.subprocess, "run", side_effect=processes):
				output = compare_adapter.compare(
					campaign, {"id": "p2", "suite": "p2", "workload": "P2_PREP"},
					"attempt-p2-ready", Path("/stage"), Path("/remote/run"),
					root / "comparison.json", Path(pin["result_manifest"]["local_path"]),
					"image")
		self.assertTrue(output["passed"])
		self.assertEqual(state["published_request"]["reference_source"]["source_root"],
			str(compare_adapter.P2_REFERENCE_ROOT))
		self.assertEqual(output["reference"]["result_manifest"]["sha256"],
			hashlib.sha256(result).hexdigest())


if __name__ == "__main__":
	unittest.main()
