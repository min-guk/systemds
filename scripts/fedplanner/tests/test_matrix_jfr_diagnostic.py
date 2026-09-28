"""Pure tests for the compile-only matrix JFR diagnostic contract."""

import csv
import importlib.util
import io
import json
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import unittest
from unittest import mock


RUNNER = Path(__file__).resolve().parents[1] / 'run_matrix_campaign.py'
SPEC = importlib.util.spec_from_file_location('matrix_jfr_runner', RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAMPAIGN)


class DiagnosticCliContractTest(unittest.TestCase):
    def test_requires_compile_phase_and_exactly_one_cell(self):
        invalid = (
            ('--phase', 'runtime', '--max-cells', '1'),
            ('--phase', 'all', '--max-cells', '1'),
            ('--phase', 'prepare', '--max-cells', '1'),
            ('--phase', 'compile'),
            ('--phase', 'compile', '--max-cells', '2'),
        )
        for options in invalid:
            with self.subTest(options=options), \
                    mock.patch.object(CAMPAIGN, 'dependencies') as dependencies, \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                CAMPAIGN.main(['--root', '/tmp/not-used', '--diagnostic-jfr', *options])
            dependencies.assert_not_called()

    def test_compact_ablation_requires_the_single_cell_jfr_diagnostic(self):
        invalid = (
            ('--phase', 'compile', '--max-cells', '1'),
            ('--diagnostic-jfr', '--phase', 'runtime', '--max-cells', '1'),
            ('--diagnostic-jfr', '--phase', 'compile'),
            ('--diagnostic-jfr', '--phase', 'compile', '--max-cells', '2'),
        )
        for options in invalid:
            with self.subTest(options=options), \
                    mock.patch.object(CAMPAIGN, 'dependencies') as dependencies, \
                    redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                CAMPAIGN.main(['--root', '/tmp/not-used', '--diagnostic-compact', *options])
            dependencies.assert_not_called()

        with mock.patch.object(CAMPAIGN, 'dependencies',
                side_effect=RuntimeError('VALID_COMPACT_REACHED_DEPENDENCIES')):
            with self.assertRaisesRegex(RuntimeError, 'VALID_COMPACT_REACHED_DEPENDENCIES'):
                CAMPAIGN.main(['--root', '/tmp/not-used', '--diagnostic-compact',
                    '--diagnostic-jfr', '--phase', 'compile', '--max-cells', '1'])

    def test_flags_apply_only_to_coordinator_probe_java(self):
        cell = CAMPAIGN.matrix()[0]
        ordinary = CAMPAIGN.coordinator_java(cell, 'compile', False)
        diagnostic = CAMPAIGN.coordinator_java(cell, 'compile', True)
        for option in CAMPAIGN.JFR_OPTIONS:
            self.assertNotIn(option, CAMPAIGN.JAVA)
            self.assertNotIn(option, ordinary)
            self.assertIn(option, diagnostic)
        contract = CAMPAIGN.diagnostic_contract(True)
        self.assertTrue(contract['diagnostic_jfr'])
        self.assertEqual(list(CAMPAIGN.JFR_OPTIONS), contract['diagnostic_jfr_options'])

    def test_pre_solve_trace_is_diagnostic_only_and_does_not_change_order_policy(self):
        expected = ['-Dsysds.fedplanner.trace=true',
                    '-Dsysds.fedplanner.trace.details=false']
        cell = CAMPAIGN.matrix()[0]
        diagnostic = CAMPAIGN.coordinator_java(cell, 'compile', True)
        ordinary = CAMPAIGN.coordinator_java(cell, 'compile', False)
        for option in expected:
            self.assertIn(option, diagnostic)
            self.assertNotIn(option, ordinary)
            self.assertNotIn(option, CAMPAIGN.JAVA)
        self.assertEqual(expected,
                         CAMPAIGN.diagnostic_contract(True)['diagnostic_planner_trace_options'])
        self.assertEqual([], CAMPAIGN.diagnostic_contract(False)['diagnostic_planner_trace_options'])
        for option in diagnostic:
            self.assertFalse(option.startswith(('-Dsysds.fedplanner.exact.fastOrder',
                                                '-Dsysds.fedplanner.regional.fastBlock',
                                                '-Dsysds.fedplanner.regional.compact')))

    def test_compact_option_is_exact_and_coordinator_diagnostic_only(self):
        cell = CAMPAIGN.matrix()[0]
        compact = CAMPAIGN.coordinator_java(cell, 'compile', True, True)
        baseline_diagnostic = CAMPAIGN.coordinator_java(cell, 'compile', True, False)
        ordinary = CAMPAIGN.coordinator_java(cell, 'compile', False, False)
        option = '-Dsysds.fedplanner.regional.compact=true'
        self.assertEqual(1, compact.count(option))
        self.assertNotIn(option, baseline_diagnostic)
        self.assertNotIn(option, ordinary)
        self.assertNotIn(option, CAMPAIGN.JAVA)
        self.assertEqual(baseline_diagnostic, [item for item in compact if item != option])
        with self.assertRaisesRegex(ValueError, 'requires diagnostic JFR'):
            CAMPAIGN.coordinator_java(cell, 'compile', False, True)
        self.assertEqual({
            'diagnostic_jfr': True,
            'diagnostic_jfr_options': list(CAMPAIGN.JFR_OPTIONS),
            'diagnostic_planner_trace_options': list(CAMPAIGN.DIAGNOSTIC_TRACE_OPTIONS),
            'diagnostic_compact': True,
            'diagnostic_compact_options': [option],
        }, CAMPAIGN.diagnostic_contract(True, True))


class DiagnosticGateAndReportingTest(unittest.TestCase):
    def test_compact_manifest_flag_alone_closes_gate(self):
        passed = {row['id']: {'status': 'passed', 'timeout_seconds': 60} for row in CAMPAIGN.matrix()}
        for manifest in ({'measurement': {'diagnostic_compact': True}},
                         {'identity': {'diagnostic': {'diagnostic_compact': True}}}):
            with self.subTest(manifest=manifest), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                CAMPAIGN.dump(root / 'manifest.json', manifest)
                with mock.patch.object(CAMPAIGN, 'latest', return_value=passed):
                    self.assertFalse(CAMPAIGN.compile_gate(root))

    def test_identity_flag_or_individual_diagnostic_row_also_closes_gate(self):
        passed = {row['id']: {'status': 'passed', 'timeout_seconds': 60} for row in CAMPAIGN.matrix()}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            CAMPAIGN.dump(root / 'manifest.json', {
                'identity': {'diagnostic': {'diagnostic_jfr': True}}})
            with mock.patch.object(CAMPAIGN, 'latest', return_value=passed):
                self.assertFalse(CAMPAIGN.compile_gate(root))
            CAMPAIGN.dump(root / 'manifest.json', {'measurement': {'diagnostic_jfr': False}})
            passed[CAMPAIGN.matrix()[0]['id']]['diagnostic_only'] = True
            with mock.patch.object(CAMPAIGN, 'latest', return_value=passed):
                self.assertFalse(CAMPAIGN.compile_gate(root))

    def test_global_gate_rejects_diagnostic_root_even_if_every_cell_passes(self):
        passed = {row['id']: {'status': 'passed', 'timeout_seconds': 60} for row in CAMPAIGN.matrix()}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            CAMPAIGN.dump(root / 'manifest.json', {
                'measurement': {'diagnostic_jfr': True}})
            with mock.patch.object(CAMPAIGN, 'latest', return_value=passed):
                self.assertFalse(CAMPAIGN.compile_gate(root))

    def test_diagnostic_timings_are_blank_in_ordinary_comparison_csv(self):
        cell = CAMPAIGN.matrix()[0]
        row = {'status': 'passed', 'diagnostic_only': True, 'diagnostic_compact': True,
               'attempt': 'diagnostic', 'timeout_seconds': 60,
               'compile_seconds': 12.3, 'searchspace_seconds': 4.5,
               'selection_adapter_seconds': 2.1}
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(CAMPAIGN, 'latest', side_effect=(
                    {cell['id']: row}, {})), \
                mock.patch.object(CAMPAIGN, 'schedule', return_value=[cell]), \
                mock.patch.object(CAMPAIGN, 'compile_gate', return_value=False):
            root = Path(directory)
            CAMPAIGN.summarize(root)
            with (root / 'compile-comparison.csv').open(newline='') as stream:
                exported = next(csv.DictReader(stream))
            self.assertEqual('60', exported['timeout_seconds'])
            self.assertEqual('True', exported['diagnostic_only'])
            self.assertEqual('True', exported['diagnostic_compact'])
            self.assertEqual('', exported['compile_seconds'])
            self.assertEqual('', exported['searchspace_seconds'])
            self.assertEqual('', exported['selection_adapter_seconds'])


class DiagnosticCollectionTest(unittest.TestCase):
    def test_collection_records_local_path_hash_and_size(self):
        payload = b'JFR\x00diagnostic-profile'

        def fake_run(argv, **kwargs):
            Path(argv[-1]).write_bytes(payload)
            return ''

        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(CAMPAIGN, 'run', side_effect=fake_run):
            receipt = CAMPAIGN.collect_diagnostic_jfr(
                'so007', Path('/remote/attempt'), Path(directory))
            self.assertEqual(len(payload), receipt['size_bytes'])
            self.assertEqual(CAMPAIGN.sha(receipt['path']), receipt['sha256'])
            self.assertEqual(str(Path(directory) / 'compile.jfr'), receipt['path'])

    def test_collection_error_does_not_mask_primary_compile_failure(self):
        result = {'errors': ['production compile failed, rc=124']}
        with mock.patch.object(CAMPAIGN, 'collect_diagnostic_jfr',
                side_effect=RuntimeError('remote profile missing')):
            CAMPAIGN.record_diagnostic_jfr(
                result, 'so007', Path('/remote/attempt'), Path('/local/attempt'))
        self.assertEqual(['production compile failed, rc=124'], result['errors'])
        self.assertEqual(1, len(result['diagnostic_collection_errors']))
        self.assertIn('remote profile missing', result['diagnostic_collection_errors'][0])


if __name__ == '__main__':
    unittest.main()
