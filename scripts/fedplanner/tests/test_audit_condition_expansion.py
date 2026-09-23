import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "audit_condition_expansion.py"
SPEC = importlib.util.spec_from_file_location("audit_condition_expansion", SCRIPT)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class ConditionExpansionAuditTest(unittest.TestCase):
    def test_registry_axes_expand_without_claiming_frozen_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sources = {
                "driver/run_multihost_campaign_network_quality_v2.py":
                    'WORKLOADS_BY_SUITE = {"ml": ("lm",), "sliceline": ("ADULT",)}\n'
                    'PROFILES = ("lan", "wan")\nWORKERS = (1, 3)\n',
                "campaign/run_ml10_campaign.py":
                    'WORKLOADS = ("glm",)\nPROFILES = ("lan", "wan")\nWORKERS = (1,)\n',
                "microbench/generate.py":
                    'SCALES = (1,)\nFAMILIES = ("linear", "reuse_update")\n'
                    'REUSE_VARIANTS = ("reuse", "update")\n',
            }
            for name, content in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            def row(cell, discovery, kind, status, source=None, profile="unresolved"):
                files = {} if source is None else {
                    source: hashlib.sha256((root / source).read_bytes()).hexdigest()}
                return {"id": cell, "discoveryId": discovery,
                        "inventoryStatus": "IN_SCOPE", "conditionId": profile,
                        "sourceFiles": files,
                        "sourceBinding": {"conditionStatus": status,
                                          "kind": kind, "discoveryId": discovery}}
            cells = [row("f1", "planning-w1:lm", "planning-snapshot", "SNAPSHOT_ONLY", profile="lan"),
                     row("f2", "planning-w1:lm", "planning-snapshot", "SNAPSHOT_ONLY", profile="wan"),
                     row("b1", "base:ml:lm", "base-campaign", "UNRESOLVED", next(iter(sources))),
                     row("b2", "base:sliceline:sliceline-adult", "base-campaign", "UNRESOLVED", next(iter(sources))),
                     row("m1", "ml10:glm", "ml10-campaign", "UNRESOLVED", "campaign/run_ml10_campaign.py"),
                     row("p1", "planning-w1:sliceline-kdd98", "planning-snapshot", "UNRESOLVED"),
                     *[row(f"u{i}", discovery, "generated-microbench", "UNRESOLVED", "microbench/generate.py")
                       for i, discovery in enumerate(("microbench:linear-k1",
                                                       "microbench:reuse_update-reuse-k1",
                                                       "microbench:reuse_update-update-k1"))]]
            catalog = root / "catalog.json"
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": cells}))
            result = AUDIT.build(catalog, root)
            self.assertEqual((result["frozenSnapshotRows"], result["unresolvedPlaceholders"],
                              result["unresolvedCandidates"],
                              result["candidateRowsBeforeDedupAndInputValidation"]),
                             (2, 7, 15, 17))
            self.assertEqual(result["claimScope"], "REGISTRY_AXIS_INVENTORY_ONLY")
            self.assertTrue(all(item["inputStatus"] == "UNRESOLVED"
                                for item in result["candidates"] if item["placeholder"] not in ("f1", "f2")))
            (root / "campaign/run_ml10_campaign.py").write_text("WORKLOADS = ()\n")
            with self.assertRaisesRegex(ValueError, "source missing or changed"):
                AUDIT.build(catalog, root)
            (root / "campaign/run_ml10_campaign.py").write_text(
                sources["campaign/run_ml10_campaign.py"])
            cells[2]["discoveryId"] = "base:ml:unknown"
            cells[2]["sourceBinding"]["discoveryId"] = "base:ml:unknown"
            catalog.write_text(json.dumps({"schema": "closed-comparison-cases-v1", "cells": cells}))
            with self.assertRaisesRegex(ValueError, "base workload absent from registry"):
                AUDIT.build(catalog, root)


if __name__ == "__main__":
    unittest.main()
