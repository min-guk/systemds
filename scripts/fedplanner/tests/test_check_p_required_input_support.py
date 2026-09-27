import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_p_required_input_support import (check, evaluate_verified_domain)
from interpret_p_acceptance_slice import _load_verified_v2_artifact
from verify_p_model_artifact import canonical


ROOT = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921")
P2 = ROOT / ("current-pe-campaign-v12-ledger-aware/p-models/"
             "cell_00d1aa1ca27bce14d826")


def fixture():
    semantics = [
        {"coordinateIndex": 0, "candidateIndex": 0, "owner": "A",
         "reference": "ref-A", "placement": "CP",
         "requiredInputSupport": ["ref-B-1"]},
        {"coordinateIndex": 1, "candidateIndex": 0, "owner": "B",
         "reference": "ref-B-1", "placement": "CP",
         "requiredInputSupport": []},
        {"coordinateIndex": 1, "candidateIndex": 1, "owner": "B",
         "reference": "ref-B-2", "placement": "CP",
         "requiredInputSupport": []},
    ]
    references = [
        {"reference": "ref-A", "owner": "A", "rule": "A|inputs=[]",
         "placement": "CP"},
        {"reference": "ref-B-1", "owner": "B", "rule": "B|inputs=[]",
         "placement": "CP"},
        {"reference": "ref-B-2", "owner": "B", "rule": "B|inputs=[]",
         "placement": "CP"},
    ]
    return {
        "placementDomains": [["A", ["CP", "FED"]],
                             ["B", ["CP", "FED"]]],
        "candidateDomains": [["A", ["a"]], ["B", ["b1", "b2"]]],
        "candidateReceiptSemanticFacts": semantics,
        "candidateRealizationReferenceFacts": references,
        "candidateRealizationClauseInventory": [
            {"reference": row["reference"]} for row in references],
    }


class RequiredInputSupportTest(unittest.TestCase):
    def test_selected_exact_reference_and_different_realization(self):
        domain = fixture()
        passed = evaluate_verified_domain(domain, [0, 0], [1, 1])
        failed = evaluate_verified_domain(domain, [0, 0], [1, 2])
        self.assertEqual("PASS", passed["verdict"])
        self.assertEqual("CALLER_ASSERTED_DOMAIN_SINGLE_LEAF_ONLY",
                         passed["claimScope"])
        self.assertEqual(1, passed["checkedSupportReferences"])
        self.assertEqual("FAIL", failed["verdict"])
        self.assertEqual("SELECTED_SUPPORT_REFERENCE_DIFFERS", failed["reason"])
        self.assertIsNone(failed["javaExceptionClass"])
        self.assertFalse(failed["completePAcceptance"])

    def test_unselected_owner_existential_and_placement_filter(self):
        domain = fixture()
        self.assertEqual("PASS", evaluate_verified_domain(
            domain, [0, 0], [1, 0])["verdict"])
        mismatch = evaluate_verified_domain(domain, [0, 1], [1, 0])
        self.assertEqual("FAIL", mismatch["verdict"])
        self.assertEqual("UNSELECTED_SUPPORT_PLACEMENT_DIFFERS",
                         mismatch["reason"])
        domain["placementDomains"].pop()
        domain["candidateDomains"].pop()
        domain["candidateReceiptSemanticFacts"] = domain[
            "candidateReceiptSemanticFacts"][:1]
        self.assertEqual("PASS", evaluate_verified_domain(
            domain, [0], [1])["verdict"])

    def test_selected_same_realization_with_different_support_clause(self):
        domain = fixture()
        domain["candidateDomains"][1][1].append("b3")
        extra = copy.deepcopy(domain["candidateReceiptSemanticFacts"][1])
        extra["candidateIndex"] = 2
        extra["requiredInputSupport"] = ["ref-A"]
        domain["candidateReceiptSemanticFacts"].append(extra)
        result = evaluate_verified_domain(domain, [0, 0], [1, 3])
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(2, result["checkedSupportReferences"])

    def test_missing_reference_and_foreign_selection_fail_closed(self):
        domain = fixture()
        domain["candidateRealizationReferenceFacts"].pop()
        with self.assertRaisesRegex(ValueError, "AVAILABLE realization inventory"):
            evaluate_verified_domain(domain, [0, 0], [1, 0])
        domain = fixture()
        domain["candidateReceiptSemanticFacts"][0]["owner"] = "forged"
        with self.assertRaisesRegex(ValueError, "selected candidate semantic"):
            evaluate_verified_domain(domain, [0, 0], [1, 0])
        domain = fixture()
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            domain, [1, 0], [1, 0])["verdict"])

    def test_rule_parent_must_match_reference_owner(self):
        domain = fixture()
        domain["candidateRealizationReferenceFacts"][1]["rule"] = \
            "foreign|inputs=[]"
        with self.assertRaisesRegex(ValueError, "rule owner differs"):
            evaluate_verified_domain(domain, [0, 0], [1, 0])

    def test_producer_verdict_is_not_an_input_and_budgets_bound_work(self):
        domain = fixture()
        before = evaluate_verified_domain(domain, [0, 0], [1, 0])
        for semantic in domain["candidateReceiptSemanticFacts"]:
            semantic["candidateFeasibilityVerdict"] = "PRODUCER_FORGED"
            semantic["interpretation"] = "PRODUCER_FORGED"
            semantic["unsupportedReasons"] = ["PRODUCER_FORGED"]
        self.assertEqual(before, evaluate_verified_domain(domain, [0, 0], [1, 0]))
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            domain, [0, 0], [1, 0], max_candidate_rows=1)["verdict"])
        budget_domain = copy.deepcopy(domain)
        budget_domain["candidateReceiptSemanticFacts"][0][
            "requiredInputSupport"].append("ref-B-2")
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            budget_domain, [0, 0], [1, 0],
            max_support_references=1)["verdict"])
        with self.assertRaisesRegex(ValueError, "index outside domain"):
            evaluate_verified_domain(domain, [0, 0], [1, 3])

    def test_public_check_requires_verified_v2_authority(self):
        domain = fixture()
        with patch("check_p_required_input_support._load_verified_v2_artifact",
                   return_value=({"cell": "tiny", "status": "STRUCTURE_VERIFIED"},
                                 domain, True)) as load:
            result = check("/tmp/tiny", "a" * 64, [0, 0], [1, 0])
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual("tiny", result["cell"])
        self.assertEqual("VERIFIED_P_V2_MODEL_BYTES", result["evidenceAuthority"])
        self.assertEqual("VERIFIED_P_V2_SINGLE_ACCEPTANCE_LEAF_ONLY",
                         result["claimScope"])
        self.assertEqual("UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
                         result["executionProvenance"])
        load.assert_called_once()

    @unittest.skipUnless((P2 / "p-model.json.gz").is_file(),
                         "frozen P2 model unavailable")
    def test_real_p2_verified_artifact_vacuous_leaf_smoke(self):
        receipt = json.loads((P2 / "receipt.json").read_text())
        with __import__("gzip").open(P2 / "p-model.json.gz", "rt") as stream:
            domain = json.load(stream)["nativeDomain"]
        placements = [0] * len(domain["placementDomains"])
        candidates = [0] * len(domain["candidateDomains"])
        result = check(P2 / "p-model.json.gz", receipt["artifactSha256"],
                       placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(0, result["requiredSupportCount"])
        self.assertFalse(result["completePAcceptance"])

    @unittest.skipUnless((P2 / "p-model.json.gz").is_file(),
                         "frozen P2 model unavailable")
    def test_real_p2_unselected_owner_support_leaf(self):
        receipt = json.loads((P2 / "receipt.json").read_text())
        with __import__("gzip").open(P2 / "p-model.json.gz", "rt") as stream:
            domain = json.load(stream)["nativeDomain"]
        placements = [0] * len(domain["placementDomains"])
        candidates = [0] * len(domain["candidateDomains"])
        # This verified P2 receipt has one required source realization at
        # coordinate 5.  Its source owner at coordinate 9 is unselected but
        # has the matching singleton FED/FOUT/ROW placement.
        candidates[5] = 1
        result = check(P2 / "p-model.json.gz", receipt["artifactSha256"],
                       placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, result["requiredSupportCount"])
        self.assertEqual(1, result["checkedSupportReferences"])
        self.assertEqual(1, result["selectedReceiptCount"])

    @unittest.skipUnless((P2 / "p-model.json.gz").is_file(),
                         "frozen P2 model unavailable")
    def test_real_p2_model_sha_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            check(P2 / "p-model.json.gz", "0" * 64, [0] * 144, [0] * 144)

    @unittest.skipUnless((P2 / "p-model.json.gz").is_file(),
                         "frozen P2 model unavailable")
    def test_verified_model_with_reparented_rule_fails_leaf(self):
        with gzip.open(P2 / "p-model.json.gz", "rt") as stream:
            artifact = json.load(stream)
        domain = artifact["nativeDomain"]
        consumer = next(row for row in domain["candidateReceiptSemanticFacts"]
                        if row["coordinateIndex"] == 5 and
                        row["candidateIndex"] == 0)
        support = consumer["requiredInputSupport"][0]
        source = next(row for row in domain["candidateRealizationReferenceFacts"]
                      if row["reference"] == support)
        old_rule = source["rule"]
        marker = old_rule.rfind("org.apache.sysds.")
        self.assertGreater(marker, 0)
        new_rule = old_rule[:marker] + "x" + old_rule[marker + 1:]
        old_hash = hashlib.sha256(old_rule.encode()).hexdigest()
        new_hash = hashlib.sha256(new_rule.encode()).hexdigest()

        def replace(value):
            if isinstance(value, str):
                return value.replace(old_rule, new_rule).replace(old_hash, new_hash)
            if isinstance(value, list):
                return [replace(item) for item in value]
            if isinstance(value, dict):
                return {key: replace(item) for key, item in value.items()}
            return value

        domain = replace(domain)
        artifact["nativeDomain"] = domain
        for activation in domain["candidateReceiptActivationFacts"]:
            ci, ai = activation["coordinateIndex"], activation["candidateIndex"]
            receipt = domain["candidateDomains"][ci][1][ai]
            activation["receiptSha256"] = hashlib.sha256(receipt.encode()).hexdigest()
        artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
            canonical(domain)).hexdigest()
        plain = json.dumps(artifact, separators=(",", ":"),
                           ensure_ascii=False).encode()
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            path = Path(temporary) / "p-model.json.gz"
            with gzip.open(path, "wb") as stream:
                stream.write(plain)
            model_sha = hashlib.sha256(plain).hexdigest()
            structure, _, complete = _load_verified_v2_artifact(path, model_sha)
            self.assertEqual("STRUCTURE_VERIFIED", structure["status"])
            self.assertTrue(complete)
            placements = [0] * len(domain["placementDomains"])
            candidates = [0] * len(domain["candidateDomains"])
            candidates[5] = 1
            with self.assertRaisesRegex(ValueError, "rule owner differs"):
                check(path, model_sha, placements, candidates)


if __name__ == "__main__":
    unittest.main()
