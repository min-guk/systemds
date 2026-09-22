import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "freeze_plan_space_bundle.py"
spec = importlib.util.spec_from_file_location("freeze_plan_space_bundle", SCRIPT)
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class FreezePlanSpaceBundleTest(unittest.TestCase):
    def test_content_addressed_copy_detects_corruption_and_source_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source.dml"
            source.write_bytes(b"frozen input\n")
            entries = {}
            bundle.add_file(entries, "input/source.dml", root, "source.dml")
            output = root / "bundle"
            bundle.freeze(output, entries)
            self.assertEqual("PASS", bundle.check(output)["integrity"])

            source.write_bytes(b"changed input\n")
            self.assertEqual("PASS", bundle.check(output)["integrity"])
            changed_entries = {}
            bundle.add_file(changed_entries, "input/source.dml", root, "source.dml")
            with self.assertRaisesRegex(ValueError, "frozen bundle differs"):
                bundle.freeze(output, changed_entries)
            with self.assertRaisesRegex(ValueError, "bundle source changed"):
                bundle.freeze(root / "new-bundle", entries)

            obj = output / "objects" / entries["input/source.dml"]["sha256"]
            obj.write_bytes(b"corrupt bytes\n")
            with self.assertRaisesRegex(ValueError, "bundle object missing or corrupt"):
                bundle.check(output)


if __name__ == "__main__":
    unittest.main()
