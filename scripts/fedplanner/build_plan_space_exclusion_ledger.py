#!/usr/bin/env python3
"""Freeze ignored planner/placement tests so exclusions cannot disappear silently."""

import argparse
import hashlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/test/resources/fedplanner/plan-space/ignored-tests.json"
SEARCH_ROOTS = (
    ROOT / "src/test/java/org/apache/sysds/hops/fedplanner",
    ROOT / "src/test/java/org/apache/sysds/test/component/federated/placement",
)
IGNORE = re.compile(r'@Ignore\s*\(\s*"([^"]+)"\s*\)')
METHOD = re.compile(r'\bpublic\s+void\s+(\w+)\s*\(')
CLASS = re.compile(r'\bpublic\s+class\s+(\w+)\b')


def build(roots=SEARCH_ROOTS):
    rows = []
    for root in roots:
        for path in sorted(root.rglob("*.java")):
            source = path.read_text()
            lines = source.splitlines()
            for index, line in enumerate(lines):
                if "@Ignore" not in line:
                    continue
                annotation = IGNORE.search(line)
                if annotation is None:
                    raise ValueError(f"ignored test has no literal reason: {path}:{index + 1}")
                following = "\n".join(lines[index + 1:index + 7])
                declaration = METHOD.search(following) or CLASS.search(following)
                if declaration is None:
                    raise ValueError(f"ignored test declaration not found: {path}:{index + 1}")
                rows.append({"file": str(path.relative_to(ROOT)), "symbol": declaration.group(1),
                             "reason": annotation.group(1), "line": index + 1,
                             "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                             "publicOnly": "PUBLIC" in annotation.group(1).upper()})
    rows.sort(key=lambda row: (row["file"], row["symbol"]))
    if len({(row["file"], row["symbol"]) for row in rows}) != len(rows):
        raise ValueError("duplicate ignored test identity")
    return {"schemaVersion": 1, "purpose": "excluded test inventory; never a workload PASS",
            "ignoredTests": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    value = build()
    content = json.dumps(value, indent=2, sort_keys=True) + "\n"
    if args.check:
        if not args.output.is_file() or args.output.read_text() != content:
            parser.error("ignored-test ledger drift: regenerate and review " + str(args.output))
        print(f"ignored-test ledger matches ({len(value['ignoredTests'])} exclusions)")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
        print(f"wrote {args.output} ({len(value['ignoredTests'])} exclusions)")


if __name__ == "__main__":
    main()
