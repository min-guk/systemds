#!/usr/bin/env python3
"""Run alternating baseline/candidate FedPlanner workloads with exact paired evidence."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import pathlib
import re
import statistics
import struct
import subprocess
import time
from typing import Any


DEFAULT_WORKLOADS = ("ml_logreg", "ml_glm")
SUPPORTED_WORKLOADS = DEFAULT_WORKLOADS + ("ml_steplm", "ml_steplm_local_matrix")
SUPPORTED_WORKLOADS += ("weighted_quaternary_protected_row",)
TIMING_LINE = re.compile(r"\[PlannerTrace\]\[Planner-CandidateE2ETiming\] (?P<fields>.*)")
TIMING_FIELD = re.compile(r"(?P<name>[A-Za-z]+)=(?P<value>[0-9]+)")
OBJECT_CREATION = re.compile(r"^SEARCH_SPACE_OBJECT_CREATION\|(?P<fields>.*)$", re.MULTILINE)
EXECUTION_RELATION = re.compile(
    r"^SEARCH_SPACE_EXECUTION_RELATION\|(?P<fields>.*)$", re.MULTILINE)


class ValidationFailure(RuntimeError):
    pass


def paired_order(repetitions: int) -> list[tuple[int, str]]:
    order: list[tuple[int, str]] = []
    for pair in range(repetitions):
        variants = ("baseline", "candidate") if pair % 2 == 0 else ("candidate", "baseline")
        order.extend((pair, variant) for variant in variants)
    return order


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def engine_manifest(engine: pathlib.Path) -> dict[str, Any]:
    roots = ("classes", "test-classes", "src/main/java", "src/test/java")
    files = []
    for root in roots:
        base = engine / root
        if not base.is_dir():
            raise ValidationFailure(f"engine is missing {root}: {engine}")
        for path in sorted(item for item in base.rglob("*") if item.is_file()):
            files.append({"path": path.relative_to(engine).as_posix(),
                          "bytes": path.stat().st_size, "sha256": sha256(path)})
    payload = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"schema": "fed-oracle-native-engine-manifest-v1", "engine": str(engine),
            "fileCount": len(files), "treeSha256": hashlib.sha256(payload).hexdigest(),
            "files": files}


def cgroup_directory(pid: int, cgroup_root: pathlib.Path = pathlib.Path("/sys/fs/cgroup"),
                     proc_root: pathlib.Path = pathlib.Path("/proc")) -> pathlib.Path | None:
    try:
        rows = (proc_root / str(pid) / "cgroup").read_text().splitlines()
    except OSError:
        return None
    for row in rows:
        parts = row.split(":", 2)
        if len(parts) == 3 and parts[0] == "0" and parts[1] == "":
            return cgroup_root / parts[2].lstrip("/")
    return None


def read_integer(path: pathlib.Path) -> int | None:
    try:
        value = path.read_text().strip()
        return int(value) if value != "max" else None
    except (OSError, ValueError):
        return None


def inspect_container_pid(container: str) -> int | None:
    result = subprocess.run(["docker", "inspect", "--format", "{{.State.Pid}}", container],
                            text=True, capture_output=True, check=False)
    try:
        pid = int(result.stdout.strip())
    except ValueError:
        return None
    return pid if result.returncode == 0 and pid > 0 else None


def parse_stats_bytes(value: str) -> int | None:
    units = (("GiB", 1024**3), ("MiB", 1024**2), ("KiB", 1024), ("B", 1))
    value = value.strip()
    for suffix, scale in units:
        if value.endswith(suffix):
            try:
                return round(float(value[:-len(suffix)]) * scale)
            except ValueError:
                return None
    return None


def allocation_events_summary(payload: dict[str, Any]) -> dict[str, Any]:
    events = payload.get("recording", {}).get("events", [])
    result: dict[str, dict[str, int]] = {}
    by_class: dict[str, dict[str, int]] = {}
    by_planner_frame: dict[str, dict[str, int]] = {}
    by_planner_phase: dict[str, dict[str, int]] = {}
    by_class_and_frame: dict[str, dict[str, int]] = {}

    def add(target: dict[str, dict[str, int]], key: str, size: int) -> None:
        row = target.setdefault(key, {"count": 0, "representedBytes": 0})
        row["count"] += 1
        row["representedBytes"] += size

    def frame_name(frame: dict[str, Any]) -> str | None:
        method = frame.get("method", {})
        owner = method.get("type", {}).get("name")
        name = method.get("name")
        if not isinstance(owner, str) or not isinstance(name, str):
            return None
        return owner.replace("/", ".") + "." + name

    def planner_phase(frame: str) -> str:
        if "PlacementAnalysis" in frame:
            return "placement-analysis"
        if any(name in frame for name in
               ("PlacementCandidateGenerator", "PlacementRelationClosure", "Oracle")):
            return "candidate-generation-and-closure"
        if "PhysicalModel" in frame:
            return "physical-model"
        if "Optimizer" in frame or ".dp." in frame.lower():
            return "optimizer-dp"
        if "DMLTranslator" in frame:
            return "translator"
        return "fedplanner-other"

    def ranked(target: dict[str, dict[str, int]], limit: int = 50) -> list[dict[str, Any]]:
        return [dict(name=name, **row) for name, row in sorted(
            target.items(), key=lambda item: (-item[1]["representedBytes"], item[0]))[:limit]]

    for event in events:
        event_type = str(event.get("type", "unknown"))
        values = event.get("values", {})
        size = values.get("weight", values.get("allocationSize", values.get("tlabSize", 0)))
        size = size if isinstance(size, int) else 0
        add(result, event_type, size)
        object_class = (values.get("objectClass") or {}).get("name", "unknown")
        object_class = (object_class.replace("/", ".")
                        if isinstance(object_class, str) else "unknown")
        add(by_class, object_class, size)
        frames = [name for frame in (values.get("stackTrace") or {}).get("frames", [])
                  if (name := frame_name(frame)) is not None]
        planner_frames = [name for name in frames
                          if ("org.apache.sysds.hops.fedplanner" in name
                              or name.startswith("org.apache.sysds.parser.DMLTranslator."))]
        if planner_frames:
            top = planner_frames[0]
            add(by_planner_frame, top, size)
            add(by_planner_phase, planner_phase(top), size)
            add(by_class_and_frame, object_class + " @ " + top, size)
    return {"status": "PASSED", "events": result,
            "totalEventCount": sum(row["count"] for row in result.values()),
            "totalRepresentedBytes": sum(row["representedBytes"] for row in result.values()),
            "plannerAttributedEventCount": sum(row["count"] for row in by_planner_frame.values()),
            "plannerAttributedRepresentedBytes": sum(
                row["representedBytes"] for row in by_planner_frame.values()),
            "topAllocatedClasses": ranked(by_class),
            "topPlannerFrames": ranked(by_planner_frame),
            "plannerPhases": ranked(by_planner_phase),
            "topClassAndPlannerFrame": ranked(by_class_and_frame)}


def jfr_allocation_summary(recording: pathlib.Path) -> dict[str, Any]:
    if not recording.is_file():
        return {"status": "ABSENT"}
    result = subprocess.run([
        "jfr", "print", "--json", "--events",
        "jdk.ObjectAllocationSample,jdk.ObjectAllocationInNewTLAB,"
        "jdk.ObjectAllocationOutsideTLAB", str(recording)],
        text=True, capture_output=True, check=False)
    if result.returncode != 0:
        return {"status": "UNAVAILABLE", "returncode": result.returncode,
                "stderr": result.stderr[-1000:]}
    try:
        summary = allocation_events_summary(json.loads(result.stdout))
    except json.JSONDecodeError as error:
        return {"status": "INVALID", "error": str(error)}
    summary.update({"recording": str(recording), "bytes": recording.stat().st_size,
                    "sha256": sha256(recording)})
    return summary


def runner_command(repo: pathlib.Path, engine: pathlib.Path, dependencies: pathlib.Path,
                   output_root: pathlib.Path, stage_root: pathlib.Path, run_id: str,
                   workload: str, profile_jfr: bool) -> list[str]:
    command = [str(repo / "scripts/fedplanner/run_LAN_docker.sh"), "--joint-boundary-e2e",
               "--classes", str(engine / "classes"),
               "--test-classes", str(engine / "test-classes"),
               "--main-sources", str(engine / "src/main/java"),
               "--test-sources", str(engine / "src/test/java"),
               "--dependencies", str(dependencies), "--output-root", str(output_root),
               "--stage-root", str(stage_root), "--run-id", run_id,
               "--planner", "local", "--canonical-proof", "--timeout-seconds", "1200",
               "--case-timeout-seconds", "600", "--case", workload]
    if profile_jfr:
        command.append("--profile-jfr")
    return command


def monitor_run(command: list[str], run_dir: pathlib.Path, evidence_dir: pathlib.Path) -> int:
    evidence_dir.mkdir(parents=True, exist_ok=False)
    (evidence_dir / "command.json").write_text(json.dumps(command, indent=2) + "\n")
    started = time.monotonic()
    samples: list[dict[str, Any]] = []
    container = None
    cgroup = None
    with (evidence_dir / "run.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, text=True)
        while process.poll() is None:
            manifest = run_dir / "manifest.json"
            if container is None and manifest.is_file():
                try:
                    container = json.loads(manifest.read_text())["container"]
                except (OSError, KeyError, json.JSONDecodeError):
                    pass
            if container and cgroup is None:
                pid = inspect_container_pid(container)
                if pid is not None:
                    cgroup = cgroup_directory(pid)
            row: dict[str, Any] = {"elapsedSeconds": round(time.monotonic() - started, 3)}
            if cgroup is not None:
                row["cgroup"] = str(cgroup)
                row["memoryCurrentBytes"] = read_integer(cgroup / "memory.current")
                row["memoryPeakBytes"] = read_integer(cgroup / "memory.peak")
            if container:
                stats = subprocess.run(["docker", "stats", "--no-stream", "--format",
                                        "{{json .}}", container], text=True,
                                       capture_output=True, check=False)
                if stats.returncode == 0 and stats.stdout.strip():
                    try:
                        payload = json.loads(stats.stdout)
                        row["dockerStatsMemoryBytes"] = parse_stats_bytes(
                            payload.get("MemUsage", "").split("/", 1)[0])
                    except json.JSONDecodeError:
                        pass
            if len(row) > 1:
                samples.append(row)
            time.sleep(0.2)
        returncode = process.wait()
    with (evidence_dir / "memory-samples.jsonl").open("w") as stream:
        for sample in samples:
            stream.write(json.dumps(sample, sort_keys=True) + "\n")
    kernel_peaks = [row["memoryPeakBytes"] for row in samples
                    if row.get("memoryPeakBytes") is not None]
    sampled = [row["dockerStatsMemoryBytes"] for row in samples
               if row.get("dockerStatsMemoryBytes") is not None]
    summary = {"returncode": returncode,
               "elapsedSeconds": round(time.monotonic() - started, 3),
               "sampleCount": len(samples),
               "cgroupMemoryPeakBytes": max(kernel_peaks, default=None),
               "peakSampledDockerStatsBytes": max(sampled, default=None),
               "cgroupPeakAvailable": bool(kernel_peaks),
               "sampleDelaySeconds": 0.2,
               "dockerStatsIsBlocking": True}
    (evidence_dir / "monitor-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n")
    return returncode


def proof(run_dir: pathlib.Path, workload: str) -> dict[str, Any]:
    path = run_dir / "cases" / workload / "fed-canonical-proof.json"
    if not path.is_file():
        raise ValidationFailure(f"canonical proof is absent: {path}")
    return json.loads(path.read_text())


def workload_metrics(run_dir: pathlib.Path, workload: str) -> dict[str, Any]:
    result = json.loads((run_dir / "result.json").read_text())
    matches = [row for row in result.get("cases", []) if row.get("case") == workload]
    if len(matches) != 1:
        raise ValidationFailure(f"expected one {workload} result, found {len(matches)}")
    case = matches[0]
    log = (run_dir / "cases" / workload / "fed.log").read_text(errors="replace")
    timings = [{match.group("name"): int(match.group("value"))
                for match in TIMING_FIELD.finditer(line.group("fields"))}
               for line in TIMING_LINE.finditer(log)]
    timing = timings[-1] if timings else {}
    timing_seconds = {name[:-5]: value / 1e9 for name, value in timing.items()
                      if name.endswith("Nanos")}
    comparison = case.get("modelComparison") or {}
    object_matches = list(OBJECT_CREATION.finditer(log))
    object_creation = None
    if object_matches:
        object_creation = {}
        for item in object_matches[-1].group("fields").split("|"):
            name, value = item.split("=", 1)
            object_creation[name] = int(value) if value.isdecimal() else value
    relation_matches = list(EXECUTION_RELATION.finditer(log))
    execution_relation = None
    if relation_matches:
        execution_relation = {}
        for item in relation_matches[-1].group("fields").split("|"):
            name, value = item.split("=", 1)
            execution_relation[name] = int(value) if value.isdecimal() else value
    return {"harnessStatus": result.get("status"),
            "casePassed": case.get("passed"),
            "modelMatched": comparison.get("matched"),
            "modelMaxAbsDifference": comparison.get("maxAbsDifference"),
            "fedCompilationSeconds": (case.get("fedStatistics") or {}).get(
                "compilationSeconds"),
            "candidateE2ESeconds": timing.get("totalNanos", 0) / 1e9,
            "analysisSeconds": timing.get("analysisNanos", 0) / 1e9,
            "modelSeconds": timing.get("modelNanos", 0) / 1e9,
            "costSurfaceSeconds": timing.get("costSurfaceNanos", 0) / 1e9,
            "optimizerSeconds": timing.get("optimizerNanos", 0) / 1e9,
            "candidateE2EPhasesSeconds": timing_seconds,
            "objectCreation": object_creation,
            "executionRelation": execution_relation,
            "candidateLegality": candidate_legality_inventory(run_dir, workload)}


def input_signature(row: dict[str, Any]) -> tuple[str, ...]:
    return tuple(("PRESENT:" + str(item.get("fType"))) if item.get("presence") == "PRESENT"
                 else "ABSENT_LOCAL:-" for item in row.get("inputSignature", []))


def candidate_legality_inventory(run_dir: pathlib.Path, workload: str) -> dict[str, Any]:
    audit = run_dir / "audit" / f"{workload}-fed"
    entries: list[str] = []
    for path in sorted(audit.glob("candidate-space-*.jsonl")):
        for line in path.read_text().splitlines():
            row = json.loads(line)
            if row.get("schema") != "fedplanner-candidate-space-v1":
                continue
            axes = row.get("inputAxes")
            inputs = itertools.product(*axes) if isinstance(axes, list) else (input_signature(row),)
            rule = row.get("publishedRule") or {}
            emissions = [{name: emission.get(name) for name in
                          ("exec", "output", "fType", "executionFType", "shapeDependent",
                           "derivedFedFout", "derivedAction")}
                         for emission in rule.get("emissions", [])]
            semantics = {"occurrence": row.get("occurrence"),
                         "publishedNodeStates": row.get("publishedNodeStates", []),
                         "rule": {name: rule.get(name) for name in
                                  ("status", "failureCode", "capability", "shapeProof",
                                   "producerOutputs", "profileFailure")},
                         "emissions": emissions}
            for combination in inputs:
                item = dict(semantics)
                item["inputs"] = list(combination)
                entries.append(json.dumps(item, sort_keys=True, separators=(",", ":")))
                if len(entries) > 1_000_000:
                    raise ValidationFailure("candidate legality inventory exceeds one million rows")
    entries.sort()
    digest = hashlib.sha256("\n".join(entries).encode()).hexdigest()
    return {"expandedRows": len(entries), "sha256": digest}


def validate_weighted_authority(receipt: dict[str, Any]) -> list[str]:
    failures = []
    for opcode in ("q(wsloss)", "q(wcemm)"):
        rows = [row for row in receipt.get("selectedOccurrences", [])
                if row.get("opcode") == opcode]
        if len(rows) != 1:
            failures.append(f"{opcode}.occurrenceCount")
            continue
        row = rows[0]
        authorities = [item for item in row.get("inputAuthorities", [])
                       if item.get("inputPosition") == 0
                       and item.get("kind") == "DIRECT_FOUT"]
        if len(authorities) != 1 or "X_PROTECTED" not in str(authorities[0].get("sourceDecision")):
            failures.append(f"{opcode}.exactSourceAuthority")
        elif not authorities[0].get("relocationAction"):
            failures.append(f"{opcode}.selectedSourceAction")
        support = row.get("supportClause") or {}
        bindings = [item for item in support.get("inputBindings", [])
                    if item.get("inputPosition") == 0 and item.get("kind") == "DIRECT"]
        if len(bindings) != 1:
            failures.append(f"{opcode}.directSupport")
        elif "X_PROTECTED" not in "|".join(str(bindings[0].get(name))
                                              for name in ("sourceRule", "sourceOwner",
                                                           "sourceRealization")):
            failures.append(f"{opcode}.supportSource")
    return failures


def semantic_support_clause(clause: dict[str, Any] | None) -> dict[str, Any] | None:
    if clause is None:
        return None
    return {name: clause.get(name) for name in
            ("proofDependencies", "inputBindings", "nativeWorkerPoolWitness",
             "nativeWorkerPoolLayoutExact")}


def semantic_candidate_support(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [{name: row.get(name) for name in
             ("exactRule", "emission", "realization", "support", "proofKeys", "inputBindings")}
            | {"supportClause": semantic_support_clause(row.get("supportClause"))}
            for row in rows]


def receipt_schema_failures(side: str, receipt: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    for name in ("selectedCandidateSelections", "selectedRelocations",
                 "selectedLocalMaterializations", "sharedSupplyLifetimes"):
        if not isinstance(receipt.get(name), list):
            failures.append(f"{side}.{name}.schema")
    support_rows = receipt.get("selectedCandidateSupport")
    occurrence_rows = receipt.get("selectedOccurrences")
    if not isinstance(support_rows, list):
        failures.append(f"{side}.selectedCandidateSupport.schema")
        support_rows = []
    if not isinstance(occurrence_rows, list):
        failures.append(f"{side}.selectedOccurrences.schema")
        occurrence_rows = []

    def clause_valid(value: Any, allow_none: bool) -> bool:
        if value is None:
            return allow_none
        return (isinstance(value, dict)
                and isinstance(value.get("proofDependencies"), list)
                and isinstance(value.get("inputBindings"), list)
                and "nativeWorkerPoolWitness" in value
                and isinstance(value.get("nativeWorkerPoolLayoutExact"), bool))

    for index, row in enumerate(support_rows):
        valid = (isinstance(row, dict)
                 and all(isinstance(row.get(name), str) for name in
                         ("exactRule", "emission", "realization", "support"))
                 and isinstance(row.get("proofKeys"), list)
                 and isinstance(row.get("inputBindings"), list)
                 and "supportClause" in row
                 and clause_valid(row.get("supportClause"), False))
        if not valid:
            failures.append(f"{side}.selectedCandidateSupport[{index}].schema")
    for index, row in enumerate(occurrence_rows):
        valid = (isinstance(row, dict)
                 and isinstance(row.get("occurrence"), str)
                 and isinstance(row.get("physicalState"), str)
                 and isinstance(row.get("inputAuthorities"), list)
                 and "supportClause" in row
                 and clause_valid(row.get("supportClause"), True))
        if not valid:
            failures.append(f"{side}.selectedOccurrences[{index}].schema")
    return failures


def compare_pair(baseline: dict[str, Any], candidate: dict[str, Any],
                 baseline_legality: dict[str, Any] | None = None,
                 candidate_legality: dict[str, Any] | None = None,
                 weighted: bool = False) -> dict[str, Any]:
    fields = ("selectedCandidateSelections", "selectedRelocations",
              "selectedLocalMaterializations",
              "sharedSupplyLifetimes")
    failures = receipt_schema_failures("baseline", baseline)
    failures.extend(receipt_schema_failures("candidate", candidate))
    failures.extend(field for field in fields if baseline.get(field) != candidate.get(field))
    baseline_support = baseline.get("selectedCandidateSupport")
    candidate_support = candidate.get("selectedCandidateSupport")
    baseline_support = baseline_support if isinstance(baseline_support, list) else []
    candidate_support = candidate_support if isinstance(candidate_support, list) else []
    if semantic_candidate_support(baseline_support) != semantic_candidate_support(candidate_support):
        failures.append("selectedCandidateSupport")
    baseline_occurrences = baseline.get("selectedOccurrences")
    candidate_occurrences = candidate.get("selectedOccurrences")
    baseline_occurrences = baseline_occurrences if isinstance(baseline_occurrences, list) else []
    candidate_occurrences = candidate_occurrences if isinstance(candidate_occurrences, list) else []
    baseline_states = [(row.get("occurrence"), row.get("physicalState"))
                       for row in baseline_occurrences if isinstance(row, dict)]
    candidate_states = [(row.get("occurrence"), row.get("physicalState"))
                        for row in candidate_occurrences if isinstance(row, dict)]
    if baseline_states != candidate_states:
        failures.append("selectedOccurrencePhysicalStates")
    baseline_authority = [(row.get("occurrence"), row.get("inputAuthorities"),
                           semantic_support_clause(row.get("supportClause")))
                          for row in baseline_occurrences if isinstance(row, dict)]
    candidate_authority = [(row.get("occurrence"), row.get("inputAuthorities"),
                            semantic_support_clause(row.get("supportClause")))
                           for row in candidate_occurrences if isinstance(row, dict)]
    if baseline_authority != candidate_authority:
        failures.append("selectedOccurrenceSourceAuthority")
    left = baseline.get("canonicalProof", {})
    right = candidate.get("canonicalProof", {})
    if left.get("canonicalObjectiveBits") != right.get("canonicalObjectiveBits"):
        failures.append("canonicalObjectiveBits")
    if baseline_legality != candidate_legality:
        failures.append("candidateLegalityAndCapabilities")
    if weighted:
        failures.extend("baseline." + item for item in validate_weighted_authority(baseline))
        failures.extend("candidate." + item for item in validate_weighted_authority(candidate))
    for side, value in (("baseline", left), ("candidate", right)):
        for field in ("objectiveMatches", "selectedStatesMatch", "sharedLifetimesMatch"):
            if value.get(field) is not True:
                failures.append(f"{side}.{field}")
    return {"status": "PASSED" if not failures else "FAILED", "failures": failures,
            "canonicalObjectiveBits": right.get("canonicalObjectiveBits"),
            "selectedCandidateCount": len(candidate.get("selectedCandidateSelections", []))}


def numeric_csv(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file():
        return {"status": "ABSENT", "path": str(path)}
    values = [float(value) for line in path.read_text().splitlines()
              for value in line.split(",") if value.strip()]
    return {"status": "PRESENT", "path": str(path), "sha256": sha256(path),
            "count": len(values), "values": values,
            "bits": [struct.pack(">d", value).hex() for value in values]}


def compare_numeric_outputs(baseline_run: pathlib.Path, candidate_run: pathlib.Path,
                            workload: str) -> dict[str, Any]:
    names = ["fed-model.csv"]
    if workload in ("ml_steplm", "ml_steplm_local_matrix"):
        names.append("fed-selection.csv")
    files = []
    passed = True
    for name in names:
        before = numeric_csv(baseline_run / "cases" / workload / name)
        after = numeric_csv(candidate_run / "cases" / workload / name)
        if before["status"] == "ABSENT" and after["status"] == "ABSENT":
            continue
        same_shape = before.get("count") == after.get("count")
        exact_bits = same_shape and before.get("bits") == after.get("bits")
        maximum = (max((abs(left - right) for left, right in
                        zip(before.get("values", []), after.get("values", []))), default=0.0)
                   if same_shape else None)
        tolerance_passed = same_shape and maximum is not None and maximum <= 1e-12
        passed &= tolerance_passed
        files.append({"name": name, "baselineSha256": before.get("sha256"),
                      "candidateSha256": after.get("sha256"), "count": before.get("count"),
                      "exactBits": exact_bits, "maxAbsDifference": maximum,
                      "tolerance": 1e-12, "passed": tolerance_passed})
    return {"status": "PASSED" if passed else "FAILED", "files": files}


def paired_ratios(rows: list[dict[str, Any]]) -> dict[str, Any]:
    extractors = {
        "elapsed": lambda row: row.get("elapsedSeconds"),
        "cgroupPeak": lambda row: row.get("cgroupMemoryPeakBytes"),
        "fedCompilation": lambda row: row.get("metrics", {}).get("fedCompilationSeconds"),
        "candidateE2E": lambda row: row.get("metrics", {}).get("candidateE2ESeconds"),
        "analysis": lambda row: row.get("metrics", {}).get("analysisSeconds"),
        "model": lambda row: row.get("metrics", {}).get("modelSeconds"),
        "costSurface": lambda row: row.get("metrics", {}).get("costSurfaceSeconds"),
        "optimizer": lambda row: row.get("metrics", {}).get("optimizerSeconds"),
    }
    ratios: dict[str, list[float]] = {name: [] for name in extractors}
    for row in rows:
        baseline, candidate = row["baseline"], row["candidate"]
        for name, extract in extractors.items():
            before, after = extract(baseline), extract(candidate)
            if before is not None and after is not None and before != 0:
                ratios[name].append(after / before)
    return {name: {"ratios": values, "medianRatio": statistics.median(values) if values else None}
            for name, values in ratios.items()}


def summarize_completed_run(run_dir: pathlib.Path, evidence: pathlib.Path,
                            workload: str, include_jfr: bool) -> dict[str, Any]:
    summary = json.loads((evidence / "monitor-summary.json").read_text())
    summary["run"] = str(run_dir)
    summary["proof"] = proof(run_dir, workload)
    summary["metrics"] = workload_metrics(run_dir, workload)
    summary["metrics"]["modelCounts"] = summary["proof"].get(
        "canonicalProof", {}).get("modelCounts")
    if (summary["metrics"]["harnessStatus"] != "PASSED"
            or summary["metrics"]["casePassed"] is not True
            or summary["metrics"]["modelMatched"] is False):
        raise ValidationFailure(f"{run_dir.name} did not pass model/runtime validation")
    summary["jfrAllocation"] = (jfr_allocation_summary(
        run_dir / "cases" / workload / "fed.jfr") if include_jfr
        else {"status": "DISABLED"})
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=pathlib.Path, default=pathlib.Path.cwd())
    parser.add_argument("--baseline-engine", type=pathlib.Path, required=True)
    parser.add_argument("--candidate-engine", type=pathlib.Path, required=True)
    parser.add_argument("--dependencies", type=pathlib.Path, required=True)
    parser.add_argument("--output-root", type=pathlib.Path, required=True)
    parser.add_argument("--stage-root", type=pathlib.Path, required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--workload", action="append", choices=SUPPORTED_WORKLOADS)
    parser.add_argument("--jfr-pair", type=int, action="append", default=[])
    parser.add_argument("--reprocess-only", action="store_true",
                        help="rebuild paired-summary.json from completed runs")
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    workloads = tuple(args.workload or DEFAULT_WORKLOADS)
    if args.reprocess_only:
        if not args.output_root.is_dir():
            raise ValidationFailure(f"output root does not exist: {args.output_root}")
    else:
        args.output_root.mkdir(parents=True, exist_ok=False)
        manifests = {name: engine_manifest(path.resolve()) for name, path in
                     (("baseline", args.baseline_engine), ("candidate", args.candidate_engine))}
        (args.output_root / "engine-manifests.json").write_text(
            json.dumps(manifests, indent=2, sort_keys=True) + "\n")
    all_results: dict[str, Any] = {}
    for workload in workloads:
        pairs: list[dict[str, Any]] = [{"pair": pair} for pair in range(args.repetitions)]
        for pair, variant in paired_order(args.repetitions):
            engine = args.baseline_engine if variant == "baseline" else args.candidate_engine
            run_id = f"{workload}-pair{pair}-{variant}"
            run_dir = args.output_root / "runs" / run_id
            evidence = args.output_root / "monitor" / run_id
            if not args.reprocess_only:
                command = runner_command(args.repo.resolve(), engine.resolve(),
                                         args.dependencies.resolve(), args.output_root / "runs",
                                         args.stage_root.resolve() / run_id, run_id, workload,
                                         pair in args.jfr_pair)
                returncode = monitor_run(command, run_dir, evidence)
                if returncode != 0:
                    raise ValidationFailure(f"{run_id} failed with {returncode}")
            pairs[pair][variant] = summarize_completed_run(
                run_dir, evidence, workload, pair in args.jfr_pair)
        for item in pairs:
            item["comparison"] = compare_pair(
                item["baseline"]["proof"], item["candidate"]["proof"],
                item["baseline"]["metrics"]["candidateLegality"],
                item["candidate"]["metrics"]["candidateLegality"],
                workload == "weighted_quaternary_protected_row")
            item["numericOutputComparison"] = compare_numeric_outputs(
                pathlib.Path(item["baseline"]["run"]), pathlib.Path(item["candidate"]["run"]),
                workload)
            if item["numericOutputComparison"]["status"] != "PASSED":
                item["comparison"]["status"] = "FAILED"
                item["comparison"]["failures"].append("numericOutputs")
            for variant in ("baseline", "candidate"):
                item[variant].pop("proof")
        all_results[workload] = {"pairs": pairs, "ratios": paired_ratios(pairs)}
    passed = all(item["comparison"]["status"] == "PASSED"
                 for workload in all_results.values() for item in workload["pairs"])
    output = {"schema": "fed-oracle-native-paired-runtime-v1",
              "status": "PASSED" if passed else "FAILED", "repetitions": args.repetitions,
              "order": paired_order(args.repetitions), "workloads": all_results,
              "fixedRuntime": {"seed": 7, "cpus": 4, "containerMemoryBytes": 8 * 1024**3,
                               "coordinatorHeap": "3g", "workerHeap": "1g"}}
    (args.output_root / "paired-summary.json").write_text(
        json.dumps(output, indent=2, sort_keys=True) + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
