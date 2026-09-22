#!/usr/bin/env python3
"""Store a content-addressed snapshot of available certification inputs.

This bundle preserves only files present in the current checkout. It does not
fill missing worker data, compiler/runtime contexts, or plan-set exporters.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile


SYSTEMDS = Path(__file__).resolve().parents[2]
EVALUATION = SYSTEMDS.parent / "cofee-evaluation"
LEGACY = SYSTEMDS.parent / "COFEE-Experiment"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def add_file(entries, label, root, relative, expected=None):
    root = Path(root).resolve()
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError("missing or escaping bundle input: " + label)
    actual = sha256(path)
    if expected is not None and actual != expected:
        raise ValueError("bundle input hash drift: " + label)
    previous = entries.setdefault(label, {"sha256": actual, "bytes": path.stat().st_size,
                                           "source": str(path)})
    if previous["sha256"] != actual:
        raise ValueError("same bundle identity has different bytes: " + label)


def collect(systemds=SYSTEMDS, evaluation=EVALUATION, legacy=LEGACY):
    systemds, evaluation, legacy = map(Path, (systemds, evaluation, legacy))
    inventory_rel = "src/test/resources/fedplanner/plan-space/workloads.json"
    ledger_rel = "src/test/resources/fedplanner/plan-space/rule-ledger.json"
    ignored_rel = "src/test/resources/fedplanner/plan-space/ignored-tests.json"
    manifest_rel = "plan-space-cells.json"
    inventory = json.loads((systemds / inventory_rel).read_text())
    ledger = json.loads((systemds / ledger_rel).read_text())
    ignored = json.loads((systemds / ignored_rel).read_text())
    manifest = json.loads((evaluation / manifest_rel).read_text())
    entries = {}
    for name in (inventory_rel, ledger_rel, ignored_rel):
        add_file(entries, "systemds/" + name, systemds, name)
    add_file(entries, "evaluation/" + manifest_rel, evaluation, manifest_rel)
    if manifest.get("discovery", {}).get("sha256") != sha256(systemds / inventory_rel):
        raise ValueError("manifest/discovery snapshot differs")
    for cell in manifest["cells"]:
        for name, expected in cell.get("files", {}).items():
            add_file(entries, "evaluation/" + name, evaluation, name, expected)
    for name, expected in inventory["sources"].items():
        if name.startswith("legacy:"):
            add_file(entries, "legacy/" + name[len("legacy:"):], legacy,
                     name[len("legacy:"):], expected)
        else:
            add_file(entries, "evaluation/" + name, evaluation, name, expected)
    for name, expected in inventory["discoveredSourceFiles"].items():
        prefix, relative = name.split(":", 1)
        if prefix not in ("evaluation", "legacy"):
            raise ValueError("unknown discovered source root: " + prefix)
        add_file(entries, prefix + "/" + relative,
                 evaluation if prefix == "evaluation" else legacy, relative, expected)
    for name, expected in ledger["auditedSourceFiles"].items():
        add_file(entries, "systemds/" + name, systemds, name, expected)
    for row in ignored["ignoredTests"]:
        add_file(entries, "systemds/" + row["file"], systemds,
                 row["file"], row["sourceSha256"])
    for path in sorted((systemds / "scripts/fedplanner").glob("*.py")):
        add_file(entries, "systemds/" + str(path.relative_to(systemds)), systemds,
                 path.relative_to(systemds))
    for path in sorted((evaluation / "calibration").glob("*plan_space*.py")):
        add_file(entries, "evaluation/" + str(path.relative_to(evaluation)), evaluation,
                 path.relative_to(evaluation))
    for relative in ("pom.xml", "scripts/fedplanner/run_plan_space_certification.sh"):
        add_file(entries, "systemds/" + relative, systemds, relative)
    for relative in (
            "src/test/java/org/apache/sysds/test/component/federated/placement/oracle/semantic",
            "src/test/java/org/apache/sysds/test/component/federated/placement/oracle/independence",
            "src/test/java/org/apache/sysds/test/component/federated/placement/shadow",
            "src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact",
            "src/test/java/org/apache/sysds/hops/fedplanner/placement"):
        for path in sorted((systemds / relative).glob("*.java")):
            name = str(path.relative_to(systemds))
            add_file(entries, "systemds/" + name, systemds, name)
    for root, prefix, relative in ((systemds, "systemds", "scripts/fedplanner/tests"),
                                   (evaluation, "evaluation", "calibration/tests")):
        for path in sorted((root / relative).glob("test_*plan_space*.py")):
            name = str(path.relative_to(root))
            add_file(entries, prefix + "/" + name, root, name)
    return entries


def freeze(output, entries):
    output = Path(output)
    objects = output / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    index = {"schemaVersion": 1, "status": "UNKNOWN", "purpose": "available input-byte snapshot only",
             "unresolved": ["worker data bytes", "runtime FederationMaps", "compiler context",
                            "independent full plan grammar", "P/E/R adapters"],
             "files": {name: {"sha256": row["sha256"], "bytes": row["bytes"]}
                       for name, row in sorted(entries.items())}}
    path = output / "bundle-index.json"
    if path.is_file():
        if json.loads(path.read_text()) != index:
            raise ValueError("frozen bundle differs from current inputs; use a new output directory")
        check(output)
        return path
    for row in entries.values():
        target = objects / row["sha256"]
        if target.is_file():
            if sha256(target) != row["sha256"]:
                raise ValueError("existing bundle object corrupt: " + target.name)
            continue
        with tempfile.NamedTemporaryFile(dir=objects, prefix=".tmp-", delete=False) as stream:
            temporary = Path(stream.name)
            with Path(row["source"]).open("rb") as source:
                shutil.copyfileobj(source, stream)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            if sha256(temporary) != row["sha256"]:
                raise ValueError("bundle source changed during copy: " + row["source"])
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=output, prefix=".tmp-", delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(index, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    return path


def check(output):
    output = Path(output)
    index = json.loads((output / "bundle-index.json").read_text())
    if index.get("schemaVersion") != 1 or index.get("status") != "UNKNOWN":
        raise ValueError("bundle cannot claim plan-space PASS")
    for name, row in index["files"].items():
        if not re.fullmatch(r"[0-9a-f]{64}", row.get("sha256", "")):
            raise ValueError("invalid bundle object identity: " + name)
        path = output / "objects" / row["sha256"]
        if not path.is_file() or path.stat().st_size != row["bytes"] or sha256(path) != row["sha256"]:
            raise ValueError("bundle object missing or corrupt: " + name)
    return {"integrity": "PASS", "planCoverage": "UNKNOWN",
            "files": len(index["files"]),
            "objects": len({row["sha256"] for row in index["files"].values()})}


def check_current(output):
    current = collect()
    frozen = json.loads((Path(output) / "bundle-index.json").read_text())["files"]
    expected = {name: {"sha256": row["sha256"], "bytes": row["bytes"]}
                for name, row in sorted(current.items())}
    if frozen != expected:
        raise ValueError("frozen bundle differs from current inputs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--check-current", action="store_true",
                        help="also require every frozen input to match the current checkout")
    args = parser.parse_args()
    try:
        if not args.check:
            freeze(args.output, collect())
        result = check(args.output)
        if args.check_current:
            check_current(args.output)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        result = {"integrity": "FAIL", "reason": str(error)}
    print(json.dumps(result, sort_keys=True))
    return 0 if result["integrity"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
