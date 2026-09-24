import copy
import gzip
import json
from itertools import product
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from exact_e_physical_relation import compose_physical_projection, factor_status
from physical_coordinate_contract import PhysicalCoordinateCodec
from query_e_canonical_membership import _read_plan, query_membership


CALIBRATION_ROOT = Path("/home/mchoi/cofee-evaluation")


def occurrence(name):
    region = {"functionNamespace": "main", "regionPath": ["main"],
              "callSitePath": "main", "recompileContext": "base"}
    return {"sourceOrigin": name, "functionNamespace": "main",
            "callSitePath": "main", "recompileContext": "base",
            "emittedInstance": name, "controlRegion": region}


def authority(owner, worker=None):
    identity = {"owner": owner, "kind": "CANDIDATE", "layout": "BOUNDARY"}
    if worker is not None:
        identity = {"owner": owner, "kind": "FEDERATED",
                    "layout": "NATIVE_LINEAGE",
                    "workerResidency": {"ftype": "ROW", "endpoints": [worker],
                                        "layoutExact": False}}
    return {"id": identity, "source": "CAPTURED", "owner": owner,
            "kind": identity["kind"]}


def node(owner, authority_row, suffix, output="LOUT"):
    return {"occurrence": owner, "opcode": owner["sourceOrigin"], "exec": "CP",
            "output": output, "ftype": "NONE", "shapeDependent": False,
            "executionFType": "NONE",
            "valueVersion": {"lexicalVariable": suffix,
                             "definitionOrdinal": int(suffix[-1]),
                             "versionKind": "ROOT",
                             "definingControlRegion": owner["controlRegion"],
                             "predecessorVersions": []},
            "authorityRef": authority_row["id"]}


def fragment(owner, suffix, *, worker=None, output="LOUT", actions=(),
             geometry=(), bindings=()):
    authority_row = authority(owner, worker)
    return {"node": node(owner, authority_row, suffix, output),
            "authority": authority_row, "actions": list(actions),
            "geometry": list(geometry), "bindings": list(bindings)}


def alternative(signature, output="LOUT"):
    return {"signature": signature,
            "state": "CP/%s/-/SHAPE_INDEPENDENT" % output,
            "authorityKind": "CAPTURED", "candidateRule": None,
            "candidateEmission": None, "executionRule": None,
            "executionEmission": None, "realization": None,
            "supportClause": None, "relocation": None, "derivedFout": None,
            "inputAuthorities": []}


def fixture_model():
    producer_a = occurrence("producer-a")
    producer_b = occurrence("producer-b")
    consumer = occurrence("consumer")
    action_id = {"kind": "RELOCATION", "owner": producer_a,
                 "obligations": [{"consumer": consumer, "inputPosition": 1}]}
    action = {"id": action_id, "kind": "RELOCATION", "owner": producer_a}
    geometry = {"owner": consumer, "worker": "worker-a:9001",
                "ranges": [[0, 0], [10, 4]],
                "ftype": "ROW"}
    phi = {"consumer": consumer, "inputPosition": 0,
           "presence": "PRESENT", "ftype": "NONE", "mode": "PHI",
           "producerAlternatives": [
               {"producerDomain": 0, "controlArm": "then"},
               {"producerDomain": 1, "controlArm": "else"}]}
    relocation = {"consumer": consumer, "inputPosition": 1,
                  "presence": "PRESENT", "ftype": "NONE",
                  "mode": "RELOCATION", "producerDomain": 0,
                  "actionRef": action_id}
    fragments = [
        [fragment(producer_a, "a0", worker="worker-a:9001"),
         fragment(producer_a, "a1", worker="worker-b:9002")],
        [fragment(producer_b, "b0"), fragment(producer_b, "b1")],
        [fragment(consumer, "c0", actions=[action], geometry=[geometry],
                  bindings=[phi, relocation]),
         # The same physical action/geometry has a second native proof.
         fragment(consumer, "c0", actions=[copy.deepcopy(action)],
                  geometry=[copy.deepcopy(geometry)], bindings=[phi, relocation])],
    ]
    radices = [2, 2, 2]
    truth = ["REJECT", "ALLOW", "ALLOW", "ALLOW",
             "ALLOW", "ALLOW", "ALLOW", "ALLOW"]
    return {
        "schema": "closed-e-native-model-artifact-v2",
        "acceptance": "MATERIALIZED_FACTOR_TABLES", "cell": "tiny",
        "programSha256": "program", "conditionSha256": "condition",
        "sourceFiles": [],
        "domains": [
            {"index": index, "occurrence": owner["sourceOrigin"],
             "nodeKind": "OPERATION",
             "alternatives": [alternative("%s%d" % (owner["sourceOrigin"], i))
                              for i in range(radices[index])]}
            for index, owner in enumerate((producer_a, producer_b, consumer))],
        "factorAggregation": "ORDERED_SCOPE_REJECT_DOMINATES_UNKNOWN_V1",
        "nativeFactorCount": 1, "materializedFactorCount": 1,
        "sourceFactorScopes": [[0, 1, 2]],
        "nativeFactorCells": "8", "materializedFactorCells": "8",
        "factors": [{"sourceFactorIndices": [0], "scope": [0, 1, 2],
                     "cells": "8", "truth": truth}],
        "sourceIdentity": {
            "nodes": [{"occurrence": item["sourceOrigin"],
                       "operation": item["sourceOrigin"]}
                      for item in (producer_a, producer_b, consumer)],
            "orderedInputs": [], "logicalInputs": [],
            "physicalLogicalInputs": []},
        "physicalProjectionContract":
            "EXACT_PHYSICAL_COMPARISON_ROW_V1_COMPOSITIONAL",
        "physicalProjection": {
            "schema": "exact-physical-compositional-projection-v1",
            "logicalProgram": "program", "logicalInputs": [],
            "nodeOrder": [0, 1, 2], "bindingDomainOrder": [2],
            "variables": [
                {"domain": index, "occurrence": owner,
                 "alternatives": fragments[index]}
                for index, owner in enumerate(
                    (producer_a, producer_b, consumer))]},
    }


class ECanonicalMembershipTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.codec = PhysicalCoordinateCodec(CALIBRATION_ROOT)

    def write_model(self, root, model):
        path = root / "model.json.gz"
        with gzip.open(path, "wt", encoding="utf-8") as stream:
            json.dump(model, stream, sort_keys=True)
        return path

    def query(self, root, model, plan, **options):
        return query_membership(self.write_model(root, model), plan,
                                CALIBRATION_ROOT, **options)

    def test_small_exhaustive_differential_and_duplicate_proof_or(self):
        model = fixture_model()
        expected = {}
        radices = [2, 2, 2]
        truth = model["factors"][0]["truth"]
        for assignment in product(range(2), repeat=3):
            if factor_status((0, 1, 2), truth, assignment, radices) != "ALLOW":
                continue
            plan = compose_physical_projection(model, assignment)
            expected.setdefault(self.codec.encode(plan), []).append(assignment)
        self.assertTrue(any(len(proofs) > 1 for proofs in expected.values()))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for coordinate in expected:
                result = self.query(root, model, self.codec.decode(coordinate))
                self.assertEqual("SAT", result["status"])
                self.assertEqual("ALLOW", result["witness"]["acceptance"])
            rejected_proof_plan = compose_physical_projection(model, (0, 0, 0))
            recovered = self.query(root, model, rejected_proof_plan)
            self.assertEqual("SAT", recovered["status"])
            self.assertEqual([0, 0, 1], recovered["witness"]["nativeAssignment"])

    def test_all_canonical_facts_and_absence_of_extras_are_compared(self):
        model = fixture_model()
        plan = compose_physical_projection(model, (0, 0, 1))
        mutations = []
        extra = copy.deepcopy(plan)
        extra["actions"].append({"id": {"kind": "EXTRA", "owner": plan["nodes"][2]["occurrence"]},
                                 "kind": "EXTRA", "owner": plan["nodes"][2]["occurrence"]})
        mutations.append(extra)
        binding = copy.deepcopy(plan)
        binding["bindings"][0]["ftype"] = "ROW"
        mutations.append(binding)
        worker = copy.deepcopy(plan)
        worker["geometry"][0]["worker"] = "worker-z:9999"
        mutations.append(worker)
        authority_mutation = copy.deepcopy(plan)
        residency = authority_mutation["authority"][0]["id"]["workerResidency"]
        residency["endpoints"] = ["worker-z:9999"]
        authority_mutation["nodes"][0]["authorityRef"] = copy.deepcopy(
            authority_mutation["authority"][0]["id"])
        mutations.append(authority_mutation)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual("SAT", self.query(root, model, plan)["status"])
            for changed in mutations:
                self.assertEqual("UNSAT", self.query(root, model, changed)["status"])

    def test_matching_unknown_and_budget_exhaustion_are_incomplete(self):
        model = fixture_model()
        target = compose_physical_projection(model, (0, 0, 0))
        model["factors"][0]["truth"][0] = "UNKNOWN"
        model["factors"][0]["truth"][1] = "UNKNOWN"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unknown = self.query(root, model, target)
            limited = self.query(root, fixture_model(),
                                 compose_physical_projection(fixture_model(), (1, 1, 1)),
                                 max_assignments=1)
        self.assertEqual("INCOMPLETE", unknown["status"])
        self.assertIn("MATCHING_SOURCE_UNKNOWN_ASSIGNMENT", unknown["blockers"])
        self.assertEqual("INCOMPLETE", limited["status"])
        self.assertIn("NATIVE_ASSIGNMENT_BUDGET_EXHAUSTED", limited["blockers"])

    def test_unrelated_unknown_does_not_prevent_exact_unsat(self):
        model = fixture_model()
        model["factors"][0]["truth"][0] = "UNKNOWN"
        target = copy.deepcopy(compose_physical_projection(model, (1, 1, 1)))
        target["geometry"][0]["worker"] = "absent-worker:9999"
        with tempfile.TemporaryDirectory() as directory:
            result = self.query(Path(directory), model, target)
        self.assertEqual("UNSAT", result["status"])
        self.assertEqual("0", result["unknownTargetWitnesses"])

    def test_noncanonical_typed_output_fails_closed(self):
        model = fixture_model()
        del model["physicalProjection"]["variables"][0]["alternatives"][0][
            "node"]["valueVersion"]["lexicalVariable"]
        target = compose_physical_projection(fixture_model(), (0, 0, 1))
        with tempfile.TemporaryDirectory() as directory:
            result = self.query(Path(directory), model, target)
        self.assertEqual("INCOMPLETE", result["status"])
        self.assertIn("TYPED_DECODER_OUTPUT_NOT_CANONICAL", result["blockers"])
        self.assertEqual([0, 0, 1], result["blockedAssignment"])

    def test_phi_ftype_from_producer_is_incomplete_not_decoder_crash(self):
        model = fixture_model()
        for fragment in model["physicalProjection"]["variables"][2]["alternatives"]:
            phi = fragment["bindings"][0]
            phi.pop("ftype", None)
            phi["ftypeFromProducer"] = True
        target = compose_physical_projection(fixture_model(), (0, 0, 1))
        with tempfile.TemporaryDirectory() as directory:
            result = self.query(Path(directory), model, target)
        self.assertEqual("INCOMPLETE", result["status"])
        self.assertEqual(
            ["PHI_FTYPE_FROM_PRODUCER_DECODER_UNSUPPORTED"],
            result["blockers"])
        self.assertEqual("0", result["visitedAssignments"])

    def test_assignment_budget_may_exceed_platform_ssize(self):
        model = fixture_model()
        target = compose_physical_projection(model, (1, 1, 1))
        with tempfile.TemporaryDirectory() as directory:
            result = self.query(Path(directory), model, target,
                                max_assignments=sys.maxsize + 1)
        self.assertEqual("SAT", result["status"])
        self.assertEqual(sys.maxsize + 1, result["budgets"]["maxAssignments"])

    def test_verifier_integrity_error_is_not_masked_as_decoder_output(self):
        model = fixture_model()
        target = compose_physical_projection(model, (0, 0, 1))
        original_encode = PhysicalCoordinateCodec.encode
        calls = 0

        def fail_after_target(codec, plan):
            nonlocal calls
            calls += 1
            if calls > 1:
                raise ValueError(
                    "physical identity verifier module changed within process")
            return original_encode(codec, plan)

        with tempfile.TemporaryDirectory() as directory, patch.object(
                PhysicalCoordinateCodec, "encode", fail_after_target):
            with self.assertRaisesRegex(
                    ValueError, "physical identity verifier module changed"):
                self.query(Path(directory), model, target)

    def test_cli_plan_reader_enforces_byte_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text('{"schema":"physical-plan-v1"}', encoding="utf-8")
            self.assertEqual("physical-plan-v1", _read_plan(path, 1024)["schema"])
            with self.assertRaisesRegex(ValueError, "byte budget exhausted"):
                _read_plan(path, 4)

    def test_api_target_plan_also_enforces_byte_budget(self):
        model = fixture_model()
        target = compose_physical_projection(model, (0, 0, 1))
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "byte budget exhausted"):
                self.query(Path(directory), model, target,
                           max_target_plan_bytes=16)


if __name__ == "__main__":
    unittest.main()
