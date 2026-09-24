import json
import importlib.util
import os
from pathlib import Path
import py_compile
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_medium_pca import (E_RAW, EXCLUDED_PILOTS, RUNNER_SHA, TARGET_GROUPS,
                                       derive_frontier, exclusive_execution_lock,
                                       freeze_worklist, resource_preflight,
                                       isolated_python_bytecode, run_command, run_one, save, sha,
                                       summarize, validate_worklist,
                                       verify_cell, verify_one)


class MediumPcaRunnerTest(unittest.TestCase):
    def make_fixture(self, root):
        catalog = root / "catalog.json"
        p_dir, e_dir = root / "p-models", root / "e-models"
        target = []
        excluded = sorted(EXCLUDED_PILOTS)
        for index in range(20):
            cell = excluded[index] if index < 4 else f"medium-{index:02d}"
            target.append({"id": cell, "inventoryStatus": "IN_SCOPE",
                           "discoveryId": sorted(TARGET_GROUPS)[index % 2],
                           "conditionId": ("lan", "wan_light", "wan_mid", "wan_heavy")[index % 4],
                           "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY"}})
            save(p_dir / cell / "receipt.json", {"status": "COMPLETE"})
            save(e_dir / cell / "receipt.json",
                 {"status": "COMPLETE", "native": {"rawCount": E_RAW}})
        filler = [{"id": f"filler-{index:03d}", "inventoryStatus": "IN_SCOPE",
                   "discoveryId": "other", "conditionId": "lan"}
                  for index in range(592)]
        rows = target + filler
        save(catalog, {"cells": rows})
        matrix_rows = [{"cell": row["id"], "status": "COMPLETE"} for row in rows]
        save(p_dir / "matrix.json", {"schema": "current-p-matrix-capture-v1",
                                     "status": "COMPLETE", "cellCount": 612,
                                     "counts": {"COMPLETE": 612}, "cells": matrix_rows,
                                     "sourceTreeSha256": "a" * 64,
                                     "classTreeSha256": "b" * 64})
        save(e_dir / "matrix.json", {"schema": "current-e-model-matrix-v1",
                                     "status": "COMPLETE", "cellCount": 612,
                                     "counts": {"COMPLETE": 612}, "cells": matrix_rows,
                                     "binding": {"sourceTreeSha256": "a" * 64,
                                                 "classTreeSha256": "b" * 64}})
        save(p_dir / "verification.json", {"status": "PASS", "cellCount": 612,
                                           "counts": {"COMPLETE": 612},
                                           "matrixSha256": sha(p_dir / "matrix.json"),
                                           "verifiedComplete": 612, "failures": []})
        save(e_dir / "verification.json", {"status": "PASS", "cellCount": 612,
                                           "counts": {"COMPLETE": 612},
                                           "matrixSha256": sha(e_dir / "matrix.json"),
                                           "verifiedComplete": 612, "verifiedKnown": 612,
                                           "unknownFactorCells": [], "failures": []})
        args = SimpleNamespace(worklist=root / "worklist.json", catalog=catalog,
                               p_matrix_dir=p_dir, e_matrix_dir=e_dir)
        return args

    def test_freeze_proves_exact_remaining_frontier_and_detects_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.make_fixture(Path(temporary))
            frozen = freeze_worklist(args)
            cells, binding = validate_worklist(frozen, args.catalog, args.p_matrix_dir,
                                               args.e_matrix_dir)
            self.assertEqual(len(frozen["targetCells"]), 20)
            self.assertEqual(len(cells), 16)
            self.assertEqual(set(cells), {row["cell"] for row in frozen["targetCells"]} - EXCLUDED_PILOTS)
            self.assertEqual(binding["runnerSha256"], RUNNER_SHA)
            receipt = args.e_matrix_dir / cells[0] / "receipt.json"
            changed = json.loads(receipt.read_text())
            changed["native"]["rawCount"] = "1"
            save(receipt, changed)
            with self.assertRaisesRegex(ValueError, "exactly 20"):
                validate_worklist(frozen, args.catalog, args.p_matrix_dir, args.e_matrix_dir)

    def test_stale_matrix_or_verification_binding_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.make_fixture(Path(temporary))
            frozen = freeze_worklist(args)
            verification = args.e_matrix_dir / "verification.json"
            changed = json.loads(verification.read_text())
            changed["extra"] = True
            save(verification, changed)
            with self.assertRaisesRegex(ValueError, "input binding changed"):
                validate_worklist(frozen, args.catalog, args.p_matrix_dir, args.e_matrix_dir)

    def test_missing_and_partial_artifact_remain_incomplete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(artifact_root=root, evaluation_root=root,
                                   verification_root=root, timeout=10)
            (root / "cell-1" / "partial").mkdir(parents=True)
            self.assertEqual(verify_one("cell-1", args),
                             {"cell": "cell-1", "status": "INCOMPLETE",
                              "reason": "CERTIFICATE_MISSING"})

    def test_corrupt_artifact_verdict_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(artifact_root=root, evaluation_root=root,
                                   verification_root=root, timeout=10)
            verdict = {"cell": "cell-1", "status": "PASS",
                       "claimScope": "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
                       "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
                       "pStructureStatus": "STRUCTURE_VERIFIED",
                       "factorStatus": "INDEPENDENT_FACTOR_TABLE_VERIFIED",
                       "pPhysical": 10, "ePhysical": 10, "pOnly": 1, "eOnly": 0}
            with patch("run_current_pe_medium_pca.run_command", return_value=(0, verdict, "")):
                with self.assertRaisesRegex(ValueError, "verdict is invalid"):
                    verify_cell("cell-1", root / "cell-1" / "run", args)

    def test_run_command_is_bounded_compact_and_resumable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(build_root=root, catalog=root, evaluation_root=root,
                                   verification_root=root, artifact_root=root,
                                   p_matrix_dir=root, e_matrix_dir=root, timeout=10)
            native = {"cell": "cell-1", "status": "INCOMPLETE", "reason": "paused",
                      "pStateCount": "128", "eRawCount": E_RAW}
            calls = []
            def fake(argv, timeout):
                calls.append(argv)
                return 2, native, ""
            with patch("run_current_pe_medium_pca.run_command", side_effect=fake):
                row = run_one("cell-1", args)
            self.assertEqual(row["status"], "INCOMPLETE")
            argv = calls[0]
            self.assertIn("--resume", argv)
            self.assertIn("--compact", argv)
            self.assertEqual(argv[argv.index("--jobs") + 1], "2")
            self.assertEqual(argv[argv.index("--state-budget") + 1], "128")
            self.assertEqual(argv[argv.index("--e-raw-budget") + 1], E_RAW)

    def test_resource_gate_enforces_four_cells_and_eight_jvms(self):
        args = SimpleNamespace(jobs=5, max_jvms=8, artifact_root=Path("/tmp"),
                               min_free_disk_gib=0)
        with self.assertRaisesRegex(ValueError, "four cells/eight JVMs"):
            resource_preflight(args)
        args.jobs, args.max_jvms = 4, 9
        with self.assertRaisesRegex(ValueError, "four cells/eight JVMs"):
            resource_preflight(args)

    def test_children_ignore_timestamp_valid_stale_pyc_and_environment_is_restored(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            module = root / "stale_module.py"
            module.write_text("VALUE = 'OLD'\n")
            original_stat = module.stat()
            # The test suite may itself run under PYTHONPYCACHEPREFIX.  Plant
            # the stale file where a child *without* that prefix will look.
            pyc = root / "__pycache__" / ("stale_module." +
                    sys.implementation.cache_tag + ".pyc")
            pyc.parent.mkdir(parents=True)
            py_compile.compile(str(module), cfile=str(pyc), doraise=True)
            module.write_text("VALUE = 'NEW'\n")
            os.utime(module, ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
            child = [sys.executable, "-c",
                     "import json,sys;sys.path.insert(0,sys.argv[1]);"
                     "import stale_module;print(json.dumps({'value':stale_module.VALUE}))",
                     str(root)]
            names = ("PYTHONPYCACHEPREFIX", "PYTHONDONTWRITEBYTECODE")
            saved = {name: os.environ.get(name) for name in names}
            existed = {name: name in os.environ for name in names}
            try:
                for name in names:
                    os.environ.pop(name, None)
                self.assertEqual(run_command(child, 10)[1], {"value": "OLD"})
                os.environ["PYTHONPYCACHEPREFIX"] = "outer-prefix"
                os.environ["PYTHONDONTWRITEBYTECODE"] = "outer-write-setting"
                with isolated_python_bytecode(root / "artifacts") as cache:
                    self.assertTrue(cache.is_dir())
                    self.assertEqual(os.environ["PYTHONPYCACHEPREFIX"], str(cache))
                    self.assertEqual(os.environ["PYTHONDONTWRITEBYTECODE"], "1")
                    self.assertEqual(run_command(child, 10)[1], {"value": "NEW"})
                self.assertEqual(os.environ["PYTHONPYCACHEPREFIX"], "outer-prefix")
                self.assertEqual(os.environ["PYTHONDONTWRITEBYTECODE"],
                                 "outer-write-setting")
                with self.assertRaisesRegex(RuntimeError, "forced"):
                    with isolated_python_bytecode(root / "artifacts"):
                        raise RuntimeError("forced")
                self.assertEqual(os.environ["PYTHONPYCACHEPREFIX"], "outer-prefix")
                self.assertEqual(os.environ["PYTHONDONTWRITEBYTECODE"],
                                 "outer-write-setting")
            finally:
                for name in names:
                    if existed[name]:
                        os.environ[name] = saved[name]
                    else:
                        os.environ.pop(name, None)

    def test_execution_lock_rejects_duplicate_artifact_or_result_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact, result = root / "artifacts", root / "results"
            worklist = root / "worklist.json"
            save(worklist, {"cellCount": 16})
            with exclusive_execution_lock(artifact, result, worklist):
                with self.assertRaisesRegex(ValueError, "locked by another process"):
                    with exclusive_execution_lock(artifact, root / "other-results", worklist):
                        self.fail("duplicate artifact root lock was admitted")
                with self.assertRaisesRegex(ValueError, "locked by another process"):
                    with exclusive_execution_lock(root / "other-artifacts", result, worklist):
                        self.fail("duplicate result root lock was admitted")
            with exclusive_execution_lock(artifact, result, worklist):
                self.assertTrue((artifact / ".current-pe-medium-pca.lock").is_file())
    def test_summary_is_honest_about_difference_and_incomplete(self):
        cells = [f"cell-{index}" for index in range(16)]
        rows = [{"cell": cell, "status": "CAPTURED_EQUAL"} for cell in cells]
        self.assertEqual(summarize(cells, rows, {})["status"], "CAPTURED_EQUAL")
        rows[-1]["status"] = "INCOMPLETE"
        self.assertEqual(summarize(cells, rows, {})["status"], "INCOMPLETE")
        rows[-1]["status"] = "DIFFERENT"
        self.assertEqual(summarize(cells, rows, {})["status"], "DIFFERENT")


if __name__ == "__main__":
    unittest.main()
