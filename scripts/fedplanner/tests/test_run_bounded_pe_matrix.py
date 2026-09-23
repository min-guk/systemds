import gzip
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_bounded_pe_matrix import run_one, select_cells
from run_current_pe_cell import save


class BoundedPEMatrixTest(unittest.TestCase):
    def test_selection_preserves_all_bounded_cells(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for cell, domains in (("small", [["n", ["CP", "FED"]]]),
                                  ("large", [["a", [0, 1, 2]], ["b", [0, 1]]])):
                folder = root / cell
                folder.mkdir()
                (folder / "p-model.json.gz").write_bytes(gzip.compress(json.dumps({
                    "nativeDomain": {"placementDomains": domains}}).encode()))
            manifest = {"cells": [{"cell": "large", "status": "COMPLETE"},
                                  {"cell": "small", "status": "COMPLETE"}]}
            self.assertEqual(select_cells(root, manifest, 2),
                             [{"cell": "small", "pStateCount": "2"}])
            self.assertEqual([item["cell"] for item in select_cells(root, manifest, 6)],
                             ["large", "small"])
            manifest["cells"][0]["status"] = "ERROR"
            with self.assertRaisesRegex(ValueError, "incomplete P model"):
                select_cells(root, manifest, 6)

    def test_resume_still_runs_separate_offline_verifier(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run_dir = root / "native" / "cell_1" / "binding"
            run_dir.mkdir(parents=True)
            matrix_dir = root / "matrix"
            save(matrix_dir / "cell_1" / "receipt.json", {
                "status": "COMPLETE", "artifactSha256": "same-model"})
            save(run_dir / "p-model.receipt.json", {
                "status": "COMPLETE", "artifactSha256": "same-model"})
            result_dir = root / "results"
            binding = {"sourceTreeSha256": "s", "classTreeSha256": "c"}
            save(result_dir / "cell_1" / "receipt.json", {
                "binding": binding, "status": "PASS", "runDir": str(run_dir)})
            args = SimpleNamespace(result_dir=result_dir, artifact_root=root / "native",
                                   p_matrix_dir=matrix_dir, evaluation_root=root,
                                   verification_root=root,
                                   cell_timeout=10, resume=True)
            with patch("run_bounded_pe_matrix.command", return_value=(0, {
                    "cell": "cell_1", "status": "PASS", "pOnly": 0, "eOnly": 0}, "")) as call:
                result = run_one({"cell": "cell_1", "pStateCount": "2"}, args, binding)
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(call.call_count, 1)
            self.assertIn("verify", call.call_args.args[0])
            save(run_dir / "p-model.receipt.json", {
                "status": "COMPLETE", "artifactSha256": "different-model"})
            with self.assertRaisesRegex(ValueError, "P/E P model differs"):
                run_one({"cell": "cell_1", "pStateCount": "2"}, args, binding)


if __name__ == "__main__":
    unittest.main()
