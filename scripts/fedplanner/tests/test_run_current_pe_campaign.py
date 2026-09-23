import io
from contextlib import redirect_stdout
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_campaign import (model_status, physical_stage_args,
                                     physical_stage_result, preflight, publish)
from run_current_pe_cell import read, save, sha


class CurrentPeCampaignGateTest(unittest.TestCase):
    def test_disk_preflight_reserves_both_p_and_e_jobs(self):
        args = SimpleNamespace(artifact_root=Path('/tmp'), p_jobs=2, e_jobs=2,
                               max_jvms=4, min_free_disk_gib=20,
                               p_job_disk_gib=2, e_job_disk_gib=8)
        with patch('run_current_pe_campaign.resource_snapshot', return_value={
                'availableRamGiB': 100, 'freeDiskGiB': 39}):
            with self.assertRaisesRegex(ValueError, 'disk reservation'):
                preflight(args)
        with patch('run_current_pe_campaign.resource_snapshot', return_value={
                'availableRamGiB': 100, 'freeDiskGiB': 40}):
            self.assertEqual(preflight(args)['freeDiskGiB'], 40)

    def test_runner_mutation_cannot_publish_suite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(campaign=root / 'campaign.json',
                                   catalog=root / 'catalog.json',
                                   evaluation_root=root, artifact_root=root)
            with patch('run_current_pe_campaign.RUNNER_SHA', '0' * 64):
                with self.assertRaisesRegex(ValueError, 'changed during execution'):
                    publish(args, {'scope': 'FULL_CURRENT'}, ['c'], {},
                            {'status': 'COMPLETE'}, {'status': 'COMPLETE'},
                            {'status': 'EQUAL'})

    def test_stale_different_summary_cannot_mask_physical_crash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            summary = root / 'summary.json'
            log = root / 'physical.log'
            save(summary, {'status': 'DIFFERENT', 'counts': {'DIFFERENT': 1}})
            log.write_text('Traceback: physical verifier crashed\n')
            result = physical_stage_result({'exitCode': 1, 'log': str(log)}, summary)
            self.assertEqual(result['status'], 'ERROR')
            log.write_text('{"status":"DIFFERENT","counts":{"DIFFERENT":1}}\n')
            self.assertEqual(physical_stage_result(
                {'exitCode': 1, 'log': str(log)}, summary)['status'], 'DIFFERENT')

    def test_physical_stage_reuses_both_verified_model_matrices(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(campaign=root / 'campaign.json',
                                   catalog=root / 'catalog.json', evaluation_root=root,
                                   verification_root=root, artifact_root=root,
                                   physical_jobs=2, shard_jobs=2, state_budget=1000,
                                   e_raw_budget=1000000, cell_timeout=3600,
                                   max_jvms=8, min_free_disk_gib=20,
                                   build_root=root / 'build')
            command = physical_stage_args(args, 'run')
            self.assertEqual(command[command.index('--p-matrix-dir') + 1],
                             str(root / 'p-models'))
            self.assertEqual(command[command.index('--e-matrix-dir') + 1],
                             str(root / 'e-models'))

    def test_complete_p_capture_needs_stored_artifact_verification(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'p-models'
            save(folder / 'matrix.json', {'status': 'COMPLETE', 'cellCount': 1,
                                          'counts': {'COMPLETE': 1},
                                          'cells': [{'cell': 'c', 'status': 'COMPLETE'}]})
            self.assertEqual(model_status(root, 'P', ['c'])['status'], 'ERROR')
            save(folder / 'verification.json', {'status': 'PASS', 'cellCount': 1,
                                                'counts': {'COMPLETE': 1},
                                                'matrixSha256': sha(folder / 'matrix.json'),
                                                'verifiedComplete': 1, 'failures': []})
            self.assertEqual(model_status(root, 'P', ['c'])['status'], 'COMPLETE')

    def test_complete_e_capture_with_unknown_factor_cannot_enter_physical_gate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'e-models'
            save(folder / 'matrix.json', {'status': 'COMPLETE', 'cellCount': 1,
                                          'counts': {'COMPLETE': 1},
                                          'cells': [{'cell': 'c', 'status': 'COMPLETE'}],
                                          'binding': {'sourceTreeSha256': '0' * 64,
                                                      'classTreeSha256': '1' * 64}})
            save(folder / 'verification.json', {'status': 'INCOMPLETE', 'cellCount': 1,
                                                'matrixSha256': sha(folder / 'matrix.json'),
                                                'counts': {'COMPLETE': 1}, 'failures': [],
                                                'unknownFactorCells': [
                                                    {'cell': 'c', 'unknownFactorCells': 1}]})
            row = model_status(root, 'E', ['c'])
            self.assertEqual(row['captureStatus'], 'COMPLETE')
            self.assertEqual(row['status'], 'INCOMPLETE')
            self.assertEqual(row['unknownFactorCells'][0]['cell'], 'c')

    def test_complete_matrices_cannot_hide_failed_verifier_or_derived_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign = root / 'campaign.json'
            catalog = root / 'catalog.json'
            save(campaign, {'schema': 'test'})
            save(catalog, {'schema': 'test'})
            args = SimpleNamespace(campaign=campaign, catalog=catalog,
                                   evaluation_root=root, artifact_root=root)
            model = {'status': 'COMPLETE', 'counts': {'COMPLETE': 1}, 'cellCount': 1,
                     'sourceTreeSha256': '0' * 64, 'classTreeSha256': '1' * 64}
            stages = {'P': {'exitCode': 0}, 'E': {'exitCode': 0},
                      'P-verification': {'exitCode': 0},
                      'physical': {'exitCode': 0}}
            physical = {'status': 'EQUAL'}
            with redirect_stdout(io.StringIO()):
                self.assertEqual(publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                                         stages, model, model, physical), 0)
                run_receipt = read(root / 'suite.json')
                self.assertEqual(publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                                         stages, model, model, physical,
                                         'suite-verification.json'), 0)
                self.assertEqual(read(root / 'suite.json'), run_receipt)
                self.assertEqual(read(root / 'suite-verification.json')['status'], 'EQUAL')
                failed = {**stages, 'E': {'exitCode': 2}}
                self.assertEqual(publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                                         failed, model, model, physical), 1)
                self.assertEqual(publish(args, {'scope': 'FROZEN_COHORT_DERIVED_ARGV_1'},
                                         ['c'], stages, model, model, physical), 2)
                crashed = {**stages, 'physical': {'exitCode': 137}}
                self.assertEqual(publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                                         crashed, model, model,
                                         {'status': 'DIFFERENT'}), 1)
                self.assertEqual(read(root / 'suite.json')['status'], 'ERROR')
                p_verifier_failed = {**stages, 'P-verification': {'exitCode': 2},
                                     'physical': {'exitCode': 1}}
                self.assertEqual(publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                                         p_verifier_failed, model, model,
                                         {'status': 'DIFFERENT'}), 1)
                self.assertEqual(read(root / 'suite.json')['status'], 'ERROR')

    def test_source_class_mismatch_is_never_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            campaign = root / 'campaign.json'
            catalog = root / 'catalog.json'
            campaign.write_text('{}')
            catalog.write_text('{}')
            args = SimpleNamespace(campaign=campaign, catalog=catalog,
                                   evaluation_root=root, artifact_root=root)
            p = {'status': 'COMPLETE', 'counts': {}, 'sourceTreeSha256': '0' * 64,
                 'classTreeSha256': '1' * 64}
            e = {**p, 'classTreeSha256': '2' * 64}
            with self.assertRaisesRegex(ValueError, 'different source/class'):
                publish(args, {'scope': 'FULL_CURRENT'}, ['c'],
                        {'P': {'exitCode': 0}, 'E': {'exitCode': 0}}, p, e,
                        {'status': 'NOT_RUN'})


if __name__ == '__main__':
    unittest.main()
