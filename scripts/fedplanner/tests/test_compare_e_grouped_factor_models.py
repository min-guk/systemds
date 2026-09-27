import copy
import itertools
import unittest

from compare_e_grouped_factor_models import compare


def _pair():
    statuses = ("ALLOW", "REJECT", "UNKNOWN")
    pairs = list(itertools.product(statuses, repeat=2))
    old = {"schema": "closed-e-native-model-artifact-v1", "cell": "tiny",
           "programSha256": "p", "sourceIdentity": {"nodes": []},
           "domains": [{"index": 0, "alternatives": list(range(len(pairs)))}],
           "physicalProjectionContract": "tiny", "physicalProjection": {},
           "factors": [{"scope": [0], "cells": str(len(pairs)),
                        "truth": [pair[index] for pair in pairs]}
                       for index in range(2)]}
    new = copy.deepcopy(old)
    new["schema"] = "closed-e-native-model-artifact-v2"
    new["nativeFactorCount"] = 2
    new["materializedFactorCount"] = 1
    new["sourceFactorScopes"] = [[0], [0]]
    new["factors"] = [{"scope": [0], "cells": str(len(pairs)),
                       "sourceFactorIndices": [0, 1],
                       "truth": [("REJECT" if "REJECT" in pair else
                                  "UNKNOWN" if "UNKNOWN" in pair else "ALLOW")
                                 for pair in pairs]}]
    return old, new


class GroupedFactorDifferentialTest(unittest.TestCase):
    def test_two_grouped_captures_compare_full_truth_tables(self):
        _, old = _pair()
        old["sourceIdentity"]["logicalInputs"] = [{"source": "a"}, {"source": "b"}]
        new = copy.deepcopy(old)
        new["sourceIdentity"]["logicalInputs"].reverse()
        self.assertEqual("9", compare(old, new)["groupedTruthCellsChecked"])
        self.assertFalse(compare(old, new)["sourceIdentityLogicalInputOrderSame"])
        new["physicalProjection"]["nodeOrder"] = [1, 0]
        self.assertEqual(["nodeOrder"],
                         compare(old, new)["physicalProjectionDifferentKeys"])
        new["factors"][0]["truth"][0] = "UNKNOWN"
        with self.assertRaisesRegex(ValueError, "truth differs"):
            compare(old, new)
        new = copy.deepcopy(old)
        new["factors"][0]["sourceFactorIndices"] = [1, 0]
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            compare(old, new)
        new = copy.deepcopy(old)
        new["sourceIdentity"]["logicalInputs"][0]["source"] = "forged"
        with self.assertRaisesRegex(ValueError, "static sourceIdentity"):
            compare(old, new)

    def test_all_nine_tri_state_pairs_and_mutation(self):
        old, new = _pair()
        self.assertEqual({"nativeFactors": 2, "groupedFactors": 1,
                          "groupedTruthCellsChecked": "9",
                          "physicalProjectionContractSame": True,
                          "physicalProjectionSame": True,
                          "physicalProjectionDifferentKeys": []}, compare(old, new))
        new["factors"][0]["truth"][5] = "ALLOW"
        with self.assertRaisesRegex(ValueError, "truth differs"):
            compare(old, new)

    def test_three_source_conjunction_all_27_cases(self):
        old, new = _pair()
        triples = list(itertools.product(("ALLOW", "REJECT", "UNKNOWN"), repeat=3))
        old["domains"][0]["alternatives"] = list(range(len(triples)))
        old["factors"] = [{"scope": [0], "cells": "27",
                           "truth": [triple[index] for triple in triples]}
                          for index in range(3)]
        new["domains"] = copy.deepcopy(old["domains"])
        new["nativeFactorCount"] = 3
        new["sourceFactorScopes"] = [[0]] * 3
        new["factors"] = [{"scope": [0], "cells": "27",
                           "sourceFactorIndices": [0, 1, 2],
                           "truth": [("REJECT" if "REJECT" in triple else
                                      "UNKNOWN" if "UNKNOWN" in triple else "ALLOW")
                                     for triple in triples]}]
        self.assertEqual("27", compare(old, new)["groupedTruthCellsChecked"])

    def test_missing_duplicate_scope_and_static_drift_fail_closed(self):
        old, new = _pair()
        new["factors"][0]["sourceFactorIndices"] = [0]
        new["factors"][0]["truth"] = old["factors"][0]["truth"][:]
        with self.assertRaisesRegex(ValueError, "omits"):
            compare(old, new)
        old, new = _pair()
        new["factors"].append(copy.deepcopy(new["factors"][0]))
        new["materializedFactorCount"] = 2
        with self.assertRaisesRegex(ValueError, "partition invalid"):
            compare(old, new)
        old, new = _pair()
        new["domains"][0]["alternatives"][0] = "forged"
        with self.assertRaisesRegex(ValueError, "static domains"):
            compare(old, new)
        old, new = _pair()
        new["physicalProjection"]["nodeOrder"] = [1, 0]
        result = compare(old, new)
        self.assertFalse(result["physicalProjectionSame"])
        self.assertEqual(["nodeOrder"], result["physicalProjectionDifferentKeys"])
        old, new = _pair()
        new["factors"][0]["cells"] = "40000001"
        with self.assertRaisesRegex(ValueError, "resource budget"):
            compare(old, new)


if __name__ == "__main__":
    unittest.main()
