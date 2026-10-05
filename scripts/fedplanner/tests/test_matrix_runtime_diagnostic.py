#!/usr/bin/env python3
"""Pure contracts for a single diagnostic-only runtime cell."""

import csv
import importlib.util
import io
import json
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock


RUNNER = Path(__file__).resolve().parents[1] / "run_matrix_campaign.py"
SPEC = importlib.util.spec_from_file_location("matrix_runtime_diagnostic", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAMPAIGN)


def diagnostic_argv(root):
	return ["--root", str(root), "--phase", "runtime",
		"--runtime-without-compile-survey", "--diagnostic-runtime-cell",
		"--max-cells", "1", "--workload", "steplm", "--planner", "DP-local",
		"--workers", "1", "--profile", "wan_mid"]


class RuntimeDiagnosticCliTest(unittest.TestCase):
	def test_rejects_missing_contract_parts_and_incompatible_modes_before_dependencies(self):
		valid = diagnostic_argv("/tmp/not-used")
		invalid = (
			[x for x in valid if x != "--runtime-without-compile-survey"],
			[x for x in valid if x not in ("--planner", "DP-local")],
			[*valid, "--continue-runtime-from", "/tmp/old"],
			[*valid, "--diagnostic-jfr"],
			[*valid, "--diagnostic-compact"],
			["--root", "/tmp/not-used", "--diagnostic-plan-details"],
		)
		for argv in invalid:
			with self.subTest(argv=argv), mock.patch.object(CAMPAIGN, "dependencies") as deps, \
					redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
				CAMPAIGN.main(argv)
			deps.assert_not_called()

	def test_valid_contract_runs_exact_selected_cell_without_compile_gate(self):
		cell = next(c for c in CAMPAIGN.matrix() if c["workload"] == "steplm"
			and c["planner"] == "DP-local" and c["workers"] == 1 and c["profile"] == "wan_mid")
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			lane = root / "lane"
			lane.touch()
			campaign = SimpleNamespace(
				acquire_remote_stage_leases=lambda *args: ["lease"],
				release_remote_stage_leases=lambda leases: {"released": True},
				remote_stage_leases_alive=lambda leases: True,
				verify_remote_image_content=lambda hosts, image: {},
				verify_remote_bounded_stage=lambda hosts, stage: {})
			base = SimpleNamespace(RUNTIME_LANE=lane)
			renderer = SimpleNamespace(Renderer=lambda stage: object())
			seen = []
			with mock.patch.object(CAMPAIGN, "dependencies", return_value=(campaign, base, renderer)), \
					mock.patch.object(CAMPAIGN, "initialize", return_value={}) as initialize, \
					mock.patch.object(CAMPAIGN, "publish_overlay", return_value="ok"), \
					mock.patch.object(CAMPAIGN, "latest", return_value={}), \
					mock.patch.object(CAMPAIGN, "schedule", return_value=[cell]), \
					mock.patch.object(CAMPAIGN.runtime_compare, "pin_reference", return_value={
						"result_manifest": {"local_path": "/tmp/reference"}}), \
					mock.patch.object(CAMPAIGN, "execute_cell", side_effect=lambda *args: (
						seen.append(args[2]) or {"status": "passed", "cleanup_resolved": True})), \
					mock.patch.object(CAMPAIGN, "summarize", return_value={}):
				self.assertEqual(0, CAMPAIGN.main([
					*diagnostic_argv(root), "--diagnostic-plan-details"]))
			self.assertEqual([cell], seen)
			self.assertTrue(initialize.call_args.kwargs["diagnostic_runtime_cell"])
			self.assertTrue(initialize.call_args.kwargs["diagnostic_plan_details"])

	def test_optional_detailed_trace_is_coordinator_only_and_pinned(self):
		cell = CAMPAIGN.matrix()[0]
		java = CAMPAIGN.coordinator_java(cell, "runtime", diagnostic_plan_details=True)
		self.assertIn("-Dsysds.fedplanner.trace=true", java)
		self.assertIn("-Dsysds.fedplanner.trace.details=true", java)
		self.assertNotIn("-Dsysds.fedplanner.trace.details=false", java)
		self.assertNotIn("-Dsysds.fedplanner.trace.details=true", CAMPAIGN.JAVA)
		contract = CAMPAIGN.diagnostic_contract(False, runtime_cell=True, plan_details=True)
		self.assertTrue(contract["diagnostic_plan_details"])
		self.assertEqual(list(CAMPAIGN.DIAGNOSTIC_DETAIL_TRACE_OPTIONS),
			contract["diagnostic_planner_trace_options"])


class RuntimeDiagnosticEvidenceTest(unittest.TestCase):
	def test_identity_measurement_and_resume_are_immutable(self):
		with tempfile.TemporaryDirectory() as directory:
			repo = Path(directory)
			(repo / "src/main").mkdir(parents=True)
			(repo / "pom.xml").write_text("pom")
			probe = repo / "Probe.java"
			probe.write_text("probe")
			jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
			jar.parent.mkdir()
			jar.write_text("jar")
			root = repo / "run"
			with mock.patch.object(CAMPAIGN, "REPO", repo), \
					mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
					mock.patch.object(CAMPAIGN, "sha", return_value="a" * 64), \
					mock.patch.object(CAMPAIGN, "run", return_value="mocked"):
				manifest = CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True,
					diagnostic_runtime_cell=True)
				for section in (manifest["identity"]["diagnostic"], manifest["measurement"]):
					self.assertIs(True, section["diagnostic_runtime_cell"])
				altered = json.loads(json.dumps(manifest))
				altered["measurement"]["diagnostic_runtime_cell"] = False
				CAMPAIGN.dump(root / "manifest.json", altered)
				with self.assertRaisesRegex(RuntimeError, "diagnostic measurement changed"):
					CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True,
						diagnostic_runtime_cell=True)

	def test_result_is_marked_unlimited_and_timings_are_not_exported(self):
		cell = CAMPAIGN.matrix()[0]
		args = SimpleNamespace(diagnostic_jfr=False, diagnostic_compact=False,
			diagnostic_runtime_cell=True, stage=Path("/stage"))
		root = Path(tempfile.mkdtemp())
		node = SimpleNamespace(host="so007")
		spec = SimpleNamespace(coordinator=node, workers=[], container_name=lambda n: "container")
		base = SimpleNamespace(parse_manifest=lambda life: spec,
			prepare_remote_directories=lambda *args: None, build_plan=lambda *args: None,
			capture_network_snapshot=lambda *args: {}, validate_network_quality=lambda *args: {"valid": True})
		campaign = SimpleNamespace(
			bounded_pilot_lifecycle=lambda *args, **kwargs: {"mounts": [], "coordinator": {"environment": {}}, "workers": []},
			remote_resource_preflight=lambda *args: {"passed": False},
			_strict_experiment_cleanup=lambda *args: {"resolved": True})
		renderer = SimpleNamespace(render=lambda cell: {"source": "print(1);"})
		with mock.patch.object(CAMPAIGN.lifecycle, "prepare_nodes", side_effect=lambda nodes, fn: [fn(n) for n in nodes]), \
				mock.patch.object(CAMPAIGN, "ssh", return_value=""), mock.patch("sys.stdout", new=io.StringIO()):
			result = CAMPAIGN.execute_cell(root, {"remote_root": "/remote",
				"identity": {"jar_sha256": "a" * 64}}, cell, "runtime", args,
				campaign, base, renderer)
		self.assertTrue(result["diagnostic_only"])
		self.assertTrue(result["diagnostic_runtime_cell"])
		self.assertIsNone(result["timeout_seconds"])

		result.update({"status": "passed", "attempt": "diagnostic", "compile_seconds": 3.0,
			"runtime_seconds": 100.0, "searchspace_seconds": 2.0})
		with mock.patch.object(CAMPAIGN, "latest", side_effect=({}, {cell["id"]: result})), \
				mock.patch.object(CAMPAIGN, "schedule", return_value=[cell]), \
				mock.patch.object(CAMPAIGN, "compile_gate", return_value=False):
			CAMPAIGN.summarize(root)
		with (root / "runtime-comparison.csv").open() as stream:
			exported = next(csv.DictReader(stream))
		self.assertEqual("pending", exported["status"])
		self.assertEqual("", exported["diagnostic_only"])
		self.assertEqual("", exported["compile_seconds"])
		self.assertEqual("", exported["runtime_seconds"])
		self.assertEqual({"passed": 0, "failed": 0, "pending": 896},
			json.loads((root / "summary.json").read_text())["runtime"])


if __name__ == "__main__":
	unittest.main()
