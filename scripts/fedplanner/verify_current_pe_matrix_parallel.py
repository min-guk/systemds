#!/usr/bin/env python3
"""Verify captured current P/E cells in parallel with an attested bound runner."""

import argparse
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import importlib.machinery
import json
import os
from pathlib import Path
import shutil
import tempfile
import types
import sys


AUDITOR = Path(__file__).resolve()
BOUND_RUNNER = AUDITOR.with_name("run_current_pe_matrix.py")
ATTESTATION_SCHEMA = "current-pe-parallel-verification-v1"
MAX_VERIFICATION_JOBS = 16
COMPRESSED_ARTIFACT_EXPANSION = 8.0


def _file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _read_attested_json(path):
    path = Path(path).resolve()
    payload = path.read_bytes()
    return json.loads(payload), hashlib.sha256(payload).hexdigest()


def _attest_file(path, expected=None):
    path = Path(path).resolve()
    if not path.is_file() or path.is_symlink():
        raise ValueError("attested source is absent or symbolic: " + str(path))
    digest = _file_sha(path)
    if expected is not None and digest != expected:
        raise ValueError("attested source digest differs: " + str(path))
    return digest


def load_bound_runner(path, expected_sha):
    """Compile the exact attested source bytes, bypassing imports and pyc files."""
    path = Path(path).resolve()
    _attest_file(path, expected_sha)
    source = path.read_bytes()
    if hashlib.sha256(source).hexdigest() != expected_sha:
        raise ValueError("bound runner changed while being read")
    module = types.ModuleType("_attested_run_current_pe_matrix")
    module.__file__ = str(path)
    module.__package__ = ""
    exec(compile(source, str(path), "exec", dont_inherit=True), module.__dict__)
    _attest_file(path, expected_sha)
    if Path(module.SCRIPT).resolve() != path:
        raise ValueError("bound runner reports a different source path")
    return module


def _module_source_snapshot(root):
    root = Path(root).resolve()
    snapshot = {}
    for module in tuple(sys.modules.values()):
        source = getattr(module, "__file__", None)
        if not source:
            continue
        path = Path(source).resolve()
        if path.suffix == ".py" and path.is_relative_to(root) and path != AUDITOR:
            snapshot[str(path)] = _attest_file(path)
    return dict(sorted(snapshot.items()))


@contextmanager
def source_only_imports(root):
    """Force local imports to compile source in an isolated empty cache tree."""
    root = Path(root).resolve()
    displaced = {}
    for name, module in tuple(sys.modules.items()):
        source = getattr(module, "__file__", None)
        if source and Path(source).resolve() != AUDITOR and Path(source).resolve().is_relative_to(root):
            displaced[name] = sys.modules.pop(name)
    old_path = list(sys.path)
    old_meta_path = list(sys.meta_path)
    old_path_hooks = list(sys.path_hooks)
    old_importer_cache = dict(sys.path_importer_cache)
    old_prefix = sys.pycache_prefix
    old_write = sys.dont_write_bytecode
    old_environment = {
        name: (name in os.environ, os.environ.get(name))
        for name in ("PYTHONPYCACHEPREFIX", "PYTHONDONTWRITEBYTECODE")
    }
    with tempfile.TemporaryDirectory(prefix="current-pe-source-cache-") as cache:
        sys.path.insert(0, str(root))
        sys.meta_path[:] = [importlib.machinery.BuiltinImporter,
                            importlib.machinery.FrozenImporter,
                            importlib.machinery.PathFinder]
        sys.path_hooks[:] = [importlib.machinery.FileFinder.path_hook(
            (importlib.machinery.SourceFileLoader,
             importlib.machinery.SOURCE_SUFFIXES),
            (importlib.machinery.ExtensionFileLoader,
             importlib.machinery.EXTENSION_SUFFIXES))]
        sys.path_importer_cache.clear()
        sys.pycache_prefix = cache
        sys.dont_write_bytecode = True
        os.environ["PYTHONPYCACHEPREFIX"] = cache
        os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
        try:
            yield
        finally:
            for name, module in tuple(sys.modules.items()):
                source = getattr(module, "__file__", None)
                if source and Path(source).resolve().is_relative_to(root) and name not in displaced:
                    sys.modules.pop(name, None)
            sys.modules.update(displaced)
            sys.path[:] = old_path
            sys.meta_path[:] = old_meta_path
            sys.path_hooks[:] = old_path_hooks
            sys.path_importer_cache.clear()
            sys.path_importer_cache.update(old_importer_cache)
            sys.pycache_prefix = old_prefix
            sys.dont_write_bytecode = old_write
            for name, (present, value) in old_environment.items():
                if present:
                    os.environ[name] = value
                else:
                    os.environ.pop(name, None)


def require_direct_source_bootstrap(module_spec=__spec__, module_cached=__cached__,
                                    argv0=None):
    """Reject module/bytecode launch; the auditor must start from this source file."""
    invoked = Path(sys.argv[0] if argv0 is None else argv0).resolve()
    if module_spec is not None or module_cached is not None or invoked != AUDITOR:
        raise ValueError("parallel verifier requires direct source-script execution")


def _available_ram_gib():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / 1024 / 1024
    raise ValueError("host available memory is unavailable")


def _contained_run_artifact_size(artifact_root, receipt):
    cell = receipt.get("cell")
    run_dir = Path(receipt["runDir"]).resolve()
    artifact_root = Path(artifact_root).resolve()
    if (not isinstance(cell, str) or run_dir.parent.name != cell or
            not run_dir.is_relative_to(artifact_root)):
        raise ValueError("cell artifact path escapes campaign root")
    total = 0
    for path in run_dir.rglob("*"):
        if path.is_symlink():
            raise ValueError("cell artifact tree contains a symbolic link")
        if path.is_file():
            total += path.stat().st_size
    return total


def verification_preflight(args, receipts):
    completed = [row for row in receipts if row["status"] in
                 ("EQUAL", "CAPTURED_EQUAL", "DIFFERENT")]
    sizes = []
    for receipt in completed:
        sizes.append(_contained_run_artifact_size(args.artifact_root, receipt))
    largest_gib = (max(sizes, default=0) / 1024 ** 3)
    per_worker_gib = max(2.0, largest_gib * COMPRESSED_ARTIFACT_EXPANSION)
    available_gib = _available_ram_gib()
    cpu_count = os.cpu_count() or 1
    job_cap = min(MAX_VERIFICATION_JOBS, cpu_count)
    legacy = any(row.get("verification", {}).get(
        "physicalFormat", "legacy-v1") != "compact-v1" for row in completed)
    temp_disk_per_worker_gib = 0.1 + (
        COMPRESSED_ARTIFACT_EXPANSION * largest_gib if legacy else 0.0)
    disk_root = Path(args.artifact_root)
    while not disk_root.exists():
        disk_root = disk_root.parent
    free_disk_gib = shutil.disk_usage(disk_root).free / 1024 ** 3
    min_free_disk_gib = getattr(args, "verification_min_free_disk_gib", 20)
    if args.verification_jobs > job_cap:
        raise ValueError("verification jobs exceed CPU/safety cap")
    if args.verification_jobs * per_worker_gib > available_gib * 0.75:
        raise ValueError("verification jobs exceed artifact-weighted RAM budget")
    disk_reservation_gib = args.verification_jobs * temp_disk_per_worker_gib
    if disk_reservation_gib + min_free_disk_gib > free_disk_gib:
        raise ValueError("verification jobs exceed artifact-weighted disk budget")
    return {"cpuCount": cpu_count, "availableRamGiB": available_gib,
            "jobCap": job_cap, "largestCompletedArtifactGiB": largest_gib,
            "estimatedRamPerWorkerGiB": per_worker_gib,
            "estimatedRamReservationGiB": args.verification_jobs * per_worker_gib,
            "freeDiskGiB": free_disk_gib,
            "minFreeDiskGiB": min_free_disk_gib,
            "estimatedTempDiskPerWorkerGiB": temp_disk_per_worker_gib,
            "estimatedTempDiskReservationGiB": disk_reservation_gib}


def _verify_completed_receipt(runner, args, cell, receipt):
    run_dir = Path(receipt["runDir"]).resolve()
    if run_dir.parent.name != cell or not run_dir.is_relative_to(args.artifact_root):
        raise ValueError("cell artifact path escapes campaign root")
    runner.check_matrix_p_identity(args.p_matrix_dir, run_dir, cell)
    runner.check_matrix_e_identity(args.e_matrix_dir, run_dir, cell)
    verdict = runner.offline_verify(run_dir, args, cell)
    if verdict != receipt["verification"]:
        raise ValueError("offline verdict changed: " + cell)
    expected_status = (
        "EQUAL" if verdict["status"] == "PASS" and
        verdict.get("pAcceptanceVerification") == runner.FULL_P_ACCEPTANCE
        else "CAPTURED_EQUAL" if verdict["status"] == "PASS"
        else "DIFFERENT")
    if receipt["status"] != expected_status:
        raise ValueError("cell status exceeds offline evidence: " + cell)
    return receipt


def verify_completed_receipts(runner, args, cells, receipts):
    """Replay completed cells in parallel and return campaign-ordered receipts."""
    by_cell = {receipt["cell"]: receipt for receipt in receipts}
    completed = [cell for cell in cells if by_cell[cell]["status"] in
                 ("EQUAL", "CAPTURED_EQUAL", "DIFFERENT")]
    verified = {}
    errors = {}
    with ThreadPoolExecutor(max_workers=args.verification_jobs) as pool:
        futures = {
            pool.submit(_verify_completed_receipt, runner, args, cell,
                        by_cell[cell]): cell
            for cell in completed
        }
        for future in as_completed(futures):
            cell = futures[future]
            try:
                verified[cell] = future.result()
            except Exception as error:
                errors[cell] = error
    if errors:
        first = next(cell for cell in cells if cell in errors)
        raise ValueError(
            "physical verification failed for " + first + ": " + str(errors[first])) \
            from errors[first]
    return [verified.get(cell, by_cell[cell]) for cell in cells]


def _save_attestation(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(payload, sort_keys=True, indent=2) + "\n").encode("utf-8")
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".parallel-verification-",
                                     suffix=".tmp", delete=False) as temporary:
        temporary.write(encoded)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    os.replace(temporary_path, path)


def _verify_with_runner(args, auditor_sha, summary_path, summary,
                        summary_sha, bound_runner_sha, runner, source_snapshot):
    campaign = runner.read(args.campaign)
    cells = runner.campaign_cells(campaign, args.catalog, args.evaluation_root)
    matrix = runner.matrix_binding(
        args.p_matrix_dir, cells, args.catalog, args.evaluation_root)
    e_matrix = runner.e_matrix_binding(
        args.e_matrix_dir, cells, args.catalog, args.evaluation_root,
        expected_source=matrix["sourceTreeSha256"],
        expected_classes=matrix["classTreeSha256"])

    # Model prerequisites complete before any physical replay is submitted.
    runner.verify_planning_matrix(
        campaign, cells, args.p_matrix_dir, args.catalog,
        args.evaluation_root, args.p_verify_jobs)
    runner.verify_exact_matrix(
        cells, args.e_matrix_dir, args.catalog, args.evaluation_root, e_matrix)

    expected_binding = runner.binding(args, campaign, matrix, e_matrix)
    if (summary.get("schema") != "current-pe-matrix-summary-v1" or
            summary.get("binding") != expected_binding or
            [row.get("cell") for row in summary.get("cells", [])] != cells):
        raise ValueError("matrix summary binding or cell frontier differs")

    receipts = []
    receipt_digests = {}
    for cell in cells:
        receipt_path = (args.result_dir / cell / "receipt.json").resolve()
        receipt, receipt_sha = _read_attested_json(receipt_path)
        if (receipt.get("schema") != "current-pe-matrix-cell-v1" or
                receipt.get("cell") != cell or
                receipt.get("binding") != expected_binding):
            raise ValueError("cell receipt binding differs: " + cell)
        receipts.append(receipt)
        receipt_digests[str(receipt_path)] = receipt_sha

    preflight = verification_preflight(args, receipts)
    results = verify_completed_receipts(runner, args, cells, receipts)
    rebuilt = runner.summarize(
        args, campaign, cells, expected_binding, results, write=False)
    if summary != rebuilt:
        raise ValueError("matrix summary differs from saved receipts")

    # Fail if either executable source changed at any point during verification.
    if runner.binding(args, campaign, matrix, e_matrix) != expected_binding:
        raise ValueError("bound runner dependency binding changed during verification")
    _attest_file(BOUND_RUNNER, bound_runner_sha)
    _attest_file(AUDITOR, auditor_sha)
    _attest_file(summary_path, summary_sha)
    for path, digest in receipt_digests.items():
        _attest_file(path, digest)
    if _module_source_snapshot(BOUND_RUNNER.parent) != source_snapshot:
        raise ValueError("bound runner module source frontier changed during verification")
    attestation = {
        "schema": ATTESTATION_SCHEMA,
        "status": rebuilt["status"],
        "counts": rebuilt["counts"],
        "cellCount": len(cells),
        "completedCellCount": sum(
            row["status"] in ("EQUAL", "CAPTURED_EQUAL", "DIFFERENT")
            for row in results),
        "verificationJobs": args.verification_jobs,
        "summarySha256": summary_sha,
        "receiptSha256": receipt_digests,
        "resourcePreflight": preflight,
        "bootstrap": {"mode": "DIRECT_SOURCE_SCRIPT", "path": str(AUDITOR)},
        "childPython": {"isolatedEmptyPycachePrefix": True,
                        "dontWriteBytecode": True},
        "auditor": {"path": str(AUDITOR), "sha256": auditor_sha},
        "boundRunner": {"path": str(BOUND_RUNNER), "sha256": bound_runner_sha},
        "executedSourceModules": source_snapshot,
    }
    attestation_path = args.result_dir / "parallel-verification.json"
    _save_attestation(attestation_path, attestation)
    print(json.dumps({
        "status": rebuilt["status"], "counts": rebuilt["counts"],
        "auditorSha256": auditor_sha, "boundRunnerSha256": bound_runner_sha,
        "attestation": str(attestation_path)}, sort_keys=True))
    return (0 if rebuilt["status"] == "EQUAL" else
            2 if rebuilt["status"] == "INCOMPLETE" else 1)


def verify(args):
    auditor_sha = _attest_file(AUDITOR)
    summary_path = args.result_dir / "summary.json"
    summary, summary_sha = _read_attested_json(summary_path)
    bound_runner_sha = summary.get("binding", {}).get("runnerSha256")
    if not isinstance(bound_runner_sha, str) or len(bound_runner_sha) != 64:
        raise ValueError("matrix summary has no bound runner digest")
    with source_only_imports(BOUND_RUNNER.parent):
        runner = load_bound_runner(BOUND_RUNNER, bound_runner_sha)
        source_snapshot = _module_source_snapshot(BOUND_RUNNER.parent)
        return _verify_with_runner(args, auditor_sha, summary_path, summary,
                                   summary_sha, bound_runner_sha, runner,
                                   source_snapshot)


def main():
    require_direct_source_bootstrap()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("verify",))
    for name in ("campaign", "catalog", "evaluation-root", "verification-root",
                 "p-matrix-dir", "e-matrix-dir", "artifact-root", "result-dir"):
        parser.add_argument("--" + name, type=Path, required=True)
    # Historical values below reconstruct the frozen binding exactly.
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--p-verify-jobs", type=int, default=2)
    parser.add_argument("--shard-jobs", type=int, default=2)
    parser.add_argument("--state-budget", type=int, default=1000)
    parser.add_argument("--e-raw-budget", type=int, default=1000000)
    parser.add_argument("--shard-size", type=int, default=16)
    parser.add_argument("--cell-timeout", type=int, default=1800)
    parser.add_argument("--max-jvms", type=int)
    parser.add_argument("--ram-budget-gib", type=int, default=0)
    parser.add_argument("--min-free-disk-gib", type=int, default=20)
    parser.add_argument("--compact", action="store_true")
    parser.add_argument("--verification-jobs", type=int, default=2,
                        help="independent physical receipt replay parallelism")
    parser.add_argument("--verification-min-free-disk-gib", type=int, default=20)
    args = parser.parse_args()
    for name in ("campaign", "catalog", "evaluation_root", "verification_root",
                 "p_matrix_dir", "e_matrix_dir", "artifact_root", "result_dir"):
        setattr(args, name, getattr(args, name).resolve())
    if min(args.jobs, args.p_verify_jobs, args.shard_jobs, args.state_budget,
           args.e_raw_budget, args.shard_size, args.cell_timeout,
           args.verification_jobs) < 1:
        parser.error("jobs, budgets, shard size, and timeout must be positive")
    if args.max_jvms is None:
        args.max_jvms = args.jobs * args.shard_jobs
    if args.max_jvms < 1 or args.ram_budget_gib < 0 or args.min_free_disk_gib < 0:
        parser.error("resource limits must be nonnegative and max-jvms positive")
    if args.verification_min_free_disk_gib < 0:
        parser.error("verification disk reserve must be nonnegative")
    return verify(args)


if __name__ == "__main__":
    raise SystemExit(main())
