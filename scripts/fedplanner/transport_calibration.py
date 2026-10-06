#!/usr/bin/env python3
"""Bounded Docker GET/PUT calibration with explicit artifact and lifecycle identity."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
from typing import Sequence


EVALUATION_ROOT = Path("/home/mchoi/cofee-evaluation")
# Snap Docker on so002/so003 cannot bind /grid, including symlinks into it.
# Only the ~290 MiB runtime stage lives in /home; sources/evidence stay on /grid/3.
STAGE_PARENT = EVALUATION_ROOT / "transport-stages"
TOPOLOGY_MODULE = EVALUATION_ROOT / "tools" / "multihost_topology.py"
DOCKER_MODULE = EVALUATION_ROOT / "driver" / "tools" / "multihost_docker.py"
LOCK = Path(os.environ.get("COFEE_RUNTIME_LANE", EVALUATION_ROOT / "runs/.cofee-experiment-lane.lock"))
CONFIG_XML = "<root><sysds.federated.timeout>-1</sysds.federated.timeout></root>\n"
IMAGE = "cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0"
IMAGE_CONTENT_SHA256 = "f75eb2420276384ba77539d106537e6d855b44eb9a7ebf42d2c4810e4e1ef0bd"
NETWORK_PROFILES = {
    "lan": (1, 5000, 5000),
    "wan_light": (10, 2500, 1000),
    "wan_mid": (100, 250, 200),
    "wan_heavy": (200, 100, 100),
}
SMALL_MIB = 0.0625
WORKER_COUNTS = (1, 3)
PAYLOAD_MIB = (SMALL_MIB, 4, 16, 64)
JAVA = (
    "java", "--add-modules", "jdk.incubator.vector", "-Xms16g", "-Xmx16g", "-Xmn1600m",
    "-XX:ActiveProcessorCount=8", "-cp", "/probe/classes:/artifacts/SystemDS.jar:/artifacts/lib/*",
)
SOURCE = Path(__file__).resolve().parent / "calibration" / "TransportCalibrationProbe.java"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


topology_module = _load("transport_calibration_topology", TOPOLOGY_MODULE)
docker_module = _load("transport_calibration_multihost", DOCKER_MODULE)
profile_module = _load("transport_calibration_profile",
                       Path(__file__).resolve().parent / "calibration" / "transport_profile.py")


@dataclass(frozen=True)
class CalibrationCase:
    workers: int
    payload_mib: float
    distribution: str
    operation: str


@dataclass(frozen=True)
class Campaign:
    profile: str
    workers: int
    spec: object
    probe_command: tuple[str, ...]


def build_cases() -> tuple[CalibrationCase, ...]:
    cases = []
    for workers in WORKER_COUNTS:
        distributions = ("fixed-total", "fixed-per-worker") + (("skew",) if workers == 3 else ())
        for payload in PAYLOAD_MIB:
            for distribution in distributions:
                cases.append(CalibrationCase(workers, payload, distribution, "get"))
            cases.append(CalibrationCase(workers, payload, "fixed-per-worker", "broadcast-put"))
            for distribution in distributions:
                cases.append(CalibrationCase(workers, payload, distribution, "slice-put"))
    return tuple(cases)


def load_topology():
    return topology_module.load_topology()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_jar(jar: Path, expected_sha256: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("--jar-sha256 must be 64 lowercase hexadecimal characters")
    resolved = jar.expanduser().resolve(strict=True)
    if not resolved.is_file():
        raise ValueError(f"jar is not a file: {resolved}")
    validate_stage_path(resolved)
    actual = sha256(resolved)
    if actual != expected_sha256:
        raise ValueError(f"jar SHA-256 mismatch: expected={expected_sha256}, actual={actual}")
    libraries = resolved.parent / "lib"
    if not libraries.is_dir() or not any(libraries.glob("*.jar")):
        raise ValueError(f"current jar requires its populated sibling dependency directory: {libraries}")
    return actual


def compile_probe(jar: Path, classes: Path) -> dict[str, str]:
    classes.mkdir(parents=True, exist_ok=True)
    classpath = f"{jar}:{jar.parent / 'lib'}/*"
    subprocess.run([
        "javac", "--add-modules", "jdk.incubator.vector", "-cp", classpath,
        "-d", str(classes), str(SOURCE),
    ], check=True)
    compiled = sorted(classes.rglob("*.class"))
    if not compiled:
        raise RuntimeError("probe compilation produced no class files")
    return {str(path.relative_to(classes)): sha256(path) for path in compiled}


def stage_local_artifacts(jar: Path, build: Path) -> Path:
    artifacts = build / "artifacts"
    (artifacts / "lib").mkdir(parents=True, exist_ok=False)
    staged = artifacts / "SystemDS.jar"
    shutil.copy2(jar, staged)
    for dependency in sorted((jar.parent / "lib").glob("*.jar")):
        shutil.copy2(dependency, artifacts / "lib" / dependency.name)
    return staged


# Storage is host-local. Never overwrite an existing stage, including a partial
# copy from a failed run; the next invocation must use a new output directory.
STAGE_GUARD = """
import hashlib,json,pathlib,shutil,sys
root=pathlib.Path(sys.argv[1]); expected=json.loads(sys.argv[2]); mode=sys.argv[3]
if not root.is_absolute() or any(path.is_symlink() for path in (root,*root.parents)):
    raise SystemExit('refusing relative/symlink stage or ancestor')
if mode == 'prepare':
    if len(sys.argv)!=5: raise SystemExit('stage reserve required')
    required=int(sys.argv[4])
    if required<0: raise SystemExit('invalid stage reserve')
if mode == 'prepare' and not root.exists():
    ancestor=root.parent
    while not ancestor.exists(): ancestor=ancestor.parent
    if shutil.disk_usage(ancestor).free < required: raise SystemExit('insufficient stage disk reserve')
    root.parent.mkdir(parents=True,exist_ok=True)
    root.mkdir()
    print('copy')
else:
    actual={}
    for path in root.rglob('*'):
        if path.is_symlink(): raise SystemExit('refusing symlink artifact')
        if path.is_file():
            digest=hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda:stream.read(1048576),b''): digest.update(chunk)
            actual[str(path.relative_to(root))]=digest.hexdigest()
    if actual != expected: raise SystemExit('existing stage inventory mismatch; not overwriting')
    print('present')
"""


def stage_remote_artifacts(campaigns: Sequence[Campaign], jar: Path, build: Path, output: Path) -> dict:
    inventory = artifact_inventory(campaigns[0], jar)
    if build.parent != STAGE_PARENT:
        raise ValueError("runtime stage must be directly under the Snap-visible stage parent")
    relative = {str(Path(path).relative_to(build)): digest for path, digest in inventory.items()}
    required = sum(Path(path).stat().st_size for path in inventory) + 1024 ** 3
    hosts = dict.fromkeys(node.host for campaign in campaigns
                          for node in (campaign.spec.coordinator, *campaign.spec.workers))
    receipts = {}
    for host in hosts:
        command = ["python3", "-c", STAGE_GUARD, str(build), json.dumps(relative)]
        state = remote(host, command + ["prepare", str(required)]).stdout.strip()
        if state == "copy":
            copied = subprocess.run(["rsync", "-a", "--protect-args", "--rsh=ssh -o BatchMode=yes", "--", str(build) + "/",
                                     f"{host}:{build}/"], text=True, capture_output=True)
            _write_json(output / f"stage-{host}.json", {
                "returncode": copied.returncode, "stdout": copied.stdout, "stderr": copied.stderr})
            if copied.returncode:
                raise RuntimeError(f"artifact staging failed on {host}: {copied.stderr}")
        elif state != "present":
            raise RuntimeError(f"unrecognized stage state from {host}: {state!r}")
        verified = remote(host, command + ["verify"]).stdout.strip()
        if verified != "present":
            raise RuntimeError(f"stage verification failed on {host}: {verified!r}")
        receipts[host] = {"initial_state": state, "files": len(relative), "verified": True}
        _write_json(output / "remote-stage.json", receipts)
    return receipts


def make_campaign(*, campaign_id: str, selection, profile: str, jar: Path, classes: Path,
                  warmup: int, repeats: int) -> Campaign:
    rtt, c2w, w2c = NETWORK_PROFILES[profile]
    workers = tuple(docker_module.Node(
        worker.host, worker.ip,
        ("java", "--add-modules", "jdk.incubator.vector", "-Xms16g", "-Xmx16g", "-Xmn1600m",
         "-XX:ActiveProcessorCount=8", "-cp", "/artifacts/SystemDS.jar:/artifacts/lib/*",
         "org.apache.sysds.api.DMLScript", "-w", str(worker.port), "-config", "/probe/config.xml"),
        (("OMP_NUM_THREADS", "8"), ("OPENBLAS_NUM_THREADS", "8")),
        worker.index, worker.port,
    ) for worker in selection.workers)
    probe_command = JAVA + (
        "TransportCalibrationProbe", "--config", "/probe/config.xml", "--sites", ",".join(selection.worker_addresses),
        "--workers", str(len(workers)), "--warmup", str(warmup), "--repeats", str(repeats),
    )
    spec = docker_module.MultiHostSpec(
        campaign_id, IMAGE,
        docker_module.Node(selection.coordinator.host, selection.coordinator.ip,
                           ("sleep", "infinity"),
                           (("OMP_NUM_THREADS", "8"), ("OPENBLAS_NUM_THREADS", "8"))),
        workers,
        (
            docker_module.Mount(str(jar.resolve()), "/artifacts/SystemDS.jar", True),
            docker_module.Mount(str((jar.parent / "lib").resolve()), "/artifacts/lib", True),
            docker_module.Mount(str(classes.resolve()), "/probe/classes", True),
            docker_module.Mount(str((classes.parent / "config.xml").resolve()), "/probe/config.xml", True),
        ),
        docker_module.NetworkProfile(rtt, c2w, w2c, 10000),
        docker_module.ContainerResources("10041:5500", "0-7", "24g", "/workspace/experiments", "4g"),
    )
    return Campaign(profile, len(workers), spec, probe_command)


def remote(host: str, argv: Sequence[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "--", host, shlex.join(argv)],
        text=True, capture_output=True,
    )
    if check and result.returncode:
        raise RuntimeError(f"{host}: command failed rc={result.returncode}: {shlex.join(argv)}\n{result.stderr}")
    return result


def _image_fingerprint(image: dict) -> str:
    payload = {"RootFS": image["RootFS"], "Config": image["Config"]}
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


def preflight(campaign: Campaign, jar: Path, jar_sha256: str) -> dict:
    receipts = {}
    for node in (campaign.spec.coordinator, *campaign.spec.workers):
        occupancy = remote(node.host, [
            "bash", "-lc",
            "printf 'CONTAINERS\\n'; docker ps --format '{{.ID}} {{.Names}} {{.Image}}'; "
            "printf 'PROCESSES\\n'; pgrep -af '[o]rg.apache.sysds.api.DMLScript.* -w|"
            "[T]ransportCalibrationProbe' || true",
        ]).stdout
        sections = occupancy.split("PROCESSES\n", 1)
        running_containers = sections[0].removeprefix("CONTAINERS\n").strip()
        running_probes = sections[1].strip() if len(sections) == 2 else "unparseable"
        if running_containers or running_probes:
            raise RuntimeError(f"{node.host}: host is not empty; refusing to touch existing jobs\n{occupancy}")
        observed_jar = remote(node.host, ["sha256sum", str(jar)]).stdout.split()[0]
        if observed_jar != jar_sha256:
            raise RuntimeError(f"{node.host}: current jar SHA-256 mismatch")
        image = json.loads(remote(node.host, ["docker", "image", "inspect", IMAGE]).stdout)[0]
        fingerprint = _image_fingerprint(image)
        if fingerprint != IMAGE_CONTENT_SHA256:
            raise RuntimeError(f"{node.host}: fixed image content mismatch: {fingerprint}")
        inventory = artifact_inventory(campaign, jar)
        command = ["sha256sum", *inventory]
        observed = remote(node.host, command).stdout.splitlines()
        expected = [f"{digest}  {path}" for path, digest in inventory.items()]
        if observed != expected:
            raise RuntimeError(f"{node.host}: mounted dependency/probe/config inventory mismatch")
        host_network = remote(node.host, ["bash", "-lc",
            "ip -j route; ip -j -s link; for x in /sys/class/net/*/speed; do printf '%s ' \"$x\"; cat \"$x\" 2>/dev/null || true; done"]).stdout
        receipts[node.host] = {
            "artifact_inventory": inventory, "host_network": host_network,
            "occupancy": occupancy, "jar_sha256": observed_jar,
            "image_id": image["Id"], "image_content_sha256": fingerprint,
        }
    return receipts


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(path)


def _quiescent_snapshot(spec, output: Path, phase: str):
    for attempt in range(8):
        snapshot = docker_module.capture_network_snapshot(spec)
        quality = docker_module.validate_network_quality(spec, snapshot, snapshot)
        _write_json(output / f"netem-{phase}-attempt{attempt}.json", snapshot)
        if quality["valid"]:
            return snapshot
        _write_json(output / f"netem-{phase}-attempt{attempt}-quality.json", quality)
        if not quality["errors"] or any(not error.endswith("nonempty boundary backlog")
                                        for error in quality["errors"]):
            raise RuntimeError(f"{phase}: invalid network boundary: {quality['errors']}")
        time.sleep(1)
    raise RuntimeError(f"{phase}: queue did not become quiescent")


def _cleanup(spec, output: Path, primary_error: BaseException | None) -> dict:
    try:
        result = docker_module.execute_offline_cleanup(spec)
        receipt = result.as_dict()
        _write_json(output / "cleanup.json", receipt)
        return receipt
    except docker_module.OfflineCleanupUnresolved as error:
        _write_json(output / "cleanup.json", error.result.as_dict())
        raise RuntimeError(f"primary={primary_error!r}; cleanup={error!r}") from error


def artifact_inventory(campaign: Campaign, jar: Path) -> dict[str, str]:
    files = [jar, *sorted((jar.parent / "lib").glob("*.jar"))]
    for mount in campaign.spec.mounts:
        path = Path(mount.source)
        if mount.target == "/probe/classes":
            files.extend(sorted(path.rglob("*.class")))
        elif mount.target == "/probe/config.xml":
            files.append(path)
    return {str(path.resolve()): sha256(path) for path in files}


def validate_stage_path(path: Path) -> None:
    if not str(path.resolve()).startswith("/grid/3/"):
        raise ValueError("calibration stage/output must be on /grid/3; host-local stages are replicated explicitly")


def validate_samples(rows: list[dict], workers: int, repeats: int) -> None:
    expected = {(case.operation, case.distribution, case.payload_mib, workers, sample)
                for case in build_cases() if case.workers == workers for sample in range(repeats)}
    seen = set()
    for row in rows:
        key = tuple(row.get(name) for name in ("operation", "distribution", "payload_mib", "workers", "sample"))
        if key not in expected or key in seen:
            raise ValueError(f"invalid or duplicate sample key: {key}")
        seen.add(key)
        if row.get("correctness") is not True or row.get("kind") != "transport-calibration":
            raise ValueError(f"payload correctness failed: {key}")
        for name in ("logical_bytes", "application_frame_bytes", "elapsed_ns", "retrieval_elapsed_ns"):
            if type(row.get(name)) is not int or row[name] <= 0:
                raise ValueError(f"invalid positive numeric field {name}: {key}")
        if type(row.get("assembly_elapsed_ns")) is not int or row["assembly_elapsed_ns"] < 0:
            raise ValueError("invalid assembly time")
        for prefix in ("coordinator_rx_bytes", "coordinator_tx_bytes", "cgroup_cpu_usage_usec"):
            before, after = row.get(prefix + "_before"), row.get(prefix + "_after")
            if type(before) is not int or type(after) is not int or before < 0 or after < before:
                raise ValueError(f"invalid/nonmonotonic counter: {prefix}")
            row[prefix + "_delta"] = after - before
        direction = "rx" if row["operation"] == "get" else "tx"
        actual_wire = row[f"coordinator_{direction}_bytes_delta"]
        frame = row["application_frame_bytes"]
        if not 0.95 * frame <= actual_wire <= 1.5 * frame + 65536:
            raise ValueError(f"NIC counter does not support application frame bytes: {key} {actual_wire}/{frame}")
        row["observed_wire_bytes"] = actual_wire
    if seen != expected:
        raise ValueError(f"missing sample keys: {expected - seen}")


def _container_logs(spec, output: Path) -> None:
    for node in (spec.coordinator, *spec.workers):
        for label, argv in (("log", ["docker", "logs", spec.container_name(node)]),
                            ("resources", ["docker", "exec", spec.container_name(node), "sh", "-c",
                             "cat /sys/fs/cgroup/cpu.stat; cat /sys/fs/cgroup/memory.events; cat /proc/net/dev"])):
            result = remote(node.host, argv, check=False)
            (output / f"{node.host}.{label}").write_text(f"rc={result.returncode}\n{result.stdout}\n{result.stderr}")


def execute_campaign(campaign: Campaign, jar: Path, jar_sha256: str, output: Path,
                     expected_rows: int) -> dict:
    output.mkdir(parents=True, exist_ok=False)
    spec = campaign.spec
    preflight_receipt = preflight(campaign, jar, jar_sha256)
    _write_json(output / "preflight.json", preflight_receipt)
    _write_json(output / "start-plan.json", docker_module.build_plan(spec, "start").as_dict())
    _write_json(output / "cleanup-plan.json", docker_module.build_plan(spec, "cleanup").as_dict())
    primary_error = None
    try:
        docker_module.execute_plan(docker_module.build_plan(spec, "start"))
        before = _quiescent_snapshot(spec, output, "before")
        result = remote(spec.coordinator.host, [
            "docker", "exec", spec.container_name(spec.coordinator), *campaign.probe_command,
        ], check=False)
        (output / "probe.stdout").write_text(result.stdout)
        (output / "probe.stderr").write_text(result.stderr)
        if result.returncode:
            raise RuntimeError(f"probe failed rc={result.returncode}; see persisted stdout/stderr")
        rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        case_count = sum(case.workers == campaign.workers for case in build_cases())
        validate_samples(rows, campaign.workers, expected_rows // case_count)
        after = _quiescent_snapshot(spec, output, "after")
        quality = docker_module.validate_network_quality(spec, before, after)
        _write_json(output / "network-quality.json", quality)
        if not quality["valid"]:
            raise RuntimeError(f"network quality rejected samples: {quality['errors']}")
        _write_json(output / "samples.json", rows)
        return {"rows": len(rows), "network_quality": quality}
    except BaseException as error:
        primary_error = error
        raise
    finally:
        failures = []
        try:
            _container_logs(spec, output)
        except Exception as error:
            failures.append(error)
        try:
            _cleanup(spec, output, primary_error)
        except Exception as error:
            failures.append(error)
        if failures:
            details = "; ".join(repr(error) for error in failures)
            if primary_error is not None:
                raise RuntimeError(f"experiment and finalization failed: {details}") from primary_error
            raise RuntimeError(f"calibration finalization failed: {details}") from failures[0]


def summarize(output: Path, campaigns: Sequence[Campaign]) -> dict:
    del campaigns
    report = profile_module.build_profile(output, expected_rows=None)
    _write_json(output / "transport-cost-profile.json", report)
    _write_json(output / "fit-heldout.json", report)
    return report


def build_manifest(args, jar: Path, jar_sha256: str, classes: Path,
                   class_sha256: dict[str, str], campaigns: Sequence[Campaign]) -> dict:
    return {
        "schema": "cofee-transport-calibration/v1",
        "dry_run": args.dry_run,
        "jar": str(jar), "jar_sha256": jar_sha256,
        "source_jar": str(args.jar.expanduser().resolve()),
        "runtime_stage": str(classes.parent),
        "image": IMAGE, "image_content_sha256": IMAGE_CONTENT_SHA256,
        "common_lock": str(LOCK), "topology_sha256": load_topology().topology_sha256,
        "resources": {"cpuset": "0-7", "memory": "24g", "tmpfs": "4g", "jvm_heap": "16g", "apc": 8},
        "warmup": args.warmup, "repeats": args.repeats,
        "runner_sha256": sha256(Path(__file__).resolve()),
        "entrypoint_sha256": sha256(Path(__file__).resolve().parent / "run_LAN_docker.sh"),
        "probe_source_sha256": sha256(SOURCE),
        "topology_module_sha256": sha256(TOPOLOGY_MODULE),
        "docker_lifecycle_module_sha256": sha256(DOCKER_MODULE),
        "classes": class_sha256, "classes_dir": str(classes),
        "artifact_inventory": artifact_inventory(campaigns[0], jar),
        "config_sha256": sha256(classes.parent / "config.xml"),
        "cases": [case.__dict__ for case in build_cases()],
        "coefficient_scope": "GET retrieval includes response decode; assembly is separate; neither is labeled pure codec",
        "campaigns": [{
            "profile": campaign.profile, "workers": campaign.workers,
            "spec": asdict(campaign.spec),
            "probe_command": list(campaign.probe_command),
            "start_plan": docker_module.build_plan(campaign.spec, "start").as_dict(),
            "cleanup_plan": docker_module.build_plan(campaign.spec, "cleanup").as_dict(),
        } for campaign in campaigns],
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--jar", type=Path, required=True, help="current source SystemDS.jar on /grid/3")
    result.add_argument("--jar-sha256", required=True, help="expected current jar SHA-256")
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--profiles", nargs="+", choices=tuple(NETWORK_PROFILES),
                        default=tuple(NETWORK_PROFILES))
    result.add_argument("--workers", nargs="+", type=int, choices=WORKER_COUNTS, default=WORKER_COUNTS)
    result.add_argument("--warmup", type=int, default=3)
    result.add_argument("--repeats", type=int, default=5)
    result.add_argument("--dry-run", action="store_true")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if args.warmup < 0 or args.repeats < 1:
        parser().error("--warmup must be nonnegative and --repeats must be positive")
    jar = args.jar.expanduser().resolve()
    jar_sha256 = validate_jar(jar, args.jar_sha256)
    output = args.output.expanduser().resolve()
    validate_stage_path(output)
    if output.exists():
        raise FileExistsError(f"preserve previous calibration output: {output}")
    output.mkdir(parents=True)
    stamp = hashlib.sha256(f"{jar_sha256}-{time.time_ns()}".encode()).hexdigest()[:10]
    STAGE_PARENT.mkdir(parents=True, exist_ok=True)
    required = jar.stat().st_size + sum(path.stat().st_size for path in (jar.parent / "lib").glob("*.jar"))
    if shutil.disk_usage(STAGE_PARENT).free < required + 1024 ** 3:
        raise RuntimeError("insufficient local runtime-stage disk reserve")
    classes = STAGE_PARENT / f"transport-r56-{stamp}" / "classes"
    classes.parent.mkdir(parents=True, exist_ok=False)
    (classes.parent / "config.xml").write_text(CONFIG_XML)
    jar = stage_local_artifacts(jar, classes.parent)
    if sha256(jar) != jar_sha256:
        raise RuntimeError("JAR changed while freezing local calibration stage")
    class_sha256 = compile_probe(jar, classes)
    topology = load_topology()
    campaigns = []
    for profile in args.profiles:
        for workers in args.workers:
            selection = topology.select(workers)
            campaigns.append(make_campaign(
                campaign_id=f"transport-r56-{profile}-w{workers}-{stamp}", selection=selection,
                profile=profile, jar=jar, classes=classes, warmup=args.warmup, repeats=args.repeats,
            ))
    manifest = build_manifest(args, jar, jar_sha256, classes, class_sha256, campaigns)
    manifest_path = output / ("dry-run-manifest.json" if args.dry_run else "manifest.json")
    _write_json(manifest_path, manifest)
    if args.dry_run:
        print(json.dumps({"dry_run_manifest": str(manifest_path), "campaigns": len(campaigns)}, sort_keys=True))
        return 0
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with LOCK.open("a+") as lease:
        try:
            fcntl.flock(lease, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError(f"calibration lease is held: {LOCK}") from error
        stage_remote_artifacts(campaigns, jar, classes.parent, output)
        results = []
        for campaign in campaigns:
            case_count = sum(case.workers == campaign.workers for case in build_cases())
            cell = output / f"{campaign.profile}-w{campaign.workers}"
            result = execute_campaign(campaign, jar, jar_sha256, cell, case_count * args.repeats)
            results.append({"profile": campaign.profile, "workers": campaign.workers, **result})
        summarize(output, campaigns)
        _write_json(output / "complete.json", {"manifest": str(manifest_path), "results": results})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
