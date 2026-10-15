#!/usr/bin/env python3
"""Build an isolated diagnostic overlay from the final engine; production sources stay untouched."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
BASE = HERE.parent / 'engine-v2'
OUTPUT = HERE / 'engine'
PACKAGE = Path('org/apache/sysds/hops/fedplanner/memo')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_once(text, old, new):
    assert text.count(old) == 1, old
    return text.replace(old, new, 1)


def main():
    if OUTPUT.exists():
        raise ValueError('Use a fresh diagnostic output; never overwrite a frozen engine')
    frozen = json.loads(BASE.with_name(BASE.name+'-sha256.json').read_text())
    for name in ['MemoOptimizer.java', 'FederatedPlanMemo.java']:
        path = REPO / 'src/main/java' / PACKAGE / name
        if sha(path) != frozen['sourceFiles'][str(path.relative_to(REPO))]:
            raise ValueError('Diagnostic source differs from the frozen baseline: '+name)
    source_root = HERE / 'instrumented-source'
    source_root.mkdir(exist_ok=False)
    OUTPUT.mkdir()
    for name in ['classes', 'lib']:
        shutil.copytree(BASE / name, OUTPUT / name)

    opt_path = REPO / 'src/main/java' / PACKAGE / 'MemoOptimizer.java'
    opt = opt_path.read_text()
    probe = ('MemoStructureProbe.factors(%s, Arrays.stream(domains).mapToInt(BitSet::cardinality).toArray(), '
             'checks.stream().map(c -> c.owners).toList(), terms.stream().map(c -> c.owners).toList());')
    opt = replace_once(opt, 'if(feasible) normalizeAfterPropagation();',
        'if(feasible) normalizeAfterPropagation();\n\t\tif(feasible) '
        + probe % '"after-propagation-normalization"')
    opt = replace_once(opt, 'boolean feasible = propagate()',
        probe % '"before-propagation"' + '\n\t\tboolean feasible = propagate()')
    anchor = '\t\t\t\tremaining.clear(best.owner); order.add(best);\n\t\t\t}\n\t\t\treturn true;'
    addition = '''
            System.err.println("[MemoEliminationShape] {\\"buckets\\":"+order.size()
                +",\\"maxSeparatorVariables\\":"+order.stream().mapToInt(b -> b.separator.length).max().orElse(0)
                +",\\"maxOutputRows\\":"+order.stream().mapToLong(b -> b.rowCount).max().orElse(0)
                +",\\"zeroSeparatorBuckets\\":"+order.stream().filter(b -> b.separator.length==0).count()
                +",\\"oneSeparatorBuckets\\":"+order.stream().filter(b -> b.separator.length==1).count()
                +",\\"plannedRows\\":"+plannedRows+"}");
'''
    opt = replace_once(opt, anchor, anchor.replace('\n\t\t\treturn true;', addition+'\t\t\treturn true;'))
    (source_root / 'MemoOptimizer.java').write_text(opt)

    fed_path = REPO / 'src/main/java' / PACKAGE / 'FederatedPlanMemo.java'
    fed = fed_path.read_text()
    fed = replace_once(fed, 'MemoSources sources = MemoSources.acquire(facts,pool);',
        'MemoStructureProbe.hops(facts.occurrences());\n\t\tMemoSources sources = MemoSources.acquire(facts,pool);')
    (source_root / 'FederatedPlanMemo.java').write_text(fed)
    shutil.copy2(HERE / 'MemoStructureProbe.java', source_root / 'MemoStructureProbe.java')
    command = ['javac', '--release', '17', '-cp', f'{BASE}/classes:{BASE}/lib/*',
               '-d', str(OUTPUT / 'classes'), *map(str, sorted(source_root.glob('*.java')))]
    subprocess.run(command, check=True)
    manifest = {'schema': 'memo-structure-diagnostic-engine/v1',
        'baseManifestSha256': sha(BASE.with_name(BASE.name+'-sha256.json')),
        'productionSources': {str(p.relative_to(REPO)):sha(p) for p in [opt_path,fed_path]},
        'instrumentedSources': {str(p.relative_to(HERE)):sha(p) for p in source_root.glob('*.java')},
        'compileCommand':command,
        'files':{str(p.relative_to(OUTPUT)):sha(p) for p in sorted(OUTPUT.rglob('*')) if p.is_file()}}
    OUTPUT.with_name(OUTPUT.name+'-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(OUTPUT)


if __name__ == '__main__':
    main()
