#!/usr/bin/env python3
"""Explicit stage/JAR-pinned workload references; historical pins stay immutable."""
import json
from pathlib import Path
import sys
import shlex
import subprocess
import matrix_runtime_compare as old

SCHEMA = 'cofee-w1357-current-workload-reference-pin/v1'
SELECTOR = 'ml:steplm'
OUTPUTS = {
    SELECTOR: {'result': ('matrix', 'csv')},
    'p2:P2_PREP': {'result': ('matrix', 'binary'), 'transform_metadata': ('frame', 'binary')},
}


def pin_directory(selector):
    if selector not in OUTPUTS:
        raise old.RuntimeComparisonError('unsupported current reference selector')
    return 'reference-current-' + selector.split(':')[1].lower()


def validate(result, plan, pin):
    expected = pin['expected']
    selector = pin.get('workload_selector')
    if (pin.get('schema') != SCHEMA or pin.get('source_host') != old.REFERENCE_HOST
            or selector not in OUTPUTS):
        raise old.RuntimeComparisonError('current reference selector/host/schema mismatch')
    if (result.get('schema') != old.REFERENCE_RESULT_SCHEMA or result.get('status') != 'READY'
            or result.get('ready') is not True or result.get('errors') != []
            or result.get('verification_gaps') != [] or result.get('output_root') != pin['source_root']):
        raise old.RuntimeComparisonError('current reference is not a clean READY result')
    if (plan.get('schema') != old.REFERENCE_PLAN_SCHEMA or plan.get('host') != old.REFERENCE_HOST
            or plan.get('workload_selector') != selector or plan.get('output_root') != pin['source_root']
            or plan.get('planner') != 'none' or plan.get('execution_mode') != 'singlenode'):
        raise old.RuntimeComparisonError('current CP reference plan identity mismatch')
    for key, wanted in expected.items():
        if plan.get(key) != wanted:
            raise old.RuntimeComparisonError('current reference expected identity mismatch: '+key)
    for key in ('stage_seal_sha256', 'jar_sha256', 'image_sha256', 'source_sha256',
                'planner', 'execution_mode', 'seeds', 'environment', 'workload_selector'):
        if result.get('provenance', {}).get(key) != plan.get(key):
            raise old.RuntimeComparisonError('current reference result/plan identity mismatch: '+key)
    rows, contracts = result.get('workloads', []), plan.get('contracts', [])
    if len(rows) != 1 or len(contracts) != 1:
        raise old.RuntimeComparisonError('current workload reference must be singleton')
    row, contract = rows[0], contracts[0]
    if any((x.get('suite'), x.get('workload')) != tuple(selector.split(':')) for x in (row, contract)):
        raise old.RuntimeComparisonError('current reference workload mismatch')
    status = row.get('status', {})
    if status.get('returncode') != 0 or status.get('executions') != 1 or status.get('dmlscript_fatal_marker') is not False:
        raise old.RuntimeComparisonError('current reference execution failed')
    outputs = row.get('outputs', [])
    expected_outputs = OUTPUTS[selector]
    targets = contract.get('outputs', [])
    if len(outputs) != len(expected_outputs) or len(targets) != len(expected_outputs):
        raise old.RuntimeComparisonError('current reference output count mismatch')
    if {x.get('role') for x in outputs} != set(expected_outputs) or {x.get('role') for x in targets} != set(expected_outputs):
        raise old.RuntimeComparisonError('current reference output contract mismatch')
    for output in outputs:
        target = next(x for x in targets if x['role'] == output['role'])
        if (target.get('data_type'), target.get('format')) != expected_outputs[output['role']]:
            raise old.RuntimeComparisonError('current reference output contract mismatch')
        if any(output.get(key) != target.get(key) for key in ('role', 'data_type', 'format', 'path')):
            raise old.RuntimeComparisonError('current reference output identity mismatch')
        for value in (output.get('metadata_sha256'), output.get('tree', {}).get('tree_sha256'),
                      output.get('decoded', {}).get('sha256')):
            if old.SHA256.fullmatch(str(value)) is None:
                raise old.RuntimeComparisonError('current reference typed output evidence missing')
    if row.get('program_sha256') != contract.get('provenance', {}).get('derived_dml_sha256'):
        raise old.RuntimeComparisonError('current reference program identity mismatch')


def load(path):
    path = Path(path)
    pin = json.loads(old._safe_local_file(path.with_name('reference-pin.json'), 'current reference pin'))
    blobs = []
    for name, file in (('result_manifest', path), ('generation_plan', path.with_name('generation-plan.json'))):
        spec = pin[name]
        if spec.get('local_path') != str(file) or spec.get('remote_path') != str(Path(pin['source_root'])/file.name):
            raise old.RuntimeComparisonError('current reference path mismatch')
        raw = old._safe_local_file(file, name)
        if old._sha256_bytes(raw) != spec['sha256']:
            raise old.RuntimeComparisonError('current reference SHA mismatch')
        blobs.append(raw)
    result, plan = map(json.loads, blobs)
    validate(result, plan, pin)
    return *blobs, result, plan, pin


def prepare(root, stage, attempt, selector=SELECTOR):
    sys.path.insert(0, '/home/mchoi/cofee-evaluation')
    from campaign import generate_w1357_references as generator
    stage, root = Path(stage), Path(root)
    seal_sha = old._sha256_file(stage/'W1357_STAGE.json')
    jar_sha = old._sha256_file(stage/'systemds/target/SystemDS.jar')
    pin_root = root/pin_directory(selector)
    result_path = pin_root/'result-manifest.json'
    if (pin_root/'reference-pin.json').exists():
        *_, pin = load(result_path)
        if (pin['workload_selector'] != selector or pin['expected']['stage_seal_sha256'] != seal_sha
                or pin['expected']['jar_sha256'] != jar_sha):
            raise old.RuntimeComparisonError('current reference does not match campaign stage/JAR')
        return pin
    if pin_root.exists():
        raise old.RuntimeComparisonError('partial current reference pin; inspect before continuing')
    remote = Path('/home/mchoi')/('w1357-reference-'+attempt)
    plan = generator.build_plan(stage, remote, attempt, workload=selector,
        expected_stage_sha256=seal_sha, expected_jar_sha256=jar_sha)
    # A completed same-identity reference can be pinned by a new campaign without
    # retraining. A failed/partial existing attempt is never overwritten or retried.
    exists = subprocess.run(['ssh','-o','BatchMode=yes','--',old.REFERENCE_HOST,
        shlex.join(['test','-e',str(remote)])],capture_output=True,timeout=120)
    if exists.returncode == 1:
        result = generator.execute(plan)
        if result.get('status') != 'READY':
            raise old.RuntimeComparisonError('current CP reference failed: '+json.dumps(result))
    elif exists.returncode != 0:
        raise old.RuntimeComparisonError('current reference existence check failed')
    result_raw, plan_raw = old._fetch_remote_reference(remote)
    pin = dict(schema=SCHEMA, source_host=old.REFERENCE_HOST, source_root=str(remote),
        workload_selector=selector, expected={key: plan[key] for key in
            ('stage_seal_sha256','jar_sha256','image_sha256','source_sha256','seeds','environment',
             'correctness_contract_sha256','decoder_source_sha256','runtime_config_sha256')})
    validate(json.loads(result_raw), json.loads(plan_raw), pin)
    pin_root.mkdir(exist_ok=False)
    for name, path, raw in (('result_manifest',result_path,result_raw),
                          ('generation_plan',pin_root/'generation-plan.json',plan_raw)):
        with path.open('xb') as stream: stream.write(raw)
        pin[name] = dict(local_path=str(path), remote_path=str(remote/path.name), sha256=old._sha256_bytes(raw))
    with (pin_root/'reference-pin.json').open('xb') as stream: stream.write(old._canonical(pin))
    load(result_path)
    return pin
