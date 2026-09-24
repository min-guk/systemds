"""Tests for full-condition harness alias scope certification."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "certify_scope_harness_alias_rows.py"
SPEC = importlib.util.spec_from_file_location("scope_harness_alias_rows", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class ScopeHarnessAliasRowsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.receipt = MOD.build()

    def test_records_exactly_nine_unresolved_candidates_and_144_successors(self):
        self.assertEqual("current-scope-harness-alias-render-candidates-v2",
                         self.receipt["schema"])
        self.assertEqual("COMPLETE", self.receipt["status"])
        self.assertEqual({
            "candidateRegistrations": 9, "successorCells": 144,
            "distinctRenderedPrograms": 36, "excludedShaDivergentAliases": 1,
        }, self.receipt["counts"])
        self.assertEqual(set(MOD.TARGET_DISCOVERIES),
                         {row["discoveryId"] for row in self.receipt["registrations"]})

    def test_full_condition_fields_are_bound_for_every_successor(self):
        cells = set()
        for registration in self.receipt["registrations"]:
            self.assertEqual("UNRESOLVED", registration["resolution"])
            self.assertEqual("FULL_CONDITION_RENDER_EQUIVALENT_TEMPLATE_CANDIDATE",
                             registration["roleCandidate"])
            self.assertFalse(registration["repositoryToStageAncestryProven"])
            self.assertFalse(registration["sourceManifestEntry"]["adapted"])
            self.assertEqual(registration["aliasSha256"],
                             registration["sourceManifestEntry"]["source_sha256"])
            self.assertEqual(16, len(registration["successors"]))
            for successor in registration["successors"]:
                cells.add(successor["cellId"])
                self.assertEqual(successor["programSha256"],
                                 successor["independentRenderedProgramSha256"])
                self.assertEqual([], successor["compilerArgv"])
                self.assertEqual("mixed", successor["privacyExperiment"])
                self.assertEqual("private-aggregate",
                                 successor["privacyByRole"]["X"])
                self.assertEqual(64, len(successor["networkSha256"]))
                self.assertEqual(64, len(successor["inputsSha256"]))
                self.assertTrue(successor["inputs"])
        self.assertEqual(144, len(cells))

    def test_render_equivalence_does_not_claim_active_selection(self):
        self.assertIn("NO_HARNESS_ALIAS_IS_RESOLVED_BY_RENDER_EQUIVALENCE",
                      self.receipt["limitations"])
        self.assertIn("ACTIVE_SELECTION_OR_REFERENCE_BY_THE_FROZEN_PLANNING_COHORT_IS_UNPROVEN",
                      self.receipt["limitations"])
        self.assertTrue(all(row["resolution"] == "UNRESOLVED"
                            for row in self.receipt["registrations"]))

    def test_gnmf_is_excluded_for_real_sha_divergence(self):
        self.assertEqual(MOD.EXCLUDED_GNMF,
                         self.receipt["excluded"][0]["discoveryId"])
        inventory = json.loads(MOD.DEFAULT_INVENTORY.read_text())
        entry = next(row for row in inventory["entries"]
                     if row["id"] == MOD.EXCLUDED_GNMF)
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        frozen = next(row["sourceBinding"]["plannedCondition"]["case"]
                      for row in catalog["cells"]
                      if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                      .get("case", {}).get("template") == "code/exp/gnmf_fed.dml")
        self.assertNotEqual(entry["sourceSha256"], frozen["template_sha256"])

    def test_adapted_source_manifest_entry_fails_closed(self):
        manifest = json.loads(MOD.DEFAULT_SOURCE_MANIFEST.read_text())
        target = next(row for row in manifest["files"]
                      if row["path"].endswith("/als_fed.dml"))
        target["adapted"] = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source-manifest.json"
            path.write_text(MOD.render(manifest))
            with self.assertRaisesRegex(ValueError, "alias source manifest differs"):
                MOD.build(source_manifest_path=path,
                          expected_source_manifest_sha256=MOD.sha256(path))

    def _mutated_catalog_build(self, mutate, error):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        row = next(row for row in catalog["cells"]
                   if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                   .get("case", {}).get("template") == "code/exp/als_fed.dml")
        mutate(row)
        row["sourceBinding"]["conditionSha256"] = MOD.canonical_sha(
            MOD._condition_identity(row))
        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "catalog.json"
            catalog_path.write_text(MOD.render(catalog))
            parent = json.loads(MOD.DEFAULT_PARENT.read_text())
            parent["inputs"]["catalog"]["sha256"] = MOD.sha256(catalog_path)
            parent_path = Path(directory) / "parent.json"
            parent_path.write_text(MOD.render(parent))
            with self.assertRaisesRegex(ValueError, error):
                MOD.build(catalog_path=catalog_path, parent_path=parent_path,
                          expected_parent_sha256=MOD.sha256(parent_path))

    def test_nonempty_argv_fails_closed(self):
        self._mutated_catalog_build(
            lambda row: row["sourceBinding"].update({"compilerArgv": ["-stats"]}),
            "successor compiler argv differs")

    def test_network_shape_mutation_fails_closed(self):
        def mutate(row):
            del row["sourceBinding"]["plannedCondition"]["network"]["rtt_ms"]
        self._mutated_catalog_build(mutate, "protocol authority differs")

    def test_publish_check_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "receipt.json"
            content = MOD.render(self.receipt)
            MOD.publish(output, content, False)
            MOD.publish(output, content, True)
            output.write_text(content + " ")
            with self.assertRaisesRegex(SystemExit, "receipt changed"):
                MOD.publish(output, content, True)


if __name__ == "__main__":
    unittest.main()
