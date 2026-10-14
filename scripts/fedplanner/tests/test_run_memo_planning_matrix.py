import importlib.util
from pathlib import Path
import tempfile
import unittest


PATH = Path(__file__).resolve().parents[1] / "run_memo_planning_matrix.py"
SPEC = importlib.util.spec_from_file_location("memo_matrix", PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class MemoPlanningMatrixTest(unittest.TestCase):
    def rendered(self):
        value = runner.cell("ml", "logreg", 1)
        shard = {"worker": {"host": "so002", "ip": "130.149.237.12", "port": 8001},
                 "endpoint": "130.149.237.12:8001",
                 "address": "130.149.237.12:8001//workspace/experiments/data/X"}
        return {"cell": value, "source": 'x="130.149.237.12:8001//workspace/experiments/data/X";',
                "outputs": [],
                "inputs": [{"role": "X", "shards": [shard]}]}

    def test_default_matrix_is_exactly_56_unique_cells(self):
        values = runner.cells()
        self.assertEqual(56, len(values))
        self.assertEqual({1, 3, 5, 7}, {row["workers"] for row in values})
        self.assertEqual(14, len({(row["suite"], row["workload"]) for row in values}))
        self.assertTrue(all(row["planner"] == "DP-global" for row in values))
        self.assertTrue(all(runner.execution_cell(row)["planner"] == "Memo" for row in values))
        self.assertTrue(all(runner.execution_cell(row)["id"].endswith("|Memo") for row in values))

    def test_sealed_endpoints_are_preserved_and_receipted(self):
        rendered = self.rendered()
        adapted, receipt = runner.adapt_rendered(rendered, runner.planner_neutral_source(rendered))
        self.assertEqual(rendered["source"], adapted)
        self.assertIn("130.149.237.12:8001", adapted)
        self.assertEqual("COMPILE_MEMO", receipt["executionPlanner"])
        self.assertEqual(receipt["originalSha256"], receipt["adaptedSha256"])
        self.assertEqual([], receipt["transformations"])
        self.assertTrue(receipt["sealedEndpointsPreserved"])

    def test_planner_dependent_renderer_output_is_rejected(self):
        rendered = self.rendered()
        with self.assertRaisesRegex(ValueError, "depends on"):
            runner.adapt_rendered(rendered, runner.planner_neutral_source(rendered) + "# changed")

    def test_planner_specific_output_path_is_normalized_for_independence_proof(self):
        rendered = self.rendered()
        rendered["source"] += 'write(x,"tmp/a");'
        rendered["outputs"] = [{"role": "result", "path": "tmp/a"}]
        other = dict(rendered)
        other["source"] = rendered["source"].replace("tmp/a", "tmp/b")
        other["outputs"] = [{"role": "result", "path": "tmp/b"}]
        self.assertEqual(runner.planner_neutral_source(rendered), runner.planner_neutral_source(other))

    def test_config_is_memo_compile_only_and_p2_property_is_a_jvm_flag(self):
        ordinary = runner.configuration("ml")
        self.assertIn("<sysds.federated.planner>compile_memo</sysds.federated.planner>", ordinary)
        self.assertIn("<sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>", ordinary)
        self.assertNotIn("allowPublicRecodeMetadata", ordinary)
        self.assertNotIn("allowPublicRecodeMetadata", runner.configuration("p2"))
        self.assertNotIn("allowPublicRecodeMetadata", runner.container_script(False))
        self.assertIn("-Dsysds.privacy.allowPublicRecodeMetadata=true", runner.container_script(True))

    def test_failure_categories_do_not_call_setup_or_timeout_a_planner_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            self.assertEqual("setup_failure", runner.failure_category(1, attempt, [], []))
            (attempt / "setup-complete").touch()
            self.assertEqual("timeout_not_proven", runner.failure_category(124, attempt, [], []))
            self.assertEqual("killed_not_proven", runner.failure_category(137, attempt, [], []))
            self.assertEqual("harness_timeout_not_proven", runner.failure_category(
                None, attempt, [], [], harness_timeout=True))
            self.assertEqual("planner_failure", runner.failure_category(
                1, attempt, [], [{"status": "UNRESOLVED"}]))
            self.assertEqual("compilation_failure", runner.failure_category(
                1, attempt, [], [{"status": "OPTIMAL"}]))
            self.assertEqual("planning_succeeded_compile_failed", runner.failure_category(
                1, attempt, [{"status": "OPTIMAL"}], [{"status": "OPTIMAL"}]))

    def test_published_c26_cost_contract_has_no_injected_override(self):
        contract = runner.validate_cost_contract()
        self.assertEqual({}, contract["costEnvironment"])
        self.assertEqual("none", contract["nativeBlas"])
        rendered = self.rendered()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            engine, stage, attempt = root / "engine", root / "stage", root / "attempt"
            for path in (engine / "classes", engine / "lib", stage, attempt):
                path.mkdir(parents=True)
            command = runner.docker_command(attempt, engine, stage, rendered, "container")
        self.assertFalse(any("SYSDS_FED_COST_" in item for item in command))

    def test_renderer_and_stage_contracts_are_explicitly_pinned(self):
        self.assertEqual(runner.PINNED_RENDERER_SHA256, runner.sha256_file(runner.RENDERER_PATH))
        stage = Path("/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z/W1357_STAGE.json")
        self.assertEqual(runner.PINNED_STAGE_MANIFEST_SHA256, runner.sha256_file(stage))

    def test_failure_result_keeps_execution_and_render_identities_separate(self):
        rendered = runner.cell("p1", "P1_FULL", 3)
        result = runner.failed_attempt(rendered, "setup_failure", ValueError("broken"))
        self.assertEqual(rendered, result["renderCell"])
        self.assertEqual("p1|P1_FULL|lan|w3|Memo", result["cell"]["id"])
        self.assertEqual("COMPILE_MEMO", result["executionPlanner"])
        self.assertEqual("COMPILE_EXACT", result["rendererAuthorityPlanner"])

    def test_filters_are_closed_to_the_frozen_matrix(self):
        class Args:
            workers = "1,7"
            workloads = "ml:logreg,sliceline:ADULT"
        workloads, workers = runner.parse_filters(Args())
        self.assertEqual((("ml", "logreg"), ("sliceline", "ADULT")), workloads)
        self.assertEqual((1, 7), workers)
        Args.workers = "2"
        with self.assertRaises(ValueError):
            runner.parse_filters(Args())

    def test_compile_evidence_requires_complete_non_runtime_receipt(self):
        complete = ("Total compilation time: 2.500 sec.\n"
                    "PlanningFullInitialReceipt schema=planning-full-initial-v1 "
                    "Tplanning_full_initial_nanos=2000000000\n"
                    "Compile Phase RuntimeProgram: 0.125 sec.\n"
                    "Total execution time: 0.000 sec.\n")
        evidence = runner.compile_evidence(complete)
        self.assertTrue(evidence["verified"])
        self.assertEqual(2.0, evidence["fullInitialPlanningSeconds"])
        incomplete = runner.compile_evidence("Total compilation time: 2.500 sec.\n")
        self.assertFalse(incomplete["verified"])
        self.assertIsNone(incomplete["fullInitialPlanningSeconds"])


if __name__ == "__main__":
    unittest.main()
