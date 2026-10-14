import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock


BASE = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, BASE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = load("actual_ml_gate", "evaluate_actual_ml_planning.py")
workflow = load("actual_ml_workflow", "run_actual_ml_planning_performance.py")


class FakeRenderer:
    def __init__(self, stage=None):
        self.stage = stage

    def render(self, cell):
        source_cell = {key: cell[key] for key in ("suite", "workload", "profile", "workers")}
        source = json.dumps(source_cell, sort_keys=True) + "\n"
        shards = [{"metadata_sha256": "2" * 64, "range": [[index, 0], [index + 1, 1]],
                   "blocks": [{"sha256": "3" * 64}]}
                  for index in range(cell["workers"])]
        return {"schema": "cofee-w1357-render/v1", "cell": cell, "outputs": [],
            "content_verification": {
                "stage_manifest_sha256": gate.matrix.PINNED_STAGE_MANIFEST_SHA256,
                "selected_template_metadata_and_data_blocks": "sha256-verified-at-render"},
            "inputs": [{"privacy": "private-aggregate", "metadata_sha256": "1" * 64,
                "shards": shards}], "source": source}


class ActualMlPlanningGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.contract = gate.matrix.validate_cost_contract()
        self.engine = self.root / "engine"
        self.engine.mkdir()
        self.engine_receipt = {"manifest": "frozen.json", "manifestSha256": "a" * 64,
                               "verifiedFiles": 1}
        cells = gate.expected_cells()
        manifest = {"renderCells": cells,
            "executionCells": [gate.matrix.execution_cell(cell) for cell in cells],
            "executionPlanner": "COMPILE_MEMO", "rendererAuthorityPlanner": "COMPILE_EXACT",
            "image": gate.matrix.PINNED_IMAGE, "rendererSha256": gate.matrix.PINNED_RENDERER_SHA256,
            "stageManifestSha256": gate.matrix.PINNED_STAGE_MANIFEST_SHA256,
            "renderOnly": False, "costContract": self.contract, "engine": str(self.engine),
            "stage": str(self.root / "stage"),
            "enginePreflight": self.engine_receipt}
        (self.root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        for cell in cells:
            self.write_result(cell)

    def write_result(self, cell, **changes):
        attempt = self.root / "attempts" / gate.matrix.safe_id(cell)
        attempt.mkdir(parents=True, exist_ok=True)
        source, config, render, adaptation = gate.matrix.render_artifacts(FakeRenderer(), cell)
        digest = gate.matrix.sha256_bytes(source.encode())
        result = {"schema": "systemds-memo-planning-attempt/v1", "renderCell": cell,
            "cell": gate.matrix.execution_cell(cell), "executionPlanner": "COMPILE_MEMO",
            "status": "passed", "returncode": 0, "failureCategory": None,
            "harnessTimeout": False, "compileOnly": True, "compileOnlyVerified": True,
            "reportedWorkerCountVerified": True, "costContract": self.contract,
            "containerResources": gate.matrix.CONTAINER_RESOURCES,
            "image": gate.matrix.PINNED_IMAGE,
            "memoPlanning": [{"status": "OPTIMAL", "workers": cell["workers"]}],
            "memoSearch": [{"status": "OPTIMAL"}], "provenance": {
                "rendererPath": str(gate.matrix.RENDERER_PATH),
                "rendererSha256": gate.matrix.PINNED_RENDERER_SHA256,
                "stage": str(self.root / "stage"),
                "stageManifestSha256": gate.matrix.PINNED_STAGE_MANIFEST_SHA256,
                "engine": str(self.engine), "dmlSha256": digest,
                "configSha256": gate.matrix.sha256_bytes(gate.matrix.configuration("ml").encode())},
            "timing": {"fullInitialPlanningSeconds": 9.5, "fullCompileSeconds": 10.0,
                       "runtimeProgramSeconds": 0.2, "executionSeconds": 0.0}}
        result.update(changes)
        (attempt / "cell.dml").write_text(source, encoding="utf-8")
        (attempt / "execution.xml").write_text(config, encoding="utf-8")
        (attempt / "render-receipt.json").write_text(json.dumps(render), encoding="utf-8")
        (attempt / "adaptation-receipt.json").write_text(json.dumps(adaptation), encoding="utf-8")
        (attempt / "result.json").write_text(json.dumps(result), encoding="utf-8")

    def evaluate(self):
        renderer_module = types.SimpleNamespace(Renderer=FakeRenderer)
        with mock.patch.object(gate.matrix, "validate_engine", return_value=self.engine_receipt), \
                mock.patch.object(gate.matrix, "validate_sealed_stage", return_value=self.root / "stage"), \
                mock.patch.object(gate.matrix, "renderer_module", return_value=renderer_module):
            return gate.evaluate(self.root)

    def test_gate_is_exactly_actual_logreg_glm_w1357_and_inclusive_ten_seconds(self):
        result = self.evaluate()
        self.assertTrue(result["passed"])
        self.assertEqual(8, result["acceptedCells"])
        self.assertEqual({("logreg", 1), ("logreg", 3), ("logreg", 5), ("logreg", 7),
                          ("glm", 1), ("glm", 3), ("glm", 5), ("glm", 7)},
                         {(cell["workload"], cell["workers"]) for cell in gate.expected_cells()})

    def test_one_nanosecond_equivalent_over_threshold_fails(self):
        cell = gate.expected_cells()[0]
        self.write_result(cell, timing={"fullInitialPlanningSeconds": 9.0,
            "fullCompileSeconds": 10.000000001, "runtimeProgramSeconds": 0.2,
            "executionSeconds": 0.0})
        result = self.evaluate()
        self.assertFalse(result["passed"])
        self.assertIn("full compile is missing or exceeds 10 seconds", result["rejected"][0]["reasons"])

    def test_rc_zero_without_receipts_fails_closed(self):
        cell = gate.expected_cells()[0]
        self.write_result(cell, compileOnlyVerified=False, memoPlanning=[], memoSearch=[])
        reasons = self.evaluate()["rejected"][0]["reasons"]
        self.assertIn("missing compile-only receipt", reasons)
        self.assertIn("missing OPTIMAL Memo planning receipt", reasons)
        self.assertIn("missing OPTIMAL exact-search receipt", reasons)

    def test_missing_runtime_receipt_or_nonzero_execution_fails(self):
        cell = gate.expected_cells()[0]
        self.write_result(cell, timing={"fullInitialPlanningSeconds": 8.0,
            "fullCompileSeconds": 9.0, "runtimeProgramSeconds": None,
            "executionSeconds": 0.001})
        reasons = self.evaluate()["rejected"][0]["reasons"]
        self.assertIn("missing RuntimeProgram construction receipt", reasons)
        self.assertIn("runtime execution was not proven to be zero", reasons)

    def test_modified_or_missing_input_receipt_fails(self):
        cell = gate.expected_cells()[0]
        attempt = self.root / "attempts" / gate.matrix.safe_id(cell)
        (attempt / "adaptation-receipt.json").unlink()
        self.assertIn("missing or invalid sealed input artifact",
                      self.evaluate()["rejected"][0]["reasons"])

    def test_hash_shaped_receipt_not_emitted_by_pinned_renderer_fails(self):
        cell = gate.expected_cells()[0]
        attempt = self.root / "attempts" / gate.matrix.safe_id(cell)
        render = json.loads((attempt / "render-receipt.json").read_text(encoding="utf-8"))
        render["inputs"][0]["metadata_sha256"] = "f" * 64
        (attempt / "render-receipt.json").write_text(json.dumps(render), encoding="utf-8")
        self.assertIn("sealed rendered input differs from pinned renderer output",
                      self.evaluate()["rejected"][0]["reasons"])

    def test_tampered_dml_and_configuration_files_fail(self):
        cell = gate.expected_cells()[0]
        attempt = self.root / "attempts" / gate.matrix.safe_id(cell)
        (attempt / "cell.dml").write_text("# tampered\n", encoding="utf-8")
        (attempt / "execution.xml").write_text("<root/>\n", encoding="utf-8")
        reasons = self.evaluate()["rejected"][0]["reasons"]
        self.assertIn("sealed rendered input differs from pinned renderer output", reasons)
        self.assertIn("compile configuration artifact mismatch", reasons)
        self.assertIn("on-disk DML hash mismatch", reasons)

    def test_result_cannot_claim_a_different_runtime_engine(self):
        cell = gate.expected_cells()[0]
        attempt = self.root / "attempts" / gate.matrix.safe_id(cell)
        result = json.loads((attempt / "result.json").read_text(encoding="utf-8"))
        result["provenance"]["engine"] = str(self.root / "untrusted-engine")
        (attempt / "result.json").write_text(json.dumps(result), encoding="utf-8")
        self.assertIn("execution provenance does not bind pinned renderer, stage, and engine",
                      self.evaluate()["rejected"][0]["reasons"])


class ActualMlWorkflowTest(unittest.TestCase):
    def test_workflow_forces_actual_workloads_and_all_four_worker_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with mock.patch.object(workflow.matrix, "main", return_value=1) as run, \
                    mock.patch.object(workflow.evaluator, "main", return_value=0) as evaluate:
                self.assertEqual(1, workflow.main(["--engine", "engine", "--sealed-stage", "stage",
                                                  "--output-root", str(output)]))
            argv = run.call_args.args[0]
            self.assertEqual("1,3,5,7", argv[argv.index("--workers") + 1])
            self.assertEqual("ml:logreg,ml:glm", argv[argv.index("--workloads") + 1])
            self.assertEqual(["--root", str(output), "--output", str(output / "acceptance.json")],
                             evaluate.call_args.args[0])

    def test_workflow_returns_gate_failure_after_successful_matrix(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(workflow.matrix, "main", return_value=0), \
                mock.patch.object(workflow.evaluator, "main", return_value=7):
            output = Path(directory) / "new"
            self.assertEqual(7, workflow.main(["--engine", "engine", "--sealed-stage", "stage",
                                               "--output-root", str(output)]))

    def test_workflow_refuses_resume_so_each_candidate_has_one_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "one attempt"):
                workflow.main(["--engine", "engine", "--sealed-stage", "stage",
                               "--output-root", directory])


if __name__ == "__main__":
    unittest.main()
