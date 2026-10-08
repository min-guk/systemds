import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("prepare_repo", HERE / "prepare_cofee_engine_repo.py")
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class PrepareEngineRepoTest(unittest.TestCase):
    def test_prepares_repo_with_exact_dependency_link_and_deterministic_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.jar"
            with zipfile.ZipFile(base, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
                archive.writestr("old/Value.class", b"old")
            classes = root / "classes/old"
            classes.mkdir(parents=True)
            (classes / "Value.class").write_bytes(b"new")
            source = root / "source"
            for relative in MOD.REQUIRED_SOURCE_FILES:
                path = source / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative)
            dependencies = root / "lib"
            dependencies.mkdir()
            (dependencies / "dependency.jar").write_bytes(b"jar")
            freeze = root / "freeze.json"
            freeze.write_text(json.dumps({"frozen": True}))
            output = root / "engine"
            result = MOD.prepare(base, root / "classes", source, dependencies, output, freeze)
            self.assertEqual(dependencies.resolve(), (output / "target/lib").resolve())
            self.assertEqual(1, result["dependencyJarCount"])
            with zipfile.ZipFile(output / "target/systemds-3.4.0-SNAPSHOT.jar") as archive:
                self.assertEqual(b"new", archive.read("old/Value.class"))
            with self.assertRaisesRegex(ValueError, "already exists"):
                MOD.prepare(base, root / "classes", source, dependencies, output, freeze)


if __name__ == "__main__":
    unittest.main()
