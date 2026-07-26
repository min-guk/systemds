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
	CAMPAIGN_PLANNERS,
	CAMPAIGN_WORKLOADS,
	ResourceSnapshot,
	build_block_counterbalanced_schedule,
	build_counterbalanced_schedule,
	build_frozen_manifest,
	build_campaign_manifest,
	campaign_block_ids,
	campaign_cell_ids,
	check_resource_budget,
	summarize_variance_pilot,
	select_pilot_repeats,
	validate_campaign_matrix,
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

	def manifest(self, seed=7331, block_schedule=None, expected_block_order=None):
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
			expected_block_order=expected_block_order,
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
		manifest = self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))
		self.assertEqual(schedule, manifest["block_schedule"])
		self.assertEqual(["b0", "b1", "b2", "b3"], manifest["block_order"])

	def test_frozen_manifest_rejects_forged_aggregate_balance_claim(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		schedule["blocks"][0]["runs"][0]["periods"][0]["planner"] = "MinST"
		schedule["aggregate_fully_balanced"] = True
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_schedule_for_different_planner_order(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			build_frozen_manifest(
				jar=self.root / "systemds.jar",
				image_id="sha256:image",
				image_digest="repo@sha256:digest",
				config=self.root / "SystemDS-config.xml",
				dml=self.root / "workload.dml",
				dataset_root=self.root / "data",
				worker_mapping=("worker-1:8001", "worker-2:8002"),
				planner_order=("FedAll", "DP", "Heuristic", "MinST"),
				seed=19,
				warmup_runs=1,
				measured_warm_runs=5,
				block_schedule=schedule,
				expected_block_order=("b0", "b1", "b2", "b3"),
			)

	def test_frozen_manifest_rejects_forged_aggregate_period_counts(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		planner = next(iter(schedule["aggregate_period_counts"]["1"]))
		schedule["aggregate_period_counts"]["1"][planner] += 1
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_forged_directed_carryover_counts(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		pair = next(iter(schedule["aggregate_directed_carryover_counts"]))
		schedule["aggregate_directed_carryover_counts"][pair] += 1
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_forged_block_rotation(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		schedule["blocks"][0]["rotation_start_row"] = 3
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_nontext_block_identity(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		schedule["blocks"][0]["block"] = 7
		with self.assertRaisesRegex(CampaignContractError, "block"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_schedule_missing_a_complete_williams_cycle(self):
		expected_blocks = tuple(f"b{index}" for index in range(8))
		truncated_balanced_schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "MinST"), 5, expected_blocks[:4], 19
		)
		self.assertTrue(truncated_balanced_schedule["aggregate_fully_balanced"])
		with self.assertRaisesRegex(CampaignContractError, "block order"):
			self.manifest(
				seed=19,
				block_schedule=truncated_balanced_schedule,
				expected_block_order=expected_blocks,
			)

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

	def test_campaign_matrix_is_exact_ordered_84_blocks_and_336_cells(self):
		blocks, cells = validate_campaign_matrix(campaign_block_ids(), campaign_cell_ids())
		self.assertEqual(84, len(blocks))
		self.assertEqual(336, len(cells))
		self.assertEqual("workers=1|workload=kmeans|profile=lan", blocks[0])
		self.assertEqual("workers=4|planner=MinST|workload=steplm|profile=wan_mid", cells[-1])
		with self.assertRaisesRegex(CampaignContractError, "ordered"):
			validate_campaign_matrix(tuple(reversed(blocks)), cells)
		with self.assertRaisesRegex(CampaignContractError, "duplicates"):
			validate_campaign_matrix(blocks, (*cells[:-1], cells[-2]))

	def _campaign_v2_inputs(self):
		paths = {}
		for name in (
			"wrapper", "jar", "cp-config", "sidecar", "oracle", "compose", "runner", "reference"
		):
			path = self.root / f"{name}.bin"
			path.write_bytes(name.encode())
			paths[name] = path
		for planner in CAMPAIGN_PLANNERS:
			path = self.root / f"{planner}.xml"
			path.write_text(planner, encoding="utf-8")
			paths[f"planner-{planner}"] = path
		for workload in CAMPAIGN_WORKLOADS:
			for surface in ("fed", "cp"):
				path = self.root / f"{workload}-{surface}.dml"
				path.write_text(f"{workload}-{surface}", encoding="utf-8")
				paths[f"{surface}-{workload}"] = path
		blocks = campaign_block_ids()
		schedule = build_block_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, blocks, 19)
		return {
			"source_commit": "a" * 40,
			"image_id": "sha256:" + "b" * 64,
			"image_digest": "systemds@sha256:" + "c" * 64,
			"wrapper": paths["wrapper"], "jar": paths["jar"],
			"planner_configs": {p: paths[f"planner-{p}"] for p in CAMPAIGN_PLANNERS},
			"cp_config": paths["cp-config"],
			"fed_dmls": {w: paths[f"fed-{w}"] for w in CAMPAIGN_WORKLOADS},
			"cp_dmls": {w: paths[f"cp-{w}"] for w in CAMPAIGN_WORKLOADS},
			"oracle_files": {"semantic": paths["oracle"]},
			"compose_files": {"campaign": paths["compose"]},
			"runner_files": {"docker": paths["runner"]},
			"dataset_root": self.root / "data", "data_sidecar": paths["sidecar"],
			"block_ids": blocks, "cell_ids": campaign_cell_ids(),
			"network_costs": {"lan_latency_ms": 0}, "privacy_settings": {"public_ignored": True},
			"jvm_settings": {"heap": "8g"}, "thread_settings": {"blas": 1},
			"resource_settings": {"absolute_floor_bytes": 5 * 1024**3},
			"block_schedule": schedule, "reference_artifacts": {"cp": paths["reference"]},
			"tolerance_version": "oracle-v1", "seed": 19, "repeats": 5,
		}

	def test_campaign_v2_manifest_hashes_every_campaign_wide_input(self):
		inputs = self._campaign_v2_inputs()
		manifest = build_campaign_manifest(**inputs)
		self.assertEqual("systemds-federated-docker-campaign/v2", manifest["schema"])
		self.assertEqual(336, len(manifest["dimensions"]["cell_ids"]))
		before = manifest["manifest_hash"]
		inputs["runner_files"]["docker"].write_text("changed", encoding="utf-8")
		self.assertNotEqual(before, build_campaign_manifest(**inputs)["manifest_hash"])

	def test_pilot_selector_uses_preregistered_verified_rows_and_log_thresholds(self):
		def rows(values):
			return [{
				"cell": "cell-a", "pilot_repeat": index, "warm_seconds": value,
				"evidence_status": "committed", "evidence_sha256": f"{index:064x}",
			} for index, value in enumerate(values, 1)]
		self.assertEqual(3, select_pilot_repeats(rows([100, 100.5, 99.5, 101, 99]))["selected_repeats"])
		self.assertEqual(5, select_pilot_repeats(rows([100, 102.1, 98, 101, 99]))["selected_repeats"])
		self.assertEqual(7, select_pilot_repeats(rows([100, 110, 95, 101, 99]))["selected_repeats"])
		bad = rows([100] * 5)
		bad[3]["pilot_repeat"] = 3
		with self.assertRaisesRegex(CampaignContractError, "gap"):
			select_pilot_repeats(bad)

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
