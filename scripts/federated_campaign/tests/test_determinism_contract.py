# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.federated_campaign.determinism_contract import (
	CampaignContractError,
	ResourceSnapshot,
	build_block_counterbalanced_schedule,
	build_counterbalanced_schedule,
	build_frozen_manifest,
	check_resource_budget,
	summarize_variance_pilot,
	validate_phase_bundle,
)


class DeterminismContractTest(unittest.TestCase):
	def setUp(self):
		self.temp_dir = tempfile.TemporaryDirectory()
		self.root = Path(self.temp_dir.name)
		for relative, contents in {
			"systemds.jar": b"jar-v1",
			"SystemDS-config.xml": b"config-v1",
			"workload.dml": b"print('fixed')",
			"data/part-000.csv": b"1,2\n",
			"data/part-001.csv": b"3,4\n",
		}.items():
			path = self.root / relative
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_bytes(contents)

	def tearDown(self):
		self.temp_dir.cleanup()

	def manifest(self, seed=7331, block_schedule=None):
		return build_frozen_manifest(
			jar=self.root / "systemds.jar",
			image_id="sha256:image",
			image_digest="systemds@sha256:digest",
			config=self.root / "SystemDS-config.xml",
			dml=self.root / "workload.dml",
			dataset_root=self.root / "data",
			worker_mapping=("worker-0:8001", "worker-1:8002"),
			planner_order=("DP", "FedAll", "Heuristic", "MinST"),
			seed=seed,
			warmup_runs=1,
			measured_warm_runs=5,
			block_schedule=block_schedule,
		)

	def test_same_inputs_produce_same_manifest_hash(self):
		self.assertEqual(self.manifest()["manifest_hash"], self.manifest()["manifest_hash"])

	def test_dataset_drift_changes_manifest_hash(self):
		before = self.manifest()["manifest_hash"]
		(self.root / "data/part-001.csv").write_bytes(b"changed\n")
		self.assertNotEqual(before, self.manifest()["manifest_hash"])

	def test_seed_change_changes_manifest_hash(self):
		self.assertNotEqual(self.manifest(seed=7331)["manifest_hash"], self.manifest(seed=7332)["manifest_hash"])

	def test_missing_seed_fails_closed(self):
		with self.assertRaisesRegex(CampaignContractError, "seed"):
			build_frozen_manifest(
				jar=self.root / "systemds.jar",
				image_id="sha256:image",
				image_digest="systemds@sha256:digest",
				config=self.root / "SystemDS-config.xml",
				dml=self.root / "workload.dml",
				dataset_root=self.root / "data",
				worker_mapping=("worker-0:8001",),
				planner_order=("DP",),
				seed=None,
				warmup_runs=1,
				measured_warm_runs=3,
			)

	def test_manifest_records_declared_cold_and_warm_lifecycle(self):
		lifecycle = self.manifest()["lifecycle"]
		self.assertEqual("docker_e2e", lifecycle["cold_metric"])
		self.assertEqual("systemds_total_execution_time", lifecycle["warm_metric"])
		self.assertEqual("fresh", lifecycle["warm_coordinator_jvm"])
		self.assertEqual("reused_from_cold", lifecycle["warm_worker_containers"])

	def test_schedule_is_reproducible_for_same_seed(self):
		first = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "MinST"), 8, 19)
		second = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "MinST"), 8, 19)
		self.assertEqual(first, second)

	def test_schedule_places_each_planner_in_each_period_equally(self):
		schedule = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "MinST"), 8, 19)
		for period in range(4):
			counts = {planner: sum(row[period] == planner for row in schedule) for planner in schedule[0]}
			self.assertEqual({"DP": 2, "FedAll": 2, "Heuristic": 2, "MinST": 2}, counts)

	def test_schedule_balances_each_directed_carryover_pair(self):
		schedule = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "MinST"), 8, 19)
		pairs = {(left, right): 0 for left in schedule[0] for right in schedule[0] if left != right}
		for row in schedule:
			for left, right in zip(row, row[1:]):
				pairs[left, right] += 1
		self.assertEqual({2}, set(pairs.values()))

	def test_block_rotation_balances_periods_aggregate_for_3_5_7_repeats(self):
		for repeats in (3, 5, 7):
			with self.subTest(repeats=repeats):
				schedule = build_block_counterbalanced_schedule(
					("DP", "FedAll", "Heuristic", "MinST"), repeats, ("b0", "b1", "b2", "b3"), 19
				)
				for period_counts in schedule["aggregate_period_counts"].values():
					self.assertEqual({repeats}, set(period_counts.values()))

	def test_block_rotation_balances_directed_carryover_aggregate_for_3_5_7_repeats(self):
		for repeats in (3, 5, 7):
			with self.subTest(repeats=repeats):
				schedule = build_block_counterbalanced_schedule(
					("DP", "FedAll", "Heuristic", "MinST"), repeats, ("b0", "b1", "b2", "b3"), 19
				)
				self.assertEqual({repeats}, set(schedule["aggregate_directed_carryover_counts"].values()))

	def test_block_rotation_records_unbalanced_within_cell_remainder_honestly(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		self.assertTrue(all(block["within_cell_fully_balanced"] is False for block in schedule["blocks"]))

	def test_frozen_manifest_persists_aggregate_balanced_block_schedule(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		manifest = self.manifest(block_schedule=schedule)
		self.assertEqual(schedule, manifest["block_schedule"])

	def test_block_rotation_persists_block_period_and_order(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 3, tuple(f"workload=w{i}|workers=1|network=lan" for i in range(4)), 19
		)
		first = schedule["blocks"][0]["runs"][0]
		self.assertEqual({"lifecycle_replicate", "periods", "order", "williams_row"}, set(first))

	def test_resource_preflight_rejects_disk_below_margin_and_floor(self):
		with self.assertRaisesRegex(CampaignContractError, "disk"):
			check_resource_budget(
				remaining_lifecycles=10,
				p95_artifact_bytes=100,
				p95_lifecycle_seconds=10,
				absolute_disk_floor_bytes=500,
				snapshot=ResourceSnapshot(free_bytes=1_699, free_inodes=100, remaining_seconds=1_000),
			)

	def test_resource_preflight_rejects_insufficient_wall_time(self):
		with self.assertRaisesRegex(CampaignContractError, "time"):
			check_resource_budget(
				remaining_lifecycles=10,
				p95_artifact_bytes=100,
				p95_lifecycle_seconds=10,
				absolute_disk_floor_bytes=500,
				snapshot=ResourceSnapshot(free_bytes=10_000, free_inodes=100, remaining_seconds=119),
			)

	def test_variance_pilot_requires_five_valid_repeats(self):
		with self.assertRaisesRegex(CampaignContractError, "five"):
			summarize_variance_pilot([1.0, 1.1, 0.9, 1.0])

	def test_variance_pilot_reports_cv_and_mad(self):
		summary = summarize_variance_pilot([10.0, 11.0, 9.0, 10.5, 9.5])
		self.assertGreater(summary["cv"], 0)
		self.assertEqual(0.5, summary["mad"])

	def phase_bundle(self, metric_kind="systemds_total_execution_time"):
		phase = self.root / "phase"
		phase.mkdir(exist_ok=True)
		files = {
			"raw_coordinator.log": b"Total execution time: 1.25 sec.\n",
			"output.bin": b"semantic output",
			"semantic_oracle.json": json.dumps({"passed": True}).encode(),
			"return_code.txt": b"0\n",
			"scan.json": json.dumps({"timeout": False, "error": False, "fallback": False}).encode(),
			"metric.json": json.dumps({"kind": metric_kind, "seconds": 1.25}).encode(),
		}
		for name, contents in files.items():
			(phase / name).write_bytes(contents)
		checksums = {name: hashlib.sha256(contents).hexdigest() for name, contents in files.items()}
		(phase / "checksums.json").write_text(json.dumps(checksums, sort_keys=True), encoding="utf-8")
		return phase

	def test_phase_bundle_accepts_complete_checksum_valid_evidence(self):
		result = validate_phase_bundle(self.phase_bundle(), "systemds_total_execution_time")
		self.assertEqual(1.25, result["seconds"])

	def test_phase_bundle_rejects_corrupt_output(self):
		phase = self.phase_bundle()
		(phase / "output.bin").write_bytes(b"corrupt")
		with self.assertRaisesRegex(CampaignContractError, "checksum"):
			validate_phase_bundle(phase, "systemds_total_execution_time")

	def test_warm_phase_rejects_metric_without_raw_systemds_execution_time(self):
		phase = self.phase_bundle()
		(phase / "raw_coordinator.log").write_bytes(b"real 1.25\n")
		checksums = json.loads((phase / "checksums.json").read_text(encoding="utf-8"))
		checksums["raw_coordinator.log"] = hashlib.sha256(b"real 1.25\n").hexdigest()
		(phase / "checksums.json").write_text(json.dumps(checksums, sort_keys=True), encoding="utf-8")
		with self.assertRaisesRegex(CampaignContractError, "Total execution time"):
			validate_phase_bundle(phase, "systemds_total_execution_time")

	def test_warm_phase_rejects_elapsed_metric_substitution(self):
		phase = self.phase_bundle(metric_kind="docker_e2e")
		with self.assertRaisesRegex(CampaignContractError, "metric"):
			validate_phase_bundle(phase, "systemds_total_execution_time")


if __name__ == "__main__":
	unittest.main()
