import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "freeze_campaign_conditions.py"
SPEC = importlib.util.spec_from_file_location("freeze_campaign_conditions", SCRIPT)
FREEZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FREEZE)


class CampaignConditionFreezeTest(unittest.TestCase):
    def write(self, root, relative, content):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def metadata(self, rows=10, cols=2, privacy="private-aggregate"):
        return {"rows": rows, "cols": cols, "nnz": rows * cols, "format": "binary",
                "data_type": "matrix", "value_type": "double", "privacy": privacy}

    def test_freezes_complete_inputs_and_keeps_missing_base_metadata_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evaluation = root / "evaluation"
            stage = root / "stage"
            output = root / "freeze.json"
            base_source = (
                "NETWORK = {'lan': {'rtt_ms': 1, 'c2w_mbit': 5000, 'w2c_mbit': 5000}}\n"
                "PROFILES = ('lan',)\nWORKERS = (1,)\n"
                "SEEDS = {'SYSTEMDS_SEED': '10', 'KMEANS_SEED': '11', 'ALS_SEED': '12'}\n")
            base_path = self.write(evaluation, FREEZE.BASE_DRIVER, base_source)
            ml10_source = (
                "WORKLOADS = ('pca',)\nPROFILES = ('lan',)\nWORKERS = (1,)\n"
                "BINARY_LABEL_WORKLOADS = frozenset(())\n"
                "SUPERVISED = frozenset(())\nGNMF_SEED = 13\n"
                f"BASE_DRIVER_SHA256 = '{FREEZE.file_sha(base_path)}'\n")
            ml10_path = self.write(evaluation, FREEZE.ML10_DRIVER, ml10_source)
            template_relative = FREEZE.PLANNING_COMMON / "code/exp/P2_PREP.dml"
            template = "X = federated(addresses=__X_ADDRS__, ranges=__X_RANGES__)\nwrite(X, '__OUT__')\n"
            template_path = self.write(evaluation, template_relative, template)
            protocol_path = self.write(
                evaluation, "planning_study/native/protocol-w1.json",
                json.dumps({"workload_jvm_options": {"P2_PREP": [
                    FREEZE.P2_METADATA_RELEASE_OPTION]}}))
            worker_root = evaluation / FREEZE.PLANNING_WORKER / "w1"
            metadata_path = self.write(worker_root, "metadata/matrix_metadata/P2P2D_features.data.mtd",
                                       json.dumps(self.metadata()))
            partition_path = self.write(worker_root, "metadata/worker-partitions.json", json.dumps({
                "partitions": {"P2P2D_features": {
                    "global_metadata": self.metadata(),
                    "partitions": [{"worker": 1, "begin": [0, 0], "end": [10, 2],
                                    "metadata": "data/P2P2D_features.data.mtd"}]}}}))
            planned = {"workers": 1, "network": {
                "cost_environment": FREEZE.network_cost({
                    "rtt_ms": 1, "c2w_mbit": 5000, "w2c_mbit": 5000})},
                "case": {"workers": 1, "workload": "P2_PREP", "dataset": "P2",
                         "metadata": {"X": self.metadata()},
                         "worker_input_mappings": ["P2P2D_features"],
                         "template": "code/exp/P2_PREP.dml",
                         "template_sha256": FREEZE.file_sha(template_path)}}
            condition = {"discoveryId": "planning-w1:P2_PREP", "conditionId": "lan", **planned}
            source_files = {
                template_path.relative_to(evaluation).as_posix(): FREEZE.file_sha(template_path),
                protocol_path.relative_to(evaluation).as_posix(): FREEZE.file_sha(protocol_path),
                metadata_path.relative_to(evaluation).as_posix(): FREEZE.file_sha(metadata_path),
                partition_path.relative_to(evaluation).as_posix(): FREEZE.file_sha(partition_path),
            }
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": [{
                "id": "planning-p2", "discoveryId": "planning-w1:P2_PREP", "conditionId": "lan",
                "sourceFiles": source_files, "sourceBinding": {"kind": "planning-snapshot",
                    "conditionStatus": "SNAPSHOT_ONLY", "plannedCondition": planned,
                    "conditionSha256": FREEZE.digest(condition)}}]}))

            stage_template = self.write(stage, FREEZE.STAGE_EXPERIMENTS / "code/exp/pca_fed.dml",
                                        template)
            stage_meta = self.write(stage, "data/continuous/ADULT_features.data.mtd",
                                    json.dumps(self.metadata(rows=20, cols=4)))
            generator = self.write(stage, FREEZE.STAGE_EXPERIMENTS / "code/distributedExpNew.sh",
                                   "render_fed_dml() { :; }\n")
            parameters = self.write(stage, FREEZE.STAGE_EXPERIMENTS / "parameters.sh",
                                    "privacyConstraints=private-aggregate\n")
            sealed = [stage_template, stage_meta, generator, parameters]
            self.write(stage, "STAGE_CONTENT.sha256", "".join(
                f"{FREEZE.file_sha(path)}  {path.relative_to(stage).as_posix()}\n" for path in sealed))

            topology = {"coordinator": {"host": "coord", "ip": "10.0.0.1"},
                        "workers": [{"index": 1, "host": "worker", "ip": "10.0.0.2", "port": 8001}],
                        "worker_sweep": [1]}
            base_topology = root / "base-topology.json"
            ml10_topology = root / "ml10-topology.json"
            base_topology.write_text(json.dumps(topology))
            ml10_topology.write_text(json.dumps(topology))

            def candidate(candidate_id, discovery, kind, placeholder):
                source = base_path if kind == "base-campaign" else ml10_path
                return {"candidateId": candidate_id, "placeholderCellId": placeholder,
                        "discoveryId": discovery, "kind": kind, "workers": 1,
                        "networkProfile": "lan", "sourceFiles": {
                            source.relative_to(evaluation).as_posix(): FREEZE.file_sha(source)}}

            unresolved = root / "unresolved.json"
            unresolved.write_text(json.dumps({"schema": "current-pe-corpus-unresolved-v1",
                "records": [candidate("base-ready", "base:p2:P2_PREP", "base-campaign", "p1"),
                            candidate("base-blocked", "base:sliceline:sliceline-kdd98",
                                      "base-campaign", "p2"),
                            candidate("ml10-ready", "ml10:pca", "ml10-campaign", "p3")]}))

            result = FREEZE.build(unresolved, catalog, evaluation, base_topology,
                                  ml10_topology, stage)
            self.assertEqual(result["counts"], {
                "campaignCandidates": 3, "readyForNativeCapture": 2, "blocked": 1,
                "byKind": {
                    "base-campaign": {"candidates": 2, "readyForNativeCapture": 1, "blocked": 1},
                    "ml10-campaign": {"candidates": 1, "readyForNativeCapture": 1, "blocked": 0}}})
            self.assertEqual(result["blocked"][0]["reasons"],
                             ["FROZEN_PLANNING_INPUT_CONTRACT_ABSENT"])
            for row in result["conditions"]:
                program = row["compileModelInput"]["program"]
                self.assertIn("10.0.0.2:8001/data/", program["text"])
                self.assertEqual(hashlib.sha256(program["text"].encode()).hexdigest(),
                                 program["sha256"])
                self.assertNotRegex(program["text"], r"__[A-Z0-9_]+__")
            base = next(row for row in result["conditions"]
                        if row["kind"] == "base-campaign")
            self.assertEqual([FREEZE.P2_METADATA_RELEASE_OPTION],
                             base["compileModelInput"]["workloadJvmOptions"])
            FREEZE.publish(output, FREEZE.render(result), False)
            self.assertEqual(json.loads(output.read_text())["counts"]["blocked"], 1)

            stage_meta.write_text(json.dumps(self.metadata(rows=19, cols=4)))
            with self.assertRaisesRegex(ValueError, "digest differs"):
                FREEZE.build(unresolved, catalog, evaluation, base_topology,
                             ml10_topology, stage)


if __name__ == "__main__":
    unittest.main()
