#!/usr/bin/env python3
"""The performance gate never turns partial/invalid compile evidence into a pass."""
import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

PATH = Path(__file__).resolve().parents[1] / "evaluate_planning_performance.py"
SPEC = importlib.util.spec_from_file_location("planning_performance", PATH)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class PlanningPerformanceGateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.cell = GATE.campaign.matrix()[0]
        overlay = self.root / "overlay/SystemDS.jar"
        overlay.parent.mkdir()
        overlay.write_bytes(b"frozen engine fixture")
        self.jar = hashlib.sha256(overlay.read_bytes()).hexdigest()
        self.manifest = {
            "cells": GATE.campaign.matrix(),
            "identity": {"jar_sha256": self.jar, "timeout_seconds": {"compile": 60, "runtime": 60}},
            "measurement": {"timeout_seconds": {"compile": 60, "runtime": 60}},
        }
        self.write_manifest()

    def write_manifest(self):
        (self.root / "manifest.json").write_text(json.dumps(self.manifest))

    def write_row(self, cell=None, attempt="001", **changes):
        cell = cell or self.cell
        row = {
            "cell": cell, "phase": "compile", "status": "passed", "returncode": 0,
            "timeout_seconds": 60, "diagnostic_only": False, "cleanup_resolved": True,
            "errors": [], "jar_sha256": self.jar, "compile_seconds": 20.0,
            "receipt": {"status": "success", "actualCompileOnly": True,
                "actualPlannerCanonical": cell["planner_enum"], "runtimeProgramConstructed": True,
                "workloadExecutionStarted": False, "workloadExecutionCompleted": False,
                "observedRunNanos": 0, "executionNanos": 0, "compileNanos": 20_000_000_000,
                "planningFullInitialNanos": 19_999_999_999,
                "compilePhasesNanos": {"runtimeProgramNanos": 100},
                "plannerRuntimeAudit": {"plannedPhysicalHops": 1, "loweredPhysicalHops": 1,
                    "missingPhysicalHops": 0, "missingSynthetic": 0, "mismatches": 0,
                    "runtimeInstructionKinds": 0, "federatedDispatchKinds": 0, "workerFragmentKinds": 0}},
        }
        row.update(changes)
        path = self.root / "attempts/compile" / attempt / "result.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(row))
        return row

    def test_latest_user_target_is_twenty_seconds(self):
        self.assertEqual(20_000_000_000, GATE.TARGET_NANOS)

    def test_former_thirty_second_success_is_rejected(self):
        row = self.write_row()
        row["receipt"]["compileNanos"] = 30_000_000_000
        row["receipt"]["planningFullInitialNanos"] = 29_999_999_999
        self.write_row(**row)
        result = GATE.evaluate(self.root)
        self.assertEqual(0, result["within_target"])
        self.assertIn("compile exceeds 20 seconds", result["rejected"][0]["reasons"])

    def test_partial_evidence_is_not_completion(self):
        self.write_row()
        result = GATE.evaluate(self.root)
        self.assertFalse(result["passed"])
        self.assertEqual(1, result["within_target"])
        self.assertEqual(895, result["missing"])

    def test_all_896_at_inclusive_threshold_pass(self):
        for index, cell in enumerate(GATE.campaign.matrix()):
            self.write_row(cell, f"{index:04}")
        self.assertTrue(GATE.evaluate(self.root)["passed"])

    def test_one_nanosecond_over_target_fails(self):
        row = self.write_row()
        row["receipt"]["compileNanos"] += 1
        self.write_row(**row)
        result = GATE.evaluate(self.root)
        self.assertEqual(0, result["within_target"])
        self.assertIn("compile exceeds 20 seconds", result["rejected"][0]["reasons"])

    def test_invalid_measurements_never_count(self):
        for changes in ({"status": "failed", "returncode": 124},
                        {"cleanup_resolved": False}, {"diagnostic_only": True},
                        {"jar_sha256": "b" * 64}, {"timeout_seconds": 30},
                        {"errors": ["network invalid"]}, {"receipt": {}}):
            with self.subTest(changes=changes):
                self.write_row(**changes)
                self.assertEqual(0, GATE.evaluate(self.root)["within_target"])

    def test_latest_failure_cannot_be_hidden_by_earlier_success(self):
        self.write_row(attempt="001")
        self.write_row(attempt="002", status="failed", returncode=124)
        self.assertEqual(0, GATE.evaluate(self.root)["within_target"])

    def test_diagnostic_or_truncated_manifest_is_rejected(self):
        self.write_row()
        self.manifest["measurement"]["diagnostic_jfr"] = True
        self.write_manifest()
        self.assertTrue(GATE.evaluate(self.root)["manifest_errors"])
        self.manifest["measurement"].pop("diagnostic_jfr")
        self.manifest["cells"] = [self.cell]
        self.write_manifest()
        self.assertTrue(GATE.evaluate(self.root)["manifest_errors"])

    def test_missing_or_modified_frozen_engine_is_rejected(self):
        overlay = self.root / "overlay/SystemDS.jar"
        overlay.write_bytes(b"different engine")
        self.assertIn("frozen engine hash differs from manifest",
            GATE.evaluate(self.root)["manifest_errors"])
        overlay.unlink()
        self.assertIn("frozen engine artifact is unreadable",
            GATE.evaluate(self.root)["manifest_errors"])

    def test_engine_identity_requires_sha256_hex(self):
        self.manifest["identity"]["jar_sha256"] = "z" * 64
        self.write_manifest()
        self.assertIn("missing frozen engine identity",
            GATE.evaluate(self.root)["manifest_errors"])


if __name__ == "__main__":
    unittest.main()
