#!/usr/bin/env python3
"""Aggregate Memo dual-mode experiments and independently audit every published certificate."""

import argparse
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation, localcontext
import json
from pathlib import Path
import re
import sys


PREFIXES = (
    "MemoFactorMeta", "MemoFactor", "MemoFactorGraph", "MemoFactorEval",
    "MemoKernelPhase", "MemoAdmission",
)
LOG_RECORD = re.compile(r"\[(%s)\]\s*(.*)$" % "|".join(PREFIXES))
NULLS = (None, "null", "")


def decimal(value):
    if value in NULLS:
        return None
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError(f"invalid decimal {value!r}") from exc
    if not result.is_finite():
        raise ValueError(f"non-finite decimal {value!r}")
    return result


def decimal_text(value):
    return None if value is None else str(value)


def median(values):
    ordered = sorted(values)
    size = len(ordered)
    if size % 2:
        return ordered[size // 2]
    return (ordered[size // 2 - 1] + ordered[size // 2]) / Decimal(2)


def metric_summary(values):
    if not values:
        return None
    values = [Decimal(str(value)) for value in values]
    return {
        "count": len(values),
        "median": decimal_text(median(values)),
        "min": decimal_text(min(values)),
        "max": decimal_text(max(values)),
    }


def variant_for(suite, run):
    if run.startswith("before-"):
        return "before"
    if run.startswith("after-"):
        return "after"
    if any(run.startswith(name + "-") for name in ("independent", "cluster", "cluster-seed")) \
        and run.rsplit("-", 1)[-1].isdigit():
        return run.rsplit("-", 1)[0]
    return run


def optimization_seconds(row):
    total = 0
    plans = row.get("memoPlans") or []
    if not plans:
        return None
    for plan in plans:
        value = (plan.get("phaseNanos") or {}).get("optimization")
        if value is None:
            return None
        total += int(value)
    return Decimal(total) / Decimal(1_000_000_000)


def attempt_record(path, suite, run, variant, row):
    optimization = optimization_seconds(row) if row.get("status") == "passed" else None
    compile_data = row.get("compile") or {}
    return {
        "summary": str(path),
        "run": run,
        "variant": variant,
        "workload": row.get("workload"),
        "algorithm": row.get("algorithm"),
        "status": row.get("status"),
        "failureCategory": row.get("failureCategory"),
        "returncode": row.get("returncode"),
        "timedOut": row.get("timedOut"),
        "sourceSha256": row.get("sourceSha256"),
        "optimizationSeconds": decimal_text(optimization),
        "fullCompileSeconds": decimal_text(decimal(compile_data.get("fullCompileSeconds"))),
        "wallSeconds": decimal_text(decimal(row.get("wallSeconds"))),
        "peakContainerMemoryUsageBytes": row.get("peakContainerMemoryUsageBytes"),
        "memoPlanCosts": [plan.get("cost") for plan in row.get("memoPlans") or []],
        "certificateCount": len(certificates(row)),
        "suite": suite,
    }


def certificates(row):
    return row.get("dualCertificates") or row.get("treeCertificates") or []


def aggregate(summaries):
    grouped = defaultdict(list)
    all_attempts = []
    for path, data in summaries:
        suite = path.parent.parent.name
        run = path.parent.name
        variant = variant_for(suite, run)
        for row in data.get("compileRows") or []:
            record = attempt_record(path, suite, run, variant, row)
            all_attempts.append(record)
            grouped[(suite, variant, row.get("workload"), row.get("algorithm"))].append(record)

    groups = []
    for key, attempts in sorted(grouped.items(), key=lambda item: tuple(str(v) for v in item[0])):
        suite, variant, workload, algorithm = key
        passed = [item for item in attempts if item["status"] == "passed"]
        metrics = {}
        for output, field in (
            ("optimizationSeconds", "optimizationSeconds"),
            ("fullCompileSeconds", "fullCompileSeconds"),
            ("wallSeconds", "wallSeconds"),
            ("peakContainerMemoryUsageBytes", "peakContainerMemoryUsageBytes"),
        ):
            values = [item[field] for item in passed if item[field] is not None]
            metrics[output] = metric_summary(values)
        groups.append({
            "suite": suite,
            "variant": variant,
            "workload": workload,
            "algorithm": algorithm,
            "attempts": len(attempts),
            "passed": len(passed),
            "failed": len(attempts) - len(passed),
            "metrics": metrics,
            "runs": attempts,
        })

    comparisons = []
    lookup = {(row["suite"], row["variant"], row["workload"], row["algorithm"]): row for row in groups}
    keys = {(row["suite"], row["workload"], row["algorithm"]) for row in groups
        if row["variant"] in ("before", "after")}
    for suite, workload, algorithm in sorted(keys, key=lambda value: tuple(str(v) for v in value)):
        before = lookup.get((suite, "before", workload, algorithm))
        after = lookup.get((suite, "after", workload, algorithm))
        before_value = metric_median(before, "optimizationSeconds")
        after_value = metric_median(after, "optimizationSeconds")
        ratio = None
        if before_value is not None and before_value != 0 and after_value is not None:
            with localcontext() as context:
                context.prec = 300
                ratio = after_value / before_value
        comparisons.append({
            "suite": suite,
            "workload": workload,
            "algorithm": algorithm,
            "beforeMedianOptimizationSeconds": decimal_text(before_value),
            "afterMedianOptimizationSeconds": decimal_text(after_value),
            "afterToBeforeOptimizationRatio": decimal_text(ratio),
        })
    return {
        "schema": "memo-dual-improvement-aggregate/v1",
        "timingContract": "independent cold JVM/process; optimization is sum(memoPlans.phaseNanos.optimization)/1e9",
        "summaryFiles": len(summaries),
        "attempts": len(all_attempts),
        "groups": groups,
        "beforeAfter": comparisons,
    }


def metric_median(group, name):
    if not group:
        return None
    metric = group["metrics"].get(name)
    return None if not metric else decimal(metric["median"])


def audit(summaries):
    errors = []
    audited_certificates = 0
    audited_points = 0
    oom_without_certificates = []
    unaudited_certificates = []
    certificate_audits = []
    summary_results = []
    global_exact = defaultdict(list)
    comparison_exact = defaultdict(lambda: defaultdict(list))

    for path, data in summaries:
        suite = path.parent.parent.name
        variant = variant_for(suite, path.parent.name)
        for row_index, row in enumerate(data.get("compileRows") or []):
            if row.get("algorithm") != "EXACT" or row.get("status") != "passed":
                continue
            vector = exact_cost_vector(row, errors, path, row_index)
            if vector is None:
                continue
            entry = {"path": path, "row": row_index, "vector": vector}
            global_exact[(row.get("sourceSha256"), row.get("workload"))].append(entry)
            if variant in ("before", "after"):
                comparison_exact[(suite, row.get("workload"))][variant].append(entry)

    for key, entries in global_exact.items():
        vectors = {tuple(entry["vector"]) for entry in entries}
        if len(vectors) > 1:
            errors.append({"summary": None, "row": None, "certificate": None,
                "message": f"passed EXACT cost vectors disagree for source/workload {key!r}",
                "vectors": [[str(value) for value in vector] for vector in sorted(vectors)]})
    for key, variants in comparison_exact.items():
        before = {tuple(entry["vector"]) for entry in variants.get("before", [])}
        after = {tuple(entry["vector"]) for entry in variants.get("after", [])}
        if before and after and before != after:
            errors.append({"summary": None, "row": None, "certificate": None,
                "message": f"before/after EXACT cost vectors disagree for suite/workload {key!r}",
                "before": [[str(value) for value in vector] for vector in sorted(before)],
                "after": [[str(value) for value in vector] for vector in sorted(after)]})

    with localcontext() as context:
        context.prec = 300
        for path, data in summaries:
            rows = data.get("compileRows") or []
            summary_error_start = len(errors)
            local_exact = defaultdict(list)
            for row_index, row in enumerate(rows):
                if row.get("algorithm") == "EXACT" and row.get("status") == "passed":
                    vector = exact_cost_vector(row, errors, path, row_index)
                    if vector is not None:
                        local_exact[(row.get("sourceSha256"), row.get("workload"))].append(
                            {"path": path, "row": row_index, "vector": vector})
            for row_index, row in enumerate(rows):
                certs = certificates(row)
                algorithm = str(row.get("algorithm") or "")
                solver_row = algorithm.startswith("MEMO_") or algorithm.startswith("TREE_")
                passed = row.get("status") == "passed"
                failure = str(row.get("failureCategory") or "").lower()
                if row.get("status") == "failed" and not certs and ("oom" in failure or "out_of_memory" in failure):
                    oom_without_certificates.append({
                        "summary": str(path), "row": row_index, "workload": row.get("workload"),
                        "algorithm": row.get("algorithm"), "failureCategory": row.get("failureCategory"),
                    })
                if passed and solver_row:
                    if not certs:
                        errors.append(error(path, row_index, None,
                            "passed MEMO_/TREE_ row requires nonempty certificates"))
                    elif not all(certificate.get("certified") is True for certificate in certs):
                        errors.append(error(path, row_index, None,
                            "passed MEMO_/TREE_ row requires every certificate to be certified"))
                    if len(certs) != len(row.get("memoPlans") or []):
                        errors.append(error(path, row_index, None,
                            "passed MEMO_/TREE_ row requires one certificate per memo plan"))
                if not certs:
                    continue
                key = (row.get("sourceSha256"), row.get("workload"))
                matches = local_exact.get(key) or global_exact.get(key) or []
                baseline = matches[0] if matches else None
                baseline_kind = "same-summary" if local_exact.get(key) else "cross-summary" if baseline else None
                for cert_index, certificate in enumerate(certs):
                    audited_certificates += 1
                    certified = certificate.get("certified") is True
                    if certified and (not passed or (row.get("compile") or {}).get("verified") is not True):
                        errors.append(error(path, row_index, cert_index,
                            "certified certificate requires a passed row with compile.verified=true"))
                    if certificate.get("status") == "EPSILON_OPTIMAL" and not algorithm.endswith("_LOCAL"):
                        errors.append(error(path, row_index, cert_index,
                            "EPSILON_OPTIMAL is permitted only for a LOCAL algorithm"))
                    if baseline is None or cert_index >= len(baseline["vector"]):
                        reason = "missing passed EXACT baseline for source/workload/index"
                        unaudited_certificates.append({"summary": str(path), "row": row_index,
                            "certificate": cert_index, "certified": certified, "reason": reason})
                        if passed or certified:
                            errors.append(error(path, row_index, cert_index, reason))
                        continue
                    optimum = baseline["vector"][cert_index]
                    certificate_audits.append({"summary": str(path), "row": row_index,
                        "certificate": cert_index, "baseline": baseline_kind,
                        "baselineSummary": str(baseline["path"]), "optimum": str(optimum)})
                    previous_lb = None
                    previous_ub = None
                    for point_index, point in enumerate(certificate.get("progression") or []):
                        audited_points += 1
                        previous_lb, previous_ub, _, _ = audit_interval(point, optimum, previous_lb, previous_ub,
                            errors, path, row_index, cert_index, f"progression[{point_index}]")
                    audited_points += 1
                    _, _, final_lb, final_ub = audit_interval(certificate, optimum, previous_lb, previous_ub,
                        errors, path, row_index, cert_index, "final")
                    status = certificate.get("status")
                    if status == "OPTIMAL":
                        if not certified or final_lb is None or final_ub is None or not (final_lb == final_ub == optimum):
                            errors.append(error(path, row_index, cert_index,
                                "OPTIMAL requires certified LB == OPT == UB"))
                    if status == "EPSILON_OPTIMAL":
                        if not certified or final_lb is None or final_ub is None or final_lb <= 0 \
                            or final_ub > Decimal("1.05") * final_lb:
                            errors.append(error(path, row_index, cert_index,
                                "EPSILON_OPTIMAL requires certified positive LB and UB <= 1.05*LB"))
                    if certified and status not in ("OPTIMAL", "EPSILON_OPTIMAL"):
                        errors.append(error(path, row_index, cert_index,
                            f"unexpected certified status {status!r}"))
                    plans = row.get("memoPlans") or []
                    if certified:
                        if cert_index >= len(plans) or plans[cert_index].get("cost") in NULLS or final_ub is None:
                            errors.append(error(path, row_index, cert_index,
                                "certified certificate requires memoPlans[index].cost and terminal UB"))
                        else:
                            plan_cost = checked_decimal(plans[cert_index].get("cost"), errors,
                                path, row_index, cert_index, "memo plan cost")
                            if plan_cost is not None and plan_cost != final_ub:
                                errors.append(error(path, row_index, cert_index,
                                    "terminal memo plan cost differs from certified UB"))
            summary_results.append({
                "summary": str(path),
                "rows": len(rows),
                "valid": len(errors) == summary_error_start,
                "errors": len(errors) - summary_error_start,
            })
    return {
        "schema": "memo-dual-improvement-result-validation/v1",
        "decimalPrecision": 300,
        "valid": not errors,
        "summaryFiles": len(summaries),
        "certificatesChecked": audited_certificates,
        "boundPointsCheckedIncludingFinal": audited_points,
        "exactBaselineKeys": len(global_exact),
        "certificateAudits": certificate_audits,
        "unauditedCertificates": unaudited_certificates,
        "failedOomWithoutCertificatesRetained": oom_without_certificates,
        "summaries": summary_results,
        "errors": errors,
    }


def exact_cost_vector(row, errors, path, row_index):
    plans = row.get("memoPlans") or []
    if not plans:
        errors.append(error(path, row_index, None, "passed EXACT row has no memo plans"))
        return None
    result = []
    for index, plan in enumerate(plans):
        value = checked_decimal(plan.get("cost"), errors, path, row_index, None,
            f"EXACT memoPlans[{index}].cost")
        if value is None:
            return None
        result.append(value)
    return tuple(result)


def checked_decimal(value, errors, path, row, certificate, label):
    try:
        return decimal(value)
    except ValueError as exc:
        errors.append(error(path, row, certificate, f"{label}: {exc}"))
        return None


def audit_interval(point, optimum, previous_lb, previous_ub, errors, path, row, certificate, label):
    lb = checked_decimal(point.get("lowerBound"), errors, path, row, certificate, f"{label} LB")
    ub = checked_decimal(point.get("upperBound"), errors, path, row, certificate, f"{label} UB")
    if lb is not None:
        if lb > optimum:
            errors.append(error(path, row, certificate, f"{label} LB exceeds same-source EXACT optimum"))
        if previous_lb is not None and lb < previous_lb:
            errors.append(error(path, row, certificate, f"{label} LB decreased"))
        previous_lb = lb
    if ub is not None:
        if ub < optimum:
            errors.append(error(path, row, certificate, f"{label} UB is below same-source EXACT optimum"))
        if previous_ub is not None and ub > previous_ub:
            errors.append(error(path, row, certificate, f"{label} UB increased"))
        previous_ub = ub
    return previous_lb, previous_ub, lb, ub


def error(path, row, certificate, message):
    return {"summary": str(path), "row": row, "certificate": certificate, "message": message}


def expected_log(summary_path, row):
    workload = str(row.get("workload") or "").lower()
    algorithm = str(row.get("algorithm") or "").lower()
    workers = row.get("workers")
    candidate = summary_path.parent / "compile" / f"{workload}-w{workers}-{algorithm}" / "coordinator.log"
    return candidate if candidate.is_file() else None


def factor_analysis(summaries):
    records = []
    parse_errors = []
    seen_logs = set()
    for summary_path, data in summaries:
        suite = summary_path.parent.parent.name
        run = summary_path.parent.name
        variant = variant_for(suite, run)
        mapped = {}
        for row in data.get("compileRows") or []:
            log = expected_log(summary_path, row)
            if log:
                mapped[log.resolve()] = (row.get("workload"), row.get("algorithm"))
        logs = sorted(summary_path.parent.rglob("coordinator.log"))
        for log in logs:
            resolved = log.resolve()
            if resolved in seen_logs:
                continue
            seen_logs.add(resolved)
            workload, algorithm = mapped.get(resolved, (None, None))
            for line_number, line in enumerate(log.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                match = LOG_RECORD.search(line)
                if not match:
                    continue
                prefix, payload = match.groups()
                try:
                    value = json.loads(payload)
                except json.JSONDecodeError as exc:
                    parse_errors.append({"log": str(log), "line": line_number, "prefix": prefix,
                        "error": str(exc)})
                    continue
                records.append({"suite": suite, "run": run, "variant": variant,
                    "workload": workload, "algorithm": algorithm, "log": str(log),
                    "line": line_number, "prefix": prefix, "value": value})

    groups = defaultdict(list)
    for record in records:
        groups[(record["suite"], record["variant"], record["workload"], record["algorithm"])].append(record)
    output_groups = []
    overall_categories = Counter()
    overall_shared = 0
    all_raw_complete = True
    factor_timing_table = []
    for key, values in sorted(groups.items(), key=lambda item: tuple(str(v) for v in item[0])):
        factor_categories = Counter()
        shared = 0
        graph = []
        evaluation = defaultdict(lambda: {"records": 0, "evaluations": 0, "nanos": 0})
        kernel = []
        admissions = []
        metadata = []
        for record in values:
            value = record["value"]
            if record["prefix"] == "MemoFactor":
                category = str(value.get("category"))
                factor_categories[category] += 1
                tags = value.get("tags") or []
                if "shared:true" in tags:
                    shared += 1
            elif record["prefix"] == "MemoFactorGraph":
                graph.append(value)
            elif record["prefix"] == "MemoFactorEval":
                category = str(value.get("category"))
                target = evaluation[category]
                target["records"] += 1
                target["evaluations"] += int(value.get("evaluations") or 0)
                target["nanos"] += int(value.get("nanos") or 0)
            elif record["prefix"] == "MemoKernelPhase":
                kernel.append(value)
            elif record["prefix"] == "MemoAdmission":
                admissions.append(value)
            elif record["prefix"] == "MemoFactorMeta":
                metadata.append(value)
        overall_categories.update(factor_categories)
        overall_shared += shared
        kernel_totals = defaultdict(lambda: {"records": 0, "nanos": 0})
        for value in kernel:
            phase = str(value.get("phase"))
            kernel_totals[phase]["records"] += 1
            kernel_totals[phase]["nanos"] += int(value.get("nanos") or 0)
        admission_totals = {
            "records": len(admissions),
            "admitted": sum(value.get("admitted") is True for value in admissions),
            "rejected": sum(value.get("admitted") is False for value in admissions),
            "reasons": dict(sorted(Counter(str(value.get("reason")) for value in admissions).items())),
        }
        expected_raw = sum(int(value.get("emittedFactors") or 0) for value in metadata)
        omitted_raw = sum(int(value.get("omittedFactors") or 0) for value in metadata)
        raw_complete = bool(metadata) and omitted_raw == 0 and sum(factor_categories.values()) == expected_raw
        raw_status = "complete" if raw_complete else "partial" if metadata else "unavailable"
        all_raw_complete &= raw_complete
        for category, timing in sorted(evaluation.items()):
            factor_timing_table.append({"suite": key[0], "variant": key[1], "workload": key[2],
                "algorithm": key[3], "category": category, **timing})
        output_groups.append({
            "suite": key[0], "variant": key[1], "workload": key[2], "algorithm": key[3],
            "rawFactorCountsStatus": raw_status,
            "rawFactorCountsComplete": raw_complete,
            "rawFactorRecordsExpectedEmitted": expected_raw,
            "rawFactorRecordsObserved": sum(factor_categories.values()),
            "rawFactorRecordsOmittedByEmitter": omitted_raw,
            "factorCategoryRecordCountsObserved": dict(sorted(factor_categories.items())),
            "sharedTrueFactorRecordsObserved": shared,
            "factorMetadata": metadata,
            "graphRawAndLeaveOutRecords": graph,
            "factorEvaluationTotals": dict(sorted(evaluation.items())),
            "kernelPhaseTotals": dict(sorted(kernel_totals.items())),
            "kernelPhaseRecords": kernel,
            "admissionTotals": admission_totals,
            "admissionRecords": admissions,
        })
    return {
        "schema": "memo-dual-improvement-factor-analysis/v1",
        "interpretation": "Raw overlapping category/tag leave-outs are descriptive structural projections; they are not additive or causal contributions.",
        "logPrefixes": list(PREFIXES),
        "logsScanned": len(seen_logs),
        "recordsParsed": len(records),
        "parseErrors": parse_errors,
        "overallRawFactorCountsComplete": all_raw_complete if output_groups else None,
        "overallFactorCategoryRecordCountsObserved": dict(sorted(overall_categories.items())),
        "overallSharedTrueFactorRecordsObserved": overall_shared,
        "factorTimingSuites": factor_timing_table,
        "groups": output_groups,
    }


def read_summaries(root):
    result = []
    for path in sorted(root.glob("*/*/summary.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"cannot read {path}: {exc}") from exc
        result.append((path, data))
    return result


def write_json(path, value):
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent,
        help="artifact root containing suite/run/summary.json (default: this script's directory)")
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    summaries = read_summaries(root)
    aggregate_result = aggregate(summaries)
    validation_result = audit(summaries)
    factor_result = factor_analysis(summaries)
    write_json(root / "aggregate.json", aggregate_result)
    write_json(root / "result-validation.json", validation_result)
    write_json(root / "factor-analysis.json", factor_result)
    print(f"summaries={len(summaries)} attempts={aggregate_result['attempts']} "
        f"certificates={validation_result['certificatesChecked']} valid={validation_result['valid']}")
    return 0 if validation_result["valid"] and not factor_result["parseErrors"] else 1


if __name__ == "__main__":
    sys.exit(main())
