import copy
import gzip
import hashlib
import json
from itertools import product
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from diagnose_e_physical_forest import translate_physical_forest
from e_target_preimage import (_canonical_static, compile_target_preimage)
from exact_e_physical_relation import compose_physical_projection
from physical_coordinate_contract import PhysicalCoordinateCodec


CALIBRATION_ROOT = Path("/home/mchoi/cofee-evaluation")
REAL_P2_MODEL = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
    "current-pe-campaign-v12-ledger-aware/e-models/"
    "cell_00d1aa1ca27bce14d826/e-model.json.gz")


def owner(name):
    region = {"functionNamespace": "main", "regionPath": ["main"],
              "callSitePath": "main", "recompileContext": "base"}
    return {"sourceOrigin": name, "functionNamespace": "main",
            "callSitePath": "main", "recompileContext": "base",
            "emittedInstance": name, "controlRegion": region}


def alternative(name, output="LOUT"):
    return {"signature": name, "state": "CP/%s/-/SHAPE_INDEPENDENT" % output,
            "authorityKind": "CAPTURED_RULE", "candidateRule": None,
            "candidateEmission": None, "executionRule": None,
            "executionEmission": None, "realization": None,
            "supportClause": None, "relocation": None, "derivedFout": None,
            "inputAuthorities": []}


def fragment(name, suffix, *, output="LOUT", actions=(), geometry=(), bindings=()):
    occurrence = owner(name)
    identity = {"owner": occurrence, "kind": "LOCAL", "layout": "LOCAL"}
    return {
        "node": {"occurrence": occurrence, "opcode": name,
                 "exec": "CP", "output": output, "ftype": "NONE",
                 "shapeDependent": False, "executionFType": "NONE",
                 "valueVersion": {"lexicalVariable": name,
                                  "definitionOrdinal": int(suffix[-1]),
                                  "versionKind": "ROOT",
                                  "definingControlRegion": occurrence["controlRegion"],
                                  "predecessorVersions": []},
                 "authorityRef": identity},
        "authority": {"id": identity, "source": "fixture",
                      "owner": occurrence, "kind": "LOCAL"},
        "actions": list(actions), "geometry": list(geometry),
        "bindings": list(bindings),
    }


def domain(index, name):
    return {"index": index, "occurrence": name, "nodeKind": "OPERATION",
            "alternatives": [alternative(name + "0"),
                             alternative(name + "1", "FOUT" if name == "a"
                                         else "LOUT")]}


def action(kind):
    return {"id": {"kind": kind, "owner": owner("a")},
            "kind": "DERIVED_FOUT", "owner": owner("a"),
            "geometry": {"begin": [0, 0], "end": [4, 4]}}


def geometry(worker):
    return {"owner": owner("a"), "worker": worker,
            "ranges": [{"begin": [0, 0], "end": [2, 4]},
                       {"begin": [2, 0], "end": [4, 4]}],
            "ftype": "ROW", "blocksize": 1024}


def model():
    logical_inputs = [
        {"kind": "TRANSIENT", "source": owner("b"), "target": owner("a"),
         "position": 1},
        {"kind": "TRANSIENT", "source": owner("a"), "target": owner("b"),
         "position": 0},
    ]
    binding = {"consumer": owner("b"), "inputPosition": 0,
               "presence": "PRESENT", "ftype": "NONE",
               "mode": "DIRECT_OR_FOUT", "producerDomain": 0}
    shared_action = action("MATERIALIZE")
    shared_geometry = geometry("worker-a")
    projection = {
        "schema": "exact-physical-compositional-projection-v1",
        "logicalProgram": "program", "logicalInputs": logical_inputs,
        "nodeOrder": [0, 1], "bindingDomainOrder": [1],
        "variables": [
            {"domain": 0, "occurrence": owner("a"), "alternatives": [
                fragment("a", "a0", actions=[shared_action],
                         geometry=[shared_geometry]),
                fragment("a", "a1", output="FOUT")]},
            {"domain": 1, "occurrence": owner("b"), "alternatives": [
                fragment("b", "b0", actions=[shared_action],
                         geometry=[shared_geometry], bindings=[binding]),
                fragment("b", "b1", actions=[action("PREFETCH")],
                         geometry=[geometry("worker-b")], bindings=[binding])]},
        ],
    }
    return {
        "schema": "closed-e-native-model-artifact-v2",
        "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "tiny-preimage",
        "programSha256": "program", "conditionSha256": "condition",
        "sourceFiles": [], "domains": [domain(0, "a"), domain(1, "b")],
        "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
        "nativeFactorCount": 1, "materializedFactorCount": 1,
        "sourceFactorScopes": [[0, 1]], "nativeFactorCells": "4",
        "materializedFactorCells": "4",
        "factors": [{"sourceFactorIndices": [0], "scope": [0, 1],
                     "cells": "4", "truth": ["ALLOW"] * 4}],
        "sourceIdentity": {
            "nodes": [{"occurrence": "a", "operation": "a"},
                      {"occurrence": "b", "operation": "b"}],
            "orderedInputs": [{"consumer": "b", "producer": "a",
                               "inputPosition": 0}],
            "logicalInputs": [], "physicalLogicalInputs": logical_inputs},
        "physicalProjectionContract":
            "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL",
        "physicalProjection": projection,
    }


class ETargetPreimageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.codec = PhysicalCoordinateCodec(CALIBRATION_ROOT)

    def write_model(self, root, value):
        root.mkdir(parents=True, exist_ok=True)
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True)
        return path

    def canonical_plan(self, source_model, assignment):
        plan = compose_physical_projection(source_model, assignment)
        return self.codec.decode(self.codec.encode(plan))

    @staticmethod
    def active_levels(translated, assignment):
        levels = set()
        for token, terms in translated["atomConditions"].items():
            if any(all(assignment[translated["order"][level]] == alternative
                       for level, alternative in term) for term in terms):
                levels.add(translated["atomLevels"][token])
        return levels

    @staticmethod
    def accepts(factors, active):
        for factor in factors:
            offset = 0
            for level in factor["scope"]:
                offset = offset * 2 + int(level in active)
            if not factor["truth"][offset]:
                return False
        return True

    def test_every_tiny_assignment_preimage_matches_full_pinned_codec(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            translated = translate_physical_forest(path)
            plans = {assignment: self.canonical_plan(source, assignment)
                     for assignment in product(range(2), repeat=2)}
            for target_assignment, target in plans.items():
                result = compile_target_preimage(
                    path, target, CALIBRATION_ROOT)
                self.assertEqual("COMPLETE", result["status"], result)
                self.assertEqual("DIAGNOSTIC_ONLY", result["diagnosticStatus"])
                self.assertEqual("UNATTESTED_NORMAL_PYTHON_IMPORT_GRAPH",
                                 result["executionProvenance"])
                self.assertEqual("NOT_ESTABLISHED",
                                 result["claims"]["executableAttestation"])
                self.assertEqual(
                    result["artifactSha256"],
                    hashlib.sha256(json.dumps(
                        {key: value for key, value in result.items()
                         if key != "artifactSha256"}, sort_keys=True,
                        separators=(",", ":"), ensure_ascii=False).encode()).hexdigest())
                self.assertTrue(all(value is not None for key, value in
                                    result["bindings"].items()
                                    if key.endswith("Sha256")))
                factors = result["factorPayload"]["targetFactors"]
                for assignment, plan in plans.items():
                    actual = self.accepts(
                        factors, self.active_levels(translated, assignment))
                    expected = (self.codec.encode(plan) ==
                                self.codec.encode(target))
                    self.assertEqual(expected, actual,
                                     (target_assignment, assignment))

            duplicate_target = plans[(0, 0)]
            self.assertEqual(1, len(duplicate_target["actions"]))
            self.assertEqual(1, len(duplicate_target["geometry"]))
            names = [row["name"] for row in compile_target_preimage(
                path, duplicate_target, CALIBRATION_ROOT)["factorPayload"]
                ["targetFactors"]]
            self.assertTrue(any(name.startswith("require-deduplicated:actions")
                                for name in names))
            self.assertTrue(any(name.startswith("forbid-extra:actions")
                                for name in names))

    def test_mutated_target_distinguishes_unsupported_from_empty_preimage(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            target = self.canonical_plan(source, (0, 0))
            missing_coordinate = copy.deepcopy(target)
            del missing_coordinate["geometry"]
            missing_row = copy.deepcopy(target)
            missing_row["nodes"][0]["opcode"] = "not-in-model"
            static_drift = copy.deepcopy(target)
            static_drift["context"]["logical"] = "other-program"
            noncanonical = copy.deepcopy(target)
            noncanonical["nodes"].reverse()
            results = [compile_target_preimage(path, candidate, CALIBRATION_ROOT)
                       for candidate in (missing_coordinate, missing_row,
                                         static_drift, noncanonical)]
        self.assertIn("TARGET_UNSUPPORTED_OR_INVALID", results[0]["blockers"])
        self.assertEqual("EMPTY_PREIMAGE", results[1]["preimageStatus"])
        self.assertEqual("TARGET_ROW_MISSING_FROM_ATOM_DICTIONARY",
                         results[1]["emptyReason"])
        self.assertEqual([False], results[1]["factorPayload"]
                         ["targetFactors"][0]["truth"])
        self.assertEqual("EMPTY_PREIMAGE", results[2]["preimageStatus"])
        self.assertEqual("STATIC_COORDINATES_DIFFER", results[2]["emptyReason"])
        self.assertIn("TARGET_NOT_CANONICAL", results[3]["blockers"])
        self.assertIsNone(results[0]["factorPayload"])
        self.assertIsNone(results[3]["factorPayload"])

    def test_budgets_and_phi_are_incomplete_without_partial_factors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = model()
            path = self.write_model(root, source)
            target = self.canonical_plan(source, (0, 0))
            row_limited = compile_target_preimage(
                path, target, CALIBRATION_ROOT, max_target_rows=1)
            cell_limited = compile_target_preimage(
                path, target, CALIBRATION_ROOT, max_target_factor_cells=1)
            translation_limited = compile_target_preimage(
                path, target, CALIBRATION_ROOT, max_factor_cells=1)

            phi = model()
            template = phi["physicalProjection"]["variables"][1][
                "alternatives"][0]["bindings"][0]
            template["mode"] = "PHI"
            template.pop("producerDomain")
            template["producerAlternatives"] = [
                {"producerDomain": 0, "controlArm": "then"},
                {"producerDomain": 0, "controlArm": "else"}]
            phi_path = self.write_model(root / "phi", phi)
            phi_result = compile_target_preimage(
                phi_path, target, CALIBRATION_ROOT)
        self.assertIn("TARGET_ROW_BUDGET_EXHAUSTED", row_limited["blockers"])
        self.assertIn("TARGET_FACTOR_CELL_BUDGET_EXHAUSTED",
                      cell_limited["blockers"])
        self.assertIn("BOOLEAN_FACTOR_CELL_BUDGET_EXHAUSTED",
                      translation_limited["blockers"])
        self.assertIn("PHI_UNSUPPORTED", phi_result["blockers"])
        for result in (row_limited, cell_limited, translation_limited,
                       phi_result):
            self.assertEqual("INCOMPLETE", result["status"])
            self.assertIsNone(result["factorPayload"])
            self.assertEqual("NOT_ASSESSED", result["claims"]["satisfiability"])

    def test_unknown_source_blocks_even_when_target_matches_unknown_assignment(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            source["factors"][0]["truth"][0] = "UNKNOWN"
            path = self.write_model(Path(directory), source)
            target = self.canonical_plan(source, (0, 0))
            with patch("e_target_preimage.translate_physical_forest",
                       wraps=translate_physical_forest) as translate:
                result = compile_target_preimage(path, target, CALIBRATION_ROOT)
            translate.assert_called_once()
        self.assertEqual("INCOMPLETE", result["status"])
        self.assertIn("SOURCE_UNKNOWN_NOT_DEFINITELY_ALLOWED",
                      result["blockers"])
        self.assertIsNone(result["factorPayload"])
        self.assertEqual("NOT_ASSESSED", result["claims"]["satisfiability"])

    def test_verifier_integrity_error_is_not_relabelled_as_target_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = model()
            path = self.write_model(Path(directory), source)
            target = self.canonical_plan(source, (0, 0))
            with patch("e_target_preimage.PhysicalCoordinateCodec",
                       side_effect=ValueError(
                           "physical identity verifier module changed within process")):
                with self.assertRaisesRegex(ValueError, "verifier module changed"):
                    compile_target_preimage(path, target, CALIBRATION_ROOT)

    @unittest.skipUnless(REAL_P2_MODEL.is_file(), "real P2 model unavailable")
    def test_real_p2_static_rows_use_pinned_canonical_order(self):
        with gzip.open(REAL_P2_MODEL, "rt", encoding="utf-8") as stream:
            source = json.load(stream)
        assignment = [0] * len(source["domains"])
        target = self.codec.decode(self.codec.encode(
            compose_physical_projection(source, assignment)))
        projection = copy.deepcopy(source["physicalProjection"])
        self.assertGreater(len(projection["logicalInputs"]), 1)
        projection["logicalInputs"].reverse()
        canonical = _canonical_static(projection)
        self.assertEqual(target["context"], canonical["context"])
        self.assertEqual(target["logicalInputs"], canonical["logicalInputs"])
        result = compile_target_preimage(
            REAL_P2_MODEL, target, CALIBRATION_ROOT)
        self.assertEqual("COMPLETE", result["status"], result)
        self.assertEqual("FACTORIZED", result["preimageStatus"])
        self.assertEqual("547", result["factorPayload"]["targetRowCount"])

    def test_target_from_different_node_universe_is_exact_empty_preimage(self):
        def rename(value):
            if isinstance(value, dict):
                return {key: rename(item) for key, item in value.items()}
            if isinstance(value, list):
                return [rename(item) for item in value]
            return {"a": "x", "b": "y"}.get(value, value)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = model()
            path = self.write_model(root, source)
            foreign = rename(model())
            target = self.canonical_plan(foreign, (0, 0))
            result = compile_target_preimage(path, target, CALIBRATION_ROOT)
        self.assertEqual("COMPLETE", result["status"])
        self.assertEqual("EMPTY_PREIMAGE", result["preimageStatus"])
        self.assertEqual("STATIC_COORDINATES_DIFFER", result["emptyReason"])
        self.assertEqual([False], result["factorPayload"]
                         ["targetFactors"][0]["truth"])


if __name__ == "__main__":
    unittest.main()
