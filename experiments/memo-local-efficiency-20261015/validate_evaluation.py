#!/usr/bin/env python3
"""Fail-closed validation for the Memo Local efficiency experiment artifacts."""

import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PRIOR = REPO / "experiments/memo-local-certificate-first-20261015/analyze_local_results.py"
FINAL_SUITES = ("comparison", "ablation", "runtime", "memory")
FINAL_COUNTS = {"jobs": 29, "compileRows": 165, "certificates": 104, "newLocalRows": 40}
SYNTHETIC_CASES = ("adversarial-shared-wide", "tree-32", "cycle-16", "diamond-8",
                   "shared-wide-8", "epsilon-boundary-3")
SYNTHETIC_CORE_ALGORITHMS = ("EXACT", "TREE_DP_GLOBAL", "TREE_DP_LOCAL", "MEMO_GLOBAL",
                             "MEMO_LOCAL")
SYNTHETIC_EXTRA_CASES = ("adversarial-shared-wide", "diamond-8", "shared-wide-8",
                         "epsilon-boundary-3")
SYNTHETIC_EXTRA_ALGORITHMS = ("MEMO_GLOBAL_SEARCH", "MEMO_LOCAL_SEARCH", "MEMO_LOCAL_NO_SEED")


def load_prior():
    spec = importlib.util.spec_from_file_location("memo_local_certificate_auditor", PRIOR)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load prior independent auditor: {PRIOR}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"cannot read {path}: {exc}") from exc


def artifact_root(root, partial):
    evaluation = root / "evaluation"
    if not partial and evaluation.is_dir():
        return evaluation
    return root


def summary_records(root, prior):
    return prior.load_base().read_summaries(root)


def option(argv, name):
    matches = [index for index, value in enumerate(argv) if value == name]
    if len(matches) != 1:
        return None
    index = matches[0]
    return argv[index + 1] if index + 1 < len(argv) else None


def expected_seed_mode(suite, name):
    if suite in ("comparison", "runtime", "memory", "pilot"):
        return "false"
    if suite == "ablation":
        if "seed-on" in name:
            return "true"
        if "seed-off" in name:
            return "false"
    return None


def audit_execution(root, summaries, partial):
    errors = []
    summary_by_path = {path.resolve(): data for path, data in summaries}
    receipt_paths = sorted(root.glob("*/receipts.json"))
    observed = Counter()
    jobs = compile_rows = certificates = new_local_rows = 0
    memory_jobs = memory_rows = 0
    memory_heaps = Counter()
    runtime_summaries = []

    if not summaries:
        errors.append({"message": "no summary artifacts found"})
    if not receipt_paths:
        errors.append({"message": "no receipt artifacts found"})

    for receipt_path in receipt_paths:
        receipt = read_json(receipt_path)
        suite = receipt.get("suite")
        observed[suite] += 1
        for index, job in enumerate(receipt.get("jobs") or []):
            jobs += 1
            record = {"receipt": str(receipt_path), "job": index,
                      "name": job.get("name"), "suite": suite}
            if job.get("returncode") != 0:
                errors.append({**record, "message": "harness returncode must be zero",
                               "returncode": job.get("returncode")})
            argv = job.get("argv") or []
            expected_seed = expected_seed_mode(suite, str(job.get("name") or ""))
            actual_seed = option(argv, "--local-improve-seed")
            if expected_seed is None:
                errors.append({**record, "message": "cannot determine required seed mode"})
            elif actual_seed != expected_seed:
                errors.append({**record, "message": "receipt seed mode does not match suite contract",
                               "expected": expected_seed, "actual": actual_seed})
            if suite == "ablation":
                expected_messages = "true" if "messages-on" in str(job.get("name") or "") else \
                    "false" if "messages-off" in str(job.get("name") or "") else None
                actual_messages = option(argv, "--local-messages")
                if expected_messages is None or actual_messages != expected_messages:
                    errors.append({**record, "message":
                                   "ablation message mode does not match job name",
                                   "expected": expected_messages, "actual": actual_messages})
            if suite == "memory":
                heap = option(argv, "--heap-mib")
                memory_heaps[heap] += 1

            raw_summary = job.get("summary")
            path = Path(raw_summary).resolve() if raw_summary else None
            data = summary_by_path.get(path)
            if data is None and job.get("name"):
                relocated = (receipt_path.parent / str(job["name"]) / "summary.json").resolve()
                data = summary_by_path.get(relocated)
                path = relocated if data is not None else path
            if data is None:
                errors.append({**record, "message": "receipt has no matched summary"})
                continue
            if data.get("complete") is not True:
                errors.append({**record, "summary": str(path),
                               "message": "matched summary.complete must be true",
                               "complete": data.get("complete")})
            rows = data.get("compileRows") or []
            compile_rows += len(rows)
            if suite == "memory":
                memory_jobs += 1
                memory_rows += len(rows)
            if suite == "runtime":
                runtime_summaries.append((path, data))
            for row_index, row in enumerate(rows):
                if row.get("status") != "passed" or row.get("returncode") != 0:
                    errors.append({**record, "summary": str(path), "row": row_index,
                                   "message": "compile row must pass with returncode zero",
                                   "status": row.get("status"), "returncode": row.get("returncode")})
                row_certificates = row.get("dualCertificates") or row.get("treeCertificates") or []
                certificates += len(row_certificates)
                run = path.parent.name
                if row.get("algorithm") == "MEMO_LOCAL" and not run.startswith("before-"):
                    new_local_rows += 1

    if not partial:
        for suite in FINAL_SUITES:
            if observed[suite] != 1:
                errors.append({"suite": suite, "message": "final evaluation requires exactly one receipt"})
        if memory_jobs != 2 or memory_rows != 12:
            errors.append({"suite": "memory", "message": "memory suite must contain 2 jobs and 12 rows",
                           "jobs": memory_jobs, "compileRows": memory_rows})
        if memory_heaps != Counter({"1024": 1, "1536": 1}):
            errors.append({"suite": "memory", "message":
                           "memory suite must contain unique 1024 and 1536 MiB jobs",
                           "heaps": dict(memory_heaps)})
        actual = {"jobs": jobs, "compileRows": compile_rows, "certificates": certificates,
                  "newLocalRows": new_local_rows}
        if actual != FINAL_COUNTS:
            errors.append({"message": "final evaluation matrix counts differ", "expected": FINAL_COUNTS,
                           "actual": actual})

        if not runtime_summaries:
            errors.append({"suite": "runtime", "message": "runtime summary is missing"})
        for path, data in runtime_summaries:
            runtime_rows = data.get("runtimeRows")
            if not isinstance(runtime_rows, list) or not runtime_rows:
                errors.append({"summary": str(path), "message": "runtimeRows must be nonempty"})
            else:
                for index, row in enumerate(runtime_rows):
                    if row.get("status") != "passed" or row.get("returncode") != 0:
                        errors.append({"summary": str(path), "runtimeRow": index,
                                       "message": "runtime row must pass with returncode zero"})
                    tests = row.get("tests")
                    if not isinstance(tests, int) or isinstance(tests, bool) or tests < 1:
                        errors.append({"summary": str(path), "runtimeRow": index,
                                       "message": "runtime row must report a positive test count",
                                       "tests": tests})
            synthetic = data.get("synthetic")
            if not isinstance(synthetic, dict) or synthetic.get("status") != "passed" \
                    or synthetic.get("returncode") != 0:
                errors.append({"summary": str(path), "message": "synthetic comparison must pass"})
            else:
                synthetic_rows = synthetic.get("rows")
                regional_rows = synthetic.get("legacyRegional")
                if not isinstance(synthetic_rows, list) or not synthetic_rows:
                    errors.append({"summary": str(path),
                                   "message": "synthetic rows must be nonempty"})
                else:
                    observed_pairs = Counter((row.get("case"), row.get("algorithm"))
                                             for row in synthetic_rows)
                    required_pairs = {(case, algorithm) for case in SYNTHETIC_CASES
                                      for algorithm in SYNTHETIC_CORE_ALGORITHMS}
                    required_pairs.update((case, algorithm) for case in SYNTHETIC_EXTRA_CASES
                                          for algorithm in SYNTHETIC_EXTRA_ALGORITHMS)
                    if len(synthetic_rows) != len(required_pairs) or set(observed_pairs) != required_pairs \
                            or any(count != 1 for count in observed_pairs.values()):
                        errors.append({"summary": str(path), "message":
                                       "synthetic case/algorithm coverage differs",
                                       "expectedRows": len(required_pairs),
                                       "actualRows": len(synthetic_rows)})
                if synthetic.get("legacyRegionalCompared") is not True \
                        or not isinstance(regional_rows, list) or not regional_rows:
                    errors.append({"summary": str(path),
                                   "message": "synthetic comparison must include nonempty legacy Regional"})

    return {"schema": "memo-local-efficiency-execution-validation/v1", "valid": not errors,
            "partial": partial, "observedSuites": dict(sorted(observed.items())),
            "jobs": jobs, "compileRows": compile_rows, "certificates": certificates,
            "newLocalRows": new_local_rows, "memoryJobs": memory_jobs,
            "memoryCompileRows": memory_rows, "memoryHeaps": dict(memory_heaps), "errors": errors}


def write_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def validate(root, partial):
    prior = load_prior()
    evidence_root = artifact_root(root.resolve(), partial)
    summaries = summary_records(evidence_root, prior)
    base = prior.load_base()
    certificate = prior.enforce_local_contract(base, summaries, base.audit(summaries))
    actions = prior.analyze_actions(base, summaries)
    action_contract = prior.audit_action_contract(summaries, actions)
    receipts = prior.audit_receipts(evidence_root, summaries, () if partial else FINAL_SUITES)
    execution = audit_execution(evidence_root, summaries, partial)
    errors = list(certificate.get("errors") or [])
    errors.extend(action_contract.get("errors") or [])
    errors.extend(receipts.get("errors") or [])
    errors.extend(actions.get("parseErrors") or [])
    errors.extend(execution.get("errors") or [])
    return {"schema": "memo-local-efficiency-validation/v1", "valid": not errors,
            "evidenceRoot": str(evidence_root), "certificate": certificate,
            "actionContract": action_contract, "receiptMatrix": receipts,
            "execution": execution, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=HERE)
    parser.add_argument("--allow-partial", action="store_true")
    args = parser.parse_args()
    result = validate(args.root, args.allow_partial)
    output = args.root.resolve() / "evaluation-validation.json"
    write_json(output, result)
    execution = result["execution"]
    print(f"jobs={execution['jobs']} rows={execution['compileRows']} "
          f"certificates={execution['certificates']} newLocalRows={execution['newLocalRows']} "
          f"valid={result['valid']}")
    return 0 if result["valid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
