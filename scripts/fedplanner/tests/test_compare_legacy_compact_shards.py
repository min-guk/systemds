import gzip
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compare_legacy_compact_shards import compare_one
from run_current_pe_cell import save, sha


class LegacyCompactShardComparisonTest(unittest.TestCase):
    def test_e_receipt_uses_distinct_name_and_detects_both_directions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old, compact, output = (root / name for name in ("old", "compact", "result"))
            old.mkdir()
            compact.mkdir()
            old_rows = old / "e-rows.jsonl.gz"
            dictionary = compact / "e-dictionary.jsonl.gz"
            references = compact / "e-refs.tsv.gz"
            with gzip.open(old_rows, "wb") as stream:
                stream.write(b'{"id":"a"}\n{"id":"a"}\n{"id":"b"}\n')
            with gzip.open(dictionary, "wb") as stream:
                stream.write(b'{"id":"a"}\n{"id":"b"}\n')
            with gzip.open(references, "wb") as stream:
                stream.write(b'0\n0\n1\n')
            common = {"status": "COMPLETE", "cell": "c", "source": "E_C0",
                      "programSha256": "program", "conditionSha256": "condition",
                      "sourceFiles": {"program.dml": "program"}, "raw": "4",
                      "accepted": "3", "rejected": "1", "unknown": "0",
                      "acceptedOrdinalsSha256": "ordinals"}
            old_receipt = {**common, "rowsSha256": sha(old_rows)}
            save(old / "e-rows.receipt.json", old_receipt)
            save(compact / "e-receipt.json",
                 {**common, "dictionarySha256": sha(dictionary),
                  "referencesSha256": sha(references), "dictionaryCount": 2,
                  "emittedRows": "3"})
            with patch("compare_legacy_compact_shards.verify_compact",
                       return_value={"proofCount": 3}):
                equal = compare_one(old, compact, "e-rows.receipt.json", root,
                                    output, e=True)
                self.assertEqual(equal["status"], "EQUAL")
                with gzip.open(old_rows, "wb") as stream:
                    stream.write(b'{"id":"a"}\n{"id":"a"}\n{"id":"c"}\n')
                save(old / "e-rows.receipt.json",
                     {**old_receipt, "rowsSha256": sha(old_rows)})
                different = compare_one(old, compact, "e-rows.receipt.json", root,
                                        output, e=True)
            self.assertEqual(different["status"], "DIFFERENT")
            self.assertIsNotNone(different["firstLegacyOnlySha256"])
            self.assertIsNotNone(different["firstCompactOnlySha256"])


if __name__ == "__main__":
    unittest.main()
