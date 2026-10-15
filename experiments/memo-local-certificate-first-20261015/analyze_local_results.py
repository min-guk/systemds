#!/usr/bin/env python3
"""Reuse the independent certificate auditor and enforce Local's new route contract."""

import argparse
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path
import re


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
BASE_ANALYZER = REPO / "experiments/memo-dual-improvement-20261015/analyze_results.py"
LOCAL_RECORD = re.compile(r"\[(MemoLocalAction|MemoLocalSummary)\]\s*(.*)$")


def load_base():
    spec = importlib.util.spec_from_file_location("memo_dual_result_auditor", BASE_ANALYZER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load prior independent auditor: {BASE_ANALYZER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def enforce_local_contract(base, summaries, validation):
    errors = list(validation.get("errors") or [])
    checks = []
    for path, data in summaries:
        run = path.parent.name
        is_new = not run.startswith("before-")
        for row_index, row in enumerate(data.get("compileRows") or []):
            if not is_new or row.get("algorithm") != "MEMO_LOCAL" or row.get("status") != "passed":
                continue
            certificates = base.certificates(row)
            for certificate_index, certificate in enumerate(certificates):
                statistics = certificate.get("statistics") or {}
                route = certificate.get("route")
                direct_attempts = statistics.get("directAttempts")
                direct_solves = statistics.get("directExactSolves")
                record = {"summary": str(path), "row": row_index,
                          "certificate": certificate_index, "route": route,
                          "directAttempts": direct_attempts,
                          "directExactSolves": direct_solves}
                checks.append(record)
                if route != "LOCAL_CERTIFICATE_FIRST":
                    errors.append({**record, "message": "new MEMO_LOCAL must use certificate-first route"})
                if direct_attempts != 0 or direct_solves != 0:
                    errors.append({**record,
                                   "message": "new MEMO_LOCAL must not invoke a monolithic direct exact solve"})
    result = dict(validation)
    result.update({
        "schema": "memo-local-certificate-first-result-validation/v1",
        "valid": not errors,
        "localRouteChecks": checks,
        "localRouteChecksPassed": sum(check["route"] == "LOCAL_CERTIFICATE_FIRST"
                                       and check["directAttempts"] == 0
                                       and check["directExactSolves"] == 0 for check in checks),
        "errors": errors,
    })
    return result


def option(argv, name):
    try:
        position = argv.index(name)
    except ValueError:
        return None
    return argv[position + 1] if position + 1 < len(argv) else None


def expected_job_count(suite, repetitions):
    if suite in ("comparison", "old-search"):
        return 2 * repetitions
    if suite == "ablation":
        return 4 * repetitions
    if suite == "seed-off":
        return repetitions
    if suite == "memory":
        return 2
    if suite in ("pilot", "runtime"):
        return 1
    return None


def audit_receipts(root, summaries, required_suites=()):
    """Prove that every requested Docker job and compile cell reached a summary row.

    Certificate validity alone cannot detect a truncated experiment because a partial
    summary (or even an empty artifact root) contains no bad certificate to reject.
    The append-only driver receipts are therefore the independent experiment matrix.
    """
    errors = []
    summary_by_path = {path.resolve(): data for path, data in summaries}
    receipt_files = sorted(root.glob("*/receipts.json"))
    observed_suites = set()
    jobs_expected = jobs_matched = rows_expected = rows_matched = 0
    failed_rows = []
    records = []

    for receipt_path in receipt_files:
        try:
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append({"receipt": str(receipt_path), "message": f"cannot read receipt: {exc}"})
            continue
        suite = receipt.get("suite")
        observed_suites.add(suite)
        repetitions = receipt.get("repetitions")
        jobs = receipt.get("jobs") or []
        if receipt.get("schema") != "memo-local-certificate-first-receipts/v1":
            errors.append({"receipt": str(receipt_path), "message": "unexpected receipt schema"})
        if suite != receipt_path.parent.name and not receipt_path.parent.name.startswith(str(suite)+"-"):
            errors.append({"receipt": str(receipt_path), "message": "receipt suite differs from directory"})
        if not isinstance(repetitions, int) or repetitions < 1:
            errors.append({"receipt": str(receipt_path), "message": "invalid repetition count"})
            repetitions = 0
        planned = expected_job_count(suite, repetitions)
        if planned is None:
            errors.append({"receipt": str(receipt_path), "message": f"unknown suite {suite!r}"})
        elif len(jobs) != planned:
            errors.append({"receipt": str(receipt_path), "message":
                           f"truncated suite: expected {planned} jobs, found {len(jobs)}"})
        jobs_expected += planned or 0

        names = [job.get("name") for job in jobs]
        if len(names) != len(set(names)):
            errors.append({"receipt": str(receipt_path), "message": "duplicate job names"})
        for job_index, job in enumerate(jobs):
            argv = job.get("argv") or []
            workloads = [value for value in str(option(argv, "--workloads") or "").split(",") if value]
            algorithms = [value for value in str(option(argv, "--algorithms") or "").split(",") if value]
            expected = Counter((workload, algorithm) for workload in workloads for algorithm in algorithms)
            rows_expected += sum(expected.values())
            raw_summary = job.get("summary")
            summary_path = Path(raw_summary).resolve() if raw_summary else None
            data = summary_by_path.get(summary_path)
            if data is None and job.get("name"):
                relocated = (receipt_path.parent / str(job.get("name")) / "summary.json").resolve()
                if relocated in summary_by_path:
                    summary_path = relocated
                    data = summary_by_path[relocated]
            record = {"receipt": str(receipt_path), "job": job_index, "name": job.get("name"),
                      "engine": job.get("engine"), "summary": raw_summary,
                      "returncode": job.get("returncode"), "expectedRows": sum(expected.values())}
            records.append(record)
            if not workloads or not algorithms:
                errors.append({**record, "message": "job command lacks workloads or algorithms"})
            if data is None:
                errors.append({**record, "message": "receipt summary is missing from audited summaries"})
                continue
            jobs_matched += 1
            actual_rows = data.get("compileRows") or []
            actual = Counter((str(row.get("workload")), str(row.get("algorithm"))) for row in actual_rows)
            rows_matched += sum((expected & actual).values())
            missing = expected - actual
            extra = actual - expected
            if missing:
                errors.append({**record, "message": "requested compile rows are missing",
                               "missing": [[list(key), count] for key, count in sorted(missing.items())]})
            if extra:
                errors.append({**record, "message": "summary contains unrequested or duplicate compile rows",
                               "extra": [[list(key), count] for key, count in sorted(extra.items())]})
            for row_index, row in enumerate(actual_rows):
                if row.get("status") != "passed":
                    failed_rows.append({"summary": str(summary_path), "row": row_index,
                                        "workload": row.get("workload"),
                                        "algorithm": row.get("algorithm"),
                                        "status": row.get("status"),
                                        "failureCategory": row.get("failureCategory")})

    for suite in required_suites:
        if suite not in observed_suites:
            errors.append({"receipt": None, "message": f"required suite {suite!r} is missing"})
    if not receipt_files:
        errors.append({"receipt": None, "message": "no experiment receipts found"})

    return {"schema": "memo-local-certificate-first-receipt-validation/v1",
            "valid": not errors, "requiredSuites": list(required_suites),
            "observedSuites": sorted(value for value in observed_suites if value is not None),
            "receiptFiles": len(receipt_files), "jobsExpected": jobs_expected,
            "jobsMatched": jobs_matched, "compileRowsExpected": rows_expected,
            "compileRowsMatched": rows_matched, "failedCompileRows": failed_rows,
            "allRequestedCompileRowsPassed": not failed_rows and rows_expected == rows_matched,
            "jobs": records, "errors": errors}


def analyze_actions(base, summaries):
    records = []
    errors = []
    scanned_logs = set()
    for summary_path, data in summaries:
        suite = summary_path.parent.parent.name
        run = summary_path.parent.name
        mapped = {}
        for row in data.get("compileRows") or []:
            log = base.expected_log(summary_path, row)
            if log is not None:
                mapped[log.resolve()] = (row.get("workload"), row.get("algorithm"))
        for log in sorted(summary_path.parent.rglob("coordinator.log")):
            resolved = log.resolve()
            if resolved in scanned_logs:
                continue
            scanned_logs.add(resolved)
            workload, algorithm = mapped.get(resolved, (None, None))
            for line_number, line in enumerate(
                    log.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                match = LOCAL_RECORD.search(line)
                if match is None:
                    continue
                try:
                    value = json.loads(match.group(2))
                except json.JSONDecodeError as exc:
                    errors.append({"log": str(log), "line": line_number,
                                   "prefix": match.group(1), "error": str(exc)})
                    continue
                records.append({"suite": suite, "run": run, "workload": workload,
                                "algorithm": algorithm, "log": str(log), "line": line_number,
                                "prefix": match.group(1), "value": value})

    grouped = defaultdict(list)
    for record in records:
        grouped[(record["suite"], record["run"], record["workload"],
                 record["algorithm"])].append(record)
    groups = []
    for key, values in sorted(grouped.items(), key=lambda item: tuple(str(v) for v in item[0])):
        actions = [record["value"] for record in values if record["prefix"] == "MemoLocalAction"]
        summaries = [record["value"] for record in values if record["prefix"] == "MemoLocalSummary"]
        kinds = Counter(str(value.get("action", value.get("type", value.get("kind", "UNKNOWN"))))
                        for value in actions)
        groups.append({"suite": key[0], "run": key[1], "workload": key[2],
                       "algorithm": key[3], "records": len(values),
                       "actionRecords": len(actions), "summaryRecords": len(summaries),
                       "actions": dict(sorted(kinds.items())), "summaries": summaries,
                       "actionValues": actions,
                       "values": [record["value"] for record in values]})
    return {"schema": "memo-local-action-analysis/v1", "logsScanned": len(scanned_logs),
            "recordsParsed": len(records),
            "actionRecordsParsed": sum(record["prefix"] == "MemoLocalAction" for record in records),
            "summaryRecordsParsed": sum(record["prefix"] == "MemoLocalSummary" for record in records),
            "parseErrors": errors, "groups": groups}


def audit_action_contract(summaries, actions):
    """Check the observable certificate-first ordering for every passed new Local row."""
    errors = []
    checks = []
    grouped = {(group["suite"], group["run"], group["workload"], group["algorithm"]): group
               for group in actions.get("groups") or []}
    for summary_path, data in summaries:
        suite = summary_path.parent.parent.name
        run = summary_path.parent.name
        if run.startswith("before-"):
            continue
        for row_index, row in enumerate(data.get("compileRows") or []):
            if row.get("algorithm") != "MEMO_LOCAL" or row.get("status") != "passed":
                continue
            certificates = row.get("dualCertificates") or []
            key = (suite, run, row.get("workload"), row.get("algorithm"))
            group = grouped.get(key)
            values = [] if group is None else group.get("actionValues") or []
            local_summaries = [] if group is None else group.get("summaries") or []
            kinds = [str(value.get("action", value.get("type", value.get("kind", "UNKNOWN"))))
                     for value in values]
            initial = [index for index, kind in enumerate(kinds) if kind == "INITIAL_BOUND"]
            first_feasible = [index for index, kind in enumerate(kinds) if kind == "FIRST_FEASIBLE"]
            record = {"summary": str(summary_path), "row": row_index,
                      "workload": row.get("workload"), "certificates": len(certificates),
                      "initialBounds": len(initial), "firstFeasible": len(first_feasible),
                      "actions": len(kinds), "localSummaries": len(local_summaries)}
            checks.append(record)
            if len(initial) != len(certificates):
                errors.append({**record, "message":
                               "each Local certificate requires one observable INITIAL_BOUND action"})
                continue
            if len(first_feasible) != len(certificates):
                errors.append({**record, "message":
                               "each Local certificate requires one observable FIRST_FEASIBLE yield"})
                continue
            boundaries = initial + [len(kinds)]
            for certificate_index in range(len(certificates)):
                segment = kinds[boundaries[certificate_index]:boundaries[certificate_index + 1]]
                if not segment or segment[0] != "INITIAL_BOUND" or "FIRST_FEASIBLE" not in segment:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "Local action segment violates bound-before-seed ordering"})
                    continue
                first_index = segment.index("FIRST_FEASIBLE")
                refinement = [segment.index(kind) for kind in ("MESSAGE", "BOUNDARY_SPLIT")
                              if kind in segment]
                if refinement and min(refinement) < first_index:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "proof refinement started before the first-feasible yield"})
            if len(local_summaries) != len(certificates):
                errors.append({**record, "message":
                               "each Local certificate requires one terminal MemoLocalSummary"})
            for certificate_index, local in enumerate(local_summaries):
                numeric = ("variables", "originalFactors", "completedMessages", "untouchedBuckets",
                           "messageTouchedVariables", "messageTouchedFactors", "candidatePreparations",
                           "messageMemoryRejections", "memoryRejections", "activeRegions",
                           "unknownRegions", "deferredRegions")
                if any(not isinstance(local.get(name), int) or local.get(name) < 0 for name in numeric):
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "MemoLocalSummary counters must be nonnegative integers"})
                    continue
                if local["completedMessages"] + local["untouchedBuckets"] != local["variables"]:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "completed and untouched message buckets do not cover variables"})
                if local["candidatePreparations"] < local["completedMessages"]:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "completed messages exceed prepared candidates"})
                if local["messageTouchedVariables"] > local["variables"]:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "message touched-variable count exceeds model variables"})
                if local["messageTouchedFactors"] > local["originalFactors"]:
                    errors.append({**record, "certificate": certificate_index,
                                   "message": "message touched-factor count exceeds original factors"})
    return {"schema": "memo-local-action-contract-validation/v1",
            "valid": not errors, "checks": checks, "errors": errors}


def write_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=HERE)
    parser.add_argument("--allow-partial", action="store_true",
                        help="audit currently present suites without requiring the final comparison/ablation/runtime set")
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    base = load_base()
    summaries = base.read_summaries(root)
    aggregate = base.aggregate(summaries)
    actions = analyze_actions(base, summaries)
    validation = enforce_local_contract(base, summaries, base.audit(summaries))
    action_contract = audit_action_contract(summaries, actions)
    receipts = audit_receipts(root, summaries, () if args.allow_partial else
                             ("comparison", "ablation", "runtime"))
    combined_errors = list(validation.get("errors") or [])
    combined_errors.extend(action_contract["errors"])
    combined_errors.extend(receipts["errors"])
    validation.update({"valid": not combined_errors, "errors": combined_errors,
                       "actionContract": action_contract,
                       "receiptValidation": receipts})
    write_json(root / "aggregate.json", aggregate)
    write_json(root / "result-validation.json", validation)
    write_json(root / "local-action-analysis.json", actions)
    write_json(root / "receipt-validation.json", receipts)
    print(f"summaries={len(summaries)} attempts={aggregate['attempts']} "
          f"certificates={validation['certificatesChecked']} "
          f"localRouteChecks={len(validation['localRouteChecks'])} "
          f"actions={actions['recordsParsed']} jobs={receipts['jobsMatched']}/{receipts['jobsExpected']} "
          f"valid={validation['valid']}")
    return 0 if validation["valid"] and not actions["parseErrors"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
