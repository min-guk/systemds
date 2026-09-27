import hashlib
import unittest
from pathlib import Path

from scripts.fedplanner.check_p_fout_materialization_reachability import (
    check, evaluate_verified_domain,
)
from scripts.fedplanner.verify_p_model_artifact import encode_java_length_fields


ROOT = Path(__file__).resolve().parents[3]
MODEL = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
             "current-pe-campaign-v12-ledger-aware/p-models/"
             "cell_capture_de25f5d592f6ab3a1877/p-model.json.gz")
MODEL_SHA256 = "75861eae0e3806d82716366c72924086f716ad4bc25b7c1c1ae740b0d6e142cf"


def fields(values):
    return encode_java_length_fields(values)


def fixture():
    producer, anchor = "producer", "anchor"
    source = "CP/LOUT/-/SHAPE_INDEPENDENT"
    target = "CP/FOUT/BROADCAST/SHAPE_INDEPENDENT"
    anchor_state = "FED/FOUT/ROW/SHAPE_INDEPENDENT"
    rule = producer + "|inputs=[]"
    action = fields((producer, "value", rule, source, target, "durable",
                     anchor, "ROW", "BROADCAST", "scope"))
    source_ref, target_ref = "source-ref", "target-ref"
    source_receipt = "source-receipt"
    target_receipt = "target-receipt"
    domain = {
        "placementDomains": [[producer, [target, source]],
                             [anchor, [anchor_state, "FED/FOUT/COL/SHAPE_INDEPENDENT"]]],
        "candidateDomains": [[producer, [source_receipt, target_receipt]],
                             [anchor, []]],
        "candidateReceiptSemanticFacts": [
            {"coordinateIndex": 0, "candidateIndex": 0, "owner": producer,
             "rule": rule, "placement": source,
             "emission": source + "|derivedFedFout=false|executionFType=-|derivedAction=-",
             "reference": source_ref, "derivedFoutAction": "-"},
            {"coordinateIndex": 0, "candidateIndex": 1, "owner": producer,
             "rule": rule, "placement": target,
             "emission": target + "|derivedFedFout=false|executionFType=-|derivedAction=" + action,
             "reference": target_ref, "derivedFoutAction": action},
        ],
        "candidateRealizationReferenceFacts": [
            {"reference": source_ref}, {"reference": target_ref}],
        "candidateRealizationClauseInventory": [
            {"reference": source_ref}, {"reference": target_ref}],
        "candidateRuleFactInventory": [{
            "ruleSignature": hashlib.sha256(rule.encode()).hexdigest(),
            "status": "AVAILABLE", "failure": "", "capabilityPresent": True,
            "profileAvailable": True,
            "emissions": [
                {"selection": source +
                 "|derivedFedFout=false|executionFType=-|derivedAction=-",
                 "realizations": ["source"]},
                {"selection": target +
                 "|derivedFedFout=false|executionFType=-|derivedAction=" + action,
                 "realizations": ["target"]},
            ],
        }],
        "derivedFoutActions": [action],
        "derivedFoutOwnershipBindings": [[0, 1, 0]],
    }
    return domain, [0, 0], [2, 0]


class TestFoutMaterializationReachability(unittest.TestCase):
    def test_required_cp_fout_passes(self):
        domain, placements, candidates = fixture()
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, result["requiredMaterializationCount"])
        self.assertEqual(1, result["checkedMaterializationCount"])
        self.assertFalse(result["completePAcceptance"])
        self.assertIsNone(result["javaExceptionClass"])

    def test_derived_fed_fout_requires_action(self):
        domain, placements, candidates = fixture()
        row = domain["candidateReceiptSemanticFacts"][1]
        old = domain["derivedFoutActions"][0]
        action_parts = _decode(old)
        action_parts[3] = "FED/LOUT/BROADCAST/SHAPE_INDEPENDENT"
        action_parts[4] = "FED/FOUT/BROADCAST/SHAPE_INDEPENDENT"
        new = fields(action_parts)
        _replace_action(domain, old, new)
        domain["placementDomains"][0][1][0] = action_parts[4]
        domain["placementDomains"][0][1][1] = action_parts[3]
        source_row = domain["candidateReceiptSemanticFacts"][0]
        source_row["placement"] = action_parts[3]
        source_row["emission"] = action_parts[3] + \
            "|derivedFedFout=false|executionFType=BROADCAST|derivedAction=-"
        row["placement"] = action_parts[4]
        row["emission"] = action_parts[4] + \
            "|derivedFedFout=true|executionFType=BROADCAST|derivedAction=" + new
        inventory = domain["candidateRuleFactInventory"][0]["emissions"]
        inventory[0]["selection"] = source_row["emission"]
        inventory[1]["selection"] = row["emission"]
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, result["requiredMaterializationCount"])

    def test_unrequired_receipt_must_not_carry_action(self):
        domain, _, _ = fixture()
        row = domain["candidateReceiptSemanticFacts"][0]
        action = domain["derivedFoutActions"][0]
        row["derivedFoutAction"] = action
        row["emission"] = row["emission"].rsplit("|derivedAction=", 1)[0] + \
            "|derivedAction=" + action
        domain["derivedFoutOwnershipBindings"] = [[0, 0, 0], [0, 1, 0]]
        result = evaluate_verified_domain(domain, [1, 0], [1, 0])
        self.assertEqual("FAIL", result["verdict"])
        self.assertEqual("UNREQUIRED_MATERIALIZATION_ACTION_PRESENT", result["reason"])

    def test_required_action_absent_fails(self):
        domain, placements, candidates = fixture()
        row = domain["candidateReceiptSemanticFacts"][1]
        row["derivedFoutAction"] = "-"
        row["emission"] = row["emission"].rsplit("|derivedAction=", 1)[0] + \
            "|derivedAction=-"
        domain["derivedFoutOwnershipBindings"] = []
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("REQUIRED_MATERIALIZATION_ACTION_ABSENT", result["reason"])

    def test_action_producer_rule_and_target_are_exact(self):
        for part, reason in ((0, "MATERIALIZATION_ACTION_IDENTITY_DIFFERS"),
                             (2, "MATERIALIZATION_ACTION_IDENTITY_DIFFERS"),
                             (4, "MATERIALIZATION_ACTION_IDENTITY_DIFFERS")):
            with self.subTest(part=part):
                domain, placements, candidates = fixture()
                old = domain["derivedFoutActions"][0]
                action_parts = _decode(old)
                action_parts[part] = ("other" if part != 4 else
                                      "CP/FOUT/ROW/SHAPE_INDEPENDENT")
                if part == 4:
                    action_parts[8] = "ROW"
                new = fields(action_parts)
                _replace_action(domain, old, new)
                result = evaluate_verified_domain(domain, placements, candidates)
                self.assertEqual(reason, result["reason"])

    def test_same_rule_action_free_source_is_required(self):
        domain, placements, candidates = fixture()
        inventory = domain["candidateRuleFactInventory"][0]
        inventory["emissions"] = inventory["emissions"][1:]
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("ACTION_SOURCE_EMISSION_UNAVAILABLE", result["reason"])

    def test_source_emission_need_not_have_a_receipt(self):
        domain, placements, candidates = fixture()
        domain["candidateReceiptSemanticFacts"][0]["rule"] = \
            "producer|inputs=[PRESENT:ROW]"
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("PASS", result["verdict"])

    def test_source_must_be_available(self):
        domain, placements, candidates = fixture()
        domain["candidateRealizationReferenceFacts"] = [
            {"reference": "target-ref"}]
        domain["candidateRealizationClauseInventory"] = [
            {"reference": "target-ref"}]
        with self.assertRaisesRegex(ValueError, "semantic authority"):
            evaluate_verified_domain(domain, placements, candidates)

    def test_anchor_uses_owner_ftype_not_target_ftype(self):
        domain, placements, candidates = fixture()
        old = domain["derivedFoutActions"][0]
        action_parts = _decode(old)
        self.assertEqual("ROW", action_parts[7])
        self.assertEqual("BROADCAST", action_parts[8])
        action_parts[7] = "BROADCAST"
        new = fields(action_parts)
        _replace_action(domain, old, new)
        result = evaluate_verified_domain(domain, placements, candidates)
        self.assertEqual("ANCHOR_OWNER_ASSIGNMENT_INCOMPATIBLE", result["reason"])

    def test_anchor_must_currently_be_fout(self):
        domain, placements, candidates = fixture()
        domain["placementDomains"][1][1].append(
            "FED/LOUT/ROW/SHAPE_INDEPENDENT")
        result = evaluate_verified_domain(domain, [0, 2], candidates)
        self.assertEqual("ANCHOR_OWNER_ASSIGNMENT_INCOMPATIBLE", result["reason"])

    def test_action_inventory_and_ownership_fail_closed(self):
        domain, placements, candidates = fixture()
        domain["derivedFoutActions"].append(domain["derivedFoutActions"][0])
        with self.assertRaisesRegex(ValueError, "action inventory"):
            evaluate_verified_domain(domain, placements, candidates)
        domain, placements, candidates = fixture()
        domain["derivedFoutOwnershipBindings"] = []
        with self.assertRaisesRegex(ValueError, "ownership binding"):
            evaluate_verified_domain(domain, placements, candidates)

    def test_budgets_return_unknown_before_detail(self):
        domain, placements, candidates = fixture()
        result = evaluate_verified_domain(
            domain, placements, candidates, max_candidate_rows=1)
        self.assertEqual("UNKNOWN", result["verdict"])
        self.assertEqual("CANDIDATE_ROW_BUDGET_EXHAUSTED", result["reason"])
        result = evaluate_verified_domain(
            domain, placements, candidates, max_action_records=1)
        self.assertEqual("ACTION_RECORD_BUDGET_EXHAUSTED", result["reason"])
        result = evaluate_verified_domain(
            domain, placements, candidates, max_rule_emissions=1)
        self.assertEqual("RULE_EMISSION_BUDGET_EXHAUSTED", result["reason"])
        result = evaluate_verified_domain(
            domain, placements, candidates, max_rule_rows=1)
        self.assertEqual("PASS", result["verdict"])
        domain["candidateRuleFactInventory"].append({
            "ruleSignature": "other", "status": "RULE_ERROR", "failure": "x",
            "capabilityPresent": False, "profileAvailable": False,
            "emissions": [],
        })
        result = evaluate_verified_domain(
            domain, placements, candidates, max_rule_rows=1)
        self.assertEqual("RULE_ROW_BUDGET_EXHAUSTED", result["reason"])

    @unittest.skipUnless(MODEL.is_file(), "frozen nonvacuous P model unavailable")
    def test_real_nonvacuous_verified_model_passes(self):
        placements = [0] * 41
        candidates = [0] * 41
        candidates[6] = 2
        result = check(MODEL, MODEL_SHA256, placements, candidates)
        self.assertEqual("PASS", result["verdict"])
        self.assertEqual(1, result["requiredMaterializationCount"])
        self.assertEqual(1, result["checkedMaterializationCount"])
        self.assertEqual(MODEL_SHA256, result["modelSha256"])
        self.assertEqual("VERIFIED_P_V2_SINGLE_ACCEPTANCE_LEAF_ONLY",
                         result["claimScope"])

    @unittest.skipUnless(MODEL.is_file(), "frozen nonvacuous P model unavailable")
    def test_real_model_sha_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            check(MODEL, "0" * 64, [0] * 41, [0] * 41)


def _decode(value):
    result, cursor = [], 0
    for index in range(10):
        colon = value.index(":", cursor)
        size = int(value[cursor:colon])
        start = colon + 1
        result.append(value[start:start + size])
        cursor = start + size + (index != 9)
    return result


def _replace_action(domain, old, new):
    domain["derivedFoutActions"] = [new if value == old else value
                                    for value in domain["derivedFoutActions"]]
    for row in domain["candidateReceiptSemanticFacts"]:
        if row["derivedFoutAction"] == old:
            row["derivedFoutAction"] = new
            row["emission"] = row["emission"].replace(old, new)
    for rule in domain["candidateRuleFactInventory"]:
        for emission in rule["emissions"]:
            emission["selection"] = emission["selection"].replace(old, new)


if __name__ == "__main__":
    unittest.main()
