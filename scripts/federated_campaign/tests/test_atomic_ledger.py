# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.federated_campaign.atomic_ledger import (
	PUBLICATION_BOUNDARIES,
	AtomicEvidenceLedger,
	DiscoveryKey,
	InjectedPublicationCrash,
	LedgerContractError,
	PerformanceKey,
)


class AtomicEvidenceLedgerTest(unittest.TestCase):
	def setUp(self):
		self.temp_dir = tempfile.TemporaryDirectory()
		self.root = Path(self.temp_dir.name)

	def tearDown(self):
		self.temp_dir.cleanup()

	def _phase(self, name, metric_kind, seconds=1.25):
		phase = self.root / name
		phase.mkdir()
		raw_log = (
			f"Total execution time: {seconds} sec.\n"
			if metric_kind == "systemds_total_execution_time"
			else f"docker_e2e={seconds}\n"
		).encode()
		files = {
			"raw_coordinator.log": raw_log,
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

	def _shared_manifest(self, key, cold, warm, name="shared.json"):
		manifest = {
			"identity": key.as_dict(),
			"cold_checksums_sha256": hashlib.sha256((cold / "checksums.json").read_bytes()).hexdigest(),
			"warm_checksums_sha256": hashlib.sha256((warm / "checksums.json").read_bytes()).hexdigest(),
		}
		path = self.root / name
		path.write_text(json.dumps(manifest, sort_keys=True), encoding="utf-8")
		return path

	def _publish_discovery(self, ledger, key, suffix=""):
		cold = self._phase(f"cold{suffix}", "docker_e2e", 2.5)
		warm = self._phase(f"warm{suffix}", "systemds_total_execution_time", 1.25)
		shared = self._shared_manifest(key, cold, warm, f"shared{suffix}.json")
		return ledger.publish_success(key, cold, warm, shared)

	def test_discovery_key_contains_exact_identity_fields(self):
		key = DiscoveryKey("cell-a", 2, "token-a", "manifest-a")
		self.assertEqual(
			{"kind": "discovery", "cell": "cell-a", "attempt": 2, "run_token": "token-a", "manifest_hash": "manifest-a"},
			key.as_dict(),
		)

	def test_performance_key_contains_exact_identity_fields(self):
		key = PerformanceKey("cell-a", 3, 2, "FedAll>DP", "manifest-a")
		self.assertEqual(
			{
				"kind": "performance",
				"cell": "cell-a",
				"lifecycle_replicate": 3,
				"period": 2,
				"order": "FedAll>DP",
				"manifest_hash": "manifest-a",
			},
			key.as_dict(),
		)

	def test_performance_success_resumes_only_exact_period_order_identity(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = PerformanceKey("cell-a", 3, 2, "FedAll>DP", "manifest-a")
		cold = self._phase("performance-cold", "docker_e2e", 2.5)
		warm = self._phase("performance-warm", "systemds_total_execution_time", 1.25)
		shared = self._shared_manifest(key, cold, warm, "performance-shared.json")
		ledger.publish_success(key, cold, warm, shared)
		wrong_order = PerformanceKey("cell-a", 3, 2, "DP>FedAll", "manifest-a")
		self.assertIsNone(ledger.performance_success(wrong_order))

	def test_success_requires_both_complete_phase_bundles(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		cold = self._phase("cold", "docker_e2e")
		missing_warm = self.root / "missing-warm"
		shared = self.root / "shared.json"
		shared.write_text("{}", encoding="utf-8")
		with self.assertRaises(LedgerContractError):
			ledger.publish_success(key, cold, missing_warm, shared)

	def test_success_rejects_stale_shared_manifest_identity(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		cold = self._phase("cold", "docker_e2e")
		warm = self._phase("warm", "systemds_total_execution_time")
		stale_key = DiscoveryKey("cell-a", 1, "token-a", "stale-manifest")
		shared = self._shared_manifest(stale_key, cold, warm)
		with self.assertRaisesRegex(LedgerContractError, "identity"):
			ledger.publish_success(key, cold, warm, shared)

	def test_committed_success_resumes_only_after_checksum_validation(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		self._publish_discovery(ledger, key)
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertEqual(1, restarted.latest_discovery_success("cell-a", "manifest-a")["identity"]["attempt"])

	def test_corrupt_committed_output_is_rejected_on_resume(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		committed = self._publish_discovery(ledger, key)
		(committed / "warm" / "output.bin").write_bytes(b"corrupt")
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertIsNone(restarted.latest_discovery_success("cell-a", "manifest-a"))

	def test_latest_corrupt_attempt_never_backfills_older_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"), "-one")
		latest = self._publish_discovery(
			ledger, DiscoveryKey("cell-a", 2, "token-b", "manifest-a"), "-two"
		)
		(latest / "warm" / "output.bin").write_bytes(b"corrupt")
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertIsNone(restarted.latest_discovery_success("cell-a", "manifest-a"))

	def test_latest_failed_attempt_never_backfills_older_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"))
		ledger.publish_failure(DiscoveryKey("cell-a", 2, "token-b", "manifest-a"), "timeout")
		self.assertIsNone(ledger.latest_discovery_success("cell-a", "manifest-a"))

	def test_orphan_staging_directory_is_quarantined_on_restart(self):
		ledger_root = self.root / "ledger"
		orphan = ledger_root / "staging" / "orphan"
		orphan.mkdir(parents=True)
		(orphan / "partial").write_text("partial", encoding="utf-8")
		AtomicEvidenceLedger(ledger_root)
		self.assertFalse(orphan.exists())
		self.assertTrue(any((ledger_root / "quarantine").iterdir()))

	def test_crash_at_each_boundary_recovers_old_or_new_complete_record(self):
		for boundary in PUBLICATION_BOUNDARIES:
			with self.subTest(boundary=boundary):
				case_root = self.root / boundary
				ledger = AtomicEvidenceLedger(case_root / "ledger")
				old_key = DiscoveryKey("cell-a", 1, "old-token", "manifest-a")
				old_cold = self._phase(f"{boundary}-old-cold", "docker_e2e", 2.5)
				old_warm = self._phase(f"{boundary}-old-warm", "systemds_total_execution_time", 1.25)
				old_shared = self._shared_manifest(old_key, old_cold, old_warm, f"{boundary}-old-shared.json")
				ledger.publish_success(old_key, old_cold, old_warm, old_shared)
				new_key = DiscoveryKey("cell-a", 2, "new-token", "manifest-a")
				new_cold = self._phase(f"{boundary}-new-cold", "docker_e2e", 2.4)
				new_warm = self._phase(f"{boundary}-new-warm", "systemds_total_execution_time", 1.2)
				new_shared = self._shared_manifest(new_key, new_cold, new_warm, f"{boundary}-new-shared.json")
				with self.assertRaises(InjectedPublicationCrash):
					ledger.publish_success(new_key, new_cold, new_warm, new_shared, crash_after=boundary)
				restarted = AtomicEvidenceLedger(case_root / "ledger")
				record = restarted.latest_discovery_success("cell-a", "manifest-a")
				self.assertIn(record["identity"]["attempt"], (1, 2))


if __name__ == "__main__":
	unittest.main()
