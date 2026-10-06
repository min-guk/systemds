import importlib.util
import json
import math
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "calibration" / "transport_profile.py"
SPEC = importlib.util.spec_from_file_location("transport_profile", SCRIPT)
PROFILE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROFILE)
NETWORK_SCRIPT = Path(__file__).resolve().parents[1] / "network_cost_profile.py"
NETWORK_SPEC = importlib.util.spec_from_file_location("network_cost_profile_test", NETWORK_SCRIPT)
NETWORK = importlib.util.module_from_spec(NETWORK_SPEC)
NETWORK_SPEC.loader.exec_module(NETWORK)


def row(operation, payload_mib, elapsed_sec, *, workers=1,
        distribution="fixed-total", wire_mib=None):
	logical_mib = payload_mib * workers if distribution == "fixed-per-worker" else payload_mib
	return {
		"kind": "transport-calibration", "correctness": True,
		"operation": operation, "payload_mib": payload_mib,
		"workers": workers, "distribution": distribution, "sample": 0,
		"logical_bytes": round(logical_mib * PROFILE.MIB),
		"application_frame_bytes": round((wire_mib or logical_mib) * PROFILE.MIB),
		"elapsed_ns": round(elapsed_sec * 1e9),
	}


class TransportCalibrationProfileTest(unittest.TestCase):
	def test_stage_cost_uses_shared_coordinator_bottleneck(self):
		cost = PROFILE.stage_seconds(
			latency_seconds=0.003, largest_wire_mib=10, total_wire_mib=30,
			worker_leg_mib_per_sec=100, coordinator_mib_per_sec=120,
			total_codec_mib=30, codec_mib_per_sec=300)
		self.assertAlmostEqual(cost, 0.003 + 0.25 + 0.1)

	def test_fit_is_nonnegative_and_has_no_intercept(self):
		observations = [
			PROFILE.Observation(4, 0.05, 0.01, 4),
			PROFILE.Observation(16, 0.17, 0.01, 16),
		]
		fit = PROFILE.fit_codec_through_origin(observations)
		self.assertNotIn("intercept", fit)
		self.assertAlmostEqual(fit["seconds_per_mib"], 0.01)
		self.assertAlmostEqual(fit["codec_mib_per_sec"], 100)
		negative = PROFILE.fit_codec_through_origin([
			PROFILE.Observation(4, 0.005, 0.01, 4)])
		self.assertEqual(negative["seconds_per_mib"], 0)
		self.assertIsNone(negative["codec_mib_per_sec"])
		self.assertEqual(negative["negative_residual_count"], 1)

	def test_direction_fit_pools_put_operations_and_keeps_split(self):
		latency = 0.001
		rate = 100.0
		codec = 200.0
		rows = []
		for operation in ("broadcast-put", "slice-put"):
			for payload in (4, 16, 64):
				elapsed = 2 * latency + payload / rate + payload / codec
				rows.append(row(operation, payload, elapsed))
		result = PROFILE.fit_direction(
			rows, direction="C2W", latency_seconds=latency,
			worker_leg_mib_per_sec=rate, coordinator_mib_per_sec=rate,
			baseline_codec_mib_per_sec=400)
		self.assertEqual(result["operations"], ["broadcast-put", "slice-put"])
		self.assertEqual(result["training_samples"], 4)
		self.assertEqual(result["heldout_samples"], 2)
		self.assertEqual(result["diagnostic_samples"], 0)
		self.assertAlmostEqual(result["candidate_codec_mib_per_sec"], codec)
		self.assertTrue(result["adopted"])
		self.assertAlmostEqual(result["selected_codec_mib_per_sec"], codec)

	def test_non_improving_candidate_retains_baseline(self):
		rows = []
		for payload in (4, 16):
			rows.append(row("get", payload, 0.002 + payload / 100 + payload / 200))
		# Held-out observation matches the baseline rather than the training candidate.
		rows.append(row("get", 64, 0.002 + 64 / 100 + 64 / 400))
		result = PROFILE.fit_direction(
			rows, direction="W2C", latency_seconds=0.001,
			worker_leg_mib_per_sec=100, coordinator_mib_per_sec=100,
			baseline_codec_mib_per_sec=400)
		self.assertFalse(result["adopted"])
		self.assertEqual(result["adoption_status"], "baseline-retained")
		self.assertEqual(result["selected_codec_mib_per_sec"], 400)

	def test_wire_bound_violation_rejects_without_nonstandard_json(self):
		rows = [row("get", payload, 0.001) for payload in (4, 16, 64)]
		result = PROFILE.fit_direction(
			rows, direction="W2C", latency_seconds=0.001,
			worker_leg_mib_per_sec=100, coordinator_mib_per_sec=100,
			baseline_codec_mib_per_sec=400)
		self.assertFalse(result["adopted"])
		self.assertIsNone(result["candidate_codec_mib_per_sec"])
		self.assertIsNone(result["candidate_heldout_median_relative_error"])
		self.assertGreater(result["negative_training_residual_count"], 0)
		json.dumps(result, allow_nan=False)

	def test_environment_settings_are_directional_and_have_no_legacy_keys(self):
		profile = {
			"directions": {
				"C2W": {"one_way_latency_seconds": 0.003,
					"worker_leg_mib_per_sec": 100, "coordinator_mib_per_sec": 80,
					"selected_codec_mib_per_sec": 200},
				"W2C": {"one_way_latency_seconds": 0.007,
					"worker_leg_mib_per_sec": 90, "coordinator_mib_per_sec": 70,
					"selected_codec_mib_per_sec": 180},
			}
		}
		environment = PROFILE.cost_environment(profile)
		self.assertEqual(environment, {
			"SYSDS_FED_COST_NET_LATENCY_C2W": "0.003000",
			"SYSDS_FED_COST_NET_LATENCY_W2C": "0.007000",
			"SYSDS_FED_COST_NET_BW_C2W": "100.000000",
			"SYSDS_FED_COST_NET_BW_W2C": "90.000000",
			"SYSDS_FED_COST_NET_BW_COORD_C2W": "80.000000",
			"SYSDS_FED_COST_NET_BW_COORD_W2C": "70.000000",
			"SYSDS_FED_COST_NET_SERDES_BW_C2W": "200.000000",
			"SYSDS_FED_COST_NET_SERDES_BW_W2C": "180.000000",
		})
		self.assertNotIn("SYSDS_FED_COST_NET_BW", environment)
		self.assertNotIn("SYSDS_FED_COST_NET_SERDES_BW", environment)


if __name__ == "__main__":
	unittest.main()
