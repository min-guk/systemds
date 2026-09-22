"""Regression checks for source-qualified workload discovery."""

import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_plan_space_inventory import (  # noqa: E402
    DEFAULT_EVALUATION,
    DEFAULT_LEGACY,
    build,
    sha256,
)


class PlanSpaceInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inventory = build(DEFAULT_EVALUATION, DEFAULT_LEGACY)

    def test_every_external_dml_revision_is_hashed(self):
        sources = self.inventory["discoveredSourceFiles"]
        for label, root in (("evaluation", DEFAULT_EVALUATION),
                            ("legacy", DEFAULT_LEGACY)):
            actual = {f"{label}:{path.relative_to(root).as_posix()}": sha256(path)
                      for path in root.rglob("*.dml") if path.is_file()}
            self.assertEqual(actual, {key: value for key, value in sources.items()
                                      if key.startswith(label + ":")})

    def test_same_name_different_revision_stays_distinct(self):
        entries = {row["id"]: row for row in self.inventory["entries"]}
        w1 = entries["planning-w1:P1_FULL"]
        common = entries["common:P1_FULL"]
        self.assertNotEqual(w1["templateSha256"], common["templateSha256"])
        self.assertNotEqual(w1["id"], common["id"])

    def test_unclassified_sources_block_full_scope(self):
        entries = self.inventory["entries"]
        self.assertEqual(len(entries), len({row["id"] for row in entries}))
        self.assertTrue(any(row["kind"] == "unclassified-source" and
                            row["status"] == "UNSUPPORTED" for row in entries))

    def test_planning_snapshot_profiles_are_explicit(self):
        conditions = self.inventory["plannedConditions"]
        self.assertEqual(224, len(conditions))
        self.assertEqual(len(conditions), len({(row["discoveryId"], row["conditionId"])
                                               for row in conditions}))
        self.assertEqual({"lan", "wan_light", "wan_mid", "wan_heavy"},
                         {row["conditionId"] for row in conditions})
        entries = {row["id"]: row for row in self.inventory["entries"]}
        self.assertEqual("UNRESOLVED", entries["planning-w1:sliceline-kdd98"]["conditionStatus"])


if __name__ == "__main__":
    unittest.main()
