import base64
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from exact_e_physical_relation import (build, compose_physical_projection, publish,
                                       read_artifact, verify_artifact)


def domain(index, occurrence, alternatives):
    return {'index': index, 'occurrence': occurrence, 'nodeKind': 'OPERATION',
            'alternatives': alternatives}


def alternative(name):
    return {'signature': name, 'state': 'CP/LOUT/-/SHAPE_INDEPENDENT',
            'authorityKind': 'CAPTURED_RULE', 'candidateRule': None,
            'candidateEmission': None, 'executionRule': None,
            'executionEmission': None, 'realization': None,
            'supportClause': None, 'relocation': None, 'derivedFout': None,
            'inputAuthorities': []}


class ExactEPhysicalRelationTest(unittest.TestCase):
    def write_model(self, root, model):
        path = root / 'model.json.gz'
        with gzip.open(path, 'wt', encoding='utf-8') as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    def model(self):
        return {
            'schema': 'closed-e-native-model-artifact-v1', 'acceptance': 'MATERIALIZED_FACTOR_TABLES',
            'cell': 'fixture', 'programSha256': 'p', 'conditionSha256': 'c', 'sourceFiles': [],
            'domains': [domain(0, 'a', [alternative('a0'), alternative('a1')]),
                        domain(1, 'b', [alternative('b0'), alternative('b1')])],
            'factors': [{'scope': [0, 1], 'cells': '4',
                         'truth': ['ALLOW', 'REJECT', 'UNKNOWN', 'ALLOW']}],
            'sourceIdentity': {
                'nodes': [{'occurrence': 'a', 'operation': 'a'},
                          {'occurrence': 'b', 'operation': 'b'}],
                'orderedInputs': [{'consumer': 'b', 'producer': 'a', 'inputPosition': 0}],
                'logicalInputs': []}}

    def add_projection(self, model):
        def owner(name):
            return {'sourceOrigin': name, 'functionNamespace': '', 'callSitePath': '',
                    'recompileContext': '', 'emittedInstance': name,
                    'controlRegion': {'functionNamespace': '', 'regionPath': '',
                                      'callSitePath': '', 'recompileContext': ''}}

        def fragment(name, suffix, output='LOUT', bindings=()):
            occurrence = owner(name)
            authority_id = {'owner': occurrence, 'kind': 'CANDIDATE', 'layout': 'BOUNDARY'}
            return {'node': {'occurrence': occurrence, 'opcode': name, 'exec': 'CP',
                             'output': output, 'ftype': 'NONE', 'shapeDependent': False,
                             'executionFType': 'NONE', 'valueVersion': {'id': suffix},
                             'authorityRef': authority_id},
                    'authority': {'id': authority_id, 'source': 'CANDIDATE',
                                  'owner': occurrence, 'kind': 'CANDIDATE'},
                    'actions': [], 'geometry': [], 'bindings': list(bindings)}

        a0 = fragment('a', 'a0')
        a1 = fragment('a', 'a1', output='FOUT')
        model['domains'][0]['alternatives'][1]['state'] = 'CP/FOUT/-/SHAPE_INDEPENDENT'
        binding = {'consumer': owner('b'), 'inputPosition': 0, 'presence': 'PRESENT',
                   'ftype': 'NONE', 'mode': 'DIRECT_OR_FOUT', 'producerDomain': 0}
        b0 = fragment('b', 'b0', bindings=[binding])
        b1 = fragment('b', 'b1', bindings=[binding])
        model['sourceIdentity']['physicalLogicalInputs'] = []
        model['physicalProjectionContract'] = \
            'EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL'
        model['physicalProjection'] = {
            'schema': 'exact-physical-compositional-projection-v1',
            'logicalProgram': 'p', 'logicalInputs': [], 'nodeOrder': [0, 1],
            'bindingDomainOrder': [1],
            'variables': [
                {'domain': 0, 'occurrence': owner('a'), 'alternatives': [a0, a1]},
                {'domain': 1, 'occurrence': owner('b'), 'alternatives': [b0, b1]}]}
        return model

    def test_preserves_factor_and_witness_correlations_but_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = build(self.write_model(root, self.model()), exhaustive_limit=4)
            self.assertEqual(result['nativeRelationStatus'], 'INCOMPLETE_UNKNOWN')
            self.assertEqual(result['physicalProjectionStatus'], 'BLOCKED_NONCOMPOSITIONAL')
            self.assertEqual(result['counts'], {'raw': '4', 'accepted': '2',
                                                'rejected': '1', 'unknown': '1'})
            self.assertEqual(result['exhaustiveDifferential']['accepted'], '2')
            self.assertEqual(result['decodeScopes'][0]['domainScope'], [0, 1])
            self.assertIn('EXPLICIT_COMPOSITIONAL_PROJECTION_CONTRACT_MISSING',
                          result['projectionContract']['blockers'])

    def test_packed_factor_remains_packed_through_exhaustive_differential(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            raw = bytes([0 | (1 << 2) | (2 << 4) | (0 << 6)])
            packed = {'schema': 'packed-factor-truth-v1',
                      'encoding': '2BIT_LSB_FIRST_BASE64', 'cells': '4',
                      'statusCodes': {'ALLOW': 0, 'REJECT': 1, 'UNKNOWN': 2},
                      'data': base64.b64encode(raw).decode('ascii'),
                      'packedSha256': hashlib.sha256(raw).hexdigest(),
                      'statusCounts': {'ALLOW': '2', 'REJECT': '1', 'UNKNOWN': '1'}}
            model['factors'][0]['truth'] = packed
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('FACTOR_WITNESS_REPLAY_COMPLETE',
                             result['exhaustiveDifferential']['status'])
            self.assertEqual('2', result['exhaustiveDifferential']['accepted'])
            self.assertEqual(packed, result['factors'][0]['truth'])

    def test_artifact_round_trip_and_mutation_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.write_model(root, self.model())
            expected = build(path, exhaustive_limit=4)
            artifact = root / 'relation.json.gz'
            publish(artifact, expected)
            self.assertEqual(read_artifact(artifact), expected)
            damaged = read_artifact(artifact)
            damaged['factors'][0]['truth'][0] = 'REJECT'
            publish(artifact, damaged)
            with self.assertRaisesRegex(ValueError, 'independent recomputation'):
                verify_artifact(path, artifact, exhaustive_limit=4)

    def test_rejects_duplicate_domain_occurrence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model['domains'][1]['occurrence'] = 'a'
            with self.assertRaisesRegex(ValueError, 'duplicated'):
                build(self.write_model(root, model))

    def test_coordinate_names_alone_cannot_certify_physical_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model['physicalProjectionContract'] = \
                'EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL'
            model['sourceIdentity']['physicalLogicalInputs'] = []
            model['physicalProjection'] = {
                'schema': 'exact-physical-compositional-projection-v1',
                'coordinates': [
                    {'key': kind, 'kind': kind, 'scope': [], 'table': [[]]}
                    for kind in ('nodes', 'authority', 'actions', 'bindings',
                                 'logicalInputs', 'geometry')]}
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual(result['status'], 'BLOCKED')
            self.assertEqual(result['claimScope'], 'SERIALIZED_E_WITNESS_RELATION_ONLY')
            self.assertIn('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID',
                          result['projectionContract']['blockers'])
            model['physicalProjection']['coordinates'][0].update(
                {'scope': [0], 'table': [[{'fake': 'a0'}], [{'fake': 'a1'}]]})
            nonempty = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual(nonempty['status'], 'BLOCKED')

    def test_typed_projection_composes_all_accepted_assignments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            path = self.write_model(root, model)
            result = build(path, exhaustive_limit=4)
            self.assertEqual('BLOCKED', result['status'])
            self.assertEqual('DECODER_STRUCTURAL_ONLY', result['physicalProjectionStatus'])
            self.assertEqual('BLOCKED_SEMANTIC_BINDING', result['physicalImageStatus'])
            self.assertNotIn('physicalImage', result)
            self.assertEqual('2', result['exhaustiveDifferential']['accepted'])
            self.assertEqual('2', result['exhaustiveDifferential']['uniqueCanonicalPhysicalPlans'])
            plan = compose_physical_projection(model, [1, 1])
            self.assertEqual('DIRECT_FOUT', plan['bindings'][0]['inputAuthority'])
            self.assertEqual(plan['authority'][0]['id'],
                             plan['bindings'][0]['sourceAuthorityRef'])

    def test_decoder_only_does_not_claim_complete_physical_relation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            result = build(self.write_model(root, model), exhaustive_limit=0)
            self.assertEqual('BLOCKED', result['status'])
            self.assertEqual('DECODER_STRUCTURAL_ONLY', result['physicalProjectionStatus'])
            self.assertEqual('BLOCKED_EXHAUSTIVE_LIMIT_AND_SEMANTIC_BINDING',
                             result['physicalImageStatus'])
            self.assertEqual('STRUCTURAL_DECODER_ONLY', result['claimScope'])
            self.assertNotIn('physicalImage', result)

    def test_typed_projection_rejects_dangling_domain_and_action_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.add_projection(self.model())
            binding = model['physicalProjection']['variables'][1]['alternatives'][0]['bindings'][0]
            binding['producerDomain'] = 2
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED', result['status'])
            self.assertIn('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID',
                          result['projectionContract']['blockers'])

            model = self.add_projection(self.model())
            binding = model['physicalProjection']['variables'][1]['alternatives'][0]['bindings'][0]
            binding['mode'] = 'RELOCATION'
            binding['actionRef'] = {'missing': True}
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED', result['status'])

    def test_dynamic_native_residency_is_typed_and_mutation_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            fragment = model['physicalProjection']['variables'][0]['alternatives'][1]
            identity = dict(fragment['authority']['id'])
            identity['layout'] = 'NATIVE_LINEAGE'
            identity['workerResidency'] = {
                'ftype': 'ROW', 'endpoints': ['worker-a.example:4234',
                                              'worker-b.example:4235'],
                'layoutExact': False}
            fragment['authority']['id'] = identity
            fragment['node']['authorityRef'] = identity
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED', result['status'])
            self.assertEqual('BLOCKED_SEMANTIC_BINDING', result['physicalImageStatus'])
            plan = compose_physical_projection(model, [1, 1])
            self.assertEqual(identity, plan['authority'][0]['id'])
            self.assertNotIn('anchor', plan['authority'][0]['id'])
            artifact = root / 'dynamic-relation.json.gz'
            publish(artifact, result)

            fragment['authority']['id']['workerResidency']['endpoints'][0] = \
                'worker-c.example:4236'
            fragment['node']['authorityRef'] = fragment['authority']['id']
            mutated_path = self.write_model(root, model)
            with self.assertRaisesRegex(ValueError, 'independent recomputation'):
                verify_artifact(mutated_path, artifact, exhaustive_limit=4)
            fragment['authority']['id']['workerResidency']['endpoints'][0] = \
                'worker-a.example:4234'
            fragment['authority']['id']['workerResidency']['ftype'] = 'COL'
            fragment['node']['authorityRef'] = fragment['authority']['id']
            mutated_path = self.write_model(root, model)
            with self.assertRaisesRegex(ValueError, 'independent recomputation'):
                verify_artifact(mutated_path, artifact, exhaustive_limit=4)
            fragment['authority']['id']['workerResidency']['ftype'] = 'ROW'

            fragment['authority']['id']['workerResidency']['endpoints'][0] = 'not-an-endpoint'
            fragment['node']['authorityRef'] = fragment['authority']['id']
            damaged = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED', damaged['status'])
            self.assertIn('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID',
                          damaged['projectionContract']['blockers'])

            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            fragment = model['physicalProjection']['variables'][0]['alternatives'][1]
            identity = dict(fragment['authority']['id'])
            identity['layout'] = 'NATIVE_LINEAGE'
            identity['workerResidency'] = {
                'ftype': 'PART', 'endpoints': ['worker-a.example:4234'],
                'layoutExact': False}
            fragment['authority']['id'] = identity
            fragment['node']['authorityRef'] = identity
            damaged = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED', damaged['status'])
            self.assertIn('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID',
                          damaged['projectionContract']['blockers'])

    def test_unknown_native_assignment_cannot_be_complete_even_with_exhaustive_decoder(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = build(self.write_model(root, self.add_projection(self.model())),
                           exhaustive_limit=4)
            self.assertEqual('INCOMPLETE_UNKNOWN', result['nativeRelationStatus'])
            self.assertEqual('BLOCKED', result['status'])
            self.assertEqual('BLOCKED_UNKNOWN_NATIVE_ACCEPTANCE',
                             result['physicalImageStatus'])
            self.assertNotIn('physicalImage', result)

    def test_typed_opcode_and_source_lineage_must_match_frozen_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            fragment = model['physicalProjection']['variables'][0]['alternatives'][0]
            fragment['node']['opcode'] = 'CORRUPTED_BUT_TYPED'
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED_NONCOMPOSITIONAL', result['physicalProjectionStatus'])
            self.assertEqual('BLOCKED', result['status'])

            model = self.add_projection(self.model())
            model['factors'][0]['truth'][2] = 'REJECT'
            fragment = model['physicalProjection']['variables'][0]['alternatives'][0]
            identity = dict(fragment['authority']['id'])
            identity['layout'] = 'SOURCE_LINEAGE'
            fragment['authority']['id'] = identity
            fragment['node']['authorityRef'] = identity
            result = build(self.write_model(root, model), exhaustive_limit=4)
            self.assertEqual('BLOCKED_NONCOMPOSITIONAL', result['physicalProjectionStatus'])
            self.assertEqual('BLOCKED', result['status'])

if __name__ == '__main__':
    unittest.main()
