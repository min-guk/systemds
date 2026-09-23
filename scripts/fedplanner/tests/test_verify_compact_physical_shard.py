import gzip
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_compact_physical_shard import sha, verify


CALIBRATION_ROOT = Path('/home/mchoi/cofee-evaluation')


def plan(execution='CP'):
    region = {'functionNamespace': 'main', 'regionPath': ['main', '0'],
              'callSitePath': 'main', 'recompileContext': 'base'}
    occurrence = {'sourceOrigin': '0', 'functionNamespace': 'main',
                  'callSitePath': 'main', 'recompileContext': 'base',
                  'emittedInstance': '0', 'controlRegion': region}
    authority = {'kind': 'LOCAL', 'owner': occurrence}
    return {'schema': 'physical-plan-v1', 'context': {'logical': 'fixture'},
            'nodes': [{'occurrence': occurrence, 'opcode': 'literal',
                       'exec': execution, 'output': 'LOUT', 'ftype': 'NONE',
                       'valueVersion': {'lexicalVariable': 'x', 'definitionOrdinal': 0,
                                        'versionKind': 'ROOT', 'definingControlRegion': region,
                                        'predecessorVersions': []},
                       'authorityRef': authority}],
            'authority': [{'id': authority, 'source': 'literal', 'owner': occurrence,
                           'kind': 'LOCAL'}],
            'actions': [], 'bindings': [], 'logicalInputs': [], 'geometry': []}


class CompactShardTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.dictionary = root / 'dictionary.jsonl.gz'
        self.references = root / 'refs.jsonl.gz'
        self.receipt = root / 'receipt.json'
        self.write([plan(), plan('FED')], b'1\t0\n3\t1\n5\t0\n')

    def write(self, rows, refs):
        with gzip.open(self.dictionary, 'wt') as stream:
            for row in rows:
                stream.write(json.dumps(row) + '\n')
        with gzip.open(self.references, 'wb') as stream:
            stream.write(refs)
        self.receipt.write_text(json.dumps({
            'schema': 'closed-planning-physical-shard-compact-v1',
            'source': 'P_C0', 'status': 'COMPLETE', 'raw': '7',
            'accepted': str(len(refs.splitlines())),
            'rejected': str(7-len(refs.splitlines())), 'unknown': '0',
            'rows': str(self.dictionary), 'references': str(self.references),
            'dictionarySha256': sha(self.dictionary),
            'referencesSha256': sha(self.references),
            'dictionaryCount': len(rows), 'emittedRows': len(refs.splitlines())}))

    def test_proof_multiplicity_and_exact_physical_set(self):
        result = verify(self.receipt, CALIBRATION_ROOT)
        self.assertEqual((3, 2, 2), (result['proofCount'], result['dictionaryCount'],
                                    result['physicalCount']))
        self.assertEqual(3, result['wire']['proofCount'])

    def test_missing_reference_and_dictionary_mutations_fail(self):
        self.write([plan(), plan('FED')], b'1\t0\n3\t0\n5\t0\n')
        with self.assertRaisesRegex(ValueError, 'proof coverage'):
            verify(self.receipt, CALIBRATION_ROOT)
        self.write([plan(), plan('FED')], b'1\t0\n3\t2\n5\t0\n')
        with self.assertRaisesRegex(ValueError, 'ordinal/plan reference'):
            verify(self.receipt, CALIBRATION_ROOT)
        self.write([plan(), plan('FED')], b'3\t0\n3\t1\n5\t0\n')
        with self.assertRaisesRegex(ValueError, 'ordinal/plan reference'):
            verify(self.receipt, CALIBRATION_ROOT)


if __name__ == '__main__':
    unittest.main()
