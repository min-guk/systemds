#!/usr/bin/env python3
"""Full production compile survey, then globally gated ordered Docker runtime.

One fresh coordinator JVM and fresh workers per attempt. Immutable inputs/engine,
append-only attempts, no retries unless explicitly selected, no runtime fallback.
The existing frozen P5 harness is deliberately not imported or modified.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import matrix_runtime_compare as runtime_compare
import matrix_lifecycle as lifecycle

REPO = Path(__file__).resolve().parents[2]
EVALUATION = Path('/home/mchoi/cofee-evaluation')
STAGE = Path('/home/mchoi/w1357-stage-privacy-test-only-e21cac2-07726cd')
IMAGE = 'cofee-experiment:content-0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0'
PLANNERS = ('DP-local', 'FedFirst', 'AggLocal', 'DP-global')
PROFILES = ('lan', 'wan_light', 'wan_mid', 'wan_heavy')
WORKERS = (1, 3, 5, 7)
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
CP = '/candidate/probe/classes:/candidate/SystemDS.jar:/opt/systemds/target/lib/*'


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def dump(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    os.replace(tmp, path)


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


def diagnostic_contract(enabled):
    return {'diagnostic_jfr': bool(enabled),
            'diagnostic_jfr_options': list(JFR_OPTIONS) if enabled else []}


def initialize(root, stage, diagnostic_jfr=False):
    """Freeze build/probe once. Resumes verify rather than replace artifacts."""
    manifest_path = root / 'manifest.json'
    jar = REPO / 'target/systemds-3.4.0-SNAPSHOT.jar'
    files = sorted(p for p in (REPO / 'src/main').rglob('*') if p.is_file()) + [REPO / 'pom.xml']
    if not jar.is_file() or jar.stat().st_mtime_ns < max(p.stat().st_mtime_ns for p in files):
        raise RuntimeError('production JAR is missing/stale; build the exact source first')
    if not PROBE_SOURCE.is_file():
        raise RuntimeError(f'probe source missing: {PROBE_SOURCE}')
    external = [EVALUATION / x for x in ('campaign/run_w1357_integrated.py',
        'campaign/render_w1357_workload.py', 'driver/run_multihost_campaign_network_quality_v2.py',
        'driver/tools/multihost_docker.py', 'config/multihost_topology.json',
        'campaign/w1357_output_compare.py', 'campaign/w1357_metric_producer.py',
        'config/w1357_correctness_contract.json', 'calibration/java/W1357OutputDecoder.java',
        'campaign/generate_w1357_references.py', 'campaign/w1357_reference.py')]
    identity = {'jar_sha256': sha(jar), 'source_sha256': {str(p.relative_to(REPO)): sha(p) for p in files},
                'probe_source_sha256': sha(PROBE_SOURCE), 'runner_sha256': sha(Path(__file__)),
                'runtime_compare_sha256': sha(Path(runtime_compare.__file__)),
                'lifecycle_sha256': sha(Path(lifecycle.__file__)),
                'external_sha256': {str(p): sha(p) for p in external},
                'stage': str(stage), 'stage_seal_sha256': sha(stage / 'W1357_STAGE.json'),
                'diagnostic': diagnostic_contract(diagnostic_jfr)}
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest['identity'] != identity:
            raise RuntimeError('campaign identity changed: use a new root, never mix revisions')
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
    (root / 'probe-javac.log').write_text(run(command))
    manifest = {'schema': 'w1357-four-planner-campaign/v1', 'identity': identity,
                'head': run(['git', '-C', str(REPO), 'rev-parse', 'HEAD']).strip(),
                'git_status': run(['git', '-C', str(REPO), 'status', '--short']),
                'cells': matrix(), 'image': IMAGE, 'remote_root':
                f'/home/mchoi/w1357-policy-matrix/{uuid.uuid4().hex}',
                'overlay_sha256': {str(p.relative_to(overlay)): sha(p)
                                   for p in overlay.rglob('*') if p.is_file()},
                'measurement': {'samples_per_cell': 1, 'warmups': 0, 'fresh_jvm': True,
                    'fresh_workers': True, 'detailed_searchspace_metrics': False,
                    'compile_is_full_production_pipeline': True, 'runtime_audit': True,
                    'parallel_setup_only': True, 'concurrent_timed_cells': 1,
                    'timeout_semantics': 'unresolved failure, not infeasibility',
                    'runtime_order': [list(x) for x in WORKLOADS],
                    **diagnostic_contract(diagnostic_jfr)}}
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


def config(cell, phase):
    root = ET.Element('root')
    values = {'sysds.native.blas': 'mkl', 'sysds.local.spark': 'true',
              'sysds.federated.planner': cell['planner_enum'],
              'sysds.benchmark.compile_only': str(phase == 'compile').lower(),
              'sysds.localtmpdir': '/tmp/w1357-systemds/local',
              'sysds.scratch': '/tmp/w1357-systemds/scratch'}
    for name, value in values.items():
        ET.SubElement(root, name).text = value
    return ET.tostring(root, encoding='unicode') + '\n'


def latest(root, phase):
    result = {}
    for path in sorted((root / 'attempts' / phase).glob('*/result.json')):
        row = json.loads(path.read_text())
        result[row['cell']['id']] = row
    return result


def compile_gate(root):
    manifest_path = Path(root) / 'manifest.json'
    if manifest_path.is_file():
        manifest = json.loads(manifest_path.read_text())
        if (manifest.get('measurement', {}).get('diagnostic_jfr') is True
                or manifest.get('identity', {}).get('diagnostic', {}).get('diagnostic_jfr') is True):
            return False
    rows = latest(root, 'compile')
    return len(rows) == len(matrix()) and all(
        rows.get(c['id'], {}).get('status') == 'passed'
        and not rows[c['id']].get('diagnostic_only') for c in matrix())


def coordinator_java(cell, phase, diagnostic_jfr=False):
    java = list(JAVA)
    if diagnostic_jfr:
        java.extend(JFR_OPTIONS)
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


def execute_cell(root, manifest, cell, phase, args, campaign, base, renderer, reference_manifest=None):
    token = f'{time.time_ns():020d}-{uuid.uuid4().hex[:8]}'
    local = root / 'attempts' / phase / token
    local.mkdir(parents=True)
    remote = Path(manifest['remote_root']) / 'attempts' / phase / token
    rendered = renderer.render(cell)
    (local / 'cell.dml').write_text(rendered['source'])
    (local / 'execution.xml').write_text(config(cell, phase))
    dump(local / 'render-contract.json', rendered)
    life = campaign.bounded_pilot_lifecycle(cell, args.stage, remote, image=IMAGE)
    life['mounts'].append({'source': str(Path(manifest['remote_root']) / 'overlay'),
                           'target': '/candidate', 'read_only': True})
    for node in [life['coordinator'], *life['workers']]:
        node['environment']['BENCHMARK_COMPILE_ONLY'] = '1' if phase == 'compile' else '0'
        node['environment']['BENCHMARK_RUNTIME_PLAN_AUDIT'] = '1'
        node['environment']['SYSTEMDS_STANDALONE_OPTS'] = shlex.join(JAVA[3:])
        node['environment']['SYSTEMDS_JAR_SHA256'] = manifest['identity']['jar_sha256']
        if 'port' in node:
            node['command'] = ['bash', '-lc', 'set -euo pipefail; mkdir -p /tmp/w1357-systemds/local '
                '/tmp/w1357-systemds/scratch; exec ' + shlex.join([*JAVA, '-cp', CP,
                    'org.apache.sysds.api.DMLScript', '-w', str(node['port']),
                    '-config', '/workspace/experiments/tmp/execution.xml'])]
    spec = base.parse_manifest(life)
    dump(local / 'lifecycle.json', life)
    result = {'schema': 'w1357-matrix-attempt/v1', 'cell': cell, 'phase': phase,
              'attempt': token, 'status': 'failed', 'errors': [], 'remote': str(remote),
              'diagnostic_only': bool(getattr(args, 'diagnostic_jfr', False)),
              'jar_sha256': manifest['identity']['jar_sha256'], 'timeout_seconds':
              args.compile_timeout if phase == 'compile' else args.runtime_timeout}
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
        start_attempted = True
        def start_command(command):
            (local / (command.label + '.log')).write_text(run(command.argv, timeout=150))
        lifecycle.execute_start(base.build_plan(spec, 'start'), start_command)
        before = base.capture_network_snapshot(spec)
        dump(local / 'netem-before.json', before)
        java = coordinator_java(cell, phase, getattr(args, 'diagnostic_jfr', False))
        command = ['ssh', '-o', 'BatchMode=yes', '--', spec.coordinator.host,
            shlex.join(['docker', 'exec', spec.container_name(spec.coordinator), 'timeout',
                '--signal=TERM', '--kill-after=30s', str(result['timeout_seconds']), *java])]
        dump(local / 'command.json', command)
        started = time.monotonic()
        with (local / 'coordinator.log').open('x') as output:
            completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                timeout=result['timeout_seconds'] + 90)
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
        result.update(parsed_timing)
        result['full_initial_planning_seconds'] = receipt['planningFullInitialNanos'] / 1e9
        if phase == 'runtime':
            result['runtime_seconds'] = receipt['executionNanos'] / 1e9
            outputs = [{'path': p['path'], 'format': p['format']} for p in rendered['outputs']]
            result['output_identity'] = campaign.capture_remote_output_identity(spec.coordinator.host, remote, outputs)
            comparison = runtime_compare.compare(campaign, cell, token, args.stage, remote,
                local / 'comparison.json', reference_manifest, IMAGE)
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
            for node in nodes:
                try:
                    health = capture_container_health(spec, node)
                    dump(local / f'{node.host}-container-health.json', health)
                    if health['state']['OOMKilled'] or health['oom_events']:
                        result['errors'].append(f'container OOM observed: {node.host}')
                except Exception as error:
                    result.setdefault('health_collection_errors', []).append(str(error))
            try:
                after = base.capture_network_snapshot(spec)
                dump(local / 'netem-after.json', after)
                if before is not None:
                    network = base.validate_network_quality(spec, before, after)
                    dump(local / 'netem-validation.json', network)
                    if network.get('valid') is not True:
                        result['errors'].append('network quality invalid')
                for node in nodes:
                    try:
                        (local / f'{node.host}-container.log').write_text(ssh(node.host,
                            ['docker', 'logs', '--tail', '3000', spec.container_name(node)]))
                    except Exception as error:
                        result.setdefault('log_collection_errors', []).append(str(error))
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
        dump(local / 'result.json', result)
    print(json.dumps({k: result.get(k) for k in ('cell', 'phase', 'attempt', 'status',
        'compile_seconds', 'searchspace_seconds', 'selection_adapter_seconds', 'errors')}), flush=True)
    return result


def summarize(root):
    summary = {}
    for phase in ('compile', 'runtime'):
        rows = latest(root, phase)
        summary[phase] = {'passed': sum(r['status'] == 'passed' for r in rows.values()),
                          'failed': sum(r['status'] != 'passed' for r in rows.values()),
                          'pending': len(matrix()) - len(rows)}
        columns = ['id', 'planner', 'suite', 'workload', 'workers', 'profile', 'status',
                   'diagnostic_only',
                   'compile_seconds', 'common_preparation_seconds', 'analysis_seconds',
                   'searchspace_seconds', 'selection_adapter_seconds',
                   'planning_after_analysis_seconds', 'full_initial_planning_seconds',
                   'runtime_seconds', 'attempt']
        with (root / f'{phase}-comparison.csv').open('w') as stream:
            writer = csv.DictWriter(stream, fieldnames=columns)
            writer.writeheader()
            for cell in schedule(phase):
                row = rows.get(cell['id'], {'status': 'pending'})
                values = {k: cell.get(k, row.get(k, '')) for k in columns}
                if row.get('diagnostic_only'):
                    for key in columns:
                        if key.endswith('_seconds'):
                            values[key] = ''
                writer.writerow(values)
    summary['compile_gate'] = compile_gate(root)
    dump(root / 'summary.json', summary)
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--stage', type=Path, default=STAGE)
    parser.add_argument('--phase', choices=('prepare', 'compile', 'runtime', 'all', 'summary'), default='compile')
    parser.add_argument('--compile-timeout', type=int, default=900)
    parser.add_argument('--runtime-timeout', type=int, default=3600)
    parser.add_argument('--reference-manifest', type=Path)
    parser.add_argument('--max-cells', type=int)
    parser.add_argument('--retry-failed', action='store_true')
    parser.add_argument('--keep-going', action='store_true')
    parser.add_argument('--diagnostic-jfr', action='store_true',
                        help='single compile-only coordinator JFR diagnostic; never benchmark data')
    for key, options in (('planner', PLANNERS), ('profile', PROFILES), ('workload', tuple(w for _, w in WORKLOADS))):
        parser.add_argument('--' + key, choices=options)
    parser.add_argument('--workers', type=int, choices=WORKERS)
    args = parser.parse_args(argv)
    args.root = args.root.resolve()
    if args.diagnostic_jfr and (args.phase != 'compile' or args.max_cells != 1):
        parser.error('--diagnostic-jfr requires --phase compile and --max-cells 1')
    if args.phase == 'summary':
        print(json.dumps(summarize(args.root), indent=2))
        return 0
    if min(args.compile_timeout, args.runtime_timeout) <= 0 or (args.max_cells is not None and args.max_cells <= 0):
        parser.error('timeouts/max-cells must be positive')
    if args.phase in ('runtime', 'all') and any(getattr(args, k) is not None for k in ('planner', 'profile', 'workload', 'workers')):
        parser.error('runtime/all must preserve the complete schedule; filters are compile-only')
    campaign, base, renderer_module = dependencies()
    manifest = initialize(args.root, args.stage, args.diagnostic_jfr)
    if args.phase == 'prepare':
        print(json.dumps({'prepared': True, 'cells': len(matrix()), 'root': str(args.root)}))
        return 0
    if args.phase == 'runtime' and not compile_gate(args.root):
        raise RuntimeError('runtime blocked: full same-engine 896-cell compile gate is not passed')
    hosts = ['so007', 'so002', 'so003', 'so004', 'so005', 'so006', 'so008', 'so009']
    leases = []
    count = 0
    with base.RUNTIME_LANE.open('a+') as lane:
        fcntl.flock(lane, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            leases = campaign.acquire_remote_stage_leases(hosts, args.stage)
            preflight = {'image': campaign.verify_remote_image_content(hosts, IMAGE),
                         'stage': campaign.verify_remote_bounded_stage(hosts, args.stage)}
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                preflight['overlay'] = dict(zip(hosts, pool.map(lambda h: publish_overlay(h, args.root, manifest), hosts)))
            dump(args.root / f'preflight-{time.time_ns()}.json', preflight)
            renderer = renderer_module.Renderer(args.stage)
            for phase in (('compile', 'runtime') if args.phase == 'all' else (args.phase,)):
                if phase == 'runtime' and not compile_gate(args.root):
                    raise RuntimeError('runtime blocked: all 896 compile conditions must pass first')
                if phase == 'runtime':
                    runtime_compare.pin_reference(args.root, args.reference_manifest)
                previous = latest(args.root, phase)
                for cell in schedule(phase):
                    if any(getattr(args, key) is not None and cell[key] != getattr(args, key)
                           for key in ('planner', 'profile', 'workload', 'workers')):
                        continue
                    old = previous.get(cell['id'])
                    if old:
                        if old['status'] == 'passed':
                            continue
                        if not args.retry_failed:
                            if args.keep_going and phase == 'compile':
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
                                        args.root, args.stage, cell, compile_gate(args.root))
                                finally:
                                    leases = campaign.acquire_remote_stage_leases(hosts, args.stage)
                                    dump(args.root / f'post-reference-stage-{time.time_ns()}.json',
                                         campaign.verify_remote_bounded_stage(hosts, args.stage))
                            else:
                                reference = runtime_compare.prepare_workload_reference(
                                    args.root, args.stage, cell, compile_gate(args.root))
                        else:
                            reference = runtime_compare.pin_reference(args.root, args.reference_manifest)
                        reference_manifest = Path(reference['result_manifest']['local_path'])
                    result = execute_cell(args.root, manifest, cell, phase, args, campaign, base,
                                          renderer, reference_manifest)
                    count += 1
                    summarize(args.root)
                    if result.get('cleanup_resolved') is False:
                        raise RuntimeError('cleanup unresolved: no subsequent cell may start')
                    if result['status'] != 'passed' and (not args.keep_going or phase == 'runtime'):
                        return 1
                    if args.max_cells and count >= args.max_cells:
                        return 0 if result['status'] == 'passed' else 1
        finally:
            release = campaign.release_remote_stage_leases(leases) if leases else {'released': True}
            dump(args.root / f'lease-release-{time.time_ns()}.json', release)
            summarize(args.root)
            if not release['released']:
                raise RuntimeError('remote stage lease release unproven')
    status = summarize(args.root)
    print(json.dumps(status, indent=2))
    runtime_complete = status['runtime'] == {'passed': len(matrix()), 'failed': 0, 'pending': 0}
    return 0 if status['compile_gate'] and (args.phase == 'compile' or runtime_complete) else 1


if __name__ == '__main__':
    raise SystemExit(main())
