#!/usr/bin/env python3
"""Publish one immutable, hash-checked source/class snapshot for current P/E capture."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import tempfile

from run_current_pe_cell import class_tree_sha, tree_sha


SOURCE_DIRS = ('src/main/java', 'src/test/java', 'src/main/resources',
               'src/test/resources/fedplanner')
CLASS_DIRS = ('target/classes', 'target/test-classes', 'target/lib')


def freeze(source, destination):
    source = Path(source).resolve()
    destination = Path(destination).resolve()
    if destination.exists():
        raise FileExistsError('frozen build already exists: ' + str(destination))
    source_sha = tree_sha(source)
    class_sha = class_tree_sha(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=destination.name + '.tmp-',
                                       dir=destination.parent))
    try:
        shutil.copy2(source / 'pom.xml', temporary / 'pom.xml')
        for name in (*SOURCE_DIRS, *CLASS_DIRS):
            original = source / name
            if original.is_dir():
                shutil.copytree(original, temporary / name)
        if (tree_sha(source) != source_sha or class_tree_sha(source) != class_sha or
                tree_sha(temporary) != source_sha or class_tree_sha(temporary) != class_sha):
            raise ValueError('source/class bytes changed while freezing build')
        receipt = {'schema': 'current-pe-immutable-build-v1',
                   'createdUtc': datetime.now(timezone.utc).isoformat(),
                   'sourceRoot': str(source), 'buildRoot': str(destination),
                   'sourceTreeSha256': source_sha, 'classTreeSha256': class_sha}
        (temporary / 'build-freeze.json').write_text(
            json.dumps(receipt, sort_keys=True, indent=2) + '\n')
        os.replace(temporary, destination)
        return receipt
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--build-root', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(freeze(args.source_root, args.build_root), sort_keys=True))


if __name__ == '__main__':
    main()
