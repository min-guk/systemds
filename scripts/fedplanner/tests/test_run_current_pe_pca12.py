import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_cell import save, sha
from run_current_pe_pca12 import (CONDITIONS, E_RAW, GROUPS, check_result_bindings,
                                  input_binding, recheck_completed, run_one, summarize,
                                  validate_worklist, verify_cell)


class Pca12WorklistTest(unittest.TestCase):
    def make_fixture(self, root):
        catalog = root / "catalog.json"
        p_dir, e_dir = root / "p-models", root / "e-models"
        entries, cells = [], []
        for group in sorted(GROUPS):
            for condition in sorted(CONDITIONS):
                cell = f"{group}-{condition}"
                entries.append({"id": cell, "inventoryStatus": "IN_SCOPE",
                                "discoveryId": group, "conditionId": condition,
                                "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY"}})
                cells.append({"cell": cell, "discoveryId": group,
                              "conditionId": condition, "eRawCount": E_RAW})
                save(p_dir / cell / "receipt.json", {"status": "COMPLETE"})
                save(e_dir / cell / "receipt.json", {"status": "COMPLETE",
                                                   "native": {"rawCount": E_RAW}})
        save(catalog, {"cells": entries})
        save(p_dir / "matrix.json", {"status": "COMPLETE", "cellCount": 612,
                                     "counts": {"COMPLETE": 612},
                                     "sourceTreeSha256": "a" * 64,
                                     "classTreeSha256": "b" * 64})
        save(e_dir / "matrix.json", {"status": "COMPLETE", "cellCount": 612,
                                     "counts": {"COMPLETE": 612},
                                     "binding": {"sourceTreeSha256": "a" * 64,
                                                 "classTreeSha256": "b" * 64}})
        save(p_dir / "verification.json", {"status": "PASS", "cellCount": 612,
                                           "counts": {"COMPLETE": 612},
                                           "matrixSha256": sha(p_dir / "matrix.json"),
                                           "verifiedComplete": 612, "failures": []})
        save(e_dir / "verification.json", {"status": "PASS", "cellCount": 612,
                                           "counts": {"COMPLETE": 612},
                                           "matrixSha256": sha(e_dir / "matrix.json"),
                                           "verifiedKnown": 612,
                                           "unknownFactorCells": [], "failures": []})
        cells.sort(key=lambda row: row["cell"])
        work = {"schema": "current-pe-pca12-worklist-v1",
                "status": "FROZEN_INPUT_WORKLIST_ONLY", "cellCount": 12,
                "catalogSha256": sha(catalog),
                "pMatrixSha256": sha(p_dir / "matrix.json"),
                "eMatrixSha256": sha(e_dir / "matrix.json"),
                "pVerificationSha256": sha(p_dir / "verification.json"),
                "eVerificationSha256": sha(e_dir / "verification.json"),
                "sourceTreeSha256": "a" * 64, "classTreeSha256": "b" * 64,
                "cells": cells}
        return work, catalog, p_dir, e_dir

    def test_all_twelve_worker_network_pairs_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            work, catalog, p_dir, e_dir = self.make_fixture(Path(temporary))
            self.assertEqual(len(validate_worklist(work, catalog, p_dir, e_dir, None)), 12)
            changed = next(row for row in work["cells"]
                           if row["conditionId"] == "wan_mid")
            changed["conditionId"] = "wan_other"
            catalog_data = json.loads(catalog.read_text())
            next(row for row in catalog_data["cells"]
                 if row["id"] == changed["cell"])["conditionId"] = "wan_other"
            save(catalog, catalog_data)
            work["catalogSha256"] = sha(catalog)
            with self.assertRaisesRegex(ValueError, "does not cover all"):
                validate_worklist(work, catalog, p_dir, e_dir, None)

    def test_unknown_e_factor_blocks_worklist(self):
        with tempfile.TemporaryDirectory() as temporary:
            work, catalog, p_dir, e_dir = self.make_fixture(Path(temporary))
            save(e_dir / "verification.json", {"status": "PASS", "cellCount": 612,
                                               "counts": {"COMPLETE": 612},
                                               "matrixSha256": sha(e_dir / "matrix.json"),
                                               "verifiedKnown": 612,
                                               "unknownFactorCells": ["one"],
                                               "failures": []})
            work["eVerificationSha256"] = sha(e_dir / "verification.json")
            with self.assertRaisesRegex(ValueError, "fully verified"):
                validate_worklist(work, catalog, p_dir, e_dir, None)

    def test_summary_preserves_denominator_and_difference(self):
        cells = [f"cell-{index}" for index in range(12)]
        results = [{"cell": cell, "status": "CAPTURED_EQUAL"} for cell in cells]
        self.assertEqual(summarize(cells, results, "a" * 64)["status"], "CAPTURED_EQUAL")
        results[-1]["status"] = "DIFFERENT"
        self.assertEqual(summarize(cells, results, "a" * 64)["status"], "DIFFERENT")
        with self.assertRaisesRegex(ValueError, "frontier"):
            summarize(cells, results[:-1], "a" * 64)

    def test_artifact_verifier_cannot_promote_producer_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(artifact_root=root, evaluation_root=root,
                                   verification_root=root, timeout=10)
            verdict = {"cell": "cell-1", "status": "PASS",
                       "claimScope": "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
                       "pAcceptanceVerification": "INDEPENDENT_FULL_ACCEPTANCE_VERIFIED"}
            with patch("run_current_pe_pca12.run_command", return_value=(0, verdict, "")):
                with self.assertRaisesRegex(ValueError, "artifact-only verification failed"):
                    verify_cell("cell-1", root / "cell-1" / "run", args)

    def test_pass_with_nonempty_difference_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(artifact_root=root, evaluation_root=root,
                                   verification_root=root, timeout=10)
            verdict = {"cell": "cell-1", "status": "PASS",
                       "claimScope": "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
                       "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
                       "pStructureStatus": "STRUCTURE_VERIFIED",
                       "factorStatus": "INDEPENDENT_FACTOR_TABLE_VERIFIED", "pPhysical": 1,
                       "ePhysical": 1, "pOnly": 1, "eOnly": 0}
            with patch("run_current_pe_pca12.run_command", return_value=(0, verdict, "")):
                with self.assertRaisesRegex(ValueError, "artifact-only verification failed"):
                    verify_cell("cell-1", root / "cell-1" / "run", args)

    def test_complete_factor_verdict_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(artifact_root=root, evaluation_root=root,
                                   verification_root=root, timeout=10)
            verdict = {"cell": "cell-1", "status": "PASS",
                       "claimScope": "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
                       "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
                       "pStructureStatus": "STRUCTURE_VERIFIED",
                       "factorStatus": "INDEPENDENT_FACTOR_TABLE_VERIFIED",
                       "pPhysical": 600, "ePhysical": 600, "pOnly": 0, "eOnly": 0}
            with patch("run_current_pe_pca12.run_command", return_value=(0, verdict, "")):
                self.assertEqual(verify_cell("cell-1", root / "cell-1" / "run", args),
                                 verdict)

    def test_native_budget_result_remains_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(build_root=root, catalog=root, evaluation_root=root,
                                   verification_root=root, artifact_root=root,
                                   p_matrix_dir=root, e_matrix_dir=root,
                                   shard_jobs=1, timeout=10)
            native = {"status": "INCOMPLETE", "reason": "native frontier exceeds budget",
                      "pStateCount": "256", "eRawCount": E_RAW}
            with patch("run_current_pe_pca12.run_command", return_value=(2, native, "")):
                row = run_one("cell-1", args)
            self.assertEqual(row["status"], "INCOMPLETE")
            self.assertEqual(row["eRawCount"], E_RAW)

    def test_input_binding_and_cell_imports_are_frozen(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work, catalog, p_dir, e_dir = self.make_fixture(root)
            work_path = root / "worklist.json"
            save(work_path, work)
            args = SimpleNamespace(worklist=work_path, catalog=catalog,
                                   p_matrix_dir=p_dir, e_matrix_dir=e_dir,
                                   build_root=None)
            cells, binding = input_binding(args)
            self.assertEqual(len(cells), 12)
            cell = cells[0]
            run_dir = root / "physical" / cell / "run"
            save(run_dir / "run-meta.json", {
                "cell": cell, "sourceTreeSha256": binding["sourceTreeSha256"],
                "classTreeSha256": binding["classTreeSha256"],
                "catalogSha256": binding["catalogSha256"],
                "modelImports": {
                    kind: {"matrixDir": str(directory),
                           "matrixManifestSha256": binding[kind + "MatrixSha256"],
                           "matrixVerificationSha256": binding[kind + "VerificationSha256"]}
                    for kind, directory in (("p", p_dir), ("e", e_dir))}})
            row = {"cell": cell, "status": "CAPTURED_EQUAL", "runDir": str(run_dir)}
            check_result_bindings([row], args, binding)
            meta = json.loads((run_dir / "run-meta.json").read_text())
            meta["modelImports"]["p"]["matrixManifestSha256"] = "0" * 64
            save(run_dir / "run-meta.json", meta)
            with self.assertRaisesRegex(ValueError, "model import differs"):
                check_result_bindings([row], args, binding)
            work["status"] = "MUTATED"
            save(work_path, work)
            with self.assertRaises(ValueError):
                input_binding(args)

    def test_final_recheck_detects_changed_cell(self):
        row = {"cell": "cell-1", "status": "CAPTURED_EQUAL", "runDir": "/tmp/cell-1"}
        with patch("run_current_pe_pca12.verify_one", return_value={**row, "pOnly": 1}):
            with self.assertRaisesRegex(ValueError, "changed after capture"):
                recheck_completed([row], SimpleNamespace())


if __name__ == "__main__":
    unittest.main()
