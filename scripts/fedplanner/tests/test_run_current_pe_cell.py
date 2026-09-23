import gzip
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_current_pe_cell as runner


class CurrentPeCellReceiptTest(unittest.TestCase):
    def _write_import_matrix(self, root, kind, catalog, evaluation, source, classes):
        matrix = root / (kind + "-matrix")
        cell = "fixture"
        artifact = matrix / cell / f"{kind}-model.json.gz"
        artifact.parent.mkdir(parents=True)
        plain = json.dumps({"source": kind.upper() + "_C0"}, sort_keys=True).encode()
        artifact.write_bytes(gzip.compress(plain))
        artifact_sha = runner.hashlib.sha256(plain).hexdigest()
        native = {"schema": "closed-native-model-capture-v1", "status": "COMPLETE",
                  "cell": cell, "source": kind.upper() + "_C0",
                  "artifactPath": str(artifact), "artifactSha256": artifact_sha,
                  "rawCount": "1"}
        if kind == "p":
            manifest = {"schema": "current-p-matrix-capture-v1", "status": "COMPLETE",
                        "sourceTreeSha256": source, "classTreeSha256": classes,
                        "runnerSha256": runner._p_matrix_runner_sha(),
                        "catalogSha256": runner.sha(catalog),
                        "evaluationRoot": str(evaluation), "cellCount": 1,
                        "cells": [{"cell": cell, "status": "COMPLETE"}]}
            receipt = {"schema": "current-p-matrix-cell-v1", "status": "COMPLETE",
                       "cell": cell, "sourceTreeSha256": source,
                       "classTreeSha256": classes,
                       "runnerSha256": manifest["runnerSha256"],
                       "catalogSha256": runner.sha(catalog),
                       "evaluationRoot": str(evaluation), "artifactPath": str(artifact),
                       "artifactSha256": artifact_sha}
            runner.save(matrix / cell / "capture.json", native)
            runner.save(matrix / "matrix.json", manifest)
            verification = {
                "schema": "current-p-matrix-artifact-verification-v1", "status": "PASS",
                "matrixSha256": runner.sha(matrix / "matrix.json"),
                "verifierSha256": runner._p_matrix_verifier_sha(),
                "catalogSha256": runner.sha(catalog), "evaluationRoot": str(evaluation),
                "cellCount": 1, "verifiedComplete": 1, "failures": []}
        else:
            binding = {"sourceTreeSha256": source, "classTreeSha256": classes,
                       "runnerSha256": runner._e_matrix_runner_sha(),
                       "catalogSha256": runner.sha(catalog),
                       "evaluationRoot": str(evaluation)}
            manifest = {"schema": "current-e-model-matrix-v1", "status": "COMPLETE",
                        "binding": binding, "cellCount": 1,
                        "cells": [{"cell": cell, "status": "COMPLETE"}]}
            receipt = {"schema": "current-e-model-matrix-cell-v1", "status": "COMPLETE",
                       "cell": cell, "binding": binding, "artifactPath": str(artifact),
                       "artifactSha256": artifact_sha, "native": native}
            runner.save(matrix / "matrix.json", manifest)
            verification = {
                "schema": "current-e-model-matrix-verification-v1", "status": "PASS",
                "matrixSha256": runner.sha(matrix / "matrix.json"),
                "catalogSha256": runner.sha(catalog),
                "evaluationRoot": str(evaluation),
                "runnerSha256": binding["runnerSha256"],
                "cellCount": 1, "verifiedComplete": 1, "verifiedKnown": 1,
                "failures": [], "unknownFactorCells": []}
        runner.save(matrix / cell / "receipt.json", receipt)
        runner.save(matrix / "verification.json", verification)
        return matrix, artifact

    def test_verified_matrix_import_is_bound_and_source_mutation_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            evaluation = root / "evaluation"
            evaluation.mkdir()
            runner.save(catalog, {"cells": []})
            source, classes = "a" * 64, "b" * 64
            matrices = [self._write_import_matrix(root, kind, catalog, evaluation,
                                                  source, classes)
                        for kind in ("p", "e")]
            run_dir = root / "run"
            run_dir.mkdir()
            imported = {}
            for kind, (matrix, _) in zip(("p", "e"), matrices):
                descriptor, native = runner.describe_model_import(
                    kind, matrix, catalog, evaluation, "fixture", source, classes)
                receipt = runner.import_model(kind, descriptor, native, run_dir)
                runner.verify_model_import(kind, descriptor, receipt, run_dir, catalog,
                                           evaluation, "fixture", source, classes)
                imported[kind] = (descriptor, receipt)
            self.assertEqual(imported["p"][0]["artifactSha256"],
                             imported["p"][1]["artifactSha256"])
            p_source = matrices[0][1]
            p_source.write_bytes(gzip.compress(b'{"changed":true}'))
            with self.assertRaises((ValueError, OSError)):
                runner.verify_model_import("p", *imported["p"], run_dir, catalog,
                                           evaluation, "fixture", source, classes)

    def test_matrix_import_rejects_class_mismatch_and_unknown_e_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            evaluation = root / "evaluation"
            evaluation.mkdir()
            runner.save(catalog, {"cells": []})
            source, classes = "a" * 64, "b" * 64
            p_matrix, _ = self._write_import_matrix(root, "p", catalog, evaluation,
                                                    source, classes)
            with self.assertRaisesRegex(ValueError, "execution binding differs"):
                runner.describe_model_import("p", p_matrix, catalog, evaluation,
                                             "fixture", source, "c" * 64)
            e_matrix, _ = self._write_import_matrix(root, "e", catalog, evaluation,
                                                    source, classes)
            verification = runner.read(e_matrix / "verification.json")
            verification["status"] = "INCOMPLETE"
            verification["unknownFactorCells"] = [{"cell": "fixture"}]
            runner.save(e_matrix / "verification.json", verification)
            with self.assertRaisesRegex(ValueError, "complete known independent"):
                runner.describe_model_import("e", e_matrix, catalog, evaluation,
                                             "fixture", source, classes)

    def test_matrix_import_rejects_stale_verifier_and_rewritten_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            evaluation = root / "evaluation"
            evaluation.mkdir()
            runner.save(catalog, {"cells": []})
            source, classes = "a" * 64, "b" * 64
            p_matrix, _ = self._write_import_matrix(root, "p", catalog, evaluation,
                                                    source, classes)
            verification = runner.read(p_matrix / "verification.json")
            verification["verifierSha256"] = "0" * 64
            runner.save(p_matrix / "verification.json", verification)
            with self.assertRaisesRegex(ValueError, "complete independent verification"):
                runner.describe_model_import("p", p_matrix, catalog, evaluation,
                                             "fixture", source, classes)
            e_matrix, _ = self._write_import_matrix(root, "e", catalog, evaluation,
                                                    source, classes)
            manifest = runner.read(e_matrix / "matrix.json")
            manifest["tampered"] = True
            runner.save(e_matrix / "matrix.json", manifest)
            with self.assertRaisesRegex(ValueError, "complete known independent"):
                runner.describe_model_import("e", e_matrix, catalog, evaluation,
                                             "fixture", source, classes)

    def test_second_verifier_root_requires_fresh_process(self):
        prior = sys.modules.get("plan_space_verify")
        fake = types.ModuleType("plan_space_verify")
        fake.__file__ = "/other/calibration/plan_space_verify.py"
        sys.modules["plan_space_verify"] = fake
        try:
            with self.assertRaisesRegex(ValueError, "fresh Python process"):
                runner.verify(Path("/unused"), Path("/unused"), Path("/different"))
        finally:
            if prior is None:
                sys.modules.pop("plan_space_verify", None)
            else:
                sys.modules["plan_space_verify"] = prior

    def test_native_model_must_attest_effective_frozen_settings(self):
        network = {"SYSDS_FED_COST_NET_BW": "625.000000"}
        options = ["-Dsysds.privacy.allowPublicRecodeMetadata=true"]
        recorded = {"compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                    "networkEnvironmentChecked": True,
                    "networkEnvironment": network,
                    "workloadJvmProperties": {
                        "sysds.privacy.allowPublicRecodeMetadata": "true"}}
        runner.check_frozen_model_settings(recorded, options, network, "P")
        for changed in ({**recorded, "networkEnvironmentChecked": False},
                        {**recorded, "networkEnvironment": {}},
                        {**recorded, "workloadJvmProperties": {}}):
            with self.assertRaisesRegex(ValueError, "frozen JVM/network condition"):
                runner.check_frozen_model_settings(changed, options, network, "P")

    def test_promoted_compiler_attestation_rejects_missing_or_changed_mode(self):
        argv = ["-exec", "singlenode", "-seed", "1011081480",
                "-noFedRuntimeConversion", "-stats", "100"]
        binding = {"kind": "campaign-compile-condition", "compilerArgv": argv}
        configuration = runner.frozen_compiler_configuration(binding)
        recorded = {"compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                    "networkEnvironmentChecked": True, "networkEnvironment": {},
                    "workloadJvmProperties": {}, "compilerArgv": argv,
                    "compilerConfiguration": configuration}
        runner.check_frozen_model_settings(recorded, [], {}, "E", argv, configuration)
        with self.assertRaisesRegex(ValueError, "frozen compiler configuration"):
            runner.check_frozen_model_settings({**recorded,
                "compilerConfiguration": {**configuration, "seed": 1}},
                [], {}, "E", argv, configuration)
        with self.assertRaisesRegex(ValueError, "unsupported frozen compiler argv"):
            runner.frozen_compiler_configuration({"kind": "campaign-compile-condition"})

    def test_frozen_capture_settings_pins_network_and_jvm_options(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            protocol = root / "planning_study/native/protocol-w3.json"
            protocol.parent.mkdir(parents=True)
            protocol.write_text(json.dumps({"workload_jvm_options": {
                "P2_PREP": ["-Dsysds.privacy.allowPublicRecodeMetadata=true"]}}))
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"cells": [{
                "id": "fixture", "discoveryId": "planning-w3:P2_PREP",
                "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY",
                    "kind": "planning-snapshot", "plannedCondition": {"network": {
                        "cost_environment": {"SYSDS_FED_COST_NET_BW": "625.000000"}}}},
            }]}))
            environment, options, network = runner.frozen_capture_settings(
                catalog, root, "fixture", {"UNRELATED": "kept"})
            self.assertEqual(network["SYSDS_FED_COST_NET_BW"],
                             environment["SYSDS_FED_COST_NET_BW"])
            self.assertEqual(["-Dsysds.privacy.allowPublicRecodeMetadata=true"], options)
            with self.assertRaisesRegex(ValueError, "conflicts"):
                runner.frozen_capture_settings(catalog, root, "fixture",
                                               {"SYSDS_FED_COST_NET_BW": "1"})
            with self.assertRaisesRegex(ValueError, "conflicts"):
                runner.frozen_capture_settings(catalog, root, "fixture",
                    {"SYSDS_FED_COST_NET_SERDES_BW": "999"})
            for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                with self.assertRaisesRegex(ValueError, "ambient JVM options"):
                    runner.frozen_capture_settings(catalog, root, "fixture",
                                                   {name: "-Dsysds.privacy.allowPublicRecodeMetadata=true"})

    def test_promoted_condition_uses_bound_network_and_jvm_options(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"cells": [{
                "id": "promoted", "discoveryId": "microbench:linear-k1",
                "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY",
                    "kind": "generated-microbench",
                    "workloadJvmOptions": ["-Dsysds.codegen.enabled=false"],
                    "plannedCondition": {"workers": 4, "network": {
                        "cost_environment": {"SYSDS_FED_COST_NET_BW": "2"}}}},
            }]}))
            environment, options, network = runner.frozen_capture_settings(
                catalog, root, "promoted", {})
            self.assertEqual(["-Dsysds.codegen.enabled=false"], options)
            self.assertEqual({"SYSDS_FED_COST_NET_BW": "2"}, network)
            self.assertEqual("2", environment["SYSDS_FED_COST_NET_BW"])

    def test_compressed_rows_feed_plain_only_sorter(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rows = root / "rows.jsonl.gz"
            target = root / "sorted.jsonl.gz"
            with gzip.open(rows, "wb") as stream:
                stream.write(b'{"schema":"physical-plan-v1","logicalInputs":[]}\n')

            def strict_identity(row):
                if "logicalInputs" not in row:
                    raise ValueError("logical input relations missing")
                return json.dumps(row, sort_keys=True, separators=(",", ":")).encode()

            def plain_only_sorter(source, destination, limit):
                self.assertEqual(".jsonl", source.suffix)
                self.assertEqual(strict_identity({"schema": "physical-plan-v1",
                                                 "logicalInputs": []}) + b"\n",
                                 source.read_bytes())
                self.assertEqual(1024, limit)
                destination.write_bytes(b"sorted")
                return 1, 1

            self.assertEqual((1, 1), runner.sort_compressed_rows(
                plain_only_sorter, strict_identity, rows, target))
            self.assertEqual(b"sorted", target.read_bytes())
            self.assertEqual([], list(root.glob("tmp*.jsonl")))
            with gzip.open(rows, "wt") as stream:
                stream.write('{"schema":"physical-plan-v1"}\n')
            with self.assertRaisesRegex(ValueError, "logical input relations missing"):
                runner.sort_compressed_rows(plain_only_sorter, strict_identity, rows, target)
            with self.assertRaisesRegex(ValueError, "strict physical identity"):
                list(runner.strict_rows([b'{"schema":"physical-plan-v1"}\n'],
                                        lambda row: b'{}'))

    def test_class_tree_hash_detects_executable_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for folder, name in (("target/classes", "Main.class"),
                                 ("target/test-classes", "Capture.class"),
                                 ("target/lib", "dependency.jar")):
                path = root / folder / name
                path.parent.mkdir(parents=True)
                path.write_bytes(name.encode())
            original = runner.class_tree_sha(root)
            (root / "target/classes/Main.class").write_bytes(b"changed")
            self.assertNotEqual(original, runner.class_tree_sha(root))
            resource = root / "target/test-classes/config.properties"
            resource.write_bytes(b"input=one")
            with_resource = runner.class_tree_sha(root)
            resource.write_bytes(b"input=two")
            self.assertNotEqual(with_resource, runner.class_tree_sha(root))

    def test_p_shard_fails_closed_on_row_mutation_and_frontier_gap(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rows = root / "p.jsonl.gz"
            receipt_path = root / "p.json"
            with gzip.open(rows, "wt") as stream:
                stream.write(json.dumps({"schema": "physical-plan-v1"}) + "\n")
            model = {"programSha256": "program", "conditionSha256": "condition",
                     "sourceFiles": {"program.dml": "frozen"}}
            receipt = {"schema": "closed-planning-physical-shard-v1",
                       "status": "COMPLETE", "source": "P_C0", "cell": "fixture",
                       "start": "0", "stop": "1", "raw": "2", "accepted": "1",
                       "rejected": "1", "unknown": "0", "rows": str(rows),
                       "rowsSha256": runner.sha(rows), **model}
            runner.save(receipt_path, receipt)
            self.assertEqual("1", runner.check_row_receipt(
                receipt_path, "P_C0", "fixture", model, (0, 1))["accepted"])
            with self.assertRaisesRegex(ValueError, "row path differs"):
                runner.check_row_receipt(receipt_path, "P_C0", "fixture", model,
                                         (0, 1), root / "other.jsonl.gz")
            with self.assertRaisesRegex(ValueError, "frontier"):
                runner.check_row_receipt(receipt_path, "P_C0", "fixture", model, (1, 2))
            with gzip.open(rows, "wt") as stream:
                stream.write(json.dumps({"schema": "illegal-row"}) + "\n")
            with self.assertRaisesRegex(ValueError, "damaged"):
                runner.check_row_receipt(receipt_path, "P_C0", "fixture", model, (0, 1))


if __name__ == "__main__":
    unittest.main()
