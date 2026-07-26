# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import cast

from scripts.federated_campaign.atomic_ledger import (
	PUBLICATION_BOUNDARIES,
	DISCOVERY_PUBLICATION_BOUNDARIES,
	FAILURE_PUBLICATION_BOUNDARIES,
	AtomicEvidenceLedger,
	DiscoveryKey,
	InjectedPublicationCrash,
	LedgerContractError,
	PerformanceKey,
	ResumeState,
)


class AtomicEvidenceLedgerTest(unittest.TestCase):
	temp_dir = cast(tempfile.TemporaryDirectory[str], object())
	root = cast(Path, object())

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
		return ledger.publish_legacy_success_for_migration(key, cold, warm, shared)

	def _failure(self, name="failure", return_code=17):
		bundle = self.root / name
		bundle.mkdir(parents=True)
		files = {
			"raw_coordinator.log": b"coordinator failed\n",
			"raw_worker.log": b"worker failed\n",
			"raw_compose.log": b"compose failed\n",
			"output.bin": b"partial-output",
			"semantic_oracle.json": json.dumps({"passed": False}).encode(),
			"metric.json": json.dumps({"kind": "failure", "seconds": 1.0}).encode(),
			"parser.json": json.dumps({"passed": False, "diagnostic": "missing metric"}).encode(),
			"return_code.txt": f"{return_code}\n".encode(),
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
				"attempt": 1,
				"run_token": "legacy-performance-attempt",
			},
			key.as_dict(),
		)

	def test_performance_success_resumes_only_exact_period_order_identity(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = PerformanceKey("cell-a", 3, 2, "FedAll>DP", "manifest-a")
		cold = self._phase("performance-cold", "docker_e2e", 2.5)
		warm = self._phase("performance-warm", "systemds_total_execution_time", 1.25)
		shared = self._shared_manifest(key, cold, warm, "performance-shared.json")
		ledger.publish_legacy_success_for_migration(key, cold, warm, shared)
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
			ledger.publish_legacy_success_for_migration(key, cold, missing_warm, shared)

	def test_success_rejects_stale_shared_manifest_identity(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		cold = self._phase("cold", "docker_e2e")
		warm = self._phase("warm", "systemds_total_execution_time")
		stale_key = DiscoveryKey("cell-a", 1, "token-a", "stale-manifest")
		shared = self._shared_manifest(stale_key, cold, warm)
		with self.assertRaisesRegex(LedgerContractError, "identity"):
			ledger.publish_legacy_success_for_migration(key, cold, warm, shared)

	def test_committed_success_resumes_only_after_checksum_validation(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		key = DiscoveryKey("cell-a", 1, "token-a", "manifest-a")
		self._publish_discovery(ledger, key)
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		record = restarted.latest_discovery_success("cell-a", "manifest-a")
		self.assertIsNotNone(record)
		assert record is not None
		identity = cast(dict[str, object], record["identity"])
		self.assertEqual(1, identity["attempt"])

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

	def test_commit_rename_crash_then_corruption_preserves_latest_identity_and_blocks_stale_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"), "-old")
		key = DiscoveryKey("cell-a", 2, "token-b", "manifest-a")
		cold = self._phase("rename-crash-cold", "docker_e2e", 2.4)
		warm = self._phase("rename-crash-warm", "systemds_total_execution_time", 1.2)
		shared = self._shared_manifest(key, cold, warm, "rename-crash-shared.json")
		with self.assertRaises(InjectedPublicationCrash):
			ledger.publish_legacy_success_for_migration(key, cold, warm, shared, crash_after="after_commit_rename")
		record_id = hashlib.sha256(
			json.dumps(key.as_dict(), sort_keys=True, separators=(",", ":")).encode()
		).hexdigest()
		latest = ledger.committed / "discovery" / record_id
		(latest / "warm" / "output.bin").write_bytes(b"corrupt-after-rename")
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertIsNone(restarted.latest_discovery_success("cell-a", "manifest-a"))
		latest_summary = next(record for record in restarted.record_summaries() if (
			isinstance(record.get("identity"), dict)
			and cast(dict[str, object], record["identity"]).get("attempt") == 2
		))
		self.assertEqual("success", latest_summary["status"])
		self.assertFalse(latest_summary["valid"])

	def test_commit_rename_crash_then_unreadable_manifest_preserves_latest_identity_and_blocks_stale_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"), "-old-state")
		key = DiscoveryKey("cell-a", 2, "token-b", "manifest-a")
		cold = self._phase("state-crash-cold", "docker_e2e", 2.4)
		warm = self._phase("state-crash-warm", "systemds_total_execution_time", 1.2)
		shared = self._shared_manifest(key, cold, warm, "state-crash-shared.json")
		with self.assertRaises(InjectedPublicationCrash):
			ledger.publish_legacy_success_for_migration(key, cold, warm, shared, crash_after="after_commit_rename")
		record_id = hashlib.sha256(
			json.dumps(key.as_dict(), sort_keys=True, separators=(",", ":")).encode()
		).hexdigest()
		latest = ledger.committed / "discovery" / record_id
		(latest / "bundle_manifest.json").write_bytes(b"{unreadable")
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertIsNone(restarted.latest_discovery_success("cell-a", "manifest-a"))
		latest_summary = next(record for record in restarted.record_summaries() if (
			isinstance(record.get("identity"), dict)
			and cast(dict[str, object], record["identity"]).get("attempt") == 2
		))
		self.assertEqual("success", latest_summary["status"])
		self.assertFalse(latest_summary["valid"])

	def test_unidentified_invalid_discovery_directory_fails_closed_against_stale_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"), "-known")
		unknown = ledger.committed / "discovery" / ("f" * 64)
		unknown.mkdir()
		(unknown / "bundle_manifest.json").write_bytes(b"unreadable")
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertIsNone(restarted.latest_discovery_success("cell-a", "manifest-a"))

	def test_latest_failed_attempt_never_backfills_older_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"))
		lease = ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]}
		)
		ledger.publish_failure(lease, self._failure())
		self.assertIsNone(ledger.latest_discovery_success("cell-a", "manifest-a"))
		self.assertEqual(ResumeState.LATEST_FAILED, ledger.resume_discovery("cell-a", "manifest-a").state)

	def test_begin_attempt_is_durable_monotonic_and_blocks_stale_success(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		self._publish_discovery(ledger, DiscoveryKey("cell-a", 1, "token-a", "manifest-a"))
		lease = ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"argv": ["docker"]}
		)
		self.assertEqual(2, lease.key.attempt)
		self.assertEqual(ResumeState.IN_PROGRESS_OR_ABANDONED, ledger.resume_discovery("cell-a", "manifest-a").state)
		restarted = AtomicEvidenceLedger(self.root / "ledger")
		self.assertEqual(ResumeState.IN_PROGRESS_OR_ABANDONED, restarted.resume_discovery("cell-a", "manifest-a").state)

	def test_two_process_views_allocate_distinct_monotonic_attempts(self):
		root = self.root / "ledger"
		first = AtomicEvidenceLedger(root).begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"process": 1}
		)
		second = AtomicEvidenceLedger(root).begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"process": 2}
		)
		self.assertEqual((1, 2), (first.key.attempt, second.key.attempt))
		self.assertNotEqual(first.key.run_token, second.key.run_token)

	def test_concurrent_allocators_are_serialized_by_durable_lock(self):
		root = self.root / "ledger"
		ledgers = (AtomicEvidenceLedger(root), AtomicEvidenceLedger(root))
		def allocate(index):
			return ledgers[index].begin_attempt(
				kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"process": index}
			)
		with ThreadPoolExecutor(max_workers=2) as pool:
			leases = list(pool.map(allocate, (0, 1)))
		self.assertEqual({1, 2}, {lease.key.attempt for lease in leases})
		self.assertEqual(2, len({lease.key.run_token for lease in leases}))

	def test_discovery_success_is_one_phase_and_performance_api_is_discriminated(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		lease = ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"phase": "discovery"}
		)
		bundle = self._phase("discovery", "discovery_correctness", 1.0)
		committed = ledger.publish_discovery_success(lease, bundle)
		manifest = ledger.validate_committed(committed)
		self.assertEqual("discovery", manifest["evidence_kind"])
		self.assertFalse((committed / "cold").exists())
		other = ledger.begin_attempt(
			kind="discovery", cell="cell-b", manifest_hash="manifest-a", invocation_manifest={"phase": "wrong"}
		)
		with self.assertRaisesRegex(LedgerContractError, "performance AttemptLease"):
			ledger.publish_performance_success(other, bundle, bundle, self.root / "missing")

	def test_discovery_success_crash_boundaries_are_fail_closed(self):
		for index, boundary in enumerate(DISCOVERY_PUBLICATION_BOUNDARIES, start=1):
			with self.subTest(boundary=boundary):
				ledger = AtomicEvidenceLedger(self.root / f"discovery-crash-{index}" / "ledger")
				lease = ledger.begin_attempt(
					kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"case": boundary}
				)
				bundle = self._phase(f"discovery-crash-{index}-bundle", "discovery_correctness", 1.0)
				with self.assertRaises(InjectedPublicationCrash):
					ledger.publish_discovery_success(lease, bundle, crash_after=boundary)
				decision = AtomicEvidenceLedger(ledger.root).resume_discovery("cell-a", "manifest-a")
				self.assertIn(decision.state, (ResumeState.IN_PROGRESS_OR_ABANDONED, ResumeState.LATEST_SUCCESS))

	def test_zero_return_code_semantic_failure_is_valid_failure_evidence(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		lease = ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"semantic": True}
		)
		committed = ledger.publish_failure(lease, self._failure("semantic-zero", return_code=0))
		manifest = ledger.validate_committed(committed, require_success=False)
		self.assertEqual("failed", manifest["status"])
		self.assertEqual("semantic_oracle", manifest["failure_category"])
		self.assertEqual({"passed": False}, manifest["semantic_oracle_summary"])
		self.assertRegex(str(manifest["semantic_oracle_sha256"]), r"^[0-9a-f]{64}$")
		parser_summary = cast(dict[str, object], manifest["parser_summary"])
		scan_summary = cast(dict[str, object], manifest["scan_summary"])
		self.assertEqual(False, parser_summary["passed"])
		self.assertIn("timeout", scan_summary)

	def test_bare_key_cannot_publish_v2_failure(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		with self.assertRaisesRegex(LedgerContractError, "durable AttemptLease"):
			ledger.publish_failure(DiscoveryKey("cell-a", 1, "token", "manifest"), self._failure())  # pyright: ignore[reportArgumentType]

	def test_failed_attempt_requires_complete_checksummed_bundle(self):
		ledger = AtomicEvidenceLedger(self.root / "ledger")
		lease = ledger.begin_attempt(
			kind="discovery", cell="cell-a", manifest_hash="manifest", invocation_manifest={"argv": ["docker"]}
		)
		bundle = self._failure("incomplete")
		(bundle / "raw_worker.log").unlink()
		with self.assertRaisesRegex(LedgerContractError, "raw_worker"):
			ledger.publish_failure(lease, bundle)

	def test_failure_crash_boundaries_never_publish_partial_valid_failure(self):
		for boundary in FAILURE_PUBLICATION_BOUNDARIES:
			with self.subTest(boundary=boundary):
				ledger = AtomicEvidenceLedger(self.root / f"failure-{boundary}" / "ledger")
				lease = ledger.begin_attempt(
					kind="discovery", cell="cell-a", manifest_hash="manifest-a", invocation_manifest={"case": boundary}
				)
				with self.assertRaises(InjectedPublicationCrash):
					ledger.publish_failure(lease, self._failure(f"bundle-{boundary}"), crash_after=boundary)
				restarted = AtomicEvidenceLedger(ledger.root)
				self.assertNotEqual(ResumeState.LATEST_SUCCESS, restarted.resume_discovery("cell-a", "manifest-a").state)

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
				ledger.publish_legacy_success_for_migration(old_key, old_cold, old_warm, old_shared)
				new_key = DiscoveryKey("cell-a", 2, "new-token", "manifest-a")
				new_cold = self._phase(f"{boundary}-new-cold", "docker_e2e", 2.4)
				new_warm = self._phase(f"{boundary}-new-warm", "systemds_total_execution_time", 1.2)
				new_shared = self._shared_manifest(new_key, new_cold, new_warm, f"{boundary}-new-shared.json")
				with self.assertRaises(InjectedPublicationCrash):
					ledger.publish_legacy_success_for_migration(new_key, new_cold, new_warm, new_shared, crash_after=boundary)
				restarted = AtomicEvidenceLedger(case_root / "ledger")
				record = restarted.latest_discovery_success("cell-a", "manifest-a")
				self.assertIsNotNone(record)
				assert record is not None
				identity = cast(dict[str, object], record["identity"])
				self.assertIn(identity["attempt"], (1, 2))


if __name__ == "__main__":
	unittest.main()
