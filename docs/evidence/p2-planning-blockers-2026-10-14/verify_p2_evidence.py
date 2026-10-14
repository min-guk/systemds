#!/usr/bin/env python3
"""Verify copied P2 metadata/log evidence; not a workload/performance test.

Reads only report artifacts, never dataset payloads or workers. Rewrites only
analysis.json in this directory. The canonical arithmetic below is an explicit
report witness, not a replacement production allocator.
"""
import hashlib
import json
import re
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent
    manifest = json.loads((root / "manifest.json").read_text())
    for entry in manifest["files"]:
        assert hashlib.sha256((root / entry["path"]).read_bytes()).hexdigest() == entry["sha256"]

    cases = []
    for version, workers in [("c26", w) for w in (1, 3, 5, 7)] + [("candidate06", 3)]:
        base = root / f"{version}-w{workers}"
        receipt = json.loads((base / "render-receipt.json").read_text())
        result = json.loads((base / "result.json").read_text())
        dml = (base / "cell.dml").read_text()
        log = (base / "coordinator.log").read_text()
        inputs = []
        for item in receipt["inputs"]:
            n = item["rows"]
            q, r = divmod(n, workers)
            bounds = [i * q + min(i, r) for i in range(workers + 1)]
            actual = [shard["range"] for shard in item["shards"]]
            assert len(actual) == workers
            canonical = [[[bounds[i], 0], [bounds[i + 1], item["cols"]]] for i in range(workers)]
            variable = "Xraw" if item["role"] == "X" else "y"
            line = next(line for line in dml.splitlines() if line.startswith(variable + " = federated("))
            pairs = [[int(a), int(b)] for a, b in re.findall(r"list\((\d+),(\d+)\)", line)]
            assert [pairs[i:i + 2] for i in range(0, len(pairs), 2)] == actual
            assert actual[0][0] == [0, 0] and actual[-1][1] == [n, item["cols"]]
            assert all(a[1][0] == b[0][0] for a, b in zip(actual, actual[1:]))
            assert all(a[0][0] < a[1][0] and a[0][1] == 0 and a[1][1] == item["cols"] for a in actual)
            mismatch = [i for i, (a, b) in enumerate(zip(actual, canonical)) if a != b]
            assert mismatch == {1: [], 3: [1, 2], 5: [], 7: [5, 6]}[workers]
            inputs.append({"role": item["role"], "rows": n, "cols": item["cols"],
                           "actualRanges": actual, "canonicalRanges": canonical,
                           "mismatchedZeroBasedWorkerIndices": mismatch})
        assert result["status"] == "failed" and result["timing"]["fullCompileSeconds"] is None
        assert not result["harnessTimeout"]
        error = "UNKNOWN_SIZE" if workers in (1, 5) else "canonical ingress"
        assert error in log
        cases.append({"version": version, "workers": workers, "observedFailure": error,
                      "harnessWallSecondsNotCompletionTime": result["timing"]["harnessWallSeconds"],
                      "inputs": inputs})

    mtd = json.loads((root / "input-metadata/P2_features.data.mtd").read_text())
    assert (mtd["rows"], mtd["cols"], mtd["nnz"]) == (100000, 1001, 100100000)
    assert not any("distinct" in key.lower() or "domain" in key.lower() for key in mtd)

    # Logical witness only: identical local counts do not determine global union.
    domains = [[[1, 2], [1, 2], [1, 2]], [[1, 2], [2, 3], [3, 4]]]
    witnesses = []
    for shards in domains:
        counts = [len(set(s)) for s in shards]
        distinct = len(set().union(*map(set, shards)))
        witnesses.append({"localCounts": counts, "globalDistinct": distinct,
                          "dummycodeWidthWith1001InputColumns": 1000 + max(1, distinct)})
    assert witnesses[0]["localCounts"] == witnesses[1]["localCounts"] == [2, 2, 2]
    assert [w["globalDistinct"] for w in witnesses] == [2, 4]
    out = {"scope": "static metadata and log checks, not new SystemDS execution",
           "cases": cases, "featureMetadataKeys": sorted(mtd),
           "logicalDistinctWitnessNotActualP2Domain": witnesses}
    (root / "analysis.json").write_text(json.dumps(out, indent=2) + "\n")
    print("PASS: five historical receipts; exact range mismatches W3=[1,2], W7=[5,6]; local distinct witness")


if __name__ == "__main__":
    main()
