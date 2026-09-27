import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_cell import frozen_compiler_configuration, save, sha
from verify_current_p_matrix import verify, verify_frozen_condition


class MatrixArtifactVerifierTest(unittest.TestCase):
    def test_model_and_receipt_must_match_frozen_jvm_network_condition(self):
        network = {"SYSDS_FED_COST_NET_BW": "625.000000"}
        options = ["-Dsysds.privacy.allowPublicRecodeMetadata=true"]
        summary = {"compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                   "networkEnvironmentChecked": True, "networkEnvironment": network,
                   "workloadJvmProperties": {
                       "sysds.privacy.allowPublicRecodeMetadata": "true"}}
        receipt = {"networkEnvironment": network, "jvmOptions": options}
        verify_frozen_condition(summary, receipt, options, network, {})
        for wrong_summary, wrong_receipt in (
                ({**summary, "networkEnvironmentChecked": False}, receipt),
                ({**summary, "networkEnvironment": {"SYSDS_FED_COST_NET_BW": "1"}}, receipt),
                ({**summary, "workloadJvmProperties": {}}, receipt),
                (summary, {**receipt, "jvmOptions": []}),
                (summary, {**receipt, "networkEnvironment": {}})):
            with self.assertRaisesRegex(ValueError, "frozen JVM/network condition"):
                verify_frozen_condition(wrong_summary, wrong_receipt, options, network, {})

    def test_promoted_model_and_receipt_must_attest_frozen_compiler_mode(self):
        binding = {"kind": "campaign-compile-condition", "compilerArgv": [
            "-exec", "singlenode", "-seed", "1011081480",
            "-noFedRuntimeConversion", "-stats", "100"]}
        expected = frozen_compiler_configuration(binding)
        network = {"SYSDS_FED_COST_NET_BW": "2"}
        summary = {"compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                   "networkEnvironmentChecked": True, "networkEnvironment": network,
                   "workloadJvmProperties": {}, "compilerArgv": binding["compilerArgv"],
                   "compilerConfiguration": expected}
        receipt = {"networkEnvironment": network, "jvmOptions": [],
                   "compilerArgv": binding["compilerArgv"],
                   "compilerConfiguration": expected}
        verify_frozen_condition(summary, receipt, [], network, binding)
        for changed_summary, changed_receipt in (
                ({**summary, "compilerConfiguration": {**expected, "execMode": "HYBRID"}}, receipt),
                (summary, {**receipt, "compilerConfiguration": None}),
                ({**summary, "compilerArgv": []}, receipt)):
            with self.assertRaisesRegex(ValueError, "frozen compiler condition"):
                verify_frozen_condition(changed_summary, changed_receipt, [], network, binding)

    def test_error_receipt_remains_incomplete_and_missing_receipt_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "x.dml"
            source.write_text("x")
            catalog = root / "catalog.json"
            save(catalog, {"cells": [{"id": "c1", "conditionId": "lan",
                "inventoryStatus": "IN_SCOPE", "sourceFiles": {"x.dml": sha(source)},
                "sourceBinding": {"conditionSha256": "condition"}}]})
            matrix = root / "matrix"
            matrix.mkdir()
            save(matrix / "matrix.json", {
                "schema": "current-p-matrix-capture-v1", "status": "INCOMPLETE",
                "catalogSha256": sha(catalog),
                "sourceTreeSha256": "a" * 64, "classTreeSha256": "c" * 64,
                "runnerSha256": "b" * 64, "evaluationRoot": str(root),
                "selectedConditions": None, "selectedCell": "c1", "cellCount": 1,
                "counts": {"ERROR": 1},
                "cells": [{"cell": "c1", "status": "ERROR"}],
            })
            (matrix / "c1").mkdir()
            save(matrix / "c1/receipt.json", {
                "schema": "current-p-matrix-cell-v1", "cell": "c1", "status": "ERROR",
                "sourceTreeSha256": "a" * 64, "classTreeSha256": "c" * 64,
                "runnerSha256": "b" * 64, "evaluationRoot": str(root),
                "catalogSha256": sha(catalog), "sourceFiles": {"x.dml": sha(source)},
            })
            result = verify(matrix, catalog, root, expected_cell="c1")
            self.assertEqual("INCOMPLETE", result["status"])
            self.assertEqual([{"cell": "c1", "reason": "ERROR"}], result["failures"])
            self.assertEqual(result, verify(matrix, catalog, root,
                                            expected_cell="c1", jobs=2))
            source.write_text("changed")
            result = verify(matrix, catalog, root, expected_cell="c1")
            self.assertEqual("FROZEN_SOURCE_CHANGED", result["failures"][0]["reason"])
            source.write_text("x")
            (matrix / "c1/receipt.json").unlink()
            with self.assertRaisesRegex(ValueError, "counts differ"):
                verify(matrix, catalog, root, expected_cell="c1")

    def test_empty_or_manifest_only_scope_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            save(catalog, {"cells": [{"id": "c1", "conditionId": "lan",
                "inventoryStatus": "IN_SCOPE"}]})
            matrix = root / "matrix"
            matrix.mkdir()
            save(matrix / "matrix.json", {
                "schema": "current-p-matrix-capture-v1", "catalogSha256": sha(catalog),
                "classTreeSha256": "c" * 64, "selectedConditions": None,
                "evaluationRoot": str(root),
                "selectedCell": "unknown", "cellCount": 0, "counts": {}, "cells": []})
            with self.assertRaisesRegex(ValueError, "independent expected scope"):
                verify(matrix, catalog, root)
            with self.assertRaisesRegex(ValueError, "no in-scope cells"):
                verify(matrix, catalog, root, expected_cell="unknown")
            with self.assertRaisesRegex(ValueError, "selected scope differs"):
                verify(matrix, catalog, root, expected_conditions={"lan"})

    def test_duplicate_catalog_ids_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            catalog = root / "catalog.json"
            save(catalog, {"cells": [{"id": "c1", "inventoryStatus": "IN_SCOPE"},
                                     {"id": "c1", "inventoryStatus": "IN_SCOPE"}]})
            matrix = root / "matrix"
            matrix.mkdir()
            save(matrix / "matrix.json", {
                "schema": "current-p-matrix-capture-v1", "catalogSha256": sha(catalog),
                "classTreeSha256": "c" * 64, "selectedConditions": None,
                "evaluationRoot": str(root),
                "selectedCell": "c1", "cellCount": 1, "counts": {}, "cells": []})
            with self.assertRaisesRegex(ValueError, "duplicate catalog cell ID"):
                verify(matrix, catalog, root, expected_cell="c1")


if __name__ == "__main__":
    unittest.main()
