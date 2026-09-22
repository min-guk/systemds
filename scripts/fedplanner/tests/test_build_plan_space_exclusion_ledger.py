import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "build_plan_space_exclusion_ledger.py"
spec = importlib.util.spec_from_file_location("build_plan_space_exclusion_ledger", SCRIPT)
ledger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ledger)


class ExclusionLedgerTest(unittest.TestCase):
    def test_current_public_ignores_are_explicit(self):
        rows = ledger.build()["ignoredTests"]
        self.assertEqual(14, len(rows))
        self.assertTrue(all(row["publicOnly"] for row in rows))
        self.assertTrue(all(row["reason"] and len(row["sourceSha256"]) == 64 for row in rows))

    def test_missing_reason_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "IgnoredTest.java"
            path.write_text('@Ignore\npublic class IgnoredTest {}\n')
            with self.assertRaisesRegex(ValueError, "no literal reason"):
                ledger.build((Path(temp),))


if __name__ == "__main__":
    unittest.main()
