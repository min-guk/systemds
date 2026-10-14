from pathlib import Path
import hashlib
import json
import shutil
import subprocess

root = Path(__file__).resolve().parent
engine = root / 'after-engine'
engine.mkdir()
for src, dst in [('target/classes', 'classes'), ('target/test-classes', 'test-classes'),
                 ('target/lib', 'lib'), ('src/main/java', 'main-sources'),
                 ('src/test/java', 'test-sources')]:
    shutil.copytree(src, engine / dst)
manifest = {str(path.relative_to(engine)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(engine.rglob('*')) if path.is_file()}
(root / 'after-engine-sha256.json').write_text(json.dumps(manifest, indent=2) + '\n')
baseline = root.parent / 'optimizer-reuse-20261014/after-engine'
changed = {}
for subtree in ('main-sources', 'test-sources'):
    for path in sorted((engine / subtree).rglob('*.java')):
        relative = path.relative_to(engine)
        old = baseline / relative
        if not old.exists() or old.read_bytes() != path.read_bytes():
            changed[str(relative)] = {'before': hashlib.sha256(old.read_bytes()).hexdigest()
                                     if old.exists() else None,
                                     'after': hashlib.sha256(path.read_bytes()).hexdigest()}
(root / 'task-source-changes.json').write_text(json.dumps(changed, indent=2) + '\n')
with (root / 'task-main.diff').open('w') as output:
    result = subprocess.run(['diff', '-ru', str(baseline / 'main-sources'), str(engine / 'main-sources')],
                            stdout=output)
    if result.returncode not in (0, 1):
        raise SystemExit(result.returncode)
print('Frozen engine:', engine, 'files:', len(manifest), 'changed sources:', len(changed))
