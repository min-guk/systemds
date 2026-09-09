#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("compare_forced_attempt_runtime_space.py")


def candidate(occurrence: str, state: str, privacy: str = "PUBLIC", analysis: str = "a",
              context: str = "ctx") -> dict:
    exec_type, output, ftype = state.split("/")
    return {
        "occurrenceKeyHash": occurrence,
        "analysisFingerprint": analysis,
        "auditContext": context,
        "privacy": privacy,
        "inputSignature": [{"presence": "PRESENT", "fType": "ROW"}],
        "publishedStatesP": [{"exec": exec_type, "output": output,
                              "fType": None if ftype == "-" else ftype}],
    }


def witness(occurrence: str, target: str | None, physical: str | None,
            analysis: str = "a", context: str = "ctx") -> dict:
    row = {
        "outcome": "SUCCESS",
        "pid": 1,
        "instructionSimpleClass": "BinaryFEDInstruction",
        "opcode": "+",
        "occurrenceKeyHashes": [occurrence],
        "plannedInputSignatures": {occurrence: ["PRESENT:ROW"]},
        "actualInputSignatures": {occurrence: ["PRESENT:ROW"]},
        "federatedOutput": "LOUT",
        "output": {"present": True, "federated": False},
    }
    if target is not None:
        row.update({
            "plannerAnalysisFingerprint": analysis,
            "auditContext": context,
            "plannedTargetStates": {occurrence: target},
            "plannedPhysicalStates": {occurrence: physical},
        })
    return row


def write_attempt(root: Path, target: str, candidates: list[dict], witnesses: list[dict],
                  frontiers: list[dict] | None = None) -> None:
    attempt = root / "chunks" / "chunk-00000" / "targets" / target / "attempt-0000"
    attempt.mkdir(parents=True)
    (attempt / "candidate-space-1.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in candidates), encoding="utf-8")
    (attempt / "runtime-capability-1.jsonl").write_text(
        "".join(json.dumps(row) + "\n" for row in witnesses), encoding="utf-8")
    if frontiers is not None:
        (attempt / "runtime-conversion-frontier-1.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in frontiers), encoding="utf-8")


class AttemptLocalComparatorTest(unittest.TestCase):
    def test_strict_and_legacy_evidence_are_separated_per_attempt(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "campaign"
            out = Path(tmp) / "out"
            write_attempt(root, "published", [candidate("o1", "FED/LOUT/ROW")],
                          [witness("o1", "FED/LOUT/ROW", "FED/LOUT/ROW")], [
                              {"frontierKind": "DIRECT_FED"},
                              {"frontierKind": "FEDERATED_INPUT_NOT_CONVERTED"},
                          ])
            write_attempt(root, "missing", [candidate("o2", "CP/LOUT/-")],
                          [witness("o2", "FED/LOUT/ROW", "FED/LOUT/ROW")])
            write_attempt(root, "restricted", [candidate("o3", "CP/LOUT/-", "PRIVATE_AGGREGATION")],
                          [witness("o3", "FED/LOUT/ROW", "FED/LOUT/ROW")])
            write_attempt(root, "legacy", [candidate("o4", "CP/LOUT/-")],
                          [witness("o4", None, None)])
            # o1 exists in another attempt, but this attempt contains only x.  An
            # occurrence-only global map would incorrectly join it.
            write_attempt(root, "cross-attempt", [candidate("x", "FED/LOUT/ROW")],
                          [witness("o1", "FED/LOUT/ROW", "FED/LOUT/ROW")])
            divergent = witness("o5", "FED/LOUT/ROW", "FED/LOUT/ROW")
            divergent["actualInputSignatures"]["o5"] = ["ABSENT_LOCAL:-"]
            write_attempt(root, "input-divergence", [candidate("o5", "FED/LOUT/ROW")],
                          [divergent])

            subprocess.run([sys.executable, str(SCRIPT), "--campaign-root", str(root),
                            "--out-dir", str(out)], check=True)
            summary = json.loads((out / "summary.json").read_text())
            classes = summary["classifications"]
            self.assertEqual(1, summary["confirmedMissing"])
            self.assertEqual(1, classes["EXACT_PLANNED_TARGET_IN_P"])
            self.assertEqual(1, classes["CONFIRMED_MISSING_PUBLIC_DIRECT"])
            self.assertEqual(1, classes["PLANNED_TARGET_OUTSIDE_P_NEEDS_REVIEW"])
            self.assertEqual(1, classes["LEGACY_ACTUAL_STATE_OUTSIDE_P_UNQUALIFIED"])
            self.assertEqual(1, classes["OCCURRENCE_NOT_IN_SAME_ATTEMPT_P"])
            self.assertEqual(1, classes["SELECTED_RUNTIME_INPUT_DIVERGENCE"])
            self.assertEqual(1, summary["uniqueSelectedRuntimeInputDivergences"])
            self.assertEqual(2, summary["runtimeFrontierRows"])
            self.assertEqual({"DIRECT_FED": 1, "FEDERATED_INPUT_NOT_CONVERTED": 1},
                             summary["runtimeFrontierKinds"])


if __name__ == "__main__":
    unittest.main()
