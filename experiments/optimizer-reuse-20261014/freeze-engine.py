from pathlib import Path
import hashlib,json,shutil,subprocess
root=Path(__file__).resolve().parent
engine=root/'after-engine'
engine.mkdir()
for src,dst in [('target/classes','classes'),('target/test-classes','test-classes'),('target/lib','lib'),('src/main/java','main-sources'),('src/test/java','test-sources')]:
    shutil.copytree(src,engine/dst)
manifest={}
for path in sorted(engine.rglob('*')):
    if path.is_file():
        manifest[str(path.relative_to(engine))]=hashlib.sha256(path.read_bytes()).hexdigest()
(root/'after-engine-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n')
(root/'after-production.diff').write_bytes(subprocess.check_output(['git','diff','--','src/main/java','src/test/java']))
print('Frozen engine:',engine,'files:',len(manifest))
