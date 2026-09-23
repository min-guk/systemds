import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.fedplanner import verify_p_graph_relation as relation
from scripts.fedplanner.verify_p_graph_relation import (
    canonical, create_certificate, verify_certificate, predicate,
)
from scripts.fedplanner.verify_p_model_artifact import source_order


class GraphPlacementRelationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.model = Path(self.temp.name) / "model.json.gz"
        self.certificate = Path(self.temp.name) / "certificate.json.gz"
        graph = {key: [] for key in
                 ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
        self.domain = {
            "placementDomains": [
                ["a", ["CP/LOUT/-/SHAPE_INDEPENDENT", "FED/FOUT/ROW/SHAPE_INDEPENDENT"]],
                ["b", ["CP/LOUT/-/SHAPE_INDEPENDENT", "FED/FOUT/ROW/SHAPE_INDEPENDENT"]],
            ],
            "candidateDomains": [["a", []], ["b", []]], "relocationDomains": [],
            "radices": [2, 2, 1, 1], "nodes": [
                ["a", "OPERATION", "a", ["CP/LOUT/-/SHAPE_INDEPENDENT",
                                           "FED/FOUT/ROW/SHAPE_INDEPENDENT"], []],
                ["b", "OPERATION", "b", ["CP/LOUT/-/SHAPE_INDEPENDENT",
                                           "FED/FOUT/ROW/SHAPE_INDEPENDENT"], []]],
            "nonDecisionCandidateOwners": [],
            "derivedFoutActions": [],
            "derivedFoutOwnershipBindings": [],
            "constraints": [self.constraint("SAME_PLACEMENT")],
        }
        digest = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
        self.artifact = {
            "schema": "closed-native-model-artifact-v1",
            "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
            "preRewriteGraph": graph, "finalHopGraph": graph,
            "nativeDomain": self.domain,
            "summary": {"status": "COMPLETE", "cell": "fixture", "rawCount": "4",
                        "placementCoordinates": 2, "candidateCoordinates": 2,
                        "relocationCoordinates": 0, "preRewriteGraphSha256": digest,
                        "finalHopGraphSha256": digest},
        }
        self.digest = self.write_model()
        self.write_certificate(create_certificate(self.model, self.digest))

    @staticmethod
    def constraint(kind):
        return {"kind": kind, "left": "a", "right": "b", "inputPosition": 0,
                "evidence": "fixture", "signature": "fixture"}

    def write_model(self):
        self.artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
            canonical(self.domain)).hexdigest()
        raw = canonical(self.artifact)
        self.model.write_bytes(gzip.compress(raw, mtime=0))
        return hashlib.sha256(raw).hexdigest()

    def write_certificate(self, certificate):
        self.certificate.write_bytes(gzip.compress(canonical(certificate), mtime=0))

    def test_exact_fixture_and_independent_recheck(self):
        receipt = verify_certificate(self.model, self.digest, self.certificate)
        self.assertEqual("2", receipt["graphValidPlacementCount"])
        self.assertEqual("GRAPH_CONSTRAINT_PLACEMENT_ONLY_ACCEPTANCE_OPEN", receipt["scope"])
        self.assertEqual(
            ["DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS",
             "DERIVED_FOUT_GRAPH_OWNERSHIP",
             "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION"],
            receipt["acceptanceCoverage"]["assessedPredicates"])

    def test_corrupt_tuple(self):
        cert = create_certificate(self.model, self.digest)
        cert["components"][0]["tuples"][0] = [0, 1]
        self.write_certificate(cert)
        with self.assertRaisesRegex(ValueError, "tuple"):
            verify_certificate(self.model, self.digest, self.certificate)

    def test_missing_component(self):
        cert = create_certificate(self.model, self.digest)
        cert["components"].clear()
        self.write_certificate(cert)
        with self.assertRaisesRegex(ValueError, "component"):
            verify_certificate(self.model, self.digest, self.certificate)

    def test_missing_coordinate(self):
        cert = create_certificate(self.model, self.digest)
        cert["components"][0]["coordinates"] = [0]
        self.write_certificate(cert)
        with self.assertRaisesRegex(ValueError, "coordinates"):
            verify_certificate(self.model, self.digest, self.certificate)

    def test_missing_value(self):
        cert = create_certificate(self.model, self.digest)
        cert["components"][0]["tuples"].pop()
        self.write_certificate(cert)
        with self.assertRaisesRegex(ValueError, "tuple"):
            verify_certificate(self.model, self.digest, self.certificate)

    def test_stale_model_hash(self):
        self.domain["placementDomains"][0][1][1] = "FED/FOUT/COL/SHAPE_INDEPENDENT"
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "hash differs"):
            verify_certificate(self.model, changed, self.certificate)

    def test_removed_constraint_with_recomputed_model_digest_rejected(self):
        self.domain["constraints"].clear()
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "model hash differs"):
            verify_certificate(self.model, changed, self.certificate)

    def test_unknown_constraint_rejected(self):
        self.domain["constraints"][0]["kind"] = "UNKNOWN"
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "unsupported constraint kind"):
            create_certificate(self.model, changed)

    def test_malformed_forbid_pair_rejected(self):
        self.domain["constraints"][0]["kind"] = "CONJUNCTIVE"
        self.domain["constraints"][0]["evidence"] = "forbid-pair:missing-right"
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "invalid forbid-pair"):
            create_certificate(self.model, changed)

    def test_unknown_state_rejected(self):
        self.domain["placementDomains"][0][1][1] = "FED/FOUT/ALIEN/SHAPE_INDEPENDENT"
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "unsupported placement state"):
            create_certificate(self.model, changed)

    def test_nondecision_constraint_id_mutation(self):
        self.domain["nodes"].append(["outside", "FUNCTION_BODY_NON_EMITTED", "outside", [], []])
        self.domain["constraints"].append({"kind": "CONJUNCTIVE", "left": "outside",
                                           "right": "a", "inputPosition": 0,
                                           "evidence": "fixture", "signature": "outside-to-a"})
        changed = self.write_model()
        cert = create_certificate(self.model, changed)
        self.assertEqual(1, len(cert["nonDecisionConstraintIds"]))
        self.write_certificate(cert)
        verify_certificate(self.model, changed, self.certificate)
        cert["nonDecisionConstraintIds"].clear()
        self.write_certificate(cert)
        with self.assertRaisesRegex(ValueError, "nondecision"):
            verify_certificate(self.model, changed, self.certificate)

    def test_self_constraint_tests_one_digit(self):
        self.domain["constraints"][0]["right"] = "a"
        self.domain["constraints"][0]["kind"] = "CONJUNCTIVE"
        self.domain["constraints"][0]["evidence"] = (
            "forbid-pair:FED/FOUT/ROW/SHAPE_INDEPENDENT=>FED/FOUT/ROW/SHAPE_INDEPENDENT")
        changed = self.write_model()
        cert = create_certificate(self.model, changed)
        self.assertEqual("2", cert["graphValidPlacementCount"])
        self.write_certificate(cert)
        verify_certificate(self.model, changed, self.certificate)

    def test_java_neutral_graph_predicate_truth_table(self):
        # Expected outcomes follow NeutralPlacementGraph.constraintSatisfied;
        # these are fixed differential fixtures, independent of certificate creation.
        cp = "CP/LOUT/-/SHAPE_INDEPENDENT"
        cp_fout = "CP/FOUT/ROW/SHAPE_INDEPENDENT"
        fed_row = "FED/FOUT/ROW/SHAPE_INDEPENDENT"
        fed_col = "FED/FOUT/COL/SHAPE_INDEPENDENT"
        fed_lout = "FED/LOUT/ROW/SHAPE_INDEPENDENT"
        cases = [
            ("SAME_PLACEMENT", "", cp, cp, True),
            ("SAME_PLACEMENT", "", cp, cp_fout, False),
            ("SAME_VALUE_PLACEMENT", "", cp_fout, fed_row, True),
            ("SAME_VALUE_PLACEMENT", "", fed_row, fed_col, False),
            ("SAME_VALUE_PLACEMENT", "", cp, fed_lout, True),
            ("SAME_FTYPE", "", cp_fout, fed_row, True),
            ("SAME_FTYPE", "", fed_row, fed_col, False),
            ("CONJUNCTIVE", "", cp, fed_row, False),
            ("CONJUNCTIVE", "", cp_fout, fed_row, True),
            ("CONJUNCTIVE", "", fed_row, fed_col, False),
            ("CONJUNCTIVE", "", cp, fed_lout, True),
            ("CONJUNCTIVE", "forbid-pair:" + cp + "=>" + cp, cp, cp, False),
            ("CONJUNCTIVE", "forbid-pair:" + cp + "=>" + cp, cp, fed_row, True),
            ("CONJUNCTIVE", "forbid-pair:=>", cp, cp, True),
            ("DOMINATES", "", fed_row, fed_col, True),
            ("DISTINCT_CONTEXT", "", fed_row, fed_col, True),
            ("SAME_ORIGIN", "", fed_row, fed_col, True),
        ]
        for kind, evidence, left, right, expected in cases:
            with self.subTest(kind=kind, left=left, right=right):
                self.assertEqual(expected, predicate(kind, evidence, left, right))

    def test_node_domain_bijection_rejects_missing_or_modified_node(self):
        self.domain["nodes"][0][3].pop()
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "node and placement"):
            create_certificate(self.model, changed)

    def test_node_domain_bijection_rejects_extra_decision_node(self):
        self.domain["nodes"].append(["extra", "OPERATION", "extra",
                                     ["CP/LOUT/-/SHAPE_INDEPENDENT"], []])
        changed = self.write_model()
        with self.assertRaisesRegex(ValueError, "node and placement"):
            create_certificate(self.model, changed)

    def test_model_swap_after_structural_check_rejected(self):
        original_verify = relation.verify_model

        def swap_after_check(path, digest):
            original_verify(path, digest)
            self.domain["constraints"][0]["kind"] = "CONJUNCTIVE"
            self.write_model()

        with patch.object(relation, "verify_model", side_effect=swap_after_check):
            with self.assertRaisesRegex(ValueError, "digest differs after"):
                create_certificate(self.model, self.digest)


if __name__ == "__main__":
    unittest.main()
