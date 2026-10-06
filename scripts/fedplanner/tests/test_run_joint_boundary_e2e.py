import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


MODULE = Path(__file__).resolve().parents[1] / "run_joint_boundary_e2e.py"
SPEC = importlib.util.spec_from_file_location("run_joint_boundary_e2e", MODULE)
runner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


class JointBoundaryE2ETest(unittest.TestCase):
    def test_cases_cover_true_false_privacy_and_joint_shapes(self):
        by_name = {case.name: case for case in runner.cases()}
        self.assertEqual({"l2svm_true_01", "l2svm_false_m11", "joint_correlated_aa",
                          "joint_correlated_bb", "joint_independent_ab", "joint_independent_ba",
                          "joint_independent_private_ab_negative",
                          "joint_loop_toggle", "joint_function_calls",
                          "joint_function_private_mix_negative",
                          "joint_branch_upload",
                          "l2svm_protected_y_negative"}, set(by_name))
        self.assertEqual("private", by_name["l2svm_protected_y_negative"].y_privacy)
        self.assertFalse(by_name["l2svm_protected_y_negative"].expected_success)
        self.assertTrue(by_name["l2svm_true_01"].requires_action_evidence)
        self.assertTrue(by_name["joint_branch_upload"].requires_branch_upload)

    def test_programs_pin_branch_and_privacy_inputs(self):
        true_case = runner.cases()[0]
        false_case = runner.cases()[1]
        correlated = {case.name: case for case in runner.cases()}["joint_correlated_aa"]
        self.assertIn("Y_l2svm_true_01", runner.program(true_case, True))
        self.assertIn("Y_l2svm_false_m11", runner.program(false_case, True))
        correlated_program = runner.program(correlated, True)
        self.assertIn("localhost:13001//evidence/data/A1.csv", correlated_program)
        self.assertIn("localhost:13002//evidence/data/B1.csv", correlated_program)
        self.assertIn("if(flag){U=A1;V=A2;}else{U=B1;V=B2;}", correlated_program)
        self.assertIn("JOINT_E2E_NORM2", runner.program(true_case, False))

    def test_independent_loop_and_call_programs_cover_required_control_flow(self):
        by_name = {case.name: case for case in runner.cases()}
        ab = runner.program(by_name["joint_independent_ab"], True)
        ba = runner.program(by_name["joint_independent_ba"], True)
        private_ab = runner.program(by_name["joint_independent_private_ab_negative"], True)
        loop = runner.program(by_name["joint_loop_toggle"], True)
        calls = runner.program(by_name["joint_function_calls"], True)
        function_negative = runner.program(by_name["joint_function_private_mix_negative"], True)
        upload = runner.program(by_name["joint_branch_upload"], True)
        self.assertIn("if(flagU){U=A1;}else{U=B1;}", ab)
        self.assertIn("if(flagV){V=A2;}else{V=B2;}", ab)
        self.assertIn("flagU=sum(A1)<0;flagV=sum(A1)>0", ba)
        self.assertIn("A2_PUBLIC.csv", ab)
        self.assertIn("B2_PUBLIC.csv", ba)
        self.assertNotIn("_PUBLIC.csv", private_ab)
        self.assertEqual("infeasible_private_tuple",
                         by_name["joint_independent_private_ab_negative"].expected_failure)
        self.assertIn("while(i<=2)", loop)
        self.assertEqual(2, calls.count("poolJoin("))
        self.assertIn("SC=sum(C);SD=sum(D)", calls)
        self.assertIn("Z=rbind(matrix(SC,rows=1,cols=1),matrix(SD,rows=1,cols=1))", calls)
        self.assertIn('print("JOINT_E2E_CALL_C="+SC)', calls)
        self.assertIn('print("JOINT_E2E_CALL_D="+SD)', calls)
        self.assertIn("Z=C+D", function_negative)
        self.assertEqual("infeasible_private_tuple",
                         by_name["joint_function_private_mix_negative"].expected_failure)
        self.assertNotIn("poolJoin(", calls.split("return(matrix[double] R)", 1)[1].split("}", 1)[0])
        self.assertIn("flag=sum(X)>0", upload)
        self.assertIn("Y=matrix(0,rows=nrow(X),cols=ncol(X))", upload)
        self.assertIn("else{Y=X;}", upload)
        self.assertIn("Z=Y+X", upload)

    def test_docker_command_is_pinned_isolated_and_read_only(self):
        run = Path("/home/mchoi/joint-boundary-e2e-runtime/example-run")
        classes = run / "frozen-inputs/main-classes"
        test_classes = run / "frozen-inputs/test-classes"
        dependencies = run / "frozen-inputs/dependencies"
        _, command = runner.docker_command(run, classes, test_classes, dependencies)
        self.assertIn("never", command)
        self.assertIn("none", command)
        self.assertIn(runner.PINNED_IMAGE, command)
        self.assertIn(f"{classes}:/engine/classes:ro", command)
        self.assertIn(f"{test_classes}:/engine/test-classes:ro", command)
        self.assertIn(f"{dependencies}:/deps:ro", command)
        self.assertNotIn("/overlay", " ".join(command))
        self.assertEqual(len([value for value in command if value == str(run)]), 0)

    def test_default_inputs_are_from_the_current_repository(self):
        args = runner.parse_args([])
        self.assertEqual(runner.REPO_ROOT / "target/classes", args.classes)
        self.assertEqual(runner.REPO_ROOT / "target/test-classes", args.test_classes)
        self.assertEqual(runner.REPO_ROOT / "target/lib", args.dependencies)
        self.assertEqual(runner.REPO_ROOT / "src/main/java", args.main_sources)
        self.assertEqual(runner.REPO_ROOT / "src/test/java", args.test_sources)
        self.assertEqual(runner.REPO_ROOT / "target/joint-boundary-e2e-runtime",
                         args.stage_root)

    def test_stage_root_is_explicit_and_existing_run_is_never_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            explicit = root / "docker-visible"
            args = runner.parse_args(["--stage-root", str(explicit)])
            self.assertEqual(explicit, args.stage_root)
            run = root / "evidence" / "unique-run"
            run.mkdir(parents=True)
            stage = runner.allocate_stage(args.stage_root, run)
            self.assertEqual(explicit / run.name, stage)
            with self.assertRaises(FileExistsError):
                runner.allocate_stage(args.stage_root, run)

    def test_pool_inputs_are_protected_and_all_workers_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.write_inputs(root)
            runner.write_fixtures(root)
            script = runner.write_container_script(root).read_text(encoding="utf-8")
            protected = json.loads((root / "data/A1.csv.mtd").read_text(encoding="utf-8"))
            public = json.loads((root / "data/A1_PUBLIC.csv.mtd").read_text(encoding="utf-8"))
        self.assertEqual("private-aggregate", protected["privacy"])
        self.assertNotIn("privacy", public)
        self.assertIn("-w 13000", script)
        self.assertIn("-w 13001", script)
        self.assertIn("-w 13002", script)
        self.assertIn("org.junit.runner.JUnitCore", script)
        self.assertIn(runner.DEFAULT_MODEL_PROOF_CLASS, script)
        self.assertLess(script.index("class-preflight-expected.json"), script.index("-w 13000"))

    def test_class_preflight_pins_main_and_model_proof_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            overlay = root / "main"
            test_classes = root / "test"
            for relative in runner.CLASS_PREFLIGHT_MAIN:
                path = overlay / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((relative + "\n").encode())
            proof_relative = Path(*runner.DEFAULT_MODEL_PROOF_CLASS.split(".")).with_suffix(".class")
            proof = test_classes / proof_relative
            proof.parent.mkdir(parents=True)
            proof.write_bytes(b"proof\n")
            proof_hash = runner.sha256(proof)
            expected = runner.class_preflight_expectations(
                overlay, test_classes, runner.DEFAULT_MODEL_PROOF_CLASS)
            script_root = root / "script"
            script_root.mkdir()
            script = runner.write_container_script(
                script_root, selected=(runner.cases()[0],), class_preflight=expected
            ).read_text(encoding="utf-8")
            receipt = json.loads(
                (script_root / "class-preflight-expected.json").read_text(encoding="utf-8"))
        self.assertEqual(len(runner.CLASS_PREFLIGHT_MAIN) + 1, len(expected))
        self.assertEqual(expected, receipt)
        self.assertIn("mandatory merged-build preflight failed", script)
        self.assertIn(proof_hash, {item["sha256"] for item in expected.values()})

    def test_freeze_tree_copies_complete_input_and_returns_frozen_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            destination = root / "frozen"
            (source / "nested").mkdir(parents=True)
            (source / "A.class").write_bytes(b"main\n")
            (source / "nested/B.class").write_bytes(b"nested\n")
            inventory = runner.freeze_tree(source, destination)
            frozen_inventory = runner.tree_inventory(destination)
        self.assertEqual({"A.class", "nested/B.class"}, set(inventory))
        self.assertEqual(inventory, frozen_inventory)

    def test_missing_class_preflight_fails_overall_result(self):
        selected = (next(case for case in runner.cases()
                         if case.name == "l2svm_protected_y_negative"),)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / selected[0].name
            case_dir.mkdir(parents=True)
            (case_dir / "fed.rc").write_text("0\n", encoding="utf-8")
            (case_dir / "fed.log").write_text(
                "privacy constraint rejected private input\n", encoding="utf-8")
            result = runner.evaluate(root, 0, selected)
        self.assertFalse(result["classPreflightPassed"])
        self.assertEqual("MISSING", result["classPreflight"]["status"])
        self.assertEqual(result["classPreflight"], result["overlayPreflight"])
        self.assertEqual("FAILED", result["status"])

    def test_fedreq_debug_is_opt_in_for_coordinator_and_workers(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = runner.write_container_script(
                root, selected=(runner.cases()[0],), debug_fedreq=True).read_text()
        self.assertIn("JAVA_TOOL_OPTIONS=-Dsysds.debug.fedreq=true", script)

    def test_marker_comparison_requires_finite_complete_fingerprint(self):
        expected = {"SUM": 1.0, "NORM2": 2.0, "ROWS": 3.0, "COLS": 1.0}
        self.assertTrue(runner.close_markers(expected, dict(expected)))
        self.assertFalse(runner.close_markers(expected, {"SUM": 1.0}))
        invalid = dict(expected)
        invalid["SUM"] = float("nan")
        self.assertFalse(runner.close_markers(expected, invalid))

    def test_function_call_markers_detect_swapped_results(self):
        expected = {"SUM": 186.0, "NORM2": 20340.0, "ROWS": 2.0, "COLS": 1.0,
                    "CALL_C": 54.0, "CALL_D": 132.0}
        self.assertTrue(runner.close_markers(expected, dict(expected)))
        swapped = dict(expected, CALL_C=132.0, CALL_D=54.0)
        self.assertFalse(runner.close_markers(expected, swapped))

    def test_marker_parser_ignores_runtime_explain_literals(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "run.log"
            log.write_text(
                "------CP + JOINT_E2E_SUM=.SCALAR.STRING.true\n"
                "JOINT_E2E_SUM=-1.25e-2\nJOINT_E2E_NORM2=2.5\n"
                "JOINT_E2E_ROWS=3\nJOINT_E2E_COLS=1\n", encoding="utf-8")
            parsed = runner.markers(log)
        self.assertEqual(-0.0125, parsed["SUM"])
        self.assertEqual(4, len(parsed))

    def test_audit_reader_and_action_extraction_are_hermetic(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "audit.jsonl").write_text(json.dumps({
                "schema": "fed-runtime-capability-v1",
                "plannerSyntheticActionKey": "action-1",
            }) + "\n", encoding="utf-8")
            log = root / "run.log"
            log.write_text("prefetch X Y\n", encoding="utf-8")
            rows, errors = runner.read_audits(root)
            actions = runner.action_diagnostics(log, rows)
        self.assertFalse(errors)
        self.assertEqual("fed-runtime-capability-v1", rows[0]["schema"])
        self.assertTrue(any("action-1" in action for action in actions))
        self.assertTrue(any("prefetch" in action for action in actions))

    def test_case_filter_records_requested_result_cardinality(self):
        selected = (next(case for case in runner.cases()
                         if case.name == "l2svm_protected_y_negative"),)
        args = runner.parse_args(["--case", selected[0].name])
        self.assertEqual([selected[0].name], args.selected_cases)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / selected[0].name
            case_dir.mkdir(parents=True)
            (case_dir / "fed.rc").write_text("0\n", encoding="utf-8")
            (case_dir / "fed.log").write_text(
                "privacy constraint rejected private input\n", encoding="utf-8")
            result = runner.evaluate(root, 0, selected)
        self.assertEqual([selected[0].name], result["requestedCases"])
        self.assertEqual(1, len(result["cases"]))
        self.assertTrue(result["actionEvidencePresent"])

    def test_branch_upload_requires_matched_fout_lowering_and_execution(self):
        selected = (next(case for case in runner.cases()
                         if case.name == "joint_branch_upload"),)
        markers = ("JOINT_E2E_SUM=1\nJOINT_E2E_NORM2=1\n"
                   "JOINT_E2E_ROWS=1\nJOINT_E2E_COLS=1\n")
        lowering = ("[PlannerRuntimeAudit][Lowering-Synthetic] status=MATCH plan=p "
                    "stage=FOUT opcode=fed_fout plannedPhysical=FED/FOUT/FULL "
                    "actual=FED/FOUT/FULL\n")
        execution = ("[PlannerRuntimeAudit][Execution] status=MATCH plan=p "
                     "opcode=fed_fout plannedPhysical=FED/FOUT/FULL "
                     "actual=FED/FOUT/FULL\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / selected[0].name
            case_dir.mkdir(parents=True)
            (case_dir / "cp.rc").write_text("0\n", encoding="utf-8")
            (case_dir / "fed.rc").write_text("0\n", encoding="utf-8")
            (case_dir / "cp.log").write_text(markers, encoding="utf-8")
            (case_dir / "fed.log").write_text(
                markers + lowering + execution, encoding="utf-8")
            result = runner.evaluate(root, 0, selected)
            self.assertTrue(result["cases"][0]["branchUploadLoweredAndExecuted"])
            self.assertTrue(result["cases"][0]["passed"])
            (case_dir / "fed.log").write_text(markers + lowering, encoding="utf-8")
            missing_execution = runner.evaluate(root, 0, selected)
        self.assertFalse(
            missing_execution["cases"][0]["branchUploadLoweredAndExecuted"])
        self.assertFalse(missing_execution["cases"][0]["passed"])

    def test_private_tuple_negative_accepts_exact_infeasible_assignment(self):
        selected = (next(case for case in runner.cases()
                         if case.name == "joint_function_private_mix_negative"),)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / selected[0].name
            case_dir.mkdir(parents=True)
            (case_dir / "fed.rc").write_text("0\n", encoding="utf-8")
            (case_dir / "fed.log").write_text(
                "IllegalArgumentException -- EXACT_VE_NO_FEASIBLE_ASSIGNMENT\n",
                encoding="utf-8",
            )
            result = runner.evaluate(root, 0, selected)
        self.assertTrue(result["cases"][0]["rejectionDiagnostic"])
        self.assertTrue(result["cases"][0]["passed"])


if __name__ == "__main__":
    unittest.main()
