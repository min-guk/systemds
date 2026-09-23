#!/usr/bin/env python3
"""One command to capture and recheck a frozen current-P/E campaign.

Model capture and physical equality are separate gates. Incomplete native
models, non-independent P acceptance, and unprojected large cells stay visible
as INCOMPLETE; this command never promotes them to equality.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys

from capture_current_plan_matrix import select_cells
from run_current_pe_cell import read, save, sha
from run_current_pe_matrix import campaign_cells, resource_snapshot


HERE = Path(__file__).resolve().parent
RUNNER_SHA = sha(__file__)


def stage(script, args, log):
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as output:
        result = subprocess.run([sys.executable, str(HERE / script), *args],
                                stdout=output, stderr=subprocess.STDOUT, check=False)
    return {'exitCode': result.returncode, 'log': str(log)}


def physical_stage_result(stage_result, summary_path):
    """Accept a physical summary only when this invocation attested it."""
    if not summary_path.is_file():
        return {'status': 'ERROR', 'reason': 'PHYSICAL_SUMMARY_MISSING'}
    try:
        summary = read(summary_path)
        lines = Path(stage_result['log']).read_text().splitlines()
        attestation = json.loads(next(line for line in reversed(lines) if line.strip()))
        expected_exit = {'EQUAL': 0, 'INCOMPLETE': 2,
                         'DIFFERENT': 1, 'ERROR': 1}
        if (attestation.get('status') != summary.get('status') or
                attestation.get('counts') != summary.get('counts') or
                stage_result['exitCode'] != expected_exit.get(summary.get('status'))):
            raise ValueError('physical stage status/counts/exit disagree')
        return summary
    except (OSError, StopIteration, ValueError, TypeError, AttributeError) as error:
        return {'status': 'ERROR', 'reason': 'PHYSICAL_STAGE_NOT_ATTESTED',
                'detail': str(error)[:300]}


def frozen_frontier(args):
    campaign = read(args.campaign)
    cells = campaign_cells(campaign, args.catalog, args.evaluation_root)
    ready = sorted(row['id'] for row in select_cells(args.catalog, None, None, True))
    if cells != ready:
        raise ValueError('campaign differs from the entire frozen-ready catalog frontier')
    return campaign, cells


def model_stage_args(args, source, mode='run'):
    common = ['--catalog', str(args.catalog), '--evaluation-root',
              str(args.evaluation_root), '--ready-only']
    if mode == 'run':
        common = ['--build-root', str(args.build_root), *common]
    if source == 'P':
        return common + ['--artifact-root', str(args.artifact_root / 'p-models'),
                         '--jobs', str(args.p_jobs), '--timeout', str(args.timeout)]
    return common + ['--artifact-root', str(args.artifact_root / 'e-models'),
                     '--jobs', str(args.e_jobs), '--timeout', str(args.timeout),
                     '--min-free-disk-gib', str(args.min_free_disk_gib),
                     '--per-job-disk-gib', str(args.e_job_disk_gib)]


def physical_stage_args(args, mode):
    result = ['--campaign', str(args.campaign), '--catalog', str(args.catalog),
              '--evaluation-root', str(args.evaluation_root),
              '--verification-root', str(args.verification_root),
              '--p-matrix-dir', str(args.artifact_root / 'p-models'),
              '--e-matrix-dir', str(args.artifact_root / 'e-models'),
              '--artifact-root', str(args.artifact_root / 'physical-artifacts'),
              '--result-dir', str(args.artifact_root / 'physical-results'),
              '--jobs', str(args.physical_jobs), '--shard-jobs', str(args.shard_jobs),
              '--state-budget', str(args.state_budget),
              '--e-raw-budget', str(args.e_raw_budget),
              '--cell-timeout', str(args.cell_timeout),
              '--max-jvms', str(args.max_jvms),
              '--min-free-disk-gib', str(args.min_free_disk_gib), '--compact']
    if mode == 'run':
        result += ['--build-root', str(args.build_root), '--resume']
    return result


def preflight(args):
    capacity = resource_snapshot(args.artifact_root)
    jvms = args.p_jobs + args.e_jobs
    if jvms > args.max_jvms or jvms * 5 > capacity['availableRamGiB']:
        raise ValueError('P/E capture concurrency exceeds JVM or available RAM budget')
    reservation = (args.min_free_disk_gib + args.p_jobs * args.p_job_disk_gib +
                   args.e_jobs * args.e_job_disk_gib)
    if capacity['freeDiskGiB'] < reservation:
        raise ValueError('P/E capture disk reservation exceeds free artifact space')
    return capacity


def model_status(root, source, expected_cells):
    folder = root / ('p-models' if source == 'P' else 'e-models')
    path = folder / 'matrix.json'
    if not path.is_file():
        return {'status': 'MISSING', 'counts': {}, 'cellCount': 0}
    matrix = read(path)
    cells = matrix.get('cells', [])
    if len(cells) != len(expected_cells) or [row.get('cell') for row in cells] != expected_cells:
        raise ValueError(source + ' model matrix frontier differs from campaign')
    counts = matrix.get('counts') or {}
    status = ('ERROR' if any(counts.get(name, 0) for name in
                         ('ERROR', 'INPUT_ERROR', 'NO_CAPTURE'))
              else matrix.get('status'))
    verification_path = folder / 'verification.json'
    verification = read(verification_path) if verification_path.is_file() else None
    if verification is not None:
        if (verification.get('cellCount') != len(expected_cells) or
                verification.get('counts') != counts or
                verification.get('matrixSha256') != sha(path)):
            raise ValueError(source + ' model verification frontier/counts differ')
        if verification.get('status') == 'PASS' and (
                (source == 'P' and verification.get('verifiedComplete') != len(expected_cells)) or
                (source == 'E' and verification.get('verifiedKnown') != len(expected_cells))):
            raise ValueError(source + ' model verifier lacks complete known coverage')
        if status == 'COMPLETE' and verification.get('failures'):
            status = 'ERROR'
        elif status == 'COMPLETE' and verification.get('status') != 'PASS':
            status = 'INCOMPLETE'
    elif status == 'COMPLETE':
        status = 'ERROR'
    return {'status': status, 'captureStatus': matrix.get('status'),
            'verificationStatus': verification.get('status') if verification else None,
            'verificationCodeSha256': verification.get('verifierSha256') if verification else None,
            'unknownFactorCells': verification.get('unknownFactorCells') if verification else None,
            'counts': counts,
            'cellCount': matrix.get('cellCount'), 'matrixSha256': sha(path),
            'sourceTreeSha256': (matrix.get('sourceTreeSha256') if source == 'P'
                                 else matrix.get('binding', {}).get('sourceTreeSha256')),
            'classTreeSha256': (matrix.get('classTreeSha256') if source == 'P'
                                else matrix.get('binding', {}).get('classTreeSha256'))}


def publish(args, campaign, cells, stages, model_p, model_e, physical,
            receipt_name='suite.json'):
    if sha(__file__) != RUNNER_SHA:
        raise ValueError('campaign runner changed during execution')
    if (model_p.get('sourceTreeSha256') and model_e.get('sourceTreeSha256') and
            (model_p['sourceTreeSha256'] != model_e['sourceTreeSha256'] or
             model_p['classTreeSha256'] != model_e['classTreeSha256'])):
        raise ValueError('P and E model matrices used different source/class trees')
    physical_status = physical.get('status', 'NOT_RUN')
    stage_ok = all(row['exitCode'] == 0 for row in stages.values())
    model_stages_ok = all(stages.get(name, {}).get('exitCode') == 0
                          for name in ('P', 'E', 'P-verification'))
    status = ('EQUAL' if stage_ok and model_stages_ok and
              stages.get('physical', {}).get('exitCode') == 0 and
              model_p['status'] == model_e['status'] == 'COMPLETE'
              and physical_status == 'EQUAL' and campaign.get('scope') == 'FULL_CURRENT'
              and not campaign.get('unresolved')
              else 'DIFFERENT' if physical_status == 'DIFFERENT' and
                   model_p['status'] == model_e['status'] == 'COMPLETE' and
                   model_stages_ok
                   and stages.get('physical', {}).get('exitCode') == 1
              else 'ERROR' if model_p['status'] == 'ERROR' or model_e['status'] == 'ERROR'
                   or physical_status == 'ERROR'
                   or any(row['exitCode'] not in (0, 2) for row in stages.values())
                   or (model_p['status'] == 'COMPLETE' and stages.get('P', {}).get('exitCode') != 0)
                   or (model_p['status'] == 'COMPLETE' and
                       stages.get('P-verification', {}).get('exitCode') != 0)
                   or (model_e['status'] == 'COMPLETE' and stages.get('E', {}).get('exitCode') != 0)
              else 'INCOMPLETE')
    result = {'schema': 'current-pe-campaign-suite-v1', 'status': status,
              'claimScope': campaign['scope'], 'cellCount': len(cells),
              'campaignSha256': sha(args.campaign), 'catalogSha256': sha(args.catalog),
              'suiteCodeSha256': RUNNER_SHA,
              'evaluationRoot': str(args.evaluation_root),
              'stages': stages, 'pModel': model_p, 'eModel': model_e,
              'physical': physical,
              'runtimeSemanticCoverage': 'NOT_ASSESSED_BY_THIS_CONTRACT'}
    save(args.artifact_root / receipt_name, result)
    print(json.dumps({'status': status, 'cellCount': len(cells),
                      'pModel': model_p['counts'], 'eModel': model_e['counts'],
                      'physical': physical_status}, sort_keys=True), flush=True)
    return 0 if status == 'EQUAL' else 1 if status in ('ERROR', 'DIFFERENT') else 2


def run(args, campaign, cells):
    args.artifact_root.mkdir(parents=True, exist_ok=True)
    preflight(args)
    stages = {}
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = {}
        for source, script in [('P', 'capture_current_plan_matrix.py'),
                               ('E', 'capture_current_e_model_matrix.py')]:
            command = (['run'] if source == 'E' else []) + model_stage_args(args, source)
            command += ['--resume']
            if getattr(args, 'recapture_invalid', False):
                command += ['--recapture-invalid']
            if source == 'P':
                command += ['--retry-errors']
            futures[source] = pool.submit(stage, script, command,
                                          args.artifact_root / ('capture-' + source + '.log'))
        stages.update({source: future.result() for source, future in futures.items()})
    stages['P-verification'] = stage(
        'verify_current_p_matrix.py',
        ['--matrix-dir', str(args.artifact_root / 'p-models'),
         '--catalog', str(args.catalog), '--evaluation-root',
         str(args.evaluation_root), '--frozen-ready'],
        args.artifact_root / 'verify-P.log')
    model_p = model_status(args.artifact_root, 'P', cells)
    model_e = model_status(args.artifact_root, 'E', cells)
    physical = {'status': 'NOT_RUN', 'reason': 'MODEL_MATRIX_INCOMPLETE'}
    if (model_p['status'] == model_e['status'] == 'COMPLETE' and
            stages['P']['exitCode'] == stages['E']['exitCode'] == 0 and
            stages['P-verification']['exitCode'] == 0):
        stages['physical'] = stage('run_current_pe_matrix.py',
                                   ['run', *physical_stage_args(args, 'run')],
                                   args.artifact_root / 'physical-run.log')
        path = args.artifact_root / 'physical-results/summary.json'
        physical = physical_stage_result(stages['physical'], path)
    return publish(args, campaign, cells, stages, model_p, model_e, physical)


def verify(args, campaign, cells):
    previous = read(args.artifact_root / 'suite.json')
    if (previous.get('schema') != 'current-pe-campaign-suite-v1' or
            previous.get('campaignSha256') != sha(args.campaign) or
            previous.get('catalogSha256') != sha(args.catalog) or
            previous.get('suiteCodeSha256') != RUNNER_SHA or
            previous.get('cellCount') != len(cells)):
        raise ValueError('stored suite binding or frontier differs')
    stages = {}
    stages['P-verification'] = stage('verify_current_p_matrix.py',
                        ['--matrix-dir', str(args.artifact_root / 'p-models'),
                         '--catalog', str(args.catalog), '--evaluation-root',
                         str(args.evaluation_root), '--frozen-ready'],
                        args.artifact_root / 'verify-P.log')
    stages['P'] = previous['stages']['P']
    stages['E'] = stage('capture_current_e_model_matrix.py',
                        ['verify', *model_stage_args(args, 'E', 'verify')],
                        args.artifact_root / 'verify-E.log')
    model_p = model_status(args.artifact_root, 'P', cells)
    model_e = model_status(args.artifact_root, 'E', cells)
    physical = {'status': 'NOT_RUN', 'reason': 'MODEL_MATRIX_INCOMPLETE'}
    if (args.artifact_root / 'physical-results/summary.json').is_file():
        stages['physical'] = stage('run_current_pe_matrix.py',
                                   ['verify', *physical_stage_args(args, 'verify')],
                                   args.artifact_root / 'physical-verify.log')
        physical = physical_stage_result(
            stages['physical'], args.artifact_root / 'physical-results/summary.json')
    if (previous.get('pModel') != model_p or previous.get('eModel') != model_e or
            previous.get('physical') != physical):
        raise ValueError('stored campaign suite differs from offline reconstruction')
    return publish(args, campaign, cells, stages, model_p, model_e, physical,
                   'suite-verification.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('run', 'verify'))
    for name in ('campaign', 'catalog', 'evaluation-root', 'verification-root',
                 'artifact-root'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--build-root', type=Path)
    parser.add_argument('--p-jobs', type=int, default=4)
    parser.add_argument('--e-jobs', type=int, default=4)
    parser.add_argument('--max-jvms', type=int, default=8)
    parser.add_argument('--physical-jobs', type=int, default=2)
    parser.add_argument('--shard-jobs', type=int, default=2)
    parser.add_argument('--timeout', type=int, default=3600)
    parser.add_argument('--cell-timeout', type=int, default=3600)
    parser.add_argument('--state-budget', type=int, default=1000)
    parser.add_argument('--e-raw-budget', type=int, default=1000000)
    parser.add_argument('--min-free-disk-gib', type=int, default=20)
    parser.add_argument('--p-job-disk-gib', type=int, default=2)
    parser.add_argument('--e-job-disk-gib', type=int, default=8)
    parser.add_argument('--recapture-invalid', action='store_true',
                        help='quarantine stale or corrupt prior model evidence before recapture')
    args = parser.parse_args()
    for name in ('campaign', 'catalog', 'evaluation_root', 'verification_root',
                 'artifact_root', 'build_root'):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.resolve())
    if min(args.p_jobs, args.e_jobs, args.max_jvms, args.physical_jobs,
           args.shard_jobs, args.timeout, args.cell_timeout, args.state_budget,
           args.e_raw_budget, args.p_job_disk_gib, args.e_job_disk_gib) < 1 or args.min_free_disk_gib < 0:
        parser.error('jobs, budgets, timeouts, and per-job disk reserve must be positive')
    if args.mode == 'run' and args.build_root is None:
        parser.error('run requires --build-root')
    campaign, cells = frozen_frontier(args)
    return run(args, campaign, cells) if args.mode == 'run' else verify(args, campaign, cells)


if __name__ == '__main__':
    raise SystemExit(main())
