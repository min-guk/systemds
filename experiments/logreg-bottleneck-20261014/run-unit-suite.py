from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
prior = (root.parent / 'optimizer-reuse-20261014/final-unit-command.txt').read_text().splitlines()
start = prior.index('org.junit.runner.JUnitCore') + 1
classes = prior[start:] + ['org.apache.sysds.hops.fedplanner.memo.MemoOptimizerDomainCacheTest']
cmd = prior[:1] + ['-ea'] + prior[1:start] + classes
(root / 'final-unit-command.txt').write_text('\n'.join(cmd) + '\n')
(root / 'final-unit-classes.txt').write_text('\n'.join(classes) + '\n')
with (root / 'final-unit.log').open('w') as log:
    result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT)
print('JUnit classes:', len(classes), 'exit:', result.returncode)
print('\n'.join((root / 'final-unit.log').read_text().splitlines()[-45:]))
raise SystemExit(result.returncode)
