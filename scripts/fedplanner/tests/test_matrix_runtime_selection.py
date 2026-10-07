#!/usr/bin/env python3
"""Missing-only scheduling never imports historical results or expands silently."""
import hashlib
import importlib.util
import io
import json
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock

RUNNER = Path(__file__).resolve().parents[1] / "run_matrix_campaign.py"
SPEC = importlib.util.spec_from_file_location("matrix_runtime_selection", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAMPAIGN)


def selection(path, ids):
    path.write_text(json.dumps({"schema": "w1357-runtime-selection/v1",
        "selected_cell_ids": ids, "historical_coverage_is_not_result_import": True}))
    return {"identity": {"runtime_selection_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}}


class SelectionEvidenceTest(unittest.TestCase):
    def test_rejects_invalid_empty_duplicate_and_unknown_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cid = CAMPAIGN.matrix()[0]["id"]
            for ids in ([], [cid, cid], ["unknown"], [None], "not-a-list"):
                with self.subTest(ids=ids):
                    manifest = selection(root / "runtime-selection.json", ids)
                    with self.assertRaises(ValueError):
                        CAMPAIGN.runtime_selection_ids(root, manifest)

    def test_hash_mutation_rejected_and_absent_mode_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ids = [CAMPAIGN.matrix()[0]["id"]]
            manifest = selection(root / "runtime-selection.json", ids)
            self.assertEqual(set(ids), CAMPAIGN.runtime_selection_ids(root, manifest))
            with (root / "runtime-selection.json").open("a") as stream:
                stream.write(" ")
            with self.assertRaisesRegex(RuntimeError, "selection.*changed"):
                CAMPAIGN.runtime_selection_ids(root, manifest)
            self.assertIsNone(CAMPAIGN.runtime_selection_ids(root, {"identity": {}}))

    def test_selected_summary_does_not_claim_old_passes_or_full_matrix_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cells = CAMPAIGN.schedule("runtime")[:3]
            manifest = selection(root / "runtime-selection.json", [cells[2]["id"], cells[1]["id"]])
            CAMPAIGN.dump(root / "manifest.json", manifest)
            rows = {cells[1]["id"]: {"status": "passed"}}
            with mock.patch.object(CAMPAIGN, "latest", side_effect=lambda root, phase: rows if phase == "runtime" else {}), \
                    mock.patch.object(CAMPAIGN, "compile_gate", return_value=False):
                result = CAMPAIGN.summarize(root)
            self.assertEqual({"passed": 1, "failed": 0, "pending": 895}, result["runtime"])
            self.assertEqual({"selected": 2, "passed": 1, "failed": 0, "pending": 1,
                "excluded": 894}, result["runtime_selection"])
            self.assertFalse(result["compile_gate"])

    def test_manifest_resume_requires_same_source_and_frozen_selection(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            (repo / "src/main").mkdir(parents=True)
            (repo / "scripts/builtin").mkdir(parents=True)
            for name in CAMPAIGN.BUILTIN_SCRIPTS:
                (repo / "scripts/builtin" / name).write_text(name)
            (repo / "pom.xml").write_text("pom")
            probe = repo / "Probe.java"
            probe.write_text("probe")
            jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
            jar.parent.mkdir()
            jar.write_text("jar")
            source = repo / "selection.json"
            selection(source, [CAMPAIGN.matrix()[0]["id"]])
            root = repo / "run"
            with mock.patch.object(CAMPAIGN, "REPO", repo), \
                    mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
                    mock.patch.object(CAMPAIGN, "verify_builtin_sync"), \
                    mock.patch.object(CAMPAIGN, "sha", return_value="a" * 64), \
                    mock.patch.object(CAMPAIGN, "run", return_value="mocked"):
                manifest = CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True, runtime_selection=source)
                self.assertEqual(source.read_bytes(), (root / "runtime-selection.json").read_bytes())
                self.assertEqual(manifest, CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True,
                    runtime_selection=source))
                with self.assertRaisesRegex(RuntimeError, "identity changed"):
                    CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True)
                other = repo / "other.json"
                other.write_bytes(source.read_bytes())
                with self.assertRaisesRegex(RuntimeError, "identity changed"):
                    CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True, runtime_selection=other)
                selection(source, [CAMPAIGN.matrix()[1]["id"]])
                with self.assertRaisesRegex(RuntimeError, "identity changed"):
                    CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True, runtime_selection=source)


class SelectionCliTest(unittest.TestCase):
    def test_incompatible_modes_rejected_before_external_dependencies(self):
        options = (["--phase", "compile"], ["--phase", "runtime"],
            ["--phase", "runtime", "--runtime-without-compile-survey", "--planner", "FedFirst"],
            ["--phase", "runtime", "--runtime-without-compile-survey", "--continue-runtime-from", "/old"],
            ["--phase", "runtime", "--runtime-without-compile-survey", "--retry-failed"],
            ["--phase", "runtime", "--runtime-without-compile-survey", "--diagnostic-runtime-cell"])
        for option in options:
            with self.subTest(option=option), redirect_stderr(io.StringIO()), \
                    mock.patch.object(CAMPAIGN, "dependencies") as deps, self.assertRaises(SystemExit):
                CAMPAIGN.main(["--root", "/unused", "--runtime-selection", "/selection", *option])
            deps.assert_not_called()

    def test_runs_only_selected_in_canonical_order_and_finishes_partial_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cells = CAMPAIGN.schedule("runtime")[:4]
            # Covered cells 0 and 2 must not execute; input list order is not execution order.
            source = root / "selection.json"
            manifest = selection(source, [cells[3]["id"], cells[1]["id"]])
            (root / "runtime-selection.json").write_bytes(source.read_bytes())
            campaign = SimpleNamespace(acquire_remote_stage_leases=lambda *args: ["lease"],
                release_remote_stage_leases=lambda *args: {"released": True},
                remote_stage_leases_alive=lambda *args: True,
                verify_remote_image_content=lambda *args: {}, verify_remote_bounded_stage=lambda *args: {})
            lane = root / "lane"
            lane.touch()
            base = SimpleNamespace(RUNTIME_LANE=lane)
            renderer = SimpleNamespace(Renderer=lambda *args: object())
            seen = []
            def execute(*args):
                seen.append(args[2]["id"])
                return {"status": "passed", "cleanup_resolved": True}
            summary = {"runtime": {"passed": 2, "failed": 0, "pending": 894},
                "runtime_selection": {"selected": 2, "passed": 2, "failed": 0, "pending": 0, "excluded": 894}}
            with mock.patch.object(CAMPAIGN, "dependencies", return_value=(campaign, base, renderer)), \
                    mock.patch.object(CAMPAIGN, "initialize", return_value=manifest), \
                    mock.patch.object(CAMPAIGN, "publish_overlay", return_value="ok"), \
                    mock.patch.object(CAMPAIGN, "latest", return_value={}), \
                    mock.patch.object(CAMPAIGN, "schedule", return_value=cells), \
                    mock.patch.object(CAMPAIGN, "execute_cell", side_effect=execute), \
                    mock.patch.object(CAMPAIGN.runtime_compare, "pin_reference", return_value={"result_manifest": {"local_path": "/reference"}}), \
                    mock.patch.object(CAMPAIGN, "summarize", return_value=summary):
                rc = CAMPAIGN.main(["--root", str(root), "--phase", "runtime",
                    "--runtime-without-compile-survey", "--keep-going", "--runtime-selection", str(source)])
            self.assertEqual(0, rc)
            self.assertEqual([cells[1]["id"], cells[3]["id"]], seen)


if __name__ == "__main__":
    unittest.main()
