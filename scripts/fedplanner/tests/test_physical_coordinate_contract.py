import copy
import gzip
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from physical_coordinate_contract import (PhysicalCoordinateCodec,
                                            VERIFIER_FILES, VERIFIER_VERSION,
                                            attest_verification_root, same_output)


CALIBRATION_ROOT = Path("/home/mchoi/cofee-evaluation")
PCA_ROOT = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
                "current-pe-v12-ledger-aware-pca-representatives/physical")
SMALL_CELL_ROOT = Path("/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921/"
                       "current-pe-campaign-v12-ledger-aware/physical-artifacts")
ARTIFACT_MATRIX = (
    ("small-cell", SMALL_CELL_ROOT / "cell_0b7e2829a1e84335c6ae" /
     "16ae408a3f700c71e0b5", {
         "e-dictionary.jsonl.gz", "p-state-0-16-dictionary.jsonl.gz",
         "p-state-16-32-dictionary.jsonl.gz"}),
    ("pca", PCA_ROOT / "cell_545432b0d0968368998e" /
     "695f2e3d546dca365fa9", {
         "e-dictionary.jsonl.gz", "p-state-0-16-dictionary.jsonl.gz",
         "p-state-16-32-dictionary.jsonl.gz", "p-state-32-48-dictionary.jsonl.gz",
         "p-state-48-64-dictionary.jsonl.gz", "p-state-64-80-dictionary.jsonl.gz",
         "p-state-80-96-dictionary.jsonl.gz", "p-state-96-112-dictionary.jsonl.gz",
         "p-state-112-128-dictionary.jsonl.gz"}),
)


def occurrence(name):
    region = {"functionNamespace": "main", "regionPath": ["main", "if"],
              "callSitePath": "main", "recompileContext": "base"}
    return {"sourceOrigin": name, "functionNamespace": "main",
            "callSitePath": "main", "recompileContext": "base",
            "emittedInstance": name, "controlRegion": region}


def plan():
    producer_a = occurrence("producer-a")
    producer_b = occurrence("producer-b")
    consumer = occurrence("consumer")
    occurrences = (producer_a, producer_b, consumer)
    authority_ids = [{"kind": "LOCAL", "layout": "LOCAL", "owner": item}
                     for item in occurrences]
    nodes = []
    for index, (item, authority) in enumerate(zip(occurrences, authority_ids)):
        nodes.append({
            "occurrence": item, "opcode": "plus" if index == 2 else "read",
            "exec": "CP", "output": "LOUT", "ftype": "NONE",
            "payload": {"rows": 10, "cols": index + 1},
            "valueVersion": {
                "lexicalVariable": "v" + str(index), "definitionOrdinal": index,
                "versionKind": "ROOT", "definingControlRegion": item["controlRegion"],
                "predecessorVersions": []},
            "authorityRef": authority})
    alternatives = [
        {"producer": producer_a, "controlArm": "then",
         "sourceAuthorityRef": authority_ids[0]},
        {"producer": producer_b, "controlArm": "else",
         "sourceAuthorityRef": authority_ids[1]}]
    return {
        "schema": "physical-plan-v1", "context": {"logical": "fixture-program"},
        "nodes": nodes,
        "authority": [
            {"id": authority, "source": "fixture", "owner": item,
             "kind": "LOCAL", "details": {"replicas": ["w1", "w2"]}}
            for item, authority in zip(occurrences, authority_ids)],
        "actions": [{"id": {"kind": "MATERIALIZE", "owner": consumer},
                     "kind": "MATERIALIZE", "owner": consumer,
                     "geometry": {"begin": [0, 0], "end": [10, 3]}}],
        "bindings": [{
            "consumer": consumer, "inputPosition": 0,
            "producer": {"kind": "PHI_JOIN_PORT", "owner": consumer},
            "presence": "PRESENT", "ftype": "NONE", "inputAuthority": "PHI",
            "producerAlternatives": alternatives}],
        "logicalInputs": [{"kind": "TRANSIENT", "source": producer_a,
                           "target": consumer, "position": 0}],
        "geometry": [{"owner": consumer, "worker": "w1",
                      "ranges": [{"begin": [0, 0], "end": [10, 3]}],
                      "ftype": "ROW", "blocksize": 1024}]}


class PhysicalCoordinateContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.codec = PhysicalCoordinateCodec(CALIBRATION_ROOT)

    def test_round_trip_is_exact_canonical_plan(self):
        source = plan()
        coordinate = self.codec.encode(source)
        decoded = self.codec.decode(coordinate)
        self.assertEqual(self.codec.canonical_plan_bytes(source),
                         json.dumps(decoded, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False).encode())
        self.assertEqual(coordinate, self.codec.encode(decoded))
        wire = json.loads(coordinate)
        self.assertEqual(
            {"version": VERIFIER_VERSION, "files": VERIFIER_FILES},
            wire["identityVerifier"])

    def test_native_domain_and_ordinal_are_not_output_identity(self):
        source = plan()
        left = self.codec.encode_proof({"plan": source, "nativeDomain": "P_C0",
                                        "nativeOrdinal": 7})
        right = self.codec.encode_proof({"plan": copy.deepcopy(source),
                                         "nativeDomain": "E_C0",
                                         "nativeOrdinal": 987654})
        self.assertEqual(left, right)
        for bad in ({"plan": source, "nativeDomain": "P_C0"},
                    {"plan": source, "nativeDomain": "", "nativeOrdinal": 1},
                    {"plan": source, "nativeDomain": "P_C0", "nativeOrdinal": -1}):
            with self.assertRaises(ValueError):
                self.codec.encode_proof(bad)

    def test_verifier_root_is_digest_pinned_and_same_path_drift_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            fake = Path(temporary)
            calibration = fake / "calibration"
            calibration.mkdir()
            for name in ("closed_physical_identity.py", "plan_space_verify.py"):
                (calibration / name).write_text("# structurally plausible fake\n")
            with self.assertRaisesRegex(ValueError, "digest differs"):
                attest_verification_root(fake)

        script = """
import pathlib, shutil, sys
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec
root = pathlib.Path(sys.argv[2])
source = pathlib.Path(sys.argv[3]) / 'calibration'
target = root / 'calibration'
target.mkdir()
for name in ('closed_physical_identity.py', 'plan_space_verify.py'):
    shutil.copy2(source / name, target / name)
PhysicalCoordinateCodec(root)
with (target / 'closed_physical_identity.py').open('a') as stream:
    stream.write('\\n# same-path drift\\n')
try:
    PhysicalCoordinateCodec(root)
except ValueError as error:
    if 'digest differs' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, "-c", script,
                 str(Path(__file__).resolve().parents[1]), temporary,
                 str(CALIBRATION_ROOT)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_same_path_preloaded_module_is_bypassed(self):
        script = """
import pathlib, sys, types
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec
name = sys.argv[2]
forged = types.ModuleType(name)
forged.__file__ = str(pathlib.Path(sys.argv[3]) / 'calibration' / (name + '.py'))
if name == 'closed_physical_identity':
    forged.canonical_plan = lambda _: b'{}'
else:
    forged.canonical = lambda _: b'{}'
    forged.normalize_plan = lambda value: value
sys.modules[name] = forged
try:
    PhysicalCoordinateCodec(sys.argv[3]).canonical_plan_bytes({})
except ValueError as error:
    if 'logical input relations missing' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        for name in ("closed_physical_identity", "plan_space_verify"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [sys.executable, "-c", script,
                     str(Path(__file__).resolve().parents[1]), name,
                     str(CALIBRATION_ROOT)], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_public_digest_metadata_cannot_replace_pinned_source(self):
        script = """
import hashlib, pathlib, sys
sys.path.insert(0, sys.argv[1])
import physical_coordinate_contract as contract
root = pathlib.Path(sys.argv[2])
calibration = root / 'calibration'
calibration.mkdir()
fake = b'def canonical_plan(value): return b"{}"\\n'
for name in contract.VERIFIER_FILES:
    (calibration / name).write_bytes(fake)
try:
    contract.VERIFIER_FILES['closed_physical_identity.py'] = hashlib.sha256(fake).hexdigest()
except TypeError:
    pass
contract.VERIFIER_FILES = {name: hashlib.sha256(fake).hexdigest()
                           for name in contract.VERIFIER_FILES}
try:
    contract.PhysicalCoordinateCodec(root)
except ValueError as error:
    if 'digest differs' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, "-c", script,
                 str(Path(__file__).resolve().parents[1]), temporary],
                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_forged_meta_path_loader_with_matching_file_is_bypassed(self):
        script = """
import importlib
import importlib.abc
import importlib.util
import pathlib
import sys
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec

root = pathlib.Path(sys.argv[2])
targets = {'closed_physical_identity', 'plan_space_verify'}
hits = []
class ForgedLoader(importlib.abc.Loader):
    def create_module(self, spec):
        return None
    def exec_module(self, module):
        hits.append(module.__name__)
        module.__file__ = str(root / 'calibration' / (module.__name__ + '.py'))
        module.canonical = lambda _: b'{}'
        module.normalize_plan = lambda value: value
        module.canonical_plan = lambda _: b'{}'
class ForgedFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path, target=None):
        if fullname in targets:
            return importlib.util.spec_from_loader(fullname, ForgedLoader(),
                                                   origin=str(root / 'calibration' / (fullname + '.py')))
        return None
finder = ForgedFinder()
sys.meta_path.insert(0, finder)
for name in sorted(targets):
    module = importlib.import_module(name)
    if pathlib.Path(module.__file__) != root / 'calibration' / (name + '.py'):
        raise SystemExit(4)
    del sys.modules[name]
if len(hits) != 2:
    raise SystemExit(5)
codec = PhysicalCoordinateCodec(root)
if len(hits) != 2:
    raise SystemExit(6)
try:
    codec.canonical_plan_bytes({})
except ValueError as error:
    if 'logical input relations missing' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        result = subprocess.run(
            [sys.executable, "-c", script,
             str(Path(__file__).resolve().parents[1]), str(CALIBRATION_ROOT)],
            capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_first_codec_rejects_mutated_identity_globals(self):
        script = """
import sys
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec
codec = PhysicalCoordinateCodec(sys.argv[2])
codec._canonical_plan.__globals__[sys.argv[3]] = lambda value: value
try:
    codec.canonical_plan_bytes({})
except ValueError as error:
    if 'module changed within process' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        for name in ("normalize_plan", "canonical"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [sys.executable, "-c", script,
                     str(Path(__file__).resolve().parents[1]),
                     str(CALIBRATION_ROOT), name], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_second_codec_rejects_mutated_identity_globals(self):
        script = """
import sys
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec
first = PhysicalCoordinateCodec(sys.argv[2])
first._canonical_plan.__globals__[sys.argv[3]] = lambda value: value
try:
    PhysicalCoordinateCodec(sys.argv[2])
except ValueError as error:
    if 'module changed within process' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        for name in ("normalize_plan", "canonical"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [sys.executable, "-c", script,
                     str(Path(__file__).resolve().parents[1]),
                     str(CALIBRATION_ROOT), name], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_in_place_verifier_code_mutation_fails_closed(self):
        script = """
import sys
sys.path.insert(0, sys.argv[1])
from physical_coordinate_contract import PhysicalCoordinateCodec
codec = PhysicalCoordinateCodec(sys.argv[2])
if sys.argv[3] == 'canonical_plan':
    codec._canonical_plan.__code__ = (lambda value: b'{}').__code__
else:
    helper = codec._canonical_plan.__globals__['_walk']
    helper.__code__ = (lambda value: None).__code__
try:
    codec.canonical_plan_bytes({})
except ValueError as error:
    if 'module changed within process' in str(error):
        raise SystemExit(0)
raise SystemExit(3)
"""
        for name in ("canonical_plan", "helper"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [sys.executable, "-c", script,
                     str(Path(__file__).resolve().parents[1]),
                     str(CALIBRATION_ROOT), name], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_verifier_identity_wire_is_defensive(self):
        modified = self.codec.verifier_identity
        modified["version"] = "forged-version"
        modified["files"]["closed_physical_identity.py"] = "0" * 64
        coordinate = self.codec.encode(plan())
        wire = json.loads(coordinate)
        self.assertNotEqual(modified, wire["identityVerifier"])
        self.assertEqual(wire["identityVerifier"], self.codec.verifier_identity)
        self.codec.decode(coordinate)

    def test_full_payload_authority_action_and_geometry_are_identity(self):
        baseline = plan()
        mutations = []
        for path, value in (
                (("nodes", 0, "payload", "rows"), 11),
                (("authority", 0, "details", "replicas", 0), "other"),
                (("actions", 0, "geometry", "end", 1), 4),
                (("geometry", 0, "ranges", 0, "end", 0), 9),
                (("context", "logical"), "other-program"),
                (("logicalInputs", 0, "position"), 1)):
            changed = copy.deepcopy(baseline)
            target = changed
            for part in path[:-1]:
                target = target[part]
            target[path[-1]] = value
            mutations.append(changed)
        for changed in mutations:
            self.assertFalse(same_output(self.codec, baseline, changed))

    def test_phi_list_order_normalizes_but_arm_identity_does_not(self):
        baseline = plan()
        reordered = copy.deepcopy(baseline)
        reordered["bindings"][0]["producerAlternatives"].reverse()
        self.assertTrue(same_output(self.codec, baseline, reordered))
        changed_arm = copy.deepcopy(baseline)
        changed_arm["bindings"][0]["producerAlternatives"][0]["controlArm"] = "loop"
        self.assertFalse(same_output(self.codec, baseline, changed_arm))
        moved_slot = copy.deepcopy(baseline)
        moved_slot["bindings"][0]["inputPosition"] = 1
        self.assertFalse(same_output(self.codec, baseline, moved_slot))

    def test_duplicate_and_unsupported_structures_fail_closed(self):
        duplicate_node = plan()
        duplicate_node["nodes"].append(copy.deepcopy(duplicate_node["nodes"][0]))
        duplicate_phi = plan()
        duplicate_phi["bindings"][0]["producerAlternatives"][1] = copy.deepcopy(
            duplicate_phi["bindings"][0]["producerAlternatives"][0])
        unknown = plan()
        unknown["nodes"][0]["ordinal"] = 3
        for invalid in (duplicate_node, duplicate_phi, unknown):
            with self.assertRaises(ValueError):
                self.codec.encode(invalid)

    def test_coordinate_wire_rejects_noncanonical_or_mutated_payload(self):
        coordinate = self.codec.encode(plan())
        with self.assertRaisesRegex(ValueError, "noncanonical"):
            self.codec.decode(coordinate + b"\n")
        wire = json.loads(coordinate)
        wire["canonicalPlan"]["nodes"][0]["payload"]["rows"] = 99
        # A fully canonical mutation is a different valid coordinate, not an
        # undetectable alias for the original output.
        mutated = json.dumps(wire, sort_keys=True, separators=(",", ":")).encode()
        self.assertNotEqual(coordinate, mutated)
        self.assertEqual(mutated, self.codec.encode(self.codec.decode(mutated)))

    def test_non_finite_numbers_fail_closed_on_encode_and_decode(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            invalid = plan()
            invalid["nodes"][0]["payload"]["rows"] = value
            with self.assertRaisesRegex(ValueError, "noncanonical JSON"):
                self.codec.canonical_plan_bytes(invalid)
            with self.assertRaisesRegex(ValueError, "noncanonical JSON"):
                self.codec.encode(invalid)
        coordinate = self.codec.encode(plan())
        with self.assertRaisesRegex(ValueError, "not JSON"):
            self.codec.decode(coordinate.replace(b'"rows":10', b'"rows":NaN', 1))

    @unittest.skipUnless(os.environ.get("G009_SCAN_STORED_COORDINATES") == "1",
                         "set G009_SCAN_STORED_COORDINATES=1 for bounded artifact scan")
    def test_explicit_stored_artifact_matrix_all_rows(self):
        coverage = set()
        rows = 0
        for cohort, folder, expected in ARTIFACT_MATRIX:
            actual = {path.name for path in folder.glob("*-dictionary.jsonl.gz")}
            self.assertEqual(expected, actual, cohort)
            for name in sorted(expected):
                side = "E" if name == "e-dictionary.jsonl.gz" else "P"
                path_rows = 0
                with gzip.open(folder / name, "rt") as stream:
                    for line in stream:
                        source = json.loads(line, parse_constant=lambda value: self.fail(
                            "non-finite stored JSON: " + value))
                        coordinate = self.codec.encode(source)
                        self.assertEqual(
                            coordinate, self.codec.encode(self.codec.decode(coordinate)))
                        path_rows += 1
                if path_rows:
                    coverage.add((cohort, side))
                rows += path_rows
        self.assertEqual({("small-cell", "P"), ("small-cell", "E"),
                          ("pca", "P"), ("pca", "E")}, coverage)
        self.assertGreater(rows, 0)


if __name__ == "__main__":
    unittest.main()
