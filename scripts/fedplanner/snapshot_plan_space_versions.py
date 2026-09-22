#!/usr/bin/env python3
"""Freeze current dirty source plus workload inputs for a closed-model campaign.

The source tree is copied byte-for-byte before builds or exporters run.  This
does not certify compiler inputs that are absent from the available files.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BASELINES = {
    'B0': 'ffb7be5bd85367156ed9ea86dacbaff4be0f035d',
    'B1': 'd8fbd30b5476a1ceef460c9f3886381a369ac619',
}


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def files(root):
    root = Path(root).resolve()
    tracked = git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard')
    paths = sorted({raw.decode('utf-8') for raw in tracked.split(b'\0') if raw})
    result = {}
    for name in paths:
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError('escaping or missing source: ' + name)
        result[name] = {'sha256': sha(path), 'bytes': path.stat().st_size}
    return result


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def build_index(roots):
    current = Path(roots['systemds']).resolve()
    if git(current, 'rev-parse', 'HEAD').decode().strip() == BASELINES['B0']:
        raise ValueError('current checkout unexpectedly at B0')
    if git(current, 'rev-parse', BASELINES['B1'] + '^').decode().strip() != BASELINES['B0']:
        raise ValueError('historical baseline ancestry changed')
    index = {'schema': 'closed-comparison-source-snapshot-v1',
             'baselineCommits': BASELINES,
             'currentHead': git(current, 'rev-parse', 'HEAD').decode().strip(),
             'roots': {label: {'origin': str(Path(root).resolve()), 'files': files(root)}
                       for label, root in sorted(roots.items())}}
    index['contentSha256'] = fingerprint({label: row['files'] for label, row in index['roots'].items()})
    return index


def create(output, roots):
    output = Path(output).resolve()
    index = build_index(roots)
    destination = output / index['contentSha256']
    if destination.exists():
        check(destination)
        if json.loads((destination / 'source-index.json').read_text()) != index:
            raise ValueError('snapshot provenance differs for same content')
        return destination
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output, prefix='.snapshot-') as staging_name:
        staging = Path(staging_name)
        for label, row in index['roots'].items():
            root = Path(row['origin'])
            for relative, meta in row['files'].items():
                source = root / relative
                target = staging / 'trees' / label / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
                if sha(source) != meta['sha256'] or sha(target) != meta['sha256']:
                    raise ValueError('source drift during snapshot: ' + label + '/' + relative)
        if build_index(roots) != index:
            raise ValueError('source set or bytes drifted during snapshot')
        (staging / 'source-index.json').write_text(json.dumps(index, sort_keys=True, indent=2) + '\n')
        staging.rename(destination)
    check(destination)
    return destination


def check(destination):
    destination = Path(destination).resolve()
    index = json.loads((destination / 'source-index.json').read_text())
    if index.get('schema') != 'closed-comparison-source-snapshot-v1':
        raise ValueError('snapshot schema changed')
    if destination.name != index['contentSha256'] or index['contentSha256'] != fingerprint(
            {label: row['files'] for label, row in index['roots'].items()}):
        raise ValueError('snapshot content binding changed')
    for label, row in index['roots'].items():
        root = destination / 'trees' / label
        actual = {path.relative_to(root).as_posix() for path in root.rglob('*')
                  if path.is_file() and path.relative_to(root).parts[0] != 'target'
                  and '__pycache__' not in path.relative_to(root).parts
                  and path.suffix != '.pyc'}
        if actual != set(row['files']):
            raise ValueError('snapshot file inventory changed: ' + label)
        for relative, meta in row['files'].items():
            path = root / relative
            if path.stat().st_size != meta['bytes'] or sha(path) != meta['sha256']:
                raise ValueError('snapshot file corrupt: ' + label + '/' + relative)
    return {'status': 'COMPLETE', 'sourceContentSha256': index['contentSha256'],
            'files': sum(len(row['files']) for row in index['roots'].values())}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    freeze = sub.add_parser('create')
    freeze.add_argument('--output', required=True)
    freeze.add_argument('--systemds', default=str(ROOT))
    freeze.add_argument('--evaluation', default=str(ROOT.parent / 'cofee-evaluation'))
    freeze.add_argument('--legacy', default=str(ROOT.parent / 'COFEE-Experiment'))
    verify = sub.add_parser('check')
    verify.add_argument('--snapshot', required=True)
    args = parser.parse_args(argv)
    if args.action == 'create':
        path = create(args.output, {'systemds': args.systemds, 'evaluation': args.evaluation,
                                    'legacy': args.legacy})
        result = check(path)
        result['snapshot'] = str(path)
    else:
        result = check(args.snapshot)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
