#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("inventory_fed_runtime_space.py")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


class RuntimeSpaceInventoryTest(unittest.TestCase):
    def test_source_and_evidence_layers_remain_distinct(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "source"
            fed = root / "src/main/java/org/apache/sysds/runtime/instructions/fed"
            fed.mkdir(parents=True)
            (fed / "BaseFEDInstruction.java").write_text(
                "public abstract class BaseFEDInstruction extends Instruction {}\n",
                encoding="utf-8")
            (fed / "ObservedFEDInstruction.java").write_text(
                """public final class ObservedFEDInstruction extends BaseFEDInstruction {
                static ObservedFEDInstruction parseInstruction(String s) { return null; }
                void processInstruction(ExecutionContext ec) {}
                Object x = FType.ROW;
                Object y = FederatedOutput.FOUT;
                Object z = Opcodes.MM;
                }\n""", encoding="utf-8")
            (fed / "UnobservedFEDInstruction.java").write_text(
                "public class UnobservedFEDInstruction extends BaseFEDInstruction {}\n",
                encoding="utf-8")
            # Planner/runtime bridges use the FED prefix rather than the
            # *FEDInstruction suffix and must still be inventoried.
            (fed / "FEDBridgeInstruction.java").write_text(
                "public class FEDBridgeInstruction extends BaseFEDInstruction {}\n",
                encoding="utf-8")

            evidence = Path(tmp) / "evidence"
            write_jsonl(evidence / "runtime-capability-1.jsonl", [{
                "instructionSimpleClass": "ObservedFEDInstruction",
                "opcode": "ba+*",
                "outcome": "SUCCESS",
                "federatedOutput": "FOUT",
                "actualInputSignatures": {"o": ["PRESENT:ROW", "PRESENT:COL"]},
                "output": {"present": True, "federated": True, "fType": "ROW"},
            }, {
                "instructionSimpleClass": "ObservedFEDInstruction",
                "opcode": "bad",
                "outcome": "FAILURE",
                "federatedOutput": "LOUT",
                "inputs": [{"present": True, "federated": False}],
                "output": {"present": False},
            }])
            write_jsonl(evidence / "runtime-conversion-frontier-1.jsonl", [{
                "frontierKind": "RUNTIME_TO_FED",
                "sourceInstructionSimpleClass": "AggregateBinaryCPInstruction",
                "sourceOpcode": "ba+*",
                "sourceInputStates": ["PRESENT:ROW", "PRESENT:COL"],
                "resultInstructionSimpleClass": "ObservedFEDInstruction",
                "resultOpcode": "ba+*",
                "resultFederatedOutput": "FOUT",
                "resultInputStates": ["PRESENT:ROW", "PRESENT:COL"],
            }])
            write_jsonl(evidence / "candidate-space-1.jsonl", [{
                "hopClass": "org.apache.sysds.hops.AggBinaryOp",
                "opcode": "ba(+*)",
                "inputSignature": [
                    {"presence": "PRESENT", "fType": "ROW"},
                    {"presence": "PRESENT", "fType": "COL"},
                ],
                "privacy": "PUBLIC",
                "auditContext": "ctx",
                "publishedStatesP": [
                    {"exec": "FED", "output": "FOUT", "fType": "ROW"},
                    {"exec": "FED", "output": "LOUT", "fType": None},
                ],
            }])

            out = Path(tmp) / "out"
            subprocess.run([
                sys.executable, str(SCRIPT), "--source-root", str(root),
                "--evidence-root", str(evidence), "--out-dir", str(out),
            ], check=True, capture_output=True, text=True)

            summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(4, summary["source"]["fedInstructionFiles"])
            self.assertEqual(3, summary["source"]["concreteFamilies"])
            self.assertEqual(1, summary["capability"]["successRows"])
            self.assertEqual(1, summary["capability"]["failureRows"])
            self.assertEqual(1, summary["capability"]["successSignatureGroups"])
            self.assertEqual(1, summary["capability"]["exactActualInputRows"])
            self.assertEqual(0, summary["capability"]["plannedTargetRows"])
            self.assertEqual({"UNSPECIFIED": 2},
                             summary["capability"]["actualInputSignatureMethods"])
            self.assertEqual(1, summary["capability"]["positiveWitnessedConcreteFamilies"])
            self.assertEqual(["FEDBridgeInstruction", "UnobservedFEDInstruction"],
                             summary["unobservedConcreteFamilies"])
            self.assertEqual({"RUNTIME_TO_FED": 1}, summary["frontier"]["kinds"])
            self.assertEqual(1, summary["candidate"]["rows"])

            with (out / "fed_instruction_families.csv").open(encoding="utf-8") as handle:
                families = list(csv.DictReader(handle))
            observed = next(row for row in families
                            if row["class_name"] == "ObservedFEDInstruction")
            self.assertEqual("final", observed["modifier"])
            self.assertEqual("ROW", observed["ftype_references"])
            self.assertEqual("FOUT", observed["output_mode_references"])
            self.assertEqual("MM", observed["opcode_references"])
            self.assertEqual("1", observed["capability_success_rows"])
            self.assertEqual("COL;ROW", observed["observed_input_ftypes"])
            self.assertEqual("FOUT", observed["observed_requested_outputs"])
            self.assertEqual("FED:ROW", observed["observed_output_residencies"])
            self.assertEqual("1", observed["successful_signature_groups"])
            self.assertEqual("1", observed["frontier_runtime_to_fed_rows"])

            report = (out / "REPORT.md").read_text(encoding="utf-8")
            self.assertIn("dispatch-frontier rows as successful runtime witnesses", report)
            self.assertIn("`UnobservedFEDInstruction`", report)


if __name__ == "__main__":
    unittest.main()
