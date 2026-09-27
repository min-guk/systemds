"""Tests for conservative exact-path template applicability certification."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "certify_scope_template_rows.py"
SPEC = importlib.util.spec_from_file_location("scope_template_rows", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class ScopeTemplateRowsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.receipt = MOD.build()

    def test_resolves_exactly_13_paths_and_224_successors(self):
        self.assertEqual("current-scope-template-closure-v3", self.receipt["schema"])
        self.assertEqual("COMPLETE", self.receipt["status"])
        self.assertEqual({
            "resolvedRegistrations": 13, "successorCells": 224,
            "distinctRenderedPrograms": 56,
            "sixteenCellRegistrations": 12, "thirtyTwoCellRegistrations": 1,
            "unresolvedByteIdenticalHarnessAliases": 9,
        }, self.receipt["counts"])
        self.assertEqual(set(MOD.TARGET_DISCOVERIES),
                         {row["discoveryId"] for row in self.receipt["registrations"]})
        self.assertEqual(224, len({cell for row in self.receipt["registrations"]
                                  for cell in row["successorCellIds"]}))

    def test_template_paths_and_condition_hashes_are_exact(self):
        for row in self.receipt["registrations"]:
            name = row["discoveryId"].split(":", 1)[1]
            self.assertEqual(f"code/exp/{name}.dml", row["templatePath"])
            self.assertEqual(row["successorCount"], len(row["successors"]))
            for successor in row["successors"]:
                self.assertEqual(64, len(successor["conditionSha256"]))
                self.assertEqual(64, len(successor["programSha256"]))
                self.assertEqual(successor["programSha256"],
                                 successor["independentRender"]["renderedProgramSha256"])
                self.assertTrue(successor["independentRender"]["renderInputs"])
                self.assertTrue(successor["programPath"].startswith(
                    "planning_study/native/input_templates/w"))

    def test_byte_identical_harness_aliases_stay_unresolved(self):
        aliases = self.receipt["candidateHarnessAliases"]
        self.assertEqual(set(MOD.HARNESS_ALIAS_DISCOVERIES),
                         {row["discoveryId"] for row in aliases})
        self.assertTrue(all(row["resolution"] == "UNRESOLVED" for row in aliases))

    def test_parent_mutation_fails_closed(self):
        parent = json.loads(MOD.DEFAULT_PARENT.read_text())
        parent["counts"]["resolution"]["UNRESOLVED"] = 161
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "parent.json"
            path.write_text(MOD.render(parent))
            with self.assertRaisesRegex(ValueError, "parent v2 ledger bytes differ"):
                MOD.build(parent_path=path)

    def test_catalog_condition_mutation_fails_closed(self):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        row = next(row for row in catalog["cells"]
                   if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                   .get("case", {}).get("template") == "code/exp/als_fed.dml")
        row["sourceBinding"]["conditionSha256"] = "0" * 64
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(MOD.render(catalog))
            parent = json.loads(MOD.DEFAULT_PARENT.read_text())
            parent["inputs"]["catalog"]["sha256"] = MOD.sha256(path)
            parent_path = Path(directory) / "parent.json"
            parent_path.write_text(MOD.render(parent))
            with self.assertRaisesRegex(ValueError, "successor condition hash differs"):
                MOD.build(catalog_path=path, parent_path=parent_path,
                          expected_parent_sha256=MOD.sha256(parent_path))

    def _mutated_catalog_build(self, mutate, error):
        catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
        mutate(catalog)
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

    def _mutated_parent_build(self, mutate, error):
        parent = json.loads(MOD.DEFAULT_PARENT.read_text())
        mutate(parent)
        with tempfile.TemporaryDirectory() as directory:
            parent_path = Path(directory) / "parent.json"
            parent_path.write_text(MOD.render(parent))
            with self.assertRaisesRegex(ValueError, error):
                MOD.build(parent_path=parent_path,
                          expected_parent_sha256=MOD.sha256(parent_path))

    def test_swapped_als_pca_template_fails_independent_render(self):
        def mutate(catalog):
            als = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/als_fed.dml")
            pca = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/pca_fed.dml")
            target = als["sourceBinding"]["plannedCondition"]["case"]
            source = pca["sourceBinding"]["plannedCondition"]["case"]
            target["template"], source["template"] = source["template"], target["template"]
            target["template_sha256"], source["template_sha256"] = (
                source["template_sha256"], target["template_sha256"])
            for row in (als, pca):
                row["sourceBinding"]["conditionSha256"] = MOD.canonical_sha(
                    MOD._condition_identity(row))
        self._mutated_catalog_build(mutate, "catalog/context case binding differs")

    def test_empty_parent_source_evidence_fails_closed(self):
        def mutate(parent):
            next(row for row in parent["records"]
                 if row["discoveryId"] == "common:als_fed")["sourceEvidence"] = []
        self._mutated_parent_build(mutate, "parent target sourceEvidence differs")

    def test_corrupted_discovery_authority_fails_closed(self):
        def mutate(catalog):
            row = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/als_fed.dml")
            row["sourceBinding"]["discoveryId"] = "planning-w1:pca"
        self._mutated_catalog_build(mutate, "discovery authority differs")

    def test_coordinated_als_pca_discovery_swap_fails_workload_authority(self):
        def mutate(catalog):
            als = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/als_fed.dml")
            pca = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/pca_fed.dml")
            als["discoveryId"], pca["discoveryId"] = pca["discoveryId"], als["discoveryId"]
            for row in (als, pca):
                row["sourceBinding"]["discoveryId"] = row["discoveryId"]
                row["sourceBinding"]["conditionSha256"] = MOD.canonical_sha(
                    MOD._condition_identity(row))
        self._mutated_catalog_build(mutate, "worker/discovery authority differs")

    def test_false_alias_evidence_fails_closed(self):
        def mutate(parent):
            row = next(row for row in parent["records"]
                       if row["discoveryId"] == MOD.HARNESS_ALIAS_DISCOVERIES[0])
            row["sourceEvidence"][0]["verified"] = False
        self._mutated_parent_build(mutate, "harness alias byte identity differs")

    def test_false_alias_inventory_registration_fails_closed(self):
        inventory = json.loads(MOD.DEFAULT_INVENTORY.read_text())
        alias_id = MOD.HARNESS_ALIAS_DISCOVERIES[0]
        alias = next(row for row in inventory["entries"] if row["id"] == alias_id)
        alias["sourcePath"] = "evaluation:harness/wrong/als_fed.dml"
        with tempfile.TemporaryDirectory() as directory:
            inventory_path = Path(directory) / "inventory.json"
            inventory_path.write_text(MOD.render(inventory))
            catalog = json.loads(MOD.DEFAULT_CATALOG.read_text())
            catalog["discoveryInventorySha256"] = MOD.sha256(inventory_path)
            catalog_path = Path(directory) / "catalog.json"
            catalog_path.write_text(MOD.render(catalog))
            parent = json.loads(MOD.DEFAULT_PARENT.read_text())
            parent["inputs"]["inventory"]["sha256"] = MOD.sha256(inventory_path)
            parent["inputs"]["catalog"]["sha256"] = MOD.sha256(catalog_path)
            parent_path = Path(directory) / "parent.json"
            parent_path.write_text(MOD.render(parent))
            with self.assertRaisesRegex(ValueError,
                                        "harness alias inventory registration differs"):
                MOD.build(catalog_path=catalog_path, inventory_path=inventory_path,
                          parent_path=parent_path,
                          expected_parent_sha256=MOD.sha256(parent_path))

    def test_duplicate_successor_cell_id_fails_globally(self):
        def mutate(catalog):
            als = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/als_fed.dml")
            pca = next(row for row in catalog["cells"]
                       if ((row.get("sourceBinding") or {}).get("plannedCondition") or {})
                       .get("case", {}).get("template") == "code/exp/pca_fed.dml")
            als["id"] = pca["id"]
        self._mutated_catalog_build(mutate, "global successor cell identity set differs")

    def test_publish_check_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "receipt.json"
            content = MOD.render(self.receipt)
            MOD.publish(output, content, False)
            MOD.publish(output, content, True)
            output.write_text(content.replace('"status": "COMPLETE"',
                                              '"status": "TAMPERED"'))
            with self.assertRaisesRegex(SystemExit, "receipt changed"):
                MOD.publish(output, content, True)


if __name__ == "__main__":
    unittest.main()
