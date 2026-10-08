#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


HERE = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("evaluate_planning_goal", HERE / "evaluate_planning_goal.py")
GOAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GOAL)


class PlanningGoalEvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.jar = "a" * 64
        self.generation = 0
        self.regression = self.root / "regression.json"
        self._write(self.regression, {"status": "passed", "jarSha256": self.jar,
                                      "sourceHashMismatches": 0, "frozenClassHashMismatches": 0})

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def _write(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, sort_keys=True))

    def _attempt(self, workload, number, nanos=20_000_000_000):
        attempt = self.root / f"{workload}-{number}"
        attempt.mkdir()
        cell_id = GOAL.WORKLOAD_CELLS[workload]
        receipt = {"status": "success", "workloadExecutionStarted": True,
                   "workloadExecutionCompleted": True, "planningFullInitialNanos": nanos,
                   "scriptSha256": f"attempt-specific-{number}"}
        result = {"phase": "runtime", "status": "passed", "errors": [],
                  "attempt": str(number), "cell": {"id": cell_id, "workload": workload},
                  "compile_seconds": 21.0, "runtime_seconds": 1.0,
                  "full_initial_planning_seconds": nanos / 1e9, "jar_sha256": self.jar,
                  "receipt": receipt, "comparison": {"receipt": {"passed": True}}}
        self._write(attempt / "result.json", result)
        self._write(attempt / "render-contract.json", {
            "template": {"sha256": f"template-{workload}"},
            "inputs": [{"role": "X", "rows": 50000, "cols": 128, "nnz": 6400000,
                        "privacy": "private-aggregate", "metadata_sha256": "x",
                        "shards": [{"blocks": [{"path": "x/0", "bytes": 1, "sha256": "xb"}]}]},
                       {"role": "Y", "rows": 50000, "cols": 1, "nnz": 50000,
                        "privacy": "public", "metadata_sha256": "y",
                        "shards": [{"blocks": [{"path": "y/0", "bytes": 1, "sha256": "yb"}]}]}],
            "topology": {"workers": ["so006"]}, "seeds": {"SYSTEMDS_SEED": 1011081480},
            "attemptOutput": f"ignored-{number}"})
        self._write(attempt / "lifecycle.json", {
            "container": dict(GOAL.EXPECTED_CONTAINER),
            "coordinator": {"environment": {
                "SYSTEMDS_STANDALONE_OPTS": " ".join(GOAL.EXPECTED_JVM),
                "privacyConstraints": "private-aggregate", "DOCKER_NUM_WORKERS": "1"}}})
        self._write(attempt / "cost-profile.json", {
            "profile_sha256": "profile", "_runtime": {"source_sha256": "profile-source"}})
        (attempt / "coordinator.log").write_text(
            "SEARCH_SPACE_LIVE|seq=9|terminal=true|LivePhase[phase=ANALYSIS, completedCalls=1, activeCount=0]\n")
        return {"path": str(attempt / "result.json"), "eligibleSuccess": True,
                "exclusionReasons": []}

    def _manifest(self, attempts, regression_sha=None):
        report = self.root / "report.json"
        self._write(report, {"pairs": [{"variants": {"candidate": {"allAttempts": attempts}}}]})
        manifest = self.root / "evidence-list.json"
        self._write(manifest, {
            "schema": "cofee-planning-goal-evidence/v1",
            "regressionReport": {"path": str(self.regression),
                                 "sha256": regression_sha or GOAL.sha256(self.regression)},
            "evidence": [{"report": str(report)}]})
        return manifest

    def _six(self, nanos=20_000_000_000):
        self.generation += 1
        return [self._attempt(workload, self.generation * 10 + index, nanos)
                for workload in ("logreg", "glm") for index in range(3)]

    def test_accepts_three_clean_runs_per_workload_at_threshold(self):
        result = GOAL.evaluate(self._manifest(self._six()))
        self.assertTrue(result["passed"], result["errors"])

    def test_empty_evidence_is_a_valid_concrete_failure(self):
        manifest = self._manifest([])
        result = GOAL.evaluate(manifest)
        self.assertFalse(result["passed"])
        self.assertIn("logreg requires exactly 3 clean successes; found 0", result["errors"])

    def test_rejects_excluded_cached_over_threshold_and_wrong_workload(self):
        mutations = ("excluded", "cached", "threshold", "workload")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                attempts = self._six()
                first = attempts[0]
                result_path = Path(first["path"])
                row = json.loads(result_path.read_text())
                if mutation == "excluded":
                    self._write(result_path.parent / "superseded-validation.json",
                                {"reason": "newer engine"})
                elif mutation == "cached":
                    row["receipt"]["analysisCacheHit"] = True
                    self._write(result_path, row)
                elif mutation == "threshold":
                    row["receipt"]["planningFullInitialNanos"] += 1
                    row["full_initial_planning_seconds"] = row["receipt"]["planningFullInitialNanos"] / 1e9
                    self._write(result_path, row)
                else:
                    row["cell"]["id"] = "ml|step-lm|lan|w1|DP-local"
                    self._write(result_path, row)
                self.assertFalse(GOAL.evaluate(self._manifest(attempts))["passed"])

    def test_rejects_missing_timer_and_regression_hash_or_engine_mismatch(self):
        attempts = self._six()
        row_path = Path(attempts[0]["path"])
        row = json.loads(row_path.read_text())
        del row["receipt"]["planningFullInitialNanos"]
        self._write(row_path, row)
        self.assertFalse(GOAL.evaluate(self._manifest(attempts))["passed"])

        attempts = self._six()
        bad_hash = GOAL.evaluate(self._manifest(attempts, "0" * 64))
        self.assertIn("regression report SHA256 mismatch", bad_hash["errors"])
        regression = json.loads(self.regression.read_text())
        regression["jarSha256"] = "b" * 64
        self._write(self.regression, regression)
        mismatch = GOAL.evaluate(self._manifest(attempts))
        self.assertIn("regression report engine does not match runtime evidence", mismatch["errors"])

    def test_rejects_malformed_manifest_and_changed_input_contract(self):
        malformed = self.root / "malformed.json"
        malformed.write_text("[]")
        self.assertFalse(GOAL.evaluate(malformed)["passed"])

        attempts = self._six()
        render = Path(attempts[0]["path"]).parent / "render-contract.json"
        value = json.loads(render.read_text())
        value["inputs"][0]["metadata_sha256"] = "changed"
        self._write(render, value)
        result = GOAL.evaluate(self._manifest(attempts))
        self.assertIn("logreg runs differ in inputContractSha256", result["errors"])

    def test_rejects_shared_null_pinned_hashes(self):
        cases = ("profile", "template", "input")
        for case in cases:
            with self.subTest(case=case):
                attempts = self._six()
                for summary in attempts:
                    attempt = Path(summary["path"]).parent
                    if case == "profile":
                        profile = json.loads((attempt / "cost-profile.json").read_text())
                        profile["profile_sha256"] = None
                        self._write(attempt / "cost-profile.json", profile)
                    else:
                        render = json.loads((attempt / "render-contract.json").read_text())
                        if case == "template":
                            render["template"]["sha256"] = None
                        else:
                            render["inputs"][0]["metadata_sha256"] = None
                        self._write(attempt / "render-contract.json", render)
                self.assertFalse(GOAL.evaluate(self._manifest(attempts))["passed"])

    def test_rejects_reusing_one_success_as_three_runs(self):
        logreg = self._attempt("logreg", 1)
        glm = self._attempt("glm", 1)
        result = GOAL.evaluate(self._manifest([logreg] * 3 + [glm] * 3))
        self.assertFalse(result["passed"])
        self.assertIn("evidence reuses an attempt path; three distinct runs are required", result["errors"])


if __name__ == "__main__":
    unittest.main()
