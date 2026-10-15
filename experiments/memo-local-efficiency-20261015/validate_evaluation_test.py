#!/usr/bin/env python3

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("validate_evaluation.py")
SPEC = importlib.util.spec_from_file_location("validate_evaluation", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EvaluationValidationTest(unittest.TestCase):
    def test_empty_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result = MODULE.audit_execution(Path(directory), [], partial=True)
        self.assertFalse(result["valid"])
        self.assertEqual(0, result["jobs"])
        self.assertTrue(any("no summary" in error["message"] for error in result["errors"]))
        self.assertTrue(any("no receipt" in error["message"] for error in result["errors"]))

    def test_partial_discovers_pilot_below_experiment_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = add_suite(root, "pilot", [("new-pilot", rows(3, 3, 3), "false")])
            result = MODULE.audit_execution(root, summaries, partial=True)
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual({"pilot": 1}, result["observedSuites"])

    def test_nonzero_harness_return_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = add_suite(root, "pilot", [("new-pilot", rows(3, 3, 3), "false")])
            receipt_path = root / "pilot/receipts.json"
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            receipt["jobs"][0]["returncode"] = 9
            receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
            result = MODULE.audit_execution(root, summaries, partial=True)
        self.assertFalse(result["valid"])
        self.assertTrue(any("returncode" in error["message"] for error in result["errors"]))

    def test_incomplete_summary_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = add_suite(root, "pilot", [("new-pilot", rows(3, 3, 3), "false")])
            summaries[0][1]["complete"] = False
            result = MODULE.audit_execution(root, summaries, partial=True)
        self.assertFalse(result["valid"])
        self.assertTrue(any("summary.complete" in error["message"] for error in result["errors"]))

    def test_seed_mode_must_match_suite_and_ablation_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = add_suite(root, "pilot", [("new-pilot", rows(3, 3, 3), "true")])
            result = MODULE.audit_execution(root, summaries, partial=True)
        self.assertFalse(result["valid"])
        self.assertTrue(any("seed mode" in error["message"] for error in result["errors"]))

    def test_complete_final_matrix_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = final_matrix(root)
            result = MODULE.audit_execution(root, summaries, partial=False)
        self.assertTrue(result["valid"], result["errors"])
        self.assertEqual(MODULE.FINAL_COUNTS,
                         {key: result[key] for key in MODULE.FINAL_COUNTS})
        self.assertEqual(2, result["memoryJobs"])
        self.assertEqual(12, result["memoryCompileRows"])

    def test_final_runtime_synthetic_and_memory_guards_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = final_matrix(root)
            runtime = next(data for path, data in summaries if path.parent.parent.name == "runtime")
            runtime["runtimeRows"] = [{"status": "passed", "returncode": 0, "tests": 0}]
            runtime["synthetic"]["legacyRegionalCompared"] = False
            runtime["synthetic"]["legacyRegional"] = []
            memory = next(data for path, data in summaries
                          if path.parent.parent.name == "memory")
            memory["compileRows"].pop()
            result = MODULE.audit_execution(root, summaries, partial=False)
        self.assertFalse(result["valid"])
        messages = [error["message"] for error in result["errors"]]
        self.assertIn("runtime row must report a positive test count", messages)
        self.assertIn("synthetic comparison must include nonempty legacy Regional", messages)
        self.assertIn("memory suite must contain 2 jobs and 12 rows", messages)

    def test_synthetic_coverage_ablation_messages_and_unique_heaps_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            summaries = final_matrix(root)
            runtime = next(data for path, data in summaries if path.parent.parent.name == "runtime")
            runtime["synthetic"]["rows"].pop()
            ablation_receipt = root / "ablation/receipts.json"
            ablation = json.loads(ablation_receipt.read_text(encoding="utf-8"))
            argv = ablation["jobs"][0]["argv"]
            argv[argv.index("--local-messages") + 1] = "true" if "messages-off" in \
                ablation["jobs"][0]["name"] else "false"
            ablation_receipt.write_text(json.dumps(ablation), encoding="utf-8")
            memory_receipt = root / "memory/receipts.json"
            memory = json.loads(memory_receipt.read_text(encoding="utf-8"))
            second = memory["jobs"][1]["argv"]
            second[second.index("--heap-mib") + 1] = "1024"
            memory_receipt.write_text(json.dumps(memory), encoding="utf-8")
            result = MODULE.audit_execution(root, summaries, partial=False)
        self.assertFalse(result["valid"])
        messages = [error["message"] for error in result["errors"]]
        self.assertIn("synthetic case/algorithm coverage differs", messages)
        self.assertIn("ablation message mode does not match job name", messages)
        self.assertIn("memory suite must contain unique 1024 and 1536 MiB jobs", messages)


def final_matrix(root):
    summaries = []
    comparison = []
    for repeat in range(1, 8):
        comparison.append((f"before-{repeat:02d}", rows(3, 3, 3), "false"))
        comparison.append((f"after-{repeat:02d}", rows(3, 3, 3), "false"))
    summaries.extend(add_suite(root, "comparison", comparison, repetitions=7))

    ablation = []
    for repeat in range(1, 4):
        for messages in ("off", "on"):
            for seed in ("off", "on"):
                ablation.append((f"messages-{messages}-seed-{seed}-{repeat:02d}",
                                 rows(1, 1, 0), "true" if seed == "on" else "false"))
    summaries.extend(add_suite(root, "ablation", ablation, repetitions=3))

    runtime_rows = rows(1, 1, 1)
    summaries.extend(add_suite(root, "runtime",
                               [("runtime-and-regional", runtime_rows, "false")]))
    runtime = summaries[-1][1]
    runtime["runtimeRows"] = [{"status": "passed", "returncode": 0, "tests": 18}]
    runtime["synthetic"] = {"status": "passed", "returncode": 0,
                            "legacyRegionalCompared": True, "legacyRegional": [{"case": "regional"}],
                            "rows": synthetic_rows()}

    memory = [("heap-1024", rows(3, 3, 0), "false"),
              ("heap-1536", rows(3, 3, 0), "false")]
    summaries.extend(add_suite(root, "memory", memory))
    return summaries


def rows(exact, local, global_count):
    result = []
    for algorithm, count, certificate in (("EXACT", exact, False),
                                           ("MEMO_LOCAL", local, True),
                                           ("MEMO_GLOBAL", global_count, True)):
        for index in range(count):
            result.append({"workload": f"workload-{index}", "algorithm": algorithm,
                           "status": "passed", "returncode": 0,
                           "dualCertificates": [{}] if certificate else []})
    return result


def synthetic_rows():
    result = [{"case": case, "algorithm": algorithm}
              for case in MODULE.SYNTHETIC_CASES
              for algorithm in MODULE.SYNTHETIC_CORE_ALGORITHMS]
    result.extend({"case": case, "algorithm": algorithm}
                  for case in MODULE.SYNTHETIC_EXTRA_CASES
                  for algorithm in MODULE.SYNTHETIC_EXTRA_ALGORITHMS)
    return result


def add_suite(root, suite, jobs, repetitions=1):
    directory = root / suite
    directory.mkdir(parents=True)
    summaries = []
    receipt_jobs = []
    for name, compile_rows, seed in jobs:
        summary_path = directory / name / "summary.json"
        summary_path.parent.mkdir()
        data = {"complete": True, "compileRows": compile_rows,
                "runtimeRows": [], "synthetic": None}
        summary_path.write_text(json.dumps(data), encoding="utf-8")
        summaries.append((summary_path, data))
        argv = ["runner", "--local-improve-seed", seed]
        if suite == "ablation":
            argv.extend(["--local-messages", "true" if "messages-on" in name else "false"])
        if suite == "memory":
            argv.extend(["--heap-mib", name.removeprefix("heap-")])
        receipt_jobs.append({"name": name, "returncode": 0, "summary": str(summary_path.resolve()),
                             "argv": argv})
    receipt = {"schema": "memo-local-certificate-first-receipts/v1", "suite": suite,
               "repetitions": repetitions, "jobs": receipt_jobs}
    (directory / "receipts.json").write_text(json.dumps(receipt), encoding="utf-8")
    return summaries


if __name__ == "__main__":
    unittest.main()
