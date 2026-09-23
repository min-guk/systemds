#!/usr/bin/env python3
"""Freeze generated microbenchmarks as reproducible compile-model conditions.

The frozen contract is intentionally limited to planner/compiler inputs.  It
does not create worker data, claim that worker bytes exist, or attest runtime
reachability.  The authoritative generator and runner remain part of every
condition binding by content hash.
"""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile


PRODUCER = Path(__file__).resolve()
FROZEN_SOURCE_SHA256 = {
    "microbench/generate.py": "cc284f928d10ba9fae8b29777b53da4f4339d0c53ce1411a187dadb3281ce7e3",
    "microbench/run.py": "1bf63cb7bb0a12bd23107bc410434a2bad447e5cea0af4025cbaad223037f783",
}
WORKERS = 4
ROWS = 4096
COLS = 64
ITERATIONS = 5
CONDITION_ID = "microbench-unshaped-5gbit-assumed"
NETWORK = {
    "c2w_mbit": 5000,
    "cost_environment": {
        "SYSDS_FED_COST_NET_BW": "596.04644775390625",
        "SYSDS_FED_COST_NET_BW_C2W": "596.04644775390625",
        "SYSDS_FED_COST_NET_BW_W2C": "596.04644775390625",
        "SYSDS_FED_COST_NET_LATENCY": ".001",
    },
    "profile": CONDITION_ID,
    "rtt_ms": 1,
    "w2c_mbit": 5000,
}
PLANNER_CONFIGURATION = {
    "sysds.benchmark.compile_only": "true",
    "sysds.codegen.enabled": "false",
    "sysds.federated.initialization.timeout": "45",
    "sysds.federated.planner": "compile_cost_based",
    "sysds.federated.readcache": "false",
    "sysds.federated.timeout": "45",
    "sysds.localtmpdir": "/tmp/systemds",
    "sysds.native.blas": "none",
    "sysds.scratch": "/tmp/scratch",
}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_generator(path):
    spec = importlib.util.spec_from_file_location("frozen_microbench_generator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    required = ("TARGET_COMMIT", "SCALES", "DIMENSIONS", "FAMILIES",
                "REUSE_VARIANTS", "render_program", "_filename")
    missing = [name for name in required if not hasattr(module, name)]
    if missing:
        raise ValueError(f"microbenchmark generator contract missing: {missing}")
    if COLS not in module.DIMENSIONS:
        raise ValueError(f"default column count {COLS} is outside generator dimensions")
    return module


def expected_programs(generator):
    result = {}
    for family in generator.FAMILIES:
        variants = generator.REUSE_VARIANTS if family == "reuse_update" else (None,)
        for variant in variants:
            for scale in generator.SCALES:
                discovery = f"microbench:{family}"
                if variant is not None:
                    discovery += f"-{variant}"
                discovery += f"-k{scale}"
                filename = generator._filename(family, scale, variant)
                if discovery in result or filename in {item[0] for item in result.values()}:
                    raise ValueError("generator produces duplicate discovery IDs or filenames")
                program = generator.render_program(
                    family, scale, n=ROWS, d=COLS, variant=variant,
                    iterations=ITERATIONS)
                result[discovery] = (filename, program, family, variant, scale)
    return result


def microbench_candidates(candidate_manifest):
    document = json.loads(Path(candidate_manifest).read_text())
    records = document.get("unresolved", document.get("records"))
    if not isinstance(records, list):
        raise ValueError("candidate manifest has no unresolved/records list")
    selected = {}
    for record in records:
        if record.get("kind") != "generated-microbench":
            continue
        discovery = record.get("discoveryId")
        if not isinstance(discovery, str) or discovery in selected:
            raise ValueError("microbenchmark discovery IDs are missing or duplicated")
        if record.get("workers") is not None or record.get("networkProfile") is not None:
            raise ValueError(f"candidate already carries an incompatible axis binding: {discovery}")
        selected[discovery] = record
    if not selected:
        raise ValueError("candidate manifest contains no generated microbenchmarks")
    return selected


def matrix_metadata(rows, cols, privacy):
    return {
        "cols": cols,
        "cols_in_block": -1,
        "data_type": "matrix",
        "format": "csv",
        "header": False,
        "nnz": rows * cols,
        "privacy": privacy,
        "rows": rows,
        "rows_in_block": -1,
        "sep": ",",
        "value_type": "double",
    }


def input_contract():
    parts = []
    for worker in range(1, WORKERS + 1):
        begin = (worker - 1) * ROWS // WORKERS
        end = worker * ROWS // WORKERS
        parts.append({
            "argument": f"X{worker}",
            "authority": f"worker{worker}:18090",
            "begin": [begin, 0],
            "end": [end, COLS],
            "metadata": matrix_metadata(ROWS // WORKERS, COLS, "private-aggregate"),
            "path": f"/data/X{worker}.csv",
            "worker": worker,
        })
    return {
        "R": {
            "argument": "R",
            "location": "coordinator",
            "metadata": matrix_metadata(COLS, COLS, "public"),
            "path": "/data/R.csv",
        },
        "X": {
            "federationType": "ROW",
            "globalMetadata": matrix_metadata(ROWS, COLS, "private-aggregate"),
            "parts": parts,
        },
    }


def arguments(contract, filename):
    values = {part["argument"]: f'{part["authority"]}/{part["path"]}'
              for part in contract["X"]["parts"]}
    values.update({"R": contract["R"]["path"],
                   "out": f"/results/{Path(filename).stem}.csv"})
    return values


def build(candidate_manifest, evaluation_root, source_pins=FROZEN_SOURCE_SHA256):
    candidate_manifest = Path(candidate_manifest).resolve()
    evaluation_root = Path(evaluation_root).resolve()
    generator_path = evaluation_root / "microbench/generate.py"
    runner_path = evaluation_root / "microbench/run.py"
    for path in (generator_path, runner_path):
        if not path.is_file():
            raise ValueError(f"required frozen source is absent: {path}")
    generator_sha = file_sha(generator_path)
    runner_sha = file_sha(runner_path)
    actual_source_sha = {"microbench/generate.py": generator_sha,
                         "microbench/run.py": runner_sha}
    if actual_source_sha != source_pins:
        raise ValueError(
            f"frozen microbenchmark source binding differs: expected={source_pins}, "
            f"actual={actual_source_sha}")
    generator = load_generator(generator_path)
    programs = expected_programs(generator)
    candidates = microbench_candidates(candidate_manifest)
    if set(candidates) != set(programs):
        missing = sorted(set(programs) - set(candidates))
        extra = sorted(set(candidates) - set(programs))
        raise ValueError(f"candidate/generator program set differs: missing={missing}, extra={extra}")
    for discovery, candidate in candidates.items():
        declared = candidate.get("sourceFiles", {}).get("microbench/generate.py")
        if declared != generator_sha:
            raise ValueError(f"candidate generator digest differs: {discovery}")

    contract = input_contract()
    conditions = []
    rendered = {}
    for discovery in sorted(programs):
        filename, program, family, variant, scale = programs[discovery]
        rendered[filename] = program.encode()
        program_sha = hashlib.sha256(rendered[filename]).hexdigest()
        case = {
            "arguments": arguments(contract, filename),
            "dimensions": {"cols": COLS, "rows": ROWS},
            "family": family,
            "inputs": contract,
            "iterations": ITERATIONS if family == "reuse_update" else None,
            "plannerConfiguration": PLANNER_CONFIGURATION,
            "program": f"programs/{filename}",
            "programSha256": program_sha,
            "scale": scale,
            "targetCommit": generator.TARGET_COMMIT,
            "variant": variant,
            "workers": WORKERS,
        }
        planned = {
            "case": case,
            "network": NETWORK,
            "workers": WORKERS,
        }
        identity = {"conditionId": CONDITION_ID, "discoveryId": discovery, **planned}
        candidate = candidates[discovery]
        conditions.append({
            "candidateId": candidate.get("candidateId"),
            "conditionId": CONDITION_ID,
            "conditionSha256": digest(identity),
            "discoveryId": discovery,
            "inputValidation": {
                "compileModelReady": True,
                "generatedProgramDigestVerified": True,
                "metadataAndPrivacyBound": True,
                "runtimeDataBytesAttested": False,
                "runtimeWorkerReachabilityAssessed": False,
                "workerPartitionsCreatedOrCopied": False,
            },
            "placeholderCellId": candidate.get("placeholderCellId"),
            "plannedCondition": planned,
            "sourceFiles": {
                "microbench/generate.py": generator_sha,
                "microbench/run.py": runner_sha,
                f"programs/{filename}": program_sha,
            },
            "status": "READY_FOR_COMPILE_MODEL_CAPTURE",
        })
    manifest = {
        "schema": "microbench-condition-freeze-v1",
        "status": "COMPLETE",
        "claimScope": "STATIC_COMPILE_MODEL_INPUT_BINDING_ONLY",
        "candidateManifest": {"path": str(candidate_manifest),
                              "sha256": file_sha(candidate_manifest)},
        "evaluationRoot": str(evaluation_root),
        "producerScriptSha256": file_sha(PRODUCER),
        "sourceSha256": actual_source_sha,
        "counts": {"candidates": len(candidates), "conditions": len(conditions),
                   "generatedPrograms": len(rendered), "workersPerCondition": WORKERS},
        "conditions": conditions,
        "limitations": [
            "WORKER_DATA_BYTES_NOT_ATTESTED",
            "RUNTIME_WORKER_REACHABILITY_NOT_ASSESSED",
            "NETWORK_VALUES_ARE_COST_MODEL_ASSUMPTIONS",
        ],
    }
    return manifest, rendered


def atomic_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    temporary.replace(path)


def publish(manifest, rendered, output, program_dir, check=False):
    output = Path(output)
    program_dir = Path(program_dir)
    content = json.dumps(manifest, indent=2, sort_keys=True).encode() + b"\n"
    expected = {program_dir / name: value for name, value in rendered.items()}
    expected[output] = content
    if check:
        mismatches = [str(path) for path, value in expected.items()
                      if not path.is_file() or path.read_bytes() != value]
        if mismatches:
            raise ValueError(f"frozen microbenchmark artifact differs: {mismatches}")
        return
    for path, value in sorted(expected.items(), key=lambda item: str(item[0])):
        atomic_write(path, value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-manifest", type=Path, required=True)
    parser.add_argument("--evaluation-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--program-dir", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest, rendered = build(args.candidate_manifest, args.evaluation_root)
    publish(manifest, rendered, args.output, args.program_dir, args.check)
    print(json.dumps({"status": manifest["status"], **manifest["counts"]}, sort_keys=True))


if __name__ == "__main__":
    main()
