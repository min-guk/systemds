import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "freeze_planning_remainder_conditions.py"
SPEC = importlib.util.spec_from_file_location("freeze_planning_remainder_conditions", SCRIPT)
FREEZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FREEZER)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FreezePlanningRemainderConditionsTest(unittest.TestCase):
    def fixture(self, root, *, context_case=False, input_workers=1):
        native = root / "planning_study/native"
        metadata = native / "input_templates/w3/metadata"
        programs = native / "input_templates/w3/programs"
        metadata.mkdir(parents=True)
        programs.mkdir(parents=True)
        program = programs / "example.dml"
        program.write_text(
            'X = federated(addresses=list("worker1:1/data/X", "worker2:1/data/X", '
            '"worker3:1/data/X"), ranges=list(list(0,0),list(1,1)))\n')
        program_sha = sha(program)
        case = {"workload": "example", "workers": input_workers,
                "program": "programs/example.dml", "metadata": {"X": {"rows": 1}}}
        profile = {"cost_environment": {"SYSDS_FED_COST_NET_BW": "1"},
                   "rtt_ms": 1}
        context_cases = []
        if context_case:
            ready_case = dict(case)
            ready_case["workers"] = 3
            ready_case["program_sha256"] = program_sha
            context_cases.append(ready_case)
            case = ready_case
        context = native / "context-w3.json"
        context.write_text(json.dumps({"workers": 3, "cases": context_cases,
                                       "profiles": {"lan": profile}}))
        protocol = native / "protocol-w3.json"
        protocol.write_text(json.dumps({"workers": 3, "profiles": ["lan"]}))
        inputs = metadata / "inputs.json"
        inputs.write_text(json.dumps({"cases": [case], "profiles": {"lan": profile},
                                      "files": {"programs/example.dml": program_sha}}))
        sources = {str(path.relative_to(root)): sha(path)
                   for path in (context, protocol, inputs, program)}
        manifest = root / "unresolved.json"
        manifest.write_text(json.dumps({"records": [{
            "candidateId": "candidate-1", "discoveryId": "planning-w3:example",
            "kind": "planning-snapshot", "networkProfile": "lan",
            "placeholderCellId": "cell-1", "sourceFiles": sources, "workers": 3,
        }]}))
        return manifest

    def test_fails_closed_when_capture_context_is_missing_and_input_workers_differ(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            result = FREEZER.build(manifest, root)
            self.assertEqual(result["counts"], {
                "candidates": 1, "ready": 0, "sourceVerified": 1, "unresolved": 1})
            row = result["conditions"][0]
            self.assertEqual(row["status"], "UNRESOLVED")
            self.assertEqual(row["conditionSha256"], None)
            self.assertEqual(row["plannedCondition"], None)
            self.assertEqual(row["resolutionBlockers"], [
                "CASE_ABSENT_FROM_CAPTURE_CONTEXT",
                "INPUT_MANIFEST_WORKER_COUNT_MISMATCH",
            ])
            self.assertTrue(row["inputValidation"]["programDigestVerified"])
            self.assertTrue(row["inputValidation"]["programWorkerAuthoritiesVerified"])
            self.assertFalse(row["inputValidation"]["compileModelReady"])

    def test_freezes_only_when_both_descriptors_are_complete_and_consistent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root, context_case=True, input_workers=3)
            result = FREEZER.build(manifest, root)
            self.assertEqual(result["counts"]["ready"], 1)
            row = result["conditions"][0]
            self.assertEqual(row["status"], "READY_FOR_COMPILE_MODEL_CAPTURE")
            self.assertEqual(set(row["plannedCondition"]), {"case", "network", "workers"})
            identity = {"discoveryId": row["discoveryId"],
                        "conditionId": row["conditionId"], **row["plannedCondition"]}
            self.assertEqual(row["conditionSha256"], FREEZER.digest(identity))

            output = root / "artifact/conditions.json"
            FREEZER.publish(result, output)
            FREEZER.publish(result, output, check=True)
            output.write_text("{}\n")
            with self.assertRaisesRegex(ValueError, "artifact differs"):
                FREEZER.publish(result, output, check=True)

    def test_rejects_changed_candidate_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self.fixture(root)
            program = root / "planning_study/native/input_templates/w3/programs/example.dml"
            program.write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "source missing or changed"):
                FREEZER.build(manifest, root)


if __name__ == "__main__":
    unittest.main()
