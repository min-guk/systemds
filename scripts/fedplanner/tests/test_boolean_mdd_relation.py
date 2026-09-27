import copy
import hashlib
from itertools import product
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from boolean_mdd_relation import (MDDManager, MDDResourceLimitError, Variable,
                                  canonical_artifact_bytes, validate_artifact,
                                  write_canonical_artifact)


class BooleanMDDRelationTest(unittest.TestCase):
    def setUp(self):
        self.variables = (Variable("proof", ("p0", "p1")),
                          Variable("action", ("none", "upload", "fout")),
                          Variable("worker", ("w0", "w1")))
        self.manager = MDDManager(self.variables)
        self.assignments = list(product(*(range(len(variable.values))
                                          for variable in self.variables)))

    def assert_relation(self, root, predicate):
        observed = {assignment for assignment in self.assignments
                    if self.manager.evaluate(root, assignment)}
        expected = {assignment for assignment in self.assignments if predicate(assignment)}
        self.assertEqual(expected, observed)
        self.assertEqual(len(expected), self.manager.count(root))
        self.assertEqual(not expected, self.manager.is_empty(root))
        if expected:
            self.assertEqual(min(expected), self.manager.witness(root))
        else:
            self.assertIsNone(self.manager.witness(root))

    def test_boolean_operations_match_exhaustive_truth(self):
        left = self.manager.from_predicate(lambda row: row[0] == row[2])
        right = self.manager.from_predicate(lambda row: row[1] != 0)
        self.assert_relation(self.manager.and_(left, right),
                             lambda row: row[0] == row[2] and row[1] != 0)
        self.assert_relation(self.manager.or_(left, right),
                             lambda row: row[0] == row[2] or row[1] != 0)
        self.assert_relation(self.manager.not_(left), lambda row: row[0] != row[2])
        self.assert_relation(self.manager.difference(left, right),
                             lambda row: row[0] == row[2] and row[1] == 0)
        self.assertTrue(self.manager.is_empty(self.manager.difference(left, left)))

    def test_all_binary_factor_relations_differentially(self):
        variables = (Variable("x", ("0", "1")), Variable("y", ("0", "1")))
        manager = MDDManager(variables)
        assignments = list(product(range(2), repeat=2))
        relations = []
        for mask in range(16):
            truth = [bool(mask & (1 << index)) for index in range(4)]
            root = manager.from_factor([0, 1], truth)
            self.assertEqual(truth, [manager.evaluate(root, row) for row in assignments])
            relations.append((truth, root))
        for left_truth, left in relations:
            self.assertEqual([not value for value in left_truth],
                             [manager.evaluate(manager.not_(left), row)
                              for row in assignments])
            expected_projection = any(left_truth)
            projected = manager.exists(left, {0, 1})
            self.assertEqual(expected_projection, manager.evaluate(projected, (0, 0)))
            for right_truth, right in relations:
                self.assertEqual([a and b for a, b in zip(left_truth, right_truth)],
                                 [manager.evaluate(manager.and_(left, right), row)
                                  for row in assignments])
                self.assertEqual([a or b for a, b in zip(left_truth, right_truth)],
                                 [manager.evaluate(manager.or_(left, right), row)
                                  for row in assignments])
                self.assertEqual([a and not b for a, b in zip(left_truth, right_truth)],
                                 [manager.evaluate(
                                     manager.directional_difference(left, right), row)
                                  for row in assignments])

    def test_duplicate_proofs_canonicalize_to_one_relation(self):
        rows = [(0, 1, 0), (0, 1, 0), (1, 2, 1), (0, 1, 0)]
        duplicated = self.manager.from_assignments(rows)
        unique = self.manager.from_assignments(sorted(set(rows)))
        self.assertEqual(unique, duplicated)
        self.assert_relation(duplicated, lambda row: row in set(rows))

    def test_correlated_or_is_not_cartesian_product(self):
        correlated = self.manager.or_(self.manager.cube({0: 0, 2: 0}),
                                      self.manager.cube({0: 1, 2: 1}))
        proof_any = self.manager.literal(0, (0, 1))
        worker_any = self.manager.literal(2, (0, 1))
        product_relation = self.manager.and_(proof_any, worker_any)
        self.assertEqual(12, self.manager.count(product_relation))
        self.assertEqual(6, self.manager.count(correlated))
        self.assertFalse(self.manager.is_empty(
            self.manager.difference(product_relation, correlated)))
        self.assert_relation(correlated, lambda row: row[0] == row[2])

    def test_shared_action_or_preserves_alternatives(self):
        proof_zero = self.manager.cube({0: 0, 1: 1})
        proof_one = self.manager.cube({0: 1, 1: 1})
        shared_upload = self.manager.or_(proof_zero, proof_one)
        self.assert_relation(shared_upload, lambda row: row[1] == 1)
        projected = self.manager.exists(shared_upload, {0})
        self.assert_relation(projected, lambda row: row[1] == 1)
        self.assertEqual(self.manager.literal(1, {1}), projected)

    def test_factor_compilation_and_projection_match_exhaustive_truth(self):
        # scope [0, 2], last scoped variable changes fastest.
        equality = self.manager.from_factor([0, 2], [True, False, False, True])
        action = self.manager.from_factor([1], [False, True, True])
        relation = self.manager.and_(equality, action)
        self.assert_relation(relation, lambda row: row[0] == row[2] and row[1] != 0)
        projected = self.manager.exists(relation, {2})
        self.assert_relation(projected, lambda row: row[1] != 0)

    def test_deterministic_serialization_and_independent_replay(self):
        root = self.manager.from_predicate(lambda row: row[0] == row[2] and row[1] != 2)
        artifact = self.manager.to_artifact({"accepted": root,
                                             "rejected": self.manager.not_(root)})

        other = MDDManager(self.variables)
        other_root = other.from_assignments(reversed([
            row for row in self.assignments if row[0] == row[2] and row[1] != 2]))
        other_artifact = other.to_artifact({"rejected": other.not_(other_root),
                                            "accepted": other_root})
        self.assertEqual(artifact, other_artifact)
        self.assertEqual(canonical_artifact_bytes(artifact),
                         canonical_artifact_bytes(other_artifact))

        replay = {
            "accepted": [(row, row[0] == row[2] and row[1] != 2)
                          for row in self.assignments],
            "rejected": [(row, not (row[0] == row[2] and row[1] != 2))
                          for row in self.assignments]}
        checked = validate_artifact(artifact, self.variables, replay)
        for row in self.assignments:
            self.assertEqual(self.manager.evaluate(root, row),
                             checked.evaluate("accepted", row))

    @staticmethod
    def resign(artifact):
        payload = {key: artifact[key] for key in
                   ("schema", "variables", "roots", "nodes")}
        artifact["artifactSha256"] = hashlib.sha256(json.dumps(
            payload, sort_keys=True, separators=(",", ":"),
            ensure_ascii=False).encode("utf-8")).hexdigest()

    def test_mutated_dictionary_fails_even_when_resigned(self):
        artifact = self.manager.to_artifact({"r": self.manager.literal(0, {0})})
        damaged = copy.deepcopy(artifact)
        damaged["variables"][0]["values"].reverse()
        self.resign(damaged)
        with self.assertRaisesRegex(ValueError, "dictionary drift"):
            validate_artifact(damaged, self.variables)

    def test_mutated_branch_is_detected_by_content_id_or_replay(self):
        root = self.manager.from_predicate(lambda row: row[0] == row[2])
        artifact = self.manager.to_artifact({"r": root})
        damaged = copy.deepcopy(artifact)
        damaged["nodes"][0]["children"].reverse()
        self.resign(damaged)
        with self.assertRaisesRegex(ValueError, "content identifier mismatch"):
            validate_artifact(damaged)

        # A semantically different, internally valid artifact needs producer-owned
        # replay facts to distinguish it from the intended relation.
        alternative = self.manager.to_artifact({
            "r": self.manager.from_predicate(lambda row: row[0] != row[2])})
        cases = {"r": [(row, row[0] == row[2]) for row in self.assignments]}
        with self.assertRaisesRegex(ValueError, "replay mismatch"):
            validate_artifact(alternative, self.variables, cases)

    def test_partial_replay_cannot_certify_resigned_semantic_substitution(self):
        intended = self.manager.from_predicate(lambda row: row[0] == row[2])
        substituted = self.manager.from_predicate(
            lambda row: row[0] == row[2] or row == (0, 2, 1))
        artifact = self.manager.to_artifact({"r": substituted})
        partial = {"r": [((0, 0, 0), True), ((1, 1, 1), True)]}
        with self.assertRaisesRegex(ValueError, "coverage is incomplete"):
            validate_artifact(artifact, self.variables, partial)
        intended_artifact = self.manager.to_artifact({"r": intended})
        with self.assertRaisesRegex(ValueError, "root commitment drift"):
            validate_artifact(artifact, self.variables,
                              expected_roots=intended_artifact["roots"])

    def test_root_commitment_cannot_certify_reordered_dictionary_alone(self):
        root = self.manager.literal(0, {0})
        artifact = self.manager.to_artifact({"r": root})
        damaged = copy.deepcopy(artifact)
        damaged["variables"][0]["values"].reverse()
        self.resign(damaged)
        # Content-addressed graph IDs do not encode category labels, so callers
        # must supply the producer-owned dictionary for semantic certification.
        self.assertEqual(artifact["roots"], damaged["roots"])
        with self.assertRaisesRegex(ValueError, "requires expected_variables"):
            validate_artifact(damaged, expected_roots=artifact["roots"])
        with self.assertRaisesRegex(ValueError, "dictionary drift"):
            validate_artifact(damaged, self.variables,
                              expected_roots=artifact["roots"])

    def test_streaming_canonical_writer_matches_bytes(self):
        artifact = self.manager.to_artifact({
            "r": self.manager.from_predicate(lambda row: row[0] == row[2])})

        class Sink:
            def __init__(self):
                self.parts = []

            def write(self, value):
                self.parts.append(value)

        sink = Sink()
        write_canonical_artifact(artifact, sink)
        self.assertGreater(len(sink.parts), 1)
        self.assertEqual(canonical_artifact_bytes(artifact), b"".join(sink.parts))

    def test_node_and_apply_pair_budgets_fail_closed(self):
        limited_nodes = MDDManager(self.variables, max_nodes=1)
        limited_nodes.literal(0, {0})
        with self.assertRaisesRegex(MDDResourceLimitError, "node budget"):
            limited_nodes.literal(1, {0})

        limited_apply = MDDManager(self.variables, max_apply_pairs=0)
        left = limited_apply.literal(0, {0})
        right = limited_apply.literal(1, {0})
        with self.assertRaisesRegex(MDDResourceLimitError, "apply-pair budget"):
            limited_apply.and_(left, right)

        zero_nodes = MDDManager(self.variables, max_nodes=0)
        with self.assertRaisesRegex(MDDResourceLimitError, "node budget"):
            zero_nodes.literal(0, {0})

    def test_foreign_manager_handles_are_rejected(self):
        other = MDDManager(self.variables)
        local = self.manager.literal(0, {0})
        foreign = other.literal(0, {0})
        with self.assertRaisesRegex(ValueError, "does not belong"):
            self.manager.directional_difference(local, foreign)
        with self.assertRaisesRegex(ValueError, "does not belong"):
            self.manager.is_empty(other.false)

    def test_compaction_preserves_roots_and_reclaims_only_unreachable_nodes(self):
        kept = self.manager.from_predicate(
            lambda row: row[0] == row[2] and row[1] != 2)
        discarded = self.manager.from_predicate(
            lambda row: row[0] != row[2] or row[1] == 2)
        before = self.manager.to_artifact({"kept": kept})
        expected = [(self.manager.evaluate(kept, row), row)
                    for row in self.assignments]
        expected_count = self.manager.count(kept)
        expected_witness = self.manager.witness(kept)
        self.assertGreater(self.manager.compact({"kept": kept}), 0)
        self.assertEqual(before, self.manager.to_artifact({"kept": kept}))
        self.assertEqual(expected,
                         [(self.manager.evaluate(kept, row), row)
                          for row in self.assignments])
        self.assertEqual(expected_count, self.manager.count(kept))
        self.assertEqual(expected_witness, self.manager.witness(kept))
        self.assertEqual(self.manager.false,
                         self.manager.directional_difference(kept, kept))
        with self.assertRaisesRegex(ValueError, "does not belong"):
            self.manager.evaluate(discarded, self.assignments[0])

    def test_compaction_validates_roots_and_can_recover_node_budget(self):
        manager = MDDManager(self.variables, max_nodes=3)
        first = manager.literal(0, {0})
        second = manager.literal(1, {0})
        third = manager.literal(2, {0})
        with self.assertRaisesRegex(MDDResourceLimitError, "node budget"):
            manager.and_(first, second)
        self.assertEqual(2, manager.compact(third))
        combined = manager.and_(third, manager.literal(0, {1}))
        self.assertTrue(manager.evaluate(combined, (1, 0, 0)))
        with self.assertRaisesRegex(ValueError, "at least one root"):
            manager.compact([])
        with self.assertRaisesRegex(ValueError, "does not belong"):
            manager.compact(MDDManager(self.variables).true)

    def test_deep_not_serialization_and_validation_are_iterative(self):
        variables = tuple(Variable("v" + str(index), ("0", "1"))
                          for index in range(1100))
        manager = MDDManager(variables)
        root = manager.cube({index: 0 for index in range(1100)})
        complement = manager.not_(root)
        self.assertEqual((1 << 1100) - 1, manager.count(complement))
        self.assertEqual(manager.true, manager.exists(root, set(range(1100))))
        self.assertEqual(manager.true, manager.or_(root, complement))
        self.assertEqual(manager.false,
                         manager.directional_difference(root, root))
        artifact = manager.to_artifact({"r": complement})
        checked = validate_artifact(artifact, variables,
                                    expected_roots=artifact["roots"])
        self.assertFalse(checked.evaluate("r", (0,) * 1100))
        assignment = [0] * 1100
        assignment[-1] = 1
        self.assertTrue(checked.evaluate("r", assignment))

    def test_all_coordinates_are_validated_for_terminal_and_skipped_roots(self):
        skipped = self.manager.literal(2, {0})
        artifact = self.manager.to_artifact({"terminal": self.manager.true,
                                             "skipped": skipped})
        checked = validate_artifact(artifact)
        for root in (self.manager.true, skipped):
            with self.assertRaisesRegex(ValueError, "category index"):
                self.manager.evaluate(root, (99, 0, 0))
        with self.assertRaisesRegex(ValueError, "category index"):
            checked.evaluate("terminal", (0, 99, 0))
        with self.assertRaisesRegex(ValueError, "category index"):
            checked.evaluate("skipped", (99, 0, 0))

    def test_rejects_unreachable_and_duplicate_nodes(self):
        root = self.manager.from_predicate(lambda row: row[0] == row[2])
        artifact = self.manager.to_artifact({"r": root})
        damaged = copy.deepcopy(artifact)
        extra = copy.deepcopy(damaged["nodes"][0])
        extra["id"] = "n_" + "0" * 64
        damaged["nodes"].append(extra)
        damaged["nodes"].sort(key=lambda row: row["id"])
        self.resign(damaged)
        with self.assertRaisesRegex(ValueError, "content identifier mismatch|duplicate"):
            validate_artifact(damaged)

    def test_validation_rejects_bad_scope_and_assignments(self):
        with self.assertRaisesRegex(ValueError, "scope"):
            self.manager.from_factor([2, 0], [True] * 4)
        with self.assertRaisesRegex(ValueError, "truth table"):
            self.manager.from_factor([0], [True])
        with self.assertRaisesRegex(ValueError, "wrong width"):
            self.manager.evaluate(self.manager.true, (0,))


if __name__ == "__main__":
    unittest.main()
