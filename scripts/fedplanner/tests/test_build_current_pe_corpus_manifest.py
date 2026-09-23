import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build_current_pe_corpus_manifest.py"
SPEC = importlib.util.spec_from_file_location("build_current_pe_corpus_manifest", SCRIPT)
CORPUS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CORPUS)


class CurrentPeCorpusManifestTest(unittest.TestCase):
    def test_frozen_cohort_and_unresolved_contract_are_kept_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "driver/run_multihost_campaign_network_quality_v2.py":
                    'WORKLOADS_BY_SUITE = {"ml": ("lm",)}\nPROFILES = ("lan",)\nWORKERS = (1,)\n',
                "campaign/run_ml10_campaign.py":
                    'WORKLOADS = ("lm",)\nPROFILES = ("lan",)\nWORKERS = (1,)\n',
                "microbench/generate.py":
                    'SCALES = (1,)\nFAMILIES = ("linear", "reuse_update")\n'
                    'REUSE_VARIANTS = ("reuse", "update")\n',
                "planning/program.dml": "X = read($X);\n",
            }
            for name, content in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)

            planned = {"workers": 1, "case": {"workers": 1}, "network": {"rtt_ms": 1}}
            condition = {"discoveryId": "planning-w1:lm", "conditionId": "lan", **planned}
            current_pair = [{"applicability": "REQUIRED", "left": "P_C0",
                             "right": "E_C0", "role": "CURRENT_PE"}]
            frozen = {"id": "frozen", "discoveryId": "planning-w1:lm",
                      "inventoryStatus": "IN_SCOPE", "conditionId": "lan",
                      "expectedPairs": current_pair,
                      "sourceFiles": {"planning/program.dml": hashlib.sha256(
                          (root / "planning/program.dml").read_bytes()).hexdigest()},
                      "sourceBinding": {"conditionStatus": "SNAPSHOT_ONLY",
                                        "kind": "planning-snapshot",
                                        "discoveryId": "planning-w1:lm",
                                        "conditionSha256": CORPUS.digest(condition),
                                        "plannedCondition": planned}}

            def placeholder(cell, discovery, kind, source):
                return {"id": cell, "discoveryId": discovery, "inventoryStatus": "IN_SCOPE",
                        "conditionId": "unresolved", "expectedPairs": current_pair,
                        "sourceFiles": {source: hashlib.sha256((root / source).read_bytes()).hexdigest()},
                        "sourceBinding": {"conditionStatus": "UNRESOLVED", "kind": kind,
                                          "discoveryId": discovery}}

            rows = [frozen,
                    placeholder("base", "base:ml:lm", "base-campaign",
                                "driver/run_multihost_campaign_network_quality_v2.py"),
                    placeholder("ml10", "ml10:lm", "ml10-campaign",
                                "campaign/run_ml10_campaign.py")]
            for number, discovery in enumerate(("microbench:linear-k1",
                                                 "microbench:reuse_update-reuse-k1",
                                                 "microbench:reuse_update-update-k1")):
                rows.append(placeholder(f"micro{number}", discovery, "generated-microbench",
                                        "microbench/generate.py"))
            rows.append({"id": "unsupported", "discoveryId": "optional:x",
                         "inventoryStatus": "UNSUPPORTED", "conditionId": None,
                         "inventoryReason": "entrypoint is not connected", "expectedPairs": [],
                         "sourceFiles": {}, "sourceBinding": None})
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": rows}))

            manifest, unresolved = CORPUS.build(catalog, root)
            self.assertEqual((manifest["status"], manifest["scope"]),
                             ("INCOMPLETE", "PLANNING_COHORT_1"))
            self.assertEqual(manifest["counts"]["readyCells"], 1)
            self.assertEqual(manifest["counts"]["unresolvedCandidates"], 5)
            self.assertEqual(len({row["candidateId"] for row in manifest["unresolved"]}), 5)
            self.assertTrue(manifest["cells"][0]["inputValidation"]["sourceDigestsVerified"])
            micro = next(row for row in unresolved["records"]
                         if row["kind"] == "generated-microbench")
            self.assertIn("WORKER_COUNT_NOT_BOUND", micro["reasons"])
            self.assertEqual({row["decision"] for row in manifest["registryCoverage"]},
                             {"READY", "UNRESOLVED", "OUT_OF_SCOPE"})

            resolved_rows = json.loads(json.dumps(rows))
            for row in resolved_rows:
                binding = row.get("sourceBinding")
                if not binding or binding.get("conditionStatus") != "UNRESOLVED":
                    continue
                resolved_condition = {"workers": 1, "case": {"workers": 1},
                                      "network": {"rtt_ms": 1}}
                binding["conditionStatus"] = "SNAPSHOT_ONLY"
                binding["plannedCondition"] = resolved_condition
                binding["conditionSha256"] = CORPUS.digest({
                    "discoveryId": row["discoveryId"], "conditionId": row["conditionId"],
                    **resolved_condition})
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1",
                                           "cells": resolved_rows}))
            complete, complete_unresolved = CORPUS.build(catalog, root)
            self.assertEqual((complete["status"], complete["scope"]),
                             ("COMPLETE", "FULL_CURRENT"))
            self.assertEqual(complete_unresolved["records"], [])
            self.assertTrue(complete["fullCurrentRequirements"][
                "cellsExactlyMatchInScopeCatalogIds"])

            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": rows}))
            (root / "planning/program.dml").write_text("changed\n")
            with self.assertRaisesRegex(ValueError, "digest differs"):
                CORPUS.build(catalog, root)

    def test_missing_out_of_scope_reason_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name, content in {
                "driver/run_multihost_campaign_network_quality_v2.py":
                    'WORKLOADS_BY_SUITE = {"ml": ("lm",)}\nPROFILES = ("lan",)\nWORKERS = (1,)\n',
                "campaign/run_ml10_campaign.py":
                    'WORKLOADS = ("lm",)\nPROFILES = ("lan",)\nWORKERS = (1,)\n',
                "microbench/generate.py":
                    'SCALES = (1,)\nFAMILIES = ("linear", "reuse_update")\n'
                    'REUSE_VARIANTS = ("reuse", "update")\n',
            }.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": [{
                "id": "bad", "discoveryId": "optional:x", "inventoryStatus": "UNSUPPORTED",
                "conditionId": None, "expectedPairs": [], "sourceFiles": {}, "sourceBinding": None}]}))
            with self.assertRaisesRegex(ValueError, "lacks an explicit reason"):
                CORPUS.build(catalog, root)


if __name__ == "__main__":
    unittest.main()
