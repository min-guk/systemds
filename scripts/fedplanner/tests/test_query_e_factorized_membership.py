import copy
import gzip
import hashlib
from itertools import product
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from e_target_preimage import compile_target_preimage
from diagnose_e_physical_forest import translate_physical_forest
from exact_e_physical_relation import compose_physical_projection
from factor_forest_image import eliminate_factor_forest
from physical_coordinate_contract import PhysicalCoordinateCodec
from query_e_canonical_membership import query_membership
from query_e_factorized_membership import (_dense_replay, _recover_witness,
                                           query_factorized_membership,
                                           verify_factorized_membership)
from test_e_target_preimage import (CALIBRATION_ROOT, REAL_P2_MODEL, model)


class EFactorizedMembershipTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.codec = PhysicalCoordinateCodec(CALIBRATION_ROOT)

    def write_model(self, root, value):
        root.mkdir(parents=True, exist_ok=True)
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True)
        return path

    def canonical_plan(self, source, assignment):
        projected = compose_physical_projection(source, assignment)
        return self.codec.decode(self.codec.encode(projected))

    def test_tiny_sat_matches_exhaustive_and_absent_target_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            targets = {}
            for assignment in product(range(2), repeat=2):
                plan = self.canonical_plan(source, assignment)
                targets[self.codec.encode(plan)] = plan
            absent = copy.deepcopy(next(iter(targets.values())))
            absent["nodes"][0]["opcode"] = "definitely-absent-opcode"
            absent = self.codec.decode(self.codec.encode(absent))
            targets[self.codec.encode(absent)] = absent

            for coordinate, target in targets.items():
                with self.subTest(target=coordinate.hex()[:24]):
                    exhaustive = query_membership(
                        path, target, CALIBRATION_ROOT, max_assignments=4)
                    factorized = query_factorized_membership(
                        path, target, CALIBRATION_ROOT)
                    expected = ("SAT" if exhaustive["status"] == "SAT" else
                                "UNSAT")
                    self.assertEqual(expected, factorized["status"], factorized)
                    self.assertEqual(expected == "SAT",
                                     factorized["membership"])
                    self.assertEqual("PASS_COMPLETE",
                                     factorized["proof"]["denseReplayStatus"])
                    self.assertEqual("PASS_EVERY_ROOT", factorized["proof"]
                                     ["producerMddResidualReplay"])
                    self.assertEqual(
                        "CAPTURED_E_DEFINITE_ALLOW_TYPED_DECODER_MEMBERSHIP_ONLY",
                        factorized["claimScope"])
                    self.assertIn("NOT_A_P_E_EQUALITY_RESULT",
                                  factorized["semanticBoundary"])
                    if factorized["status"] == "SAT":
                        witness = tuple(factorized["witness"]
                                        ["nativeAssignment"])
                        self.assertEqual(
                            self.codec.encode(target),
                            self.codec.encode(compose_physical_projection(
                                source, witness)))
                    else:
                        self.assertIsNone(factorized["witness"])
                        self.assertEqual([], factorized["blockers"])
                        self.assertEqual("COMPLETE", factorized["proof"]
                                         ["atomSemantics"]["status"])
                        self.assertTrue(factorized["proof"]
                                        ["compiledFactorsUnsat"])

    def test_unknown_phi_invalid_target_and_resource_limits_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = model()
            path = self.write_model(root, source)
            target = self.canonical_plan(source, (0, 0))

            unknown = copy.deepcopy(source)
            unknown["factors"][0]["truth"][0] = "UNKNOWN"
            unknown_path = self.write_model(root / "unknown", unknown)
            unknown_result = query_factorized_membership(
                unknown_path, target, CALIBRATION_ROOT)

            phi = copy.deepcopy(source)
            template = phi["physicalProjection"]["variables"][1][
                "alternatives"][0]["bindings"][0]
            template["mode"] = "PHI"
            template.pop("producerDomain")
            template["producerAlternatives"] = [
                {"producerDomain": 0, "controlArm": "then"},
                {"producerDomain": 0, "controlArm": "else"}]
            phi_path = self.write_model(root / "phi", phi)
            phi_result = query_factorized_membership(
                phi_path, target, CALIBRATION_ROOT)

            invalid = copy.deepcopy(target)
            invalid.pop("context")
            invalid_result = query_factorized_membership(
                path, invalid, CALIBRATION_ROOT)
            dense_limited = query_factorized_membership(
                path, target, CALIBRATION_ROOT,
                max_dense_bucket_cells=1)
            mdd_limited = query_factorized_membership(
                path, target, CALIBRATION_ROOT, max_nodes=1)

        for result in (unknown_result, phi_result, invalid_result,
                       dense_limited, mdd_limited):
            self.assertEqual("INCOMPLETE", result["status"], result)
            self.assertIsNone(result["membership"])
            self.assertIsNone(result["witness"])
        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                      unknown_result["blockers"])
        self.assertIn("PHI_UNSUPPORTED", phi_result["blockers"])
        self.assertIn("TARGET_UNSUPPORTED_OR_INVALID",
                      invalid_result["blockers"])
        self.assertIn("DENSE_BUCKET_CELL_BUDGET_EXHAUSTED",
                      dense_limited["blockers"])
        self.assertTrue(mdd_limited["blockers"])

    def test_corrupted_preimage_or_producer_mdd_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            target = self.canonical_plan(source, (0, 0))

            def damaged_preimage(*args, **kwargs):
                result = compile_target_preimage(*args, **kwargs)
                result["factorPayload"]["targetFactors"][0]["truth"][0] = \
                    not result["factorPayload"]["targetFactors"][0]["truth"][0]
                return result

            with patch("query_e_factorized_membership.compile_target_preimage",
                       side_effect=damaged_preimage):
                with self.assertRaisesRegex(ValueError,
                                            "preimage artifact commitment"):
                    query_factorized_membership(
                        path, target, CALIBRATION_ROOT)

            def resigned_semantic_preimage(*args, **kwargs):
                result = copy.deepcopy(compile_target_preimage(*args, **kwargs))
                factor = result["factorPayload"]["targetFactors"][0]
                factor["truth"] = [False] * len(factor["truth"])
                payload = result["factorPayload"]
                payload["factorPayloadSha256"] = hashlib.sha256(json.dumps(
                    {key: value for key, value in payload.items()
                     if key != "factorPayloadSha256"}, sort_keys=True,
                    separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
                result["artifactSha256"] = hashlib.sha256(json.dumps(
                    {key: value for key, value in result.items()
                     if key != "artifactSha256"}, sort_keys=True,
                    separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
                return result

            with patch("query_e_factorized_membership.compile_target_preimage",
                       side_effect=resigned_semantic_preimage):
                with self.assertRaisesRegex(
                        ValueError, "independent rebuild"):
                    query_factorized_membership(
                        path, target, CALIBRATION_ROOT)

            def damaged_mdd(*args, **kwargs):
                result = eliminate_factor_forest(*args, **kwargs)
                name = next(iter(result["relation"]["roots"]))
                result["relation"]["roots"][name] = (
                    "F" if result["relation"]["roots"][name] == "T" else "T")
                return result

            with patch("query_e_factorized_membership.eliminate_factor_forest",
                       side_effect=damaged_mdd):
                with self.assertRaises(ValueError):
                    query_factorized_membership(
                        path, target, CALIBRATION_ROOT)

    def test_result_is_deterministic_and_binds_inputs_and_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            target = self.canonical_plan(source, (1, 1))
            left = query_factorized_membership(
                path, target, CALIBRATION_ROOT)
            right = query_factorized_membership(
                path, target, CALIBRATION_ROOT)
            verification = verify_factorized_membership(
                left, path, target, CALIBRATION_ROOT)
            resigned = copy.deepcopy(left)
            resigned["proof"]["finalConjunction"] = \
                not resigned["proof"]["finalConjunction"]
            resigned["artifactSha256"] = hashlib.sha256(json.dumps(
                {key: value for key, value in resigned.items()
                 if key != "artifactSha256"}, sort_keys=True,
                separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            with self.assertRaisesRegex(ValueError, "differs from dense proof"):
                verify_factorized_membership(
                    resigned, path, target, CALIBRATION_ROOT)
            certificate_mutation = copy.deepcopy(left)
            first_truth = certificate_mutation["proof"]["denseTrace"][0][
                "outputTruth"]
            first_truth[0] = not first_truth[0]
            certificate_mutation["artifactSha256"] = hashlib.sha256(json.dumps(
                {key: value for key, value in certificate_mutation.items()
                 if key != "artifactSha256"}, sort_keys=True,
                separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            with self.assertRaisesRegex(ValueError,
                                        "operation certificate mismatch"):
                verify_factorized_membership(
                    certificate_mutation, path, target, CALIBRATION_ROOT)
            budget_mutation = copy.deepcopy(left)
            budget_mutation["budgets"]["maxDenseBucketCells"] = 1
            budget_mutation["budgets"]["maxDenseTotalCells"] = 1
            budget_mutation["budgets"]["maxDenseFactorLookups"] = 1
            budget_mutation["artifactSha256"] = hashlib.sha256(json.dumps(
                {key: value for key, value in budget_mutation.items()
                 if key != "artifactSha256"}, sort_keys=True,
                separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            with self.assertRaisesRegex(ValueError,
                                        "committed construction budgets"):
                verify_factorized_membership(
                    budget_mutation, path, target, CALIBRATION_ROOT)
            mdd_budget_mutation = copy.deepcopy(left)
            mdd_budget_mutation["budgets"]["maxNodes"] = 1
            mdd_budget_mutation["artifactSha256"] = hashlib.sha256(json.dumps(
                {key: value for key, value in mdd_budget_mutation.items()
                 if key != "artifactSha256"}, sort_keys=True,
                separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            with self.assertRaisesRegex(ValueError, "producer MDD budgets"):
                verify_factorized_membership(
                    mdd_budget_mutation, path, target, CALIBRATION_ROOT)
            replay_limited = verify_factorized_membership(
                left, path, target, CALIBRATION_ROOT, max_total_cells=1)
            proof_limited = verify_factorized_membership(
                left, path, target, CALIBRATION_ROOT, max_proof_bytes=1)
            construction_limited = verify_factorized_membership(
                left, path, target, CALIBRATION_ROOT, max_atoms=1)
        self.assertEqual(left, right)
        self.assertEqual("PASS", verification["status"])
        for limited in (replay_limited, proof_limited, construction_limited):
            self.assertEqual("INCOMPLETE", limited["status"], limited)
        for name in ("modelSha256", "modelFileSha256", "projectionSha256",
                     "atomDictionarySha256", "atomConditionsSha256",
                     "allVariablesSha256", "hardAndAtomFactorsSha256",
                     "targetCanonicalSha256", "targetCoordinateSha256",
                     "identityVerifierSha256"):
            self.assertRegex(left["bindings"][name], r"^[0-9a-f]{64}$")
        self.assertRegex(left["preimageArtifactSha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(left["proof"]["producerMddResultSha256"],
                         r"^[0-9a-f]{64}$")
        self.assertRegex(left["artifactSha256"], r"^[0-9a-f]{64}$")

    @unittest.skipUnless(REAL_P2_MODEL.is_file(), "real P2 model unavailable")
    def test_real_p2_witness_derived_target(self):
        with gzip.open(REAL_P2_MODEL, "rt", encoding="utf-8") as stream:
            source = json.load(stream)
        translated = translate_physical_forest(REAL_P2_MODEL)
        native_count = len(translated["order"])
        dense, reverse, _, _, blocker = _dense_replay(
            translated["variables"], translated["factors"],
            tuple(range(native_count)), max_bucket_cells=1_000_000,
            max_total_cells=10_000_000, max_factor_lookups=50_000_000)
        self.assertIsNone(blocker)
        self.assertTrue(all(row["truth"] == (True,) for row in dense))
        varying = _recover_witness(
            reverse, tuple(len(variable.values)
                           for variable in translated["variables"]))
        assignment = [0] * len(source["domains"])
        for level, domain in enumerate(translated["order"]):
            assignment[domain] = varying[level]
        assignment = tuple(assignment)
        target = self.canonical_plan(source, assignment)
        started = time.monotonic()
        result = query_factorized_membership(
            REAL_P2_MODEL, target, CALIBRATION_ROOT,
            max_dense_bucket_cells=1_000_000,
            max_dense_total_cells=10_000_000)
        self.assertEqual("SAT", result["status"], result)
        self.assertEqual("PASS_COMPLETE", result["proof"]["denseReplayStatus"])
        self.assertEqual("PASS_EVERY_ROOT",
                         result["proof"]["producerMddResidualReplay"])
        zero_target = self.canonical_plan(
            source, tuple(0 for _ in source["domains"]))
        zero_result = query_factorized_membership(
            REAL_P2_MODEL, zero_target, CALIBRATION_ROOT)
        self.assertEqual("UNSAT", zero_result["status"], zero_result)
        self.assertFalse(zero_result["membership"])
        self.assertEqual([], zero_result["blockers"])
        self.assertEqual("COMPLETE",
                         zero_result["proof"]["atomSemantics"]["status"])
        self.assertTrue(zero_result["proof"]["compiledFactorsUnsat"])
        self.assertEqual("PASS_COMPLETE",
                         zero_result["proof"]["denseReplayStatus"])
        self.assertLess(time.monotonic() - started, 60)


if __name__ == "__main__":
    unittest.main()
