import copy
import gzip
import hashlib
from itertools import product
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from boolean_mdd_relation import MDDManager, Variable, validate_artifact
from canonical_e_quotient import (build, materialize_canonical, publish,
                                  verify_saved)
from compositional_e_image import build as build_atom_image
from compositional_e_image import publish as publish_atom_image
from exact_e_physical_relation import compose_physical_projection


def owner(name):
    return {"sourceOrigin": name, "functionNamespace": "", "callSitePath": "",
            "recompileContext": "", "emittedInstance": name,
            "controlRegion": {"functionNamespace": "", "regionPath": "",
                              "callSitePath": "", "recompileContext": ""}}


def native(name):
    return {"signature": name, "state": "CP/LOUT/-/SHAPE_INDEPENDENT",
            "authorityKind": "CAPTURED_RULE", "candidateRule": None,
            "candidateEmission": None, "executionRule": None,
            "executionEmission": None, "realization": None,
            "supportClause": None, "relocation": None, "derivedFout": None,
            "inputAuthorities": []}


def fragment(name, suffix, actions=(), geometry=(), bindings=()):
    occurrence = owner(name)
    identity = {"owner": occurrence, "kind": "CANDIDATE", "layout": "BOUNDARY"}
    return {"node": {"occurrence": occurrence, "opcode": name, "exec": "CP",
                     "output": "LOUT", "ftype": "NONE", "shapeDependent": False,
                     "executionFType": "NONE", "valueVersion": {"id": suffix},
                     "authorityRef": identity},
            "authority": {"id": identity, "source": "CANDIDATE",
                          "owner": occurrence, "kind": "CANDIDATE"},
            "actions": list(actions), "geometry": list(geometry),
            "bindings": list(bindings)}


class CanonicalEQuotientTest(unittest.TestCase):
    def model(self):
        names = ("left", "middle", "right", "phi")
        x_left = {"kind": "DERIVED_FOUT", "id": {"value": "x"},
                  "owner": owner("left")}
        x_right = copy.deepcopy(x_left)
        y = {"kind": "DERIVED_FOUT", "id": {"value": "y"},
             "owner": owner("middle")}
        gx_left = {"worker": "x.example:4040", "ranges": [[0, 1], [0, 2]],
                   "owner": owner("left"), "ftype": "ROW"}
        gx_right = copy.deepcopy(gx_left)
        gy = {"worker": "y.example:4040", "ranges": [[2, 3], [0, 2]],
              "owner": owner("middle"), "ftype": "ROW"}
        variables = [
            {"domain": 0, "occurrence": owner("left"), "alternatives": [
                fragment("left", "left-x", [x_left], [gx_left]),
                fragment("left", "left-none")]},
            {"domain": 1, "occurrence": owner("middle"), "alternatives": [
                fragment("middle", "middle-y", [y], [gy])]},
            {"domain": 2, "occurrence": owner("right"), "alternatives": [
                fragment("right", "right-none"),
                fragment("right", "right-x", [x_right], [gx_right])]},
        ]
        phi = {"consumer": owner("phi"), "inputPosition": 0,
               "presence": "PRESENT", "ftype": "NONE", "mode": "PHI",
               "producerAlternatives": [
                   {"producerDomain": 0, "controlArm": "left"},
                   {"producerDomain": 2, "controlArm": "right"}]}
        variables.append({"domain": 3, "occurrence": owner("phi"),
                          "alternatives": [fragment("phi", "phi", bindings=[phi])]})
        return {"schema": "closed-e-native-model-artifact-v2",
                "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "quotient-toy",
                "programSha256": "program", "conditionSha256": "condition",
                "sourceFiles": [],
                "domains": [
                    {"index": index, "occurrence": name, "nodeKind": "OPERATION",
                     "alternatives": [native(name + "-" + str(choice))
                                      for choice in range(len(variables[index]["alternatives"]))]}
                    for index, name in enumerate(names)],
                "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
                "nativeFactorCount": 0, "materializedFactorCount": 0,
                "sourceFactorScopes": [], "nativeFactorCells": "0",
                "materializedFactorCells": "0", "factors": [],
                "sourceIdentity": {
                    "nodes": [{"occurrence": name, "operation": name}
                              for name in names],
                    "orderedInputs": [
                        {"consumer": "phi", "producer": "left", "inputPosition": 0},
                        {"consumer": "phi", "producer": "right", "inputPosition": 0}],
                    "logicalInputs": [], "physicalLogicalInputs": []},
                "physicalProjectionContract":
                    "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL",
                "physicalProjection": {
                    "schema": "exact-physical-compositional-projection-v1",
                    "logicalProgram": "program", "logicalInputs": [],
                    "nodeOrder": [0, 1, 2, 3], "bindingDomainOrder": [3],
                    "variables": variables}}

    @staticmethod
    def write_model(root, model):
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    def source(self, root):
        model = self.model()
        model_path = self.write_model(root, model)
        source = build_atom_image(model_path)
        self.assertEqual("DIAGNOSTIC_COMPLETE", source["diagnosticStatus"])
        source_path = root / "atom-image.json.gz"
        publish_atom_image(source_path, source)
        return model, source_path

    def test_exact_quotient_matches_reference_with_order_duplicate_absent_and_phi(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, source_path = self.source(root)
            result = build(source_path)
            self.assertEqual("BLOCKED", result["status"])
            self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
            self.assertEqual("BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
                             result["semanticBindingStatus"])
            relation = validate_artifact(result["relation"])
            observed = set()
            radices = [len(variable.values) for variable in relation.variables]
            for assignment in product(*(range(radix) for radix in radices)):
                if relation.evaluate("canonicalPhysicalImage", assignment):
                    observed.add(json.dumps(materialize_canonical(result, assignment),
                                            sort_keys=True, separators=(",", ":")))
            expected = set()
            reference = []
            for assignment in product(range(2), range(1), range(2), range(1)):
                plan = compose_physical_projection(model, assignment)
                reference.append(plan)
                expected.add(json.dumps(plan, sort_keys=True, separators=(",", ":")))
            self.assertEqual(expected, observed)
            self.assertEqual(str(len(expected)),
                             result["counts"]["uniqueCanonicalPhysicalPlans"])
            action_orders = {tuple(row["id"]["value"] for row in plan["actions"])
                             for plan in reference}
            self.assertIn(("x", "y"), action_orders)
            self.assertIn(("y", "x"), action_orders)
            self.assertTrue(all(len(plan["actions"]) == len({json.dumps(row, sort_keys=True)
                                for row in plan["actions"]}) for plan in reference))
            self.assertTrue(any(len(plan["bindings"][0]["producerAlternatives"]) == 2
                                for plan in reference))
            self.assertGreaterEqual(int(result["counts"]["dynamicPrecedenceVariables"]), 2)

    def test_resource_limits_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            _, source_path = self.source(Path(directory))
            result = build(source_path, max_nodes=1)
            self.assertEqual("BLOCKED", result["status"])
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             result["diagnosticStatus"])
            self.assertIn("MDD_NONTERMINAL_NODE_BUDGET_EXHAUSTED",
                          result["blockers"])
            self.assertNotIn("relation", result)

            pairs = build(source_path, max_coordinate_pairs=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             pairs["diagnosticStatus"])
            self.assertIn("CANONICAL_COORDINATE-PAIR_BUDGET_EXHAUSTED",
                          pairs["blockers"])
            occurrences = build(source_path, max_occurrence_comparisons=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             occurrences["diagnosticStatus"])
            self.assertIn("CANONICAL_OCCURRENCE-COMPARISON_BUDGET_EXHAUSTED",
                          occurrences["blockers"])

    def test_forged_native_supported_source_root_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, source_path = self.source(root)
            with gzip.open(source_path, "rt", encoding="utf-8") as stream:
                source = json.load(stream)
            variables = [Variable(row["name"], row["values"])
                         for row in source["relation"]["variables"]]
            manager = MDDManager(variables)
            native_level = next(index for index, variable in enumerate(variables)
                                if variable.name.startswith("native:"))
            forged = manager.to_artifact({
                "definitelyAcceptedAtomImage": manager.literal(native_level, (0,))})
            source["relation"] = forged
            source["rootCommitments"] = forged["roots"]
            publish_atom_image(source_path, source)
            with self.assertRaisesRegex(ValueError, "retains a native variable"):
                build(source_path)

    def test_duplicate_provenance_sets_collapse_to_two_canonical_plans(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            variables = model["physicalProjection"]["variables"]
            variables[0]["alternatives"][1]["node"] = copy.deepcopy(
                variables[0]["alternatives"][0]["node"])
            variables[0]["alternatives"][1]["authority"] = copy.deepcopy(
                variables[0]["alternatives"][0]["authority"])
            variables[2]["alternatives"][1]["node"] = copy.deepcopy(
                variables[2]["alternatives"][0]["node"])
            variables[2]["alternatives"][1]["authority"] = copy.deepcopy(
                variables[2]["alternatives"][0]["authority"])
            variables[1]["alternatives"][0]["actions"] = []
            variables[1]["alternatives"][0]["geometry"] = []
            model_path = self.write_model(root, model)
            source = build_atom_image(model_path)
            self.assertEqual("4", source["counts"][
                "uniqueDefinitelyAcceptedProvenanceAtomSets"])
            source_path = root / "duplicate-atom-image.json.gz"
            publish_atom_image(source_path, source)
            quotient = build(source_path)
            self.assertEqual("2", quotient["counts"]["uniqueCanonicalPhysicalPlans"])

    def test_saved_artifact_requires_external_sha_and_source_bound_rebuild(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, source_path = self.source(root)
            result = build(source_path)
            artifact_path = root / "quotient.json.gz"
            publish(artifact_path, result)
            expected_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
            verification = verify_saved(artifact_path, source_path, expected_sha)
            self.assertEqual("PASS", verification["status"])
            tampered = copy.deepcopy(result)
            tampered["counts"]["uniqueCanonicalPhysicalPlans"] = "999"
            publish(artifact_path, tampered)
            with self.assertRaisesRegex(ValueError, "expected SHA-256"):
                verify_saved(artifact_path, source_path, expected_sha)
            changed_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "source-bound rebuild"):
                verify_saved(artifact_path, source_path, changed_sha)


if __name__ == "__main__":
    unittest.main()
