"""Synthetic evidence tests: cohort shift must not masquerade as a gate effect."""

import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("factorial_analysis", Path(__file__).with_name("analyze.py"))
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


class FactorialAnalysisTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.addCleanup(self.temp.cleanup)
		self.old = Path(self.temp.name) / "old"
		self.new = Path(self.temp.name) / "new"
		self.make_cohort(self.old, {"baseline": 11, "local_only": 10, "local": 9})
		self.make_cohort(self.new, {"dominance_local": 8 * 1.4, "support_local": 12 * 1.4, "local": 9 * 1.4})

	def make_cohort(self, root, timings):
		planned = []
		for workload in analysis.WORKLOADS:
			cell = dict(id=f"ml|{workload}|wan_mid|w3|DP-local", suite="ml", workload=workload,
				profile="wan_mid", workers=3, planner="DP-local", planner_enum="COMPILE_COST_BASED")
			for variant, duration in timings.items():
				for repetition in (1, 2, 3):
					key = f"{cell['id']}|{variant}|r{repetition}"
					row = dict(key=key, cell=cell, variant=variant, repetition=repetition)
					planned.append(dict(row))
					pruning = dict.fromkeys(analysis.COUNTERS, 0)
					pruning.update(variant=variant, planFingerprint="a" * 64, lower="1", upper="1", gap="0",
						objectiveBits="4607182418800017408", initialUpperBits="4607182418800017408",
						globalInitialUpperBits="0", globalConsidered=0, globalPruned=0,
						globalFactors=0, globalPrepNanos=0, globalOutcome="DISABLED", stop="EXACT")
					probe = dict(status="success", schema="matrix-campaign-probe-v1",
						actualCompileOnly=True, configuredCompileOnly=True, runtimeProgramConstructed=True,
						runtimeAuditEnabled=True, actualPlannerCanonical="COMPILE_COST_BASED",
						candidateE2E=dict(calls=1, exactPhaseCalls=1, optimizerNanos=round(duration * 1e9)),
						workloadExecutionStarted=False, workloadExecutionCompleted=False,
						executionNanos=0, observedRunNanos=0, planningFullInitialNanos=round(duration * 1e9),
						analysisNanos=round(duration * 1e9), plannerRuntimeAudit=dict(mismatches=0,
							missingPhysicalHops=0, missingSynthetic=0, plannedPhysicalHops=1, loweredPhysicalHops=1))
					row.update(status="passed", returncode=0, cleanup_resolved=True, errors=[],
						receipt=probe, pruning=[pruning])
					row.update({field: round(duration * 1e9) / 1e9 for field in analysis.TIMINGS})
					attempt = root / "attempts" / key
					(attempt / "tmp").mkdir(parents=True)
					(attempt / "tmp/cell.dml").write_text("X = 1;\n")
					(attempt / "render-contract.json").write_text(json.dumps(dict(cell=cell,
						source="X = 1;\n", inputs=[], dependencies=[])))
					(attempt / "result.json").write_text(json.dumps(row))
		identity = dict.fromkeys(analysis.SHARED_IDENTITY, "same synthetic fixture")
		identity.update(samples=planned, jar_sha256=root.name, source_sha256={
			"src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/PruningAblation.java": root.name})
		measurement = dict(full_production_compile=True, compile_only=True, fresh_jvm=True,
			concurrent_timed_samples=1, workers_started=0, runtime_lowering_audit=True,
			network="none", workload_execution=False, repetitions=3)
		(root / "ablation.json").write_text(json.dumps(dict(identity=identity, measurement=measurement)))

	def test_main_effects_cancel_common_cohort_shift_and_direct_effects_use_new_control(self):
		report = analysis.analyze(self.old, self.new)
		self.assertEqual(150, report["totals"]["selected"])
		self.assertEqual(180, report["totals"]["validated_raw"])
		effects = analysis.effects(report, set(analysis.WORKLOADS) - {"steplm"})
		for metric in analysis.TIMINGS:
			self.assertAlmostEqual(.75, effects["D_given_S"][metric + "_geomean_ratio"])
			self.assertAlmostEqual(1.125, effects["S_given_D"][metric + "_geomean_ratio"])
			self.assertAlmostEqual(1.4, effects["DSL_control_shift"][metric + "_geomean_ratio"])
			self.assertAlmostEqual(1.12, effects["D_without_S_raw"][metric + "_geomean_ratio"])
			self.assertAlmostEqual(math.sqrt(.6), effects["balanced_main_effects"][metric + "_D_main_ratio"])
			self.assertAlmostEqual(math.sqrt(1.35), effects["balanced_main_effects"][metric + "_S_main_ratio"])
		self.assertEqual(27, effects["D_given_S"]["n"])
		self.assertEqual(27, effects["D_given_S"]["objective_equal"])
		self.assertEqual(3, analysis.effects(report, {"steplm"})["D_given_S"]["n"])

	def test_missing_sample_is_rejected_instead_of_filtering_to_successful_subset(self):
		next((self.new / "attempts").glob("*/result.json")).unlink()
		with self.assertRaises(analysis.receipt.AnalysisError):
			analysis.analyze(self.old, self.new)

	def test_changed_profile_cannot_be_pooled_with_reused_results(self):
		path = self.new / "ablation.json"
		manifest = json.loads(path.read_text())
		manifest["identity"]["cost_profile_sha256"] = "different fixture"
		path.write_text(json.dumps(manifest))
		with self.assertRaisesRegex(analysis.receipt.AnalysisError, "configuration differs"):
			analysis.analyze(self.old, self.new)

	def test_cost_change_retains_regression_direction_and_magnitude(self):
		for path in (self.new / "attempts").glob("*|local|*/result.json"):
			row = json.loads(path.read_text())
			row["pruning"][0].update(upper="2", objectiveBits="4611686018427387904")
			path.write_text(json.dumps(row))
		report = analysis.analyze(self.old, self.new)
		effects = analysis.effects(report, set(analysis.WORKLOADS) - {"steplm"})
		self.assertEqual(0, effects["D_given_S"]["objective_equal"])
		self.assertAlmostEqual(2, effects["D_given_S"]["cost_geomean_ratio"])
		self.assertAlmostEqual(100, effects["D_given_S"]["cost_change_pct"])


if __name__ == "__main__":
	unittest.main()
