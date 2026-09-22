import copy
import unittest

from verify_b01_local import physical_local


class B01LocalProjectionTest(unittest.TestCase):
    def setUp(self):
        owner = {"source": "compiled occurrence"}
        state = {"execType": "CP", "output": "LOUT", "fType": None,
                 "shapeDependent": False}
        inputs = [{"presence": "ABSENT_LOCAL", "fType": None}]
        self.catalog = {"nodes": [{"occurrence": owner, "valueVersion": {"ordinal": 1},
                                   "sourceHop": {"operation": "plus"},
                                   "emittedWork": True}], "orderedInputs": [],
                        "logicalInputs": []}
        self.e = {"source": "E", "choices": [{"occurrence": owner, "state": state,
                    "authority": "CAPTURED_RULE", "anchor": None, "relocation": None,
                    "derivedFout": None, "candidateRule": {"parentOccurrence": owner,
                    "orderedInputs": inputs}, "candidateEmission": {"emissionState": {
                    "placementState": state, "derivedFedFout": False}},
                    "inputAuthorities": [{"kind": "NATIVE_LOCAL", "inputPosition": 0,
                    "expectedFType": None, "sourceDecision": None,
                    "relocationAction": None}]}]}
        self.p = {"source": "P", "choices": [{"occurrence": owner, "state": state}],
                  "candidateReceipts": [{"rule": {"parentOccurrence": owner,
                  "orderedInputs": inputs}, "emission": {"emissionState": {
                  "placementState": state, "derivedFedFout": False}},
                  "fallbackMaterializations": []}], "relocationReceipts": []}

    def test_local_p_and_e_have_one_key(self):
        self.assertEqual(physical_local(self.e, self.catalog),
                         physical_local(self.p, self.catalog))

    def test_nonlocal_input_cannot_be_silently_projected(self):
        changed = copy.deepcopy(self.e)
        changed["choices"][0]["inputAuthorities"][0]["kind"] = "RELOCATION"
        with self.assertRaisesRegex(ValueError, "nonlocal input authority"):
            physical_local(changed, self.catalog)

    def test_missing_p_authority_fails(self):
        changed = copy.deepcopy(self.p)
        changed["candidateReceipts"] = []
        with self.assertRaisesRegex(ValueError, "candidate authority incomplete"):
            physical_local(changed, self.catalog)


if __name__ == "__main__":
    unittest.main()
