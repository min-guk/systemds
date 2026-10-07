#!/usr/bin/env python3
"""Full production compile survey, then globally gated ordered Docker runtime.

One fresh coordinator JVM and fresh workers per attempt. Immutable inputs/engine,
append-only attempts, no retries unless explicitly selected, no runtime fallback.
Explicit direct runtime may omit the separate compile survey, not runtime audits.
The existing frozen P5 harness is deliberately not imported or modified.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
from dataclasses import replace
from decimal import Decimal
import fcntl
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import matrix_runtime_compare as runtime_compare
import matrix_lifecycle as lifecycle
import matrix_continuation as continuation

REPO = Path(__file__).resolve().parents[2]
EVALUATION = Path('/home/mchoi/cofee-evaluation')
STAGE = Path('/home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd')
IMAGE = 'cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0'
PLANNERS = ('DP-local', 'FedFirst', 'AggLocal', 'DP-global')
PROFILES = ('lan', 'wan_light', 'wan_mid', 'wan_heavy')
WORKERS = (1, 3, 5, 7)
# User policy: neither compilation nor runtime has a workload deadline.
# Administrative connection/setup/cleanup waits are separate from computation.
WORKLOAD_TIMEOUT_SECONDS = None
WORKLOADS = (('ml', 'logreg'), ('ml', 'l2svm'), ('ml', 'pca'), ('ml', 'als'),
             ('ml', 'kmeans'), ('ml', 'lm'), ('ml', 'steplm'), ('ml', 'glm'),
             ('ml', 'gnmf'), ('ml', 'gmm'), ('p1', 'P1_FULL'), ('p2', 'P2_PREP'),
             ('sliceline', 'ADULT'), ('sliceline', 'COVTYPE'))
ENUMS = dict(zip(PLANNERS, ('COMPILE_COST_BASED', 'COMPILE_FED_ALL_MAX_FED_FOUT_SINGLE_PASS',
                          'COMPILE_FED_HEURISTIC_SINGLE_PASS', 'COMPILE_EXACT')))
PROBE = 'org.apache.sysds.test.functions.federated.fedplanning.MatrixCampaignProbe'
PROBE_SOURCE = REPO / ('src/test/java/' + PROBE.replace('.', '/') + '.java')
JAVA = ('java', '--add-modules', 'jdk.incubator.vector', '-Xms16g', '-Xmx16g',
        '-Xmn1600m', '-XX:ActiveProcessorCount=8',
        '-Dsysds.fedplanner.runtime.audit=true', '-Dsysds.fedplanner.phaseMarkers=true',
        '-Dsysds.fedplanner.structuralArena.maxEntries=262144',
        '-Dsysds.fedplanner.structuralArena.maxIdentityEntries=262144',
        '-Dsysds.fedplanner.signatureCache.maxChars=536870912')
JFR_OPTIONS = (
    '-XX:StartFlightRecording=name=matrix_compile,settings=profile,'
    'filename=/workspace/experiments/tmp/compile.jfr,dumponexit=true,maxsize=128m',
    '-XX:FlightRecorderOptions=stackdepth=128',
)
DIAGNOSTIC_TRACE_OPTIONS = ('-Dsysds.fedplanner.trace=true',
                            '-Dsysds.fedplanner.trace.details=false')
DIAGNOSTIC_DETAIL_TRACE_OPTIONS = ('-Dsysds.fedplanner.trace=true',
                                   '-Dsysds.fedplanner.trace.details=true')
DIAGNOSTIC_COMPACT_OPTIONS = ('-Dsysds.fedplanner.regional.compact=true',)
CP = '/candidate/probe/classes:/candidate/SystemDS.jar:/opt/systemds/target/lib/*'
BUILTIN_SCRIPTS = ('steplm.dml', 'lmCG.dml')
PINNED_RUNTIME_SCHEMA = 'w1357-pinned-runtime-contract/v3'
PINNED_RUNTIME_FILE = 'pinned-runtime-contract.json'
PINNED_SOURCE_DIRECTORY = 'pinned-runtime-sources'
JAVA_DECIMAL = re.compile(r'[+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?\Z')
PINNED_COST_KEYS = frozenset({
    'SYSDS_FED_COST_FLOPS', 'SYSDS_FED_COST_AGGBINARY_FLOPS', 'SYSDS_FED_COST_MEM_BW',
    'SYSDS_FED_COST_NET_LATENCY_C2W', 'SYSDS_FED_COST_NET_LATENCY_W2C',
    'SYSDS_FED_COST_NET_BW_C2W', 'SYSDS_FED_COST_NET_BW_W2C',
    'SYSDS_FED_COST_NET_BW_COORD_C2W', 'SYSDS_FED_COST_NET_BW_COORD_W2C',
    'SYSDS_FED_COST_NET_SERDES_BW_C2W', 'SYSDS_FED_COST_NET_SERDES_BW_W2C',
})


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_builtin_sync(jar):
    """Require engine, evaluation, and packaged STEP-LM builtins to be identical."""
    sources = {}
    for name in BUILTIN_SCRIPTS:
        source = REPO / 'scripts/builtin' / name
        canonical = EVALUATION / 'campaign/engine_workflow/scripts/builtin' / name
        if not source.is_file():
            raise RuntimeError(f'source builtin missing: {source}; sync the engine source first')
        if not canonical.is_file():
            raise RuntimeError(f'canonical evaluation builtin missing: {canonical}; '
                               'restore the evaluation snapshot before campaign initialization')
        source_bytes = source.read_bytes()
        if source_bytes != canonical.read_bytes():
            raise RuntimeError(f'source builtin {source} differs from canonical evaluation '
                               f'snapshot {canonical}; sync the engine source before rebuilding')
        sources[name] = source_bytes
    if not Path(jar).is_file():
        raise RuntimeError(f'production JAR missing: {jar}; rebuild the exact synchronized source')
    try:
        with zipfile.ZipFile(jar) as archive:
            for name, source_bytes in sources.items():
                entry = f'scripts/builtin/{name}'
                try:
                    packaged = archive.read(entry)
                except KeyError as error:
                    raise RuntimeError(f'JAR builtin missing: {entry}; rebuild the production JAR '
                                       'from the synchronized source') from error
                if packaged != source_bytes:
                    raise RuntimeError(f'JAR builtin {entry} differs from source bytes; rebuild the '
                                       'production JAR from the synchronized source')
    except zipfile.BadZipFile as error:
        raise RuntimeError(f'malformed production JAR {jar}; rebuild the exact synchronized source') \
            from error


def dump(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    os.replace(tmp, path)


def normalized_image_content(raw):
    """Return the established daemon-independent Docker image content identity."""
    if not isinstance(raw, list) or len(raw) != 1 or not isinstance(raw[0], dict):
        raise ValueError('image inspect must contain exactly one image')
    image = raw[0]
    if (not isinstance(image.get('Id'), str)
            or re.fullmatch(r'sha256:[0-9a-f]{64}', image['Id']) is None
            or any(not isinstance(image.get(key), str) or not image[key]
                   for key in ('Architecture', 'Os', 'Created'))
            or not isinstance(image.get('Config'), dict)
            or not isinstance(image.get('RootFS'), dict)
            or not isinstance(image['RootFS'].get('Layers'), list)
            or not image['RootFS']['Layers']
            or any(not isinstance(layer, str)
                   or re.fullmatch(r'sha256:[0-9a-f]{64}', layer) is None
                   for layer in image['RootFS']['Layers'])):
        raise ValueError('image inspect is missing immutable content fields')
    payload = {key: image[key] for key in ('Architecture', 'Os', 'Created', 'Config')}
    payload['RootFSLayers'] = image['RootFS']['Layers']
    encoded = json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()
    return image['Id'], hashlib.sha256(encoded).hexdigest()


def read_pinned_runtime_contract(path, *, source_root=None):
    raw = Path(path).read_bytes()
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError('pinned runtime contract must be UTF-8 JSON') from error
    if not isinstance(value, dict) or set(value) != {
            'schema', 'image', 'runtime_image', 'cost_environment', 'config',
            'resources', 'provenance'}:
        raise ValueError('pinned runtime contract keys are not exact')
    if value['schema'] != PINNED_RUNTIME_SCHEMA:
        raise ValueError('invalid pinned runtime contract schema')
    if not isinstance(value['image'], str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', value['image']):
        raise ValueError('pinned runtime image must be an immutable Docker digest')
    if (not isinstance(value['runtime_image'], str)
            or re.fullmatch(r'cofee-experiment:content-[0-9a-f]{64}', value['runtime_image']) is None):
        raise ValueError('pinned runtime image must have one canonical content tag')
    cost = value['cost_environment']
    if (not isinstance(cost, dict) or set(cost) != PINNED_COST_KEYS
            or any(not isinstance(item, str) or not item for item in cost.values())):
        raise ValueError('pinned runtime cost environment is not the complete string vector')
    for name, item in cost.items():
        if JAVA_DECIMAL.fullmatch(item) is None:
            raise ValueError(f'invalid pinned runtime cost value: {name}')
        try:
            parsed = float(item)
        except ValueError as error:
            raise ValueError(f'invalid pinned runtime cost value: {name}') from error
        if not parsed > 0 or parsed == float('inf'):
            raise ValueError(f'invalid pinned runtime cost value: {name}')
    config_value = value['config']
    if (not isinstance(config_value, dict) or set(config_value) != {
            'sysds.native.blas', 'sysds.codegen.enabled'}
            or config_value['sysds.native.blas'] not in ('none', 'mkl', 'openblas')
            or config_value['sysds.codegen.enabled'] not in ('true', 'false')):
        raise ValueError('invalid pinned runtime SystemDS configuration')
    resources = value['resources']
    if (not isinstance(resources, dict) or set(resources) != {
            'container_cpus', 'container_memory', 'jvm_max_heap',
            'active_processor_count'}
            or not isinstance(resources['active_processor_count'], int)
            or isinstance(resources['active_processor_count'], bool)
            or resources['active_processor_count'] <= 0
            or not isinstance(resources['container_cpus'], str)
            or JAVA_DECIMAL.fullmatch(resources['container_cpus']) is None
            or not 0 < float(resources['container_cpus']) < float('inf')
            or not isinstance(resources['container_memory'], str)
            or not re.fullmatch(r'[1-9][0-9]*[kKmMgG]', resources['container_memory'])
            or not isinstance(resources['jvm_max_heap'], str)
            or not re.fullmatch(r'[1-9][0-9]*[kKmMgG]', resources['jvm_max_heap'])):
        raise ValueError('invalid pinned runtime resource contract')
    provenance = value['provenance']
    provenance_keys = {'cost_environment_source', 'cost_environment_sha256',
        'configuration_source', 'configuration_sha256', 'command_source', 'command_sha256',
        'image_inspect_source', 'image_inspect_sha256'}
    if (not isinstance(provenance, dict) or set(provenance) != provenance_keys
            or any(not isinstance(provenance[name], str) or not provenance[name]
                   for name in provenance_keys)
            or any(not re.fullmatch(r'[0-9a-f]{64}', provenance[name])
                   for name in provenance_keys if name.endswith('_sha256'))):
        raise ValueError('pinned runtime provenance is not the exact hashed source set')
    pinned_runtime_sources(value, source_root=source_root)
    return raw, value, hashlib.sha256(raw).hexdigest()


def pinned_runtime_sources(contract, *, source_root=None):
    """Verify bytes and values, not merely a caller's claimed provenance hashes."""
    provenance = contract['provenance']
    sources = {}
    for name in ('cost_environment', 'configuration', 'command', 'image_inspect'):
        path = (Path(provenance[name + '_source']) if source_root is None
                else Path(source_root) / name)
        try:
            raw = path.read_bytes()
        except OSError as error:
            raise ValueError('pinned runtime provenance source unavailable: ' + name) from error
        if hashlib.sha256(raw).hexdigest() != provenance[name + '_sha256']:
            raise ValueError('pinned runtime provenance hash mismatch: ' + name)
        sources[name] = raw
    try:
        cost_source = json.loads(sources['cost_environment'])
        command = json.loads(sources['command'])
        source_image_id, content_sha256 = normalized_image_content(
            json.loads(sources['image_inspect']))
        if command['image'] != contract['image'] or source_image_id != contract['image']:
            raise ValueError('Docker image differs')
        if contract['runtime_image'] != 'cofee-experiment:content-' + content_sha256:
            raise ValueError('runtime Docker image content tag differs')
        config_source = ET.fromstring(sources['configuration'])
        def costs(environment):
            return {key: item for key, item in environment.items() if key.startswith('SYSDS_FED_COST_')}
        if (costs(cost_source) != contract['cost_environment']
                or costs(command['environment']) != contract['cost_environment']):
            raise ValueError('cost vector differs')
        for key, expected in contract['config'].items():
            entries = config_source.findall(key)
            if len(entries) != 1 or entries[0].text != expected:
                raise ValueError('SystemDS config differs: ' + key)
        docker, java = command['docker_argv'], command['java_argv']
        def option(argv, key):
            if argv.count(key) != 1:
                raise ValueError('missing/duplicate option: ' + key)
            return argv[argv.index(key) + 1]
        def prefix(prefix):
            matches = [item[len(prefix):] for item in java if item.startswith(prefix)]
            if len(matches) != 1:
                raise ValueError('missing/duplicate JVM option: ' + prefix)
            return matches[0]
        resources = contract['resources']
        if (option(docker, '--cpus') != resources['container_cpus']
                or any(item.startswith(('--cpuset-', '--cpu-quota', '--cpu-period')) for item in docker)
                or option(docker, '--memory') != resources['container_memory']
                or prefix('-Xmx') != resources['jvm_max_heap']
                or prefix('-XX:ActiveProcessorCount=') != str(resources['active_processor_count'])):
            raise ValueError('execution resources differ')
    except (AttributeError, IndexError, KeyError, TypeError, ValueError, ET.ParseError) as error:
        raise ValueError('pinned runtime provenance values mismatch: ' + str(error)) from error
    return sources


def frozen_pinned_runtime_contract(root, manifest):
    expected = manifest.get('identity', {}).get('pinned_runtime_contract_sha256')
    if expected is None:
        return None
    if sha(Path(root) / PINNED_RUNTIME_FILE) != expected:
        raise RuntimeError('frozen pinned runtime contract changed')
    raw, value, observed = read_pinned_runtime_contract(Path(root) / PINNED_RUNTIME_FILE,
        source_root=Path(root) / PINNED_SOURCE_DIRECTORY)
    if observed != expected:
        raise RuntimeError('frozen pinned runtime contract changed')
    return value


def runtime_java(contract=None):
    if contract is None:
        return list(JAVA)
    resources = contract['resources']
    return ['java', '--add-modules', 'jdk.incubator.vector',
            '-Xmx' + resources['jvm_max_heap'],
            '-XX:ActiveProcessorCount=' + str(resources['active_processor_count']),
            '-Dsysds.fedplanner.runtime.audit=true', '-Dsysds.fedplanner.phaseMarkers=true',
            '-Dsysds.fedplanner.structuralArena.maxEntries=262144',
            '-Dsysds.fedplanner.structuralArena.maxIdentityEntries=262144',
            '-Dsysds.fedplanner.signatureCache.maxChars=536870912']


def apply_pinned_runtime_contract(lifecycle_manifest, contract, digest):
    resources = contract['resources']
    lifecycle_manifest['container']['memory'] = resources['container_memory']
    for node in [lifecycle_manifest['coordinator'], *lifecycle_manifest['workers']]:
        environment = {name: item for name, item in node['environment'].items()
                       if not name.startswith('SYSDS_FED_COST_')}
        environment.update(contract['cost_environment'])
        environment['COFEE_COST_PROFILE_MODE'] = 'pinned-replay'
        environment['COFEE_COST_PROFILE_SHA256'] = digest
        node['environment'] = environment


def pinned_runtime_start_plan(plan, contract):
    """Apply explicit quota replay to start commands without changing the external driver."""
    if contract is None:
        return plan
    if plan.action != 'start':
        raise ValueError('pinned runtime resources require a start plan')
    commands = []
    for command in plan.commands:
        if command.label == 'run-coordinator' or re.fullmatch(r'run-worker-[1-9][0-9]*', command.label):
            remote = shlex.split(command.argv[-1])
            if (remote[:2] != ['docker', 'run'] or remote.count('--cpuset-cpus') != 1
                    or '--cpus' in remote or '--cpu-quota' in remote):
                raise ValueError('unexpected Docker resource command for pinned runtime')
            index = remote.index('--cpuset-cpus')
            remote[index:index + 2] = ['--cpus', contract['resources']['container_cpus']]
            command = replace(command, argv=(*command.argv[:-1], shlex.join(remote)))
        commands.append(command)
    return replace(plan, commands=tuple(commands))


def matrix():
    return [dict(id=f'{suite}|{workload}|{profile}|w{workers}|{planner}', suite=suite,
                 workload=workload, profile=profile, workers=workers, planner=planner,
                 planner_enum=ENUMS[planner])
            for planner in PLANNERS for suite, workload in WORKLOADS
            for workers in WORKERS for profile in PROFILES]


def schedule(phase):
    rows = matrix()
    if phase == 'runtime':
        ranks = {pair: i for i, pair in enumerate(WORKLOADS)}
        rows.sort(key=lambda c: (ranks[c['suite'], c['workload']], PLANNERS.index(c['planner']),
                                 WORKERS.index(c['workers']), PROFILES.index(c['profile'])))
    return rows


def dependencies():
    sys.dont_write_bytecode = True
    for path in ('campaign', 'driver', 'driver/tools'):
        sys.path.insert(0, str(EVALUATION / path))
    campaign = importlib.import_module('run_w1357_integrated')
    renderer = importlib.import_module('render_w1357_workload')
    if sorted(matrix(), key=lambda c: c['id']) != sorted(campaign.matrix(), key=lambda c: c['id']):
        raise RuntimeError('canonical matrix differs from the frozen campaign contract')
    return campaign, campaign._base_driver(), renderer


def run(argv, *, timeout=120, input=None):
    result = subprocess.run(argv, input=input, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'command rc={result.returncode}: {shlex.join(map(str, argv))}\n'
                           + result.stdout[-1000:] + result.stderr[-3000:])
    return result.stdout


def ssh(host, argv, **kwargs):
    return run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '--', host,
                shlex.join(map(str, argv))], **kwargs)


def diagnostic_contract(enabled, compact=False, runtime_cell=False, plan_details=False):
    trace_options = (DIAGNOSTIC_DETAIL_TRACE_OPTIONS if plan_details else
                     DIAGNOSTIC_TRACE_OPTIONS if enabled else ())
    return {'diagnostic_jfr': bool(enabled),
            'diagnostic_jfr_options': list(JFR_OPTIONS) if enabled else [],
            'diagnostic_planner_trace_options': list(trace_options),
            'diagnostic_compact': bool(enabled and compact),
            'diagnostic_compact_options': list(DIAGNOSTIC_COMPACT_OPTIONS)
                if enabled and compact else [],
            'diagnostic_runtime_cell': bool(runtime_cell),
            'diagnostic_plan_details': bool(plan_details)}


def read_runtime_selection(path):
    """Select work, never import historical results or change the canonical matrix."""
    raw = Path(path).read_bytes()
    value = json.loads(raw)
    if not isinstance(value, dict) or value.get('schema') != 'w1357-runtime-selection/v1':
        raise ValueError('invalid runtime selection schema')
    ids = value.get('selected_cell_ids')
    if (not isinstance(ids, list) or not ids or any(not isinstance(x, str) for x in ids)
            or len(ids) != len(set(ids)) or not set(ids) <= {c['id'] for c in matrix()}):
        raise ValueError('runtime selection requires nonempty unique canonical cell IDs')
    return raw, set(ids)


def runtime_selection_ids(root, manifest):
    expected = manifest.get('identity', {}).get('runtime_selection_sha256')
    if expected is None:
        return None
    raw, ids = read_runtime_selection(Path(root) / 'runtime-selection.json')
    if hashlib.sha256(raw).hexdigest() != expected:
        raise RuntimeError('frozen runtime selection changed')
    return ids


def initialize(root, stage, diagnostic_jfr=False, diagnostic_compact=False, direct_runtime=False,
               continuation_source=None, diagnostic_runtime_cell=False,
               diagnostic_plan_details=False, runtime_selection=None, profile_native_blas="mkl",
               pinned_runtime_contract=None):
    """Freeze build/probe once. Resumes verify rather than replace artifacts."""
    selection_raw = None
    pinned_raw = pinned_digest = None
    pinned_sources = pinned_value = None
    if pinned_runtime_contract is not None:
        if not direct_runtime:
            raise ValueError('pinned runtime contract requires explicit direct runtime mode')
        frozen_sources = (root / PINNED_SOURCE_DIRECTORY
                          if (root / 'manifest.json').exists() else None)
        pinned_raw, pinned_value, pinned_digest = read_pinned_runtime_contract(
            pinned_runtime_contract, source_root=frozen_sources)
        pinned_sources = pinned_runtime_sources(pinned_value, source_root=frozen_sources)
    if runtime_selection is not None:
        if (not direct_runtime or continuation_source is not None or diagnostic_jfr
                or diagnostic_compact or diagnostic_runtime_cell or diagnostic_plan_details):
            raise ValueError('runtime selection requires exclusive direct runtime mode')
        selection_raw, _ = read_runtime_selection(runtime_selection)
    manifest_path = root / 'manifest.json'
    jar = REPO / 'target/systemds-3.4.0-SNAPSHOT.jar'
    verify_builtin_sync(jar)
    builtin_sources = [REPO / 'scripts/builtin' / name for name in BUILTIN_SCRIPTS]
    files = (sorted(p for p in (REPO / 'src/main').rglob('*') if p.is_file())
             + [REPO / 'pom.xml', *builtin_sources])
    if not jar.is_file() or jar.stat().st_mtime_ns < max(p.stat().st_mtime_ns for p in files):
        raise RuntimeError('production JAR is missing/stale; build the exact source first')
    if not PROBE_SOURCE.is_file():
        raise RuntimeError(f'probe source missing: {PROBE_SOURCE}')
    external = [EVALUATION / x for x in ('campaign/run_w1357_integrated.py',
        'campaign/render_w1357_workload.py', 'driver/run_multihost_campaign_network_quality_v2.py',
        'calibration/experiment_profile.py', 'calibration/experiment_profile_probe.py',
        'calibration/java/BasicRateProbe.java', 'calibration/java/ProbeEnvironment.java',
        'calibration/java/TransportProbe.java',
        'driver/tools/multihost_docker.py', 'config/multihost_topology.json',
        'campaign/w1357_output_compare.py', 'campaign/w1357_metric_producer.py',
        'config/w1357_correctness_contract.json', 'calibration/java/W1357OutputDecoder.java',
        'campaign/generate_w1357_references.py', 'campaign/w1357_reference.py')]
    identity = {'jar_sha256': sha(jar), 'source_sha256': {str(p.relative_to(REPO)): sha(p) for p in files},
                'probe_source_sha256': sha(PROBE_SOURCE), 'runner_sha256': sha(Path(__file__)),
                'runtime_compare_sha256': sha(Path(runtime_compare.__file__)),
                'lifecycle_sha256': sha(Path(lifecycle.__file__)),
                'continuation_module_sha256': sha(Path(continuation.__file__)),
                'external_sha256': {str(p): sha(p) for p in external},
                'stage': str(stage), 'stage_seal_sha256': sha(stage / 'W1357_STAGE.json'),
                'timeout_seconds': dict.fromkeys(('compile', 'runtime'), WORKLOAD_TIMEOUT_SECONDS),
                'direct_runtime': bool(direct_runtime),
                'cost_profiling': ('auto-measured-environment/v1' if pinned_digest is None
                    else 'pinned-runtime-replay/v1'),
                'profile_native_blas': profile_native_blas if pinned_digest is None else 'not-applicable',
                'diagnostic': diagnostic_contract(diagnostic_jfr, diagnostic_compact,
                                                  diagnostic_runtime_cell,
                                                  diagnostic_plan_details)}
    if selection_raw is not None:
        identity['runtime_selection_sha256'] = hashlib.sha256(selection_raw).hexdigest()
        identity['runtime_selection_source'] = str(Path(runtime_selection).resolve())
    if pinned_digest is not None:
        identity['pinned_runtime_contract_sha256'] = pinned_digest
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if 'continuation_sha256' in manifest['identity']:
            identity['continuation_sha256'] = manifest['identity']['continuation_sha256']
            continuation.load_rows(root, manifest)
        if continuation_source is not None:
            snapshot_path = root / 'continuation.json'
            if (not snapshot_path.is_file() or Path(json.loads(snapshot_path.read_text())
                    ['source_root']).resolve() != Path(continuation_source).resolve()):
                raise RuntimeError('campaign continuation source changed: use a new root')
        if manifest['identity'] != identity:
            raise RuntimeError('campaign identity changed: use a new root, never mix revisions')
        runtime_selection_ids(root, manifest)
        frozen_pinned_runtime_contract(root, manifest)
        if manifest.get('measurement', {}).get('timeout_seconds') != identity['timeout_seconds']:
            raise RuntimeError('campaign timeout measurement changed: use a new root, never mix policies')
        if manifest.get('measurement', {}).get('direct_runtime') is not identity['direct_runtime']:
            raise RuntimeError('campaign direct runtime measurement changed: use a new root, never mix policies')
        measurement_diagnostic = {key: manifest.get('measurement', {}).get(key)
                                  for key in identity['diagnostic']}
        if measurement_diagnostic != identity['diagnostic']:
            raise RuntimeError('campaign diagnostic measurement changed: use a new root, never mix policies')
        for name, expected in manifest['overlay_sha256'].items():
            if sha(root / 'overlay' / name) != expected:
                raise RuntimeError(f'frozen overlay changed: {name}')
        return manifest
    root.mkdir(parents=True, exist_ok=True)
    overlay = root / 'overlay'
    classes = overlay / 'probe/classes'
    classes.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(jar, overlay / 'SystemDS.jar')
    command = ['javac', '-encoding', 'UTF-8', '-cp', f'{jar}:{REPO}/target/lib/*',
               '-d', str(classes), str(PROBE_SOURCE)]
    (root / 'probe-javac.log').write_text(run(command, timeout=None))
    manifest = {'schema': 'w1357-four-planner-campaign/v1', 'identity': identity,
                'head': run(['git', '-C', str(REPO), 'rev-parse', 'HEAD']).strip(),
                'git_status': run(['git', '-C', str(REPO), 'status', '--short']),
                'cells': matrix(), 'image': IMAGE if pinned_value is None else pinned_value['runtime_image'], 'remote_root':
                f'/home/mchoi/w1357-policy-matrix/{uuid.uuid4().hex}',
                'overlay_sha256': {str(p.relative_to(overlay)): sha(p)
                                   for p in overlay.rglob('*') if p.is_file()},
                'measurement': {'samples_per_cell': 1, 'warmups': 0, 'fresh_jvm': True,
                    'fresh_workers': True, 'detailed_searchspace_metrics': False,
                    'compile_is_full_production_pipeline': True, 'runtime_audit': True,
                    'parallel_setup_only': False, 'parallel_untimed_only': True,
                    'concurrent_timed_cells': 1,
                    'timeout_seconds': dict(identity['timeout_seconds']),
                    'direct_runtime': identity['direct_runtime'],
                    'pinned_runtime_contract_sha256': pinned_digest,
                    'timeout_semantics': 'no workload deadline',
                    'runtime_order': [list(x) for x in WORKLOADS],
                    **diagnostic_contract(diagnostic_jfr, diagnostic_compact,
                                          diagnostic_runtime_cell,
                                          diagnostic_plan_details)}}
    if continuation_source is not None:
        if Path(continuation_source).resolve() == root.resolve():
            raise ValueError('continuation source must be a different, stopped campaign root')
        snapshot = continuation.build_snapshot(continuation_source, manifest, Path(__file__),
                                               validate_probe_receipt)
        dump(root / 'continuation.json', snapshot)
        identity['continuation_sha256'] = sha(root / 'continuation.json')
    if selection_raw is not None:
        with (root / 'runtime-selection.json').open('xb') as stream:
            stream.write(selection_raw)
    if pinned_raw is not None:
        with (root / PINNED_RUNTIME_FILE).open('xb') as stream:
            stream.write(pinned_raw)
        source_directory = root / PINNED_SOURCE_DIRECTORY
        source_directory.mkdir(exist_ok=False)
        for name, raw in pinned_sources.items():
            with (source_directory / name).open('xb') as stream:
                stream.write(raw)
    dump(manifest_path, manifest)
    return manifest


def publish_overlay(host, root, manifest):
    remote = Path(manifest['remote_root']) / 'overlay'
    payload = json.dumps(manifest['overlay_sha256'])
    check = ('import hashlib,json,pathlib,sys; p=pathlib.Path(sys.argv[1]); '
             'expected=json.loads(sys.argv[2]); '
             'actual={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() '
             'for f in p.rglob("*") if f.is_file()}; '
             'assert actual==expected,(actual,expected); print("VERIFIED")')
    exists = ssh(host, ['bash', '-lc', f'if test -e {shlex.quote(str(remote))}; then echo yes; else echo no; fi']).strip()
    if exists == 'no':
        temp = remote.with_name('overlay-upload-' + uuid.uuid4().hex)
        ssh(host, ['mkdir', '-p', str(temp)])
        run(['rsync', '-a', '--', str(root / 'overlay') + '/', f'{host}:{temp}/'])
        ssh(host, ['bash', '-lc', f'test ! -e {shlex.quote(str(remote))} && mv -T -- '
                                  f'{shlex.quote(str(temp))} {shlex.quote(str(remote))}'])
    elif exists != 'yes':
        raise RuntimeError('invalid overlay existence response')
    return ssh(host, ['python3', '-c', check, str(remote), payload]).strip()


def config(cell, phase, pinned_runtime=None):
    root = ET.Element('root')
    values = {'sysds.native.blas': 'mkl', 'sysds.local.spark': 'true',
              'sysds.federated.planner': cell['planner_enum'],
              'sysds.federated.timeout': '-1',
              'sysds.benchmark.compile_only': str(phase == 'compile').lower(),
              'sysds.localtmpdir': '/tmp/w1357-systemds/local',
              'sysds.scratch': '/tmp/w1357-systemds/scratch'}
    if pinned_runtime is not None:
        values.update(pinned_runtime['config'])
    for name, value in values.items():
        ET.SubElement(root, name).text = value
    return ET.tostring(root, encoding='unicode') + '\n'


def latest(root, phase):
    result = continuation.load_rows(root) if phase == 'runtime' else {}
    for path in sorted((root / 'attempts' / phase).glob('*/result.json')):
        row = json.loads(path.read_text())
        result[row['cell']['id']] = row
    return result


def compile_gate(root):
    manifest_path = Path(root) / 'manifest.json'
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text())
        if (manifest.get('measurement', {}).get('diagnostic_jfr') is True
                or manifest.get('measurement', {}).get('diagnostic_compact') is True
                or manifest.get('measurement', {}).get('diagnostic_runtime_cell') is True
                or manifest.get('identity', {}).get('diagnostic', {}).get('diagnostic_jfr') is True
                or manifest.get('identity', {}).get('diagnostic', {}).get('diagnostic_compact') is True
                or manifest.get('identity', {}).get('diagnostic', {}).get('diagnostic_runtime_cell') is True):
            return False
    rows = latest(root, 'compile')
    return len(rows) == len(matrix()) and all(
        rows.get(c['id'], {}).get('status') == 'passed'
        and 'timeout_seconds' in rows[c['id']]
        and rows[c['id']].get('timeout_seconds') == WORKLOAD_TIMEOUT_SECONDS
        and not rows[c['id']].get('diagnostic_only') for c in matrix())


def coordinator_java(cell, phase, diagnostic_jfr=False, diagnostic_compact=False,
                     diagnostic_plan_details=False, pinned_runtime=None):
    if diagnostic_compact and not diagnostic_jfr:
        raise ValueError('diagnostic compact requires diagnostic JFR')
    java = runtime_java(pinned_runtime)
    if diagnostic_jfr:
        java.extend(JFR_OPTIONS)
        java.extend(DIAGNOSTIC_DETAIL_TRACE_OPTIONS if diagnostic_plan_details
                    else DIAGNOSTIC_TRACE_OPTIONS)
        if diagnostic_compact:
            java.extend(DIAGNOSTIC_COMPACT_OPTIONS)
    elif diagnostic_plan_details:
        java.extend(DIAGNOSTIC_DETAIL_TRACE_OPTIONS)
    if cell['suite'] == 'p2':
        java.append('-Dsysds.privacy.allowPublicRecodeMetadata=true')
    java += ['-cp', CP, PROBE, '--mode', phase, '--script', 'tmp/cell.dml',
             '--config', 'tmp/execution.xml', '--planner', cell['planner_enum'],
             '--receipt', 'tmp/receipt.json', '--seed', '1011081480']
    return java


def collect_diagnostic_jfr(host, remote, local):
    destination = Path(local) / 'compile.jfr'
    run(['rsync', '-a', '--', f'{host}:{Path(remote) / "tmp/compile.jfr"}', str(destination)])
    if not destination.is_file() or destination.stat().st_size <= 0:
        raise RuntimeError('collected diagnostic JFR is missing or empty')
    return {'path': str(destination), 'sha256': sha(destination),
            'size_bytes': destination.stat().st_size}


def record_diagnostic_jfr(result, host, remote, local):
    try:
        result['diagnostic_jfr'] = collect_diagnostic_jfr(host, remote, local)
    except Exception as error:
        message = 'diagnostic JFR collection: ' + str(error)
        result.setdefault('diagnostic_collection_errors', []).append(message)
        # Preserve the production failure as the primary error.  A missing JFR
        # is fatal only when the diagnostic compile itself otherwise succeeded.
        if not result['errors']:
            result['errors'].append(message)


def timing(log):
    lines = re.findall(r'^CandidateE2EReceipt (.+)$', log, re.M)
    if len(lines) != 1:
        raise ValueError(f'expected one aggregate CandidateE2EReceipt, observed {len(lines)}')
    phases = {k: int(v) for k, v in re.findall(r'(\w+Nanos)=(\d+)', lines[0])}
    if not phases or sum(v for k, v in phases.items() if k != 'totalNanos') != phases.get('totalNanos'):
        raise ValueError('candidate exclusive phases do not reconcile')
    compile_seconds = re.findall(r'Total compilation time:\s*([0-9.]+) sec', log)
    if len(compile_seconds) != 1:
        raise ValueError('missing/duplicate total compile timing')
    return {'compile_seconds': float(compile_seconds[0]), 'candidate_phases_ns': phases,
            'analysis_seconds': phases['analysisNanos'] / 1e9,
            'common_preparation_seconds': phases['commonPreparationNanos'] / 1e9,
            'searchspace_seconds': (phases['commonPreparationNanos'] + phases['analysisNanos']) / 1e9,
            'planning_after_analysis_seconds': (phases['totalNanos'] - phases['analysisNanos']
                                                - phases['commonPreparationNanos']) / 1e9,
            'selection_adapter_seconds': sum(phases[k] for k in ('plannerSetupNanos', 'modelNanos',
                'costSurfaceNanos', 'optimizerNanos', 'selectionNanos', 'otherPlanningNanos')) / 1e9}


def validate_probe_receipt(receipt, cell, phase, parsed_timing):
    if (receipt.get('status') != 'success'
            or receipt.get('actualPlannerCanonical') != cell['planner_enum']
            or receipt.get('actualCompileOnly') != (phase == 'compile')
            or receipt.get('runtimeProgramConstructed') is not True
            or receipt.get('workloadExecutionStarted') != (phase == 'runtime')
            or (phase == 'compile' and receipt.get('executionNanos') != 0)):
        raise ValueError('probe receipt planner/compile/runtime evidence mismatch')
    audit = receipt.get('plannerRuntimeAudit', {})
    if (receipt.get('runtimeAuditEnabled') is not True
            or any(audit.get(k) != 0 for k in ('mismatches', 'missingPhysicalHops', 'missingSynthetic'))
            or audit.get('plannedPhysicalHops', 0) <= 0
            or audit.get('loweredPhysicalHops') != audit.get('plannedPhysicalHops')):
        raise ValueError('probe runtime audit disabled or incomplete/mismatched lowering')
    if phase == 'compile' and (receipt.get('observedRunNanos') != 0
            or any(audit.get(k) != 0 for k in ('runtimeInstructionKinds', 'federatedDispatchKinds', 'workerFragmentKinds'))):
        raise ValueError('compile-only probe observed workload execution')
    if phase == 'runtime' and (receipt.get('workloadExecutionCompleted') is not True
            or audit.get('runtimeInstructionKinds', 0) <= 0):
        raise ValueError('runtime probe lacks completed audited execution')
    candidate = {k: v for k, v in receipt.get('candidateE2E', {}).items() if k.endswith('Nanos')}
    if candidate != parsed_timing['candidate_phases_ns']:
        raise ValueError('probe and log candidate timings differ')
    if abs(receipt['compileNanos'] / 1e9 - parsed_timing['compile_seconds']) > 0.000000501:
        raise ValueError('probe and rounded log total compile timings differ')
    if not 0 < receipt.get('planningFullInitialNanos', 0) <= receipt['compileNanos']:
        raise ValueError('full initial planning timing outside total compile timer')


def validate_pinned_runtime_receipt(receipt, contract, script_sha256, config_sha256):
    if (receipt.get('scriptSha256') != script_sha256
            or receipt.get('configSha256') != config_sha256
            or receipt.get('effectiveCostEnvironment') != contract['cost_environment']):
        raise ValueError('pinned runtime executed input/cost binding mismatch')
    settings = receipt.get('executionSettings', {})
    resources = contract['resources']
    java = settings.get('jvmArguments', [])
    heap = [item[4:] for item in java if isinstance(item, str) and item.startswith('-Xmx')]
    cpu = [item.split('=', 1)[1] for item in java
           if isinstance(item, str) and item.startswith('-XX:ActiveProcessorCount=')]
    if (any(settings.get(key) != item for key, item in contract['config'].items())
            or heap != [resources['jvm_max_heap']]
            or cpu != [str(resources['active_processor_count'])]
            or settings.get('availableProcessors') != resources['active_processor_count']
            or type(settings.get('maxHeapSizeBytes')) is not int
            or settings['maxHeapSizeBytes'] != docker_memory_bytes(resources['jvm_max_heap'])
            or not isinstance(settings.get('maxHeapBytes'), int)
            or not 0 < settings['maxHeapBytes'] <= docker_memory_bytes(resources['jvm_max_heap'])):
        raise ValueError('pinned runtime observed configuration/JVM settings mismatch')
    generations = receipt.get('plannerAuthorityGenerations')
    if not isinstance(generations, list) or not generations:
        raise ValueError('pinned runtime initial planner authority is missing')
    fingerprints = []
    for index, row in enumerate(generations):
        if (not isinstance(row, dict) or type(row.get('sequence')) is not int or row['sequence'] != index
                or any(not isinstance(row.get(key), str) or not re.fullmatch(r'[0-9a-f]{64}', row[key])
                       for key in ('planFingerprint', 'analysisFingerprint'))):
            raise ValueError('pinned runtime planner generation receipt is malformed')
        fingerprints.append(row['planFingerprint'])
    audit = receipt.get('plannerRuntimeAudit', {})
    if (receipt.get('initialSelectionFingerprint') != fingerprints[0]
            or receipt.get('finalSelectionFingerprint') != fingerprints[-1]
            or audit.get('plan') != fingerprints[-1]
            or type(audit.get('authorityGenerations')) is not int
            or audit.get('authorityGenerations') != len(set(fingerprints))):
        raise ValueError('pinned runtime initial/final authority linkage mismatch')


def docker_memory_bytes(token):
    return int(token[:-1]) * (1024 ** {'k': 1, 'm': 2, 'g': 3}[token[-1].lower()])


def verify_campaign_images(campaign, hosts, manifest):
    image = manifest.get('image', IMAGE)
    return campaign.verify_remote_image_content(hosts, image)


def pinned_container_resources(spec, nodes, contract):
    """Observe enforced quota, memory and environment before starting the timed JVM."""
    resources = contract['resources']
    nano_cpus = Decimal(resources['container_cpus']) * 1_000_000_000
    if nano_cpus != int(nano_cpus) or nano_cpus <= 0:
        raise ValueError('pinned runtime CPU quota cannot be represented by Docker NanoCpus')
    def inspect(node):
        name = spec.container_name(node)
        records = json.loads(ssh(node.host, ['docker', 'inspect', '--', name]))
        if len(records) != 1:
            raise ValueError('pinned runtime container identity is ambiguous')
        record = records[0]
        actual = record['HostConfig']
        environment = dict(item.split('=', 1) for item in record['Config']['Env'] if '=' in item)
        image_inspect = json.loads(ssh(node.host,
            ['docker', 'image', 'inspect', contract['runtime_image']]))
        actual_image_id, content_sha256 = normalized_image_content(image_inspect)
        expected_content = contract['runtime_image'].removeprefix('cofee-experiment:content-')
        if (record['Name'].lstrip('/') != name or actual.get('NanoCpus') != int(nano_cpus)
                or actual.get('CpusetCpus', '') != ''
                or actual['Memory'] != docker_memory_bytes(resources['container_memory'])
                or record['Image'] != actual_image_id or content_sha256 != expected_content
                or any(environment.get(key) != item for key, item in contract['cost_environment'].items())):
            raise ValueError('pinned runtime observed container resources/environment mismatch: ' + node.host)
        return {'host': node.host, 'container': name, 'id': record['Id'],
                'actualImageId': actual_image_id, 'contentSha256': content_sha256,
                'nanoCpus': actual['NanoCpus'], 'cpusetCpus': actual.get('CpusetCpus', ''),
                'memoryBytes': actual['Memory'], 'costEnvironment': {
                    key: environment[key] for key in contract['cost_environment']}}
    return lifecycle.prepare_nodes(nodes, inspect)


def capture_container_health(spec, node):
    """Untimed pre-cleanup evidence, including OOM of a docker-exec child JVM."""
    name = spec.container_name(node)
    record = json.loads(ssh(node.host, ['docker', 'inspect', '--', name]))[0]
    container_id = record['Id']
    health = {'container': name, 'id': container_id, 'state': record['State'],
              'memory_limit': record['HostConfig']['Memory']}
    # OOMKilled on the init process alone does not describe a killed exec child.
    until = ssh(node.host, ['date', '-u', '+%Y-%m-%dT%H:%M:%S.%NZ']).strip()
    raw = ssh(node.host, ['docker', 'events', '--since', record['State']['StartedAt'],
        '--until', until, '--filter', 'container=' + container_id,
        '--format', '{{json .}}'], timeout=30)
    health['events'] = [json.loads(line) for line in raw.splitlines() if line.strip()]
    health['oom_events'] = [event for event in health['events'] if event.get('Action') == 'oom']
    if record['State']['Running']:
        health['memory_cgroup_raw'] = ssh(node.host, ['docker', 'exec', container_id,
            'sh', '-c', 'for f in /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.events.local '
            '/sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak '
            '/sys/fs/cgroup/memory.max /sys/fs/cgroup/memory/memory.failcnt '
            '/sys/fs/cgroup/memory/memory.max_usage_in_bytes; do '
            'if test -r "$f"; then echo "FILE=$f"; cat "$f"; fi; done'])
    return health


def collect_node_evidence(local, spec, nodes):
    """Collect independent untimed host evidence, draining all before cleanup."""
    def collect(node):
        evidence = {'errors': [], 'health_collection_errors': [], 'log_collection_errors': []}
        try:
            health = capture_container_health(spec, node)
            dump(local / f'{node.host}-container-health.json', health)
            if health['state']['OOMKilled'] or health['oom_events']:
                evidence['errors'].append(f'container OOM observed: {node.host}')
        except Exception as error:
            evidence['health_collection_errors'].append(str(error))
        try:
            (local / f'{node.host}-container.log').write_text(ssh(node.host,
                ['docker', 'logs', '--tail', '3000', spec.container_name(node)]))
        except Exception as error:
            evidence['log_collection_errors'].append(str(error))
        return evidence

    merged = {'errors': [], 'health_collection_errors': [], 'log_collection_errors': []}
    for evidence in lifecycle.prepare_nodes(nodes, collect):
        for key in merged:
            merged[key].extend(evidence[key])
    return merged


def execute_cell(root, manifest, cell, phase, args, campaign, base, renderer, reference_manifest=None):
    cell_started = time.monotonic()
    token = f'{time.time_ns():020d}-{uuid.uuid4().hex[:8]}'
    local = root / 'attempts' / phase / token
    local.mkdir(parents=True)
    remote = Path(manifest['remote_root']) / 'attempts' / phase / token
    rendered = renderer.render(cell)
    pinned_runtime = frozen_pinned_runtime_contract(root, manifest)
    java_options = runtime_java(pinned_runtime)
    (local / 'cell.dml').write_text(rendered['source'])
    (local / 'execution.xml').write_text(config(cell, phase, pinned_runtime))
    dump(local / 'render-contract.json', rendered)
    execution_image = manifest.get('image', IMAGE) if pinned_runtime is None else pinned_runtime['runtime_image']
    life = campaign.bounded_pilot_lifecycle(cell, args.stage, remote, image=execution_image)
    if pinned_runtime is not None:
        apply_pinned_runtime_contract(life, pinned_runtime,
            manifest['identity']['pinned_runtime_contract_sha256'])
    life['mounts'].append({'source': str(Path(manifest['remote_root']) / 'overlay'),
                           'target': '/candidate', 'read_only': True})
    for node in [life['coordinator'], *life['workers']]:
        node['environment']['BENCHMARK_COMPILE_ONLY'] = '1' if phase == 'compile' else '0'
        node['environment']['BENCHMARK_RUNTIME_PLAN_AUDIT'] = '1'
        node['environment']['SYSTEMDS_STANDALONE_OPTS'] = shlex.join(java_options[3:])
        node['environment']['SYSTEMDS_JAR_SHA256'] = manifest['identity']['jar_sha256']
        if 'port' in node:
            node['command'] = ['bash', '-lc', 'set -euo pipefail; mkdir -p /tmp/w1357-systemds/local '
                '/tmp/w1357-systemds/scratch; exec ' + shlex.join([*java_options, '-cp', CP,
                    'org.apache.sysds.api.DMLScript', '-w', str(node['port']),
                    '-config', '/workspace/experiments/tmp/execution.xml'])]
    spec = base.parse_manifest(life)
    dump(local / 'lifecycle.json', life)
    result = {'schema': 'w1357-matrix-attempt/v1', 'cell': cell, 'phase': phase,
              'attempt': token, 'status': 'failed', 'errors': [], 'remote': str(remote),
              'diagnostic_only': bool(getattr(args, 'diagnostic_jfr', False)
                                      or getattr(args, 'diagnostic_runtime_cell', False)),
              'diagnostic_runtime_cell': bool(getattr(args, 'diagnostic_runtime_cell', False)),
              'diagnostic_plan_details': bool(getattr(args, 'diagnostic_plan_details', False)),
              'diagnostic_compact': bool(getattr(args, 'diagnostic_compact', False)),
              'jar_sha256': manifest['identity']['jar_sha256'],
              'pinned_runtime_contract_sha256': manifest['identity'].get(
                  'pinned_runtime_contract_sha256'),
              'timeout_seconds': WORKLOAD_TIMEOUT_SECONDS}
    nodes = [spec.coordinator, *spec.workers]
    start_attempted = False
    before = None
    try:
        def check_absent(node):
            names = ssh(node.host, ['docker', 'ps', '-a', '--filter',
                f'name=^/{spec.container_name(node)}$', '--format', '{{.ID}}']).strip()
            if names:
                raise RuntimeError('same-ID container exists; refusing to replace it')
        lifecycle.prepare_nodes(nodes, check_absent)
        base.prepare_remote_directories(spec, remote)
        resource = campaign.remote_resource_preflight([n.host for n in nodes], remote, spec.coordinator.host)
        dump(local / 'resource-preflight.json', resource)
        if not resource['passed']:
            raise RuntimeError('host resource preflight failed')
        def copy_input(node):
            run(['rsync', '-a', '--', str(local / 'cell.dml'), str(local / 'execution.xml'),
                 f'{node.host}:{remote}/tmp/'])
        lifecycle.prepare_nodes(nodes, copy_input)
        if pinned_runtime is None:
            cost_profile = base.prepare_cost_profile(life, root / 'cost-profiles',
                jar_path='/candidate/SystemDS.jar', jar_sha256=manifest['identity']['jar_sha256'],
                classpath='/candidate/SystemDS.jar:/opt/systemds/target/lib/*',
                jvm_options=JAVA[1:], config_path='/workspace/experiments/tmp/execution.xml',
                local_classpath=f"{root / 'overlay/SystemDS.jar'}:{args.stage}/systemds/target/lib/*",
                expected_native_blas=getattr(args, 'profile_native_blas', 'mkl'))
        else:
            cost_profile = {'schema': 'pinned-runtime-replay/v1',
                'profile_sha256': manifest['identity']['pinned_runtime_contract_sha256'],
                'cost_environment': dict(pinned_runtime['cost_environment']),
                'provenance': dict(pinned_runtime['provenance']),
                '_runtime': {'cache_hit': True, 'preparation_seconds': 0.0,
                    'source': 'explicit-pinned-runtime-contract'}}
        spec = base.parse_manifest(life)
        dump(local / 'lifecycle.json', life)
        dump(local / 'cost-profile.json', cost_profile)
        result['cost_profile_sha256'] = cost_profile['profile_sha256']
        result['cost_profile_preparation'] = cost_profile.get('_runtime')
        result['profiling_seconds'] = cost_profile.get('_runtime', {}).get('preparation_seconds', 0.0)
        start_attempted = True
        def start_command(command):
            (local / (command.label + '.log')).write_text(run(command.argv, timeout=150))
        start_plan = pinned_runtime_start_plan(base.build_plan(spec, 'start'), pinned_runtime)
        if pinned_runtime is not None:
            dump(local / 'pinned-start-plan.json', start_plan.as_dict())
        lifecycle.execute_start(start_plan, start_command)
        if pinned_runtime is not None:
            observed = pinned_container_resources(spec, nodes, pinned_runtime)
            dump(local / 'pinned-container-resources.json', observed)
            result['pinned_container_resources'] = observed
        before = base.capture_network_snapshot(spec)
        dump(local / 'netem-before.json', before)
        java = coordinator_java(cell, phase, getattr(args, 'diagnostic_jfr', False),
                                getattr(args, 'diagnostic_compact', False),
                                getattr(args, 'diagnostic_plan_details', False), pinned_runtime)
        command = ['ssh', '-o', 'BatchMode=yes', '--', spec.coordinator.host,
            shlex.join(['docker', 'exec', spec.container_name(spec.coordinator), *java])]
        dump(local / 'command.json', command)
        started = time.monotonic()
        result['setup_seconds'] = started - cell_started
        with (local / 'coordinator.log').open('x') as output:
            completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                timeout=None)
        result['process_seconds'] = time.monotonic() - started
        result['returncode'] = completed.returncode
        raw = ssh(spec.coordinator.host, ['bash', '-lc',
            f'if test -f {remote}/tmp/receipt.json; then cat {remote}/tmp/receipt.json; else echo null; fi'])
        receipt = json.loads(raw)
        dump(local / 'receipt.json', receipt)
        result['receipt'] = receipt
        if completed.returncode or not receipt or receipt.get('status') != 'success':
            raise RuntimeError(f'production {phase} failed, rc={completed.returncode}; inspect coordinator.log')
        parsed_timing = timing((local / 'coordinator.log').read_text())
        validate_probe_receipt(receipt, cell, phase, parsed_timing)
        if pinned_runtime is not None:
            validate_pinned_runtime_receipt(receipt, pinned_runtime,
                sha(local / 'cell.dml'), sha(local / 'execution.xml'))
        result.update(parsed_timing)
        result['full_initial_planning_seconds'] = receipt['planningFullInitialNanos'] / 1e9
        if phase == 'runtime':
            result['runtime_seconds'] = receipt['executionNanos'] / 1e9
            outputs = [{'path': p['path'], 'format': p['format']} for p in rendered['outputs']]
            result['output_identity'] = campaign.capture_remote_output_identity(spec.coordinator.host, remote, outputs)
            comparison = runtime_compare.compare(campaign, cell, token, args.stage, remote,
                local / 'comparison.json', reference_manifest, execution_image)
            result['comparison'] = comparison
            if comparison['receipt'].get('passed') is not True:
                raise RuntimeError('numerical output comparison failed')
            bindings = comparison['receipt']['metrics']['artifact_bindings']['actual']
            for output in rendered['outputs']:
                actual, expected = bindings[output['role']], result['output_identity'][output['path']]
                if (actual['campaign_sha256'] != expected['sha256']
                        or actual['metadata_sha256'] != expected['metadata_sha256']):
                    raise RuntimeError('runtime output changed between capture and numerical comparison')
        result['status'] = 'passed'
    except Exception as error:
        result['errors'].append(str(error))
        (local / 'exception.txt').write_text(traceback.format_exc())
    finally:
        if start_attempted:
            if getattr(args, 'diagnostic_jfr', False):
                record_diagnostic_jfr(result, spec.coordinator.host, remote, local)
            # Keep this independent of network collection: a failed/terminated
            # container can invalidate netem evidence but still explain a kill.
            try:
                for key, errors in collect_node_evidence(local, spec, nodes).items():
                    if errors:
                        result.setdefault(key, []).extend(errors)
            except Exception as error:
                result['errors'].append('host evidence: ' + str(error))
            try:
                after = base.capture_network_snapshot(spec)
                dump(local / 'netem-after.json', after)
                if before is not None:
                    network = base.validate_network_quality(spec, before, after)
                    dump(local / 'netem-validation.json', network)
                    if network.get('valid') is not True:
                        result['errors'].append('network quality invalid')
            except Exception as error:
                result['errors'].append('network evidence: ' + str(error))
            try:
                cleanup = campaign._strict_experiment_cleanup(base, spec)
                dump(local / 'cleanup.json', cleanup)
                result['cleanup_resolved'] = cleanup['resolved']
            except Exception as error:
                result['errors'].append('cleanup unresolved: ' + str(error))
                result['cleanup_resolved'] = False
        if result['errors']:
            result['status'] = 'failed'
        result['cell_wall_seconds'] = time.monotonic() - cell_started
        if 'process_seconds' in result:
            result['postprocess_seconds'] = (result['cell_wall_seconds']
                - result['setup_seconds'] - result['process_seconds'])
        dump(local / 'result.json', result)
    print(json.dumps({k: result.get(k) for k in ('cell', 'phase', 'attempt', 'status',
        'compile_seconds', 'searchspace_seconds', 'selection_adapter_seconds', 'runtime_seconds',
        'setup_seconds', 'process_seconds', 'postprocess_seconds', 'cell_wall_seconds', 'errors')}), flush=True)
    return result


def summarize(root):
    summary = {}
    for phase in ('compile', 'runtime'):
        rows = latest(root, phase)
        benchmark_rows = {cell_id: row for cell_id, row in rows.items()
                          if row.get('diagnostic_runtime_cell') is not True}
        summary[phase] = {'passed': sum(r['status'] == 'passed' for r in benchmark_rows.values()),
                          'failed': sum(r['status'] != 'passed' for r in benchmark_rows.values()),
                          'pending': len(matrix()) - len(benchmark_rows)}
        columns = ['id', 'planner', 'suite', 'workload', 'workers', 'profile', 'status',
                   'diagnostic_only', 'diagnostic_runtime_cell', 'diagnostic_plan_details',
                   'diagnostic_compact', 'timeout_seconds',
                   'compile_seconds', 'common_preparation_seconds', 'analysis_seconds',
                   'searchspace_seconds', 'selection_adapter_seconds',
                   'planning_after_analysis_seconds', 'full_initial_planning_seconds',
                   'runtime_seconds', 'attempt', 'setup_seconds', 'profiling_seconds',
                   'cost_profile_sha256', 'process_seconds', 'postprocess_seconds',
                   'cell_wall_seconds', 'origin_root', 'origin_result']
        with (root / f'{phase}-comparison.csv').open('w') as stream:
            writer = csv.DictWriter(stream, fieldnames=columns)
            writer.writeheader()
            for cell in schedule(phase):
                row = benchmark_rows.get(cell['id'], {'status': 'pending'})
                values = {k: cell.get(k, row.get(k, '')) for k in columns}
                if row.get('attempt'):
                    origin = row.get('continuation_origin', {})
                    values['origin_root'] = origin.get('root', str(root))
                    values['origin_result'] = origin.get('result_path',
                        f"attempts/{phase}/{row['attempt']}/result.json")
                if row.get('diagnostic_only'):
                    for key in columns:
                        if key.endswith('_seconds') and key != 'timeout_seconds':
                            values[key] = ''
                writer.writerow(values)
    summary['compile_gate'] = compile_gate(root)
    manifest_path = root / 'manifest.json'
    summary['direct_runtime'] = (json.loads(manifest_path.read_text()).get('identity', {})
                                 .get('direct_runtime', False)) if manifest_path.is_file() else False
    if manifest_path.is_file():
        ids = runtime_selection_ids(root, json.loads(manifest_path.read_text()))
        if ids is not None:
            if not set(benchmark_rows) <= ids:
                raise RuntimeError('unselected runtime result in partial campaign')
            summary['runtime_selection'] = {
                'selected': len(ids), 'passed': summary['runtime']['passed'],
                'failed': summary['runtime']['failed'], 'pending': len(ids) - len(benchmark_rows),
                'excluded': len(matrix()) - len(ids)}
    dump(root / 'summary.json', summary)
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--stage', type=Path, default=STAGE)
    parser.add_argument('--phase', choices=('prepare', 'compile', 'runtime', 'all', 'summary'), default='compile')
    parser.add_argument('--reference-manifest', type=Path)
    parser.add_argument('--profile-native-blas', choices=('mkl', 'openblas', 'unavailable'), default='mkl',
                        help='assert the observed backend when profiling; never changes the execution engine')
    parser.add_argument('--continue-runtime-from', type=Path,
                        help='preserve verified completed results from a stopped compatible campaign; '
                             'requires explicit direct runtime and a new immutable root')
    parser.add_argument('--runtime-selection', type=Path,
                        help='immutable explicit runtime subset; historical coverage is not imported')
    parser.add_argument('--pinned-runtime-contract', type=Path,
                        help='explicit immutable cost/config/resource replay contract; direct runtime only')
    parser.add_argument('--max-cells', type=int)
    parser.add_argument('--retry-failed', action='store_true')
    parser.add_argument('--keep-going', action='store_true')
    parser.add_argument('--runtime-without-compile-survey', action='store_true',
                        help='explicit full runtime campaign without a separate compile-only survey; '
                             'actual compilation, runtime audits and numerical comparison remain required')
    parser.add_argument('--diagnostic-jfr', action='store_true',
                        help='single compile-only coordinator JFR/planner-trace diagnostic; never benchmark data')
    parser.add_argument('--diagnostic-compact', action='store_true',
                        help='diagnostic-only regional compact ablation; never a production default')
    parser.add_argument('--diagnostic-runtime-cell', action='store_true',
                        help='single fully selected direct-runtime diagnostic cell; never benchmark data')
    parser.add_argument('--diagnostic-plan-details', action='store_true',
                        help='emit detailed planner trace only in an explicit diagnostic lane')
    for key, options in (('planner', PLANNERS), ('profile', PROFILES), ('workload', tuple(w for _, w in WORKLOADS))):
        parser.add_argument('--' + key, choices=options)
    parser.add_argument('--workers', type=int, choices=WORKERS)
    args = parser.parse_args(argv)
    args.root = args.root.resolve()
    direct_runtime = args.runtime_without_compile_survey
    if direct_runtime and args.phase != 'runtime':
        parser.error('--runtime-without-compile-survey requires --phase runtime')
    if args.continue_runtime_from is not None and not (direct_runtime and args.phase == 'runtime'):
        parser.error('--continue-runtime-from requires --phase runtime --runtime-without-compile-survey')
    if args.pinned_runtime_contract is not None:
        if not (direct_runtime and args.phase == 'runtime'):
            parser.error('--pinned-runtime-contract requires --phase runtime '
                         '--runtime-without-compile-survey')
        frozen_sources = (args.root / PINNED_SOURCE_DIRECTORY
                          if (args.root / 'manifest.json').exists() else None)
        read_pinned_runtime_contract(args.pinned_runtime_contract, source_root=frozen_sources)
    if args.diagnostic_compact and not args.diagnostic_jfr:
        parser.error('--diagnostic-compact requires --diagnostic-jfr')
    if args.diagnostic_jfr and (args.phase != 'compile' or args.max_cells != 1):
        parser.error('--diagnostic-jfr requires --phase compile and --max-cells 1')
    if args.diagnostic_plan_details and not (args.diagnostic_jfr or args.diagnostic_runtime_cell):
        parser.error('--diagnostic-plan-details requires --diagnostic-jfr or --diagnostic-runtime-cell')
    filters = ('planner', 'profile', 'workload', 'workers')
    if args.runtime_selection is not None:
        if (args.phase != 'runtime' or not direct_runtime or args.continue_runtime_from is not None
                or args.retry_failed or args.diagnostic_jfr or args.diagnostic_compact
                or args.diagnostic_runtime_cell or args.diagnostic_plan_details
                or any(getattr(args, key) is not None for key in filters)):
            parser.error('--runtime-selection requires exclusive direct runtime without '
                         'continuation, diagnostics, filters, or retry-failed')
        read_runtime_selection(args.runtime_selection)
    if args.diagnostic_runtime_cell:
        if (args.phase != 'runtime' or not direct_runtime or args.max_cells != 1
                or any(getattr(args, key) is None for key in filters)):
            parser.error('--diagnostic-runtime-cell requires --phase runtime, '
                         '--runtime-without-compile-survey, --max-cells 1, and all cell filters')
        if args.continue_runtime_from is not None:
            parser.error('--diagnostic-runtime-cell cannot use --continue-runtime-from')
        if args.diagnostic_jfr or args.diagnostic_compact:
            parser.error('--diagnostic-runtime-cell cannot use JFR/compact diagnostics')
    if args.phase == 'summary':
        print(json.dumps(summarize(args.root), indent=2))
        return 0
    if args.max_cells is not None and args.max_cells <= 0:
        parser.error('max-cells must be positive')
    if (args.phase in ('runtime', 'all') and not args.diagnostic_runtime_cell
            and any(getattr(args, k) is not None for k in filters)):
        parser.error('runtime/all must preserve the complete schedule; filters are compile-only')
    campaign, base, renderer_module = dependencies()
    continuation_options = ({'continuation_source': args.continue_runtime_from.resolve()}
                            if args.continue_runtime_from is not None else {})
    if args.diagnostic_runtime_cell:
        continuation_options['diagnostic_runtime_cell'] = True
    if args.diagnostic_plan_details:
        continuation_options['diagnostic_plan_details'] = True
    if args.runtime_selection is not None:
        continuation_options['runtime_selection'] = args.runtime_selection.resolve()
    if args.pinned_runtime_contract is not None:
        continuation_options['pinned_runtime_contract'] = args.pinned_runtime_contract.resolve()
    manifest = initialize(args.root, args.stage, args.diagnostic_jfr, args.diagnostic_compact,
                          direct_runtime=direct_runtime, profile_native_blas=args.profile_native_blas, **continuation_options)
    selected_ids = runtime_selection_ids(args.root, manifest)
    if args.phase == 'prepare':
        print(json.dumps({'prepared': True, 'cells': len(matrix()), 'root': str(args.root)}))
        return 0
    if args.phase == 'runtime' and not direct_runtime and not compile_gate(args.root):
        raise RuntimeError('runtime blocked: full same-engine 896-cell compile gate is not passed')
    hosts = ['so007', 'so002', 'so003', 'so004', 'so005', 'so006', 'so008', 'so009']
    leases = []
    count = 0
    attempted_failure = False
    with base.RUNTIME_LANE.open('a+') as lane:
        fcntl.flock(lane, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            leases = campaign.acquire_remote_stage_leases(hosts, args.stage)
            preflight = {'image': verify_campaign_images(campaign, hosts, manifest),
                         'stage': campaign.verify_remote_bounded_stage(hosts, args.stage)}
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                preflight['overlay'] = dict(zip(hosts, pool.map(lambda h: publish_overlay(h, args.root, manifest), hosts)))
            dump(args.root / f'preflight-{time.time_ns()}.json', preflight)
            renderer = renderer_module.Renderer(args.stage)
            for phase in (('compile', 'runtime') if args.phase == 'all' else (args.phase,)):
                if phase == 'runtime' and not direct_runtime and not compile_gate(args.root):
                    raise RuntimeError('runtime blocked: all 896 compile conditions must pass first')
                phase_reference = (runtime_compare.pin_reference(args.root, args.reference_manifest)
                                   if phase == 'runtime' else None)
                previous = latest(args.root, phase)
                for cell in schedule(phase):
                    if selected_ids is not None and cell['id'] not in selected_ids:
                        continue
                    if any(getattr(args, key) is not None and cell[key] != getattr(args, key)
                           for key in ('planner', 'profile', 'workload', 'workers')):
                        continue
                    old = previous.get(cell['id'])
                    if old:
                        if direct_runtime and old.get('cleanup_resolved') is not True:
                            raise RuntimeError('previous cleanup unresolved: no subsequent cell may start')
                        if old['status'] == 'passed':
                            continue
                        if not args.retry_failed:
                            if args.keep_going and (phase == 'compile' or direct_runtime):
                                continue
                            raise RuntimeError('previous failed cell requires diagnosis and --retry-failed: '
                                               + cell['id'])
                    if not campaign.remote_stage_leases_alive(leases):
                        raise RuntimeError('remote stage lease lost')
                    reference_manifest = None
                    if phase == 'runtime':
                        if (cell['suite'], cell['workload']) == ('p2', 'P2_PREP'):
                            if not (args.root / 'reference-p2ref1/reference-pin.json').is_file():
                                # The trusted reference generator owns an exclusive stage lease.
                                # Keep the physical lane, but release shared leases before it starts.
                                released = campaign.release_remote_stage_leases(leases)
                                leases = []
                                dump(args.root / f'p2-reference-lease-handoff-{time.time_ns()}.json', released)
                                if not released['released']:
                                    raise RuntimeError('P2 reference lease handoff was not proven')
                                try:
                                    reference = runtime_compare.prepare_workload_reference(
                                        args.root, args.stage, cell, compile_gate(args.root),
                                        direct_runtime=direct_runtime)
                                finally:
                                    leases = campaign.acquire_remote_stage_leases(hosts, args.stage)
                                    dump(args.root / f'post-reference-stage-{time.time_ns()}.json',
                                         campaign.verify_remote_bounded_stage(hosts, args.stage))
                            else:
                                reference = runtime_compare.prepare_workload_reference(
                                    args.root, args.stage, cell, compile_gate(args.root),
                                    direct_runtime=direct_runtime)
                        else:
                            reference = phase_reference
                        reference_manifest = Path(reference['result_manifest']['local_path'])
                        if not campaign.remote_stage_leases_alive(leases):
                            raise RuntimeError('remote stage lease lost during reference preparation')
                    result = execute_cell(args.root, manifest, cell, phase, args, campaign, base,
                                          renderer, reference_manifest)
                    count += 1
                    attempted_failure |= result['status'] != 'passed'
                    summarize(args.root)
                    if (result.get('cleanup_resolved') is False
                            or (direct_runtime and result.get('cleanup_resolved') is not True)):
                        raise RuntimeError('cleanup unresolved: no subsequent cell may start')
                    if result['status'] != 'passed' and (not args.keep_going
                            or (phase == 'runtime' and not direct_runtime)):
                        return 1
                    if args.max_cells and count >= args.max_cells:
                        if direct_runtime and attempted_failure:
                            return 1
                        return 0 if result['status'] == 'passed' else 1
        finally:
            release = campaign.release_remote_stage_leases(leases) if leases else {'released': True}
            dump(args.root / f'lease-release-{time.time_ns()}.json', release)
            summarize(args.root)
            if not release['released']:
                raise RuntimeError('remote stage lease release unproven')
    status = summarize(args.root)
    print(json.dumps(status, indent=2))
    if selected_ids is not None:
        selected = status['runtime_selection']
        return 0 if selected['passed'] == selected['selected'] else 1
    runtime_complete = status['runtime'] == {'passed': len(matrix()), 'failed': 0, 'pending': 0}
    if direct_runtime:
        return 0 if runtime_complete else 1
    return 0 if status['compile_gate'] and (args.phase == 'compile' or runtime_complete) else 1


if __name__ == '__main__':
    raise SystemExit(main())
