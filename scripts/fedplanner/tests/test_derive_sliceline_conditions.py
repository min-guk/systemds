import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "derive_sliceline_conditions.py"
SPEC = importlib.util.spec_from_file_location("derive_sliceline_conditions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, sort_keys=True) + "\n")
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DeriveSliceLineConditionsTest(unittest.TestCase):
    def test_base_requires_all_promoted_compiler_argv_and_ranges(self):
        rows = []
        for index in range(296):
            rows.append({"id": f"cell-{index}", "sourceBinding": {
                "kind": "campaign-compile-condition",
                "compilerArgv": list(MODULE.COMPILER_ARGV),
                "plannedCondition": {"case": {"federatedSources": [{
                    "origins": ["worker/data/X"],
                    "ranges": [[0, 0], [2, 2]]}]}}}})
        catalog = {"cells": rows}
        campaign = {"cells": [{"id": row["id"]} for row in rows]}
        self.assertEqual(MODULE.validate_base_compiler_contract(catalog, campaign), 296)
        del rows[0]["sourceBinding"]["compilerArgv"]
        with self.assertRaisesRegex(ValueError, "lacks compiler argv"):
            MODULE.validate_base_compiler_contract(catalog, campaign)

    def test_stage_input_uses_exact_sealed_partition_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            metadata = {"rows": 5, "cols": 2, "nnz": 7, "privacy": "private-aggregate",
                        "format": "binary", "data_type": "matrix", "value_type": "double"}
            entries = {}
            entries["data/KDD98_features.data.mtd"] = {
                write_json(stage / "data/KDD98_features.data.mtd", metadata)}
            for index, (rows, nnz) in enumerate(((2, 3), (2, 3), (1, 1)), 1):
                part = {**metadata, "rows": rows, "nnz": nnz}
                name = f"data/KDD98_features_3_{index}.data.mtd"
                entries[name] = {write_json(stage / name, part)}
            topology = {"workers": [{"ip": f"10.0.0.{i}", "port": 8000 + i}
                                    for i in range(1, 4)]}
            result = MODULE.stage_input(stage, entries, topology, "KDD98", "features", 3)
            self.assertEqual([part["begin"][0] for part in result["parts"]], [0, 2, 4])
            self.assertEqual([part["end"][0] for part in result["parts"]], [2, 4, 5])
            self.assertEqual(result["origins"][2],
                             "10.0.0.3:8003/data/KDD98_features_3_3.data")

    def test_literal_address_binding_rejects_adapter_fact_drift(self):
        program = ('X = federated(addresses=list("w1:1/X"), '
                   'ranges=list(list(0, 0), list(2, 2)))\n')
        source = [{"variable": "X", "origins": ["w1:1/other"],
                   "ranges": [[0, 0], [2, 2]]}]
        with self.assertRaisesRegex(ValueError, "literal/source-fact address mismatch"):
            MODULE.assert_literal_binding(program, source)

    def test_lineages_are_distinct_and_stage_seal_ambiguity_fails_closed(self):
        self.assertEqual(MODULE.COMPILER_ARGV,
                         ["-exec", "singlenode", "-seed", "1011081480",
                          "-noFedRuntimeConversion", "-stats", "100"])
        base = {"kind": "base-campaign", "workers": 3,
                "discoveryId": "base:sliceline:sliceline-kdd98"}
        planning = {"kind": "planning-snapshot", "workers": 3,
                    "discoveryId": "planning-w3:sliceline-kdd98"}
        self.assertEqual(MODULE.candidate_axes(base),
                         ("KDD98", "DERIVED_CURRENT_BASE_CAMPAIGN"))
        self.assertEqual(MODULE.candidate_axes(planning),
                         ("KDD98", "DERIVED_FROM_HISTORICAL_PLANNING_PARENT"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "file"
            path.write_text("a")
            entries = {"file": {hashlib.sha256(b"a").hexdigest(),
                                hashlib.sha256(b"b").hexdigest()}}
            with self.assertRaisesRegex(ValueError, "sealed stage file"):
                MODULE.sealed(root, entries, "file")


if __name__ == "__main__":
    unittest.main()
