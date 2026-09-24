"""Tests for the fail-closed current-scope applicability ledger."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "audit_current_scope_applicability.py"
SPEC = importlib.util.spec_from_file_location("scope_audit", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class CurrentScopeApplicabilityAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = MOD.build()

    def test_real_catalog_is_complete_and_fail_closed(self):
        self.assertEqual(164, self.ledger["counts"]["records"])
        self.assertEqual({"HISTORICAL": 16, "UNSUPPORTED": 148},
                         self.ledger["counts"]["priorStatus"])
        self.assertEqual(164, self.ledger["counts"]["resolution"]["UNRESOLVED"])
        self.assertEqual("INCOMPLETE", self.ledger["status"])
        supersession = self.ledger["frozenCohortSupersession"]
        self.assertEqual("COMPLETE", supersession["status"])
        self.assertEqual(59, supersession["placeholderCount"])
        self.assertEqual(388, supersession["successorCount"])
        self.assertEqual(612, supersession["frozenSnapshotCount"])
        self.assertEqual({"1": 28, "4": 8, "12": 10, "16": 13},
                         supersession["successorsPerPlaceholder"])

    def test_every_record_has_verified_or_explicitly_missing_source_evidence(self):
        for row in self.ledger["records"]:
            self.assertEqual("UNRESOLVED", row["resolution"])
            self.assertIsInstance(row["sourceEvidence"], list)
            for source in row["sourceEvidence"]:
                self.assertEqual(source["verified"],
                                 source["expectedSha256"] == source["actualSha256"])
            if row["archiveLocationVerified"]:
                self.assertEqual("ARCHIVE_REVISION", row["roleCandidate"])
                self.assertTrue(row["sourceEvidence"])
                self.assertTrue(all(source["verified"] for source in row["sourceEvidence"]))

    def test_published_ledger_round_trips_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ledger.json"
            content = MOD.render(self.ledger)
            MOD.publish(output, content, False)
            MOD.publish(output, content, True)
            self.assertEqual(self.ledger, json.loads(output.read_text()))

    def test_catalog_inventory_binding_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        catalog["discoveryInventorySha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "not bound"):
                MOD.build(path)

    def test_duplicate_cell_id_with_same_count_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        audited = [row for row in catalog["cells"]
                   if row.get("inventoryStatus") in ("UNSUPPORTED", "HISTORICAL")]
        audited[1]["id"] = audited[0]["id"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "cell IDs must be unique"):
                MOD.build(path)

    def test_duplicate_discovery_replacing_another_with_same_count_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        audited = [row for row in catalog["cells"]
                   if row.get("inventoryStatus") == "UNSUPPORTED"]
        audited[1]["discoveryId"] = audited[0]["discoveryId"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "discovery IDs must be unique"):
                MOD.build(path)

    def test_missing_discovery_replaced_by_unknown_with_same_count_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        audited = [row for row in catalog["cells"]
                   if row.get("inventoryStatus") == "UNSUPPORTED"]
        audited[0]["discoveryId"] = "unclassified:evaluation:not-in-inventory.dml"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "discovery set differs"):
                MOD.build(path)

    def test_duplicate_in_scope_cell_fails_supersession(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        rows = [row for row in catalog["cells"] if row["inventoryStatus"] == "IN_SCOPE"]
        rows[1]["id"] = rows[0]["id"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "in-scope denominator or cell identities"):
                MOD.build(path)

    def test_successor_source_digest_mutation_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        row = next(row for row in catalog["cells"]
                   if row["inventoryStatus"] == "IN_SCOPE" and
                   row["conditionId"] != "unresolved")
        name = next(iter(row["sourceFiles"]))
        row["sourceFiles"][name] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(catalog))
            with self.assertRaisesRegex(ValueError, "successor source changed"):
                MOD.build(path)


if __name__ == "__main__":
    unittest.main()
