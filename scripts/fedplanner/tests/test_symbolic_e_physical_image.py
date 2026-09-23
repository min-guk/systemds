import gzip
import json
from itertools import product
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from boolean_mdd_relation import validate_artifact
from exact_e_physical_relation import compose_physical_projection, factor_status
import symbolic_e_physical_image as symbolic
from symbolic_e_physical_image import (build, digest, publish, read_artifact,
                                       verify_artifact,
                                       _enumerate_definitely_accepted)


def alternative(name, output='LOUT'):
    return {'signature': name, 'state': 'CP/%s/-/SHAPE_INDEPENDENT' % output,
            'authorityKind': 'CAPTURED_RULE', 'candidateRule': None,
            'candidateEmission': None, 'executionRule': None,
            'executionEmission': None, 'realization': None,
            'supportClause': None, 'relocation': None, 'derivedFout': None,
            'inputAuthorities': []}


def owner(name):
    return {'sourceOrigin': name, 'functionNamespace': '', 'callSitePath': '',
            'recompileContext': '', 'emittedInstance': name,
            'controlRegion': {'functionNamespace': '', 'regionPath': '',
                              'callSitePath': '', 'recompileContext': ''}}


def fragment(name, suffix, output='LOUT', bindings=(), actions=(), geometry=()):
    occurrence = owner(name)
    authority_id = {'owner': occurrence, 'kind': 'CANDIDATE', 'layout': 'BOUNDARY'}
    return {'node': {'occurrence': occurrence, 'opcode': name, 'exec': 'CP',
                     'output': output, 'ftype': 'NONE', 'shapeDependent': False,
                     'executionFType': 'NONE', 'valueVersion': {'id': suffix},
                     'authorityRef': authority_id},
            'authority': {'id': authority_id, 'source': 'CANDIDATE',
                          'owner': occurrence, 'kind': 'CANDIDATE'},
            'actions': list(actions), 'geometry': list(geometry),
            'bindings': list(bindings)}


class SymbolicEPhysicalImageTest(unittest.TestCase):
    @staticmethod
    def resign(value):
        payload = {name: item for name, item in value.items()
                   if name != 'envelopeSha256'}
        value['envelopeSha256'] = digest(payload)
        return value['envelopeSha256']

    @staticmethod
    def resign_relation(value):
        relation = value['relation']
        payload = {name: relation[name] for name in
                   ('schema', 'variables', 'roots', 'nodes')}
        relation['artifactSha256'] = digest(payload)

    def model(self):
        bind = {'consumer': owner('b'), 'inputPosition': 0, 'presence': 'PRESENT',
                'ftype': 'NONE', 'mode': 'DIRECT_OR_FOUT', 'producerDomain': 0}
        action_id = {'kind': 'RELOCATION', 'value': 'move-a-to-b'}
        relocation_bind = dict(bind, mode='RELOCATION', actionRef=action_id)
        relocation = {'kind': 'RELOCATION', 'id': action_id, 'owner': owner('b')}
        a0, a1 = fragment('a', 'a0'), fragment('a', 'a1', output='FOUT')
        b0 = fragment('b', 'b0', bindings=[bind])
        b1 = fragment('b', 'b1', bindings=[relocation_bind], actions=[relocation],
                      geometry=[{'worker': 'worker-a.example:4234',
                                 'ranges': [[0, 9], [0, 4]],
                                 'owner': owner('b'), 'ftype': 'ROW'}])
        return {
            'schema': 'closed-e-native-model-artifact-v1',
            'acceptance': 'MATERIALIZED_FACTOR_TABLES', 'cell': 'toy',
            'programSha256': 'p', 'conditionSha256': 'c', 'sourceFiles': [],
            'domains': [
                {'index': 0, 'occurrence': 'a', 'nodeKind': 'OPERATION',
                 'alternatives': [alternative('a0'), alternative('a1', 'FOUT')]},
                {'index': 1, 'occurrence': 'b', 'nodeKind': 'OPERATION',
                 'alternatives': [alternative('b0'), alternative('b1')]},
            ],
            'factors': [{'scope': [0, 1], 'cells': '4',
                         'truth': ['ALLOW', 'REJECT', 'REJECT', 'ALLOW']}],
            'sourceIdentity': {
                'nodes': [{'occurrence': 'a', 'operation': 'a'},
                          {'occurrence': 'b', 'operation': 'b'}],
                'orderedInputs': [{'consumer': 'b', 'producer': 'a',
                                   'inputPosition': 0}],
                'logicalInputs': [], 'physicalLogicalInputs': []},
            'physicalProjectionContract':
                'EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL',
            'physicalProjection': {
                'schema': 'exact-physical-compositional-projection-v1',
                'logicalProgram': 'p', 'logicalInputs': [], 'nodeOrder': [0, 1],
                'bindingDomainOrder': [1],
                'variables': [
                    {'domain': 0, 'occurrence': owner('a'),
                     'alternatives': [a0, a1]},
                    {'domain': 1, 'occurrence': owner('b'),
                     'alternatives': [b0, b1]}]},
        }

    def write_model(self, root, model):
        path = root / 'model.json.gz'
        with gzip.open(path, 'wt', encoding='utf-8') as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    def accepted_plans(self, model):
        result = []
        radices = [len(domain['alternatives']) for domain in model['domains']]
        for assignment in product(*(range(radix) for radix in radices)):
            if all(factor_status(tuple(row['scope']), row['truth'], assignment, radices)
                   == 'ALLOW' for row in model['factors']):
                result.append(compose_physical_projection(model, assignment))
        return result

    def test_exact_image_matches_exhaustive_toy_and_preserves_correlations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            result = build(self.write_model(root, model))
            self.assertEqual('BLOCKED', result['status'])
            self.assertEqual('DIAGNOSTIC_COMPLETE', result['diagnosticStatus'])
            self.assertEqual('2', result['counts']['definitelyAcceptedNativeAssignments'])
            self.assertEqual('2', result['counts']['uniqueDefinitelyAcceptedOutputs'])
            self.assertEqual('BLOCKED_INDEPENDENT_SEMANTIC_BINDING',
                             result['semanticBindingStatus'])
            validated = validate_artifact(result['relation'])
            self.assertIn('definitelyAcceptedImage', validated.roots)
            self.assertIn('acceptedTypedProjection', validated.roots)
            self.assertNotIn('typedDecoder', validated.roots)
            self.assertEqual({'visitedPrefixes': '7', 'prunedPrefixes': '2'},
                             result['prefixEnumeration'])

            plans = self.accepted_plans(model)
            expected_outputs = {
                tuple('o_' + digest(plan[coordinate])
                      for coordinate in result['coordinates'])
                for plan in plans}
            output_variables = validated.variables[len(model['domains']):]
            for indices in product(*(range(len(variable.values))
                                     for variable in output_variables)):
                tokens = tuple(variable.values[index]
                               for variable, index in zip(output_variables, indices))
                actual = validated.evaluate(
                    'definitelyAcceptedImage',
                    (0,) * len(model['domains']) + indices)
                self.assertEqual(tokens in expected_outputs, actual)

            pairs = {(plan['bindings'][0]['inputAuthority'],
                      tuple(row['worker'] for row in plan['geometry']))
                     for plan in plans}
            self.assertEqual({('DIRECT', ()),
                              ('RELOCATION', ('worker-a.example:4234',))}, pairs)
            relocated = next(plan for plan in plans if plan['actions'])
            self.assertEqual(relocated['actions'][0]['id'],
                             relocated['bindings'][0]['actionRef'])

    def test_optional_p_comparison_is_directional_and_suppresses_unverified_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            plans = self.accepted_plans(model)
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': plans}), encoding='utf-8')
            result = build(self.write_model(root, model), p_path)
            self.assertEqual('COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE',
                             result['comparison']['status'])
            self.assertNotIn('EQUAL', result['comparison']['status'])
            self.assertEqual('UNVERIFIED_PROVIDED_ROWS',
                             result['comparison']['providedPCompletenessStatus'])
            self.assertEqual(['P_IMAGE_COMPLETENESS_UNVERIFIED'],
                             result['comparison']['matchSuppressedReasons'])
            self.assertEqual('0', result['comparison']['eMinusPCount'])
            self.assertEqual('0', result['comparison']['pMinusECount'])
            validated = validate_artifact(result['relation'])
            self.assertIn('providedPImage', validated.roots)
            self.assertNotIn('pExhaustiveImage', validated.roots)

            p_path.write_text(json.dumps({'rows': plans[:1]}), encoding='utf-8')
            result = build(self.write_model(root, model), p_path)
            self.assertEqual('PROVIDED_IMAGES_DIFFER_DIAGNOSTIC_ONLY',
                             result['comparison']['status'])
            self.assertEqual('1', result['comparison']['eMinusPCount'])
            self.assertIsNotNone(result['comparison']['eMinusPWitness'])

    def test_unknown_acceptance_is_explicit_and_cannot_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model['factors'][0]['truth'][1] = 'UNKNOWN'
            result = build(self.write_model(root, model))
            self.assertEqual('INCOMPLETE_UNKNOWN', result['nativeAcceptanceStatus'])
            self.assertEqual('1', result['counts']['unknownNativeAssignments'])
            self.assertEqual('BLOCKED', result['status'])

    def test_unknown_acceptance_suppresses_matching_p_projection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model['factors'][0]['truth'][1] = 'UNKNOWN'
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': self.accepted_plans(model)}),
                              encoding='utf-8')
            result = build(self.write_model(root, model), p_path)
            self.assertEqual('COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE',
                             result['comparison']['status'])
            self.assertEqual(['E_NATIVE_ACCEPTANCE_HAS_UNKNOWN',
                              'P_IMAGE_COMPLETENESS_UNVERIFIED'],
                             result['comparison']['matchSuppressedReasons'])

    def test_partial_coordinate_comparison_is_explicitly_scoped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': self.accepted_plans(model)}),
                              encoding='utf-8')
            result = build(self.write_model(root, model), p_path,
                           coordinates=('nodes', 'bindings'))
            comparison = result['comparison']
            self.assertEqual('PARTIAL_PHYSICAL_COORDINATES',
                             comparison['coordinateScope'])
            self.assertEqual('COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE',
                             comparison['status'])
            self.assertIn('PARTIAL_PHYSICAL_COORDINATES',
                          comparison['matchSuppressedReasons'])

    def test_typed_decoder_mutation_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model['physicalProjection']['variables'][0]['alternatives'][0][
                'node']['opcode'] = 'MUTATED'
            result = build(self.write_model(root, model))
            self.assertEqual('DIAGNOSTIC_BLOCKED_INPUT', result['diagnosticStatus'])
            self.assertIn('COMPOSITIONAL_PROJECTION_PROGRAM_INVALID',
                          result['blockers'])
            self.assertNotIn('relation', result)

    def test_valid_typed_output_mutation_changes_exact_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            before = build(self.write_model(root, model))
            fragment = model['physicalProjection']['variables'][1]['alternatives'][1]
            fragment['geometry'].append({'worker': 'worker-b.example:4235',
                                         'ranges': [[10, 19], [0, 4]],
                                         'owner': owner('b'), 'ftype': 'ROW'})
            after = build(self.write_model(root, model))
            self.assertNotEqual(before['relation']['artifactSha256'],
                                after['relation']['artifactSha256'])

    def test_prefix_pruning_matches_exhaustive_truth_and_only_composes_accepted(self):
        radices = [2, 3, 2]
        tables = [((0, 2), ('ALLOW', 'REJECT', 'REJECT', 'ALLOW')),
                  ((1,), ('REJECT', 'ALLOW', 'UNKNOWN'))]
        expected = [assignment for assignment in product(
            *(range(radix) for radix in radices))
            if all(factor_status(scope, truth, assignment, radices) == 'ALLOW'
                   for scope, truth in tables)]
        actual, evidence = _enumerate_definitely_accepted(
            radices, tables, 100, 100)
        self.assertEqual(expected, actual)
        self.assertLess(int(evidence['visitedPrefixes']), 1 + 2 + 6 + 12)

        mutated = [tables[0], ((1,), ('ALLOW', 'ALLOW', 'UNKNOWN'))]
        changed, _ = _enumerate_definitely_accepted(radices, mutated, 100, 100)
        self.assertNotEqual(actual, changed)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            original = symbolic._compose_validated_projection
            with patch.object(symbolic, '_compose_validated_projection',
                              wraps=original) as compose:
                result = build(self.write_model(root, model))
            self.assertEqual(2, compose.call_count)
            self.assertEqual('2',
                             result['counts']['definitelyAcceptedNativeAssignments'])

    def test_explicit_prefix_factor_and_mdd_budgets_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.write_model(root, self.model())
            visited = build(path, max_visited_prefixes=3)
            self.assertEqual('DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT',
                             visited['diagnosticStatus'])
            self.assertIn('VISITED_PREFIX_BUDGET_EXHAUSTED', visited['blockers'])
            accepted = build(path, max_accepted_assignments=1)
            self.assertIn('ACCEPTED_ASSIGNMENT_BUDGET_EXHAUSTED',
                          accepted['blockers'])
            factor = build(path, max_factor_cells=3)
            self.assertIn('FACTOR_CELL_BUDGET_EXHAUSTED', factor['blockers'])
            bag = build(path, max_bag_cells=3)
            self.assertIn('FACTOR_ELIMINATION_BAG_BUDGET_EXHAUSTED', bag['blockers'])
            mdd = build(path, max_nodes=1)
            self.assertEqual('DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT',
                             mdd['diagnosticStatus'])
            self.assertIn('MDD_NODE_OR_APPLY_BUDGET_EXHAUSTED', mdd['blockers'])

    def test_artifact_only_verify_binds_full_envelope_model_and_p_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model_path = self.write_model(root, model)
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': self.accepted_plans(model)}),
                              encoding='utf-8')
            result = build(model_path, p_path)
            artifact_path = root / 'image.json.gz'
            publish(artifact_path, result)
            receipt = verify_artifact(artifact_path, model_path, p_path,
                                      result['envelopeSha256'])
            self.assertEqual('PASS', receipt['status'])
            self.assertEqual(
                'IMMUTABLY_COMMITTED_ARTIFACT_AND_BOUNDED_RELATION_REPLAY_ONLY',
                             receipt['claimScope'])

            with self.assertRaisesRegex(ValueError, 'commitment is required'):
                verify_artifact(artifact_path, model_path, p_path)

            damaged = read_artifact(artifact_path)
            damaged['counts']['rawNativeAssignments'] = '999'
            publish(artifact_path, damaged)
            with self.assertRaisesRegex(ValueError, 'envelope digest mismatch'):
                verify_artifact(artifact_path, model_path, p_path,
                                result['envelopeSha256'])

    def test_artifact_only_verify_rejects_resigned_dictionary_and_input_mutations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model_path = self.write_model(root, model)
            plans = self.accepted_plans(model)
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': plans}), encoding='utf-8')
            artifact_path = root / 'image.json.gz'
            committed = build(model_path, p_path)
            publish(artifact_path, committed)

            damaged = read_artifact(artifact_path)
            key = next(iter(damaged['valueDictionary']))
            damaged['valueDictionary'][key] = {'mutated': True}
            expected = self.resign(damaged)
            publish(artifact_path, damaged)
            with self.assertRaisesRegex(ValueError, 'dictionary token mismatch'):
                verify_artifact(artifact_path, model_path, p_path, expected)

            committed = build(model_path, p_path)
            publish(artifact_path, committed)
            damaged = read_artifact(artifact_path)
            damaged['counts']['definitelyAcceptedNativeAssignments'] = '3'
            expected = self.resign(damaged)
            publish(artifact_path, damaged)
            with self.assertRaisesRegex(ValueError, 'factor counts are inconsistent'):
                verify_artifact(artifact_path, model_path, p_path, expected)

            committed = build(model_path, p_path)
            publish(artifact_path, committed)
            damaged = read_artifact(artifact_path)
            damaged['comparison']['status'] = 'SETS_MATCH_DIAGNOSTIC_ONLY'
            expected = self.resign(damaged)
            publish(artifact_path, damaged)
            with self.assertRaisesRegex(ValueError, 'verdict is inconsistent'):
                verify_artifact(artifact_path, model_path, p_path, expected)

            committed = build(model_path, p_path)
            publish(artifact_path, committed)
            p_path.write_text(json.dumps({'rows': plans[:1]}), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'provided P input binding mismatch'):
                verify_artifact(artifact_path, model_path, p_path,
                                committed['envelopeSha256'])

            p_path.write_text(json.dumps({'rows': plans}), encoding='utf-8')
            model['programSha256'] = 'mutated-program'
            self.write_model(root, model)
            with self.assertRaisesRegex(ValueError, 'model binding mismatch'):
                verify_artifact(artifact_path, model_path, p_path,
                                committed['envelopeSha256'])

    def test_artifact_verify_rejects_resigned_claim_and_relation_substitution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = self.write_model(root, self.model())
            plans = self.accepted_plans(self.model())
            p_path = root / 'p.json'
            p_path.write_text(json.dumps({'rows': plans}), encoding='utf-8')
            artifact_path = root / 'image.json.gz'
            original = build(model_path, p_path)

            certified = json.loads(json.dumps(original))
            certified['semanticBindingStatus'] = 'CERTIFIED'
            certified['blockers'] = []
            expected = self.resign(certified)
            publish(artifact_path, certified)
            with self.assertRaisesRegex(ValueError, 'claim shape is invalid'):
                verify_artifact(artifact_path, model_path, p_path, expected)

            empty = json.loads(json.dumps(original))
            empty['relation']['roots'] = {
                name: 'F' for name in empty['relation']['roots']}
            empty['relation']['nodes'] = []
            self.resign_relation(empty)
            empty['counts']['uniqueDefinitelyAcceptedOutputs'] = '0'
            empty['comparison'].update({
                'status': 'COMPARISON_INCONCLUSIVE_INCOMPLETE_EVIDENCE',
                'matchSuppressedReasons': ['P_IMAGE_COMPLETENESS_UNVERIFIED'],
                'eMinusPCount': '0', 'pMinusECount': '0',
                'eMinusPWitness': None, 'pMinusEWitness': None})
            expected = self.resign(empty)
            publish(artifact_path, empty)
            with self.assertRaisesRegex(ValueError, 'relation replay differs'):
                verify_artifact(artifact_path, model_path, p_path, expected)

            false_difference = json.loads(json.dumps(original))
            false_difference['relation']['roots']['eMinusP'] = \
                false_difference['relation']['roots']['definitelyAcceptedImage']
            self.resign_relation(false_difference)
            false_difference['comparison'].update({
                'status': 'PROVIDED_IMAGES_DIFFER_DIAGNOSTIC_ONLY',
                'matchSuppressedReasons': [], 'eMinusPCount': '999',
                'eMinusPWitness': {'nodes': 'fabricated'}})
            expected = self.resign(false_difference)
            publish(artifact_path, false_difference)
            with self.assertRaisesRegex(ValueError, 'relation replay differs'):
                verify_artifact(artifact_path, model_path, p_path, expected)


if __name__ == '__main__':
    unittest.main()
