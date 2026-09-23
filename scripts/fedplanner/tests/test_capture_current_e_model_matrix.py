import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout
import io

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import capture_current_e_model_matrix as e_matrix
from capture_current_e_model_matrix import (GIB, capture_one, require_disk_budget,
                                            verify_matrix, verify_one)
from run_current_pe_cell import frozen_compiler_configuration, save, sha


class EModelMatrixVerifierTest(unittest.TestCase):
    def test_invalid_resume_is_a_structured_incomplete_matrix(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / 'catalog.json'
            save(catalog, {'cells': []})
            artifacts = root / 'artifacts'
            folder = artifacts / 'c'
            folder.mkdir(parents=True)
            (folder / 'receipt.json').write_bytes(b'broken prior receipt')
            row = {'id': 'c', 'sourceBinding': {}}
            binding = {'sourceTreeSha256': '0' * 64,
                       'classTreeSha256': '1' * 64,
                       'catalogSha256': sha(catalog), 'evaluationRoot': str(root),
                       'runnerSha256': e_matrix.runner_sha()}
            args = SimpleNamespace(artifact_root=artifacts, catalog=catalog,
                                   evaluation_root=root, resume=True,
                                   recapture_invalid=False)
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                 patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                 patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})):
                result = capture_one(row, args, binding)
            self.assertEqual(result['status'], 'ERROR')
            self.assertEqual(json.loads((folder / 'receipt.json').read_text()), result)
            self.assertEqual((Path(result['invalidPriorEvidence']) / 'receipt.json').read_bytes(),
                             b'broken prior receipt')
            save(artifacts / 'matrix.json', {
                'schema': 'current-e-model-matrix-v1', 'status': 'INCOMPLETE',
                'binding': binding, 'counts': {'ERROR': 1},
                'cells': [{'cell': 'c', 'status': 'ERROR'}]})
            with patch.object(e_matrix, 'check_frozen_inputs'):
                verdict = verify_matrix(args, [row], binding)
            self.assertEqual(verdict['status'], 'INCOMPLETE')
            self.assertEqual(verdict['counts'], {'ERROR': 1})

    def test_resume_preserves_unreadable_and_stale_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'artifacts/c'
            folder.mkdir(parents=True)
            receipt = folder / 'receipt.json'
            model = folder / 'e-model.json.gz'
            model.write_bytes(b'old model bytes')
            args = SimpleNamespace(artifact_root=root / 'artifacts',
                                   catalog=root / 'catalog', evaluation_root=root,
                                   resume=True, recapture_invalid=False, jobs=1,
                                   min_free_disk_gib=0, per_job_disk_gib=1)
            row = {'id': 'c', 'sourceBinding': {}}
            binding = {'runnerSha256': e_matrix.runner_sha()}
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                 patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                 patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})), \
                 patch.object(e_matrix.subprocess, 'Popen',
                              side_effect=AssertionError('Java must not run')):
                receipt.write_bytes(b'not JSON')
                unreadable = capture_one(row, args, binding)
                self.assertEqual(unreadable['status'], 'ERROR')
                self.assertIn('unreadable', unreadable['error'])
                first_quarantine = next(folder.glob('prior-capture-*'))
                self.assertEqual((first_quarantine / 'receipt.json').read_bytes(), b'not JSON')
                self.assertEqual((first_quarantine / 'e-model.json.gz').read_bytes(),
                                 b'old model bytes')
                stale = {'binding': {'runnerSha256': 'stale'},
                         'status': 'COMPLETE', 'cell': 'c'}
                save(receipt, stale)
                model.write_bytes(b'old model bytes')
                stale_result = capture_one(row, args, binding)
                self.assertEqual(stale_result['status'], 'ERROR')
                self.assertIn('binding differs', stale_result['error'])
                self.assertTrue(any(json.loads((path / 'receipt.json').read_text()) == stale
                    for path in folder.glob('prior-capture-*')
                    if (path / 'receipt.json').is_file() and path != first_quarantine))
            args.recapture_invalid = True
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                 patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                 patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})), \
                 patch.object(e_matrix, 'require_disk_budget',
                              side_effect=OSError('bounded retry stop')):
                result = capture_one(row, args, binding)
            self.assertEqual(result['status'], 'RESOURCE_LIMIT')
            quarantines = list(folder.glob('prior-capture-*'))
            self.assertGreaterEqual(len(quarantines), 3)
            self.assertTrue(any((path / 'e-model.json.gz').read_bytes() == b'old model bytes'
                                for path in quarantines if (path / 'e-model.json.gz').exists()))

    def test_verification_receipt_binds_exact_manifest_and_verifier_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifacts = root / 'artifacts'
            catalog = root / 'catalog.json'
            save(catalog, {'cells': []})
            binding = {'sourceTreeSha256': '0' * 64,
                       'classTreeSha256': '1' * 64,
                       'catalogSha256': sha(catalog),
                       'evaluationRoot': str(root),
                       'runnerSha256': e_matrix.runner_sha()}
            save(artifacts / 'matrix.json', {
                'schema': 'current-e-model-matrix-v1', 'status': 'COMPLETE',
                'binding': binding, 'counts': {'COMPLETE': 1},
                'cells': [{'cell': 'c', 'status': 'COMPLETE'}]})
            save(artifacts / 'c/receipt.json', {'binding': binding, 'cell': 'c',
                                                'status': 'COMPLETE'})
            args = SimpleNamespace(artifact_root=artifacts, catalog=catalog,
                                   evaluation_root=root)
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                    patch.object(e_matrix, 'verify_one', return_value=0):
                verdict = verify_matrix(args, [{'id': 'c'}], binding)
            self.assertEqual(verdict['status'], 'PASS')
            self.assertEqual(verdict['matrixSha256'], sha(artifacts / 'matrix.json'))
            self.assertEqual(verdict['catalogSha256'], sha(catalog))
            self.assertEqual(verdict['evaluationRoot'], str(root))
            self.assertEqual(verdict['runnerSha256'], e_matrix.runner_sha())

            stale = {**binding, 'runnerSha256': 'f' * 64}
            save(artifacts / 'matrix.json', {
                'schema': 'current-e-model-matrix-v1', 'status': 'COMPLETE',
                'binding': stale, 'counts': {'COMPLETE': 1},
                'cells': [{'cell': 'c', 'status': 'COMPLETE'}]})
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                    patch.object(e_matrix, 'verify_one', return_value=0), \
                    self.assertRaisesRegex(ValueError, 'supplied binding differs'):
                verify_matrix(args, [{'id': 'c'}], stale)

    def test_corrupt_complete_resume_does_not_silently_recapture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifacts = root / 'artifacts'
            folder = artifacts / 'c'
            folder.mkdir(parents=True)
            binding = {'runnerSha256': e_matrix.runner_sha()}
            old = {'schema': 'current-e-model-matrix-cell-v1',
                   'binding': binding, 'status': 'COMPLETE', 'cell': 'c'}
            save(folder / 'receipt.json', old)
            (folder / 'e-model.json.gz').write_bytes(b'corrupt')
            args = SimpleNamespace(artifact_root=artifacts, catalog=root / 'catalog',
                                   evaluation_root=root, resume=True,
                                   recapture_invalid=False)
            row = {'id': 'c', 'sourceBinding': {}}
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                    patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                    patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})), \
                    patch.object(e_matrix, 'verify_one', side_effect=ValueError('corrupt')), \
                    patch.object(e_matrix.subprocess, 'Popen',
                                 side_effect=AssertionError('recaptured')):
                result = capture_one(row, args, binding)
            self.assertEqual(result['status'], 'ERROR')
            self.assertIn('corrupt', result['error'])
            original_quarantine = next(folder.glob('prior-capture-*'))
            self.assertEqual(json.loads((original_quarantine / 'receipt.json').read_text()), old)
            self.assertEqual((folder / 'e-model.json.gz').read_bytes(), b'corrupt')
            self.assertEqual(json.loads((folder / 'receipt.json').read_text()), result)
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                    patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                    patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})), \
                    patch.object(e_matrix.subprocess, 'Popen',
                                 side_effect=AssertionError('recaptured')):
                self.assertEqual(capture_one(row, args, binding), result)

            args.recapture_invalid = True
            args.jobs = 1
            args.min_free_disk_gib = 0
            args.per_job_disk_gib = 1
            with patch.object(e_matrix, 'check_frozen_inputs'), \
                    patch.object(e_matrix, 'frozen_compiler_configuration', return_value={}), \
                    patch.object(e_matrix, 'frozen_capture_settings', return_value=({}, [], {})), \
                    patch.object(e_matrix, 'require_disk_budget',
                                 side_effect=OSError('bounded retry stop')):
                retried = capture_one(row, args, binding)
            self.assertEqual(retried['status'], 'RESOURCE_LIMIT')
            quarantines = list(folder.glob('prior-capture-*'))
            self.assertEqual(len(quarantines), 2)
            self.assertEqual(json.loads((original_quarantine / 'receipt.json').read_text()), old)
            self.assertTrue(any((path / 'e-model.json.gz').read_bytes() == b'corrupt'
                                for path in quarantines if (path / 'e-model.json.gz').exists()))

    def test_offline_verify_uses_saved_source_binding_without_build_tree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            catalog = root / 'catalog.json'
            save(catalog, {'cells': [{'id': 'c', 'inventoryStatus': 'IN_SCOPE',
                                      'sourceBinding': {'conditionStatus': 'SNAPSHOT_ONLY'}}]})
            artifacts = root / 'artifacts'
            artifacts.mkdir()
            binding = {'sourceTreeSha256': '0' * 64, 'classTreeSha256': '1' * 64,
                       'catalogSha256': sha(catalog), 'evaluationRoot': str(root),
                       'runnerSha256': e_matrix.runner_sha(), 'readyOnly': True,
                       'selectedCell': None}
            save(artifacts / 'matrix.json', {'binding': binding})
            argv = ['capture_current_e_model_matrix.py', 'verify', '--catalog', str(catalog),
                    '--evaluation-root', str(root), '--artifact-root', str(artifacts),
                    '--ready-only']
            with patch.object(sys, 'argv', argv), patch.object(e_matrix, 'verify_matrix') as verify, \
                    patch.object(e_matrix, 'tree_sha', side_effect=AssertionError('build touched')):
                verify.return_value = {'status': 'PASS', 'verifiedComplete': 1, 'cellCount': 1}
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(e_matrix.main(), 0)
                self.assertEqual(verify.call_args.args[2], binding)
            binding['catalogSha256'] = '2' * 64
            save(artifacts / 'matrix.json', {'binding': binding})
            with patch.object(sys, 'argv', argv), self.assertRaisesRegex(ValueError, 'binding differs'):
                e_matrix.main()

    def test_concurrent_capture_reserves_disk_for_atomic_publication(self):
        with patch('capture_current_e_model_matrix.shutil.disk_usage') as usage:
            usage.return_value = SimpleNamespace(free=67 * GIB)
            with self.assertRaisesRegex(OSError, 'E capture disk reserve'):
                require_disk_budget(Path('/unused'), 6, 20, 8)
            usage.return_value = SimpleNamespace(free=68 * GIB)
            require_disk_budget(Path('/unused'), 6, 20, 8)

    def test_saved_factor_and_compiler_contract_replayed_without_java(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evaluation = root / "evaluation"
            evaluation.mkdir()
            program = evaluation / "program.dml"
            program.write_text("X=1;\n")
            argv = ["-exec", "singlenode", "-seed", "1011081480",
                    "-noFedRuntimeConversion", "-stats", "100"]
            binding = {"kind": "campaign-compile-condition",
                       "conditionStatus": "SNAPSHOT_ONLY", "compilerArgv": argv,
                       "conditionSha256": "condition", "workloadJvmOptions": [],
                       "plannedCondition": {"workers": 1, "case": {"program": "program.dml"},
                                            "network": {"cost_environment": {
                                                "SYSDS_FED_COST_NET_BW": "2"}}}}
            row = {"id": "c", "discoveryId": "base:c", "inventoryStatus": "IN_SCOPE",
                   "sourceBinding": binding,
                   "sourceFiles": {"program.dml": sha(program)}}
            catalog = root / "catalog.json"
            save(catalog, {"cells": [row]})
            compiler = frozen_compiler_configuration(binding)
            facts = {"compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                     "networkEnvironmentChecked": True,
                     "networkEnvironment": {"SYSDS_FED_COST_NET_BW": "2"},
                     "workloadJvmProperties": {}, "compilerArgv": argv,
                     "compilerConfiguration": compiler}
            model = {"schema": "closed-e-native-model-artifact-v1",
                     "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "c", "source": "E_C0",
                     "programSha256": sha(program), "conditionSha256": "condition",
                     "sourceFiles": row["sourceFiles"], **facts,
                     "domains": [{"index": 0, "alternatives": [{"signature": "a"}]}],
                     "factors": [{"scope": [0], "cells": "1", "truth": ["ALLOW"]}]}
            artifact = root / "artifacts/c/e-model.json.gz"
            artifact.parent.mkdir(parents=True)
            def write_model():
                plain = json.dumps(model).encode()
                artifact.write_bytes(gzip.compress(plain))
                return hashlib.sha256(plain).hexdigest()
            receipt = {"schema": "current-e-model-matrix-cell-v1", "cell": "c",
                       "status": "COMPLETE", "artifactPath": str(artifact),
                       "artifactSha256": write_model(),
                       "native": {**facts, "schema": "closed-native-model-capture-v1",
                                  "source": "E_C0", "artifactPath": str(artifact),
                                  "radices": [1], "opaqueFactors": 0,
                                  "status": "COMPLETE", "cell": "c",
                                  "artifactSha256": None, "domainCount": 1,
                                  "factorCount": 1, "rawCount": "1"},
                       "networkEnvironment": facts["networkEnvironment"],
                       "jvmOptions": [], "compilerArgv": argv,
                       "compilerConfiguration": compiler}
            args = SimpleNamespace(artifact_root=root / "artifacts", catalog=catalog,
                                   evaluation_root=evaluation)
            receipt["native"]["artifactSha256"] = receipt["artifactSha256"]
            self.assertEqual(verify_one(receipt, row, args), 0)
            model['factors'][0]['truth'] = ['UNKNOWN']
            receipt['artifactSha256'] = write_model()
            receipt['native']['artifactSha256'] = receipt['artifactSha256']
            self.assertEqual(verify_one(receipt, row, args), 1)
            model['factors'][0]['truth'] = ['ALLOW']
            receipt['artifactSha256'] = write_model()
            receipt['native']['artifactSha256'] = receipt['artifactSha256']
            receipt["native"]["source"] = "P_C0"
            with self.assertRaisesRegex(ValueError, "frozen input/domain"):
                verify_one(receipt, row, args)
            receipt["native"]["source"] = "E_C0"
            model["compilerConfiguration"] = {**compiler, "execMode": "HYBRID"}
            receipt["artifactSha256"] = write_model()
            receipt["native"]["artifactSha256"] = receipt["artifactSha256"]
            with self.assertRaisesRegex(ValueError, "frozen compiler configuration"):
                verify_one(receipt, row, args)
            model["compilerConfiguration"] = compiler
            model["factors"][0]["cells"] = "2"
            receipt["artifactSha256"] = write_model()
            receipt["native"]["artifactSha256"] = receipt["artifactSha256"]
            with self.assertRaisesRegex(ValueError, "factor table cardinality"):
                verify_one(receipt, row, args)


if __name__ == "__main__":
    unittest.main()
