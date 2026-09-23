#!/usr/bin/env python3
"""Recompute finite E-native factor coverage from a saved model, without Java.

This checks the captured factor tables and emitted assignment receipt. It does not
establish that the E model contains every semantically feasible physical plan.
"""
import argparse
import gzip
import hashlib
import json
from itertools import product
from pathlib import Path

from exact_e_factor_count import decode_factor_truth


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def factor_relation(radices, factors, *, component_limit=2_000_000,
                    accepted_limit=10_000_000):
    """Enumerate disconnected hard-factor components, then join accepted tuples.

    Singleton domains are constants, so they cannot couple two varying
    components. Counts cover the complete Cartesian raw space, including
    rejected and unknown assignments. Returned ordinals are exact integers in
    native mixed-radix order; no producer receipt is used to derive them.
    """
    parent = list(range(len(radices)))

    def root(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    for scope, _ in factors:
        varying = [index for index in scope if radices[index] > 1]
        for index in varying[1:]:
            parent[root(index)] = root(varying[0])
    groups = {}
    for index, radix in enumerate(radices):
        if radix > 1:
            groups.setdefault(root(index), []).append(index)
    components = list(groups.values()) or [[]]
    component_factors = [[] for _ in components]
    owner = {index: component for component, indices in enumerate(components)
             for index in indices}
    for scope, truth in factors:
        varying = [index for index in scope if radices[index] > 1]
        component = owner[varying[0]] if varying else 0
        if any(owner[index] != component for index in varying):
            raise ValueError('factor crosses disconnected components')
        component_factors[component].append((scope, truth))
    suffix = [1] * (len(radices) + 1)
    for index in range(len(radices) - 1, -1, -1):
        suffix[index] = suffix[index + 1] * radices[index]
    accepted_components = []
    nonrejected = 1
    accepted = 1
    for indices, local_factors in zip(components, component_factors):
        size = 1
        for index in indices:
            size *= radices[index]
        if size > component_limit:
            raise ValueError('factor component exceeds exact enumeration limit')
        allow_contributions = []
        local_nonrejected = 0
        for choices in product(*(range(radices[index]) for index in indices)):
            assignment = dict(zip(indices, choices))
            unresolved = False
            invalid = False
            for scope, truth in local_factors:
                offset = 0
                for index in scope:
                    offset = offset * radices[index] + assignment.get(index, 0)
                status = truth[offset]
                if status == 'REJECT':
                    invalid = True
                    break
                unresolved |= status == 'UNKNOWN'
            if invalid:
                continue
            local_nonrejected += 1
            if not unresolved:
                allow_contributions.append(sum(value * suffix[index + 1]
                                               for index, value in assignment.items()))
        nonrejected *= local_nonrejected
        accepted *= len(allow_contributions)
        accepted_components.append(allow_contributions)
    if accepted > accepted_limit:
        raise ValueError('accepted factor relation exceeds exact ordinal limit')
    ordinals = [sum(parts) for parts in product(*accepted_components)]
    ordinals.sort()
    if len(ordinals) != accepted or len(set(ordinals)) != accepted:
        raise ValueError('factor relation has duplicate native ordinals')
    return {'accepted': accepted, 'unknown': nonrejected - accepted,
            'rejected': suffix[0] - nonrejected}, ordinals


def verify(model_path, expected_model_sha, rows_path, receipt_path, compact_result=None):
    plain = gzip.decompress(Path(model_path).read_bytes())
    if hashlib.sha256(plain).hexdigest() != expected_model_sha:
        raise ValueError('E model artifact SHA-256 mismatch')
    model = json.loads(plain)
    receipt = json.loads(Path(receipt_path).read_text())
    if (model.get('schema') != 'closed-e-native-model-artifact-v1' or
            model.get('acceptance') != 'MATERIALIZED_FACTOR_TABLES' or
            receipt.get('schema') not in ('closed-planning-physical-shard-v1',
                                          'closed-planning-physical-shard-compact-v1') or
            receipt.get('source') != 'E_C0' or receipt.get('status') != 'COMPLETE' or
            receipt.get('cell') != model.get('cell') or
            receipt.get('programSha256') != model.get('programSha256') or
            receipt.get('conditionSha256') != model.get('conditionSha256') or
            receipt.get('sourceFiles') != model.get('sourceFiles')):
        raise ValueError('E model and physical receipt bindings differ')
    domains = model.get('domains')
    factors = model.get('factors')
    if not isinstance(domains, list) or not domains or not isinstance(factors, list):
        raise ValueError('E model lacks finite domains/factors')
    radices = []
    for index, domain in enumerate(domains):
        alternatives = domain.get('alternatives')
        if domain.get('index') != index or not isinstance(alternatives, list) or not alternatives:
            raise ValueError('E domain index or alternatives invalid')
        if len({item.get('signature') for item in alternatives}) != len(alternatives):
            raise ValueError('E domain has duplicate native alternatives')
        for alternative in alternatives:
            if alternative.get('authorityKind') == 'RELOCATION_SOURCE':
                rule = alternative.get('executionRule')
                emission = alternative.get('executionEmission')
                if (rule is None) != (emission is None):
                    raise ValueError('E relocation source has partial execution proof')
                if emission is not None and not emission.startswith(alternative['state'] + '|'):
                    raise ValueError('E relocation source execution state differs from selected state')
        radices.append(len(alternatives))
    validated_factors = []
    for factor in factors:
        scope, truth = factor.get('scope'), factor.get('truth')
        if (not isinstance(scope, list) or any(type(i) is not int or i < 0 or i >= len(radices)
                                               for i in scope)):
            raise ValueError('E factor has opaque or malformed scope/truth table')
        cells = 1
        for index in scope:
            cells *= radices[index]
        if factor.get('cells') != str(cells):
            raise ValueError('E factor table cardinality/status invalid')
        validated_factors.append((scope, decode_factor_truth(truth, cells)))
    counts, accepted_ordinals = factor_relation(radices, validated_factors)
    raw = 1
    for radix in radices:
        raw *= radix
    ordinals = hashlib.sha256()
    for ordinal in accepted_ordinals:
        ordinals.update(f'{ordinal}\n'.encode('ascii'))
    if sum(counts.values()) != raw:
        raise ValueError('E factor partition does not cover raw assignments')
    compact = receipt['schema'] == 'closed-planning-physical-shard-compact-v1'
    if (receipt.get('raw') != str(raw) or
            any(receipt.get(key) != str(value) for key, value in counts.items()) or
            receipt.get('acceptedOrdinalsSha256') != ordinals.hexdigest() or
            (not compact and receipt.get('rowsSha256') != sha(rows_path))):
        raise ValueError('E factor coverage/ordinal/rows receipt mismatch')
    if compact:
        if (compact_result is None or compact_result['receipt'] != receipt or
                compact_result['proofCount'] != counts['accepted'] or
                compact_result['acceptedOrdinalsSha256'] != ordinals.hexdigest()):
            raise ValueError('E compact proof references differ from factor relation')
        with gzip.open(receipt['references'], 'rt', encoding='ascii') as stream:
            for expected, line in zip(accepted_ordinals, stream, strict=True):
                if line.split('\t', 1)[0] != str(expected):
                    raise ValueError('E compact proof ordinal differs from factor relation')
    else:
        with gzip.open(rows_path, 'rt', encoding='utf-8') as stream:
            row_count = 0
            for line in stream:
                row = json.loads(line)
                if row.get('schema') != 'physical-plan-v1':
                    raise ValueError('E output contains a non-physical row')
                row_count += 1
        if row_count != counts['accepted']:
            raise ValueError('E physical row count differs from accepted assignments')
    return {'status': 'INDEPENDENT_FACTOR_TABLE_VERIFIED', 'cell': model['cell'],
            'raw': str(raw), **{key: str(value) for key, value in counts.items()},
            'acceptedOrdinalsSha256': ordinals.hexdigest(),
            'scope': 'captured E factor truth tables and emitted assignments only'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--model-sha256', required=True)
    parser.add_argument('--rows', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.model, args.model_sha256, args.rows, args.receipt),
                     sort_keys=True))


if __name__ == '__main__':
    main()
