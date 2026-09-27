#!/usr/bin/env python3
"""Freeze, run, and independently recheck the remaining medium Pca cells.

The frontier is the exact set of in-scope ``base:ml:pca`` and ``ml10:pca``
cells whose verified E model has raw count 2,057,529,600, less the four
separately-run pilot cells.  A successful result proves equality of captured
native physical sets; P acceptance remains producer-receipt-only.
"""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile


SCRIPT = Path(__file__).resolve()
CELL_RUNNER = SCRIPT.with_name("run_current_pe_cell.py")
TARGET_GROUPS = {"base:ml:pca", "ml10:pca"}
E_RAW = "2057529600"
EXCLUDED_PILOTS = {
    "cell_capture_9448acdafce72420f2e4",
    "cell_capture_dea2495740c271036724",
    "cell_capture_888c4bdf76e7dd85061a",
    "cell_capture_9ef53013a945fc28cd9b",
}
JVM_RESERVATION_GIB = 5


def sha(path):
    import hashlib
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


RUNNER_SHA = sha(SCRIPT)


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as stream:
        json.dump(value, stream, sort_keys=True, indent=2)
        stream.write("\n")
        temporary = Path(stream.name)
    temporary.replace(path)


def tree_sha(root):
    import hashlib
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
    import hashlib
    files = []
    for folder, suffix in (("target/classes", None), ("target/test-classes", None),
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


def run_command(argv, timeout):
    process = subprocess.Popen(
        argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        start_new_session=True,
        env={key: value for key, value in os.environ.items()
             if key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")})
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        _, stderr = process.communicate()
        return 124, None, stderr[-3000:]
    try:
        payload = json.loads(stdout.strip().splitlines()[-1])
    except (IndexError, json.JSONDecodeError):
        payload = None
    return process.returncode, payload, stderr[-3000:]


@contextmanager
def isolated_python_bytecode(artifact_root):
    """Force child imports through a fresh empty cache and restore the environment."""
    artifact_root.mkdir(parents=True, exist_ok=True)
    names = ("PYTHONPYCACHEPREFIX", "PYTHONDONTWRITEBYTECODE")
    missing = object()
    previous = {name: os.environ.get(name, missing) for name in names}
    try:
        with tempfile.TemporaryDirectory(prefix=".medium-pca-pycache-",
                                         dir=artifact_root) as cache:
            os.environ["PYTHONPYCACHEPREFIX"] = cache
            os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
            yield Path(cache)
    finally:
        for name, value in previous.items():
            if value is missing:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


@contextmanager
def exclusive_execution_lock(artifact_root, result_dir, worklist):
    """Exclude overlapping run/verify processes for either output root."""
    roots = sorted({artifact_root.resolve(), result_dir.resolve()}, key=str)
    handles = []
    metadata = json.dumps({"schema": "current-pe-medium-pca-lock-v1",
                           "worklist": str(worklist.resolve()),
                           "worklistSha256": sha(worklist), "pid": os.getpid()},
                          sort_keys=True)
    try:
        for root in roots:
            root.mkdir(parents=True, exist_ok=True)
            handle = (root / ".current-pe-medium-pca.lock").open("a+")
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                handle.close()
                raise ValueError("medium Pca output root is locked by another process: " +
                                 str(root)) from error
            handle.seek(0)
            handle.truncate()
            handle.write(metadata + "\n")
            handle.flush()
            os.fsync(handle.fileno())
            handles.append(handle)
        yield
    finally:
        for handle in reversed(handles):
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            handle.close()


def _matrix_inputs(catalog, p_matrix_dir, e_matrix_dir, build=None):
    paths = {
        "catalogSha256": catalog,
        "pMatrixSha256": p_matrix_dir / "matrix.json",
        "eMatrixSha256": e_matrix_dir / "matrix.json",
        "pVerificationSha256": p_matrix_dir / "verification.json",
        "eVerificationSha256": e_matrix_dir / "verification.json",
    }
    binding = {key: sha(path) for key, path in paths.items()}
    p_matrix, e_matrix = read(paths["pMatrixSha256"]), read(paths["eMatrixSha256"])
    p_verify = read(paths["pVerificationSha256"])
    e_verify = read(paths["eVerificationSha256"])
    source, classes = p_matrix.get("sourceTreeSha256"), p_matrix.get("classTreeSha256")
    p_cells, e_cells = p_matrix.get("cells"), e_matrix.get("cells")
    if (p_matrix.get("schema") != "current-p-matrix-capture-v1" or
            e_matrix.get("schema") != "current-e-model-matrix-v1" or
            p_matrix.get("status") != e_matrix.get("status") or
            p_matrix.get("status") != "COMPLETE" or
            p_matrix.get("cellCount") != e_matrix.get("cellCount") or
            p_matrix.get("cellCount") != 612 or
            p_matrix.get("counts") != e_matrix.get("counts") or
            p_matrix.get("counts") != {"COMPLETE": 612} or
            not isinstance(p_cells, list) or not isinstance(e_cells, list) or
            len(p_cells) != 612 or len(e_cells) != 612 or
            {row.get("cell") for row in p_cells} != {row.get("cell") for row in e_cells} or
            any(row.get("status") != "COMPLETE" for row in p_cells + e_cells) or
            p_verify.get("status") != e_verify.get("status") or
            p_verify.get("status") != "PASS" or
            p_verify.get("cellCount") != 612 or e_verify.get("cellCount") != 612 or
            p_verify.get("counts") != {"COMPLETE": 612} or
            e_verify.get("counts") != {"COMPLETE": 612} or
            p_verify.get("matrixSha256") != binding["pMatrixSha256"] or
            e_verify.get("matrixSha256") != binding["eMatrixSha256"] or
            p_verify.get("verifiedComplete") != 612 or
            e_verify.get("verifiedComplete") != 612 or
            e_verify.get("verifiedKnown") != 612 or
            p_verify.get("failures") or e_verify.get("failures") or
            e_verify.get("unknownFactorCells") or
            e_matrix.get("binding", {}).get("sourceTreeSha256") != source or
            e_matrix.get("binding", {}).get("classTreeSha256") != classes or
            (build is not None and
             (tree_sha(build) != source or class_tree_sha(build) != classes))):
        raise ValueError("medium Pca frontier is not bound to verified 612-cell models")
    binding.update({"sourceTreeSha256": source, "classTreeSha256": classes,
                    "runnerSha256": RUNNER_SHA})
    return binding


def derive_frontier(catalog, p_matrix_dir, e_matrix_dir):
    catalog_rows = read(catalog).get("cells")
    if not isinstance(catalog_rows, list):
        raise ValueError("catalog has no cells")
    by_id = {row.get("id"): row for row in catalog_rows}
    if len(by_id) != len(catalog_rows) or None in by_id:
        raise ValueError("catalog cell IDs are missing or duplicated")
    matrix_rows = read(p_matrix_dir / "matrix.json").get("cells")
    matrix_ids = {row.get("cell") for row in matrix_rows or []}
    if len(matrix_ids) != 612 or None in matrix_ids:
        raise ValueError("P matrix frontier is missing or duplicated")
    target = []
    for cell in sorted(matrix_ids):
        row = by_id.get(cell)
        if row is None:
            raise ValueError("P matrix cell is absent from catalog: " + cell)
        if row.get("inventoryStatus") != "IN_SCOPE" or row.get("discoveryId") not in TARGET_GROUPS:
            continue
        p_receipt = read(p_matrix_dir / cell / "receipt.json")
        e_receipt = read(e_matrix_dir / cell / "receipt.json")
        if p_receipt.get("status") != "COMPLETE" or e_receipt.get("status") != "COMPLETE":
            raise ValueError("target medium Pca cell has incomplete model receipt: " + cell)
        if e_receipt.get("native", {}).get("rawCount") == E_RAW:
            target.append({"cell": cell, "discoveryId": row["discoveryId"],
                           "conditionId": row["conditionId"], "eRawCount": E_RAW,
                           "pReceiptSha256": sha(p_matrix_dir / cell / "receipt.json"),
                           "eReceiptSha256": sha(e_matrix_dir / cell / "receipt.json")})
    target.sort(key=lambda row: row["cell"])
    target_ids = {row["cell"] for row in target}
    if len(target) != 20 or not EXCLUDED_PILOTS <= target_ids:
        raise ValueError("verified base/ML10 medium Pca target frontier is not exactly 20 cells")
    selected = [row for row in target if row["cell"] not in EXCLUDED_PILOTS]
    if len(selected) != 16 or {row["cell"] for row in selected} != target_ids - EXCLUDED_PILOTS:
        raise ValueError("remaining medium Pca frontier is not exactly target minus four pilots")
    return target, selected


def freeze_worklist(args):
    binding = _matrix_inputs(args.catalog, args.p_matrix_dir, args.e_matrix_dir)
    target, selected = derive_frontier(args.catalog, args.p_matrix_dir, args.e_matrix_dir)
    value = {"schema": "current-pe-medium-pca16-worklist-v1",
             "status": "FROZEN_INPUT_WORKLIST_ONLY",
             **binding, "targetDefinition": {
                 "discoveryIds": sorted(TARGET_GROUPS), "eRawCount": E_RAW,
                 "targetCellCount": 20,
                 "excludedPilotCells": sorted(EXCLUDED_PILOTS)},
             "cellCount": 16, "targetCells": target, "cells": selected}
    save(args.worklist, value)
    return value


def validate_worklist(worklist, catalog, p_matrix_dir, e_matrix_dir, build=None):
    if (worklist.get("schema") != "current-pe-medium-pca16-worklist-v1" or
            worklist.get("status") != "FROZEN_INPUT_WORKLIST_ONLY" or
            worklist.get("cellCount") != 16 or
            worklist.get("runnerSha256") != RUNNER_SHA):
        raise ValueError("medium Pca worklist schema, status, denominator, or runner differs")
    binding = _matrix_inputs(catalog, p_matrix_dir, e_matrix_dir, build)
    if any(worklist.get(key) != value for key, value in binding.items()):
        raise ValueError("medium Pca worklist input binding changed")
    target, selected = derive_frontier(catalog, p_matrix_dir, e_matrix_dir)
    definition = worklist.get("targetDefinition")
    expected_definition = {"discoveryIds": sorted(TARGET_GROUPS), "eRawCount": E_RAW,
                           "targetCellCount": 20,
                           "excludedPilotCells": sorted(EXCLUDED_PILOTS)}
    if definition != expected_definition or worklist.get("targetCells") != target or \
            worklist.get("cells") != selected:
        raise ValueError("worklist frontier differs from all remaining base/ML10 medium Pca cells")
    return [row["cell"] for row in selected], binding


def verify_cell(cell, run_dir, args):
    if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
        raise ValueError("medium Pca run directory escapes artifact root")
    code, verdict, error = run_command(
        [sys.executable, str(CELL_RUNNER), "verify", "--run-dir", str(run_dir),
         "--evaluation-root", str(args.evaluation_root),
         "--verification-root", str(args.verification_root)], args.timeout)
    if (not isinstance(verdict, dict) or verdict.get("cell") != cell or
            verdict.get("status") not in ("PASS", "FAIL") or
            code != (0 if verdict.get("status") == "PASS" else 1) or
            verdict.get("claimScope") != "CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY" or
            verdict.get("pAcceptanceVerification") != "PRODUCER_RECEIPT_ONLY" or
            verdict.get("pStructureStatus") != "STRUCTURE_VERIFIED" or
            verdict.get("factorStatus") != "INDEPENDENT_FACTOR_TABLE_VERIFIED" or
            (verdict.get("status") == "PASS" and
             (verdict.get("pOnly") != 0 or verdict.get("eOnly") != 0 or
              verdict.get("pPhysical") != verdict.get("ePhysical"))) or
            (verdict.get("status") == "FAIL" and
             verdict.get("pOnly") == verdict.get("eOnly") == 0)):
        raise ValueError("medium Pca artifact-only verdict is invalid: " + error[:300])
    return verdict


def result_from_verdict(cell, run_dir, verdict):
    return {"cell": cell,
            "status": "CAPTURED_EQUAL" if verdict["status"] == "PASS" else "DIFFERENT",
            "runDir": str(run_dir), "certificateSha256": sha(run_dir / "certificate.json"),
            "pPhysical": verdict["pPhysical"], "ePhysical": verdict["ePhysical"],
            "pOnly": verdict["pOnly"], "eOnly": verdict["eOnly"],
            "pAccepted": verdict["pAccepted"], "eAccepted": verdict["eAccepted"]}


def verify_one(cell, args):
    try:
        cell_root = args.artifact_root / cell
        run_dirs = [path.parent for path in cell_root.glob("*/certificate.json")]
        if not run_dirs:
            return {"cell": cell, "status": "INCOMPLETE", "reason": "CERTIFICATE_MISSING"}
        if len(run_dirs) != 1:
            return {"cell": cell, "status": "ERROR", "reason": "CERTIFICATE_AMBIGUOUS"}
        run_dir = run_dirs[0].resolve()
        return result_from_verdict(cell, run_dir, verify_cell(cell, run_dir, args))
    except Exception as error:
        return {"cell": cell, "status": "ERROR", "reason": str(error)[:1000]}


def run_one(cell, args):
    try:
        argv = [sys.executable, str(CELL_RUNNER), "run", "--build-root", str(args.build_root),
                "--catalog", str(args.catalog), "--evaluation-root", str(args.evaluation_root),
                "--verification-root", str(args.verification_root),
                "--artifact-root", str(args.artifact_root),
                "--p-matrix-dir", str(args.p_matrix_dir),
                "--e-matrix-dir", str(args.e_matrix_dir), "--cell", cell,
                "--jobs", "2", "--state-budget", "128", "--e-raw-budget", E_RAW,
                "--shard-size", "16", "--compact", "--resume"]
        code, native, error = run_command(argv, args.timeout)
        if code == 124:
            return {"cell": cell, "status": "INCOMPLETE", "reason": "CELL_TIMEOUT"}
        if code == 2 and isinstance(native, dict) and native.get("status") == "INCOMPLETE":
            return {"cell": cell, "status": "INCOMPLETE",
                    "reason": native.get("reason", "NATIVE_FRONTIER_INCOMPLETE"),
                    "pStateCount": native.get("pStateCount"),
                    "eRawCount": native.get("eRawCount")}
        if (not isinstance(native, dict) or native.get("cell") != cell or
                native.get("status") not in ("PASS", "FAIL") or
                code != (0 if native.get("status") == "PASS" else 1)):
            raise ValueError("medium Pca native capture failed: " + error[:300])
        run_dir = Path(native["runDir"]).resolve()
        verdict = verify_cell(cell, run_dir, args)
        if verdict["status"] != native["status"]:
            raise ValueError("native and artifact-only verdicts differ")
        return result_from_verdict(cell, run_dir, verdict)
    except Exception as error:
        return {"cell": cell, "status": "ERROR", "reason": str(error)[:1000]}


def check_result_bindings(results, args, binding):
    for row in results:
        if row["status"] not in ("CAPTURED_EQUAL", "DIFFERENT"):
            continue
        meta = read(Path(row["runDir"]) / "run-meta.json")
        imports = meta.get("modelImports", {})
        if (meta.get("cell") != row["cell"] or
                meta.get("sourceTreeSha256") != binding["sourceTreeSha256"] or
                meta.get("classTreeSha256") != binding["classTreeSha256"] or
                meta.get("catalogSha256") != binding["catalogSha256"] or
                imports.get("p", {}).get("matrixDir") != str(args.p_matrix_dir) or
                imports.get("e", {}).get("matrixDir") != str(args.e_matrix_dir) or
                imports.get("p", {}).get("matrixManifestSha256") != binding["pMatrixSha256"] or
                imports.get("e", {}).get("matrixManifestSha256") != binding["eMatrixSha256"] or
                imports.get("p", {}).get("matrixVerificationSha256") !=
                binding["pVerificationSha256"] or
                imports.get("e", {}).get("matrixVerificationSha256") !=
                binding["eVerificationSha256"]):
            raise ValueError("medium Pca result import binding differs: " + row["cell"])


def summarize(cells, results, binding, resources=None):
    by_cell = {row.get("cell"): row for row in results}
    if len(by_cell) != len(results) or set(by_cell) != set(cells):
        raise ValueError("medium Pca result frontier differs from worklist")
    counts = dict(sorted(Counter(row["status"] for row in results).items()))
    status = ("ERROR" if counts.get("ERROR") else
              "DIFFERENT" if counts.get("DIFFERENT") else
              "CAPTURED_EQUAL" if counts.get("CAPTURED_EQUAL") == len(cells) else "INCOMPLETE")
    value = {"schema": "current-pe-medium-pca16-summary-v1", "status": status,
             "claimScope": "FROZEN_REMAINING_BASE_ML10_PCA_CAPTURED_NATIVE_PHYSICAL_SET_EQUALITY",
             "pAcceptanceVerification": "PRODUCER_RECEIPT_ONLY", "cellCount": len(cells),
             "counts": counts, "inputBinding": binding,
             "cells": [by_cell[cell] for cell in cells]}
    if resources is not None:
        value["resourcePreflight"] = resources
    return value


def resource_preflight(args):
    if args.jobs > 4 or args.jobs * 2 > args.max_jvms or args.max_jvms > 8:
        raise ValueError("medium Pca parallelism exceeds four cells/eight JVMs")
    available_kib = next((int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()
                          if line.startswith("MemAvailable:")), None)
    disk_root = args.artifact_root
    while not disk_root.exists():
        disk_root = disk_root.parent
    if available_kib is None:
        raise ValueError("host available memory is unavailable")
    snapshot = {"cpuCount": os.cpu_count(), "availableRamGiB": available_kib / 1024 / 1024,
                "freeDiskGiB": shutil.disk_usage(disk_root).free / 1024**3}
    if args.jobs * 2 * JVM_RESERVATION_GIB > snapshot["availableRamGiB"]:
        raise ValueError("medium Pca JVM reservation exceeds available RAM")
    if snapshot["freeDiskGiB"] < args.min_free_disk_gib:
        raise ValueError("medium Pca artifact filesystem is below free-disk reserve")
    return {"schema": "current-pe-medium-pca-resource-preflight-v1",
            "binding": {"cellJobs": args.jobs, "shardJobs": 2, "maxJvms": args.max_jvms,
                        "minFreeDiskGiB": args.min_free_disk_gib,
                        "jvmReservationGiB": JVM_RESERVATION_GIB}, "measured": snapshot}


def _execute(args, cells, binding):
    resources = resource_preflight(args) if args.mode == "run" else None
    handler = run_one if args.mode == "run" else verify_one
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(handler, cell, args): cell for cell in cells}
        results = []
        for future in as_completed(futures):
            row = future.result()
            results.append(row)
            print(json.dumps({"cell": row["cell"], "status": row["status"]}, sort_keys=True),
                  flush=True)
    if sha(SCRIPT) != RUNNER_SHA:
        raise ValueError("medium Pca runner changed during execution")
    final_cells, final_binding = validate_worklist(read(args.worklist), args.catalog,
                                                   args.p_matrix_dir, args.e_matrix_dir,
                                                   args.build_root if args.mode == "run" else None)
    final_binding["worklistSha256"] = sha(args.worklist)
    if final_cells != cells or final_binding != binding:
        raise ValueError("medium Pca inputs changed during execution")
    check_result_bindings(results, args, binding)
    if args.mode == "run":
        for row in results:
            if row["status"] in ("CAPTURED_EQUAL", "DIFFERENT") and verify_one(row["cell"], args) != row:
                raise ValueError("medium Pca cell changed during final recheck: " + row["cell"])
    summary = summarize(cells, results, binding, resources)
    args.result_dir.mkdir(parents=True, exist_ok=True)
    if args.mode == "verify":
        previous = read(args.result_dir / "summary.json")
        comparable = dict(previous)
        comparable.pop("resourcePreflight", None)
        if comparable != summary:
            raise ValueError("medium Pca offline summary differs from stored run")
        target = args.result_dir / "summary-verification.json"
    else:
        target = args.result_dir / "summary.json"
    save(target, summary)
    print(json.dumps({"status": summary["status"], "counts": summary["counts"]}, sort_keys=True),
          flush=True)
    return 0 if summary["status"] == "CAPTURED_EQUAL" else \
        1 if summary["status"] in ("ERROR", "DIFFERENT") else 2


def execute(args, cells, binding):
    with exclusive_execution_lock(args.artifact_root, args.result_dir, args.worklist):
        with isolated_python_bytecode(args.artifact_root):
            return _execute(args, cells, binding)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("freeze", "run", "verify"))
    for name in ("worklist", "catalog", "p-matrix-dir", "e-matrix-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("evaluation-root", "verification-root", "artifact-root", "result-dir"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--max-jvms", type=int, default=8)
    parser.add_argument("--timeout", type=int, default=28800)
    parser.add_argument("--min-free-disk-gib", type=int, default=20)
    args = parser.parse_args()
    for name, value in vars(args).items():
        if isinstance(value, Path):
            setattr(args, name, value.resolve())
    if min(args.jobs, args.max_jvms, args.timeout) < 1 or args.min_free_disk_gib < 0:
        parser.error("jobs, JVM limit, and timeout must be positive")
    if args.jobs > 4 or args.max_jvms > 8 or args.jobs * 2 > args.max_jvms:
        parser.error("parallelism must reserve at most four cells and eight JVMs")
    if args.mode == "freeze":
        value = freeze_worklist(args)
        print(json.dumps({"status": value["status"], "cellCount": value["cellCount"]},
                         sort_keys=True))
        return 0
    required = (args.evaluation_root, args.verification_root, args.artifact_root, args.result_dir)
    if any(value is None for value in required) or (args.mode == "run" and args.build_root is None):
        parser.error("run/verify require evaluation, verification, artifact, and result roots; run also requires build")
    cells, binding = validate_worklist(read(args.worklist), args.catalog, args.p_matrix_dir,
                                       args.e_matrix_dir,
                                       args.build_root if args.mode == "run" else None)
    return execute(args, cells, {**binding, "worklistSha256": sha(args.worklist)})


if __name__ == "__main__":
    raise SystemExit(main())
