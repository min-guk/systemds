#!/usr/bin/env python3
"""Build a deterministic COFEE engine JAR from a pinned base and frozen classes."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

FIXED_TIME = (2026, 10, 8, 0, 0, 0)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build(base_jar: Path, classes: Path, output_jar: Path) -> dict:
    with zipfile.ZipFile(base_jar) as archive:
        base = {info.filename: archive.read(info) for info in archive.infolist()
                if not info.is_dir()}
    overlay = {path.relative_to(classes).as_posix(): path.read_bytes()
               for path in sorted(classes.rglob("*")) if path.is_file()}
    if "META-INF/MANIFEST.MF" not in base:
        raise ValueError("pinned base JAR has no manifest")
    if "META-INF/MANIFEST.MF" in overlay:
        raise ValueError("frozen classes must not replace the pinned base manifest")
    entries = {**base, **overlay}
    output_jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_jar, "w", compression=zipfile.ZIP_DEFLATED,
                         compresslevel=9) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED,
                             compresslevel=9)
    with zipfile.ZipFile(output_jar) as archive:
        corrupt = archive.testzip()
        if corrupt is not None:
            raise ValueError(f"result JAR failed ZIP validation at {corrupt}")
        if archive.read("META-INF/MANIFEST.MF") != base["META-INF/MANIFEST.MF"]:
            raise ValueError("base manifest changed")
        for name, content in overlay.items():
            if archive.read(name) != content:
                raise ValueError(f"overlay byte mismatch: {name}")
    return {
        "schema": "cofee-frozen-engine-artifact/v1",
        "baseJar": str(base_jar.resolve()),
        "baseJarSha256": sha256(base_jar),
        "classes": str(classes.resolve()),
        "artifactJar": str(output_jar.resolve()),
        "artifactJarSha256": sha256(output_jar),
        "baseEntries": len(base),
        "overlayEntries": len(overlay),
        "replacedEntries": len(base.keys() & overlay.keys()),
        "addedEntries": len(overlay.keys() - base.keys()),
        "resultEntries": len(entries),
        "baseManifestPreserved": True,
        "fixedZipTimestamp": "2026-10-08T00:00:00Z",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-jar", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--output-jar", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    if not args.base_jar.is_file() or not args.classes.is_dir():
        parser.error("base JAR and classes directory must exist")
    result = build(args.base_jar, args.classes, args.output_jar)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
