#!/usr/bin/env python3
"""Prepare a minimal immutable COFEE engine repository around frozen classes."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "package_overlay", HERE / "package_cofee_engine_overlay.py")
OVERLAY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OVERLAY)

REQUIRED_SOURCE_FILES = (
    "pom.xml",
    "scripts/builtin/steplm.dml",
    "scripts/builtin/lmCG.dml",
    "src/test/java/org/apache/sysds/test/functions/federated/fedplanning/MatrixCampaignProbe.java",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(base_jar: Path, classes: Path, source_repo: Path, dependencies: Path,
            output_repo: Path, freeze_manifest: Path) -> dict:
    if output_repo.exists():
        raise ValueError("output engine repository already exists")
    if not dependencies.is_dir() or not list(dependencies.glob("*.jar")):
        raise ValueError("pinned dependency directory is missing JARs")
    for relative in REQUIRED_SOURCE_FILES:
        if not (source_repo / relative).is_file():
            raise ValueError(f"source repository input missing: {relative}")
    if not freeze_manifest.is_file():
        raise ValueError("freeze manifest is missing")

    output_repo.mkdir(parents=True)
    for relative in REQUIRED_SOURCE_FILES:
        destination = output_repo / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_repo / relative, destination)
    target = output_repo / "target"
    target.mkdir()
    (target / "lib").symlink_to(dependencies.resolve(), target_is_directory=True)
    artifact = target / "systemds-3.4.0-SNAPSHOT.jar"
    package = OVERLAY.build(base_jar, classes, artifact)
    result = {
        "schema": "cofee-frozen-engine-repository/v1",
        "artifactJar": str(artifact.resolve()),
        "artifactJarSha256": sha256(artifact),
        "classes": str(classes.resolve()),
        "classesFiles": sum(1 for path in classes.rglob("*") if path.is_file()),
        "dependencies": str(dependencies.resolve()),
        "dependencyJarCount": len(list(dependencies.glob("*.jar"))),
        "freezeManifest": str(freeze_manifest.resolve()),
        "freezeManifestSha256": sha256(freeze_manifest),
        "package": package,
        "probeSha256": sha256(output_repo / REQUIRED_SOURCE_FILES[-1]),
        "sourceRepo": str(source_repo.resolve()),
    }
    (output_repo / "ENGINE_ARTIFACT.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-jar", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--source-repo", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output-repo", type=Path, required=True)
    parser.add_argument("--freeze-manifest", type=Path, required=True)
    args = parser.parse_args()
    if not args.base_jar.is_file() or not args.classes.is_dir():
        parser.error("base JAR and frozen classes must exist")
    print(json.dumps(prepare(args.base_jar, args.classes, args.source_repo,
        args.dependencies, args.output_repo, args.freeze_manifest), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
