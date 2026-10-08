import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from copy import deepcopy

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("cofee_validation", HERE / "run_cofee_50k128_validation.py")
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class ValidationRunnerTest(unittest.TestCase):
    def topology(self, root, **changes):
        value = {"schema": MOD.SCHEMA, "coordinator": dict(MOD.ALLOWED_COORDINATOR),
                 "workers": [dict(MOD.ALLOWED_WORKER)], "worker_sweep": [1],
                 "adjustment": {"canonicalWorker": "so002", "actualWorker": "so006",
                                "reason": "canonical-worker-occupied-by-unowned-containers"}}
        value.update(changes)
        path = root / "topology.json"
        path.write_text(json.dumps(value))
        return path

    def test_accepts_only_explicit_so006_w1_adjustment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = MOD.validate_topology(self.topology(root))
            self.assertEqual("so006", value["workers"][0]["host"])
            bad = value.copy()
            bad["workers"] = [{**MOD.ALLOWED_WORKER, "host": "so002"}]
            path = root / "bad.json"
            path.write_text(json.dumps(bad))
            with self.assertRaisesRegex(ValueError, "only W1 so006"):
                MOD.validate_topology(path)

    def test_rejects_reserved_proxy_and_extra_workers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for worker in ({**MOD.ALLOWED_WORKER, "host": "so001"}, MOD.ALLOWED_WORKER):
                value = json.loads(self.topology(root).read_text())
                value["workers"] = [worker, dict(MOD.ALLOWED_WORKER)]
                path = root / (worker["host"] + ".json")
                path.write_text(json.dumps(value))
                with self.assertRaises(ValueError):
                    MOD.validate_topology(path)

    def test_transform_limits_all_host_loop_and_pins_provenance(self):
        upstream = Path("/home/mchoi/w1357-structural-grounding-20261006/scripts/fedplanner/run_matrix_campaign.py")
        result = MOD.transform(upstream.read_text())
        self.assertNotIn("hosts = ['so007', 'so002', 'so003'", result)
        self.assertIn("hosts = [topology_value['coordinator']['host']", result)
        self.assertIn("validation_topology_sha256", result)
        self.assertIn("engine_artifact_manifest_sha256", result)
        self.assertIn("Renderer(args.stage, topology=VALIDATION_TOPOLOGY)", result)
        self.assertIn("RUNTIME_LANE.open('a+')", result)
        self.assertIn("validation-measured-replay", result)
        self.assertIn("provider.validate_selection(cost_profile", result)
        self.assertIn("start_plan = pinned_runtime_start_plan(base.build_plan(spec, 'start'), pinned_runtime)", result)
        self.assertIn("-Dsysds.fedplanner.liveMetrics=true", result)

    def test_transform_fails_closed_on_upstream_drift(self):
        upstream = Path("/home/mchoi/w1357-structural-grounding-20261006/scripts/fedplanner/run_matrix_campaign.py").read_text()
        with self.assertRaisesRegex(ValueError, "patch anchor count"):
            MOD.transform(upstream.replace("hosts = ['so007'", "hosts = ['changed'"))

    def test_cost_profile_rejects_resource_native_and_vector_drift(self):
        valid = {"profile_sha256": "a" * 64,
                 "cost_environment": {f"SYSDS_FED_COST_{i}": "1" for i in range(11)},
                 "identity": {"resources": MOD.EXPECTED_RESOURCES, "threads": 8,
                              "expected_native_blas": "unavailable",
                              "jvm_options": MOD.EXPECTED_JVM_CORE,
                              "coordinator": MOD.ALLOWED_COORDINATOR,
                              "workers": [MOD.EXPECTED_PROFILE_WORKER]}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"
            path.write_text(json.dumps(valid))
            self.assertEqual("a" * 64, MOD.validate_cost_profile(path)["profile_sha256"])
            for mutation in (lambda x: x["identity"]["resources"].update(memory="16g"),
                             lambda x: x["identity"].update(expected_native_blas="mkl"),
                             lambda x: x["cost_environment"].pop("SYSDS_FED_COST_0")):
                changed = deepcopy(valid)
                mutation(changed)
                path.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, "identity mismatch|vector mismatch"):
                    MOD.validate_cost_profile(path)

    def test_accepts_frozen_measured_profile_worker_identity(self):
        path = Path(
            "/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/"
            "evidence/cofee-50k128-measured-cost-profile-so007-so006-unavailable.json")
        if not path.is_file():
            self.skipTest("frozen measured profile is not available on this validation host")
        profile = MOD.validate_cost_profile(path)
        self.assertEqual([MOD.EXPECTED_PROFILE_WORKER], profile["identity"]["workers"])
        self.assertNotIn("index", profile["identity"]["workers"][0])

    def test_receipt_cost_must_equal_frozen_vector(self):
        profile = {"cost_environment": {"SYSDS_FED_COST_FLOPS": "1"}}
        MOD.validate_receipt_cost(
            {"effectiveCostEnvironment": dict(profile["cost_environment"])}, profile)
        with self.assertRaisesRegex(ValueError, "differs from frozen"):
            MOD.validate_receipt_cost(
                {"effectiveCostEnvironment": {"SYSDS_FED_COST_FLOPS": "2"}}, profile)

    def test_engine_repo_requires_exact_pinned_dependency_link(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            dependencies = root / "pinned-lib"
            dependencies.mkdir()
            (dependencies / "one.jar").write_bytes(b"one")
            engine = root / "engine"
            for relative in ("target/systemds-3.4.0-SNAPSHOT.jar", "pom.xml",
                             "scripts/builtin/steplm.dml", "scripts/builtin/lmCG.dml",
                             "src/test/java/org/apache/sysds/test/functions/federated/fedplanning/MatrixCampaignProbe.java"):
                path = engine / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture")
            (engine / "ENGINE_ARTIFACT.json").write_text(json.dumps({
                "dependencies": str(dependencies)}))
            with self.assertRaisesRegex(ValueError, "target/lib does not resolve"):
                MOD.validate_engine_repo(engine)
            (engine / "target/lib").symlink_to(dependencies, target_is_directory=True)
            MOD.validate_engine_repo(engine)
            (engine / "target/lib").unlink()
            other = root / "other-lib"
            other.mkdir()
            (other / "one.jar").write_bytes(b"one")
            (engine / "target/lib").symlink_to(other, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "target/lib does not resolve"):
                MOD.validate_engine_repo(engine)


if __name__ == "__main__":
    unittest.main()
