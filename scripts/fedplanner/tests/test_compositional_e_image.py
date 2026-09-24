import base64
import copy
import gzip
import hashlib
import json
from itertools import product
from pathlib import Path
import random
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from boolean_mdd_relation import MDDManager, Variable, validate_artifact
from compositional_e_image import (build, compile_atoms, materialize_atoms,
                                   publish, verify_saved,
                                   _normalized_factor_truth, _condition_is_total,
                                   _assert_native_projected)
from exact_e_physical_relation import compose_physical_projection, factor_status


def alternative(name, output="LOUT"):
    return {"signature": name, "state": "CP/%s/-/SHAPE_INDEPENDENT" % output,
            "authorityKind": "CAPTURED_RULE", "candidateRule": None,
            "candidateEmission": None, "executionRule": None,
            "executionEmission": None, "realization": None,
            "supportClause": None, "relocation": None, "derivedFout": None,
            "inputAuthorities": []}


def owner(name):
    return {"sourceOrigin": name, "functionNamespace": "", "callSitePath": "",
            "recompileContext": "", "emittedInstance": name,
            "controlRegion": {"functionNamespace": "", "regionPath": "",
                              "callSitePath": "", "recompileContext": ""}}


def fragment(name, suffix, output="LOUT", bindings=(), actions=(), geometry=()):
    occurrence = owner(name)
    authority_id = {"owner": occurrence, "kind": "CANDIDATE", "layout": "BOUNDARY"}
    return {"node": {"occurrence": occurrence, "opcode": name, "exec": "CP",
                     "output": output, "ftype": "NONE", "shapeDependent": False,
                     "executionFType": "NONE", "valueVersion": {"id": suffix},
                     "authorityRef": authority_id},
            "authority": {"id": authority_id, "source": "CANDIDATE",
                          "owner": occurrence, "kind": "CANDIDATE"},
            "actions": list(actions), "geometry": list(geometry),
            "bindings": list(bindings)}


class CompositionalEImageTest(unittest.TestCase):
    def assert_exact_atom_image(self, model, result):
        validated = validate_artifact(result["relation"])
        tokens = tuple(result["atomVariableTokens"])
        radices = [len(domain["alternatives"]) for domain in model["domains"]]
        expected = set()
        for native in product(*(range(radix) for radix in radices)):
            if all(factor_status(tuple(row["scope"]), row["truth"], native,
                                 radices) == "ALLOW"
                   for row in model["factors"]):
                active = self.active_tokens(result, native)
                expected.add(tuple(int(token in active) for token in tokens))
        actual = set()
        for bits in product((0, 1), repeat=len(tokens)):
            by_token = dict(zip(tokens, bits))
            replay = tuple(0 if variable.name.startswith("native:") else
                           by_token[variable.name[5:]]
                           for variable in validated.variables)
            if validated.evaluate("definitelyAcceptedAtomImage", replay):
                actual.add(bits)
        self.assertEqual(expected, actual)

    def test_large_atom_tautology_check_respects_cell_budget(self):
        # This DNF is true, but proving it by enumeration visits 2**30
        # assignments. Keeping the atom dynamic remains an exact fallback.
        rows = [tuple((domain, 1) for domain in range(prefix)) +
                ((prefix, 0),) for prefix in range(30)]
        rows.append(tuple((domain, 1) for domain in range(30)))
        self.assertFalse(_condition_is_total(rows, [2] * 30, 1000))
        self.assertTrue(_condition_is_total(
            (((0, 0),), ((0, 1),)), [2] * 30, 1000))

    def test_tautology_budget_counts_terms_and_comparisons(self):
        rows = (((0, 0),), ((0, 1),))
        self.assertFalse(_condition_is_total(rows, [2], 3))
        self.assertTrue(_condition_is_total(rows, [2], 4))

    def test_disconnected_native_components_have_exact_same_atom_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model["sourceIdentity"]["orderedInputs"] = []
            model["physicalProjection"]["bindingDomainOrder"] = []
            for fragment in model["physicalProjection"]["variables"][1]["alternatives"]:
                fragment["bindings"] = []
            model["factors"] = [
                {"sourceFactorIndices": [0], "scope": [0], "cells": "2",
                 "truth": ["ALLOW", "ALLOW"]},
                {"sourceFactorIndices": [1], "scope": [1], "cells": "2",
                 "truth": ["ALLOW", "ALLOW"]}]
            model["nativeFactorCount"] = 2
            model["materializedFactorCount"] = 2
            model["sourceFactorScopes"] = [[0], [1]]
            model["nativeFactorCells"] = "4"
            model["materializedFactorCells"] = "4"
            path = self.write_model(root, model)
            ordinary = build(path)
            left = validate_artifact(ordinary["relation"])
            tokens = ordinary["atomVariableTokens"]
            for strategy in ("decomposed-components", "terminal-aware-components"):
                decomposed = build(path, image_strategy=strategy)
                self.assertEqual("DIAGNOSTIC_COMPLETE", decomposed["diagnosticStatus"])
                self.assertEqual(2, decomposed["resourceUsage"]["components"])
                self.assertEqual("PER_MDD_MANAGER",
                                 decomposed["resourceBudgetScope"])
                self.assertGreaterEqual(
                    decomposed["resourceUsage"]["peakLiveNodes"],
                    decomposed["resourceUsage"]["liveNodes"])
                self.assertEqual(ordinary["counts"], decomposed["counts"])
                self.assertEqual(set(tokens), set(decomposed["atomVariableTokens"]))
                if strategy == "terminal-aware-components":
                    self.assertEqual(1_000_000,
                                     decomposed["budgets"]["maxOrderOperations"])
                    limited = build(path, image_strategy=strategy,
                                    max_order_operations=1)
                    self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                                     limited["diagnosticStatus"])
                    self.assertIn("TERMINAL-AWARE_OPERATION_BUDGET_EXHAUSTED",
                                  limited["blockers"])
                    artifact = root / "terminal.json.gz"
                    publish(artifact, decomposed)
                    self.assertEqual("PASS", verify_saved(
                        artifact, path,
                        hashlib.sha256(artifact.read_bytes()).hexdigest())["status"])
                right = validate_artifact(decomposed["relation"])
                for bits in product((0, 1), repeat=len(tokens)):
                    active = dict(zip(tokens, bits))
                    left_values = self.relation_assignment(
                        ordinary, left, (0, 0), bits)
                    right_bits = tuple(active[token] for token in
                                       decomposed["atomVariableTokens"])
                    right_values = self.relation_assignment(
                        decomposed, right, (0, 0), right_bits)
                    self.assertEqual(left.evaluate("definitelyAcceptedAtomImage",
                                                   left_values),
                                     right.evaluate("definitelyAcceptedAtomImage",
                                                    right_values))

    def test_cutset_conditioned_image_matches_all_existing_tiny_strategies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            path = self.write_model(root, model)
            results = [build(path, image_strategy=strategy)
                       for strategy in ("partitioned", "decomposed-components",
                                        "terminal-aware-components")]
            cutset = build(path, image_strategy="cutset-conditioned-components",
                           native_cutset=(0,))
            results.append(cutset)
            for result in results:
                self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
                self.assert_exact_atom_image(model, result)
            self.assertEqual({result["counts"][
                "uniqueDefinitelyAcceptedProvenanceAtomSets"] for result in results},
                {"2"})
            self.assertEqual([0], cutset["nativeCutset"])
            self.assertEqual(2, cutset["resourceUsage"]["cutsetAssignments"])
            self.assertEqual("COMPLETE", cutset["constructionSchedule"][
                "conditionedDependencyCoverage"])
            artifact = root / "cutset.json.gz"
            publish(artifact, cutset)
            self.assertEqual("PASS", verify_saved(
                artifact, path,
                hashlib.sha256(artifact.read_bytes()).hexdigest())["status"])

    def test_cutset_handles_impossible_branches_separator_atoms_and_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            model["factors"][0]["truth"] = ["ALLOW", "ALLOW", "REJECT",
                                                   "REJECT"]
            impossible = build(
                self.write_model(root, model),
                image_strategy="cutset-conditioned-components", native_cutset=(0,))
            self.assert_exact_atom_image(model, impossible)
            self.assertEqual(1, impossible["resourceUsage"]["feasibleBranches"])

            model = self.model()
            separator = build(
                self.write_model(root, model),
                image_strategy="cutset-conditioned-components",
                native_cutset=(0, 1))
            self.assert_exact_atom_image(model, separator)
            self.assertTrue(any(branch["forcedPresentAtoms"] or
                                branch["forcedAbsentAtoms"]
                                for branch in separator["constructionSchedule"][
                                    "branches"]))

            model = self.model()
            for variable in model["physicalProjection"]["variables"]:
                variable["alternatives"][1] = copy.deepcopy(
                    variable["alternatives"][0])
            model["domains"][0]["alternatives"][1]["state"] = \
                model["domains"][0]["alternatives"][0]["state"]
            duplicate = build(
                self.write_model(root, model),
                image_strategy="cutset-conditioned-components",
                native_cutset=(0, 1))
            self.assert_exact_atom_image(model, duplicate)
            self.assertEqual("1", duplicate["counts"][
                "uniqueDefinitelyAcceptedProvenanceAtomSets"])

    def test_cutset_treats_singleton_factor_domains_as_constants(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model()
            model["domains"][1]["alternatives"] = \
                model["domains"][1]["alternatives"][:1]
            model["physicalProjection"]["variables"][1]["alternatives"] = \
                model["physicalProjection"]["variables"][1]["alternatives"][:1]
            model["factors"][0]["cells"] = "2"
            model["factors"][0]["truth"] = ["ALLOW", "REJECT"]
            model["nativeFactorCells"] = "2"
            model["materializedFactorCells"] = "2"
            result = build(
                self.write_model(Path(directory), model),
                image_strategy="cutset-conditioned-components", native_cutset=(0,))
            self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
            self.assert_exact_atom_image(model, result)

    def test_random_tiny_cutset_images_match_brute_force_native_image(self):
        generator = random.Random(192837)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for iteration in range(100):
                model = self.model()
                model["cell"] = "random-%d" % iteration
                model["factors"][0]["truth"] = [generator.choice(
                    ("ALLOW", "REJECT", "UNKNOWN")) for _ in range(4)]
                path = self.write_model(root, model)
                result = build(
                    path, image_strategy="cutset-conditioned-components",
                    native_cutset=(generator.randrange(2),))
                self.assertEqual("DIAGNOSTIC_COMPLETE",
                                 result["diagnosticStatus"])
                self.assert_exact_atom_image(model, result)

    def test_native_support_is_checked_not_inferred_from_count_divisibility(self):
        manager = MDDManager([Variable("native:0", ("0", "1")),
                              Variable("native:1", ("0", "1")),
                              Variable("atom:a", ("0", "1"))])
        dependent = manager.from_predicate(lambda assignment:
                                           assignment[2] == assignment[0])
        self.assertEqual(0, manager.count(dependent) % 4)
        with self.assertRaisesRegex(AssertionError, "native dependency"):
            _assert_native_projected(manager.to_artifact({"image": dependent}),
                                     {0: 0, 1: 1})
        projected = manager.exists(dependent, (0, 1))
        _assert_native_projected(manager.to_artifact({"image": projected}),
                                 {0: 0, 1: 1})

    def model(self):
        bind = {"consumer": owner("b"), "inputPosition": 0, "presence": "PRESENT",
                "ftype": "NONE", "mode": "DIRECT_OR_FOUT", "producerDomain": 0}
        action_id = {"kind": "RELOCATION", "value": "move-a-to-b"}
        relocation_bind = dict(bind, mode="RELOCATION", actionRef=action_id)
        relocation = {"kind": "RELOCATION", "id": action_id, "owner": owner("b")}
        a0, a1 = fragment("a", "a0"), fragment("a", "a1", output="FOUT")
        a1["authority"]["id"]["kind"] = "CANDIDATE_FOUT"
        a1["authority"]["kind"] = "CANDIDATE_FOUT"
        a1["node"]["authorityRef"] = a1["authority"]["id"]
        b0 = fragment("b", "b0", bindings=[bind])
        b1 = fragment("b", "b1", bindings=[relocation_bind], actions=[relocation],
                      geometry=[{"worker": "worker-a.example:4234",
                                 "ranges": [[0, 9], [0, 4]],
                                 "owner": owner("b"), "ftype": "ROW"}])
        return {
            "schema": "closed-e-native-model-artifact-v2",
            "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "toy",
            "programSha256": "p", "conditionSha256": "c", "sourceFiles": [],
            "domains": [
                {"index": 0, "occurrence": "a", "nodeKind": "OPERATION",
                 "alternatives": [alternative("a0"), alternative("a1", "FOUT")]},
                {"index": 1, "occurrence": "b", "nodeKind": "OPERATION",
                 "alternatives": [alternative("b0"), alternative("b1")]},
            ],
            "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
            "nativeFactorCount": 1, "materializedFactorCount": 1,
            "sourceFactorScopes": [[0, 1]], "nativeFactorCells": "4",
            "materializedFactorCells": "4",
            "factors": [{"sourceFactorIndices": [0], "scope": [0, 1], "cells": "4",
                         "truth": ["ALLOW", "REJECT", "REJECT", "ALLOW"]}],
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
                "logicalProgram": "p", "logicalInputs": [], "nodeOrder": [0, 1],
                "bindingDomainOrder": [1],
                "variables": [
                    {"domain": 0, "occurrence": owner("a"),
                     "alternatives": [a0, a1]},
                    {"domain": 1, "occurrence": owner("b"),
                     "alternatives": [b0, b1]}]},
        }

    def write_model(self, root, model):
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    @staticmethod
    def active_tokens(result, assignment):
        return set(result["alwaysPresentAtoms"]) | {
            token for token, rows in result["atomConditions"].items()
            if any(all(assignment[domain] == alternative
                       for domain, alternative in row) for row in rows)}

    @staticmethod
    def relation_assignment(result, validated, native, atom_bits):
        bit_by_token = dict(zip(result["atomVariableTokens"], atom_bits))
        values = []
        for variable in validated.variables:
            if variable.name.startswith("native:"):
                values.append(native[int(variable.name.split(":", 2)[1])])
            else:
                values.append(bit_by_token[variable.name[5:]])
        return tuple(values)

    def test_atom_projection_exhaustively_reconstructs_every_typed_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            result = build(self.write_model(root, model))
            self.assertEqual("BLOCKED", result["status"])
            self.assertEqual("DIAGNOSTIC_COMPLETE", result["diagnosticStatus"])
            self.assertEqual("BLOCKED_INDEPENDENT_SEMANTIC_BINDING",
                             result["semanticBindingStatus"])
            self.assertNotIn("EQUAL", json.dumps(result))
            validated = validate_artifact(result["relation"])
            self.assertIn("definitelyAcceptedAtomImage", validated.roots)
            self.assertEqual({"definitelyAcceptedAtomImage"}, set(validated.roots))
            self.assertEqual(result["rootCommitments"], result["relation"]["roots"])

            for assignment in product(range(2), repeat=2):
                active = self.active_tokens(result, assignment)
                self.assertEqual(compose_physical_projection(model, assignment),
                                 materialize_atoms(result, active))

    def test_accepted_atom_image_preserves_action_geometry_and_binding_correlation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            result = build(self.write_model(root, model))
            validated = validate_artifact(result["relation"])
            accepted_sets = set()
            for assignment in product(range(2), repeat=2):
                if all(factor_status(tuple(row["scope"]), row["truth"], assignment,
                                     [2, 2]) == "ALLOW" for row in model["factors"]):
                    accepted_sets.add(frozenset(
                        self.active_tokens(result, assignment)))
            tokens = tuple(result["atomVariableTokens"])
            actual_sets = set()
            for bits in product(range(2), repeat=len(tokens)):
                relation_row = self.relation_assignment(result, validated, (0, 0), bits)
                if validated.evaluate("definitelyAcceptedAtomImage", relation_row):
                    actual_sets.add(frozenset(result["alwaysPresentAtoms"]) |
                                    frozenset(token for token, bit in zip(tokens, bits)
                                              if bit))
            self.assertEqual(accepted_sets, actual_sets)
            for atom_set in actual_sets:
                coordinates = {result["atomDictionary"][token]["coordinate"]
                               for token in atom_set}
                self.assertEqual("actions" in coordinates, "geometry" in coordinates)
                plan = materialize_atoms(result, atom_set)
                self.assertIn(plan["bindings"][0]["inputAuthority"],
                              {"DIRECT", "RELOCATION"})
                producer_authority = plan["authority"][0]["id"]
                self.assertEqual(producer_authority,
                                 plan["bindings"][0]["sourceAuthorityRef"])

    def test_factor_and_typed_fragment_mutations_change_only_diagnostic_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            before = build(self.write_model(root, model))
            model["physicalProjection"]["variables"][1]["alternatives"][1][
                "geometry"][0]["worker"] = "worker-mutated.example:9999"
            typed = build(self.write_model(root, model))
            self.assertNotEqual(before["relation"]["artifactSha256"],
                                typed["relation"]["artifactSha256"])
            model = self.model()
            model["factors"][0]["truth"] = ["ALLOW", "ALLOW", "REJECT", "ALLOW"]
            factor = build(self.write_model(root, model))
            self.assertEqual("3", factor["counts"]["definitelyAcceptedNativeAssignments"])
            self.assertNotEqual(before["relation"]["roots"]["definitelyAcceptedAtomImage"],
                                factor["relation"]["roots"]["definitelyAcceptedAtomImage"])
            self.assertEqual("BLOCKED", factor["status"])

    def test_partitioned_image_is_exhaustively_equal_to_monolithic_mdd(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model()
            result = build(self.write_model(Path(directory), model))
            atoms, terms = compile_atoms(model["physicalProjection"], 10_000, 100_000)
            atom_tokens = tuple(atoms)
            variables = [Variable("native:%d" % index, ("0", "1"))
                         for index in range(2)]
            variables.extend(Variable("atom:" + token, ("0", "1"))
                             for token in atom_tokens)
            manager = MDDManager(variables)
            accepted = manager.true
            for scope, truth in ((row["scope"], row["truth"])
                                 for row in model["factors"]):
                ordered, allowed = _normalized_factor_truth(scope, truth, [2, 2])
                accepted = manager.and_(accepted,
                                        manager.from_factor(ordered, allowed))
            projection = manager.true
            for offset, token in enumerate(atom_tokens):
                condition = manager.from_assignments(dict(row) for row in terms[token])
                present = manager.literal(2 + offset, (1,))
                equivalence = manager.or_(manager.and_(condition, present),
                                          manager.and_(manager.not_(condition),
                                                       manager.not_(present)))
                projection = manager.and_(projection, equivalence)
            monolithic = manager.exists(manager.and_(accepted, projection), (0, 1))
            validated = validate_artifact(result["relation"])
            dynamic = tuple(result["atomVariableTokens"])
            for bits in product((0, 1), repeat=len(dynamic)):
                full = tuple(int(token in result["alwaysPresentAtoms"] or
                                 (token in dynamic and bits[dynamic.index(token)]))
                             for token in atom_tokens)
                expected = manager.evaluate(monolithic, (0, 0) + full)
                actual = validated.evaluate(
                    "definitelyAcceptedAtomImage",
                    self.relation_assignment(result, validated, (0, 0), bits))
                self.assertEqual(expected, actual)

    def test_unknown_and_resource_or_schema_limits_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            path = self.write_model(root, model)
            atoms = build(path, max_atoms=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             atoms["diagnosticStatus"])
            terminal_atoms = build(
                path, max_atoms=1, image_strategy="terminal-aware-components")
            self.assertEqual("terminal-aware-components",
                             terminal_atoms["imageStrategy"])
            self.assertEqual("PER_MDD_MANAGER",
                             terminal_atoms["resourceBudgetScope"])
            terminal_artifact = root / "terminal-blocked.json.gz"
            publish(terminal_artifact, terminal_atoms)
            self.assertEqual("PASS", verify_saved(
                terminal_artifact, path,
                hashlib.sha256(terminal_artifact.read_bytes()).hexdigest())["status"])
            cutset_budget = build(
                path, image_strategy="cutset-conditioned-components",
                native_cutset=(0,), max_cutset_assignments=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             cutset_budget["diagnosticStatus"])
            self.assertIn("NATIVE_CUTSET_ASSIGNMENT_BUDGET_EXHAUSTED",
                          cutset_budget["blockers"])
            cutset_artifact = root / "cutset-budget-blocked.json.gz"
            publish(cutset_artifact, cutset_budget)
            self.assertEqual("PASS", verify_saved(
                cutset_artifact, path,
                hashlib.sha256(cutset_artifact.read_bytes()).hexdigest())["status"])
            terms = build(path, max_terms=1)
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             terms["diagnosticStatus"])
            mdd = build(path, max_nodes=1)
            self.assertIn("MDD_NONTERMINAL_NODE_BUDGET_EXHAUSTED", mdd["blockers"])
            self.assertEqual(1, mdd["budgets"]["maxNodes"])
            self.assertEqual("PARTITIONED_FACTOR_ATOM_ELIMINATION",
                             mdd["resourcePhase"])
            self.assertEqual(1, mdd["resourceUsage"]["liveNodes"])
            failure = mdd["resourceUsage"]["atFailure"]
            self.assertIsInstance(failure["stage"], int)
            self.assertIn(failure["operation"],
                          ("COMPILE", "CONJOIN", "ELIMINATE"))

            decomposed = build(path, max_nodes=1,
                               image_strategy="decomposed-components")
            self.assertEqual("DIAGNOSTIC_BLOCKED_RESOURCE_LIMIT",
                             decomposed["diagnosticStatus"])
            self.assertEqual("PER_MDD_MANAGER",
                             decomposed["resourceBudgetScope"])
            local_failure = decomposed["resourceUsage"]["atFailure"]
            self.assertGreater(local_failure["localLiveNodes"], 0)
            self.assertIn("localApplyPairsCached", local_failure)

            model["factors"][0]["truth"][1] = "UNKNOWN"
            unknown = build(self.write_model(root, model))
            self.assertEqual("INCOMPLETE_UNKNOWN", unknown["nativeAcceptanceStatus"])
            self.assertEqual("BLOCKED", unknown["status"])

            model = self.model()
            model.pop("physicalProjectionContract")
            unsupported = build(self.write_model(root, model))
            self.assertEqual("DIAGNOSTIC_BLOCKED_INPUT",
                             unsupported["diagnosticStatus"])
            self.assertNotIn("relation", unsupported)

    def test_phi_producer_ftype_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model()
            for fragment in model["physicalProjection"]["variables"][1]["alternatives"]:
                binding = fragment["bindings"][0]
                binding["mode"] = "PHI"
                binding.pop("producerDomain")
                binding.pop("ftype")
                binding["ftypeFromProducer"] = True
                binding["producerAlternatives"] = [{"producerDomain": 0,
                    "controlArm": "left"}]
                binding.pop("actionRef", None)
            result = build(self.write_model(Path(directory), model))
            self.assertEqual("BLOCKED", result["status"])
            self.assertIn("PHI_PRODUCER_FTYPE_NOT_COMPOSITIONAL", result["blockers"])
            self.assertNotIn("relation", result)

    def test_saved_diagnostic_requires_external_commitment_and_model_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = self.write_model(root, self.model())
            result = build(model_path)
            artifact_path = root / "result.json.gz"
            publish(artifact_path, result)
            expected_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
            verification = verify_saved(artifact_path, model_path, expected_sha)
            self.assertEqual("PASS", verification["status"])
            self.assertEqual("PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY",
                             verification["replayStatus"])
            tampered = copy.deepcopy(result)
            tampered["counts"]["uniqueDefinitelyAcceptedProvenanceAtomSets"] = "99"
            publish(artifact_path, tampered)
            with self.assertRaisesRegex(ValueError, "expected SHA-256"):
                verify_saved(artifact_path, model_path, expected_sha)
            changed_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "model-bound rebuild"):
                verify_saved(artifact_path, model_path, changed_sha)

    def test_saved_diagnostic_replays_packed_factor_truth(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model = self.model()
            statuses = model["factors"][0]["truth"]
            codes = {"ALLOW": 0, "REJECT": 1, "UNKNOWN": 2}
            packed = bytearray((len(statuses) + 3) // 4)
            for index, status in enumerate(statuses):
                packed[index >> 2] |= codes[status] << ((index & 3) * 2)
            raw = bytes(packed)
            model["factors"][0]["truth"] = {
                "schema": "packed-factor-truth-v1",
                "encoding": "2BIT_LSB_FIRST_BASE64", "cells": str(len(statuses)),
                "statusCodes": codes, "data": base64.b64encode(raw).decode("ascii"),
                "packedSha256": hashlib.sha256(raw).hexdigest(),
                "statusCounts": {status: str(statuses.count(status))
                                 for status in codes}}
            model_path = self.write_model(root, model)
            artifact_path = root / "result.json.gz"
            publish(artifact_path, build(model_path))
            verification = verify_saved(artifact_path, model_path,
                hashlib.sha256(artifact_path.read_bytes()).hexdigest())
            self.assertEqual("PASS_EXHAUSTIVE_TYPED_DECODER_REPLAY",
                             verification["replayStatus"])

    def test_provenance_atom_count_is_not_physical_plan_count(self):
        with tempfile.TemporaryDirectory() as directory:
            model = self.model()
            alternatives = model["physicalProjection"]["variables"]
            alternatives[0]["alternatives"][1] = copy.deepcopy(
                alternatives[0]["alternatives"][0])
            model["domains"][0]["alternatives"][1]["state"] = \
                "CP/LOUT/-/SHAPE_INDEPENDENT"
            alternatives[1]["alternatives"][1] = copy.deepcopy(
                alternatives[1]["alternatives"][0])
            shared = {"kind": "RELOCATION", "id": {"value": "same"},
                      "owner": owner("b")}
            alternatives[0]["alternatives"][0]["actions"] = [copy.deepcopy(shared)]
            alternatives[1]["alternatives"][1]["actions"] = [copy.deepcopy(shared)]
            result = build(self.write_model(Path(directory), model))
            self.assertEqual("2", result["counts"][
                "uniqueDefinitelyAcceptedProvenanceAtomSets"])
            self.assertEqual(compose_physical_projection(model, (0, 0)),
                             compose_physical_projection(model, (1, 1)))
            self.assertNotIn("uniqueDefinitelyAcceptedPhysicalPlans", result["counts"])


if __name__ == "__main__":
    unittest.main()
