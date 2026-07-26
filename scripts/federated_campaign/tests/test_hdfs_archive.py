# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.federated_campaign.atomic_ledger import AtomicEvidenceLedger, DiscoveryKey
from scripts.federated_campaign.hdfs_archive import (
	ArchiveContractError,
	HdfsArchiveAdapter,
	HostResourceSnapshot,
)


class FakeHdfsBackend:
	def __init__(self):
		self.objects = {}
		self.events = []
		self.fail_rename = False

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
		if remote_uri not in self.objects:
			raise RuntimeError("missing")
		Path(local_path).write_bytes(self.objects[remote_uri])

	def exists(self, remote_uri):
		return remote_uri in self.objects

	def delete(self, remote_uri):
		self.objects.pop(remote_uri, None)


class HdfsArchiveAdapterTest(unittest.TestCase):
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
		return key, self.ledger.publish_success(key, cold, warm, shared)

	def _adapter(self, retention=1):
		return HdfsArchiveAdapter(
			self.ledger,
			self.backend,
			self.root / "archive-work",
			"hdfs://dams-so001:12000/tmp/logs/mchoi-g007-unit",
			max_local_raw_bundles=retention,
		)

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
		with self.assertRaisesRegex(ArchiveContractError, "archive"):
			adapter = self._adapter(retention=0)
			adapter.archive(committed)
		self.assertTrue(committed.exists())
		self.assertEqual([], adapter.catalog())

	def test_remote_corruption_fails_preflight_before_next_lifecycle(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		receipt = adapter.archive(committed)
		self.backend.objects[receipt["archive_uri"]] = b"corrupt"
		with self.assertRaisesRegex(ArchiveContractError, "remote"):
			adapter.preflight_next_lifecycle(
				lambda: HostResourceSnapshot(10_000, 100, 1000, 0.01, 0, 0),
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
			return HostResourceSnapshot(10_000, 100, 1000, 0.01, 0, 0)
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

	def test_latest_local_failure_never_falls_back_to_archived_success(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter(retention=0)
		adapter.archive(committed)
		self.ledger.publish_failure(DiscoveryKey("cell-a", 2, "token-b", "manifest-a"), "timeout")
		gets_before = sum(event[0] == "get" for event in self.backend.events)
		self.assertIsNone(adapter.latest_discovery_success("cell-a", "manifest-a"))
		self.assertEqual(gets_before, sum(event[0] == "get" for event in self.backend.events))

	def test_preflight_samples_host_after_remote_verification_and_rejects_busy_io(self):
		_, committed = self._commit(1, "token-a")
		adapter = self._adapter()
		adapter.archive(committed)
		def snapshot():
			self.backend.events.append(("snapshot",))
			return HostResourceSnapshot(10_000, 100, 1000, 0.5, 0, 0)
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
