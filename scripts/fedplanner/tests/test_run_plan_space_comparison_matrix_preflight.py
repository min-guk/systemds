import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_current_pe_cell import save, sha
from verify_p_model_artifact import canonical, source_order


SCRIPT = Path(__file__).resolve().parents[1] / "run_plan_space_comparison.sh"
PREFLIGHT = re.search(r"python3 - .*? <<'PY'\n(.*?)\nPY", SCRIPT.read_text(), re.S).group(1)


class MatrixGatePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.evaluation = self.root / "evaluation"
        self.evaluation.mkdir()
        (self.evaluation / "x.dml").write_text("x")
        protocol = self.evaluation / "planning_study/native/protocol-w1.json"
        protocol.parent.mkdir(parents=True)
        save(protocol, {"workload_jvm_options": {"demo": []}})
        self.catalog = self.root / "catalog.json"
        binding = {"conditionStatus": "SNAPSHOT_ONLY", "kind": "planning-snapshot",
                   "conditionSha256": "condition", "plannedCondition": {"network": {
                       "cost_environment": {"SYSDS_FED_COST_NET_BW": "625.000000"}}}}
        rows = [{"id": cell, "inventoryStatus": "IN_SCOPE", "conditionId": condition,
                 "discoveryId": "planning-w1:demo", "sourceFiles": {
                     "x.dml": sha(self.evaluation / "x.dml")}, "sourceBinding": binding,
                 "nativeInputCapture": "PENDING", "expectedPairs": []}
                for cell, condition in (("c1", "lan"), ("c2", "wan_mid"))]
        save(self.catalog, {"cells": rows})
        self.matrix = self.root / "matrix"
        (self.matrix / "c1").mkdir(parents=True)
        self.make_complete_matrix(rows[0])

    def make_complete_matrix(self, row):
        graph = {name: [] for name in
                 ("blocks", "roots", "nodes", "edges", "functions", "calls", "inlinedCalls")}
        domain = {"nodes": [], "placementDomains": [], "candidateDomains": [],
                  "relocationDomains": [], "radices": []}
        graph_digest = hashlib.sha256(source_order(list(graph.values()))).hexdigest()
        summary = {"status": "COMPLETE", "cell": "c1", "rawCount": "1",
                   "placementCoordinates": 0, "candidateCoordinates": 0,
                   "relocationCoordinates": 0,
                   "nativeDomainSha256": hashlib.sha256(canonical(domain)).hexdigest(),
                   "preRewriteGraphSha256": graph_digest,
                   "finalHopGraphSha256": graph_digest,
                   "conditionSha256": "condition", "sourceFiles": row["sourceFiles"],
                   "programPath": "x.dml", "programSha256": row["sourceFiles"]["x.dml"],
                   "compilerBoundary": "POST_REWRITE_HOPS_DAG_PRE_PLANNER",
                   "networkEnvironmentChecked": True,
                   "networkEnvironment": {"SYSDS_FED_COST_NET_BW": "625.000000"},
                   "workloadJvmProperties": {}}
        artifact = {"schema": "closed-native-model-artifact-v1",
                    "acceptance": "PRODUCTION_JAVA_PREDICATE_NOT_SERIALIZED",
                    "preRewriteGraph": graph, "finalHopGraph": graph,
                    "nativeDomain": domain, "summary": summary}
        plain = canonical(artifact)
        model = self.matrix / "c1/p-model.json.gz"
        model.write_bytes(gzip.compress(plain))
        common = {"sourceTreeSha256": "a" * 64, "classTreeSha256": "b" * 64,
                  "runnerSha256": "c" * 64, "evaluationRoot": str(self.evaluation),
                  "catalogSha256": sha(self.catalog)}
        save(self.matrix / "matrix.json", {
            "schema": "current-p-matrix-capture-v1", "status": "COMPLETE", **common,
            "selectedConditions": ["lan"], "selectedCell": None, "cellCount": 1,
            "counts": {"COMPLETE": 1}, "cells": [{"cell": "c1", "status": "COMPLETE"}]})
        save(self.matrix / "c1/receipt.json", {
            "schema": "current-p-matrix-cell-v1", "cell": "c1", "status": "COMPLETE",
            **common, "sourceFiles": row["sourceFiles"], "artifactPath": str(model),
            "artifactSha256": hashlib.sha256(plain).hexdigest(), "jvmOptions": [],
            "networkEnvironment": {"SYSDS_FED_COST_NET_BW": "625.000000"}})

    def preflight(self, *, expected="lan", catalog=None, evaluation=None):
        output = self.root / "gate"
        output.mkdir(exist_ok=True)
        args = [str(self.catalog), "", str(output), str(SCRIPT.parent), str(self.matrix),
                str(catalog or self.catalog), str(evaluation or self.evaluation), expected]
        run = subprocess.run([sys.executable, "-", *args], input=PREFLIGHT, text=True,
                             capture_output=True)
        return run, json.loads((output / "preflight.json").read_text())

    def test_partial_matrix_is_verified_without_reclassifying_native_capture(self):
        run, result = self.preflight()
        self.assertEqual(2, run.returncode)
        self.assertEqual("PASS", result["pMatrixStatus"])
        self.assertEqual(1, result["verifiedPModelCells"])
        self.assertEqual(2, result["uncapturedCurrentCells"])
        self.assertEqual(0, result["unresolvedCurrentConditions"])
        self.assertEqual(sha(self.matrix / "matrix.json"), result["pMatrixSha256"])
        self.assertEqual("P_MODEL_STRUCTURAL_CAPTURE_COVERAGE", result["pMatrixClaimScope"])
        self.assertEqual("NOT_ASSESSED_BY_THIS_CONTRACT", result["pMatrixAcceptance"])
        self.assertEqual([], result["pMatrixFailures"])

    def test_tampered_model_and_changed_frozen_source_fail_closed(self):
        model = self.matrix / "c1/p-model.json.gz"
        model.write_bytes(model.read_bytes() + b"tamper")
        run, result = self.preflight()
        self.assertEqual(2, run.returncode)
        self.assertEqual("INCOMPLETE", result["pMatrixStatus"])
        self.assertEqual(0, result["verifiedPModelCells"])
        self.assertEqual("MODEL_VERIFICATION_ERROR", result["pMatrixFailures"][0]["reason"])
        (self.evaluation / "x.dml").write_text("changed")
        run, result = self.preflight()
        self.assertEqual(2, run.returncode)
        self.assertEqual("FROZEN_SOURCE_CHANGED", result["pMatrixFailures"][0]["reason"])

    def test_scope_and_catalog_mismatch_are_rejected(self):
        run, result = self.preflight(expected="lan,wan_mid")
        self.assertEqual(2, run.returncode)
        self.assertEqual("ERROR", result["pMatrixStatus"])
        self.assertIn("selected scope differs", result["pMatrixFailures"][0]["detail"])
        other = self.root / "other.json"
        save(other, {"cells": []})
        run, result = self.preflight(catalog=other)
        self.assertEqual(2, run.returncode)
        self.assertEqual("ERROR", result["pMatrixStatus"])
        self.assertIn("catalog differs", result["pMatrixFailures"][0]["detail"])


if __name__ == "__main__":
    unittest.main()
