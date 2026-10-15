import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "run_memo_tree_dp_benchmark.py"
SPEC = importlib.util.spec_from_file_location("run_memo_tree_dp_benchmark", SCRIPT)
benchmark = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = benchmark
SPEC.loader.exec_module(benchmark)


class MemoTreeDpBenchmarkTest(unittest.TestCase):
    def test_output_root_is_absolute_for_docker_bind_mounts(self):
        self.assertTrue(benchmark.absolute_output_root(Path("relative-results")).is_absolute())

    def test_trace_keeps_progression_with_its_preceding_certificate(self):
        trace = """[MemoTreeDP] status=EPSILON_OPTIMAL certified=true lowerBound=100 upperBound=104 statistics=Statistics[nodes=7, conflicts=2]
[MemoTreeDPBound] nodes=1 lowerBound=90 upperBound=120
[MemoTreeDPBound] nodes=7 lowerBound=100 upperBound=104
[MemoTreeDP] status=OPTIMAL certified=true lowerBound=12 upperBound=12 statistics=Statistics[nodes=3]
[MemoTreeDPBound] nodes=3 lowerBound=12 upperBound=12
"""
        rows = benchmark.parse_tree_trace(trace)
        self.assertEqual(2, len(rows))
        self.assertEqual("0.04", rows[0]["relativeGap"])
        self.assertEqual(2, len(rows[0]["progression"]))
        self.assertEqual(7, rows[0]["statistics"]["nodes"])
        self.assertEqual(1, len(rows[1]["progression"]))

    def test_local_requires_certified_five_percent_bound(self):
        plan = [{"status": "OPTIMAL"}]
        accepted = [{"status": "EPSILON_OPTIMAL", "certified": True,
                     "lowerBound": "100", "upperBound": "105"}]
        rejected = [{**accepted[0], "upperBound": "105.0000001"}]
        self.assertEqual(("passed", None),
            benchmark.certify("TREE_DP_LOCAL", 0, False, plan, accepted))
        self.assertEqual("epsilon_gap_not_proven",
            benchmark.certify("TREE_DP_LOCAL", 0, False, plan, rejected)[1])

    def test_dual_trace_attaches_streamed_bounds_to_following_certificate(self):
        trace = """[MemoDualBound] nodes=0 lowerBound=90 upperBound=null
[MemoDualBound] nodes=11 lowerBound=100 upperBound=104
[MemoDual] status=EPSILON_OPTIMAL certified=true lowerBound=100 upperBound=104 statistics=Statistics[directExactSolves=0, directAttempts=3, seedAttempts=1, nodes=11, relaxationCalls=8, maxPending=4, peakEstimatedBytes=900, route=CERTIFIED_SEARCH] reason=Certified gap reached
[MemoDualBound] nodes=0 lowerBound=12 upperBound=12
[MemoDual] status=OPTIMAL certified=true lowerBound=12 upperBound=12 statistics=Statistics[directExactSolves=1, directAttempts=1, nodes=0, route=DIRECT] reason=Direct_exact
"""
        rows = benchmark.parse_dual_trace(trace)
        self.assertEqual(2, len(rows))
        self.assertEqual("0.04", rows[0]["relativeGap"])
        self.assertEqual(2, len(rows[0]["progression"]))
        self.assertEqual(11, rows[0]["statistics"]["nodes"])
        self.assertEqual("CERTIFIED_SEARCH", rows[0]["route"])
        self.assertEqual("Certified gap reached", rows[0]["reason"])
        self.assertEqual("DIRECT", rows[1]["route"])

    def test_memo_dual_certification_is_separate_from_tree_certificates(self):
        plan = [{"status": "OPTIMAL"}]
        exact = [{"status": "OPTIMAL", "certified": True,
                  "lowerBound": "25", "upperBound": "25"}]
        self.assertEqual(("passed", None),
            benchmark.certify("MEMO_GLOBAL", 0, False, plan, [], exact))
        local_plan = [{"status": "EPSILON_OPTIMAL"}]
        local = [{"status": "EPSILON_OPTIMAL", "certified": True,
                  "lowerBound": "100", "upperBound": "105"}]
        self.assertEqual(("passed", None),
            benchmark.certify("MEMO_LOCAL", 0, False, local_plan, [], local))
        local[0]["lowerBound"] = "0"
        self.assertEqual("epsilon_requires_positive_lower_bound",
            benchmark.certify("MEMO_LOCAL", 0, False, local_plan, [], local)[1])

    def test_memo_dual_failed_status_is_explicit(self):
        plan = [{"status": "RESOURCE_EXHAUSTED"}]
        dual = [{"status": "RESOURCE_EXHAUSTED", "certified": False,
                 "lowerBound": "10", "upperBound": "12"}]
        self.assertEqual("deterministic_resource_exhaustion",
            benchmark.certify("MEMO_LOCAL", 0, False, plan, [], dual)[1])
        plan[0]["status"] = "UNRESOLVED"
        dual[0]["status"] = "UNRESOLVED"
        self.assertEqual("dual_status_unresolved",
            benchmark.certify("MEMO_LOCAL", 0, False, plan, [], dual)[1])

    def test_epsilon_boundary_uses_exact_decimal_arithmetic(self):
        lower_integer = 123456789012345678901234567890123456780
        lower = benchmark.Decimal(lower_integer)
        at_boundary = lower_integer * 105 // 100
        self.assertTrue(benchmark.within_epsilon(lower, benchmark.Decimal(at_boundary)))
        self.assertFalse(benchmark.within_epsilon(lower, benchmark.Decimal(at_boundary + 1)))

    def test_global_requires_equal_bounds_and_optimal_status(self):
        plan = [{"status": "OPTIMAL"}]
        tree = [{"status": "OPTIMAL", "certified": True,
                 "lowerBound": "3.25", "upperBound": "3.25"}]
        self.assertEqual(("passed", None),
            benchmark.certify("TREE_DP_GLOBAL", 0, False, plan, tree))
        tree[0]["upperBound"] = "3.26"
        self.assertEqual("global_optimality_not_proven",
            benchmark.certify("TREE_DP_GLOBAL", 0, False, plan, tree)[1])

    def test_group_comparison_uses_exact_solver_as_independent_optimum(self):
        def row(algorithm, lower, upper, status="passed", progression=None):
            return {"algorithm": algorithm, "memoPlans": [{"cost": upper}],
                    "status": status,
                    "treeCertificates": [] if algorithm == "EXACT" else
                        [{"lowerBound": lower, "upperBound": upper,
                          "progression": progression or []}]}
        rows = [row("EXACT", "100", "100"), row("TREE_DP_GLOBAL", "100", "100"),
                row("TREE_DP_LOCAL", "96", "104")]
        comparison = benchmark.compare_group(rows)
        self.assertEqual("passed", comparison["status"])
        self.assertTrue(comparison["validBounds"])
        self.assertTrue(comparison["requestedGuaranteeEstablished"])
        rows[-1]["treeCertificates"][0]["upperBound"] = "106"
        self.assertEqual("failed", benchmark.compare_group(rows)["status"])

    def test_group_comparison_checks_new_dual_modes_against_exact(self):
        exact = {"algorithm": "EXACT", "status": "passed",
                 "memoPlans": [{"cost": "100"}], "treeCertificates": [],
                 "dualCertificates": []}
        global_row = {"algorithm": "MEMO_GLOBAL", "status": "passed",
                      "memoPlans": [{"cost": "100"}], "treeCertificates": [],
                      "dualCertificates": [{"lowerBound": "100", "upperBound": "100",
                          "progression": [{"lowerBound": "90", "upperBound": None}]}]}
        local_row = {"algorithm": "MEMO_LOCAL", "status": "passed",
                     "memoPlans": [{"cost": "104"}], "treeCertificates": [],
                     "dualCertificates": [{"lowerBound": "96", "upperBound": "104",
                         "progression": []}]}
        comparison = benchmark.compare_group([exact, global_row, local_row])
        self.assertEqual("passed", comparison["status"])
        self.assertTrue(comparison["validBounds"])

    def test_group_comparison_accepts_sound_uncertified_intervals(self):
        rows = [
            {"algorithm": "EXACT", "status": "passed",
             "memoPlans": [{"cost": "100"}], "treeCertificates": []},
            {"algorithm": "TREE_DP_GLOBAL", "status": "failed", "memoPlans": [],
             "treeCertificates": [{"lowerBound": "90", "upperBound": "110",
                 "progression": [{"lowerBound": "80", "upperBound": None},
                                 {"lowerBound": "90", "upperBound": "110"}]}]},
        ]
        comparison = benchmark.compare_group(rows)
        self.assertEqual("passed", comparison["status"])
        self.assertTrue(comparison["validBounds"])
        self.assertFalse(comparison["requestedGuaranteeEstablished"])

    def test_group_comparison_rejects_unsound_progression(self):
        rows = [
            {"algorithm": "EXACT", "status": "passed",
             "memoPlans": [{"cost": "100"}], "treeCertificates": []},
            {"algorithm": "TREE_DP_LOCAL", "status": "failed", "memoPlans": [],
             "treeCertificates": [{"lowerBound": "90", "upperBound": "110",
                 "progression": [{"lowerBound": "101", "upperBound": None}]}]},
        ]
        comparison = benchmark.compare_group(rows)
        self.assertEqual("failed", comparison["status"])
        self.assertFalse(comparison["validBounds"])

    def test_watchdog_is_always_failure(self):
        self.assertEqual(("failed", "external_watchdog_no_certificate"),
            benchmark.certify("TREE_DP_LOCAL", None, True, [], []))

    def test_resource_exhaustion_retains_failure_class_without_plan_trace(self):
        trees = [{"status": "RESOURCE_EXHAUSTED", "certified": False,
                  "lowerBound": "10", "upperBound": "12"}]
        self.assertEqual(("failed", "deterministic_resource_exhaustion"),
            benchmark.certify("TREE_DP_GLOBAL", 1, False, [], trees))

    def test_configuration_and_container_set_algorithm(self):
        config = benchmark.configuration("compile_memo", "TREE_DP_LOCAL")
        limits = {"searchNodes": 2000, "frontierNodes": 1000,
                  "factorCells": 4096, "cacheEntries": 4096}
        dual = {"directExact": False, "seedWork": 123, "factorCells": 456, "directCells": 0,
                "memoryBytes": 789, "diagnosticNodes": 1011}
        script = benchmark.container_script("TREE_DP_LOCAL", limits, dual)
        self.assertIn("<sysds.fedplanner.memo.algorithm>TREE_DP_LOCAL", config)
        self.assertIn("<sysds.fedplanner.memo.local.messages>true", config)
        self.assertIn("<sysds.fedplanner.memo.local.improveSeed>true", config)
        self.assertIn("-Dsysds.fedplanner.memo.algorithm=TREE_DP_LOCAL", script)
        self.assertIn("-Dsysds.fedplanner.memo.tree.searchNodes=2000", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.directExact=false", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.seedWork=123", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.factorCells=456", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.directCells=0", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.memoryBytes=789", script)
        self.assertIn("-Dsysds.fedplanner.memo.dual.diagnosticNodes=1011", script)
        self.assertIn("-Dsysds.fedplanner.memo.local.messages=true", script)
        self.assertIn("-Dsysds.fedplanner.memo.local.improveSeed=true", script)
        self.assertIn("-Xmx3072m", script)
        self.assertNotIn("timeout ", script)

    def test_local_policy_flags_can_be_disabled_in_xml_and_jvm(self):
        limits = {"searchNodes": 2000, "frontierNodes": 1000,
                  "factorCells": 4096, "cacheEntries": 4096}
        dual = {"directExact": True, "seedWork": 100000, "factorCells": 256,
                "directCells": 2000000, "memoryBytes": 2**63-1,
                "diagnosticNodes": 2**63-1, "localMessages": False,
                "localImproveSeed": False}
        config = benchmark.configuration("compile_memo", "MEMO_LOCAL", dual)
        script = benchmark.container_script("MEMO_LOCAL", limits, dual)
        self.assertIn("<sysds.fedplanner.memo.local.messages>false", config)
        self.assertIn("<sysds.fedplanner.memo.local.improveSeed>false", config)
        self.assertIn("-Dsysds.fedplanner.memo.local.messages=false", script)
        self.assertIn("-Dsysds.fedplanner.memo.local.improveSeed=false", script)

    def test_local_policy_parser_defaults_are_enabled_and_strict(self):
        parser = benchmark.argument_parser()
        defaults = parser.parse_args(["--engine", "/tmp/engine", "--sealed-stage", "/tmp/stage"])
        self.assertTrue(defaults.local_messages)
        self.assertTrue(defaults.local_improve_seed)
        disabled = parser.parse_args(["--engine", "/tmp/engine", "--sealed-stage", "/tmp/stage",
                                      "--local-messages", "false", "--local-improve-seed", "FALSE"])
        self.assertFalse(disabled.local_messages)
        self.assertFalse(disabled.local_improve_seed)

    def test_compile_heap_is_explicit_and_does_not_change_other_phase_defaults(self):
        limits = {"searchNodes": 2000, "frontierNodes": 1000,
                  "factorCells": 4096, "cacheEntries": 4096}
        dual = {"directExact": True, "seedWork": 100000, "factorCells": 256,
                "directCells": 2000000, "memoryBytes": 2**63-1,
                "diagnosticNodes": 2**63-1}
        compile_script = benchmark.container_script("MEMO_LOCAL", limits, dual, heap_mib=256)
        self.assertIn("-Xmx256m", compile_script)
        self.assertIn("-Xmx3g", benchmark.synthetic_script(1))
        self.assertIn("-Xmx3g", benchmark.runtime_script(limits))

    def test_reproduction_command_records_compile_heap(self):
        args = benchmark.argparse.Namespace(engine=Path("/tmp/engine"),
            sealed_stage=Path("/tmp/stage"), output_root=Path("/tmp/output"), workers=3,
            watchdog_seconds=900, heap_mib=512, search_nodes=20000, frontier_nodes=10000,
            factor_cells=256, cache_entries=4096, dual_direct_exact=True,
            dual_seed_work=100000, dual_factor_cells=256, dual_direct_cells=2000000,
            dual_cluster_cells=128, dual_improve_seed=False, factor_diagnostics=True, factor_diagnostics_max_factors=50000, dual_diagnostics=True,
            local_messages=False, local_improve_seed=True,
            dual_memory_bytes=2**63-1, dual_diagnostic_nodes=2**63-1,
            synthetic_repetitions=7, skip_synthetic=True, runtime_smoke=False)
        command = benchmark.reproduction_command(args,["gnmf"],["EXACT","MEMO_GLOBAL","MEMO_LOCAL"])
        self.assertIn("--heap-mib 512",command)
        self.assertIn("--dual-direct-cells 2000000",command)
        self.assertIn("--dual-cluster-cells 128",command)
        self.assertIn("--dual-improve-seed false",command)
        self.assertIn("--local-messages false",command)
        self.assertIn("--local-improve-seed true",command)
        self.assertIn("--factor-diagnostics true",command)
        self.assertIn("--factor-diagnostics-max-factors 50000",command)
        self.assertIn("--dual-diagnostics true",command)

    def test_heap_oom_is_classifiable_as_an_explicit_failure(self):
        self.assertIsNotNone(benchmark.OUT_OF_MEMORY.search(
            "Exception in thread main java.lang.OutOfMemoryError: Java heap space"))

    def test_default_algorithms_remain_the_original_three(self):
        self.assertEqual(("EXACT", "TREE_DP_GLOBAL", "TREE_DP_LOCAL"),
                         benchmark.DEFAULT_ALGORITHMS)
        self.assertIn("MEMO_GLOBAL", benchmark.ALGORITHMS)
        self.assertIn("MEMO_LOCAL", benchmark.ALGORITHMS)

    def test_synthetic_requires_all_five_algorithms_and_allows_ablation_rows(self):
        rows = [{"case": case, "algorithm": algorithm}
                for case in benchmark.SYNTHETIC_CASES for algorithm in benchmark.ALGORITHMS]
        self.assertTrue(benchmark.has_required_synthetic_rows(rows))
        rows.append({"case": "tree-32", "algorithm": "MEMO_GLOBAL_FORCED_SEARCH"})
        self.assertTrue(benchmark.has_required_synthetic_rows(rows))
        rows = [row for row in rows
                if not (row["case"] == "tree-32" and row["algorithm"] == "MEMO_LOCAL")]
        self.assertFalse(benchmark.has_required_synthetic_rows(rows))

    def test_dual_direct_exact_boolean_is_strict(self):
        self.assertTrue(benchmark.parse_boolean("true"))
        self.assertFalse(benchmark.parse_boolean("FALSE"))
        with self.assertRaises(benchmark.argparse.ArgumentTypeError):
            benchmark.parse_boolean("yes")


if __name__ == "__main__":
    unittest.main()
