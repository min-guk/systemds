import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


PATH = Path(__file__).resolve().parents[1] / "run_memo_metadata_planning.py"
SPEC = importlib.util.spec_from_file_location("memo_metadata_planning", PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class MemoMetadataPlanningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.renderer = runner.matrix.renderer_module().Renderer(runner.DEFAULT_STAGE)

    def rendered(self, workers=3):
        source, _, receipt, _ = runner.matrix.render_artifacts(
            self.renderer, runner.p2_cell(workers))
        return {**receipt, "source": source}

    def test_lane_is_p2_only_and_closed_to_w1357(self):
        self.assertEqual("p2|P2_PREP|lan|w7|DP-global", runner.p2_cell(7)["id"])
        with self.assertRaisesRegex(ValueError, "1,3,5,7"):
            runner.p2_cell(2)
        self.assertEqual(
            ["so002", "so003", "so004", "so005", "so006", "so008", "so009"],
            [row["host"] for row in runner.topology()["workers"]])

    def test_lifecycle_preserves_endpoints_and_explicit_resources(self):
        rendered = self.rendered(3)
        life = runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w3")
        spec = runner.docker.parse_manifest(life)
        self.assertEqual("0-3", life["container"]["cpuset_cpus"])
        self.assertEqual("8g", life["container"]["memory"])
        self.assertEqual([8001, 8002, 8003], [node.port for node in spec.workers])
        self.assertTrue(all("-Xmx3g" in node.command for node in spec.workers))
        self.assertTrue(life["mounts"][0]["read_only"])
        coordinator = runner.coordinator_exec(spec)
        command = coordinator[-1]
        self.assertIn("prepareTransformMetadata=true", command)
        self.assertIn("allowPublicRecodeMetadata=true", command)
        self.assertIn("timeout --signal=TERM --kill-after=15 300", command)
        self.assertIn("-noFedRuntimeConversion", command)

    def test_inventory_contains_full_hashes_and_exact_shard_directory_check(self):
        rendered = self.rendered(3)
        inventory = runner.selected_stage_inventory(rendered)
        self.assertEqual({"so002", "so003", "so004"}, set(inventory))
        for source in rendered["inputs"]:
            for shard in source["shards"]:
                files = inventory[shard["worker"]["host"]]
                self.assertEqual(shard["metadata_sha256"],
                                 files[shard["metadata_path"]]["sha256"])
                self.assertEqual({block["path"] for block in shard["blocks"]},
                                 {path for path in files if path.startswith(shard["stage_path"] + "/")})
        command = runner.remote_inventory_command(runner.DEFAULT_STAGE, inventory["so002"])
        self.assertIn("selected shard file-set mismatch", command[-1])
        self.assertIn("identity mismatch", command[-1])

    def test_metadata_receipt_is_exactly_one_and_uses_collections_not_rpc_count(self):
        line = ("TransformMetadataPreparationReceipt schema=transform-metadata-preparation-v1 "
                "metadataPreparationNanos=125000000 collections=1 owners=1 status=SUCCESS\n")
        parsed = runner.parse_metadata_receipt(line)
        self.assertTrue(parsed["verified"])
        self.assertEqual(0.125, parsed["metadataPreparationSeconds"])
        self.assertEqual(1, parsed["collections"])
        self.assertNotIn("requests", parsed)
        self.assertFalse(runner.parse_metadata_receipt(line + line)["verified"])
        self.assertFalse(runner.parse_metadata_receipt(line.replace("owners=1", "owners=0"))["verified"])

    def test_success_requires_compile_optimal_and_worker_identity(self):
        text = ("Total compilation time: 2.500 sec.\n"
                "PlanningFullInitialReceipt schema=planning-full-initial-v1 "
                "Tplanning_full_initial_nanos=2000000000\n"
                "Compile Phase RuntimeProgram: 0.125 sec.\n"
                "Total execution time: 0.000 sec.\n"
                "TransformMetadataPreparationReceipt schema=transform-metadata-preparation-v1 "
                "metadataPreparationNanos=125000000 collections=1 owners=1 status=SUCCESS\n")
        stats = mock.Mock()
        stats.memo_planning_statistics.return_value = [{"workers": 3}]
        stats.memo_search_statistics.return_value = [{"status": "OPTIMAL"}]
        with mock.patch.object(runner.matrix, "statistics_module", return_value=stats):
            result = runner.validate_success(text, 3, 0)
            self.assertTrue(result["verified"])
            self.assertEqual(0.125, result["metadata"]["metadataPreparationSeconds"])
            self.assertFalse(runner.validate_success(text, 7, 0)["verified"])

    def test_success_accepts_metadata_receipt_emitted_only_on_stderr(self):
        stdout = ("Total compilation time: 2.500 sec.\n"
                  "PlanningFullInitialReceipt schema=planning-full-initial-v1 "
                  "Tplanning_full_initial_nanos=2000000000\n"
                  "Compile Phase RuntimeProgram: 0.125 sec.\n"
                  "Total execution time: 0.000 sec.\n")
        stderr = ("TransformMetadataPreparationReceipt schema=transform-metadata-preparation-v1 "
                  "metadataPreparationNanos=125000000 collections=1 owners=1 status=SUCCESS\n")
        stats = mock.Mock()
        stats.memo_planning_statistics.return_value = [{"workers": 3}]
        stats.memo_search_statistics.return_value = [{"status": "OPTIMAL"}]
        with mock.patch.object(runner.matrix, "statistics_module", return_value=stats):
            self.assertTrue(runner.validate_success(
                runner.combined_process_output(stdout, stderr), 3, 0)["verified"])

    def test_persisted_and_validated_output_are_identical_without_stdout_newline(self):
        stdout = "stdout without trailing newline"
        stderr = ("TransformMetadataPreparationReceipt schema=transform-metadata-preparation-v1 "
                  "metadataPreparationNanos=125000000 collections=1 owners=1 status=SUCCESS\n")
        expected = stdout + "\n" + stderr
        validation = {"verified": True}
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(runner, "validate_success", return_value=validation) as validate:
            attempt = Path(directory)
            actual = runner.record_and_validate_success(attempt, stdout, stderr, 3, 0)
            persisted = (attempt / "coordinator.log").read_text(encoding="utf-8")
        self.assertIs(validation, actual)
        self.assertEqual(expected, persisted)
        validate.assert_called_once_with(persisted, 3, 0)

    def test_commands_fail_closed_on_existing_container_and_ports(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        checks = runner.foreign_use_checks(spec)
        self.assertEqual(2, len(checks))
        self.assertIn("docker ps -a", checks[0][-1])
        self.assertIn("8001", checks[1][-1])
        cleanup = runner.docker.build_plan(spec, "cleanup").as_dict()
        self.assertTrue(all("memo-p2-meta-dryrun-w1" in " ".join(row["argv"])
                            for row in cleanup["commands"]))

    def test_cgroup_snapshot_is_explicitly_not_process_rss(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        command = runner.resource_snapshot_command(spec, spec.workers[0])[-1]
        self.assertIn("memory.peak", command)
        self.assertIn("cpu.stat", command)
        completed = mock.Mock(returncode=0, stdout=json.dumps({
            "containerId": "container", "pid": 42, "state": {"Running": True},
            "cgroupPath": "/sys/fs/cgroup/container", "memoryCurrent": "1",
            "memoryPeak": "1", "cpuStat": "usage_usec 1"}), stderr="")
        snapshot = runner.capture_resource_snapshot(spec, run=mock.Mock(return_value=completed))
        self.assertEqual("container-cgroup-not-process-rss", snapshot["scope"])

    def test_cgroup_snapshot_failure_is_required_evidence_failure(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        completed = mock.Mock(returncode=23, stdout="", stderr="cgroup unavailable")
        with self.assertRaisesRegex(RuntimeError, "required cgroup snapshot failed on so007"):
            runner.capture_resource_snapshot(spec, run=mock.Mock(return_value=completed))

    def test_health_failure_and_oom_are_required_evidence_failures(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        with tempfile.TemporaryDirectory() as directory:
            attempt = Path(directory)
            health = mock.Mock(side_effect=[RuntimeError("inspect failed"), {
                "state": {"OOMKilled": True}, "oom_events": []}])
            completed = mock.Mock(returncode=0, stdout="worker log", stderr="")
            with mock.patch.object(runner.matrix_campaign, "capture_container_health", health), \
                    mock.patch.object(runner.subprocess, "run", return_value=completed):
                errors = runner.capture_required_container_evidence(spec, attempt)
        self.assertTrue(any("health capture failed on so007" in row for row in errors))
        self.assertTrue(any("container OOM observed: so002" in row for row in errors))
        result = {"status": "passed", "errors": []}
        runner.apply_required_evidence_errors(result, errors)
        self.assertEqual("failed", result["status"])
        self.assertEqual(errors, result["errors"])

    def test_uuid_namespace_cleanup_is_per_host_and_exact(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        remote = Path("/home/mchoi/memo-p2-metadata-0123456789abcdef")
        run = mock.Mock(side_effect=[
            mock.Mock(returncode=0, stdout="", stderr=""),
            mock.Mock(returncode=9, stdout="", stderr="permission denied")])
        receipt = runner.cleanup_remote_namespace(spec, remote, run=run)
        self.assertFalse(receipt["resolved"])
        self.assertEqual({"so007", "so002"}, set(receipt["hosts"]))
        for call in run.call_args_list:
            command = call.args[0][-1]
            self.assertIn("rm -rf -- /home/mchoi/memo-p2-metadata-0123456789abcdef", command)
        with self.assertRaisesRegex(ValueError, "refusing namespace cleanup"):
            runner.cleanup_remote_namespace(spec, Path("/home/mchoi/unrelated"), run=mock.Mock())

    def test_sync_failure_still_cleans_uuid_namespace_before_start(self):
        rendered = self.rendered(1)
        image_receipt = {"hosts": {
            "so007": {"actualImageId": runner.PINNED_IMAGE},
            "so002": {"actualImageId": runner.PINNED_IMAGE},
        }}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "result"
            engine = Path(directory) / "engine"
            engine.mkdir()
            (engine / "marker").write_text("engine", encoding="utf-8")
            cleanup = mock.Mock(return_value={"schema": "test", "hosts": {}, "resolved": True})
            with mock.patch.object(runner.matrix, "render_artifacts", return_value=(
                    rendered["source"], runner.metadata_configuration(), rendered, {})), \
                    mock.patch.object(runner.campaign, "acquire_remote_stage_leases", return_value=[]), \
                    mock.patch.object(runner, "verify_remote_selected_stage", return_value={}), \
                    mock.patch.object(runner, "remote_resource_preflight", return_value={}), \
                    mock.patch.object(runner, "verify_remote_images", return_value=image_receipt), \
                    mock.patch.object(runner, "foreign_use_checks", return_value=[]), \
                    mock.patch.object(runner, "engine_sync_commands", return_value=[["sync"]]), \
                    mock.patch.object(runner, "run_checked", side_effect=RuntimeError("sync failed")), \
                    mock.patch.object(runner, "cleanup_remote_namespace", cleanup):
                result = runner.run_cell(runner.p2_cell(1), root, engine,
                                         runner.DEFAULT_STAGE, self.renderer)
        self.assertEqual("failed", result["status"])
        self.assertTrue(result["cleanupResolved"])
        cleanup.assert_called_once()

    def test_verified_per_host_image_ids_are_bound_without_arbitrary_tag(self):
        rendered = self.rendered(1)
        spec = runner.docker.parse_manifest(runner.build_lifecycle(rendered, runner.DEFAULT_STAGE,
            Path("/remote/engine"), Path("/remote/attempt"), "dryrun-w1"))
        receipt = {"hosts": {
            "so007": {"actualImageId": runner.PINNED_IMAGE},
            "so002": {"actualImageId": runner.ALTERNATE_CLASSIC_IMAGE_ID},
        }}
        plan = runner.bind_start_plan_images(runner.docker.build_plan(spec, "start"), receipt)
        worker = next(item for item in plan.commands if item.label == "run-worker-1")
        coordinator = next(item for item in plan.commands if item.label == "run-coordinator")
        self.assertIn(runner.ALTERNATE_CLASSIC_IMAGE_ID, worker.argv[-1])
        self.assertNotIn(runner.PINNED_IMAGE, worker.argv[-1])
        self.assertIn(runner.PINNED_IMAGE, coordinator.argv[-1])
        self.assertFalse(any("cofee-experiment:" in item.argv[-1] for item in (worker, coordinator)))

    def test_exclusive_lane_is_nonblocking_and_released(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lane.lock"
            with runner.exclusive_lane(path) as receipt:
                self.assertTrue(receipt["held"])
                with self.assertRaisesRegex(RuntimeError, "held"):
                    with runner.exclusive_lane(path):
                        pass
            with runner.exclusive_lane(path):
                pass


if __name__ == "__main__":
    unittest.main()
