#!/usr/bin/env python3
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

SOURCE_REPO = Path(os.environ.get('FEDS_SOURCE_REPO', Path(__file__).resolve().parents[3]))
sys.path.insert(0, str(SOURCE_REPO / 'scripts/fedplanner'))
SOURCE = Path(__file__).resolve().parents[1] / 'run_matrix_campaign.py'
SPEC = importlib.util.spec_from_file_location('pinned_runtime_hardening', SOURCE)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


def image_inspect(image='sha256:' + 'd' * 64):
    return [{'Id': image, 'Architecture': 'amd64', 'Os': 'linux',
             'Created': '2026-07-08T03:24:10Z', 'Config': {'Cmd': ['/bin/bash']},
             'RootFS': {'Layers': ['sha256:' + '1' * 64]}}]


def fixture(root):
    cost = {key: '1.25' for key in RUNNER.PINNED_COST_KEYS}
    config = {'sysds.native.blas': 'none', 'sysds.codegen.enabled': 'false'}
    files = {
        'cost_environment': root / 'cost.json',
        'configuration': root / 'execution.xml',
        'command': root / 'command.json',
        'image_inspect': root / 'image-inspect.json',
    }
    files['cost_environment'].write_text(json.dumps(cost))
    files['configuration'].write_text('<root><sysds.native.blas>none</sysds.native.blas>'
                                     '<sysds.codegen.enabled>false</sysds.codegen.enabled></root>')
    files['command'].write_text(json.dumps({
        'image': 'sha256:' + 'd' * 64, 'environment': cost, 'docker_argv': ['docker', 'run', '--cpus', '4', '--memory', '16g'],
        'java_argv': ['java', '-Xmx10g', '-XX:ActiveProcessorCount=4']}))
    files['image_inspect'].write_text(json.dumps(image_inspect()))
    provenance = {}
    for key, path in files.items():
        provenance[key + '_source'] = str(path)
        provenance[key + '_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    _, content = RUNNER.normalized_image_content(image_inspect())
    value = {'schema': RUNNER.PINNED_RUNTIME_SCHEMA, 'image': 'sha256:' + 'd' * 64,
             'runtime_image': 'cofee-experiment:content-' + content, 'cost_environment': cost,
             'config': config, 'resources': {'container_cpus': '4',
                 'container_memory': '16g', 'jvm_max_heap': '10g', 'active_processor_count': 4},
             'provenance': provenance}
    path = root / 'contract.json'
    path.write_text(json.dumps(value))
    return path, value, files


class PinnedRuntimeHardeningTest(unittest.TestCase):
    def test_image_preflight_always_uses_the_normalized_content_verifier(self):
        campaign = SimpleNamespace(verify_remote_image_content=mock.Mock(return_value={'host': 'content'}))
        self.assertEqual({'host': 'content'}, RUNNER.verify_campaign_images(campaign, ['host'], {}))
        campaign.verify_remote_image_content.assert_called_once_with(['host'], RUNNER.IMAGE)
        runtime_image = 'cofee-experiment:content-' + 'a' * 64
        manifest = {'image': runtime_image,
                    'identity': {'pinned_runtime_contract_sha256': 'b' * 64}}
        self.assertEqual({'host': 'content'}, RUNNER.verify_campaign_images(campaign, ['host'], manifest))
        campaign.verify_remote_image_content.assert_called_with(['host'], runtime_image)

    def test_pinned_cli_uses_the_canonical_content_tag_verifier(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, contract, _ = fixture(root)
            manifest = {'image': contract['runtime_image'], 'identity': {
                'pinned_runtime_contract_sha256': hashlib.sha256(path.read_bytes()).hexdigest()}}
            verifier = mock.Mock(return_value={'host': contract['runtime_image'].split('-')[-1]})
            campaign = SimpleNamespace(acquire_remote_stage_leases=lambda *args: ['lease'],
                release_remote_stage_leases=lambda *args: {'released': True},
                verify_remote_image_content=verifier, verify_remote_bounded_stage=lambda *args: {})
            base = SimpleNamespace(RUNTIME_LANE=root / 'lane')
            renderer = SimpleNamespace(Renderer=lambda stage: object())
            status = {'runtime': {'passed': len(RUNNER.matrix()), 'failed': 0, 'pending': 0}}
            with mock.patch.object(RUNNER, 'dependencies', return_value=(campaign, base, renderer)), \
                    mock.patch.object(RUNNER, 'initialize', return_value=manifest), \
                    mock.patch.object(RUNNER, 'runtime_selection_ids', return_value=None), \
                    mock.patch.object(RUNNER, 'publish_overlay', return_value={}), \
                    mock.patch.object(RUNNER, 'latest', return_value={}), \
                    mock.patch.object(RUNNER, 'schedule', return_value=[]), \
                    mock.patch.object(RUNNER.runtime_compare, 'pin_reference', return_value={}), \
                    mock.patch.object(RUNNER, 'summarize', return_value=status), \
                    mock.patch('sys.stdout', new=io.StringIO()):
                result = RUNNER.main(['--root', str(root), '--phase', 'runtime',
                    '--runtime-without-compile-survey', '--pinned-runtime-contract', str(path)])
            self.assertEqual(0, result)
            verifier.assert_called_once()
            self.assertEqual(contract['runtime_image'], verifier.call_args.args[1])

    def test_cli_resume_validates_frozen_provenance_without_original_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path, _, files = fixture(root)
            campaign = root / 'campaign'
            frozen = campaign / RUNNER.PINNED_SOURCE_DIRECTORY
            frozen.mkdir(parents=True)
            (campaign / 'manifest.json').write_text('{}')
            for name, source in files.items():
                (frozen / name).write_bytes(source.read_bytes())
                source.unlink()
            argv = ['--root', str(campaign), '--phase', 'runtime',
                    '--runtime-without-compile-survey', '--pinned-runtime-contract', str(path)]
            with mock.patch.object(RUNNER, 'dependencies',
                    side_effect=RuntimeError('validated CLI handoff')):
                with self.assertRaisesRegex(RuntimeError, 'validated CLI handoff'):
                    RUNNER.main(argv)

    def test_java_rejected_numeric_tokens_do_not_become_silent_defaults(self):
        for token in ('1_000', '\u0661\u0662.5'):
            with self.subTest(token=token), tempfile.TemporaryDirectory() as directory:
                path, value, _ = fixture(Path(directory))
                value['cost_environment']['SYSDS_FED_COST_FLOPS'] = token
                path.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'cost value'):
                    RUNNER.read_pinned_runtime_contract(path)

    def test_source_bytes_must_match_claimed_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path, _, files = fixture(Path(directory))
            files['command'].write_text('{}')
            with self.assertRaisesRegex(ValueError, 'provenance'):
                RUNNER.read_pinned_runtime_contract(path)

    def test_contract_values_must_match_hashed_sources(self):
        for field in ('cost_environment', 'config'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                path, value, _ = fixture(Path(directory))
                if field == 'cost_environment':
                    value[field]['SYSDS_FED_COST_FLOPS'] = '2.5'
                else:
                    value[field]['sysds.native.blas'] = 'mkl'
                path.write_text(json.dumps(value))
                with self.assertRaisesRegex(ValueError, 'provenance'):
                    RUNNER.read_pinned_runtime_contract(path)


if __name__ == '__main__':
    unittest.main()
