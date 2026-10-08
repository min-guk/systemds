import importlib.util
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("analysis_phase_report.py")
SPEC = importlib.util.spec_from_file_location("analysis_phase_report", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def live(seq, terminal, phase, inclusive, exclusive, allocated, calls=1):
    return (f"SEARCH_SPACE_LIVE|seq={seq}|terminal={str(terminal).lower()}|"
            f"LivePhase[phase={phase}, completedCalls={calls}, activeCount=0, "
            f"inclusiveWallNanos={inclusive}, exclusiveWallNanos={exclusive}, "
            f"inclusiveCpuNanos={inclusive}, exclusiveCpuNanos={exclusive}, "
            f"inclusiveAllocatedBytes={allocated}, exclusiveAllocatedBytes={exclusive}]")


class AnalysisPhaseReportTest(unittest.TestCase):
    def test_terminal_snapshots_repeat_and_nested_inclusive_is_not_double_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            campaign = pathlib.Path(tmp)
            log = campaign / "runs" / "ml_logreg-pair0-candidate" / "cases" / "ml_logreg" / "fed.log"
            log.parent.mkdir(parents=True)
            log.write_text("\n".join([
                live(1, False, "ANALYSIS", 50, 10, 50),
                "SEARCH_SPACE_WORK|seq=1|supportPrefixes=2|supportLeaves=1|relocationLeaves=0",
                live(2, True, "ANALYSIS", 100, 20, 100),
                live(2, True, "CLOSURE_REPLAY", 80, 80, 80, calls=3),
                "SEARCH_SPACE_WORK|seq=2|supportPrefixes=9|supportLeaves=5|relocationLeaves=4",
                live(3, True, "ANALYSIS", 40, 10, 40),
                live(3, True, "SOURCE_PRUNING", 30, 30, 30, calls=2),
                "SEARCH_SPACE_WORK|seq=3|supportPrefixes=12|supportLeaves=7|relocationLeaves=6",
            ]) + "\n")

            report = MODULE.build_report(campaign)
            run = report["runs"][0]
            self.assertEqual([2, 3], [row["seq"] for row in run["terminalAnalyses"]])
            self.assertEqual(100, run["terminalAnalyses"][0]["analysisInclusive"]["inclusiveWallNanos"])
            self.assertEqual(100, run["terminalAnalyses"][0]["observedNonOverlappingExclusive"]["wallNanos"])
            self.assertEqual(12, run["terminalAnalyses"][1]["workCumulativeAtTerminal"]["supportPrefixes"])
            self.assertEqual(140, report["summary"]["analysisInclusiveTotals"]["inclusiveWallNanos"])
            self.assertEqual(30, report["summary"]["phaseTotalsAcrossAnalyses"]["ANALYSIS"]["exclusiveAllocatedBytes"])
            self.assertEqual(12, report["summary"]["sumOfRunFinalCumulativeWork"]["supportPrefixes"])
            self.assertEqual(2, report["groups"]["ml_logreg:candidate"]["terminalAnalysisCount"])
            self.assertEqual(12, report["groups"]["ml_logreg:candidate"]
                             ["sumOfRunFinalCumulativeWork"]["supportPrefixes"])
            self.assertIn("cumulative within each log", report["semantics"]["work"])


if __name__ == "__main__":
    unittest.main()
