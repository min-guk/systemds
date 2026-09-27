"""Tests for the isolated v2 current-scope library resolution adapter."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "audit_current_scope_library_resolution.py"
SPEC = importlib.util.spec_from_file_location("scope_library_resolution", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class CurrentScopeLibraryResolutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = MOD.build()

    def test_resolves_exactly_two_rows_and_keeps_optional_unresolved(self):
        self.assertEqual("current-scope-applicability-audit-v2", self.ledger["schema"])
        self.assertEqual({"ACTIVE_IMPORTED_FUNCTION_LIBRARY": 2, "UNRESOLVED": 162},
                         self.ledger["counts"]["resolution"])
        resolved = [row for row in self.ledger["records"]
                    if row["resolution"] == MOD.RESOLUTION]
        self.assertEqual(set(MOD.RESOLVED_DISCOVERIES),
                         {row["discoveryId"] for row in resolved})
        optional = next(row for row in self.ledger["records"]
                        if row["discoveryId"] == MOD.OPTIONAL_GMM_DISCOVERY)
        self.assertEqual("UNRESOLVED", optional["resolution"])
        self.assertNotIn("closureEvidence", optional)

    def test_v1_and_v2_producers_report_their_actual_source_hashes(self):
        inputs = self.ledger["inputs"]
        self.assertEqual(MOD.sha256(MOD.HERE), inputs["producerScript"]["sha256"])
        self.assertEqual(MOD.sha256(MOD.V1_PRODUCER),
                         inputs["parentV1ProducerScript"]["sha256"])
        self.assertNotEqual(inputs["producerScript"]["sha256"],
                            inputs["parentV1ProducerScript"]["sha256"])

    def test_receipt_tampering_fails_independent_regeneration(self):
        receipt = json.loads(MOD.DEFAULT_RECEIPT.read_text())
        receipt["recordsSha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
            with self.assertRaisesRegex(ValueError, "independent regeneration"):
                MOD.build(path)

    def test_optional_alias_promotion_tamper_is_rejected(self):
        receipt = json.loads(MOD.DEFAULT_RECEIPT.read_text())
        receipt["candidateAliasEvidence"][0]["resolution"] = MOD.RESOLUTION
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
            with self.assertRaisesRegex(ValueError, "independent regeneration"):
                MOD.build(path)

    def test_published_ledger_round_trips_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ledger.json"
            content = MOD.render(self.ledger)
            v1 = MOD.load_module("scope_v1_publish", MOD.V1_PRODUCER)
            v1.publish(output, content, False)
            v1.publish(output, content, True)


if __name__ == "__main__":
    unittest.main()
