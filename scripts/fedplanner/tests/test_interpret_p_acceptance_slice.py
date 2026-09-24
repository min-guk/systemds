import copy
import gzip
import hashlib
import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.fedplanner.interpret_p_acceptance_slice import (
    ALIGNMENT_RULE, CANDIDATE_RULE, JAVA_ARGUMENT_EXCEPTION, RELOCATION_RULE,
    _interpret_verified_assignment, _pool_key, interpret_assignment,
)
from scripts.fedplanner.tests import test_verify_p_model_artifact as verifier_fixtures
from scripts.fedplanner.verify_p_model_artifact import (
    canonical, encode_java_length_fields, source_order,
)


LOCAL = "CP/LOUT/-/SHAPE_INDEPENDENT"
FED = "FED/LOUT/ROW/SHAPE_INDEPENDENT"


def candidate_fact(ci, ai, active, *, present=0):
    return {
        "coordinateIndex": ci, "candidateIndex": ai,
        "activePlacementAlternativeIndex": active,
        "functionCallBoundary": False, "specialRuntimeAuthority": {"kind": "NONE"},
        "unsupportedReasons": [], "presentInputCount": present,
        "derivedFoutAuthority": {"required": False, "graphActionIndex": -1},
    }


def semantic(ci, ai, owner, placement, *, bindings=None):
    bindings = [] if bindings is None else bindings
    simple = not bindings
    return {
        "coordinateIndex": ci, "candidateIndex": ai, "owner": owner,
        "placement": placement, "reference": f"ref-{ci}-{ai}",
        "requiredInputSupport": [], "inputBindings": bindings,
        "derivedFoutAction": "-", "nativeWorkerPoolWitness": "-",
        "independentReachabilityClass": "SIMPLE_LOCAL" if simple else "UNSUPPORTED_COMPLEX",
    }


def base_domain():
    return {
        "placementDomains": [["owner", [LOCAL]]],
        "candidateDomains": [["owner", ["receipt"]]],
        "relocationDomains": [],
        "candidateReceiptSemanticFacts": [semantic(0, 0, "owner", LOCAL)],
        "candidateReceiptActivationFacts": [candidate_fact(0, 0, 0)],
        "candidateRealizationReferenceFacts": [{"reference": "ref-0-0"}],
        "relocationActionFacts": [], "relocationWorkerPoolAuthorities": [],
        "logicalCandidateReachability": {"transient": [], "function": [], "boundaries": []},
    }


def demand(source, consumer, position, placement=FED, scope="scope"):
    return encode_java_length_fields((source, consumer, str(position), placement, scope))


def choice(demand_signature, action):
    return encode_java_length_fields((demand_signature, action))


def relocation_domain(*, two_pools=False, zero_options=False):
    domain = base_domain()
    domain["placementDomains"] = [["owner", [FED]]]
    bindings = [{"kind": "RELOCATION", "inputPosition": 0,
                 "relocationAction": "action-a"}]
    if two_pools:
        bindings.append({"kind": "RELOCATION", "inputPosition": 1,
                         "relocationAction": "action-b"})
    domain["candidateReceiptSemanticFacts"] = [
        semantic(0, 0, "owner", FED, bindings=bindings)]
    domain["candidateReceiptActivationFacts"] = [
        candidate_fact(0, 0, 0, present=len(bindings))]
    first = demand("source-a", "owner", 0)
    domains = [[first, [] if zero_options else [choice(first, "action-a")]]]
    actions = [{"action": "action-a"}]
    pools = [{"kind": "EXACT_LAYOUT", "ftype": "ROW", "layoutExact": True,
              "partitions": [{"worker": "worker-a", "begin": [0, 0],
                              "end": [10, 20]}]}]
    if two_pools:
        second = demand("source-b", "owner", 1)
        domains.append([second, [choice(second, "action-b")]])
        actions.append({"action": "action-b"})
        pools.append({"kind": "EXACT_LAYOUT", "ftype": "ROW", "layoutExact": True,
                      "partitions": [{"worker": "worker-b", "begin": [0, 0],
                                      "end": [10, 20]}]})
    domain["relocationDomains"] = domains
    domain["relocationActionFacts"] = actions
    domain["relocationWorkerPoolAuthorities"] = pools
    return domain


def exact_pool(ftype, partitions):
    return {"kind": "EXACT_LAYOUT", "ftype": ftype, "layoutExact": True,
            "partitions": partitions}


def add_two_relocation_bindings(domain, pools):
    """Add two graph-owned relocation choices while preserving the v2 schema."""
    source_reference = domain["candidateRealizationReferenceFacts"][0]
    semantic_row = domain["candidateReceiptSemanticFacts"][0]
    placement = semantic_row["placement"]
    actions = []
    for position, pool in enumerate(pools):
        partition_tokens = []
        for partition in pool["partitions"]:
            begin = ",".join(verifier_fixtures.PModelArtifactVerificationTest.java_token(
                str(value)) for value in partition["begin"])
            end = ",".join(verifier_fixtures.PModelArtifactVerificationTest.java_token(
                str(value)) for value in partition["end"])
            partition_tokens.append(encode_java_length_fields(
                (partition["worker"], begin, end)))
        encoded_partitions = ",".join(
            verifier_fixtures.PModelArtifactVerificationTest.java_token(value)
            for value in partition_tokens)
        anchor = encode_java_length_fields(
            (f"anchor-{position}", pool["ftype"], encoded_partitions))
        action_signature = encode_java_length_fields((
            "source-value", placement, pool["ftype"], anchor, "scope",
            verifier_fixtures.PModelArtifactVerificationTest.java_token("consumer-owner")))
        actions.append({
            "action": action_signature, "sourceValueVersion": "source-value",
            "targetPlacement": placement, "materializationFType": pool["ftype"],
            "durableAnchor": anchor, "statementBlockScope": "scope",
            "compatibleConsumers": ["consumer-owner"], "directSourcePlacements": [],
            "obligations": [{"consumer": "consumer-owner", "inputPosition": position,
                             "sourceValueVersion": "source-value",
                             "requiredPlacement": placement}],
        })

    bindings = []
    for position, action in enumerate(actions):
        signature = encode_java_length_fields((str(position), source_reference["reference"],
                                               "RELOCATION", action["action"]))
        bindings.append({
            "signature": signature, "inputPosition": position,
            "source": source_reference["reference"], "sourceOwner": "source-owner",
            "sourcePlacement": source_reference["placement"], "kind": "RELOCATION",
            "relocationAction": action["action"],
        })
    clause = "proofs=[]|inputs=[" + ", ".join(
        binding["signature"] for binding in bindings) + "]|nativePool=-"
    receipt = encode_java_length_fields((semantic_row["rule"], semantic_row["emission"],
                                         semantic_row["realization"], clause, ""))
    semantic_row["orderedInputs"] = ["PRESENT:ROW", "PRESENT:ROW"]
    semantic_row["requiredInputSupport"] = [source_reference["reference"]]
    semantic_row["inputBindings"] = copy.deepcopy(bindings)
    domain["candidateDomains"][1][1][0] = receipt
    consumer_clause = domain["candidateRealizationClauseInventory"][1]["clauses"][0]
    consumer_clause["clauseIdentity"] = clause
    consumer_clause["requiredInputSupport"] = [source_reference["reference"]]
    consumer_clause["inputBindings"] = copy.deepcopy(bindings)
    domain["compiledCandidateInputEdges"].append(
        {"producer": "source-owner", "consumer": "consumer-owner", "inputPosition": 1})
    domain["relocationActionFacts"] = actions
    domain["relocationWorkerPoolAuthorities"] = copy.deepcopy(pools)
    domain["relocationDomains"] = []
    for position, action in enumerate(actions):
        demand_signature = demand("source-value", "consumer-owner", position,
                                  placement=placement)
        domain["relocationDomains"].append([
            demand_signature, [choice(demand_signature, action["action"])],
        ])

    activation = domain["candidateReceiptActivationFacts"][0]
    activation["receiptSha256"] = hashlib.sha256(receipt.encode()).hexdigest()
    activation["presentInputCount"] = 2
    activation["presentPhysicalInputCount"] = 2
    activation["inputs"] = []
    for position in range(2):
        activation["inputs"].append({
            "inputPosition": position, "present": True, "requiredFType": "ROW",
            "compiledSources": [{"sourceNodeIndex": 0, "placementCoordinateIndex": 0,
                                 "compatibleFoutAlternativeIndices": [0],
                                 "latentWdivmmBoundary": False}],
            "exactBindings": [{"kind": "RELOCATION", "sourceReferenceIndex": 0,
                               "sourceNodeIndex": 0, "sourcePlacementCoordinateIndex": 0,
                               "sourcePlacementAlternativeIndex": 0,
                               "compatibleFoutAlternativeIndices": [0],
                               "relocationActionIndex": position}],
            "matchingRelocationActionIndices": [position],
            "functionForwardingSources": [],
        })
    domain["radices"] = [1, 1, 1, 2, 2, 2]


def verified_v2_artifact(folder, mutate=None):
    """Create the realistic v2 fixture used by the production verifier tests."""
    domain = verifier_fixtures.PModelArtifactVerificationTest.activation_primitive_domain()
    source_reference, consumer_reference = domain["candidateRealizationReferenceFacts"]
    semantic_row = domain["candidateReceiptSemanticFacts"][0]
    source_emission = (source_reference["placement"]
                       + "|derivedFedFout=false|executionFType=ROW|derivedAction=-")
    source_clause = "proofs=[]|inputs=[]|nativePool=-"
    consumer_binding = semantic_row["inputBindings"][0]
    consumer_clause = ("proofs=[]|inputs=[" + consumer_binding["signature"]
                       + "]|nativePool=-")
    absent = {"kind": "ABSENT", "ftype": "-", "layoutExact": False}
    domain["candidateRealizationSupportAuthorities"] = [
        {"referenceIndex": 0, "supportAuthorities": [copy.deepcopy(absent)]},
        {"referenceIndex": 1, "supportAuthorities": [copy.deepcopy(absent)]},
    ]
    domain["candidateRealizationClauseInventory"] = [
        {**source_reference, "emission": source_emission, "clauses": [{
            "ordinal": 0, "clauseIdentity": source_clause,
            "proofDependencies": [], "requiredInputSupport": [],
            "inputBindings": [], "nativeWorkerPoolWitness": "-",
            "workerPoolAuthority": copy.deepcopy(absent),
            "nativeWorkerPoolLayoutExact": True}]},
        {**consumer_reference, "emission": semantic_row["emission"], "clauses": [{
            "ordinal": 0, "clauseIdentity": consumer_clause,
            "proofDependencies": [],
            "requiredInputSupport": [source_reference["reference"]],
            "inputBindings": [copy.deepcopy(consumer_binding)],
            "nativeWorkerPoolWitness": "-",
            "workerPoolAuthority": copy.deepcopy(absent),
            "nativeWorkerPoolLayoutExact": True}]},
    ]
    domain["candidateRuleFactInventory"].append({
        "ruleSignature": hashlib.sha256(source_reference["rule"].encode()).hexdigest(),
        "status": "AVAILABLE", "failure": "", "capabilityPresent": True,
        "profileAvailable": True,
        "emissions": [{"selection": source_emission,
                       "realizations": [source_reference["realization"]]}],
    })
    privacy_rules = []
    for inventory_row in domain["candidateRuleFactInventory"]:
        selection = inventory_row["emissions"][0]["selection"]
        placement = selection.split("|derivedFedFout=", 1)[0]
        exec_type, output, ftype, _ = placement.split("/")
        privacy_rules.append({
            "ruleSignature": inventory_row["ruleSignature"],
            "inputStatus": "AVAILABLE", "inputFailure": "",
            "capabilityPresent": True, "profileAvailable": True,
            "capabilityExec": exec_type, "capabilityOutput": output,
            "effectivePrivacy": "PUBLIC", "matrix": True,
            "federatedSource": False, "printOrPwrite": False,
            "dmlFunctionPlaceholder": False, "multiReturnBoundary": False,
            "dataOp": "-", "inputPresence": [],
            "protectedPayloadPositions": [],
            "inputEmissions": [{"signature": selection, "placement": placement,
                "exec": exec_type, "output": output, "fType": ftype,
                "executionFType": "ROW", "derivedFedFout": False,
                "derivedSourcePlacement": "-"}],
            "outputStatus": "AVAILABLE", "outputFailure": "",
            "outputEmissionSignatures": [selection],
        })
    domain["candidatePrivacyClosurePasses"] = [{"ordinal": 0, "rules": privacy_rules}]
    state = source_reference["placement"]
    domain["placementDomains"] = [["source-owner", [state]],
                                  ["consumer-owner", [state]]]
    domain["relocationDomains"] = []
    domain["radices"] = [1, 1, 1, 2]
    domain["constraints"] = []
    domain["derivedFoutOwnershipBindings"] = []
    domain["nonDecisionCandidateOwners"] = []
    if mutate is not None:
        mutate(domain)

    raw_count = 1
    for radix in domain["radices"]:
        raw_count *= radix

    graph = {key: [] for key in
             ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
    graph_digest = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
    artifact = {
        "schema": "closed-native-model-artifact-v2",
        "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
        "preRewriteGraph": graph, "finalHopGraph": graph,
        "nativeDomain": domain,
        "summary": {"status": "COMPLETE", "cell": "v12-diagnostic",
                    "rawCount": str(raw_count),
                    "placementCoordinates": len(domain["placementDomains"]),
                    "candidateCoordinates": len(domain["candidateDomains"]),
                    "relocationCoordinates": len(domain["relocationDomains"]),
                    "preRewriteGraphSha256": graph_digest,
                    "finalHopGraphSha256": graph_digest,
                    "nativeDomainSha256": hashlib.sha256(canonical(domain)).hexdigest()},
    }
    plain = canonical(artifact)
    path = Path(folder) / "model.json.gz"
    path.write_bytes(gzip.compress(plain, mtime=0))
    return domain, path, hashlib.sha256(plain).hexdigest()


def add_simple_candidate_with_logical_boundary(domain):
    placement = LOCAL
    rule = "simple-rule"
    emission_state = placement + "|derivedFedFout=false"
    emission = emission_state + "|executionFType=-|derivedAction=-"
    realization = encode_java_length_fields(
        (emission_state, "NATIVE_LINEAGE", "-", "simple"))
    reference = encode_java_length_fields((rule, realization))
    clause = "proofs=[]|inputs=[]|nativePool=-"
    receipt = encode_java_length_fields((rule, emission, realization, clause, ""))
    absent = {"kind": "ABSENT", "ftype": "-", "layoutExact": False}

    domain["nodes"].append(["simple-owner", "OPERATION", "simple-value", [placement], []])
    domain["placementDomains"].append(["simple-owner", [placement]])
    domain["candidateDomains"].append(["simple-owner", [receipt]])
    domain["candidateRealizationReferenceFacts"].append({
        "reference": reference, "rule": rule, "owner": "simple-owner",
        "placement": placement, "realization": realization,
    })
    domain["candidateReceiptSemanticFacts"].append({
        "coordinateIndex": 2, "candidateIndex": 0, "rule": rule,
        "owner": "simple-owner", "orderedInputs": [], "emission": emission,
        "placement": placement, "realization": realization, "reference": reference,
        "proofDependencies": [], "requiredInputSupport": [], "inputBindings": [],
        "nativeWorkerPoolWitness": "-", "nativeWorkerPoolLayoutExact": True,
        "derivedFoutAction": "-", "independentReachabilityClass": "SIMPLE_LOCAL",
    })
    domain["candidateRealizationSupportAuthorities"].append({
        "referenceIndex": 2, "supportAuthorities": [copy.deepcopy(absent)],
    })
    domain["candidateRealizationClauseInventory"].append({
        "reference": reference, "rule": rule, "owner": "simple-owner",
        "emission": emission, "placement": placement, "realization": realization,
        "clauses": [{
            "ordinal": 0, "clauseIdentity": clause, "proofDependencies": [],
            "requiredInputSupport": [], "inputBindings": [],
            "nativeWorkerPoolWitness": "-", "workerPoolAuthority": copy.deepcopy(absent),
            "nativeWorkerPoolLayoutExact": True,
        }],
    })
    rule_signature = hashlib.sha256(rule.encode()).hexdigest()
    domain["candidateRuleFactInventory"].append({
        "ruleSignature": rule_signature, "status": "AVAILABLE", "failure": "",
        "capabilityPresent": True, "profileAvailable": True,
        "emissions": [{"selection": emission, "realizations": [realization]}],
    })
    domain["candidatePrivacyClosurePasses"][0]["rules"].append({
        "ruleSignature": rule_signature, "inputStatus": "AVAILABLE", "inputFailure": "",
        "capabilityPresent": True, "profileAvailable": True, "capabilityExec": "CP",
        "capabilityOutput": "LOUT", "effectivePrivacy": "PUBLIC", "matrix": True,
        "federatedSource": False, "printOrPwrite": False,
        "dmlFunctionPlaceholder": False, "multiReturnBoundary": False, "dataOp": "-",
        "inputPresence": [], "protectedPayloadPositions": [],
        "inputEmissions": [{
            "signature": emission, "placement": placement, "exec": "CP", "output": "LOUT",
            "fType": "-", "executionFType": "-", "derivedFedFout": False,
            "derivedSourcePlacement": "-",
        }],
        "outputStatus": "AVAILABLE", "outputFailure": "",
        "outputEmissionSignatures": [emission],
    })
    domain["candidateReceiptActivationFacts"].append({
        "coordinateIndex": 2, "candidateIndex": 0,
        "receiptSha256": hashlib.sha256(receipt.encode()).hexdigest(),
        "ownerPlacementCoordinateIndex": 2, "activePlacementAlternativeIndex": 0,
        "realizationReferenceIndex": 2, "supportAuthorityIndex": 0,
        "functionCallBoundary": False, "consumerExec": "CP", "executionFType": "-",
        "derivedFedFout": False, "presentInputCount": 0,
        "presentPhysicalInputCount": 0, "inputs": [],
        "derivedFoutAuthority": {
            "required": False, "graphActionIndex": -1, "producerNodeIndex": -1,
            "producerPlacementCoordinateIndex": -1, "sourcePlacementAlternativeIndex": -1,
            "targetPlacementAlternativeIndex": -1, "anchorOwnerNodeIndex": -1,
            "anchorOwnerPlacementCoordinateIndex": -1, "anchorOwnerRequiredFType": "-",
            "anchorOwnerCompatibleFoutAlternativeIndices": [],
        },
        "specialRuntimeAuthority": {"kind": "NONE"},
        "candidateFeasibilityVerdict": "MUTABLE_UNTRUSTED_VALUE",
        "interpretation": "MUTABLE_UNTRUSTED_VALUE", "unsupportedReasons": [],
    })
    domain["logicalCandidateReachability"]["boundaries"].append({
        "source": "source-owner", "target": "consumer-owner",
    })
    domain["logicalCandidateCoordinateAuthority"]["boundaries"].append({
        "source": "source-owner", "target": "consumer-owner", "sourceNodeIndex": 0,
        "sourcePlacementCoordinateIndex": 0, "targetNodeIndex": 1,
        "targetPlacementCoordinateIndex": 1,
    })
    domain["radices"] = [1, 1, 1, 1, 2, 2]


class PAcceptanceSliceTest(unittest.TestCase):
    def test_simple_candidate_coverage_accepts_and_missing_selection_rejects(self):
        accepted = _interpret_verified_assignment(base_domain(), [0], [1], [])
        self.assertEqual(("SUPPORTED", "ACCEPT"),
                         (accepted["rules"][CANDIDATE_RULE]["support"],
                          accepted["rules"][CANDIDATE_RULE]["verdict"]))
        self.assertFalse(accepted["complete"])

        rejected = _interpret_verified_assignment(base_domain(), [0], [0], [])
        rule = rejected["rules"][CANDIDATE_RULE]
        self.assertEqual(("SUPPORTED", "REJECT", JAVA_ARGUMENT_EXCEPTION),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))

    def test_global_logical_relation_blocks_simple_missing_selection_rejection(self):
        domain = base_domain()
        domain["logicalCandidateReachability"]["boundaries"] = [
            {"source": "source", "target": "owner"}]
        result = _interpret_verified_assignment(domain, [0], [0], [])
        rule = result["rules"][CANDIDATE_RULE]
        self.assertEqual(("PARTIAL", "UNKNOWN", None),
                         (rule["support"], rule["verdict"],
                          rule["javaExceptionClass"]))

    def test_tiny_exhaustive_candidate_differential(self):
        domain = base_domain()
        domain["placementDomains"] = [["owner", [LOCAL, FED]]]
        domain["candidateDomains"] = [["owner", ["local", "fed"]]]
        domain["candidateReceiptSemanticFacts"] = [
            semantic(0, 0, "owner", LOCAL), semantic(0, 1, "owner", FED)]
        domain["candidateReceiptActivationFacts"] = [
            candidate_fact(0, 0, 0), candidate_fact(0, 1, 1)]
        domain["candidateRealizationReferenceFacts"] = [
            {"reference": "ref-0-0"}, {"reference": "ref-0-1"}]
        for placement, selected in itertools.product(range(2), range(3)):
            expected = "ACCEPT" if selected == placement + 1 else "REJECT"
            actual = _interpret_verified_assignment(domain, [placement], [selected], [])
            self.assertEqual(expected, actual["rules"][CANDIDATE_RULE]["verdict"])

    def test_missing_v2_inventory_is_unknown_never_reject(self):
        result = interpret_assignment({}, [], [], [])
        for rule in result["rules"].values():
            self.assertEqual(("UNKNOWN", "UNKNOWN", None),
                             (rule["support"], rule["verdict"],
                              rule["javaExceptionClass"]))

    def test_public_entry_requires_complete_path_and_digest_authority(self):
        with self.assertRaisesRegex(ValueError, "supplied together"):
            interpret_assignment({}, [], [], [], model_path="model.json.gz")
        with self.assertRaisesRegex(ValueError, "supplied together"):
            interpret_assignment({}, [], [], [], model_sha256="0" * 64)
        with tempfile.TemporaryDirectory() as folder:
            domain, path, _ = verified_v2_artifact(folder)
            with self.assertRaisesRegex(ValueError, "digest differs"):
                interpret_assignment(domain, [0, 0], [0, 1], [],
                                     model_path=path, model_sha256="0" * 64)

    def test_realistic_v12_verified_artifact_produces_positive_diagnostic(self):
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(folder)
            result = interpret_assignment(
                domain, [0, 0], [0, 1], [], model_path=path, model_sha256=digest)
        self.assertEqual(("PARTIAL", "UNKNOWN"),
                         (result["rules"][CANDIDATE_RULE]["support"],
                          result["rules"][CANDIDATE_RULE]["verdict"]))
        self.assertEqual(("SUPPORTED", "ACCEPT"),
                         (result["rules"][ALIGNMENT_RULE]["support"],
                          result["rules"][ALIGNMENT_RULE]["verdict"]))

    def test_public_entry_keeps_unselected_simple_candidate_unknown_with_logical_relation(self):
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(
                folder, add_simple_candidate_with_logical_boundary)
            result = interpret_assignment(
                domain, [0, 0, 0], [0, 1, 0], [],
                model_path=path, model_sha256=digest)
        rule = result["rules"][CANDIDATE_RULE]
        self.assertEqual(("PARTIAL", "UNKNOWN", None),
                         (rule["support"], rule["verdict"],
                          rule["javaExceptionClass"]))

    def test_cli_keeps_unselected_simple_candidate_unknown_with_logical_relation(self):
        with tempfile.TemporaryDirectory() as folder:
            _, path, digest = verified_v2_artifact(
                folder, add_simple_candidate_with_logical_boundary)
            run = subprocess.run([
                sys.executable,
                str(Path(__file__).parents[1] / "interpret_p_acceptance_slice.py"),
                "--model", str(path), "--model-sha256", digest,
                "--placements", "0,0,0", "--candidates", "0,1,0",
                "--relocations", "",
            ], check=False, capture_output=True, text=True)
        self.assertEqual(0, run.returncode, run.stderr)
        rule = json.loads(run.stdout)["rules"][CANDIDATE_RULE]
        self.assertEqual(("PARTIAL", "UNKNOWN", None),
                         (rule["support"], rule["verdict"],
                          rule["javaExceptionClass"]))

    def test_verified_artifact_domain_binding_rejects_substitution(self):
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(folder)
            substituted = copy.deepcopy(domain)
            substituted["candidateReceiptActivationFacts"][0][
                "candidateFeasibilityVerdict"] = "forged"
            with self.assertRaisesRegex(ValueError, "differs from verified artifact"):
                interpret_assignment(substituted, [0, 0], [0, 1], [],
                                     model_path=path, model_sha256=digest)

    def test_deleted_privacy_authority_returns_unknown(self):
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(
                folder, lambda value: value.pop("candidatePrivacyClosurePasses"))
            result = interpret_assignment(
                domain, [0, 0], [0, 1], [], model_path=path, model_sha256=digest)
        for rule in result["rules"].values():
            self.assertEqual(("UNKNOWN", "UNKNOWN", None),
                             (rule["support"], rule["verdict"],
                              rule["javaExceptionClass"]))

    def test_mutated_privacy_rule_and_feasible_row_authorities_are_malformed(self):
        mutations = [
            (lambda value: value["candidatePrivacyClosurePasses"][0]["rules"][0]
                .__setitem__("outputEmissionSignatures", []), "privacy replay differs"),
            (lambda value: value["candidateRuleFactInventory"][0]["emissions"][0]
                .__setitem__("realizations", ["forged"]), "rule|realization|authority"),
            (lambda value: value["candidateReceiptActivationFacts"][0]
                .__setitem__("realizationReferenceIndex", 0),
             "realizationReferenceIndex differs"),
        ]
        for mutate, message in mutations:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as folder:
                domain, path, digest = verified_v2_artifact(folder, mutate)
                with self.assertRaisesRegex(ValueError, message):
                    interpret_assignment(domain, [0, 0], [0, 1], [],
                                         model_path=path, model_sha256=digest)

    def test_deleted_rule_inventory_is_malformed_authority(self):
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(
                folder, lambda value: value.pop("candidateRuleFactInventory"))
            with self.assertRaisesRegex(ValueError, "inventory|incomplete|coverage"):
                interpret_assignment(domain, [0, 0], [0, 1], [],
                                     model_path=path, model_sha256=digest)

    def test_duplicate_candidate_fact_fails_closed_as_malformed(self):
        domain = base_domain()
        domain["candidateReceiptActivationFacts"].append(
            copy.deepcopy(domain["candidateReceiptActivationFacts"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            _interpret_verified_assignment(domain, [0], [1], [])

    def test_explicit_relocation_binding_alignment(self):
        domain = relocation_domain()
        missing = _interpret_verified_assignment(domain, [0], [1], [0])
        rule = missing["rules"][ALIGNMENT_RULE]
        self.assertEqual(("SUPPORTED", "REJECT", JAVA_ARGUMENT_EXCEPTION),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))
        selected = _interpret_verified_assignment(domain, [0], [1], [1])
        self.assertEqual(("SUPPORTED", "ACCEPT"),
                         (selected["rules"][ALIGNMENT_RULE]["support"],
                          selected["rules"][ALIGNMENT_RULE]["verdict"]))
        self.assertEqual(("PARTIAL", "UNKNOWN"),
                         (selected["rules"][RELOCATION_RULE]["support"],
                          selected["rules"][RELOCATION_RULE]["verdict"]))

    def test_active_demand_with_zero_options_rejects_only_exact_alignment(self):
        result = _interpret_verified_assignment(
            relocation_domain(zero_options=True), [0], [1], [0])
        self.assertEqual("REJECT", result["rules"][ALIGNMENT_RULE]["verdict"])
        self.assertEqual("UNKNOWN", result["rules"][RELOCATION_RULE]["verdict"])

    def test_selected_worker_pool_mismatch_is_exact_rejection(self):
        domain = relocation_domain(two_pools=True)
        result = _interpret_verified_assignment(domain, [0], [1], [1, 1])
        rule = result["rules"][RELOCATION_RULE]
        self.assertEqual(("SUPPORTED", "REJECT", JAVA_ARGUMENT_EXCEPTION),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))

    def test_verified_v2_row_pool_ignores_orthogonal_extent(self):
        left = exact_pool("ROW", [
            {"worker": "worker-b:9002/left", "begin": [5, 0], "end": [10, 8]},
            {"worker": "worker-a:9001/left", "begin": [0, 0], "end": [5, 8]},
        ])
        right = exact_pool("ROW", [
            {"worker": "worker-a:9001/right", "begin": [0, 100], "end": [5, 900]},
            {"worker": "worker-b:9002/right", "begin": [5, 200], "end": [10, 700]},
        ])
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(
                folder, lambda value: add_two_relocation_bindings(value, [left, right]))
            result = interpret_assignment(
                domain, [0, 0], [0, 1], [1, 1],
                model_path=path, model_sha256=digest)
        rule = result["rules"][RELOCATION_RULE]
        self.assertEqual(("PARTIAL", "UNKNOWN", None),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))

    def test_verified_v2_full_pool_preserves_worker_multiplicity(self):
        left = exact_pool("FULL", [
            {"worker": "worker-a:9001/first", "begin": [0, 0], "end": [5, 8]},
            {"worker": "worker-a:9001/second", "begin": [5, 0], "end": [10, 8]},
        ])
        right = exact_pool("FULL", [
            {"worker": "worker-a:9001/only", "begin": [0, 100], "end": [99, 900]},
        ])
        def full_pool_fixture(value):
            add_two_relocation_bindings(value, [left, right])
            value["candidateReceiptSemanticFacts"][0]["orderedInputs"] = [
                "PRESENT:FULL", "PRESENT:FULL"]
            for fact in value["candidateReceiptActivationFacts"][0]["inputs"]:
                fact["requiredFType"] = "FULL"
                for source in fact["compiledSources"]:
                    source["compatibleFoutAlternativeIndices"] = []
                for binding in fact["exactBindings"]:
                    binding["compatibleFoutAlternativeIndices"] = []
        with tempfile.TemporaryDirectory() as folder:
            domain, path, digest = verified_v2_artifact(
                folder, full_pool_fixture)
            result = interpret_assignment(
                domain, [0, 0], [0, 1], [1, 1],
                model_path=path, model_sha256=digest)
        rule = result["rules"][RELOCATION_RULE]
        self.assertEqual(("SUPPORTED", "REJECT", JAVA_ARGUMENT_EXCEPTION),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))

    def test_worker_pool_projection_matches_java_axis_endpoint_and_order_semantics(self):
        row_left = exact_pool("ROW", [
            {"worker": "b:2/x", "begin": [5, 0], "end": [10, 3]},
            {"worker": "a:1/x", "begin": [0, 0], "end": [5, 3]},
        ])
        row_right = exact_pool("ROW", [
            {"worker": "a:1/y", "begin": [0, 40], "end": [5, 80]},
            {"worker": "b:2/y", "begin": [5, 20], "end": [10, 90]},
        ])
        col_left = exact_pool("COL", [
            {"worker": "a:1/x", "begin": [0, 0], "end": [3, 5]},
            {"worker": "b:2/x", "begin": [0, 5], "end": [3, 10]},
        ])
        col_right = exact_pool("COL", [
            {"worker": "b:2/y", "begin": [200, 5], "end": [900, 10]},
            {"worker": "a:1/y", "begin": [100, 0], "end": [800, 5]},
        ])
        full_left = exact_pool("FULL", [
            {"worker": "b:2/x", "begin": [0, 0], "end": [3, 5]},
            {"worker": "a:1/x", "begin": [0, 0], "end": [3, 5]},
        ])
        full_right = exact_pool("FULL", [
            {"worker": "a:1/y", "begin": [99], "end": [100]},
            {"worker": "b:2/y", "begin": [200, 300, 400], "end": [500, 600, 700]},
        ])
        self.assertEqual(_pool_key(row_left), _pool_key(row_right))
        self.assertEqual(_pool_key(col_left), _pool_key(col_right))
        self.assertEqual(_pool_key(full_left), _pool_key(full_right))

        broadcast_duplicate = exact_pool("BROADCAST", [
            {"worker": "a:1/x", "begin": [0], "end": [3]},
            {"worker": "a:1/y", "begin": [3], "end": [6]},
        ])
        broadcast_single = exact_pool("BROADCAST", [
            {"worker": "a:1/z", "begin": [100], "end": [200]},
        ])
        self.assertNotEqual(_pool_key(broadcast_duplicate), _pool_key(broadcast_single))

        different_address = copy.deepcopy(full_right)
        different_address["partitions"][1]["worker"] = "c:2/y"
        self.assertNotEqual(_pool_key(full_left), _pool_key(different_address))

    def test_opaque_extra_choice_cannot_create_worker_pool_rejection(self):
        domain = relocation_domain(two_pools=True)
        domain["candidateReceiptSemanticFacts"][0]["inputBindings"].pop()
        result = _interpret_verified_assignment(domain, [0], [1], [1, 1])
        rule = result["rules"][RELOCATION_RULE]
        self.assertEqual(("PARTIAL", "UNKNOWN", None),
                         (rule["support"], rule["verdict"], rule["javaExceptionClass"]))

    def test_duplicate_relocation_demand_is_malformed_not_rejection(self):
        domain = relocation_domain()
        domain["relocationDomains"].append(
            copy.deepcopy(domain["relocationDomains"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            _interpret_verified_assignment(domain, [0], [1], [0, 0])

    def test_assignment_out_of_range_is_malformed_not_java_rejection(self):
        with self.assertRaisesRegex(ValueError, "outside domain"):
            _interpret_verified_assignment(base_domain(), [0], [2], [])


if __name__ == "__main__":
    unittest.main()
