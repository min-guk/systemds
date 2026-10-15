#!/usr/bin/env python3
"""Hostile stdlib tests for analyze_results certificate and diagnostic auditing."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("analyze_results.py")
SPEC = importlib.util.spec_from_file_location("memo_dual_analyze_results", SCRIPT)
ANALYZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYZE)


def plan(cost):
    return {"cost": str(cost), "phaseNanos": {"optimization": 1_000_000}}


def exact(cost="10", source="source", workload="glm"):
    return {"algorithm": "EXACT", "status": "passed", "sourceSha256": source,
        "workload": workload, "compile": {"verified": True}, "memoPlans": [plan(cost)]}


def certificate(status="OPTIMAL", certified=True, lower="10", upper="10"):
    return {"status": status, "certified": certified, "lowerBound": lower,
        "upperBound": upper, "progression": []}


def solver(algorithm="MEMO_GLOBAL", certs=None, cost="10", source="source", workload="glm",
        status="passed", verified=True):
    return {"algorithm": algorithm, "status": status, "sourceSha256": source,
        "workload": workload, "compile": {"verified": verified}, "memoPlans": [plan(cost)],
        "dualCertificates": [certificate()] if certs is None else certs}


def summary(path, *rows):
    return (Path(path), {"compileRows": list(rows)})


class CertificateAuditTest(unittest.TestCase):
    def test_cross_summary_exact_fallback(self):
        result = ANALYZE.audit([
            summary("/artifact/baseline/exact/summary.json", exact()),
            summary("/artifact/diagnostics/memo/summary.json", solver()),
        ])
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual("cross-summary", result["certificateAudits"][0]["baseline"])

    def test_missing_certificate_rejects_passed_solver(self):
        row = solver(certs=[])
        result = ANALYZE.audit([summary("/artifact/suite/run/summary.json", exact(), row)])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "requires nonempty certificates")

    def test_uncertified_flag_rejects_passed_solver(self):
        row = solver(certs=[certificate(status="UNRESOLVED", certified=False)])
        result = ANALYZE.audit([summary("/artifact/suite/run/summary.json", exact(), row)])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "every certificate to be certified")

    def test_certified_requires_verified_compile(self):
        result = ANALYZE.audit([summary("/artifact/suite/run/summary.json",
            exact(), solver(verified=False))])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "compile.verified=true")

    def test_terminal_plan_cost_must_equal_upper_bound(self):
        result = ANALYZE.audit([summary("/artifact/suite/run/summary.json",
            exact(), solver(cost="11"))])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "plan cost differs")

    def test_epsilon_is_rejected_for_global(self):
        row = solver(certs=[certificate(status="EPSILON_OPTIMAL")])
        result = ANALYZE.audit([summary("/artifact/suite/run/summary.json", exact(), row)])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "only for a LOCAL")

    def test_missing_baseline_is_explicit_and_not_valid(self):
        result = ANALYZE.audit([summary("/artifact/diagnostics/run/summary.json", solver())])
        self.assertFalse(result["valid"])
        self.assertEqual(1, len(result["unauditedCertificates"]))
        self.assertMessage(result, "missing passed EXACT baseline")

    def test_failed_uncertified_missing_baseline_is_retained_as_unaudited(self):
        row = solver(certs=[certificate(status="RESOURCE_EXHAUSTED", certified=False)],
            status="failed", verified=False)
        result = ANALYZE.audit([summary("/artifact/heap/run/summary.json", row)])
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual(1, len(result["unauditedCertificates"]))

    def test_before_after_exact_vector_mismatch_is_rejected(self):
        result = ANALYZE.audit([
            summary("/artifact/comparison/before-01/summary.json", exact("10")),
            summary("/artifact/comparison/after-01/summary.json", exact("11")),
        ])
        self.assertFalse(result["valid"])
        self.assertMessage(result, "cost vectors disagree")

    def assertMessage(self, result, text):
        self.assertTrue(any(text in item["message"] for item in result["errors"]), result["errors"])


class FactorAnalysisTest(unittest.TestCase):
    def test_truncated_raw_factor_records_are_marked_partial(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "diagnostics" / "typed-factors"
            log = run / "compile" / "glm-w3-memo_global" / "coordinator.log"
            log.parent.mkdir(parents=True)
            records = [
                '[MemoFactorMeta] {"factors":5,"emittedFactors":3,"omittedFactors":2}',
                '[MemoFactor] {"category":"INPUT_SUPPLY","tags":[]}',
                '[MemoFactor] {"category":"INPUT_SUPPLY","tags":["shared:true"]}',
                '[MemoFactor] {"category":"NATIVE_UNARY","tags":[]}',
                '[MemoFactorEval] {"category":"INPUT_SUPPLY","evaluations":7,"nanos":11}',
            ]
            log.write_text("\n".join(records) + "\n", encoding="utf-8")
            data = {"compileRows": [{"algorithm": "MEMO_GLOBAL", "workload": "glm", "workers": 3}]}
            result = ANALYZE.factor_analysis([(run / "summary.json", data)])
            group = result["groups"][0]
            self.assertEqual("partial", group["rawFactorCountsStatus"])
            self.assertFalse(group["rawFactorCountsComplete"])
            self.assertEqual(3, group["rawFactorRecordsObserved"])
            self.assertEqual(2, group["rawFactorRecordsOmittedByEmitter"])
            self.assertEqual(1, group["sharedTrueFactorRecordsObserved"])
            self.assertIn("INPUT_SUPPLY", group["factorCategoryRecordCountsObserved"])
            self.assertEqual(1, len(result["factorTimingSuites"]))


if __name__ == "__main__":
    unittest.main()
