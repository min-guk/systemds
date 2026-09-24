"""Tests for the v3 template-resolution scope adapter."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "audit_current_scope_template_resolution.py"
SPEC = importlib.util.spec_from_file_location("scope_template_resolution", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)
CLOSURE = MOD.load_module("scope_template_test_closure", MOD.CLOSURE_PRODUCER)


class ScopeTemplateResolutionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.receipt_path = Path(cls.directory.name) / "receipt.json"
        cls.receipt = CLOSURE.build()
        cls.receipt_path.write_text(CLOSURE.render(cls.receipt))
        cls.ledger = MOD.build(cls.receipt_path)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_resolves_13_and_preserves_149_unresolved(self):
        self.assertEqual("current-scope-applicability-audit-v5", self.ledger["schema"])
        self.assertEqual("INCOMPLETE", self.ledger["status"])
        self.assertEqual({
            "ACTIVE_EXACT_PATH_FROZEN_TEMPLATE": 13,
            "ACTIVE_IMPORTED_FUNCTION_LIBRARY": 2,
            "UNRESOLVED": 149,
        }, self.ledger["counts"]["resolution"])
        self.assertEqual(164, self.ledger["counts"]["records"])
        self.assertEqual(149, self.ledger["counts"]["remainingUnresolved"])

    def test_harness_aliases_are_evidence_only(self):
        records = {row["discoveryId"]: row for row in self.ledger["records"]}
        for discovery in CLOSURE.HARNESS_ALIAS_DISCOVERIES:
            self.assertEqual("UNRESOLVED", records[discovery]["resolution"])
            self.assertEqual("UNRESOLVED",
                             records[discovery]["templateClosureCandidateEvidence"]
                             ["resolution"])

    def test_receipt_tampering_fails_independent_regeneration(self):
        receipt = json.loads(self.receipt_path.read_text())
        receipt["registrations"][0]["successorCellIds"] = []
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_text(CLOSURE.render(receipt))
            with self.assertRaisesRegex(ValueError, "independent regeneration"):
                MOD.build(path)

    def test_producer_and_parent_hashes_are_bound(self):
        inputs = self.ledger["inputs"]
        self.assertEqual(MOD.sha256(MOD.HERE), inputs["producerScript"]["sha256"])
        self.assertEqual(MOD.sha256(CLOSURE.DEFAULT_PARENT),
                         inputs["parentV2Ledger"]["sha256"])
        self.assertEqual(MOD.sha256(MOD.CLOSURE_PRODUCER),
                         inputs["templateClosureReceipt"]["producerScriptSha256"])

    def test_publish_check_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ledger.json"
            content = MOD.render(self.ledger)
            MOD.publish(output, content, False)
            MOD.publish(output, content, True)
            output.write_text(content + " ")
            with self.assertRaisesRegex(SystemExit, "ledger changed"):
                MOD.publish(output, content, True)


if __name__ == "__main__":
    unittest.main()
