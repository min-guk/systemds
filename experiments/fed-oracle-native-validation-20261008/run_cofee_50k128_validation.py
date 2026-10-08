#!/usr/bin/env python3
"""Generate and execute a pinned W1 validation copy of the current COFEE runner.

The production runner hard-codes canonical W1 to so002.  This adapter makes the
smallest reviewable transformation needed to run the same 24 GiB/16 GiB
contract on the idle, authorized so006 worker.  It refuses arbitrary topology,
resource, lane-lock, or engine substitutions.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import sys

ALLOWED_COORDINATOR = {"host": "so007", "ip": "130.149.237.17"}
ALLOWED_WORKER = {"index": 1, "host": "so006", "ip": "130.149.237.16", "port": 8001}
EXPECTED_PROFILE_WORKER = {key: value for key, value in ALLOWED_WORKER.items() if key != "index"}
SCHEMA = "cofee-w1357-validation-topology/v1"
EXPECTED_RESOURCES = {"cpuset_cpus": "0-7", "memory": "24g", "tmpfs_size": "4g",
                      "user": "10041:5500", "working_dir": "/workspace/experiments"}
EXPECTED_JVM_CORE = ["--add-modules", "jdk.incubator.vector", "-Xms16g", "-Xmx16g",
                     "-Xmn1600m", "-XX:ActiveProcessorCount=8",
                     "-Dsysds.fedplanner.runtime.audit=true",
                     "-Dsysds.fedplanner.phaseMarkers=true",
                     "-Dsysds.fedplanner.structuralArena.maxEntries=262144",
                     "-Dsysds.fedplanner.structuralArena.maxIdentityEntries=262144",
                     "-Dsysds.fedplanner.signatureCache.maxChars=536870912"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_topology(path: Path) -> dict:
    value = json.loads(path.read_text())
    expected_keys = {"schema", "coordinator", "workers", "worker_sweep", "adjustment"}
    if not isinstance(value, dict) or set(value) != expected_keys:
        raise ValueError("validation topology has unexpected fields")
    if value["schema"] != SCHEMA or value["coordinator"] != ALLOWED_COORDINATOR:
        raise ValueError("validation topology coordinator/schema mismatch")
    if value["workers"] != [ALLOWED_WORKER] or value["worker_sweep"] != [1]:
        raise ValueError("validation topology must select only W1 so006")
    adjustment = value["adjustment"]
    if (not isinstance(adjustment, dict)
            or adjustment.get("canonicalWorker") != "so002"
            or adjustment.get("actualWorker") != "so006"
            or adjustment.get("reason") != "canonical-worker-occupied-by-unowned-containers"):
        raise ValueError("validation topology adjustment provenance mismatch")
    return value


def validate_cost_profile(path: Path) -> dict:
    value = json.loads(path.read_text())
    identity = value.get("identity")
    costs = value.get("cost_environment")
    if (not isinstance(identity, dict) or identity.get("resources") != EXPECTED_RESOURCES
            or identity.get("threads") != 8
            or identity.get("expected_native_blas") != "unavailable"
            or identity.get("jvm_options") != EXPECTED_JVM_CORE):
        raise ValueError("validation cost profile resource/JVM/native identity mismatch")
    if (identity.get("coordinator") != ALLOWED_COORDINATOR
            or identity.get("workers") != [EXPECTED_PROFILE_WORKER]):
        raise ValueError("validation cost profile topology identity mismatch")
    if (not isinstance(costs, dict) or len(costs) != 11
            or any(not key.startswith("SYSDS_FED_COST_") for key in costs)):
        raise ValueError("validation cost profile vector mismatch")
    if not isinstance(value.get("profile_sha256"), str) or len(value["profile_sha256"]) != 64:
        raise ValueError("validation cost profile ID mismatch")
    return value


def validate_receipt_cost(receipt: dict, profile: dict) -> None:
    if receipt.get("effectiveCostEnvironment") != profile.get("cost_environment"):
        raise ValueError("receipt effective cost environment differs from frozen validation vector")


def validate_engine_repo(path: Path) -> dict:
    required = ("target/systemds-3.4.0-SNAPSHOT.jar", "ENGINE_ARTIFACT.json", "pom.xml",
                "scripts/builtin/steplm.dml", "scripts/builtin/lmCG.dml",
                "src/test/java/org/apache/sysds/test/functions/federated/fedplanning/MatrixCampaignProbe.java")
    for relative in required:
        candidate = path / relative
        if not candidate.is_file():
            raise ValueError(f"engine repository input missing: {candidate}")
    manifest = json.loads((path / "ENGINE_ARTIFACT.json").read_text())
    dependency_root = Path(manifest.get("dependencies", ""))
    linked_root = path / "target/lib"
    if (not dependency_root.is_dir() or not linked_root.is_dir()
            or linked_root.resolve() != dependency_root.resolve()):
        raise ValueError("engine repository target/lib does not resolve to pinned dependencies")
    expected = sorted(dependency_root.glob("*.jar"))
    linked = sorted(linked_root.glob("*.jar"))
    if not expected or [entry.name for entry in linked] != [entry.name for entry in expected]:
        raise ValueError("engine repository target/lib does not expose every pinned dependency JAR")
    if any(not (linked_root / entry.name).is_file() for entry in expected):
        raise ValueError("engine repository target/lib contains an unresolved dependency JAR")
    return manifest


def transform(source: str) -> str:
    replacements = {
        "REPO = Path(__file__).resolve().parents[2]":
            "REPO = Path(os.environ['COFEE_VALIDATION_ENGINE_REPO']).resolve()\n"
            "VALIDATION_TOPOLOGY = Path(os.environ['COFEE_VALIDATION_TOPOLOGY']).resolve()\n"
            "VALIDATION_COST_PROFILE = os.environ.get('COFEE_VALIDATION_COST_PROFILE')",
        "def dependencies():\n":
            "def dependencies():\n",
        "        '-Dsysds.fedplanner.signatureCache.maxChars=536870912')\nJFR_OPTIONS = (\n":
            "        '-Dsysds.fedplanner.signatureCache.maxChars=536870912')\n"
            "if os.environ.get('COFEE_VALIDATION_LIVE_METRICS') == '1':\n"
            "    JAVA = (*JAVA, '-Dsysds.fedplanner.liveMetrics=true')\n"
            "JFR_OPTIONS = (\n",
        "    return campaign, campaign._base_driver(), renderer\n\n\n":
            "    base = campaign._base_driver()\n"
            "    raw = json.loads(VALIDATION_TOPOLOGY.read_text())\n"
            "    canonical = base.load_topology()\n"
            "    node_type = type(canonical.coordinator)\n"
            "    worker_type = type(canonical.workers[0])\n"
            "    topology_type = type(canonical)\n"
            "    coordinator = node_type(**raw['coordinator'])\n"
            "    worker = worker_type(**raw['workers'][0])\n"
            "    topology = topology_type('so001', coordinator, (worker,), (1,), sha(VALIDATION_TOPOLOGY))\n"
            "    base.load_topology = lambda: topology\n"
            "    campaign.TOPOLOGY = VALIDATION_TOPOLOGY\n"
            "    renderer.Renderer._load_topology = staticmethod(lambda content: raw)\n"
            "    return campaign, base, renderer\n\n\n",
        "            'continuation_module_sha256': sha(Path(continuation.__file__)),\n":
            "            'continuation_module_sha256': sha(Path(continuation.__file__)),\n"
            "            'validation_topology_sha256': sha(VALIDATION_TOPOLOGY),\n"
            "            'validation_topology': json.loads(VALIDATION_TOPOLOGY.read_text()),\n"
            "            'engine_artifact_manifest_sha256': sha(REPO / 'ENGINE_ARTIFACT.json'),\n",
        "                'direct_runtime': bool(direct_runtime),\n":
            "                'direct_runtime': bool(direct_runtime),\n"
            "                'validation_cost_profile_sha256': sha(Path(VALIDATION_COST_PROFILE)) if VALIDATION_COST_PROFILE else None,\n"
            "                'validation_cost_profile_id': json.loads(Path(VALIDATION_COST_PROFILE).read_text())['profile_sha256'] if VALIDATION_COST_PROFILE else None,\n"
            "                'validation_live_metrics': os.environ.get('COFEE_VALIDATION_LIVE_METRICS') == '1',\n",
        "        if pinned_runtime is None:\n"
        "            cost_profile = base.prepare_cost_profile(life, root / 'cost-profiles',\n"
        "                jar_path='/candidate/SystemDS.jar', jar_sha256=manifest['identity']['jar_sha256'],\n"
        "                classpath='/candidate/SystemDS.jar:/opt/systemds/target/lib/*',\n"
        "                jvm_options=JAVA[1:], config_path='/workspace/experiments/tmp/execution.xml',\n"
        "                local_classpath=f\"{root / 'overlay/SystemDS.jar'}:{args.stage}/systemds/target/lib/*\",\n"
        "                expected_native_blas=getattr(args, 'profile_native_blas', 'mkl'))\n":
            "        if pinned_runtime is None:\n"
            "            if VALIDATION_COST_PROFILE:\n"
            "                profile_path = Path(VALIDATION_COST_PROFILE)\n"
            "                if sha(profile_path) != manifest['identity']['validation_cost_profile_sha256']:\n"
            "                    raise RuntimeError('validation cost profile changed after campaign initialization')\n"
            "                cost_profile = json.loads(profile_path.read_text())\n"
            "                provider = base._cost_profiles()\n"
            "                provider.validate_profile(cost_profile)\n"
            "                current_spec = base.parse_manifest(life)\n"
            "                provider.validate_selection(cost_profile,\n"
            "                    coordinator_host=current_spec.coordinator.host,\n"
            "                    worker_hosts=tuple(worker.host for worker in current_spec.workers),\n"
            "                    image=current_spec.image, network=life['network'])\n"
            "                expected_resources = {'cpuset_cpus': '0-7', 'memory': '24g', 'tmpfs_size': '4g',\n"
            "                    'user': '10041:5500', 'working_dir': '/workspace/experiments'}\n"
            "                if (cost_profile['identity'].get('resources') != expected_resources\n"
            "                        or life.get('container') != expected_resources\n"
            "                        or cost_profile['identity'].get('threads') != 8\n"
            "                        or cost_profile['identity'].get('expected_native_blas') != 'unavailable'):\n"
            "                    raise RuntimeError('validation cost profile resource/JVM/native identity mismatch')\n"
            "                for node in [life['coordinator'], *life['workers']]:\n"
            "                    environment = {key: value for key, value in node['environment'].items()\n"
            "                        if not key.startswith('SYSDS_FED_COST_')}\n"
            "                    environment.update(cost_profile['cost_environment'])\n"
            "                    environment.update({'COFEE_COST_PROFILE_MODE': 'validation-measured-replay',\n"
            "                        'COFEE_COST_PROFILE_SHA256': cost_profile['profile_sha256']})\n"
            "                    node['environment'] = environment\n"
            "                cost_profile = {**cost_profile, '_runtime': {'cache_hit': True,\n"
            "                    'preparation_seconds': 0.0, 'source': 'validation-measured-replay',\n"
            "                    'source_path': str(profile_path), 'source_sha256': sha(profile_path)}}\n"
            "            else:\n"
            "                cost_profile = base.prepare_cost_profile(life, root / 'cost-profiles',\n"
            "                    jar_path='/candidate/SystemDS.jar', jar_sha256=manifest['identity']['jar_sha256'],\n"
            "                    classpath='/candidate/SystemDS.jar:/opt/systemds/target/lib/*',\n"
            "                    jvm_options=JAVA[1:], config_path='/workspace/experiments/tmp/execution.xml',\n"
            "                    local_classpath=f\"{root / 'overlay/SystemDS.jar'}:{args.stage}/systemds/target/lib/*\",\n"
            "                    expected_native_blas=getattr(args, 'profile_native_blas', 'mkl'))\n",
        "                'head': run(['git', '-C', str(REPO), 'rev-parse', 'HEAD']).strip(),\n"
        "                'git_status': run(['git', '-C', str(REPO), 'status', '--short']),\n":
            "                'head': 'compiled-overlay-see-ENGINE_ARTIFACT.json',\n"
            "                'git_status': 'not-applicable-immutable-compiled-overlay',\n",
        "    hosts = ['so007', 'so002', 'so003', 'so004', 'so005', 'so006', 'so008', 'so009']\n":
            "    topology_value = json.loads(VALIDATION_TOPOLOGY.read_text())\n"
            "    hosts = [topology_value['coordinator']['host'], topology_value['workers'][0]['host']]\n",
        "            renderer = renderer_module.Renderer(args.stage)\n":
            "            renderer = renderer_module.Renderer(args.stage, topology=VALIDATION_TOPOLOGY)\n",
        "        validate_probe_receipt(receipt, cell, phase, parsed_timing)\n":
            "        validate_probe_receipt(receipt, cell, phase, parsed_timing)\n"
            "        if (VALIDATION_COST_PROFILE and receipt.get('effectiveCostEnvironment')\n"
            "                != cost_profile['cost_environment']):\n"
            "            raise ValueError('receipt effective cost environment differs from frozen validation vector')\n",
    }
    result = source
    for old, new in replacements.items():
        count = result.count(old)
        if count != 1:
            raise ValueError(f"upstream runner patch anchor count {count}, expected 1: {old[:80]!r}")
        result = result.replace(old, new)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--upstream-runner", type=Path, required=True)
    parser.add_argument("--engine-repo", type=Path, required=True)
    parser.add_argument("--topology", type=Path, required=True)
    parser.add_argument("--generated-runner", type=Path, required=True)
    parser.add_argument("--validation-cost-profile", type=Path)
    parser.add_argument("--live-metrics", action="store_true")
    args, forwarded = parser.parse_known_args(argv)
    topology = validate_topology(args.topology)
    validate_engine_repo(args.engine_repo)
    original = args.upstream_runner.read_text()
    generated = transform(original)
    args.generated_runner.parent.mkdir(parents=True, exist_ok=True)
    args.generated_runner.write_text(generated)
    provenance = {
        "schema": "cofee-w1357-validation-runner-provenance/v1",
        "upstreamRunner": str(args.upstream_runner.resolve()),
        "upstreamRunnerSha256": sha256(args.upstream_runner),
        "generatedRunner": str(args.generated_runner.resolve()),
        "generatedRunnerSha256": sha256(args.generated_runner),
        "engineRepo": str(args.engine_repo.resolve()),
        "engineArtifactManifestSha256": sha256(args.engine_repo / "ENGINE_ARTIFACT.json"),
        "topology": topology,
        "topologySha256": sha256(args.topology),
        "resourceContract": {"containerMemory": "24g", "jvmHeap": "16g",
                             "tmpfs": "4g", "cpuset": "0-7", "activeProcessors": 8},
        "sharedRuntimeLanePreserved": True,
    }
    (args.generated_runner.with_suffix(".provenance.json")).write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n")
    os.environ["COFEE_VALIDATION_ENGINE_REPO"] = str(args.engine_repo.resolve())
    os.environ["COFEE_VALIDATION_TOPOLOGY"] = str(args.topology.resolve())
    if args.validation_cost_profile is not None:
        if not args.validation_cost_profile.is_file():
            raise ValueError("validation cost profile is missing")
        validate_cost_profile(args.validation_cost_profile)
        os.environ["COFEE_VALIDATION_COST_PROFILE"] = str(args.validation_cost_profile.resolve())
    else:
        os.environ.pop("COFEE_VALIDATION_COST_PROFILE", None)
    if args.live_metrics:
        os.environ["COFEE_VALIDATION_LIVE_METRICS"] = "1"
    else:
        os.environ.pop("COFEE_VALIDATION_LIVE_METRICS", None)
    sys.path.insert(0, str(args.upstream_runner.resolve().parent))
    sys.argv = [str(args.generated_runner), *forwarded]
    runpy.run_path(str(args.generated_runner), run_name="__main__")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
