import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'build_closed_comparison_cases.py'
spec = importlib.util.spec_from_file_location('build_closed_comparison_cases', SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ClosedComparisonCasesTest(unittest.TestCase):
    def test_complete_discovered_denominator_is_retained(self):
        result = mod.build()
        self.assertEqual(447, len(result['cells']))
        self.assertEqual(283, sum(row['inventoryStatus'] == 'IN_SCOPE' for row in result['cells']))
        self.assertEqual(148, sum(row['inventoryStatus'] == 'UNSUPPORTED' for row in result['cells']))
        self.assertEqual(16, sum(row['inventoryStatus'] == 'HISTORICAL' for row in result['cells']))
        for row in result['cells']:
            if row['inventoryStatus'] == 'IN_SCOPE':
                self.assertEqual(7, len(row['expectedPairs']))
                self.assertEqual('REQUIRED', row['expectedPairs'][0]['applicability'])
                self.assertEqual(6, sum(pair['applicability'] == 'UNDETERMINED'
                                        for pair in row['expectedPairs']))
        self.assertEqual('INCOMPLETE', result['status'])

    def test_historical_decision_requires_pinned_evidence_and_exact_pair(self):
        source = mod.SOURCE
        original = mod.build(source, Path('/nonexistent/closed-decisions.json'))
        cell = next(row for row in original['cells'] if row['inventoryStatus'] == 'IN_SCOPE')
        pair = cell['expectedPairs'][1]
        decision = {'cellId': cell['id'], 'left': pair['left'], 'right': pair['right'],
                    'role': pair['role'], 'applicability': 'NATIVE_UNSUPPORTED',
                    'evidence': 'pinned compiler source and diagnostic'}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'decisions.json'
            diagnostic = Path(temp) / 'native-diagnostic.json'
            diagnostic.write_text('{"reason":"unsupported operation"}\n')
            decision['evidenceFiles'] = {str(diagnostic): mod.sha(diagnostic)}
            ledger = {'schema': 'closed-comparison-applicability-v1',
                      'discoveryBacklogSha256': mod.sha(source), 'decisions': [decision]}
            path.write_text(json.dumps(ledger))
            result = mod.build(source, path)
            selected = next(row for row in result['cells'] if row['id'] == cell['id'])
            self.assertEqual('NATIVE_UNSUPPORTED', selected['expectedPairs'][1]['applicability'])
            self.assertEqual(mod.sha(path), result['applicabilityLedgerSha256'])
            ledger['decisions'].append(dict(decision))
            path.write_text(json.dumps(ledger))
            with self.assertRaisesRegex(ValueError, 'duplicate applicability'):
                mod.build(source, path)
            ledger['decisions'] = [dict(decision, evidence='')]
            path.write_text(json.dumps(ledger))
            with self.assertRaisesRegex(ValueError, 'lacks evidence'):
                mod.build(source, path)


if __name__ == '__main__':
    unittest.main()
