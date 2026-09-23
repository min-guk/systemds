import base64
import gzip
import hashlib
import json
from itertools import product
import random
import tempfile
import unittest
from pathlib import Path

from scripts.fedplanner.verify_e_factor_artifact import factor_relation, verify


class EFactorArtifactVerificationTest(unittest.TestCase):
    def test_packed_unknown_remains_unknown_in_independent_verification(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            model_path = root / 'model.json.gz'
            receipt_path = root / 'receipt.json'
            references = root / 'refs.tsv.gz'
            with gzip.open(references, 'wt') as stream:
                stream.write('0\t0\n')
            raw = bytes([0 | (1 << 2) | (2 << 4)])
            truth = {'schema': 'packed-factor-truth-v1',
                     'encoding': '2BIT_LSB_FIRST_BASE64', 'cells': '3',
                     'statusCodes': {'ALLOW': 0, 'REJECT': 1, 'UNKNOWN': 2},
                     'data': base64.b64encode(raw).decode('ascii'),
                     'packedSha256': hashlib.sha256(raw).hexdigest(),
                     'statusCounts': {'ALLOW': '1', 'REJECT': '1', 'UNKNOWN': '1'}}
            model = {
                'schema': 'closed-e-native-model-artifact-v1',
                'acceptance': 'MATERIALIZED_FACTOR_TABLES', 'cell': 'cell_packed',
                'programSha256': 'p' * 64, 'conditionSha256': 'c' * 64,
                'sourceFiles': {'program.dml': 'd' * 64},
                'domains': [{'index': 0, 'alternatives': [
                    {'signature': str(index)} for index in range(3)]}],
                'factors': [{'scope': [0], 'cells': '3', 'truth': truth}],
            }
            plain = json.dumps(model).encode()
            model_path.write_bytes(gzip.compress(plain))
            receipt = {'schema': 'closed-planning-physical-shard-compact-v1',
                       'source': 'E_C0', 'status': 'COMPLETE', 'cell': 'cell_packed',
                       'programSha256': 'p' * 64, 'conditionSha256': 'c' * 64,
                       'sourceFiles': {'program.dml': 'd' * 64}, 'raw': '3',
                       'accepted': '1', 'rejected': '1', 'unknown': '1',
                       'acceptedOrdinalsSha256': hashlib.sha256(b'0\n').hexdigest(),
                       'references': str(references)}
            receipt_path.write_text(json.dumps(receipt))
            compact = {'receipt': receipt, 'proofCount': 1,
                       'acceptedOrdinalsSha256': receipt['acceptedOrdinalsSha256']}
            result = verify(model_path, hashlib.sha256(plain).hexdigest(),
                            root / 'dictionary.jsonl.gz', receipt_path, compact)
            self.assertEqual('1', result['unknown'])

    def test_compact_proof_ordinals_must_match_factor_relation(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            model_path = root / 'model.json.gz'
            receipt_path = root / 'receipt.json'
            references = root / 'refs.tsv.gz'
            with gzip.open(references, 'wt') as stream:
                stream.write('0\t0\n')
            model = {
                'schema': 'closed-e-native-model-artifact-v1',
                'acceptance': 'MATERIALIZED_FACTOR_TABLES',
                'cell': 'cell_test', 'programSha256': 'p' * 64,
                'conditionSha256': 'c' * 64, 'sourceFiles': {'program.dml': 'd' * 64},
                'domains': [{'index': 0, 'alternatives': [
                    {'signature': 'left'}, {'signature': 'right'}]}],
                'factors': [{'scope': [0], 'cells': '2', 'truth': ['ALLOW', 'REJECT']}],
            }
            plain = json.dumps(model).encode()
            model_path.write_bytes(gzip.compress(plain))
            ordinal = hashlib.sha256(b'0\n').hexdigest()
            receipt = {'schema': 'closed-planning-physical-shard-compact-v1',
                       'source': 'E_C0', 'status': 'COMPLETE', 'cell': 'cell_test',
                       'programSha256': 'p' * 64, 'conditionSha256': 'c' * 64,
                       'sourceFiles': {'program.dml': 'd' * 64}, 'raw': '2',
                       'accepted': '1', 'rejected': '1', 'unknown': '0',
                       'acceptedOrdinalsSha256': ordinal,
                       'references': str(references)}
            receipt_path.write_text(json.dumps(receipt))
            compact = {'receipt': receipt, 'proofCount': 1,
                       'acceptedOrdinalsSha256': ordinal}
            digest = hashlib.sha256(plain).hexdigest()
            self.assertEqual('INDEPENDENT_FACTOR_TABLE_VERIFIED',
                             verify(model_path, digest, root / 'dictionary.jsonl.gz',
                                    receipt_path, compact)['status'])
            compact['acceptedOrdinalsSha256'] = hashlib.sha256(b'1\n').hexdigest()
            with self.assertRaisesRegex(ValueError, 'compact proof'):
                verify(model_path, digest, root / 'dictionary.jsonl.gz',
                       receipt_path, compact)

            compact['acceptedOrdinalsSha256'] = ordinal
            with gzip.open(references, 'wt') as stream:
                stream.write('1\t0\n')
            with self.assertRaisesRegex(ValueError, 'ordinal differs'):
                verify(model_path, digest, root / 'dictionary.jsonl.gz',
                       receipt_path, compact)

    def test_component_relation_matches_primitive_cartesian_truth(self):
        rng = random.Random(7324)
        statuses = ('ALLOW', 'REJECT', 'UNKNOWN')
        for _ in range(100):
            radices = [rng.choice((1, 2, 3)) for _ in range(5)]
            factors = []
            for _ in range(9):
                scope = rng.sample(range(5), rng.randrange(3))
                cells = 1
                for index in scope:
                    cells *= radices[index]
                factors.append((scope, [rng.choice(statuses) for _ in range(cells)]))
            expected = {'accepted': 0, 'rejected': 0, 'unknown': 0}
            expected_ordinals = []
            for ordinal, choices in enumerate(product(*(range(radix) for radix in radices))):
                truth = []
                for scope, table in factors:
                    offset = 0
                    for index in scope:
                        offset = offset * radices[index] + choices[index]
                    truth.append(table[offset])
                status = ('rejected' if 'REJECT' in truth else
                          'unknown' if 'UNKNOWN' in truth else 'accepted')
                expected[status] += 1
                if status == 'accepted':
                    expected_ordinals.append(ordinal)
            self.assertEqual((expected, expected_ordinals),
                             factor_relation(radices, factors))

    def test_saved_tables_are_recomputed_and_tampering_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            model_path = root / 'model.json.gz'
            rows_path = root / 'rows.jsonl.gz'
            receipt_path = root / 'receipt.json'
            model = {
                'schema': 'closed-e-native-model-artifact-v1',
                'acceptance': 'MATERIALIZED_FACTOR_TABLES',
                'cell': 'cell_test', 'programSha256': 'p' * 64,
                'conditionSha256': 'c' * 64, 'sourceFiles': {'program.dml': 'd' * 64},
                'domains': [
                    {'index': index, 'alternatives': [{'signature': 'left'}, {'signature': 'right'}]}
                    for index in range(2)],
                'factors': [{'scope': [0, 1], 'cells': '4',
                             'truth': ['ALLOW', 'REJECT', 'REJECT', 'ALLOW']}],
            }
            plain = json.dumps(model).encode()
            model_path.write_bytes(gzip.compress(plain))
            with gzip.open(rows_path, 'wt') as stream:
                stream.write('{"schema":"physical-plan-v1"}\n' * 2)
            receipt = {
                'schema': 'closed-planning-physical-shard-v1', 'source': 'E_C0',
                'status': 'COMPLETE', 'cell': 'cell_test', 'programSha256': 'p' * 64,
                'conditionSha256': 'c' * 64, 'sourceFiles': {'program.dml': 'd' * 64},
                'raw': '4', 'accepted': '2', 'rejected': '2', 'unknown': '0',
                'acceptedOrdinalsSha256': hashlib.sha256(b'0\n3\n').hexdigest(),
                'rowsSha256': hashlib.sha256(rows_path.read_bytes()).hexdigest(),
            }
            receipt_path.write_text(json.dumps(receipt))
            model_sha = hashlib.sha256(plain).hexdigest()
            self.assertEqual('INDEPENDENT_FACTOR_TABLE_VERIFIED',
                             verify(model_path, model_sha, rows_path, receipt_path)['status'])

            receipt['acceptedOrdinalsSha256'] = '0' * 64
            receipt_path.write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError, 'ordinal'):
                verify(model_path, model_sha, rows_path, receipt_path)

            receipt['acceptedOrdinalsSha256'] = hashlib.sha256(b'0\n3\n').hexdigest()
            receipt_path.write_text(json.dumps(receipt))
            model['factors'][0]['truth'][1] = 'ALLOW'
            tampered = json.dumps(model).encode()
            model_path.write_bytes(gzip.compress(tampered))
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                verify(model_path, model_sha, rows_path, receipt_path)

            model['factors'][0]['truth'][1] = 'REJECT'
            model['domains'][0]['alternatives'][0].update({
                'authorityKind': 'RELOCATION_SOURCE', 'state': 'FED/FOUT/ROW/SHAPE_INDEPENDENT',
                'executionRule': 'rule',
                'executionEmission': 'FED/LOUT/ROW/SHAPE_INDEPENDENT|derivedFedFout=false',
            })
            malformed = json.dumps(model).encode()
            model_path.write_bytes(gzip.compress(malformed))
            with self.assertRaisesRegex(ValueError, 'execution state'):
                verify(model_path, hashlib.sha256(malformed).hexdigest(), rows_path, receipt_path)


if __name__ == '__main__':
    unittest.main()
