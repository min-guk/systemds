#!/usr/bin/env python3
"""Recompute finite E-native factor coverage from a saved model, without Java.

This checks the captured factor tables and emitted assignment receipt. It does not
establish that the E model contains every semantically feasible physical plan.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify(model_path, expected_model_sha, rows_path, receipt_path):
    plain = gzip.decompress(Path(model_path).read_bytes())
    if hashlib.sha256(plain).hexdigest() != expected_model_sha:
        raise ValueError('E model artifact SHA-256 mismatch')
    model = json.loads(plain)
    receipt = json.loads(Path(receipt_path).read_text())
    if (model.get('schema') != 'closed-e-native-model-artifact-v1' or
            model.get('acceptance') != 'MATERIALIZED_FACTOR_TABLES' or
            receipt.get('schema') != 'closed-planning-physical-shard-v1' or
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
    suffix = [1] * (len(radices) + 1)
    for index in range(len(radices) - 1, -1, -1):
        suffix[index] = suffix[index + 1] * radices[index]
    ready = [[] for _ in suffix]
    for factor in factors:
        scope, truth = factor.get('scope'), factor.get('truth')
        if (not isinstance(scope, list) or any(type(i) is not int or i < 0 or i >= len(radices)
                                               for i in scope) or not isinstance(truth, list)):
            raise ValueError('E factor has opaque or malformed scope/truth table')
        cells = 1
        for index in scope:
            cells *= radices[index]
        if (len(truth) != cells or factor.get('cells') != str(cells) or
                any(value not in ('ALLOW', 'REJECT', 'UNKNOWN') for value in truth)):
            raise ValueError('E factor table cardinality/status invalid')
        ready[max(scope, default=-1) + 1].append((scope, truth))
    values = [0] * len(radices)
    counts = {'accepted': 0, 'rejected': 0, 'unknown': 0}
    ordinals = hashlib.sha256()

    def walk(position, ordinal, unresolved=False):
        for scope, truth in ready[position]:
            offset = 0
            for index in scope:
                offset = offset * radices[index] + values[index]
            status = truth[offset]
            if status == 'REJECT':
                counts['rejected'] += suffix[position]
                return
            unresolved |= status == 'UNKNOWN'
        if position == len(radices):
            counts['unknown' if unresolved else 'accepted'] += 1
            if not unresolved:
                ordinals.update(f'{ordinal}\n'.encode('ascii'))
            return
        for value in range(radices[position]):
            values[position] = value
            walk(position + 1, ordinal * radices[position] + value, unresolved)

    walk(0, 0)
    if sum(counts.values()) != suffix[0]:
        raise ValueError('E factor partition does not cover raw assignments')
    if (receipt.get('raw') != str(suffix[0]) or
            any(receipt.get(key) != str(value) for key, value in counts.items()) or
            receipt.get('acceptedOrdinalsSha256') != ordinals.hexdigest() or
            receipt.get('rowsSha256') != sha(rows_path)):
        raise ValueError('E factor coverage/ordinal/rows receipt mismatch')
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
            'raw': str(suffix[0]), **{key: str(value) for key, value in counts.items()},
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
