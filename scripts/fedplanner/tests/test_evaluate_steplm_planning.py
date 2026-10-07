import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "evaluate_steplm_planning.py"
SPEC = importlib.util.spec_from_file_location("evaluate_steplm_planning", MODULE_PATH)
runner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(runner)


class StepLMPlanningEvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.environment = {
            "image": "sha256:image", "pull": "never", "network": "none",
            "cpus": "4", "memory": "8g", "entrypoint": "bash",
            "manifestNetwork": "none (worker and coordinator use container loopback)",
            "planner": "local", "configuredPlanner": "compile_cost_based",
            "noFedRuntimeConversion": True, "caseTimeoutSeconds": 600,
            "jfrEnabled": False,
        }
        self.workload = {
            "inputManifestPresent": True,
            "selectedInputSha256": None,
            "caseFixtureSha256": {"cp": "cp-fixture", "fed": "fed-fixture"},
            "configFixtureSha256": {"sha256": "config-fixture"},
            "dependenciesSha256": "d" * 64,
        }
        self.jvm_resources = {
            "workers": [{"heap": "-Xmx1g", "port": str(port)}
                        for port in (13000, 13001, 13002)],
            "caseProcesses": [
                {"heap": "-Xmx3g", "activeProcessors": "4", "mode": mode}
                for mode in ("cp", "fed")],
        }
        self.contract = {"environment": self.environment, "workload": self.workload,
                         "jvmResources": self.jvm_resources}

    def tearDown(self):
        self.temp.cleanup()

    def fixture(self, name="run", seconds=19.5, objective=37040.115993804146,
                selection=None):
        run = self.root / name
        run.mkdir()
        docker = ["docker", "run", "--pull", "never", "--network", "none",
                  "--cpus", "4", "--memory", "8g", "--entrypoint", "bash",
                  "sha256:image"]
        digests = {key: key[0] * 64 for key in
                   ("mainClasses", "testClasses", "mainSources", "testSources", "dependencies")}
        manifest = {
            "dockerArgv": docker, "image": "sha256:image",
            "network": "none (worker and coordinator use container loopback)",
            "planner": "local", "configuredPlanner": "compile_cost_based",
            "noFedRuntimeConversion": True, "caseTimeoutSeconds": 600,
            "jfrProfile": {"enabled": False}, "requestedCases": [runner.CASE],
            "artifactInventoryDigests": digests,
            "sourceSha256": {"runner": "a" * 64, "dispatch": "b" * 64},
            "inputSha256": {},
            "fixtureSha256": {
                runner.CASE: self.workload["caseFixtureSha256"],
                "config": self.workload["configFixtureSha256"],
            },
            "container": f"container-{name}", "run": str(run.resolve()),
        }
        overlay = {"/engine/classes/A.class": {
            "matched": True, "actualSha256": "c" * 64}}
        chosen = selection if selection is not None else runner.EXPECTED_SELECTION
        case = {
            "case": runner.CASE, "passed": True,
            "cpReturncode": 0, "fedReturncode": 0,
            "auditErrors": [], "runtimeAuditViolations": [], "auditRows": 7,
            "auditSchemas": sorted(runner.REQUIRED_AUDIT_SCHEMAS),
            "modelComparison": {"matched": True, "finite": True, "nonzero": True,
                                "entries": 5, "actualEntries": 5, "actualShape": [5, 1],
                                "shapeMatched": True, "maxAbsDifference": 0, "errors": []},
            "selectionComparison": {"matched": True, "integral": True,
                                    "actual": chosen, "reference": chosen},
            "plannerTraceRequired": True, "plannerTraceComplete": True,
            "fedStatistics": {"compilationSeconds": seconds, "executionSeconds": 0.1},
            "plannerCheckpointSummary": {"final": {"phase": "RESOURCE", "upper": objective}},
        }
        result = {
            "status": "PASSED", "containerReturncode": 0,
            "requestedCases": [runner.CASE], "classPreflightPassed": True,
            "overlayPreflightPassed": True, "modelProofPassed": True,
            "runtimeConversionViolations": [],
            "overlayPreflight": {"status": "PASSED", "actual": overlay},
            "cases": [case],
        }
        (run / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        (run / "container-run.sh").write_text(
            "\n".join([
                *(f'java -Xmx1g -cp "$CP" Main -w {port} -config /evidence/config.xml &'
                  for port in (13000, 13001, 13002)),
                *(f'timeout 600 java -Xmx3g -XX:ActiveProcessorCount=4 -cp "$CP" Main '
                  f'-f /evidence/cases/{runner.CASE}/{mode}.dml'
                  for mode in ("cp", "fed")),
            ]) + "\n", encoding="utf-8")
        result_path = run / "result.json"
        result_path.write_text(json.dumps(result), encoding="utf-8")
        return result_path

    def test_accepts_complete_run_at_contract_boundaries(self):
        evaluated = runner.evaluate_result(self.fixture(seconds=20.0), self.contract)
        self.assertTrue(evaluated["passed"], evaluated["errors"])
        self.assertEqual(20.0, evaluated["compilationSeconds"])
        self.assertEqual(runner.EXPECTED_SELECTION, evaluated["selection"])

    def test_rejects_slow_run_and_worse_objective(self):
        evaluated = runner.evaluate_result(
            self.fixture(seconds=20.01, objective=runner.MAX_OBJECTIVE + 0.01),
            self.contract)
        self.assertFalse(evaluated["passed"])
        self.assertTrue(any("compilationSeconds" in error for error in evaluated["errors"]))
        self.assertTrue(any("objective" in error for error in evaluated["errors"]))

    def test_rejects_missing_fields_without_substituting_defaults(self):
        path = self.fixture()
        result = json.loads(path.read_text(encoding="utf-8"))
        case = result["cases"][0]
        del case["fedStatistics"]["compilationSeconds"]
        del case["plannerCheckpointSummary"]["final"]["upper"]
        del case["modelComparison"]["actualEntries"]
        path.write_text(json.dumps(result), encoding="utf-8")
        evaluated = runner.evaluate_result(path, self.contract)
        self.assertIsNone(evaluated["compilationSeconds"])
        self.assertIsNone(evaluated["selectedObjective"])
        self.assertGreaterEqual(len(evaluated["errors"]), 3)

    def test_missing_result_is_reported_with_visible_null_measurements(self):
        evaluated = runner.evaluate_result(self.root / "missing/result.json", self.contract)
        self.assertFalse(evaluated["passed"])
        self.assertIsNone(evaluated["compilationSeconds"])
        self.assertIsNone(evaluated["selectedObjective"])
        self.assertTrue(any("missing result" in error for error in evaluated["errors"]))

    def test_rejects_environment_or_selection_change(self):
        path = self.fixture(selection=[3, 5, 1])
        manifest_path = path.with_name("manifest.json")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["dockerArgv"][manifest["dockerArgv"].index("--cpus") + 1] = "8"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        evaluated = runner.evaluate_result(path, self.contract)
        self.assertFalse(evaluated["passed"])
        self.assertTrue(any("environment mismatch" in error for error in evaluated["errors"]))
        self.assertTrue(any("selection" in error for error in evaluated["errors"]))

    def test_command_uses_only_official_local_matrix_lane(self):
        command = runner.harness_command(Path("/out"), Path("/stage"), "trial-01")
        self.assertEqual(str(runner.DISPATCH), command[0])
        self.assertEqual(1, command.count("--joint-boundary-e2e"))
        self.assertEqual(runner.CASE, command[command.index("--case") + 1])
        self.assertEqual("600", command[command.index("--case-timeout-seconds") + 1])
        self.assertNotIn("ml_steplm", command)
        self.assertNotIn("csv", " ".join(command).lower())

    def test_report_requires_exactly_three_consistent_runs(self):
        paths = [self.fixture(f"run-{index}") for index in range(3)]
        args = runner.parse_args(["--existing-result", str(paths[0]),
                                  "--existing-result", str(paths[1]),
                                  "--existing-result", str(paths[2])])
        provenance = {"gitHead": "head", "changedFileSha256": {"file": "hash"}}
        report = runner.write_report(args, paths, self.contract, provenance, provenance)
        self.assertEqual("PASSED", report["status"])
        self.assertEqual(3, report["runCount"])
        self.assertTrue(report["artifactDigestsConsistent"])

    def test_fresh_mode_invokes_exactly_three_unique_official_runs(self):
        args = runner.parse_args(["--output-root", str(self.root / "out"),
                                  "--stage-root", str(self.root / "stage"),
                                  "--run-id", "candidate"])
        with mock.patch.object(runner.subprocess, "run") as invoked:
            invoked.side_effect = [mock.Mock(returncode=value) for value in (0, 1, 2)]
            paths, returncodes = runner.run_fresh(args)
        self.assertEqual(3, invoked.call_count)
        self.assertEqual(["candidate-01", "candidate-02", "candidate-03"],
                         [path.parent.name for path in paths])
        self.assertEqual([0, 1, 2], [returncodes[str(path)] for path in paths])
        for call in invoked.call_args_list:
            command = call.args[0]
            self.assertEqual(str(runner.DISPATCH), command[0])
            self.assertEqual(runner.CASE, command[command.index("--case") + 1])

    def test_exact_model_and_nonnegative_compilation_are_required(self):
        path = self.fixture(seconds=-0.01)
        result = json.loads(path.read_text(encoding="utf-8"))
        model = result["cases"][0]["modelComparison"]
        model["maxAbsDifference"] = 1e-15
        model["errors"] = ["mismatch"]
        model["shapeMatched"] = False
        path.write_text(json.dumps(result), encoding="utf-8")
        evaluated = runner.evaluate_result(path, self.contract)
        self.assertFalse(evaluated["passed"])
        self.assertTrue(any("difference" in error for error in evaluated["errors"]))
        self.assertTrue(any("outside" in error for error in evaluated["errors"]))

    def test_workload_and_jvm_resource_changes_fail(self):
        path = self.fixture()
        manifest_path = path.with_name("manifest.json")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["fixtureSha256"][runner.CASE]["fed"] = "changed"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        script = path.with_name("container-run.sh")
        script.write_text(script.read_text(encoding="utf-8").replace("-Xmx1g", "-Xmx2g", 1),
                          encoding="utf-8")
        evaluated = runner.evaluate_result(path, self.contract)
        self.assertFalse(evaluated["passed"])
        self.assertTrue(any("workload fingerprint" in error for error in evaluated["errors"]))
        self.assertTrue(any("JVM resource" in error for error in evaluated["errors"]))

    def test_duplicate_receipts_nonzero_harness_and_provenance_change_fail(self):
        path = self.fixture("duplicate")
        args = runner.parse_args(["--existing-result", str(path)] * 3)
        before = {"gitHead": "head", "changedFileSha256": {"source": "before"}}
        after = {"gitHead": "head", "changedFileSha256": {"source": "after"}}
        report = runner.write_report(
            args, [path, path, path], self.contract, before, after, {str(path): 9})
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["provenanceStable"])
        self.assertTrue(any("result paths" in error for error in report["errors"]))
        self.assertTrue(any("container identities" in error for error in report["errors"]))
        self.assertTrue(any("provenance changed" in error for error in report["errors"]))
        self.assertTrue(all(not item["passed"] for item in report["runs"]))


if __name__ == "__main__":
    unittest.main()
