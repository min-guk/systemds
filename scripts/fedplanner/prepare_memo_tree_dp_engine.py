#!/usr/bin/env python3
"""Freeze an already compiled Tree DP engine and its exact source/binary provenance."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[2]


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    manifest = output.with_name(output.name + "-sha256.json")
    if output.exists() or manifest.exists():
        raise ValueError("Use a new engine path; frozen artifacts are never overwritten")
    required = ["classes/org/apache/sysds/hops/fedplanner/memo/MemoTreeDpOptimizer.class",
                "test-classes/org/apache/sysds/hops/fedplanner/memo/MemoTreeDpBenchmark.class",
                "test-classes/org/apache/sysds/hops/fedplanner/memo/MemoTreeDpTrainingRuntimeTest.class",
                "lib/junit-4.13.1.jar"]
    for relative in required:
        if not (REPO / "target" / relative).is_file():
            raise ValueError("Run mvn test-compile first: missing " + relative)
    output.mkdir(parents=True)
    for directory in ("classes", "test-classes", "lib"):
        shutil.copytree(REPO / "target" / directory, output / directory, symlinks=False)
    files = {p.relative_to(output).as_posix(): digest(p)
             for p in sorted(output.rglob("*")) if p.is_file()}
    sources = {p.relative_to(REPO).as_posix(): digest(p)
               for root in ("src/main/java", "src/test/java")
               for p in sorted((REPO / root).rglob("*.java"))}
    manifest.write_text(json.dumps({"schema": "memo-tree-dp-frozen-engine/v1",
        "repositoryBase": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
        "sourceFiles": sources, "files": files}, indent=2, sort_keys=True) + "\n")
    print(manifest)


if __name__ == "__main__":
    main()
