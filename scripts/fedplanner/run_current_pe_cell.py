#!/usr/bin/env python3
"""Run and independently recheck one frozen current P/E physical-set cell.

The certificate proves equality of the two captured native physical sets. It
does not claim that either native model exhausts runtime-feasible behavior.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/fedplanner"))
from verify_e_factor_artifact import verify as verify_e_factors
from verify_p_model_artifact import verify as verify_p_model
from verify_compact_physical_shard import verify as verify_compact_shard


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_sha(root):
    digest = hashlib.sha256()
    files = [root / "pom.xml"]
    for folder in ("src/main/java", "src/test/java", "src/main/resources",
                   "src/test/resources/fedplanner"):
        source = root / folder
        if source.exists():
            files.extend(path for path in source.rglob("*") if path.is_file())
    for path in sorted(files):
        digest.update(str(path.relative_to(root)).encode())
        digest.update(bytes.fromhex(sha(path)))
    return digest.hexdigest()


def class_tree_sha(build):
    """Bind the executed classpath bytes, including the frozen dependency jars."""
    files = []
    for folder, suffix in (("target/classes", None),
                           ("target/test-classes", None),
                           ("target/lib", ".jar")):
        directory = build / folder
        if not directory.is_dir():
            raise ValueError("compiled classpath directory missing: " + str(directory))
        files.extend(path for path in directory.rglob("*")
                     if path.is_file() and (suffix is None or path.suffix == suffix))
    if not files:
        raise ValueError("compiled classpath is empty")
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(str(path.relative_to(build)).encode())
        digest.update(bytes.fromhex(sha(path)))
    return digest.hexdigest()


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write("\n")
        temporary = Path(stream.name)
    temporary.replace(path)


def read(path):
    return json.loads(Path(path).read_text())


def _gzip_content_sha(path):
    digest = hashlib.sha256()
    with gzip.open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _canonical_json_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def _digest_file_hashes(names, *, include_names=False):
    digest = hashlib.sha256()
    for name in names:
        if include_names:
            digest.update(name.encode())
        digest.update(bytes.fromhex(sha(ROOT / "scripts/fedplanner" / name)))
    return digest.hexdigest()


def _p_matrix_runner_sha():
    return _digest_file_hashes(("capture_current_plan_matrix.py",
                                "run_current_pe_cell.py"))


def _p_matrix_verifier_sha():
    return _digest_file_hashes(("verify_current_p_matrix.py",
                                "verify_p_model_artifact.py",
                                "run_current_pe_cell.py"))


def _e_matrix_runner_sha():
    return _digest_file_hashes(("capture_current_e_model_matrix.py",
                                "run_current_pe_cell.py",
                                "capture_current_plan_matrix.py",
                                "exact_e_factor_count.py"), include_names=True)


def _regular_file(path, description):
    path = Path(path)
    if not path.is_file() or path.is_symlink():
        raise ValueError(description + " missing or is a symlink")
    return path


def describe_model_import(kind, matrix_dir, catalog, evaluation, cell,
                          source_sha, classes_sha):
    """Validate one independently checked matrix cell and bind its exact bytes."""
    if kind not in ("p", "e"):
        raise ValueError("unknown model import kind")
    matrix_dir = Path(matrix_dir).resolve()
    manifest_path = _regular_file(matrix_dir / "matrix.json", kind.upper() + " matrix manifest")
    verification_path = _regular_file(matrix_dir / "verification.json",
                                      kind.upper() + " matrix verification")
    receipt_path = _regular_file(matrix_dir / cell / "receipt.json",
                                 kind.upper() + " matrix cell receipt")
    artifact_path = _regular_file(matrix_dir / cell / f"{kind}-model.json.gz",
                                  kind.upper() + " matrix model")
    manifest, verification, receipt = (read(path) for path in
                                       (manifest_path, verification_path, receipt_path))
    listed = manifest.get("cells")
    if (not isinstance(listed, list) or manifest.get("status") != "COMPLETE" or
            manifest.get("cellCount") != len(listed) or
            sum(row.get("cell") == cell and row.get("status") == "COMPLETE"
                for row in listed) != 1):
        raise ValueError(kind.upper() + " matrix does not contain one complete cell")
    catalog_sha = sha(catalog)
    if kind == "p":
        if (manifest.get("schema") != "current-p-matrix-capture-v1" or
                manifest.get("sourceTreeSha256") != source_sha or
                manifest.get("classTreeSha256") != classes_sha or
                manifest.get("catalogSha256") != catalog_sha or
                manifest.get("evaluationRoot") != str(evaluation) or
                manifest.get("runnerSha256") != _p_matrix_runner_sha()):
            raise ValueError("P matrix execution binding differs")
        if (receipt.get("schema") != "current-p-matrix-cell-v1" or
                receipt.get("cell") != cell or receipt.get("status") != "COMPLETE" or
                receipt.get("sourceTreeSha256") != source_sha or
                receipt.get("classTreeSha256") != classes_sha or
                receipt.get("runnerSha256") != manifest["runnerSha256"] or
                receipt.get("catalogSha256") != catalog_sha or
                receipt.get("evaluationRoot") != str(evaluation) or
                receipt.get("artifactPath") != str(artifact_path)):
            raise ValueError("P matrix cell receipt binding differs")
        if (verification.get("schema") != "current-p-matrix-artifact-verification-v1" or
                verification.get("status") != "PASS" or
                verification.get("matrixSha256") != sha(manifest_path) or
                verification.get("verifierSha256") != _p_matrix_verifier_sha() or
                verification.get("catalogSha256") != catalog_sha or
                verification.get("evaluationRoot") != str(evaluation) or
                verification.get("cellCount") != manifest.get("cellCount") or
                verification.get("verifiedComplete") != manifest.get("cellCount") or
                verification.get("failures")):
            raise ValueError("P matrix has no complete independent verification")
        native_path = _regular_file(matrix_dir / cell / "capture.json",
                                    "P native capture receipt")
        native = read(native_path)
        native_path_value = str(native_path)
        native_file_sha = sha(native_path)
    else:
        matrix_binding = manifest.get("binding")
        if (manifest.get("schema") != "current-e-model-matrix-v1" or
                not isinstance(matrix_binding, dict) or
                matrix_binding.get("sourceTreeSha256") != source_sha or
                matrix_binding.get("classTreeSha256") != classes_sha or
                matrix_binding.get("catalogSha256") != catalog_sha or
                matrix_binding.get("evaluationRoot") != str(evaluation) or
                matrix_binding.get("runnerSha256") != _e_matrix_runner_sha()):
            raise ValueError("E matrix execution binding differs")
        if (receipt.get("schema") != "current-e-model-matrix-cell-v1" or
                receipt.get("cell") != cell or receipt.get("status") != "COMPLETE" or
                receipt.get("binding") != matrix_binding or
                receipt.get("artifactPath") != str(artifact_path)):
            raise ValueError("E matrix cell receipt binding differs")
        if (verification.get("schema") != "current-e-model-matrix-verification-v1" or
                verification.get("status") != "PASS" or
                verification.get("matrixSha256") != sha(manifest_path) or
                verification.get("catalogSha256") != catalog_sha or
                verification.get("evaluationRoot") != str(evaluation) or
                verification.get("runnerSha256") != matrix_binding["runnerSha256"] or
                verification.get("cellCount") != manifest.get("cellCount") or
                verification.get("verifiedComplete") != manifest.get("cellCount") or
                verification.get("verifiedKnown") != manifest.get("cellCount") or
                verification.get("failures") or verification.get("unknownFactorCells")):
            raise ValueError("E matrix has no complete known independent verification")
        native = receipt.get("native")
        native_path_value = None
        native_file_sha = None
    if (not isinstance(native, dict) or
            native.get("schema") != "closed-native-model-capture-v1" or
            native.get("status") != "COMPLETE" or native.get("cell") != cell or
            native.get("source") != kind.upper() + "_C0" or
            native.get("artifactPath") != str(artifact_path)):
        raise ValueError(kind.upper() + " native capture receipt differs")
    artifact_sha = _gzip_content_sha(artifact_path)
    if (not artifact_sha or receipt.get("artifactSha256") != artifact_sha or
            native.get("artifactSha256") != artifact_sha):
        raise ValueError(kind.upper() + " matrix model digest differs")
    return {
        "schema": "current-pe-cell-model-import-v1", "kind": kind.upper(),
        "matrixDir": str(matrix_dir), "matrixManifestSha256": sha(manifest_path),
        "matrixVerificationSha256": sha(verification_path),
        "cellReceiptSha256": sha(receipt_path),
        "nativeReceiptPath": native_path_value,
        "nativeReceiptFileSha256": native_file_sha,
        "nativeReceiptSha256": _canonical_json_sha(native),
        "sourceArtifactPath": str(artifact_path),
        "artifactSha256": artifact_sha,
        "compressedArtifactSha256": sha(artifact_path),
        "storage": "REFLINK_OR_HARDLINK",
    }, native


def _materialize_import(source, target):
    """Atomically create a copy-on-write clone, falling back to an exact hardlink."""
    target = Path(target)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
        temporary = Path(stream.name)
    temporary.unlink()
    try:
        cloned = subprocess.run(["cp", "--reflink=always", "--", str(source), str(temporary)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if cloned.returncode:
            temporary.unlink(missing_ok=True)
            os.link(source, temporary)
        temporary.replace(target)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def import_model(kind, descriptor, native, run_dir):
    artifact = run_dir / f"{kind}-model.json.gz"
    receipt_path = run_dir / f"{kind}-model.receipt.json"
    expected = dict(native)
    expected["artifactPath"] = str(artifact)
    expected["modelImport"] = descriptor
    if artifact.exists() and receipt_path.exists():
        saved = read(receipt_path)
        if (saved == expected and not artifact.is_symlink() and
                sha(artifact) == descriptor["compressedArtifactSha256"]):
            return saved
    _materialize_import(descriptor["sourceArtifactPath"], artifact)
    if sha(artifact) != descriptor["compressedArtifactSha256"]:
        artifact.unlink(missing_ok=True)
        raise ValueError(kind.upper() + " imported model bytes differ")
    save(receipt_path, expected)
    return expected


def verify_model_import(kind, descriptor, receipt, run_dir, catalog, evaluation,
                        cell, source_sha, classes_sha):
    current, native = describe_model_import(kind, descriptor["matrixDir"], catalog,
                                            evaluation, cell, source_sha, classes_sha)
    if current != descriptor:
        raise ValueError(kind.upper() + " matrix import provenance changed")
    artifact = run_dir / f"{kind}-model.json.gz"
    if artifact.is_symlink() or not artifact.is_file():
        raise ValueError(kind.upper() + " imported model is missing or a symlink")
    expected = dict(native)
    expected["artifactPath"] = str(artifact)
    expected["modelImport"] = descriptor
    if receipt != expected:
        raise ValueError(kind.upper() + " imported native receipt changed")
    source = Path(descriptor["sourceArtifactPath"])
    local_sha = sha(artifact)
    source_sha256 = local_sha if os.path.samefile(source, artifact) else sha(source)
    if (local_sha != descriptor["compressedArtifactSha256"] or
            source_sha256 != descriptor["compressedArtifactSha256"]):
        raise ValueError(kind.upper() + " imported/source model bytes changed")


def verifier_sha(verification, compact=False):
    digest = hashlib.sha256()
    paths = [Path(__file__).resolve(), ROOT / "scripts/fedplanner/verify_e_factor_artifact.py",
             ROOT / "scripts/fedplanner/verify_p_model_artifact.py",
             verification / "calibration/plan_space_verify.py",
             verification / "calibration/closed_physical_identity.py"]
    if compact:
        paths.extend((ROOT / "scripts/fedplanner/verify_compact_physical_shard.py",
                      verification / "calibration/compact_physical_identity.py"))
    for path in paths:
        digest.update(str(path.name).encode())
        digest.update(bytes.fromhex(sha(path)))
    return digest.hexdigest()


def check_frozen_inputs(catalog, evaluation, cell):
    matches = [row for row in read(catalog)["cells"] if row["id"] == cell]
    if len(matches) != 1 or matches[0].get("inventoryStatus") != "IN_SCOPE":
        raise ValueError("cell absent from frozen in-scope catalog")
    files = matches[0].get("sourceFiles")
    if not isinstance(files, dict) or not files:
        raise ValueError("cell has no frozen source files")
    for name, expected in files.items():
        path = (evaluation / name).resolve()
        if not path.is_relative_to(evaluation) or not path.is_file() or sha(path) != expected:
            raise ValueError("frozen source file missing or changed: " + name)
    return files


def frozen_capture_settings(catalog, evaluation, cell, environment):
    if any(environment.get(name) for name in
           ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise ValueError("ambient JVM options conflict with frozen condition")
    rows = [row for row in read(catalog)["cells"] if row["id"] == cell]
    if len(rows) != 1:
        raise ValueError("cell absent or duplicated in frozen catalog")
    binding = rows[0].get("sourceBinding", {})
    discovery = rows[0].get("discoveryId", "")
    kind = binding.get("kind")
    if (binding.get("conditionStatus") != "SNAPSHOT_ONLY" or
            kind not in ("planning-snapshot", "campaign-compile-condition",
                         "generated-microbench")):
        raise ValueError("cell lacks frozen planning condition")
    planned = binding.get("plannedCondition")
    if not isinstance(planned, dict):
        raise ValueError("cell lacks frozen planned condition payload")
    if kind == "planning-snapshot":
        if not discovery.startswith("planning-w") or ":" not in discovery:
            raise ValueError("planning snapshot discovery ID is malformed")
        workers_text, workload = discovery.split(":", 1)
        workers = int(workers_text.removeprefix("planning-w"))
        if planned.get("workers", workers) != workers:
            raise ValueError("planning snapshot worker binding differs")
        protocol = evaluation / f"planning_study/native/protocol-w{workers}.json"
        options = read(protocol).get("workload_jvm_options", {}).get(workload, [])
    else:
        options = binding.get("workloadJvmOptions")
    if (not isinstance(options, list) or any(not isinstance(option, str) or
            not option.startswith("-D") or "=" not in option for option in options)):
        raise ValueError("malformed frozen workload JVM properties")
    network = planned.get("network", {}).get("cost_environment")
    if not isinstance(network, dict) or not network:
        raise ValueError("frozen network environment missing")
    supplied = {key: value for key, value in environment.items()
                if key.startswith("SYSDS_FED_COST_NET_")}
    if any(key not in network or network[key] != value for key, value in supplied.items()):
        raise ValueError("process network environment conflicts with frozen condition")
    result = environment.copy()
    result.update(network)
    return result, options, network


def java(build, environment, klass, *args, jvm_options=()):
    command = ["java", *jvm_options, "--add-modules", "jdk.incubator.vector", "-Xmx4g",
               "-cp", "target/classes:target/test-classes:target/lib/*", klass,
               *map(str, args)]
    process = subprocess.run(command, cwd=build, env=environment,
                             text=True, capture_output=True)
    if process.returncode:
        raise RuntimeError(f"{klass} failed: {process.stderr[-2000:]}")
    return json.loads(process.stdout.strip().splitlines()[-1])


def check_row_receipt(receipt_path, source, cell, model, expected=None,
                      expected_rows=None):
    receipt = read(receipt_path)
    rows = Path(receipt["rows"])
    if expected_rows is not None and (rows != expected_rows or rows.is_symlink()):
        raise ValueError("physical shard row path differs from run frontier")
    if (receipt.get("schema") != "closed-planning-physical-shard-v1" or
            receipt.get("status") != "COMPLETE" or receipt.get("source") != source or
            receipt.get("cell") != cell or receipt.get("unknown") != "0" or
            receipt.get("rowsSha256") != sha(rows) or
            receipt.get("programSha256") != model["programSha256"] or
            receipt.get("conditionSha256") != model["conditionSha256"] or
            receipt.get("sourceFiles") != model["sourceFiles"]):
        raise ValueError("stale, damaged, or unresolved physical shard: " + str(receipt_path))
    if int(receipt["raw"]) != int(receipt["accepted"]) + int(receipt["rejected"]):
        raise ValueError("raw coverage does not balance: " + str(receipt_path))
    if expected is not None and (int(receipt["start"]), int(receipt["stop"])) != expected:
        raise ValueError("state frontier gap or overlap")
    count = 0
    with gzip.open(rows, "rt") as stream:
        for line in stream:
            if json.loads(line).get("schema") != "physical-plan-v1":
                raise ValueError("physical shard has invalid row")
            count += 1
    if count != int(receipt["accepted"]):
        raise ValueError("accepted proof and physical row count differ")
    return receipt


def check_compact_receipt(receipt_path, source, cell, model, verification,
                          expected_rows, expected_references, expected=None):
    result = verify_compact_shard(receipt_path, verification,
                                  expected_dictionary=expected_rows,
                                  expected_references=expected_references)
    receipt = result["receipt"]
    if (receipt.get("source") != source or receipt.get("cell") != cell or
            receipt.get("programSha256") != model["programSha256"] or
            receipt.get("conditionSha256") != model["conditionSha256"] or
            receipt.get("sourceFiles") != model["sourceFiles"] or
            (expected is not None and
             (int(receipt.get("start", -1)), int(receipt.get("stop", -1))) != expected)):
        raise ValueError("compact physical shard binding differs")
    return result


def sort_compressed_rows(sorted_chunk, canonical_plan, source, target):
    """Validate every native row before the external physical-set sort."""
    with tempfile.NamedTemporaryFile(dir=target.parent, suffix=".jsonl", delete=False) as stream:
        plain = Path(stream.name)
    try:
        with gzip.open(source, "rt") as compressed, plain.open("wb") as stream:
            for line in compressed:
                if line.strip():
                    stream.write(canonical_plan(json.loads(line)) + b"\n")
        return sorted_chunk(plain, target, limit=1024)
    finally:
        plain.unlink(missing_ok=True)


def strict_rows(rows, canonical_plan):
    """Ensure the sorter preserved the exact identity checked on input."""
    for line in rows:
        if canonical_plan(json.loads(line)) + b"\n" != line:
            raise ValueError("sorted row differs from strict physical identity")
        yield line


def frozen_compiler_configuration(binding):
    """Derive the supported compile configuration from the frozen argv, not a receipt."""
    argv = binding.get("compilerArgv")
    if argv is None and binding.get("kind") != "campaign-compile-condition":
        return None
    if (not isinstance(argv, list) or len(argv) != 7 or
            argv[:3] != ["-exec", "singlenode", "-seed"] or
            argv[4:] != ["-noFedRuntimeConversion", "-stats", "100"] or
            not isinstance(argv[3], str) or not argv[3].isdecimal()):
        raise ValueError("unsupported frozen compiler argv")
    seed = int(argv[3])
    if seed > 2**31 - 1 or str(seed) != argv[3]:
        raise ValueError("frozen compiler seed is not a canonical signed int")
    return {"argvSha256": hashlib.sha256(json.dumps(
                argv, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest(),
            "execMode": "SINGLE_NODE", "seed": seed,
            "noFedRuntimeConversion": True, "statistics": True,
            "statisticsCount": 100,
            "appliedBefore": "PARSE_VALIDATE_CONSTRUCT_REWRITE"}


def check_frozen_model_settings(recorded, options, network, source,
                                compiler_argv=None, compiler_configuration=None):
    properties = dict(option[2:].split("=", 1) for option in options)
    if (recorded.get("compilerBoundary") != "POST_REWRITE_HOPS_DAG_PRE_PLANNER" or
            recorded.get("networkEnvironmentChecked") is not True or
            recorded.get("networkEnvironment") != network or
            recorded.get("workloadJvmProperties") != properties):
        raise ValueError(source + " frozen JVM/network condition differs")
    if compiler_configuration is not None and (
            recorded.get("compilerArgv") != compiler_argv or
            recorded.get("compilerConfiguration") != compiler_configuration):
        raise ValueError(source + " frozen compiler configuration differs")


def verify(run_dir, evaluation, verification, enforce_claim=True):
    for name in ("plan_space_verify", "closed_physical_identity"):
        loaded = sys.modules.get(name)
        expected = (verification / "calibration" / (name + ".py")).resolve()
        actual = getattr(loaded, "__file__", None)
        if loaded is not None and (actual is None or Path(actual).resolve() != expected):
            raise ValueError("different verification root requires a fresh Python process")
    certificate = read(run_dir / "certificate.json")
    physical_format = certificate.get("physicalFormat", "legacy-v1")
    if physical_format not in ("legacy-v1", "compact-v1"):
        raise ValueError("unknown physical shard format")
    compact = physical_format == "compact-v1"
    if compact:
        loaded = sys.modules.get("compact_physical_identity")
        expected = (verification / "calibration/compact_physical_identity.py").resolve()
        actual = getattr(loaded, "__file__", None)
        if loaded is not None and (actual is None or Path(actual).resolve() != expected):
            raise ValueError("different verification root requires a fresh Python process")
    sys.path.insert(0, str(verification / "calibration"))
    from plan_space_verify import sorted_chunk, iter_chunk, merged_chunks, compare
    from closed_physical_identity import canonical_plan
    if certificate.get("schema") != "current-pe-cell-certificate-v1":
        raise ValueError("wrong current P/E certificate schema")
    if (certificate.get("verificationRoot") != str(verification) or
        certificate.get("verifierSha256") != verifier_sha(verification, compact)):
        raise ValueError("independent verifier implementation changed")
    meta = read(run_dir / "run-meta.json")
    if (not isinstance(certificate.get("classTreeSha256"), str) or
        len(certificate["classTreeSha256"]) != 64 or
        meta.get("binding") != certificate.get("binding") or
        meta.get("sourceTreeSha256") != certificate.get("sourceTreeSha256") or
        meta.get("classTreeSha256") != certificate.get("classTreeSha256") or
        meta.get("evaluationRoot") != str(evaluation)):
        raise ValueError("run binding differs from certificate")
    cell = certificate["cell"]
    catalog_snapshot = run_dir / "catalog-snapshot.json"
    if not catalog_snapshot.is_file() or sha(catalog_snapshot) != meta.get("catalogSha256"):
        raise ValueError("frozen catalog snapshot missing or changed")
    files = check_frozen_inputs(catalog_snapshot, evaluation, cell)
    catalog_row = next(row for row in read(catalog_snapshot)["cells"] if row["id"] == cell)
    compiler_argv = catalog_row["sourceBinding"].get("compilerArgv")
    compiler_configuration = frozen_compiler_configuration(catalog_row["sourceBinding"])
    _, options, network = frozen_capture_settings(catalog_snapshot, evaluation, cell, {})
    binding_input = {"cell": cell, "source": meta["sourceTreeSha256"],
                     "classes": meta["classTreeSha256"],
                     "catalog": meta["catalogSha256"],
                     "evaluation": str(evaluation), "network": network}
    if options:
        binding_input["jvmOptions"] = options
    if compiler_configuration is not None:
        binding_input["compilerConfiguration"] = compiler_configuration
    if compact:
        binding_input["physicalFormat"] = "compact-v1"
    model_imports = meta.get("modelImports")
    if model_imports is not None:
        if (not isinstance(model_imports, dict) or set(model_imports) != {"p", "e"} or
                any(not isinstance(value, dict) for value in model_imports.values())):
            raise ValueError("model import provenance is incomplete")
        binding_input["modelImports"] = model_imports
    if hashlib.sha256(json.dumps(binding_input, sort_keys=True).encode()).hexdigest() != meta["binding"]:
        raise ValueError("frozen input and executable binding differs")
    p_model, e_model = (read(run_dir / f"{source}-model.receipt.json")
                        for source in ("p", "e"))
    if model_imports is not None:
        for source, model in (("p", p_model), ("e", e_model)):
            verify_model_import(source, model_imports[source], model, run_dir,
                                catalog_snapshot, evaluation, cell,
                                meta["sourceTreeSha256"], meta["classTreeSha256"])
    elif p_model.get("modelImport") is not None or e_model.get("modelImport") is not None:
        raise ValueError("unbound model import provenance")
    for source, model in (("p", p_model), ("e", e_model)):
        if (model.get("status") != "COMPLETE" or model.get("cell") != cell or
                model.get("artifactPath") != str(run_dir / f"{source}-model.json.gz")):
            raise ValueError("native model receipt incomplete")
        with gzip.open(model["artifactPath"], "rb") as stream:
            if hashlib.sha256(stream.read()).hexdigest() != model["artifactSha256"]:
                raise ValueError("native model artifact digest changed")
    native_p = json.loads(gzip.decompress((run_dir / "p-model.json.gz").read_bytes()))
    native_e = json.loads(gzip.decompress((run_dir / "e-model.json.gz").read_bytes()))
    for field in ("cell", "programSha256", "conditionSha256", "sourceFiles"):
        if native_p["summary"].get(field) != native_e.get(field):
            raise ValueError("P/E native model frozen input differs: " + field)
    if (native_e.get("sourceFiles") != files or
            native_e.get("conditionSha256") != catalog_row["sourceBinding"]["conditionSha256"]):
        raise ValueError("native model input differs from frozen catalog")
    for source, recorded in (("P", native_p["summary"]), ("E", native_e),
                             ("P receipt", p_model), ("E receipt", e_model)):
        check_frozen_model_settings(recorded, options, network, source,
                                    compiler_argv, compiler_configuration)
    p_structure = verify_p_model(run_dir / "p-model.json.gz", p_model["artifactSha256"])
    if p_structure["cell"] != cell or p_structure["raw"] != p_model["rawCount"]:
        raise ValueError("P native model structure and receipt differ")
    state_count = math.prod(len(domain[1]) for domain in native_p["nativeDomain"]["placementDomains"])
    p_paths = []
    p_identities = set()
    shard_count = 0
    cursor = raw = accepted = rejected = 0
    for shard in certificate["pShards"]:
        start, stop = int(shard["start"]), int(shard["stop"])
        if start != cursor or stop <= start or stop > state_count:
            raise ValueError("P state frontier gap, overlap, or empty shard")
        expected_name = f"p-state-{start}-{stop}.receipt.json"
        if shard["receipt"] != expected_name:
            raise ValueError("P shard receipt path differs from frontier")
        path = run_dir / shard["receipt"]
        if path.is_symlink():
            raise ValueError("P shard receipt is a symlink")
        if compact:
            checked = check_compact_receipt(
                path, "P_C0", cell, native_e, verification,
                run_dir / f"p-state-{start}-{stop}-dictionary.jsonl.gz",
                run_dir / f"p-state-{start}-{stop}-refs.tsv.gz", (start, stop))
            receipt = checked["receipt"]
            p_identities.update(checked["identities"])
        else:
            receipt = check_row_receipt(path, "P_C0", cell, native_e,
                                        (start, stop),
                                        run_dir / f"p-state-{start}-{stop}.jsonl.gz")
        if receipt.get("stateCount") != str(state_count):
            raise ValueError("P shard state domain differs from saved model")
        cursor = int(receipt["stop"])
        raw += int(receipt["raw"])
        accepted += int(receipt["accepted"])
        rejected += int(receipt["rejected"])
        if not compact:
            p_paths.append(Path(receipt["rows"]))
        shard_count += 1
    if cursor != state_count or not shard_count or raw != int(p_model["rawCount"]):
        raise ValueError("P state frontier or raw product incomplete")
    if (run_dir / "e-rows.receipt.json").is_symlink():
        raise ValueError("E shard receipt is a symlink")
    e_checked = None
    if compact:
        e_checked = check_compact_receipt(
            run_dir / "e-rows.receipt.json", "E_C0", cell, native_e, verification,
            run_dir / "e-dictionary.jsonl.gz", run_dir / "e-refs.tsv.gz")
        e_receipt = e_checked["receipt"]
    else:
        e_receipt = check_row_receipt(run_dir / "e-rows.receipt.json", "E_C0", cell,
                                      native_e, expected_rows=run_dir / "e-rows.jsonl.gz")
    if int(e_receipt["raw"]) != int(e_model["rawCount"]):
        raise ValueError("E model/raw receipt count differs")
    factor_result = verify_e_factors(run_dir / "e-model.json.gz",
                                     e_model["artifactSha256"],
                                     Path(e_receipt["rows"]),
                                     run_dir / "e-rows.receipt.json", e_checked)
    if compact:
        e_identities = set(e_checked["identities"])
        p_only = sorted(p_identities - e_identities)
        e_only = sorted(e_identities - p_identities)
        difference = {"leftOnly": len(p_only), "rightOnly": len(e_only),
                      "leftWitness": [json.loads(p_only[0])] if p_only else [],
                      "rightWitness": [json.loads(e_only[0])] if e_only else []}
        p_proofs, e_proofs = accepted, e_checked["proofCount"]
        p_unique, e_unique = len(p_identities), len(e_identities)
    else:
        with tempfile.TemporaryDirectory(dir=run_dir) as temporary:
            temporary = Path(temporary)
            p_sorted = []
            p_proofs = 0
            for index, path in enumerate(p_paths):
                target = temporary / f"p-{index}.jsonl.gz"
                count, _ = sort_compressed_rows(sorted_chunk, canonical_plan, path, target)
                p_proofs += count
                p_sorted.append(target)
            e_sorted = temporary / "e.jsonl.gz"
            e_proofs, _ = sort_compressed_rows(sorted_chunk, canonical_plan,
                                               Path(e_receipt["rows"]), e_sorted)
            difference = compare(strict_rows(merged_chunks(p_sorted), canonical_plan),
                                 strict_rows(iter_chunk(e_sorted), canonical_plan),
                                 witness_limit=1)
            p_unique = sum(1 for _ in strict_rows(merged_chunks(p_sorted), canonical_plan))
            e_unique = sum(1 for _ in strict_rows(iter_chunk(e_sorted), canonical_plan))
    if p_proofs != accepted or e_proofs != int(e_receipt["accepted"]):
        raise ValueError("sorted proof count differs from receipts")
    verdict = {
        "schema": "current-pe-cell-verification-v1", "cell": cell,
        "physicalFormat": physical_format,
        "claimScope": "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
        "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY",
        "status": "PASS" if not difference["leftOnly"] and not difference["rightOnly"] else "FAIL",
        "pRaw": str(raw), "pAccepted": str(accepted), "pRejected": str(rejected),
        "eRaw": e_receipt["raw"], "eAccepted": e_receipt["accepted"],
        "eRejected": e_receipt["rejected"], "pPhysical": p_unique,
        "ePhysical": e_unique, "pOnly": difference["leftOnly"],
        "eOnly": difference["rightOnly"], "witnesses": difference,
        "factorStatus": factor_result["status"],
        "pStructureStatus": p_structure["status"],
        "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT",
    }
    if enforce_claim:
        for key in ("claimScope", "pAcceptanceVerification", "status",
                    "pRaw", "pAccepted", "pRejected", "eRaw",
                    "eAccepted", "eRejected", "pPhysical", "ePhysical", "pOnly", "eOnly",
                    "pStructureStatus", "factorStatus"):
            if certificate.get(key) != verdict[key]:
                raise ValueError("stored certificate differs from independent recomputation: " + key)
        if certificate.get("physicalFormat", "legacy-v1") != physical_format:
            raise ValueError("stored certificate physical format differs")
    return verdict


def run(args):
    run_started = time.monotonic()
    elapsed = {}
    build = args.build_root.resolve()
    catalog = args.catalog.resolve()
    evaluation = args.evaluation_root.resolve()
    verification = args.verification_root.resolve()
    check_frozen_inputs(catalog, evaluation, args.cell)
    catalog_row = next(row for row in read(catalog)["cells"] if row["id"] == args.cell)
    compiler_configuration = frozen_compiler_configuration(catalog_row["sourceBinding"])
    environment, jvm_options, network = frozen_capture_settings(
        catalog, evaluation, args.cell, os.environ)
    if args.compile:
        process = subprocess.run(["mvn", "-q", "-DskipTests", "test-compile"],
                                 cwd=build, env=os.environ.copy())
        if process.returncode:
            raise RuntimeError("isolated Java build failed")
    source_sha = tree_sha(build)
    classes_sha = class_tree_sha(build)
    binding_input = {
        "cell": args.cell, "source": source_sha, "classes": classes_sha,
        "catalog": sha(catalog),
        "evaluation": str(evaluation),
        "network": network,
    }
    if jvm_options:
        binding_input["jvmOptions"] = jvm_options
    if compiler_configuration is not None:
        binding_input["compilerConfiguration"] = compiler_configuration
    if args.compact:
        binding_input["physicalFormat"] = "compact-v1"
    import_paths = (getattr(args, "p_matrix_dir", None),
                    getattr(args, "e_matrix_dir", None))
    if (import_paths[0] is None) != (import_paths[1] is None):
        raise ValueError("P and E matrix imports must be supplied together")
    model_imports = None
    imported_native = {}
    if import_paths[0] is not None:
        model_imports = {}
        for name, matrix_dir in zip(("p", "e"), import_paths):
            descriptor, native = describe_model_import(
                name, matrix_dir, catalog, evaluation, args.cell, source_sha, classes_sha)
            model_imports[name] = descriptor
            imported_native[name] = native
        binding_input["modelImports"] = model_imports
    binding = hashlib.sha256(json.dumps(binding_input, sort_keys=True).encode()).hexdigest()
    run_dir = args.artifact_root.resolve() / args.cell / binding[:20]
    run_dir.mkdir(parents=True, exist_ok=True)
    catalog_snapshot = run_dir / "catalog-snapshot.json"
    if catalog_snapshot.exists() and sha(catalog_snapshot) != sha(catalog):
        raise ValueError("run catalog snapshot changed")
    if not catalog_snapshot.exists():
        with tempfile.NamedTemporaryFile("wb", dir=run_dir, delete=False) as stream:
            temporary = Path(stream.name)
            with catalog.open("rb") as source:
                shutil.copyfileobj(source, stream)
        temporary.replace(catalog_snapshot)
    meta_path = run_dir / "run-meta.json"
    meta = {"schema": "current-pe-cell-run-v1", "cell": args.cell,
            "sourceTreeSha256": source_sha, "catalogSha256": sha(catalog),
            "classTreeSha256": classes_sha,
            "evaluationRoot": str(evaluation), "binding": binding}
    if model_imports is not None:
        meta["modelImports"] = model_imports
    if meta_path.exists() and read(meta_path) != meta:
        raise ValueError("resume binding changed")
    save(meta_path, meta)
    def capture(name, klass):
        if model_imports is not None:
            return import_model(name, model_imports[name], imported_native[name], run_dir)
        receipt_path = run_dir / f"{name}-model.receipt.json"
        artifact = run_dir / f"{name}-model.json.gz"
        if args.resume and receipt_path.exists() and artifact.exists():
            saved = read(receipt_path)
            with gzip.open(artifact, "rb") as stream:
                if hashlib.sha256(stream.read()).hexdigest() == saved.get("artifactSha256"):
                    return saved
        result = java(build, environment, klass, catalog, evaluation, args.cell, artifact,
                      jvm_options=jvm_options)
        if result.get("status") != "COMPLETE":
            raise RuntimeError(f"{name} model capture: {result}")
        save(receipt_path, result)
        return result
    stage_started = time.monotonic()
    p_model = capture("p", "org.apache.sysds.test.component.federated.placement.shadow.PlanningNativeModelCapture")
    elapsed['pCaptureSeconds'] = round(time.monotonic() - stage_started, 3)
    stage_started = time.monotonic()
    e_model = capture("e", "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPlanningModelCapture")
    elapsed['eCaptureSeconds'] = round(time.monotonic() - stage_started, 3)
    native_p = json.loads(gzip.decompress((run_dir / "p-model.json.gz").read_bytes()))
    native_e = json.loads(gzip.decompress((run_dir / "e-model.json.gz").read_bytes()))
    state_count = math.prod(len(domain[1]) for domain in native_p["nativeDomain"]["placementDomains"])
    e_raw = int(e_model["rawCount"])
    if state_count > args.state_budget or e_raw > args.e_raw_budget:
        save(run_dir / "incomplete.json", {"status": "INCOMPLETE",
             "reason": "native frontier exceeds bounded exact-enumeration budget",
             "pStateCount": str(state_count), "pStateBudget": args.state_budget,
             "eRawCount": str(e_raw), "eRawBudget": args.e_raw_budget,
             "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT"})
        print(json.dumps(read(run_dir / "incomplete.json"), sort_keys=True))
        return 2
    ranges = [(start, min(start + args.shard_size, state_count))
              for start in range(0, state_count, args.shard_size)]
    def p_shard(start, stop):
        rows = run_dir / (f"p-state-{start}-{stop}-dictionary.jsonl.gz" if args.compact
                          else f"p-state-{start}-{stop}.jsonl.gz")
        references = run_dir / f"p-state-{start}-{stop}-refs.tsv.gz"
        receipt = run_dir / f"p-state-{start}-{stop}.receipt.json"
        if args.resume and rows.exists() and receipt.exists() and (
                not args.compact or references.exists()):
            if args.compact:
                check_compact_receipt(receipt, "P_C0", args.cell, native_e,
                                      verification, rows, references, (start, stop))
            else:
                check_row_receipt(receipt, "P_C0", args.cell, native_e,
                                  (start, stop), rows)
            return
        java(build, environment,
             "org.apache.sysds.test.component.federated.placement.shadow.CurrentPlanningPhysicalRows",
             catalog, evaluation, args.cell, start, stop, rows,
             *((references,) if args.compact else ()), receipt,
             jvm_options=jvm_options)
    stage_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(p_shard, start, stop) for start, stop in ranges]
        for future in as_completed(futures):
            future.result()
    elapsed['pPhysicalSeconds'] = round(time.monotonic() - stage_started, 3)
    e_rows = run_dir / ("e-dictionary.jsonl.gz" if args.compact else "e-rows.jsonl.gz")
    e_references = run_dir / "e-refs.tsv.gz"
    e_receipt = run_dir / "e-rows.receipt.json"
    stage_started = time.monotonic()
    if not (args.resume and e_rows.exists() and e_receipt.exists() and
            (not args.compact or e_references.exists())):
        java(build, environment,
             "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPlanningPhysicalRows",
             catalog, evaluation, args.cell, e_rows,
             *((e_references,) if args.compact else ()), e_receipt,
             jvm_options=jvm_options)
    elapsed['ePhysicalSeconds'] = round(time.monotonic() - stage_started, 3)
    if source_sha != tree_sha(build) or classes_sha != class_tree_sha(build):
        raise ValueError("isolated build changed during native capture")
    shards = [{"receipt": f"p-state-{start}-{stop}.receipt.json",
               "start": str(start), "stop": str(stop)} for start, stop in ranges]
    seed = {"schema": "current-pe-cell-certificate-v1", "cell": args.cell,
            "binding": binding, "sourceTreeSha256": source_sha,
            "classTreeSha256": classes_sha,
            "physicalFormat": "compact-v1" if args.compact else "legacy-v1",
            "verificationRoot": str(verification),
            "verifierSha256": verifier_sha(verification, args.compact), "pShards": shards}
    save(run_dir / "certificate.json", seed)
    # The verifier deliberately rereads stored gzip artifacts in a separate pass.
    stage_started = time.monotonic()
    verdict = verify(run_dir, evaluation, verification, enforce_claim=False)
    elapsed['verificationSeconds'] = round(time.monotonic() - stage_started, 3)
    if source_sha != tree_sha(build) or classes_sha != class_tree_sha(build):
        raise ValueError("isolated build changed during independent verification")
    certificate = dict(seed)
    certificate.update({key: verdict[key] for key in
                        ("claimScope", "pAcceptanceVerification", "status",
                         "pRaw", "pAccepted", "pRejected", "eRaw",
                         "eAccepted", "eRejected", "pPhysical", "ePhysical", "pOnly", "eOnly",
                         "pStructureStatus", "factorStatus")})
    save(run_dir / "certificate.json", certificate)
    save(run_dir / 'profile.json', {
        'schema': 'current-pe-cell-profile-v1', 'cell': args.cell,
        'binding': binding, 'resumeRequested': args.resume,
        'stageWallSeconds': elapsed,
        'totalWallSeconds': round(time.monotonic() - run_started, 3),
        'peakChildRSSKiB': resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
        'artifactBytes': sum(path.stat().st_size for path in run_dir.iterdir()
                             if path.is_file()),
        'pAcceptedProofs': verdict['pAccepted'], 'pPhysical': verdict['pPhysical'],
        'eAcceptedProofs': verdict['eAccepted'], 'ePhysical': verdict['ePhysical'],
    })
    print(json.dumps({"runDir": str(run_dir), **verdict}, sort_keys=True))
    return 0 if verdict["status"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--verification-root", type=Path, required=True)
    parser.add_argument("--cell")
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--p-matrix-dir", type=Path,
                        help="verified P model matrix to import without Java recapture")
    parser.add_argument("--e-matrix-dir", type=Path,
                        help="verified E model matrix to import without Java recapture")
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--state-budget", type=int, default=1000)
    parser.add_argument("--e-raw-budget", type=int, default=1000000)
    parser.add_argument("--shard-size", type=int, default=16)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--compact", action="store_true",
                        help="store unique full physical rows with ordinal/reference stream")
    parser.add_argument("--compile", action="store_true")
    args = parser.parse_args()
    if args.mode == "verify":
        if args.run_dir is None:
            parser.error("--run-dir required for verify")
        result = verify(args.run_dir.resolve(), args.evaluation_root.resolve(),
                        args.verification_root.resolve())
        print(json.dumps(result, sort_keys=True))
        return 0 if result["status"] == "PASS" else 1
    if any(value is None for value in (args.build_root, args.catalog, args.cell, args.artifact_root)):
        parser.error("run needs --build-root --catalog --cell --artifact-root")
    if (args.p_matrix_dir is None) != (args.e_matrix_dir is None):
        parser.error("--p-matrix-dir and --e-matrix-dir must be supplied together")
    if min(args.jobs, args.state_budget, args.e_raw_budget, args.shard_size) < 1:
        parser.error("jobs, state/raw budgets, and shard size must be positive")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
