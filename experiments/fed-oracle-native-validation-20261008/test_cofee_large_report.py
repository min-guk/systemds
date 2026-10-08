import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("cofee_large_report.py")
SPEC = importlib.util.spec_from_file_location("cofee_large_report", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


def successful_result(cell_id, fingerprint, output_hash):
    phases = {"analysisNanos": 2_000_000_000, "modelNanos": 300_000_000,
              "costSurfaceNanos": 200_000_000, "optimizerNanos": 100_000_000,
              "totalNanos": 3_000_000_000}
    return {"phase": "runtime", "status": "passed", "errors": [], "attempt": "001",
            "cell": {"id": cell_id}, "compile_seconds": 4.0, "runtime_seconds": 5.0,
            "full_initial_planning_seconds": 3.5,
            "receipt": {"status": "success", "workloadExecutionStarted": True,
                "workloadExecutionCompleted": True, "candidateE2E": phases,
                "initialSelectionFingerprint": fingerprint,
                "finalSelectionFingerprint": fingerprint,
                "plannerRuntimeAudit": {"plan": fingerprint, "mismatches": 0}},
            "comparison": {"receipt": {"passed": True, "maxError": 0}},
            "output_identity": {"result": {"sha256": output_hash}}}


def create_variant(campaign, name, result=None):
    root = campaign / name
    root.mkdir(parents=True)
    cell = "ml|logreg|lan|w1|DP-local"
    dump(root / "runtime-selection.json", {"selected_cell_ids": [cell]})
    dump(root / "manifest.json", {"schema": "campaign/v1", "identity": {"engine": name}})
    if result is not None:
        attempt = root / "attempts/runtime/001"
        dump(attempt / "result.json", result)
        dump(attempt / "receipt.json", result.get("receipt"))
        dump(attempt / "comparison.json", result.get("comparison"))
        dump(attempt / "cost-profile.json", {"profile_sha256": "a" * 64,
            "identity": {"resources": {"memory": "24g"}, "threads": 8},
            "_runtime": {"source": "replay", "source_sha256": "b" * 64}})
        dump(attempt / "so007-container-health.json", {
            "memory_cgroup_raw": "FILE=/sys/fs/cgroup/memory.peak\n12345\n"})
    return root


class CofeeLargeReportTest(unittest.TestCase):
    def test_passed_pair_reports_timings_resources_peak_and_equivalence(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            cell = "ml|logreg|lan|w1|DP-local"
            create_variant(campaign, "baseline-logreg-pair0", successful_result(cell, "f" * 64, "o" * 64))
            create_variant(campaign, "candidate-logreg-pair0", successful_result(cell, "f" * 64, "o" * 64))

            report = MODULE.build_report(campaign)
            pair = report["pairs"][0]
            self.assertEqual("passed", pair["variants"]["baseline"]["status"])
            attempt = pair["variants"]["candidate"]["successfulAttempts"][0]
            self.assertEqual(2.0, attempt["timing"]["analysisSeconds"])
            self.assertEqual(12345, attempt["peakMemory"]["maxContainerPeakBytes"])
            self.assertEqual("a" * 64, attempt["profile"]["profileId"])
            self.assertIs(True, pair["equivalence"]["allNumericalOutputsEqual"])
            self.assertIs(True, pair["equivalence"]["allFinalPlansEqual"])
            self.assertIsNone(pair["equivalence"]["allSelectedReceiptsEqual"])
            self.assertIn("does not emit", pair["equivalence"]["rows"][0]
                          ["selectedReceipts"]["reason"])
            self.assertIs(True, pair["equivalence"]["profileEquality"]["profileId"])
            self.assertEqual(8.0, report["summary"]["successfulAttemptTimingTotals"]
                             ["compilationElapsedSeconds"])

    def test_incomplete_failed_diagnostic_and_calibration_never_count_as_success_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            cell = "ml|logreg|lan|w1|DP-local"
            baseline = create_variant(campaign, "baseline-logreg-pair0")
            diagnostic = successful_result(cell, "f" * 64, "o" * 64)
            diagnostic["diagnostic_only"] = True
            dump(baseline / "attempts/runtime/001/result.json", diagnostic)
            failed = successful_result(cell, "f" * 64, "o" * 64)
            failed.update({"status": "failed", "errors": ["timeout"]})
            create_variant(campaign, "candidate-logreg-pair0", failed)
            calibration = successful_result(cell, "f" * 64, "o" * 64)
            calibration["phase"] = "calibration"
            dump(campaign / "candidate-logreg-pair0/attempts/runtime/002/result.json", calibration)

            report = MODULE.build_report(campaign)
            pair = report["pairs"][0]
            self.assertEqual("incomplete", pair["variants"]["baseline"]["status"])
            self.assertEqual("failed", pair["variants"]["candidate"]["status"])
            self.assertEqual({}, report["summary"]["successfulAttemptTimingTotals"])
            self.assertFalse(pair["equivalence"]["comparable"])

    def test_diagnostic_conversion_marker_excludes_an_otherwise_successful_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            cell = "ml|logreg|lan|w1|DP-local"
            root = create_variant(campaign, "baseline-logreg-pair0",
                                  successful_result(cell, "f" * 64, "o" * 64))
            dump(root / "attempts/runtime/001/diagnostic-conversion.json", {})

            report = MODULE.build_report(campaign)
            variant = report["pairs"][0]["variants"]["baseline"]
            self.assertEqual("incomplete", variant["status"])
            self.assertEqual([], variant["successfulAttempts"])
            self.assertEqual({}, report["summary"]["successfulAttemptTimingTotals"])
            attempt = MODULE._summarize_attempt(
                root / "attempts/runtime/001/result.json",
                successful_result(cell, "f" * 64, "o" * 64))
            self.assertEqual("diagnostic", attempt["kind"])
            self.assertIn("attempt converted to diagnostic execution", attempt["exclusionReasons"])

    def test_superseded_validation_never_contributes_successful_timing(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            cell = "ml|logreg|lan|w1|DP-local"
            row = successful_result(cell, "f" * 64, "o" * 64)
            root = create_variant(campaign, "candidate-logreg-pair0", row)
            dump(root / "attempts/runtime/001/superseded-validation.json", {})
            report = MODULE.build_report(campaign)
            self.assertEqual({}, report["summary"]["successfulAttemptTimingTotals"])
            attempt = MODULE._summarize_attempt(root / "attempts/runtime/001/result.json", row)
            self.assertEqual("superseded", attempt["kind"])
            self.assertFalse(attempt["eligibleSuccess"])
            self.assertIsNone(attempt["timing"])

    def test_malformed_result_marks_variant_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            root = create_variant(campaign, "baseline-logreg-pair0")
            path = root / "attempts/runtime/001/result.json"
            path.parent.mkdir(parents=True)
            path.write_text("not json")

            report = MODULE.build_report(campaign)
            baseline = report["pairs"][0]["variants"]["baseline"]
            self.assertEqual("invalid", baseline["status"])
            self.assertEqual(1, len(baseline["malformedResults"]))
            self.assertEqual({}, report["summary"]["successfulAttemptTimingTotals"])

    def test_nonfinite_and_boolean_elapsed_values_are_not_success_timings(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            cell = "ml|logreg|lan|w1|DP-local"
            bad = successful_result(cell, "f" * 64, "o" * 64)
            bad["compile_seconds"] = True
            bad["runtime_seconds"] = float("inf")
            create_variant(campaign, "baseline-logreg-pair0", bad)

            report = MODULE.build_report(campaign)
            baseline = report["pairs"][0]["variants"]["baseline"]
            self.assertEqual("failed", baseline["status"])
            self.assertEqual([], baseline["successfulAttempts"])
            self.assertEqual({}, report["summary"]["successfulAttemptTimingTotals"])

    def test_selected_cell_ids_must_match_before_pairing(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = Path(tmp)
            left = "ml|logreg|lan|w1|DP-local"
            right = "ml|glm|lan|w1|DP-local"
            create_variant(campaign, "baseline-logreg-pair0", successful_result(left, "f" * 64, "o" * 64))
            candidate = create_variant(campaign, "candidate-logreg-pair0",
                                       successful_result(right, "f" * 64, "o" * 64))
            dump(candidate / "runtime-selection.json", {"selected_cell_ids": [right]})

            report = MODULE.build_report(campaign)
            equivalence = report["pairs"][0]["equivalence"]
            self.assertFalse(equivalence["comparable"])
            self.assertEqual("selectedCellIds differ", equivalence["reason"])


if __name__ == "__main__":
    unittest.main()
