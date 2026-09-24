#!/usr/bin/env python3
"""Independent bounded checker for non-PHI E atom translation semantics.

This module deliberately does not import the atom compiler or the typed
projection decoder.  It reconstructs every local physical emission directly
from the frozen projection program, checks every emitted atom equivalence truth
table, and checks the Boolean hard-factor translation against the saved
three-valued E tables.  The proof is structural and model-local; it does not
bind the captured projection program to Java planner semantics.
"""

import base64
import gzip
import hashlib
from itertools import product
import json
from math import prod
from pathlib import Path


SCHEMA = "e-non-phi-atom-semantics-certificate-v1"


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _digest(value):
    return hashlib.sha256(_canonical(value)).hexdigest()


def _file_sha(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def _read_model(path, max_compressed_bytes, max_decoded_bytes):
    path = Path(path)
    if path.stat().st_size > max_compressed_bytes:
        return None, "ATOM_SEMANTICS_COMPRESSED_BYTE_BUDGET_EXHAUSTED"
    decoded = bytearray()
    with gzip.open(path, "rb") as stream:
        while True:
            block = stream.read(1024 * 1024)
            if not block:
                break
            if len(decoded) + len(block) > max_decoded_bytes:
                return None, "ATOM_SEMANTICS_DECODED_BYTE_BUDGET_EXHAUSTED"
            decoded.extend(block)
    return json.loads(decoded), None


def _token(atom):
    return "a_" + _digest(atom)


def _add(emissions, atom, condition):
    token = _token(atom)
    prior = emissions.setdefault(token, {"atom": atom, "conditions": set()})
    if prior["atom"] != atom:
        raise ValueError("independent atom token collision")
    prior["conditions"].add(tuple(sorted(condition.items())))


def _state_parts(native):
    state = native.get("state") if isinstance(native, dict) else None
    parts = state.split("/") if isinstance(state, str) else []
    if len(parts) != 4 or parts[3] not in (
            "SHAPE_DEPENDENT", "SHAPE_INDEPENDENT"):
        raise ValueError("native alternative state is not decodable")
    return parts


def _projection_work_upper_bound(model, max_semantic_cells):
    """Bound local source-slot expansion before constructing atom payloads."""
    projection = model.get("physicalProjection")
    domains = model.get("domains")
    variables = projection.get("variables") if isinstance(projection, dict) else None
    if not isinstance(domains, list) or not isinstance(variables, list):
        raise ValueError("model lacks projection variables for preflight")
    slots = 0
    for domain, variable in enumerate(variables):
        alternatives = variable.get("alternatives") if isinstance(variable, dict) else None
        if not isinstance(alternatives, list):
            raise ValueError("projection alternatives are invalid in preflight")
        for fragment in alternatives:
            if not isinstance(fragment, dict):
                raise ValueError("projection fragment is invalid in preflight")
            actions = fragment.get("actions")
            geometry = fragment.get("geometry")
            bindings = fragment.get("bindings")
            if any(not isinstance(rows, list)
                   for rows in (actions, geometry, bindings)):
                raise ValueError("projection slots are invalid in preflight")
            slots += 2 + len(actions) + len(geometry)
            for template in bindings:
                if not isinstance(template, dict):
                    raise ValueError("projection binding is invalid in preflight")
                producer = template.get("producerDomain")
                if template.get("mode") == "PHI":
                    return None, "PHI_ATOM_SEMANTICS_UNSUPPORTED"
                if type(producer) is not int or producer < 0 or producer >= len(domains):
                    raise ValueError("projection producer is invalid in preflight")
                slots += (1 if producer == domain else
                          len(domains[producer].get("alternatives", ())))
            if slots > max_semantic_cells:
                return None, "ATOM_SEMANTICS_LOCAL_EMISSION_BUDGET_EXHAUSTED"
    return slots, None


def _projection_emissions(model):
    domains = model.get("domains")
    projection = model.get("physicalProjection")
    if not isinstance(domains, list) or not isinstance(projection, dict):
        raise ValueError("model lacks a physical projection")
    if (projection.get("schema") !=
            "exact-physical-compositional-projection-v1" or
            projection.get("logicalProgram") != model.get("programSha256") or
            projection.get("logicalInputs") != model.get(
                "sourceIdentity", {}).get("physicalLogicalInputs")):
        raise ValueError("projection static coordinates are invalid")
    variables = projection.get("variables")
    if not isinstance(variables, list) or len(variables) != len(domains):
        raise ValueError("projection variables do not cover native domains")
    node_order = projection.get("nodeOrder")
    if (not isinstance(node_order, list) or
            node_order != list(dict.fromkeys(node_order)) or
            set(node_order) != set(range(len(domains)))):
        raise ValueError("projection node order is not a permutation")
    binding_order = projection.get("bindingDomainOrder")
    if (not isinstance(binding_order, list) or
            len(binding_order) != len(set(binding_order)) or
            any(type(value) is not int or value < 0 or value >= len(domains)
                for value in binding_order)):
        raise ValueError("projection binding order is invalid")
    node_positions = {domain: index for index, domain in enumerate(node_order)}
    binding_positions = {domain: index
                         for index, domain in enumerate(binding_order)}
    emissions = {}
    owners = {}
    authority_rows = {}
    action_rows = {}
    geometry_rows = {}
    binding_domains = set()
    relocation_templates = 0
    source_operations = {
        row.get("occurrence"): row.get("operation")
        for row in model.get("sourceIdentity", {}).get("nodes", ())
        if isinstance(row, dict)}
    for domain, (native_domain, variable) in enumerate(zip(domains, variables)):
        if not isinstance(variable, dict) or variable.get("domain") != domain:
            raise ValueError("projection variable index drift")
        if (native_domain.get("index") != domain or
                not isinstance(native_domain.get("occurrence"), str) or
                source_operations.get(native_domain["occurrence"]) is None):
            raise ValueError("native domain identity is invalid")
        owner = variable.get("occurrence")
        if (not isinstance(owner, dict) or
                owner.get("sourceOrigin") != native_domain.get("occurrence") or
                owner.get("emittedInstance") != native_domain.get("occurrence")):
            raise ValueError("projection variable occurrence drift")
        owner_key = _canonical(owner)
        previous_domain = owners.setdefault(owner_key, domain)
        if previous_domain != domain:
            raise ValueError("projection node owner is not unique")
        alternatives = variable.get("alternatives")
        native_alternatives = native_domain.get("alternatives")
        if (not isinstance(alternatives, list) or
                not isinstance(native_alternatives, list) or
                len(alternatives) != len(native_alternatives) or
                not alternatives):
            raise ValueError("projection alternative coverage drift")
        for alternative, (fragment, native) in enumerate(zip(
                alternatives, native_alternatives)):
            if not isinstance(fragment, dict) or set(fragment) != {
                    "node", "authority", "actions", "geometry", "bindings"}:
                raise ValueError("projection fragment shape is invalid")
            if any(not isinstance(fragment[key], list)
                   for key in ("actions", "geometry", "bindings")):
                raise ValueError("projection fragment collection is invalid")
            node = fragment["node"]
            authority = fragment["authority"]
            parts = _state_parts(native)
            if (not isinstance(node, dict) or node.get("occurrence") != owner or
                    node.get("exec") != parts[0] or
                    node.get("output") != parts[1] or
                    node.get("ftype") != ("NONE" if parts[2] == "-" else parts[2]) or
                    node.get("shapeDependent") is not
                    (parts[3] == "SHAPE_DEPENDENT") or
                    node.get("opcode") != source_operations[
                        native_domain["occurrence"]]):
                raise ValueError("projection node differs from native state")
            if (not isinstance(authority, dict) or
                    authority.get("owner") != owner or
                    authority.get("id") != node.get("authorityRef")):
                raise ValueError("projection authority ownership drift")
            authority_identity = _canonical(authority["id"])
            prior_authority = authority_rows.setdefault(
                authority_identity, _canonical(authority))
            if prior_authority != _canonical(authority):
                return None, "AMBIGUOUS_AUTHORITY_IDENTITY"
            condition = {domain: alternative}
            _add(emissions, {"coordinate": "nodes",
                             "position": node_positions[domain],
                             "value": node}, condition)
            _add(emissions, {"coordinate": "authority", "position": domain,
                             "value": authority}, condition)
            for ordinal, row in enumerate(fragment["actions"]):
                if not isinstance(row, dict) or "id" not in row:
                    raise ValueError("projection action is invalid")
                identity = _canonical(row["id"])
                prior = action_rows.setdefault(identity, _canonical(row))
                if prior != _canonical(row):
                    return None, "AMBIGUOUS_ACTION_IDENTITY"
                _add(emissions, {"coordinate": "actions",
                                 "sourceDomain": domain, "ordinal": ordinal,
                                 "value": row}, condition)
            for ordinal, row in enumerate(fragment["geometry"]):
                if not isinstance(row, dict):
                    raise ValueError("projection geometry is invalid")
                # This is the exact unique identity used by the pinned physical
                # codec (plan_space_verify.normalize_plan).
                identity = _canonical({key: row.get(key)
                                       for key in ("owner", "worker", "ranges")})
                prior = geometry_rows.setdefault(identity, _canonical(row))
                if prior != _canonical(row):
                    return None, "AMBIGUOUS_GEOMETRY_IDENTITY"
                _add(emissions, {"coordinate": "geometry",
                                 "sourceDomain": domain, "ordinal": ordinal,
                                 "value": row}, condition)
            positions = set()
            for template in fragment["bindings"]:
                if (not isinstance(template, dict) or
                        template.get("consumer") != owner or
                        type(template.get("inputPosition")) is not int or
                        template["inputPosition"] < 0 or
                        template["inputPosition"] in positions):
                    raise ValueError("projection binding identity is invalid")
                positions.add(template["inputPosition"])
                mode = template.get("mode")
                if mode == "PHI":
                    return None, "PHI_ATOM_SEMANTICS_UNSUPPORTED"
                if (mode not in ("LOGICAL_TRANSIENT", "RELOCATION",
                                 "ABSENT_LOCAL", "DIRECT_OR_FOUT") or
                        ("ftype" in template) ==
                        ("ftypeFromProducer" in template)):
                    raise ValueError("projection binding mode is invalid")
                producer_domain = template.get("producerDomain")
                if (type(producer_domain) is not int or producer_domain < 0 or
                        producer_domain >= len(domains)):
                    raise ValueError("projection binding producer is invalid")
                if template.get("mode") == "RELOCATION":
                    relocation_templates += 1
                    if not any(row.get("id") == template.get("actionRef")
                               for row in fragment["actions"]):
                        raise ValueError("relocation action is not co-emitted")
            if fragment["bindings"]:
                binding_domains.add(domain)
    if set(binding_order) != binding_domains:
        raise ValueError("binding order does not cover binding domains")

    for consumer_domain in binding_order:
        order = binding_positions[consumer_domain]
        variable = variables[consumer_domain]
        for consumer_alternative, fragment in enumerate(variable["alternatives"]):
            for ordinal, template in enumerate(fragment["bindings"]):
                producer_domain = template["producerDomain"]
                producer_alternatives = variables[producer_domain]["alternatives"]
                producer_choices = (
                    ((consumer_alternative,
                      producer_alternatives[consumer_alternative]),)
                    if producer_domain == consumer_domain else
                    enumerate(producer_alternatives))
                for producer_alternative, producer in producer_choices:
                    row = {key: template[key] for key in
                           ("consumer", "inputPosition", "presence")}
                    row["ftype"] = (template["ftype"] if "ftype" in template
                                    else producer["node"]["ftype"])
                    row["producer"] = producer["node"]["occurrence"]
                    row["sourceAuthorityRef"] = producer["authority"]["id"]
                    mode = template["mode"]
                    row["inputAuthority"] = (
                        "DIRECT_FOUT" if mode == "DIRECT_OR_FOUT" and
                        producer["node"]["output"] == "FOUT" else
                        "DIRECT" if mode == "DIRECT_OR_FOUT" else mode)
                    if mode == "RELOCATION":
                        row["actionRef"] = template["actionRef"]
                    condition = {consumer_domain: consumer_alternative,
                                 producer_domain: producer_alternative}
                    _add(emissions, {"coordinate": "bindings",
                                     "bindingOrder": order, "ordinal": ordinal,
                                     "part": "ROW", "value": row}, condition)
    return emissions, {"nodeOwners": len(owners),
                       "authorityIdentities": len(authority_rows),
                       "actionIdentities": len(action_rows),
                       "geometryIdentities": len(geometry_rows),
                       "relocationTemplates": relocation_templates}


def _check_translation_layout(model, translated, emissions):
    radices = tuple(len(row["alternatives"]) for row in model["domains"])
    order = tuple(translated.get("order", ()))
    if (set(order) != {index for index, radix in enumerate(radices)
                       if radix > 1} or len(order) != len(set(order)) or
            tuple(translated.get("radices", ())) != radices):
        raise ValueError("translated native variable partition differs")
    variables = tuple(translated.get("allVariables") or ())
    levels = translated.get("atomLevels") or {}
    expected_levels = {
        token: len(order) + offset
        for offset, token in enumerate(sorted(emissions))}
    if levels != expected_levels or len(variables) != len(order) + len(emissions):
        raise ValueError("translated atom variable layout differs")
    for level, domain in enumerate(order):
        variable = variables[level]
        expected_values = tuple(row["signature"]
                                for row in model["domains"][domain]["alternatives"])
        if (variable.name != "native:%d:%s" %
                (domain, model["domains"][domain]["occurrence"]) or
                tuple(variable.values) != expected_values):
            raise ValueError("translated native variable differs")
    for token, level in levels.items():
        variable = variables[level]
        if variable.name != "atom:" + token or \
                tuple(variable.values) != ("0", "1"):
            raise ValueError("translated atom Boolean variable differs")


def _decode_truth(value, cells):
    if isinstance(value, list):
        if len(value) != cells or any(row not in
                                     ("ALLOW", "REJECT", "UNKNOWN")
                                     for row in value):
            raise ValueError("source factor truth is invalid")
        return tuple(value)
    if not isinstance(value, dict) or value.get("encoding") != \
            "2BIT_LSB_FIRST_BASE64" or value.get("cells") != str(cells):
        raise ValueError("packed source factor truth is invalid")
    try:
        data = base64.b64decode(value.get("data"), validate=True)
    except Exception as error:
        raise ValueError("packed source factor data is invalid") from error
    if hashlib.sha256(data).hexdigest() != value.get("packedSha256"):
        raise ValueError("packed source factor commitment differs")
    result = []
    names = ("ALLOW", "REJECT", "UNKNOWN")
    for index in range(cells):
        code = (data[index >> 2] >> ((index & 3) * 2)) & 3
        if code == 3:
            raise ValueError("packed source factor has reserved status")
        result.append(names[code])
    if cells % 4 and data[-1] >> ((cells % 4) * 2):
        raise ValueError("packed source factor has nonzero padding")
    return tuple(result)


def _check_hard_factors(model, translated, max_cells):
    radices = tuple(len(row["alternatives"]) for row in model["domains"])
    order = tuple(translated["order"])
    position = {domain: level for level, domain in enumerate(order)}
    expected = []
    checked = 0
    source_cells_checked = 0
    unknown = 0
    for index, source in enumerate(model.get("factors", ())):
        scope = tuple(source.get("scope", ()))
        if (len(scope) != len(set(scope)) or
                any(type(domain) is not int or domain < 0 or
                    domain >= len(radices) for domain in scope)):
            raise ValueError("source factor scope is invalid")
        source_cells = prod(radices[x] for x in scope)
        source_cells_checked += source_cells
        if source_cells_checked > max_cells:
            return None, unknown, source_cells_checked, \
                "ATOM_SEMANTICS_CELL_BUDGET_EXHAUSTED"
        truth = _decode_truth(source.get("truth"), source_cells)
        unknown += sum(row == "UNKNOWN" for row in truth)
        varying = tuple(sorted((x for x in scope if radices[x] > 1),
                               key=position.__getitem__))
        cells = prod(radices[x] for x in varying)
        checked += cells
        if source_cells_checked + checked > max_cells:
            return None, unknown, source_cells_checked + checked, \
                "ATOM_SEMANTICS_CELL_BUDGET_EXHAUSTED"
        values = []
        assignment = {domain: 0 for domain in scope}
        for selected in product(*(range(radices[x]) for x in varying)):
            assignment.update(zip(varying, selected))
            offset = 0
            for domain in scope:
                offset = offset * radices[domain] + assignment[domain]
            values.append(truth[offset] == "ALLOW")
        expected.append({"name": "e-factor:%06d" % index,
                         "scope": tuple(position[x] for x in varying),
                         "truth": tuple(values)})
    actual = list(translated["factors"])
    if expected != actual:
        raise ValueError("Boolean hard factors differ from source E tables")
    return _digest([{"name": row["name"], "scope": list(row["scope"]),
                     "truth": list(row["truth"])} for row in expected]), \
        unknown, source_cells_checked + checked, None


def certify_atom_semantics(model_path, translated, *,
                           max_semantic_cells=10_000_000,
                           max_compressed_bytes=4 * 1024 ** 3,
                           max_decoded_bytes=4 * 1024 ** 3):
    """Return a committed COMPLETE or INCOMPLETE local semantic certificate."""
    for name, value in (("max_semantic_cells", max_semantic_cells),
                        ("max_compressed_bytes", max_compressed_bytes),
                        ("max_decoded_bytes", max_decoded_bytes)):
        if type(value) is not int or value < 1:
            raise ValueError(name + " must be a positive integer")
    model, blocker = _read_model(
        model_path, max_compressed_bytes, max_decoded_bytes)
    budgets = {"maxSemanticCells": max_semantic_cells,
               "maxCompressedBytes": max_compressed_bytes,
               "maxDecodedBytes": max_decoded_bytes}
    base = {"schema": SCHEMA, "budgets": budgets,
            "modelFileSha256": _file_sha(model_path),
            "modelSha256": translated.get("modelSha256")}
    if blocker:
        result = dict(base, status="INCOMPLETE", blockers=[blocker], proof=None)
        result["certificateSha256"] = _digest(result)
        return result
    # ``modelSha256`` commits the exact decoded JSON byte stream, whose
    # whitespace is intentionally not reconstructed by this independent
    # reader.  Object equality plus the separately recorded file hash binds
    # this replay to the same parsed model without pretending canonical JSON
    # and source bytes are interchangeable.
    if model != translated.get("model"):
        raise ValueError("independently loaded model differs from translation")
    local_emission_bound, blocker = _projection_work_upper_bound(
        model, max_semantic_cells)
    if blocker:
        result = dict(base, status="INCOMPLETE", blockers=[blocker], proof=None)
        result["certificateSha256"] = _digest(result)
        return result
    emissions, assembly = _projection_emissions(model)
    if isinstance(assembly, str):
        result = dict(base, status="INCOMPLETE", blockers=[assembly], proof=None)
        result["certificateSha256"] = _digest(result)
        return result
    _check_translation_layout(model, translated, emissions)
    hard_sha, unknown, checked, blocker = _check_hard_factors(
        model, translated, max_semantic_cells)
    if unknown:
        blocker = "SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED"
    if blocker:
        result = dict(base, status="INCOMPLETE", blockers=[blocker], proof=None)
        result["certificateSha256"] = _digest(result)
        return result
    dictionary = translated.get("atomDictionary")
    levels = translated.get("atomLevels")
    factors = {row["name"]: row for row in translated.get("allFactors", ())}
    if set(emissions) != set(dictionary or ()) or set(emissions) != set(levels or ()):
        raise ValueError("independent atom universe differs from translation")
    expected_factor_names = {
        "e-factor:%06d" % index
        for index in range(len(model.get("factors", ())))} | {
        "atom-equivalence:" + token for token in emissions}
    if set(factors) != expected_factor_names or \
            len(factors) != len(translated.get("allFactors", ())):
        raise ValueError("translated hard/atom factor inventory differs")
    for hard in translated["factors"]:
        replayed = factors[hard["name"]]
        if (tuple(replayed["scope"]) != tuple(hard["scope"]) or
                tuple(replayed["truth"]) != tuple(hard["truth"])):
            raise ValueError("allFactors hard payload differs from source replay")
    semantic_cells = checked
    semantic_lookups = 0
    condition_rows = 0
    atom_factor_rows = []
    order = tuple(translated["order"])
    position = {domain: level for level, domain in enumerate(order)}
    radices = tuple(translated["radices"])
    for token in sorted(emissions):
        expected = emissions[token]
        if dictionary[token] != expected["atom"]:
            raise ValueError("independent atom payload differs from translation")
        source_conditions = expected["conditions"]
        dependencies = sorted({position[domain] for row in source_conditions
                               for domain, _ in row if radices[domain] > 1})
        scope = tuple(dependencies + [levels[token]])
        factor = factors.get("atom-equivalence:" + token)
        if factor is None or tuple(factor["scope"]) != scope:
            raise ValueError("atom equivalence factor scope differs")
        truth = tuple(factor["truth"])
        cells = 2 * prod(len(translated["allVariables"][level].values)
                         for level in dependencies)
        semantic_cells += cells
        if semantic_cells > max_semantic_cells:
            result = dict(base, status="INCOMPLETE",
                          blockers=["ATOM_SEMANTICS_CELL_BUDGET_EXHAUSTED"],
                          proof=None)
            result["certificateSha256"] = _digest(result)
            return result
        lookup_bound = cells * sum(max(1, len(row))
                                   for row in source_conditions)
        semantic_lookups += lookup_bound
        if semantic_lookups > max_semantic_cells:
            result = dict(
                base, status="INCOMPLETE",
                blockers=[
                    "ATOM_SEMANTICS_COMPARISON_LOOKUP_BUDGET_EXHAUSTED"],
                proof=None)
            result["certificateSha256"] = _digest(result)
            return result
        expected_truth = []
        for selected in product(*(range(len(translated["allVariables"][level].values))
                                  for level in scope)):
            assignment = dict(zip(scope, selected))
            active = False
            for row in source_conditions:
                matches = True
                for domain, alternative in row:
                    if radices[domain] == 1:
                        matches = matches and alternative == 0
                    else:
                        matches = matches and assignment[position[domain]] == alternative
                active = active or matches
            expected_truth.append(bool(assignment[levels[token]]) is active)
        if truth != tuple(expected_truth):
            raise ValueError("atom equivalence truth differs from local emissions")
        condition_rows += len(source_conditions)
        atom_factor_rows.append({"token": token, "scope": list(scope),
                                 "truthSha256": _digest(expected_truth)})
    proof = {
        "projectionSha256": _digest(model["physicalProjection"]),
        "hardFactorsSha256": hard_sha,
        "atomDictionarySha256": _digest(dictionary),
        "atomFactorReplaySha256": _digest(atom_factor_rows),
        "nativeDomainCount": len(radices),
        "varyingNativeDomainCount": len(order),
        "atomCount": len(emissions),
        "localConditionCount": condition_rows,
        "localEmissionUpperBound": local_emission_bound,
        "semanticCellsChecked": semantic_cells,
        "semanticComparisonLookupBound": semantic_lookups,
        "assembly": assembly,
        "scope": "CAPTURED_NON_PHI_TYPED_PROJECTION_LOCAL_COMPOSITION",
    }
    result = dict(base, status="COMPLETE", blockers=[], proof=proof)
    result["certificateSha256"] = _digest(result)
    return result


def verify_atom_semantics_certificate(certificate, model_path, translated,
                                      *, max_semantic_cells=10_000_000,
                                      max_compressed_bytes=4 * 1024 ** 3,
                                      max_decoded_bytes=4 * 1024 ** 3):
    if not isinstance(certificate, dict) or certificate.get("schema") != SCHEMA:
        raise ValueError("atom semantic certificate contract mismatch")
    if certificate.get("certificateSha256") != _digest({
            key: value for key, value in certificate.items()
            if key != "certificateSha256"}):
        raise ValueError("atom semantic certificate commitment mismatch")
    budgets = certificate.get("budgets")
    if not isinstance(budgets, dict) or set(budgets) != {
            "maxSemanticCells", "maxCompressedBytes", "maxDecodedBytes"}:
        raise ValueError("atom semantic certificate budgets are invalid")
    if (budgets["maxSemanticCells"] > max_semantic_cells or
            budgets["maxCompressedBytes"] > max_compressed_bytes or
            budgets["maxDecodedBytes"] > max_decoded_bytes):
        return {"status": "INCOMPLETE",
                "blocker": "VERIFY_ATOM_SEMANTICS_BUDGET_CAP_EXCEEDED"}
    regenerated = certify_atom_semantics(
        model_path, translated,
        max_semantic_cells=budgets["maxSemanticCells"],
        max_compressed_bytes=budgets["maxCompressedBytes"],
        max_decoded_bytes=budgets["maxDecodedBytes"])
    if regenerated != certificate:
        raise ValueError("atom semantic certificate does not regenerate")
    return {"status": "PASS" if certificate["status"] == "COMPLETE" else
            "INCOMPLETE", "blocker": (None if certificate["status"] ==
                                       "COMPLETE" else certificate["blockers"][0])}
