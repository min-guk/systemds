#!/usr/bin/env python3
"""Run sealed P2 Memo planning with an explicit worker-metadata preparation phase.

Unlike ``run_memo_planning_matrix.py``, this lane deliberately starts the real
ordered worker prefix.  The only remotely executed workload operation before
planning is the privacy-authorized transform metadata preparation performed by
MemoRuleBuilder.  The workload RuntimeProgram remains compile-only.
"""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
from typing import Any, Callable, Iterator, Sequence


REPO = Path(__file__).resolve().parents[2]
EVALUATION = Path("/home/mchoi/cofee-evaluation")
DEFAULT_STAGE = Path("/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z")
TOPOLOGY = EVALUATION / "config/multihost_topology.json"
CAMPAIGN = EVALUATION / "campaign/run_w1357_integrated.py"
DOCKER = EVALUATION / "driver/tools/multihost_docker.py"
LIFECYCLE = REPO / "scripts/fedplanner/matrix_lifecycle.py"
MATRIX = REPO / "scripts/fedplanner/run_memo_planning_matrix.py"
MATRIX_CAMPAIGN = REPO / "scripts/fedplanner/run_matrix_campaign.py"
LANE_LOCK = Path(os.environ.get(
    "COFEE_RUNTIME_LANE", EVALUATION / "runs/.cofee-experiment-lane.lock"))
PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
ALTERNATE_CLASSIC_IMAGE_ID = "sha256:26eaea7a28a130f2c4c2fd4492b74d0e32d4a1df4f1d3e4f0b28c15b59eca8cc"
PINNED_IMAGE_CONTENT_SHA256 = "0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0"
WORKERS = (1, 3, 5, 7)
HOSTS = ("so007", "so002", "so003", "so004", "so005", "so006", "so008", "so009")
TIMEOUT_SECONDS = 300
WRAPPER_TIMEOUT_SECONDS = 335
METADATA_RECEIPT = re.compile(
    r"(?m)^TransformMetadataPreparationReceipt "
    r"schema=transform-metadata-preparation-v1 "
    r"metadataPreparationNanos=(\d+) collections=(\d+) owners=(\d+) status=SUCCESS$")

for dependency_root in (EVALUATION, EVALUATION / "campaign", EVALUATION / "driver"):
    if str(dependency_root) not in sys.path:
        sys.path.insert(0, str(dependency_root))


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


matrix = load_module("memo_metadata_base_matrix", MATRIX)
campaign = load_module("memo_metadata_campaign", CAMPAIGN)
docker = load_module("memo_metadata_multihost", DOCKER)
lifecycle = load_module("memo_metadata_lifecycle", LIFECYCLE)
matrix_campaign = load_module("memo_metadata_matrix_campaign", MATRIX_CAMPAIGN)


def atomic_json(path: Path, value: Any) -> None:
    matrix.atomic_json(path, value)


def p2_cell(workers: int) -> dict[str, Any]:
    if workers not in WORKERS:
        raise ValueError("P2 metadata planning workers must be one of 1,3,5,7")
    return matrix.cell("p2", "P2_PREP", workers)


def topology() -> dict[str, Any]:
    value = json.loads(TOPOLOGY.read_text(encoding="utf-8"))
    expected = ["so002", "so003", "so004", "so005", "so006", "so008", "so009"]
    if (value.get("schema") != "cofee-multihost-topology-v1"
            or value.get("coordinator", {}).get("host") != "so007"
            or [row.get("host") for row in value.get("workers", [])] != expected
            or [row.get("port") for row in value.get("workers", [])] != list(range(8001, 8008))
            or value.get("worker_sweep") != list(WORKERS)):
        raise ValueError("ordered W1/3/5/7 topology contract changed")
    return value


def metadata_configuration() -> str:
    # The JVM properties below are intentionally not XML configuration: they
    # are process-scoped authorization and preparation gates.
    return matrix.configuration("p2")


def adapt_receipt(receipt: dict[str, Any], source: str) -> dict[str, Any]:
    result = dict(receipt)
    result.update({
        "schema": "systemds-memo-metadata-planning-input-adaptation/v1",
        "reason": "metadata-assisted compile-only planning; worker RPC is limited to authorized metadata preparation",
        "originalSha256": matrix.sha256_bytes(source.encode()),
        "adaptedSha256": matrix.sha256_bytes(source.encode()),
        "transformations": [],
        "sealedEndpointsPreserved": True,
        "workloadBodyExecutionCount": 0,
        "metadataRpcExpected": True,
    })
    return result


def selected_stage_inventory(rendered: dict[str, Any]) -> dict[str, dict[str, dict[str, Any]]]:
    """Return the exact expected selected input file set for each remote host."""
    inventory: dict[str, dict[str, dict[str, Any]]] = {}
    for source in rendered["inputs"]:
        for shard in source["shards"]:
            host = shard["worker"]["host"]
            files = inventory.setdefault(host, {})
            files[shard["metadata_path"]] = {
                "sha256": shard["metadata_sha256"], "bytes": None}
            for block in shard["blocks"]:
                files[block["path"]] = {"sha256": block["sha256"], "bytes": block["bytes"]}
    return inventory


def remote_inventory_command(stage: Path, expected: dict[str, dict[str, Any]]) -> list[str]:
    encoded = json.dumps(expected, sort_keys=True, separators=(",", ":"))
    script = r'''import hashlib,json,os,pathlib,sys
root=pathlib.Path(sys.argv[1]); expected=json.loads(sys.argv[2]); actual={}
parents=set()
for rel,want in expected.items():
 p=root/rel
 if not p.is_file() or p.is_symlink(): raise SystemExit("missing/nonregular: "+rel)
 h=hashlib.sha256(); n=0
 with p.open("rb") as f:
  while True:
   b=f.read(1048576)
   if not b: break
   h.update(b); n+=len(b)
 if h.hexdigest()!=want["sha256"] or (want["bytes"] is not None and n!=want["bytes"]):
  raise SystemExit("identity mismatch: "+rel)
 actual[rel]={"sha256":h.hexdigest(),"bytes":n}
 if rel.count("/")>=2: parents.add(str(pathlib.Path(rel).parent))
for parent in parents:
 prefix=parent+"/"
 expected_children=sorted(k for k in expected if k.startswith(prefix) and "/" not in k[len(prefix):])
 observed_children=sorted(str(p.relative_to(root)) for p in (root/parent).iterdir() if p.is_file())
 if observed_children!=expected_children: raise SystemExit("selected shard file-set mismatch: "+parent)
print(json.dumps({"verified":True,"files":actual},sort_keys=True))'''
    return ["ssh", "-o", "BatchMode=yes", "--", "HOST",
            shlex.join(("python3", "-c", script, str(stage), encoded))]


def verify_remote_selected_stage(rendered: dict[str, Any], stage: Path,
                                 run: Callable[..., subprocess.CompletedProcess] = subprocess.run
                                 ) -> dict[str, Any]:
    receipts = {}
    for host, expected in selected_stage_inventory(rendered).items():
        argv = remote_inventory_command(stage, expected)
        argv[4] = host
        completed = run(argv, capture_output=True, text=True, check=False, timeout=300)
        if completed.returncode:
            raise RuntimeError(f"remote selected stage verification failed on {host}: {completed.stderr}")
        receipt = json.loads(completed.stdout)
        if receipt.get("verified") is not True or set(receipt.get("files", {})) != set(expected):
            raise RuntimeError(f"remote selected stage receipt incomplete on {host}")
        receipts[host] = receipt
    return {"schema": "systemds-memo-metadata-remote-stage/v1", "hosts": receipts,
            "contract": "full SHA-256 plus exact selected shard directory file sets"}


def build_lifecycle(rendered: dict[str, Any], stage: Path, engine_remote: Path,
                    attempt_remote: Path, token: str) -> dict[str, Any]:
    topo = topology()
    count = rendered["cell"]["workers"]
    chosen = topo["workers"][:count]
    if [row["host"] for row in chosen] != rendered["topology"]["workers"]:
        raise ValueError("renderer topology differs from lifecycle topology")
    java = ["java", "--add-modules", "jdk.incubator.vector", "-Xmx3g",
            "-XX:ActiveProcessorCount=4",
            "-Dsysds.fedplanner.memo.prepareTransformMetadata=true",
            "-Dsysds.privacy.allowPublicRecodeMetadata=true",
            "-cp", "/engine/classes:/deps/*",
            "org.apache.sysds.api.DMLScript"]
    value = {
        "schema": "cofee-multihost-docker/v2",
        "campaign_id": "memo-p2-meta-" + token,
        "image": PINNED_IMAGE,
        "container": {"user": f"{os.getuid()}:{os.getgid()}", "cpuset_cpus": "0-3",
                      "memory": "8g", "working_dir": "/workspace/experiments"},
        "coordinator": {"host": topo["coordinator"]["host"], "ip": topo["coordinator"]["ip"],
                        "command": ["sleep", "infinity"], "environment": {}},
        "workers": [{"index": row["index"], "host": row["host"], "ip": row["ip"],
                     "port": row["port"],
                     "command": [*java, "-w", str(row["port"]), "-config", "/evidence/execution.xml"],
                     "environment": {}} for row in chosen],
        "mounts": [
            {"source": str(stage), "target": "/workspace/experiments", "read_only": True},
            {"source": str(engine_remote / "classes"), "target": "/engine/classes", "read_only": True},
            {"source": str(engine_remote / "lib"), "target": "/deps", "read_only": True},
            {"source": str(attempt_remote), "target": "/evidence", "read_only": False},
        ],
        "network": {"rtt_ms": 1, "c2w_mbit": 5000, "w2c_mbit": 5000,
                    "queue_limit_packets": 10000},
    }
    docker.parse_manifest(value)
    return value


def coordinator_exec(spec: Any) -> list[str]:
    remote = ["docker", "exec", spec.container_name(spec.coordinator),
        "timeout", "--signal=TERM", "--kill-after=15", str(TIMEOUT_SECONDS),
        "java", "--add-modules", "jdk.incubator.vector", "-Xmx3g", "-XX:ActiveProcessorCount=4",
        "-Dsysds.fedplanner.memo.trace=true",
        "-Dsysds.fedplanner.memo.prepareTransformMetadata=true",
        "-Dsysds.privacy.allowPublicRecodeMetadata=true",
        "-cp", "/engine/classes:/deps/*", "org.apache.sysds.api.DMLScript",
        "-f", "/evidence/cell.dml", "-config", "/evidence/execution.xml",
        "-exec", "singlenode", "-seed", "1011081480", "-noFedRuntimeConversion",
        "-stats", "100", "-explain", "runtime"]
    return ["ssh", "-o", "BatchMode=yes", "--", spec.coordinator.host, shlex.join(remote)]


def parse_metadata_receipt(text: str) -> dict[str, Any]:
    values = METADATA_RECEIPT.findall(text)
    if len(values) != 1:
        return {"verified": False, "metadataPreparationSeconds": None,
                "requests": None, "sources": None}
    nanos, collections, owners = (int(item) for item in values[0])
    verified = nanos > 0 and collections > 0 and owners > 0
    return {"verified": verified, "metadataPreparationSeconds": nanos / 1e9,
            "collections": collections, "owners": owners}


def combined_process_output(stdout: str, stderr: str) -> str:
    separator = "\n" if stdout and stderr and not stdout.endswith("\n") else ""
    return stdout + separator + stderr


def validate_success(text: str, workers: int, returncode: int) -> dict[str, Any]:
    stats = matrix.statistics_module()
    plans = stats.memo_planning_statistics(text)
    searches = stats.memo_search_statistics(text)
    compile_result = matrix.compile_evidence(text)
    metadata = parse_metadata_receipt(text)
    worker_match = bool(plans) and all(row.get("workers") == workers for row in plans)
    optimal = bool(searches) and all(row.get("status") == "OPTIMAL" for row in searches)
    success = returncode == 0 and compile_result["verified"] and metadata["verified"] \
        and worker_match and optimal
    return {"verified": success, "compile": compile_result, "metadata": metadata,
            "workerCountVerified": worker_match, "optimal": optimal,
            "memoPlanning": plans, "memoSearch": searches}


def record_and_validate_success(attempt: Path, stdout: str, stderr: str,
                                workers: int, returncode: int) -> dict[str, Any]:
    evidence = combined_process_output(stdout, stderr)
    (attempt / "coordinator.log").write_text(evidence, encoding="utf-8")
    return validate_success(evidence, workers, returncode)


def foreign_use_checks(spec: Any) -> list[list[str]]:
    commands = []
    for node in (spec.coordinator, *spec.workers):
        name = spec.container_name(node)
        script = f'''set -euo pipefail
test -z "$(docker ps -a --filter {shlex.quote('name=^/'+name+'$')} --format '{{{{.ID}}}}')" || exit 71'''
        if node.port is not None:
            script += (f"\ntest -z \"$(docker ps --filter publish={node.port} "
                       "--format '{{.ID}}')\" || exit 72"
                       f"\n! ss -H -ltn 'sport = :{node.port}' | grep -q . || exit 73")
        commands.append(["ssh", "-o", "BatchMode=yes", "--", node.host,
                         shlex.join(("bash", "-lc", script))])
    return commands


def engine_sync_commands(spec: Any, engine: Path, remote: Path) -> list[list[str]]:
    manifest_path = engine.parent / (engine.name + "-sha256.json")
    result = []
    for node in (spec.coordinator, *spec.workers):
        result.append(["ssh", "-o", "BatchMode=yes", "--", node.host,
                       shlex.join(("mkdir", "-p", str(remote)))])
        result.append(["rsync", "-a", "--delete", "--", str(engine / "classes"),
                       str(engine / "lib"), f"{node.host}:{remote}/"])
        result.append(["rsync", "-a", "--", str(manifest_path),
                       f"{node.host}:{remote}/manifest.json"])
    return result


def verify_remote_images(spec: Any,
        run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> dict[str, Any]:
    allowed = (PINNED_IMAGE, ALTERNATE_CLASSIC_IMAGE_ID)
    rows = {}
    for node in (spec.coordinator, *spec.workers):
        accepted = None
        failures = []
        for candidate in allowed:
            argv = ["ssh", "-o", "BatchMode=yes", "--", node.host,
                    shlex.join(("docker", "image", "inspect", candidate))]
            completed = run(argv, capture_output=True, text=True, check=False, timeout=30)
            if completed.returncode:
                failures.append({"candidate": candidate, "returncode": completed.returncode})
                continue
            actual_id, content = matrix_campaign.normalized_image_content(
                json.loads(completed.stdout))
            if actual_id != candidate or content != PINNED_IMAGE_CONTENT_SHA256:
                raise RuntimeError(f"pinned image content mismatch on {node.host}")
            accepted = {"host": node.host, "actualImageId": actual_id,
                        "contentSha256": content, "failedCandidates": failures}
            break
        if accepted is None:
            raise RuntimeError(f"neither approved pinned image representation exists on {node.host}")
        rows[node.host] = accepted
    return {"schema": "systemds-memo-metadata-image-identity/v1", "hosts": rows,
            "allowedImageIds": list(allowed), "contentSha256": PINNED_IMAGE_CONTENT_SHA256}


def bind_start_plan_images(plan: Any, image_receipt: dict[str, Any]) -> Any:
    commands = []
    for command in plan.commands:
        if not command.label.startswith("run-"):
            commands.append(command)
            continue
        remote = shlex.split(command.argv[-1])
        matches = [index for index, value in enumerate(remote) if value == PINNED_IMAGE]
        if len(matches) != 1:
            raise ValueError(f"run command does not contain exactly one pinned image: {command.label}")
        remote[matches[0]] = image_receipt["hosts"][command.host]["actualImageId"]
        argv = (*command.argv[:-1], shlex.join(remote))
        commands.append(docker.RemoteCommand(command.label, command.host, argv))
    return docker.CommandPlan(action=plan.action, campaign_id=plan.campaign_id,
                              commands=tuple(commands))


def engine_verify_commands(spec: Any, engine: Path, remote: Path) -> list[list[str]]:
    script = r'''import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); value=json.loads((root/"manifest.json").read_text())
expected=value.get("files",value); actual={}
for rel,want in expected.items():
 p=root/rel
 if not p.is_file() or p.is_symlink(): raise SystemExit("missing/nonregular engine file: "+rel)
 h=hashlib.sha256()
 with p.open("rb") as f:
  while True:
   b=f.read(1048576)
   if not b: break
   h.update(b)
 if h.hexdigest()!=want: raise SystemExit("engine identity mismatch: "+rel)
 actual[rel]=want
observed=sorted(str(p.relative_to(root)) for p in root.rglob("*") if p.is_file() and p.name!="manifest.json")
if observed!=sorted(expected): raise SystemExit("engine file-set mismatch")
print(json.dumps({"verified":True,"files":len(actual)},sort_keys=True))'''
    result = []
    for node in (spec.coordinator, *spec.workers):
        result.append(["ssh", "-o", "BatchMode=yes", "--", node.host,
                       shlex.join(("python3", "-c", script, str(remote)))])
    return result


def resource_snapshot_command(spec: Any, node: Any) -> list[str]:
    script = r'''set -euo pipefail
name="$1"; id=$(docker inspect --format '{{.Id}}' "$name")
pid=$(docker inspect --format '{{.State.Pid}}' "$name")
state=$(docker inspect --format '{{json .State}}' "$name")
cg=$(awk -F: '$1=="0"{print $3}' "/proc/$pid/cgroup"); test -n "$cg"
python3 - "$id" "$pid" "$state" "/sys/fs/cgroup$cg" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[4])
def read(name):
 p=root/name
 return p.read_text().strip() if p.is_file() else None
print(json.dumps({"containerId":sys.argv[1],"pid":int(sys.argv[2]),
 "state":json.loads(sys.argv[3]),"cgroupPath":str(root),
 "memoryCurrent":read("memory.current"),"memoryPeak":read("memory.peak"),
 "cpuStat":read("cpu.stat")},sort_keys=True))
PY'''
    return ["ssh", "-o", "BatchMode=yes", "--", node.host,
            shlex.join(("bash", "-lc", script, "--", spec.container_name(node)))]


def capture_resource_snapshot(spec: Any,
        run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> dict[str, Any]:
    values = {}
    for node in (spec.coordinator, *spec.workers):
        completed = run(resource_snapshot_command(spec, node), capture_output=True, text=True,
                        check=False, timeout=30)
        if completed.returncode:
            raise RuntimeError(
                f"required cgroup snapshot failed on {node.host} "
                f"({completed.returncode}): {completed.stderr}")
        try:
            row = json.loads(completed.stdout)
        except (TypeError, json.JSONDecodeError) as error:
            raise RuntimeError(
                f"required cgroup snapshot invalid on {node.host}: {error}") from error
        required = ("containerId", "pid", "state", "cgroupPath",
                    "memoryCurrent", "memoryPeak", "cpuStat")
        if not isinstance(row, dict) or any(
                key not in row or row[key] is None for key in required):
            raise RuntimeError(f"required cgroup snapshot incomplete on {node.host}")
        values[node.host] = row
    return {"schema": "systemds-memo-metadata-cgroup-snapshot/v1", "nodes": values,
            "scope": "container-cgroup-not-process-rss"}


def capture_required_container_evidence(spec: Any, attempt: Path) -> list[str]:
    """Collect mandatory health evidence and best-effort logs for every started node."""
    errors = []
    for node in (spec.coordinator, *spec.workers):
        try:
            health = matrix_campaign.capture_container_health(spec, node)
            atomic_json(attempt / f"{node.host}-container-health.json", health)
            if health["state"].get("OOMKilled") or health.get("oom_events"):
                errors.append(f"container OOM observed: {node.host}")
        except Exception as error:
            atomic_json(attempt / f"{node.host}-container-health.json",
                        {"captureFailed": True, "error": str(error)})
            errors.append(f"health capture failed on {node.host}: {error}")
        try:
            logs = subprocess.run(["ssh", "-o", "BatchMode=yes", "--", node.host,
                shlex.join(("docker", "logs", "--tail", "3000", spec.container_name(node)))],
                capture_output=True, text=True, check=False, timeout=30)
            (attempt / f"{node.host}-container.log").write_text(
                logs.stdout + logs.stderr, encoding="utf-8")
        except Exception as error:
            (attempt / f"{node.host}-container.log").write_text(
                f"log capture failed: {error}\n", encoding="utf-8")
    return errors


def apply_required_evidence_errors(result: dict[str, Any], errors: Sequence[str]) -> None:
    if errors:
        result["errors"].extend(errors)
        result["status"] = "failed"


def cleanup_remote_namespace(spec: Any, remote: Path,
        run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> dict[str, Any]:
    """Remove only this attempt's UUID-owned namespace and record every host outcome."""
    if (remote.parent != Path("/home/mchoi")
            or re.fullmatch(r"memo-p2-metadata-[0-9a-f]{16}", remote.name) is None):
        raise ValueError(f"refusing namespace cleanup outside exact UUID-owned path: {remote}")
    rows = {}
    for node in (spec.coordinator, *spec.workers):
        argv = ["ssh", "-o", "BatchMode=yes", "--", node.host,
                shlex.join(("rm", "-rf", "--", str(remote)))]
        try:
            completed = run(argv, capture_output=True, text=True, check=False, timeout=60)
            rows[node.host] = {"returncode": completed.returncode,
                               "stdout": completed.stdout, "stderr": completed.stderr}
        except Exception as error:
            rows[node.host] = {"returncode": None, "error": str(error)}
    return {"schema": "systemds-memo-metadata-namespace-cleanup/v1",
            "remote": str(remote), "uuidOwned": True, "hosts": rows,
            "resolved": all(row.get("returncode") == 0 for row in rows.values())}


def remote_resource_preflight(spec: Any, engine: Path, remote: Path,
        run: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> dict[str, Any]:
    required = sum(path.stat().st_size for path in engine.rglob("*") if path.is_file())
    required += 512 * 1024**2
    script = r'''import json,os,pathlib,sys
p=pathlib.Path(sys.argv[1]); p.mkdir(parents=True,exist_ok=True)
v=os.statvfs(p); mem=0
for line in pathlib.Path("/proc/meminfo").read_text().splitlines():
 if line.startswith("MemAvailable:"): mem=int(line.split()[1])*1024
t=p/(".memo-metadata-write-test-"+str(os.getpid())); t.write_bytes(b"ok"); t.unlink()
print(json.dumps({"diskFreeBytes":v.f_bavail*v.f_frsize,"memAvailableBytes":mem,
 "writeVerified":True},sort_keys=True))'''
    rows = {}
    for node in (spec.coordinator, *spec.workers):
        argv = ["ssh", "-o", "BatchMode=yes", "--", node.host,
                shlex.join(("python3", "-c", script, str(remote)))]
        completed = run(argv, capture_output=True, text=True, check=False, timeout=30)
        if completed.returncode:
            raise RuntimeError(f"resource preflight failed on {node.host}: {completed.stderr}")
        row = json.loads(completed.stdout)
        row["requiredDiskFreeBytes"] = required
        row["passed"] = row.get("writeVerified") is True and row.get("diskFreeBytes", 0) >= required
        if not row["passed"]:
            raise RuntimeError(f"insufficient engine deployment disk on {node.host}")
        rows[node.host] = row
    return {"schema": "systemds-memo-metadata-resource-preflight/v1", "hosts": rows,
            "passed": True,
            "memoryPolicy": "record MemAvailable; Docker enforces the explicit 8g limit without a synthetic host-memory floor"}


@contextlib.contextmanager
def exclusive_lane(path: Path = LANE_LOCK) -> Iterator[dict[str, Any]]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError(f"exclusive experiment lane is held: {path}") from error
        yield {"path": str(path), "mode": "exclusive-nonblocking", "held": True}


def manifest(args: argparse.Namespace, engine: Path, stage: Path) -> dict[str, Any]:
    return {"schema": "systemds-memo-metadata-planning/v1",
        "cells": [p2_cell(value) for value in args.worker_values],
        "image": PINNED_IMAGE, "engine": str(engine), "stage": str(stage),
        "enginePreflight": matrix.validate_engine(engine),
        "costContract": matrix.validate_cost_contract(),
        "resources": {"coordinator": {"host": "so007", "cpuEnforcement": "cpuset=0-3",
                                        "memory": "8g", "heap": "3g"},
                      "worker": {"cpuEnforcement": "cpuset=0-3", "memory": "8g", "heap": "3g"}},
        "lifecycleAdapterConstraint": {
            "requestedReference": "local coordinator with Docker --network host and --cpus=4",
            "actual": "existing strict multihost lifecycle: coordinator so007, bridge/published workers, LAN netem 1ms/5000Mbit, cpuset 0-3",
            "reason": "reuses the validated ordered-start/readiness/owned-cleanup lifecycle whose parser fixes coordinator=so007 and cpuset resources",
            "comparability": "not directly interchangeable with the local no-RPC planning-matrix timing"},
        "timingContract": {
            "metadataPreparationSeconds": "subset of fullCompileSeconds",
            "fullCompileSeconds": "DMLScript compilation including worker metadata preparation",
            "coldToReadySeconds": "render, full remote snapshot verification, engine deployment, worker start, and compilation",
            "engineDeploymentIncluded": True,
        },
        "executionContract": {"workloadBodyExecutionCount": 0, "metadataRpcExpected": True,
            "stageReadOnlyMount": True,
            "snapshotImmutability": "cooperative shared stage leases, not filesystem-enforced host snapshot"},
        "timeouts": {"plannerSeconds": TIMEOUT_SECONDS, "wrapperSeconds": WRAPPER_TIMEOUT_SECONDS},
        "leaseContract": {"sharedStageLeaseHelper": str(CAMPAIGN),
                          "exclusiveLane": str(LANE_LOCK)}}


def run_checked(argv: Sequence[str], **kwargs: Any) -> subprocess.CompletedProcess:
    completed = subprocess.run(argv, **kwargs)
    if completed.returncode:
        raise RuntimeError(f"command failed ({completed.returncode}): {shlex.join(argv)}")
    return completed


def run_cell(value: dict[str, Any], root: Path, engine: Path, stage: Path,
             renderer: Any) -> dict[str, Any]:
    token = uuid.uuid4().hex[:16]
    attempt = root / "attempts" / f"p2-P2_PREP-w{value['workers']}"
    attempt.mkdir(parents=True, exist_ok=False)
    cold_started = time.monotonic()
    source, config, receipt, base_adaptation = matrix.render_artifacts(renderer, value)
    rendered = {**receipt, "source": source}
    (attempt / "cell.dml").write_text(source, encoding="utf-8")
    (attempt / "execution.xml").write_text(config, encoding="utf-8")
    atomic_json(attempt / "render-receipt.json", receipt)
    atomic_json(attempt / "adaptation-receipt.json", adapt_receipt(base_adaptation, source))
    remote = Path("/home/mchoi") / ("memo-p2-metadata-" + token)
    life = build_lifecycle(rendered, stage, remote / "engine", remote / "attempt", token)
    spec = docker.parse_manifest(life)
    atomic_json(attempt / "lifecycle.json", life)
    atomic_json(attempt / "command-plan.json", {
        "foreignUseChecks": foreign_use_checks(spec),
        "engineSync": engine_sync_commands(spec, engine, remote / "engine"),
        "engineVerify": engine_verify_commands(spec, engine, remote / "engine"),
        "resourceSnapshot": {node.host: resource_snapshot_command(spec, node)
                             for node in (spec.coordinator, *spec.workers)},
        "start": docker.build_plan(spec, "start").as_dict(),
        "coordinator": coordinator_exec(spec)})
    result = {"schema": "systemds-memo-metadata-planning-attempt/v1",
              "cell": matrix.execution_cell(value), "renderCell": value,
              "status": "failed", "errors": [], "cleanupResolved": False,
              "workloadBodyExecutionCount": 0, "metadataRpcExpected": True,
              "physicalMetadataRpcCount": "not-instrumented"}
    leases = []
    started = False
    try:
        selected_hosts = [spec.coordinator.host, *(node.host for node in spec.workers)]
        leases = campaign.acquire_remote_stage_leases(selected_hosts, stage)
        if not campaign.remote_stage_leases_alive(leases):
            raise RuntimeError("remote shared stage lease lost immediately after acquisition")
        atomic_json(attempt / "remote-stage-verification.json",
                    verify_remote_selected_stage(rendered, stage))
        atomic_json(attempt / "resource-preflight.json",
                    remote_resource_preflight(spec, engine, remote))
        image_receipt = verify_remote_images(spec)
        atomic_json(attempt / "image-identity.json", image_receipt)
        for argv in foreign_use_checks(spec):
            run_checked(argv, capture_output=True, text=True, timeout=30)
        for argv in engine_sync_commands(spec, engine, remote / "engine"):
            run_checked(argv, capture_output=True, text=True, timeout=300)
        engine_receipts = {}
        for argv in engine_verify_commands(spec, engine, remote / "engine"):
            completed = run_checked(argv, capture_output=True, text=True, timeout=300)
            engine_receipts[argv[4]] = json.loads(completed.stdout)
        atomic_json(attempt / "remote-engine-verification.json", engine_receipts)
        for node in (spec.coordinator, *spec.workers):
            run_checked(["ssh", "-o", "BatchMode=yes", "--", node.host,
                         shlex.join(("mkdir", "-p", str(remote / "attempt")))],
                        capture_output=True, text=True, timeout=30)
            run_checked(["rsync", "-a", "--", str(attempt / "cell.dml"),
                         str(attempt / "execution.xml"), f"{node.host}:{remote / 'attempt'}/"],
                        capture_output=True, text=True, timeout=60)
        started = True
        def start(command: Any):
            completed = run_checked(command.argv, capture_output=True, text=True, timeout=150)
            (attempt / (command.label + ".log")).write_text(completed.stdout, encoding="utf-8")
            return completed
        actual_start_plan = bind_start_plan_images(docker.build_plan(spec, "start"), image_receipt)
        atomic_json(attempt / "actual-start-plan.json", actual_start_plan.as_dict())
        lifecycle.execute_start(actual_start_plan, start)
        atomic_json(attempt / "cgroup-before.json", capture_resource_snapshot(spec))
        try:
            completed = subprocess.run(coordinator_exec(spec), capture_output=True, text=True,
                                       check=False, timeout=WRAPPER_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as error:
            stdout = error.stdout or ""
            stderr = error.stderr or ""
            if isinstance(stdout, bytes):
                stdout = stdout.decode("utf-8", errors="replace")
            if isinstance(stderr, bytes):
                stderr = stderr.decode("utf-8", errors="replace")
            (attempt / "coordinator.log").write_text(
                stdout + "\n" + stderr + "\nHARNESS_TIMEOUT\n", encoding="utf-8")
            raise RuntimeError("coordinator wrapper timeout; completion is not proven") from error
        cold_to_ready = time.monotonic() - cold_started
        atomic_json(attempt / "cgroup-after.json", capture_resource_snapshot(spec))
        validation = record_and_validate_success(
            attempt, completed.stdout, completed.stderr,
            value["workers"], completed.returncode)
        result.update(returncode=completed.returncode, validation=validation,
                      timing={"fullCompileSeconds": validation["compile"].get("fullCompileSeconds"),
                              "metadataPreparationSeconds": validation["metadata"].get("metadataPreparationSeconds"),
                              "coldToReadySeconds": cold_to_ready})
        if validation["verified"]:
            result["status"] = "passed"
        else:
            result["errors"].append("compile/metadata/OPTIMAL/worker-count evidence validation failed")
    except Exception as error:
        result["errors"].append(f"{type(error).__name__}: {error}")
    finally:
        container_cleanup_resolved = not started
        if started:
            try:
                apply_required_evidence_errors(
                    result, capture_required_container_evidence(spec, attempt))
            except Exception as error:
                apply_required_evidence_errors(
                    result, ["required container evidence unresolved: " + str(error)])
            try:
                cleanup = campaign._strict_experiment_cleanup(docker, spec)
                atomic_json(attempt / "cleanup.json", cleanup)
                container_cleanup_resolved = cleanup.get("resolved") is True
            except Exception as error:
                result["errors"].append("cleanup unresolved: " + str(error))
        try:
            namespace_cleanup = cleanup_remote_namespace(spec, remote)
        except Exception as error:
            namespace_cleanup = {"schema": "systemds-memo-metadata-namespace-cleanup/v1",
                "remote": str(remote), "uuidOwned": True, "hosts": {}, "resolved": False,
                "error": str(error)}
        atomic_json(attempt / "namespace-cleanup.json", namespace_cleanup)
        if not namespace_cleanup.get("resolved"):
            result["errors"].append("UUID-owned remote namespace cleanup unresolved")
        result["cleanupResolved"] = (container_cleanup_resolved
                                     and namespace_cleanup.get("resolved") is True)
        release = campaign.release_remote_stage_leases(leases) if leases else {"released": True}
        atomic_json(attempt / "lease-release.json", release)
        result["stageLeaseReleased"] = release.get("released") is True
        if not result["stageLeaseReleased"] or not result["cleanupResolved"]:
            result["status"] = "failed"
        result["totalCellSecondsIncludingEvidenceAndCleanup"] = time.monotonic() - cold_started
        atomic_json(attempt / "result.json", result)
    return result


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--sealed-stage", type=Path, default=DEFAULT_STAGE)
    parser.add_argument("--workers", default="1,3,5,7")
    parser.add_argument("--command-plan-only", action="store_true")
    args = parser.parse_args(argv)
    try:
        args.worker_values = tuple(int(item) for item in args.workers.split(","))
    except ValueError as error:
        parser.error(str(error))
    if (not args.worker_values or len(set(args.worker_values)) != len(args.worker_values)
            or any(item not in WORKERS for item in args.worker_values)):
        parser.error("--workers must be a unique comma list drawn from 1,3,5,7")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    engine = args.engine.resolve()
    stage = matrix.validate_sealed_stage(args.sealed_stage)
    root = args.output_root.resolve()
    root.mkdir(parents=True, exist_ok=False)
    value = manifest(args, engine, stage)
    value["commandPlanOnly"] = args.command_plan_only
    atomic_json(root / "manifest.json", value)
    renderer = matrix.renderer_module().Renderer(stage)
    if args.command_plan_only:
        for count in args.worker_values:
            cell = p2_cell(count)
            source, config, receipt, adaptation = matrix.render_artifacts(renderer, cell)
            rendered = {**receipt, "source": source}
            token = f"dryrun-w{count}"
            spec = docker.parse_manifest(build_lifecycle(
                rendered, stage, Path("/remote/engine"), Path("/remote/attempt"), token))
            atomic_json(root / f"command-plan-w{count}.json", {
                "foreignUseChecks": foreign_use_checks(spec),
                "engineSync": engine_sync_commands(spec, engine, Path("/remote/engine")),
                "engineVerify": engine_verify_commands(spec, engine, Path("/remote/engine")),
                "remoteStageVerification": {host: remote_inventory_command(stage, files)
                    for host, files in selected_stage_inventory(rendered).items()},
                "start": docker.build_plan(spec, "start").as_dict(),
                "coordinator": coordinator_exec(spec),
                "adaptation": adapt_receipt(adaptation, source)})
        return 0
    results = []
    with exclusive_lane() as lane:
        value["exclusiveLaneReceipt"] = lane
        atomic_json(root / "manifest.json", value)
        for count in args.worker_values:
            results.append(run_cell(p2_cell(count), root, engine, stage, renderer))
            if results[-1]["status"] != "passed":
                break
    atomic_json(root / "summary.json", {"schema": "systemds-memo-metadata-planning-summary/v1",
        "expected": len(args.worker_values), "attempted": len(results),
        "passed": sum(item["status"] == "passed" for item in results), "rows": results})
    return 0 if len(results) == len(args.worker_values) and all(
        item["status"] == "passed" for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
