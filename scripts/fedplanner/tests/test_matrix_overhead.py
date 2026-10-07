#!/usr/bin/env python3
"""Untimed-overhead contracts: local mocks only, never SSH/Java/Docker."""
import importlib.util
import io
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
from contextlib import redirect_stderr, redirect_stdout


RUNNER = Path(__file__).resolve().parents[1] / "run_matrix_campaign.py"
SPEC = importlib.util.spec_from_file_location("matrix_overhead_runner", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAMPAIGN)


class NodeEvidenceTest(unittest.TestCase):
    def test_hosts_collect_in_parallel_and_drain_before_return(self):
        nodes = [SimpleNamespace(host="node1"), SimpleNamespace(host="node2")]
        barrier = threading.Barrier(2, timeout=2)
        completed = []

        def health(spec, node):
            barrier.wait()
            if node.host == "node2":
                time.sleep(0.02)
            completed.append(node.host)
            return {"state": {"OOMKilled": False}, "oom_events": []}

        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(CAMPAIGN, "capture_container_health", side_effect=health), \
                mock.patch.object(CAMPAIGN, "ssh", side_effect=lambda host, argv: host):
            root = Path(directory)
            result = CAMPAIGN.collect_node_evidence(root,
                SimpleNamespace(container_name=lambda node: node.host), nodes)
            self.assertEqual({"node1", "node2"}, set(completed))
            self.assertEqual({"errors": [], "health_collection_errors": [],
                "log_collection_errors": []}, result)
            for node in nodes:
                self.assertEqual(node.host, (root / f"{node.host}-container.log").read_text())
                self.assertFalse(json.loads((root / f"{node.host}-container-health.json")
                    .read_text())["state"]["OOMKilled"])

    def test_failed_health_does_not_skip_logs_or_peer_and_oom_is_preserved(self):
        nodes = [SimpleNamespace(host=f"node{n}") for n in range(3)]
        completed = []

        def health(spec, node):
            if node.host == "node0":
                raise RuntimeError("health unavailable")
            time.sleep(0.02)
            completed.append(node.host)
            return {"state": {"OOMKilled": node.host == "node1"},
                "oom_events": [{"Action": "oom"}] if node.host == "node2" else []}

        def logs(host, argv):
            if host == "node1":
                raise RuntimeError("logs unavailable")
            return host

        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(CAMPAIGN, "capture_container_health", side_effect=health), \
                mock.patch.object(CAMPAIGN, "ssh", side_effect=logs):
            root = Path(directory)
            result = CAMPAIGN.collect_node_evidence(root,
                SimpleNamespace(container_name=lambda node: node.host), nodes)
            self.assertEqual({"node1", "node2"}, set(completed))
            self.assertEqual(["container OOM observed: node1", "container OOM observed: node2"],
                result["errors"])
            self.assertEqual(["health unavailable"], result["health_collection_errors"])
            self.assertEqual(["logs unavailable"], result["log_collection_errors"])
            self.assertEqual("node0", (root / "node0-container.log").read_text())
            self.assertTrue((root / "node1-container-health.json").is_file())

    def test_collector_failure_cannot_prevent_strict_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            node = SimpleNamespace(host="node")
            spec = SimpleNamespace(coordinator=node, workers=[], container_name=lambda n: n.host)
            cleanup = mock.Mock(return_value={"resolved": True})
            campaign = SimpleNamespace(
                bounded_pilot_lifecycle=lambda *a, **k: {"mounts": [],
                    "coordinator": {"environment": {}}, "workers": []},
                remote_resource_preflight=lambda *a: {"passed": True},
                _strict_experiment_cleanup=cleanup)
            base = SimpleNamespace(parse_manifest=lambda life: spec,
                prepare_remote_directories=lambda *a: None, build_plan=lambda *a: None,
                prepare_cost_profile=mock.Mock(return_value={"profile_sha256": "p" * 64,
                    "_runtime": {"preparation_seconds": 0.0}}),
                capture_network_snapshot=lambda *a: {},
                validate_network_quality=lambda *a: {"valid": True})
            with mock.patch.object(CAMPAIGN, "run", return_value=""), \
                    mock.patch.object(CAMPAIGN, "ssh", return_value=""), \
                    mock.patch.object(CAMPAIGN.lifecycle, "execute_start"), \
                    mock.patch.object(CAMPAIGN.subprocess, "run",
                        return_value=SimpleNamespace(returncode=124)), \
                    mock.patch.object(CAMPAIGN, "collect_node_evidence",
                        side_effect=RuntimeError("thread pool unavailable")), \
                    redirect_stdout(io.StringIO()):
                result = CAMPAIGN.execute_cell(root, {"remote_root": "/remote",
                    "identity": {"jar_sha256": "jar"}}, CAMPAIGN.matrix()[0], "runtime",
                    SimpleNamespace(stage=Path("/stage")), campaign, base,
                    SimpleNamespace(render=lambda c: {"source": "print(1);"}))
            cleanup.assert_called_once_with(base, spec)
            self.assertIs(True, result["cleanup_resolved"])
            self.assertEqual("failed", result["status"])
            self.assertTrue(any("thread pool unavailable" in error for error in result["errors"]))


class ReferenceReuseTest(unittest.TestCase):
    def test_reference_pin_is_prepared_once_and_shared_between_cells(self):
        cells = CAMPAIGN.schedule("runtime")[:2]
        reference = {"result_manifest": {"local_path": "/tmp/reference"}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            campaign = SimpleNamespace(
                acquire_remote_stage_leases=lambda *args: ["lease"],
                release_remote_stage_leases=lambda *args: {"released": True},
                remote_stage_leases_alive=lambda *args: True,
                verify_remote_image_content=lambda *args: {},
                verify_remote_bounded_stage=lambda *args: {})
            with mock.patch.object(CAMPAIGN, "dependencies", return_value=(campaign,
                    SimpleNamespace(RUNTIME_LANE=root / "lane.lock"),
                    SimpleNamespace(Renderer=lambda stage: object()))), \
                    mock.patch.object(CAMPAIGN, "initialize", return_value={}), \
                    mock.patch.object(CAMPAIGN, "publish_overlay", return_value="ok"), \
                    mock.patch.object(CAMPAIGN, "latest", return_value={}), \
                    mock.patch.object(CAMPAIGN, "schedule", return_value=cells), \
                    mock.patch.object(CAMPAIGN, "summarize", return_value={}), \
                    mock.patch.object(CAMPAIGN.runtime_compare, "pin_reference",
                        return_value=reference) as pin, \
                    mock.patch.object(CAMPAIGN, "execute_cell", return_value={
                        "status": "passed", "cleanup_resolved": True}) as execute:
                self.assertEqual(0, CAMPAIGN.main(["--root", str(root), "--phase", "runtime",
                    "--runtime-without-compile-survey", "--max-cells", "2"]))
            pin.assert_called_once_with(root, None)
            self.assertEqual(2, execute.call_count)
            self.assertEqual([Path("/tmp/reference")] * 2,
                [call.args[-1] for call in execute.call_args_list])


class ContinuationIntegrationTest(unittest.TestCase):
    def test_cli_requires_explicit_direct_runtime_before_any_external_action(self):
        for options in ([], ["--phase", "runtime"], ["--phase", "summary"],
                        ["--phase", "all", "--runtime-without-compile-survey"]):
            with self.subTest(options=options), redirect_stderr(io.StringIO()), \
                    mock.patch.object(CAMPAIGN, "dependencies") as dependencies, \
                    self.assertRaises(SystemExit):
                CAMPAIGN.main(["--root", "/tmp/unused", "--continue-runtime-from", "/old",
                    *options])
            dependencies.assert_not_called()

    def test_latest_preserves_imported_origin_and_new_attempt_overrides_same_cell(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = {"one": {"cell": {"id": "one"}, "status": "passed",
                    "continuation_origin": {"root": "/old"}},
                "two": {"cell": {"id": "two"}, "status": "failed"}}
            replacement = {"cell": {"id": "two"}, "status": "passed"}
            CAMPAIGN.dump(root / "attempts/runtime/new/result.json", replacement)
            with mock.patch.object(CAMPAIGN.continuation, "load_rows", return_value=rows):
                actual = CAMPAIGN.latest(root, "runtime")
            self.assertEqual("/old", actual["one"]["continuation_origin"]["root"])
            self.assertEqual(replacement, actual["two"])

    def test_initialize_freezes_one_snapshot_and_resume_never_rebuilds_it(self):
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
            root, source = repo / "new", repo / "source"
            seen = []

            def build(old, manifest, runner, validate):
                seen.append(json.loads(json.dumps(manifest)))
                self.assertEqual(source, old)
                return {"source_root": str(source)}

            with mock.patch.object(CAMPAIGN, "REPO", repo), \
                    mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
                    mock.patch.object(CAMPAIGN, "verify_builtin_sync"), \
                    mock.patch.object(CAMPAIGN, "sha", return_value="a" * 64), \
                    mock.patch.object(CAMPAIGN, "run", return_value="mocked"), \
                    mock.patch.object(CAMPAIGN.continuation, "build_snapshot", side_effect=build) as builder, \
                    mock.patch.object(CAMPAIGN.continuation, "load_rows", return_value={}) as loader:
                manifest = CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True,
                    continuation_source=source)
                self.assertEqual(manifest, CAMPAIGN.initialize(root, repo / "stage",
                    direct_runtime=True, continuation_source=source))
                builder.assert_called_once()
                loader.assert_called_once_with(root, manifest)
                with self.assertRaisesRegex(RuntimeError, "continuation source changed"):
                    CAMPAIGN.initialize(root, repo / "stage", direct_runtime=True,
                        continuation_source=repo / "other")
            self.assertNotIn("continuation_sha256", seen[0]["identity"])
            seen[0]["identity"]["continuation_sha256"] = "a" * 64
            self.assertEqual(manifest, seen[0])
            self.assertTrue(manifest["measurement"]["parallel_untimed_only"])


if __name__ == "__main__":
    unittest.main()
