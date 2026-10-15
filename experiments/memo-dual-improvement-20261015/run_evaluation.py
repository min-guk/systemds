#!/usr/bin/env python3
"""Sequential randomized cold JVM Docker evaluation, preserving every failed attempt."""
import argparse
import json
from pathlib import Path
import random
import subprocess

REPO = Path(__file__).resolve().parents[2]
ROOT = Path(__file__).resolve().parent

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--suite', choices=['diagnostics','comparison','ablation','heap','heap-paired','runtime'], required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--engine', type=Path, default=ROOT/'engine-v3')
    p.add_argument('--baseline-engine', type=Path, default=REPO/'experiments/memo-dual-mode-20261015/engine-v1')
    p.add_argument('--repetitions', type=int, default=7)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    rng = random.Random(20261015)
    jobs = []
    if a.suite == 'diagnostics':
        jobs = [('typed-factors',a.engine,['--workloads','logreg,glm,gnmf','--algorithms','MEMO_GLOBAL',
            '--factor-diagnostics','true','--factor-diagnostics-max-factors','50000','--dual-diagnostics','true'])]
    elif a.suite == 'comparison':
        for repeat in range(1,a.repetitions+1):
            engines = [('before',a.baseline_engine),('after',a.engine)]
            rng.shuffle(engines)
            for version,engine in engines:
                algorithms=['EXACT','MEMO_GLOBAL','MEMO_LOCAL']; rng.shuffle(algorithms)
                workloads=['logreg','glm','gnmf']; rng.shuffle(workloads)
                jobs.append((f'{version}-{repeat:02}',engine,['--workloads',','.join(workloads),
                    '--algorithms',','.join(algorithms)]))
    elif a.suite == 'ablation':
        variants=[('independent',['--dual-cluster-cells','0','--dual-improve-seed','false']),
            ('cluster',['--dual-cluster-cells','4096','--dual-improve-seed','false']),
            ('cluster-seed',['--dual-cluster-cells','4096','--dual-improve-seed','true'])]
        for repeat in range(1,a.repetitions+1):
            rng.shuffle(variants)
            for name,extra in variants:
                jobs.append((f'{name}-{repeat:02}',a.engine,['--workloads','gnmf',
                    '--algorithms','EXACT,MEMO_GLOBAL,MEMO_LOCAL','--dual-direct-cells','0']+extra))
    elif a.suite == 'heap-paired':
        for repeat in range(1,a.repetitions+1):
            engines=[('before',a.baseline_engine),('after',a.engine)]; rng.shuffle(engines)
            for version,engine in engines:
                algorithms=['EXACT','MEMO_GLOBAL','MEMO_LOCAL']; rng.shuffle(algorithms)
                jobs.append((f'{version}-{repeat:02}',engine,['--workloads','glm',
                    '--algorithms',','.join(algorithms),'--heap-mib','256']))
    elif a.suite == 'heap':
        for heap in [128,256,512]:
            jobs.append((f'heap-{heap}',a.engine,['--workloads','glm,gnmf','--algorithms',
                'EXACT,MEMO_GLOBAL,MEMO_LOCAL','--heap-mib',str(heap)]))
        jobs.append(('admission-256',a.engine,['--workloads','glm','--algorithms',
            'MEMO_GLOBAL','--heap-mib','256','--dual-diagnostics','true']))
    else:
        jobs=[('runtime-and-regional',a.engine,['--workloads','gnmf','--algorithms',
            'EXACT,MEMO_GLOBAL,MEMO_LOCAL','--runtime-smoke','--synthetic-repetitions','7'])]
    receipts=[]
    for name,engine,extra in jobs:
        command=[str(REPO/'scripts/fedplanner/run_LAN_docker.sh'),'--memo-tree-dp-benchmark',
            '--engine',str(engine.resolve()),'--sealed-stage','/home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z',
            '--output-root',str((a.output/name).resolve()),'--workers','3','--search-nodes','2000']+extra
        if a.suite!='runtime': command += ['--skip-synthetic']
        with (a.output/(name+'.log')).open('w') as log:
            result=subprocess.run(command,cwd=REPO,stdout=log,stderr=subprocess.STDOUT)
        summary=a.output/name/'summary.json'
        if not summary.is_file(): raise RuntimeError(f'Missing evidence: {summary}')
        receipts.append({'name':name,'engine':str(engine.resolve()),'argv':command,
            'returncode':result.returncode,'summary':str(summary.resolve())})
        (a.output/'receipts.json').write_text(json.dumps({'seed':20261015,'suite':a.suite,
            'repetitions':a.repetitions,'jobs':receipts},indent=2)+'\n')
        print(name,result.returncode,flush=True)

if __name__=='__main__': main()
