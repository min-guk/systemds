#!/usr/bin/env python3
"""Independently check the stored P model and its serialized acceptance evidence.

The artifact does not serialize the complete Java acceptance predicate. The
verification receipt therefore carries a machine-readable inventory of what was
and was not assessed. Callers must not promote STRUCTURE_VERIFIED to full P acceptance.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


# Keep these names stable: receipts and graph-relation verification use them as
# an explicit fail-closed boundary around the opaque production Java predicate.
OPAQUE_ACCEPTANCE_PREDICATES = (
    "CANDIDATE_RULE_STATUS_AND_PRIVACY_FILTERING",
    "CANDIDATE_FEASIBLE_VARIANTS_AND_SOURCE_REACHABILITY",
    "CANDIDATE_SELECTION_COVERAGE_AND_REALIZATION_COMPATIBILITY",
    "RELOCATION_ACTIVE_DEMAND_CONSTRUCTION",
    "RELOCATION_SELECTION_COVERAGE_AND_WORKER_POOL_COMPATIBILITY",
    "CANDIDATE_RELOCATION_REALIZATION_ALIGNMENT",
    "DERIVED_FOUT_GRAPH_OWNERSHIP",
    "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
    "JAVA_VALIDATOR_EXCEPTION_CLASSIFICATION",
)

INDEPENDENT_ARTIFACT_PREDICATES = {
    "SERIALIZED_CANDIDATE_PRIVACY_TRANSFORMATION",
    "SERIALIZED_CANDIDATE_RECEIPT_SEMANTICS_AND_SOURCE_LINKS",
    "SERIALIZED_RELOCATION_RECEIPT_SEMANTICS_AND_ACTION_LINKS",
    "DERIVED_FOUT_GRAPH_OWNERSHIP",
    "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION",
    "SERIALIZED_CANDIDATE_ASSIGNMENT_STRUCTURAL_LINKS",
}

PRODUCER_CARRIED_ACTIVATION_FIELDS = (
    "compiledSources.latentWdivmmBoundary",
    "specialRuntimeAuthority",
    "supportAuthorityIndex",
    "logicalCandidateCoordinateAuthority.function.callInputPosition",
)
MAX_DECOMPRESSED_ARTIFACT_BYTES = 512 * 1024 * 1024


def _candidate_status_valid(status, failure, capability, profile, emissions):
    if status == "AVAILABLE":
        return capability and profile and not failure and bool(emissions)
    if status == "PRIVACY_EXCLUDED":
        return capability and profile and failure.startswith("PRIVACY:") and not emissions
    if status == "RULE_ERROR":
        return not profile and bool(failure) and not emissions
    if status == "PROFILE_ERROR":
        return capability and not profile and bool(failure) and not emissions
    return False


def _privacy_policy_allows(rule, emission):
    privacy = rule["effectivePrivacy"]
    origin_private = privacy in {"PRIVATE", "PRIVATE_AGGREGATE"}
    allow = {"CP/LOUT": False, "CP/FOUT": False,
             "FED/LOUT": False, "FED/FOUT": False}
    if rule["federatedSource"]:
        allow["FED/FOUT"] = True
    elif rule["printOrPwrite"]:
        allow["CP/LOUT"] = not origin_private
    else:
        cap_exec, cap_output = rule["capabilityExec"], rule["capabilityOutput"]
        logical_ftype = emission["fType"] if emission["fType"] != "-" \
            else emission["executionFType"]
        if origin_private:
            if cap_exec == "FED" and cap_output == "FOUT":
                allow["FED/FOUT"] = True
            if rule["dmlFunctionPlaceholder"]:
                allow["CP/LOUT"] = True
        elif privacy in {"PUBLIC", "PRIVATE_AGGREGATE_TO_PUBLIC"}:
            allow["CP/LOUT"] = True
            if cap_exec == "FED":
                allow["FED/LOUT"] = True
                if cap_output == "FOUT":
                    allow["FED/FOUT"] = True
            if rule["matrix"] and logical_ftype not in {"-", "PART", "OTHER"}:
                allow["CP/FOUT"] = True
        else:
            raise ValueError("candidate privacy value unsupported")
        if rule["multiReturnBoundary"]:
            allow["CP/FOUT"] = False
            allow["FED/LOUT"] = False
        if rule["dataOp"] == "TRANSIENTREAD":
            allow["CP/FOUT"] = False
            allow["FED/LOUT"] = False
        elif rule["dataOp"] == "TRANSIENTWRITE":
            allow["FED/LOUT"] = False
    key = emission["exec"] + "/" + emission["output"]
    if key == "FED/FOUT" and emission["derivedFedFout"]:
        return allow[key] or allow["FED/LOUT"]
    return allow.get(key, False)


def verify_candidate_privacy_closure(domain):
    """Replay every captured privacy pass from serialized primitive policy inputs.

    This proves the filter transformation for the recorded primitives. It does
    not independently derive their provenance (for example effective privacy or
    federated-source classification) from the HOP graph; that remains part of
    the artifact producer trust boundary and is why full acceptance stays closed.
    """
    passes = domain.get("candidatePrivacyClosurePasses")
    inventory = domain.get("candidateRuleFactInventory")
    if passes is None or inventory is None:
        return False
    if not isinstance(passes, list) or not passes or not isinstance(inventory, list):
        raise ValueError("candidate privacy closure evidence malformed")
    final_rows = []
    for ordinal, privacy_pass in enumerate(passes):
        if not isinstance(privacy_pass, dict) or set(privacy_pass) != {"ordinal", "rules"} or \
                privacy_pass["ordinal"] != ordinal or not isinstance(privacy_pass["rules"], list):
            raise ValueError("candidate privacy pass contract differs")
        signatures = set()
        output_rows = []
        for rule in privacy_pass["rules"]:
            required = {"ruleSignature", "inputStatus", "inputFailure", "capabilityPresent",
                        "profileAvailable", "capabilityExec", "capabilityOutput",
                        "effectivePrivacy", "matrix", "federatedSource", "printOrPwrite",
                        "dmlFunctionPlaceholder", "multiReturnBoundary", "dataOp",
                        "inputPresence", "protectedPayloadPositions", "inputEmissions",
                        "outputStatus", "outputFailure", "outputEmissionSignatures"}
            if not isinstance(rule, dict) or set(rule) != required or \
                    not isinstance(rule["ruleSignature"], str) or \
                    rule["ruleSignature"] in signatures:
                raise ValueError("candidate privacy rule contract differs")
            signatures.add(rule["ruleSignature"])
            bools = ("capabilityPresent", "profileAvailable", "matrix", "federatedSource",
                     "printOrPwrite", "dmlFunctionPlaceholder", "multiReturnBoundary")
            if any(type(rule[name]) is not bool for name in bools) or \
                    not isinstance(rule["inputPresence"], list) or \
                    any(type(value) is not bool for value in rule["inputPresence"]) or \
                    not isinstance(rule["protectedPayloadPositions"], list) or \
                    any(type(value) is not int for value in rule["protectedPayloadPositions"]) or \
                    rule["protectedPayloadPositions"] != sorted(set(rule["protectedPayloadPositions"])) or \
                    not isinstance(rule["inputEmissions"], list) or \
                    not isinstance(rule["outputEmissionSignatures"], list):
                raise ValueError("candidate privacy primitive inputs malformed")
            input_signatures = []
            emissions = []
            for emission in rule["inputEmissions"]:
                fields = {"signature", "placement", "exec", "output", "fType",
                          "executionFType", "derivedFedFout", "derivedSourcePlacement"}
                if not isinstance(emission, dict) or set(emission) != fields or \
                        type(emission["derivedFedFout"]) is not bool:
                    raise ValueError("candidate privacy emission evidence malformed")
                state = emission["placement"].split("/")
                if len(state) != 4 or state[:3] != [emission["exec"], emission["output"], emission["fType"]]:
                    raise ValueError("candidate privacy emission placement differs")
                input_signatures.append(emission["signature"])
                emissions.append(emission)
            if not _candidate_status_valid(rule["inputStatus"], rule["inputFailure"],
                                           rule["capabilityPresent"], rule["profileAvailable"],
                                           input_signatures):
                raise ValueError("candidate privacy input status evidence differs")
            if rule["inputStatus"] != "AVAILABLE":
                expected_status = rule["inputStatus"]
                expected_failure = rule["inputFailure"]
                expected = input_signatures
            else:
                retained = []
                for emission in emissions:
                    protected = rule["protectedPayloadPositions"]
                    protected_allowed = not protected or emission["exec"] == "FED" and all(
                        0 <= position < len(rule["inputPresence"])
                        and rule["inputPresence"][position] for position in protected)
                    if _privacy_policy_allows(rule, emission) and protected_allowed:
                        retained.append(emission)
                native_sources = {emission["placement"] for emission in retained
                                  if emission["derivedSourcePlacement"] == "-"}
                retained = [emission for emission in retained
                            if emission["derivedSourcePlacement"] == "-"
                            or emission["derivedSourcePlacement"] in native_sources]
                expected = [emission["signature"] for emission in retained]
                expected_status = "AVAILABLE" if retained else "PRIVACY_EXCLUDED"
                expected_failure = rule["inputFailure"] if retained else \
                    "PRIVACY:" + rule["effectivePrivacy"]
            if rule["outputStatus"] != expected_status or \
                    rule["outputFailure"] != expected_failure or \
                    rule["outputEmissionSignatures"] != expected:
                raise ValueError("candidate privacy replay differs")
            output_rows.append((rule["ruleSignature"], rule["outputStatus"],
                                rule["outputFailure"], rule["capabilityPresent"],
                                rule["profileAvailable"]))
        final_rows = output_rows
    if len(inventory) != len(final_rows):
        raise ValueError("candidate rule final inventory count differs")
    for row, expected in zip(inventory, final_rows):
        fields = {"ruleSignature", "status", "failure", "capabilityPresent",
                  "profileAvailable", "emissions"}
        actual = (row.get("ruleSignature"), row.get("status"), row.get("failure"),
                  row.get("capabilityPresent"), row.get("profileAvailable")) \
            if isinstance(row, dict) else None
        # Later grounding can replace realization-bearing emission signatures.
        # Its coverage remains opaque here; privacy owns status/failure and the
        # capability/profile authority, while the generic status invariant still
        # checks that the final row has an admissible emission cardinality.
        if not isinstance(row, dict) or set(row) != fields or actual != expected or \
                not isinstance(row["emissions"], list) or not _candidate_status_valid(
                    row["status"], row["failure"], row["capabilityPresent"],
                    row["profileAvailable"], row["emissions"]):
            raise ValueError("candidate rule final inventory differs")
    return True


def decode_java_length_fields(value, expected_fields):
    """Decode PlacementIdentity.fields(), whose lengths are Java UTF-16 units."""
    if not isinstance(value, str) or type(expected_fields) is not int or expected_fields < 0:
        raise ValueError("length-field signature malformed")
    position = 0
    fields = []
    for index in range(expected_fields):
        colon = value.find(":", position)
        if colon < 0:
            raise ValueError("length-field signature prefix absent")
        prefix = value[position:colon]
        if not prefix.isascii() or not prefix.isdigit() or str(int(prefix)) != prefix:
            raise ValueError("length-field signature length malformed")
        remaining = int(prefix)
        cursor = colon + 1
        start = cursor
        while remaining and cursor < len(value):
            units = 2 if ord(value[cursor]) > 0xffff else 1
            if units > remaining:
                raise ValueError("length-field signature splits UTF-16 character")
            remaining -= units
            cursor += 1
        if remaining:
            raise ValueError("length-field signature truncated")
        fields.append(value[start:cursor])
        if index + 1 < expected_fields:
            if cursor >= len(value) or value[cursor] != "|":
                raise ValueError("length-field signature separator absent")
            cursor += 1
        position = cursor
    if position != len(value):
        raise ValueError("length-field signature has trailing data")
    return fields


def encode_java_length_fields(fields):
    """Encode PlacementIdentity.fields() without relying on Java's UTF-16 length."""
    if not isinstance(fields, (list, tuple)) or any(not isinstance(value, str) for value in fields):
        raise ValueError("length-field values malformed")
    return "|".join(f"{len(value.encode('utf-16-le')) // 2}:{value}" for value in fields)


def _java_list(values):
    return "[" + ", ".join(values) + "]"


def _java_token_list(values):
    return ",".join(f"{len(value.encode('utf-16-le')) // 2}:{value}" for value in values)


def verify_candidate_receipt_semantics_and_source_links(domain):
    """Check the serialized candidate receipt DTO and every explicit source link.

    This proves a bijection between candidate coordinates and their clause-level
    semantic records, validates normalized identities from primitive fields, and
    checks DIRECT/RELOCATION/LOGICAL_TRANSIENT bindings against serialized graph
    authority. Assignment-dependent feasibility remains outside this predicate.
    """
    rows = domain.get("candidateReceiptSemanticFacts")
    references = domain.get("candidateRealizationReferenceFacts")
    edges = domain.get("compiledCandidateInputEdges")
    logical = domain.get("logicalCandidateReachability")
    actions = domain.get("relocationActionFacts")
    candidates = domain.get("candidateDomains")
    inventory = domain.get("candidateRuleFactInventory")
    nodes = domain.get("nodes")
    evidence = (rows, references, edges, logical, actions)
    if all(value is None for value in evidence):
        return False
    if any(value is None for value in evidence) or not isinstance(candidates, list) or \
            not isinstance(inventory, list) or not isinstance(nodes, list) or \
            not all(isinstance(value, list) for value in (rows, references, edges, actions)) or \
            not isinstance(logical, dict) or set(logical) != {"transient", "function", "boundaries"}:
        raise ValueError("candidate receipt semantic evidence incomplete")

    node_by_owner = {}
    for node in nodes:
        if not isinstance(node, list) or len(node) != 5 or \
                not all(isinstance(node[index], str) for index in (0, 1, 2)) or \
                not isinstance(node[3], list) or node[0] in node_by_owner:
            raise ValueError("candidate receipt node evidence malformed")
        node_by_owner[node[0]] = node

    available = {}
    for fact in inventory:
        required = {"ruleSignature", "status", "failure", "capabilityPresent",
                    "profileAvailable", "emissions"}
        if not isinstance(fact, dict) or set(fact) != required or \
                not isinstance(fact["ruleSignature"], str) or \
                not isinstance(fact["emissions"], list) or \
                fact["ruleSignature"] in available:
            raise ValueError("candidate rule inventory evidence malformed")
        if fact["status"] == "AVAILABLE":
            emissions = {}
            for emission in fact["emissions"]:
                if not isinstance(emission, dict) or set(emission) != {"selection", "realizations"} or \
                        not isinstance(emission["selection"], str) or \
                        not isinstance(emission["realizations"], list) or \
                        not emission["realizations"] or \
                        any(not isinstance(value, str) for value in emission["realizations"]) or \
                        len(set(emission["realizations"])) != len(emission["realizations"]) or \
                        emission["selection"] in emissions:
                    raise ValueError("candidate rule emission inventory malformed")
                emissions[emission["selection"]] = set(emission["realizations"])
            available[fact["ruleSignature"]] = emissions

    reference_by_signature = {}
    reference_fields = {"reference", "rule", "owner", "placement", "realization"}
    for reference in references:
        if not isinstance(reference, dict) or set(reference) != reference_fields or \
                any(not isinstance(reference[field], str) for field in reference_fields) or \
                reference["reference"] in reference_by_signature:
            raise ValueError("candidate realization reference evidence malformed")
        if reference["reference"] != encode_java_length_fields(
                (reference["rule"], reference["realization"])):
            raise ValueError("candidate realization reference identity differs")
        realization_fields = decode_java_length_fields(reference["realization"], 4)
        emission_state = realization_fields[0]
        marker = "|derivedFedFout="
        if marker not in emission_state or emission_state.rsplit(marker, 1)[0] != reference["placement"]:
            raise ValueError("candidate realization placement differs")
        reference_by_signature[reference["reference"]] = reference
    realization_authority = {(hashlib.sha256(reference["rule"].encode("utf-8")).hexdigest(),
                              reference["realization"]) for reference in references}
    for rule_hash, emissions in available.items():
        for realizations in emissions.values():
            for realization in realizations:
                if (rule_hash, realization) not in realization_authority:
                    raise ValueError("candidate rule emission lacks realization authority")

    compiled_edges = set()
    for edge in edges:
        if not isinstance(edge, dict) or set(edge) != {"producer", "consumer", "inputPosition"} or \
                not isinstance(edge["producer"], str) or not isinstance(edge["consumer"], str) or \
                type(edge["inputPosition"]) is not int or edge["inputPosition"] < 0:
            raise ValueError("compiled candidate input edge malformed")
        key = (edge["producer"], edge["consumer"], edge["inputPosition"])
        if key in compiled_edges:
            raise ValueError("compiled candidate input edge duplicate")
        compiled_edges.add(key)

    transient_relations = set()
    for relation in logical["transient"]:
        fields = {"source", "target", "inputPosition", "compatibility"}
        if not isinstance(relation, dict) or set(relation) != fields or \
                not isinstance(relation["source"], str) or not isinstance(relation["target"], str) or \
                type(relation["inputPosition"]) is not int or relation["inputPosition"] < 0 or \
                not isinstance(relation["compatibility"], list):
            raise ValueError("logical transient reachability evidence malformed")
        for compatible in relation["compatibility"]:
            if not isinstance(compatible, dict) or set(compatible) != {
                    "sourceRealization", "readerRealization"}:
                raise ValueError("logical transient compatibility evidence malformed")
            transient_relations.add((relation["source"], relation["target"],
                                     relation["inputPosition"],
                                     compatible["sourceRealization"],
                                     compatible["readerRealization"]))
    function_relations = set()
    for relation in logical["function"]:
        fields = {"source", "boundary", "target", "inputPosition"}
        if not isinstance(relation, dict) or set(relation) != fields or \
                any(not isinstance(relation[field], str) for field in ("source", "boundary", "target")) or \
                type(relation["inputPosition"]) is not int or relation["inputPosition"] < 0:
            raise ValueError("logical function reachability evidence malformed")
        key = (relation["source"], relation["target"], relation["inputPosition"])
        if key in function_relations:
            raise ValueError("logical function reachability evidence duplicate")
        function_relations.add(key)
    boundary_relations = set()
    for relation in logical["boundaries"]:
        if not isinstance(relation, dict) or set(relation) != {"source", "target"} or \
                not isinstance(relation["source"], str) or not isinstance(relation["target"], str):
            raise ValueError("logical boundary reachability evidence malformed")
        key = (relation["source"], relation["target"])
        if key in boundary_relations:
            raise ValueError("logical boundary reachability evidence duplicate")
        boundary_relations.add(key)

    action_by_signature = {}
    action_fields = {"action", "sourceValueVersion", "targetPlacement", "materializationFType",
                     "durableAnchor", "statementBlockScope", "compatibleConsumers",
                     "directSourcePlacements", "obligations"}
    for action in actions:
        if not isinstance(action, dict) or set(action) != action_fields or \
                any(not isinstance(action[field], str) for field in (
                    "action", "sourceValueVersion", "targetPlacement", "materializationFType",
                    "durableAnchor", "statementBlockScope")) or \
                not isinstance(action["compatibleConsumers"], list) or \
                not isinstance(action["directSourcePlacements"], list) or \
                not isinstance(action["obligations"], list) or action["action"] in action_by_signature:
            raise ValueError("relocation action evidence malformed")
        expected_action = encode_java_length_fields((action["sourceValueVersion"],
            action["targetPlacement"], action["materializationFType"], action["durableAnchor"],
            action["statementBlockScope"], _java_token_list(action["compatibleConsumers"])))
        if action["action"] != expected_action:
            raise ValueError("relocation action identity differs")
        if any(not isinstance(value, str) for value in action["compatibleConsumers"] +
               action["directSourcePlacements"]):
            raise ValueError("relocation action source/consumer evidence malformed")
        obligation_keys = set()
        for obligation in action["obligations"]:
            required = {"consumer", "inputPosition", "sourceValueVersion", "requiredPlacement"}
            if not isinstance(obligation, dict) or set(obligation) != required or \
                    not isinstance(obligation["consumer"], str) or \
                    type(obligation["inputPosition"]) is not int or obligation["inputPosition"] < 0 or \
                    obligation["sourceValueVersion"] != action["sourceValueVersion"] or \
                    obligation["requiredPlacement"] != action["targetPlacement"] or \
                    obligation["consumer"] not in action["compatibleConsumers"]:
                raise ValueError("relocation action obligation evidence malformed")
            key = (obligation["consumer"], obligation["inputPosition"],
                   obligation["sourceValueVersion"], obligation["requiredPlacement"])
            if key in obligation_keys:
                raise ValueError("relocation action obligation duplicate")
            obligation_keys.add(key)
        action_by_signature[action["action"]] = action

    expected_receipts = {}
    for coordinate_index, coordinate in enumerate(candidates):
        if not isinstance(coordinate, list) or len(coordinate) != 2 or \
                not isinstance(coordinate[0], str) or not isinstance(coordinate[1], list):
            raise ValueError("candidate coordinate malformed")
        for candidate_index, receipt in enumerate(coordinate[1]):
            key = (coordinate_index, candidate_index)
            if not isinstance(receipt, str) or key in expected_receipts:
                raise ValueError("candidate receipt coordinate malformed")
            expected_receipts[key] = (coordinate[0], receipt)
    if len(rows) != len(expected_receipts):
        raise ValueError("candidate receipt semantic coverage differs")

    row_fields = {"coordinateIndex", "candidateIndex", "rule", "owner",
                  "orderedInputs", "emission", "placement", "realization",
                  "reference", "proofDependencies", "requiredInputSupport",
                  "inputBindings", "nativeWorkerPoolWitness", "nativeWorkerPoolLayoutExact",
                  "derivedFoutAction", "independentReachabilityClass"}
    seen = set()
    simple_local = 0
    linked = 0
    unsupported_links = 0
    for row in rows:
        if not isinstance(row, dict) or set(row) != row_fields or \
                type(row["coordinateIndex"]) is not int or type(row["candidateIndex"]) is not int or \
                not isinstance(row["orderedInputs"], list) or \
                not isinstance(row["proofDependencies"], list) or \
                not isinstance(row["requiredInputSupport"], list) or \
                not isinstance(row["inputBindings"], list) or \
                type(row["nativeWorkerPoolLayoutExact"]) is not bool:
            raise ValueError("candidate receipt semantic row malformed")
        key = (row["coordinateIndex"], row["candidateIndex"])
        expected = expected_receipts.get(key)
        if key in seen or expected is None or expected[0] != row["owner"]:
            raise ValueError("candidate receipt semantic coordinate differs")
        seen.add(key)
        receipt = expected[1]
        if row["reference"] != encode_java_length_fields((row["rule"], row["realization"])):
            raise ValueError("candidate receipt realization reference differs")
        own_reference = reference_by_signature.get(row["reference"])
        if own_reference is None or own_reference["owner"] != row["owner"] or \
                own_reference["placement"] != row["placement"]:
            raise ValueError("candidate receipt realization authority differs")
        receipt_fields = decode_java_length_fields(receipt, 5)
        if row["placement"] not in node_by_owner.get(row["owner"], [None, None, None, []])[3]:
            raise ValueError("candidate receipt placement is outside its owner domain")
        emission_marker = "|executionFType="
        if emission_marker not in row["emission"] or \
                decode_java_length_fields(row["realization"], 4)[0] != \
                row["emission"].split(emission_marker, 1)[0]:
            raise ValueError("candidate receipt emission/realization identity differs")
        derived_action = candidate_derived_fout_action(receipt)
        if ("-" if derived_action is None else derived_action) != row["derivedFoutAction"]:
            raise ValueError("candidate receipt derived FOUT identity differs")
        rule_hash = hashlib.sha256(row["rule"].encode("utf-8")).hexdigest()
        emission_realizations = available.get(rule_hash, {}).get(row["emission"])
        if emission_realizations is None or row["realization"] not in emission_realizations:
            raise ValueError("candidate receipt lacks final available rule emission")

        binding_signatures = []
        all_links = True
        for binding in row["inputBindings"]:
            fields = {"signature", "inputPosition", "source", "sourceOwner", "sourcePlacement",
                      "kind", "relocationAction"}
            if not isinstance(binding, dict) or set(binding) != fields or \
                    type(binding["inputPosition"]) is not int or binding["inputPosition"] < 0 or \
                    binding["kind"] not in {"DIRECT", "RELOCATION", "LOGICAL_TRANSIENT"}:
                raise ValueError("candidate input binding evidence malformed")
            expected_signature = encode_java_length_fields((str(binding["inputPosition"]),
                binding["source"], binding["kind"], binding["relocationAction"]))
            if binding["signature"] != expected_signature:
                raise ValueError("candidate input binding identity differs")
            source = reference_by_signature.get(binding["source"])
            if source is None or source["owner"] != binding["sourceOwner"] or \
                    source["placement"] != binding["sourcePlacement"]:
                raise ValueError("candidate input binding source authority differs")
            if binding["kind"] == "LOGICAL_TRANSIENT":
                if binding["relocationAction"] != "-" or (
                        binding["sourceOwner"], row["owner"], binding["inputPosition"],
                        binding["source"], row["reference"]) not in transient_relations:
                    all_links = False
            else:
                physical_link = (binding["sourceOwner"], row["owner"],
                                 binding["inputPosition"])
                if physical_link not in compiled_edges and physical_link not in function_relations and \
                        (binding["sourceOwner"], row["owner"]) not in boundary_relations:
                    all_links = False
                if binding["kind"] == "DIRECT" and binding["relocationAction"] != "-":
                    raise ValueError("direct candidate binding carries relocation action")
                if binding["kind"] == "RELOCATION":
                    action = action_by_signature.get(binding["relocationAction"])
                    source_node = node_by_owner.get(binding["sourceOwner"])
                    if action is None or source_node is None or source_node[2] != action["sourceValueVersion"]:
                        raise ValueError("candidate relocation binding lacks graph-owned source action")
                    obligation = {"consumer": row["owner"],
                                  "inputPosition": binding["inputPosition"],
                                  "sourceValueVersion": action["sourceValueVersion"],
                                  "requiredPlacement": row["placement"]}
                    if obligation not in action["obligations"]:
                        raise ValueError("candidate relocation binding lacks exact graph obligation")
            binding_signatures.append(binding["signature"])
        if row["requiredInputSupport"] != sorted(set(
                binding["source"] for binding in row["inputBindings"])):
            raise ValueError("candidate required input support differs")
        expected_clause = "proofs=" + _java_list(row["proofDependencies"]) + \
            "|inputs=" + _java_list(binding_signatures) + \
            "|nativePool=" + row["nativeWorkerPoolWitness"]
        if row["nativeWorkerPoolWitness"] != "-" and not row["nativeWorkerPoolLayoutExact"]:
            expected_clause += "|nativePoolLayout=dynamic"
        if receipt_fields != [row["rule"], row["emission"], row["realization"],
                              expected_clause, ""]:
            raise ValueError("candidate support clause identity differs")
        simple = row["placement"].startswith("CP/LOUT/") and not row["inputBindings"] and \
            not row["requiredInputSupport"] and row["derivedFoutAction"] == "-"
        expected_class = "SIMPLE_LOCAL" if simple else "UNSUPPORTED_COMPLEX"
        if row["independentReachabilityClass"] != expected_class:
            raise ValueError("candidate independent reachability class differs")
        simple_local += simple
        linked += all_links
        unsupported_links += not all_links
    return {"receipts": len(rows), "realizationReferences": len(references),
            "simpleLocalReceipts": simple_local, "sourceLinkedReceipts": linked,
            "unsupportedSourceLinkReceipts": unsupported_links}


def verify_relocation_receipt_semantics_and_action_links(domain):
    """Rebuild each relocation demand/choice identity and bind it to a graph action."""
    coordinates = domain.get("relocationDomains")
    actions = domain.get("relocationActionFacts")
    if actions is None:
        return False
    if not isinstance(coordinates, list) or not isinstance(actions, list):
        raise ValueError("relocation receipt semantic evidence incomplete")
    action_by_signature = {}
    for action in actions:
        if not isinstance(action, dict) or not isinstance(action.get("action"), str) or \
                action["action"] in action_by_signature:
            raise ValueError("relocation receipt action evidence malformed")
        expected = encode_java_length_fields((action.get("sourceValueVersion"),
            action.get("targetPlacement"), action.get("materializationFType"),
            action.get("durableAnchor"), action.get("statementBlockScope"),
            _java_token_list(action.get("compatibleConsumers", []))))
        if action["action"] != expected:
            raise ValueError("relocation receipt action identity differs")
        action_by_signature[action["action"]] = action
    receipts = 0
    for coordinate in coordinates:
        if not isinstance(coordinate, list) or len(coordinate) != 2 or \
                not isinstance(coordinate[0], str) or not isinstance(coordinate[1], list) or \
                len(coordinate[1]) != len(set(coordinate[1])):
            raise ValueError("relocation receipt coordinate malformed")
        demand = decode_java_length_fields(coordinate[0], 5)
        source_version, consumer, position_text, required_placement, _ = demand
        if not position_text.isascii() or not position_text.isdigit():
            raise ValueError("relocation demand input position malformed")
        position = int(position_text)
        for receipt in coordinate[1]:
            choice = decode_java_length_fields(receipt, 2)
            if choice[0] != coordinate[0]:
                raise ValueError("relocation choice demand identity differs")
            action = action_by_signature.get(choice[1])
            if action is None or action["sourceValueVersion"] != source_version or \
                    action["targetPlacement"] != required_placement or \
                    consumer not in action["compatibleConsumers"]:
                raise ValueError("relocation choice lacks compatible graph action")
            obligation = {"consumer": consumer, "inputPosition": position,
                          "sourceValueVersion": source_version,
                          "requiredPlacement": required_placement}
            if obligation not in action["obligations"]:
                raise ValueError("relocation choice lacks exact graph obligation")
            receipts += 1
    return {"demandCoordinates": len(coordinates), "choiceReceipts": receipts,
            "graphActions": len(actions)}


def candidate_assignment_locally_compatible(domain, placement_assignment,
                                            candidate_assignment, relocation_assignment):
    """Evaluate the serialized local candidate/reference gates for one assignment.

    Indices follow the native domain: placements are zero-based alternatives;
    candidate and relocation index zero means no selection. This deliberately
    returns ``None`` when boundary/derived/worker-pool semantics outside the raw
    DTO subset are needed, so callers cannot mistake a partial decision for full
    production acceptance.
    """
    evidence = verify_candidate_receipt_semantics_and_source_links(domain)
    relocation_evidence = verify_relocation_receipt_semantics_and_action_links(domain)
    if not evidence or evidence["unsupportedSourceLinkReceipts"] or not relocation_evidence:
        return None
    placements = domain.get("placementDomains")
    candidates = domain.get("candidateDomains")
    relocations = domain.get("relocationDomains")
    logical = domain.get("logicalCandidateReachability")
    if not all(isinstance(value, list) for value in (
            placement_assignment, candidate_assignment, relocation_assignment)) or \
            len(placement_assignment) != len(placements) or \
            len(candidate_assignment) != len(candidates) or \
            len(relocation_assignment) != len(relocations):
        raise ValueError("candidate local assignment arity differs")
    # Exact boundary compatibility and output materialization need additional
    # assignment semantics; preserve the fail-closed unsupported result.
    if logical["function"] or logical["boundaries"]:
        return None
    semantic_by_coordinate = {(row["coordinateIndex"], row["candidateIndex"]): row
                              for row in domain["candidateReceiptSemanticFacts"]}
    selected = {}
    for coordinate, (placement_row, candidate_row) in enumerate(zip(placements, candidates)):
        placement_index = placement_assignment[coordinate]
        candidate_index = candidate_assignment[coordinate]
        if type(placement_index) is not int or placement_index < 0 or \
                placement_index >= len(placement_row[1]) or type(candidate_index) is not int or \
                candidate_index < 0 or candidate_index > len(candidate_row[1]):
            raise ValueError("candidate local assignment index outside domain")
        state = placement_row[1][placement_index]
        active = [row for (owner_coordinate, _), row in semantic_by_coordinate.items()
                  if owner_coordinate == coordinate and row["placement"] == state]
        if not active:
            if candidate_index:
                return False
            continue
        if not candidate_index:
            return False
        row = semantic_by_coordinate[(coordinate, candidate_index - 1)]
        if row["placement"] != state:
            return False
        if row["derivedFoutAction"] != "-" or row["nativeWorkerPoolWitness"] != "-":
            return None
        selected[row["owner"]] = row
    for row in selected.values():
        for support in row["requiredInputSupport"]:
            if not any(source["reference"] == support for source in selected.values()):
                return False
        for binding in row["inputBindings"]:
            source = selected.get(binding["sourceOwner"])
            if source is None or source["reference"] != binding["source"] or \
                    source["placement"] != binding["sourcePlacement"]:
                return False
            if binding["kind"] == "RELOCATION":
                matched = False
                for demand_index, coordinate in enumerate(relocations):
                    demand = decode_java_length_fields(coordinate[0], 5)
                    if demand[1] != row["owner"] or int(demand[2]) != binding["inputPosition"] or \
                            demand[3] != row["placement"]:
                        continue
                    choice_index = relocation_assignment[demand_index]
                    if type(choice_index) is not int or choice_index < 0 or \
                            choice_index > len(coordinate[1]):
                        raise ValueError("relocation local assignment index outside domain")
                    if choice_index and decode_java_length_fields(
                            coordinate[1][choice_index - 1], 2)[1] == binding["relocationAction"]:
                        matched = True
                if not matched:
                    return False
    for relation in logical["transient"]:
        source = selected.get(relation["source"])
        reader = selected.get(relation["target"])
        if source is None or reader is None or not any(
                edge["sourceRealization"] == source["reference"] and
                edge["readerRealization"] == reader["reference"]
                for edge in relation["compatibility"]):
            return False
    return True


def candidate_derived_fout_action(receipt):
    """Extract the exact derived action from a serialized candidate receipt."""
    emission = decode_java_length_fields(receipt, 5)[1]
    prefix, marker, action = emission.rpartition("|derivedAction=")
    if not marker or not action:
        raise ValueError("candidate receipt derived action field malformed")
    _, execution_marker, execution_ftype = prefix.rpartition("|executionFType=")
    if not execution_marker or execution_ftype not in {
            "-", "ROW", "COL", "FULL", "BROADCAST", "PART", "OTHER"}:
        raise ValueError("candidate receipt execution FType field malformed")
    return None if action == "-" else action


def verify_derived_fout_graph_ownership(domain):
    """Prove every serialized candidate's derived action has an identity binding."""
    graph_actions = domain.get("derivedFoutActions")
    bindings = domain.get("derivedFoutOwnershipBindings")
    candidates = domain.get("candidateDomains")
    if graph_actions is None or bindings is None:
        return False
    if not isinstance(graph_actions, list) or not isinstance(bindings, list) or \
            not isinstance(candidates, list) or \
            any(not isinstance(action, str) or not action or action == "-"
                for action in graph_actions) or len(graph_actions) != len(set(graph_actions)):
        raise ValueError("derived FOUT graph action universe malformed")
    expected = []
    for coordinate_index, coordinate in enumerate(candidates):
        if not isinstance(coordinate, list) or len(coordinate) != 2 or \
                not isinstance(coordinate[0], str) or not isinstance(coordinate[1], list):
            raise ValueError("derived FOUT candidate coordinate malformed")
        if len(coordinate[1]) != len(set(coordinate[1])):
            raise ValueError("derived FOUT candidate receipt duplicate")
        for candidate_index, receipt in enumerate(coordinate[1]):
            action = candidate_derived_fout_action(receipt)
            if action is not None:
                expected.append((coordinate_index, candidate_index, action))
    if len(bindings) != len(expected):
        raise ValueError("derived FOUT identity binding coverage differs")
    seen = set()
    for binding, (coordinate_index, candidate_index, action) in zip(bindings, expected):
        if not isinstance(binding, list) or len(binding) != 3 or \
                any(type(index) is not int for index in binding):
            raise ValueError("derived FOUT identity binding malformed")
        if tuple(binding[:2]) != (coordinate_index, candidate_index):
            raise ValueError("derived FOUT identity binding candidate differs")
        action_index = binding[2]
        if action_index < 0 or action_index >= len(graph_actions) or \
                graph_actions[action_index] != action:
            raise ValueError("candidate derived FOUT action is not graph-owned")
        if tuple(binding) in seen:
            raise ValueError("derived FOUT identity binding duplicate")
        seen.add(tuple(binding))
    return True


def verify_nondecision_candidate_owner_classification(domain):
    """Replay the Java owner-role classifier from serialized graph descriptors.

    This checks the classification of every captured non-decision candidate owner.
    It deliberately does not assert that the captured owner list is a complete
    projection of the un-serialized candidate-rule facts.
    """
    rows = domain.get("nonDecisionCandidateOwners")
    nodes = domain.get("nodes")
    placements = domain.get("placementDomains")
    if rows is None:
        return False
    if not isinstance(rows, list) or not isinstance(nodes, list) or \
            not isinstance(placements, list):
        raise ValueError("non-decision candidate owner classification inputs malformed")
    node_by_owner = {}
    for node in nodes:
        if not isinstance(node, list) or len(node) != 5 or \
                not isinstance(node[0], str) or not isinstance(node[1], str) or \
                not isinstance(node[3], list) or node[0] in node_by_owner:
            raise ValueError("non-decision candidate owner node descriptor malformed")
        node_by_owner[node[0]] = node
    decision_owners = set()
    for placement in placements:
        if not isinstance(placement, list) or len(placement) != 2 or \
                not isinstance(placement[0], str):
            raise ValueError("non-decision candidate owner placement descriptor malformed")
        decision_owners.add(placement[0])
    captured = set()
    for row in rows:
        if not isinstance(row, list) or len(row) != 2 or \
                not isinstance(row[0], str) or not isinstance(row[1], str):
            raise ValueError("non-decision candidate owner record malformed")
        owner, role = row
        if owner in captured:
            raise ValueError("non-decision candidate owner duplicate")
        captured.add(owner)
        if owner in decision_owners:
            raise ValueError("non-decision candidate owner is a decision coordinate")
        node = node_by_owner.get(owner)
        expected = "INERT_FUNCTION_TEMPLATE" if node is not None and \
            node[1] == "FUNCTION_BODY_NON_EMITTED" and not node[3] else "UNRESOLVED"
        if role != expected:
            raise ValueError("non-decision candidate owner classification differs")
    return True


def _placement_parts(state):
    if not isinstance(state, str):
        raise ValueError("candidate activation placement malformed")
    fields = state.split("/")
    if len(fields) != 4:
        raise ValueError("candidate activation placement malformed")
    return fields


def _fout_alternatives(placements, coordinate, ftype):
    if ftype == "-":
        return []
    return [index for index, state in enumerate(placements[coordinate][1])
            if _placement_parts(state)[1:3] == ["FOUT", ftype]]


def _partition_authority_signature(authority):
    fields = {"kind", "ftype", "layoutExact", "partitions"}
    if not isinstance(authority, dict) or set(authority) != fields or \
            authority["kind"] != "EXACT_LAYOUT" or authority["layoutExact"] is not True or \
            not isinstance(authority["ftype"], str) or not authority["ftype"] or \
            not isinstance(authority["partitions"], list) or not authority["partitions"]:
        raise ValueError("exact worker-pool authority malformed")
    partitions = []
    for partition in authority["partitions"]:
        if not isinstance(partition, dict) or set(partition) != {"worker", "begin", "end"} or \
                not isinstance(partition["worker"], str) or not partition["worker"] or \
                not isinstance(partition["begin"], list) or not partition["begin"] or \
                not isinstance(partition["end"], list) or \
                len(partition["begin"]) != len(partition["end"]) or \
                any(type(value) is not int for value in partition["begin"] + partition["end"]):
            raise ValueError("exact worker-pool partition malformed")
        begin = _java_token_list([str(value) for value in partition["begin"]])
        end = _java_token_list([str(value) for value in partition["end"]])
        partitions.append(encode_java_length_fields((partition["worker"], begin, end)))
    return authority["ftype"], _java_token_list(partitions)


def _verify_support_authority(authority):
    if not isinstance(authority, dict):
        raise ValueError("candidate support authority malformed")
    kind = authority.get("kind")
    if kind == "ABSENT":
        if authority != {"kind": "ABSENT", "ftype": "-", "layoutExact": False}:
            raise ValueError("absent candidate support authority malformed")
    elif kind == "DYNAMIC_RESIDENCY":
        if set(authority) != {"kind", "ftype", "layoutExact", "endpoints"} or \
                authority.get("layoutExact") is not False or \
                not isinstance(authority.get("ftype"), str) or authority["ftype"] == "-" or \
                not isinstance(authority.get("endpoints"), list) or not authority["endpoints"] or \
                authority["endpoints"] != sorted(set(authority["endpoints"])) or \
                any(not isinstance(endpoint, str) or not endpoint
                    for endpoint in authority["endpoints"]):
            raise ValueError("dynamic candidate support authority malformed")
    elif kind == "EXACT_LAYOUT":
        _partition_authority_signature(authority)
    else:
        raise ValueError("candidate support authority kind unsupported")


def verify_candidate_assignment_primitives(domain):
    """Recompute the serialized coordinate-level inputs to candidate feasibility.

    Producer interpretation/verdict/reason fields are deliberately ignored.  The
    DTO still does not contain a complete independent assignment interpreter, so
    successful verification is structural evidence and cannot close P acceptance.
    """
    facts = domain.get("candidateReceiptActivationFacts")
    supports = domain.get("candidateRealizationSupportAuthorities")
    logical_authority = domain.get("logicalCandidateCoordinateAuthority")
    relocation_pools = domain.get("relocationWorkerPoolAuthorities")
    required = (facts, supports, logical_authority, relocation_pools)
    if all(value is None for value in required):
        return False
    if any(value is None for value in required) or not isinstance(facts, list) or \
            not isinstance(supports, list) or not isinstance(logical_authority, dict) or \
            not isinstance(relocation_pools, list):
        raise ValueError("candidate assignment primitive evidence incomplete")

    nodes = domain.get("nodes")
    placements = domain.get("placementDomains")
    candidates = domain.get("candidateDomains")
    semantics = domain.get("candidateReceiptSemanticFacts")
    references = domain.get("candidateRealizationReferenceFacts")
    edges = domain.get("compiledCandidateInputEdges")
    logical = domain.get("logicalCandidateReachability")
    actions = domain.get("relocationActionFacts")
    derived_actions = domain.get("derivedFoutActions")
    if not all(isinstance(value, list) for value in (
            nodes, placements, candidates, semantics, references, edges, actions,
            derived_actions)) or not isinstance(logical, dict):
        raise ValueError("candidate assignment primitive domains absent")
    if len(placements) != len(candidates):
        raise ValueError("candidate assignment primitive coordinate arity differs")

    node_index = {}
    for index, node in enumerate(nodes):
        if not isinstance(node, list) or len(node) != 5 or not isinstance(node[0], str) or \
                node[0] in node_index:
            raise ValueError("candidate activation node universe malformed")
        node_index[node[0]] = index
    coordinate = {}
    for index, row in enumerate(placements):
        if not isinstance(row, list) or len(row) != 2 or not isinstance(row[0], str) or \
                not isinstance(row[1], list) or row[0] in coordinate:
            raise ValueError("candidate activation placement universe malformed")
        coordinate[row[0]] = index

    if len(supports) != len(references):
        raise ValueError("candidate support authority coverage differs")
    for index, row in enumerate(supports):
        if not isinstance(row, dict) or set(row) != {"referenceIndex", "supportAuthorities"} or \
                row["referenceIndex"] != index or not isinstance(row["supportAuthorities"], list) or \
                not row["supportAuthorities"]:
            raise ValueError("candidate support authority index differs")
        for authority in row["supportAuthorities"]:
            _verify_support_authority(authority)

    if len(relocation_pools) != len(actions):
        raise ValueError("relocation worker-pool authority coverage differs")
    for index, (pool, action) in enumerate(zip(relocation_pools, actions)):
        ftype, partitions = _partition_authority_signature(pool)
        anchor = decode_java_length_fields(action["durableAnchor"], 3)
        if anchor[1:] != [ftype, partitions]:
            raise ValueError("relocation worker-pool authority/action differs")

    logical_fields = {
        "transient": ("source", "target", "sourceNodeIndex", "sourcePlacementCoordinateIndex",
                      "targetNodeIndex", "targetPlacementCoordinateIndex", "inputPosition",
                      "compatibility"),
        "function": ("source", "boundary", "target", "sourceNodeIndex",
                     "sourcePlacementCoordinateIndex", "boundaryNodeIndex",
                     "boundaryPlacementCoordinateIndex", "targetNodeIndex",
                     "targetPlacementCoordinateIndex", "callInputPosition", "logicalPosition"),
        "boundaries": ("source", "target", "sourceNodeIndex", "sourcePlacementCoordinateIndex",
                       "targetNodeIndex", "targetPlacementCoordinateIndex"),
    }
    if set(logical_authority) != set(logical_fields) or set(logical) != set(logical_fields):
        raise ValueError("logical candidate coordinate authority contract differs")
    logical_by_kind = {}
    for kind, fields in logical_fields.items():
        raw_rows, authority_rows = logical[kind], logical_authority[kind]
        if not isinstance(raw_rows, list) or not isinstance(authority_rows, list) or \
                len(raw_rows) != len(authority_rows):
            raise ValueError("logical candidate coordinate authority coverage differs")
        checked = []
        for raw, authority in zip(raw_rows, authority_rows):
            if not isinstance(raw, dict) or not isinstance(authority, dict) or \
                    set(authority) != set(fields):
                raise ValueError("logical candidate coordinate authority malformed")
            expected = dict(raw)
            if kind == "function":
                expected.pop("inputPosition", None)
                expected["logicalPosition"] = raw.get("inputPosition")
                call_position = authority.get("callInputPosition")
                if type(call_position) is not int or call_position < 0:
                    raise ValueError("logical function call input authority malformed")
                expected["callInputPosition"] = call_position
            for name in ("source", "target"):
                expected[name + "NodeIndex"] = node_index.get(raw[name], -1)
                expected[name + "PlacementCoordinateIndex"] = coordinate.get(raw[name], -1)
            if kind == "function":
                expected["boundaryNodeIndex"] = node_index.get(raw["boundary"], -1)
                expected["boundaryPlacementCoordinateIndex"] = coordinate.get(raw["boundary"], -1)
            if authority != expected or any(expected[name + "NodeIndex"] < 0
                    for name in (("source", "target", "boundary") if kind == "function"
                                 else ("source", "target"))):
                raise ValueError("logical candidate coordinate authority differs")
            checked.append(authority)
        logical_by_kind[kind] = checked

    reference_index = {row["reference"]: index for index, row in enumerate(references)}
    if len(reference_index) != len(references):
        raise ValueError("candidate activation reference universe duplicate")
    action_index = {row["action"]: index for index, row in enumerate(actions)}
    if len(action_index) != len(actions):
        raise ValueError("candidate activation action universe duplicate")
    derived_index = {value: index for index, value in enumerate(derived_actions)}
    semantic_by_coordinate = {(row["coordinateIndex"], row["candidateIndex"]): row
                              for row in semantics}
    expected_count = sum(len(row[1]) for row in candidates)
    if len(facts) != expected_count or len(semantic_by_coordinate) != expected_count:
        raise ValueError("candidate activation fact coverage differs")

    compiled = {}
    for edge in edges:
        compiled.setdefault((edge["consumer"], edge["inputPosition"]), []).append(edge["producer"])
    seen = set()
    for fact in facts:
        fact_fields = {"coordinateIndex", "candidateIndex", "receiptSha256",
            "ownerPlacementCoordinateIndex", "activePlacementAlternativeIndex",
            "realizationReferenceIndex", "supportAuthorityIndex", "functionCallBoundary",
            "consumerExec", "executionFType", "derivedFedFout", "presentInputCount",
            "presentPhysicalInputCount", "inputs", "derivedFoutAuthority",
            "specialRuntimeAuthority", "candidateFeasibilityVerdict", "interpretation",
            "unsupportedReasons"}
        if not isinstance(fact, dict) or set(fact) != fact_fields:
            raise ValueError("candidate activation fact malformed")
        key = (fact["coordinateIndex"], fact["candidateIndex"])
        semantic = semantic_by_coordinate.get(key)
        if key in seen or semantic is None:
            raise ValueError("candidate activation coordinate differs")
        seen.add(key)
        ci, ai = key
        owner = candidates[ci][0]
        receipt = candidates[ci][1][ai]
        placement = semantic["placement"]
        try:
            placement_alternative = placements[ci][1].index(placement)
        except ValueError as error:
            raise ValueError("candidate activation placement differs") from error
        ref_index = reference_index.get(semantic["reference"], -1)
        expected_scalar = {
            "receiptSha256": hashlib.sha256(receipt.encode("utf-8")).hexdigest(),
            "ownerPlacementCoordinateIndex": ci,
            "activePlacementAlternativeIndex": placement_alternative,
            "realizationReferenceIndex": ref_index,
            "functionCallBoundary": nodes[node_index[owner]][1] == "FUNCTION_CALL",
            "consumerExec": _placement_parts(placement)[0],
        }
        emission_prefix = semantic["emission"].split("|executionFType=", 1)
        if len(emission_prefix) != 2 or "|derivedFedFout=" not in emission_prefix[0]:
            raise ValueError("candidate activation emission semantics malformed")
        expected_scalar["executionFType"] = emission_prefix[1].split("|", 1)[0]
        expected_scalar["derivedFedFout"] = emission_prefix[0].rsplit(
            "|derivedFedFout=", 1)[1] == "true"
        for name, value in expected_scalar.items():
            if fact[name] != value:
                raise ValueError("candidate activation " + name + " differs")
        support_index = fact["supportAuthorityIndex"]
        if ref_index < 0 or references[ref_index]["owner"] != owner or \
                references[ref_index]["placement"] != placement or \
                type(support_index) is not int or support_index < 0 or \
                support_index >= len(supports[ref_index]["supportAuthorities"]):
            raise ValueError("candidate activation support/reference ownership differs")
        selected_support = supports[ref_index]["supportAuthorities"][support_index]
        if semantic["nativeWorkerPoolWitness"] != "-" and \
                semantic["nativeWorkerPoolLayoutExact"] is True:
            ftype, partitions = _partition_authority_signature(selected_support)
            witness = decode_java_length_fields(semantic["nativeWorkerPoolWitness"], 3)
            if witness[1:] != [ftype, partitions]:
                raise ValueError("candidate activation selected worker-pool authority differs")

        ordered_inputs = semantic["orderedInputs"]
        if not isinstance(fact["inputs"], list) or len(fact["inputs"]) != len(ordered_inputs):
            raise ValueError("candidate activation input coverage differs")
        present_count = physical_count = 0
        semantic_bindings = {}
        for binding in semantic["inputBindings"]:
            semantic_bindings.setdefault(binding["inputPosition"], []).append(binding)
        for position, (input_state, input_fact) in enumerate(zip(ordered_inputs, fact["inputs"])):
            if input_state == "ABSENT_LOCAL:-":
                present, ftype = False, "-"
            elif input_state.startswith("PRESENT:"):
                present, ftype = True, input_state.split(":", 1)[1]
            else:
                raise ValueError("candidate activation ordered input state malformed")
            present_count += present
            sources = compiled.get((owner, position), [])
            physical_count += present and bool(sources)
            if any(source not in node_index or source not in coordinate for source in sources):
                raise ValueError("candidate activation compiled source authority absent")
            expected_sources = [{"sourceNodeIndex": node_index[source],
                "placementCoordinateIndex": coordinate[source],
                "compatibleFoutAlternativeIndices": _fout_alternatives(
                    placements, coordinate[source], ftype) if present else []}
                for source in sources]
            actual_sources = input_fact.get("compiledSources") if isinstance(input_fact, dict) else None
            if not isinstance(actual_sources, list) or len(actual_sources) != len(expected_sources):
                raise ValueError("candidate activation compiled source coverage differs")
            for actual, expected in zip(actual_sources, expected_sources):
                if not isinstance(actual, dict) or set(actual) != set(expected) | {"latentWdivmmBoundary"} or \
                        type(actual["latentWdivmmBoundary"]) is not bool or \
                        any(actual[name] != value for name, value in expected.items()):
                    raise ValueError("candidate activation compiled source authority differs")
            expected_bindings = []
            for binding in semantic_bindings.get(position, []):
                source_ref = reference_index.get(binding["source"], -1)
                source_owner = binding["sourceOwner"]
                source_coordinate = coordinate.get(source_owner, -1)
                if source_ref < 0 or source_owner not in node_index or source_coordinate < 0:
                    raise ValueError("candidate activation binding source authority absent")
                source_placement = binding["sourcePlacement"]
                try:
                    source_alternative = placements[source_coordinate][1].index(source_placement)
                except (ValueError, IndexError) as error:
                    raise ValueError("candidate activation binding placement differs") from error
                relocation_index = -1 if binding["relocationAction"] == "-" else \
                    action_index.get(binding["relocationAction"], -1)
                if binding["relocationAction"] != "-" and relocation_index < 0:
                    raise ValueError("candidate activation binding action authority absent")
                expected_bindings.append({"kind": binding["kind"],
                    "sourceReferenceIndex": source_ref,
                    "sourceNodeIndex": node_index.get(source_owner, -1),
                    "sourcePlacementCoordinateIndex": source_coordinate,
                    "sourcePlacementAlternativeIndex": source_alternative,
                    "compatibleFoutAlternativeIndices": _fout_alternatives(
                        placements, source_coordinate, ftype) if present else [],
                    "relocationActionIndex": relocation_index})
            matching_actions = [index for index, action in enumerate(actions)
                if present and action["materializationFType"] == ftype and
                action["targetPlacement"] == placement and any(
                    obligation["consumer"] == owner and
                    obligation["inputPosition"] == position
                    for obligation in action["obligations"])]
            function_sources = []
            for source in sources:
                for relation in logical_by_kind["function"]:
                    if relation["target"] == source:
                        function_sources.append({name: relation[name] for name in (
                            "sourceNodeIndex", "sourcePlacementCoordinateIndex",
                            "boundaryNodeIndex", "boundaryPlacementCoordinateIndex",
                            "targetNodeIndex", "targetPlacementCoordinateIndex",
                            "callInputPosition", "logicalPosition")})
            expected_input = {"inputPosition": position, "present": present,
                "requiredFType": ftype, "compiledSources": actual_sources,
                "exactBindings": expected_bindings,
                "matchingRelocationActionIndices": matching_actions,
                "functionForwardingSources": function_sources}
            if input_fact != expected_input:
                raise ValueError("candidate activation input primitives differ")
        if fact["presentInputCount"] != present_count or \
                fact["presentPhysicalInputCount"] != physical_count:
            raise ValueError("candidate activation present input counts differ")

        authority = fact["derivedFoutAuthority"]
        action_signature = semantic["derivedFoutAction"]
        required_fout = expected_scalar["derivedFedFout"] or \
            _placement_parts(placement)[:2] == ["CP", "FOUT"]
        if action_signature == "-":
            expected_authority = {"required": required_fout, "graphActionIndex": -1,
                "producerNodeIndex": -1, "producerPlacementCoordinateIndex": -1,
                "sourcePlacementAlternativeIndex": -1, "targetPlacementAlternativeIndex": -1,
                "anchorOwnerNodeIndex": -1, "anchorOwnerPlacementCoordinateIndex": -1,
                "anchorOwnerRequiredFType": "-", "anchorOwnerCompatibleFoutAlternativeIndices": []}
        else:
            action_parts = decode_java_length_fields(action_signature, 10)
            producer, source_state, target_state, anchor_owner, owner_ftype = (
                action_parts[0], action_parts[3], action_parts[4], action_parts[6], action_parts[7])
            pc, ac = coordinate.get(producer, -1), coordinate.get(anchor_owner, -1)
            graph_action = derived_index.get(action_signature, -1)
            if producer not in node_index or anchor_owner not in node_index or \
                    pc < 0 or ac < 0 or graph_action < 0:
                raise ValueError("candidate activation derived FOUT graph authority absent")
            expected_authority = {"required": required_fout,
                "graphActionIndex": graph_action,
                "producerNodeIndex": node_index.get(producer, -1),
                "producerPlacementCoordinateIndex": pc,
                "sourcePlacementAlternativeIndex": placements[pc][1].index(source_state),
                "targetPlacementAlternativeIndex": placements[pc][1].index(target_state),
                "anchorOwnerNodeIndex": node_index.get(anchor_owner, -1),
                "anchorOwnerPlacementCoordinateIndex": ac,
                "anchorOwnerRequiredFType": owner_ftype,
                "anchorOwnerCompatibleFoutAlternativeIndices": _fout_alternatives(
                    placements, ac, owner_ftype)}
        if authority != expected_authority:
            raise ValueError("candidate activation derived FOUT authority differs")
        special = fact["specialRuntimeAuthority"]
        if not isinstance(special, dict) or special.get("kind") not in {
                "NONE", "LATENT_WDIVMM", "DIRECT_WDIVMM"}:
            raise ValueError("candidate activation special runtime authority malformed")
        if special["kind"] == "NONE" and special != {"kind": "NONE"}:
            raise ValueError("candidate activation NONE runtime authority malformed")
    return {"activationFacts": len(facts), "supportAuthorities": len(supports),
            "logicalAuthorities": sum(len(value) for value in logical_by_kind.values()),
            "relocationWorkerPools": len(relocation_pools),
            "producerAssessmentFieldsTrusted": False,
            "producerCarriedUnassessedFields":
                list(PRODUCER_CARRIED_ACTIVATION_FIELDS)}


def artifact_assessment(domain):
    """Verify each serialized evidence family once and retain its result."""
    assessed = []
    privacy = verify_candidate_privacy_closure(domain)
    if privacy:
        assessed.append("SERIALIZED_CANDIDATE_PRIVACY_TRANSFORMATION")
    receipt_evidence = verify_candidate_receipt_semantics_and_source_links(domain)
    if receipt_evidence and receipt_evidence["unsupportedSourceLinkReceipts"] == 0:
        assessed.append("SERIALIZED_CANDIDATE_RECEIPT_SEMANTICS_AND_SOURCE_LINKS")
    relocation_evidence = verify_relocation_receipt_semantics_and_action_links(domain)
    if relocation_evidence:
        assessed.append("SERIALIZED_RELOCATION_RECEIPT_SEMANTICS_AND_ACTION_LINKS")
    derived_fout = verify_derived_fout_graph_ownership(domain)
    if derived_fout:
        assessed.append("DERIVED_FOUT_GRAPH_OWNERSHIP")
    owner_classification = verify_nondecision_candidate_owner_classification(domain)
    if owner_classification:
        assessed.append("UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION")
    activation_evidence = verify_candidate_assignment_primitives(domain)
    if activation_evidence:
        assessed.append("SERIALIZED_CANDIDATE_ASSIGNMENT_STRUCTURAL_LINKS")
    evidence = {
        "privacyTransformation": privacy,
        "candidateReceipt": receipt_evidence,
        "relocationReceipt": relocation_evidence,
        "derivedFoutOwnership": derived_fout,
        "ownerClassification": owner_classification,
        "candidateAssignmentStructuralLinks": activation_evidence,
    }
    return tuple(assessed), evidence


def artifact_assessed_predicates(domain):
    return artifact_assessment(domain)[0]


def _acceptance_coverage(domain, assessed, verified_evidence):
    """Describe the exact acceptance boundary of an offline verification receipt."""
    constraints = domain.get("constraints")
    nodes = domain.get("nodes")
    placements = domain.get("placementDomains")
    constraints_absent = constraints is None
    if constraints_absent:
        constraints = []
    if not isinstance(constraints, list) or not isinstance(nodes, list) or \
            not isinstance(placements, list):
        raise ValueError("P acceptance inventory inputs absent")
    owners = {row[0] for row in placements
              if isinstance(row, list) and len(row) == 2 and isinstance(row[0], str)}
    decision_constraints = sum(
        isinstance(row, dict) and row.get("left") in owners and row.get("right") in owners
        for row in constraints)
    nondecision_constraints = len(constraints) - decision_constraints
    assessed = list(assessed)
    if len(assessed) != len(set(assessed)):
        raise ValueError("duplicate assessed acceptance predicate")
    unsupported = set(assessed) - INDEPENDENT_ARTIFACT_PREDICATES - {
        "DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS",
    }
    if unsupported:
        raise ValueError("unsupported assessed acceptance predicate")
    evidence_fields = {"privacyTransformation", "candidateReceipt",
                       "relocationReceipt", "derivedFoutOwnership",
                       "ownerClassification", "candidateAssignmentStructuralLinks"}
    if not isinstance(verified_evidence, dict) or set(verified_evidence) != evidence_fields:
        raise ValueError("verified acceptance evidence malformed")
    privacy_evidence = verified_evidence["privacyTransformation"]
    receipt_evidence = verified_evidence["candidateReceipt"]
    relocation_receipt_evidence = verified_evidence["relocationReceipt"]
    derived_fout_evidence = verified_evidence["derivedFoutOwnership"]
    owner_evidence = verified_evidence["ownerClassification"]
    activation_evidence = verified_evidence["candidateAssignmentStructuralLinks"]
    if "UNRESOLVED_CANDIDATE_OWNER_CLASSIFICATION" in assessed and not owner_evidence:
        raise ValueError("assessed owner classification evidence absent")
    if "DERIVED_FOUT_GRAPH_OWNERSHIP" in assessed and not derived_fout_evidence:
        raise ValueError("assessed derived FOUT ownership evidence absent")
    if "SERIALIZED_CANDIDATE_PRIVACY_TRANSFORMATION" in assessed and not privacy_evidence:
        raise ValueError("assessed candidate privacy evidence absent")
    if "SERIALIZED_CANDIDATE_RECEIPT_SEMANTICS_AND_SOURCE_LINKS" in assessed and \
            not receipt_evidence:
        raise ValueError("assessed candidate receipt semantic evidence absent")
    if "SERIALIZED_RELOCATION_RECEIPT_SEMANTICS_AND_ACTION_LINKS" in assessed and \
            not relocation_receipt_evidence:
        raise ValueError("assessed relocation receipt semantic evidence absent")
    if "SERIALIZED_CANDIDATE_ASSIGNMENT_STRUCTURAL_LINKS" in assessed and \
            not activation_evidence:
        raise ValueError("assessed candidate assignment primitive evidence absent")
    serialized_unassessed = []
    if constraints_absent:
        serialized_unassessed.append("GRAPH_CONSTRAINT_RECORDS_ABSENT_FROM_ARTIFACT")
    if decision_constraints and "DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS" not in assessed:
        serialized_unassessed.append("DECISION_ENDPOINT_NEUTRAL_GRAPH_CONSTRAINTS")
    if nondecision_constraints:
        serialized_unassessed.append("NON_DECISION_ENDPOINT_GRAPH_CONSTRAINT_RECORDS")
    if "DERIVED_FOUT_GRAPH_OWNERSHIP" not in assessed:
        serialized_unassessed.append("DERIVED_FOUT_ACTION_IDENTITY_BINDINGS")
    if "SERIALIZED_CANDIDATE_PRIVACY_TRANSFORMATION" not in assessed:
        serialized_unassessed.append("CANDIDATE_PRIVACY_CLOSURE_PASS_EVIDENCE")
    if "SERIALIZED_CANDIDATE_RECEIPT_SEMANTICS_AND_SOURCE_LINKS" not in assessed:
        serialized_unassessed.append("CANDIDATE_RECEIPT_SEMANTICS_AND_SOURCE_LINKS")
    if "SERIALIZED_RELOCATION_RECEIPT_SEMANTICS_AND_ACTION_LINKS" not in assessed:
        serialized_unassessed.append("RELOCATION_RECEIPT_SEMANTICS_AND_ACTION_LINKS")
    if "SERIALIZED_CANDIDATE_ASSIGNMENT_STRUCTURAL_LINKS" not in assessed:
        serialized_unassessed.append("CANDIDATE_ASSIGNMENT_STRUCTURAL_LINK_EVIDENCE")
    else:
        serialized_unassessed.append(
            "CANDIDATE_ASSIGNMENT_PRODUCER_CARRIED_AUTHORITY_FIELDS")
    serialized_unassessed.extend((
        "CANDIDATE_ASSIGNMENT_DEPENDENT_FEASIBILITY",
        "NON_DECISION_CANDIDATE_OWNER_UNIVERSE",
    ))
    return {
        "complete": False,
        "assessedPredicates": assessed,
        "serializedButNotAssessed": serialized_unassessed,
        "opaquePredicates": [predicate for predicate in OPAQUE_ACCEPTANCE_PREDICATES
                             if predicate not in assessed],
        "decisionGraphConstraints": decision_constraints,
        "nonDecisionGraphConstraints": nondecision_constraints,
        "candidateReceiptEvidence": receipt_evidence or None,
        "relocationReceiptEvidence": relocation_receipt_evidence or None,
        "candidateAssignmentStructuralLinkEvidence": activation_evidence or None,
    }


def acceptance_coverage(domain, assessed=()):
    """Public coverage check; independently verifies every referenced evidence family."""
    _, evidence = artifact_assessment(domain)
    return _acceptance_coverage(domain, assessed, evidence)


def require_full_acceptance(receipt):
    """Fail closed when a caller asks this structural verifier for a full P proof."""
    coverage = receipt.get("acceptanceCoverage") if isinstance(receipt, dict) else None
    if not isinstance(coverage, dict) or coverage.get("complete") is not True or \
            coverage.get("opaquePredicates"):
        raise ValueError("P independent full acceptance is unavailable from this artifact")
    return receipt


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def source_order(value):
    return json.dumps(value, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def verify(path, expected_sha):
    with gzip.open(path, "rb") as stream:
        plain = stream.read(MAX_DECOMPRESSED_ARTIFACT_BYTES + 1)
    if len(plain) > MAX_DECOMPRESSED_ARTIFACT_BYTES:
        raise ValueError("P artifact exceeds decompressed byte budget")
    if hashlib.sha256(plain).hexdigest() != expected_sha:
        raise ValueError("P artifact digest differs")
    artifact = json.loads(plain)
    if (artifact.get("schema") != "closed-native-model-artifact-v1" or
            artifact.get("acceptance") != "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED"):
        raise ValueError("P artifact contract differs")
    summary, domain = artifact.get("summary"), artifact.get("nativeDomain")
    if not isinstance(summary, dict) or not isinstance(domain, dict) or \
            summary.get("status") != "COMPLETE":
        raise ValueError("P model incomplete")
    if hashlib.sha256(canonical(domain)).hexdigest() != summary.get("nativeDomainSha256"):
        raise ValueError("P native domain digest differs")
    for side, digest_name in (("preRewriteGraph", "preRewriteGraphSha256"),
                              ("finalHopGraph", "finalHopGraphSha256")):
        graph = artifact.get(side)
        if not isinstance(graph, dict):
            raise ValueError("P graph absent: " + side)
        rows = [graph.get(key) for key in
                ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")]
        if hashlib.sha256(source_order(rows)).hexdigest() != summary.get(digest_name):
            raise ValueError("P graph digest differs: " + side)
    placements = domain.get("placementDomains")
    candidates = domain.get("candidateDomains")
    relocations = domain.get("relocationDomains")
    radices = domain.get("radices")
    nodes = domain.get("nodes")
    if (not all(isinstance(rows, list) for rows in
                (placements, candidates, relocations, radices, nodes)) or
            len(placements) != len(candidates) or
            len(radices) != len(placements) + len(candidates) + len(relocations) or
            len(placements) != summary.get("placementCoordinates") or
            len(candidates) != summary.get("candidateCoordinates") or
            len(relocations) != summary.get("relocationCoordinates")):
        raise ValueError("P coordinate counts differ")
    owners = set()
    product = 1
    coordinates = placements + candidates + relocations
    for index, row in enumerate(coordinates):
        if (not isinstance(row, list) or len(row) != 2 or
                not isinstance(row[0], str) or not isinstance(row[1], list)):
            raise ValueError("P coordinate malformed")
        radix = len(row[1]) + (0 if index < len(placements) else 1)
        if radix < 1 or type(radices[index]) is not int or radices[index] != radix:
            raise ValueError("P radix differs from alternatives")
        if index < len(placements):
            if row[0] in owners:
                raise ValueError("P placement owner duplicate")
            owners.add(row[0])
        elif index < 2 * len(placements) and row[0] != placements[index - len(placements)][0]:
            raise ValueError("P candidate owner order differs")
        product *= radix
    if str(product) != summary.get("rawCount"):
        raise ValueError("P raw product differs")
    # DML function calls lower to a CP call instruction. A final CFG replay
    # must not republish a synthetic FED placement after executable-candidate
    # projection, even if its raw coordinate/radix structure is well formed.
    for node in nodes:
        if not isinstance(node, list) or len(node) != 5 or not isinstance(node[3], list):
            raise ValueError("P node descriptor malformed")
        if node[1] == "FUNCTION_CALL" and any(
                state != "CP/LOUT/-/SHAPE_INDEPENDENT" for state in node[3]):
            raise ValueError("P function call has non-CP executable placement")
    assessed, evidence = artifact_assessment(domain)
    return {"schema": "p-native-model-structure-verification-v1",
            "status": "STRUCTURE_VERIFIED", "cell": summary.get("cell"),
            "raw": str(product), "artifactSha256": expected_sha,
            "acceptance": "NOT_ASSESSED_BY_THIS_CONTRACT",
            "acceptanceCoverage": _acceptance_coverage(domain, assessed, evidence),
            "functionCallPlacementGuard": "VERIFIED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--require-full-acceptance", action="store_true")
    args = parser.parse_args()
    receipt = verify(args.model, args.model_sha256)
    if args.require_full_acceptance:
        require_full_acceptance(receipt)
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
