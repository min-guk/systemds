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
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/fedplanner"))
from verify_e_factor_artifact import verify as verify_e_factors


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


def verifier_sha(evaluation):
    digest = hashlib.sha256()
    for path in (Path(__file__).resolve(), ROOT / "scripts/fedplanner/verify_e_factor_artifact.py",
                 evaluation / "calibration/plan_space_verify.py",
                 evaluation / "calibration/closed_physical_identity.py"):
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


def java(build, environment, klass, *args):
    command = ["java", "--add-modules", "jdk.incubator.vector", "-Xmx4g",
               "-cp", "target/classes:target/test-classes:target/lib/*", klass,
               *map(str, args)]
    process = subprocess.run(command, cwd=build, env=environment,
                             text=True, capture_output=True)
    if process.returncode:
        raise RuntimeError(f"{klass} failed: {process.stderr[-2000:]}")
    return json.loads(process.stdout.strip().splitlines()[-1])


def check_row_receipt(receipt_path, source, cell, model, expected=None):
    receipt = read(receipt_path)
    rows = Path(receipt["rows"])
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


def verify(run_dir, evaluation, enforce_claim=True):
    sys.path.insert(0, str(evaluation / "calibration"))
    from plan_space_verify import sorted_chunk, iter_chunk, merged_chunks, compare
    certificate = read(run_dir / "certificate.json")
    if certificate.get("schema") != "current-pe-cell-certificate-v1":
        raise ValueError("wrong current P/E certificate schema")
    if certificate.get("verifierSha256") != verifier_sha(evaluation):
        raise ValueError("independent verifier implementation changed")
    meta = read(run_dir / "run-meta.json")
    if (meta.get("binding") != certificate.get("binding") or
            meta.get("sourceTreeSha256") != certificate.get("sourceTreeSha256") or
            meta.get("evaluationRoot") != str(evaluation)):
        raise ValueError("run binding differs from certificate")
    cell = certificate["cell"]
    p_model, e_model = (read(run_dir / f"{source}-model.receipt.json")
                        for source in ("p", "e"))
    for source, model in (("p", p_model), ("e", e_model)):
        if (model.get("status") != "COMPLETE" or model.get("cell") != cell or
                model.get("artifactPath") != str(run_dir / f"{source}-model.json.gz")):
            raise ValueError("native model receipt incomplete")
        with gzip.open(model["artifactPath"], "rb") as stream:
            if hashlib.sha256(stream.read()).hexdigest() != model["artifactSha256"]:
                raise ValueError("native model artifact digest changed")
    native_p = json.loads(gzip.decompress((run_dir / "p-model.json.gz").read_bytes()))
    native_e = json.loads(gzip.decompress((run_dir / "e-model.json.gz").read_bytes()))
    state_count = math.prod(len(domain[1]) for domain in native_p["nativeDomain"]["placementDomains"])
    p_paths = []
    cursor = raw = accepted = rejected = 0
    for shard in certificate["pShards"]:
        path = run_dir / shard["receipt"]
        receipt = check_row_receipt(path, "P_C0", cell, native_e,
                                    (cursor, int(shard["stop"])))
        cursor = int(receipt["stop"])
        raw += int(receipt["raw"])
        accepted += int(receipt["accepted"])
        rejected += int(receipt["rejected"])
        p_paths.append(Path(receipt["rows"]))
    if cursor != state_count or not p_paths or raw != int(p_model["rawCount"]):
        raise ValueError("P state frontier or raw product incomplete")
    e_receipt = check_row_receipt(run_dir / "e-rows.receipt.json", "E_C0", cell, native_e)
    if int(e_receipt["raw"]) != int(e_model["rawCount"]):
        raise ValueError("E model/raw receipt count differs")
    factor_result = verify_e_factors(run_dir / "e-model.json.gz",
                                     e_model["artifactSha256"],
                                     Path(e_receipt["rows"]),
                                     run_dir / "e-rows.receipt.json")
    with tempfile.TemporaryDirectory(dir=run_dir) as temporary:
        temporary = Path(temporary)
        p_sorted = []
        p_proofs = 0
        for index, path in enumerate(p_paths):
            target = temporary / f"p-{index}.jsonl.gz"
            count, _ = sorted_chunk(path, target, limit=16)
            p_proofs += count
            p_sorted.append(target)
        e_sorted = temporary / "e.jsonl.gz"
        e_proofs, _ = sorted_chunk(Path(e_receipt["rows"]), e_sorted, limit=16)
        difference = compare(merged_chunks(p_sorted), iter_chunk(e_sorted),
                             witness_limit=1)
        p_unique = sum(1 for _ in merged_chunks(p_sorted))
        e_unique = sum(1 for _ in iter_chunk(e_sorted))
    if p_proofs != accepted or e_proofs != int(e_receipt["accepted"]):
        raise ValueError("sorted proof count differs from receipts")
    verdict = {
        "schema": "current-pe-cell-verification-v1", "cell": cell,
        "status": "PASS" if not difference["leftOnly"] and not difference["rightOnly"] else "FAIL",
        "pRaw": str(raw), "pAccepted": str(accepted), "pRejected": str(rejected),
        "eRaw": e_receipt["raw"], "eAccepted": e_receipt["accepted"],
        "eRejected": e_receipt["rejected"], "pPhysical": p_unique,
        "ePhysical": e_unique, "pOnly": difference["leftOnly"],
        "eOnly": difference["rightOnly"], "witnesses": difference,
        "factorStatus": factor_result["status"],
        "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT",
    }
    if enforce_claim:
        for key in ("status", "pRaw", "pAccepted", "pRejected", "eRaw",
                    "eAccepted", "eRejected", "pPhysical", "ePhysical", "pOnly", "eOnly"):
            if certificate.get(key) != verdict[key]:
                raise ValueError("stored certificate differs from independent recomputation: " + key)
    return verdict


def run(args):
    build = args.build_root.resolve()
    catalog = args.catalog.resolve()
    evaluation = args.evaluation_root.resolve()
    check_frozen_inputs(catalog, evaluation, args.cell)
    source_sha = tree_sha(build)
    binding = hashlib.sha256(json.dumps({
        "cell": args.cell, "source": source_sha, "catalog": sha(catalog),
        "evaluation": str(evaluation),
        "network": {key: os.environ.get(key, "") for key in sorted(os.environ)
                    if key.startswith("SYSDS_FED_COST_NET_")},
    }, sort_keys=True).encode()).hexdigest()
    run_dir = args.artifact_root.resolve() / args.cell / binding[:20]
    run_dir.mkdir(parents=True, exist_ok=True)
    meta_path = run_dir / "run-meta.json"
    meta = {"schema": "current-pe-cell-run-v1", "cell": args.cell,
            "sourceTreeSha256": source_sha, "catalogSha256": sha(catalog),
            "evaluationRoot": str(evaluation), "binding": binding}
    if meta_path.exists() and read(meta_path) != meta:
        raise ValueError("resume binding changed")
    save(meta_path, meta)
    if args.compile:
        process = subprocess.run(["mvn", "-q", "-DskipTests", "test-compile"],
                                 cwd=build, env=os.environ.copy())
        if process.returncode:
            raise RuntimeError("isolated Java build failed")
    environment = os.environ.copy()
    def capture(name, klass):
        receipt_path = run_dir / f"{name}-model.receipt.json"
        artifact = run_dir / f"{name}-model.json.gz"
        if args.resume and receipt_path.exists() and artifact.exists():
            saved = read(receipt_path)
            with gzip.open(artifact, "rb") as stream:
                if hashlib.sha256(stream.read()).hexdigest() == saved.get("artifactSha256"):
                    return saved
        result = java(build, environment, klass, catalog, evaluation, args.cell, artifact)
        if result.get("status") != "COMPLETE":
            raise RuntimeError(f"{name} model capture: {result}")
        save(receipt_path, result)
        return result
    p_model = capture("p", "org.apache.sysds.test.component.federated.placement.shadow.PlanningNativeModelCapture")
    e_model = capture("e", "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPlanningModelCapture")
    native_p = json.loads(gzip.decompress((run_dir / "p-model.json.gz").read_bytes()))
    state_count = math.prod(len(domain[1]) for domain in native_p["nativeDomain"]["placementDomains"])
    if state_count > args.state_budget:
        save(run_dir / "incomplete.json", {"status": "INCOMPLETE",
             "reason": "P placement frontier exceeds exact state budget",
             "stateCount": str(state_count), "budget": args.state_budget,
             "runtimeSemanticCoverage": "NOT_ASSESSED_BY_THIS_CONTRACT"})
        print(json.dumps(read(run_dir / "incomplete.json"), sort_keys=True))
        return 2
    def p_shard(index):
        rows = run_dir / f"p-state-{index}.jsonl.gz"
        receipt = run_dir / f"p-state-{index}.receipt.json"
        if args.resume and rows.exists() and receipt.exists():
            check_row_receipt(receipt, "P_C0", args.cell,
                              json.loads(gzip.decompress((run_dir / "e-model.json.gz").read_bytes())),
                              (index, index + 1))
            return
        java(build, environment,
             "org.apache.sysds.test.component.federated.placement.shadow.CurrentPlanningPhysicalRows",
             catalog, evaluation, args.cell, index, index + 1, rows, receipt)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(p_shard, index) for index in range(state_count)]
        for future in as_completed(futures):
            future.result()
    e_rows = run_dir / "e-rows.jsonl.gz"
    e_receipt = run_dir / "e-rows.receipt.json"
    if not (args.resume and e_rows.exists() and e_receipt.exists()):
        java(build, environment,
             "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ExactPlanningPhysicalRows",
             catalog, evaluation, args.cell, e_rows, e_receipt)
    shards = [{"receipt": f"p-state-{index}.receipt.json", "stop": str(index + 1)}
              for index in range(state_count)]
    seed = {"schema": "current-pe-cell-certificate-v1", "cell": args.cell,
            "binding": binding, "sourceTreeSha256": source_sha,
            "verifierSha256": verifier_sha(evaluation), "pShards": shards}
    save(run_dir / "certificate.json", seed)
    # The verifier deliberately rereads stored gzip artifacts in a separate pass.
    verdict = verify(run_dir, evaluation, enforce_claim=False)
    certificate = dict(seed)
    certificate.update({key: verdict[key] for key in
                        ("status", "pRaw", "pAccepted", "pRejected", "eRaw",
                         "eAccepted", "eRejected", "pPhysical", "ePhysical", "pOnly", "eOnly")})
    save(run_dir / "certificate.json", certificate)
    print(json.dumps({"runDir": str(run_dir), **verdict}, sort_keys=True))
    return 0 if verdict["status"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    parser.add_argument("--build-root", type=Path)
    parser.add_argument("--catalog", type=Path)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--cell")
    parser.add_argument("--artifact-root", type=Path)
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--state-budget", type=int, default=100000)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--compile", action="store_true")
    args = parser.parse_args()
    if args.mode == "verify":
        if args.run_dir is None:
            parser.error("--run-dir required for verify")
        result = verify(args.run_dir.resolve(), args.evaluation_root.resolve())
        print(json.dumps(result, sort_keys=True))
        return 0 if result["status"] == "PASS" else 1
    if any(value is None for value in (args.build_root, args.catalog, args.cell, args.artifact_root)):
        parser.error("run needs --build-root --catalog --cell --artifact-root")
    if args.jobs < 1 or args.state_budget < 1:
        parser.error("jobs and state budget must be positive")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
