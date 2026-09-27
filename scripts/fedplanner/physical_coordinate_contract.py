#!/usr/bin/env python3
"""Lossless common coordinates for supported canonical physical plans.

This module deliberately does not invent a second physical identity.  A
coordinate contains the complete plan accepted and normalized by the frozen
``closed_physical_identity.canonical_plan`` verifier.  Consequently, for every
supported plan, coordinate equality is exactly canonical-plan equality.

Native proof domains and ordinals identify evidence, not physical outputs.
``encode_proof`` accepts them only in a strict envelope and projects them away,
so distinct native proofs may name the same output coordinate.
"""

import builtins
import hashlib
import json
import marshal
from pathlib import Path
import types


SCHEMA = "g009-physical-output-coordinate-v3"
VERIFIER_VERSION = "closed-physical-identity-v1"
PROOF_KEYS = {"plan", "nativeDomain", "nativeOrdinal"}
_PINNED_FILES = (
    ("closed_physical_identity.py",
     "3c5ce688847bd35d1d6e2e21e52d6ac35038eb24eca3d0333dffcf95be3576b2"),
    ("plan_space_verify.py",
     "755c834afabea34d5e49e33657b0ab3982fcbd79eea8d6dd181646ae88bee5d5"),
)
VERIFIER_FILES = types.MappingProxyType(dict(_PINNED_FILES))
_PINNED_IDENTITY_BYTES = json.dumps(
    {"version": VERIFIER_VERSION, "files": dict(_PINNED_FILES)},
    sort_keys=True, separators=(",", ":")).encode("utf-8")
_TRUSTED_MODULES = None
_TRUSTED_CALIBRATION = None
_TRUSTED_FUNCTIONS = None
_TRUSTED_RUNTIME_SNAPSHOT = None


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def _reject_constant(value):
    raise ValueError("non-finite JSON number: " + value)


def _strict_loads(value):
    return json.loads(value, parse_constant=_reject_constant)


def _read_attested_verifiers(verification_root, expected_files=_PINNED_FILES):
    """Read and attest the exact verifier bytes that will be executed."""
    calibration = (Path(verification_root) / "calibration").resolve()
    sources = {}
    for name, expected in expected_files:
        path = calibration / name
        if not path.is_file() or path.is_symlink():
            raise ValueError("frozen physical identity verifier digest differs: " + name)
        source = path.read_bytes()
        if hashlib.sha256(source).hexdigest() != expected:
            raise ValueError("frozen physical identity verifier digest differs: " + name)
        sources[name] = source
    return calibration, sources


def attest_verification_root(verification_root):
    """Return the pinned verifier identity or fail on path/content drift."""
    _read_attested_verifiers(verification_root)
    return json.loads(_PINNED_IDENTITY_BYTES)


def _execute_attested_module(name, path, source, private_imports):
    """Execute attested bytes without consulting sys.modules or sys.meta_path."""
    module = types.ModuleType(name)
    module.__file__ = str(path)
    module.__package__ = ""
    original_import = builtins.__import__

    def controlled_import(imported, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and imported in private_imports:
            return private_imports[imported]
        return original_import(imported, globals, locals, fromlist, level)

    controlled_builtins = dict(vars(builtins))
    controlled_builtins["__import__"] = controlled_import
    module.__dict__["__builtins__"] = controlled_builtins
    exec(compile(source, str(path), "exec", dont_inherit=True), module.__dict__)
    return module


def _runtime_snapshot(modules):
    """Fingerprint attested callable code and mutable verifier constants."""
    rows = []
    for module in modules:
        for name, value in sorted(module.__dict__.items()):
            if name.startswith("__"):
                continue
            if isinstance(value, types.FunctionType):
                rows.append((module.__name__, name, value,
                             hashlib.sha256(marshal.dumps(value.__code__)).hexdigest(),
                             repr(value.__defaults__), repr(value.__kwdefaults__)))
            elif isinstance(value, (dict, list, set, tuple)):
                rows.append((module.__name__, name, value,
                             hashlib.sha256(repr(value).encode("utf-8")).hexdigest()))
    return tuple(rows)


def _assert_trusted_runtime():
    """Fail if canonical_plan's dynamically resolved globals have changed."""
    if (_TRUSTED_MODULES is None or _TRUSTED_FUNCTIONS is None or
            _TRUSTED_RUNTIME_SNAPSHOT is None):
        raise ValueError("physical identity verifier is not initialized")
    dependency, identity = _TRUSTED_MODULES
    canonical, normalize_plan, canonical_plan = _TRUSTED_FUNCTIONS
    if (getattr(dependency, "canonical", None) is not canonical or
            getattr(dependency, "normalize_plan", None) is not normalize_plan or
            getattr(identity, "canonical_plan", None) is not canonical_plan or
            canonical_plan.__globals__ is not identity.__dict__ or
            identity.__dict__.get("canonical") is not canonical or
            identity.__dict__.get("normalize_plan") is not normalize_plan or
            _runtime_snapshot(_TRUSTED_MODULES) != _TRUSTED_RUNTIME_SNAPSHOT):
        raise ValueError("physical identity verifier module changed within process")


class PhysicalCoordinateCodec:
    """A bijection between validated canonical plans and coordinate bytes.

    ``verification_root`` must contain the frozen ``calibration`` directory.
    The pinned sources execute in private namespaces, independent of normal
    module import state. Later changes to their critical bindings fail closed.
    """

    def __init__(self, verification_root):
        global _TRUSTED_MODULES, _TRUSTED_CALIBRATION, _TRUSTED_FUNCTIONS
        global _TRUSTED_RUNTIME_SNAPSHOT
        calibration, sources = _read_attested_verifiers(
            verification_root)
        if _TRUSTED_MODULES is None:
            dependency = _execute_attested_module(
                "_physical_coordinate_plan_space_verify",
                calibration / "plan_space_verify.py",
                sources["plan_space_verify.py"], {})
            identity = _execute_attested_module(
                "_physical_coordinate_closed_physical_identity",
                calibration / "closed_physical_identity.py",
                sources["closed_physical_identity.py"],
                {"plan_space_verify": dependency})
            if (attest_verification_root(verification_root) !=
                    json.loads(_PINNED_IDENTITY_BYTES)):
                raise ValueError("physical identity verifier changed during import")
            _TRUSTED_MODULES = (dependency, identity)
            _TRUSTED_CALIBRATION = calibration
            _TRUSTED_FUNCTIONS = (dependency.canonical, dependency.normalize_plan,
                                  identity.canonical_plan)
            _TRUSTED_RUNTIME_SNAPSHOT = _runtime_snapshot(_TRUSTED_MODULES)
        if _TRUSTED_CALIBRATION != calibration:
            raise ValueError("physical identity verifier module changed within process")
        _assert_trusted_runtime()
        self._canonical_plan = _TRUSTED_FUNCTIONS[2]
        self.verification_root = Path(verification_root).resolve()

    @property
    def verifier_identity(self):
        """Return a defensive copy of the pinned wire identity."""
        return json.loads(_PINNED_IDENTITY_BYTES)

    def canonical_plan_bytes(self, plan):
        """Validate a supported plan and return its authoritative identity."""
        _assert_trusted_runtime()
        return self._canonical_plan_bytes_attested(plan)

    def _canonical_plan_bytes_attested(self, plan):
        """Canonicalize after the public entry has checked verifier integrity."""
        result = self._canonical_plan(plan)
        if not isinstance(result, bytes):
            raise ValueError("physical identity verifier returned a non-byte identity")
        try:
            parsed = _strict_loads(result)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            raise ValueError("physical identity verifier returned noncanonical JSON") from error
        if _canonical(parsed) != result:
            raise ValueError("physical identity verifier returned noncanonical JSON")
        return result

    def encode(self, plan):
        """Encode one supported plan as deterministic canonical coordinate bytes."""
        _assert_trusted_runtime()
        normalized = _strict_loads(self._canonical_plan_bytes_attested(plan))
        return _canonical({"schema": SCHEMA, "identityVerifier": self.verifier_identity,
                           "canonicalPlan": normalized})

    def encode_proof(self, proof):
        """Project a strict native-proof envelope onto its physical output.

        Domain and ordinal are validated as evidence coordinates, then omitted
        from the output identity by construction.
        """
        if not isinstance(proof, dict) or set(proof) != PROOF_KEYS:
            raise ValueError("native proof envelope incomplete")
        domain = proof["nativeDomain"]
        ordinal = proof["nativeOrdinal"]
        if not isinstance(domain, str) or not domain:
            raise ValueError("native proof domain invalid")
        if type(ordinal) is not int or ordinal < 0:
            raise ValueError("native proof ordinal invalid")
        return self.encode(proof["plan"])

    def decode(self, coordinate):
        """Decode and independently revalidate canonical coordinate bytes."""
        _assert_trusted_runtime()
        if not isinstance(coordinate, bytes) or not coordinate:
            raise ValueError("physical coordinate must be non-empty bytes")
        try:
            wire = _strict_loads(coordinate)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            raise ValueError("physical coordinate is not JSON") from error
        if (_canonical(wire) != coordinate or not isinstance(wire, dict) or
                set(wire) != {"schema", "identityVerifier", "canonicalPlan"} or
                wire.get("schema") != SCHEMA or
                wire.get("identityVerifier") != self.verifier_identity or
                not isinstance(wire.get("canonicalPlan"), dict)):
            raise ValueError("physical coordinate wire is noncanonical or unsupported")
        plan = wire["canonicalPlan"]
        if self._canonical_plan_bytes_attested(plan) != _canonical(plan):
            raise ValueError("physical coordinate payload is not canonical")
        return plan


def same_output(codec, left, right):
    """Return equality in the common physical-output coordinate space."""
    if not isinstance(codec, PhysicalCoordinateCodec):
        raise TypeError("codec must be PhysicalCoordinateCodec")
    return codec.encode(left) == codec.encode(right)
