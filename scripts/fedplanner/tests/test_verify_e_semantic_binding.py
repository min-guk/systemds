import gzip
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from exact_e_physical_relation import canonical, compose_physical_projection, digest
from verify_e_semantic_binding import verify_case, verify_cases


def owner(name):
    return {"sourceOrigin": name, "functionNamespace": "main",
            "callSitePath": "root", "recompileContext": "static",
            "emittedInstance": name,
            "controlRegion": {"functionNamespace": "main", "regionPath": "root",
                              "callSitePath": "root", "recompileContext": "static"}}


def fixture():
    who = owner("x")
    authority = {"owner": who, "kind": "CANDIDATE", "layout": "LOCAL"}
    fragments = []
    for value in ("CP", "FED"):
        fragments.append({"node": {"occurrence": who, "opcode": "+", "exec": value,
                                    "output": "LOUT", "ftype": "NONE",
                                    "shapeDependent": False, "executionFType": "NONE",
                                    "valueVersion": {"v": 1}, "authorityRef": authority},
                          "authority": {"id": authority, "source": "CANDIDATE",
                                        "owner": who, "kind": "CANDIDATE"},
                          "actions": [], "geometry": [], "bindings": []})
    model = {"schema": "closed-e-native-model-artifact-v1", "cell": "tiny",
             "source": "E_C0", "programSha256": "p",
             "sourceIdentity": {"nodes": [{"occurrence": "x", "operation": "+"}],
                                "orderedInputs": [], "logicalInputs": [],
                                "physicalLogicalInputs": []},
             "domains": [{"index": 0, "occurrence": "x",
                           "alternatives": [{"signature": "a", "state": "CP/LOUT/-/SHAPE_INDEPENDENT"},
                                            {"signature": "b", "state": "FED/LOUT/-/SHAPE_INDEPENDENT"}]}],
             "factors": [{"scope": [0], "cells": "2", "truth": ["ALLOW", "REJECT"]}],
             "physicalProjectionContract": "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL",
             "physicalProjection": {"schema": "exact-physical-compositional-projection-v1",
                                    "logicalProgram": "p", "variables": [{"domain": 0,
                                        "occurrence": who, "alternatives": fragments}],
                                    "nodeOrder": [0], "bindingDomainOrder": [],
                                    "logicalInputs": []},
             "acceptance": "MATERIALIZED_FACTOR_TABLES"}
    plan = compose_physical_projection(model, [0])
    oracle = {"schema": "exact-e-java-physical-semantic-oracle-v1",
              "status": "BLOCKED_PENDING_INDEPENDENT_PYTHON_VERIFICATION",
              "claimScope": "TINY_FIXTURE_FULL_PHYSICAL_IDENTITY_DIFFERENTIAL_ONLY",
              "fixture": "tiny", "programSha256": "p",
              "sourceIdentitySha256": digest(model["sourceIdentity"]),
              "domainCommitmentSha256": digest([["a", "b"]]),
              "projectionSha256": digest(model["physicalProjection"]),
              "rawAssignments": "2",
              "rows": [{"ordinal": "0", "assignment": [0],
                         "alternativeSignatures": ["a"], "status": "EMITTED",
                         "reason": "", "physicalPlan": plan},
                        {"ordinal": "1", "assignment": [1],
                         "alternativeSignatures": ["b"], "status": "REJECTED",
                         "reason": "hard-factor:0", "physicalPlan": None}]}
    return model, oracle


class SemanticBindingTest(unittest.TestCase):
    def save(self, directory, name, value):
        raw = canonical(value)
        path = Path(directory) / name
        with gzip.GzipFile(filename=str(path), mode="wb", mtime=0) as stream:
            stream.write(raw)
        return path, hashlib.sha256(raw).hexdigest()

    def artifacts(self, directory):
        model, oracle = fixture()
        model_path, model_sha = self.save(directory, "model.json.gz", model)
        oracle["modelSha256"] = model_sha
        oracle_path, oracle_sha = self.save(directory, "oracle.json.gz", oracle)
        return model_path, oracle_path, oracle_sha

    def test_exact_differential_passes_but_missing_coverage_stays_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            case = self.artifacts(directory)
            result = verify_cases([case])
            self.assertEqual("PASS_EXHAUSTIVE_FULL_PHYSICAL_IDENTITY",
                             result["cases"][0]["comparisonStatus"])
            self.assertEqual("BLOCKED", result["status"])
            self.assertIn("binding:PHI", result["missingRequiredCoverage"])
            self.assertEqual("NOT_CLAIMED", result["fullWorkloadCertification"])

    def test_tampered_direct_physical_coordinate_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            model, oracle = fixture()
            model_path, model_sha = self.save(directory, "model.json.gz", model)
            oracle["modelSha256"] = model_sha
            oracle["rows"][0]["physicalPlan"]["nodes"][0]["exec"] = "FORGED"
            oracle_path, oracle_sha = self.save(directory, "oracle.json.gz", oracle)
            with self.assertRaisesRegex(ValueError, "physical identity differs"):
                verify_case(model_path, oracle_path, oracle_sha)

    def test_unknown_and_external_sha_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path, oracle_path, oracle_sha = self.artifacts(directory)
            with self.assertRaisesRegex(ValueError, "external commitment"):
                verify_case(model_path, oracle_path, "0" * 64)
            model, oracle = fixture()
            model["factors"][0]["truth"][0] = "UNKNOWN"
            model_path, model_sha = self.save(directory, "unknown-model.json.gz", model)
            oracle["modelSha256"] = model_sha
            oracle["rows"][0]["status"] = "UNKNOWN"
            oracle["rows"][0]["physicalPlan"] = None
            oracle_path, oracle_sha = self.save(directory, "unknown-oracle.json.gz", oracle)
            with self.assertRaisesRegex(ValueError, "UNKNOWN acceptance"):
                verify_case(model_path, oracle_path, oracle_sha)

    def test_phi_producer_derived_ftype_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            model, oracle = fixture()
            template = {"consumer": owner("x"), "inputPosition": 0,
                        "presence": "PRESENT", "mode": "PHI",
                        "ftypeFromProducer": True,
                        "producerAlternatives": [{"producerDomain": 0,
                                                  "controlArm": "then"}]}
            model["physicalProjection"]["variables"][0]["alternatives"][0]["bindings"] = [template]
            model["physicalProjection"]["bindingDomainOrder"] = [0]
            oracle["projectionSha256"] = digest(model["physicalProjection"])
            model_path, model_sha = self.save(directory, "phi-model.json.gz", model)
            oracle["modelSha256"] = model_sha
            oracle_path, oracle_sha = self.save(directory, "phi-oracle.json.gz", oracle)
            with self.assertRaisesRegex(ValueError, "unsupported PHI"):
                verify_case(model_path, oracle_path, oracle_sha)

    def test_all_accepted_fixture_cannot_complete_acceptance_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            model, oracle = fixture()
            model["factors"][0]["truth"] = ["ALLOW", "ALLOW"]
            oracle["rows"][1]["status"] = "EMITTED"
            oracle["rows"][1]["reason"] = ""
            oracle["rows"][1]["physicalPlan"] = compose_physical_projection(model, [1])
            model_path, model_sha = self.save(directory, "all-model.json.gz", model)
            oracle["modelSha256"] = model_sha
            oracle_path, oracle_sha = self.save(directory, "all-oracle.json.gz", oracle)
            result = verify_cases([(model_path, oracle_path, oracle_sha)])
            self.assertEqual("PASS_EXHAUSTIVE_FULL_PHYSICAL_IDENTITY",
                             result["cases"][0]["comparisonStatus"])
            self.assertEqual("BLOCKED", result["status"])
            self.assertIn("acceptance:REJECTED", result["missingRequiredCoverage"])

    def test_model_and_oracle_decompression_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path, oracle_path, oracle_sha = self.artifacts(directory)
            with self.assertRaisesRegex(ValueError, "decoded-byte budget exhausted"):
                verify_case(model_path, oracle_path, oracle_sha, max_decoded_bytes=16)
            model, oracle = fixture()
            model["boundedPadding"] = "x" * 10_000
            model_path, model_sha = self.save(directory, "large-model.json.gz", model)
            oracle["modelSha256"] = model_sha
            oracle_path, oracle_sha = self.save(directory, "small-oracle.json.gz", oracle)
            budget = len(canonical(oracle)) + 1
            self.assertGreater(len(canonical(model)), budget)
            with self.assertRaisesRegex(ValueError, "decoded-byte budget exhausted"):
                verify_case(model_path, oracle_path, oracle_sha,
                            max_decoded_bytes=budget)


if __name__ == "__main__":
    unittest.main()
