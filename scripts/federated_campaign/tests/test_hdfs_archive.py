# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch
from typing import cast

from scripts.federated_campaign.atomic_ledger import AtomicEvidenceLedger, DiscoveryKey, PerformanceKey, ResumeDecision, ResumeState
from scripts.federated_campaign.determinism_contract import CAMPAIGN_PLANNERS, build_block_counterbalanced_schedule, build_counterbalanced_schedule
from scripts.federated_campaign.hdfs_archive import (
	ArchiveContractError,
	ARCHIVE_BOUNDARIES,
	CampaignHarnessAdapter,
	HdfsArchiveAdapter,
	HdfsCliBackend,
	HostResourceSnapshot,
	InjectedArchiveCrash,
)


class FakeHdfsBackend:
	def __init__(self):
		self.objects = {}
		self.events = []
		self.fail_rename = False
		self.fail_gets = 0

	def mkdirs(self, remote_uri):
		self.events.append(("mkdirs", remote_uri))

	def put(self, local_path, remote_uri):
		self.events.append(("put", remote_uri))
		self.objects[remote_uri] = Path(local_path).read_bytes()

	def rename(self, source_uri, destination_uri):
		self.events.append(("rename", source_uri, destination_uri))
		if self.fail_rename:
			raise RuntimeError("rename failed")
		if destination_uri in self.objects:
			raise RuntimeError("destination exists")
		self.objects[destination_uri] = self.objects.pop(source_uri)

	def get(self, remote_uri, local_path):
		self.events.append(("get", remote_uri))
		if self.fail_gets:
			self.fail_gets -= 1
			raise RuntimeError("get failed")
		if remote_uri not in self.objects:
			raise RuntimeError("missing")
		Path(local_path).write_bytes(self.objects[remote_uri])

	def exists(self, remote_uri):
		return remote_uri in self.objects

	def delete(self, remote_uri):
		self.objects.pop(remote_uri, None)


class HdfsArchiveAdapterTest(unittest.TestCase):
	temp_dir = cast(tempfile.TemporaryDirectory[str], object())
	root = cast(Path, object())
	ledger = cast(AtomicEvidenceLedger, object())
	backend = cast(FakeHdfsBackend, object())

	def setUp(self):
		self.temp_dir = tempfile.TemporaryDirectory()
		self.root = Path(self.temp_dir.name)
		self.ledger = AtomicEvidenceLedger(self.root / "ledger")
		self.backend = FakeHdfsBackend()

	def tearDown(self):
		self.temp_dir.cleanup()

	def _phase(self, name, metric_kind, seconds):
		phase = self.root / name
		phase.mkdir()
		raw = (
			f"Total execution time: {seconds} sec.\n"
			if metric_kind == "systemds_total_execution_time"
			else f"docker_e2e={seconds}\n"
		).encode()
		files = {
			"raw_coordinator.log": raw,
			"output.bin": f"output-{name}".encode(),
			"semantic_oracle.json": json.dumps({"passed": True}).encode(),
			"return_code.txt": b"0\n",
			"scan.json": json.dumps({"timeout": False, "error": False, "fallback": False}).encode(),
			"metric.json": json.dumps({"kind": metric_kind, "seconds": seconds}).encode(),
		}
		for filename, contents in files.items():
			(phase / filename).write_bytes(contents)
		checksums = {filename: hashlib.sha256(contents).hexdigest() for filename, contents in files.items()}
		(phase / "checksums.json").write_text(json.dumps(checksums, sort_keys=True), encoding="utf-8")
		return phase

	def _commit(self, attempt, token):
		key = DiscoveryKey("cell-a", attempt, token, "manifest-a")
		cold = self._phase(f"cold-{attempt}", "docker_e2e", 2.5 + attempt)
		warm = self._phase(f"warm-{attempt}", "systemds_total_execution_time", 1.25 + attempt)
		shared = self.root / f"shared-{attempt}.json"
		shared.write_text(
			json.dumps(
				{
					"identity": key.as_dict(),
					"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
					"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
				},
				sort_keys=True,
			),
			encoding="utf-8",
		)
		return key, self.ledger.publish_legacy_success_for_migration(key, cold, warm, shared)

	def _adapter(self, retention=1):
		return HdfsArchiveAdapter(
			self.ledger,
			self.backend,
			self.root / "archive-work",
			"hdfs://dams-so001:12000/tmp/logs/mchoi-g007-unit",
			max_local_raw_bundles=retention,
		)

	def _manifest_inputs(self):
		for relative, contents in {
			"systemds.jar": b"jar",
			"config.xml": b"config",
			"workload.dml": b"print('fixed')",
			"data/part.csv": b"1,2\n",
		}.items():
			path = self.root / relative
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_bytes(contents)
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		return {
			"jar": self.root / "systemds.jar",
			"image_id": "sha256:image",
			"image_digest": "repo@sha256:digest",
			"config": self.root / "config.xml",
			"dml": self.root / "workload.dml",
			"dataset_root": self.root / "data",
			"worker_mapping": ("worker-1:8001",),
			"planner_order": ("DP", "FedAll", "Heuristic", "MinST"),
			"seed": 19,
			"warmup_runs": 1,
			"measured_warm_runs": 5,
			"block_schedule": schedule,
			"expected_block_order": ("b0", "b1", "b2", "b3"),
		}

	def test_archive_validates_local_commit_before_upload(self):
		_, committed = self._commit(1, "token-a")
		(committed / "warm" / "output.bin").write_bytes(b"corrupt")
		with self.assertRaises(ArchiveContractError):
			self._adapter().archive(committed)
		self.assertFalse(any(event[0] == "put" for event in self.backend.events))

	def test_archive_verifies_remote_before_rolling_local_eviction(self):
		_, first = self._commit(1, "token-a")
		adapter = self._adapter(retention=1)
		first_receipt = adapter.archive(first)
		_, second = self._commit(2, "token-b")
		adapter.archive(second)
		self.assertFalse(first.exists())
		self.assertTrue(second.exists())
		self.assertEqual(first_receipt["archive_sha256"], adapter.catalog()[0]["archive_sha256"])

	def test_archive_rename_failure_keeps_local_bundle_and_stops(self):
		_, committed = self._commit(1, "token-a")
		self.backend.fail_rename = True
		adapter = self._adapter(retention=0)
		with self.assertRaisesRegex(ArchiveContractError, "archive"):
			adapter.archive(committed)
		self.assertTrue(committed.exists())
		self.assertEqual([], adapter.catalog())

	def test_retry_after_post_rename_get_failure_adopts_exact_final_object(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=0)
		self.backend.fail_gets = 1
		with self.assertRaises(ArchiveContractError):
			adapter.archive(committed)
		receipt = adapter.archive(committed)
		self.assertEqual(1, len(adapter.catalog()))
		self.assertEqual(receipt["archive_sha256"], adapter.catalog()[0]["archive_sha256"])
		self.assertFalse(committed.exists())

	def test_retry_after_catalog_publication_failure_adopts_exact_final_object(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=0)
		original = adapter._replace_catalog
		calls = 0
		def fail_once(entries):
			nonlocal calls
			calls += 1
			result = original(entries)
			if calls == 1:
				raise OSError("catalog fsync failed")
			return result
		with patch.object(adapter, "_replace_catalog", side_effect=fail_once):
			with self.assertRaises(ArchiveContractError):
				adapter.archive(committed)
			receipt = adapter.archive(committed)
		self.assertEqual(1, len(adapter.catalog()))
		self.assertEqual(receipt["archive_sha256"], adapter.catalog()[0]["archive_sha256"])
		self.assertFalse(committed.exists())

	def test_retry_rejects_conflicting_existing_final_object(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=1)
		record_id = committed.name
		remote = f"{adapter.remote_base_uri}/committed/{record_id}.tar"
		self.backend.objects[remote] = b"conflicting-object"
		with self.assertRaisesRegex(ArchiveContractError, "conflict"):
			adapter.archive(committed)

	def test_archive_crash_boundaries_retry_to_one_exact_receipt(self):
		for index, boundary in enumerate(ARCHIVE_BOUNDARIES, start=1):
			with self.subTest(boundary=boundary):
				case = self.root / f"archive-crash-{index}"
				ledger = AtomicEvidenceLedger(case / "ledger")
				key = DiscoveryKey(f"cell-{index}", 1, f"token-{index}", "manifest-a")
				cold = self._phase(f"archive-crash-{index}-cold", "docker_e2e", 2.5)
				warm = self._phase(f"archive-crash-{index}-warm", "systemds_total_execution_time", 1.25)
				shared = self.root / f"archive-crash-{index}-shared.json"
				shared.write_text(json.dumps({
					"identity": key.as_dict(),
					"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
					"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
				}), encoding="utf-8")
				committed = ledger.publish_legacy_success_for_migration(key, cold, warm, shared)
				adapter = HdfsArchiveAdapter(
					ledger, self.backend, case / "archive-work",
					f"hdfs://dams-so001:12000/tmp/logs/mchoi-g007-crash-{index}", max_local_raw_bundles=1,
				)
				with self.assertRaises(InjectedArchiveCrash):
					adapter.archive(committed, crash_after=boundary)
				receipt = adapter.archive(committed)
				self.assertEqual(1, len(adapter.catalog()))
				self.assertEqual(key.as_dict(), receipt["identity"])

	def test_remote_corruption_fails_preflight_before_next_lifecycle(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		receipt = adapter.archive(committed)
		self.backend.objects[receipt["archive_uri"]] = b"corrupt"
		with self.assertRaisesRegex(ArchiveContractError, "remote"):
			adapter.preflight_next_lifecycle(
				lambda: HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0),
				required_free_bytes=100,
				required_free_inodes=10,
				required_seconds=100,
				max_io_utilization=0.1,
				max_combined_io_bps=100,
			)

	def test_remote_missing_fails_preflight_before_host_snapshot(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		receipt = adapter.archive(committed)
		del self.backend.objects[receipt["archive_uri"]]
		snapshot_called = False
		def snapshot():
			nonlocal snapshot_called
			snapshot_called = True
			return HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0)
		with self.assertRaisesRegex(ArchiveContractError, "missing"):
			adapter.preflight_next_lifecycle(
				snapshot,
				required_free_bytes=100,
				required_free_inodes=10,
				required_seconds=100,
				max_io_utilization=0.1,
				max_combined_io_bps=100,
			)
		self.assertFalse(snapshot_called)

	def test_cli_exists_distinguishes_absence_from_transport_failure(self):
		backend = HdfsCliBackend("hdfs")
		with patch.object(
			backend, "_run", return_value=CompletedProcess([], 1, stdout="", stderr="")
		):
			self.assertFalse(backend.exists("hdfs://host/missing"))
		with patch.object(
			backend, "_run", return_value=CompletedProcess([], 1, stdout="", stderr="Permission denied")
		):
			with self.assertRaisesRegex(ArchiveContractError, "Permission denied"):
				backend.exists("hdfs://host/denied")
		with patch.object(
			backend, "_run", return_value=CompletedProcess([], 1, stdout="Permission denied", stderr="")
		):
			with self.assertRaisesRegex(ArchiveContractError, "Permission denied"):
				backend.exists("hdfs://host/stdout-denied")

	def test_eviction_rejects_catalog_path_outside_identity_derived_committed_path(self):
		_, first = self._commit(1, "token-a")
		adapter = self._adapter(retention=2)
		adapter.archive(first)
		victim = self.root / "must-not-delete"
		victim.mkdir()
		(victim / "sentinel").write_text("keep", encoding="utf-8")
		catalog = json.loads(adapter.catalog_path.read_text(encoding="utf-8"))
		catalog["entries"][0]["local_committed_path"] = str(victim)
		adapter.catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
		adapter.max_local_raw_bundles = 0
		_, second = self._commit(2, "token-b")
		with self.assertRaisesRegex(ArchiveContractError, "committed path"):
			adapter.archive(second)
		self.assertTrue((victim / "sentinel").is_file())

	def _failure(self, name="failure"):
		bundle = self.root / name
		bundle.mkdir()
		files = {
			"raw_coordinator.log": b"coordinator failed\n",
			"raw_worker.log": b"worker failed\n",
			"raw_compose.log": b"compose failed\n",
			"output.bin": b"partial-output",
			"semantic_oracle.json": json.dumps({"passed": False}).encode(),
			"metric.json": json.dumps({"kind": "failure", "seconds": 1.0}).encode(),
			"parser.json": json.dumps({"passed": False, "diagnostic": "missing metric"}).encode(),
			"return_code.txt": b"23\n",
			"scan.json": json.dumps({"timeout": True, "error": True, "fallback": False}).encode(),
			"command.json": json.dumps({"argv": ["docker", "compose"]}).encode(),
			"host_snapshot.json": json.dumps({"free_bytes": 10_000}).encode(),
		}
		for filename, contents in files.items():
			(bundle / filename).write_bytes(contents)
		(bundle / "checksums.json").write_text(json.dumps({
			filename: hashlib.sha256(contents).hexdigest() for filename, contents in files.items()
		}, sort_keys=True), encoding="utf-8")
		return bundle

	def test_harness_adapter_exposes_only_typed_sole_driver_surface(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		self.assertEqual(
			{
				"begin", "publish_discovery_success", "publish_performance_success", "publish_failure", "archive", "exact_resume",
				"select_pilot_repeats", "normalize_resume_row", "assert_planner_barrier", "preflight",
			},
			facade.integration_operations,
		)
		self.assertFalse(hasattr(facade, "ledger"))

	def test_harness_adapter_begins_publishes_and_resumes_exact_attempt(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = facade.begin(
			kind="performance", cell="cell-a", manifest_hash="manifest-a",
			invocation_manifest={"argv": ["docker"]}, lifecycle_replicate=3, period=2,
			order="FedAll>DP",
		)
		cold = self._phase("facade-cold", "docker_e2e", 2.5)
		warm = self._phase("facade-warm", "systemds_total_execution_time", 1.25)
		shared = self.root / "facade-shared.json"
		shared.write_text(json.dumps({
			"identity": lease.key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
		}), encoding="utf-8")
		committed = facade.publish_performance_success(lease, cold, warm, shared)
		decision = facade.exact_resume(lease.key)
		self.assertEqual(ResumeState.LATEST_SUCCESS, decision.state)
		row = facade.normalize_resume_row(decision, requested_identity=lease.key.as_dict(), schedule={"period": 2, "order": "FedAll>DP"}, host_load={"io": 0.01}, lifecycle={"cold": 1, "warm": 1})
		self.assertTrue(row["valid"])
		metrics = cast(dict[str, object], row["metrics"])
		warm_metric = cast(dict[str, object], metrics["warm"])
		self.assertEqual(1.25, warm_metric["seconds"])
		forged_evidence = dict(decision.evidence or {})
		forged_evidence["warm_metric"] = {"kind": "systemds_total_execution_time", "seconds": 999999}
		forged = facade.normalize_resume_row(
			ResumeDecision(ResumeState.LATEST_SUCCESS, decision.attempt, forged_evidence),
			requested_identity=lease.key.as_dict(), schedule={"period": 2, "order": "FedAll>DP"},
			host_load={"io": 0.01}, lifecycle={"cold": 1, "warm": 1},
		)
		forged_metrics = cast(dict[str, object], forged["metrics"])
		forged_warm = cast(dict[str, object], forged_metrics["warm"])
		self.assertEqual(1.25, forged_warm["seconds"])
		self.assertEqual(lease.key.as_dict(), self.ledger.validate_committed(committed)["identity"])

	def test_facade_discovery_success_normalizes_one_phase_without_schedule(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = facade.begin(
			kind="discovery", cell="cell-d", manifest_hash="manifest-a", invocation_manifest={"phase": "discovery"}
		)
		bundle = self._phase("discovery-facade", "discovery_correctness", 0.5)
		facade.publish_discovery_success(lease, bundle)
		decision = facade.exact_resume(lease.key)
		row = facade.normalize_resume_row(
			decision, requested_identity=lease.key.as_dict(), schedule=None,
			host_load={"io": 0.01}, lifecycle={"phase": "discovery"},
		)
		self.assertTrue(row["valid"])
		metrics = cast(dict[str, object], row["metrics"])
		discovery_metric = cast(dict[str, object], metrics["discovery"])
		self.assertEqual(0.5, discovery_metric["seconds"])
		self.assertIsNone(metrics["warm"])

	def test_harness_adapter_archives_valid_failure_and_never_stale_backfills(self):
		_, success = self._commit(1, "token-a")
		facade = CampaignHarnessAdapter(self.ledger, self._adapter(retention=0))
		facade.archive(success)
		lease = facade.begin(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]}
		)
		failed = facade.publish_failure(lease, self._failure())
		receipt = facade.archive(failed)
		self.assertEqual("failed", receipt["status"])
		decision = facade.exact_resume(DiscoveryKey("cell-a", 1, "query", "manifest-a"))
		self.assertEqual(ResumeState.LATEST_FAILED, decision.state)
		self.assertIsNone(self._adapter(retention=0).latest_discovery_success("cell-a", "manifest-a"))
		row = facade.normalize_resume_row(decision, requested_identity=lease.key.as_dict(), schedule=None, host_load={"io": 0.01}, lifecycle={"phase": "discovery"})
		self.assertTrue(row["failure"])
		metrics = cast(dict[str, object], row["metrics"])
		failure_metrics = cast(dict[str, object], metrics["failure"])
		self.assertEqual("process_exit", failure_metrics["category"])
		self.assertEqual(23, failure_metrics["return_code"])
		self.assertRegex(cast(str, failure_metrics["parser_sha256"]), r"^[0-9a-f]{64}$")

	def test_normalization_rejects_identity_mismatch_and_revalidates_local_bytes(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = facade.begin(
			kind="discovery", cell="cell-d", manifest_hash="manifest-a", invocation_manifest={"phase": "discovery"}
		)
		committed = facade.publish_discovery_success(lease, self._phase("identity-source", "discovery_correctness", 0.5))
		decision = facade.exact_resume(lease.key)
		wrong = DiscoveryKey("cell-other", lease.key.attempt, lease.key.run_token, "manifest-a").as_dict()
		row = facade.normalize_resume_row(
			decision, requested_identity=wrong, schedule=None,
			host_load={"io": 0.01}, lifecycle={"phase": "discovery"},
		)
		self.assertFalse(row["valid"])
		blocker = cast(dict[str, object], row["blocker"])
		self.assertEqual("IDENTITY_MISMATCH", blocker["code"])
		(committed / "discovery" / "output.bin").write_bytes(b"tampered")
		row = facade.normalize_resume_row(
			decision, requested_identity=lease.key.as_dict(), schedule=None,
			host_load={"io": 0.01}, lifecycle={"phase": "discovery"},
		)
		self.assertFalse(row["valid"])
		blocker = cast(dict[str, object], row["blocker"])
		self.assertEqual("LOCAL_REVALIDATION_FAILED", blocker["code"])

	def test_duplicate_same_attempt_archive_receipts_are_ambiguous(self):
		adapter = self._adapter(retention=0)
		facade = CampaignHarnessAdapter(self.ledger, adapter)
		lease = facade.begin(
			kind="discovery", cell="cell-d", manifest_hash="manifest-a", invocation_manifest={"phase": "discovery"}
		)
		committed = facade.publish_discovery_success(lease, self._phase("duplicate-archive", "discovery_correctness", 0.5))
		facade.archive(committed)
		catalog = json.loads(adapter.catalog_path.read_text(encoding="utf-8"))
		catalog["entries"].append(dict(catalog["entries"][0]))
		adapter.catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
		decision = facade.exact_resume(lease.key)
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("duplicate archived receipts", str(decision.detail))

	def test_legacy_v1_local_success_cannot_satisfy_v2_resume(self):
		key, _ = self._commit(1, "legacy-token")
		decision = self._adapter().exact_resume(key)
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("legacy v1", str(decision.detail))

	def test_harness_adapter_pilot_selection_rejects_forged_exact_120_rows(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		rows = []
		orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)
		for pilot_class in ("cheap", "medium", "heavy"):
			for planner in CAMPAIGN_PLANNERS:
				for workers, profile in ((1, "lan"), (4, "wan_mid")):
					cell = f"pilot_class={pilot_class}|planner={planner}|workers={workers}|profile={profile}"
					for repeat, order_tuple in enumerate(orders, 1):
						period = order_tuple.index(planner) + 1
						identity = PerformanceKey(cell, repeat, period, ">".join(order_tuple), "manifest-a", repeat, f"token-{len(rows)}").as_dict()
						rows.append({
							"pilot_class": pilot_class, "planner": planner, "workers": workers, "profile": profile,
							"cell": cell, "pilot_repeat": repeat, "warm_seconds": 1.0, "period": period,
							"order": ">".join(order_tuple), "carryover": "NONE" if period == 1 else order_tuple[period - 2],
							"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 1, "write_bytes_per_second": 1},
							"lifecycle": {"cold_seconds": 2, "warm_seconds": 1.0, "coordinator_restart_count": 1, "worker_restart_count": 1},
							"evidence_status": "committed", "evidence_sha256": f"{len(rows)+1:064x}",
							"identity": identity, "evidence_location": {"committed_path": f"/forged/{len(rows)+1}"},
						})
		with self.assertRaisesRegex(ArchiveContractError, "revalidation"):
			facade.select_pilot_repeats(rows)

	def test_facade_routes_resource_preflight_and_normalizes_explicit_invalid_row(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		snapshot = HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0)
		self.assertEqual(snapshot, facade.preflight(
			lambda: snapshot, required_free_bytes=5 * 1024**3, required_free_inodes=10,
			required_seconds=100, max_io_utilization=0.1, max_combined_io_bps=100,
		))
		row = facade.normalize_resume_row(
			ResumeDecision(ResumeState.CORRUPT_OR_AMBIGUOUS, detail="corrupt"),
			requested_identity=DiscoveryKey("cell-a", 1, "token", "manifest-a").as_dict(),
			schedule=None, host_load={"io": 0.01}, lifecycle={"phase": "discovery"},
		)
		self.assertFalse(row["valid"])
		self.assertTrue(row["failure"])
		self.assertEqual("discovery", row["kind"])

	def test_global_unidentified_corruption_blocks_archive_and_same_attempt_disagreement(self):
		key, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=1)
		adapter.archive(committed)
		unknown = self.ledger.committed / "discovery" / ("f" * 64)
		unknown.mkdir()
		(unknown / "bundle_manifest.json").write_bytes(b"bad")
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, adapter.exact_resume(key).state)
		(unknown / "bundle_manifest.json").unlink()
		unknown.rmdir()
		catalog = json.loads(adapter.catalog_path.read_text(encoding="utf-8"))
		catalog["entries"][0]["status"] = "failed"
		adapter.catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, adapter.exact_resume(key).state)

	def test_performance_failure_archive_is_never_returned_as_success(self):
		adapter = self._adapter(retention=0)
		facade = CampaignHarnessAdapter(self.ledger, adapter)
		lease = facade.begin(
			kind="performance", cell="cell-p", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]},
			lifecycle_replicate=1, period=1, order="DP>FedAll>Heuristic>MinST",
		)
		failed = facade.publish_failure(lease, self._failure("performance-failure"))
		facade.archive(failed)
		self.assertIsInstance(lease.key, PerformanceKey)
		assert isinstance(lease.key, PerformanceKey)
		self.assertIsNone(adapter.performance_success(lease.key))

	def test_global_planner_barrier_checks_exact_84_cells_per_completed_planner(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		seen = []
		def success(key):
			seen.append(key.cell)
			return ResumeDecision(ResumeState.LATEST_SUCCESS, 1, {"identity": key.as_dict(), "status": "success"})
		with patch.object(facade, "exact_resume", side_effect=success):
			receipt = facade.assert_planner_barrier("FedAll", "manifest-a")
		self.assertEqual(168, receipt["verified_cells"])
		self.assertEqual(84, sum("planner=DP|" in cell for cell in seen))
		self.assertEqual(84, sum("planner=FedAll|" in cell for cell in seen))
		self.assertFalse(any("planner=Heuristic|" in cell for cell in seen))

	def test_discovery_begin_enforces_previous_global_planner_barrier(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		cell = "workers=1|planner=FedAll|workload=kmeans|profile=lan"
		with patch.object(facade, "assert_planner_barrier", return_value={"verified_cells": 84}) as barrier:
			lease = facade.begin(
				kind="discovery", cell=cell, manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]}
			)
		barrier.assert_called_once_with("DP", "manifest-a")
		self.assertEqual(cell, lease.key.cell)

	def test_latest_local_failure_never_falls_back_to_archived_success(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=0)
		adapter.archive(committed)
		lease = self.ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]}
		)
		self.ledger.publish_failure(lease, self._failure("latest-failure"))
		gets_before = sum(event[0] == "get" for event in self.backend.events)
		self.assertIsNone(adapter.latest_discovery_success("cell-a", "manifest-a"))
		self.assertEqual(gets_before, sum(event[0] == "get" for event in self.backend.events))

	def test_preflight_samples_host_after_remote_verification_and_rejects_busy_io(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		adapter.archive(committed)
		def snapshot():
			self.backend.events.append(("snapshot",))
			return HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.5, 0, 0)
		with self.assertRaisesRegex(ArchiveContractError, "quiescence"):
			adapter.preflight_next_lifecycle(
				snapshot,
				required_free_bytes=100,
				required_free_inodes=10,
				required_seconds=100,
				max_io_utilization=0.1,
				max_combined_io_bps=100,
			)
		self.assertEqual("snapshot", self.backend.events[-1][0])


if __name__ == "__main__":
	unittest.main()
