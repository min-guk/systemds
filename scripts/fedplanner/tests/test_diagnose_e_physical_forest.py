import copy
import gzip
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from diagnose_e_physical_forest import (
    _reduce_terms,
    build,
    publish,
    read_saved,
    translate_physical_forest,
    verify_saved,
)
from factor_forest_image import materialize_factor_forest


def alternative(name, output="LOUT"):
    return {"signature": name,
            "state": "CP/%s/-/SHAPE_INDEPENDENT" % output,
            "authorityKind": "CAPTURED_RULE", "candidateRule": None,
            "candidateEmission": None, "executionRule": None,
            "executionEmission": None, "realization": None,
            "supportClause": None, "relocation": None, "derivedFout": None,
            "inputAuthorities": []}


def owner(name):
    return {"sourceOrigin": name, "functionNamespace": "",
            "callSitePath": "", "recompileContext": "",
            "emittedInstance": name,
            "controlRegion": {"functionNamespace": "", "regionPath": "",
                              "callSitePath": "", "recompileContext": ""}}


def fragment(name, suffix, output="LOUT", bindings=()):
    occurrence = owner(name)
    identity = {"owner": occurrence, "kind": "CANDIDATE",
                "layout": "BOUNDARY"}
    return {"node": {"occurrence": occurrence, "opcode": name,
                     "exec": "CP", "output": output, "ftype": "NONE",
                     "shapeDependent": False, "executionFType": "NONE",
                     "valueVersion": {"id": suffix},
                     "authorityRef": identity},
            "authority": {"id": identity, "source": "CANDIDATE",
                          "owner": occurrence, "kind": "CANDIDATE"},
            "actions": [], "geometry": [], "bindings": list(bindings)}


def domain(index, name, alternatives):
    return {"index": index, "occurrence": name, "nodeKind": "OPERATION",
            "alternatives": alternatives}


class EPhysicalForestDiagnosticTest(unittest.TestCase):
    def write_model(self, root, model):
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    def model(self, singleton=False):
        a_alternatives = [alternative("a0")]
        a_fragments = [fragment("a", "a0")]
        if not singleton:
            a_alternatives.append(alternative("a1", "FOUT"))
            a_fragments.append(fragment("a", "a1", "FOUT"))
        b_alternatives = [alternative("b0"), alternative("b1")]
        binding = {"consumer": owner("b"), "inputPosition": 0,
                   "presence": "PRESENT", "ftype": "NONE",
                   "mode": "DIRECT_OR_FOUT", "producerDomain": 0}
        b_fragments = [fragment("b", "b0", bindings=[binding]),
                       fragment("b", "b1", bindings=[binding])]
        a_radix = len(a_alternatives)
        truth = (["ALLOW", "REJECT"] if singleton else
                 ["ALLOW", "REJECT", "ALLOW", "ALLOW"])
        return {
            "schema": "closed-e-native-model-artifact-v2",
            "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "tiny",
            "programSha256": "program", "conditionSha256": "condition",
            "sourceFiles": [],
            "domains": [domain(0, "a", a_alternatives),
                        domain(1, "b", b_alternatives)],
            "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
            "nativeFactorCount": 1, "materializedFactorCount": 1,
            "sourceFactorScopes": [[0, 1]],
            "nativeFactorCells": str(a_radix * 2),
            "materializedFactorCells": str(a_radix * 2),
            "factors": [{"sourceFactorIndices": [0], "scope": [0, 1],
                         "cells": str(a_radix * 2), "truth": truth}],
            "sourceIdentity": {
                "nodes": [{"occurrence": "a", "operation": "a"},
                          {"occurrence": "b", "operation": "b"}],
                "orderedInputs": [{"consumer": "b", "producer": "a",
                                   "inputPosition": 0}],
                "logicalInputs": [], "physicalLogicalInputs": []},
            "physicalProjectionContract":
                "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL",
            "physicalProjection": {
                "schema": "exact-physical-compositional-projection-v1",
                "logicalProgram": "program", "logicalInputs": [],
                "nodeOrder": [0, 1], "bindingDomainOrder": [1],
                "variables": [
                    {"domain": 0, "occurrence": owner("a"),
                     "alternatives": a_fragments},
                    {"domain": 1, "occurrence": owner("b"),
                     "alternatives": b_fragments}]},
        }

    def test_exact_atom_equivalences_eliminate_only_native_variables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = build(self.write_model(root, self.model()),
                           max_replay_cells=100_000)
        self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
        self.assertEqual("PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY",
                         result["exhaustiveDifferential"]["status"])
        self.assertEqual("3", result["exhaustiveDifferential"]
                         ["acceptedNativeAssignments"])
        self.assertEqual("BLOCKED", result["status"])
        self.assertIn("INDEPENDENT_JAVA_SEMANTIC_BINDING_UNAVAILABLE",
                      result["blockers"])
        self.assertIn("NOT_A_CANONICAL_DISTINCT_PLAN_COUNT",
                      result["blockers"])
        forest = result["factorForest"]
        native_count = result["translation"]["varyingNativeVariableCount"]
        self.assertEqual(list(range(native_count)), forest["eliminateLevels"])
        self.assertTrue(all(level >= native_count
                            for level in forest["remainingSupport"]))
        atom_names = {"atom:" + token for token in result["atomDictionary"]}
        self.assertEqual(atom_names,
                         {row["name"] for row in forest["relation"]["variables"]
                          if row["name"].startswith("atom:")})

    def test_singleton_domains_are_substituted_in_atom_conditions(self):
        with tempfile.TemporaryDirectory() as directory:
            result = build(self.write_model(
                Path(directory), self.model(singleton=True)),
                max_replay_cells=100_000)
        self.assertEqual(1, result["translation"]["singletonNativeDomainCount"])
        self.assertEqual("PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY",
                         result["exhaustiveDifferential"]["status"])
        forest = result["factorForest"]
        replay = materialize_factor_forest(forest, max_cells=100_000)
        self.assertEqual("1", result["exhaustiveDifferential"]
                         ["acceptedAtomAssignments"])
        self.assertEqual(1, len(replay["acceptedAssignments"]))

    def test_terms_are_canonicalized_and_absorbed_before_factor_build(self):
        rows = [
            ((0, 0),),
            ((0, 0), (1, 1)),
            ((0, 0), (0, 0)),
            ((0, 0), (0, 1)),
        ]
        self.assertEqual(
            ((((0, 0),),), 1),
            _reduce_terms(rows, (2, 2), {0: 0, 1: 1}, 10))
        self.assertEqual(
            (((),), 0),
            _reduce_terms(rows + [()], (2, 2), {0: 0, 1: 1}, 10))

    def test_term_absorption_preparation_obeys_equivalence_check_budget(self):
        rows = [((0, 1),), ((1, 1),),
                ((0, 0), (1, 0), (2, 0))]
        with self.assertRaisesRegex(
                RuntimeError, "atom equivalence check budget exhausted"):
            _reduce_terms(rows, (2, 2, 2), {0: 0, 1: 1, 2: 2}, 1)

    def test_unknown_is_excluded_from_definite_allow_and_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model(singleton=True)
            model["factors"][0]["truth"] = ["UNKNOWN", "ALLOW"]
            result = build(self.write_model(Path(directory), model),
                           max_replay_cells=100_000)
        self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                      result["blockers"])
        self.assertEqual("1", result["exhaustiveDifferential"]
                         ["acceptedNativeAssignments"])
        self.assertEqual("BLOCKED", result["status"])

    def test_unknown_is_reported_when_physical_translation_is_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model(singleton=True)
            model["factors"][0]["truth"] = ["UNKNOWN", "ALLOW"]
            result = build(self.write_model(Path(directory), model), max_atoms=1)
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         result["diagnosticStatus"])
        self.assertIn("PHYSICAL_ATOM_BUDGET_EXHAUSTED", result["blockers"])
        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                      result["blockers"])

    def test_resource_limits_fail_closed_without_partial_relation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), self.model())
            atom_limited = build(path, max_atoms=1)
            node_limited = build(path, max_nodes=1)
            factor_limited = build(
                self.write_model(Path(directory), self.model(singleton=True)),
                max_factor_cells=2)
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         atom_limited["diagnosticStatus"])
        self.assertIsNone(atom_limited["factorForest"])
        self.assertIn("PHYSICAL_ATOM_BUDGET_EXHAUSTED",
                      atom_limited["blockers"])
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         node_limited["diagnosticStatus"])
        self.assertNotIn("relation", node_limited["factorForest"])
        self.assertEqual("NOT_APPLICABLE_BLOCKED_CONSTRUCTION",
                         node_limited["exhaustiveDifferential"]["status"])
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         factor_limited["diagnosticStatus"])
        self.assertIsNone(factor_limited["factorForest"])
        self.assertIn("BOOLEAN_FACTOR_CELL_BUDGET_EXHAUSTED",
                      factor_limited["blockers"])

    def test_equivalence_check_budget_blocks_before_truth_table_allocation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), self.model(singleton=True))
            with patch("diagnose_e_physical_forest._equivalence_factor") as factor:
                result = build(path, max_equivalence_checks=1)
        factor.assert_not_called()
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         result["diagnosticStatus"])
        self.assertIsNone(result["factorForest"])
        self.assertIn("ATOM_EQUIVALENCE_CHECK_BUDGET_EXHAUSTED",
                      result["blockers"])
        self.assertEqual(1, result["budgets"]["maxEquivalenceChecks"])
        self.assertGreater(int(result["translation"]["atomEquivalenceChecks"]),
                           result["budgets"]["maxEquivalenceChecks"])

    def test_equivalence_check_budget_also_bounds_term_reduction(self):
        rows = [((0, 1),), ((1, 1),), ((0, 0), (1, 0))]
        compiled = ({"x": {}}, {"x": rows})
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), self.model())
            with patch("diagnose_e_physical_forest.compile_atoms",
                       return_value=compiled), patch(
                           "diagnose_e_physical_forest._equivalence_factor"
                       ) as factor:
                result = build(path, max_equivalence_checks=1)
        factor.assert_not_called()
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         result["diagnosticStatus"])
        self.assertIsNone(result["factorForest"])
        self.assertIn("ATOM_EQUIVALENCE_CHECK_BUDGET_EXHAUSTED",
                      result["blockers"])

    def test_prior_atom_factor_checks_reduce_next_reduction_budget(self):
        rows = [((0, 1),), ((1, 1),), ((0, 0), (1, 0))]
        compiled = ({"a": {}, "b": {}},
                    {"a": [((0, 0),)], "b": rows})
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), self.model())
            with patch("diagnose_e_physical_forest.compile_atoms",
                       return_value=compiled), patch(
                           "diagnose_e_physical_forest._reduce_terms",
                           wraps=_reduce_terms) as reducer:
                result = build(path, max_equivalence_checks=5)
        self.assertEqual(2, reducer.call_count)
        self.assertEqual(1, reducer.call_args_list[1].args[-1])
        self.assertEqual("6", result["translation"]
                         ["atomEquivalenceChecks"])
        self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                         result["diagnosticStatus"])
        self.assertIn("ATOM_EQUIVALENCE_CHECK_BUDGET_EXHAUSTED",
                      result["blockers"])

    def test_published_checks_include_reduction_on_success_and_factor_block(self):
        rows = [((0, 1),), ((0, 0), (1, 0))]
        compiled = ({"x": {}}, {"x": rows})
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_model(Path(directory), self.model())
            with patch("diagnose_e_physical_forest.compile_atoms",
                       return_value=compiled):
                complete = translate_physical_forest(path)
                blocked = translate_physical_forest(
                    path, max_factor_cells=4)
        self.assertEqual("COMPLETE", complete["physicalTranslationStatus"])
        self.assertEqual(26, complete["atomEquivalenceChecks"])
        self.assertEqual("BLOCKED_RESOURCE_LIMIT",
                         blocked["physicalTranslationStatus"])
        self.assertEqual(2, blocked["atomEquivalenceChecks"])
        self.assertIn("BOOLEAN_FACTOR_CELL_BUDGET_EXHAUSTED",
                      blocked["physicalBlockers"])

    def test_invalid_projection_fails_closed_before_atom_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model()
            model["physicalProjection"]["variables"][0]["alternatives"][0] \
                ["node"]["opcode"] = "mutated"
            result = build(self.write_model(Path(directory), model))
        self.assertEqual("DIAGNOSTIC_BLOCKED_INPUT", result["diagnosticStatus"])
        self.assertIsNone(result["factorForest"])
        self.assertIn("COMPOSITIONAL_PROJECTION_PROGRAM_INVALID",
                      result["blockers"])

    def test_saved_artifact_replay_and_mutation_detection(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = self.write_model(root, self.model(singleton=True))
            result = build(model_path, max_replay_cells=100_000)
            artifact = root / "diagnostic.json.gz"
            publish(artifact, result)
            file_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
            checked = verify_saved(model_path, artifact, file_sha)
            self.assertEqual("PASS", checked["status"])
            self.assertEqual("VERIFIED_EXHAUSTIVE",
                             checked["forestSemanticReplayStatus"])
            with self.assertRaisesRegex(ValueError, "exceeds verifier cap"):
                verify_saved(model_path, artifact, max_nodes=1)
            with self.assertRaisesRegex(ValueError, "maxEquivalenceChecks"):
                verify_saved(model_path, artifact, max_equivalence_checks=1)

            damaged = copy.deepcopy(read_saved(artifact))
            damaged["blockers"].append("MUTATED")
            publish(artifact, damaged)
            with self.assertRaisesRegex(ValueError, "commitment mismatch"):
                verify_saved(model_path, artifact)

    def test_artifact_read_budget_is_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = self.write_model(root, self.model(singleton=True))
            artifact = root / "diagnostic.json.gz"
            publish(artifact, build(model_path))
            with self.assertRaisesRegex(ValueError, "compressed-byte budget"):
                read_saved(artifact, max_compressed_bytes=1)
            with self.assertRaisesRegex(ValueError, "decoded-byte budget"):
                read_saved(artifact, max_decoded_bytes=1)


if __name__ == "__main__":
    unittest.main()
