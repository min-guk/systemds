import copy
import gzip
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_p_transient_relation_support import check, evaluate_verified_domain


ROOT = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921")
LM = ROOT / ("current-pe-campaign-v12-ledger-aware/p-models/"
             "cell_0b7e2829a1e84335c6ae")


def fixture():
    references = [
        {"reference": "ref-A-CP", "owner": "A", "rule": "A|inputs=[]",
         "placement": "CP"},
        {"reference": "ref-A-FED", "owner": "A", "rule": "A|inputs=[]",
         "placement": "FED"},
        {"reference": "ref-R-CP", "owner": "R", "rule": "R|inputs=[]",
         "placement": "CP"},
        {"reference": "ref-R-FED", "owner": "R", "rule": "R|inputs=[]",
         "placement": "FED"},
    ]
    return {
        "placementDomains": [["A", ["CP", "FED"]], ["R", ["CP", "FED"]]],
        "candidateDomains": [["A", ["a-cp", "a-fed"]],
                             ["R", ["r-cp", "r-fed"]]],
        "candidateReceiptSemanticFacts": [
            {"coordinateIndex": 0, "candidateIndex": 0, "owner": "A",
             "reference": "ref-A-CP", "placement": "CP"},
            {"coordinateIndex": 0, "candidateIndex": 1, "owner": "A",
             "reference": "ref-A-FED", "placement": "FED"},
            {"coordinateIndex": 1, "candidateIndex": 0, "owner": "R",
             "reference": "ref-R-CP", "placement": "CP"},
            {"coordinateIndex": 1, "candidateIndex": 1, "owner": "R",
             "reference": "ref-R-FED", "placement": "FED"},
        ],
        "candidateRealizationReferenceFacts": references,
        "candidateRealizationClauseInventory": [
            {"reference": row["reference"]} for row in references],
        "logicalCandidateReachability": {
            "transient": [{
                "source": "A", "target": "R", "inputPosition": 0,
                "compatibility": [
                    {"sourceRealization": "ref-A-CP",
                     "readerRealization": "ref-R-CP"},
                    {"sourceRealization": "ref-A-FED",
                     "readerRealization": "ref-R-FED"},
                ],
            }],
        },
    }


class TransientRelationSupportTest(unittest.TestCase):
    def test_selected_pair_passes_and_incompatible_selected_pair_fails(self):
        domain = fixture()
        passed = evaluate_verified_domain(domain, [0, 0], [1, 1])
        failed = evaluate_verified_domain(domain, [0, 1], [1, 2])
        self.assertEqual("PASS", passed["verdict"])
        self.assertEqual(1, passed["relationCount"])
        self.assertEqual(2, passed["storedEdgeCount"])
        self.assertEqual("FAIL", failed["verdict"])
        self.assertEqual("STORED_TRANSIENT_RELATION_UNSUPPORTED", failed["reason"])
        self.assertFalse(failed["completePAcceptance"])
        self.assertIsNone(failed["javaExceptionClass"])

    def test_unselected_references_are_existential_per_relation(self):
        domain = fixture()
        self.assertEqual("PASS", evaluate_verified_domain(
            domain, [0, 0], [0, 0])["verdict"])
        self.assertEqual("PASS", evaluate_verified_domain(
            domain, [1, 1], [0, 0])["verdict"])
        self.assertEqual("FAIL", evaluate_verified_domain(
            domain, [0, 1], [0, 0])["verdict"])

    def test_required_input_support_does_not_affect_this_leaf(self):
        domain = fixture()
        domain["candidateReceiptSemanticFacts"][0]["requiredInputSupport"] = [
            "definitely-incompatible-with-selected-reader"]
        result = evaluate_verified_domain(domain, [0, 0], [1, 1])
        self.assertEqual("PASS", result["verdict"])
        self.assertIn("ALL_OTHER_CANDIDATE_AND_RELOCATION_PREDICATES",
                      result["unassessed"])

    def test_vacuous_empty_relation_passes(self):
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"] = []
        result = evaluate_verified_domain(domain, [0, 0], [0, 0])
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(0, result["relationCount"])

    def test_malformed_relation_and_reference_authority_fail_closed(self):
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"] = []
        with self.assertRaisesRegex(ValueError, "relation malformed"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"][0]["sourceRealization"] = "ref-R-CP"
        with self.assertRaisesRegex(ValueError, "endpoint owner differs"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])
        domain = fixture()
        duplicate = copy.deepcopy(
            domain["logicalCandidateReachability"]["transient"][0])
        domain["logicalCandidateReachability"]["transient"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "slot duplicated"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "inputPosition"] = 1
        with self.assertRaisesRegex(ValueError, "relation malformed"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])

    def test_duplicate_projected_endpoint_pair_is_harmless(self):
        domain = fixture()
        first = domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"][0]
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"].insert(0, copy.deepcopy(first))
        result = evaluate_verified_domain(domain, [0, 0], [0, 0])
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(3, result["storedEdgeCount"])

    def test_all_edges_validated_even_after_first_pair_can_pass(self):
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"][1]["readerRealization"] = "missing"
        with self.assertRaisesRegex(ValueError, "edge reference absent"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"] = domain["logicalCandidateReachability"][
                "transient"][0]["compatibility"][:1]
        with self.assertRaisesRegex(ValueError, "reader realization universe"):
            evaluate_verified_domain(domain, [0, 0], [0, 0])

    def test_budgets_and_assignments_fail_closed(self):
        domain = fixture()
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            domain, [0, 0], [0, 0], max_candidate_rows=1)["verdict"])
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            domain, [0, 0], [0, 0], max_relations=1,
            max_relation_edges=1)["verdict"])
        two = copy.deepcopy(domain["logicalCandidateReachability"]["transient"][0])
        two["inputPosition"] = 1
        domain["logicalCandidateReachability"]["transient"].append(two)
        self.assertEqual("UNKNOWN", evaluate_verified_domain(
            domain, [0, 0], [0, 0], max_relations=1)["verdict"])
        with self.assertRaisesRegex(ValueError, "index outside domain"):
            evaluate_verified_domain(fixture(), [0, 0], [0, 3])

    def test_edge_budget_preflights_before_detailed_edge_validation(self):
        domain = fixture()
        domain["logicalCandidateReachability"]["transient"][0][
            "compatibility"][1] = {"malformed": "must-not-be-inspected"}
        result = evaluate_verified_domain(
            domain, [0, 0], [0, 0], max_relation_edges=1)
        self.assertEqual("UNKNOWN", result["verdict"])
        self.assertEqual("RELATION_EDGE_BUDGET_EXHAUSTED", result["reason"])
        self.assertEqual(2, result["storedEdgeCount"])
        self.assertEqual(0, result["checkedEdges"])

        with self.assertRaisesRegex(ValueError, "compatibility edge malformed"):
            evaluate_verified_domain(
                domain, [0, 0], [0, 0], max_relation_edges=2)

    def test_public_check_requires_verified_v2_authority(self):
        domain = fixture()
        with patch("check_p_transient_relation_support._load_verified_v2_artifact",
                   return_value=({"cell": "tiny", "status": "STRUCTURE_VERIFIED"},
                                 domain, True)) as load:
            result = check("/tmp/tiny", "a" * 64, [0, 0], [0, 0])
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual("tiny", result["cell"])
        self.assertEqual("VERIFIED_P_V2_MODEL_BYTES", result["evidenceAuthority"])
        self.assertEqual("VERIFIED_P_V2_STORED_RELATION_SINGLE_LEAF_ONLY",
                         result["claimScope"])
        load.assert_called_once()

    @unittest.skipUnless((LM / "p-model.json.gz").is_file(),
                         "frozen LM model unavailable")
    def test_real_lm_verified_artifact_nonvacuous_smoke(self):
        receipt = json.loads((LM / "receipt.json").read_text())
        with gzip.open(LM / "p-model.json.gz", "rt") as stream:
            domain = json.load(stream)["nativeDomain"]
        placements = [0] * len(domain["placementDomains"])
        candidates = [0] * len(domain["candidateDomains"])
        result = check(LM / "p-model.json.gz", receipt["artifactSha256"],
                       placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(20, result["relationCount"])
        self.assertEqual(20, result["storedEdgeCount"])
        self.assertGreater(result["checkedEdges"], 0)
        self.assertFalse(result["completePAcceptance"])

        # The Java predicate permits an unselected reference only at its own
        # placement.  Add a structurally valid alternative to one source
        # coordinate and move that owner away from the only stored edge.
        mutated = copy.deepcopy(domain)
        relation = mutated["logicalCandidateReachability"]["transient"][0]
        source_coordinate = next(
            index for index, row in enumerate(mutated["placementDomains"])
            if row[0] == relation["source"])
        mutated["placementDomains"][source_coordinate][1].append(
            "FED/FOUT/ROW/SHAPE_INDEPENDENT")
        placements[source_coordinate] = 1
        failed = evaluate_verified_domain(mutated, placements, candidates)
        self.assertEqual("FAIL", failed["verdict"])
        self.assertEqual(0, failed["witness"]["relationIndex"])


if __name__ == "__main__":
    unittest.main()
