import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from diagnose_e_factor_forest import (build, publish, read_saved,
                                      select_elimination_order, translate_model,
                                      verify_saved)


def model_fixture(unknown=False):
    truth = ["ALLOW", "REJECT", "REJECT", "ALLOW"]
    if unknown:
        truth[3] = "UNKNOWN"
    return {
        "schema": "closed-e-native-model-artifact-v2",
        "acceptance": "MATERIALIZED_FACTOR_TABLES",
        "cell": "tiny", "programSha256": "1" * 64,
        "conditionSha256": "2" * 64,
        "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
        "nativeFactorCount": 2, "materializedFactorCount": 2,
        "sourceFactorScopes": [[0, 1], [2, 0]],
        "nativeFactorCells": "6", "materializedFactorCells": "6",
        "domains": [
            {"index": 0, "occurrence": "x", "alternatives": [
                {"signature": "x0"}, {"signature": "x1"}]},
            {"index": 1, "occurrence": "y", "alternatives": [
                {"signature": "y0"}, {"signature": "y1"}]},
            {"index": 2, "occurrence": "singleton", "alternatives": [
                {"signature": "s0"}]},
        ],
        "factors": [
            {"scope": [0, 1], "sourceFactorIndices": [0], "cells": "4",
             "truth": truth},
            {"scope": [2, 0], "sourceFactorIndices": [1], "cells": "2",
             "truth": ["ALLOW", "REJECT"]},
        ],
    }


def singleton_model(status):
    return {
        "schema": "closed-e-native-model-artifact-v2",
        "acceptance": "MATERIALIZED_FACTOR_TABLES",
        "cell": "singleton-" + status.lower(),
        "programSha256": "3" * 64, "conditionSha256": "4" * 64,
        "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
        "nativeFactorCount": 1, "materializedFactorCount": 1,
        "sourceFactorScopes": [[0]], "nativeFactorCells": "1",
        "materializedFactorCells": "1",
        "domains": [{"index": 0, "occurrence": "constant",
                     "alternatives": [{"signature": "only"}]}],
        "factors": [{"scope": [0], "sourceFactorIndices": [0],
                     "cells": "1", "truth": [status]}],
    }


def zero_factor_model(varying):
    alternatives = [{"signature": "zero"}]
    if varying:
        alternatives.append({"signature": "one"})
    return {
        "schema": "closed-e-native-model-artifact-v2",
        "acceptance": "MATERIALIZED_FACTOR_TABLES",
        "cell": "zero-factor-" + ("varying" if varying else "singleton"),
        "programSha256": "5" * 64, "conditionSha256": "6" * 64,
        "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
        "nativeFactorCount": 0, "materializedFactorCount": 0,
        "sourceFactorScopes": [], "nativeFactorCells": "0",
        "materializedFactorCells": "0",
        "domains": [{"index": 0, "occurrence": "unconstrained",
                     "alternatives": alternatives}],
        "factors": [],
    }


def write_model(root, model):
    path = root / "e-model.json.gz"
    with gzip.open(path, "wt", encoding="utf-8") as stream:
        json.dump(model, stream, sort_keys=True)
    return path


class DiagnoseEFactorForestTest(unittest.TestCase):
    def test_translation_substitutes_singleton_and_reorders_factor_truth_exactly(self):
        with tempfile.TemporaryDirectory() as directory:
            path = write_model(Path(directory), model_fixture())
            translated = translate_model(path)
        self.assertEqual("COMPLETE", translated["translationStatus"])
        self.assertEqual({0, 1}, set(translated["order"]))
        self.assertEqual(2, len(translated["variables"]))
        by_name = {row["name"]: row for row in translated["factors"]}
        second = by_name["e-factor:000001"]
        self.assertEqual((translated["order"].index(0),), second["scope"])
        self.assertEqual((True, False), second["truth"])
        self.assertEqual({"ALLOW": 3, "REJECT": 3, "UNKNOWN": 0},
                         translated["sourceStatusCounts"])

    def test_all_singleton_allow_reject_unknown_have_exact_constant_semantics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for status, accepted in (("ALLOW", True), ("REJECT", False),
                                     ("UNKNOWN", False)):
                with self.subTest(status=status):
                    model = write_model(root, singleton_model(status))
                    artifact = root / (status.lower() + ".json.gz")
                    result = build(model)
                    self.assertEqual("DIAGNOSTIC_COMPLETE",
                                     result["diagnosticStatus"])
                    self.assertEqual(0,
                                     result["translation"]["varyingDomainCount"])
                    forest = result["factorForest"]
                    self.assertEqual([], forest["eliminateLevels"])
                    root_id = forest["relation"]["roots"]["e-factor:000000"]
                    self.assertEqual("T" if accepted else "F", root_id)
                    if status == "UNKNOWN":
                        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                                      result["blockers"])
                    receipt = verify_saved_after_publish(model, artifact, result)
                    self.assertEqual("VERIFIED_EXHAUSTIVE",
                                     receipt["forestSemanticReplayStatus"])

    def test_zero_factor_models_normalize_empty_conjunction_to_true(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for varying in (False, True):
                with self.subTest(varying=varying):
                    model = write_model(root, zero_factor_model(varying))
                    artifact = root / ("varying.json.gz" if varying else
                                       "singleton.json.gz")
                    result = build(model)
                    self.assertEqual(0,
                                     result["translation"]["sourceFactorCount"])
                    self.assertEqual(
                        "EMPTY_CONJUNCTION_TO_CONSTANT_TRUE_FACTOR",
                        result["translation"]["zeroFactorNormalization"])
                    self.assertEqual("DIAGNOSTIC_COMPLETE",
                                     result["diagnosticStatus"])
                    forest = result["factorForest"]
                    self.assertEqual("constant:true",
                                     forest["inputFactorScopes"][0]["name"])
                    self.assertTrue(all(root_id == "T" for root_id in
                                        forest["relation"]["roots"].values()))
                    receipt = verify_saved_after_publish(model, artifact, result)
                    self.assertEqual("PASS", receipt["status"])
                    self.assertEqual(
                        "BLOCKED_REPLAY_CELL_BUDGET_EXHAUSTED" if varying else
                        "VERIFIED_EXHAUSTIVE",
                        receipt["forestSemanticReplayStatus"])

    def test_order_is_deterministic_and_covers_varying_domains(self):
        radices = [2, 3, 1, 2]
        tables = [((0, 1), ()), ((1, 3), ()), ((2, 0), ())]
        left = select_elimination_order(radices, tables)
        right = select_elimination_order(radices, list(reversed(tables)))
        self.assertEqual(left, right)
        self.assertEqual({0, 1, 3}, set(left[0]))

    def test_complete_artifact_is_published_and_recomputed_from_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture(unknown=True))
            artifact = root / "diagnostic.json.gz"
            result = build(model, max_nodes=10_000, max_apply_pairs=50_000)
            self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
            self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                          result["blockers"])
            self.assertEqual("BLOCKED_REPLAY_CELL_BUDGET_EXHAUSTED",
                             verify_saved_after_publish(
                                 model, artifact, result)[
                                     "forestSemanticReplayStatus"])
            self.assertEqual(result, read_saved(artifact))

    def test_translation_budget_block_is_persisted_and_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture())
            artifact = root / "blocked.json.gz"
            result = build(model, max_factor_cells=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             result["diagnosticStatus"])
            self.assertIsNone(result["factorForest"])
            receipt = verify_saved_after_publish(model, artifact, result)
            self.assertEqual("PASS", receipt["status"])
            self.assertEqual("NOT_PRODUCED_TRANSLATION_BLOCKED",
                             receipt["forestSemanticReplayStatus"])

    def test_publishing_is_byte_deterministic_and_all_budgets_are_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture())
            result = build(model)
            left, right = root / "left.json.gz", root / "right.json.gz"
            publish(left, result)
            publish(right, result)
            self.assertEqual(left.read_bytes(), right.read_bytes())
            with self.assertRaisesRegex(ValueError, "max_nodes"):
                build(model, max_nodes=0, max_factor_cells=1)

    def test_saved_artifact_compressed_and_decoded_limits_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture())
            artifact = root / "diagnostic.json.gz"
            publish(artifact, build(model))
            with self.assertRaisesRegex(ValueError, "compressed-byte budget"):
                read_saved(artifact, max_compressed_bytes=1)
            with self.assertRaisesRegex(ValueError, "decoded-byte budget"):
                read_saved(artifact, max_decoded_bytes=1)
            with self.assertRaisesRegex(ValueError, "compressed-byte budget"):
                verify_saved(model, artifact, max_artifact_compressed_bytes=1)
            with patch("diagnose_e_factor_forest._file_sha",
                       side_effect=AssertionError("hash must not run")):
                with self.assertRaisesRegex(ValueError, "compressed-byte budget"):
                    verify_saved(
                        model, artifact, max_artifact_compressed_bytes=1)
            with self.assertRaisesRegex(ValueError, "decoded-byte budget"):
                verify_saved(model, artifact, max_artifact_decoded_bytes=1)

    def test_saved_construction_budgets_must_fit_verifier_caps_before_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture())
            artifact = root / "diagnostic.json.gz"
            result = build(model)
            publish(artifact, result)
            cases = (
                ("max_nodes", result["budgets"]["maxNodes"] - 1),
                ("max_apply_pairs", result["budgets"]["maxApplyPairs"] - 1),
                ("max_factor_cells", result["budgets"]["maxFactorCells"] - 1),
                ("max_total_factor_cells",
                 result["budgets"]["maxTotalFactorCells"] - 1),
                ("max_model_decoded_bytes",
                 result["budgets"]["maxModelDecodedBytes"] - 1),
                ("max_model_compressed_bytes",
                 result["budgets"]["maxModelCompressedBytes"] - 1),
            )
            for argument, cap in cases:
                with self.subTest(argument=argument), patch(
                        "diagnose_e_factor_forest.build",
                        side_effect=AssertionError("rebuild must not run")):
                    with self.assertRaisesRegex(ValueError, "exceeds verifier cap"):
                        verify_saved(model, artifact, **{argument: cap})

    def test_source_or_artifact_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = write_model(root, model_fixture())
            artifact = root / "diagnostic.json.gz"
            result = build(model)
            publish(artifact, result)
            expected_file = hashlib.sha256(artifact.read_bytes()).hexdigest()
            self.assertEqual("PASS", verify_saved(
                model, artifact, expected_file)["status"])

            changed = model_fixture()
            changed["cell"] = "mutated"
            changed_model = write_model(root, changed)
            with self.assertRaisesRegex(ValueError, "differs from source replay"):
                verify_saved(changed_model, artifact)

            damaged = copy.deepcopy(result)
            damaged["blockers"].append("MUTATED")
            publish(artifact, damaged)
            with self.assertRaisesRegex(ValueError, "commitment mismatch"):
                verify_saved(model, artifact)


def verify_saved_after_publish(model, artifact, result):
    publish(artifact, result)
    file_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    return verify_saved(model, artifact, file_sha)


if __name__ == "__main__":
    unittest.main()
