from itertools import product
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from boolean_mdd_relation import MDDManager, Variable
from terminal_aware_order import (TerminalOrderResourceLimitError,
                                  plan_terminal_aware_order,
                                  terminal_aware_order)


class TerminalAwareOrderTest(unittest.TestCase):
    def test_exact_terminal_scope_score_is_deterministic_and_complete(self):
        radices = (2, 3, 2, 1)
        factors = ((0, 1), (1, 2))
        atoms = {"a": (0,), "b": (0, 1), "c": (1, 2), "d": (2,)}
        first = plan_terminal_aware_order(radices, factors, atoms, (0, 1, 2))
        second = plan_terminal_aware_order(radices, reversed(factors),
                                           dict(reversed(tuple(atoms.items()))),
                                           (0, 1, 2))
        self.assertEqual(first, second)
        self.assertEqual({0, 1, 2}, set(first["order"]))
        self.assertEqual(3, len(first["order"]))
        self.assertEqual(tuple(first["order"]),
                         terminal_aware_order(radices, factors, atoms,
                                              (0, 1, 2)))
        self.assertTrue(all(int(row["bagCells"]) >= 1 for row in first["trace"]))
        self.assertGreaterEqual(first["peakScopeSymbols"], 1)

    def test_terminal_frontier_changes_the_native_only_tie(self):
        radices = (2, 2, 2)
        # Native scopes are symmetric, but eliminating domain 0 opens three
        # immortal terminals while domain 2 opens only one.
        factors = ((0, 1), (1, 2))
        atoms = {"a0": (0,), "a1": (0,), "a2": (0,), "z": (2,)}
        order = terminal_aware_order(radices, factors, atoms, (0, 1, 2))
        self.assertLess(order.index(2), order.index(0))
        self.assertEqual({0, 1, 2}, set(order))

    def test_validation_and_heuristic_budgets_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "uniquely cover"):
            terminal_aware_order((2, 2), (), {}, (0, 0))
        with self.assertRaisesRegex(ValueError, "component-local"):
            terminal_aware_order((2, 2), (), {"a": (0, 1)}, (0,))
        with self.assertRaisesRegex(TerminalOrderResourceLimitError,
                                    "operation budget"):
            terminal_aware_order((2, 2, 2), ((0, 1), (1, 2)),
                                 {"a": (0, 2)}, (0, 1, 2), max_operations=1)
        with self.assertRaisesRegex(TerminalOrderResourceLimitError,
                                    "scope-symbol budget"):
            terminal_aware_order((2, 2), ((0, 1),), {"a": (0, 1)}, (0, 1),
                                 max_scope_symbols=2)

    def test_dense_existing_pair_traversal_is_charged(self):
        radices = (2,) * 6
        scope = (tuple(range(6)),)
        plan = plan_terminal_aware_order(radices, scope, {}, tuple(range(6)))
        self.assertGreater(plan["operations"], 6 * 15)
        with self.assertRaisesRegex(TerminalOrderResourceLimitError,
                                    "operation budget"):
            plan_terminal_aware_order(radices, scope, {}, tuple(range(6)),
                                      max_operations=200)

    @staticmethod
    def image(order):
        variables = [Variable("native:%d" % domain, ("0", "1"))
                     for domain in order]
        variables += [Variable("atom:a", ("0", "1")),
                      Variable("atom:b", ("0", "1"))]
        manager = MDDManager(variables)
        native_level = {domain: index for index, domain in enumerate(order)}
        atom_a, atom_b = len(order), len(order) + 1

        def predicate(row):
            native = {domain: row[level] for domain, level in native_level.items()}
            accepted = native[0] == native[1] or native[2] == 1
            expected_a = native[0] == 1
            expected_b = native[1] != native[2]
            return accepted and row[atom_a] == expected_a and row[atom_b] == expected_b

        relation = manager.from_predicate(predicate)
        image = manager.exists(relation, native_level.values())
        return {bits for bits in product((0, 1), repeat=2)
                if manager.evaluate(image, (0,) * len(order) + bits)}

    def test_exhaustive_image_is_invariant_under_terminal_aware_order(self):
        radices = (2, 2, 2)
        factors = ((0, 1), (2,))
        atoms = {"a": (0,), "b": (1, 2)}
        left = terminal_aware_order(radices, factors, atoms, (0, 1, 2))
        right = terminal_aware_order(radices, factors, atoms, (2, 1, 0))
        brute = set()
        for native in product((0, 1), repeat=3):
            if native[0] == native[1] or native[2] == 1:
                brute.add((int(native[0] == 1), int(native[1] != native[2])))
        self.assertEqual(brute, self.image(left))
        self.assertEqual(brute, self.image(right))


if __name__ == "__main__":
    unittest.main()
