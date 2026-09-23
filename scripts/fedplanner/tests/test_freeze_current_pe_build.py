from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from freeze_current_pe_build import freeze
from run_current_pe_cell import class_tree_sha, tree_sha


class FrozenCurrentPeBuildTest(unittest.TestCase):
    def test_snapshot_preserves_source_and_executed_classpath_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source'
            (source / 'src/main/java').mkdir(parents=True)
            (source / 'target/classes').mkdir(parents=True)
            (source / 'target/test-classes').mkdir(parents=True)
            (source / 'target/lib').mkdir(parents=True)
            (source / 'pom.xml').write_text('<project/>')
            (source / 'src/main/java/A.java').write_text('class A {}')
            (source / 'target/classes/A.class').write_bytes(b'compiled-a')
            (source / 'target/test-classes/ATest.class').write_bytes(b'compiled-test')
            (source / 'target/lib/dep.jar').write_bytes(b'jar')
            destination = root / 'frozen'
            receipt = freeze(source, destination)
            self.assertEqual(receipt['sourceTreeSha256'], tree_sha(destination))
            self.assertEqual(receipt['classTreeSha256'], class_tree_sha(destination))
            self.assertTrue((destination / 'build-freeze.json').is_file())
            with self.assertRaises(FileExistsError):
                freeze(source, destination)
            self.assertEqual((destination / 'target/classes/A.class').read_bytes(), b'compiled-a')


if __name__ == '__main__':
    unittest.main()
