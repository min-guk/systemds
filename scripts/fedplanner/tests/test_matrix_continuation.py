import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "matrix_continuation.py"
SPEC = importlib.util.spec_from_file_location("matrix_continuation", MODULE_PATH)
CONT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONT)


def write_json(path, value):
	path.parent.mkdir(parents=True, exist_ok=True)
	path.write_text(json.dumps(value, sort_keys=True) + "\n")


RUNNER = '''
IMAGE = "image"
PLANNERS = ("p",)
PROFILES = ("lan",)
WORKERS = (1,)
WORKLOAD_TIMEOUT_SECONDS = 60
WORKLOADS = (("ml", "w"),)
ENUMS = dict(zip(PLANNERS, ("ENUM",)))
PROBE = "Probe"
JAVA = ("java", "-Xmx1g")
CP = "cp"
def config(cell, phase):
    return (cell, phase)
def coordinator_java(cell, phase, diagnostic_jfr=False, diagnostic_compact=False):
    return JAVA + (CP, PROBE, cell["planner_enum"], phase)
'''

INTEGRATED = '''
def stable():
    return 1
def comparator_container_plan(cell, attempt, stage, run_root, reference_manifest, image):
    metric_root = run_root / "metrics"
    decoder_smoke = "old"
    create = ["docker", image, "sh", "-c", decoder_smoke]
    return {"create_argv": create, "metric_root": str(metric_root)}
'''

INTEGRATED_REVIEWED = INTEGRATED.replace(
	'decoder_smoke = "old"',
	'formats = {output.get("format") for output in cell.get("outputs", [])}\n'
	'    decoder_smoke = "fast" if formats == {"csv"} else "old"')


class ContinuationFixture:
	def __init__(self, root):
		self.root = root
		self.source = root / "source"
		self.current_runner = root / "current-runner.py"
		self.current_integrated = root / "current/run_w1357_integrated.py"
		self.old_runner = self.source / "frozen-harness/run_matrix_campaign.py"
		self.old_integrated = self.source / "frozen-harness/run_w1357_integrated.py"
		for path, text in ((self.old_runner, RUNNER), (self.current_runner, RUNNER + "\n# reviewed\n"),
				(self.old_integrated, INTEGRATED), (self.current_integrated, INTEGRATED_REVIEWED)):
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_text(text)
		self.cell = {"id": "ml|w|lan|w1|p", "suite": "ml", "workload": "w",
			"profile": "lan", "workers": 1, "planner": "p", "planner_enum": "ENUM"}
		self.identity = {
			"jar_sha256": "jar", "source_sha256": {"x": "source"},
			"probe_source_sha256": "probe", "runner_sha256": self.sha(self.old_runner),
			"runtime_compare_sha256": "compare", "lifecycle_sha256": "life",
			"external_sha256": {str(self.old_integrated): self.sha(self.old_integrated), "/x/other": "same"},
			"stage": "/stage", "stage_seal_sha256": "stage", "timeout_seconds": {"compile": 60, "runtime": 60},
			"direct_runtime": True, "diagnostic": {"diagnostic_jfr": False}}
		self.measurement = {"samples_per_cell": 1, "fresh_jvm": True, "parallel_setup_only": True,
			"timeout_seconds": {"compile": 60, "runtime": 60}}
		self.source_manifest = {"identity": self.identity, "image": "image", "cells": [self.cell],
			"measurement": self.measurement}
		write_json(self.source / "manifest.json", self.source_manifest)
		self.current_manifest = json.loads(json.dumps(self.source_manifest))
		self.current_manifest["identity"]["runner_sha256"] = self.sha(self.current_runner)
		self.current_manifest["identity"]["external_sha256"] = {
			str(self.current_integrated): self.sha(self.current_integrated), "/x/other": "same"}
		self.current_manifest["identity"]["continuation_module_sha256"] = self.sha(MODULE_PATH)
		self.current_manifest["measurement"]["parallel_setup_only"] = False
		self.current_manifest["measurement"]["parallel_untimed_only"] = True
		write_json(self.source / "launch.json", {"pid": 999999999, "process_start_ticks": "1"})
		(self.source / "finished-at.txt").write_text("done\n")
		(self.source / "exit-code.txt").write_text("130\n")
		write_json(self.source / "lease-release-2.json", {"released": True})

	@staticmethod
	def sha(path):
		return hashlib.sha256(path.read_bytes()).hexdigest()

	def add_result(self, status="passed", errors=None, receipt_complete=True):
		attempt = self.source / "attempts/runtime/001"
		receipt = {"status": "success", "workloadExecutionCompleted": receipt_complete}
		comparison_receipt = {"passed": True, "status": "verified"}
		comparison = {"passed": True, "raw_output_hashes_verified": True,
			"cleanup": {"resolved": True}, "receipt": comparison_receipt}
		row = {"schema": "w1357-matrix-attempt/v1", "cell": self.cell, "attempt": "001",
			"phase": "runtime", "status": status, "errors": [] if errors is None else errors,
			"timeout_seconds": 60, "jar_sha256": "jar", "cleanup_resolved": True,
			"receipt": receipt if receipt_complete else None, "candidate_phases_ns": {"xNanos": 1},
			"compile_seconds": 1.0}
		if status == "passed":
			row["comparison"] = comparison
		write_json(attempt / "result.json", row)
		write_json(attempt / "cleanup.json", {"resolved": True})
		if receipt_complete:
			write_json(attempt / "receipt.json", receipt)
		if status == "passed":
			write_json(attempt / "comparison.json", comparison_receipt)
			comparison["sha256"] = self.sha(attempt / "comparison.json")
			write_json(attempt / "result.json", row)
		return attempt, row

	def add_prestart_resource_failure(self):
		attempt = self.source / "attempts/runtime/001"
		row = {"schema": "w1357-matrix-attempt/v1", "cell": self.cell, "attempt": "001",
			"phase": "runtime", "status": "failed", "errors": ["host resource preflight failed"],
			"timeout_seconds": 60, "jar_sha256": "jar"}
		write_json(attempt / "result.json", row)
		write_json(attempt / "resource-preflight.json", {"passed": False,
			"hosts": {"worker": {"passed": False}}})
		write_json(attempt / "lifecycle.json", {"campaign_id": "campaign", "workers": []})
		write_json(attempt / "post-stop-cleanup.json", {"resolved": True, "evidence": ["absent"]})
		return attempt, row


class MatrixContinuationTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.fx = ContinuationFixture(Path(self.temp.name))

	def tearDown(self):
		self.temp.cleanup()

	@staticmethod
	def validate(receipt, cell, phase, timing):
		if not receipt["workloadExecutionCompleted"] or phase != "runtime" or timing["compile_seconds"] != 1.0:
			raise ValueError("invalid")

	def test_load_without_continuation_is_empty(self):
		self.assertEqual({}, CONT.load_rows(Path(self.temp.name) / "unused"))

	def test_load_rejects_missing_manifest_pinned_continuation(self):
		root = Path(self.temp.name) / "target"
		root.mkdir()
		manifest = json.loads(json.dumps(self.fx.current_manifest))
		manifest["identity"]["continuation_sha256"] = "0" * 64
		write_json(root / "manifest.json", manifest)
		with self.assertRaisesRegex(ValueError, "missing"):
			CONT.load_rows(root)

	def test_builds_and_loads_origin_annotated_passed_row(self):
		self.fx.add_result()
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		root = Path(self.temp.name) / "target"
		root.mkdir()
		write_json(root / "continuation.json", snapshot)
		manifest = json.loads(json.dumps(self.fx.current_manifest))
		manifest["identity"]["continuation_sha256"] = self.fx.sha(root / "continuation.json")
		write_json(root / "manifest.json", manifest)
		rows = CONT.load_rows(root)
		self.assertEqual("passed", rows[self.fx.cell["id"]]["status"])
		self.assertEqual(str(self.fx.source), rows[self.fx.cell["id"]]["continuation_origin"]["root"])

	def test_rejects_live_matching_wrapper(self):
		self.fx.add_result()
		write_json(self.fx.source / "launch.json", {"pid": os.getpid(),
			"process_start_ticks": CONT._process_start_ticks(os.getpid())})
		with self.assertRaisesRegex(ValueError, "still alive"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_missing_terminal_or_lease_proof(self):
		self.fx.add_result()
		(self.fx.source / "finished-at.txt").unlink()
		with self.assertRaisesRegex(ValueError, "terminal"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_unresolved_cleanup(self):
		attempt, _ = self.fx.add_result(status="failed", errors=["timeout"])
		write_json(attempt / "cleanup.json", {"resolved": False})
		with self.assertRaisesRegex(ValueError, "cleanup"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_excludes_one_latest_interrupted_attempt(self):
		attempt, _ = self.fx.add_result(status="failed", errors=[], receipt_complete=False)
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		self.assertEqual([], snapshot["rows"])
		self.assertEqual(str(attempt.relative_to(self.fx.source)), snapshot["excluded"][0]["path"])

	def test_excludes_latest_prestart_resource_failure_with_post_stop_cleanup(self):
		attempt, _ = self.fx.add_prestart_resource_failure()
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		self.assertEqual([], snapshot["rows"])
		exclusion = snapshot["excluded"][0]
		self.assertEqual("pre-start resource failure; post-stop cleanup resolved", exclusion["reason"])
		self.assertEqual({"result.json", "resource-preflight.json", "lifecycle.json",
			"post-stop-cleanup.json"}, {Path(item["path"]).name for item in exclusion["artifacts"]})

	def test_rejects_prestart_failure_without_explicit_post_stop_cleanup(self):
		attempt, _ = self.fx.add_prestart_resource_failure()
		(attempt / "post-stop-cleanup.json").unlink()
		with self.assertRaisesRegex(ValueError, "cleanup"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_prestart_exclusion_when_command_was_created(self):
		attempt, _ = self.fx.add_prestart_resource_failure()
		write_json(attempt / "command.json", {"argv": ["java"]})
		with self.assertRaisesRegex(ValueError, "cleanup"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_passed_none_comparison_is_explicit_validation_error(self):
		attempt, _ = self.fx.add_result()
		row = json.loads((attempt / "result.json").read_text())
		row["comparison"] = None
		write_json(attempt / "result.json", row)
		(attempt / "comparison.json").unlink()
		with self.assertRaisesRegex(ValueError, "trusted comparison"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_unexplained_resultless_attempt(self):
		self.fx.add_result()
		(self.fx.source / "attempts/runtime/002").mkdir(parents=True)
		with self.assertRaisesRegex(ValueError, "result-less"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_contract_or_unreviewed_integrated_change(self):
		self.fx.add_result()
		self.fx.current_runner.write_text(RUNNER.replace('CP = "cp"', 'CP = "other"'))
		self.fx.current_manifest["identity"]["runner_sha256"] = self.fx.sha(self.fx.current_runner)
		with self.assertRaisesRegex(ValueError, "timed runner contract"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_rejects_chained_source_without_frozen_module_pin(self):
		self.fx.add_result()
		manifest = json.loads((self.fx.source / "manifest.json").read_text())
		manifest["identity"]["continuation_sha256"] = "0" * 64
		write_json(self.fx.source / "manifest.json", manifest)
		with self.assertRaisesRegex(ValueError, "module pin"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_accepts_only_reviewed_timeout_removal(self):
		self.fx.add_result()
		source = json.loads((self.fx.source / "manifest.json").read_text())
		source["measurement"]["timeout_semantics"] = "unresolved failure, not infeasibility"
		write_json(self.fx.source / "manifest.json", source)
		self.fx.current_runner.write_text(RUNNER.replace(
			"WORKLOAD_TIMEOUT_SECONDS = 60", "WORKLOAD_TIMEOUT_SECONDS = None"))
		self.fx.current_manifest["identity"]["runner_sha256"] = self.fx.sha(self.fx.current_runner)
		self.fx.current_manifest["identity"]["timeout_seconds"] = {"compile": None, "runtime": None}
		self.fx.current_manifest["measurement"]["timeout_seconds"] = {"compile": None, "runtime": None}
		self.fx.current_manifest["measurement"]["timeout_semantics"] = "no workload deadline"
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		self.assertEqual(60, snapshot["rows"][0]["row"]["timeout_seconds"])

	def test_rejects_arbitrary_timeout_change(self):
		self.fx.add_result()
		self.fx.current_runner.write_text(RUNNER.replace(
			"WORKLOAD_TIMEOUT_SECONDS = 60", "WORKLOAD_TIMEOUT_SECONDS = 30"))
		self.fx.current_manifest["identity"]["runner_sha256"] = self.fx.sha(self.fx.current_runner)
		self.fx.current_manifest["identity"]["timeout_seconds"] = {"compile": 30, "runtime": 30}
		self.fx.current_manifest["measurement"]["timeout_seconds"] = {"compile": 30, "runtime": 30}
		with self.assertRaisesRegex(ValueError, "timed runner contract"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
				self.fx.current_runner, self.validate)

	def test_unlimited_attempt_requires_explicit_null_timeout_policy(self):
		attempt, _ = self.fx.add_result(status="failed", errors=["failure"], receipt_complete=False)
		manifest = json.loads((self.fx.source / "manifest.json").read_text())
		manifest["identity"]["timeout_seconds"] = {"compile": None, "runtime": None}
		write_json(self.fx.source / "manifest.json", manifest)
		row = json.loads((attempt / "result.json").read_text())
		del row["timeout_seconds"]
		write_json(attempt / "result.json", row)
		with self.assertRaisesRegex(ValueError, "timeout"):
			CONT._validate_attempt(self.fx.source, attempt, manifest, self.validate)

	def test_rejects_unreviewed_integrated_function_change(self):
		self.fx.add_result()
		self.fx.current_integrated.write_text(INTEGRATED_REVIEWED.replace("return 1", "return 2"))
		self.fx.current_manifest["identity"]["external_sha256"][str(self.fx.current_integrated)] = \
			self.fx.sha(self.fx.current_integrated)
		with self.assertRaisesRegex(ValueError, "outside comparator"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest, self.fx.current_runner, self.validate)

	def test_imports_failed_attempt_with_error_evidence(self):
		self.fx.add_result(status="failed", errors=["timeout"], receipt_complete=False)
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		self.assertEqual("failed", snapshot["rows"][0]["row"]["status"])

	def test_rejects_diagnostic_runtime_source_and_rows(self):
		self.fx.add_result()
		source = json.loads((self.fx.source / "manifest.json").read_text())
		source["identity"]["diagnostic"]["diagnostic_runtime_cell"] = True
		write_json(self.fx.source / "manifest.json", source)
		with self.assertRaisesRegex(ValueError, "diagnostic runtime campaign"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
				self.fx.current_runner, self.validate)

		del source["identity"]["diagnostic"]["diagnostic_runtime_cell"]
		write_json(self.fx.source / "manifest.json", source)
		attempt = self.fx.source / "attempts/runtime/001"
		row = json.loads((attempt / "result.json").read_text())
		row["diagnostic_only"] = True
		write_json(attempt / "result.json", row)
		with self.assertRaisesRegex(ValueError, "diagnostic attempt"):
			CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
				self.fx.current_runner, self.validate)

	def test_load_rejects_mutated_source_result(self):
		attempt, _ = self.fx.add_result()
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		root = Path(self.temp.name) / "target"
		root.mkdir()
		write_json(root / "continuation.json", snapshot)
		manifest = json.loads(json.dumps(self.fx.current_manifest))
		manifest["identity"]["continuation_sha256"] = self.fx.sha(root / "continuation.json")
		write_json(root / "manifest.json", manifest)
		(attempt / "result.json").write_text("{}\n")
		with self.assertRaisesRegex(ValueError, "mutated"):
			CONT.load_rows(root, manifest)

	def test_load_rejects_mutated_excluded_evidence(self):
		attempt, _ = self.fx.add_prestart_resource_failure()
		snapshot = CONT.build_snapshot(self.fx.source, self.fx.current_manifest,
			self.fx.current_runner, self.validate)
		root = Path(self.temp.name) / "target"
		root.mkdir()
		write_json(root / "continuation.json", snapshot)
		manifest = json.loads(json.dumps(self.fx.current_manifest))
		manifest["identity"]["continuation_sha256"] = self.fx.sha(root / "continuation.json")
		write_json(root / "manifest.json", manifest)
		write_json(attempt / "resource-preflight.json", {"passed": True})
		with self.assertRaisesRegex(ValueError, "mutated"):
			CONT.load_rows(root, manifest)


if __name__ == "__main__":
	unittest.main()
