import importlib.util
import copy
import pathlib
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("compare_receipt_authority.py")
SPEC = importlib.util.spec_from_file_location("compare_receipt_authority", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def binding(source="X_PROTECTED"):
    return {"inputPosition": 0, "kind": "DIRECT", "sourceRule": source,
            "sourceOwner": source, "sourceRealization": source,
            "relocationAction": None}


def receipt(authority="CAPTURED_RULE", proofs=None, bindings=None):
    selected = [{
        "opcode": opcode, "occurrence": opcode, "physicalState": "FED/LOUT/ROW",
        "authority": authority, "inputAuthorities": [{"inputPosition": 0,
            "kind": "DIRECT_FOUT", "sourceDecision": "X_PROTECTED"}],
        "supportClause": {"proofDependencies": list(proofs or []),
                          "inputBindings": list(bindings if bindings is not None
                                                else [binding()])},
    } for opcode in ("q(wsloss)", "q(wcemm)")]
    candidate_support = [{"exactRule": opcode, "emission": opcode,
                          "realization": opcode, "support": opcode,
                          "proofKeys": list(proofs or []),
                          "inputBindings": list(bindings if bindings is not None
                                                else [binding()])}
                         for opcode in ("q(wsloss)", "q(wcemm)")]
    return {"selectedOccurrences": selected,
            "selectedCandidateSupport": candidate_support}


class ReceiptAuthorityComparisonTest(unittest.TestCase):
    def test_representation_change_preserves_exact_authority(self):
        result = MODULE.compare_receipts(
            receipt(), receipt("CANDIDATE_RULE_RELATION"),
            ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")
        self.assertEqual("PASSED", result["status"])

    def test_missing_support_binding_fails(self):
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "selected candidate inputBindings changed"):
            MODULE.compare_receipts(receipt(), receipt(bindings=[]),
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")

    def test_missing_proof_fails(self):
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "selected candidate proofKeys changed"):
            MODULE.compare_receipts(receipt(proofs=["privacy-proof"]), receipt(),
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")

    def test_different_exact_source_fails(self):
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "selected candidate inputBindings changed"):
            MODULE.compare_receipts(receipt(), receipt(bindings=[binding("OTHER")]),
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")

    def test_relocation_action_authority_loss_fails(self):
        baseline = receipt()
        for row in baseline["selectedOccurrences"]:
            row["inputAuthorities"][0]["relocationAction"] = "exact-action"
        candidate = copy.deepcopy(baseline)
        for row in candidate["selectedOccurrences"]:
            row["authority"] = "CANDIDATE_RULE_RELATION"
            row["inputAuthorities"][0]["relocationAction"] = None
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "exact input authority changed"):
            MODULE.compare_receipts(baseline, candidate,
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")

    def test_both_missing_input_authorities_fail_closed(self):
        baseline = receipt()
        candidate = receipt()
        for payload in (baseline, candidate):
            for row in payload["selectedOccurrences"]:
                del row["inputAuthorities"]
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "required field inputAuthorities is absent"):
            MODULE.compare_receipts(baseline, candidate,
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")

    def test_both_missing_candidate_proof_inventory_fail_closed(self):
        baseline = receipt()
        candidate = receipt()
        for payload in (baseline, candidate):
            for row in payload["selectedCandidateSupport"]:
                del row["proofKeys"]
        with self.assertRaisesRegex(MODULE.AuthorityRegression,
                                    "required field proofKeys is absent"):
            MODULE.compare_receipts(baseline, candidate,
                                    ("q(wsloss)", "q(wcemm)"), "X_PROTECTED")


if __name__ == "__main__":
    unittest.main()
