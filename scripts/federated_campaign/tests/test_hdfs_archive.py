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
from typing import Any, Mapping, cast

from scripts.federated_campaign.atomic_ledger import AtomicEvidenceLedger, AttemptLease, DiscoveryKey, LedgerContractError, PerformanceKey, ResumeDecision, ResumeState
from scripts.federated_campaign.determinism_contract import CAMPAIGN_PLANNERS, build_block_counterbalanced_schedule, build_counterbalanced_schedule, campaign_block_ids
from scripts.federated_campaign.hdfs_archive import (
	ArchiveContractError,
	ARCHIVE_BOUNDARIES,
	CampaignHarnessAdapter,
	HdfsArchiveAdapter,
	HdfsCliBackend,
	HostResourceSnapshot,
	InjectedArchiveCrash,
)


_TEST_ALLOCATION_AUTHORITY = object()


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

	def _begin_raw(self, facade: CampaignHarnessAdapter, **kwargs: Any):
		"""Ledger-fixture plumbing only; it does not exercise facade allocation authority."""
		_ = facade
		crash_after = kwargs.pop("crash_after", None)
		kwargs.setdefault("lifecycle_replicate", None)
		kwargs.setdefault("period", None)
		kwargs.setdefault("order", None)
		minimum_attempt = facade._minimum_archive_attempt(
			kwargs["kind"], kwargs["cell"], kwargs["manifest_hash"],
			kwargs["lifecycle_replicate"], kwargs["period"], kwargs["order"],
		)
		with patch.object(self.ledger, "_validate_allocation_authority", return_value=None):
			return self.ledger._begin_attempt_from_adapter(
				**kwargs, minimum_attempt=minimum_attempt, crash_after=crash_after,
				_allocation_authority=_TEST_ALLOCATION_AUTHORITY,
			)

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
			"scan.json": json.dumps({"timeout": False, "error": False, "fallback": False, "resource_invalid": False}).encode(),
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
					"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
					"lifecycle": {"cold_seconds": 2.5 + attempt, "warm_seconds": 1.25 + attempt, "coordinator_restart_count": 0, "worker_restart_count": 0},
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
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		return {
			"jar": self.root / "systemds.jar",
			"image_id": "sha256:image",
			"image_digest": "repo@sha256:digest",
			"config": self.root / "config.xml",
			"dml": self.root / "workload.dml",
			"dataset_root": self.root / "data",
			"worker_mapping": ("worker-1:8001",),
			"planner_order": ("DP", "FedAll", "Heuristic", "Exact"),
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
					"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
					"lifecycle": {"cold_seconds": 2.5, "warm_seconds": 1.25, "coordinator_restart_count": 0, "worker_restart_count": 0},
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

	def test_remote_corruption_is_deferred_from_per_cell_preflight_to_exact_resume(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		receipt = adapter.archive(committed)
		self.backend.objects[receipt["archive_uri"]] = b"corrupt"
		gets_before = sum(event[0] == "get" for event in self.backend.events)
		adapter.preflight_next_lifecycle(
			lambda: HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0),
			required_free_bytes=100,
			required_free_inodes=10,
			required_seconds=100,
			max_io_utilization=0.1,
			max_combined_io_bps=100,
		)
		self.assertEqual(gets_before, sum(event[0] == "get" for event in self.backend.events))
		decision = adapter.exact_resume(DiscoveryKey("cell-a", 1, "query", "manifest-a"))
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("remote", decision.detail or "")

	def test_remote_missing_is_deferred_from_per_cell_preflight_to_exact_resume(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		receipt = adapter.archive(committed)
		del self.backend.objects[receipt["archive_uri"]]
		snapshot_called = False
		def snapshot():
			nonlocal snapshot_called
			snapshot_called = True
			return HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0)
		adapter.preflight_next_lifecycle(
			snapshot,
			required_free_bytes=100,
			required_free_inodes=10,
			required_seconds=100,
			max_io_utilization=0.1,
			max_combined_io_bps=100,
		)
		self.assertTrue(snapshot_called)
		decision = adapter.exact_resume(DiscoveryKey("cell-a", 1, "query", "manifest-a"))
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("missing", decision.detail or "")

	def test_corrupt_local_catalog_receipt_still_fails_preflight_before_host_snapshot(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		adapter.archive(committed)
		catalog = json.loads(adapter.catalog_path.read_text(encoding="utf-8"))
		catalog["entries"][0]["receipt_sha256"] = "0" * 64
		adapter.catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
		snapshot_called = False
		def snapshot():
			nonlocal snapshot_called
			snapshot_called = True
			return HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0)
		with self.assertRaisesRegex(ArchiveContractError, "checksum"):
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
		entry = catalog["entries"][0]
		entry.pop("receipt_sha256")
		entry["receipt_sha256"] = hashlib.sha256(json.dumps(
			entry, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
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
				"begin_discovery", "begin_pilot", "begin_final_performance", "publish_discovery_success", "publish_performance_success", "publish_failure", "archive", "exact_resume",
				"select_pilot_repeats", "normalize_resume_row", "assert_planner_barrier", "complete_discovery", "preflight",
				"build_pilot_resource_reservation", "build_final_campaign_manifest",
			},
			facade.integration_operations,
		)
		self.assertFalse(hasattr(facade, "ledger"))

	def test_harness_adapter_begins_publishes_and_resumes_exact_attempt(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = self._begin_raw(facade,
			kind="performance", cell="cell-a", manifest_hash="manifest-a",
			invocation_manifest={"argv": ["docker"]}, lifecycle_replicate=3, period=1,
			order="FedAll>DP",
		)
		cold = self._phase("facade-cold", "docker_e2e", 2.5)
		warm = self._phase("facade-warm", "systemds_total_execution_time", 1.25)
		shared = self.root / "facade-shared.json"
		shared.write_text(json.dumps({
			"identity": lease.key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
			"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
			"lifecycle": {"cold_seconds": 2.5, "warm_seconds": 1.25, "coordinator_restart_count": 0, "worker_restart_count": 0},
			"lifecycle_wall_seconds": 4.5,
		}), encoding="utf-8")
		committed = facade.publish_performance_success(lease, cold, warm, shared)
		decision = facade.exact_resume(lease.key)
		self.assertEqual(ResumeState.LATEST_SUCCESS, decision.state)
		row = facade.normalize_resume_row(decision, requested_identity=lease.key.as_dict(), schedule={"period": 1, "order": "FedAll>DP"}, host_load={"io": 0.01}, lifecycle={"cold": 1, "warm": 1})
		self.assertTrue(row["valid"])
		metrics = cast(dict[str, object], row["metrics"])
		warm_metric = cast(dict[str, object], metrics["warm"])
		self.assertEqual(1.25, warm_metric["seconds"])
		self.assertEqual(
			{"io_utilization": 0.01, "read_bytes_per_second": 10.0, "write_bytes_per_second": 20.0},
			row["host_load"],
		)
		manifest = self.ledger.validate_committed(committed)
		self.assertEqual(manifest["resource_evidence"], row["resource_evidence"])

		self.assertEqual(1.25, cast(dict[str, object], row["lifecycle"])["warm_seconds"])
		for alias in (True, 1.0):
			with self.assertRaisesRegex(ArchiveContractError, "schedule disagrees"):
				facade.normalize_resume_row(
					decision, requested_identity=lease.key.as_dict(), schedule={"period": alias, "order": "FedAll>DP"},
					host_load={}, lifecycle={},
				)
		for attempt_alias in (True, 1.0):
			aliased = facade.normalize_resume_row(
				ResumeDecision(ResumeState.LATEST_SUCCESS, cast(int, attempt_alias), dict(decision.evidence or {})),
				requested_identity=lease.key.as_dict(), schedule={"period": 1, "order": "FedAll>DP"},
				host_load={}, lifecycle={},
			)
			self.assertFalse(aliased["valid"])
			self.assertEqual(
				"STALE_OR_NONCANONICAL_RESUME_DECISION", cast(dict[str, object], aliased["blocker"])["code"],
			)
		for field, alias in (("attempt", True), ("lifecycle_replicate", 3.0), ("period", 1.0)):
			aliased_evidence = dict(decision.evidence or {})
			aliased_identity = dict(cast(dict[str, object], aliased_evidence["identity"]))
			aliased_identity[field] = alias
			aliased_evidence["identity"] = aliased_identity
			aliased = facade.normalize_resume_row(
				ResumeDecision(ResumeState.LATEST_SUCCESS, decision.attempt, aliased_evidence),
				requested_identity=lease.key.as_dict(), schedule={"period": 1, "order": "FedAll>DP"},
				host_load={}, lifecycle={},
			)
			self.assertFalse(aliased["valid"])
			self.assertEqual("IDENTITY_MISMATCH", cast(dict[str, object], aliased["blocker"])["code"])
		forged_evidence = dict(decision.evidence or {})
		forged_evidence["warm_metric"] = {"kind": "systemds_total_execution_time", "seconds": 999999}
		forged = facade.normalize_resume_row(
			ResumeDecision(ResumeState.LATEST_SUCCESS, decision.attempt, forged_evidence),
			requested_identity=lease.key.as_dict(), schedule={"period": 1, "order": "FedAll>DP"},
			host_load={"io": 0.01}, lifecycle={"cold": 1, "warm": 1},
		)
		forged_metrics = cast(dict[str, object], forged["metrics"])
		forged_warm = cast(dict[str, object], forged_metrics["warm"])
		self.assertEqual(1.25, forged_warm["seconds"])
		self.assertEqual(lease.key.as_dict(), self.ledger.validate_committed(committed)["identity"])

	def test_interrupted_archive_preserves_exact_resource_evidence_on_archive_only_resume(self):
		adapter = self._adapter(retention=0)
		facade = CampaignHarnessAdapter(self.ledger, adapter)
		lease = self._begin_raw(facade,
			kind="performance", cell="resource-cell", manifest_hash="manifest-a",
			invocation_manifest={"argv": ["docker"]}, lifecycle_replicate=1, period=1,
			order="DP>FedAll>Heuristic>Exact",
		)
		cold = self._phase("resource-cold", "docker_e2e", 2.5)
		warm = self._phase("resource-warm", "systemds_total_execution_time", 1.25)
		shared = self.root / "resource-shared.json"
		shared.write_text(json.dumps({
			"identity": lease.key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
			"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
			"lifecycle": {"cold_seconds": 2.5, "warm_seconds": 1.25, "coordinator_restart_count": 0, "worker_restart_count": 0},
			"lifecycle_wall_seconds": 5.0,
		}), encoding="utf-8")
		committed = facade.publish_performance_success(lease, cold, warm, shared)
		resource = self.ledger.validate_committed(committed)["resource_evidence"]
		with self.assertRaises(InjectedArchiveCrash):
			facade.archive(committed, crash_after="after_archive_catalog_replace")
		receipt = facade.archive(committed)
		self.assertFalse(committed.exists())
		self.assertEqual(resource, receipt["resource_evidence"])
		decision = facade.exact_resume(lease.key)
		self.assertEqual(ResumeState.LATEST_SUCCESS, decision.state)
		self.assertEqual(resource, cast(dict[str, object], decision.evidence)["resource_evidence"])
		catalog = json.loads(adapter.catalog_path.read_text(encoding="utf-8"))
		catalog["entries"][0]["resource_evidence"]["artifact_bytes"] += 1
		entry = catalog["entries"][0]; entry.pop("receipt_sha256")
		entry["receipt_sha256"] = hashlib.sha256(json.dumps(
			entry, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		adapter.catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, facade.exact_resume(lease.key).state)

	def test_facade_discovery_success_normalizes_one_phase_without_schedule(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = self._begin_raw(facade,
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
		self.assertIsNone(row["host_load"])
		self.assertIsNone(row["lifecycle"])

	def test_harness_adapter_archives_valid_failure_and_never_stale_backfills(self):
		_, success = self._commit(1, "token-a")
		facade = CampaignHarnessAdapter(self.ledger, self._adapter(retention=0))
		facade.archive(success)
		lease = self._begin_raw(facade,
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
		forged_success = facade.normalize_resume_row(
			ResumeDecision(ResumeState.LATEST_SUCCESS, decision.attempt, dict(decision.evidence or {})),
			requested_identity=lease.key.as_dict(), schedule=None,
			host_load={"forged": 999}, lifecycle={"forged": 999},
		)
		self.assertFalse(forged_success["valid"])
		self.assertTrue(forged_success["failure"])
		self.assertEqual(ResumeState.LATEST_FAILED.value, forged_success["resume_state"])
		self.assertIsNone(forged_success["host_load"])
		self.assertIsNone(forged_success["lifecycle"])

	def test_normalization_rejects_identity_mismatch_and_revalidates_local_bytes(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		lease = self._begin_raw(facade,
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
		forged_evidence = dict(decision.evidence or {})
		forged_evidence["discovery_metric"] = {"seconds": 999999}
		row = facade.normalize_resume_row(
			ResumeDecision(ResumeState.LATEST_SUCCESS, decision.attempt, forged_evidence),
			requested_identity=lease.key.as_dict(), schedule=None,
			host_load={"io": 999999}, lifecycle={"warm": 999999},
		)
		self.assertFalse(row["valid"])
		blocker = cast(dict[str, object], row["blocker"])
		self.assertEqual("STALE_OR_NONCANONICAL_RESUME_DECISION", blocker["code"])
		metrics = cast(dict[str, object], row["metrics"])
		self.assertIsNone(metrics["discovery"])
		self.assertIsNone(metrics["warm"])
		self.assertIsNone(row["host_load"])
		self.assertIsNone(row["lifecycle"])
		location = cast(dict[str, object], row["evidence_location"])
		self.assertIsNone(location["committed_path"])

	def test_normalization_rejects_stale_success_after_newer_failure_locally_and_from_archive(self):
		for suffix, archive_old in (("local", False), ("archive", True)):
			with self.subTest(source=suffix):
				self.ledger = AtomicEvidenceLedger(self.root / f"ledger-{suffix}")
				self.backend = FakeHdfsBackend()
				facade = CampaignHarnessAdapter(self.ledger, self._adapter(retention=0 if archive_old else 1))
				cell = f"cell-stale-{suffix}"
				first = self._begin_raw(facade,
					kind="discovery", cell=cell, manifest_hash="manifest-a", invocation_manifest={"attempt": 1}
				)
				committed = facade.publish_discovery_success(
					first, self._phase(f"stale-{suffix}-success", "discovery_correctness", 0.5)
				)
				if archive_old:
					facade.archive(committed)
				old_success = facade.exact_resume(first.key)
				self.assertEqual(ResumeState.LATEST_SUCCESS, old_success.state)
				second = self._begin_raw(facade,
					kind="discovery", cell=cell, manifest_hash="manifest-a", invocation_manifest={"attempt": 2}
				)
				facade.publish_failure(second, self._failure(f"stale-{suffix}-failure"))
				row = facade.normalize_resume_row(
					old_success, requested_identity=first.key.as_dict(), schedule=None,
					host_load={"forged": 999}, lifecycle={"forged": 999},
				)
				self.assertFalse(row["valid"])
				self.assertTrue(row["failure"])
				self.assertEqual(ResumeState.LATEST_FAILED.value, row["resume_state"])
				blocker = cast(dict[str, object], row["blocker"])
				self.assertEqual("STALE_OR_NONCANONICAL_RESUME_DECISION", blocker["code"])
				metrics = cast(dict[str, object], row["metrics"])
				self.assertIsNone(metrics["discovery"])

	def test_pilot_evidence_schedule_must_match_preregistered_row(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		cell = "pilot_class=cheap|workload=kmeans|planner=DP|workers=1|profile=lan"
		lease = self._begin_raw(facade,
			kind="performance", cell=cell,
			manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]},
			lifecycle_replicate=99, period=4, order="WRONG",
		)
		cold = self._phase("pilot-wrong-cold", "docker_e2e", 2.0)
		warm = self._phase("pilot-wrong-warm", "systemds_total_execution_time", 1.0)
		shared = self.root / "pilot-wrong-shared.json"
		shared.write_text(json.dumps({
			"identity": lease.key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
			"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
			"lifecycle": {"cold_seconds": 2.0, "warm_seconds": 1.0, "coordinator_restart_count": 0, "worker_restart_count": 0},
			"lifecycle_wall_seconds": 4.0,
		}), encoding="utf-8")
		committed = facade.publish_performance_success(lease, cold, warm, shared)
		manifest = self.ledger.validate_committed(committed)
		row = {
			"pilot_class": "cheap", "workload": "kmeans", "planner": "DP", "workers": 1, "profile": "lan",
			"cell": lease.key.cell, "pilot_repeat": 1, "period": 1, "order": "DP>FedAll>Heuristic>Exact",
			"lifecycle": {"cold_seconds": 2.0, "warm_seconds": 1.0, "coordinator_restart_count": 0, "worker_restart_count": 0},
			"identity": lease.key.as_dict(), "evidence_status": "committed",
			"evidence_sha256": hashlib.sha256((committed / "bundle_manifest.json").read_bytes()).hexdigest(),
			"evidence_location": {"committed_path": str(committed)}, "warm_seconds": 1.0,
			"invocation_manifest_sha256": manifest["invocation_manifest_sha256"],
			"resource_evidence": manifest["resource_evidence"],
		}
		with self.assertRaisesRegex(ArchiveContractError, "scheduling identity"):
			facade._verify_pilot_row(row)

	def test_pilot_rejects_caller_forged_diagnostics_against_committed_evidence(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)
		order_tuple = orders[0]
		order = ">".join(order_tuple)
		period = order_tuple.index("DP") + 1
		cell = "pilot_class=cheap|workload=kmeans|planner=DP|workers=1|profile=lan"
		lease = self._begin_raw(facade,
			kind="performance", cell=cell, manifest_hash="a" * 64, invocation_manifest={"argv": ["docker"]},
			lifecycle_replicate=1, period=period, order=order,
		)
		cold = self._phase("pilot-canonical-cold", "docker_e2e", 2.0)
		warm = self._phase("pilot-canonical-warm", "systemds_total_execution_time", 1.0)
		shared = self.root / "pilot-canonical-shared.json"
		canonical_host = {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20}
		canonical_lifecycle = {"cold_seconds": 2.0, "warm_seconds": 1.0, "coordinator_restart_count": 0, "worker_restart_count": 0}
		shared.write_text(json.dumps({
			"identity": lease.key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
			"host_load": canonical_host, "lifecycle": canonical_lifecycle,
			"lifecycle_wall_seconds": 4.0,
		}), encoding="utf-8")
		committed = facade.publish_performance_success(lease, cold, warm, shared)
		manifest = self.ledger.validate_committed(committed)
		row = {
			"pilot_class": "cheap", "workload": "kmeans", "planner": "DP", "workers": 1, "profile": "lan",
			"cell": cell, "pilot_repeat": 1, "warm_seconds": 1.0, "period": period, "order": order,
			"carryover": "NONE" if period == 1 else order_tuple[period - 2],
			"host_load": {"io_utilization": 0.99, "read_bytes_per_second": 999, "write_bytes_per_second": 999},
			"lifecycle": canonical_lifecycle,
			"evidence_status": "committed",
			"evidence_sha256": hashlib.sha256((committed / "bundle_manifest.json").read_bytes()).hexdigest(),
			"identity": lease.key.as_dict(), "evidence_location": {"committed_path": str(committed)},
			"invocation_manifest_sha256": manifest["invocation_manifest_sha256"],
			"resource_evidence": manifest["resource_evidence"],
		}
		with self.assertRaisesRegex(ArchiveContractError, "diagnostics disagree"):
			facade._verify_pilot_row(row)
		row["host_load"] = manifest["host_load"]
		row["lifecycle"] = manifest["lifecycle"]
		facade._verify_pilot_row(row)
		receipt = facade.archive(committed)
		row["evidence_status"] = "archive"
		row["evidence_sha256"] = receipt["archive_sha256"]
		row["evidence_location"] = {
			"archive_uri": receipt["archive_uri"], "archive_sha256": receipt["archive_sha256"],
		}
		row["host_load"] = {"io_utilization": 0.5, "read_bytes_per_second": 500, "write_bytes_per_second": 500}
		with self.assertRaisesRegex(ArchiveContractError, "diagnostics disagree"):
			facade._verify_pilot_row(row)
		row["host_load"] = manifest["host_load"]
		facade._verify_pilot_row(row)
		for field, alias in (("pilot_repeat", True), ("pilot_repeat", 1.0), ("period", True), ("period", 1.0)):
			original = row[field]
			row[field] = alias
			with self.assertRaisesRegex(ArchiveContractError, "integer schedule"):
				facade._verify_pilot_row(row)
			row[field] = original
		for field, alias in (("coordinator_restart_count", 0.0), ("worker_restart_count", False)):
			lifecycle = dict(cast(dict[str, object], row["lifecycle"]))
			lifecycle[field] = alias
			row["lifecycle"] = lifecycle
			with self.assertRaisesRegex(ArchiveContractError, "exact integer zero"):
				facade._verify_pilot_row(row)
			row["lifecycle"] = manifest["lifecycle"]
		for field, alias in (("attempt", True), ("lifecycle_replicate", 1.0), ("period", True)):
			identity = dict(cast(dict[str, object], row["identity"]))
			identity[field] = alias
			row["identity"] = identity
			with self.assertRaisesRegex(ArchiveContractError, "integer schedule"):
				facade._verify_pilot_row(row)
			row["identity"] = lease.key.as_dict()

	def test_duplicate_same_attempt_archive_receipts_are_ambiguous(self):
		adapter = self._adapter(retention=0)
		facade = CampaignHarnessAdapter(self.ledger, adapter)
		lease = self._begin_raw(facade,
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

	def test_same_attempt_local_and_archive_compare_complete_validated_manifests(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter(retention=1))
		lease = self._begin_raw(facade,
			kind="discovery", cell="cell-d", manifest_hash="manifest-a", invocation_manifest={"phase": "discovery"}
		)
		committed = facade.publish_discovery_success(lease, self._phase("coherent-local", "discovery_correctness", 0.5))
		facade.archive(committed)
		phase = committed / "discovery"
		raw = b"docker_e2e=0.75\n"
		metric = json.dumps({"kind": "discovery_correctness", "seconds": 0.75}).encode()
		(phase / "raw_coordinator.log").write_bytes(raw)
		(phase / "metric.json").write_bytes(metric)
		checksums = json.loads((phase / "checksums.json").read_text(encoding="utf-8"))
		checksums["raw_coordinator.log"] = hashlib.sha256(raw).hexdigest()
		checksums["metric.json"] = hashlib.sha256(metric).hexdigest()
		(phase / "checksums.json").write_text(json.dumps(checksums, sort_keys=True), encoding="utf-8")
		manifest = json.loads((committed / "bundle_manifest.json").read_text(encoding="utf-8"))
		manifest["discovery_metric"] = {"kind": "discovery_correctness", "seconds": 0.75, "return_code": 0}
		manifest["discovery_checksums_sha256"] = hashlib.sha256((phase / "checksums.json").read_bytes()).hexdigest()
		(committed / "bundle_manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
		validated_metric = cast(dict[str, object], self.ledger.validate_committed(committed)["discovery_metric"])
		self.assertEqual(0.75, validated_metric["seconds"])
		decision = facade.exact_resume(lease.key)
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("same-attempt disagreement", str(decision.detail))

	def test_legacy_v1_local_success_cannot_satisfy_v2_resume(self):
		key, _ = self._commit(1, "legacy-token")
		decision = self._adapter().exact_resume(key)
		self.assertEqual(ResumeState.CORRUPT_OR_AMBIGUOUS, decision.state)
		self.assertIn("legacy v1", str(decision.detail))

	def test_empty_or_incomplete_discovery_cannot_allocate_pilot_lease(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		cell = "pilot_class=cheap|workload=kmeans|planner=DP|workers=1|profile=lan"
		for kind, candidate, invocation in (
			("performance", cell, {"phase": "pilot"}),
			("performance", "variance-probe", {"phase": "performance"}),
			("performance", "arbitrary-final", {"phase": "final"}),
			("discovery", "arbitrary-discovery", {"phase": "discovery"}),
		):
			with self.subTest(cell=candidate), self.assertRaisesRegex(ArchiveContractError, "generic begin is deprecated"):
				facade.begin(
					kind=kind, cell=candidate, manifest_hash="a" * 64,
					invocation_manifest=invocation, lifecycle_replicate=1,
					period=1, order="DP>FedAll>Heuristic>Exact",
				)
		with self.assertRaisesRegex(ArchiveContractError, "completion schema"):
			facade.begin_pilot(
				pilot_class="cheap", planner="DP", workers=1, profile="lan", pilot_repeat=1,
				preregistration_manifest_sha256="a" * 64, discovery_completion_receipt={},
			)
		with self.assertRaisesRegex(ArchiveContractError, "class/planner"):
			facade.begin_pilot(
				pilot_class="cheap", planner="CP", workers=1, profile="lan", pilot_repeat=1,
				preregistration_manifest_sha256="a" * 64, discovery_completion_receipt={},
			)
		with self.assertRaisesRegex(ArchiveContractError, "regime"):
			facade.begin_pilot(
				pilot_class="cheap", planner="DP", workers=cast(Any, True), profile="lan", pilot_repeat=1,
				preregistration_manifest_sha256="a" * 64, discovery_completion_receipt={},
			)
		with self.assertRaisesRegex(ArchiveContractError, "exact integer"):
			facade.begin_pilot(
				pilot_class="cheap", planner="DP", workers=1, profile="lan", pilot_repeat=cast(Any, 1.0),
				preregistration_manifest_sha256="a" * 64, discovery_completion_receipt={},
			)
		self.assertEqual([], list((self.root / "ledger" / "intents" / "performance").glob("*.json")))
		self.assertEqual([], list((self.root / "ledger" / "intents" / "discovery").glob("*.json")))
		forged_final = {"schema": "systemds-federated-docker-campaign/v4"}
		forged_final["manifest_hash"] = hashlib.sha256(json.dumps(
			forged_final, sort_keys=True, separators=(",", ":")
		).encode()).hexdigest()
		with self.assertRaises(ArchiveContractError):
			facade.begin_final_performance(
				preregistration_manifest={}, discovery_completion_receipt={},
				pilot_selection_receipt={}, pilot_resource_reservation={},
				final_campaign_manifest=forged_final,
				cell="workers=1|planner=DP|workload=kmeans|profile=lan",
				lifecycle_replicate=1, period=1, order="DP>FedAll>Heuristic>Exact",
			)
		self.assertEqual([], list((self.root / "ledger" / "intents" / "performance").glob("*.json")))
		self.assertFalse(hasattr(facade, "_allocate_attempt"))
		module = __import__("scripts.federated_campaign.hdfs_archive", fromlist=["*"])
		self.assertNotIn("_FACADE_ALLOCATION_CAPABILITY", vars(module))

	def test_typed_pilot_rows_flow_through_selection_resource_final_and_final_begin(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		prereg_hash, completion_hash = "a" * 64, "b" * 64
		prereg: dict[str, object] = {
			"preregistration_manifest_sha256": prereg_hash,
			"dimensions": {"block_ids": list(campaign_block_ids())}, "seed_streams": {"schedule": 19},
			"lineage": {"stage_descriptor_sha256": "1" * 64, "cp_lifecycle_descriptor_sha256": "2" * 64, "reference_manifest_sha256": "3" * 64},
			"frozen_core": {}, "resource_settings": {}, "commands": {},
			"pilot_preregistration": {}, "conservative_pre_pilot_bounds": {},
		}
		completion = {"preregistration_manifest_sha256": prereg_hash, "discovery_completion_sha256": completion_hash}
		rows: list[dict[str, object]] = []
		orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)
		allocation_count = 0
		def allocate(**kwargs: Any):
			nonlocal allocation_count
			allocation_count += 1
			key = PerformanceKey(
				kwargs["cell"], kwargs["lifecycle_replicate"], kwargs["period"], kwargs["order"],
				kwargs["manifest_hash"], 1, f"typed-{allocation_count}",
			)
			invocation_hash = hashlib.sha256(json.dumps(
				kwargs["invocation_manifest"], sort_keys=True, separators=(",", ":"), ensure_ascii=True,
			).encode()).hexdigest()
			return AttemptLease(key, invocation_hash, self.root / f"intent-{allocation_count}")
		with (
			patch("scripts.federated_campaign.hdfs_archive.validate_discovery_completion_receipt", return_value=completion),
			patch.object(self.ledger, "_begin_attempt_from_adapter", side_effect=allocate),
			patch.object(facade, "publish_performance_success", return_value=self.root / "published") as publish,
			patch.object(facade, "_verify_pilot_row", return_value=None),
			patch("scripts.federated_campaign.determinism_contract.validate_campaign_preregistration_manifest", return_value=prereg),
			patch("scripts.federated_campaign.determinism_contract.validate_discovery_completion_receipt", return_value=completion),
		):
			for pilot_class, workload in (("cheap", "kmeans"), ("medium", "logreg"), ("heavy", "als")):
				for planner in CAMPAIGN_PLANNERS:
					for workers, profile in ((1, "lan"), (4, "wan_mid")):
						cell = f"pilot_class={pilot_class}|workload={workload}|planner={planner}|workers={workers}|profile={profile}"
						for repeat in range(1, 6):
							lease = facade.begin_pilot(
								pilot_class=pilot_class, planner=planner, workers=workers, profile=profile,
								pilot_repeat=repeat, preregistration_manifest_sha256=prereg_hash,
								discovery_completion_receipt=completion,
							)
							facade.publish_performance_success(lease, self.root, self.root, self.root)
							order_tuple = orders[repeat - 1]; period = order_tuple.index(planner) + 1
							warm_seconds = 100.0 + (repeat - 3) * 0.5
							rows.append({
								"pilot_class": pilot_class, "workload": workload, "planner": planner,
								"workers": workers, "profile": profile, "cell": cell, "pilot_repeat": repeat,
								"warm_seconds": warm_seconds, "period": period, "order": ">".join(order_tuple),
								"carryover": "NONE" if period == 1 else order_tuple[period - 2],
								"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
								"lifecycle": {"cold_seconds": 2.0, "warm_seconds": warm_seconds, "coordinator_restart_count": 0, "worker_restart_count": 0},
								"evidence_status": "committed", "evidence_sha256": f"{len(rows)+1:064x}",
								"identity": lease.key.as_dict(), "evidence_location": {"committed_path": f"/typed/{len(rows)+1}"},
								"invocation_manifest_sha256": lease.invocation_manifest_sha256,
								"resource_evidence": {"artifact_bytes": 1000 + len(rows), "artifact_inodes": 20 + repeat, "lifecycle_wall_seconds": 105.0 + repeat},
							})
			selection = facade.select_pilot_repeats(
				rows, expected_manifest_hash=prereg_hash, preregistration_manifest=prereg,
				discovery_completion_receipt=completion,
			)
			reservation = facade.build_pilot_resource_reservation(
				pilot_selection_receipt=selection, preregistration_manifest=prereg, discovery_completion_receipt=completion,
			)
			final = facade.build_final_campaign_manifest(
				preregistration_manifest=prereg, pilot_selection_receipt=selection,
				pilot_resource_reservation=reservation, discovery_completion_receipt=completion,
			)
			first_block = cast(dict[str, object], cast(list[object], cast(dict[str, object], final["schedule"])["blocks"])[0])
			run = cast(dict[str, object], cast(list[object], first_block["runs"])[0]); order = cast(str, run["order"])
			period = next(cast(int, item["period"]) for item in cast(list[dict[str, object]], run["periods"]) if item["planner"] == "DP")
			invocation = {"schema": "systemds-federated-final-invocation/v1", "kind": "final_performance", "manifest_hash": final["manifest_hash"], "cell": "workers=1|planner=DP|workload=kmeans|profile=lan", "lifecycle_replicate": 1, "period": period, "order": order}
			with (
				patch("scripts.federated_campaign.hdfs_archive.validate_final_campaign_manifest", return_value=final),
				patch("scripts.federated_campaign.hdfs_archive.build_canonical_final_invocation", return_value=invocation),
			):
				final_lease = facade.begin_final_performance(
					preregistration_manifest=prereg, discovery_completion_receipt=completion,
					pilot_selection_receipt=selection, pilot_resource_reservation=reservation,
					final_campaign_manifest=final, cell=cast(str, invocation["cell"]),
					lifecycle_replicate=1, period=period, order=order,
				)
		self.assertEqual(120, publish.call_count)
		self.assertEqual(121, allocation_count)
		self.assertIsInstance(final_lease.key, PerformanceKey)

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

	def test_facade_preflight_does_not_coerce_invalid_requirement_types(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		snapshot = HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0)
		valid: dict[str, Any] = {
			"required_free_bytes": 5 * 1024**3, "required_free_inodes": 10, "required_seconds": 100,
			"max_io_utilization": 0.1, "max_combined_io_bps": 100,
		}
		for override in (
			{"required_free_bytes": True}, {"required_free_bytes": 5.5}, {"required_free_inodes": True},
			{"required_free_inodes": 1.5}, {"required_seconds": float("nan")},
		):
			requirements = dict(valid)
			requirements.update(override)
			with self.assertRaises(ArchiveContractError):
				facade.preflight(lambda: snapshot, **requirements)

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
		lease = self._begin_raw(facade,
			kind="performance", cell="cell-p", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]},
			lifecycle_replicate=1, period=1, order="DP>FedAll>Heuristic>Exact",
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

	def test_repeated_same_planner_barrier_reuses_verified_immutable_receipt_scope(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		seen = []
		def success(key):
			seen.append(key.cell)
			return ResumeDecision(ResumeState.LATEST_SUCCESS, 1, {
				"identity": key.as_dict(), "status": "success",
			})
		with (
			patch.object(facade, "exact_resume", side_effect=success),
			patch.object(facade._archive, "_receipt_scope_digest", return_value="a" * 64, create=True),
		):
			first = facade.assert_planner_barrier("DP", "manifest-a")
			second = facade.assert_planner_barrier("DP", "manifest-a")
		self.assertEqual(84, len(seen))
		self.assertEqual(84, first["verified_cells"])
		self.assertEqual(first, second)

	def test_immutable_receipt_scope_reuses_one_validated_catalog_index_per_catalog_version(self):
		key, committed = self._commit(1, "scope-token")
		adapter = self._adapter(retention=1)
		adapter.archive(committed)
		with patch.object(adapter, "catalog", wraps=adapter.catalog) as catalog:
			first = adapter._receipt_scope_digest((key.as_dict(),))
			second = adapter._receipt_scope_digest((key.as_dict(),))
		self.assertRegex(cast(str, first), r"^[0-9a-f]{64}$")
		self.assertEqual(first, second)
		self.assertEqual(1, catalog.call_count)

	def test_immutable_receipt_scope_ignores_retention_and_unrelated_catalog_growth(self):
		first_key, first_committed = self._commit(1, "scope-first")
		adapter = self._adapter(retention=1)
		adapter.archive(first_committed)
		before = adapter._receipt_scope_digest((first_key.as_dict(),))
		_, second_committed = self._commit(2, "scope-second")
		adapter.archive(second_committed)
		after = adapter._receipt_scope_digest((first_key.as_dict(),))
		self.assertEqual(before, after)

	def test_cached_planner_barrier_fails_closed_when_immutable_receipt_scope_changes(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		def success(key):
			return ResumeDecision(ResumeState.LATEST_SUCCESS, 1, {
				"identity": key.as_dict(), "status": "success",
			})
		with (
			patch.object(facade, "exact_resume", side_effect=success) as resume,
			patch.object(
				facade._archive, "_receipt_scope_digest",
				side_effect=("a" * 64, "b" * 64), create=True,
			),
		):
			facade.assert_planner_barrier("DP", "manifest-a")
			with self.assertRaisesRegex(ArchiveContractError, "changed after full verification"):
				facade.assert_planner_barrier("DP", "manifest-a")
		self.assertEqual(84, resume.call_count)

	def test_discovery_begin_enforces_previous_global_planner_barrier(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		cell = "workers=1|planner=FedAll|workload=kmeans|profile=lan"
		prereg = {"preregistration_manifest_sha256": "manifest-a"}
		invocation = {"schema": "canonical-discovery", "preregistration_manifest_sha256": "manifest-a"}
		with (
			patch("scripts.federated_campaign.hdfs_archive.validate_campaign_preregistration_manifest", return_value=prereg),
			patch("scripts.federated_campaign.hdfs_archive.build_canonical_discovery_invocation", return_value=invocation),
			patch.object(facade, "assert_planner_barrier", return_value={"verified_cells": 84}) as barrier,
		):
			lease = facade.begin_discovery(preregistration_manifest=prereg, cell=cell)
		barrier.assert_called_once_with("DP", "manifest-a")
		self.assertEqual(cell, lease.key.cell)

	def test_typed_allocation_authority_rejects_replay_mutation_and_rebinding_without_intent(self):
		facade = CampaignHarnessAdapter(self.ledger, self._adapter())
		intent_root = self.root / "ledger" / "intents"
		before_bind = len(list(intent_root.rglob("*.json")))
		with self.assertRaisesRegex(LedgerContractError, "binding"):
			self.ledger._bind_typed_allocation_validator(lambda _authority, _request: None)
		with self.assertRaisesRegex((LedgerContractError, ArchiveContractError), "binding"):
			CampaignHarnessAdapter(self.ledger, self._adapter())
		self.assertEqual(before_bind, len(list(intent_root.rglob("*.json"))))
		cell = "workers=1|planner=DP|workload=kmeans|profile=lan"
		prereg = {"preregistration_manifest_sha256": "manifest-a"}
		invocation = {"schema": "canonical-discovery", "preregistration_manifest_sha256": "manifest-a"}
		captured: list[tuple[object, Mapping[str, object]]] = []
		original_validator = self.ledger._validate_allocation_authority
		def capture(authority: object, request: Mapping[str, object]) -> None:
			captured.append((authority, dict(request)))
			original_validator(authority, request)
		with (
			patch("scripts.federated_campaign.hdfs_archive.validate_campaign_preregistration_manifest", return_value=prereg),
			patch("scripts.federated_campaign.hdfs_archive.build_canonical_discovery_invocation", return_value=invocation),
			patch.object(self.ledger, "_validate_allocation_authority", side_effect=capture),
		):
			facade.begin_discovery(preregistration_manifest=prereg, cell=cell)
		authority, request = captured[0]
		intent_count = len(list(intent_root.rglob("*.json")))
		with self.assertRaisesRegex(ArchiveContractError, "stale"):
			self.ledger._begin_attempt_from_adapter(
				kind=cast(str, request["kind"]), cell=cast(str, request["cell"]),
				manifest_hash=cast(str, request["manifest_hash"]),
				invocation_manifest=cast(Mapping[str, object], request["invocation_manifest"]),
				minimum_attempt=cast(int, request["minimum_attempt"]), _allocation_authority=authority,
			)
		self.assertEqual(intent_count, len(list(intent_root.rglob("*.json"))))

		ledger = AtomicEvidenceLedger(self.root / "mutated-ledger")
		mutated_facade = CampaignHarnessAdapter(ledger, HdfsArchiveAdapter(
			ledger, self.backend, self.root / "mutated-archive", "hdfs://dams-so001:12000/tmp/logs/mchoi-g007-mutated",
			max_local_raw_bundles=1,
		))
		unconsumed: dict[str, object] = {}
		def intercept(**kwargs: object) -> AttemptLease:
			unconsumed.update(kwargs)
			return AttemptLease(DiscoveryKey(cell, 1, "captured", "manifest-a"), "c" * 64, self.root / "captured")
		with (
			patch("scripts.federated_campaign.hdfs_archive.validate_campaign_preregistration_manifest", return_value=prereg),
			patch("scripts.federated_campaign.hdfs_archive.build_canonical_discovery_invocation", return_value=invocation),
			patch.object(ledger, "_begin_attempt_from_adapter", side_effect=intercept),
		):
			mutated_facade.begin_discovery(preregistration_manifest=prereg, cell=cell)
		with self.assertRaisesRegex(ArchiveContractError, "mismatched"):
			ledger._begin_attempt_from_adapter(
				kind="discovery", cell="workers=2|planner=DP|workload=kmeans|profile=lan",
				manifest_hash="manifest-a", invocation_manifest=invocation,
				minimum_attempt=1, _allocation_authority=unconsumed["_allocation_authority"],
			)
		self.assertFalse(any((self.root / "mutated-ledger" / "intents").rglob("*.json")))

	def test_latest_local_failure_never_falls_back_to_archived_success(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=0)
		adapter.archive(committed)
		lease = self._begin_raw(CampaignHarnessAdapter(self.ledger, adapter),
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

	def test_preflight_rejects_nonfinite_boolean_and_out_of_range_resources(self):
		adapter = self._adapter()
		valid: dict[str, Any] = dict(
			required_free_bytes=100, required_free_inodes=10, required_seconds=100.0,
			max_io_utilization=0.1, max_combined_io_bps=100.0,
		)
		bad_requirements = (
			{"required_free_bytes": True}, {"required_free_inodes": True}, {"required_free_inodes": 1.5},
			{"required_seconds": True}, {"required_seconds": float("nan")}, {"max_io_utilization": float("inf")},
			{"max_io_utilization": 1.1}, {"max_combined_io_bps": float("nan")},
		)
		for override in bad_requirements:
			arguments = dict(valid)
			arguments.update(override)
			with self.assertRaises(ArchiveContractError):
				adapter.preflight_next_lifecycle(
					lambda: HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, 0), **arguments,
				)
		bad_snapshots = (
			HostResourceSnapshot(cast(int, True), 100, 1000, 0.01, 0, 0),
			HostResourceSnapshot(6 * 1024**3, cast(int, True), 1000, 0.01, 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, cast(float, True), 0.01, 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, float("nan"), 0.01, 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, cast(float, True), 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, float("inf"), 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, 1.1, 0, 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, float("nan"), 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, cast(float, True), 0),
			HostResourceSnapshot(6 * 1024**3, 100, 1000, 0.01, 0, float("inf")),
		)
		for snapshot in bad_snapshots:
			with self.assertRaises(ArchiveContractError):
				adapter.preflight_next_lifecycle(lambda snapshot=snapshot: snapshot, **valid)


if __name__ == "__main__":
	unittest.main()
