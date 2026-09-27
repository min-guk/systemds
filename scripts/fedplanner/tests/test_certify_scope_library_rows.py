"""Tests for the bounded parser-backed source-library closure receipt."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "certify_scope_library_rows.py"
SPEC = importlib.util.spec_from_file_location("scope_library", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


class ScopeLibraryClosureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.evaluation = self.root / "evaluation"
        self.build_root = self.root / "build"
        parser_root = self.build_root / "target/classes/org/apache/sysds/parser/dml"
        for name in ("DmlParser.class", "DmlParser$ProgramContext.class",
                     "DmlLexer.class", "DmlLexer$Mode.class", "DmlListener.class"):
            path = parser_root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("runtime:" + name).encode())
        antlr = self.build_root / MOD.ANTLR_RUNTIME_RELATIVE
        antlr.parent.mkdir(parents=True, exist_ok=True)
        antlr.write_bytes(b"antlr-runtime")
        self.inventory = self.root / "workloads.json"
        self.catalog = self.root / "catalog.json"
        self.gmm = self._write(
            "planning_study/native/input_templates/common/code/exp/gmm_p1_compat.dml",
            "m_gmm = function(Matrix[Double] X) return (Matrix[Double] Y) { Y = X; }\n")
        self.sliceline = self._write(
            "planning_study/native/input_templates/common/code/workloads/sliceline/"
            "slicefinder_core.dml",
            "findSlice = function(Matrix[Double] X) return (Matrix[Double] Y) { Y = X; }\n")
        self._write_inputs()

    def tearDown(self):
        self.temporary.cleanup()

    def _write(self, relative, content):
        path = self.evaluation / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def _write_inputs(self):
        gmm_sha, slice_sha = MOD.sha256(self.gmm), MOD.sha256(self.sliceline)
        inventory = {
            "entries": [
                {"id": MOD.GMM_COMMON, "kind": "unclassified-template",
                 "name": "gmm_p1_compat", "status": "UNSUPPORTED",
                 "templateSha256": gmm_sha},
                {"id": MOD.GMM_OPTIONAL, "kind": "optional-template",
                 "name": "gmm_p1_compat", "status": "UNSUPPORTED"},
                {"id": MOD.SLICE_DISCOVERY, "kind": "unclassified-source",
                 "name": "slicefinder_core", "status": "UNSUPPORTED",
                 "sourceSha256": slice_sha},
            ],
            "discoveredSourceFiles": {
                "evaluation:planning_study/native/input_templates/common/code/exp/"
                "gmm_p1_compat.dml": gmm_sha,
                "evaluation:planning_study/native/input_templates/common/code/workloads/"
                "sliceline/slicefinder_core.dml": slice_sha,
            },
        }
        self.inventory.write_text(json.dumps(inventory))
        cells = []
        for discovery, literal, namespace, digest in (
                ("base:p1:P1_FULL", "code/exp/gmm_p1_compat.dml", "GMMCompat", gmm_sha),
                ("base:sliceline:sliceline-adult",
                 "code/workloads/sliceline/slicefinder_core.dml", "SL", slice_sha),
                ("base:sliceline:sliceline-covtype",
                 "code/workloads/sliceline/slicefinder_core.dml", "SL", slice_sha)):
            for workers in MOD.WORKERS:
                for profile in MOD.PROFILES:
                    cell_id = f"cell-{discovery.replace(':', '-')}-{workers}-{profile}"
                    program_relative = f"cells/{cell_id}/program.dml"
                    import_relative = f"cells/{cell_id}/imports/library.dml"
                    program = self._write(program_relative,
                                          f'source("{literal}") as {namespace}\nX = 1;\n')
                    imported = self.evaluation / import_relative
                    imported.parent.mkdir(parents=True, exist_ok=True)
                    imported.write_bytes((self.gmm if namespace == "GMMCompat"
                                          else self.sliceline).read_bytes())
                    cells.append({
                        "id": cell_id, "discoveryId": discovery, "conditionId": profile,
                        "sourceBinding": {"kind": "campaign-compile-condition",
                            "conditionSha256": MOD.canonical_sha([discovery, workers, profile]),
                            "plannedCondition": {"workers": workers, "case": {
                                "program": program_relative, "program_sha256": MOD.sha256(program),
                                "imports": {literal: import_relative}}}},
                        "sourceFiles": {program_relative: MOD.sha256(program),
                                        import_relative: digest},
                    })
        cells.append({"id": "planning-extra", "discoveryId": "planning-w1:P1_FULL",
                      "conditionId": "lan", "sourceBinding": {"kind": "planning-snapshot"},
                      "sourceFiles": {"historical/gmm_p1_compat.dml": gmm_sha}})
        self.catalog.write_text(json.dumps({
            "discoveryInventorySha256": MOD.sha256(self.inventory), "cells": cells}))

    @staticmethod
    def _probe(paths, _build):
        rows = {}
        for path in paths:
            path = Path(path).resolve()
            text = path.read_text()
            imports = []
            if 'source("code/exp/gmm_p1_compat.dml") as GMMCompat' in text:
                imports = [{"path": "code/exp/gmm_p1_compat.dml", "namespace": "GMMCompat"}]
            elif 'source("code/workloads/sliceline/slicefinder_core.dml") as SL' in text:
                imports = [{"path": "code/workloads/sliceline/slicefinder_core.dml",
                            "namespace": "SL"}]
            function_only = "function(" in text
            rows[str(path)] = {"path": str(path), "syntaxErrors": 0,
                               "topLevelStatementCount": 0 if function_only else 2,
                               "functionCount": 1 if function_only else 0,
                               "imports": imports,
                               "functions": ["library"] if function_only else []}
        return rows, {"sourcePath": str(MOD.PROBE_SOURCE),
                      "sourceSha256": MOD.sha256(MOD.PROBE_SOURCE),
                      "compiledClassSha256": "1" * 64,
                      "executionClasspathPolicy":
                          "FRESH_CLASS_ONLY_THEN_TARGET_CLASSES_AND_LIB"}

    def build(self):
        return MOD.build(self.catalog, self.inventory, self.evaluation,
                         self.build_root, self._probe)

    def test_exact_two_resolved_registrations_and_active_cell_denominators(self):
        receipt = self.build()
        self.assertEqual("COMPLETE", receipt["status"])
        self.assertEqual(2, receipt["counts"]["resolvedRegistrations"])
        self.assertEqual(1, receipt["counts"]["unresolvedCandidateAliases"])
        self.assertEqual(16, receipt["counts"]["p1ActiveCells"])
        self.assertEqual(32, receipt["counts"]["slicelineActiveCells"])
        self.assertEqual(1, receipt["counts"]["otherSourceConsumers"])
        self.assertEqual({MOD.GMM_COMMON, MOD.SLICE_DISCOVERY},
                         {row["discoveryId"] for row in receipt["registrations"]})

    def test_optional_registration_remains_unresolved_candidate_alias(self):
        receipt = self.build()
        rows = {row["discoveryId"]: row for row in receipt["registrations"]}
        self.assertEqual("templateSha256", rows[MOD.GMM_COMMON]["sourceIdentityBasis"])
        self.assertNotIn(MOD.GMM_OPTIONAL, rows)
        alias = receipt["candidateAliasEvidence"]
        self.assertEqual(1, len(alias))
        self.assertEqual(MOD.GMM_OPTIONAL, alias[0]["discoveryId"])
        self.assertEqual("UNRESOLVED", alias[0]["resolution"])

    def test_catalog_must_bind_exact_inventory_bytes(self):
        catalog = json.loads(self.catalog.read_text())
        catalog["discoveryInventorySha256"] = "0" * 64
        self.catalog.write_text(json.dumps(catalog))
        with self.assertRaisesRegex(ValueError, "not bound"):
            self.build()

    def test_parser_runtime_binds_main_and_nested_class_tree(self):
        receipt = self.build()
        runtime = receipt["inputs"]["parserRuntime"]
        self.assertEqual(5, runtime["classCount"])
        self.assertEqual(MOD.canonical_sha(runtime["classes"]),
                         runtime["classTreeSha256"])
        self.assertTrue(any("DmlParser$" in row["path"]
                            for row in runtime["classes"]))
        self.assertTrue(any("DmlLexer$" in row["path"]
                            for row in runtime["classes"]))

    def test_missing_active_cell_fails_closed(self):
        catalog = json.loads(self.catalog.read_text())
        catalog["cells"] = catalog["cells"][1:]
        self.catalog.write_text(json.dumps(catalog))
        with self.assertRaisesRegex(ValueError, "active cell count differs"):
            self.build()

    def test_literal_import_namespace_mutation_fails_closed(self):
        catalog = json.loads(self.catalog.read_text())
        cell = next(row for row in catalog["cells"]
                    if row["discoveryId"] == "base:p1:P1_FULL")
        path = self.evaluation / cell["sourceBinding"]["plannedCondition"]["case"]["program"]
        path.write_text(path.read_text().replace("GMMCompat", "WrongNamespace"))
        digest = MOD.sha256(path)
        cell["sourceBinding"]["plannedCondition"]["case"]["program_sha256"] = digest
        cell["sourceFiles"][cell["sourceBinding"]["plannedCondition"]["case"]["program"]] = digest
        self.catalog.write_text(json.dumps(catalog))
        with self.assertRaisesRegex(ValueError, "literal import syntax differs"):
            self.build()

    def test_top_level_library_execution_fails_closed(self):
        original = self._probe

        def invalid(paths, build):
            rows, binding = original(paths, build)
            rows[str(self.gmm.resolve())]["topLevelStatementCount"] = 1
            return rows, binding

        with self.assertRaisesRegex(ValueError, "not a parser-proven function-only library"):
            MOD.build(self.catalog, self.inventory, self.evaluation, self.build_root, invalid)

    def test_published_receipt_check_rejects_tampering(self):
        output = self.root / "receipt.json"
        content = MOD.render(self.build())
        MOD.publish(output, content, False)
        MOD.publish(output, content, True)
        output.write_text(content.replace('"status": "COMPLETE"', '"status": "TAMPERED"'))
        with self.assertRaisesRegex(SystemExit, "receipt changed"):
            MOD.publish(output, content, True)


if __name__ == "__main__":
    unittest.main()
