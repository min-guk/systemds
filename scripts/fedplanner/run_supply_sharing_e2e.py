#!/usr/bin/env python3
"""Automatic DML supply-sharing validation in a fresh, frozen Docker run."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid


PROBE = "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.AutomaticSupplySharingDockerProbe"
PLANNERS = {"local": "compile_cost_based", "global": "compile_exact"}
MARKER = re.compile(r"^JOINT_E2E_(SUM|NORM2|ROWS|COLS)=([-+0-9.eE]+)$", re.MULTILINE)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_matrix(path: Path, rows: int, cols: int, offset: int, privacy: str = "public") -> None:
    # Keep input generation bounded in memory even for the residency sweep.
    with path.open("w", encoding="utf-8") as out:
        for row in range(rows):
            out.write(",".join(str(((row % 7 + col % 5 + offset) % 11 + 1) / 16)
                               for col in range(cols)) + "\n")
    metadata = {"data_type": "matrix", "value_type": "double", "rows": rows,
                "cols": cols, "nnz": rows * cols, "format": "csv", "header": False}
    if privacy != "public":
        metadata["privacy"] = privacy
    write_json(Path(str(path) + ".mtd"), metadata)


def read_matrix(name: str, rows: int, cols: int, port: int, federated: bool,
                private: bool = False) -> str:
    source = name if federated or not private else name + "_PUBLIC"
    path = f"/evidence/data/{source}.csv"
    if federated:
        return (f'{name}=federated(addresses=list("localhost:{port}/{path}"),'
                f'ranges=list(list(0,0),list({rows},{cols})));\n')
    return f'{name}=read("{path}",format="csv");\n'


def program(spec: dict, kind: str, federated: bool) -> str:
    rows, inner, width = spec["rows"], spec["inner"], spec["width"]
    if kind == "flat":
        lines = [read_matrix("UA", inner, width, 13001, federated, True),
                 read_matrix("UB", inner, width, 13002, federated, True)]
        lines += [read_matrix(f"S{copy}", inner, width, 13001, federated)
                  for copy in range(spec["copies"])]
        lines += ["total=0;energy=0;\n", f'for(i in 1:{spec["iterations"]}) {{\n']
        for copy in range(spec["copies"]):
            lines += [f"  QA{copy}=UA+S{copy};QB{copy}=UB+S{copy};QC{copy}=UB*S{copy};\n",
                      f"  QM{copy}=QB{copy}/QC{copy};\n",
                      f"  total=total+sum(QA{copy})+sum(QM{copy});\n",
                      f"  energy=energy+sum(QA{copy}*QA{copy})+sum(QM{copy}*QM{copy});\n",
                      f"  S{copy}=S{copy}+i;\n"]
        return "".join(lines + ["}\n", 'print("JOINT_E2E_SUM="+total);\n',
                       'print("JOINT_E2E_NORM2="+energy);\n',
                       f'print("JOINT_E2E_ROWS="+{inner});\n', f'print("JOINT_E2E_COLS="+{width});\n'])
    lines = [read_matrix("LA", rows, inner, 13001, federated, True),
             read_matrix("LB", rows, inner, 13002, federated, True)]
    prefix = "B" if kind == "updated" else "S"
    lines += [read_matrix(f"{prefix}{copy}", inner, width, 13001, federated)
              for copy in range(spec["copies"])]
    if kind == "phi":
        lines += [f"C{copy}=S{copy};\n" for copy in range(spec["copies"])]
    lines += ["total=0;energy=0;\n", f'for(i in 1:{spec["iterations"]}) {{\n']
    if kind in ("updated", "phi"):
        # Each outer iteration produces a fresh value from an immutable base.
        # Repeated inner uses make sharing worthwhile; the next outer value
        # must get a different physical copy. Protected operands also change
        # inside the inner loop, preventing loop-invariant expression hoisting.
        if kind == "updated":
            lines += [f"  S{copy}=B{copy}+i;\n" for copy in range(spec["copies"])]
        else:
            lines += [f"  S{copy}=C{copy};\n" for copy in range(spec["copies"])]
        lines += [f'  for(j in 1:{spec["usesPerVersion"]}) {{\n', "  LA=LA+j;LB=LB+j;\n"]
    else:
        lines.append("  LA=LA+i;LB=LB+i;\n")
    for copy in range(spec["copies"]):
        lines.append(f"  QA{copy}=LA%*%S{copy};QB{copy}=LB%*%S{copy};\n")
        lines.append(f"  total=total+sum(QA{copy})+sum(QB{copy});\n")
        lines.append(f"  energy=energy+sum(QA{copy}*QA{copy})+sum(QB{copy}*QB{copy});\n")
    if kind in ("updated", "phi"):
        lines.append("  }\n")
    if kind == "phi":
        # The next loop-head epoch receives either entry S or this backedge S.
        # The final update is not consumed; charge N epochs, not N+1 definitions.
        lines += [f"  C{copy}=C{copy}+i;\n" for copy in range(spec["copies"])]
    lines += ["}\n", 'print("JOINT_E2E_SUM="+total);\n',
              'print("JOINT_E2E_NORM2="+energy);\n',
              f'print("JOINT_E2E_ROWS="+{rows});\n', f'print("JOINT_E2E_COLS="+{width});\n']
    return "".join(lines)


def prepare_inputs(stage: Path, spec: dict) -> None:
    (stage / "data").mkdir()
    (stage / "cases").mkdir()
    protected = [("LA", 1, spec["rows"], spec["inner"]), ("LB", 3, spec["rows"], spec["inner"])]
    if "flat" in spec["kinds"]:
        protected += [("UA", 1, spec["inner"], spec["width"]), ("UB", 3, spec["inner"], spec["width"])]
    for name, offset, rows, cols in protected:
        write_matrix(stage / "data" / f"{name}.csv", rows, cols, offset, "private-aggregate")
        shutil.copyfile(stage / "data" / f"{name}.csv", stage / "data" / f"{name}_PUBLIC.csv")
        metadata = json.loads((stage / "data" / f"{name}.csv.mtd").read_text())
        metadata.pop("privacy")
        write_json(stage / "data" / f"{name}_PUBLIC.csv.mtd", metadata)
    for copy in range(spec["copies"]):
        write_matrix(stage / "data" / f"S{copy}.csv", spec["inner"], spec["width"], copy + 5)
        if "updated" in spec["kinds"]:
            shutil.copyfile(stage / "data" / f"S{copy}.csv", stage / "data" / f"B{copy}.csv")
            shutil.copyfile(stage / "data" / f"S{copy}.csv.mtd", stage / "data" / f"B{copy}.csv.mtd")
    for kind in spec["kinds"]:
        for mode in ("cp", *spec["planners"]):
            case = stage / "cases" / f"{kind}-{mode}"
            case.mkdir()
            (case / "program.dml").write_text(program(spec, kind, mode != "cp"), encoding="utf-8")
            planner = "none" if mode == "cp" else PLANNERS[mode]
            config = (
                "<root><sysds.native.blas>none</sysds.native.blas>"
                "<sysds.local.spark>true</sysds.local.spark>"
                f"<sysds.federated.planner>{planner}</sysds.federated.planner>"
                "<sysds.codegen.enabled>false</sysds.codegen.enabled>"
                f"<sysds.localtmpdir>/evidence/cases/{case.name}/tmp</sysds.localtmpdir>"
                f"<sysds.scratch>/evidence/cases/{case.name}/scratch</sysds.scratch></root>\n")
            (case / "config.xml").write_text(config, encoding="utf-8")
            # Worker cache experiments must not alter the coordinator's planning budget.
            worker_config = config.replace("</root>",
                "<sysds.caching.memorymanager>static</sysds.caching.memorymanager>"
                f'<sysds.caching.bufferpoollimit>{spec.get("workerBufferPercent", 15)}</sysds.caching.bufferpoollimit>'
                "</root>")
            for port in (13001, 13002):
                # PID/host based UUIDs can fall back to the same value in Docker.
                # Give each process its own root even if that fallback happens.
                isolated = worker_config.replace(f"/{case.name}/tmp", f"/{case.name}/worker-{port}/tmp")
                isolated = isolated.replace(f"/{case.name}/scratch", f"/{case.name}/worker-{port}/scratch")
                (case / f"worker-{port}-config.xml").write_text(isolated, encoding="utf-8")


def process_sample(pid: int) -> dict:
    result: dict = {"pid": pid}
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            key, _, value = line.partition(":")
            if key in ("VmRSS", "VmHWM", "VmSwap", "Threads"):
                result[key] = int(value.split()[0]) * (1024 if "kB" in value else 1)
        result["io"] = {key: int(value) for key, value in (
            line.split(":", 1) for line in Path(f"/proc/{pid}/io").read_text().splitlines())}
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        result["unavailable"] = True
    return result


def cgroup_sample() -> dict:
    result = {}
    for name in ("memory.current", "memory.peak", "memory.max", "memory.events"):
        path = Path("/sys/fs/cgroup") / name
        if path.is_file():
            result[name] = path.read_text().strip()
    return result


def java_prefix(heap: str, gc_log: Path) -> list[str]:
    return ["java", "--add-modules", "jdk.incubator.vector", "-Xms64m", f"-Xmx{heap}",
            "-XX:ActiveProcessorCount=2", f"-Xlog:gc*:file={gc_log}:time,uptime,level,tags"]


def run_inside(stage: Path) -> int:
    spec = json.loads((stage / "spec.json").read_text())
    cp = "/engine/classes:/engine/test-classes:/deps/*"
    preflight = json.loads((stage / "class-preflight-expected.json").read_text())
    matched = all(Path(path).is_file() and hashlib.sha256(Path(path).read_bytes()).hexdigest() == item["sha256"]
                  for path, item in preflight.items())
    write_json(stage / "class-preflight-actual.json", {"passed": bool(preflight) and matched})
    if not preflight or not matched:
        return 1
    for kind in spec["kinds"]:
        for mode in ("cp", *spec["planners"]):
            case = stage / "cases" / f"{kind}-{mode}"
            workers: list[tuple[str, subprocess.Popen]] = []
            handles = []
            child = None
            started = time.monotonic()
            try:
                if mode != "cp":
                    for port in (13001, 13002):
                        log = (case / f"worker-{port}.log").open("w")
                        handles.append(log)
                        argv = java_prefix(spec["workerHeap"], case / f"worker-{port}.gc.log")
                        if spec.get("workerSoftRefMsPerMb") is not None:
                            argv.append(f'-XX:SoftRefLRUPolicyMSPerMB={spec["workerSoftRefMsPerMb"]}')
                        argv += ["-cp", cp]
                        if spec.get("workerCache", "disabled") == "static":
                            argv += [PROBE, "--worker-cache", str(case / f"worker-{port}-config.xml"), str(port)]
                        else:
                            argv += ["org.apache.sysds.api.DMLScript", "-w", str(port),
                                     "-config", str(case / f"worker-{port}-config.xml"), "-stats", "100"]
                        write_json(case / f"worker-{port}-command.json", argv)
                        workers.append((f"worker-{port}", subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT)))
                    for name, process in workers:
                        port = int(name.rsplit("-", 1)[1])
                        for _ in range(120):
                            if process.poll() is not None:
                                raise RuntimeError(f"{name} exited during startup")
                            try:
                                with socket.create_connection(("localhost", port), 0.5):
                                    break
                            except OSError:
                                time.sleep(0.25)
                        else:
                            raise RuntimeError(f"{name} did not start")
                audit = case / "audit"
                audit.mkdir()
                argv = java_prefix(spec["coordinatorHeap"], case / "coordinator.gc.log") + [
                    "-Dsysds.fedplanner.runtime.audit=true", "-Dsysds.fed.refed.reuse.audit=true",
                    "-Dsysds.fedplanner.phaseMarkers=true", "-Dsysds.fedplanner.space.audit=true",
                    f"-Dsysds.fedplanner.space.audit.dir={audit}",
                    f'-Dsysds.fed.supply.flat.diagnostic={str(kind == "flat").lower()}',
                    "-cp", cp]
                if mode == "cp":
                    argv += ["org.apache.sysds.api.DMLScript", "-f", str(case / "program.dml"),
                             "-config", str(case / "config.xml"), "-exec", "singlenode", "-seed", "7", "-stats", "100"]
                else:
                    argv += [PROBE, str(case / "program.dml"), str(case / "config.xml"), str(case / "probe.json")]
                write_json(case / "command.json", argv)
                with (case / "coordinator.log").open("w") as log, (case / "memory.jsonl").open("w") as memory:
                    child = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT)
                    while True:
                        row = {"monotonicSeconds": time.monotonic(), "elapsedSeconds": time.monotonic() - started,
                               "processes": {name: process_sample(process.pid) for name, process in workers},
                               "cgroup": cgroup_sample()}
                        row["processes"]["coordinator"] = process_sample(child.pid)
                        memory.write(json.dumps(row, sort_keys=True) + "\n")
                        memory.flush()
                        code = child.poll()
                        if code is not None:
                            break
                        if time.monotonic() - started > spec["caseTimeoutSeconds"]:
                            raise TimeoutError(f"{case.name} exceeded its isolated case timeout")
                        time.sleep(0.1)
                write_json(case / "exit.json", {"returncode": code, "elapsedSeconds": time.monotonic() - started,
                                               "cgroup": cgroup_sample()})
                if mode != "cp":
                    # A fresh observer has no active DML plan. Its scalar-only UDF is
                    # separate from the measured program and never relaxes runtime audit.
                    observe = java_prefix("256m", case / "observer.gc.log") + [
                        "-Dsysds.fedplanner.runtime.audit=true", "-cp", cp, PROBE,
                        "--observe-workers", str(case / "worker-observations.json")]
                    write_json(case / "observer-command.json", observe)
                    try:
                        with (case / "observer.log").open("w") as observe_log:
                            observation = subprocess.run(observe, stdout=observe_log, stderr=subprocess.STDOUT,
                                                         timeout=90, check=False)
                        observer_result = {"returncode": observation.returncode}
                    except Exception as exc:
                        observer_result = {"returncode": -1, "error": str(exc)}
                    write_json(case / "observer-exit.json", {
                        **observer_result, "phase": "after DML coordinator exit"})
            except Exception as exc:
                write_json(case / "exit.json", {"returncode": -1, "error": str(exc)})
            finally:
                owned = ([] if child is None else [("coordinator", child)]) + workers
                for _, process in owned:
                    if process.poll() is None:
                        process.terminate()
                for _, process in owned:
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                for handle in handles:
                    handle.close()
    return 0


def numeric_markers(path: Path) -> dict[str, float]:
    if not path.is_file():
        return {}
    return {key: float(value) for key, value in MARKER.findall(path.read_text(errors="replace"))}


def transport_gate(log: str, probe: dict, sources: set[str], iterations: int, creations: int,
                   allow_native: bool = False) -> dict:
    """Join emitted action -> executed instruction -> actual GET/MatrixBlock PUT.

    Runtime audit prints cumulative counters, so repeated snapshots are deduplicated
    with max, never summed. Input initialization and scalar result GETs are excluded.
    The fixture has one source worker and one target worker per selected relocation.
    """
    rows = []
    for line in log.splitlines():
        match = re.search(r"\[PlannerRuntimeAudit\]\[([^]]+)\] (.*)", line)
        if match:
            fields = dict(re.findall(r"(\w+)=([^\s]+)", match[2]))
            if fields.get("status") == "MATCH":
                rows.append((match[1], fields))
    checks = []
    seen = set()
    for action in probe.get("selectedRelocations", []):
        token = action.get("actionKeyDigest")
        if action.get("sourceLexicalVariable") not in sources or not token or token in seen:
            continue
        staged = action.get("expectedStaged", True)
        if not allow_native and not any(event.get("staged") for event in action.get("runtimeSupplyEvents", [])):
            continue
        seen.add(token)
        keys = {(row.get("plan"), row.get("auditKey")) for kind, row in rows
                if kind == "Lowering-Synthetic" and row.get("token") == token
                and row.get("stage") == ("REFED_STAGED" if staged else "REFED")}
        executions, requests = {}, {}
        for kind, row in rows:
            if kind == "Execution" and (row.get("plan"), row.get("auditKey")) in keys:
                key = (row.get("plan"), row.get("auditKey"))
                executions[key] = max(executions.get(key, 0), int(row.get("count", 0)))
            elif kind == "Federated-Dispatch" and (row.get("plan"), row.get("parentAuditKey")) in keys:
                key = (row.get("plan"), row.get("parentAuditKey"), row.get("requestType"),
                       row.get("fragmentOpcode"))
                requests[key] = max(requests.get(key, 0), int(row.get("count", 0)))
        gets = sum(count for key, count in requests.items() if key[2] == "GET_VAR")
        puts = sum(count for key, count in requests.items() if key[2:] == ("PUT_VAR", "MatrixBlock"))
        execution_count = sum(executions.values())
        observed_creations = sum(bool(event.get("created")) for event in action.get("runtimeSupplyEvents", [])
                                 if event.get("staged") is staged)
        checks.append({"source": action["sourceLexicalVariable"], "actionKeyDigest": token,
                       "instructionExecutions": execution_count, "sourceGets": gets, "targetMatrixPuts": puts,
                       "observedCreations": observed_creations, "staged": staged,
                       "passed": execution_count == iterations and observed_creations == creations
                       and puts == observed_creations and gets == (observed_creations if staged else 0)})
    expected_actions = (len({action.get("actionKeyDigest") for action in probe.get("selectedRelocations", [])
                            if action.get("sourceLexicalVariable") in sources}) if allow_native else len(sources))
    return {"actions": checks, "passed": len(checks) == expected_actions and expected_actions >= len(sources)
            and {row["source"] for row in checks} == sources and all(row["passed"] for row in checks)}


def sharing_gate(probe: dict, kind: str, copies: int, iterations: int,
                 uses_per_version: int = 1) -> dict:
    """Reject successful DML that never selected/executed the sharing under test."""
    audit = probe.get("refedReuseAudit", {})
    expected_sources = {f"S{index}" for index in range(copies)}
    selected_actions = {action["physicalEmissionIdentity"] for action in probe.get("selectedRelocations", [])
                        if action.get("sourceLexicalVariable") in expected_sources}
    allow_native = kind in ("phi", "flat")
    expected_actions = len(selected_actions) if allow_native else copies
    by_identity = {}
    for action in probe.get("selectedRelocations", []):
        if action.get("sourceLexicalVariable") not in expected_sources:
            continue
        events = [event for event in action.get("runtimeSupplyEvents", []) if allow_native or event.get("staged")]
        if events:
            by_identity[action["physicalEmissionIdentity"]] = {"action": action, "events": events}
    checks = []
    seen_supply_tuples = set()
    seen_canonical_ids = set()
    for identity, value in sorted(by_identity.items()):
        action, events = value["action"], value["events"]
        creation_count = sum(bool(event.get("created")) for event in events)
        versions = {(event.get("sourceUniqueId"), event.get("sourceVersion")) for event in events}
        tuples = {(event.get("sourceUniqueId"), event.get("sourceVersion"),
                   event.get("groupDigest"), event.get("layoutDigest")) for event in events}
        expected_creations = 1 if kind == "invariant" else iterations
        expected_executions = iterations if kind == "invariant" else iterations * uses_per_version
        shared = (kind == "invariant" and iterations > 1) or (kind != "invariant" and uses_per_version > 1)
        lifecycle = []
        for supply_tuple in tuples:
            lifecycle += [event for event in audit.get("events", [])
                          if (event.get("sourceUniqueId"), event.get("sourceVersion"),
                              event.get("groupDigest"), event.get("layoutDigest")) == supply_tuple]
        names = ("CREATION_ATTEMPT", "CREATION_SUCCESS", "RETAINED", "HIT", "ALIAS",
                 "RETIREMENT", "CLEANUP")
        counts = {name: sum(event.get("event") == name for event in lifecycle) for name in names}
        expected_type = action.get("materializationFType")
        lifecycle_modes = {event.get("mode") for event in lifecycle}
        lifecycle_types = {event.get("outType") for event in lifecycle}
        canonical_ids = {event.get("canonicalRemoteId") for event in lifecycle
                         if event.get("event") != "CREATION_ATTEMPT"
                         and isinstance(event.get("canonicalRemoteId"), int)
                         and event.get("canonicalRemoteId") >= 0}
        published_ids = {event.get("publishedMapId") for event in events}
        disjoint_source = seen_supply_tuples.isdisjoint(tuples)
        seen_supply_tuples.update(tuples)
        base = (disjoint_source and len(events) == expected_executions and creation_count == expected_creations
                and action.get("sharedAcrossExecutions") == shared
                and (not shared or bool(action.get("expectedSharingGroupDigest")))
                and all(event.get("groupDigest") == action.get("expectedSharingGroupDigest") for event in events)
                and all(event.get("staged") is (action.get("expectedStaged") if allow_native else True)
                        for event in events)
                and (len(versions) == 1 if kind == "invariant" else len(versions) == iterations)
                and len(published_ids) == expected_executions)
        if kind == "invariant":
            retirement_times = [event.get("nanoTime", -1) for event in lifecycle
                                if event.get("event") == "RETIREMENT"]
            creation_times = [event.get("nanoTime", -1) for event in lifecycle
                              if event.get("event") == "CREATION_SUCCESS"]
            lifecycle_ok = (len(tuples) == 1 and lifecycle_modes == {"PLANNED"}
                            and lifecycle_types == {expected_type} and len(canonical_ids) == 1
                            and counts == {"CREATION_ATTEMPT": 1, "CREATION_SUCCESS": 1,
                                           "RETAINED": 1, "HIT": iterations - 1, "ALIAS": iterations,
                                           "RETIREMENT": 1, "CLEANUP": 1}
                            and not any(event.get("cleanupSuccess") is False for event in lifecycle)
                            and creation_times and creation_times[0] < min(event.get("nanoTime", -1) for event in events)
                            and retirement_times and retirement_times[0] > max(event.get("nanoTime", -1) for event in events)
                            and canonical_ids.isdisjoint(published_ids))
        elif uses_per_version == 1:
            lifecycle_ok = len(tuples) == iterations and lifecycle_modes == {"SINGLE_USE"}
            for supply in events:
                joined = [event for event in lifecycle
                          if (event.get("sourceUniqueId"), event.get("sourceVersion"),
                              event.get("groupDigest"), event.get("layoutDigest"))
                          == (supply.get("sourceUniqueId"), supply.get("sourceVersion"),
                              supply.get("groupDigest"), supply.get("layoutDigest"))]
                joined_counts = {name: sum(event.get("event") == name for event in joined) for name in names}
                successes = [event for event in joined if event.get("event") == "CREATION_SUCCESS"]
                lifecycle_ok &= (joined_counts == {"CREATION_ATTEMPT": 1, "CREATION_SUCCESS": 1,
                                                   "RETAINED": 0, "HIT": 0, "ALIAS": 0,
                                                   "RETIREMENT": 0, "CLEANUP": 0}
                                 and {event.get("outType") for event in joined} == {expected_type}
                                 and len(successes) == 1
                                 and successes[0].get("canonicalRemoteId") == supply.get("publishedMapId")
                                 and successes[0].get("nanoTime", -1) < supply.get("nanoTime", -1))
        else:
            lifecycle_ok = len(tuples) == iterations and lifecycle_modes == {"PLANNED"}
            version_canonical_ids = set()
            for supply_tuple in tuples:
                version_supplies = sorted((event for event in events
                                           if (event.get("sourceUniqueId"), event.get("sourceVersion"),
                                               event.get("groupDigest"), event.get("layoutDigest")) == supply_tuple),
                                          key=lambda event: event.get("nanoTime", -1))
                joined = [event for event in lifecycle
                          if (event.get("sourceUniqueId"), event.get("sourceVersion"),
                              event.get("groupDigest"), event.get("layoutDigest")) == supply_tuple]
                joined_counts = {name: sum(event.get("event") == name for event in joined) for name in names}
                joined_canonical_ids = {event.get("canonicalRemoteId") for event in joined
                                        if event.get("event") != "CREATION_ATTEMPT"
                                        and isinstance(event.get("canonicalRemoteId"), int)
                                        and event.get("canonicalRemoteId") >= 0}
                retirements = [event.get("nanoTime", -1) for event in joined
                               if event.get("event") == "RETIREMENT"]
                successes = [event.get("nanoTime", -1) for event in joined
                             if event.get("event") == "CREATION_SUCCESS"]
                version_published_ids = {event.get("publishedMapId") for event in version_supplies}
                lifecycle_ok &= (len(version_supplies) == uses_per_version
                                 and [bool(event.get("created")) for event in version_supplies]
                                 == [True] + [False] * (uses_per_version - 1)
                                 and joined_counts == {"CREATION_ATTEMPT": 1, "CREATION_SUCCESS": 1,
                                                      "RETAINED": 1, "HIT": uses_per_version - 1,
                                                      "ALIAS": uses_per_version, "RETIREMENT": 1, "CLEANUP": 1}
                                 and {event.get("mode") for event in joined} == {"PLANNED"}
                                 and {event.get("outType") for event in joined} == {expected_type}
                                 and len(joined_canonical_ids) == 1
                                 and len(version_published_ids) == uses_per_version
                                 and joined_canonical_ids.isdisjoint(version_published_ids)
                                 and not any(event.get("cleanupSuccess") is False for event in joined)
                                 and successes and successes[0] < version_supplies[0].get("nanoTime", -1)
                                 and retirements and retirements[0] > version_supplies[-1].get("nanoTime", -1))
                version_canonical_ids.update(joined_canonical_ids)
            lifecycle_ok &= len(version_canonical_ids) == iterations
        passed = base and lifecycle_ok and seen_canonical_ids.isdisjoint(canonical_ids)
        seen_canonical_ids.update(canonical_ids)
        checks.append({"physicalEmissionIdentity": identity, "source": action.get("sourceLexicalVariable"),
                       "executions": len(events), "creations": creation_count, "distinctRuntimeVersions": len(versions),
                       "lifecycleCounts": counts, "canonicalIds": sorted(canonical_ids),
                       "shared": action.get("sharedAcrossExecutions"), "staged": action.get("expectedStaged", True),
                       "passed": passed})
    proof = probe.get("canonicalProof", {})
    return {"actions": checks, "expectedActionCount": expected_actions, "expectedSources": sorted(expected_sources),
            "passed": len(checks) == expected_actions and expected_actions >= copies
            and all(check["passed"] for check in checks)
            and {check["source"] for check in checks} == expected_sources
            and audit.get("enabled") is True and audit.get("droppedEvents") == 0
            and audit.get("loggingFailures") == 0
            and probe.get("runtimeFallbackCount") == 0 and probe.get("runtimeRepairCount") == 0
            and proof.get("objectiveMatches") is True and proof.get("sharedLifetimesMatch") is True}


def cleanup_gate(probe: dict, log: str = "") -> dict:
    audit = probe.get("refedReuseAudit", {})
    events = audit.get("events", [])
    failures = [event for event in events if event.get("cleanupSuccess") is False]
    cleanups = [event for event in events if event.get("event") == "CLEANUP"]
    clear_failed = "Failed to execute CLEAR request on existing federated sites" in log
    confirmed = [event for event in cleanups if event.get("cleanupSuccess") is True
                 and event.get("reason") == "WORKER_RESET"]
    dispatched = [event for event in cleanups if event.get("cleanupSuccess") is True
                  and event.get("reason") != "WORKER_RESET"]
    logical_only = [event for event in cleanups if event.get("cleanupSuccess") is None]
    return {"currentCanonicalCount": audit.get("currentCanonicalCount"),
            "currentEstimatedBytes": audit.get("currentEstimatedBytes"),
            "cleanupFailures": len(failures),
            "confirmedRemoteCleanupCount": len(confirmed),
            "asynchronousCleanupDispatchCount": len(dispatched),
            "logicalRetirementOnly": bool(logical_only),
            "remoteCleanupConfirmed": bool(cleanups) and len(confirmed) == len(cleanups) and not failures,
            "workerClearFailureDetected": clear_failed,
            "retirementReasons": sorted({event["reason"] for event in events
                                         if event.get("event") == "RETIREMENT"}),
            "passed": audit.get("currentCanonicalCount") == 0
            and audit.get("currentEstimatedBytes") == 0 and not failures and not clear_failed}


def worker_cache_gate(observation: dict, spec: dict) -> dict:
    workers = observation.get("workerObservations", [])
    enabled = spec.get("workerCache", "disabled") == "static"
    ports = {str(worker.get("address", "")).rsplit(":", 1)[-1] for worker in workers}
    active = len(workers) == 2 and ports == {"13001", "13002"} and all(
        worker.get("cachingActive") is enabled for worker in workers)
    manager = not enabled or all(worker.get("cacheManager") == "STATIC"
                                 and worker.get("unifiedMemoryManagerEnabled") is False for worker in workers)
    isolated = not enabled or len(workers) == 2 and all(
        f'/worker-{str(worker.get("address", "")).rsplit(":", 1)[-1]}/' in str(worker.get("cachePath", ""))
        for worker in workers) and len({worker.get("cachePath") for worker in workers}) == 2
    target = [worker for worker in workers if str(worker.get("address", "")).endswith(":13002")]
    spill = len(target) == 1 and target[0].get("fsWrites", 0) > 0 and target[0].get("fsHits", 0) > 0
    required = spec.get("requireWorkerSpill", False)
    return {"expectedCachingActive": enabled, "cachingStateMatches": active, "cacheManagerMatches": manager,
            "cachePathsIsolated": isolated,
            "requireTargetSpillRestore": required, "targetSpillRestoreObserved": spill,
            "scope": "worker aggregate cache counters; not per-canonical attribution",
            "passed": active and manager and isolated and (not required or enabled and spill)}


def flat_plan_gate(probe: dict, planner: str, iterations: int) -> dict:
    diagnostic = probe.get("flatUpdatedDiagnostic", {})
    runtime = sharing_gate(probe, "flat", 1, iterations, 1)
    selected_identity = diagnostic.get("selectedCommonPhysicalEmissionIdentity")
    runtime_identities = {action["physicalEmissionIdentity"] for action in runtime["actions"]}
    identity_matches = bool(selected_identity) and runtime_identities == {selected_identity}
    # Local is a neighborhood optimizer; canonical agreement and actual shared
    # movement are required, while its gap to the exact diagnostic stays visible.
    return {"mode": "complete-assignment-and-runtime-sharing", "diagnostic": diagnostic,
            "runtime": runtime, "requiresExactOptimum": planner == "global",
            "selectedSharedIdentityExecuted": identity_matches,
            "passed": diagnostic.get("status") == "checked"
            and diagnostic.get("selectedHardCost") == 0
            and diagnostic.get("bestForcedHardCost") == 0
            and diagnostic.get("selectedCommonRelocation") is True
            and identity_matches
            and (planner != "global" or diagnostic.get("cheaperLegalAlternativeFound") is False)
            and runtime["passed"]}


def evaluate(run: Path, spec: dict) -> dict:
    results = []
    for kind in spec["kinds"]:
        reference = numeric_markers(run / "cases" / f"{kind}-cp/coordinator.log")
        reference_exit_path = run / "cases" / f"{kind}-cp/exit.json"
        reference_exit = json.loads(reference_exit_path.read_text()) if reference_exit_path.is_file() else {}
        for mode in spec["planners"]:
            case = run / "cases" / f"{kind}-{mode}"
            actual = numeric_markers(case / "coordinator.log")
            probe = json.loads((case / "probe.json").read_text()) if (case / "probe.json").is_file() else {}
            exit_result = json.loads((case / "exit.json").read_text()) if (case / "exit.json").is_file() else {}
            numeric = (set(reference) == set(actual) == {"SUM", "NORM2", "ROWS", "COLS"}
                       and all(math.isfinite(actual[key]) and math.isclose(reference[key], actual[key], rel_tol=1e-9, abs_tol=1e-9)
                               for key in reference))
            peaks: dict[str, int] = {}
            memory = case / "memory.jsonl"
            if memory.is_file():
                for line in memory.read_text().splitlines():
                    for name, sample in json.loads(line)["processes"].items():
                        peaks[name] = max(peaks.get(name, 0), sample.get("VmRSS", 0))
            uses = spec.get("usesPerVersion", 1) if kind not in ("invariant", "flat") else 1
            sharing = sharing_gate(probe, kind, spec["copies"], spec["iterations"], uses)
            log = (case / "coordinator.log").read_text(errors="replace") if (case / "coordinator.log").is_file() else ""
            transport = transport_gate(log, probe, {f"S{i}" for i in range(spec["copies"])},
                                       spec["iterations"] * uses, 1 if kind == "invariant" else spec["iterations"],
                                       allow_native=kind in ("phi", "flat"))
            cleanup = cleanup_gate(probe, log)
            observer_path = case / "worker-observations.json"
            observation = json.loads(observer_path.read_text()) if observer_path.is_file() else {}
            observer_exit_path = case / "observer-exit.json"
            observer_exit = json.loads(observer_exit_path.read_text()) if observer_exit_path.is_file() else {}
            observed = (observer_exit.get("returncode") == 0 and observation.get("status") == "passed"
                        and len(observation.get("workerObservations", [])) == 2)
            worker_cache = worker_cache_gate(observation, spec)
            if kind == "flat":
                sharing = flat_plan_gate(probe, mode, spec["iterations"])
            results.append({"kind": kind, "planner": mode, "numericMatch": numeric, "sharing": sharing,
                            "transport": transport, "cleanup": cleanup, "workerObservationPassed": observed,
                            "workerCache": worker_cache,
                            "reference": reference, "actual": actual, "probeStatus": probe.get("status"),
                            "returncode": exit_result.get("returncode"), "sampledRssPeakBytes": peaks,
                            "passed": numeric and reference_exit.get("returncode") == 0
                            and exit_result.get("returncode") == 0 and probe.get("status") == "passed"
                            and sharing["passed"] and transport["passed"] and cleanup["passed"] and observed
                            and worker_cache["passed"]})
    preflight_path = run / "class-preflight-actual.json"
    preflight = json.loads(preflight_path.read_text()) if preflight_path.is_file() else {}
    docker_exit_path = run / "docker-exit.json"
    docker_exit = json.loads(docker_exit_path.read_text()) if docker_exit_path.is_file() else {}
    return {"schema": "automatic-supply-sharing-e2e-v1", "cases": results,
            "classPreflightPassed": preflight.get("passed") is True,
            "status": "PASSED" if results and all(row["passed"] for row in results)
            and preflight.get("passed") is True and docker_exit.get("returncode") == 0 else "FAILED"}


def main() -> int:
    import run_joint_boundary_e2e as common
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=Path("/grid/3/cofee-lm-sweep-mchoi-20260914/automatic-supply-sharing-20261006"))
    parser.add_argument("--stage-root", type=Path, default=common.REPO_ROOT / "target/automatic-supply-sharing")
    parser.add_argument("--run-id")
    parser.add_argument("--kind", choices=("invariant", "updated", "phi", "flat"), action="append")
    parser.add_argument("--planner", choices=tuple(PLANNERS), action="append")
    for name, default in (("rows", 16), ("inner", 16), ("width", 8192), ("copies", 1),
                          ("iterations", 3), ("uses-per-version", 3), ("case-timeout-seconds", 600)):
        parser.add_argument("--" + name, type=int, default=default)
    parser.add_argument("--worker-heap", default="1g")
    parser.add_argument("--worker-cache", choices=("disabled", "static"), default="disabled",
                        help="static initializes the existing cache in the test-only worker launcher")
    parser.add_argument("--worker-buffer-percent", type=int, default=15)
    parser.add_argument("--worker-soft-ref-ms-per-mb", type=int)
    parser.add_argument("--require-worker-spill", action="store_true",
                        help="require real filesystem writes AND restores on the target worker")
    parser.add_argument("--coordinator-heap", default="3g")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if min(args.rows, args.inner, args.width, args.copies, args.iterations, args.uses_per_version) <= 0:
        parser.error("matrix dimensions, copies, and iterations must be positive")
    if not 30 <= args.case_timeout_seconds <= 1800:
        parser.error("case timeout must be between 30 and 1800 seconds")
    if not 1 <= args.worker_buffer_percent <= 99:
        parser.error("worker buffer percent must be an integer between 1 and 99")
    if args.worker_soft_ref_ms_per_mb is not None and args.worker_soft_ref_ms_per_mb < 0:
        parser.error("worker soft reference policy must be nonnegative")
    if args.require_worker_spill and args.worker_cache != "static":
        parser.error("requiring worker spill needs --worker-cache static")
    if args.kind and "flat" in args.kind and args.copies != 1:
        parser.error("flat assignment diagnostics currently require one source copy")
    for heap in (args.worker_heap, args.coordinator_heap):
        if not re.fullmatch(r"[1-9][0-9]*[mg]", heap):
            parser.error("heap must be a positive JVM size in m or g")
    spec = {"rows": args.rows, "inner": args.inner, "width": args.width, "copies": args.copies,
            "iterations": args.iterations, "usesPerVersion": args.uses_per_version,
            "kinds": list(dict.fromkeys(args.kind or ["invariant", "updated", "phi"])),
            "planners": list(dict.fromkeys(args.planner or ["local", "global"])),
            "workerHeap": args.worker_heap, "coordinatorHeap": args.coordinator_heap,
            "workerCache": args.worker_cache, "workerBufferPercent": args.worker_buffer_percent,
            "workerSoftRefMsPerMb": args.worker_soft_ref_ms_per_mb,
            "requireWorkerSpill": args.require_worker_spill,
            "caseTimeoutSeconds": args.case_timeout_seconds}
    run = common.allocate_run(common.assert_grid_root(args.output_root), args.run_id)
    stage = common.allocate_stage(args.stage_root, run)
    frozen = stage / "frozen-inputs"
    frozen.mkdir()
    inventories = {}
    for name, source in (("main-classes", common.DEFAULT_CLASSES), ("test-classes", common.DEFAULT_TEST_CLASSES),
                         ("main-sources", common.DEFAULT_MAIN_SOURCES), ("test-sources", common.DEFAULT_TEST_SOURCES),
                         ("dependencies", common.DEFAULT_DEPENDENCIES)):
        inventories[name] = common.freeze_tree(source, frozen / name)
    preflight = common.class_preflight_expectations(frozen / "main-classes", frozen / "test-classes", PROBE)
    write_json(stage / "class-preflight-expected.json", preflight)
    write_json(stage / "spec.json", spec)
    prepare_inputs(stage, spec)
    shutil.copyfile(__file__, stage / "runner.py")
    container = "systemds-supply-sharing-" + uuid.uuid4().hex[:16]
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none", "--name", container,
               "--cpus", "4", "--memory", "8g", "--user", f"{os.getuid()}:{os.getgid()}",
               "--volume", f"{stage}:/evidence:rw", "--volume", f"{frozen / 'main-classes'}:/engine/classes:ro",
               "--volume", f"{frozen / 'test-classes'}:/engine/test-classes:ro",
               "--volume", f"{frozen / 'dependencies'}:/deps:ro",
               "--entrypoint", "python3", common.PINNED_IMAGE, "/evidence/runner.py", "--inside"]
    manifest = {"schema": "automatic-supply-sharing-input-v1", "baseCommit": subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], cwd=common.REPO_ROOT, text=True).strip(),
                "run": str(run), "stage": str(stage), "spec": spec, "dockerArgv": command,
                "image": common.PINNED_IMAGE, "sourceSha256": common.sha256(Path(__file__)),
                "artifactInventoryDigests": {name: common.inventory_digest(value) for name, value in inventories.items()},
                "inputHashes": common.tree_inventory(stage / "data"), "fixtureHashes": common.tree_inventory(stage / "cases")}
    write_json(run / "manifest.json", manifest)
    write_json(run / "artifact-inventories.json", inventories)
    if args.dry_run:
        write_json(stage / "result.json", {"status": "DRY_RUN"})
    else:
        with (run / "container.log").open("w") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        write_json(stage / "docker-exit.json", {"returncode": completed.returncode})
    shutil.copytree(stage, run, dirs_exist_ok=True)
    shutil.rmtree(stage)
    result = {"status": "DRY_RUN"} if args.dry_run else evaluate(run, spec)
    write_json(run / "result.json", result)
    print(json.dumps({"run": str(run), **result}, sort_keys=True))
    return 0 if result["status"] in ("PASSED", "DRY_RUN") else 1


if __name__ == "__main__":
    raise SystemExit(run_inside(Path("/evidence")) if sys.argv[1:] == ["--inside"] else main())
