import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "freeze_microbench_conditions.py"
SPEC = importlib.util.spec_from_file_location("freeze_microbench_conditions", SCRIPT)
FREEZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FREEZER)


GENERATOR = '''\
TARGET_COMMIT = "pinned"
SCALES = (1, 2)
DIMENSIONS = (32, 64)
FAMILIES = ("linear", "reuse_update")
REUSE_VARIANTS = ("reuse", "update")
def _filename(family, k, variant):
    return f"{family}{'-' + variant if variant else ''}-k{k}.dml"
def render_program(family, k, *, n, d, variant=None, iterations=5):
    return f"# {family} {variant} {k} {n} {d} {iterations}\\n"
'''


class FreezeMicrobenchConditionsTest(unittest.TestCase):
    def fixture(self, root):
        microbench = root / "microbench"
        microbench.mkdir(exist_ok=True)
        (microbench / "generate.py").write_text(GENERATOR)
        (microbench / "run.py").write_text("# frozen runner contract\n")
        generator_sha = hashlib.sha256((microbench / "generate.py").read_bytes()).hexdigest()
        discoveries = []
        for family in ("linear", "reuse_update"):
            variants = ("reuse", "update") if family == "reuse_update" else (None,)
            for variant in variants:
                for scale in (1, 2):
                    name = f"microbench:{family}"
                    if variant:
                        name += f"-{variant}"
                    discoveries.append(name + f"-k{scale}")
        unresolved = [{
            "candidateId": f"candidate-{number}",
            "discoveryId": discovery,
            "kind": "generated-microbench",
            "networkProfile": None,
            "placeholderCellId": f"cell-{number}",
            "sourceFiles": {"microbench/generate.py": generator_sha},
            "workers": None,
        } for number, discovery in enumerate(discoveries)]
        candidate = root / "campaign.json"
        candidate.write_text(json.dumps({"unresolved": unresolved}))
        return candidate

    def pins(self, root):
        return {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                for name in ("microbench/generate.py", "microbench/run.py")}

    def test_freezes_all_generator_programs_without_creating_worker_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = self.fixture(root)
            manifest, programs = FREEZER.build(candidate, root, self.pins(root))
            self.assertEqual(manifest["counts"], {
                "candidates": 6, "conditions": 6, "generatedPrograms": 6,
                "workersPerCondition": 4})
            self.assertEqual(len(programs), 6)
            condition = next(row for row in manifest["conditions"]
                             if row["discoveryId"] == "microbench:linear-k1")
            case = condition["plannedCondition"]["case"]
            self.assertEqual(set(condition["plannedCondition"]), {"case", "network", "workers"})
            self.assertEqual(case["workers"], condition["plannedCondition"]["workers"])
            self.assertEqual(case["inputs"]["X"]["parts"][3]["end"], [4096, 64])
            self.assertEqual(case["inputs"]["X"]["parts"][0]["metadata"]["privacy"],
                             "private-aggregate")
            self.assertEqual(case["inputs"]["R"]["metadata"]["privacy"], "public")
            self.assertFalse(condition["inputValidation"]["runtimeDataBytesAttested"])
            self.assertFalse(condition["inputValidation"]["workerPartitionsCreatedOrCopied"])
            self.assertEqual(list(root.rglob("*.csv")), [])

            output = root / "artifact/conditions.json"
            program_dir = root / "artifact/programs"
            FREEZER.publish(manifest, programs, output, program_dir)
            FREEZER.publish(manifest, programs, output, program_dir, check=True)
            (program_dir / sorted(programs)[0]).write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "artifact differs"):
                FREEZER.publish(manifest, programs, output, program_dir, check=True)

    def test_rejects_generator_digest_and_candidate_set_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = self.fixture(root)
            document = json.loads(candidate.read_text())
            document["unresolved"][0]["sourceFiles"]["microbench/generate.py"] = "0" * 64
            candidate.write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, "generator digest differs"):
                FREEZER.build(candidate, root, self.pins(root))

            candidate = self.fixture(root)
            document = json.loads(candidate.read_text())
            document["unresolved"].pop()
            candidate.write_text(json.dumps(document))
            with self.assertRaisesRegex(ValueError, "program set differs"):
                FREEZER.build(candidate, root, self.pins(root))

    def test_rejects_changed_frozen_runner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = self.fixture(root)
            pins = self.pins(root)
            (root / "microbench/run.py").write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "source binding differs"):
                FREEZER.build(candidate, root, pins)


if __name__ == "__main__":
    unittest.main()
