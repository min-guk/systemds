#!/usr/bin/env python3
"""Pure mocked contracts for the explicit runtime-without-compile survey lane."""

from __future__ import annotations

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


RUNNER = Path(__file__).resolve().parents[1] / "run_matrix_campaign.py"
SPEC = importlib.util.spec_from_file_location("run_matrix_campaign_direct_runtime", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAMPAIGN)


class DirectRuntimeCliContractTest(unittest.TestCase):
	def test_option_is_runtime_only_and_cannot_be_diagnostic_or_filtered(self):
		invalid = (
			("--phase", "compile"), ("--phase", "all"), ("--phase", "prepare"),
			("--phase", "summary"), ("--phase", "runtime", "--diagnostic-jfr",
				"--max-cells", "1"),
			("--phase", "runtime", "--planner", "FedFirst"),
			("--phase", "runtime", "--profile", "lan"),
			("--phase", "runtime", "--workload", "logreg"),
			("--phase", "runtime", "--workers", "1"),
		)
		for options in invalid:
			errors = io.StringIO()
			with self.subTest(options=options), mock.patch.object(
					CAMPAIGN, "dependencies") as dependencies, redirect_stderr(errors), \
					self.assertRaises(SystemExit):
				CAMPAIGN.main(["--root", "/tmp/not-used", *options,
					"--runtime-without-compile-survey"])
			self.assertNotIn("unrecognized arguments", errors.getvalue())
			dependencies.assert_not_called()

	def test_default_runtime_remains_blocked_by_closed_compile_gate(self):
		with mock.patch.object(CAMPAIGN, "dependencies", return_value=(None, None, None)), \
				mock.patch.object(CAMPAIGN, "initialize", return_value={}), \
				mock.patch.object(CAMPAIGN, "compile_gate", return_value=False):
			with self.assertRaisesRegex(RuntimeError, "compile gate"):
				CAMPAIGN.main(["--root", "/tmp/not-used", "--phase", "runtime"])


class DirectRuntimeManifestContractTest(unittest.TestCase):
	def test_policy_is_frozen_in_identity_and_measurement(self):
		with tempfile.TemporaryDirectory() as directory:
			repo = Path(directory)
			(repo / "src/main").mkdir(parents=True)
			(repo / "scripts/builtin").mkdir(parents=True)
			for name in CAMPAIGN.BUILTIN_SCRIPTS:
				(repo / "scripts/builtin" / name).write_text(name)
			(repo / "pom.xml").write_text("pom")
			probe = repo / "Probe.java"
			probe.write_text("probe")
			jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
			jar.parent.mkdir()
			jar.write_text("jar")
			with mock.patch.object(CAMPAIGN, "REPO", repo), \
					mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
					mock.patch.object(CAMPAIGN, "verify_builtin_sync"), \
					mock.patch.object(CAMPAIGN, "sha", return_value="a" * 64), \
					mock.patch.object(CAMPAIGN, "run", return_value="mocked"):
				root = repo / "direct"
				manifest = CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True)
				self.assertIs(True, manifest["identity"]["direct_runtime"])
				self.assertIs(True, manifest["measurement"]["direct_runtime"])
				self.assertEqual(manifest,
					CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True))
				altered = dict(manifest)
				altered["measurement"] = dict(manifest["measurement"])
				altered["measurement"]["direct_runtime"] = False
				CAMPAIGN.dump(root / "manifest.json", altered)
				with self.assertRaises(RuntimeError):
					CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True)
				CAMPAIGN.dump(root / "manifest.json", manifest)
				with self.assertRaisesRegex(RuntimeError, "campaign identity changed"):
					CAMPAIGN.initialize(root, repo / "stage", direct_runtime=False)


class DirectRuntimeExecutionContractTest(unittest.TestCase):
	def _run(self, results, *, keep_going=True, leases_alive=True,
			max_cells=True, final_runtime_complete=False, previous=None, retry_failed=False):
		cells = CAMPAIGN.schedule("runtime")[:len(results)]
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory).resolve()
			lane = root / "runtime.lock"
			lane.touch()
			campaign = SimpleNamespace(
				acquire_remote_stage_leases=lambda hosts, stage: ["lease"],
				release_remote_stage_leases=lambda leases: {"released": True},
				remote_stage_leases_alive=lambda leases: leases_alive,
				verify_remote_image_content=lambda hosts, image: {h: "ok" for h in hosts},
				verify_remote_bounded_stage=lambda hosts, stage: {h: "ok" for h in hosts},
			)
			base = SimpleNamespace(RUNTIME_LANE=lane)
			renderer = SimpleNamespace(Renderer=lambda stage: object())
			seen = []
			def execute(*args):
				seen.append(args[2]["id"])
				return results[len(seen) - 1]
			status = {"compile_gate": False,
				"compile": {"passed": 0, "failed": 0, "pending": 896},
				"runtime": ({"passed": 896, "failed": 0, "pending": 0}
					if final_runtime_complete else
					{"passed": 0, "failed": 0, "pending": 896})}
			initialized = []
			def initialize(root_arg, stage, diagnostic_jfr=False,
					diagnostic_compact=False, direct_runtime=False, **kwargs):
				initialized.append(direct_runtime)
				return {}
			with mock.patch.object(CAMPAIGN, "dependencies",
					return_value=(campaign, base, renderer)), \
					mock.patch.object(CAMPAIGN, "initialize", side_effect=initialize), \
					mock.patch.object(CAMPAIGN, "compile_gate", return_value=False), \
					mock.patch.object(CAMPAIGN, "publish_overlay", return_value="ok"), \
					mock.patch.object(CAMPAIGN, "latest", return_value=previous or {}), \
					mock.patch.object(CAMPAIGN, "schedule", return_value=cells), \
					mock.patch.object(CAMPAIGN, "execute_cell", side_effect=execute), \
					mock.patch.object(CAMPAIGN.runtime_compare, "pin_reference",
						return_value={"result_manifest": {"local_path": "/tmp/reference"}}), \
					mock.patch.object(CAMPAIGN, "summarize", return_value=status):
				argv = ["--root", str(root), "--phase", "runtime",
					"--runtime-without-compile-survey"]
				if max_cells:
					argv += ["--max-cells", str(len(results))]
				if keep_going:
					argv.append("--keep-going")
				if retry_failed:
					argv.append("--retry-failed")
				outcome = CAMPAIGN.main(argv)
			self.assertEqual([True], initialized)
			return outcome, seen

	def test_explicit_direct_runtime_runs_while_evidence_gate_remains_false(self):
		outcome, seen = self._run([{"status": "passed", "cleanup_resolved": True}])
		self.assertEqual(0, outcome)
		self.assertEqual(1, len(seen))

	def test_keep_going_continues_only_after_a_cleanly_resolved_failure(self):
		outcome, seen = self._run([
			{"status": "failed", "cleanup_resolved": True},
			{"status": "passed", "cleanup_resolved": True},
		])
		self.assertEqual(1, outcome,
			"an attempted failure cannot be hidden by the last cell")
		self.assertEqual(2, len(seen))

	def test_explicit_retry_reruns_inherited_timeout_instead_of_skipping_it(self):
		cell = CAMPAIGN.schedule("runtime")[0]
		previous = {cell["id"]: {"status": "failed", "returncode": 124,
			"timeout_seconds": 60, "cleanup_resolved": True}}
		outcome, seen = self._run([{"status": "passed", "cleanup_resolved": True}],
			previous=previous, retry_failed=True)
		self.assertEqual(0, outcome)
		self.assertEqual([cell["id"]], seen)
		self.assertEqual(60, previous[cell["id"]]["timeout_seconds"])

	def test_unresolved_cleanup_stops_even_with_keep_going(self):
		for cleanup in (False, None):
			with self.subTest(cleanup=cleanup), \
					self.assertRaisesRegex(RuntimeError, "cleanup unresolved"):
				first = {"status": "failed"}
				if cleanup is not None:
					first["cleanup_resolved"] = cleanup
				self._run([first, {"status": "passed", "cleanup_resolved": True}])

	def test_lost_lease_stops_before_starting_any_cell(self):
		with self.assertRaisesRegex(RuntimeError, "lease lost"):
			self._run([{"status": "passed", "cleanup_resolved": True}], leases_alive=False)

	def test_resume_never_skips_prior_evidence_with_unresolved_cleanup(self):
		cell = CAMPAIGN.schedule("runtime")[0]
		for cleanup in (False, None):
			with self.subTest(cleanup=cleanup), \
					self.assertRaisesRegex(RuntimeError, "cleanup unresolved"):
				old = {"status": "passed"}
				if cleanup is not None:
					old["cleanup_resolved"] = cleanup
				self._run([{"status": "passed", "cleanup_resolved": True}],
					previous={cell["id"]: old})

	def test_complete_direct_runtime_exits_success_with_factual_closed_compile_gate(self):
		outcome, seen = self._run(
			[{"status": "passed", "cleanup_resolved": True}],
			max_cells=False, final_runtime_complete=True)
		self.assertEqual(0, outcome)
		self.assertEqual(1, len(seen))


class DirectRuntimeP2ContractTest(unittest.TestCase):
	def test_p2_direct_mode_bypasses_gate_without_fabricating_gate_success(self):
		failed = SimpleNamespace(returncode=1, stdout="", stderr="expected mocked failure")
		with mock.patch.object(CAMPAIGN.runtime_compare.subprocess, "run",
				return_value=failed) as run:
			with self.assertRaisesRegex(CAMPAIGN.runtime_compare.RuntimeComparisonError,
					"reference generation failed"):
				CAMPAIGN.runtime_compare.prepare_workload_reference(
					Path("/campaign"), Path("/stage"),
					{"suite": "p2", "workload": "P2_PREP"}, False,
					direct_runtime=True)
		run.assert_called_once()

	def test_p2_direct_mode_remains_p2_prep_only(self):
		with mock.patch.object(CAMPAIGN.runtime_compare.subprocess, "run") as run:
			with self.assertRaisesRegex(CAMPAIGN.runtime_compare.RuntimeComparisonError,
					"P2_PREP-only"):
				CAMPAIGN.runtime_compare.prepare_workload_reference(
					Path("/campaign"), Path("/stage"),
					{"suite": "glm", "workload": "glm"}, False,
					direct_runtime=True)
		run.assert_not_called()


if __name__ == "__main__":
	unittest.main()
