import gzip
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.fedplanner.verify_e_factor_artifact import verify


class EFactorArtifactVerificationTest(unittest.TestCase):
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
