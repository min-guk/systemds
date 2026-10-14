#!/usr/bin/env python3
"""Verify a candidate engine manifest and its Docker frozen-input snapshot."""
import argparse
import hashlib
import json
from pathlib import Path


def hashes(root):
    root = Path(root)
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(root.rglob("*")) if path.is_file()}


def compare(left, right):
    lh, rh = hashes(left), hashes(right)
    return {"leftFiles": len(lh), "rightFiles": len(rh),
            "missing": sorted(set(lh) - set(rh)), "extra": sorted(set(rh) - set(lh)),
            "hashMismatches": sorted(key for key in set(lh) & set(rh) if lh[key] != rh[key]),
            "passed": lh == rh}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--run-frozen", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()
    engine = Path(args.engine)
    expected = json.loads(Path(args.manifest).read_text())
    actual = hashes(engine)
    manifest = {"expectedFiles": len(expected), "actualFiles": len(actual),
                "missing": sorted(set(expected) - set(actual)), "extra": sorted(set(actual) - set(expected)),
                "hashMismatches": sorted(k for k in set(expected) & set(actual) if expected[k] != actual[k])}
    manifest["passed"] = not manifest["missing"] and not manifest["extra"] and not manifest["hashMismatches"]
    frozen = Path(args.run_frozen)
    inputs = {
        "mainClasses": compare(engine / "classes", frozen / "main-classes"),
        "testClasses": compare(engine / "test-classes", frozen / "test-classes"),
        "mainSources": compare(engine / "main-sources", frozen / "main-sources"),
        "testSources": compare(engine / "test-sources", frozen / "test-sources"),
    }
    out = {"schema": "logreg-under10-frozen-verification-v1", "manifest": manifest,
           "runInputs": inputs, "passed": manifest["passed"] and all(v["passed"] for v in inputs.values())}
    Path(args.output).write_text(json.dumps(out, indent=2) + "\n")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
