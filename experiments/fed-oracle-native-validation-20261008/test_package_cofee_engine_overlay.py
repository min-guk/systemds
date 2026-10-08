import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "package_overlay", HERE / "package_cofee_engine_overlay.py")
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class PackageOverlayTest(unittest.TestCase):
    def test_preserves_base_manifest_and_replaces_classes_deterministically(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.jar"
            with zipfile.ZipFile(base, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
                archive.writestr("old/Retained.class", b"retained")
                archive.writestr("replace/Value.class", b"old")
            classes = root / "classes"
            (classes / "replace").mkdir(parents=True)
            (classes / "added").mkdir()
            (classes / "replace/Value.class").write_bytes(b"new")
            (classes / "added/Value.class").write_bytes(b"added")
            first, second = root / "first.jar", root / "second.jar"
            result = MOD.build(base, classes, first)
            MOD.build(base, classes, second)
            self.assertEqual(MOD.sha256(first), MOD.sha256(second))
            self.assertEqual(1, result["replacedEntries"])
            self.assertEqual(1, result["addedEntries"])
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(b"Manifest-Version: 1.0\n",
                                 archive.read("META-INF/MANIFEST.MF"))
                self.assertEqual(b"retained", archive.read("old/Retained.class"))
                self.assertEqual(b"new", archive.read("replace/Value.class"))

    def test_rejects_overlay_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.jar"
            with zipfile.ZipFile(base, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", b"base")
            classes = root / "classes/META-INF"
            classes.mkdir(parents=True)
            (classes / "MANIFEST.MF").write_bytes(b"overlay")
            with self.assertRaisesRegex(ValueError, "must not replace"):
                MOD.build(base, root / "classes", root / "result.jar")


if __name__ == "__main__":
    unittest.main()
