#!/usr/bin/env python3
"""Audit original-cost certificates and immutable input identities in recorded Docker runs."""
import argparse
from collections import Counter
from decimal import Decimal, localcontext
import hashlib
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    paths = sorted(args.root.glob("comparison-r1/repeat-*/summary.json"))
    paths += sorted(args.root.glob("comparison-r1-*/summary.json"))
    baseline = {}
    for path in paths:
        for row in json.loads(path.read_text())["compileRows"]:
            if row["algorithm"] == "EXACT" and row["status"] == "passed":
                key = row["sourceSha256"]
                costs = [Decimal(p["cost"]) for p in row["memoPlans"]]
                if key in baseline:
                    assert baseline[key] == costs, (path, "EXACT objective changed")
                baseline[key] = costs
    status = Counter()
    certificates = Counter()
    points = 0
    attempts = []
    manifest_hashes = set()
    with localcontext() as context:
        context.prec = 300
        for path in paths:
            data = json.loads(path.read_text())
            manifest = json.loads((path.parent / "manifest.json").read_text())
            manifest_hashes.add(manifest["engineValidation"]["manifestSha256"])
            for row in data["compileRows"]:
                status[row["status"]] += 1
                optimum = baseline[row["sourceSha256"]]
                certs = row.get("dualCertificates", []) or row.get("treeCertificates", [])
                for index, cert in enumerate(certs):
                    def value(item, field):
                        return None if item.get(field) in (None, "null") else Decimal(item[field])
                    previous_lb = previous_ub = None
                    for point in cert.get("progression", []) + [cert]:
                        lb, ub = value(point, "lowerBound"), value(point, "upperBound")
                        if lb is not None:
                            assert lb <= optimum[index], (path, row["algorithm"], "invalid LB")
                            if previous_lb is not None:
                                assert lb >= previous_lb
                            previous_lb = lb
                        if ub is not None:
                            assert optimum[index] <= ub, (path, row["algorithm"], "invalid UB")
                            if previous_ub is not None:
                                assert ub <= previous_ub
                            previous_ub = ub
                    points += len(cert.get("progression", []))
                    certificates[cert["status"]] += 1
                    if cert["certified"]:
                        lb, ub = value(cert, "lowerBound"), value(cert, "upperBound")
                        assert lb is not None and ub is not None
                        if cert["status"] == "OPTIMAL":
                            assert lb == ub == optimum[index]
                        else:
                            assert cert["status"] == "EPSILON_OPTIMAL"
                            assert "LOCAL" in row["algorithm"]
                            assert lb > 0 and ub <= Decimal("1.05") * lb
                        assert row["status"] == "passed"
                        assert Decimal(row["memoPlans"][index]["cost"]) == ub
                    else:
                        assert row["status"] == "failed"
                if row["status"] == "passed":
                    assert row["compile"]["verified"]
                    if row["algorithm"] != "EXACT":
                        assert certs and all(c["certified"] for c in certs)
                attempts.append({"summary": str(path), "workload": row["workload"],
                    "algorithm": row["algorithm"], "heapMiB": row["compileHeapMiB"],
                    "status": row["status"], "failure": row.get("failureCategory")})
    assert len(manifest_hashes) == 1
    manifest_path = args.root / "engine-v1-sha256.json"
    assert hashlib.sha256(manifest_path.read_bytes()).hexdigest() in manifest_hashes
    result = {"schema": "memo-dual-recorded-results-audit/v1", "summaryCount": len(paths),
        "compileAttempts": len(attempts), "attemptStatuses": dict(status),
        "certificateStatuses": dict(certificates), "publishedBoundPointsChecked": points,
        "sameSourceExactOptimumGroups": len(baseline), "engineManifestSha256": next(iter(manifest_hashes)),
        "allPublishedBoundsValidAndMonotone": True, "allSuccessfulCertificatesValidated": True,
        "attempts": attempts,
        "scope": "Recorded Docker results, original-cost intervals and DML identity; does not rerun solver or certify runtime feasibility independently."}
    destination = args.root / "result-validation.json"
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "attempts"}, indent=2))


if __name__ == "__main__":
    main()
