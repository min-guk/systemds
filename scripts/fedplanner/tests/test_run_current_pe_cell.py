import gzip
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_current_pe_cell as runner


class CurrentPeCellReceiptTest(unittest.TestCase):
    def test_p_shard_fails_closed_on_row_mutation_and_frontier_gap(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            rows = root / "p.jsonl.gz"
            receipt_path = root / "p.json"
            with gzip.open(rows, "wt") as stream:
                stream.write(json.dumps({"schema": "physical-plan-v1"}) + "\n")
            model = {"programSha256": "program", "conditionSha256": "condition",
                     "sourceFiles": {"program.dml": "frozen"}}
            receipt = {"schema": "closed-planning-physical-shard-v1",
                       "status": "COMPLETE", "source": "P_C0", "cell": "fixture",
                       "start": "0", "stop": "1", "raw": "2", "accepted": "1",
                       "rejected": "1", "unknown": "0", "rows": str(rows),
                       "rowsSha256": runner.sha(rows), **model}
            runner.save(receipt_path, receipt)
            self.assertEqual("1", runner.check_row_receipt(
                receipt_path, "P_C0", "fixture", model, (0, 1))["accepted"])
            with self.assertRaisesRegex(ValueError, "frontier"):
                runner.check_row_receipt(receipt_path, "P_C0", "fixture", model, (1, 2))
            with gzip.open(rows, "wt") as stream:
                stream.write(json.dumps({"schema": "illegal-row"}) + "\n")
            with self.assertRaisesRegex(ValueError, "damaged"):
                runner.check_row_receipt(receipt_path, "P_C0", "fixture", model, (0, 1))


if __name__ == "__main__":
    unittest.main()
