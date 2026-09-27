import base64
import copy
import gzip
import hashlib
from itertools import product
import json
from pathlib import Path
import random
import tempfile
import unittest
from unittest.mock import patch

from scripts.fedplanner.exact_e_factor_count import (certify, count_relation, read_model,
                                                     validate_factor_aggregation)


def packed_truth(statuses):
    codes = {'ALLOW': 0, 'REJECT': 1, 'UNKNOWN': 2}
    data = bytearray((len(statuses) + 3) // 4)
    for index, status in enumerate(statuses):
        data[index >> 2] |= codes[status] << ((index & 3) * 2)
    raw = bytes(data)
    return {'schema': 'packed-factor-truth-v1',
            'encoding': '2BIT_LSB_FIRST_BASE64', 'cells': str(len(statuses)),
            'statusCodes': codes, 'data': base64.b64encode(raw).decode('ascii'),
            'packedSha256': hashlib.sha256(raw).hexdigest(),
            'statusCounts': {status: str(statuses.count(status)) for status in codes}}


def v2_model():
    return {
        'schema': 'closed-e-native-model-artifact-v2',
        'acceptance': 'MATERIALIZED_FACTOR_TABLES', 'cell': 'aggregated',
        'domains': [
            {'index': 0, 'alternatives': [{'signature': 'a'}, {'signature': 'b'}]},
            {'index': 1, 'alternatives': [
                {'signature': 'x'}, {'signature': 'y'}, {'signature': 'z'}]}],
        'factorAggregation': 'ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1',
        'nativeFactorCount': 3, 'materializedFactorCount': 2,
        'sourceFactorScopes': [[0], [1, 0], [0]],
        'nativeFactorCells': '10', 'materializedFactorCells': '8',
        'factors': [
            {'sourceFactorIndices': [0, 2], 'scope': [0], 'cells': '2',
             'truth': ['ALLOW', 'REJECT']},
            {'sourceFactorIndices': [1], 'scope': [1, 0], 'cells': '6',
             'truth': ['ALLOW'] * 6}],
    }


class ExactEFactorCountTest(unittest.TestCase):
    def test_v2_aggregation_and_v1_legacy_are_accepted(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'model.json.gz'
            model = v2_model()
            path.write_bytes(gzip.compress(json.dumps(model).encode()))
            loaded, _, radices, _ = read_model(path)
            self.assertEqual(3, validate_factor_aggregation(
                loaded, radices)['nativeFactorCount'])
            current = certify(path)
            self.assertEqual('closed-e-native-model-artifact-v2',
                             current['modelSchema'])
            self.assertEqual('ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1',
                             current['factorAggregation'])
            legacy = copy.deepcopy(model)
            legacy['schema'] = 'closed-e-native-model-artifact-v1'
            for key in ('factorAggregation', 'nativeFactorCount',
                        'materializedFactorCount', 'sourceFactorScopes',
                        'nativeFactorCells', 'materializedFactorCells'):
                legacy.pop(key)
            for factor in legacy['factors']:
                factor.pop('sourceFactorIndices')
            path.write_bytes(gzip.compress(json.dumps(legacy).encode()))
            self.assertEqual(2, len(read_model(path, allow_legacy_v1=True)[3]))
            with self.assertRaisesRegex(ValueError, 'explicit legacy mode'):
                read_model(path)
            with self.assertRaisesRegex(ValueError, 'explicit legacy mode'):
                certify(path)
            legacy['nativeFactorCount'] = 2
            path.write_bytes(gzip.compress(json.dumps(legacy).encode()))
            with self.assertRaisesRegex(ValueError, 'hybrid'):
                read_model(path, allow_legacy_v1=True)

    def test_v2_zero_arity_factor_has_one_cell(self):
        model = v2_model()
        model.update(nativeFactorCount=1, materializedFactorCount=1,
                     sourceFactorScopes=[[]], nativeFactorCells='1',
                     materializedFactorCells='1')
        model['factors'] = [{'sourceFactorIndices': [0], 'scope': [], 'cells': '1',
                             'truth': ['ALLOW']}]
        self.assertEqual('1', validate_factor_aggregation(
            model, [2, 3])['materializedFactorCells'])

    def test_v2_aggregation_hostile_mutations_fail_closed(self):
        mutations = {
            'gap': lambda model: model['factors'][0].update(sourceFactorIndices=[0]),
            'duplicate': lambda model: model['factors'][1].update(sourceFactorIndices=[1, 2]),
            'reorder': lambda model: model['factors'].reverse(),
            'source scope': lambda model: model['sourceFactorScopes'].__setitem__(1, [0, 1]),
            'group scope': lambda model: model['factors'][1].update(scope=[0, 1]),
            'native count': lambda model: model.update(nativeFactorCount=4),
            'materialized count': lambda model: model.update(materializedFactorCount=3),
            'native cells': lambda model: model.update(nativeFactorCells='9'),
            'materialized cells': lambda model: model.update(materializedFactorCells='7'),
            'aggregation': lambda model: model.update(factorAggregation='FORGED'),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                model = v2_model()
                mutate(model)
                with self.assertRaises(ValueError):
                    validate_factor_aggregation(model, [2, 3])

    def test_model_gzip_byte_limits_fail_before_json_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'model.json.gz'
            model = v2_model()
            model['padding'] = 'x' * 4096
            path.write_bytes(gzip.compress(json.dumps(model).encode()))
            with self.assertRaisesRegex(ValueError, 'decoded-byte budget'):
                read_model(path, max_decoded_bytes=128)
            with self.assertRaisesRegex(ValueError, 'compressed-byte budget'):
                read_model(path, max_compressed_bytes=8)
            with patch('scripts.fedplanner.exact_e_factor_count.tempfile.SpooledTemporaryFile',
                       wraps=tempfile.SpooledTemporaryFile) as spool:
                read_model(path)
                self.assertEqual(path.parent, spool.call_args.kwargs['dir'])

    def test_packed_truth_matches_list_and_mutations_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            model_path = Path(folder) / 'model.json.gz'
            statuses = ['ALLOW', 'REJECT', 'UNKNOWN', 'ALLOW', 'REJECT']
            model = {'schema': 'closed-e-native-model-artifact-v1',
                     'acceptance': 'MATERIALIZED_FACTOR_TABLES', 'cell': 'packed',
                     'domains': [{'index': 0, 'alternatives': [{'signature': str(k)}
                                 for k in range(5)]}],
                     'factors': [{'scope': [0], 'cells': '5', 'truth': statuses}]}
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            listed = certify(model_path, allow_legacy_v1=True)
            model['factors'][0]['truth'] = packed_truth(statuses)
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            packed = certify(model_path, allow_legacy_v1=True)
            self.assertEqual(tuple(listed[key] for key in
                                   ('raw', 'accepted', 'rejected', 'unknown')),
                             tuple(packed[key] for key in
                                   ('raw', 'accepted', 'rejected', 'unknown')))

            def rejected(mutator, message):
                damaged = copy.deepcopy(model)
                mutator(damaged['factors'][0]['truth'])
                model_path.write_bytes(gzip.compress(json.dumps(damaged).encode()))
                with self.assertRaisesRegex(ValueError, message):
                    certify(model_path, allow_legacy_v1=True)

            rejected(lambda truth: truth.update(data='!'), 'base64')
            rejected(lambda truth: truth.update(packedSha256='0' * 64), 'SHA-256')
            rejected(lambda truth: truth['statusCounts'].update(UNKNOWN='0'), 'counts')

            def reserved(truth):
                data = bytearray(base64.b64decode(truth['data']))
                data[0] = (data[0] & ~3) | 3
                truth['data'] = base64.b64encode(data).decode('ascii')
                truth['packedSha256'] = hashlib.sha256(data).hexdigest()
            reserved_model = copy.deepcopy(model)
            reserved(reserved_model['factors'][0]['truth'])
            model_path.write_bytes(gzip.compress(json.dumps(reserved_model).encode()))
            with self.assertRaisesRegex(ValueError, 'reserved'):
                certify(model_path, allow_legacy_v1=True)

            def padding(truth):
                data = bytearray(base64.b64decode(truth['data']))
                data[-1] |= 1 << 2
                truth['data'] = base64.b64encode(data).decode('ascii')
                truth['packedSha256'] = hashlib.sha256(data).hexdigest()
            padding_model = copy.deepcopy(model)
            padding(padding_model['factors'][0]['truth'])
            model_path.write_bytes(gzip.compress(json.dumps(padding_model).encode()))
            with self.assertRaisesRegex(ValueError, 'padding'):
                certify(model_path, allow_legacy_v1=True)

    def test_elimination_matches_primitive_cartesian_enumeration(self):
        rng = random.Random(93751)
        statuses = ('ALLOW', 'REJECT', 'UNKNOWN')
        for _ in range(100):
            radices = [rng.choice((1, 2, 3)) for _ in range(6)]
            tables = []
            for _ in range(8):
                scope = tuple(rng.sample(range(6), rng.randrange(4)))
                size = 1
                for index in scope:
                    size *= radices[index]
                tables.append((scope, tuple(rng.choice(statuses) for _ in range(size))))
            brute = {'accepted': 0, 'nonrejected': 0}
            for values in product(*(range(radix) for radix in radices)):
                row = []
                for scope, table in tables:
                    offset = 0
                    for index in scope:
                        offset = offset * radices[index] + values[index]
                    row.append(table[offset])
                brute['accepted'] += int(all(value == 'ALLOW' for value in row))
                brute['nonrejected'] += int(all(value != 'REJECT' for value in row))
            accepted, _, _ = count_relation(radices, tables, frozenset(('ALLOW',)))
            nonrejected, _, _ = count_relation(
                radices, tables, frozenset(('ALLOW', 'UNKNOWN')))
            self.assertEqual((brute['accepted'], brute['nonrejected']),
                             (accepted, nonrejected))

    def test_offline_artifact_replay_and_mutated_factor(self):
        with tempfile.TemporaryDirectory() as folder:
            model_path = Path(folder) / 'model.json.gz'
            model = {'schema': 'closed-e-native-model-artifact-v1',
                     'acceptance': 'MATERIALIZED_FACTOR_TABLES', 'cell': 'example',
                     'domains': [{'index': i, 'alternatives': [{'signature': str(k)}
                                 for k in range(2)]} for i in range(2)],
                     'factors': [{'scope': [0, 1], 'cells': '4',
                                  'truth': ['ALLOW', 'REJECT', 'UNKNOWN', 'ALLOW']}]}
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            result = certify(model_path, allow_legacy_v1=True)
            self.assertEqual(('4', '2', '1', '1'), tuple(result[key] for key in
                             ('raw', 'accepted', 'rejected', 'unknown')))
            model['factors'][0]['truth'][3] = 'REJECT'
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            changed = certify(model_path, allow_legacy_v1=True)
            self.assertNotEqual(result['modelSha256'], changed['modelSha256'])
            self.assertEqual('1', changed['accepted'])

    def test_bag_budget_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'bag exceeds'):
            count_relation([3, 3], [((0, 1), ('REJECT',) + ('ALLOW',) * 8)],
                           frozenset(('ALLOW',)), max_bag_cells=8)


if __name__ == '__main__':
    unittest.main()
