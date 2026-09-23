import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from capture_current_plan_matrix import capture_one, select_cells
from run_current_pe_cell import save, sha
from verify_current_p_matrix import verify as verify_matrix


class MatrixCaptureCacheTest(unittest.TestCase):
    def test_invalid_resume_is_a_structured_incomplete_matrix(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / 'catalog.json'
            row = {'id': 'c', 'inventoryStatus': 'IN_SCOPE', 'sourceBinding': {},
                   'sourceFiles': {}}
            save(catalog, {'cells': [row]})
            artifacts = root / 'artifacts'
            folder = artifacts / 'c'
            folder.mkdir(parents=True)
            (folder / 'receipt.json').write_bytes(b'broken prior receipt')
            with patch('capture_current_plan_matrix.check_frozen_inputs'), \
                 patch('capture_current_plan_matrix.frozen_capture_settings',
                       return_value=({}, [], {})):
                result = capture_one(row, catalog, root, root, artifacts, 1,
                    '0' * 64, '1' * 64, sha(catalog), '2' * 64, True, True)
            self.assertEqual(result['status'], 'ERROR')
            self.assertEqual(json.loads((folder / 'receipt.json').read_text()), result)
            self.assertEqual((Path(result['invalidPriorEvidence']) / 'receipt.json').read_bytes(),
                             b'broken prior receipt')
            save(artifacts / 'matrix.json', {
                'schema': 'current-p-matrix-capture-v1', 'status': 'INCOMPLETE',
                'sourceTreeSha256': '0' * 64, 'classTreeSha256': '1' * 64,
                'runnerSha256': '2' * 64, 'catalogSha256': sha(catalog),
                'evaluationRoot': str(root), 'selectedConditions': None,
                'selectedCell': 'c', 'readyOnly': False, 'cellCount': 1,
                'counts': {'ERROR': 1}, 'cells': [{'cell': 'c', 'status': 'ERROR'}]})
            with patch('verify_current_p_matrix.check_frozen_inputs'):
                verdict = verify_matrix(artifacts, catalog, root, expected_cell='c')
            self.assertEqual(verdict['status'], 'INCOMPLETE')
            self.assertEqual(verdict['counts'], {'ERROR': 1})

    def test_ready_only_keeps_unresolved_rows_visible_but_out_of_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            catalog = Path(temp) / "catalog.json"
            save(catalog, {"cells": [
                {"id": "ready", "inventoryStatus": "IN_SCOPE", "conditionId": "lan",
                 "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY"}},
                {"id": "pending", "inventoryStatus": "IN_SCOPE", "conditionId": "lan",
                 "sourceBinding": {"conditionStatus": "UNRESOLVED"}}]})
            self.assertEqual([row["id"] for row in select_cells(
                catalog, None, None, ready_only=True)], ["ready"])
            self.assertEqual(len(select_cells(catalog, None, None)), 2)
            with self.assertRaisesRegex(ValueError, "no in-scope cells"):
                select_cells(catalog, None, "pending", ready_only=True)

    def test_resume_rejects_changed_executable_tree(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cell = {"id": "c1", "sourceBinding": {}, "sourceFiles": {}}
            cell_dir = root / "c1"
            cell_dir.mkdir()
            plain = b"model"
            digest = hashlib.sha256(plain).hexdigest()
            (cell_dir / "p-model.json.gz").write_bytes(gzip.compress(plain))
            save(cell_dir / "capture.json", {"status": "COMPLETE", "artifactSha256": digest})
            old = {"schema": "current-p-matrix-cell-v1", "cell": "c1",
                   "status": "COMPLETE", "sourceTreeSha256": "source",
                   "classTreeSha256": "old-classes", "catalogSha256": "catalog",
                   "runnerSha256": "runner", "evaluationRoot": str(root),
                   "sourceFiles": {}, "artifactPath": str(cell_dir / "p-model.json.gz"),
                   "jvmOptions": [], "networkEnvironment": {},
                   "compilerArgv": None, "compilerConfiguration": None,
                   "artifactSha256": digest}
            save(cell_dir / "receipt.json", old)
            with patch("capture_current_plan_matrix.check_frozen_inputs"), \
                 patch("capture_current_plan_matrix.frozen_capture_settings",
                       return_value=({}, [], {})), \
                 patch("capture_current_plan_matrix.subprocess.Popen",
                       side_effect=RuntimeError("recapture attempted")):
                self.assertEqual(old, capture_one(cell, root, root, root, root, 1,
                    "source", "old-classes", "catalog", "runner", True, False))
                stale = capture_one(cell, root, root, root, root, 1,
                    "source", "new-classes", "catalog", "runner", True, False)
                self.assertEqual(stale["status"], "ERROR")
                self.assertIn("binding differs", stale["error"])
            quarantines = list(cell_dir.glob("prior-capture-*"))
            self.assertEqual(len(quarantines), 1)
            self.assertEqual(old, json.loads((quarantines[0] / "receipt.json").read_text()))
            self.assertEqual(plain,
                gzip.decompress((quarantines[0] / "p-model.json.gz").read_bytes()))
            with patch("capture_current_plan_matrix.check_frozen_inputs",
                       side_effect=ValueError("source changed")):
                new = capture_one(cell, root, root, root, root, 1,
                    "source", "old-classes", "catalog", "runner", True, False)
            self.assertEqual("INPUT_ERROR", new["status"])
            self.assertIn("source changed", new["error"])
            self.assertEqual(old, json.loads((quarantines[0] / "receipt.json").read_text()))

    def test_resume_preserves_unreadable_or_corrupt_complete_and_requires_explicit_recapture(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cell_dir = root / "c1"
            cell_dir.mkdir()
            cell = {"id": "c1", "sourceBinding": {}, "sourceFiles": {}}
            raw = b"not JSON"
            (cell_dir / "receipt.json").write_bytes(raw)
            model = gzip.compress(b"model")
            (cell_dir / "p-model.json.gz").write_bytes(model)
            with patch("capture_current_plan_matrix.check_frozen_inputs"), \
                 patch("capture_current_plan_matrix.frozen_capture_settings",
                       return_value=({}, [], {})), \
                 patch("capture_current_plan_matrix.subprocess.Popen",
                       side_effect=AssertionError("Java must not run")):
                result = capture_one(cell, root, root, root, root, 1,
                    "source", "classes", "catalog", "runner", True, False)
            self.assertEqual(result["status"], "ERROR")
            self.assertIn("unreadable", result["error"])
            first_quarantine = next(cell_dir.glob("prior-capture-*"))
            self.assertEqual((first_quarantine / "receipt.json").read_bytes(), raw)
            self.assertEqual((first_quarantine / "p-model.json.gz").read_bytes(), model)

            with patch("capture_current_plan_matrix.check_frozen_inputs"), \
                 patch("capture_current_plan_matrix.frozen_capture_settings",
                       return_value=({}, [], {})), \
                 patch("capture_current_plan_matrix.subprocess.Popen",
                       side_effect=RuntimeError("recapture attempted")):
                with self.assertRaisesRegex(RuntimeError, "recapture attempted"):
                    capture_one(cell, root, root, root, root, 1,
                        "source", "classes", "catalog", "runner", True, False, True)
            self.assertEqual((first_quarantine / "receipt.json").read_bytes(), raw)
            self.assertEqual((first_quarantine / "p-model.json.gz").read_bytes(), model)

            old = {"schema": "current-p-matrix-cell-v1", "cell": "c1",
                   "status": "COMPLETE", "sourceTreeSha256": "source",
                   "classTreeSha256": "classes", "catalogSha256": "catalog",
                   "runnerSha256": "runner", "evaluationRoot": str(root),
                   "sourceFiles": {}, "artifactPath": str(cell_dir / "p-model.json.gz"),
                   "jvmOptions": [], "networkEnvironment": {},
                   "compilerArgv": None, "compilerConfiguration": None,
                   "artifactSha256": "f" * 64}
            save(cell_dir / "receipt.json", old)
            save(cell_dir / "capture.json", {"status": "COMPLETE", "artifactSha256": "f" * 64})
            (cell_dir / "p-model.json.gz").write_bytes(model)
            with patch("capture_current_plan_matrix.check_frozen_inputs"), \
                 patch("capture_current_plan_matrix.frozen_capture_settings",
                       return_value=({}, [], {})), \
                 patch("capture_current_plan_matrix.subprocess.Popen",
                       side_effect=AssertionError("Java must not run")):
                result = capture_one(cell, root, root, root, root, 1,
                    "source", "classes", "catalog", "runner", True, False)
            self.assertEqual(result["status"], "ERROR")
            self.assertIn("model bytes differ", result["error"])
            self.assertTrue(any(json.loads((path / "receipt.json").read_text()) == old
                for path in cell_dir.glob("prior-capture-*")
                if path != first_quarantine and (path / "receipt.json").is_file()))


if __name__ == "__main__":
    unittest.main()
