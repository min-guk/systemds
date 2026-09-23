import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "promote_frozen_capture_conditions.py"
SPEC = importlib.util.spec_from_file_location("promote_frozen_capture_conditions", SCRIPT)
PROMOTE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROMOTE)


class FrozenCapturePromotionTest(unittest.TestCase):
    def test_promotes_campaign_and_micro_conditions_with_exact_bindings(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evaluation = root / "evaluation"
            evaluation.mkdir()
            producer = evaluation / "microbench/generate.py"
            producer.parent.mkdir()
            producer.write_text("# generator\n")
            runner = evaluation / "microbench/run.py"
            runner.write_text("# runner\n")
            base = evaluation / "base.txt"
            base.write_text("base\n")
            catalog_path = root / "catalog.json"
            catalog_path.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": [
                {"id": "campaign-cell", "discoveryId": "base:p2:P2_PREP",
                 "inventoryStatus": "IN_SCOPE", "sourceFiles": {
                     "base.txt": PROMOTE.file_sha(base)}},
                {"id": "micro-cell", "discoveryId": "microbench:linear-k1",
                 "inventoryStatus": "IN_SCOPE", "sourceFiles": {
                     "microbench/generate.py": PROMOTE.file_sha(producer)}}]}))

            campaign_program = ('X = federated(addresses=list("w1/data/X"), '
                                'ranges=list(list(0,0),list(10,2)))\nprint(sum(X))\n')
            payload = {"workers": 1, "workerEndpoints": [{"index": 1}],
                       "networkProfile": "lan", "networkCost": {"SYSDS_FED_COST_NET_BW": "1"},
                       "privacyMode": "private-aggregate", "workload": "P2_PREP", "dataset": "P2",
                       "inputs": [{"addresses": ["w1/data/X"],
                                   "ranges": [[0, 0], [10, 2]], "partitioning": "ROW",
                                   "globalMetadata": {"privacy": "private-aggregate"}}],
                       "template": {}, "dependencies": [],
                       "program": {"text": campaign_program,
                                   "sha256": hashlib.sha256(campaign_program.encode()).hexdigest()},
                       "output": "out", "seedBindings": {},
                       "compilerArgv": ["-exec", "singlenode", "-seed", "7",
                                        "-noFedRuntimeConversion", "-stats", "100"],
                       "workloadJvmOptions": [PROMOTE.P2_METADATA_RELEASE_OPTION],
                       "runtimeDataExecutionAssessed": False}
            campaign_path = root / "campaign.json"
            campaign_path.write_text(json.dumps({
                "schema": "campaign-compile-condition-freeze-v1",
                "counts": {"readyForNativeCapture": 1}, "conditions": [{
                    "candidateId": "candidate-campaign",
                    "placeholderCellId": "campaign-cell", "discoveryId": "base:p2:P2_PREP",
                    "conditionId": "lan", "conditionSha256": PROMOTE.digest(payload),
                    "resolutionStatus": "READY_FOR_NATIVE_CAPTURE",
                    "compileModelInput": payload}]}))

            micro_root = root / "micro-programs"
            micro_root.mkdir()
            micro_program = micro_root / "linear-k1.dml"
            micro_program.write_text(
                "X = federated(addresses=list($X1), ranges=list(list(0,0),list(10,2)))\nprint(sum(X))\n")
            planned = {"workers": 1,
                       "network": {"cost_environment": {"SYSDS_FED_COST_NET_BW": "2"}},
                       "case": {"program": "programs/linear-k1.dml",
                                "programSha256": PROMOTE.file_sha(micro_program),
                                "arguments": {"X1": "worker1/data/X", "out": "/tmp/out"},
                                "plannerConfiguration": {"sysds.codegen.enabled": "false"},
                                "inputs": {"X": {"federationType": "ROW",
                                    "globalMetadata": {"privacy": "private-aggregate"},
                                    "parts": [{"argument": "X1"}]}}}}
            identity = {"conditionId": "micro", "discoveryId": "microbench:linear-k1", **planned}
            micro_path = root / "micro.json"
            micro_path.write_text(json.dumps({
                "schema": "microbench-condition-freeze-v1", "counts": {"conditions": 1},
                "conditions": [{"placeholderCellId": "micro-cell",
                    "candidateId": "candidate-micro",
                    "discoveryId": "microbench:linear-k1", "conditionId": "micro",
                    "conditionSha256": PROMOTE.digest(identity),
                    "status": "READY_FOR_COMPILE_MODEL_CAPTURE", "plannedCondition": planned,
                    "sourceFiles": {"microbench/generate.py": PROMOTE.file_sha(producer),
                                    "microbench/run.py": PROMOTE.file_sha(runner),
                                    "programs/linear-k1.dml": PROMOTE.file_sha(micro_program)}}]}))

            catalog, files = PROMOTE.build(catalog_path, evaluation, campaign_path,
                                           micro_path, micro_root)
            self.assertEqual(2, catalog["promotion"]["promotedConditions"])
            by_id = {row["promotedFromPlaceholderCellId"]: row for row in catalog["cells"]
                     if "promotedFromPlaceholderCellId" in row}
            self.assertEqual("campaign-compile-condition",
                             by_id["campaign-cell"]["sourceBinding"]["kind"])
            self.assertEqual(["-exec", "singlenode", "-seed", "7",
                              "-noFedRuntimeConversion", "-stats", "100"],
                             by_id["campaign-cell"]["sourceBinding"]["compilerArgv"])
            self.assertEqual([PROMOTE.P2_METADATA_RELEASE_OPTION],
                             by_id["campaign-cell"]["sourceBinding"]["workloadJvmOptions"])
            self.assertEqual([[0, 0], [10, 2]],
                             by_id["campaign-cell"]["sourceBinding"]["plannedCondition"]
                             ["case"]["federatedSources"][0]["ranges"])
            self.assertEqual(["-Dsysds.codegen.enabled=false"],
                             by_id["micro-cell"]["sourceBinding"]["workloadJvmOptions"])
            self.assertIn("base.txt", files)
            output, overlay = root / "augmented.json", root / "overlay"
            PROMOTE.publish(catalog, files, output, overlay)
            PROMOTE.publish(catalog, files, output, overlay, check=True)
            self.assertEqual(PROMOTE.file_sha(overlay / "base.txt"), PROMOTE.file_sha(base))
            (overlay / "base.txt").write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "artifact differs"):
                PROMOTE.publish(catalog, files, output, overlay, check=True)

    def test_campaign_promotion_rejects_missing_compiler_contract(self):
        payload = {"workers": 1, "program": {"text": "", "sha256": hashlib.sha256(b"").hexdigest()},
                   "inputs": [], "dependencies": [], "networkCost": {"x": "1"}}
        row = {"resolutionStatus": "READY_FOR_NATIVE_CAPTURE",
               "conditionSha256": PROMOTE.digest(payload), "compileModelInput": payload,
               "discoveryId": "base:ml:pca"}
        with self.assertRaisesRegex(ValueError, "compiler argv"):
            PROMOTE.campaign_cell(row, "cell", "artifact", {})

    def test_campaign_promotion_rejects_missing_p2_release_option(self):
        program = ('X = federated(addresses=list("w1/data/X"), '
                   'ranges=list(list(0,0),list(10,2)))\nprint(sum(X))\n')
        payload = {
            "workers": 1, "workload": "P2_PREP", "networkCost": {"x": "1"},
            "program": {"text": program,
                        "sha256": hashlib.sha256(program.encode()).hexdigest()},
            "inputs": [{"addresses": ["w1/data/X"], "ranges": [[0, 0], [10, 2]],
                        "partitioning": "ROW",
                        "globalMetadata": {"privacy": "private-aggregate"}}],
            "dependencies": [],
            "compilerArgv": ["-exec", "singlenode", "-seed", "7",
                             "-noFedRuntimeConversion", "-stats", "100"]}
        row = {"resolutionStatus": "READY_FOR_NATIVE_CAPTURE",
               "conditionSha256": PROMOTE.digest(payload), "compileModelInput": payload,
               "discoveryId": "base:p2:P2_PREP"}
        with self.assertRaisesRegex(ValueError, "lacks the frozen metadata release option"):
            PROMOTE.campaign_cell(row, "cell", "artifact", {})


if __name__ == "__main__":
    unittest.main()
