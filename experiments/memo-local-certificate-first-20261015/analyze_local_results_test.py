#!/usr/bin/env python3

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("analyze_local_results.py")
SPEC = importlib.util.spec_from_file_location("analyze_local_results", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LocalResultAuditTest(unittest.TestCase):
    def setUp(self):
        self.base = MODULE.load_base()

    def test_new_local_route_and_zero_direct_attempts_pass(self):
        summaries = [(Path("comparison/after-01/summary.json"), summary(
            certificate("LOCAL_CERTIFICATE_FIRST", 0, 0)))]
        result = MODULE.enforce_local_contract(self.base, summaries, {"errors": [], "valid": True})
        self.assertTrue(result["valid"])
        self.assertEqual(1, result["localRouteChecksPassed"])

    def test_new_local_direct_attempt_is_rejected_but_baseline_is_not_reinterpreted(self):
        summaries = [
            (Path("comparison/before-01/summary.json"), summary(certificate("DIRECT", 1, 1))),
            (Path("comparison/after-01/summary.json"),
             summary(certificate("LOCAL_CERTIFICATE_FIRST", 1, 1))),
        ]
        result = MODULE.enforce_local_contract(self.base, summaries, {"errors": [], "valid": True})
        self.assertFalse(result["valid"])
        self.assertEqual(1, len(result["localRouteChecks"]))
        self.assertIn("monolithic direct exact", result["errors"][0]["message"])

    def test_action_json_is_parsed_and_malformed_record_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory) / "pilot" / "new-pilot"
            log = run / "compile/logreg-w3-memo_local/coordinator.log"
            log.parent.mkdir(parents=True)
            log.write_text('[MemoLocalAction] {"action":"MESSAGE","cells":8}\n'
                           '[MemoLocalSummary] {"completedMessages":2,"untouchedBuckets":1}\n'
                           '[MemoLocalAction] {broken}\n', encoding="utf-8")
            path = run / "summary.json"
            data = {"compileRows": [{"workload": "logreg", "algorithm": "MEMO_LOCAL",
                                     "workers": 3}]}
            result = MODULE.analyze_actions(self.base, [(path, data)])
        self.assertEqual(2, result["recordsParsed"])
        self.assertEqual(1, result["actionRecordsParsed"])
        self.assertEqual(1, result["summaryRecordsParsed"])
        self.assertEqual(1, len(result["parseErrors"]))
        self.assertEqual({"MESSAGE": 1}, result["groups"][0]["actions"])
        self.assertEqual(1, result["groups"][0]["summaries"][0]["untouchedBuckets"])

    def test_action_contract_requires_bound_seed_and_consistent_terminal_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory) / "comparison" / "after-01"
            log = run / "compile/logreg-w3-memo_local/coordinator.log"
            log.parent.mkdir(parents=True)
            terminal = local_summary()
            log.write_text('[MemoLocalAction] {"kind":"INITIAL_BOUND"}\n'
                           '[MemoLocalAction] {"kind":"FIRST_FEASIBLE"}\n'
                           f'[MemoLocalSummary] {json.dumps(terminal)}\n', encoding="utf-8")
            path = run / "summary.json"
            data = {"compileRows": [{"workload": "logreg", "algorithm": "MEMO_LOCAL",
                                     "workers": 3, "status": "passed",
                                     "dualCertificates": [certificate(
                                         "LOCAL_CERTIFICATE_FIRST", 0, 0)]}]}
            actions = MODULE.analyze_actions(self.base, [(path, data)])
            result = MODULE.audit_action_contract([(path, data)], actions)
        self.assertTrue(result["valid"])
        self.assertEqual(1, result["checks"][0]["localSummaries"])

    def test_receipts_reject_a_truncated_suite_and_missing_compile_cell(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "comparison"
            run = suite / "after-01"
            run.mkdir(parents=True)
            summary_path = run / "summary.json"
            data = {"compileRows": [{"workload": "logreg", "algorithm": "MEMO_LOCAL",
                                     "status": "passed"}]}
            summary_path.write_text(json.dumps(data), encoding="utf-8")
            command = ["runner", "--workloads", "logreg,glm",
                       "--algorithms", "MEMO_LOCAL"]
            receipt = {"schema": "memo-local-certificate-first-receipts/v1",
                       "suite": "comparison", "repetitions": 1,
                       "jobs": [{"name": "after-01", "engine": "/engine",
                                 "argv": command, "returncode": 0,
                                 "summary": str(summary_path.resolve())}]}
            (suite / "receipts.json").write_text(json.dumps(receipt), encoding="utf-8")
            result = MODULE.audit_receipts(root, [(summary_path, data)], ("comparison",))
        self.assertFalse(result["valid"])
        self.assertEqual(2, result["jobsExpected"])
        self.assertEqual(2, result["compileRowsExpected"])
        self.assertTrue(any("truncated suite" in row["message"] for row in result["errors"]))
        self.assertTrue(any("compile rows are missing" in row["message"] for row in result["errors"]))


def certificate(route, attempts, solves):
    return {"status": "EPSILON_OPTIMAL", "certified": True, "route": route,
            "statistics": {"directAttempts": attempts, "directExactSolves": solves}}


def summary(value):
    return {"compileRows": [{"algorithm": "MEMO_LOCAL", "status": "passed",
                             "dualCertificates": [value]}]}


def local_summary():
    return {"variables": 3, "originalFactors": 5, "completedMessages": 1,
            "untouchedBuckets": 2, "messageTouchedVariables": 2,
            "messageTouchedFactors": 3, "candidatePreparations": 1,
            "messageMemoryRejections": 0, "memoryRejections": 0,
            "activeRegions": 0, "unknownRegions": 0, "deferredRegions": 1,
            "messageDecline": "NONE"}


if __name__ == "__main__":
    unittest.main()
