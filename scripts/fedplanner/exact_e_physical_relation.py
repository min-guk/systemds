#!/usr/bin/env python3
"""Build and recheck a factorized E relation and its typed physical decoder.

The saved E model preserves accepted, rejected, and unknown native assignments.
Compiler-owned typed fragments permit structural decoding and bounded replay,
but they do not independently prove that every physical coordinate matches the
Java projector. Physical-set completion remains blocked until that semantic
binding and exact image projection are independently certified.
"""

import argparse
import gzip
import hashlib
from itertools import product
import json
from pathlib import Path
import re
import tempfile

from exact_e_factor_count import (count_relation, decode_factor_truth,
                                  factor_truth_wire, read_model)


REQUIRED_PHYSICAL_COORDINATES = (
    'nodes', 'authority', 'actions', 'bindings', 'logicalInputs', 'geometry')
COMPOSITIONAL_CONTRACT = 'EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL'


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def factor_status(scope, truth, assignment, radices):
    offset = 0
    for index in scope:
        offset = offset * radices[index] + assignment[index]
    return truth[offset]


def validate_identity(model):
    identity = model.get('sourceIdentity')
    if not isinstance(identity, dict):
        raise ValueError('E model lacks sourceIdentity')
    for key in ('nodes', 'orderedInputs', 'logicalInputs'):
        if not isinstance(identity.get(key), list):
            raise ValueError('E sourceIdentity lacks ' + key)
    occurrences = []
    for node in identity['nodes']:
        occurrence = node.get('occurrence') if isinstance(node, dict) else None
        if not isinstance(occurrence, str) or not occurrence:
            raise ValueError('E source identity node lacks occurrence')
        occurrences.append(occurrence)
    if len(occurrences) != len(set(occurrences)):
        raise ValueError('E source identity has duplicate occurrences')
    return identity, set(occurrences)


def projection_contract(model, occurrence_to_domain, identity_occurrences, radices):
    """Validate the compiler-owned typed projection program without name inference."""
    blockers = []
    projection = model.get('physicalProjection')
    if model.get('physicalProjectionContract') != COMPOSITIONAL_CONTRACT:
        blockers.append('EXPLICIT_COMPOSITIONAL_PROJECTION_CONTRACT_MISSING')
    if 'physicalLogicalInputs' not in model.get('sourceIdentity', {}):
        blockers.append('PHYSICAL_LOGICAL_INPUTS_MISSING')
    if not set(occurrence_to_domain).issubset(identity_occurrences):
        blockers.append('DOMAIN_OCCURRENCE_MISSING_FROM_SOURCE_IDENTITY')
    if not isinstance(projection, dict) or projection.get('schema') != \
            'exact-physical-compositional-projection-v1':
        blockers.append('COMPOSITIONAL_PROJECTION_OBJECT_MISSING')
    else:
        try:
            validate_projection_program(model, occurrence_to_domain, radices)
        except ValueError:
            blockers.append('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID')
    blockers = sorted(set(blockers))
    return {
        'contract': model.get('physicalProjectionContract'),
        'requiredCoordinates': list(REQUIRED_PHYSICAL_COORDINATES),
        # Structural checks and source/state cross-checks cannot certify every
        # action, binding, and authority against the native Java alternative.
        'status': 'DECODER_STRUCTURAL_ONLY' if not blockers else 'BLOCKED_NONCOMPOSITIONAL',
        'blockers': blockers,
        'projectionSha256': digest(projection) if isinstance(projection, dict) else None,
    }


def _owner(value, label):
    if not isinstance(value, dict):
        raise ValueError(label + ' lacks typed occurrence')
    for key in ('sourceOrigin', 'functionNamespace', 'callSitePath',
                'emittedInstance', 'controlRegion'):
        if key not in value:
            raise ValueError(label + ' occurrence is incomplete')
    if not isinstance(value['controlRegion'], dict):
        raise ValueError(label + ' control region is not typed')
    return value


def validate_projection_program(model, occurrence_to_domain, radices):
    projection = model['physicalProjection']
    if projection.get('logicalProgram') != model.get('programSha256'):
        raise ValueError('projection logical program identity drift')
    logical = projection.get('logicalInputs')
    if (not isinstance(logical, list) or
            logical != model['sourceIdentity'].get('physicalLogicalInputs')):
        raise ValueError('projection physical logical inputs drift')
    variables = projection.get('variables')
    if not isinstance(variables, list) or len(variables) != len(radices):
        raise ValueError('projection variables do not cover E domains')
    source_nodes = {node['occurrence']: node for node in
                    model['sourceIdentity']['nodes']}
    domains_with_bindings = set()
    for index, variable in enumerate(variables):
        if not isinstance(variable, dict) or variable.get('domain') != index:
            raise ValueError('projection variable index drift')
        owner = _owner(variable.get('occurrence'), 'projection variable')
        if (owner.get('sourceOrigin') != model['domains'][index].get('occurrence') or
                owner.get('emittedInstance') != model['domains'][index].get('occurrence')):
            raise ValueError('projection variable occurrence drift')
        alternatives = variable.get('alternatives')
        if not isinstance(alternatives, list) or len(alternatives) != radices[index]:
            raise ValueError('projection alternative coverage drift')
        source = source_nodes.get(model['domains'][index]['occurrence'])
        if not isinstance(source, dict) or not isinstance(source.get('operation'), str):
            raise ValueError('projection source operation is not frozen')
        for ordinal, fragment in enumerate(alternatives):
            _validate_fragment(fragment, owner, len(radices), source,
                               model['domains'][index]['alternatives'][ordinal])
            if fragment['bindings']:
                domains_with_bindings.add(index)
    node_order = projection.get('nodeOrder')
    if (not isinstance(node_order, list) or len(node_order) != len(radices) or
            set(node_order) != set(range(len(radices))) or
            any(type(index) is not int for index in node_order)):
        raise ValueError('projection node order is not a domain permutation')
    binding_order = projection.get('bindingDomainOrder')
    if (not isinstance(binding_order, list) or len(binding_order) != len(set(binding_order)) or
            any(type(index) is not int or index < 0 or index >= len(radices)
                for index in binding_order) or set(binding_order) != domains_with_bindings):
        raise ValueError('projection binding order does not cover binding domains')
    return projection


def _validate_fragment(fragment, owner, domain_count, source, native_alternative):
    if not isinstance(fragment, dict) or set(fragment) != {
            'node', 'authority', 'actions', 'geometry', 'bindings'}:
        raise ValueError('projection fragment shape is invalid')
    node = fragment['node']
    authority = fragment['authority']
    if not isinstance(node, dict) or _owner(node.get('occurrence'), 'node') != owner:
        raise ValueError('projection node owner drift')
    for key in ('opcode', 'exec', 'output', 'ftype', 'shapeDependent',
                'executionFType', 'valueVersion', 'authorityRef'):
        if key not in node:
            raise ValueError('projection node is incomplete')
    if node['opcode'] != source['operation']:
        raise ValueError('projection opcode differs from frozen source operation')
    state = native_alternative.get('state')
    parts = state.split('/') if isinstance(state, str) else []
    if len(parts) != 4 or parts[3] not in ('SHAPE_DEPENDENT', 'SHAPE_INDEPENDENT'):
        raise ValueError('native alternative state is not independently decodable')
    if (node['exec'] != parts[0] or node['output'] != parts[1] or
            node['ftype'] != ('NONE' if parts[2] == '-' else parts[2]) or
            node['shapeDependent'] is not (parts[3] == 'SHAPE_DEPENDENT')):
        raise ValueError('projection node state differs from native alternative')
    if (not isinstance(authority, dict) or authority.get('owner') != owner or
            node['authorityRef'] != authority.get('id')):
        raise ValueError('projection authority reference drift')
    for key in ('source', 'kind'):
        if not isinstance(authority.get(key), str):
            raise ValueError('projection authority is incomplete')
    _validate_authority_id(authority['id'], owner)
    if authority['id']['layout'] == 'SOURCE_LINEAGE':
        if (not isinstance(source.get('externalSource'), dict) or
                authority['id'].get('externalSource') != source['externalSource']):
            raise ValueError('projection source lineage lacks frozen external source')
    elif 'externalSource' in authority['id']:
        raise ValueError('non-source authority carries external source')
    for key in ('actions', 'geometry', 'bindings'):
        if not isinstance(fragment[key], list):
            raise ValueError('projection fragment ' + key + ' is not a list')
    action_ids = []
    for action in fragment['actions']:
        if (not isinstance(action, dict) or action.get('kind') not in
                ('RELOCATION', 'DERIVED_FOUT') or 'id' not in action or 'owner' not in action):
            raise ValueError('projection action is incomplete')
        action_ids.append(action['id'])
    for geometry in fragment['geometry']:
        if (not isinstance(geometry, dict) or not isinstance(geometry.get('worker'), str) or
                not isinstance(geometry.get('ranges'), list) or
                len(geometry['ranges']) != 2 or 'owner' not in geometry or
                not isinstance(geometry.get('ftype'), str)):
            raise ValueError('projection geometry is incomplete')
    positions = set()
    for binding in fragment['bindings']:
        if not isinstance(binding, dict) or binding.get('consumer') != owner:
            raise ValueError('projection binding consumer drift')
        position = binding.get('inputPosition')
        mode = binding.get('mode')
        if (type(position) is not int or position < 0 or position in positions or
                mode not in ('PHI', 'LOGICAL_TRANSIENT', 'RELOCATION',
                    'ABSENT_LOCAL', 'DIRECT_OR_FOUT') or
                not isinstance(binding.get('presence'), str)):
            raise ValueError('projection binding identity is invalid')
        positions.add(position)
        if ('ftype' in binding) == ('ftypeFromProducer' in binding):
            raise ValueError('projection binding ftype source is ambiguous')
        if 'ftype' in binding and not isinstance(binding['ftype'], str):
            raise ValueError('projection binding ftype is invalid')
        if 'ftypeFromProducer' in binding and binding['ftypeFromProducer'] is not True:
            raise ValueError('projection binding producer ftype flag is invalid')
        if mode == 'PHI':
            alternatives = binding.get('producerAlternatives')
            if ('producerDomain' in binding or not isinstance(alternatives, list) or
                    not alternatives):
                raise ValueError('projection PHI binding is incomplete')
            arms = set()
            for alternative in alternatives:
                domain = alternative.get('producerDomain') if isinstance(alternative, dict) else None
                arm = alternative.get('controlArm') if isinstance(alternative, dict) else None
                if (type(domain) is not int or domain < 0 or domain >= domain_count or
                        not isinstance(arm, str) or not arm or arm in arms):
                    raise ValueError('projection PHI producer is invalid')
                arms.add(arm)
        else:
            domain = binding.get('producerDomain')
            if (type(domain) is not int or domain < 0 or domain >= domain_count or
                    'producerAlternatives' in binding):
                raise ValueError('projection direct producer is invalid')
        if mode == 'RELOCATION':
            if 'actionRef' not in binding or binding['actionRef'] not in action_ids:
                raise ValueError('projection relocation action reference is dangling')
        elif 'actionRef' in binding:
            raise ValueError('non-relocation projection binding has action reference')


def _validate_authority_id(identity, owner):
    if not isinstance(identity, dict) or identity.get('owner') != owner:
        raise ValueError('projection authority id owner drift')
    if not isinstance(identity.get('kind'), str):
        raise ValueError('projection authority id kind is missing')
    layout = identity.get('layout')
    if layout not in ('BOUNDARY', 'LOCAL', 'SOURCE_LINEAGE',
                      'DURABLE_MAP', 'NATIVE_LINEAGE'):
        raise ValueError('projection authority layout is invalid')
    anchor = identity.get('anchor')
    residency = identity.get('workerResidency')
    if layout == 'DURABLE_MAP' and (anchor is None or residency is not None):
        raise ValueError('durable authority lacks exact anchor')
    if layout == 'NATIVE_LINEAGE' and ((anchor is None) == (residency is None)):
        raise ValueError('native authority must have exact anchor or endpoint residency')
    if layout not in ('DURABLE_MAP', 'NATIVE_LINEAGE') and (
            anchor is not None or residency is not None):
        raise ValueError('non-federated authority carries worker geometry')
    if anchor is not None:
        _validate_anchor(anchor)
    if residency is not None:
        if (not isinstance(residency, dict) or set(residency) !=
                {'ftype', 'endpoints', 'layoutExact'} or
                residency.get('layoutExact') is not False or
                residency.get('ftype') not in ('ROW', 'COL', 'FULL')):
            raise ValueError('dynamic native residency contract is invalid')
        endpoints = residency.get('endpoints')
        if (not isinstance(endpoints, list) or not endpoints or
                endpoints != sorted(set(endpoints)) or
                any(not isinstance(endpoint, str) or not
                    re.fullmatch(r'[^\s/:]+:[0-9]+', endpoint)
                    for endpoint in endpoints)):
            raise ValueError('dynamic native endpoint authority is invalid')
        for endpoint in endpoints:
            port = int(endpoint.rsplit(':', 1)[1])
            if port > 65535:
                raise ValueError('dynamic native endpoint port is invalid')


def _validate_anchor(anchor):
    if (not isinstance(anchor, dict) or set(anchor) != {'ftype', 'partitions'} or
            not isinstance(anchor.get('ftype'), str) or
            not isinstance(anchor.get('partitions'), list) or not anchor['partitions']):
        raise ValueError('exact worker anchor is invalid')
    for partition in anchor['partitions']:
        if (not isinstance(partition, dict) or set(partition) !=
                {'worker', 'begin', 'end'} or
                not isinstance(partition.get('worker'), str) or
                not isinstance(partition.get('begin'), list) or
                not isinstance(partition.get('end'), list) or
                not partition['begin'] or len(partition['begin']) != len(partition['end']) or
                any(type(value) is not int for value in
                    partition['begin'] + partition['end'])):
            raise ValueError('exact worker anchor partition is invalid')


def _ordered_union(rows):
    result = []
    seen = set()
    for row in rows:
        key = canonical(row)
        if key not in seen:
            seen.add(key)
            result.append(row)
    return result


def compose_physical_projection(model, assignment):
    """Decode one canonical physical-plan-v1 row from typed compiler fragments."""
    radices = [len(domain['alternatives']) for domain in model['domains']]
    projection = validate_projection_program(
        model, {domain['occurrence']: index for index, domain in enumerate(model['domains'])},
        radices)
    if (not isinstance(assignment, (list, tuple)) or len(assignment) != len(radices) or
            any(type(value) is not int or value < 0 or value >= radices[index]
                for index, value in enumerate(assignment))):
        raise ValueError('projection assignment is incomplete')
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
            row = {key: template[key] for key in
                    ('consumer', 'inputPosition', 'presence')}
            row['ftype'] = (template['ftype'] if 'ftype' in template else
                selected[template['producerDomain']]['node']['ftype'])
            mode = template['mode']
            if mode == 'PHI':
                row['producer'] = {'kind': 'PHI_JOIN_PORT', 'owner': template['consumer']}
                row['inputAuthority'] = 'PHI'
                row['producerAlternatives'] = [{
                    'producer': selected[item['producerDomain']]['node']['occurrence'],
                    'controlArm': item['controlArm'],
                    'sourceAuthorityRef': selected[item['producerDomain']]['authority']['id']}
                    for item in template['producerAlternatives']]
            else:
                producer = selected[template['producerDomain']]
                row['producer'] = producer['node']['occurrence']
                row['sourceAuthorityRef'] = producer['authority']['id']
                row['inputAuthority'] = ('DIRECT_FOUT' if mode == 'DIRECT_OR_FOUT'
                    and producer['node']['output'] == 'FOUT' else
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


def decode_scopes(model, occurrence_to_domain):
    """Retain cross-variable identity edges needed by the Java decoder."""
    scopes = []
    identity = model['sourceIdentity']
    for edge in identity['orderedInputs']:
        if not isinstance(edge, dict):
            raise ValueError('ordered input identity is not an object')
        consumer = edge.get('consumer')
        producer = edge.get('producer')
        position = edge.get('inputPosition')
        if (not isinstance(consumer, str) or not isinstance(producer, str) or
                type(position) is not int):
            raise ValueError('ordered input identity is incomplete')
        indices = [occurrence_to_domain[value] for value in (consumer, producer)
                   if value in occurrence_to_domain]
        scopes.append({'kind': 'ORDERED_INPUT', 'consumer': consumer,
                       'producer': producer, 'inputPosition': position,
                       'domainScope': sorted(set(indices)),
                       'fullyBound': len(indices) == 2})
    for edge in identity['logicalInputs']:
        if not isinstance(edge, dict):
            raise ValueError('logical input identity is not an object')
        endpoints = [edge.get(key) for key in ('source', 'target')]
        indices = [occurrence_to_domain[value] for value in endpoints
                   if isinstance(value, str) and value in occurrence_to_domain]
        scopes.append({'kind': 'LOGICAL_INPUT', 'identity': edge,
                       'domainScope': sorted(set(indices)),
                       'fullyBound': len(indices) == 2})
    return scopes


def exhaustive_differential(model, relation, radices, tables, limit):
    raw = 1
    for radix in radices:
        raw *= radix
    if raw > limit:
        return {'status': 'SKIPPED_RAW_LIMIT', 'raw': str(raw), 'limit': limit}
    accepted = 0
    projected = set()
    physical = {}
    variables = relation['variables']
    factors = []
    for row in relation['factors']:
        cells = 1
        for index in row['scope']:
            cells *= radices[index]
        factors.append((row['scope'], decode_factor_truth(row['truth'], cells)))
    for values in product(*(range(radix) for radix in radices)):
        direct = all(factor_status(scope, truth, values, radices) == 'ALLOW'
                     for scope, truth in tables)
        replay = all(factor_status(scope, truth, values, radices) == 'ALLOW'
                     for scope, truth in factors)
        if direct != replay:
            raise ValueError('factorized relation exhaustive differential failed')
        direct_projection = tuple(digest(model['domains'][index]['alternatives'][value])
                                  for index, value in enumerate(values))
        replay_projection = tuple(variables[index]['choices'][value]
                                  for index, value in enumerate(values))
        if direct_projection != replay_projection:
            raise ValueError('physical witness projection exhaustive differential failed')
        if direct:
            accepted += 1
            projected.add(replay_projection)
            if relation['projectionContract']['status'] == 'DECODER_STRUCTURAL_ONLY':
                plan = compose_physical_projection(model, values)
                key = digest(plan)
                prior = physical.setdefault(key, plan)
                if prior != plan:
                    raise ValueError('canonical physical plan SHA-256 collision')
    return {'status': 'FACTOR_WITNESS_REPLAY_COMPLETE', 'raw': str(raw),
            'accepted': str(accepted),
            'uniqueSerializedWitnesses': str(len(projected)),
            'uniqueCanonicalPhysicalPlans': (str(len(physical)) if
                relation['projectionContract']['status'] == 'DECODER_STRUCTURAL_ONLY' else None),
            'canonicalPhysicalPlans': dict(sorted(physical.items()))}


def build(model_path, max_bag_cells=1_000_000, exhaustive_limit=100_000, *,
          allow_legacy_v1=False):
    model, model_sha, radices, tables = read_model(
        model_path, allow_legacy_v1=allow_legacy_v1)
    identity, identity_occurrences = validate_identity(model)
    dictionary = {}
    variables = []
    occurrence_to_domain = {}
    for index, domain in enumerate(model['domains']):
        occurrence = domain.get('occurrence')
        if (domain.get('index') != index or not isinstance(occurrence, str) or
                occurrence in occurrence_to_domain):
            raise ValueError('E domain identity is invalid or duplicated')
        occurrence_to_domain[occurrence] = index
        choices = []
        for alternative in domain['alternatives']:
            key = digest(alternative)
            prior = dictionary.setdefault(key, alternative)
            if prior != alternative:
                raise ValueError('serialized physical payload SHA-256 collision')
            choices.append(key)
        variables.append({'index': index, 'occurrence': occurrence,
                          'nodeKind': domain.get('nodeKind'),
                          'radix': len(choices), 'choices': choices})
    factors = [{'scope': list(scope), 'truth': factor_truth_wire(truth)}
               for scope, truth in tables]
    accepted, accept_peak, accept_trace = count_relation(
        radices, tables, frozenset(('ALLOW',)), max_bag_cells)
    nonrejected, nonreject_peak, nonreject_trace = count_relation(
        radices, tables, frozenset(('ALLOW', 'UNKNOWN')), max_bag_cells)
    raw = 1
    for radix in radices:
        raw *= radix
    unknown = nonrejected - accepted
    contract = projection_contract(model, occurrence_to_domain, identity_occurrences, radices)
    relation = {
        'schema': 'exact-e-physical-relation-v1',
        'status': 'BLOCKED',
        'nativeRelationStatus': 'COMPLETE' if unknown == 0 else 'INCOMPLETE_UNKNOWN',
        'physicalProjectionStatus': contract['status'],
        'physicalImageStatus': ('BLOCKED_UNKNOWN_NATIVE_ACCEPTANCE' if unknown
                                else 'BLOCKED_SEMANTIC_BINDING' if
                                contract['status'] == 'DECODER_STRUCTURAL_ONLY'
                                else 'BLOCKED_NONCOMPOSITIONAL'),
        'claimScope': ('STRUCTURAL_DECODER_ONLY' if
                       contract['status'] == 'DECODER_STRUCTURAL_ONLY'
                       else 'SERIALIZED_E_WITNESS_RELATION_ONLY'),
        'cell': model['cell'], 'modelSha256': model_sha,
        'programSha256': model.get('programSha256'),
        'conditionSha256': model.get('conditionSha256'),
        'sourceFiles': model.get('sourceFiles'),
        'counts': {'raw': str(raw), 'accepted': str(accepted),
                   'rejected': str(raw - nonrejected),
                   'unknown': str(unknown)},
        'maxBagCells': max(accept_peak, nonreject_peak),
        'variables': variables,
        'payloadDictionary': dict(sorted(dictionary.items())),
        'factors': factors,
        'sourceIdentity': identity,
        'decodeScopes': decode_scopes(model, occurrence_to_domain),
        'projectionContract': contract,
        'physicalProjection': model.get('physicalProjection'),
        'acceptTrace': accept_trace,
        'nonrejectTrace': nonreject_trace,
        'runtimeSemanticCoverage': 'NOT_ASSESSED_BY_THIS_CONTRACT',
    }
    relation['exhaustiveDifferential'] = exhaustive_differential(
        model, relation, radices, tables, exhaustive_limit)
    differential = relation['exhaustiveDifferential']
    differential.pop('canonicalPhysicalPlans', None)
    if (not unknown and contract['status'] == 'DECODER_STRUCTURAL_ONLY' and
            differential['status'] != 'FACTOR_WITNESS_REPLAY_COMPLETE'):
        relation['physicalImageStatus'] = 'BLOCKED_EXHAUSTIVE_LIMIT_AND_SEMANTIC_BINDING'
    return relation


def read_artifact(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        return json.load(stream)


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('wb', dir=path.parent, delete=False) as raw:
        temporary = Path(raw.name)
        with gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as stream:
            stream.write(canonical(value))
            stream.write(b'\n')
    temporary.replace(path)


def verify_artifact(model_path, artifact_path, max_bag_cells=1_000_000,
                    exhaustive_limit=100_000, *, allow_legacy_v1=False):
    expected = build(model_path, max_bag_cells, exhaustive_limit,
                     allow_legacy_v1=allow_legacy_v1)
    if read_artifact(artifact_path) != expected:
        raise ValueError('saved E physical relation differs from independent recomputation')
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('run', 'verify'))
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--max-bag-cells', type=int, default=1_000_000)
    parser.add_argument('--exhaustive-limit', type=int, default=100_000)
    parser.add_argument('--require-complete', action='store_true',
                        help='fail unless canonical physical projection is certified complete')
    parser.add_argument('--legacy-v1', action='store_true')
    args = parser.parse_args()
    if args.max_bag_cells < 1 or args.exhaustive_limit < 0:
        parser.error('limits must be nonnegative and max-bag-cells must be positive')
    result = build(args.model, args.max_bag_cells, args.exhaustive_limit,
                   allow_legacy_v1=args.legacy_v1)
    if args.mode == 'run':
        publish(args.artifact, result)
    else:
        result = verify_artifact(args.model, args.artifact, args.max_bag_cells,
                                 args.exhaustive_limit,
                                 allow_legacy_v1=args.legacy_v1)
    if args.require_complete and result['status'] != 'COMPLETE':
        blockers = result['projectionContract']['blockers'] or [result['physicalImageStatus']]
        raise ValueError('canonical E physical image is not complete: ' + ','.join(blockers))
    print(json.dumps({key: result[key] for key in
                      ('cell', 'status', 'nativeRelationStatus',
                       'physicalProjectionStatus', 'counts', 'maxBagCells')},
                     sort_keys=True))


if __name__ == '__main__':
    main()
