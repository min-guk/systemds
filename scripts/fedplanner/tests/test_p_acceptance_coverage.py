import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.fedplanner.verify_p_graph_relation import (
    canonical, create_certificate, verify_certificate,
)
from scripts.fedplanner.verify_p_model_artifact import (
    OPAQUE_ACCEPTANCE_PREDICATES, require_full_acceptance, source_order, verify,
)


class PAcceptanceCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.model = Path(self.temp.name) / "model.json.gz"
        self.certificate = Path(self.temp.name) / "relation.json.gz"
        graph = {key: [] for key in
                 ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
        graph_sha = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
        state = "CP/LOUT/-/SHAPE_INDEPENDENT"
        self.domain = {
            "nodes": [["a", "OPERATION", "a", [state], []],
                      ["outside", "FUNCTION_BODY_NON_EMITTED", "outside", [], []]],
            "placementDomains": [["a", [state]]],
            "candidateDomains": [["a", []]],
            "relocationDomains": [],
            "radices": [1, 1],
            "constraints": [
                {"kind": "SAME_PLACEMENT", "left": "a", "right": "a",
                 "inputPosition": 0, "evidence": "decision", "signature": "decision"},
                {"kind": "DOMINATES", "left": "outside", "right": "a",
                 "inputPosition": 0, "evidence": "structural", "signature": "structural"},
            ],
            "derivedFoutActions": [],
            "derivedFoutOwnershipBindings": [],
            "nonDecisionCandidateOwners": [],
        }
        artifact = {
            "schema": "closed-native-model-artifact-v1",
            "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
            "preRewriteGraph": graph,
            "finalHopGraph": graph,
            "nativeDomain": self.domain,
            "summary": {
                "status": "COMPLETE", "cell": "fixture", "rawCount": "1",
                "placementCoordinates": 1, "candidateCoordinates": 1,
                "relocationCoordinates": 0, "preRewriteGraphSha256": graph_sha,
                "finalHopGraphSha256": graph_sha,
                "nativeDomainSha256": hashlib.sha256(canonical(self.domain)).hexdigest(),
            },
        }
        raw = canonical(artifact)
        self.digest = hashlib.sha256(raw).hexdigest()
        self.model.write_bytes(gzip.compress(raw, mtime=0))

    def test_structure_receipt_lists_every_opaque_predicate(self):
        receipt = verify(self.model, self.digest)
        coverage = receipt["acceptanceCoverage"]
        self.assertFalse(coverage["complete"])
        self.assertEqual(["DERIVED_FOUT_GRAPH_OWNERSHIP",
                          "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION"],
                         coverage["assessedPredicates"])
        self.assertEqual([predicate for predicate in OPAQUE_ACCEPTANCE_PREDICATES
                          if predicate not in {"DERIVED_FOUT_GRAPH_OWNERSHIP",
                                               "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION"}],
                         coverage["opaquePredicates"])
        self.assertEqual(1, coverage["decisionGraphConstraints"])
        self.assertEqual(1, coverage["nonDecisionGraphConstraints"])
        with self.assertRaisesRegex(ValueError, "full acceptance is unavailable"):
            require_full_acceptance(receipt)

    def test_graph_receipt_assesses_only_decision_endpoint_constraints(self):
        certificate = create_certificate(self.model, self.digest)
        self.certificate.write_bytes(gzip.compress(canonical(certificate), mtime=0))
        receipt = verify_certificate(self.model, self.digest, self.certificate)
        coverage = receipt["acceptanceCoverage"]
        self.assertFalse(coverage["complete"])
        self.assertEqual(["DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS",
                          "DERIVED_FOUT_GRAPH_OWNERSHIP",
                          "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION"],
                         coverage["assessedPredicates"])
        self.assertIn("NON_DECISION_ENDPOINT_GRAPH_CONSTRAINT_RECORDS",
                      coverage["serializedButNotAssessed"])
        self.assertNotIn("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
                         coverage["opaquePredicates"])
        self.assertNotIn("DERIVED_FOUT_GRAPH_OWNERSHIP", coverage["opaquePredicates"])

    def test_missing_owner_evidence_remains_opaque(self):
        self.domain.pop("nonDecisionCandidateOwners")
        artifact = json.loads(gzip.decompress(self.model.read_bytes()))
        artifact["nativeDomain"] = self.domain
        artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
            canonical(self.domain)).hexdigest()
        raw = canonical(artifact)
        self.model.write_bytes(gzip.compress(raw, mtime=0))
        receipt = verify(self.model, hashlib.sha256(raw).hexdigest())
        self.assertNotIn("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
                         receipt["acceptanceCoverage"]["assessedPredicates"])
        self.assertIn("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
                      receipt["acceptanceCoverage"]["opaquePredicates"])

    def test_missing_derived_action_evidence_remains_opaque(self):
        self.domain.pop("derivedFoutActions")
        artifact = json.loads(gzip.decompress(self.model.read_bytes()))
        artifact["nativeDomain"] = self.domain
        artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
            canonical(self.domain)).hexdigest()
        raw = canonical(artifact)
        self.model.write_bytes(gzip.compress(raw, mtime=0))
        receipt = verify(self.model, hashlib.sha256(raw).hexdigest())
        self.assertNotIn("DERIVED_FOUT_GRAPH_OWNERSHIP",
                         receipt["acceptanceCoverage"]["assessedPredicates"])
        self.assertIn("DERIVED_FOUT_GRAPH_OWNERSHIP",
                      receipt["acceptanceCoverage"]["opaquePredicates"])


if __name__ == "__main__":
    unittest.main()
