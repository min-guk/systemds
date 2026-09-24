#!/usr/bin/env python3
"""Bounded exact symbolic image of a typed E physical projection.

This is a diagnostic prototype.  It uses materialized E hard factors to prune
the native prefix tree, composes typed physical coordinates only for definitely
accepted assignments, and existentially eliminates every native variable.  The
resulting MDD is an exact image of the *serialized typed projection* for those
assignments within the configured budgets.

The typed decoder is compiler-owned and is not independently semantically
bound to the Java physical projector.  Consequently this module never emits
an EQUAL or certification result.  Unsupported models and exhausted budgets
fail closed with ``status=BLOCKED``.
"""

import argparse
import gzip
import hashlib
from itertools import product
import json
from math import prod
from pathlib import Path
import tempfile

from boolean_mdd_relation import (MDDManager, MDDResourceLimitError, Variable,
                                  validate_artifact)
from exact_e_factor_count import count_relation, read_model
from exact_e_physical_relation import (REQUIRED_PHYSICAL_COORDINATES,
                                       factor_status, projection_contract,
                                       validate_identity)


SCHEMA = 'symbolic-e-physical-image-diagnostic-v1'


class PrefixEnumerationLimitError(RuntimeError):
    def __init__(self, blocker):
        super().__init__(blocker)
        self.blocker = blocker


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'),
                      ensure_ascii=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _seal(value):
    if 'envelopeSha256' in value:
        raise ValueError('symbolic image envelope is already sealed')
    return {**value, 'envelopeSha256': digest(value)}


def _token(prefix, value, dictionary):
    token = prefix + hashlib.sha256(canonical(value)).hexdigest()
    previous = dictionary.setdefault(token, value)
    if previous != value:
        raise ValueError('canonical coordinate SHA-256 collision')
    return token


def _load_json(path):
    data = Path(path).read_bytes()
    if data[:2] == b'\x1f\x8b':
        data = gzip.decompress(data)
    return json.loads(data)


def load_exhaustive_physical_rows(path):
    """Load a provided collection of physical-plan rows.

    Loading rows does not establish that the collection is exhaustive.  That
    distinction is preserved in the saved comparison metadata.
    """
    value = _load_json(path)
    if isinstance(value, list):
        rows = value
    elif isinstance(value, dict):
        rows = None
        for key in ('rows', 'physicalPlans', 'plans', 'canonicalPhysicalPlans'):
            candidate = value.get(key)
            if isinstance(candidate, list):
                rows = candidate
                break
            if key == 'canonicalPhysicalPlans' and isinstance(candidate, dict):
                rows = list(candidate.values())
                break
        if rows is None:
            raise ValueError('P exhaustive artifact has no recognized row collection')
    else:
        raise ValueError('P exhaustive artifact is not a JSON row collection')
    if any(not isinstance(row, dict) for row in rows):
        raise ValueError('P exhaustive artifact contains a non-object row')
    return rows


def _coordinate_tuple(row, coordinates):
    missing = [key for key in coordinates if key not in row]
    if missing:
        raise ValueError('physical row lacks coordinates: ' + ','.join(missing))
    return tuple(row[key] for key in coordinates)


def _ordered_union(rows):
    result = []
    seen = set()
    for row in rows:
        key = canonical(row)
        if key not in seen:
            seen.add(key)
            result.append(row)
    return result


def _compose_validated_projection(projection, assignment):
    """Compose a row after ``projection_contract`` validated the program once."""
    selected = [projection['variables'][index]['alternatives'][value]
                for index, value in enumerate(assignment)]
    nodes = [selected[index]['node'] for index in projection['nodeOrder']]
    authorities = [fragment['authority'] for fragment in selected]
    actions = _ordered_union(row for fragment in selected for row in fragment['actions'])
    geometry = _ordered_union(row for fragment in selected for row in fragment['geometry'])
    action_ids = [row['id'] for row in actions]
    bindings = []
    for domain in projection['bindingDomainOrder']:
        for template in selected[domain]['bindings']:
            row = {key: template[key]
                   for key in ('consumer', 'inputPosition', 'presence')}
            row['ftype'] = (template['ftype'] if 'ftype' in template else
                            selected[template['producerDomain']]['node']['ftype'])
            mode = template['mode']
            if mode == 'PHI':
                row['producer'] = {'kind': 'PHI_JOIN_PORT',
                                   'owner': template['consumer']}
                row['inputAuthority'] = 'PHI'
                row['producerAlternatives'] = [{
                    'producer': selected[item['producerDomain']]['node']['occurrence'],
                    'controlArm': item['controlArm'],
                    'sourceAuthorityRef':
                        selected[item['producerDomain']]['authority']['id']}
                    for item in template['producerAlternatives']]
            else:
                producer = selected[template['producerDomain']]
                row['producer'] = producer['node']['occurrence']
                row['sourceAuthorityRef'] = producer['authority']['id']
                row['inputAuthority'] = (
                    'DIRECT_FOUT' if mode == 'DIRECT_OR_FOUT' and
                    producer['node']['output'] == 'FOUT' else
                    'DIRECT' if mode == 'DIRECT_OR_FOUT' else mode)
                if mode == 'RELOCATION':
                    if template['actionRef'] not in action_ids:
                        raise ValueError('selected relocation action reference is dangling')
                    row['actionRef'] = template['actionRef']
            bindings.append(row)
    return {'schema': 'physical-plan-v1',
            'context': {'logical': projection['logicalProgram']},
            'nodes': nodes, 'authority': authorities, 'actions': actions,
            'bindings': bindings, 'logicalInputs': projection['logicalInputs'],
            'geometry': geometry}


def _blocked(model_sha, cell, blockers,
             diagnostic_status='DIAGNOSTIC_BLOCKED_INPUT', bindings=None):
    result = {
        'schema': SCHEMA,
        'status': 'BLOCKED',
        'diagnosticStatus': diagnostic_status,
        'claimScope': 'DIAGNOSTIC_TYPED_PROJECTION_IMAGE_ONLY',
        'semanticBindingStatus': 'BLOCKED_INDEPENDENT_SEMANTIC_BINDING',
        'modelSha256': model_sha,
        'cell': cell,
        'blockers': sorted(set(blockers)),
    }
    if bindings is not None:
        result['inputBindings'] = bindings
    return _seal(result)


def _normalized_factor_truth(scope, truth, radices):
    ordered = tuple(sorted(scope))
    values = []
    assignment = [0] * len(radices)
    for selected in product(*(range(radices[index]) for index in ordered)):
        for index, value in zip(ordered, selected):
            assignment[index] = value
        values.append(factor_status(scope, truth, assignment, radices) == 'ALLOW')
    return ordered, values


def _enumerate_definitely_accepted(radices, tables, max_visited_prefixes,
                                   max_accepted_assignments):
    """Enumerate ALLOW assignments, closing each factor at its last variable."""
    closing = [[] for _ in radices]
    empty_scope = []
    for scope, truth in tables:
        if scope:
            closing[max(scope)].append((scope, truth))
        else:
            empty_scope.append((scope, truth))
    zero = [0] * len(radices)
    visited = 1
    pruned = 0
    if any(factor_status(scope, truth, zero, radices) != 'ALLOW'
           for scope, truth in empty_scope):
        return [], {'visitedPrefixes': str(visited),
                    'prunedPrefixes': '1'}

    accepted = []
    stack = [()]
    while stack:
        prefix = stack.pop()
        depth = len(prefix)
        if depth == len(radices):
            accepted.append(prefix)
            if len(accepted) > max_accepted_assignments:
                raise PrefixEnumerationLimitError(
                    'ACCEPTED_ASSIGNMENT_BUDGET_EXHAUSTED')
            continue
        for value in reversed(range(radices[depth])):
            candidate = prefix + (value,)
            visited += 1
            if visited > max_visited_prefixes:
                raise PrefixEnumerationLimitError(
                    'VISITED_PREFIX_BUDGET_EXHAUSTED')
            assignment = candidate + (0,) * (len(radices) - len(candidate))
            if any(factor_status(scope, truth, assignment, radices) != 'ALLOW'
                   for scope, truth in closing[depth]):
                pruned += 1
            else:
                stack.append(candidate)
    return accepted, {'visitedPrefixes': str(visited),
                      'prunedPrefixes': str(pruned)}


def _decode_output_witness(manager, witness, native_count, coordinates,
                           coordinate_values, dictionary):
    if witness is None:
        return None
    result = {}
    for offset, coordinate in enumerate(coordinates):
        token = coordinate_values[coordinate][witness[native_count + offset]]
        result[coordinate] = dictionary[token]
    return result


def _relation_from_suffix_assignments(manager, start_level, assignments):
    """Build an exact trie relation without repeated cube/OR operations."""
    width = len(manager.variables) - start_level
    rows = set(tuple(row) for row in assignments)
    if any(len(row) != width for row in rows):
        raise ValueError('relation assignment has wrong width')
    if not rows:
        return manager.false
    handles = {row: manager.true for row in rows}
    for relative_level in range(width - 1, -1, -1):
        level = start_level + relative_level
        groups = {}
        for key, child in handles.items():
            prefix, value = key[:-1], key[-1]
            if (type(value) is not int or value < 0 or
                    value >= len(manager.variables[level].values)):
                raise ValueError('relation category index is invalid')
            children = groups.setdefault(
                prefix, [manager.false] * len(manager.variables[level].values))
            if children[value] != manager.false and children[value] != child:
                raise ValueError('relation is nondeterministic at one trie branch')
            children[value] = child
        handles = {prefix: manager.node(level, children)
                   for prefix, children in groups.items()}
    return handles[()]


def build(model_path, p_rows_path=None, *, max_nodes=250_000,
          max_apply_pairs=1_000_000, max_bag_cells=1_000_000,
          max_factor_cells=1_000_000,
          max_visited_prefixes=1_000_000,
          max_accepted_assignments=100_000,
          coordinates=REQUIRED_PHYSICAL_COORDINATES,
          allow_legacy_v1=False):
    """Construct an exact bounded image of accepted typed E projections.

    ``p_rows_path`` may point at provided physical rows.  Any comparison is
    labelled diagnostic because the E decoder's independent semantic binding
    remains unresolved.
    """
    for name, limit in (('max_nodes', max_nodes),
                        ('max_apply_pairs', max_apply_pairs),
                        ('max_bag_cells', max_bag_cells),
                        ('max_factor_cells', max_factor_cells),
                        ('max_visited_prefixes', max_visited_prefixes),
                        ('max_accepted_assignments', max_accepted_assignments)):
        if type(limit) is not int or limit < 1:
            raise ValueError(name + ' must be a positive integer')
    coordinates = tuple(coordinates)
    if (not coordinates or len(coordinates) != len(set(coordinates)) or
            any(key not in REQUIRED_PHYSICAL_COORDINATES for key in coordinates)):
        raise ValueError('coordinates must be unique common physical coordinates')

    model, model_sha, radices, tables = read_model(
        model_path, allow_legacy_v1=allow_legacy_v1)
    cell = model.get('cell')
    input_bindings = {
        'model': {'contentSha256': model_sha,
                  'fileSha256': file_digest(model_path)},
        'providedPImage': None,
    }
    p_rows = None
    if p_rows_path is not None:
        p_rows = load_exhaustive_physical_rows(p_rows_path)
        input_bindings['providedPImage'] = {
            'fileSha256': file_digest(p_rows_path),
            'rowsSha256': digest(p_rows),
            'rowCount': str(len(p_rows)),
            # A row file alone cannot prove that no P output is absent.
            'completenessStatus': 'UNVERIFIED_PROVIDED_ROWS',
        }
    _, occurrences = validate_identity(model)
    occurrence_to_domain = {domain['occurrence']: index
                            for index, domain in enumerate(model['domains'])}
    contract = projection_contract(model, occurrence_to_domain, occurrences, radices)
    if contract['status'] != 'DECODER_STRUCTURAL_ONLY':
        return _blocked(model_sha, cell,
                        contract['blockers'] or ['TYPED_DECODER_UNAVAILABLE'],
                        bindings=input_bindings)
    projection = model['physicalProjection']

    raw = prod(radices)
    if any(prod(radices[index] for index in scope) > max_factor_cells
           for scope, _ in tables):
        return _blocked(model_sha, cell, ['FACTOR_CELL_BUDGET_EXHAUSTED'],
                        'DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT', input_bindings)

    try:
        exact_accepted, _, _ = count_relation(
            radices, tables, frozenset(('ALLOW',)), max_bag_cells)
        exact_nonrejected, _, _ = count_relation(
            radices, tables, frozenset(('ALLOW', 'UNKNOWN')), max_bag_cells)
    except ValueError as error:
        if 'bag exceeds exact cell limit' not in str(error):
            raise
        return _blocked(model_sha, cell, ['FACTOR_ELIMINATION_BAG_BUDGET_EXHAUSTED'],
                        'DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT', input_bindings)

    try:
        accepted_assignments, enumeration = _enumerate_definitely_accepted(
            radices, tables, max_visited_prefixes, max_accepted_assignments)
    except PrefixEnumerationLimitError as error:
        return _blocked(model_sha, cell, [error.blocker],
                        'DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT', input_bindings)
    if len(accepted_assignments) != exact_accepted:
        raise AssertionError('prefix-pruned enumeration differs from factor elimination')
    unknown_assignments = exact_nonrejected - exact_accepted
    if not accepted_assignments:
        return _blocked(model_sha, cell, ['NO_DEFINITELY_ACCEPTED_ASSIGNMENTS'],
                        bindings=input_bindings)

    dictionary = {}
    decoded = []
    coordinate_domains = {key: set() for key in coordinates}
    for assignment in accepted_assignments:
        plan = _compose_validated_projection(projection, assignment)
        values = []
        for coordinate, value in zip(coordinates,
                                     _coordinate_tuple(plan, coordinates)):
            token = _token('o_', value, dictionary)
            coordinate_domains[coordinate].add(token)
            values.append(token)
        decoded.append((assignment, tuple(values)))

    p_outputs = [] if p_rows is not None else None
    if p_rows is not None:
        for row in p_rows:
            values = []
            for coordinate, value in zip(coordinates,
                                         _coordinate_tuple(row, coordinates)):
                token = _token('o_', value, dictionary)
                coordinate_domains[coordinate].add(token)
                values.append(token)
            p_outputs.append(tuple(values))

    variables = []
    for index, domain in enumerate(model['domains']):
        values = tuple(_token('n_', alternative, dictionary)
                       for alternative in domain['alternatives'])
        variables.append(Variable('native:%d:%s' % (index, domain['occurrence']), values))
    coordinate_values = {}
    for coordinate in coordinates:
        values = tuple(sorted(coordinate_domains[coordinate]))
        coordinate_values[coordinate] = values
        variables.append(Variable('output:' + coordinate, values))

    manager = MDDManager(variables, max_nodes=max_nodes,
                         max_apply_pairs=max_apply_pairs)
    try:
        acceptance = manager.true
        for scope, truth in tables:
            ordered, allowed = _normalized_factor_truth(scope, truth, radices)
            acceptance = manager.and_(acceptance,
                                      manager.from_factor(ordered, allowed))

        output_offsets = [{token: value for value, token in enumerate(
                           coordinate_values[coordinate])}
                          for coordinate in coordinates]
        accepted_projection_rows = []
        for assignment, outputs in decoded:
            accepted_projection_rows.append(tuple(assignment) + tuple(
                output_offsets[offset][token]
                for offset, token in enumerate(outputs)))
        accepted_projection = _relation_from_suffix_assignments(
            manager, 0, accepted_projection_rows)
        image = manager.exists(accepted_projection, range(len(radices)))
        if manager.count(accepted_projection) != exact_accepted:
            raise AssertionError('accepted typed projection differs from factor count')

        roots = {'definitelyAcceptedImage': image,
                 'acceptedTypedProjection': accepted_projection,
                 'nativeAcceptance': acceptance}
        comparison = None
        if p_outputs is not None:
            p_rows = [tuple(output_offsets[offset][token]
                            for offset, token in enumerate(outputs))
                      for outputs in p_outputs]
            p_root = _relation_from_suffix_assignments(
                manager, len(radices), p_rows)
            e_minus_p = manager.difference(image, p_root)
            p_minus_e = manager.difference(p_root, image)
            roots.update({'providedPImage': p_root, 'eMinusP': e_minus_p,
                          'pMinusE': p_minus_e})
            native_space = prod(radices)
            equal_projections = (manager.is_empty(e_minus_p) and
                                 manager.is_empty(p_minus_e))
            full_coordinates = coordinates == tuple(REQUIRED_PHYSICAL_COORDINATES)
            suppressed = []
            if unknown_assignments:
                suppressed.append('E_NATIVE_ACCEPTANCE_HAS_UNKNOWN')
            if input_bindings['providedPImage']['completenessStatus'] != \
                    'VERIFIED_COMPLETE':
                suppressed.append('P_IMAGE_COMPLETENESS_UNVERIFIED')
            if not full_coordinates:
                suppressed.append('PARTIAL_PHYSICAL_COORDINATES')
            if equal_projections and suppressed:
                comparison_status = 'COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE'
            elif equal_projections:
                comparison_status = 'SETS_MATCH_DIAGNOSTIC_ONLY'
            else:
                comparison_status = 'PROVIDED_IMAGES_DIFFER_DIAGNOSTIC_ONLY'
            comparison = {
                'status': comparison_status,
                'coordinateScope': ('FULL_REQUIRED_PHYSICAL_COORDINATES' if
                                    full_coordinates else
                                    'PARTIAL_PHYSICAL_COORDINATES'),
                'providedPCompletenessStatus':
                    input_bindings['providedPImage']['completenessStatus'],
                'matchSuppressedReasons': suppressed if equal_projections else [],
                'eMinusPCount': str(manager.count(e_minus_p) // native_space),
                'pMinusECount': str(manager.count(p_minus_e) // native_space),
                'eMinusPWitness': _decode_output_witness(
                    manager, manager.witness(e_minus_p), len(radices), coordinates,
                    coordinate_values, dictionary),
                'pMinusEWitness': _decode_output_witness(
                    manager, manager.witness(p_minus_e), len(radices), coordinates,
                    coordinate_values, dictionary),
            }
        relation = manager.to_artifact(roots)
        native_space = prod(radices)
        image_count_total = manager.count(image)
        if image_count_total % native_space:
            raise AssertionError('existential image retained a native dependency')
    except MDDResourceLimitError:
        return _blocked(model_sha, cell, ['MDD_NODE_OR_APPLY_BUDGET_EXHAUSTED'],
                        'DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT', input_bindings)

    result = {
        'schema': SCHEMA,
        'status': 'BLOCKED',
        'diagnosticStatus': 'DIAGNOSTIC_COMPLETE',
        'claimScope': 'DIAGNOSTIC_ACCEPTED_TYPED_PROJECTION_IMAGE_ONLY',
        'semanticBindingStatus': 'BLOCKED_INDEPENDENT_SEMANTIC_BINDING',
        'blockers': ['INDEPENDENT_TYPED_DECODER_SEMANTIC_BINDING_MISSING'],
        'cell': cell,
        'modelSha256': model_sha,
        'inputBindings': input_bindings,
        'coordinates': list(coordinates),
        'nativeAcceptanceStatus': ('COMPLETE' if unknown_assignments == 0
                                   else 'INCOMPLETE_UNKNOWN'),
        'counts': {'rawNativeAssignments': str(raw),
                   'definitelyAcceptedNativeAssignments': str(exact_accepted),
                   'unknownNativeAssignments': str(unknown_assignments),
                   'uniqueDefinitelyAcceptedOutputs': str(image_count_total // native_space)},
        'prefixEnumeration': enumeration,
        'budgets': {'maxNodes': max_nodes, 'maxApplyPairs': max_apply_pairs,
                    'maxBagCells': max_bag_cells,
                    'maxFactorCells': max_factor_cells,
                    'maxVisitedPrefixes': max_visited_prefixes,
                    'maxAcceptedAssignments': max_accepted_assignments},
        'valueDictionary': dict(sorted(dictionary.items())),
        'relation': relation,
        'comparison': comparison,
    }
    return _seal(result)


def read_artifact(path):
    value = _load_json(path)
    if not isinstance(value, dict):
        raise ValueError('symbolic image artifact is not an object')
    return value


def verify_artifact(artifact_path, model_path, p_rows_path=None,
                    expected_envelope_sha=None, *, allow_legacy_v1=False):
    """Verify a saved diagnostic artifact against an immutable commitment.

    Completed relations are deterministically replayed from the bound model and
    optional P rows under the stored budgets.  This remains a diagnostic typed
    projection check and deliberately does not upgrade semantic binding.
    """
    if not isinstance(expected_envelope_sha, str) or not expected_envelope_sha:
        raise ValueError('expected envelope commitment is required for verification PASS')
    artifact = read_artifact(artifact_path)
    envelope_sha = artifact.get('envelopeSha256')
    payload = {key: value for key, value in artifact.items()
               if key != 'envelopeSha256'}
    if (not isinstance(envelope_sha, str) or envelope_sha != digest(payload)):
        raise ValueError('symbolic image envelope digest mismatch')
    if envelope_sha != expected_envelope_sha:
        raise ValueError('symbolic image envelope differs from expected commitment')
    if (artifact.get('schema') != SCHEMA or artifact.get('status') != 'BLOCKED' or
            artifact.get('semanticBindingStatus') !=
            'BLOCKED_INDEPENDENT_SEMANTIC_BINDING' or
            not isinstance(artifact.get('blockers'), list) or
            not artifact['blockers'] or
            any(not isinstance(blocker, str) or not blocker
                for blocker in artifact['blockers'])):
        raise ValueError('symbolic image artifact claim shape is invalid')

    bindings = artifact.get('inputBindings')
    if not isinstance(bindings, dict) or set(bindings) != {'model', 'providedPImage'}:
        raise ValueError('symbolic image input bindings are invalid')
    model_binding = bindings['model']
    model, model_sha, radices, tables = read_model(
        model_path, allow_legacy_v1=allow_legacy_v1)
    if (not isinstance(model_binding, dict) or
            model_binding.get('contentSha256') != model_sha or
            model_binding.get('fileSha256') != file_digest(model_path) or
            artifact.get('modelSha256') != model_sha or
            artifact.get('cell') != model.get('cell')):
        raise ValueError('symbolic image model binding mismatch')

    p_binding = bindings['providedPImage']
    if (p_binding is None) != (p_rows_path is None):
        raise ValueError('symbolic image provided P input binding mismatch')
    if p_binding is not None:
        p_rows = load_exhaustive_physical_rows(p_rows_path)
        expected_p = {
            'fileSha256': file_digest(p_rows_path),
            'rowsSha256': digest(p_rows),
            'rowCount': str(len(p_rows)),
            'completenessStatus': 'UNVERIFIED_PROVIDED_ROWS',
        }
        if p_binding != expected_p:
            raise ValueError('symbolic image provided P input binding mismatch')

    relation = artifact.get('relation')
    dictionary = artifact.get('valueDictionary')
    comparison = artifact.get('comparison')
    if relation is None:
        if (dictionary is not None or comparison is not None or
                artifact.get('claimScope') !=
                'DIAGNOSTIC_TYPED_PROJECTION_IMAGE_ONLY' or
                artifact.get('diagnosticStatus') not in {
                    'DIAGNOSTIC_BLOCKED_INPUT',
                    'DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT'}):
            raise ValueError('blocked symbolic image has orphan result payload')
    else:
        if (artifact.get('diagnosticStatus') != 'DIAGNOSTIC_COMPLETE' or
                artifact.get('claimScope') !=
                'DIAGNOSTIC_ACCEPTED_TYPED_PROJECTION_IMAGE_ONLY' or
                artifact.get('blockers') != [
                    'INDEPENDENT_TYPED_DECODER_SEMANTIC_BINDING_MISSING']):
            raise ValueError('symbolic image completed diagnostic claim is invalid')
        if not isinstance(dictionary, dict):
            raise ValueError('symbolic image value dictionary is invalid')
        budgets = artifact.get('budgets')
        expected_budget_keys = {'maxNodes', 'maxApplyPairs', 'maxBagCells',
                                'maxFactorCells', 'maxVisitedPrefixes',
                                'maxAcceptedAssignments'}
        if (not isinstance(budgets, dict) or set(budgets) != expected_budget_keys or
                any(type(value) is not int or value < 1 for value in budgets.values())):
            raise ValueError('symbolic image budgets are invalid')
        exact_accepted, _, _ = count_relation(
            radices, tables, frozenset(('ALLOW',)), budgets['maxBagCells'])
        exact_nonrejected, _, _ = count_relation(
            radices, tables, frozenset(('ALLOW', 'UNKNOWN')),
            budgets['maxBagCells'])
        expected_counts = {
            'rawNativeAssignments': str(prod(radices)),
            'definitelyAcceptedNativeAssignments': str(exact_accepted),
            'unknownNativeAssignments': str(exact_nonrejected - exact_accepted),
        }
        counts = artifact.get('counts')
        if (not isinstance(counts, dict) or set(counts) != {
                'rawNativeAssignments', 'definitelyAcceptedNativeAssignments',
                'unknownNativeAssignments', 'uniqueDefinitelyAcceptedOutputs'} or
                any(counts.get(key) != value for key, value in expected_counts.items()) or
                artifact.get('nativeAcceptanceStatus') !=
                ('COMPLETE' if exact_nonrejected == exact_accepted else
                 'INCOMPLETE_UNKNOWN') or
                artifact.get('claimScope') !=
                'DIAGNOSTIC_ACCEPTED_TYPED_PROJECTION_IMAGE_ONLY'):
            raise ValueError('symbolic image factor counts are inconsistent')
        try:
            unique_outputs = int(counts['uniqueDefinitelyAcceptedOutputs'])
        except (TypeError, ValueError):
            raise ValueError('symbolic image factor counts are inconsistent')
        if (str(unique_outputs) != counts['uniqueDefinitelyAcceptedOutputs'] or
                unique_outputs < 0 or unique_outputs > exact_accepted):
            raise ValueError('symbolic image factor counts are inconsistent')
        enumeration = artifact.get('prefixEnumeration')
        if not isinstance(enumeration, dict) or set(enumeration) != {
                'visitedPrefixes', 'prunedPrefixes'}:
            raise ValueError('symbolic image prefix enumeration metadata is invalid')
        try:
            visited = int(enumeration['visitedPrefixes'])
            pruned = int(enumeration['prunedPrefixes'])
        except (TypeError, ValueError):
            raise ValueError('symbolic image prefix enumeration metadata is invalid')
        if (str(visited) != enumeration['visitedPrefixes'] or
                str(pruned) != enumeration['prunedPrefixes'] or
                visited < 1 or pruned < 0 or pruned >= visited or
                visited > budgets['maxVisitedPrefixes'] or
                exact_accepted > budgets['maxAcceptedAssignments']):
            raise ValueError('symbolic image prefix enumeration metadata is invalid')
        validated = validate_artifact(relation)
        referenced_tokens = {token for variable in validated.variables
                             for token in variable.values}
        if referenced_tokens != set(dictionary):
            raise ValueError('symbolic image dictionary coverage mismatch')
        for token, value in dictionary.items():
            prefix = ('n_' if token.startswith('n_') else
                      'o_' if token.startswith('o_') else None)
            if prefix is None or token != prefix + digest(value):
                raise ValueError('symbolic image dictionary token mismatch')

        coordinates = artifact.get('coordinates')
        if (not isinstance(coordinates, list) or not coordinates or
                len(coordinates) != len(set(coordinates)) or
                any(key not in REQUIRED_PHYSICAL_COORDINATES for key in coordinates)):
            raise ValueError('symbolic image coordinate scope is invalid')
        native_variables = [variable for variable in validated.variables
                            if variable.name.startswith('native:')]
        output_names = [variable.name for variable in validated.variables
                        if variable.name.startswith('output:')]
        if (len(native_variables) + len(output_names) != len(validated.variables) or
                output_names != ['output:' + key for key in coordinates]):
            raise ValueError('symbolic image variable dictionary is inconsistent')
        expected_native_variables = [
            ('native:%d:%s' % (index, domain['occurrence']),
             tuple('n_' + digest(alternative)
                   for alternative in domain['alternatives']))
            for index, domain in enumerate(model['domains'])]
        if [(variable.name, variable.values) for variable in native_variables] != \
                expected_native_variables:
            raise ValueError('symbolic image native dictionary differs from model')
        expected_roots = {'definitelyAcceptedImage', 'acceptedTypedProjection',
                          'nativeAcceptance'}
        if p_binding is not None:
            expected_roots.update({'providedPImage', 'eMinusP', 'pMinusE'})
        if set(validated.roots) != expected_roots:
            raise ValueError('symbolic image relation roots are inconsistent')
        if p_binding is None and comparison is not None:
            raise ValueError('symbolic image comparison lacks a provided P input')
        if p_binding is not None:
            if not isinstance(comparison, dict):
                raise ValueError('symbolic image comparison is missing')
            full_coordinates = coordinates == list(REQUIRED_PHYSICAL_COORDINATES)
            unknown = artifact.get('counts', {}).get('unknownNativeAssignments')
            status = comparison.get('status')
            expected_scope = ('FULL_REQUIRED_PHYSICAL_COORDINATES' if
                              full_coordinates else 'PARTIAL_PHYSICAL_COORDINATES')
            if (comparison.get('coordinateScope') != expected_scope or
                    comparison.get('providedPCompletenessStatus') !=
                    p_binding.get('completenessStatus')):
                raise ValueError('symbolic image comparison scope is inconsistent')
            equal_projections = (relation['roots']['eMinusP'] == 'F' and
                                 relation['roots']['pMinusE'] == 'F')
            suppressed = []
            if unknown != '0':
                suppressed.append('E_NATIVE_ACCEPTANCE_HAS_UNKNOWN')
            if p_binding.get('completenessStatus') != 'VERIFIED_COMPLETE':
                suppressed.append('P_IMAGE_COMPLETENESS_UNVERIFIED')
            if not full_coordinates:
                suppressed.append('PARTIAL_PHYSICAL_COORDINATES')
            expected_status = ('COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE'
                               if equal_projections and suppressed else
                               'SETS_MATCH_DIAGNOSTIC_ONLY' if equal_projections else
                               'PROVIDED_IMAGES_DIFFER_DIAGNOSTIC_ONLY')
            if (status != expected_status or
                    comparison.get('matchSuppressedReasons') !=
                    (suppressed if equal_projections else [])):
                raise ValueError('symbolic image comparison verdict is inconsistent')
            if (equal_projections and
                    (comparison.get('eMinusPCount') != '0' or
                     comparison.get('pMinusECount') != '0' or
                     comparison.get('eMinusPWitness') is not None or
                     comparison.get('pMinusEWitness') is not None)):
                raise ValueError('symbolic image empty differences are inconsistent')

        # Replay the complete bounded construction.  Structural ROMDD validation
        # alone cannot show that its roots are the factor relation, typed image,
        # and directional differences named by the envelope.
        replay = build(
            model_path, p_rows_path,
            max_nodes=budgets['maxNodes'],
            max_apply_pairs=budgets['maxApplyPairs'],
            max_bag_cells=budgets['maxBagCells'],
            max_factor_cells=budgets['maxFactorCells'],
            max_visited_prefixes=budgets['maxVisitedPrefixes'],
            max_accepted_assignments=budgets['maxAcceptedAssignments'],
            coordinates=tuple(coordinates), allow_legacy_v1=allow_legacy_v1)
        replay_fields = (
            'diagnosticStatus', 'claimScope', 'semanticBindingStatus', 'blockers',
            'nativeAcceptanceStatus', 'counts', 'prefixEnumeration', 'budgets',
            'coordinates', 'valueDictionary', 'relation', 'comparison')
        if replay.get('diagnosticStatus') != 'DIAGNOSTIC_COMPLETE' or any(
                artifact.get(field) != replay.get(field) for field in replay_fields):
            raise ValueError('symbolic image relation replay differs from saved artifact')

    return {
        'schema': 'symbolic-e-physical-image-verification-v1',
        'status': 'PASS',
        'claimScope': 'IMMUTABLY_COMMITTED_ARTIFACT_AND_BOUNDED_RELATION_REPLAY_ONLY',
        'artifactEnvelopeSha256': envelope_sha,
        'modelSha256': model_sha,
        'providedPImageSha256': (None if p_binding is None else
                                 p_binding['fileSha256']),
        'semanticBindingStatus': artifact.get('semanticBindingStatus'),
    }


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('wb', dir=path.parent, delete=False) as raw:
        temporary = Path(raw.name)
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as stream:
            stream.write(canonical(value) + b'\n')
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', nargs='?', choices=('run', 'verify'), default='run')
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--p-rows', type=Path)
    parser.add_argument('--expected-envelope-sha256')
    parser.add_argument('--max-nodes', type=int, default=250_000)
    parser.add_argument('--max-apply-pairs', type=int, default=1_000_000)
    parser.add_argument('--max-bag-cells', type=int, default=1_000_000)
    parser.add_argument('--max-factor-cells', type=int, default=1_000_000)
    parser.add_argument('--max-visited-prefixes', type=int, default=1_000_000)
    parser.add_argument('--max-accepted-assignments', type=int, default=100_000)
    parser.add_argument('--legacy-v1', action='store_true')
    args = parser.parse_args()
    if args.mode == 'verify':
        result = verify_artifact(args.artifact, args.model, args.p_rows,
                                 args.expected_envelope_sha256,
                                 allow_legacy_v1=args.legacy_v1)
        print(json.dumps(result, sort_keys=True))
        return
    result = build(args.model, args.p_rows, max_nodes=args.max_nodes,
                   max_apply_pairs=args.max_apply_pairs,
                   max_bag_cells=args.max_bag_cells,
                   max_factor_cells=args.max_factor_cells,
                   max_visited_prefixes=args.max_visited_prefixes,
                   max_accepted_assignments=args.max_accepted_assignments,
                   allow_legacy_v1=args.legacy_v1)
    publish(args.artifact, result)
    print(json.dumps({key: result.get(key) for key in
                      ('cell', 'status', 'diagnosticStatus',
                       'nativeAcceptanceStatus', 'counts')}, sort_keys=True))


if __name__ == '__main__':
    main()
