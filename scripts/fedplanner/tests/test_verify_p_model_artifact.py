import gzip
import hashlib
import json
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.fedplanner import verify_p_model_artifact as p_verifier
from scripts.fedplanner.verify_p_model_artifact import (
    OPAQUE_ACCEPTANCE_PREDICATES, acceptance_coverage,
    artifact_assessed_predicates, candidate_assignment_locally_compatible,
    candidate_derived_fout_action, canonical,
    decode_java_length_fields, source_order, verify,
    verify_candidate_privacy_closure, verify_derived_fout_graph_ownership,
    verify_candidate_assignment_primitives,
    verify_candidate_receipt_semantics_and_source_links,
    verify_relocation_receipt_semantics_and_action_links,
    verify_nondecision_candidate_owner_classification,
)


class PModelArtifactVerificationTest(unittest.TestCase):
    @staticmethod
    def privacy_emission(signature, exec_type, output, ftype="-", derived=False, source="-"):
        return {"signature": signature,
                "placement": "/".join((exec_type, output, ftype, "SHAPE_INDEPENDENT")),
                "exec": exec_type, "output": output, "fType": ftype,
                "executionFType": "-", "derivedFedFout": derived,
                "derivedSourcePlacement": source}

    @staticmethod
    def privacy_rule(emissions, output_emissions, privacy="PUBLIC", status="AVAILABLE",
                     output_status="AVAILABLE", failure="", output_failure="", **updates):
        row = {"ruleSignature": "rule", "inputStatus": status, "inputFailure": failure,
               "capabilityPresent": True, "profileAvailable": True,
               "capabilityExec": "CP", "capabilityOutput": "LOUT",
               "effectivePrivacy": privacy, "matrix": True, "federatedSource": False,
               "printOrPwrite": False, "dmlFunctionPlaceholder": False,
               "multiReturnBoundary": False, "dataOp": "-", "inputPresence": [],
               "protectedPayloadPositions": [], "inputEmissions": emissions,
               "outputStatus": output_status, "outputFailure": output_failure,
               "outputEmissionSignatures": output_emissions}
        row.update(updates)
        return row

    @staticmethod
    def privacy_domain(rule):
        return {"candidatePrivacyClosurePasses": [{"ordinal": 0, "rules": [rule]}],
                "candidateRuleFactInventory": [{
                    "ruleSignature": rule["ruleSignature"], "status": rule["outputStatus"],
                    "failure": rule["outputFailure"],
                    "capabilityPresent": rule["capabilityPresent"],
                    "profileAvailable": rule["profileAvailable"],
                    "emissions": rule["outputEmissionSignatures"]}]}

    def test_candidate_privacy_closure_exhaustive_tiny_fixtures_and_mutations(self):
        cp = self.privacy_emission("cp", "CP", "LOUT")
        cp_fout = self.privacy_emission("cpf", "CP", "FOUT", "ROW")
        fed = self.privacy_emission("fed", "FED", "FOUT", "ROW")
        fed_lout = self.privacy_emission("fedl", "FED", "LOUT", "ROW")
        derived = self.privacy_emission("derived", "FED", "FOUT", "ROW", True,
                                        fed_lout["placement"])
        cases = [
            self.privacy_rule([cp, cp_fout], ["cp", "cpf"]),
            self.privacy_rule([cp, fed], ["fed"], privacy="PRIVATE",
                              capabilityExec="FED", capabilityOutput="FOUT"),
            self.privacy_rule([cp], [], privacy="PRIVATE", output_status="PRIVACY_EXCLUDED",
                              output_failure="PRIVACY:PRIVATE"),
            self.privacy_rule([cp, fed], ["fed"], inputPresence=[True],
                              protectedPayloadPositions=[0], capabilityExec="FED",
                              capabilityOutput="FOUT"),
            self.privacy_rule([fed_lout, derived], ["fedl", "derived"],
                              capabilityExec="FED", capabilityOutput="LOUT"),
            self.privacy_rule([derived], [], capabilityExec="FED", capabilityOutput="LOUT",
                              output_status="PRIVACY_EXCLUDED",
                              output_failure="PRIVACY:PUBLIC"),
            self.privacy_rule([], [], status="RULE_ERROR", failure="RULE:bad",
                              output_status="RULE_ERROR", output_failure="RULE:bad",
                              capabilityPresent=False, profileAvailable=False),
        ]
        for rule in cases:
            with self.subTest(rule=rule):
                self.assertTrue(verify_candidate_privacy_closure(self.privacy_domain(rule)))
        mutated = self.privacy_domain(cases[1])
        mutated["candidatePrivacyClosurePasses"][0]["rules"][0][
            "outputEmissionSignatures"] = ["cp", "fed"]
        with self.assertRaisesRegex(ValueError, "replay differs"):
            verify_candidate_privacy_closure(mutated)
        inventory_mutated = self.privacy_domain(cases[-1])
        inventory_mutated["candidateRuleFactInventory"][0]["capabilityPresent"] = True
        with self.assertRaisesRegex(ValueError, "final inventory differs"):
            verify_candidate_privacy_closure(inventory_mutated)
        missing = self.privacy_domain(cases[0])
        missing["candidatePrivacyClosurePasses"][0]["rules"][0].pop("effectivePrivacy")
        with self.assertRaisesRegex(ValueError, "contract differs"):
            verify_candidate_privacy_closure(missing)

        privacy_evidence = self.privacy_domain(copy.deepcopy(cases[2]))
        assessed = artifact_assessed_predicates(privacy_evidence)
        self.assertIn("SERIALIZED_CANDIDATE_PRIVACY_TRANSFORMATION", assessed)
        self.assertNotIn("CANDIDATE_RULE_STATUS_AND_PRIVACY_FILTERING", assessed)
        coverage_domain = privacy_evidence
        coverage_domain.update({"constraints": [], "nodes": [],
                                "placementDomains": []})
        coverage = acceptance_coverage(coverage_domain, assessed)
        self.assertIn("CANDIDATE_RULE_STATUS_AND_PRIVACY_FILTERING",
                      coverage["opaquePredicates"])
        self.assertIn("CANDIDATE_RULE_STATUS_AND_PRIVACY_FILTERING",
                      OPAQUE_ACCEPTANCE_PREDICATES)

    @staticmethod
    def java_token(value):
        units = len(value.encode("utf-16-le")) // 2
        return f"{units}:{value}"

    @classmethod
    def candidate(cls, action):
        emission = "state|executionFType=-|derivedAction=" + (action or "-")
        return "|".join(cls.java_token(value) for value in
                        ("rule", emission, "realization", "clause", ""))

    @classmethod
    def fields(cls, *values):
        return "|".join(cls.java_token(value) for value in values)

    @classmethod
    def semantic_candidate_domain(cls):
        source_owner, owner = "source-owner", "consumer-owner"
        source_placement = "FED/FOUT/ROW/SHAPE_INDEPENDENT"
        placement = "FED/FOUT/ROW/SHAPE_INDEPENDENT"
        source_rule, rule = "source-rule", "consumer-rule"
        source_emission_state = source_placement + "|derivedFedFout=false"
        emission_state = placement + "|derivedFedFout=false"
        source_realization = cls.fields(source_emission_state, "NATIVE_LINEAGE", "-", "source")
        realization = cls.fields(emission_state, "NATIVE_LINEAGE", "-", "consumer")
        source_reference = cls.fields(source_rule, source_realization)
        reference = cls.fields(rule, realization)
        binding = cls.fields("0", source_reference, "DIRECT", "-")
        clause = "proofs=[]|inputs=[" + binding + "]|nativePool=-"
        emission = emission_state + "|executionFType=ROW|derivedAction=-"
        receipt = cls.fields(rule, emission, realization, clause, "")
        row = {
            "coordinateIndex": 1, "candidateIndex": 0,
            "rule": rule, "owner": owner, "orderedInputs": ["present-row"],
            "emission": emission, "placement": placement,
            "realization": realization, "reference": reference,
            "proofDependencies": [], "requiredInputSupport": [source_reference],
            "inputBindings": [{"signature": binding, "inputPosition": 0,
                                "source": source_reference, "sourceOwner": source_owner,
                                "sourcePlacement": source_placement, "kind": "DIRECT",
                                "relocationAction": "-"}],
            "nativeWorkerPoolWitness": "-", "nativeWorkerPoolLayoutExact": True,
            "derivedFoutAction": "-", "independentReachabilityClass": "UNSUPPORTED_COMPLEX",
        }
        return {
            "nodes": [[source_owner, "OPERATION", "source-value", [source_placement], []],
                      [owner, "OPERATION", "consumer-value", [placement], []]],
            "candidateDomains": [[source_owner, []], [owner, [receipt]]],
            "candidateReceiptSemanticFacts": [row],
            "candidateRealizationReferenceFacts": [
                {"reference": source_reference, "rule": source_rule, "owner": source_owner,
                 "placement": source_placement, "realization": source_realization},
                {"reference": reference, "rule": rule, "owner": owner,
                 "placement": placement, "realization": realization}],
            "compiledCandidateInputEdges": [
                {"producer": source_owner, "consumer": owner, "inputPosition": 0}],
            "logicalCandidateReachability": {"transient": [], "function": [], "boundaries": []},
            "relocationActionFacts": [],
            "candidateRuleFactInventory": [{
                "ruleSignature": hashlib.sha256(rule.encode()).hexdigest(),
                "status": "AVAILABLE", "failure": "", "capabilityPresent": True,
                "profileAvailable": True,
                "emissions": [{"selection": emission, "realizations": [realization]}]}],
        }

    def test_candidate_receipt_semantics_and_source_links_recomputed_from_raw_dto(self):
        domain = self.semantic_candidate_domain()
        evidence = verify_candidate_receipt_semantics_and_source_links(domain)
        self.assertEqual({"receipts": 1, "realizationReferences": 2,
                          "simpleLocalReceipts": 0, "sourceLinkedReceipts": 1,
                          "unsupportedSourceLinkReceipts": 0}, evidence)

        mutated = self.semantic_candidate_domain()
        mutated["candidateReceiptSemanticFacts"][0]["inputBindings"][0]["sourceOwner"] = "rogue"
        with self.assertRaisesRegex(ValueError, "source authority differs"):
            verify_candidate_receipt_semantics_and_source_links(mutated)

        mutated = self.semantic_candidate_domain()
        mutated["candidateReceiptSemanticFacts"][0]["candidateIndex"] = 1
        with self.assertRaisesRegex(ValueError, "coordinate differs"):
            verify_candidate_receipt_semantics_and_source_links(mutated)

        mutated = self.semantic_candidate_domain()
        mutated["candidateRuleFactInventory"][0]["emissions"][0]["realizations"] = ["rogue"]
        with self.assertRaisesRegex(ValueError, "realization authority"):
            verify_candidate_receipt_semantics_and_source_links(mutated)

        unsupported = self.semantic_candidate_domain()
        unsupported["compiledCandidateInputEdges"].clear()
        self.assertEqual(1, verify_candidate_receipt_semantics_and_source_links(
            unsupported)["unsupportedSourceLinkReceipts"])

    def test_relocation_receipt_semantics_and_action_links_recomputed_from_raw_dto(self):
        source_version = "source-value"
        consumer = "consumer-owner"
        placement = "FED/FOUT/ROW/SHAPE_INDEPENDENT"
        action = self.fields(source_version, placement, "ROW", "anchor", "scope",
                             self.java_token(consumer))
        demand = self.fields(source_version, consumer, "0", placement, "context")
        choice = self.fields(demand, action)
        action_fact = {"action": action, "sourceValueVersion": source_version,
                       "targetPlacement": placement, "materializationFType": "ROW",
                       "durableAnchor": "anchor", "statementBlockScope": "scope",
                       "compatibleConsumers": [consumer], "directSourcePlacements": [],
                       "obligations": [{"consumer": consumer, "inputPosition": 0,
                                        "sourceValueVersion": source_version,
                                        "requiredPlacement": placement}]}
        domain = {"relocationDomains": [[demand, [choice]]],
                  "relocationActionFacts": [action_fact]}
        self.assertEqual({"demandCoordinates": 1, "choiceReceipts": 1, "graphActions": 1},
                         verify_relocation_receipt_semantics_and_action_links(domain))
        domain["relocationActionFacts"][0]["obligations"][0]["inputPosition"] = 1
        with self.assertRaisesRegex(ValueError, "exact graph obligation"):
            verify_relocation_receipt_semantics_and_action_links(domain)

    def test_local_candidate_assignment_gate_exhaustive_tiny_relation(self):
        domain = self.semantic_candidate_domain()
        source = domain["candidateRealizationReferenceFacts"][0]
        source_emission_state = source["placement"] + "|derivedFedFout=false"
        source_emission = source_emission_state + "|executionFType=ROW|derivedAction=-"
        source_clause = "proofs=[]|inputs=[]|nativePool=-"
        source_receipt = self.fields(source["rule"], source_emission,
                                     source["realization"], source_clause, "")
        source_row = {
            "coordinateIndex": 0, "candidateIndex": 0,
            "rule": source["rule"], "owner": source["owner"], "orderedInputs": [],
            "emission": source_emission,
            "placement": source["placement"], "realization": source["realization"],
            "reference": source["reference"],
            "proofDependencies": [], "requiredInputSupport": [], "inputBindings": [],
            "nativeWorkerPoolWitness": "-", "nativeWorkerPoolLayoutExact": True,
            "derivedFoutAction": "-", "independentReachabilityClass": "UNSUPPORTED_COMPLEX",
        }
        domain["candidateDomains"][0][1].append(source_receipt)
        domain["candidateReceiptSemanticFacts"].insert(0, source_row)
        domain["candidateRuleFactInventory"].append({
            "ruleSignature": hashlib.sha256(source["rule"].encode()).hexdigest(),
            "status": "AVAILABLE", "failure": "", "capabilityPresent": True,
            "profileAvailable": True,
            "emissions": [{"selection": source_emission,
                           "realizations": [source["realization"]]}]})
        domain["placementDomains"] = [[node[0], node[3]] for node in domain["nodes"]]
        domain["relocationDomains"] = []

        accepted = set()
        for source_choice in range(2):
            for consumer_choice in range(2):
                decision = candidate_assignment_locally_compatible(
                    domain, [0, 0], [source_choice, consumer_choice], [])
                if decision:
                    accepted.add((source_choice, consumer_choice))
        self.assertEqual({(1, 1)}, accepted)

        domain["logicalCandidateReachability"]["boundaries"].append(
            {"source": source["owner"], "target": "consumer-owner"})
        self.assertIsNone(candidate_assignment_locally_compatible(
            domain, [0, 0], [1, 1], []))

    @classmethod
    def activation_primitive_domain(cls):
        domain = cls.semantic_candidate_domain()
        semantic = domain["candidateReceiptSemanticFacts"][0]
        semantic["orderedInputs"] = ["PRESENT:ROW"]
        source_owner = domain["candidateRealizationReferenceFacts"][0]["owner"]
        consumer_owner = semantic["owner"]
        domain["placementDomains"] = [[source_owner, ["FED/FOUT/ROW/SHAPE_INDEPENDENT"]],
                                      [consumer_owner, ["FED/FOUT/ROW/SHAPE_INDEPENDENT"]]]
        domain["candidateRealizationSupportAuthorities"] = [
            {"referenceIndex": 0, "supportAuthorities": [
                {"kind": "ABSENT", "ftype": "-", "layoutExact": False}]},
            {"referenceIndex": 1, "supportAuthorities": [
                {"kind": "ABSENT", "ftype": "-", "layoutExact": False}]},
        ]
        pool = {"kind": "EXACT_LAYOUT", "ftype": "ROW", "layoutExact": True,
                "partitions": [{"worker": "worker:1/data", "begin": [0, 0],
                                "end": [10, 20]}]}
        partition = cls.fields("worker:1/data", "1:0,1:0", "2:10,2:20")
        anchor = cls.fields("anchor", "ROW", cls.java_token(partition))
        action_signature = cls.fields("source-value", semantic["placement"], "ROW", anchor,
                                      "scope", cls.java_token(consumer_owner))
        domain["relocationActionFacts"] = [{
            "action": action_signature, "sourceValueVersion": "source-value",
            "targetPlacement": semantic["placement"], "materializationFType": "ROW",
            "durableAnchor": anchor, "statementBlockScope": "scope",
            "compatibleConsumers": [consumer_owner], "directSourcePlacements": [],
            "obligations": [{"consumer": consumer_owner, "inputPosition": 0,
                             "sourceValueVersion": "source-value",
                             "requiredPlacement": semantic["placement"]}],
        }]
        domain["relocationWorkerPoolAuthorities"] = [pool]
        domain["derivedFoutActions"] = []
        domain["logicalCandidateCoordinateAuthority"] = {
            "transient": [], "function": [], "boundaries": []}
        receipt = domain["candidateDomains"][1][1][0]
        domain["candidateReceiptActivationFacts"] = [{
            "coordinateIndex": 1, "candidateIndex": 0,
            "receiptSha256": hashlib.sha256(receipt.encode()).hexdigest(),
            "ownerPlacementCoordinateIndex": 1, "activePlacementAlternativeIndex": 0,
            "realizationReferenceIndex": 1, "supportAuthorityIndex": 0,
            "functionCallBoundary": False, "consumerExec": "FED",
            "executionFType": "ROW", "derivedFedFout": False,
            "presentInputCount": 1, "presentPhysicalInputCount": 1,
            "inputs": [{"inputPosition": 0, "present": True, "requiredFType": "ROW",
                        "compiledSources": [{"sourceNodeIndex": 0,
                            "placementCoordinateIndex": 0,
                            "compatibleFoutAlternativeIndices": [0],
                            "latentWdivmmBoundary": False}],
                        "exactBindings": [{"kind": "DIRECT", "sourceReferenceIndex": 0,
                            "sourceNodeIndex": 0, "sourcePlacementCoordinateIndex": 0,
                            "sourcePlacementAlternativeIndex": 0,
                            "compatibleFoutAlternativeIndices": [0],
                            "relocationActionIndex": -1}],
                        "matchingRelocationActionIndices": [0],
                        "functionForwardingSources": []}],
            "derivedFoutAuthority": {"required": False, "graphActionIndex": -1,
                "producerNodeIndex": -1, "producerPlacementCoordinateIndex": -1,
                "sourcePlacementAlternativeIndex": -1, "targetPlacementAlternativeIndex": -1,
                "anchorOwnerNodeIndex": -1, "anchorOwnerPlacementCoordinateIndex": -1,
                "anchorOwnerRequiredFType": "-",
                "anchorOwnerCompatibleFoutAlternativeIndices": []},
            "specialRuntimeAuthority": {"kind": "NONE"},
            "candidateFeasibilityVerdict": "MUTABLE_UNTRUSTED_VALUE",
            "interpretation": "MUTABLE_UNTRUSTED_VALUE", "unsupportedReasons": ["anything"],
        }]
        return domain

    def test_candidate_assignment_primitives_recomputed_and_mutations_rejected(self):
        domain = self.activation_primitive_domain()
        evidence = verify_candidate_assignment_primitives(domain)
        self.assertEqual({"activationFacts": 1, "supportAuthorities": 2,
                          "logicalAuthorities": 0, "relocationWorkerPools": 1,
                          "producerAssessmentFieldsTrusted": False,
                          "producerCarriedUnassessedFields": [
                              "compiledSources.latentWdivmmBoundary",
                              "specialRuntimeAuthority", "supportAuthorityIndex",
                              "logicalCandidateCoordinateAuthority.function.callInputPosition",
                          ]}, evidence)

        for mutate, message in [
            (lambda value: value["candidateReceiptActivationFacts"][0].__setitem__(
                "realizationReferenceIndex", 0), "realizationReferenceIndex differs"),
            (lambda value: value["candidateReceiptActivationFacts"][0].__setitem__(
                "presentPhysicalInputCount", 0), "present input counts"),
            (lambda value: value["candidateReceiptActivationFacts"][0]["inputs"][0]
                ["compiledSources"][0].__setitem__("compatibleFoutAlternativeIndices", []),
             "compiled source authority"),
            (lambda value: value["candidateReceiptActivationFacts"][0]["inputs"][0]
                .__setitem__("matchingRelocationActionIndices", []), "input primitives"),
            (lambda value: value["relocationWorkerPoolAuthorities"][0]["partitions"][0]
                ["end"].__setitem__(1, 21), "worker-pool authority/action"),
        ]:
            mutated = copy.deepcopy(domain)
            mutate(mutated)
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                verify_candidate_assignment_primitives(mutated)

        untrusted = copy.deepcopy(domain)
        fact = untrusted["candidateReceiptActivationFacts"][0]
        fact["candidateFeasibilityVerdict"] = "AVAILABLE"
        fact["interpretation"] = "CLAIMED_COMPLETE"
        fact["unsupportedReasons"] = []
        self.assertFalse(verify_candidate_assignment_primitives(untrusted)[
            "producerAssessmentFieldsTrusted"])

        producer_carried = copy.deepcopy(domain)
        producer_carried["candidateReceiptActivationFacts"][0]["inputs"][0][
            "compiledSources"][0]["latentWdivmmBoundary"] = True
        producer_carried["candidateReceiptActivationFacts"][0][
            "specialRuntimeAuthority"] = {
                "kind": "DIRECT_WDIVMM", "producerPayload": "unassessed"}
        producer_carried["candidateRealizationSupportAuthorities"][1][
            "supportAuthorities"].append(
                {"kind": "ABSENT", "ftype": "-", "layoutExact": False})
        producer_carried["candidateReceiptActivationFacts"][0][
            "supportAuthorityIndex"] = 1
        evidence = verify_candidate_assignment_primitives(producer_carried)
        self.assertFalse(evidence["producerAssessmentFieldsTrusted"])
        self.assertEqual(4, len(evidence["producerCarriedUnassessedFields"]))

        function_carried = copy.deepcopy(domain)
        function_carried["logicalCandidateReachability"]["function"] = [{
            "source": "source-owner", "boundary": "consumer-owner",
            "target": "source-owner", "inputPosition": 0}]
        authority = {"source": "source-owner", "boundary": "consumer-owner",
            "target": "source-owner", "sourceNodeIndex": 0,
            "sourcePlacementCoordinateIndex": 0, "boundaryNodeIndex": 1,
            "boundaryPlacementCoordinateIndex": 1, "targetNodeIndex": 0,
            "targetPlacementCoordinateIndex": 0, "callInputPosition": 7,
            "logicalPosition": 0}
        function_carried["logicalCandidateCoordinateAuthority"]["function"] = [
            authority]
        function_carried["candidateReceiptActivationFacts"][0]["inputs"][0][
            "functionForwardingSources"] = [{name: authority[name] for name in (
                "sourceNodeIndex", "sourcePlacementCoordinateIndex",
                "boundaryNodeIndex", "boundaryPlacementCoordinateIndex",
                "targetNodeIndex", "targetPlacementCoordinateIndex",
                "callInputPosition", "logicalPosition")}]
        evidence = verify_candidate_assignment_primitives(function_carried)
        self.assertIn("logicalCandidateCoordinateAuthority.function.callInputPosition",
                      evidence["producerCarriedUnassessedFields"])

    def test_java_length_fields_handles_utf16_and_rejects_mutations(self):
        signature = "|".join(self.java_token(value) for value in ("a", "😀", ""))
        self.assertEqual(["a", "😀", ""], decode_java_length_fields(signature, 3))
        with self.assertRaisesRegex(ValueError, "splits UTF-16"):
            decode_java_length_fields("1:😀", 1)
        with self.assertRaisesRegex(ValueError, "trailing"):
            decode_java_length_fields(signature + "x", 3)

    def test_derived_fout_ownership_exhaustive_tiny_relation(self):
        owned_a, owned_b, rogue = "owned-a", "owned-b", "rogue"
        passing = [
            ([], []),
            ([], [self.candidate(None)]),
            ([owned_a], [self.candidate(None), self.candidate(owned_a)]),
            ([owned_a, owned_b], [self.candidate(owned_b), self.candidate(owned_a)]),
        ]
        for actions, receipts in passing:
            with self.subTest(actions=actions, receipts=receipts):
                bindings = []
                for candidate_index, receipt in enumerate(receipts):
                    action = candidate_derived_fout_action(receipt)
                    if action is not None:
                        bindings.append([0, candidate_index, actions.index(action)])
                domain = {"derivedFoutActions": actions,
                          "derivedFoutOwnershipBindings": bindings,
                          "candidateDomains": [["owner", receipts]]}
                self.assertTrue(verify_derived_fout_graph_ownership(domain))
                for receipt in receipts:
                    expected = None if "derivedAction=-" in receipt else \
                        receipt.split("derivedAction=", 1)[1].split("|", 1)[0]
                    self.assertEqual(expected, candidate_derived_fout_action(receipt))
        with self.assertRaisesRegex(ValueError, "not graph-owned"):
            verify_derived_fout_graph_ownership({
                "derivedFoutActions": [owned_a],
                "derivedFoutOwnershipBindings": [[0, 0, 0]],
                "candidateDomains": [["owner", [self.candidate(rogue)]]],
            })
        with self.assertRaisesRegex(ValueError, "coverage differs"):
            verify_derived_fout_graph_ownership({
                "derivedFoutActions": [owned_a],
                "derivedFoutOwnershipBindings": [],
                "candidateDomains": [["owner", [self.candidate(owned_a)]]],
            })
        with self.assertRaisesRegex(ValueError, "candidate differs"):
            verify_derived_fout_graph_ownership({
                "derivedFoutActions": [owned_a],
                "derivedFoutOwnershipBindings": [[1, 0, 0]],
                "candidateDomains": [["owner", [self.candidate(owned_a)]]],
            })
        with self.assertRaisesRegex(ValueError, "universe malformed"):
            verify_derived_fout_graph_ownership({
                "derivedFoutActions": [owned_a, owned_a],
                "derivedFoutOwnershipBindings": [], "candidateDomains": []})
        malformed = self.candidate(owned_a).replace("|derivedAction=", "|derived=")
        with self.assertRaisesRegex(ValueError, "signature|derived action field"):
            verify_derived_fout_graph_ownership({
                "derivedFoutActions": [owned_a],
                "derivedFoutOwnershipBindings": [[0, 0, 0]],
                "candidateDomains": [["owner", [malformed]]],
            })

    def test_nondecision_owner_classifier_exhaustive_tiny_matrix(self):
        state = "CP/LOUT/-/SHAPE_INDEPENDENT"
        cases = [
            (None, "UNRESOLVED"),
            (["outside", "FUNCTION_BODY_NON_EMITTED", "value", [], []],
             "INERT_FUNCTION_TEMPLATE"),
            (["outside", "FUNCTION_BODY_NON_EMITTED", "value", [state], []],
             "UNRESOLVED"),
            (["outside", "OPERATION", "value", [], []], "UNRESOLVED"),
            (["outside", "OPERATION", "value", [state], []], "UNRESOLVED"),
        ]
        for node, expected in cases:
            with self.subTest(node=node, expected=expected):
                nodes = [["decision", "OPERATION", "value", [state], []]]
                if node is not None:
                    nodes.append(node)
                domain = {
                    "nodes": nodes,
                    "placementDomains": [["decision", [state]]],
                    "nonDecisionCandidateOwners": [["outside", expected]],
                }
                self.assertTrue(verify_nondecision_candidate_owner_classification(domain))
                domain["nonDecisionCandidateOwners"][0][1] = (
                    "UNRESOLVED" if expected == "INERT_FUNCTION_TEMPLATE"
                    else "INERT_FUNCTION_TEMPLATE")
                with self.assertRaisesRegex(ValueError, "classification differs"):
                    verify_nondecision_candidate_owner_classification(domain)

    def test_mutated_coordinate_is_rejected_even_with_valid_outer_digest(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "model.json.gz"
            graph = {key: [] for key in
                     ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
            domain = {
                "nodes": [["owner", "GENERAL", "value", ["CP/LOUT"], []],
                          ["template", "FUNCTION_BODY_NON_EMITTED", "value", [], []]],
                "placementDomains": [["owner", ["CP/LOUT"]]],
                "candidateDomains": [["owner", [self.candidate("owned")]]],
                "relocationDomains": [["demand", ["action"]]],
                "derivedFoutActions": ["owned"],
                "derivedFoutOwnershipBindings": [[0, 0, 0]],
                "nonDecisionCandidateOwners": [["template", "INERT_FUNCTION_TEMPLATE"],
                                                ["missing", "UNRESOLVED"]],
                "radices": [1, 2, 2],
            }
            graph_digest = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
            artifact = {
                "schema": "closed-native-model-artifact-v1",
                "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
                "preRewriteGraph": graph, "finalHopGraph": graph,
                "nativeDomain": domain,
                "summary": {
                    "status": "COMPLETE", "cell": "test", "rawCount": "4",
                    "placementCoordinates": 1, "candidateCoordinates": 1,
                    "relocationCoordinates": 1,
                    "nativeDomainSha256": hashlib.sha256(canonical(domain)).hexdigest(),
                    "preRewriteGraphSha256": graph_digest,
                    "finalHopGraphSha256": graph_digest,
                },
            }

            def write():
                plain = canonical(artifact)
                path.write_bytes(gzip.compress(plain))
                return hashlib.sha256(plain).hexdigest()

            self.assertEqual("STRUCTURE_VERIFIED", verify(path, write())["status"])
            with patch.object(p_verifier, "MAX_DECOMPRESSED_ARTIFACT_BYTES", 16), \
                    self.assertRaisesRegex(ValueError, "decompressed byte budget"):
                verify(path, write())
            artifact["nativeDomain"]["derivedFoutActions"].clear()
            artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
                canonical(artifact["nativeDomain"])).hexdigest()
            with self.assertRaisesRegex(ValueError, "not graph-owned"):
                verify(path, write())
            artifact["nativeDomain"]["derivedFoutActions"].append("owned")
            artifact["nativeDomain"]["radices"][1] = 3
            artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
                canonical(artifact["nativeDomain"])).hexdigest()
            with self.assertRaisesRegex(ValueError, "radix"):
                verify(path, write())
            artifact["nativeDomain"]["radices"][1] = 2
            artifact["nativeDomain"]["nodes"][0][1] = "FUNCTION_CALL"
            artifact["nativeDomain"]["nodes"][0][3] = ["FED/FOUT/ROW/SHAPE_INDEPENDENT"]
            artifact["nativeDomain"]["placementDomains"][0][1] = ["FED/FOUT/ROW/SHAPE_INDEPENDENT"]
            artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
                canonical(artifact["nativeDomain"])).hexdigest()
            with self.assertRaisesRegex(ValueError, "function call has non-CP"):
                verify(path, write())

    def test_nondecision_owner_role_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "model.json.gz"
            graph = {key: [] for key in
                     ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
            graph_digest = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
            domain = {
                "nodes": [["owner", "OPERATION", "value",
                           ["CP/LOUT/-/SHAPE_INDEPENDENT"], []],
                          ["template", "FUNCTION_BODY_NON_EMITTED", "value", [], []]],
                "placementDomains": [["owner", ["CP/LOUT/-/SHAPE_INDEPENDENT"]]],
                "candidateDomains": [["owner", []]], "relocationDomains": [],
                "nonDecisionCandidateOwners": [["template", "UNRESOLVED"]],
                "radices": [1, 1],
            }
            artifact = {
                "schema": "closed-native-model-artifact-v1",
                "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
                "preRewriteGraph": graph, "finalHopGraph": graph,
                "nativeDomain": domain,
                "summary": {"status": "COMPLETE", "cell": "test", "rawCount": "1",
                            "placementCoordinates": 1, "candidateCoordinates": 1,
                            "relocationCoordinates": 0, "preRewriteGraphSha256": graph_digest,
                            "finalHopGraphSha256": graph_digest},
            }

            def write():
                artifact["summary"]["nativeDomainSha256"] = hashlib.sha256(
                    canonical(domain)).hexdigest()
                plain = canonical(artifact)
                path.write_bytes(gzip.compress(plain, mtime=0))
                return hashlib.sha256(plain).hexdigest()

            with self.assertRaisesRegex(ValueError, "classification differs"):
                verify(path, write())
            domain["nonDecisionCandidateOwners"][0][1] = "INERT_FUNCTION_TEMPLATE"
            receipt = verify(path, write())
            self.assertIn("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
                          receipt["acceptanceCoverage"]["assessedPredicates"])
            self.assertNotIn("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
                             receipt["acceptanceCoverage"]["opaquePredicates"])


if __name__ == "__main__":
    unittest.main()
