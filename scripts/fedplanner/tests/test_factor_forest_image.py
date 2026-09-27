import copy
import hashlib
from itertools import product
import json
from pathlib import Path
import random
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from boolean_mdd_relation import MDDManager, Variable, validate_artifact
from factor_forest_image import (eliminate_factor_forest,
                                 materialize_factor_forest,
                                 verify_factor_forest_artifact,
                                 verify_factor_forest_blocked,
                                 verify_factor_forest_result)


def factor_value(row, assignment, radices):
    offset = 0
    for level in row["scope"]:
        offset = offset * radices[level] + assignment[level]
    return row["truth"][offset]


def brute_project(variables, factors, eliminated):
    radices = [len(variable.values) for variable in variables]
    kept = [level for level in range(len(variables)) if level not in eliminated]
    accepted = set()
    for assignment in product(*(range(radix) for radix in radices)):
        if all(factor_value(row, assignment, radices) for row in factors):
            accepted.add(tuple(assignment[level] for level in kept))
    return kept, accepted


def resign_result(result):
    payload = {key: value for key, value in result.items()
               if key != "resultSha256"}
    result["resultSha256"] = hashlib.sha256(json.dumps(
        payload, sort_keys=True, separators=(",", ":"),
        ensure_ascii=False).encode("utf-8")).hexdigest()


def resign_blocked(result):
    payload = {key: value for key, value in result.items()
               if key != "diagnosticSha256"}
    result["diagnosticSha256"] = hashlib.sha256(json.dumps(
        payload, sort_keys=True, separators=(",", ":"),
        ensure_ascii=False).encode("utf-8")).hexdigest()


class FactorForestImageTest(unittest.TestCase):
    def assert_matches_brute_force(self, variables, factors, eliminated,
                                   **budgets):
        result = eliminate_factor_forest(
            variables, factors, eliminated, **budgets)
        self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
        artifact = validate_artifact(result["relation"], variables)
        kept, expected = brute_project(variables, factors, set(eliminated))
        radices = [len(variable.values) for variable in variables]
        actual = materialize_factor_forest(
            result, max_cells=100_000)["acceptedAssignments"]
        for kept_values in product(*(range(radices[level]) for level in kept)):
            assignment = [0] * len(variables)
            for level, value in zip(kept, kept_values):
                assignment[level] = value
            # Eliminated coordinates must be semantically irrelevant.
            for hidden_values in product(
                    *(range(radices[level]) for level in eliminated)):
                replay = list(assignment)
                for level, value in zip(eliminated, hidden_values):
                    replay[level] = value
                self.assertEqual(kept_values in expected,
                                 all(artifact.evaluate(name, replay)
                                     for name in artifact.roots))
        self.assertEqual(expected, actual)
        self.assertFalse(set(eliminated) & set(result["remainingSupport"]))
        verified = verify_factor_forest_result(
            result, variables, factors, eliminated, max_replay_cells=100_000)
        self.assertEqual("VERIFIED_EXHAUSTIVE",
                         verified["semanticReplayStatus"])
        self.assertEqual(len(expected), verified["projectedAssignmentCount"])
        artifact_only = verify_factor_forest_artifact(
            result, result["resultSha256"])
        self.assertEqual("UNVERIFIED_INPUT_FACTOR_SEMANTICS",
                         artifact_only["semanticReplayStatus"])
        return result

    def test_output_only_factor_is_preserved_while_native_is_eliminated(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("a", ("0", "1")),
                     Variable("b", ("0", "1")))
        factors = [
            {"name": "x_equals_a", "scope": [0, 1],
             "truth": [True, False, False, True]},
            {"name": "output_only_a_equals_b", "scope": [1, 2],
             "truth": [True, False, False, True]},
        ]
        result = self.assert_matches_brute_force(variables, factors, (0,))
        self.assertEqual(["x_equals_a"], result["trace"][0]["incidentFactors"])
        self.assertEqual(1, result["trace"][0]["preservedFactors"])
        self.assertIn(2, result["remainingSupport"])

    def test_contradictory_factors_sharing_hidden_variable_are_empty(self):
        variables = (Variable("hidden", ("0", "1")),
                     Variable("output", ("0", "1")))
        factors = [
            {"name": "same", "scope": [0, 1],
             "truth": [True, False, False, True]},
            {"name": "different", "scope": [0, 1],
             "truth": [False, True, True, False]},
        ]
        result = self.assert_matches_brute_force(variables, factors, (0,))
        self.assertFalse(materialize_factor_forest(
            result)["acceptedAssignments"])
        self.assertEqual(["different", "same"],
                         result["trace"][0]["incidentFactors"])

    def test_zero_variable_constant_factor_forests_are_exact(self):
        for truth, expected in ((True, {()}), (False, set())):
            factors = [{"name": "constant", "scope": [], "truth": [truth]}]
            with self.subTest(truth=truth):
                result = eliminate_factor_forest((), factors, ())
                self.assertEqual("DIAGNOSTIC_COMPLETE",
                                 result["diagnosticStatus"])
                self.assertEqual([], result["remainingSupport"])
                self.assertEqual(expected, materialize_factor_forest(
                    result, max_cells=1)["acceptedAssignments"])
                checked = verify_factor_forest_result(
                    result, (), factors, (), max_replay_cells=1)
                self.assertEqual("PASS", checked["status"])
                self.assertEqual("VERIFIED_EXHAUSTIVE",
                                 checked["semanticReplayStatus"])

    def test_empty_conjunction_is_exact_true_with_and_without_variables(self):
        cases = (((), (), {()}),
                 ((Variable("x", ("0", "1")),), (0,), {()}))
        for variables, eliminated, expected in cases:
            with self.subTest(variable_count=len(variables)):
                result = eliminate_factor_forest(variables, [], eliminated)
                self.assertEqual("DIAGNOSTIC_COMPLETE",
                                 result["diagnosticStatus"])
                self.assertEqual("constant:true",
                                 result["inputFactorScopes"][0]["name"])
                self.assertEqual(expected, materialize_factor_forest(
                    result, max_cells=2)["acceptedAssignments"])
                checked = verify_factor_forest_result(
                    result, variables, [], eliminated, max_replay_cells=2)
                self.assertEqual("VERIFIED_EXHAUSTIVE",
                                 checked["semanticReplayStatus"])

    def test_random_tiny_models_match_independent_exhaustive_projection(self):
        generator = random.Random(90210)
        for iteration in range(120):
            native_count = generator.randint(1, 6)
            output_count = generator.randint(1, 2)
            variables = tuple(
                Variable("x%d" % index,
                         tuple(str(value) for value in range(generator.randint(1, 3))))
                for index in range(native_count)) + tuple(
                    Variable("o%d" % index, ("0", "1"))
                    for index in range(output_count))
            factors = []
            for factor_index in range(generator.randint(1, 9)):
                width = generator.randint(0, min(3, len(variables)))
                scope = sorted(generator.sample(range(len(variables)), width))
                cells = 1
                for level in scope:
                    cells *= len(variables[level].values)
                factors.append({
                    "name": "f%02d" % factor_index, "scope": scope,
                    "truth": [bool(generator.getrandbits(1))
                              for _ in range(cells)],
                })
            with self.subTest(iteration=iteration):
                self.assert_matches_brute_force(
                    variables, factors, tuple(range(native_count)))

    def test_scope_coverage_and_artifact_mutation_fail_closed(self):
        variables = (Variable("x", ("0", "1", "2")),
                     Variable("o", ("0", "1")))
        with self.assertRaisesRegex(ValueError, "cover.*explicit scope"):
            eliminate_factor_forest(variables, [{
                "name": "truncated", "scope": [0, 1],
                "truth": [True, False],
            }], (0,))

        result = self.assert_matches_brute_force(variables, [
            {"name": "covered", "scope": [0, 1],
             "truth": [True, False, False, True, True, False]},
            {"name": "output_constraint", "scope": [1],
             "truth": [True, False]},
        ], (0,))
        damaged = copy.deepcopy(result["relation"])
        damaged["nodes"][0]["children"].reverse()
        with self.assertRaises(ValueError):
            validate_artifact(damaged, variables)

        damaged_result = copy.deepcopy(result)
        damaged_result["trace"][0]["preservedFactors"] += 1
        with self.assertRaisesRegex(ValueError, "commitment mismatch"):
            verify_factor_forest_result(
                damaged_result, variables, [
                    {"name": "covered", "scope": [0, 1],
                     "truth": [True, False, False, True, True, False]},
                    {"name": "output_constraint", "scope": [1],
                     "truth": [True, False]},
                ], (0,))

    def test_resource_limit_omits_relation(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("o", ("0", "1")))
        result = eliminate_factor_forest(variables, [{
            "name": "link", "scope": [0, 1],
            "truth": [True, False, False, True],
        }], (0,), max_nodes=1)
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         result["diagnosticStatus"])
        self.assertNotIn("relation", result)
        self.assertNotIn("resultSha256", result)
        self.assertEqual("PASS", verify_factor_forest_blocked(
            result, result["diagnosticSha256"])["status"])

        damaged = copy.deepcopy(result)
        damaged["resourceUsage"]["peakLiveNodes"] += 1
        with self.assertRaisesRegex(ValueError, "commitment mismatch"):
            verify_factor_forest_blocked(damaged)

        resigned_contract = copy.deepcopy(result)
        resigned_contract["status"] = "PASS"
        resign_blocked(resigned_contract)
        with self.assertRaisesRegex(ValueError, "contract mismatch"):
            verify_factor_forest_blocked(resigned_contract)

        resigned_shape = copy.deepcopy(result)
        resigned_shape["unexpected"] = True
        resign_blocked(resigned_shape)
        with self.assertRaisesRegex(ValueError, "top-level shape"):
            verify_factor_forest_blocked(resigned_shape)

    def test_result_and_trace_are_deterministic_across_input_factor_order(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("y", ("0", "1")),
                     Variable("o", ("0", "1")))
        factors = [
            {"name": "right", "scope": [1, 2],
             "truth": [True, False, False, True]},
            {"name": "left", "scope": [0, 1],
             "truth": [True, False, False, True]},
        ]
        left = eliminate_factor_forest(variables, factors, (0, 1))
        right = eliminate_factor_forest(variables, list(reversed(factors)), (0, 1))
        self.assertEqual(left, right)

    def test_disjoint_output_only_factors_remain_separate_roots(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("a", ("0", "1")),
                     Variable("b", ("0", "1")))
        factors = [
            {"name": "native", "scope": [0], "truth": [True, True]},
            {"name": "only_a", "scope": [1], "truth": [True, False]},
            {"name": "only_b", "scope": [2], "truth": [False, True]},
        ]
        result = self.assert_matches_brute_force(variables, factors, (0,))
        self.assertNotIn("projectedAssignmentCount", result)
        residual = {row["name"]: row for row in result["residualFactors"]}
        self.assertEqual([1], residual["only_a"]["scope"])
        self.assertEqual([2], residual["only_b"]["scope"])
        self.assertIn("only_a", result["relation"]["roots"])
        self.assertIn("only_b", result["relation"]["roots"])
        self.assertNotEqual(result["relation"]["roots"]["only_a"],
                            result["relation"]["roots"]["only_b"])
        bounded = verify_factor_forest_result(
            result, variables, factors, (0,), max_replay_cells=1)
        self.assertEqual("INCOMPLETE", bounded["status"])
        self.assertEqual("BLOCKED_REPLAY_CELL_BUDGET_EXHAUSTED",
                         bounded["semanticReplayStatus"])

    def test_primary_construction_never_materializes_or_counts_global_relation(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("a", ("0", "1")),
                     Variable("b", ("0", "1")))
        factors = [
            {"name": "x_a", "scope": [0, 1],
             "truth": [True, False, False, True]},
            {"name": "only_b", "scope": [2], "truth": [True, False]},
        ]
        with patch("factor_forest_image.MDDManager.count",
                   side_effect=AssertionError("global count forbidden")):
            result = eliminate_factor_forest(variables, factors, (0,))
        self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
        self.assertEqual(2, len(result["residualFactors"]))

    def test_contract_mutations_and_resigned_semantic_substitution_fail_closed(self):
        variables = (Variable("x", ("0", "1")),
                     Variable("o", ("0", "1")))
        factors = [
            {"name": "same", "scope": [0, 1],
             "truth": [True, False, False, True]},
            {"name": "hidden_zero", "scope": [0],
             "truth": [True, False]},
        ]
        result = eliminate_factor_forest(variables, factors, (0,))

        for key, value, message in (
                ("status", "PASS", "contract mismatch"),
                ("claimScope", "CERTIFICATE", "contract mismatch"),
                ("blockers", [], "contract mismatch"),
                ("semanticBindingStatus", "VERIFIED", "contract mismatch")):
            damaged = copy.deepcopy(result)
            damaged[key] = value
            resign_result(damaged)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, message):
                verify_factor_forest_artifact(damaged)

        extra = copy.deepcopy(result)
        extra["unexpected"] = True
        resign_result(extra)
        with self.assertRaisesRegex(ValueError, "top-level shape"):
            verify_factor_forest_artifact(extra)

        wrong_variable = copy.deepcopy(result)
        wrong_variable["trace"][0]["eliminatedVariable"] = "wrong"
        resign_result(wrong_variable)
        with self.assertRaisesRegex(ValueError, "trace replay mismatch"):
            verify_factor_forest_artifact(wrong_variable)

        metric_mutation = copy.deepcopy(result)
        metric_mutation["resourceUsage"]["peakLiveNodes"] += 1
        with self.assertRaisesRegex(ValueError, "commitment mismatch"):
            verify_factor_forest_artifact(metric_mutation)

        # A fully valid, re-signed multi-root artifact can satisfy all structural
        # commitments while encoding the wrong semantics. The source-aware tiny
        # replay must reject it, while an over-budget replay must stay INCOMPLETE.
        manager = MDDManager(variables)
        wrong_relation = manager.to_artifact({
            row["name"]: manager.true for row in result["residualFactors"]})
        substituted = copy.deepcopy(result)
        substituted["relation"] = wrong_relation
        substituted["remainingSupport"] = []
        for row in substituted["residualFactors"]:
            row["root"] = wrong_relation["roots"][row["name"]]
        resign_result(substituted)
        self.assertEqual("PASS",
                         verify_factor_forest_artifact(substituted)["status"])
        with self.assertRaisesRegex(ValueError, "semantic replay mismatch"):
            verify_factor_forest_result(
                substituted, variables, factors, (0,), max_replay_cells=4)
        incomplete = verify_factor_forest_result(
            substituted, variables, factors, (0,), max_replay_cells=1)
        self.assertEqual("INCOMPLETE", incomplete["status"])


if __name__ == "__main__":
    unittest.main()
