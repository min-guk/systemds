#!/usr/bin/env python3
"""Exact integer-semiring count of a saved E hard-factor relation.

This certifies ACCEPT/REJECT/UNKNOWN cardinalities of the captured factor
tables. It does not project physical plans or certify planner completeness.
"""

import argparse
import base64
from collections import Counter
import gzip
import hashlib
from itertools import product
import json
from pathlib import Path
import tempfile


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


PACKED_TRUTH_FIELDS = frozenset((
    'schema', 'encoding', 'cells', 'statusCodes', 'data',
    'packedSha256', 'statusCounts'))
STATUS_NAMES = ('ALLOW', 'REJECT', 'UNKNOWN')


def _packed_byte_counts(value):
    counts = [0, 0, 0]
    for shift in range(0, 8, 2):
        code = (value >> shift) & 3
        if code == 3:
            return None
        counts[code] += 1
    return tuple(counts)


PACKED_BYTE_COUNTS = tuple(_packed_byte_counts(value) for value in range(256))


class PackedTruth:
    """Validated, lazy 2-bit factor truth table backed only by packed bytes."""

    __slots__ = ('_data', '_cells', '_wire')

    def __init__(self, data, cells, wire):
        self._data = data
        self._cells = cells
        self._wire = wire

    def __len__(self):
        return self._cells

    def __getitem__(self, index):
        if type(index) is not int or index < 0 or index >= self._cells:
            raise IndexError(index)
        code = (self._data[index >> 2] >> ((index & 3) * 2)) & 3
        if code == 3:
            raise ValueError('packed E factor contains reserved status code')
        return STATUS_NAMES[code]

    def wire(self):
        return self._wire


def decode_factor_truth(value, cells):
    """Validate a legacy list or packed-v1 truth table without expanding packed data."""
    if isinstance(value, list):
        if (len(value) != cells or
                any(status not in STATUS_NAMES for status in value)):
            raise ValueError('E factor table cardinality/status invalid')
        return tuple(value)
    if not isinstance(value, dict) or set(value) != PACKED_TRUTH_FIELDS:
        raise ValueError('E factor scope/truth invalid')
    if (value.get('schema') != 'packed-factor-truth-v1' or
            value.get('encoding') != '2BIT_LSB_FIRST_BASE64' or
            value.get('cells') != str(cells) or
            value.get('statusCodes') != {'ALLOW': 0, 'REJECT': 1, 'UNKNOWN': 2}):
        raise ValueError('packed E factor contract invalid')
    encoded = value.get('data')
    if not isinstance(encoded, str):
        raise ValueError('packed E factor data is not base64 text')
    try:
        data = base64.b64decode(encoded, validate=True)
    except Exception as error:
        raise ValueError('packed E factor base64 invalid') from error
    if base64.b64encode(data).decode('ascii') != encoded:
        raise ValueError('packed E factor base64 is not canonical')
    if len(data) != (cells + 3) // 4:
        raise ValueError('packed E factor byte cardinality invalid')
    if hashlib.sha256(data).hexdigest() != value.get('packedSha256'):
        raise ValueError('packed E factor SHA-256 mismatch')
    counts = [0, 0, 0]
    full_bytes, remainder = divmod(cells, 4)
    for byte_value, frequency in Counter(memoryview(data)[:full_bytes]).items():
        summary = PACKED_BYTE_COUNTS[byte_value]
        if summary is None:
            raise ValueError('packed E factor contains reserved status code')
        for code in range(3):
            counts[code] += frequency * summary[code]
    if remainder:
        byte_value = data[full_bytes]
        for index in range(remainder):
            code = (byte_value >> (index * 2)) & 3
            if code == 3:
                raise ValueError('packed E factor contains reserved status code')
            counts[code] += 1
    used = remainder * 2
    if used and data[-1] >> used:
        raise ValueError('packed E factor has nonzero padding bits')
    expected_counts = {name: str(counts[index])
                       for index, name in enumerate(STATUS_NAMES)}
    if value.get('statusCounts') != expected_counts:
        raise ValueError('packed E factor status counts differ')
    return PackedTruth(data, cells, dict(value))


def factor_truth_wire(truth):
    return truth.wire() if isinstance(truth, PackedTruth) else list(truth)


def read_model(path):
    plain = gzip.decompress(Path(path).read_bytes())
    model = json.loads(plain)
    if (model.get('schema') != 'closed-e-native-model-artifact-v1' or
            model.get('acceptance') != 'MATERIALIZED_FACTOR_TABLES'):
        raise ValueError('E model has no complete materialized hard factors')
    domains = model.get('domains')
    factors = model.get('factors')
    if not isinstance(domains, list) or not domains or not isinstance(factors, list):
        raise ValueError('E model lacks finite domains/factors')
    radices = []
    for index, row in enumerate(domains):
        choices = row.get('alternatives')
        if (row.get('index') != index or not isinstance(choices, list) or not choices or
                len({choice.get('signature') for choice in choices}) != len(choices)):
            raise ValueError('E domain index or alternatives invalid')
        radices.append(len(choices))
    tables = []
    for row in factors:
        scope = row.get('scope')
        truth = row.get('truth')
        if (not isinstance(scope, list) or len(scope) != len(set(scope)) or
                any(type(index) is not int or index < 0 or index >= len(radices)
                    for index in scope)):
            raise ValueError('E factor scope/truth invalid')
        cells = 1
        for index in scope:
            cells *= radices[index]
        if row.get('cells') != str(cells):
            raise ValueError('E factor table cardinality/status invalid')
        tables.append((tuple(scope), decode_factor_truth(truth, cells)))
    return model, hashlib.sha256(plain).hexdigest(), radices, tables


def index_of(values, radices):
    offset = 0
    for value, radix in zip(values, radices):
        offset = offset * radix + value
    return offset


def count_relation(radices, tables, allowed, max_bag_cells=1_000_000):
    """Eliminate native variables exactly; return count and replayable trace."""
    factors = []
    for scope, truth in tables:
        varying = tuple(index for index in scope if radices[index] > 1)
        data = []
        for assignment in product(*(range(radices[index]) for index in varying)):
            chosen = dict(zip(varying, assignment))
            offset = index_of((chosen.get(index, 0) for index in scope),
                              (radices[index] for index in scope))
            data.append(int(truth[offset] in allowed))
        if not all(data):
            factors.append((varying, tuple(data)))
    variables = set(range(len(radices)))
    trace = []
    peak = 1
    while variables:
        # The score uses the current primal graph and is independent of factor
        # insertion order. Singleton variables have already been substituted.
        neighbors = {var: set() for var in variables}
        for scope, _ in factors:
            for var in scope:
                neighbors[var].update(other for other in scope if other != var)
        def score(var):
            local = sorted(neighbors[var])
            fill = sum(right not in neighbors[left]
                       for i, left in enumerate(local) for right in local[i + 1:])
            cells = radices[var]
            for neighbor in local:
                cells *= radices[neighbor]
            return fill, cells, var
        variable = min(variables, key=score)
        selected = [(scope, data) for scope, data in factors if variable in scope]
        factors = [(scope, data) for scope, data in factors if variable not in scope]
        bag = tuple(sorted({index for scope, _ in selected for index in scope} | {variable}))
        bag_cells = 1
        for index in bag:
            bag_cells *= radices[index]
        if bag_cells > max_bag_cells:
            raise ValueError('E factor elimination bag exceeds exact cell limit')
        peak = max(peak, bag_cells)
        output_scope = tuple(index for index in bag if index != variable)
        output = [0] * (bag_cells // radices[variable])
        for assignment in product(*(range(radices[index]) for index in bag)):
            chosen = dict(zip(bag, assignment))
            term = 1
            for scope, data in selected:
                offset = index_of((chosen[index] for index in scope),
                                  (radices[index] for index in scope))
                term *= data[offset]
                if not term:
                    break
            target = index_of((chosen[index] for index in output_scope),
                              (radices[index] for index in output_scope))
            output[target] += term
        factors.append((output_scope, tuple(output)))
        trace.append({'eliminate': variable, 'bagCells': bag_cells,
                      'outputScope': list(output_scope), 'outputSha256': digest(output)})
        variables.remove(variable)
    if any(scope for scope, _ in factors):
        raise ValueError('E elimination left unbound variables')
    count = 1
    for _, data in factors:
        count *= data[0]
    return count, peak, trace


def certify(model_path, max_bag_cells=1_000_000):
    model, model_sha, radices, tables = read_model(model_path)
    accepted, accept_peak, accept_trace = count_relation(
        radices, tables, frozenset(('ALLOW',)), max_bag_cells)
    nonrejected, nonreject_peak, nonreject_trace = count_relation(
        radices, tables, frozenset(('ALLOW', 'UNKNOWN')), max_bag_cells)
    raw = 1
    for radix in radices:
        raw *= radix
    if not 0 <= accepted <= nonrejected <= raw:
        raise ValueError('E factor count partition invalid')
    return {'schema': 'exact-e-factor-count-v1', 'status': 'COMPLETE',
            'claimScope': 'CAPTURED_E_HARD_FACTOR_CARDINALITY_ONLY',
            'cell': model['cell'], 'modelSha256': model_sha,
            'raw': str(raw), 'accepted': str(accepted),
            'rejected': str(raw - nonrejected),
            'unknown': str(nonrejected - accepted),
            'domainCount': len(radices), 'factorCount': len(tables),
            'maxBagCells': max(accept_peak, nonreject_peak),
            'acceptTrace': accept_trace, 'nonrejectTrace': nonreject_trace,
            'runtimeSemanticCoverage': 'NOT_ASSESSED_BY_THIS_CONTRACT'}


def publish(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', dir=path.parent, delete=False) as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write('\n')
        temporary = Path(stream.name)
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('run', 'verify'))
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--max-bag-cells', type=int, default=1_000_000)
    args = parser.parse_args()
    if args.max_bag_cells < 1:
        parser.error('max-bag-cells must be positive')
    result = certify(args.model, args.max_bag_cells)
    if args.mode == 'run':
        publish(args.artifact, result)
    elif json.loads(args.artifact.read_text()) != result:
        raise ValueError('saved E factor count differs from independent recomputation')
    print(json.dumps({key: result[key] for key in
                      ('cell', 'status', 'raw', 'accepted', 'rejected', 'unknown', 'maxBagCells')},
                     sort_keys=True))


if __name__ == '__main__':
    main()
