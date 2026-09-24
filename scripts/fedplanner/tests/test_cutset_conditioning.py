import random
from itertools import product
from math import prod
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from cutset_conditioning import (condition_factor, condition_terms,
                                 cutset_assignments, normalize_cutset)
from exact_e_physical_relation import factor_status


class CutsetConditioningTest(unittest.TestCase):
    def test_cutset_validation_and_assignment_budget(self):
        self.assertEqual(((2, 0), 6), normalize_cutset(
            [0, 2], [2, 1, 3], [2, 0], 6))
        self.assertEqual([{2: 0, 0: 0}, {2: 0, 0: 1},
                          {2: 1, 0: 0}, {2: 1, 0: 1},
                          {2: 2, 0: 0}, {2: 2, 0: 1}],
                         list(cutset_assignments((2, 0), [2, 1, 3])))
        for cutset in ((), (0, 0), (1,), (3,)):
            with self.assertRaises(ValueError):
                normalize_cutset(cutset, [2, 1, 3], [2, 0], 6)
        with self.assertRaisesRegex(ValueError, "assignment budget"):
            normalize_cutset((0, 2), [2, 1, 3], [2, 0], 5)

    def test_random_factor_conditioning_matches_original_table(self):
        generator = random.Random(918273)
        for _ in range(300):
            radices = [generator.randint(2, 3) for _ in range(4)]
            scope = tuple(sorted(generator.sample(range(4),
                                                  generator.randint(1, 4))))
            truth = tuple(generator.choice(("ALLOW", "REJECT", "UNKNOWN"))
                          for _ in range(prod(
                              radices[domain] for domain in scope)))
            cutset = tuple(sorted(generator.sample(
                range(4), generator.randint(1, 2))))
            for fixed in cutset_assignments(cutset, radices):
                residual_scope, conditioned = condition_factor(
                    scope, truth, fixed, radices)
                for residual_values in product(*(
                        range(radices[domain]) for domain in residual_scope)):
                    assignment = [0] * 4
                    for domain, value in fixed.items():
                        assignment[domain] = value
                    for domain, value in zip(residual_scope, residual_values):
                        assignment[domain] = value
                    self.assertEqual(
                        factor_status(scope, truth, assignment, radices),
                        factor_status(residual_scope, conditioned, assignment,
                                      radices))

    def test_condition_terms_handles_impossible_true_and_duplicate_rows(self):
        rows = (((0, 0), (1, 1)), ((0, 1), (1, 0)),
                ((0, 1), (1, 0)))
        self.assertEqual(("DYNAMIC", (((1, 1),),)),
                         condition_terms(rows, {0: 0}))
        self.assertEqual(("DYNAMIC", (((1, 0),),)),
                         condition_terms(rows, {0: 1}))
        self.assertEqual(("TRUE", ()), condition_terms(rows, {0: 0, 1: 1}))
        self.assertEqual(("FALSE", ()), condition_terms(rows, {0: 0, 1: 0}))


if __name__ == "__main__":
    unittest.main()
