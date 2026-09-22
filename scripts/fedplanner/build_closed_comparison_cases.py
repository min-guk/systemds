#!/usr/bin/env python3
"""Pin the complete discovered-cell denominator before native comparisons."""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT.parent / 'cofee-evaluation/plan-space-cells.json'
OUTPUT = ROOT / 'src/test/resources/fedplanner/plan-space/closed-comparison-cases.json'
DECISIONS = ROOT / 'src/test/resources/fedplanner/plan-space/closed-comparison-applicability.json'
VERSIONS = {'B0': 'ffb7be5bd85367156ed9ea86dacbaff4be0f035d',
            'B1': 'd8fbd30b5476a1ceef460c9f3886381a369ac619',
            'C0': 'SOURCE_SNAPSHOT_REQUIRED'}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(source=SOURCE, decisions=DECISIONS):
    source = Path(source)
    backlog = json.loads(source.read_text())
    if backlog.get('schemaVersion') != 1:
        raise ValueError('unexpected discovery backlog schema')
    cells = []
    for row in backlog['cells']:
        cell = {'id': row['id'], 'discoveryId': row['discoveryId'],
                'inventoryStatus': row['status'], 'conditionId': row.get('conditionId'),
                'sourceFiles': row.get('files', {}),
                'sourceBinding': row.get('binding'),
                'nativeInputCapture': 'PENDING', 'expectedPairs': []}
        if row['status'] == 'IN_SCOPE':
            cell['expectedPairs'] = [
                {'left': 'P_C0', 'right': 'E_C0', 'role': 'CURRENT_PE',
                 'applicability': 'REQUIRED'}]
            cell['expectedPairs'].extend(
                {'left': left, 'right': right, 'role': role,
                 'applicability': 'UNDETERMINED'}
                for left, right, role in (
                    ('P_B0', 'P_C0', 'HISTORY'), ('P_B1', 'P_C0', 'HISTORY'),
                    ('E_B0', 'E_C0', 'HISTORY'), ('E_B1', 'E_C0', 'HISTORY'),
                    ('P_B0', 'E_B0', 'WITHIN_VERSION'),
                    ('P_B1', 'E_B1', 'WITHIN_VERSION')))
        else:
            cell['inventoryReason'] = row['reason']
        cells.append(cell)
    decisions = Path(decisions)
    if decisions.is_file():
        ledger = json.loads(decisions.read_text())
        if (ledger.get('schema') != 'closed-comparison-applicability-v1' or
                ledger.get('discoveryBacklogSha256') != sha(source) or
                not isinstance(ledger.get('decisions'), list)):
            raise ValueError('applicability ledger does not bind the discovery backlog')
        by_id = {cell['id']: cell for cell in cells}
        seen = set()
        for decision in ledger['decisions']:
            key = (decision.get('cellId'), decision.get('left'),
                   decision.get('right'), decision.get('role'))
            if key in seen:
                raise ValueError('duplicate applicability decision')
            seen.add(key)
            cell = by_id.get(key[0])
            matches = [] if cell is None else [pair for pair in cell['expectedPairs']
                if (pair['left'], pair['right'], pair['role']) == key[1:]]
            if len(matches) != 1 or matches[0]['applicability'] != 'UNDETERMINED':
                raise ValueError('applicability decision has no unresolved historical pair')
            if (decision.get('applicability') not in ('REQUIRED', 'NATIVE_UNSUPPORTED') or
                    not isinstance(decision.get('evidence'), str) or not decision['evidence'].strip()):
                raise ValueError('historical applicability decision lacks evidence')
            if decision['applicability'] == 'NATIVE_UNSUPPORTED':
                files = decision.get('evidenceFiles')
                if (not isinstance(files, dict) or not files or
                        any(not Path(name).is_absolute() or not Path(name).is_file() or
                            sha(name) != digest for name, digest in files.items())):
                    raise ValueError('native unsupported evidence files absent or changed')
                matches[0]['evidenceFiles'] = files
            matches[0]['applicability'] = decision['applicability']
            matches[0]['evidence'] = decision['evidence']
    if len(cells) != len({row['id'] for row in cells}):
        raise ValueError('duplicate discovered cell')
    return {'schema': 'closed-comparison-cases-v1', 'discoveryBacklogSha256': sha(source),
            'applicabilityLedgerSha256': sha(decisions) if decisions.is_file() else None,
            'discoveryInventorySha256': backlog['discovery']['sha256'],
            'versions': VERSIONS, 'cells': cells,
            'status': 'INCOMPLETE',
            'unresolved': ['C0 source snapshot', 'native input capture',
                           'historical applicability', 'physical full identity',
                           'required pair adapters and relation coverage']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=SOURCE)
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--decisions', type=Path, default=DECISIONS)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = json.dumps(build(args.source, args.decisions), indent=2, sort_keys=True) + '\n'
    if args.check:
        if not args.output.is_file() or args.output.read_text() != content:
            raise SystemExit('closed comparison denominator changed')
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
    print(json.dumps({'cells': len(json.loads(content)['cells']), 'status': 'INCOMPLETE',
                      'output': str(args.output)}))


if __name__ == '__main__':
    main()
