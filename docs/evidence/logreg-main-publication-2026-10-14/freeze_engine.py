from pathlib import Path
import argparse
import hashlib
import json
import shutil


parser = argparse.ArgumentParser()
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--manifest', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
for source, destination in [('target/classes', 'classes'), ('target/test-classes', 'test-classes'),
                            ('target/lib', 'lib'), ('src/main/java', 'main-sources'),
                            ('src/test/java', 'test-sources')]:
    shutil.copytree(source, args.output / destination)
manifest = {str(path.relative_to(args.output)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(args.output.rglob('*')) if path.is_file()}
args.manifest.write_text(json.dumps(manifest, indent=2) + '\n')
print('Frozen files:', len(manifest), 'engine:', args.output)
