"""Ablation evidence and execution-boundary tests; no Docker or workloads."""
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_pruning_ablation as ablation


class PruningAblationTest(unittest.TestCase):
    def test_only_baseline_and_local_remain(self):
        self.assertEqual(("baseline", "local_only"), ablation.VARIANTS)

    def test_removed_variants_rejected_before_preparing_an_experiment(self):
        for variant in ("dominance", "support", "dominance_local", "support_local", "local", "global"):
            with self.subTest(variant=variant), mock.patch.object(ablation.matrix, "dependencies") as prepare, \
                    mock.patch("sys.stderr", new_callable=io.StringIO):
                with self.assertRaises(SystemExit) as error:
                    ablation.main(["--root", "unused", "--cost-profile", "unused", "--variants", variant])
                self.assertEqual(2, error.exception.code)
                prepare.assert_not_called()

    def test_paired_rotation_preserves_every_sample(self):
        rows = ablation.schedule(list(ablation.WORKLOADS), list(ablation.VARIANTS), 3, 3, "wan_mid")
        self.assertEqual(60, len(rows))
        self.assertEqual(60, len({row["key"] for row in rows}))
        for workload in ablation.WORKLOADS:
            for repetition in range(1, 4):
                block = [row for row in rows if row["cell"]["workload"] == workload
                         and row["repetition"] == repetition]
                self.assertEqual(set(ablation.VARIANTS), {row["variant"] for row in block})
                self.assertEqual({ablation.matrix.ENUMS["DP-local"]},
                                 {row["cell"]["planner_enum"] for row in block})
        first = [r["variant"] for r in rows if r["cell"]["workload"] == "logreg" and r["repetition"] == 1]
        second = [r["variant"] for r in rows if r["cell"]["workload"] == "logreg" and r["repetition"] == 2]
        self.assertNotEqual(first, second)

    def test_recovery_preserves_original_sample_keys_and_order(self):
        original = ablation.schedule(list(ablation.WORKLOADS), list(ablation.VARIANTS), 3, 3, "wan_mid")
        workloads = [w for w in ablation.WORKLOADS if w not in ("logreg", "steplm")]
        recovery = ablation.schedule(workloads, list(ablation.VARIANTS), 1, 3, "wan_mid", first_repetition=3)
        self.assertEqual(16, len(recovery))
        self.assertEqual([row for row in original if row["cell"]["workload"] in workloads
                          and row["repetition"] == 3], recovery)

    def test_isolated_production_compile_and_readonly_inputs(self):
        args = SimpleNamespace(root=Path("/grid/3/study"), stage=Path("/sealed"), cpus="8-15")
        manifest = {"study_id": "abc", "identity": {"cost_environment": {}, "cost_profile_sha256": "profile"}}
        row = ablation.schedule(["l2svm"], ["local_only"], 1, 3, "wan_mid")[0]
        _, command = ablation.docker_command(args, manifest, row, Path("/grid/3/study/attempts/one"))
        self.assertEqual("none", command[command.index("--network") + 1])
        self.assertIn(ablation.matrix.PROBE, command)
        self.assertIn("-Dsysds.fedplanner.runtime.audit=true", command)
        self.assertIn("-Dsysds.fedplanner.regional.incremental.timeMillis=0", command)
        self.assertIn("-Dsysds.fedplanner.pruning.ablation=local_only", command)
        self.assertEqual("compile", command[command.index("--mode") + 1])
        self.assertNotIn("/var/run/docker.sock", " ".join(command))
        mounts = [command[i+1] for i, token in enumerate(command) if token == "--mount"]
        writable = [value for value in mounts if not value.endswith(",readonly")]
        self.assertEqual(2, len(writable))
        self.assertTrue(all("/attempts/one/" in value for value in writable))

    def test_missing_or_wrong_variant_receipt_is_rejected(self):
        for log in ("", 'DP-PruningAblationReceipt {"variant":"baseline"}\n',
                    'DP-PruningAblationReceipt {"variant":"local_only"}\n'):
            with self.assertRaises(ValueError):
                ablation.pruning_receipts(log, "local_only")
        row = dict.fromkeys(ablation.COUNTERS, 0)
        row.update(variant="local_only", objectiveBits="0", initialUpperBits="0",
                   planFingerprint="a"*64, stop="EXACT", lower="0", upper="0", gap="0")
        log = "DP-PruningAblationReceipt " + json.dumps(row) + "\n"
        self.assertEqual("0", ablation.pruning_receipts(log, "local_only")[0]["objectiveBits"])
        with self.assertRaises(ValueError):
            ablation.pruning_receipts(log + log, "local_only")
        row.update(objectiveBits="4607182418800017408", upper="1.0", lower="0.0",
                   gap="Infinity", stop="RESOURCE_INITIAL")
        log = "DP-PruningAblationReceipt " + json.dumps(row) + "\n"
        self.assertEqual("Infinity", ablation.pruning_receipts(log, "local_only")[0]["gap"])

    def test_failed_sample_never_contributes_latency(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            planned = ablation.schedule(["l2svm"], ["local_only"], 2, 3, "wan_mid")
            row = planned[0]
            passed = dict(row, status="passed", pruning=[], attempt="attempts/a", errors=[])
            passed.update(dict.fromkeys(ablation.MEASURES, 10.0))
            failed = dict(planned[1], status="failed", attempt="attempts/b", errors=["OOM"])
            failed.update(dict.fromkeys(ablation.MEASURES, 0.01))
            ablation.matrix.dump(root / "attempts/a/result.json", passed)
            ablation.matrix.dump(root / "attempts/b/result.json", failed)
            _, summary = ablation.summarize(root, {"identity": {"samples": planned}})
            self.assertEqual(1, summary["passed"])
            self.assertEqual(1, summary["failed"])
            self.assertEqual(10.0, summary["results"][0]["full_initial_planning_seconds"]["median"])

    def test_semantic_reference_covers_paired_and_single_variant_runs(self):
        cases = [(list(ablation.VARIANTS), "baseline"),
                 (["baseline"], "baseline"),
                 (["local_only"], "local_only")]
        for variants, expected_reference in cases:
            with self.subTest(variants=variants), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                planned = ablation.schedule(["l2svm"], variants, 1, 3, "wan_mid")
                for index, row in enumerate(planned):
                    result = dict(row, status="passed", errors=[], attempt=f"attempts/{index}",
                                  pruning=[dict(objectiveBits="1", planFingerprint="same", stop="EXACT",
                                                lower="1", upper="1")])
                    result.update(dict.fromkeys(ablation.MEASURES, 10.0))
                    ablation.matrix.dump(root / f"attempts/{index}/result.json", result)
                _, summary = ablation.summarize(root, {"identity": {"samples": planned}})
                self.assertEqual(expected_reference, summary["reference_variant"])
                self.assertEqual(len(variants) - 1, len(summary["paired_semantics"]))
                self.assertTrue(all(pair["reference_variant"] == expected_reference
                                    and pair["objective_equal"] and pair["plan_equal"]
                                    and pair["bounds_equal"] for pair in summary["paired_semantics"]))

    def test_duplicate_and_unknown_sample_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = ablation.schedule(["l2svm"], ["local_only"], 1, 3, "wan_mid")[0]
            result = dict(row, status="failed", errors=["test"])
            ablation.matrix.dump(root / "attempts/a/result.json", result)
            with self.assertRaisesRegex(RuntimeError, "unplanned"):
                ablation.summarize(root, {"identity": {"samples": []}})
            ablation.matrix.dump(root / "attempts/b/result.json", result)
            with self.assertRaisesRegex(RuntimeError, "Duplicate"):
                ablation.summarize(root, {"identity": {"samples": [row]}})

    def test_preparation_failure_is_recorded_without_launching_docker(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(ablation.subprocess, "run") as command:
            args = SimpleNamespace(root=Path(directory))
            row = ablation.schedule(["l2svm"], ["local_only"], 1, 3, "wan_mid")[0]
            renderer = SimpleNamespace(render=mock.Mock(side_effect=ValueError("bad metadata")))
            result = ablation.execute(args, {}, row, renderer)
            self.assertEqual("failed", result["status"])
            self.assertTrue(result["cleanup_resolved"])
            self.assertEqual(["bad metadata"], result["errors"])
            self.assertEqual(1, len(list(args.root.glob("attempts/*/result.json"))))
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
