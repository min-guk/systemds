#!/usr/bin/env python3
"""Derive report tables from preserved Docker receipts; never certify from timings."""
import argparse
from collections import defaultdict
import csv
import json
from pathlib import Path
import re
from statistics import median


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent / 'evaluation')
    args = parser.parse_args()
    rows = []
    for path in sorted(args.root.glob('*/*/summary.json')):
        data = json.loads(path.read_text())
        for value in data.get('compileRows', []):
            suite, run = path.parent.parent.name, path.parent.name
            certificate = (value.get('dualCertificates') or [{}])[0]
            statistics = certificate.get('statistics') or {}
            plans = value.get('memoPlans') or []
            nanos = [p.get('phaseNanos', {}).get('optimization') for p in plans]
            log = path.parent / 'compile' / f"{value['workload']}-w{value['workers']}-{value['algorithm'].lower()}" / 'coordinator.log'
            text = log.read_text() if log.is_file() else ''
            summaries = [json.loads(item) for item in re.findall(r'^\[MemoLocalSummary\] (.*)$', text, re.M)]
            summary = summaries[0] if summaries else {}
            native_rules = re.findall(r'^\[MemoPlanner\].*? nativeRules=(\d+)', text, re.M)
            row = dict(suite=suite, run=run, version='before' if run.startswith('before-') else 'after',
                       workload=value['workload'], algorithm=value['algorithm'], status=value['status'],
                       failure=value.get('failureCategory'), sourceSha256=value.get('sourceSha256'),
                       nativeRules=int(native_rules[0]) if native_rules else None,
                       optimizerSeconds=sum(nanos)/1e9 if nanos and None not in nanos else None,
                       compileSeconds=value.get('compile', {}).get('fullCompileSeconds'),
                       containerMiB=(value.get('peakContainerMemoryUsageBytes') or 0)/1048576,
                       solverEstimatedMiB=statistics.get('peakEstimatedBytes', 0)/1048576,
                       firstUBSeconds=statistics.get('firstUpperNanos', 0)/1e9,
                       certificateSeconds=statistics.get('certificateNanos', 0)/1e9,
                       seedSeconds=statistics.get('seedNanos', 0)/1e9,
                       messageSeconds=statistics.get('clusterBuildNanos', 0)/1e9,
                       propagationSeconds=summary.get('propagationNanos', 0)/1e9,
                       searchSeconds=statistics.get('searchNanos', 0)/1e9,
                       nodes=statistics.get('nodes'), exactCells=statistics.get('exactCells'),
                       messageCells=statistics.get('clusterCells'), seedEvaluations=statistics.get('seedEvaluations'),
                       route=certificate.get('route'), result=certificate.get('status'),
                       lowerBound=certificate.get('lowerBound'), upperBound=certificate.get('upperBound'),
                       certifiedGap=certificate.get('relativeGap'), **summary)
            actions = [json.loads(item) for item in re.findall(r'^\[MemoLocalAction\] (.*)$', text, re.M)]
            row['checkpoints'] = [a for a in actions if a.get('kind') != 'MESSAGE']
            if actions:
                row['checkpoints'].append(actions[-1])
            rows.append(row)
    grouped = defaultdict(list)
    for row in rows:
        variant = row['version'] if row['suite'] == 'comparison' else row['run'].rsplit('-', 1)[0] \
            if row['suite'] in ('ablation', 'old-search', 'seed-off') else row['run']
        grouped[row['suite'], variant, row['workload'], row['algorithm']].append(row)
    groups = []
    fields = ('optimizerSeconds', 'compileSeconds', 'containerMiB', 'solverEstimatedMiB',
              'firstUBSeconds', 'certificateSeconds', 'seedSeconds', 'messageSeconds',
              'propagationSeconds', 'searchSeconds', 'nodes', 'exactCells', 'messageCells',
              'seedEvaluations', 'variables', 'completedMessages', 'untouchedBuckets',
              'messageTouchedFactors', 'originalFactors', 'candidatePreparations', 'memoryRejections')
    for key, values in sorted(grouped.items()):
        group = dict(zip(('suite', 'variant', 'workload', 'algorithm'), key))
        group.update(attempts=len(values), passed=sum(v['status']=='passed' for v in values))
        for field in fields:
            numbers = [v[field] for v in values if v['status']=='passed' and v.get(field) is not None]
            group[field] = {'median': median(numbers), 'min': min(numbers), 'max': max(numbers)} if numbers else None
        group['finalBounds'] = sorted({(v['lowerBound'], v['upperBound']) for v in values}, key=str)
        groups.append(group)
    args.root.mkdir(exist_ok=True, parents=True)
    (args.root/'report-data.json').write_text(json.dumps({'rows':rows, 'groups':groups}, indent=2)+'\n')
    with (args.root/'report-data.csv').open('w', newline='') as stream:
        scalar = [key for key in rows[0] if key != 'checkpoints'] if rows else []
        writer = csv.DictWriter(stream, scalar, extrasaction='ignore'); writer.writeheader(); writer.writerows(rows)
    lines = ['| suite | variant | workload | algorithm | passed/attempts | optimizer median [min,max] s | compile median s | container median MiB |',
             '|---|---|---|---|---:|---:|---:|---:|']
    for g in groups:
        opt=g['optimizerSeconds']; comp=g['compileSeconds']; mem=g['containerMiB']
        timing=f"{opt['median']:.4f} [{opt['min']:.4f},{opt['max']:.4f}]" if opt else '—'
        lines.append('| '+ ' | '.join([g['suite'], g['variant'], g['workload'], g['algorithm'],
                     f"{g['passed']}/{g['attempts']}", timing, f"{comp['median']:.4f}" if comp else '—',
                     f"{mem['median']:.1f}" if mem else '—'])+' |')
    (args.root/'report-tables.md').write_text('\n'.join(lines)+'\n')
    print(f'rows={len(rows)} groups={len(groups)}')


if __name__ == '__main__':
    main()
