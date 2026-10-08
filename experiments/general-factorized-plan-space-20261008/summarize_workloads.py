#!/usr/bin/env python3
"""Extract stable LogReg/GLM correctness and planning metrics from harness runs."""

import argparse
import hashlib
import json
import pathlib
import re


TIMING = re.compile(r"\[PlannerTrace\]\[Planner-CandidateE2ETiming\] (?P<fields>.*)")
FIELD = re.compile(r"(?P<name>[A-Za-z]+)=(?P<value>[0-9]+)")
ERROR_OBJECTIVE = re.compile(
    r"certificateObjectiveBits=(?P<certificate>[0-9]+).*?"
    r"canonicalObjectiveBits=(?P<canonical>[0-9]+).*?"
    r"costSurfaceMatches=(?P<cost>true|false).*?selectedStatesMatch=(?P<states>true|false).*?"
    r"sharedLifetimesMatch=(?P<lifetimes>true|false)")
COMMITTED_OBJECTIVE = re.compile(
    r"\[PlannerTrace\]\[Physical-CostContributionComplete\].*?"
    r"objective=(?P<objective>[^ ]+) objectiveBits=(?P<bits>[0-9]+)")
LOCAL_MODEL = re.compile(
    r"\[PlannerTrace\]\[DP-LocalConflict\].*?variables=(?P<variables>[0-9]+) "
    r"hardFactors=(?P<hard>[0-9]+) costFactors=(?P<cost>[0-9]+) "
    r"transfers=(?P<transfers>[0-9]+) rawStates=(?P<states>[0-9]+).*?"
    r"costFingerprint=(?P<fingerprint>[^ ]+)")
PLANNER_COMPLETE = re.compile(
    r"\[PlannerTrace\]\[Planner-Complete\].*?analysis=(?P<analysis>[0-9a-f]{64})")
RUNTIME_PLAN = re.compile(r"\[PlannerRuntimeAudit\].*? plan=(?P<plan>[0-9a-f]{64}) ")
SEARCH_SPACE_LINE = re.compile(
    r"^(?P<kind>SEARCH_SPACE_EXECUTION_RELATION|SEARCH_SPACE_SUPPORT_STORAGE)\|(?P<fields>.*)$",
    re.MULTILINE)


def sha256(path: pathlib.Path) -> str | None:
    if not path.is_file():
        return None
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def search_space_diagnostics(log: str) -> dict[str, dict[str, int | str]]:
    diagnostics: dict[str, dict[str, int | str]] = {}
    for match in SEARCH_SPACE_LINE.finditer(log):
        fields: dict[str, int | str] = {}
        for item in match.group("fields").split("|"):
            name, value = item.split("=", 1)
            fields[name] = int(value) if value.isdecimal() else value
        diagnostics[match.group("kind")] = fields
    return diagnostics


def summarize(run: pathlib.Path, monitor: pathlib.Path | None) -> dict[str, object]:
    result = json.loads((run / "result.json").read_text(encoding="utf-8"))
    case = result["cases"][0]
    name = case["case"]
    log_path = run / "cases" / name / "fed.log"
    log = log_path.read_text(encoding="utf-8", errors="replace")
    timings = [dict((match.group("name"), int(match.group("value")))
                    for match in FIELD.finditer(item.group("fields")))
               for item in TIMING.finditer(log)]
    receipt = case.get("canonicalProof", {}).get("receipt", {})
    canonical = receipt.get("canonicalProof") or {}
    error = receipt.get("errorMessage", "")
    error_objective = ERROR_OBJECTIVE.search(error)
    if error_objective and not canonical:
        canonical = {
            "certificateObjectiveBits": int(error_objective.group("certificate")),
            "canonicalObjectiveBits": int(error_objective.group("canonical")),
            "costSurfaceMatches": error_objective.group("cost") == "true",
            "selectedStatesMatch": error_objective.group("states") == "true",
            "sharedLifetimesMatch": error_objective.group("lifetimes") == "true",
        }
    audit_dir = run / "audit" / f"{name}-fed"
    audit_rows = [json.loads(line) for path in audit_dir.glob("candidate-space-*.jsonl")
                  for line in path.read_text(encoding="utf-8").splitlines()]
    representations: dict[str, int] = {}
    logical_tuples = 0
    for row in audit_rows:
        representation = str(row.get("representation", "EXPLICIT"))
        representations[representation] = representations.get(representation, 0) + 1
        logical_tuples += int(row.get("logicalTuples", 0))
    monitor_summary = None
    if monitor is not None and (monitor / "monitor-summary.json").is_file():
        monitor_summary = json.loads(
            (monitor / "monitor-summary.json").read_text(encoding="utf-8"))
    committed_objectives = list(COMMITTED_OBJECTIVE.finditer(log))
    local_models = list(LOCAL_MODEL.finditer(log))
    planner_complete = list(PLANNER_COMPLETE.finditer(log))
    runtime_plans = sorted({match.group("plan") for match in RUNTIME_PLAN.finditer(log)})
    committed = None
    if committed_objectives:
        objective = committed_objectives[-1]
        model = local_models[-1] if local_models else None
        committed = {
            "objective": float(objective.group("objective")),
            "objectiveBits": int(objective.group("bits")),
            "costFingerprint": model.group("fingerprint") if model else None,
            "modelCounts": None if model is None else {
                "decisionCount": int(model.group("variables")),
                "hardFactorCount": int(model.group("hard")),
                "canonicalCostFactorCount": int(model.group("cost")),
                "transferCount": int(model.group("transfers")),
                "totalAlternativeCount": int(model.group("states")),
            },
            "analysisFingerprint": (planner_complete[-1].group("analysis")
                                    if planner_complete else None),
            "runtimePlanFingerprints": runtime_plans,
        }
    return {
        "run": str(run),
        "status": result["status"],
        "case": name,
        "cpReturncode": case.get("cpReturncode"),
        "fedReturncode": case.get("fedReturncode"),
        "cpFingerprint": case.get("cpFingerprint"),
        "fedFingerprint": case.get("fedFingerprint"),
        "modelComparison": case.get("modelComparison"),
        "cpModelSha256": sha256(run / "cases" / name / "cp-model.csv"),
        "fedModelSha256": sha256(run / "cases" / name / "fed-model.csv"),
        "cpStatistics": case.get("cpStatistics"),
        "fedStatistics": case.get("fedStatistics"),
        "planningTiming": timings[-1] if timings else None,
        "plannerCheckpointSummary": case.get("plannerCheckpointSummary"),
        "candidateAudit": {
            "rows": len(audit_rows),
            "representations": representations,
            "reportedLogicalTuples": logical_tuples,
        },
        "searchSpaceDiagnostics": search_space_diagnostics(log),
        "canonicalProofPassed": case.get("canonicalProof", {}).get("passed"),
        "canonical": canonical,
        "committedPlannerEvidence": committed,
        "canonicalFailureClass": receipt.get("errorClass"),
        "canonicalFailureMessage": error[:1000] if error else None,
        "compileNanos": receipt.get("compileNanos"),
        "executionNanos": receipt.get("executionNanos"),
        "coordinatorMemory": receipt.get("coordinatorMemory"),
        "monitor": monitor_summary,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=pathlib.Path)
    parser.add_argument("--monitor", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    output = summarize(args.run, args.monitor)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(output, sort_keys=True))


if __name__ == "__main__":
    main()
