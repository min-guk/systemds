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

from scripts.fedplanner.exact_e_factor_count import certify, count_relation


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


class ExactEFactorCountTest(unittest.TestCase):
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
            listed = certify(model_path)
            model['factors'][0]['truth'] = packed_truth(statuses)
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            packed = certify(model_path)
            self.assertEqual(tuple(listed[key] for key in
                                   ('raw', 'accepted', 'rejected', 'unknown')),
                             tuple(packed[key] for key in
                                   ('raw', 'accepted', 'rejected', 'unknown')))

            def rejected(mutator, message):
                damaged = copy.deepcopy(model)
                mutator(damaged['factors'][0]['truth'])
                model_path.write_bytes(gzip.compress(json.dumps(damaged).encode()))
                with self.assertRaisesRegex(ValueError, message):
                    certify(model_path)

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
                certify(model_path)

            def padding(truth):
                data = bytearray(base64.b64decode(truth['data']))
                data[-1] |= 1 << 2
                truth['data'] = base64.b64encode(data).decode('ascii')
                truth['packedSha256'] = hashlib.sha256(data).hexdigest()
            padding_model = copy.deepcopy(model)
            padding(padding_model['factors'][0]['truth'])
            model_path.write_bytes(gzip.compress(json.dumps(padding_model).encode()))
            with self.assertRaisesRegex(ValueError, 'padding'):
                certify(model_path)

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
            result = certify(model_path)
            self.assertEqual(('4', '2', '1', '1'), tuple(result[key] for key in
                             ('raw', 'accepted', 'rejected', 'unknown')))
            model['factors'][0]['truth'][3] = 'REJECT'
            model_path.write_bytes(gzip.compress(json.dumps(model).encode()))
            changed = certify(model_path)
            self.assertNotEqual(result['modelSha256'], changed['modelSha256'])
            self.assertEqual('1', changed['accepted'])

    def test_bag_budget_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'bag exceeds'):
            count_relation([3, 3], [((0, 1), ('REJECT',) + ('ALLOW',) * 8)],
                           frozenset(('ALLOW',)), max_bag_cells=8)


if __name__ == '__main__':
    unittest.main()
