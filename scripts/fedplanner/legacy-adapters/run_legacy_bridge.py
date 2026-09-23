#!/usr/bin/env python3
"""Run a version-matched, test-only native audit in an isolated Git worktree.

Rows deliberately remain NATIVE_AUDIT_ONLY until the shared physical-plan-v1
decoder proves a lossless cross-version identity. This tool never reports full
physical comparison completion on its own.
"""
import argparse
import gzip
import hashlib
import json
import subprocess
from pathlib import Path

VERSIONS = {
    "B0": ("ffb7be5bd85367156ed9ea86dacbaff4be0f035d", "ffb7be5"),
    "B1": ("d8fbd30b5476a1ceef460c9f3886381a369ac619", "d8fbd30b"),
}
JAVA_REL = Path("src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/LegacyClosedSpaceBridgeTest.java")
FACTORY_REL = Path("src/test/java/org/apache/sysds/test/component/federated/placement/shadow/ProductionShadowFixtureFactory.java")


def command(*args, cwd):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def file_sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_historical_test_sources(worktree, commit):
    changed = command("git", "status", "--porcelain", "--untracked-files=all",
                      "--", "src/test", cwd=worktree).splitlines()
    if any(line[3:] != str(JAVA_REL) for line in changed):
        raise SystemExit("LEGACY_TEST_SOURCE_MODIFIED")
    factory = worktree / FACTORY_REL
    committed = subprocess.run(["git", "show", f"{commit}:{FACTORY_REL.as_posix()}"],
                               cwd=worktree, check=True, capture_output=True).stdout
    if not factory.is_file() or hashlib.sha256(factory.read_bytes()).digest() != hashlib.sha256(committed).digest():
        raise SystemExit("LEGACY_FIXTURE_FACTORY_MODIFIED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", choices=VERSIONS, required=True)
    parser.add_argument("--worktree", type=Path, required=True)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--dml", type=Path,
                        help="frozen real DML source; compiled by the pinned historical parser")
    parser.add_argument("--source", choices=("P", "E"), required=True)
    parser.add_argument("--start", type=int, required=True)
    parser.add_argument("--stop", type=int, required=True)
    parser.add_argument("--rows", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--compact-rows", action="store_true",
                        help="P only: keep full accepted rows and short rejected ordinal/status rows")
    args = parser.parse_args()
    if args.dml and args.fixture.startswith("B-"):
        parser.error("--dml requires a real-cell identifier, not a shadow fixture")
    if not args.dml and not args.fixture.startswith("B-"):
        parser.error("a real-cell identifier requires --dml")
    if args.start < 0 or args.stop < args.start:
        parser.error("invalid half-open range")
    if args.compact_rows and args.source != "P":
        parser.error("compact rows are supported for P only")

    expected, name = VERSIONS[args.version]
    worktree = args.worktree.resolve(strict=True)
    if command("git", "rev-parse", "HEAD", cwd=worktree) != expected:
        raise SystemExit("LEGACY_COMMIT_MISMATCH")
    if command("git", "status", "--porcelain", "--untracked-files=all", "--", "src/main", "pom.xml", cwd=worktree):
        raise SystemExit("LEGACY_PRODUCTION_SOURCE_MODIFIED")
    verify_historical_test_sources(worktree, expected)
    template = Path(__file__).resolve().parent / name / JAVA_REL
    dml = args.dml.resolve(strict=True) if args.dml else None
    dml_sha = file_sha(dml) if dml else None
    target = worktree / JAVA_REL
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(template.read_bytes())

    args.rows.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    catalog_path = args.receipt.with_name(args.receipt.name + ".catalog.json")
    subprocess.run([
        "mvn", "-q", "-Dtest=LegacyClosedSpaceBridgeTest",
        f"-Dlegacy.fixture={args.fixture}", f"-Dlegacy.source={args.source}",
        *([f"-Dlegacy.dml.path={dml}", f"-Dlegacy.dml.sha256={dml_sha}"] if dml else []),
        f"-Dlegacy.begin={args.start}", f"-Dlegacy.end={args.stop}",
        f"-Dlegacy.catalog.output={catalog_path.resolve()}",
        f"-Dlegacy.compact.rows={str(args.compact_rows).lower()}",
        f"-Dlegacy.output={args.rows.resolve()}", "test",
    ], cwd=worktree, check=True)
    verify_historical_test_sources(worktree, expected)

    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if (catalog.get("contract") != "legacy-source-catalog-v1" or
            catalog.get("version") != expected or catalog.get("fixture") != args.fixture or
            catalog.get("sourcePrivacy") != "PRIVATE_AGGREGATE" or
            not isinstance(catalog.get("nodes"), list) or not catalog["nodes"] or
            not isinstance(catalog.get("orderedInputs"), list) or
            not isinstance(catalog.get("logicalInputs"), list) or
            not isinstance(catalog.get("constraints"), list)):
        raise SystemExit("LEGACY_SOURCE_CATALOG_INVALID")
    if dml and catalog.get("inputDmlSha256") != dml_sha:
        raise SystemExit("LEGACY_DML_CATALOG_SHA_MISMATCH")
    if dml and catalog.get("inputDmlPath") != str(dml):
        raise SystemExit("LEGACY_DML_CATALOG_PATH_MISMATCH")
    node_keys = {json.dumps(node["occurrence"], sort_keys=True) for node in catalog["nodes"]}
    if len(node_keys) != len(catalog["nodes"]):
        raise SystemExit("LEGACY_SOURCE_CATALOG_DUPLICATE_OCCURRENCE")
    for edge in catalog["orderedInputs"]:
        if (json.dumps(edge["producer"], sort_keys=True) not in node_keys or
                json.dumps(edge["consumer"], sort_keys=True) not in node_keys):
            raise SystemExit("LEGACY_SOURCE_CATALOG_UNRESOLVED_INPUT")
    for edge in catalog["logicalInputs"]:
        if any(json.dumps(edge[endpoint], sort_keys=True) not in node_keys
               for endpoint in ("source", "target") +
               (("boundary",) if edge["kind"] == "FUNCTION_INPUT" else ())):
            raise SystemExit("LEGACY_SOURCE_CATALOG_UNRESOLVED_LOGICAL_INPUT")

    counts = {"EMITTED": 0, "REJECTED": 0, "ERROR": 0}
    raw_count = None
    input_dml_sha = None
    expected_ordinal = args.start
    open_rows = gzip.open if args.rows.suffix == ".gz" else Path.open
    with open_rows(args.rows, "rt", encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row["version"] != expected or row["fixture"] != args.fixture or row["source"] != args.source:
                raise SystemExit("LEGACY_ROW_PROVENANCE_MISMATCH")
            if row.get("sourcePrivacy") != "PRIVATE_AGGREGATE":
                raise SystemExit("LEGACY_PRIVACY_INPUT_MISMATCH")
            dml_sha = row.get("inputDmlSha256")
            if not isinstance(dml_sha, str) or len(dml_sha) != 64:
                raise SystemExit("LEGACY_DML_SHA_MISSING")
            if input_dml_sha is None:
                input_dml_sha = dml_sha
            elif input_dml_sha != dml_sha:
                raise SystemExit("LEGACY_DML_SHA_DRIFT")
            if row["comparisonLevel"] != "NATIVE_AUDIT_ONLY":
                raise SystemExit("LEGACY_UNEXPECTED_COMPARISON_LEVEL")
            if row["status"] == "EMITTED" and any(
                    json.dumps(choice["occurrence"], sort_keys=True) not in node_keys
                    for choice in row["choices"]):
                raise SystemExit("LEGACY_ROW_UNRESOLVED_OCCURRENCE")
            if row["status"] != "EMITTED" and args.compact_rows and "choices" in row:
                raise SystemExit("LEGACY_COMPACT_REJECTED_ROW_HAS_CHOICES")
            if int(row["ordinal"]) != expected_ordinal:
                raise SystemExit("LEGACY_ORDINAL_GAP_OR_OVERLAP")
            expected_ordinal += 1
            if raw_count is None:
                raw_count = int(row["rawCount"])
            elif raw_count != int(row["rawCount"]):
                raise SystemExit("LEGACY_RAW_COUNT_DRIFT")
            counts[row["status"]] += 1
    if expected_ordinal != args.stop:
        raise SystemExit("LEGACY_TRUNCATED_RANGE")
    if input_dml_sha is not None and input_dml_sha != catalog.get("inputDmlSha256"):
        raise SystemExit("LEGACY_SOURCE_CATALOG_DML_MISMATCH")
    if dml and (input_dml_sha != dml_sha or file_sha(dml) != dml_sha):
        raise SystemExit("LEGACY_DML_CHANGED_DURING_CAPTURE")
    receipt = {
        "contract": "legacy-native-audit-v1",
        "version": expected,
        "fixture": args.fixture,
        "source": args.source,
        "sourcePrivacy": "PRIVATE_AGGREGATE",
        "inputDmlSha256": input_dml_sha,
        "inputDmlPath": str(dml) if dml else None,
        "start": args.start,
        "stop": args.stop,
        "rawCount": raw_count,
        "processed": expected_ordinal - args.start,
        "emitted": counts["EMITTED"],
        "rejected": counts["REJECTED"],
        "errors": counts["ERROR"],
        "nativeCoverage": "COMPLETE" if raw_count == args.stop and args.start == 0 and not counts["ERROR"] else "PARTIAL",
        "physicalDecode": "LEGACY_REPRESENTATION_LIMIT",
        "comparisonLevel": "NATIVE_AUDIT_ONLY",
        "rowsSha256": file_sha(args.rows),
        "rowsPath": str(args.rows.resolve()),
        "rowMode": "COMPACT_REJECTIONS" if args.compact_rows else "FULL",
        "sourceCatalogPath": str(catalog_path.resolve()),
        "sourceCatalogSha256": file_sha(catalog_path),
        "bridgeSha256": file_sha(template),
        "fixtureFactorySha256": file_sha(worktree / "src/test/java/org/apache/sysds/test/component/federated/placement/shadow/ProductionShadowFixtureFactory.java"),
        "pomSha256": file_sha(worktree / "pom.xml"),
        "javaVersion": subprocess.run(
            ["java", "-version"], cwd=worktree, check=True, text=True, capture_output=True).stderr.splitlines()[0],
        "productionCommit": expected,
    }
    args.receipt.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    main()
