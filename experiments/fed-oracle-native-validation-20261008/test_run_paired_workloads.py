import importlib.util
import json
import pathlib
import copy
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("run_paired_workloads.py")
SPEC = importlib.util.spec_from_file_location("run_paired_workloads", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def receipt(action="action"):
    support_clause = {"proofDependencies": ["proof"],
                      "inputBindings": [{"kind": "DIRECT", "sourceOwner": "X"}],
                      "nativeWorkerPoolWitness": None, "nativeWorkerPoolLayoutExact": True}
    return {
        "canonicalProof": {"canonicalObjectiveBits": "7", "objectiveMatches": True,
                           "selectedStatesMatch": True, "sharedLifetimesMatch": True},
        "selectedCandidateSelections": ["rule|support=X"],
        "selectedCandidateSupport": [{"exactRule": "rule", "emission": "emission",
                                      "realization": "realization", "support": "X",
                                      "proofKeys": ["proof"],
                                      "inputBindings": ["DIRECT:X"],
                                      "supportClause": support_clause}],
        "selectedRelocations": [action],
        "selectedLocalMaterializations": [],
        "sharedSupplyLifetimes": [],
        "selectedOccurrences": [{"occurrence": "op", "physicalState": "FED/LOUT/ROW",
                                 "inputAuthorities": [{"inputPosition": 0,
                                                       "relocationAction": action}],
                                 "supportClause": support_clause}],
    }


class PairedWorkloadsTest(unittest.TestCase):
    def test_order_alternates_first_variant(self):
        self.assertEqual([(0, "baseline"), (0, "candidate"),
                          (1, "candidate"), (1, "baseline"),
                          (2, "baseline"), (2, "candidate")], MODULE.paired_order(3))

    def test_cgroup_v2_path_is_resolved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            proc = root / "proc" / "42"
            proc.mkdir(parents=True)
            (proc / "cgroup").write_text("0::/docker/example.scope\n")
            self.assertEqual(root / "cgroup" / "docker/example.scope",
                             MODULE.cgroup_directory(42, root / "cgroup", root / "proc"))

    def test_exact_receipts_pass(self):
        self.assertEqual("PASSED", MODULE.compare_pair(receipt(), receipt())["status"])

    def test_authority_loss_fails(self):
        result = MODULE.compare_pair(receipt(), receipt(action="other"))
        self.assertEqual("FAILED", result["status"])
        self.assertIn("selectedRelocations", result["failures"])

    def test_support_storage_representation_is_not_semantic(self):
        baseline = receipt()
        candidate = receipt()
        clause = {"proofDependencies": [], "inputBindings": [{"kind": "DIRECT"}],
                  "nativeWorkerPoolWitness": None, "nativeWorkerPoolLayoutExact": True}
        baseline["selectedCandidateSupport"][0]["supportClause"] = dict(
            clause, indexed=True, combinationId="5")
        candidate["selectedCandidateSupport"][0]["supportClause"] = dict(
            clause, indexed=False, combinationId=None)
        self.assertEqual("PASSED", MODULE.compare_pair(baseline, candidate)["status"])

    def test_support_direct_binding_loss_fails(self):
        baseline = receipt()
        candidate = copy.deepcopy(baseline)
        candidate["selectedCandidateSupport"][0]["supportClause"]["inputBindings"] = []
        with self.subTest("selected support"):
            self.assertIn("selectedCandidateSupport",
                          MODULE.compare_pair(baseline, candidate)["failures"])

    def test_source_owner_action_and_proof_changes_fail(self):
        for mutation in ("owner", "action", "proof"):
            baseline = receipt()
            candidate = copy.deepcopy(baseline)
            if mutation == "owner":
                candidate["selectedOccurrences"][0]["supportClause"][
                    "inputBindings"][0]["sourceOwner"] = "OTHER"
            elif mutation == "action":
                candidate["selectedOccurrences"][0]["inputAuthorities"][0][
                    "relocationAction"] = "OTHER"
            else:
                candidate["selectedOccurrences"][0]["supportClause"][
                    "proofDependencies"] = []
            with self.subTest(mutation=mutation):
                self.assertIn("selectedOccurrenceSourceAuthority",
                              MODULE.compare_pair(baseline, candidate)["failures"])

    def test_both_receipts_missing_input_authorities_fail_closed(self):
        baseline = receipt()
        candidate = copy.deepcopy(baseline)
        del baseline["selectedOccurrences"][0]["inputAuthorities"]
        del candidate["selectedOccurrences"][0]["inputAuthorities"]
        result = MODULE.compare_pair(baseline, candidate)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("baseline.selectedOccurrences[0].schema", result["failures"])
        self.assertIn("candidate.selectedOccurrences[0].schema", result["failures"])

    def test_both_receipts_missing_candidate_proof_fields_fail_closed(self):
        baseline = receipt()
        candidate = copy.deepcopy(baseline)
        del baseline["selectedCandidateSupport"][0]["exactRule"]
        del candidate["selectedCandidateSupport"][0]["exactRule"]
        result = MODULE.compare_pair(baseline, candidate)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("baseline.selectedCandidateSupport[0].schema", result["failures"])
        self.assertIn("candidate.selectedCandidateSupport[0].schema", result["failures"])

    def test_cross_variant_numeric_comparison_records_exact_bits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            for variant in ("baseline", "candidate"):
                case = root / variant / "cases" / "ml_logreg"
                case.mkdir(parents=True)
                (case / "fed-model.csv").write_text("1.0,2.0\n")
            result = MODULE.compare_numeric_outputs(
                root / "baseline", root / "candidate", "ml_logreg")
            self.assertEqual("PASSED", result["status"])
            self.assertTrue(result["files"][0]["exactBits"])

    def test_command_pins_proof_runtime_and_optional_jfr(self):
        command = MODULE.runner_command(
            pathlib.Path("/repo"), pathlib.Path("/engine"), pathlib.Path("/deps"),
            pathlib.Path("/output"), pathlib.Path("/stage"), "run", "ml_glm", True)
        self.assertIn("--canonical-proof", command)
        self.assertIn("--profile-jfr", command)
        self.assertEqual("ml_glm", command[-2])

    def test_pair_ratios_use_within_pair_values(self):
        rows = [{"baseline": {"elapsedSeconds": 10, "cgroupMemoryPeakBytes": 100,
                              "metrics": {"candidateE2ESeconds": 5}},
                 "candidate": {"elapsedSeconds": 8, "cgroupMemoryPeakBytes": 110,
                               "metrics": {"candidateE2ESeconds": 4}}}]
        ratios = MODULE.paired_ratios(rows)
        self.assertEqual(0.8, ratios["elapsed"]["medianRatio"])
        self.assertEqual(1.1, ratios["cgroupPeak"]["medianRatio"])
        self.assertEqual(0.8, ratios["candidateE2E"]["medianRatio"])

    def test_docker_memory_units_prefer_long_suffix(self):
        self.assertEqual(10 * 1024**2, MODULE.parse_stats_bytes("10MiB"))

    def test_allocation_events_are_counted_and_weighted(self):
        payload = {"recording": {"events": [
            {"type": "jdk.ObjectAllocationSample", "values": {
                "weight": 100, "objectClass": {"name": "java/util/ArrayList"},
                "stackTrace": {"frames": [{"method": {
                    "type": {"name": "org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis"},
                    "name": "analyze"}}]}}},
            {"type": "jdk.ObjectAllocationSample", "values": {"weight": 40}},
            {"type": "jdk.ObjectAllocationOutsideTLAB", "values": {"allocationSize": 7}},
        ]}}
        summary = MODULE.allocation_events_summary(payload)
        self.assertEqual(3, summary["totalEventCount"])
        self.assertEqual(147, summary["totalRepresentedBytes"])
        self.assertEqual("java.util.ArrayList", summary["topAllocatedClasses"][0]["name"])
        self.assertEqual(1, summary["plannerAttributedEventCount"])
        self.assertEqual(100, summary["plannerAttributedRepresentedBytes"])
        self.assertEqual("placement-analysis", summary["plannerPhases"][0]["name"])
        self.assertIn("PlacementAnalysis.analyze", summary["topPlannerFrames"][0]["name"])
        self.assertIn("java.util.ArrayList @ ",
                      summary["topClassAndPlannerFrame"][0]["name"])

    def test_workload_metrics_reads_existing_harness_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = pathlib.Path(tmp)
            case_dir = run / "cases" / "ml_logreg"
            case_dir.mkdir(parents=True)
            (run / "result.json").write_text(json.dumps({"status": "PASSED", "cases": [{
                "case": "ml_logreg", "passed": True,
                "modelComparison": {"matched": True, "maxAbsDifference": 1e-12},
                "fedStatistics": {"compilationSeconds": 2.5},
            }]}))
            (case_dir / "fed.log").write_text(
                "SEARCH_SPACE_EXECUTION_RELATION|candidateOracleCalls=19|"
                "relations=3|regions=4|relationOracleCalls=4|logicalTuples=4\n"
                "[PlannerTrace][Planner-CandidateE2ETiming] "
                "analysisNanos=2000000000 modelNanos=500000000 "
                "costSurfaceNanos=250000000 optimizerNanos=3000000000 "
                "totalNanos=7000000000\n")
            metrics = MODULE.workload_metrics(run, "ml_logreg")
            self.assertEqual(7.0, metrics["candidateE2ESeconds"])
            self.assertEqual(2.5, metrics["fedCompilationSeconds"])
            self.assertTrue(metrics["modelMatched"])
            self.assertEqual(19, metrics["executionRelation"]["candidateOracleCalls"])
            self.assertEqual(0.5, metrics["modelSeconds"])
            self.assertEqual(0.25, metrics["costSurfaceSeconds"])
            self.assertEqual(3.0, metrics["candidateE2EPhasesSeconds"]["optimizer"])

    def test_legality_inventory_expands_factorized_axes(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = pathlib.Path(tmp)
            audit = run / "audit" / "ml_logreg-fed"
            audit.mkdir(parents=True)
            row = {"schema": "fedplanner-candidate-space-v1", "occurrence": "op",
                   "inputAxes": [["PRESENT:ROW", "PRESENT:COL"], ["ABSENT_LOCAL:-"]],
                   "publishedNodeStates": ["FED/LOUT/ROW"], "publishedRule": {"cap": "x"}}
            (audit / "candidate-space-1.jsonl").write_text(json.dumps(row) + "\n")
            inventory = MODULE.candidate_legality_inventory(run, "ml_logreg")
            self.assertEqual(2, inventory["expandedRows"])

if __name__ == "__main__":
    unittest.main()
