#!/usr/bin/env python3
"""Lightweight contract tests for the pruning-ablation analyzer."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).parents[1] / "analyze_pruning_ablation.py"
SPEC = importlib.util.spec_from_file_location("analyze_pruning_ablation", MODULE_PATH)
assert SPEC and SPEC.loader
ANALYZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYZER)


def receipt(upper: float, reduced_values: int) -> dict[str, object]:
	values: dict[str, object] = {
		"upper": str(upper), "lower": str(upper), "gap": "0.0",
		"rawValues": 100, "reducedValues": reduced_values,
		"rawCells": 200, "reducedCells": 100,
		"childEvaluations": 20, "fullChildEvaluations": 30,
		"infeasibleCuts": 2, "costCuts": 3, "globalConsidered": 0,
		"globalPruned": 0, "globalFactors": 0, "globalPrepNanos": 0,
		"assignments": 10, "retainedSlots": 5, "merges": 4,
		"objectiveBits": "1", "planFingerprint": "plan",
		"stop": "EXACT", "globalOutcome": "DISABLED",
	}
	return values


def passed_sample(workload: str, variant: str, repetition: int, upper: float,
		reduced_values: int, cohort: str = "study", seconds: float = 1.0) -> dict[str, object]:
	return {
		"key": f"{workload}-{variant}-r{repetition}-{cohort}",
		"cell": {"workload": workload}, "variant": variant,
		"repetition": repetition, "status": "passed", "_cohort": cohort,
		"_logical_identity": ANALYZER.logical_identity(cohort, workload, variant, repetition),
		"full_initial_planning_seconds": seconds,
		"optimizer_seconds": seconds / 2,
		"analysis_seconds": seconds / 4,
		"compile_seconds": seconds / 8,
		"pruning": [receipt(upper, reduced_values)],
	}


class PruningAblationAnalysisTest(unittest.TestCase):
	def test_global_cost_increase_is_signed_and_final_pruning_uses_reduced_values(self) -> None:
		samples = []
		for variant in ANALYZER.VARIANTS:
			for repetition in ANALYZER.REPETITIONS:
				upper = 10947.47267402624 if variant == "local" else 11206.62204829849
				reduced = 80 if variant == "local" else 65
				seconds = float(repetition) * (0.5 if variant == "global" else 1.0)
				samples.append(passed_sample("gnmf", variant, repetition, upper, reduced, seconds=seconds))
		rows = ANALYZER.aggregate_rows(samples, samples)
		global_row = next(row for row in rows if row["variant"] == "global")
		self.assertGreater(global_row["cost_change_vs_local_pct"], 2.3)
		self.assertEqual(15, global_row["extra_final_reduced_values_vs_local"])
		self.assertEqual([0.5, 0.5, 0.5], [item["ratio"] for item in
			global_row["full_initial_planning_seconds_paired_ratio_vs_local"]])

	def test_effect_uses_paired_repetitions_and_labels_ratio_of_medians_descriptive(self) -> None:
		baseline_times = (1.0, 100.0, 101.0)
		variant_times = (1.0, 2.0, 100.0)
		samples = []
		for variant in ANALYZER.VARIANTS:
			for repetition in ANALYZER.REPETITIONS:
				times = variant_times if variant == "dominance" else baseline_times
				samples.append(passed_sample("gnmf", variant, repetition, 1.0, 10,
					seconds=times[repetition - 1]))
		rows = ANALYZER.aggregate_rows(samples, samples)
		effect = ANALYZER.aggregate_effects(rows, ["gnmf"])["dominance"]
		expected_paired = ANALYZER.geometric_mean((1.0 / 1.0, 2.0 / 100.0, 100.0 / 101.0))
		self.assertAlmostEqual(expected_paired,
			effect["full_initial_planning_seconds_paired_ratio_vs_baseline_geomean"])
		self.assertAlmostEqual(2.0 / 100.0,
			effect["full_initial_planning_seconds_ratio_of_medians_vs_baseline_geomean"])
		self.assertNotAlmostEqual(
			effect["full_initial_planning_seconds_paired_ratio_vs_baseline_geomean"],
			effect["full_initial_planning_seconds_ratio_of_medians_vs_baseline_geomean"])

	def test_zero_cost_denominator_is_rejected(self) -> None:
		samples = [
			passed_sample("gnmf", variant, repetition, 0.0 if variant == "baseline" else 1.0, 10)
			for variant in ANALYZER.VARIANTS for repetition in ANALYZER.REPETITIONS
		]
		with self.assertRaisesRegex(ANALYZER.AnalysisError, "positive and finite"):
			ANALYZER.aggregate_rows(samples, samples)

	def test_lineage_requires_exact_cell_variant_and_repetition(self) -> None:
		key = "cell|local|r1"
		original = passed_sample("gnmf", "local", 1, 1.0, 10)
		original.update({"key": key, "status": "failed", "cell": {"workload": "gnmf", "workers": 3}})
		study = {"results": [original]}
		retry = {"key": key, "cell": dict(original["cell"]), "variant": "local", "repetition": 1}
		ANALYZER.validate_recovery_lineage(study, {"planned_by_key": {key: retry}})
		changed = dict(retry, cell={"workload": "gnmf", "workers": 2})
		with self.assertRaisesRegex(ANALYZER.AnalysisError, "changes original cell"):
			ANALYZER.validate_recovery_lineage(study, {"planned_by_key": {key: changed}})

		step_key = "step|local|r1"
		step = passed_sample("steplm", "local", 1, 1.0, 10)
		step.update({"key": step_key, "status": "failed",
			"cell": {"workload": "steplm", "workers": 3, "profile": "wan_mid"}})
		compatible = {"key": step_key, "cell": dict(step["cell"]), "variant": "local", "repetition": 1}
		ANALYZER.validate_compatibility_lineage({"results": [step]}, {"planned_by_key": {step_key: compatible}})
		changed_compatible = dict(compatible, cell={**step["cell"], "profile": "wan_high"})
		with self.assertRaisesRegex(ANALYZER.AnalysisError, "changes original cell"):
			ANALYZER.validate_compatibility_lineage({"results": [step]},
				{"planned_by_key": {step_key: changed_compatible}})

	def test_final_selection_accepts_failed_then_successful_retry_but_rejects_two_successes(self) -> None:
		workloads = [f"w{index}" for index in range(9)]
		study: list[dict[str, object]] = []
		recovery: list[dict[str, object]] = []
		failed_nonstep = 0
		for workload in workloads:
			for variant in ANALYZER.VARIANTS:
				for repetition in ANALYZER.REPETITIONS:
					sample = passed_sample(workload, variant, repetition, 1.0, 10)
					if failed_nonstep < 40:
						sample["status"] = "failed"
						retry = passed_sample(workload, variant, repetition, 1.0, 10, cohort="recovery")
						recovery.append(retry)
						failed_nonstep += 1
					study.append(sample)
		for variant in ANALYZER.VARIANTS:
			for repetition in ANALYZER.REPETITIONS:
				sample = passed_sample("steplm", variant, repetition, 1.0, 10)
				sample["status"] = "failed"
				study.append(sample)
		compatibility = [
			passed_sample("steplm", variant, repetition, 1.0, 10, cohort="compatibility")
			for variant in ANALYZER.VARIANTS for repetition in ANALYZER.REPETITIONS
		]
		cohorts = ({"results": study}, {"results": recovery}, {"results": compatibility})
		selected, failures, nonstep = ANALYZER.select_final_samples(cohorts)
		self.assertEqual(150, len(selected))
		self.assertEqual(55, len(failures))
		self.assertEqual(9, len(nonstep))
		duplicate = dict(recovery[0])
		duplicate["key"] = duplicate["key"] + "-duplicate"
		with self.assertRaisesRegex(ANALYZER.AnalysisError, "successful attempts"):
			ANALYZER.select_final_samples(({"results": study}, {"results": recovery + [duplicate]},
				{"results": compatibility}))


if __name__ == "__main__":
	unittest.main()
