from pathlib import Path
from contextlib import nullcontext
import hashlib
import importlib
import json
import py_compile
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_current_pe_matrix_parallel import (load_bound_runner,
                                               require_direct_source_bootstrap,
                                               source_only_imports,
                                               verify,
                                               verify_completed_receipts,
                                               verification_preflight)


class ParallelCurrentPEMatrixVerifyTest(unittest.TestCase):
    def _fixture(self, root, cells):
        args = SimpleNamespace(
            jobs=1, verification_jobs=2, artifact_root=root / "artifacts",
            p_matrix_dir=root / "p", e_matrix_dir=root / "e")
        receipts = []
        for cell in cells:
            run_dir = args.artifact_root / cell / "capture"
            receipts.append({
                "cell": cell, "status": "CAPTURED_EQUAL", "runDir": str(run_dir),
                "verification": {"status": "PASS", "pOnly": 0, "eOnly": 0,
                                 "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY"}})
        return args, receipts

    def test_completed_receipts_use_multiple_workers_and_return_cell_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            cells = ["cell_a", "cell_b", "cell_c"]
            args, receipts = self._fixture(Path(temporary), cells)
            barrier = threading.Barrier(2)
            worker_ids = set()
            lock = threading.Lock()

            with (patch("verify_current_pe_matrix_parallel."
                        "_verify_completed_receipt") as replay):
                def result(runner, unused_args, cell, receipt):
                    with lock:
                        worker_ids.add(threading.get_ident())
                    if cell in ("cell_a", "cell_b"):
                        barrier.wait(timeout=2)
                    return receipt
                replay.side_effect = result
                result = verify_completed_receipts(object(), args, cells, receipts)

            self.assertGreater(len(worker_ids), 1)
            self.assertEqual([row["cell"] for row in result], cells)

    def test_parallel_errors_are_reported_in_campaign_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            cells = ["cell_a", "cell_b"]
            args, receipts = self._fixture(Path(temporary), cells)

            def replay(runner, unused_args, cell, receipt):
                if cell == "cell_a":
                    time.sleep(0.05)
                raise ValueError("broken-" + cell)

            with patch("verify_current_pe_matrix_parallel._verify_completed_receipt",
                       side_effect=replay):
                with self.assertRaisesRegex(
                        ValueError,
                        "physical verification failed for cell_a: broken-cell_a"):
                    verify_completed_receipts(object(), args, cells, receipts)

    def test_source_only_loader_ignores_timestamp_valid_stale_pyc(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dependency = root / "bound_dependency.py"
            runner = root / "runner.py"
            dependency.write_text("VALUE = 'stale'\n")
            runner.write_text(
                "from pathlib import Path\n"
                "from bound_dependency import VALUE\n"
                "SCRIPT = Path(__file__).resolve()\n")
            child = root / "child.py"
            child.write_text("from bound_dependency import VALUE\nprint(VALUE)\n")
            py_compile.compile(str(dependency), doraise=True)
            stat = dependency.stat()
            dependency.write_text("VALUE = 'fresh'\n")
            self.assertEqual(len("VALUE = 'stale'\n"), len("VALUE = 'fresh'\n"))
            # Restore the timestamp used by the cache header, making normal import stale.
            import os
            os.utime(dependency, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            sys.path.insert(0, str(root))
            try:
                sys.modules.pop("bound_dependency", None)
                self.assertEqual(importlib.import_module("bound_dependency").VALUE,
                                 "stale")
            finally:
                sys.modules.pop("bound_dependency", None)
                sys.path.remove(str(root))

            digest = hashlib.sha256(runner.read_bytes()).hexdigest()
            with source_only_imports(root):
                loaded = load_bound_runner(runner, digest)
                self.assertEqual(loaded.VALUE, "fresh")
                child_result = subprocess.run(
                    [sys.executable, str(child)], check=True, text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    env=dict(os.environ))
                self.assertEqual(child_result.stdout.strip(), "fresh")

    def test_bound_runner_mutation_during_load_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            runner = Path(temporary) / "runner.py"
            trusted = ("from pathlib import Path\n"
                       "SCRIPT = Path(__file__).resolve()\n").encode()
            runner.write_bytes(trusted)
            digest = hashlib.sha256(trusted).hexdigest()
            original = Path.read_bytes
            reads = 0

            def mutate_after_first(path):
                nonlocal reads
                value = original(path)
                if Path(path).resolve() == runner.resolve():
                    reads += 1
                    if reads == 2:
                        runner.write_text("SCRIPT = None\n")
                return value

            with patch.object(Path, "read_bytes", mutate_after_first):
                with self.assertRaisesRegex(
                        ValueError, "changed while being read|digest differs"):
                    load_bound_runner(runner, digest)

    def test_imported_or_module_bootstrap_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "direct source-script"):
            require_direct_source_bootstrap()
        with self.assertRaisesRegex(ValueError, "direct source-script"):
            require_direct_source_bootstrap(None, None, "/tmp/not-the-auditor.py")

    def test_verification_preflight_caps_workers_and_ram(self):
        args = SimpleNamespace(verification_jobs=17, artifact_root=Path("/tmp"),
                               verification_min_free_disk_gib=0)
        with (patch("verify_current_pe_matrix_parallel.os.cpu_count",
                    return_value=64),
              patch("verify_current_pe_matrix_parallel._available_ram_gib",
                    return_value=100)):
            with self.assertRaisesRegex(ValueError, "CPU/safety cap"):
                verification_preflight(args, [])
        args.verification_jobs = 4
        with (patch("verify_current_pe_matrix_parallel.os.cpu_count",
                    return_value=8),
              patch("verify_current_pe_matrix_parallel._available_ram_gib",
                    return_value=1)):
            with self.assertRaisesRegex(ValueError, "RAM budget"):
                verification_preflight(args, [])
        args.verification_jobs = 4
        with (patch("verify_current_pe_matrix_parallel.os.cpu_count",
                    return_value=8),
              patch("verify_current_pe_matrix_parallel._available_ram_gib",
                    return_value=100),
              patch("verify_current_pe_matrix_parallel.shutil.disk_usage",
                    return_value=SimpleNamespace(free=int(0.2 * 1024 ** 3)))):
            with self.assertRaisesRegex(ValueError, "disk budget"):
                verification_preflight(args, [])

        escaped = [{"cell": "cell_a", "status": "CAPTURED_EQUAL",
                    "runDir": "/tmp/unrelated/capture", "verification": {
                        "physicalFormat": "compact-v1"}}]
        args.verification_jobs = 1
        with self.assertRaisesRegex(ValueError, "escapes campaign root"):
            verification_preflight(args, escaped)

        contained = [{"cell": "cell_a", "status": "CAPTURED_EQUAL",
                      "runDir": "/tmp/artifacts/cell_a/capture", "verification": {
                          "physicalFormat": "compact-v1"}}]
        args.artifact_root = Path("/tmp/artifacts")
        with (patch("verify_current_pe_matrix_parallel."
                    "_contained_run_artifact_size", return_value=10 * 1024 ** 3),
              patch("verify_current_pe_matrix_parallel.os.cpu_count",
                    return_value=8),
              patch("verify_current_pe_matrix_parallel._available_ram_gib",
                    return_value=100)):
            with self.assertRaisesRegex(ValueError, "RAM budget"):
                verification_preflight(args, contained)

    def test_full_verify_writes_attestation_and_rejects_verdict_mutation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binding = {"runnerSha256": "b" * 64}
            verdict = {"status": "PASS", "pOnly": 0, "eOnly": 0,
                       "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY"}
            receipt = {"schema": "current-pe-matrix-cell-v1", "cell": "cell_a",
                       "binding": binding, "status": "CAPTURED_EQUAL",
                       "runDir": str(root / "artifacts" / "cell_a" / "capture"),
                       "verification": verdict}
            summary = {"schema": "current-pe-matrix-summary-v1",
                       "binding": binding, "status": "INCOMPLETE",
                       "counts": {"CAPTURED_EQUAL": 1},
                       "cells": [{"cell": "cell_a", "status": "CAPTURED_EQUAL"}]}
            campaign = root / "campaign.json"
            result_dir = root / "results"
            result_dir.mkdir()
            campaign.write_text("{}")
            (result_dir / "summary.json").write_text(json.dumps(summary))
            (result_dir / "cell_a").mkdir()
            (result_dir / "cell_a" / "receipt.json").write_text(json.dumps(receipt))
            args = SimpleNamespace(
                campaign=campaign, catalog=root / "catalog", evaluation_root=root,
                p_matrix_dir=root / "p", e_matrix_dir=root / "e",
                verification_root=root, artifact_root=root / "artifacts",
                result_dir=result_dir, p_verify_jobs=1, verification_jobs=3,
                verification_min_free_disk_gib=0)

            class Runner:
                SCRIPT = Path(__file__).resolve()
                FULL_P_ACCEPTANCE = "INDEPENDENT_FULL_ACCEPTANCE_VERIFIED"
                mutation_target = None

                @staticmethod
                def read(path):
                    return json.loads(Path(path).read_text())

                @staticmethod
                def campaign_cells(*unused):
                    return ["cell_a"]

                @staticmethod
                def matrix_binding(*unused):
                    return {"sourceTreeSha256": "s", "classTreeSha256": "c"}

                @staticmethod
                def e_matrix_binding(*unused, **unused_keywords):
                    return {}

                @staticmethod
                def verify_planning_matrix(*unused):
                    return None

                @staticmethod
                def verify_exact_matrix(*unused):
                    return None

                @staticmethod
                def binding(*unused):
                    return binding

                @staticmethod
                def check_matrix_p_identity(*unused):
                    return None

                @staticmethod
                def check_matrix_e_identity(*unused):
                    return None

                @staticmethod
                def offline_verify(*unused):
                    if Runner.mutation_target is not None:
                        target = Runner.mutation_target
                        Runner.mutation_target = None
                        target.write_bytes(target.read_bytes() + b"\n")
                    return verdict

                @staticmethod
                def summarize(*unused, **unused_keywords):
                    return summary

            def attest(path, expected=None):
                path = Path(path).resolve()
                if path in (result_dir / "summary.json",
                            result_dir / "cell_a" / "receipt.json"):
                    digest = hashlib.sha256(path.read_bytes()).hexdigest()
                    if expected is not None and digest != expected:
                        raise ValueError("attested source digest differs: " + str(path))
                    return digest
                return expected or "a" * 64

            common = (patch("verify_current_pe_matrix_parallel.source_only_imports",
                            return_value=nullcontext()),
                      patch("verify_current_pe_matrix_parallel.load_bound_runner",
                            return_value=Runner),
                      patch("verify_current_pe_matrix_parallel._module_source_snapshot",
                            return_value={}),
                      patch("verify_current_pe_matrix_parallel._attest_file",
                            side_effect=attest))
            with common[0], common[1], common[2], common[3]:
                self.assertEqual(verify(args), 2)
            attestation = json.loads(
                (result_dir / "parallel-verification.json").read_text())
            self.assertEqual(attestation["verificationJobs"], 3)
            self.assertEqual(attestation["auditor"]["sha256"], "a" * 64)
            self.assertEqual(attestation["boundRunner"]["sha256"], "b" * 64)

            for mutated in (result_dir / "summary.json",
                            result_dir / "cell_a" / "receipt.json"):
                (result_dir / "summary.json").write_text(json.dumps(summary))
                (result_dir / "cell_a" / "receipt.json").write_text(
                    json.dumps(receipt))
                Runner.mutation_target = mutated
                with common[0], common[1], common[2], common[3]:
                    with self.assertRaisesRegex(ValueError, "digest differs"):
                        verify(args)

            receipt["verification"] = {**verdict, "pOnly": 1}
            (result_dir / "cell_a" / "receipt.json").write_text(json.dumps(receipt))
            with common[0], common[1], common[2], common[3]:
                with self.assertRaisesRegex(ValueError, "offline verdict changed"):
                    verify(args)


if __name__ == "__main__":
    unittest.main()
