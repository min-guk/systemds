# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import hashlib
import json
import math
import copy
import statistics
import tempfile
import unittest
from pathlib import Path
from typing import Any, Mapping, Sequence, cast

from scripts.federated_campaign.determinism_contract import (
	CampaignContractError,
	CAMPAIGN_PLANNERS,
	CAMPAIGN_WORKLOADS,
	ResourceSnapshot,
	build_block_counterbalanced_schedule,
	build_counterbalanced_schedule,
	build_frozen_manifest,
	build_campaign_manifest,
	build_campaign_preregistration_manifest,
	build_canonical_final_invocation,
	build_canonical_pilot_invocation,
	build_canonical_discovery_invocation_hashes,
	build_discovery_completion_receipt,
	_build_final_campaign_manifest as build_final_campaign_manifest,
	_build_pilot_resource_reservation as build_pilot_resource_reservation,
	campaign_block_ids,
	campaign_cell_ids,
	check_resource_budget,
	summarize_variance_pilot,
	select_pilot_repeats,
	select_campaign_pilot_repeats,
	validate_campaign_matrix,
	validate_campaign_preregistration_manifest,
	validate_discovery_completion_receipt,
	validate_final_campaign_manifest,
	validate_phase_bundle,
)


def _dict(value: object) -> dict[str, Any]:
	assert isinstance(value, dict)
	return cast(dict[str, Any], value)


def _list(value: object) -> list[Any]:
	assert isinstance(value, list)
	return cast(list[Any], value)


class DeterminismContractTest(unittest.TestCase):
	temp_dir = cast(tempfile.TemporaryDirectory[str], object())
	root = cast(Path, object())

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

	@staticmethod
	def _exact_row_validator(rows: Sequence[Mapping[str, object]]):
		trusted = {
			cast(str, row["evidence_sha256"]): copy.deepcopy(dict(row)) for row in rows
		}
		def validate(row: Mapping[str, object]) -> None:
			if dict(row) != trusted.get(cast(str, row.get("evidence_sha256"))):
				raise ValueError("row is not in the trusted evidence inventory")
		return validate

	def manifest(self, seed=7331, block_schedule=None, expected_block_order=None):
		return build_frozen_manifest(
			jar=self.root / "systemds.jar",
			image_id="sha256:image",
			image_digest="systemds@sha256:digest",
			config=self.root / "SystemDS-config.xml",
			dml=self.root / "workload.dml",
			dataset_root=self.root / "data",
			worker_mapping=("worker-0:8001", "worker-1:8002"),
			planner_order=("DP", "FedAll", "Heuristic", "Exact"),
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
		lifecycle = _dict(self.manifest()["lifecycle"])
		self.assertEqual("docker_e2e", lifecycle["cold_metric"])
		self.assertEqual("systemds_total_execution_time", lifecycle["warm_metric"])
		self.assertEqual("fresh", lifecycle["warm_coordinator_jvm"])
		self.assertEqual("reused_from_cold", lifecycle["warm_worker_containers"])

	def test_schedule_is_reproducible_for_same_seed(self):
		first = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "Exact"), 8, 19)
		second = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "Exact"), 8, 19)
		self.assertEqual(first, second)

	def test_schedule_places_each_planner_in_each_period_equally(self):
		schedule = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "Exact"), 8, 19)
		for period in range(4):
			counts = {planner: sum(row[period] == planner for row in schedule) for planner in schedule[0]}
			self.assertEqual({"DP": 2, "FedAll": 2, "Heuristic": 2, "Exact": 2}, counts)

	def test_schedule_balances_each_directed_carryover_pair(self):
		schedule = build_counterbalanced_schedule(("DP", "FedAll", "Heuristic", "Exact"), 8, 19)
		pairs = {(left, right): 0 for left in schedule[0] for right in schedule[0] if left != right}
		for row in schedule:
			for left, right in zip(row, row[1:]):
				pairs[left, right] += 1
		self.assertEqual({2}, set(pairs.values()))

	def test_block_rotation_balances_periods_aggregate_for_3_5_7_repeats(self):
		for repeats in (3, 5, 7):
			with self.subTest(repeats=repeats):
				schedule = build_block_counterbalanced_schedule(
					("DP", "FedAll", "Heuristic", "Exact"), repeats, ("b0", "b1", "b2", "b3"), 19
				)
				for period_counts in _dict(schedule["aggregate_period_counts"]).values():
					self.assertEqual({repeats}, set(_dict(period_counts).values()))

	def test_block_rotation_balances_directed_carryover_aggregate_for_3_5_7_repeats(self):
		for repeats in (3, 5, 7):
			with self.subTest(repeats=repeats):
				schedule = build_block_counterbalanced_schedule(
					("DP", "FedAll", "Heuristic", "Exact"), repeats, ("b0", "b1", "b2", "b3"), 19
				)
				self.assertEqual({repeats}, set(_dict(schedule["aggregate_directed_carryover_counts"]).values()))

	def test_block_rotation_records_unbalanced_within_cell_remainder_honestly(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		self.assertTrue(all(_dict(block)["within_cell_fully_balanced"] is False for block in _list(schedule["blocks"])))

	def test_frozen_manifest_persists_aggregate_balanced_block_schedule(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		manifest = self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))
		self.assertEqual(schedule, manifest["block_schedule"])
		self.assertEqual(["b0", "b1", "b2", "b3"], manifest["block_order"])

	def test_frozen_manifest_rejects_forged_aggregate_balance_claim(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		block = _dict(_list(schedule["blocks"])[0])
		run = _dict(_list(block["runs"])[0])
		period = _dict(_list(run["periods"])[0])
		period["planner"] = "Exact"
		schedule["aggregate_fully_balanced"] = True
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_schedule_for_different_planner_order(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
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
				planner_order=("FedAll", "DP", "Heuristic", "Exact"),
				seed=19,
				warmup_runs=1,
				measured_warm_runs=5,
				block_schedule=schedule,
				expected_block_order=("b0", "b1", "b2", "b3"),
			)

	def test_frozen_manifest_rejects_forged_aggregate_period_counts(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		period_one = _dict(_dict(schedule["aggregate_period_counts"])["1"])
		planner = next(iter(period_one))
		period_one[planner] += 1
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_forged_directed_carryover_counts(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		carryover = _dict(schedule["aggregate_directed_carryover_counts"])
		pair = next(iter(carryover))
		carryover[pair] += 1
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_forged_block_rotation(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		_dict(_list(schedule["blocks"])[0])["rotation_start_row"] = 3
		with self.assertRaisesRegex(CampaignContractError, "schedule"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_nontext_block_identity(self):
		schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, ("b0", "b1", "b2", "b3"), 19
		)
		_dict(_list(schedule["blocks"])[0])["block"] = 7
		with self.assertRaisesRegex(CampaignContractError, "block"):
			self.manifest(seed=19, block_schedule=schedule, expected_block_order=("b0", "b1", "b2", "b3"))

	def test_frozen_manifest_rejects_schedule_missing_a_complete_williams_cycle(self):
		expected_blocks = tuple(f"b{index}" for index in range(8))
		truncated_balanced_schedule = build_block_counterbalanced_schedule(
			("DP", "FedAll", "Heuristic", "Exact"), 5, expected_blocks[:4], 19
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
			("DP", "FedAll", "Heuristic", "Exact"), 3, tuple(f"workload=w{i}|workers=1|network=lan" for i in range(4)), 19
		)
		first_block = _dict(_list(schedule["blocks"])[0])
		first = _dict(_list(first_block["runs"])[0])
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
		self.assertEqual("workers=4|planner=Exact|workload=steplm|profile=wan_mid", cells[-1])
		for planner_index, planner in enumerate(CAMPAIGN_PLANNERS):
			planner_slice = cells[planner_index * 84:(planner_index + 1) * 84]
			self.assertEqual(84, len(planner_slice))
			self.assertTrue(all(f"planner={planner}|" in cell for cell in planner_slice))
		with self.assertRaisesRegex(CampaignContractError, "ordered"):
			validate_campaign_matrix(tuple(reversed(blocks)), cells)
		with self.assertRaisesRegex(CampaignContractError, "duplicates"):
			validate_campaign_matrix(blocks, (*cells[:-1], cells[-2]))

	def _campaign_v2_inputs(self) -> dict[str, Any]:
		paths: dict[str, Path] = {}
		for name in (
			"wrapper", "jar", "cp-config", "sidecar", "compose", "runner"
		):
			path = self.root / f"{name}.bin"
			path.write_bytes(name.encode())
			paths[name] = path
		for planner in CAMPAIGN_PLANNERS:
			path = self.root / f"{planner}.xml"
			path.write_text(planner, encoding="utf-8")
			paths[f"planner-{planner}"] = path
		for workload in CAMPAIGN_WORKLOADS:
			for surface in ("fed", "cp", "oracle", "reference"):
				path = self.root / f"{workload}-{surface}.bin"
				path.write_text(f"{workload}-{surface}", encoding="utf-8")
				paths[f"{surface}-{workload}"] = path
		blocks = campaign_block_ids()
		schedule = build_block_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, blocks, 19)
		return {
			"source_commit": "a" * 40,
			"source_tree": self.root / "data",
			"image_id": "sha256:" + "b" * 64,
			"image_digest": "systemds@sha256:" + "c" * 64,
			"wrapper": paths["wrapper"], "jar": paths["jar"],
			"planner_configs": {p: paths[f"planner-{p}"] for p in CAMPAIGN_PLANNERS},
			"cp_config": paths["cp-config"],
			"fed_dmls": {w: paths[f"fed-{w}"] for w in CAMPAIGN_WORKLOADS},
			"cp_dmls": {w: paths[f"cp-{w}"] for w in CAMPAIGN_WORKLOADS},
			"oracle_files": {w: paths[f"oracle-{w}"] for w in CAMPAIGN_WORKLOADS},
			"oracle_policies": {w: {
				"version": "oracle-v1",
				"policy_sha256": hashlib.sha256(paths[f"oracle-{w}"].read_bytes()).hexdigest(),
				"self_drift_a_sha256": hashlib.sha256(f"{w}-stable".encode()).hexdigest(),
				"self_drift_b_sha256": hashlib.sha256(f"{w}-stable".encode()).hexdigest(),
			} for w in CAMPAIGN_WORKLOADS},
			"compose_files": {name: paths["compose"] for name in ("base", "campaign")},
			"runner_files": {name: paths["runner"] for name in ("campaign", "docker", "snapshot", "data_prep")},
			"dataset_root": self.root / "data", "data_sidecar": paths["sidecar"],
			"block_ids": blocks, "cell_ids": campaign_cell_ids(),
			"network_costs": {name: {"latency_ms": index, "bandwidth_mbps": 1000} for index, name in enumerate(("lan", "wan_light", "wan_mid"))}, "privacy_settings": {"public_tests_ignored": True, "runtime_fallback_allowed": False},
			"jvm_settings": {"java_opts": ["-Xmx8g"], "heap_bytes": 8 * 1024**3, "coordinator_fresh": True}, "thread_settings": {"blas_threads": 1, "omp_threads": 1, "systemds_threads": 1},
			"resource_settings": {"absolute_disk_floor_bytes": 5 * 1024**3, "required_free_inodes": 100, "wall_time_seconds": 1000, "max_io_utilization": 0.1, "max_combined_io_bps": 1000},
			"commands": {name: [name] for name in ("compose", "campaign", "docker_lifecycle", "systemds_snapshot", "data_prep")},
			"endpoints": {name: f"{name}:8001" for name in ("coordinator", "worker_1", "worker_2", "worker_3", "worker_4")},
			"topology": {"worker_counts": [1, 2, 3, 4], "profiles": ["lan", "wan_light", "wan_mid"], "docker_project": "g007"},
			"block_schedule": schedule, "reference_artifacts": {w: paths[f"reference-{w}"] for w in CAMPAIGN_WORKLOADS},
			"tolerance_version": "oracle-v1",
			"seed_streams": {"schedule": 19, "data_generation": 23, "workload_random": 29}, "repeats": 5,
		}

	def test_campaign_v2_manifest_hashes_every_campaign_wide_input(self):
		inputs = self._campaign_v2_inputs()
		manifest = build_campaign_manifest(**inputs)
		self.assertEqual("systemds-federated-docker-campaign/v2", manifest["schema"])
		self.assertEqual(336, len(_list(_dict(manifest["dimensions"])["cell_ids"])))
		before = manifest["manifest_hash"]
		inputs["runner_files"]["docker"].write_text("changed", encoding="utf-8")
		self.assertNotEqual(before, build_campaign_manifest(**inputs)["manifest_hash"])
		del inputs["oracle_files"]["als"]
		with self.assertRaisesRegex(CampaignContractError, "oracle_files"):
			build_campaign_manifest(**inputs)

	def test_campaign_preregistration_accepts_canonical_nested_tree_order(self):
		(self.root / "data/tree/part-00000").parent.mkdir(parents=True)
		(self.root / "data/tree/part-00000").write_bytes(b"nested")
		(self.root / "data/tree.mtd").write_bytes(b"metadata")
		preregistration = self._campaign_v3_preregistration()
		validate_campaign_preregistration_manifest(preregistration)
		for tree_name in ("dataset", "source_tree"):
			records = _list(_dict(_dict(preregistration["frozen_core"])["artifacts"])[tree_name])
			paths = [cast(str, _dict(record)["relative_path"]) for record in records]
			self.assertEqual(sorted(paths), paths)

	def test_campaign_v2_manifest_rejects_mutable_or_non_distinct_inputs(self):
		inputs = self._campaign_v2_inputs()
		inputs["image_id"] = "sha256:short"
		with self.assertRaisesRegex(CampaignContractError, "immutable"):
			build_campaign_manifest(**inputs)
		inputs = self._campaign_v2_inputs()
		inputs["reference_artifacts"]["als"] = inputs["reference_artifacts"]["lm"]
		with self.assertRaisesRegex(CampaignContractError, "distinct"):
			build_campaign_manifest(**inputs)
		inputs = self._campaign_v2_inputs()
		inputs["fed_dmls"]["als"] = inputs["fed_dmls"]["lm"]
		with self.assertRaisesRegex(CampaignContractError, "distinct"):
			build_campaign_manifest(**inputs)
		inputs = self._campaign_v2_inputs()
		inputs["cp_dmls"]["als"] = inputs["cp_dmls"]["lm"]
		with self.assertRaisesRegex(CampaignContractError, "distinct"):
			build_campaign_manifest(**inputs)

	def test_campaign_v2_manifest_rejects_oracle_self_drift_and_bad_seed_streams(self):
		inputs = self._campaign_v2_inputs()
		inputs["oracle_policies"]["als"]["self_drift_b_sha256"] = "f" * 64
		with self.assertRaisesRegex(CampaignContractError, "self-drift"):
			build_campaign_manifest(**inputs)
		inputs = self._campaign_v2_inputs()
		del inputs["seed_streams"]["workload_random"]
		with self.assertRaisesRegex(CampaignContractError, "seed streams"):
			build_campaign_manifest(**inputs)

	def test_campaign_v2_manifest_rejects_unsafe_resource_and_jvm_values(self):
		for field, value in (("required_free_inodes", True), ("required_free_inodes", 1.5), ("required_free_inodes", 0),
			("max_io_utilization", float("nan")), ("max_io_utilization", 1.1), ("max_combined_io_bps", float("inf"))):
			inputs = self._campaign_v2_inputs()
			inputs["resource_settings"][field] = value
			with self.assertRaises(CampaignContractError):
				build_campaign_manifest(**inputs)
		for field, value in (("heap_bytes", True), ("java_opts", [123]), ("java_opts", [""])):
			inputs = self._campaign_v2_inputs()
			inputs["jvm_settings"][field] = value
			with self.assertRaises(CampaignContractError):
				build_campaign_manifest(**inputs)

	def _campaign_v3_preregistration(self) -> dict[str, Any]:
		inputs = self._campaign_v2_inputs()
		inputs.pop("block_schedule"); inputs.pop("repeats")
		return build_campaign_preregistration_manifest(
			campaign_core_inputs=inputs,
			stage_descriptor_sha256="1" * 64,
			cp_lifecycle_descriptor_sha256="2" * 64,
			reference_manifest_sha256="3" * 64,
			conservative_pre_pilot_bounds={
				"remaining_lifecycles": 2808, "p95_artifact_bytes": 1024,
				"p95_artifact_inodes": 8, "p95_lifecycle_seconds": 30.0,
			},
		)

	def _discovery_completion(self, prereg: Mapping[str, object]) -> dict[str, object]:
		manifest_hash = cast(str, prereg["preregistration_manifest_sha256"])
		invocation_hashes = build_canonical_discovery_invocation_hashes(prereg)
		rows = [{
			"cell": cell,
			"identity": {"kind": "discovery", "cell": cell, "attempt": 1, "run_token": f"token-{index}", "manifest_hash": manifest_hash},
			"invocation_manifest_sha256": invocation_hashes[cell],
			"evidence_status": "committed", "evidence_sha256": f"{index + 1:064x}",
			"evidence_location": {"committed_path": f"/verified/discovery/{index}"},
		} for index, cell in enumerate(campaign_cell_ids())]
		return build_discovery_completion_receipt(
			preregistration_manifest=prereg, discovery_rows=rows,
			evidence_validator=self._exact_row_validator(rows),
		)

	def test_campaign_v3_preregistration_binds_lineage_barriers_and_excludes_posthoc_selection(self):
		prereg = self._campaign_v3_preregistration()
		self.assertEqual("systemds-federated-docker-campaign-preregistration/v3", prereg["schema"])
		self.assertNotIn("selected_repeats", prereg)
		self.assertNotIn("schedule", prereg)
		dimensions = _dict(prereg["dimensions"])
		self.assertEqual(336, len(_list(dimensions["cell_ids"])))
		self.assertEqual([
			{"planner": "DP", "start": 0, "stop": 84},
			{"planner": "FedAll", "start": 84, "stop": 168},
			{"planner": "Heuristic", "start": 168, "stop": 252},
			{"planner": "Exact", "start": 252, "stop": 336},
		], dimensions["planner_major_barriers"])
		pilot = _dict(prereg["pilot_preregistration"])
		self.assertEqual((120, 5, 19), (pilot["row_count"], pilot["repeats"], pilot["schedule_seed"]))
		unsigned = dict(prereg); digest = unsigned.pop("preregistration_manifest_sha256")
		self.assertEqual(digest, hashlib.sha256(json.dumps(
			unsigned, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest())

	def test_campaign_v3_preregistration_rejects_unknown_drift_bool_nonfinite_and_zero_hash(self):
		inputs = self._campaign_v2_inputs()
		inputs.pop("block_schedule"); inputs.pop("repeats")
		base: dict[str, Any] = {
			"campaign_core_inputs": inputs, "stage_descriptor_sha256": "1" * 64,
			"cp_lifecycle_descriptor_sha256": "2" * 64, "reference_manifest_sha256": "3" * 64,
			"conservative_pre_pilot_bounds": {
				"remaining_lifecycles": 2808, "p95_artifact_bytes": 1024,
				"p95_artifact_inodes": 8, "p95_lifecycle_seconds": 30.0,
			},
		}
		for mutation in ("extra", "bool", "nan", "zero_hash"):
			candidate: dict[str, Any] = copy.deepcopy(base)
			if mutation == "extra": cast(dict[str, Any], candidate["campaign_core_inputs"])["extra"] = 1
			elif mutation == "bool": cast(dict[str, Any], candidate["conservative_pre_pilot_bounds"])["p95_artifact_bytes"] = True
			elif mutation == "nan": cast(dict[str, Any], candidate["conservative_pre_pilot_bounds"])["p95_lifecycle_seconds"] = float("nan")
			else: candidate["stage_descriptor_sha256"] = "0" * 64
			with self.subTest(mutation=mutation), self.assertRaises(CampaignContractError):
				build_campaign_preregistration_manifest(**candidate)

	def test_campaign_v3_preregistration_revalidates_raw_core_and_keeps_pilot_seed_distinct(self):
		inputs = self._campaign_v2_inputs()
		inputs.pop("block_schedule"); inputs.pop("repeats")
		inputs["seed_streams"] = dict(inputs["seed_streams"], schedule=31)
		def build(candidate: dict[str, Any]) -> dict[str, object]:
			return build_campaign_preregistration_manifest(
				campaign_core_inputs=candidate, stage_descriptor_sha256="1" * 64,
				cp_lifecycle_descriptor_sha256="2" * 64, reference_manifest_sha256="3" * 64,
				conservative_pre_pilot_bounds={
					"remaining_lifecycles": 2808, "p95_artifact_bytes": 1024,
					"p95_artifact_inodes": 8, "p95_lifecycle_seconds": 30.0,
				},
			)
		prereg = build(inputs)
		self.assertEqual(31, _dict(prereg["seed_streams"])["schedule"])
		self.assertEqual(
			build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19),
			_list(_dict(prereg["pilot_preregistration"])["orders"]),
		)
		for mutation in ("image", "artifact_path", "privacy", "privacy_alias", "topology", "oracle"):
			candidate = dict(inputs)
			if mutation == "image": candidate["image_digest"] = "mutable:latest"
			elif mutation == "artifact_path": candidate["jar"] = self.root / "missing.jar"
			elif mutation == "privacy": candidate["privacy_settings"] = {"public_tests_ignored": True, "runtime_fallback_allowed": True}
			elif mutation == "privacy_alias": candidate["privacy_settings"] = {"public_tests_ignored": 1, "runtime_fallback_allowed": 0}
			elif mutation == "topology": candidate["topology"] = dict(cast(dict[str, object], candidate["topology"]), profiles=["lan"])
			else:
				policies = dict(cast(dict[str, object], candidate["oracle_policies"])); policies.pop("als")
				candidate["oracle_policies"] = policies
			with self.subTest(mutation=mutation), self.assertRaises(CampaignContractError):
				build(candidate)

	def test_preregistration_semantics_reject_nested_aliases_and_extra_keys_after_reseal(self):
		def reseal(candidate: dict[str, Any]) -> dict[str, Any]:
			candidate.pop("preregistration_manifest_sha256", None)
			candidate["preregistration_manifest_sha256"] = hashlib.sha256(json.dumps(
				candidate, sort_keys=True, separators=(",", ":"), ensure_ascii=True
			).encode()).hexdigest()
			return candidate
		for mutation in (
			"barrier_bool", "pilot_float", "artifact_float", "network_bool", "topology_float", "oracle_extra",
			"floor_float", "duplicate_dml_path", "duplicate_dml_digest", "leading_command", "nul_command",
			"whitespace_tolerance", "privacy_true_int", "privacy_false_int", "absolute_nul",
			"absolute_dot_segment", "tree_dot", "tree_double_slash", "tree_backslash",
			"tree_absolute", "tree_parent", "tree_nul",
		):
			candidate = copy.deepcopy(self._campaign_v3_preregistration())
			if mutation == "barrier_bool": candidate["dimensions"]["planner_major_barriers"][0]["start"] = False
			elif mutation == "pilot_float": candidate["pilot_preregistration"]["row_count"] = 120.0
			elif mutation == "artifact_float": candidate["frozen_core"]["artifacts"]["jar"]["bytes"] = 6.0
			elif mutation == "network_bool": candidate["frozen_core"]["network_costs"]["lan"]["latency_ms"] = False
			elif mutation == "topology_float": candidate["frozen_core"]["topology"]["worker_counts"][0] = 1.0
			elif mutation == "oracle_extra": candidate["frozen_core"]["oracle_policies"]["kmeans"]["extra"] = 1
			elif mutation == "floor_float": candidate["resource_settings"]["absolute_disk_floor_bytes"] = float(5 * 1024**3)
			elif mutation == "duplicate_dml_path": candidate["frozen_core"]["artifacts"]["fed_dmls"]["pca"]["path"] = candidate["frozen_core"]["artifacts"]["fed_dmls"]["kmeans"]["path"]
			elif mutation == "duplicate_dml_digest": candidate["frozen_core"]["artifacts"]["fed_dmls"]["pca"]["sha256"] = candidate["frozen_core"]["artifacts"]["fed_dmls"]["kmeans"]["sha256"]
			elif mutation == "leading_command": candidate["commands"]["campaign"][0] = " docker"
			elif mutation == "nul_command": candidate["commands"]["campaign"][0] = "docker\x00"
			elif mutation == "whitespace_tolerance": candidate["frozen_core"]["tolerance_version"] = " tolerance-v1 "
			elif mutation == "privacy_true_int": candidate["frozen_core"]["privacy_settings"]["public_tests_ignored"] = 1
			elif mutation == "privacy_false_int": candidate["frozen_core"]["privacy_settings"]["runtime_fallback_allowed"] = 0
			elif mutation == "absolute_nul": candidate["frozen_core"]["artifacts"]["jar"]["path"] += "\x00"
			elif mutation == "absolute_dot_segment":
				path = candidate["frozen_core"]["artifacts"]["jar"]["path"]
				candidate["frozen_core"]["artifacts"]["jar"]["path"] = f"{str(Path(path).parent)}/./{Path(path).name}"
			else:
				tree_paths = {
					"tree_dot": ".", "tree_double_slash": "a//b", "tree_backslash": "a\\b",
					"tree_absolute": "/a", "tree_parent": "a/../b", "tree_nul": "a\x00b",
				}
				candidate["frozen_core"]["artifacts"]["dataset"][0]["relative_path"] = tree_paths[mutation]
			with self.subTest(mutation=mutation), self.assertRaises(CampaignContractError):
				validate_campaign_preregistration_manifest(reseal(candidate))

	def test_discovery_completion_requires_exact_ordered_336_latest_successes(self):
		prereg = self._campaign_v3_preregistration()
		completion = self._discovery_completion(prereg)
		self.assertEqual(336, completion["cell_count"])
		self.assertEqual(4, len(_list(completion["planner_major_barriers"])))
		rows = _list(completion["discovery_rows"])
		with self.assertRaisesRegex(CampaignContractError, "336"):
			build_discovery_completion_receipt(
				preregistration_manifest=prereg, discovery_rows=rows[:-1], evidence_validator=self._exact_row_validator(rows),
			)
		duplicate = copy.deepcopy(rows)
		duplicate[1] = duplicate[0]
		with self.assertRaises(CampaignContractError):
			build_discovery_completion_receipt(
				preregistration_manifest=prereg, discovery_rows=duplicate, evidence_validator=self._exact_row_validator(rows),
			)
		forged = copy.deepcopy(completion)
		forged_rows = _list(forged["discovery_rows"])
		_dict(forged_rows[200])["invocation_manifest_sha256"] = "f" * 64
		forged["discovery_rows_sha256"] = hashlib.sha256(json.dumps(
			forged_rows, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		forged.pop("discovery_completion_sha256")
		forged["discovery_completion_sha256"] = hashlib.sha256(json.dumps(
			forged, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		with self.assertRaisesRegex(CampaignContractError, "P-derived"):
			validate_discovery_completion_receipt(
				forged, preregistration_manifest=prereg,
				evidence_validator=self._exact_row_validator(rows),
			)
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

	def test_campaign_pilot_freezes_cross_planner_regime_q95_diagnostics(self):
		rows = []
		orders = build_counterbalanced_schedule(CAMPAIGN_PLANNERS, 5, 19)
		representatives = {"cheap": "kmeans", "medium": "logreg", "heavy": "als"}
		prereg = self._campaign_v3_preregistration()
		completion = self._discovery_completion(prereg)
		manifest_hash = cast(str, prereg["preregistration_manifest_sha256"])
		discovery_hash = cast(str, completion["discovery_completion_sha256"])
		for pilot_class in ("cheap", "medium", "heavy"):
			for planner in CAMPAIGN_PLANNERS:
				for workers, profile in ((1, "lan"), (4, "wan_mid")):
					workload = representatives[pilot_class]
					cell = f"pilot_class={pilot_class}|workload={workload}|planner={planner}|workers={workers}|profile={profile}"
					for repeat in range(1, 6):
						order_tuple = orders[repeat - 1]
						period = order_tuple.index(planner) + 1
						invocation = build_canonical_pilot_invocation(
							preregistration_manifest_sha256=manifest_hash,
							discovery_completion_sha256=discovery_hash,
							pilot_class=pilot_class, planner=planner, workers=workers,
							profile=profile, pilot_repeat=repeat,
						)
						invocation_hash = hashlib.sha256(json.dumps(
							invocation, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
						).encode()).hexdigest()
						rows.append({
							"pilot_class": pilot_class, "workload": workload, "planner": planner, "workers": workers,
							"profile": profile, "cell": cell, "pilot_repeat": repeat,
							"warm_seconds": 100 + (repeat - 3) * 0.5, "period": period,
							"order": ">".join(order_tuple), "carryover": "NONE" if period == 1 else order_tuple[period - 2],
							"host_load": {"io_utilization": 0.01, "read_bytes_per_second": 10, "write_bytes_per_second": 20},
							"lifecycle": {"cold_seconds": 110, "warm_seconds": 100 + (repeat - 3) * 0.5, "coordinator_restart_count": 0, "worker_restart_count": 0},
							"evidence_status": "committed", "evidence_sha256": f"{len(rows)+1:064x}",
							"identity": {
								"kind": "performance", "cell": cell, "attempt": repeat, "manifest_hash": manifest_hash,
								"lifecycle_replicate": repeat, "period": period, "order": ">".join(order_tuple),
								"run_token": f"pilot-{len(rows) + 1}",
							},
							"evidence_location": {"committed_path": f"/verified/{len(rows)+1}"},
							"invocation_manifest_sha256": invocation_hash,
							"resource_evidence": {
								"artifact_bytes": 1000 + len(rows), "artifact_inodes": 20 + repeat,
								"lifecycle_wall_seconds": 120.0 + repeat,
							},
						})
		select = lambda candidate_rows: select_campaign_pilot_repeats(
			candidate_rows, self._exact_row_validator(rows), expected_manifest_hash=manifest_hash,
			preregistration_manifest=prereg, discovery_completion_receipt=completion,
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		selection = select(rows)
		self.assertEqual(3, selection["selected_repeats"])
		self.assertEqual("systemds-federated-campaign-pilot/v4", selection["schema"])
		self.assertRegex(cast(str, selection["pilot_rows_sha256"]), r"^[0-9a-f]{64}$")
		self.assertEqual(120, len(_list(selection["evidence_digest_inventory"])))
		unsigned_selection = dict(selection); selection_hash = unsigned_selection.pop("pilot_selection_sha256")
		self.assertEqual(selection_hash, hashlib.sha256(json.dumps(
			unsigned_selection, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest())
		diagnostics = _list(selection["diagnostics"])
		self.assertEqual(24, len(diagnostics))
		self.assertIn("Q95", cast(str, selection["selection_rule"]))
		first_diagnostic = _dict(diagnostics[0])
		self.assertIn("first_run_ratio", first_diagnostic)
		self.assertIn("effects", first_diagnostic)
		self.assertEqual(
			{"io_utilization", "read_bytes_per_second", "write_bytes_per_second"},
			set(_dict(_dict(first_diagnostic["diagnostic_effects"])["host_load"])),
		)
		with self.assertRaisesRegex(CampaignContractError, "120-row"):
			select(rows[:-1])
		forged = [dict(row) for row in rows]
		forged[-1] = dict(forged[0], pilot_repeat=5)
		with self.assertRaises(CampaignContractError):
			select(forged)
		bad_digest = [dict(row) for row in rows]
		bad_digest[0]["evidence_sha256"] = "claimed"
		with self.assertRaisesRegex(CampaignContractError, "SHA256"):
			select(bad_digest)
		bad_host = [dict(row) for row in rows]
		bad_host[0]["host_load"] = {"io_utilization": float("nan"), "read_bytes_per_second": 10, "write_bytes_per_second": 20}
		with self.assertRaisesRegex(CampaignContractError, "finite"):
			select(bad_host)
		mixed_manifest = [dict(row) for row in rows]
		mixed_manifest[0]["identity"] = dict(cast(dict[str, object], mixed_manifest[0]["identity"]), manifest_hash="b" * 64)
		with self.assertRaisesRegex(CampaignContractError, "mixes frozen"):
			select(mixed_manifest)
		mixed_invocation = [dict(row) for row in rows]
		mixed_invocation[0]["invocation_manifest_sha256"] = "e" * 64
		with self.assertRaisesRegex(CampaignContractError, "P/D-derived typed identity"):
			select(mixed_invocation)
		wrong_workload = [dict(row) for row in rows]
		wrong_workload[0]["workload"] = "lm"
		with self.assertRaisesRegex(CampaignContractError, "non-preregistered"):
			select(wrong_workload)
		for field, alias in (("pilot_repeat", True), ("pilot_repeat", 1.0)):
			aliased = [dict(row) for row in rows]
			aliased[0][field] = alias
			with self.assertRaises(CampaignContractError):
				select(aliased)
		period_one_index = next(index for index, row in enumerate(rows) if row["period"] == 1)
		for alias in (True, 1.0):
			aliased = [dict(row) for row in rows]
			aliased[period_one_index]["period"] = alias
			with self.assertRaises(CampaignContractError):
				select(aliased)
		for field, alias in (("coordinator_restart_count", 0.0), ("worker_restart_count", False)):
			aliased = [dict(row) for row in rows]
			aliased[0]["lifecycle"] = dict(cast(dict[str, object], aliased[0]["lifecycle"]), **{field: alias})
			with self.assertRaisesRegex(CampaignContractError, "restart counts"):
				select(aliased)
		for field, alias in (("attempt", True), ("lifecycle_replicate", 1.0), ("period", True)):
			aliased = [dict(row) for row in rows]
			aliased[0]["identity"] = dict(cast(dict[str, object], aliased[0]["identity"]), **{field: alias})
			with self.assertRaisesRegex(CampaignContractError, "schedule identity"):
				select(aliased)
		first_effects = _dict(first_diagnostic["effects"])
		for field, effect_name in (("period", "period_log_effect"), ("order", "order_log_effect"), ("carryover", "carryover_log_effect")):
			first_group = [row for row in rows if row["cell"] == first_diagnostic["cell"]]
			logs = [math.log(cast(float, row["warm_seconds"])) for row in first_group]
			overall = statistics.fmean(logs)
			expected: dict[str, list[float]] = {}
			for index, row in enumerate(first_group):
				expected.setdefault(str(row[field]), []).append(logs[index] - overall)
			self.assertEqual(
				{name: statistics.fmean(values) for name, values in expected.items()},
				_dict(first_effects[effect_name]),
			)
		for row in rows:
			row["warm_seconds"] = 100 + (row["pilot_repeat"] - 3) * 2
			row["lifecycle"]["warm_seconds"] = row["warm_seconds"]
		self.assertEqual(5, select(rows)["selected_repeats"])
		for row in rows:
			row["warm_seconds"] = 100 + (row["pilot_repeat"] - 3) * 5
			row["lifecycle"]["warm_seconds"] = row["warm_seconds"]
		self.assertEqual(7, select(rows)["selected_repeats"])

		prereg_hash = cast(str, prereg["preregistration_manifest_sha256"])
		for row in rows:
			cast(dict[str, object], row["identity"])["manifest_hash"] = prereg_hash
		selection_v3 = select_campaign_pilot_repeats(
			rows, self._exact_row_validator(rows), expected_manifest_hash=prereg_hash,
			preregistration_manifest=prereg, discovery_completion_receipt=completion,
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		reservation = build_pilot_resource_reservation(
			pilot_selection_receipt=selection_v3, preregistration_manifest=prereg,
			discovery_completion_receipt=completion,
			pilot_evidence_validator=self._exact_row_validator(rows),
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		for forgery in ("fabricated_path", "relabelled_invocation"):
			forged_selection = copy.deepcopy(selection_v3)
			forged_rows = _list(forged_selection["pilot_rows"])
			if forgery == "fabricated_path":
				_dict(forged_rows[0])["evidence_location"] = {"committed_path": "/does/not/exist"}
			else:
				for row in forged_rows:
					_dict(row)["invocation_manifest_sha256"] = "e" * 64
			forged_selection["pilot_rows_sha256"] = hashlib.sha256(json.dumps(
				forged_selection["pilot_rows"], sort_keys=True, separators=(",", ":"), ensure_ascii=True
			).encode()).hexdigest()
			forged_selection.pop("pilot_selection_sha256")
			forged_selection["pilot_selection_sha256"] = hashlib.sha256(json.dumps(
				forged_selection, sort_keys=True, separators=(",", ":"), ensure_ascii=True
			).encode()).hexdigest()
			expected_error = "revalidation" if forgery == "fabricated_path" else "P/D-derived typed identity"
			with self.subTest(forgery=forgery), self.assertRaisesRegex(CampaignContractError, expected_error):
				build_pilot_resource_reservation(
					pilot_selection_receipt=forged_selection, preregistration_manifest=prereg,
					discovery_completion_receipt=completion,
					pilot_evidence_validator=self._exact_row_validator(rows),
					discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
				)
		self.assertEqual(1113, reservation["p95_artifact_bytes"])
		self.assertEqual(25, reservation["p95_artifact_inodes"])
		self.assertEqual(125.0, reservation["p95_lifecycle_seconds"])
		repeats = cast(int, selection_v3["selected_repeats"])
		schedule = build_block_counterbalanced_schedule(CAMPAIGN_PLANNERS, repeats, campaign_block_ids(), 19)
		final = build_final_campaign_manifest(
			preregistration_manifest=prereg, pilot_selection_receipt=selection_v3,
			pilot_resource_reservation=reservation, discovery_completion_receipt=completion,
			pilot_evidence_validator=self._exact_row_validator(rows),
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		self.assertEqual("systemds-federated-docker-campaign/v4", final["schema"])
		self.assertEqual(repeats, final["selected_repeats"])
		self.assertEqual(prereg["frozen_core"], final["frozen_core"])
		self.assertEqual({
			"preregistration_manifest_sha256", "pilot_selection_sha256", "stage_descriptor_sha256",
			"cp_lifecycle_descriptor_sha256", "reference_manifest_sha256",
			"pilot_resource_reservation_sha256", "discovery_completion_sha256",
		}, set(_dict(final["lineage"])))
		self.assertEqual(final, validate_final_campaign_manifest(final))
		first_cell = campaign_cell_ids()[0]
		invocation = build_canonical_final_invocation(final, first_cell, 1)
		self.assertEqual("final_performance", invocation["kind"])
		self.assertEqual(final["manifest_hash"], invocation["manifest_hash"])
		first_block = _dict(_list(_dict(final["schedule"])["blocks"])[0])
		first_run = _dict(_list(first_block["runs"])[0])
		self.assertEqual(first_run["order"], invocation["order"])
		self.assertEqual(
			next(item["period"] for item in _list(first_run["periods"]) if item["planner"] == "DP"),
			invocation["period"],
		)
		for source, field in ((selection_v3, "pilot_selection_sha256"), (reservation, "payload_sha256")):
			altered = copy.deepcopy(source)
			altered[field] = "e" * 64
			with self.subTest(altered_root=field), self.assertRaises(CampaignContractError):
				build_final_campaign_manifest(
					preregistration_manifest=prereg,
					pilot_selection_receipt=altered if field == "pilot_selection_sha256" else selection_v3,
					pilot_resource_reservation=altered if field == "payload_sha256" else reservation,
					discovery_completion_receipt=completion,
					pilot_evidence_validator=self._exact_row_validator(rows),
					discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
				)
		forged_prereg = json.loads(json.dumps(prereg))
		forged_prereg["pilot_preregistration"]["schedule_seed"] = 20
		forged_prereg.pop("preregistration_manifest_sha256")
		forged_prereg["preregistration_manifest_sha256"] = hashlib.sha256(json.dumps(
			forged_prereg, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		with self.assertRaisesRegex(CampaignContractError, "pilot seed/schedule"):
			validate_campaign_preregistration_manifest(forged_prereg)

		duplicate_selection = json.loads(json.dumps(selection_v3))
		duplicate_selection["pilot_rows"][1] = duplicate_selection["pilot_rows"][0]
		duplicate_selection["pilot_rows_sha256"] = hashlib.sha256(json.dumps(
			duplicate_selection["pilot_rows"], sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		duplicate_selection.pop("pilot_selection_sha256")
		duplicate_selection["pilot_selection_sha256"] = hashlib.sha256(json.dumps(
			duplicate_selection, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		with self.assertRaises(CampaignContractError):
			build_pilot_resource_reservation(
				pilot_selection_receipt=duplicate_selection, preregistration_manifest=prereg,
				discovery_completion_receipt=completion,
				pilot_evidence_validator=self._exact_row_validator(rows),
				discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
			)

		understated = json.loads(json.dumps(reservation))
		understated["p95_artifact_bytes"] -= 1
		understated.pop("payload_sha256")
		understated["payload_sha256"] = hashlib.sha256(json.dumps(
			understated, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		with self.assertRaisesRegex(CampaignContractError, "exact canonical"):
			build_final_campaign_manifest(
				preregistration_manifest=prereg, pilot_selection_receipt=selection_v3,
				pilot_resource_reservation=understated, discovery_completion_receipt=completion,
				pilot_evidence_validator=self._exact_row_validator(rows),
				discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
			)
		alternate_rows = copy.deepcopy(rows)
		alternate_rows[0]["resource_evidence"]["artifact_bytes"] += 5000
		alternate_selection = select_campaign_pilot_repeats(
			alternate_rows, self._exact_row_validator(alternate_rows), expected_manifest_hash=prereg_hash,
			preregistration_manifest=prereg, discovery_completion_receipt=completion,
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		alternate_reservation = build_pilot_resource_reservation(
			pilot_selection_receipt=alternate_selection, preregistration_manifest=prereg,
			discovery_completion_receipt=completion,
			pilot_evidence_validator=self._exact_row_validator(alternate_rows),
			discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
		)
		with self.assertRaisesRegex(CampaignContractError, "exact canonical"):
			build_final_campaign_manifest(
				preregistration_manifest=prereg, pilot_selection_receipt=selection_v3,
				pilot_resource_reservation=alternate_reservation, discovery_completion_receipt=completion,
				pilot_evidence_validator=self._exact_row_validator(rows),
				discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
			)

		cross_completion = self._discovery_completion(self._campaign_v3_preregistration())
		cross_completion = json.loads(json.dumps(cross_completion))
		cross_completion["discovery_rows"][0]["evidence_sha256"] = "f" * 64
		cross_completion["discovery_rows_sha256"] = hashlib.sha256(json.dumps(
			cross_completion["discovery_rows"], sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		cross_completion.pop("discovery_completion_sha256")
		cross_completion["discovery_completion_sha256"] = hashlib.sha256(json.dumps(
			cross_completion, sort_keys=True, separators=(",", ":"), ensure_ascii=True
		).encode()).hexdigest()
		with self.assertRaisesRegex(CampaignContractError, "revalidation"):
			build_final_campaign_manifest(
				preregistration_manifest=prereg, pilot_selection_receipt=selection_v3,
				pilot_resource_reservation=reservation, discovery_completion_receipt=cross_completion,
				pilot_evidence_validator=self._exact_row_validator(rows),
				discovery_evidence_validator=self._exact_row_validator(_list(completion["discovery_rows"])),
			)

	def phase_bundle(self, metric_kind="systemds_total_execution_time"):
		phase = self.root / "phase"
		phase.mkdir(exist_ok=True)
		files = {
			"raw_coordinator.log": b"Total execution time: 1.25 sec.\n",
			"output.bin": b"semantic output",
			"semantic_oracle.json": json.dumps({"passed": True}).encode(),
			"return_code.txt": b"0\n",
			"scan.json": json.dumps({"timeout": False, "error": False, "fallback": False, "resource_invalid": False}).encode(),
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
