import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_cell import read, save, sha
from run_current_pe_matrix import (campaign_cells, check_matrix_e_identity,
                                   check_matrix_p_identity,
                                   check_resources, e_matrix_binding, run_cell,
                                   summarize, verify)


class CurrentPEMatrixTest(unittest.TestCase):
    def test_global_jvm_memory_and_disk_limits_fail_closed(self):
        args = SimpleNamespace(jobs=3, shard_jobs=2, max_jvms=5,
                               ram_budget_gib=40, min_free_disk_gib=10,
                               artifact_root=Path('/tmp'))
        with patch('run_current_pe_matrix.resource_snapshot', return_value={
                'cpuCount': 8, 'availableRamGiB': 100, 'freeDiskGiB': 100}):
            with self.assertRaisesRegex(ValueError, 'global JVM limit'):
                check_resources(args)
            args.max_jvms = 6
            self.assertEqual(check_resources(args)['binding']['maxJvms'], 6)
            args.ram_budget_gib = 29
            with self.assertRaisesRegex(ValueError, 'RAM budget'):
                check_resources(args)
            args.ram_budget_gib = 40
        with patch('run_current_pe_matrix.resource_snapshot', return_value={
                'cpuCount': 8, 'availableRamGiB': 100, 'freeDiskGiB': 9}):
            with self.assertRaisesRegex(ValueError, 'free-disk reserve'):
                check_resources(args)

    def test_cell_timeout_remains_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(result_dir=root / "results", artifact_root=root / "native",
                                   build_root=root, catalog=root / "catalog.json",
                                   evaluation_root=root, verification_root=root,
                                   p_matrix_dir=root / "p-matrix",
                                   e_matrix_dir=root / "e-matrix",
                                   state_budget=2, e_raw_budget=3, shard_size=1,
                                   shard_jobs=1, cell_timeout=1, resume=False,
                                   min_free_disk_gib=0,
                                   compact=False)
            save(args.e_matrix_dir / "cell_a" / "receipt.json",
                 {"native": {"rawCount": "3"}})
            with patch("run_current_pe_matrix.command",
                       return_value=(124, None, "limit")) as invoked:
                result = run_cell("cell_a", args, {"campaignSha256": "x"})
            self.assertEqual(result["status"], "INCOMPLETE")
            self.assertEqual(result["reason"], "CELL_TIMEOUT")
            command_line = invoked.call_args.args[0]
            self.assertEqual(str(args.p_matrix_dir),
                             command_line[command_line.index("--p-matrix-dir") + 1])
            self.assertEqual(str(args.e_matrix_dir),
                             command_line[command_line.index("--e-matrix-dir") + 1])
            self.assertEqual(read(root / "results" / "cell_a" / "receipt.json"), result)

    def test_e_raw_budget_stops_before_large_model_import(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(result_dir=root / "results", artifact_root=root / "native",
                                   e_matrix_dir=root / "e-matrix", e_raw_budget=10,
                                   resume=False, min_free_disk_gib=0)
            save(args.e_matrix_dir / "cell_a" / "receipt.json",
                 {"native": {"rawCount": "1000000000000000000000"}})
            with patch("run_current_pe_matrix.command") as invoked:
                result = run_cell("cell_a", args, {"campaignSha256": "x"})
            invoked.assert_not_called()
            self.assertEqual(result["status"], "INCOMPLETE")
            self.assertEqual(result["reason"], "E_RAW_BUDGET")
            self.assertEqual(result["eRawCount"], "1000000000000000000000")

    def test_e_matrix_binding_rejects_duplicate_or_different_build_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "catalog.json"
            save(catalog, {})
            matrix = root / "e"
            common = {"sourceTreeSha256": "a" * 64, "classTreeSha256": "b" * 64,
                      "catalogSha256": sha(catalog), "evaluationRoot": str(root)}
            manifest = {"schema": "current-e-model-matrix-v1", "status": "COMPLETE",
                        "binding": common, "cellCount": 1,
                        "cells": [{"cell": "a", "status": "COMPLETE"}]}
            save(matrix / "matrix.json", manifest)
            self.assertEqual(manifest, e_matrix_binding(
                matrix, ["a"], catalog, root,
                expected_source="a" * 64, expected_classes="b" * 64))
            with self.assertRaisesRegex(ValueError, "source/class bindings differ"):
                e_matrix_binding(matrix, ["a"], catalog, root,
                                 expected_source="c" * 64, expected_classes="b" * 64)
            manifest["cellCount"] = 2
            manifest["cells"].append({"cell": "a", "status": "COMPLETE"})
            save(matrix / "matrix.json", manifest)
            with self.assertRaisesRegex(ValueError, "cover exactly"):
                e_matrix_binding(matrix, ["a"], catalog, root)

    def test_offline_cell_rejects_p_model_that_differs_from_matrix(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix, run = root / "matrix", root / "run"
            save(matrix / "cell_a" / "receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "verified-a"})
            save(run / "p-model.receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "verified-b"})
            with self.assertRaisesRegex(ValueError, "differs from verified P matrix"):
                check_matrix_p_identity(matrix, run, "cell_a")
            save(run / "p-model.receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "verified-a"})
            check_matrix_p_identity(matrix, run, "cell_a")
            save(matrix / "cell_a" / "receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "verified-e"})
            save(run / "e-model.receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "different-e"})
            with self.assertRaisesRegex(ValueError, "differs from verified E matrix"):
                check_matrix_e_identity(matrix, run, "cell_a")
            save(run / "e-model.receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "verified-e"})
            check_matrix_e_identity(matrix, run, "cell_a")

    def test_full_scope_rejects_producer_only_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(result_dir=root)
            campaign = {"scope": "FULL_CURRENT", "unresolved": []}
            binding = {"campaignSha256": "x"}
            captured = {"cell": "a", "status": "CAPTURED_EQUAL",
                        "verification": {"status": "PASS",
                                         "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
                                         "pOnly": 0, "eOnly": 0}}
            result = summarize(args, campaign, ["a"], binding, [captured], write=False)
            self.assertEqual(result["status"], "INCOMPLETE")
            forged = {**captured, "status": "EQUAL"}
            with self.assertRaisesRegex(ValueError, "independently verified"):
                summarize(args, campaign, ["a"], binding, [forged], write=False)

    def test_summary_exposes_error_alongside_real_difference(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = SimpleNamespace(result_dir=Path(temporary))
            campaign = {"scope": "FROZEN_COHORT_2", "unresolved": ["pending"]}
            rows = [{"cell": "a", "status": "DIFFERENT"},
                    {"cell": "b", "status": "ERROR"}]
            result = summarize(args, campaign, ["a", "b"], {}, rows, write=False)
            self.assertEqual(result["status"], "ERROR")
            self.assertEqual(result["counts"], {"DIFFERENT": 1, "ERROR": 1})

    def test_campaign_rejects_missing_cell_and_false_full_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / "catalog.json"
            evaluation = root / "evaluation"
            evaluation.mkdir()
            base = {"id": "cell_a", "inventoryStatus": "IN_SCOPE",
                    "discoveryId": "planning-w3:a", "conditionId": "LAN",
                    "sourceBinding": {"conditionSha256": "x"},
                    "sourceFiles": {"a.dml": "y"}}
            save(catalog, {"cells": [base]})
            campaign = {"schema": "current-pe-corpus-manifest-v1",
                        "status": "INCOMPLETE", "scope": "PLANNING_COHORT_224",
                        "catalog": {"path": str(catalog), "sha256": sha(catalog)},
                        "evaluation": {"path": str(evaluation)},
                        "cells": [{**base, "inputValidation": {
                            "compileModelReady": True, "conditionDigestRecomputed": True,
                            "sourceDigestsVerified": True}}],
                        "unresolved": [{"candidateId": "candidate_b"}]}
            self.assertEqual(campaign_cells(campaign, catalog, evaluation), ["cell_a"])
            campaign["scope"] = "FROZEN_COHORT_1"
            self.assertEqual(campaign_cells(campaign, catalog, evaluation), ["cell_a"])
            campaign["scope"] = "FROZEN_COHORT_DERIVED_1"
            self.assertEqual(campaign_cells(campaign, catalog, evaluation), ["cell_a"])
            campaign["scope"] = "FROZEN_COHORT_DERIVED_ARGV_1"
            self.assertEqual(campaign_cells(campaign, catalog, evaluation), ["cell_a"])
            campaign["scope"] = "FROZEN_COHORT_DERIVED_2"
            with self.assertRaisesRegex(ValueError, "scope count"):
                campaign_cells(campaign, catalog, evaluation)
            campaign["scope"] = "FROZEN_COHORT_2"
            with self.assertRaisesRegex(ValueError, "scope count"):
                campaign_cells(campaign, catalog, evaluation)
            campaign["scope"] = "FULL_CURRENT"
            with self.assertRaisesRegex(ValueError, "unresolved"):
                campaign_cells(campaign, catalog, evaluation)
            campaign["status"] = "COMPLETE"
            campaign["unresolved"] = []
            campaign["cells"] = []
            with self.assertRaisesRegex(ValueError, "no concrete cells"):
                campaign_cells(campaign, catalog, evaluation)

    def test_cohort_all_equal_is_still_incomplete_and_verify_does_not_rewrite_tamper(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(result_dir=root, campaign=root / "campaign.json",
                                   catalog=root / "catalog.json", evaluation_root=root,
                                   p_matrix_dir=root / "matrix", verification_root=root,
                                   e_matrix_dir=root / "e-matrix",
                                   artifact_root=root / "artifacts", cell_timeout=3,
                                   state_budget=1, e_raw_budget=1, shard_size=1)
            campaign = {"scope": "PLANNING_COHORT_224", "unresolved": ["b"]}
            binding_value = {"campaignSha256": "x"}
            result = {"cell": "a", "status": "CAPTURED_EQUAL", "binding": binding_value,
                      "schema": "current-pe-matrix-cell-v1"}
            summary = summarize(args, campaign, ["a"], binding_value, [result])
            self.assertEqual(summary["status"], "INCOMPLETE")
            save(root / "a" / "receipt.json", result)
            stored = {**summary, "status": "EQUAL"}
            save(root / "summary.json", stored)
            save(args.campaign, {"schema": "current-pe-corpus-manifest-v1", **campaign})
            with (patch("run_current_pe_matrix.campaign_cells", return_value=["a"]),
                  patch("run_current_pe_matrix.matrix_binding", return_value={
                      "sourceTreeSha256": "s", "classTreeSha256": "c"}),
                  patch("run_current_pe_matrix.e_matrix_binding", return_value={}),
                  patch("run_current_pe_matrix.verify_planning_matrix"),
                  patch("run_current_pe_matrix.verify_exact_matrix"),
                  patch("run_current_pe_matrix.check_matrix_p_identity"),
                  patch("run_current_pe_matrix.check_matrix_e_identity"),
                  patch("run_current_pe_matrix.binding", return_value=binding_value),
                  patch("run_current_pe_matrix.offline_verify", return_value={
                      "status": "PASS", "pAcceptanceVerification":
                      "INDEPENDENT_FULL_ACCEPTANCE_VERIFIED", "pOnly": 0, "eOnly": 0})):
                # The tampered summary must remain available as evidence after rejection.
                result["status"] = "EQUAL"
                result["runDir"] = str(root / "artifacts" / "a" / "binding")
                result["verification"] = {
                    "status": "PASS", "pAcceptanceVerification":
                    "INDEPENDENT_FULL_ACCEPTANCE_VERIFIED", "pOnly": 0, "eOnly": 0}
                save(root / "a" / "receipt.json", result)
                with self.assertRaisesRegex(ValueError, "summary differs"):
                    verify(args)
            self.assertEqual(json.loads((root / "summary.json").read_text()), stored)

    def test_offline_verify_rechecks_matrix_to_cell_p_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(result_dir=root / "results",
                                   campaign=root / "campaign.json",
                                   catalog=root / "catalog.json",
                                   evaluation_root=root,
                                   p_matrix_dir=root / "matrix",
                                   e_matrix_dir=root / "e-matrix",
                                   verification_root=root,
                                   artifact_root=root / "artifacts",
                                   cell_timeout=3, state_budget=1,
                                   e_raw_budget=1, shard_size=1)
            save(args.campaign, {"schema": "current-pe-corpus-manifest-v1",
                                 "scope": "FROZEN_COHORT_1", "unresolved": ["pending"]})
            run_dir = args.artifact_root / "a" / "binding"
            save(args.p_matrix_dir / "a" / "receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "matrix-model"})
            save(run_dir / "p-model.receipt.json",
                 {"status": "COMPLETE", "artifactSha256": "different-model"})
            verdict = {"status": "PASS", "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
                       "pOnly": 0, "eOnly": 0}
            receipt = {"schema": "current-pe-matrix-cell-v1", "cell": "a",
                       "binding": {"campaignSha256": "frozen"},
                       "status": "CAPTURED_EQUAL", "runDir": str(run_dir),
                       "verification": verdict}
            save(args.result_dir / "a" / "receipt.json", receipt)
            summarize(args, read(args.campaign), ["a"], receipt["binding"], [receipt])
            with (patch("run_current_pe_matrix.campaign_cells", return_value=["a"]),
                  patch("run_current_pe_matrix.matrix_binding", return_value={
                      "sourceTreeSha256": "s", "classTreeSha256": "c"}),
                  patch("run_current_pe_matrix.e_matrix_binding", return_value={}),
                  patch("run_current_pe_matrix.verify_planning_matrix"),
                  patch("run_current_pe_matrix.verify_exact_matrix"),
                  patch("run_current_pe_matrix.binding", return_value=receipt["binding"]),
                  patch("run_current_pe_matrix.offline_verify", return_value=verdict)):
                with self.assertRaisesRegex(ValueError, "differs from verified P matrix"):
                    verify(args)


if __name__ == "__main__":
    unittest.main()
