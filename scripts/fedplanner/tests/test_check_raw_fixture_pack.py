import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "check_raw_fixture_pack.py"
spec = importlib.util.spec_from_file_location("check_raw_fixture_pack", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class RawFixturePackTest(unittest.TestCase):
    def test_complete_unknown_archive_rechecks_and_corruption_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "adapter.java"
            source.write_text("frozen adapter\n")
            folder = root / "E-B-21-full-model"
            folder.mkdir()
            rows = []
            audits = []
            for index in range(2):
                row = {"schema": "raw-fixture-export-v1", "ordinal": index,
                       "globalCoverageStatus": "UNKNOWN"}
                rows.append(json.dumps(row).encode() + b"\n")
                audits.append(json.dumps({"index": index, "status": "UNKNOWN", "raw": row}).encode() + b"\n")
            packed = {"schema": "plan-space-raw-fixture-pack-v1", "status": "UNKNOWN", "files": {}}
            receipt = {"schema": "raw-fixture-export-v1", "status": "UNKNOWN", "kind": "E",
                       "fixture": "B-21", "rawDomainSize": "2", "start": "0", "stop": "2",
                       "processed": 2}
            for name, key, lines in (("rows.jsonl", "rowsSha256", rows),
                                     ("audit.jsonl", "auditSha256", audits)):
                raw = b"".join(lines)
                archive = folder / (name + ".gz")
                with archive.open("wb") as output:
                    with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as zipped:
                        zipped.write(raw)
                receipt[key] = digest(raw)
                packed["files"][name] = {"archive": archive.name, "archiveSha256": digest(archive.read_bytes()),
                                          "rawSha256": digest(raw), "rawBytes": len(raw)}
            (folder / "receipt.json").write_text(json.dumps(receipt))
            (folder / "pack.json").write_text(json.dumps(packed))
            (root / "manifest.json").write_text(json.dumps({"status": "UNKNOWN",
                "sourceSha256": {source.name: digest(source.read_bytes())},
                "exports": [{"packedFiles": str(folder / "pack.json"),
                             "receipt": str(folder / "receipt.json"),
                             "rawDomainSize": "2", "range": [0, 2]}]}))
            with patch.object(checker, "SOURCE", root):
                self.assertEqual({"integrity": "PASS", "planCoverage": "UNKNOWN",
                                  "kind": "E", "fixture": "B-21", "rawRows": 2},
                                 checker.recheck(folder))
                archive = folder / "rows.jsonl.gz"
                archive.write_bytes(archive.read_bytes() + b"damage")
                with self.assertRaisesRegex(ValueError, "archive hash changed"):
                    checker.recheck(folder)


if __name__ == "__main__":
    unittest.main()
