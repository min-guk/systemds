#!/usr/bin/env python3
"""Report terminal planner-analysis phase snapshots from paired workload logs.

SEARCH_SPACE_LIVE phase timers are hierarchical.  The report therefore uses the
ANALYSIS phase's inclusive values for end-to-end totals and sums exclusive values
for an observed non-overlapping phase breakdown.  It never sums inclusive values
across different phase names.  SEARCH_SPACE_WORK rows are cumulative snapshots;
within a run only the last snapshot at or before each terminal analysis is attached.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
from collections import defaultdict
from typing import Iterable


LIVE_RE = re.compile(
    r"^SEARCH_SPACE_LIVE\|seq=(?P<seq>\d+)\|terminal=(?P<terminal>true|false)\|"
    r"LivePhase\[phase=(?P<phase>[A-Z0-9_]+), completedCalls=(?P<completed>\d+), "
    r"activeCount=(?P<active>\d+), inclusiveWallNanos=(?P<inclusive_wall>\d+), "
    r"exclusiveWallNanos=(?P<exclusive_wall>\d+), inclusiveCpuNanos=(?P<inclusive_cpu>\d+), "
    r"exclusiveCpuNanos=(?P<exclusive_cpu>\d+), inclusiveAllocatedBytes=(?P<inclusive_alloc>\d+), "
    r"exclusiveAllocatedBytes=(?P<exclusive_alloc>\d+)\]$")

WORK_PREFIX = "SEARCH_SPACE_WORK|"
METRIC_FIELDS = (
    "completedCalls", "inclusiveWallNanos", "exclusiveWallNanos",
    "inclusiveCpuNanos", "exclusiveCpuNanos",
    "inclusiveAllocatedBytes", "exclusiveAllocatedBytes",
)
HEADLINE_PHASES = (
    "ANALYSIS", "CLOSURE_REPLAY", "DIRECT_BINDING",
    "SUPPORT_PRODUCT_RELATION_MATERIALIZATION",
)


def _parse_work(line: str) -> dict[str, int] | None:
    if not line.startswith(WORK_PREFIX):
        return None
    values: dict[str, int] = {}
    for field in line.strip().split("|")[1:]:
        key, separator, value = field.partition("=")
        if not separator or not value.isdigit():
            return None
        values[key] = int(value)
    return values if "seq" in values else None


def _phase(match: re.Match[str]) -> dict[str, int | str]:
    return {
        "phase": match.group("phase"),
        "completedCalls": int(match.group("completed")),
        "activeCount": int(match.group("active")),
        "inclusiveWallNanos": int(match.group("inclusive_wall")),
        "exclusiveWallNanos": int(match.group("exclusive_wall")),
        "inclusiveCpuNanos": int(match.group("inclusive_cpu")),
        "exclusiveCpuNanos": int(match.group("exclusive_cpu")),
        "inclusiveAllocatedBytes": int(match.group("inclusive_alloc")),
        "exclusiveAllocatedBytes": int(match.group("exclusive_alloc")),
    }


def parse_log(path: pathlib.Path) -> dict:
    terminal: dict[int, list[dict]] = defaultdict(list)
    work_rows: list[dict[str, int]] = []
    for raw in path.read_text(errors="replace").splitlines():
        match = LIVE_RE.match(raw.strip())
        if match and match.group("terminal") == "true":
            terminal[int(match.group("seq"))].append(_phase(match))
            continue
        work = _parse_work(raw.strip())
        if work is not None:
            work_rows.append(work)

    work_rows.sort(key=lambda row: row["seq"])
    analyses = []
    for seq in sorted(terminal):
        phases = terminal[seq]
        roots = [phase for phase in phases if phase["phase"] == "ANALYSIS"]
        if not roots:
            continue
        if len(roots) != 1:
            raise ValueError(f"{path}: terminal seq {seq} has {len(roots)} ANALYSIS roots")
        eligible_work = [row for row in work_rows if row["seq"] <= seq]
        exclusive = {
            "wallNanos": sum(int(row["exclusiveWallNanos"]) for row in phases),
            "cpuNanos": sum(int(row["exclusiveCpuNanos"]) for row in phases),
            "allocatedBytes": sum(int(row["exclusiveAllocatedBytes"]) for row in phases),
        }
        analyses.append({
            "seq": seq,
            "analysisInclusive": {key: roots[0][key] for key in METRIC_FIELDS},
            "observedNonOverlappingExclusive": exclusive,
            "phases": phases,
            "workCumulativeAtTerminal": eligible_work[-1] if eligible_work else None,
        })
    return {
        "log": str(path),
        "terminalAnalyses": analyses,
        "workSnapshots": work_rows,
    }


def _run_metadata(log: pathlib.Path, campaign: pathlib.Path) -> dict[str, str | int | None]:
    relative = log.relative_to(campaign)
    run = relative.parts[1]
    case = relative.parts[3]
    match = re.match(r"^(?P<workload>.+)-pair(?P<pair>\d+)-(?P<variant>baseline|candidate)$", run)
    return {
        "run": run,
        "case": case,
        "workload": match.group("workload") if match else case,
        "pair": int(match.group("pair")) if match else None,
        "variant": match.group("variant") if match else None,
    }


def _add_metrics(target: dict[str, int], source: dict, fields: Iterable[str]) -> None:
    for field in fields:
        target[field] = target.get(field, 0) + int(source[field])


def build_report(campaign: pathlib.Path) -> dict:
    runs = []
    phase_totals: dict[str, dict[str, int]] = defaultdict(dict)
    analysis_totals: dict[str, int] = {}
    exclusive_totals = {"wallNanos": 0, "cpuNanos": 0, "allocatedBytes": 0}
    work_sum: dict[str, int] = {}
    grouped: dict[str, dict] = {}
    analysis_count = 0

    for log in sorted(campaign.glob("runs/*/cases/*/fed.log")):
        parsed = parse_log(log)
        parsed.update(_run_metadata(log, campaign))
        runs.append(parsed)
        group_key = f"{parsed['workload']}:{parsed['variant'] or 'unknown'}"
        group = grouped.setdefault(group_key, {
            "workload": parsed["workload"], "variant": parsed["variant"],
            "runCount": 0, "terminalAnalysisCount": 0,
            "analysisInclusiveTotals": {}, "phaseTotalsAcrossAnalyses": defaultdict(dict),
            "sumOfRunFinalCumulativeWork": {},
        })
        group["runCount"] += 1
        for analysis in parsed["terminalAnalyses"]:
            analysis_count += 1
            group["terminalAnalysisCount"] += 1
            _add_metrics(analysis_totals, analysis["analysisInclusive"], METRIC_FIELDS)
            _add_metrics(group["analysisInclusiveTotals"],
                         analysis["analysisInclusive"], METRIC_FIELDS)
            for field in exclusive_totals:
                exclusive_totals[field] += analysis["observedNonOverlappingExclusive"][field]
            for phase in analysis["phases"]:
                _add_metrics(phase_totals[str(phase["phase"])], phase, METRIC_FIELDS)
                _add_metrics(group["phaseTotalsAcrossAnalyses"][str(phase["phase"])],
                             phase, METRIC_FIELDS)
        # WORK is cumulative within one log.  Summing only the final run snapshot
        # combines independent runs without counting earlier snapshots twice.
        if parsed["workSnapshots"]:
            final_work = parsed["workSnapshots"][-1]
            for key, value in final_work.items():
                if key != "seq":
                    work_sum[key] = work_sum.get(key, 0) + value
                    group_work = group["sumOfRunFinalCumulativeWork"]
                    group_work[key] = group_work.get(key, 0) + value

    for group in grouped.values():
        group["phaseTotalsAcrossAnalyses"] = dict(
            sorted(group["phaseTotalsAcrossAnalyses"].items()))

    return {
        "schemaVersion": 1,
        "campaign": str(campaign),
        "semantics": {
            "terminalSelection": "every terminal seq containing exactly one ANALYSIS root is preserved",
            "analysisInclusive": "ANALYSIS root only; child inclusive times are not added",
            "phaseAggregation": "same phase summed across independent terminal analyses; never summed across phase names",
            "observedNonOverlappingExclusive": "sum of exclusive metrics across emitted phases",
            "allocatedBytes": "instrumentation thread-allocation counters; cumulative bytes allocated while calls run, not live heap or stage/process peak",
            "work": "SEARCH_SPACE_WORK is cumulative within each log; attached row is last seq <= terminal; campaign sum uses only each run's final row",
            "peakMemory": "not derived here; use campaign cgroup peak evidence",
            "unavailable": ["DP visit counts are not emitted by these log records"],
        },
        "summary": {
            "runCount": len(runs),
            "terminalAnalysisCount": analysis_count,
            "analysisInclusiveTotals": analysis_totals,
            "observedNonOverlappingExclusiveTotals": exclusive_totals,
            "phaseTotalsAcrossAnalyses": dict(sorted(phase_totals.items())),
            "headlinePhaseTotals": {
                phase: phase_totals.get(phase, {}) for phase in HEADLINE_PHASES
            },
            "sumOfRunFinalCumulativeWork": work_sum,
        },
        "groups": dict(sorted(grouped.items())),
        "runs": runs,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("campaign", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    report = build_report(args.campaign.resolve())
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    else:
        print(encoded, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
