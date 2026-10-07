import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


MODULE = Path(__file__).resolve().parents[1] / "run_joint_boundary_e2e.py"
SPEC = importlib.util.spec_from_file_location("run_joint_boundary_e2e", MODULE)
runner = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


def matrix_rank(values):
    matrix = [row[:] for row in values]
    rank = 0
    for column in range(len(matrix[0])):
        pivot = next((row for row in range(rank, len(matrix))
                      if abs(matrix[row][column]) > 1e-12), None)
        if pivot is None:
            continue
        matrix[rank], matrix[pivot] = matrix[pivot], matrix[rank]
        divisor = matrix[rank][column]
        matrix[rank] = [value / divisor for value in matrix[rank]]
        for row in range(len(matrix)):
            if row == rank:
                continue
            scale = matrix[row][column]
            matrix[row] = [value - scale * pivot_value
                           for value, pivot_value in zip(matrix[row], matrix[rank])]
        rank += 1
    return rank


class JointBoundaryE2ETest(unittest.TestCase):
    def test_cases_cover_true_false_privacy_and_joint_shapes(self):
        by_name = {case.name: case for case in runner.cases()}
        self.assertEqual({"l2svm_true_01", "l2svm_false_m11", "joint_correlated_aa",
                          "joint_correlated_bb", "joint_independent_ab", "joint_independent_ba",
                          "joint_independent_private_ab_negative",
                          "joint_loop_toggle", "joint_function_calls",
                          "joint_dynamic_reverse",
                          "joint_function_private_mix_negative",
                          "joint_branch_upload",
                          "l2svm_protected_y_negative", "ml_logreg", "ml_l2svm",
                          "ml_lm", "ml_steplm", "ml_steplm_local_matrix",
                          "ml_logreg_gd", "ml_l2svm_gd",
                          "ml_lm_gd"},
                         set(by_name))
        self.assertEqual("private", by_name["l2svm_protected_y_negative"].y_privacy)
        self.assertFalse(by_name["l2svm_protected_y_negative"].expected_success)
        self.assertTrue(by_name["l2svm_true_01"].requires_action_evidence)
        self.assertTrue(by_name["joint_branch_upload"].requires_branch_upload)
        self.assertEqual({"ml_logreg", "ml_l2svm", "ml_lm", "ml_steplm",
                          "ml_steplm_local_matrix"},
                         {case.name for case in runner.cases()
                          if case.training and not case.requires_loss_progress})
        self.assertEqual({"ml_logreg_gd", "ml_l2svm_gd", "ml_lm_gd"},
                         {case.name for case in runner.cases()
                          if case.requires_loss_progress})
        self.assertFalse({"ml_logreg", "ml_l2svm", "ml_lm", "ml_steplm",
                          "ml_steplm_local_matrix",
                          "ml_logreg_gd", "ml_l2svm_gd", "ml_lm_gd"}
                         & {case.name for case in runner.default_cases()})

    def test_ml_training_programs_use_three_protected_shards_and_write_full_model(self):
        by_name = {case.name: case for case in runner.cases()}
        for name, builtin in (("ml_logreg", "multiLogReg"),
                              ("ml_l2svm", "l2svm"), ("ml_lm", "lmCG")):
            script = runner.program(by_name[name], True)
            self.assertIn(f"m={builtin}(", script)
            self.assertIn("localhost:13000//evidence/data/X_ML_0.csv", script)
            self.assertIn("localhost:13001//evidence/data/X_ML_1.csv", script)
            self.assertIn("localhost:13002//evidence/data/X_ML_2.csv", script)
            self.assertIn("list(128,0),list(192,8)", script)
            self.assertIn('write(m,$MODEL_OUTPUT,format="csv")', script)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.write_inputs(root, tuple(by_name[name] for name in
                                            ("ml_logreg", "ml_l2svm", "ml_lm")))
            public = (root / "data/X_ML_PUBLIC.csv").read_text()
            shards = "".join((root / f"data/X_ML_{index}.csv").read_text()
                             for index in range(3))
            self.assertEqual(public, shards)
            for index in range(3):
                metadata = json.loads((root / f"data/X_ML_{index}.csv.mtd").read_text())
                self.assertEqual((64, 8, "private-aggregate"),
                                 (metadata["rows"], metadata["cols"], metadata["privacy"]))

    def test_steplm_fixture_is_public_nondegenerate_and_uses_one_full_worker(self):
        case = next(case for case in runner.cases() if case.name == "ml_steplm")
        cp_script = runner.program(case, False)
        fed_script = runner.program(case, True)
        self.assertIn("[m,s]=steplm(", fed_script)
        self.assertIn("maxi=20", fed_script)
        self.assertIn("localhost:13000//evidence/data/X_STEPLM_PUBLIC.csv", fed_script)
        self.assertIn("localhost:13000//evidence/data/Y_STEPLM_PUBLIC.csv", fed_script)
        self.assertIn("ranges=list(list(0,0),list(20,5))", fed_script)
        self.assertIn("ranges=list(list(0,0),list(20,1))", fed_script)
        self.assertNotIn("localhost:13001", fed_script)
        self.assertNotIn("localhost:13002", fed_script)
        self.assertIn('write(s,$SELECTION_OUTPUT,format="csv")', fed_script)
        self.assertIn('read("/evidence/data/X_STEPLM_PUBLIC.csv"', cp_script)
        self.assertIn('read("/evidence/data/Y_STEPLM_PUBLIC.csv"', cp_script)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.write_inputs(root, (case,))
            x_rows, x_errors = runner.read_matrix(root / "data/X_STEPLM_PUBLIC.csv")
            y_rows, y_errors = runner.read_matrix(root / "data/Y_STEPLM_PUBLIC.csv")
            x_metadata = json.loads(
                (root / "data/X_STEPLM_PUBLIC.csv.mtd").read_text())
            y_metadata = json.loads(
                (root / "data/Y_STEPLM_PUBLIC.csv.mtd").read_text())
        self.assertEqual([], x_errors + y_errors)
        self.assertEqual((20, 5), (len(x_rows), len(x_rows[0])))
        self.assertEqual((20, 1), (len(y_rows), len(y_rows[0])))
        self.assertNotIn("privacy", x_metadata)
        self.assertNotIn("privacy", y_metadata)
        self.assertEqual(5, matrix_rank(x_rows))
        self.assertGreater(len({row[0] for row in y_rows}), 1)

    def test_steplm_local_matrix_uses_same_full_rank_literal_without_csv_input(self):
        by_name = {case.name: case for case in runner.cases()}
        case = by_name["ml_steplm_local_matrix"]
        cp_script = runner.program(case, False)
        fed_script = runner.program(case, True)
        x, y = runner.steplm_dataset()
        self.assertEqual(5, matrix_rank([list(row) for row in x]))
        self.assertGreater(len(set(y)), 1)
        x_literal = f"X_LOCAL={runner.dml_matrix_literal(x)};"
        y_literal = ("Y_LOCAL=" + runner.dml_matrix_literal(
            tuple((value,) for value in y)) + ";")
        for script in (cp_script, fed_script):
            self.assertIn(x_literal, script)
            self.assertIn(y_literal, script)
            self.assertIn('matrix("', script)
            self.assertNotIn("matrix(c(", script)
            self.assertNotIn("read(", script)
            self.assertNotIn("/evidence/data/", script)
            self.assertIn("maxi=20", script)
            self.assertIn('write(m,$MODEL_OUTPUT,format="csv")', script)
            self.assertIn('write(s,$SELECTION_OUTPUT,format="csv")', script)
        self.assertIn("X=X_LOCAL;", cp_script)
        self.assertIn("Y=Y_LOCAL;", cp_script)
        self.assertIn("X=federated(local_matrix=X_LOCAL,", fed_script)
        self.assertIn("Y=federated(local_matrix=Y_LOCAL,", fed_script)
        self.assertIn('addresses=list("localhost:13000")', fed_script)
        self.assertIn("ranges=list(list(0,0),list(20,5))", fed_script)
        self.assertIn("ranges=list(list(0,0),list(20,1))", fed_script)
        self.assertFalse(case.default_selected)
        self.assertNotIn(case, runner.default_cases())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.write_inputs(root, (case,))
            self.assertFalse((root / "data/X_STEPLM_PUBLIC.csv").exists())
            self.assertFalse((root / "data/Y_STEPLM_PUBLIC.csv").exists())

    def test_ml_model_and_planner_evidence_parsers_are_strict(self):
        trace = ("[PlannerTrace][DP-IncrementalRegional] phase=INITIAL_BOUND merges=0 "
                 "lower=1.0 upper=9.0 dpNanos=10 plannerElapsedNanos=20\n"
                 "[PlannerTrace][DP-IncrementalRegional] phase=SEED_BOUNDARY merges=0 "
                 "lower=1.0 upper=4.0 dpNanos=30 plannerElapsedNanos=40\n"
                 "Total compilation time: 0.125 sec.\nTotal execution time: 1.500 sec.\n")
        checkpoints = runner.planner_checkpoints(trace)
        self.assertEqual(["INITIAL_BOUND", "SEED_BOUNDARY"],
                         [item["phase"] for item in checkpoints])
        self.assertEqual(30, checkpoints[1]["dpNanos"])
        self.assertEqual({"compilationSeconds": 0.125, "executionSeconds": 1.5},
                         runner.runtime_statistics(trace))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reference = root / "reference.csv"
            actual = root / "actual.csv"
            reference.write_text("1.0,2.0\n3.0,4.0\n")
            actual.write_text("1.00000001,2.0\n3.0,4.0\n")
            self.assertTrue(runner.compare_models(reference, actual)["matched"])
            actual.write_text("1.01,2.0\n3.0,4.0\n")
            self.assertFalse(runner.compare_models(reference, actual)["matched"])
            actual.write_text("1.0,2.0,3.0,4.0\n")
            self.assertFalse(runner.compare_models(reference, actual)["matched"])

    def test_steplm_comparison_requires_b_shape_and_exact_selection_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            cp_b = root / "cp-b.csv"
            fed_b = root / "fed-b.csv"
            cp_s = root / "cp-s.csv"
            fed_s = root / "fed-s.csv"
            cp_b.write_text("1\n2\n3\n4\n5\n")
            fed_b.write_text("1.00000001\n2\n3\n4\n5\n")
            cp_s.write_text("3,1,5\n")
            fed_s.write_text("3,1,5\n")
            self.assertTrue(runner.compare_models(cp_b, fed_b, (5, 1))["matched"])
            self.assertTrue(runner.compare_selections(cp_s, fed_s, 5)["matched"])
            fed_s.write_text("1,3,5\n")
            self.assertFalse(runner.compare_selections(cp_s, fed_s, 5)["matched"])
            fed_s.write_text("3\n1\n5\n")
            self.assertFalse(runner.compare_selections(cp_s, fed_s, 5)["matched"])
            cp_s.write_text("0\n")
            fed_s.write_text("0\n")
            self.assertTrue(runner.compare_selections(cp_s, fed_s, 5)["matched"])

    def test_steplm_evaluation_rejects_wrong_b_shape_or_selection_order(self):
        fingerprint = ("JOINT_E2E_SUM=15\nJOINT_E2E_NORM2=55\n"
                       "JOINT_E2E_ROWS=5\nJOINT_E2E_COLS=1\n")
        trace = ("[PlannerTrace][DP-IncrementalRegional] phase=INITIAL_BOUND merges=0 "
                 "lower=1 upper=2 dpNanos=10 plannerElapsedNanos=20\n"
                 "[PlannerTrace][DP-IncrementalRegional] phase=EXACT merges=1 "
                 "lower=1 upper=1 dpNanos=30 plannerElapsedNanos=40\n")
        by_name = {case.name: case for case in runner.cases()}
        for name in ("ml_steplm", "ml_steplm_local_matrix"):
            case = by_name[name]
            with self.subTest(case=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                case_dir = root / "cases" / case.name
                case_dir.mkdir(parents=True)
                for mode in ("cp", "fed"):
                    (case_dir / f"{mode}.rc").write_text("0\n")
                    (case_dir / f"{mode}.log").write_text(
                        fingerprint + (trace if mode == "fed" else ""))
                    (case_dir / f"{mode}-model.csv").write_text("1\n2\n3\n4\n5\n")
                    (case_dir / f"{mode}-selection.csv").write_text("3,1,5\n")
                result = runner.evaluate(root, 0, (case,))["cases"][0]
                self.assertTrue(result["passed"])
                self.assertEqual([5, 1], result["modelComparison"]["actualShape"])
                self.assertEqual([3, 1, 5], result["selectionComparison"]["actual"])
                (case_dir / "fed-selection.csv").write_text("1,3,5\n")
                self.assertFalse(runner.evaluate(root, 0, (case,))["cases"][0]["passed"])
                (case_dir / "fed-selection.csv").write_text("3,1,5\n")
                (case_dir / "fed-model.csv").write_text("1,2,3,4,5\n")
                self.assertFalse(runner.evaluate(root, 0, (case,))["cases"][0]["passed"])

    def test_gradient_training_programs_iterate_and_report_loss(self):
        by_name = {case.name: case for case in runner.cases()}
        for name in ("ml_logreg_gd", "ml_l2svm_gd", "ml_lm_gd"):
            script = runner.program(by_name[name], True)
            self.assertIn("while(i<=20)", script)
            self.assertIn("t(X)%*%", script)
            self.assertIn('print("JOINT_E2E_LOSS_INITIAL="+loss0)', script)
            self.assertIn('print("JOINT_E2E_LOSS_FINAL="+loss1)', script)
            self.assertIn('write(m,$MODEL_OUTPUT,format="csv")', script)

    def test_gradient_training_requires_loss_decrease_and_terminal_trace(self):
        case = next(case for case in runner.cases() if case.name == "ml_lm_gd")
        fingerprint = ("JOINT_E2E_SUM=1\nJOINT_E2E_NORM2=1\n"
                       "JOINT_E2E_ROWS=8\nJOINT_E2E_COLS=1\n")
        initial = ("[PlannerTrace][DP-IncrementalRegional] phase=INITIAL_BOUND merges=0 "
                   "lower=1 upper=2 dpNanos=10 plannerElapsedNanos=20\n")
        terminal = ("[PlannerTrace][DP-IncrementalRegional] phase=EXACT merges=1 "
                    "lower=1 upper=1 dpNanos=30 plannerElapsedNanos=40\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / case.name
            case_dir.mkdir(parents=True)
            for mode in ("cp", "fed"):
                (case_dir / f"{mode}.rc").write_text("0\n")
                (case_dir / f"{mode}-model.csv").write_text("1\n0\n0\n0\n0\n0\n0\n0\n")
            good = fingerprint + "JOINT_E2E_LOSS_INITIAL=5\nJOINT_E2E_LOSS_FINAL=1\n"
            (case_dir / "cp.log").write_text(good)
            (case_dir / "fed.log").write_text(good + initial + terminal)
            result = runner.evaluate(root, 0, (case,))["cases"][0]
            self.assertTrue(result["passed"])
            self.assertTrue(result["lossProgress"]["decreased"])
            (case_dir / "fed.log").write_text(good + initial)
            self.assertFalse(runner.evaluate(root, 0, (case,))["cases"][0]["passed"])
            stalled = fingerprint + "JOINT_E2E_LOSS_INITIAL=5\nJOINT_E2E_LOSS_FINAL=5\n"
            (case_dir / "cp.log").write_text(stalled)
            (case_dir / "fed.log").write_text(stalled + initial + terminal)
            stalled_result = runner.evaluate(root, 0, (case,))["cases"][0]
            self.assertFalse(stalled_result["passed"])
            self.assertFalse(stalled_result["lossProgress"]["decreased"])

    def test_ml_java_command_enables_trace_and_places_named_argument_last(self):
        by_name = {case.name: case for case in runner.cases()}
        training = runner.java_command(by_name["ml_lm"], "fed")
        steplm = runner.java_command(by_name["ml_steplm"], "fed")
        local_steplm = runner.java_command(by_name["ml_steplm_local_matrix"], "fed")
        ordinary = runner.java_command(by_name["l2svm_true_01"], "fed")
        self.assertIn("-Dsysds.fedplanner.trace=true", training)
        self.assertTrue(training.endswith(
            "-nvargs MODEL_OUTPUT=/evidence/cases/ml_lm/fed-model.csv"))
        self.assertTrue(steplm.endswith(
            "-nvargs MODEL_OUTPUT=/evidence/cases/ml_steplm/fed-model.csv "
            "SELECTION_OUTPUT=/evidence/cases/ml_steplm/fed-selection.csv"))
        self.assertTrue(local_steplm.endswith(
            "-nvargs MODEL_OUTPUT=/evidence/cases/ml_steplm_local_matrix/fed-model.csv "
            "SELECTION_OUTPUT=/evidence/cases/ml_steplm_local_matrix/fed-selection.csv"))
        self.assertIn("-Dsysds.fedplanner.trace=true", local_steplm)
        self.assertNotIn("sysds.fedplanner.trace=true", ordinary)
        self.assertNotIn("-nvargs", ordinary)

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

    def test_dynamic_reverse_uses_two_protected_row_shards(self):
        case = next(case for case in runner.cases() if case.name == "joint_dynamic_reverse")
        script = runner.program(case, True)
        self.assertIn('localhost:13001//evidence/data/X_TOP.csv', script)
        self.assertIn('localhost:13002//evidence/data/X_BOTTOM.csv', script)
        self.assertIn('ranges=list(list(0,0),list(4,3),list(4,0),list(8,3))', script)
        self.assertIn('if(flag){T=rev(X);}else{T=rev(X);}', script)
        self.assertIn('Z=exp(T)', script)
        self.assertIn('sum(rowSums(Z)*seq(1,nrow(Z)))', script)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner.write_inputs(root, (case,))
            shards = []
            for name in ('X_TOP', 'X_BOTTOM'):
                metadata = json.loads((root / f'data/{name}.csv.mtd').read_text())
                self.assertEqual((4, 3, 'private-aggregate'),
                                 (metadata['rows'], metadata['cols'], metadata['privacy']))
                shards.append((root / f'data/{name}.csv').read_text())
            self.assertEqual((root / 'data/X_PUBLIC.csv').read_text(), ''.join(shards))

    def test_dynamic_reverse_requires_weighted_result_and_fed_execution(self):
        case = next(case for case in runner.cases() if case.name == "joint_dynamic_reverse")
        numeric = ('JOINT_E2E_SUM=24\nJOINT_E2E_NORM2=24\n'
                   'JOINT_E2E_ROWS=8\nJOINT_E2E_COLS=3\nJOINT_E2E_WEIGHTED=108\n')
        execution = ''.join(
            f'[PlannerRuntimeAudit][Execution] status=MATCH plan=test opcode={opcode} '
            'plannedTarget=FED/FOUT plannedPhysical=FED/FOUT actual=FED/FOUT count=1\n'
            for opcode in ('rev', 'exp'))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            logs = root / 'cases' / case.name
            logs.mkdir(parents=True)
            for mode in ('cp', 'fed'):
                (logs / f'{mode}.rc').write_text('0\n')
            (logs / 'cp.log').write_text(numeric)
            (logs / 'fed.log').write_text(numeric + execution)
            self.assertTrue(runner.evaluate(root, 0, (case,))['cases'][0]['passed'])
            for bad in (numeric, numeric + execution.replace('opcode=rev', 'opcode=other'),
                        numeric + execution.replace('opcode=exp', 'opcode=other'),
                        numeric.replace('WEIGHTED=108', 'WEIGHTED=109') + execution):
                (logs / 'fed.log').write_text(bad)
                self.assertFalse(runner.evaluate(root, 0, (case,))['cases'][0]['passed'])
            missing_weight = numeric.replace('JOINT_E2E_WEIGHTED=108\n', '')
            (logs / 'cp.log').write_text(missing_weight)
            (logs / 'fed.log').write_text(missing_weight + execution)
            self.assertFalse(runner.evaluate(root, 0, (case,))['cases'][0]['passed'])

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

    def test_jfr_profile_is_opt_in_and_fed_coordinator_only(self):
        case = next(case for case in runner.cases() if case.name == "ml_logreg")
        self.assertTrue(runner.parse_args(["--profile-jfr"]).profile_jfr)
        cp_command = runner.java_command(case, "cp", 120, profile_jfr=True)
        fed_command = runner.java_command(case, "fed", 120, profile_jfr=True)
        self.assertNotIn("FlightRecorder", cp_command)
        self.assertIn("-XX:FlightRecorderOptions=stackdepth=256", fed_command)
        self.assertIn("settings=profile,disk=true,dumponexit=true,duration=119s", fed_command)
        self.assertIn(f"filename=/evidence/cases/{case.name}/fed.jfr", fed_command)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = runner.write_container_script(
                root, case_timeout_seconds=120, selected=(case,),
                profile_jfr=True).read_text()
        worker_lines = [line for line in script.splitlines() if " -w 1300" in line]
        self.assertEqual(3, len(worker_lines))
        self.assertTrue(all("FlightRecorder" not in line for line in worker_lines))
        self.assertEqual(1, script.count("StartFlightRecording"))
        manifest = runner.jfr_profile_manifest((case,), True, 120)
        self.assertTrue(manifest["recordProfile"])
        self.assertEqual("fed-coordinator-only", manifest["scope"])
        self.assertEqual(256, manifest["stackDepth"])
        self.assertEqual("cases/ml_logreg/fed.jfr", manifest["files"]["ml_logreg"]["path"])

    def test_enabled_jfr_profile_requires_nonempty_dump(self):
        case = next(case for case in runner.cases() if case.name == "ml_logreg")
        fingerprint = ("JOINT_E2E_SUM=1\nJOINT_E2E_NORM2=1\n"
                       "JOINT_E2E_ROWS=8\nJOINT_E2E_COLS=1\n")
        trace = ("[PlannerTrace][DP-IncrementalRegional] phase=INITIAL_BOUND merges=0 "
                 "lower=1 upper=2 dpNanos=10 plannerElapsedNanos=20\n"
                 "[PlannerTrace][DP-IncrementalRegional] phase=EXACT merges=1 "
                 "lower=1 upper=1 dpNanos=30 plannerElapsedNanos=40\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_dir = root / "cases" / case.name
            case_dir.mkdir(parents=True)
            for mode in ("cp", "fed"):
                (case_dir / f"{mode}.rc").write_text("0\n")
                (case_dir / f"{mode}.log").write_text(fingerprint + trace)
                (case_dir / f"{mode}-model.csv").write_text(
                    "1\n0\n0\n0\n0\n0\n0\n0\n")
            missing = runner.evaluate(root, 0, (case,), profile_jfr=True)["cases"][0]
            self.assertFalse(missing["passed"])
            self.assertFalse(missing["jfrProfilePassed"])
            self.assertIn("missing", missing["jfrProfile"]["parserError"])
            (case_dir / "fed.jfr").write_bytes(b"")
            with mock.patch.object(runner.subprocess, "run") as parser:
                empty = runner.evaluate(root, 0, (case,), profile_jfr=True)["cases"][0]
            self.assertFalse(empty["passed"])
            self.assertIn("empty", empty["jfrProfile"]["parserError"])
            parser.assert_not_called()
            (case_dir / "fed.jfr").write_bytes(b"FLR\x00profile")
            with mock.patch.object(
                    runner.subprocess, "run",
                    return_value=runner.subprocess.CompletedProcess(
                        ["jfr", "summary"], 1, stdout="", stderr="not a valid JFR file")) as parser:
                rejected = runner.evaluate(root, 0, (case,), profile_jfr=True)["cases"][0]
            self.assertFalse(rejected["passed"])
            self.assertFalse(rejected["jfrProfile"]["parserPassed"])
            self.assertIn("not a valid JFR", rejected["jfrProfile"]["parserError"])
            self.assertEqual(["jfr", "summary", str(case_dir / "fed.jfr")],
                             parser.call_args.args[0])
            self.assertEqual(runner.JFR_SUMMARY_TIMEOUT_SECONDS,
                             parser.call_args.kwargs["timeout"])
            with mock.patch.object(
                    runner.subprocess, "run",
                    return_value=runner.subprocess.CompletedProcess(
                        ["jfr", "summary"], 0, stdout="Version: 2.1\n", stderr="")) as parser:
                profile = runner.jfr_profile_manifest((case,), True, 300, root)
                present = runner.evaluate(
                    root, 0, (case,), profile_jfr=True,
                    jfr_profile_evidence=profile)["cases"][0]
            parser.assert_called_once()
            self.assertTrue(present["passed"])
            self.assertTrue(present["jfrProfilePassed"])
            self.assertTrue(present["jfrProfile"]["parserPassed"])
            self.assertIsNone(present["jfrProfile"]["parserError"])
            self.assertEqual(11, present["jfrProfile"]["size"])
            self.assertIsNotNone(present["jfrProfile"]["sha256"])
            with mock.patch.object(runner.subprocess, "run", side_effect=FileNotFoundError("jfr")):
                unavailable = runner.evaluate(root, 0, (case,), profile_jfr=True)["cases"][0]
            self.assertFalse(unavailable["passed"])
            self.assertIn("unavailable", unavailable["jfrProfile"]["parserError"])

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
