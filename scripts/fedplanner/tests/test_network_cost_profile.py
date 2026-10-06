import importlib.util
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "network_cost_profile.py"
SPEC = importlib.util.spec_from_file_location("network_cost_profile_r60_test", SCRIPT)
NETWORK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NETWORK)

SYNTHETIC_ENVIRONMENT = {
    "SYSDS_FED_COST_FLOPS": "3000000001",
    "SYSDS_FED_COST_AGGBINARY_FLOPS": "3000000002",
    "SYSDS_FED_COST_MEM_BW": "31001",
    "SYSDS_FED_COST_NET_LATENCY_C2W": "0.000321",
    "SYSDS_FED_COST_NET_LATENCY_W2C": "0.000654",
    "SYSDS_FED_COST_NET_BW_C2W": "501.1",
    "SYSDS_FED_COST_NET_BW_W2C": "502.2",
    "SYSDS_FED_COST_NET_BW_COORD_C2W": "503.3",
    "SYSDS_FED_COST_NET_BW_COORD_W2C": "504.4",
    "SYSDS_FED_COST_NET_SERDES_BW_C2W": "505.5",
    "SYSDS_FED_COST_NET_SERDES_BW_W2C": "506.6",
}


def synthetic_binding(*_args, **_kwargs):
    return {"status": "measured", "profile_sha256": "b" * 64,
            "profile_path": "/synthetic/measured-profile.json", "profile_name": "lan",
            "selected_hosts": {"coordinator": "coord", "workers": ["worker"]},
            "cost_environment": dict(SYNTHETIC_ENVIRONMENT)}


class NetworkCostProfileTest(unittest.TestCase):
    def test_missing_explicit_measured_profile_fails_closed(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "COFEE_COST_PROFILE|measured cost profile"):
                NETWORK.cost_binding("lan", {"rtt_ms": 1, "c2w_mbit": 5000,
                                             "w2c_mbit": 5000},
                    coordinator_host="coord", worker_hosts=("worker",),
                    image=NETWORK.DEFAULT_IMAGE)

    def test_explicit_synthetic_measured_profile_is_validated_and_returned_verbatim(self):
        profile = {"schema": "cofee-experiment-cost-profile/v1", "synthetic": True,
                   "profile_sha256": "b" * 64,
                   "cost_environment": dict(SYNTHETIC_ENVIRONMENT)}
        provider = SimpleNamespace(load_profile=Mock(return_value=profile),
            validate_profile=Mock(), validate_selection=Mock())
        network = {"rtt_ms": 1, "c2w_mbit": 5000, "w2c_mbit": 5000}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-measured-profile.json"
            path.write_text(json.dumps(profile))
            with patch.object(NETWORK, "_provider", return_value=provider):
                binding = NETWORK.cost_binding("lan", network, coordinator_host="coord",
                    worker_hosts=("worker",), image=NETWORK.DEFAULT_IMAGE, profile_path=path)
        self.assertEqual(binding["status"], "measured")
        self.assertEqual(binding["profile_sha256"], "b" * 64)
        self.assertEqual(binding["cost_environment"], SYNTHETIC_ENVIRONMENT)
        provider.validate_profile.assert_called_once_with(profile)
        provider.validate_selection.assert_called_once_with(profile,
            coordinator_host="coord", worker_hosts=("worker",),
            image=NETWORK.DEFAULT_IMAGE, network=network)

    def test_real_provider_accepts_digest_bound_synthetic_profile(self):
        network = {"rtt_ms": 1, "c2w_mbit": 5000, "w2c_mbit": 5000,
                   "queue_limit_packets": 1000}
        profile = {
            "schema": "cofee-experiment-cost-profile/v1",
            "identity": {"coordinator": {"host": "coord", "ip": "10.0.0.1"},
                         "workers": [{"host": "worker", "ip": "10.0.0.2", "port": 8001}],
                         "network": {"rtt_ms": 1.0, "c2w_mbit": 5000.0,
                                     "w2c_mbit": 5000.0, "queue_limit_packets": 1000},
                         "image": NETWORK.DEFAULT_IMAGE, "synthetic_fixture": True},
            "cost_environment": dict(SYNTHETIC_ENVIRONMENT),
            "provenance": {"measurement": "synthetic test fixture; not performance evidence"},
            "raw_summaries": {"synthetic": True},
        }
        profile["profile_sha256"] = hashlib.sha256(json.dumps(profile, sort_keys=True,
            separators=(",", ":"), allow_nan=False).encode()).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-measured-profile.json"
            path.write_text(json.dumps(profile))
            binding = NETWORK.cost_binding("lan", network, coordinator_host="coord",
                worker_hosts=("worker",), image=NETWORK.DEFAULT_IMAGE, profile_path=path)
        self.assertEqual(binding["status"], "measured")
        self.assertEqual(binding["profile_sha256"], profile["profile_sha256"])
        self.assertEqual(binding["cost_environment"], SYNTHETIC_ENVIRONMENT)

    def test_profile_directory_selects_exact_environment_and_has_inventory_digest(self):
        def measured(worker, network, marker):
            environment = dict(SYNTHETIC_ENVIRONMENT)
            environment["SYSDS_FED_COST_FLOPS"] = str(marker)
            value = {"schema": "cofee-experiment-cost-profile/v1",
                "identity": {"coordinator": {"host": "coord", "ip": "10.0.0.1"},
                    "workers": [{"host": worker, "ip": "10.0.0.2", "port": 8001}],
                    "network": network, "image": NETWORK.DEFAULT_IMAGE, "synthetic_fixture": True},
                "cost_environment": environment,
                "provenance": {"measurement": "synthetic test fixture; not performance evidence"},
                "raw_summaries": {"synthetic": True}}
            value["profile_sha256"] = hashlib.sha256(json.dumps(value, sort_keys=True,
                separators=(",", ":"), allow_nan=False).encode()).hexdigest()
            return value
        lan = {"rtt_ms": 1.0, "c2w_mbit": 5000.0, "w2c_mbit": 5000.0,
               "queue_limit_packets": 1000}
        wan = {"rtt_ms": 10.0, "c2w_mbit": 2500.0, "w2c_mbit": 1000.0,
               "queue_limit_packets": 1000}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "lan.json").write_text(json.dumps(measured("worker", lan, 4000000001)))
            (root / "wan.json").write_text(json.dumps(measured("other", wan, 4000000002)))
            binding = NETWORK.cost_binding("lan", lan, coordinator_host="coord",
                worker_hosts=("worker",), image=NETWORK.DEFAULT_IMAGE, profile_path=root)
            reference = NETWORK.profile_reference(root)
            (root / "lan-copy.json").write_text((root / "lan.json").read_text())
            with self.assertRaisesRegex(ValueError, "selection is ambiguous"):
                NETWORK.cost_binding("lan", lan, coordinator_host="coord",
                    worker_hosts=("worker",), image=NETWORK.DEFAULT_IMAGE, profile_path=root)
        self.assertEqual(binding["cost_environment"]["SYSDS_FED_COST_FLOPS"], "4000000001")
        self.assertEqual(len(reference["profiles"]), 2)
        self.assertEqual([item["path"] for item in reference["profiles"]], ["lan.json", "wan.json"])

    def test_selection_mismatch_is_not_downgraded_to_configured_defaults(self):
        profile = {"profile_sha256": "b" * 64,
                   "cost_environment": dict(SYNTHETIC_ENVIRONMENT)}
        provider = SimpleNamespace(load_profile=Mock(return_value=profile),
            validate_profile=Mock(), ProfileError=ValueError,
            validate_selection=Mock(side_effect=ValueError("profile selection mismatch")))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic-measured-profile.json"
            path.write_text(json.dumps(profile))
            with patch.object(NETWORK, "_provider", return_value=provider):
                with self.assertRaisesRegex(ValueError, "no measured cost profile matches"):
                    NETWORK.cost_binding("lan", {"rtt_ms": 2, "c2w_mbit": 5,
                                                 "w2c_mbit": 5},
                        coordinator_host="coord", worker_hosts=("worker",),
                        image=NETWORK.DEFAULT_IMAGE, profile_path=path)


if __name__ == "__main__":
    unittest.main()
