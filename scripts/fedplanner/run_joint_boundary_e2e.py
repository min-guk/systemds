#!/usr/bin/env python3
"""Pinned one-container E2E validation for joint boundary planning.

The coordinator and three federated workers share only the container loopback.
The merged repository's sources and compiled classes are copied into the fresh
evidence stage before execution; those frozen classes and read-only dependencies
are the only code mounted in the container. The image is never pulled.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid


PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CLASSES = REPO_ROOT / "target/classes"
DEFAULT_TEST_CLASSES = REPO_ROOT / "target/test-classes"
DEFAULT_DEPENDENCIES = REPO_ROOT / "target/lib"
DEFAULT_MAIN_SOURCES = REPO_ROOT / "src/main/java"
DEFAULT_TEST_SOURCES = REPO_ROOT / "src/test/java"
DEFAULT_OUTPUT_ROOT = Path(
    "/grid/3/cofee-lm-sweep-mchoi-20260914/joint-boundary-e2e-20261006")
DEFAULT_STAGE_ROOT = REPO_ROOT / "target/joint-boundary-e2e-runtime"
JFR_SUMMARY_TIMEOUT_SECONDS = 15
WORKER_PORT = 13000
POOL_A_PORT = 13001
POOL_B_PORT = 13002
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")
SAFE_JAVA_CLASS = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
DEFAULT_MODEL_PROOF_CLASS = (
    "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact."
    "JointBoundaryPhysicalModelProofTest")
MARKER = re.compile(
    r"^JOINT_E2E_(SUM|NORM2|ROWS|COLS|CALL_C|CALL_D|WEIGHTED|LOSS_INITIAL|LOSS_FINAL)="
    r"([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)$",
    re.MULTILINE)
CHECKPOINT = re.compile(r"^\[PlannerTrace\]\[DP-IncrementalRegional\] (?P<body>.+)$", re.MULTILINE)
CHECKPOINT_FIELD = re.compile(r"([A-Za-z][A-Za-z0-9]*)=([^ ]+)")
STATISTIC = re.compile(
    r"^(Total compilation time|Total execution time):\s*([0-9.]+) sec\.?$", re.MULTILINE)
AUDIT_VIOLATION = re.compile(r"(?m)^\[PlannerRuntimeAudit\].*\bstatus=(?:MISMATCH|UNKNOWN)\b")
TERMINAL_CHECKPOINT_PHASES = {"EXACT", "TARGET_REACHED", "TIME", "RESOURCE"}
ACTION_PATTERN = re.compile(
    r"(?i)(plannerSyntheticActionKey|localMaterializationAction|relocationAction|fed_refed|prefetch)")
CLASS_PREFLIGHT_MAIN = (
    "org/apache/sysds/hops/fedplanner/placement/PlacementRelationClosure.class",
    "org/apache/sysds/hops/fedplanner/placement/JointValueMapRelations.class",
    "org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/JointPhysicalCostRows.class",
    "org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactPhysicalCostModel.class",
    "org/apache/sysds/runtime/controlprogram/context/ExecutionContext.class",
)


@dataclass(frozen=True)
class Case:
    name: str
    kind: str
    y: tuple[float, ...] = ()
    y_privacy: str = "public"
    expected_success: bool = True
    requires_action_evidence: bool = False
    requires_fed_no_relocation: bool = False
    requires_branch_upload: bool = False
    expected_failure: str = ""
    training: bool = False
    requires_loss_progress: bool = False
    default_selected: bool = True


def cases() -> tuple[Case, ...]:
    return (
        Case("l2svm_true_01", "l2svm", (0, 1, 0, 1, 0, 1, 0, 1),
             requires_action_evidence=True),
        Case("l2svm_false_m11", "l2svm", (-1, 1, -1, 1, -1, 1, -1, 1)),
        Case("joint_correlated_aa", "correlated_aa", requires_fed_no_relocation=True),
        Case("joint_correlated_bb", "correlated_bb", requires_fed_no_relocation=True),
        Case("joint_independent_ab", "independent_ab"),
        Case("joint_independent_ba", "independent_ba"),
        Case("joint_independent_private_ab_negative", "independent_private_ab",
             expected_success=False, expected_failure="infeasible_private_tuple"),
        Case("joint_loop_toggle", "loop_toggle"),
        Case("joint_function_calls", "function_calls"),
        Case("joint_dynamic_reverse", "dynamic_reverse"),
        Case("joint_function_private_mix_negative", "function_private_mix_negative",
             expected_success=False, expected_failure="infeasible_private_tuple"),
        Case("joint_branch_upload", "branch_upload", requires_action_evidence=True,
             requires_branch_upload=True),
        Case("l2svm_protected_y_negative", "l2svm", (0, 1, 0, 1, 0, 1, 0, 1),
             y_privacy="private", expected_success=False),
        Case("ml_logreg", "ml_logreg", training=True, default_selected=False),
        Case("ml_l2svm", "ml_l2svm", training=True, default_selected=False),
        Case("ml_lm", "ml_lm", training=True, default_selected=False),
        Case("ml_steplm", "ml_steplm", training=True, default_selected=False),
        Case("ml_steplm_local_matrix", "ml_steplm_local_matrix",
             training=True, default_selected=False),
        Case("ml_logreg_gd", "ml_logreg_gd", training=True,
             requires_loss_progress=True, default_selected=False),
        Case("ml_l2svm_gd", "ml_l2svm_gd", training=True,
             requires_loss_progress=True, default_selected=False),
        Case("ml_lm_gd", "ml_lm_gd", training=True,
             requires_loss_progress=True, default_selected=False),
    )


def default_cases() -> tuple[Case, ...]:
    return tuple(case for case in cases() if case.default_selected)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", type=Path, default=DEFAULT_CLASSES,
                        help="compiled merged main classes to freeze")
    parser.add_argument("--test-classes", type=Path, default=DEFAULT_TEST_CLASSES,
                        help="compiled merged test classes to freeze")
    parser.add_argument("--dependencies", type=Path, default=DEFAULT_DEPENDENCIES,
                        help="dependency directory mounted read-only")
    parser.add_argument("--main-sources", type=Path, default=DEFAULT_MAIN_SOURCES,
                        help="merged main source tree to freeze for provenance")
    parser.add_argument("--test-sources", type=Path, default=DEFAULT_TEST_SOURCES,
                        help="merged test source tree to freeze for provenance")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--stage-root", type=Path, default=DEFAULT_STAGE_ROOT,
                        help="Docker-visible parent for unique frozen runtime stages")
    parser.add_argument("--run-id", help="unique evidence directory name")
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--case-timeout-seconds", type=int, default=300,
                        help="timeout for each CP/FED process inside the container")
    parser.add_argument("--case", action="append", dest="selected_cases",
                        help="case name to run (repeatable; default: all cases)")
    parser.add_argument("--model-proof-class", default=DEFAULT_MODEL_PROOF_CLASS,
                        help="JUnit model proof required before runtime cases")
    parser.add_argument("--dry-run", action="store_true",
                        help="write and validate fixtures/receipts without Docker")
    parser.add_argument("--debug-fedreq", action="store_true",
                        help="emit coordinator/worker federated request lifecycle diagnostics")
    parser.add_argument("--profile-jfr", action="store_true",
                        help="record JFR diagnostics for each FED coordinator process")
    return parser.parse_args(argv)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_inventory(root: Path) -> dict[str, str]:
    return {str(path.relative_to(root)): sha256(path)
            for path in sorted(root.rglob("*")) if path.is_file()}


def inventory_digest(inventory: dict[str, str]) -> str:
    payload = json.dumps(inventory, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


def class_preflight_expectations(classes: Path, test_classes: Path,
                                 model_proof_class: str) -> dict[str, dict[str, object]]:
    proof_relative = str(Path(*model_proof_class.split(".")).with_suffix(".class"))
    entries = [("main", classes, Path(relative), Path("/engine/classes") / relative)
               for relative in CLASS_PREFLIGHT_MAIN]
    entries.append(("test", test_classes, Path(proof_relative),
                    Path("/engine/test-classes") / proof_relative))
    expected: dict[str, dict[str, object]] = {}
    missing: list[str] = []
    for kind, host_root, relative, container_path in entries:
        host_path = host_root / relative
        if not host_path.is_file():
            missing.append(str(host_path))
            continue
        expected[str(container_path)] = {
            "kind": kind,
            "relativePath": str(relative),
            "hostPath": str(host_path),
            "sha256": sha256(host_path),
            "size": host_path.stat().st_size,
        }
    if missing:
        raise ValueError("missing mandatory merged-build preflight classes: " + ", ".join(missing))
    return expected


def freeze_tree(source: Path, destination: Path) -> dict[str, str]:
    """Copy a complete input tree and return the inventory of the frozen copy."""
    source_inventory = tree_inventory(source)
    shutil.copytree(source, destination)
    frozen_inventory = tree_inventory(destination)
    if frozen_inventory != source_inventory:
        raise ValueError(f"input tree changed while it was being frozen: {source}")
    return frozen_inventory


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def assert_grid_root(path: Path) -> Path:
    resolved = path.resolve()
    try:
        resolved.relative_to(Path("/grid/3"))
    except ValueError as exc:
        raise ValueError(f"evidence root must be below /grid/3: {resolved}") from exc
    return resolved


def allocate_run(output_root: Path, run_id: str | None) -> Path:
    output_root.mkdir(parents=True, exist_ok=True)
    name = run_id or time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:12]
    if not SAFE_RUN_ID.fullmatch(name):
        raise ValueError(f"unsafe --run-id: {name!r}")
    run = output_root / name
    run.mkdir(exist_ok=False)
    return run


def allocate_stage(stage_root: Path, run: Path) -> Path:
    stage_root.mkdir(parents=True, exist_ok=True)
    stage = stage_root / run.name
    stage.mkdir(exist_ok=False)
    return stage


def matrix_metadata(rows: int, cols: int, privacy: str) -> dict:
    value = {"data_type": "matrix", "value_type": "double", "rows": rows,
             "cols": cols, "nnz": rows * cols, "format": "csv",
             "header": False, "sep": ","}
    if privacy != "public":
        value["privacy"] = privacy
    return value


def fingerprint(variable: str) -> str:
    return (f'print("JOINT_E2E_SUM=" + sum({variable}));\n'
            f'print("JOINT_E2E_NORM2=" + sum({variable} * {variable}));\n'
            f'print("JOINT_E2E_ROWS=" + nrow({variable}));\n'
            f'print("JOINT_E2E_COLS=" + ncol({variable}));\n')


def local_read(name: str) -> str:
    return f'{name}=read("/evidence/data/{name}.csv", format="csv");\n'


def federated_read(name: str, rows: int, cols: int) -> str:
    return (f'{name}=federated(addresses=list("localhost:{WORKER_PORT}//evidence/data/{name}.csv"),'
             f'ranges=list(list(0,0),list({rows},{cols})));\n')


def training_x_read(federated: bool) -> str:
    if not federated:
        return local_read("X_ML_PUBLIC") + "X=X_ML_PUBLIC;\n"
    return (
        f'X=federated(addresses=list("localhost:{WORKER_PORT}//evidence/data/X_ML_0.csv",'
        f'"localhost:{POOL_A_PORT}//evidence/data/X_ML_1.csv",'
        f'"localhost:{POOL_B_PORT}//evidence/data/X_ML_2.csv"),'
        'ranges=list(list(0,0),list(64,8),list(64,0),list(128,8),'
        'list(128,0),list(192,8)));\n')


def steplm_read(federated: bool, variable: str, columns: int) -> str:
    source = f"{variable}_STEPLM_PUBLIC"
    if not federated:
        return local_read(source) + f"{variable}={source};\n"
    return (
        f'{variable}=federated(addresses=list("localhost:{WORKER_PORT}//evidence/data/'
        f'{source}.csv"),ranges=list(list(0,0),list(20,{columns})));\n')


def steplm_dataset() -> tuple[tuple[tuple[float, ...], ...], tuple[float, ...]]:
    x = tuple((
        (row - 9.5) / 10.0,
        ((row * row) % 17 - 8) / 7.0,
        ((row * 5 + 3) % 19 - 9) / 6.0,
        (row % 4) - 1.5,
        ((row * 7 + row // 3) % 23 - 11) / 8.0,
    ) for row in range(20))
    y = tuple(1.75 * row[0] - 2.0 * row[2] + 0.65 * row[4]
              + ((index % 3) - 1) / 100.0
              for index, row in enumerate(x))
    return x, y


def dml_matrix_literal(values: tuple[tuple[float, ...], ...]) -> str:
    flattened = " ".join(f"{value:.17g}" for row in values for value in row)
    return (f'matrix("{flattened}",rows={len(values)},cols={len(values[0])},'
            "byrow=TRUE)")


def steplm_local_matrix_read(federated: bool, variable: str) -> str:
    x, y = steplm_dataset()
    values = x if variable == "X" else tuple((value,) for value in y)
    columns = len(values[0])
    local = f"{variable}_LOCAL={dml_matrix_literal(values)};\n"
    if not federated:
        return local + f"{variable}={variable}_LOCAL;\n"
    return (local + f'{variable}=federated(local_matrix={variable}_LOCAL,'
            f'addresses=list("localhost:{WORKER_PORT}"),'
            f'ranges=list(list(0,0),list(20,{columns})));\n')


def is_steplm(case: Case) -> bool:
    return case.kind in {"ml_steplm", "ml_steplm_local_matrix"}


def model_output() -> str:
    return (fingerprint("m")
            + 'write(m,$MODEL_OUTPUT,format="csv");\n')


def pool_read(name: str, port: int, federated: bool, public_federated: bool = False) -> str:
    if federated:
        source = name + "_PUBLIC" if public_federated else name
        return (f'{name}=federated(addresses=list("localhost:{port}//evidence/data/{source}.csv"),'
                'ranges=list(list(0,0),list(8,3)));\n')
    return local_read(name + "_PUBLIC") + f"{name}={name}_PUBLIC;\n"


def pool_prefix(federated: bool, public_second_inputs: bool = False) -> str:
    return "".join((
        pool_read("A1", POOL_A_PORT, federated),
        pool_read("A2", POOL_A_PORT, federated, public_second_inputs),
        pool_read("B1", POOL_B_PORT, federated),
        pool_read("B2", POOL_B_PORT, federated, public_second_inputs),
    ))


def program(case: Case, federated: bool) -> str:
    if case.training:
        prefix = (steplm_local_matrix_read(federated, "X")
                  if case.kind == "ml_steplm_local_matrix"
                  else steplm_read(federated, "X", 5) if case.kind == "ml_steplm"
                  else training_x_read(federated))
        if case.kind == "ml_logreg":
            body = (local_read("Y_ML_LOGREG") + "Y=Y_ML_LOGREG;\n"
                    "m=multiLogReg(X=X,Y=Y,icpt=0,tol=1e-7,reg=1e-4,maxi=10,maxii=5,"
                    "verbose=FALSE,numclasses=3,numrows=192,numcols=8);\n")
        elif case.kind == "ml_l2svm":
            body = (local_read("Y_ML_SVM") + "Y=Y_ML_SVM;\n"
                    "m=l2svm(X=X,Y=Y,intercept=FALSE,epsilon=1e-8,reg=1e-3,"
                    "maxIterations=10,maxii=5,verbose=FALSE);\n")
        elif case.kind == "ml_lm":
            body = (local_read("Y_ML_LM") + "Y=Y_ML_LM;\n"
                    "m=lmCG(X=X,y=Y,icpt=0,reg=1e-4,tol=1e-9,maxi=10,verbose=FALSE);\n")
        elif is_steplm(case):
            y_read = (steplm_local_matrix_read(federated, "Y")
                      if case.kind == "ml_steplm_local_matrix"
                      else steplm_read(federated, "Y", 1))
            body = (y_read +
                    "[m,s]=steplm(X=X,y=Y,icpt=0,reg=1e-7,tol=1e-7,maxi=20,"
                    "verbose=FALSE);\n"
                    'write(s,$SELECTION_OUTPUT,format="csv");\n')
        elif case.kind == "ml_logreg_gd":
            body = (local_read("Y_ML_SVM") + "Y=(Y_ML_SVM+1)/2;\n"
                    "m=matrix(0,rows=8,cols=1);p=1/(1+exp(-(X%*%m)));\n"
                    "loss0=-sum(Y*log(p)+(1-Y)*log(1-p))/192+5e-5*sum(m*m);\n"
                    "i=1;while(i<=20){p=1/(1+exp(-(X%*%m)));"
                    "g=t(X)%*%(p-Y)/192+1e-4*m;m=m-0.05*g;i=i+1;}\n"
                    "p=1/(1+exp(-(X%*%m)));"
                    "loss1=-sum(Y*log(p)+(1-Y)*log(1-p))/192+5e-5*sum(m*m);\n"
                    'print("JOINT_E2E_LOSS_INITIAL="+loss0);'
                    'print("JOINT_E2E_LOSS_FINAL="+loss1);\n')
        elif case.kind == "ml_l2svm_gd":
            body = (local_read("Y_ML_SVM") + "Y=Y_ML_SVM;\n"
                    "m=matrix(0,rows=8,cols=1);margin=1-Y*(X%*%m);"
                    "active=margin>0;loss0=sum((margin*active)^2)/384+5e-5*sum(m*m);\n"
                    "i=1;while(i<=20){margin=1-Y*(X%*%m);active=margin>0;"
                    "g=-(t(X)%*%(Y*margin*active))/192+1e-4*m;"
                    "m=m-0.05*g;i=i+1;}\n"
                    "margin=1-Y*(X%*%m);active=margin>0;"
                    "loss1=sum((margin*active)^2)/384+5e-5*sum(m*m);\n"
                    'print("JOINT_E2E_LOSS_INITIAL="+loss0);'
                    'print("JOINT_E2E_LOSS_FINAL="+loss1);\n')
        elif case.kind == "ml_lm_gd":
            body = (local_read("Y_ML_LM") + "Y=Y_ML_LM;\n"
                    "m=matrix(0,rows=8,cols=1);res=X%*%m-Y;"
                    "loss0=sum(res*res)/384+5e-5*sum(m*m);\n"
                    "i=1;while(i<=20){res=X%*%m-Y;"
                    "g=t(X)%*%res/192+1e-4*m;m=m-0.05*g;i=i+1;}\n"
                    "res=X%*%m-Y;loss1=sum(res*res)/384+5e-5*sum(m*m);\n"
                    'print("JOINT_E2E_LOSS_INITIAL="+loss0);'
                    'print("JOINT_E2E_LOSS_FINAL="+loss1);\n')
        else:
            raise ValueError(f"unknown training case kind: {case.kind}")
        return prefix + body + model_output()
    prefix = federated_read("X", 8, 3) if federated else local_read("X_PUBLIC") + "X=X_PUBLIC;\n"
    if case.kind == "dynamic_reverse":
        if federated:
            prefix = (f'X=federated(addresses=list("localhost:{POOL_A_PORT}//evidence/data/X_TOP.csv",'
                      f'"localhost:{POOL_B_PORT}//evidence/data/X_BOTTOM.csv"),'
                      'ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));\n')
        return prefix + (
            "flag=sum(X)>0;\nif(flag){T=rev(X);}else{T=rev(X);}\nZ=exp(T);\n"
            + fingerprint("Z")
            + 'print("JOINT_E2E_WEIGHTED="+sum(rowSums(Z)*seq(1,nrow(Z))));\n')
    if case.kind == "l2svm":
        y_name = "Y_PROTECTED" if not case.expected_success else f"Y_{case.name}"
        prefix += federated_read(y_name, 8, 1) if federated else local_read(y_name + "_PUBLIC")
        prefix += f"Y={y_name};\n" if federated else f"Y={y_name}_PUBLIC;\n"
        return prefix + (
            "m=l2svm(X=X,Y=Y,verbose=FALSE,epsilon=1e-12,maxIterations=3,maxii=3);\n"
            + fingerprint("m"))
    if case.kind == "branch_upload":
        return prefix + (
            "flag=sum(X)>0;\n"
            "if(flag){Y=matrix(0,rows=nrow(X),cols=ncol(X));}else{Y=X;}\n"
            "Z=Y+X;\n" + fingerprint("Z"))
    mixed_public = case.kind in ("independent_ab", "independent_ba")
    prefix = pool_prefix(federated, mixed_public)
    if case.kind == "correlated_aa":
        body = "flag=sum(A1)>0;\nif(flag){U=A1;V=A2;}else{U=B1;V=B2;}\nZ=U+V;\n"
    elif case.kind == "correlated_bb":
        body = "flag=sum(A1)<0;\nif(flag){U=A1;V=A2;}else{U=B1;V=B2;}\nZ=U+V;\n"
    elif case.kind == "independent_ab":
        body = ("flagU=sum(A1)>0;flagV=sum(A1)<0;\n"
                "if(flagU){U=A1;}else{U=B1;}\n"
                "if(flagV){V=A2;}else{V=B2;}\nZ=U+V;\n")
    elif case.kind == "independent_ba":
        body = ("flagU=sum(A1)<0;flagV=sum(A1)>0;\n"
                "if(flagU){U=A1;}else{U=B1;}\n"
                "if(flagV){V=A2;}else{V=B2;}\nZ=U+V;\n")
    elif case.kind == "independent_private_ab":
        body = ("flagU=sum(A1)>0;flagV=sum(A1)<0;\n"
                "if(flagU){U=A1;}else{U=B1;}\n"
                "if(flagV){V=A2;}else{V=B2;}\nZ=U+V;\n")
    elif case.kind == "loop_toggle":
        body = ("U=A1;V=A2;i=1;\nwhile(i<=2){odd=i-floor(i/2)*2;"
                "if(odd==1){U=B1;V=B2;}else{U=A1;V=A2;}i=i+1;}\nZ=U+V;\n")
    elif case.kind in ("function_calls", "function_private_mix_negative"):
        final = ("SC=sum(C);SD=sum(D);"
                 "Z=rbind(matrix(SC,rows=1,cols=1),matrix(SD,rows=1,cols=1));\n"
                 'print("JOINT_E2E_CALL_C="+SC);print("JOINT_E2E_CALL_D="+SD);\n'
                 if case.kind == "function_calls"
                 else "Z=C+D;\n")
        return (
            "poolJoin=function(matrix[double] PA1,matrix[double] PA2,matrix[double] PB1,"
            "matrix[double] PB2,boolean chooseA)return(matrix[double] R){"
            "if(chooseA){FU=PA1;FV=PA2;}else{FU=PB1;FV=PB2;}R=FU+FV;}\n"
            + prefix
            + "C=poolJoin(A1,A2,B1,B2,TRUE);D=poolJoin(A1,A2,B1,B2,FALSE);"
            + final
            + fingerprint("Z"))
    else:
        raise ValueError(f"unknown case kind: {case.kind}")
    return prefix + body + fingerprint("Z")


def write_inputs(run: Path, selected: tuple[Case, ...] | None = None) -> dict[str, str]:
    data = run / "data"
    data.mkdir()
    x = ((1, 0, 2), (0, 1, 3), (2, 1, 0), (1, 2, 1),
         (3, 0, 1), (0, 2, 2), (2, 2, 1), (1, 1, 1))
    texts: dict[str, tuple[str, int, int, str]] = {
        "X": ("\n".join(",".join(map(str, row)) for row in x) + "\n", 8, 3, "private-aggregate"),
        "X_PUBLIC": ("\n".join(",".join(map(str, row)) for row in x) + "\n", 8, 3, "public"),
    }
    selected_cases = selected if selected is not None else default_cases()
    if any(case.training for case in selected_cases):
        ml_x = tuple(tuple(
            ((row + 3) * (col + 5) % 29 - 14) / 7.0
            + ((row % 5) - 2) * (col + 1) / 37.0
            for col in range(8)) for row in range(192))
        ml_payload = "\n".join(",".join(f"{value:.17g}" for value in row) for row in ml_x) + "\n"
        texts["X_ML_PUBLIC"] = (ml_payload, 192, 8, "public")
        for shard in range(3):
            values = ml_x[shard * 64:(shard + 1) * 64]
            payload = "\n".join(",".join(f"{value:.17g}" for value in row) for row in values) + "\n"
            texts[f"X_ML_{shard}"] = (payload, 64, 8, "private-aggregate")
        logreg = tuple(1 + ((row * 7 + row // 11) % 3) for row in range(192))
        svm = tuple(-1 if (row * 5 + row // 7) % 2 == 0 else 1 for row in range(192))
        lm = tuple(sum(ml_x[row][col] * (col + 1) / 9.0 for col in range(8))
                   + ((row % 7) - 3) / 50.0 for row in range(192))
        for name, values in (("Y_ML_LOGREG", logreg), ("Y_ML_SVM", svm), ("Y_ML_LM", lm)):
            texts[name] = ("\n".join(f"{value:.17g}" for value in values) + "\n",
                           192, 1, "public")
    if any(case.kind == "ml_steplm" for case in selected_cases):
        steplm_x, steplm_y = steplm_dataset()
        x_payload = "\n".join(",".join(f"{value:.17g}" for value in row)
                              for row in steplm_x) + "\n"
        y_payload = "\n".join(f"{value:.17g}" for value in steplm_y) + "\n"
        texts["X_STEPLM_PUBLIC"] = (x_payload, 20, 5, "public")
        texts["Y_STEPLM_PUBLIC"] = (y_payload, 20, 1, "public")
    if any(case.kind == "dynamic_reverse" for case in selected_cases):
        for name, values in (("X_TOP", x[:4]), ("X_BOTTOM", x[4:])):
            payload = "\n".join(",".join(map(str, row)) for row in values) + "\n"
            texts[name] = (payload, 4, 3, "private-aggregate")
    pool_values = {
        "A1": x,
        "A2": tuple((1, 1, 1) for _ in range(8)),
        "B1": tuple(tuple(2 * value for value in row) for row in x),
        "B2": tuple((3, 3, 3) for _ in range(8)),
    }
    for name, values in pool_values.items():
        payload = "\n".join(",".join(map(str, row)) for row in values) + "\n"
        texts[name] = (payload, 8, 3, "private-aggregate")
        texts[name + "_PUBLIC"] = (payload, 8, 3, "public")
    for case in selected_cases:
        if case.kind != "l2svm":
            continue
        name = "Y_PROTECTED" if not case.expected_success else f"Y_{case.name}"
        payload = "\n".join(str(value) for value in case.y) + "\n"
        texts[name] = (payload, 8, 1, case.y_privacy)
        texts[name + "_PUBLIC"] = (payload, 8, 1, "public")
    hashes: dict[str, str] = {}
    for name, (payload, rows, cols, privacy) in texts.items():
        csv = data / f"{name}.csv"
        csv.write_text(payload, encoding="utf-8")
        write_json(data / f"{name}.csv.mtd", matrix_metadata(rows, cols, privacy))
        hashes[str(csv.relative_to(run))] = sha256(csv)
        hashes[str((data / f"{name}.csv.mtd").relative_to(run))] = sha256(data / f"{name}.csv.mtd")
    return hashes


def write_fixtures(run: Path, selected: tuple[Case, ...] | None = None) -> dict[str, dict[str, str]]:
    config = run / "config.xml"
    config.write_text(
        "<root><sysds.native.blas>none</sysds.native.blas>"
        "<sysds.local.spark>true</sysds.local.spark>"
        "<sysds.federated.planner>compile_cost_based</sysds.federated.planner>"
        "<sysds.codegen.enabled>false</sysds.codegen.enabled>"
        "<sysds.localtmpdir>/evidence/tmp/local</sysds.localtmpdir>"
        "<sysds.scratch>/evidence/tmp/scratch</sysds.scratch></root>\n", encoding="utf-8")
    fixture_hashes: dict[str, dict[str, str]] = {}
    for case in selected if selected is not None else default_cases():
        case_dir = run / "cases" / case.name
        case_dir.mkdir(parents=True)
        cp = case_dir / "cp.dml"
        fed = case_dir / "fed.dml"
        cp.write_text(program(case, False), encoding="utf-8")
        fed.write_text(program(case, True), encoding="utf-8")
        fixture_hashes[case.name] = {"cp": sha256(cp), "fed": sha256(fed)}
    fixture_hashes["config"] = {"sha256": sha256(config)}
    return fixture_hashes


def java_command(case: Case, mode: str, case_timeout_seconds: int = 300,
                 profile_jfr: bool = False) -> str:
    audit = f"/evidence/audit/{case.name}-{mode}"
    properties = " ".join((
        "-Dsysds.fedplanner.runtime.audit=true",
        "-Dsysds.fedplanner.phaseMarkers=true",
        "-Dsysds.fedplanner.space.audit=true",
        f"-Dsysds.fedplanner.space.audit.dir={audit}",
        f"-Dsysds.fedplanner.space.audit.context={case.name}-{mode}",
        f"-Dsysds.fedplanner.space.audit.invocation={case.name}-{mode}",
        "-Dsysds.fedplanner.capability.audit=true",
        f"-Dsysds.fedplanner.capability.audit.dir={audit}",
    ))
    if case.training:
        properties += " -Dsysds.fedplanner.trace=true -Dsysds.fedplanner.trace.details=false"
    jfr = ('-XX:FlightRecorderOptions=stackdepth=256 '
           f'-XX:StartFlightRecording=filename=/evidence/cases/{case.name}/fed.jfr,'
           f'settings=profile,disk=true,dumponexit=true,duration={case_timeout_seconds - 1}s '
           if profile_jfr and mode == "fed" else "")
    output_argument = (f' -nvargs MODEL_OUTPUT=/evidence/cases/{case.name}/{mode}-model.csv'
                       if case.training else "")
    if is_steplm(case):
        output_argument += (f' SELECTION_OUTPUT=/evidence/cases/{case.name}/'
                            f'{mode}-selection.csv')
    return (f'timeout {case_timeout_seconds} java --add-modules jdk.incubator.vector -Xmx3g '
            f'-XX:ActiveProcessorCount=4 {jfr}{properties} -cp "$CP" '
            f'org.apache.sysds.api.DMLScript -f /evidence/cases/{case.name}/{mode}.dml '
            '-config /evidence/config.xml -exec singlenode -seed 7 '
            '-noFedRuntimeConversion -stats 100 -explain runtime'
            f'{output_argument}')


def write_container_script(run: Path, model_proof_class: str = DEFAULT_MODEL_PROOF_CLASS,
                           case_timeout_seconds: int = 300,
                           selected: tuple[Case, ...] | None = None,
                           debug_fedreq: bool = False,
                           profile_jfr: bool = False,
                           class_preflight: dict[str, dict[str, object]] | None = None) -> Path:
    write_json(run / "class-preflight-expected.json", class_preflight or {})
    lines = [
        "#!/usr/bin/env bash", "set -euo pipefail", "cd /evidence",
        "export HOME=/tmp/joint-boundary-home", "mkdir -p \"$HOME\" /evidence/tmp /evidence/audit",
        "CP='/engine/classes:/engine/test-classes:/deps/*'", "export CP",
        "python3 - <<'PY'",
        "import hashlib,json,pathlib",
        "expected=json.loads(pathlib.Path('/evidence/class-preflight-expected.json').read_text())",
        "actual={}",
        "for name,spec in expected.items():\n p=pathlib.Path(name)\n digest=hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None\n actual[name]={'exists':p.is_file(),'expectedSha256':spec['sha256'],'actualSha256':digest,'size':p.stat().st_size if p.is_file() else None,'matched':digest==spec['sha256']}\n",
        "passed=bool(expected) and all(item['matched'] for item in actual.values())",
        "report={'schema':'systemds-joint-boundary-class-preflight-v1','status':'PASSED' if passed else 'FAILED','expectedCount':len(expected),'actual':actual}",
        "pathlib.Path('/evidence/class-preflight-actual.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\\n')",
        "if not passed: raise SystemExit('mandatory merged-build preflight failed')",
        "PY",
        f'java --add-modules jdk.incubator.vector -Xmx1g -cp "$CP" org.apache.sysds.api.DMLScript '
        f'-w {WORKER_PORT} -config /evidence/config.xml > /evidence/worker-{WORKER_PORT}.log 2>&1 &',
        "worker0=$!",
        f'java --add-modules jdk.incubator.vector -Xmx1g -cp "$CP" org.apache.sysds.api.DMLScript '
        f'-w {POOL_A_PORT} -config /evidence/config.xml > /evidence/worker-{POOL_A_PORT}.log 2>&1 &',
        "worker1=$!",
        f'java --add-modules jdk.incubator.vector -Xmx1g -cp "$CP" org.apache.sysds.api.DMLScript '
        f'-w {POOL_B_PORT} -config /evidence/config.xml > /evidence/worker-{POOL_B_PORT}.log 2>&1 &',
        "worker2=$!",
        "trap 'for worker in \"$worker0\" \"$worker1\" \"$worker2\"; do kill \"$worker\" 2>/dev/null || true; wait \"$worker\" 2>/dev/null || true; done' EXIT",
        "python3 - <<'PY'",
        "import socket,time",
        f"for port in ({WORKER_PORT},{POOL_A_PORT},{POOL_B_PORT}):\n for _ in range(120):\n  try:\n   s=socket.create_connection(('localhost',port),.5);s.close();break\n  except OSError: time.sleep(.5)\n else: raise SystemExit(f'worker {{port}} did not start')",
        "PY", "set +e",
        f'timeout 300 java --add-modules jdk.incubator.vector -Xmx3g -cp "$CP" '
        f'org.junit.runner.JUnitCore {model_proof_class} > /evidence/model-proof.log 2>&1',
        "printf '%s\\n' $? > /evidence/model-proof.rc",
    ]
    if debug_fedreq:
        lines.insert(5, "export JAVA_TOOL_OPTIONS=-Dsysds.debug.fedreq=true")
    for case in selected if selected is not None else default_cases():
        modes = ("fed",) if not case.expected_success else ("cp", "fed")
        for mode in modes:
            command = java_command(case, mode, case_timeout_seconds, profile_jfr)
            lines.extend((
                f"mkdir -p /evidence/audit/{case.name}-{mode}",
                f"{command} > /evidence/cases/{case.name}/{mode}.log 2>&1",
                f"printf '%s\\n' $? > /evidence/cases/{case.name}/{mode}.rc",
            ))
    lines.append("exit 0")
    script = run / "container-run.sh"
    script.write_text("\n".join(lines) + "\n", encoding="utf-8")
    script.chmod(0o755)
    return script


def docker_command(run: Path, classes: Path, test_classes: Path,
                   dependencies: Path) -> tuple[str, list[str]]:
    container = "systemds-joint-boundary-" + uuid.uuid4().hex[:16]
    command = [
        "docker", "run", "--rm", "--pull", "never", "--network", "none",
        "--name", container, "--cpus", "4", "--memory", "8g",
        "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{run}:/evidence:rw",
        "--volume", f"{classes}:/engine/classes:ro",
        "--volume", f"{test_classes}:/engine/test-classes:ro",
        "--volume", f"{dependencies}:/deps:ro",
        "--entrypoint", "bash", PINNED_IMAGE, "/evidence/container-run.sh",
    ]
    return container, command


def jfr_profile_manifest(selected: tuple[Case, ...], enabled: bool,
                         case_timeout_seconds: int,
                         evidence_root: Path | None = None) -> dict[str, object]:
    files: dict[str, dict[str, object]] = {}
    if enabled:
        for case in selected:
            relative = Path("cases") / case.name / "fed.jfr"
            path = evidence_root / relative if evidence_root is not None else None
            entry: dict[str, object] = {"path": str(relative)}
            if path is not None:
                entry.update(jfr_file_evidence(path))
            files[case.name] = entry
    return {"enabled": enabled, "recordProfile": enabled,
            "scope": "fed-coordinator-only", "stackDepth": 256,
            "settings": "profile", "durationSeconds": case_timeout_seconds - 1,
            "files": files}


def jfr_file_evidence(path: Path) -> dict[str, object]:
    exists = path.is_file()
    size = path.stat().st_size if exists else 0
    evidence: dict[str, object] = {
        "exists": exists,
        "size": size,
        "sha256": sha256(path) if exists else None,
        "parserPassed": False,
        "parserReturncode": None,
        "parserError": None,
    }
    if not exists:
        evidence["parserError"] = "JFR recording is missing"
        return evidence
    if size == 0:
        evidence["parserError"] = "JFR recording is empty"
        return evidence
    try:
        parsed = subprocess.run(
            ["jfr", "summary", str(path)], capture_output=True, text=True,
            timeout=JFR_SUMMARY_TIMEOUT_SECONDS, check=False)
        evidence["parserReturncode"] = parsed.returncode
        evidence["parserPassed"] = parsed.returncode == 0
        if parsed.returncode != 0:
            diagnostic = (parsed.stderr or parsed.stdout or "jfr summary failed").strip()
            evidence["parserError"] = diagnostic[:2000]
    except FileNotFoundError as exc:
        evidence["parserError"] = f"JFR parser is unavailable: {exc}"
    except subprocess.TimeoutExpired:
        evidence["parserError"] = (
            f"jfr summary exceeded {JFR_SUMMARY_TIMEOUT_SECONDS} seconds")
    except OSError as exc:
        evidence["parserError"] = f"JFR parser failed: {exc}"
    return evidence


def markers(path: Path) -> dict[str, float]:
    found: dict[str, float] = {}
    if not path.is_file():
        return found
    for key, raw in MARKER.findall(path.read_text(encoding="utf-8", errors="replace")):
        found[key] = float(raw)
    return found


def close_markers(reference: dict[str, float], actual: dict[str, float]) -> bool:
    required = {"SUM", "NORM2", "ROWS", "COLS"}
    if not required.issubset(reference) or set(actual) != set(reference):
        return False
    return all(math.isfinite(value) for value in (*reference.values(), *actual.values())) and all(
        math.isclose(reference[key], actual[key], rel_tol=1e-8, abs_tol=1e-9) for key in reference)


def read_matrix(path: Path) -> tuple[list[list[float]], list[str]]:
    rows: list[list[float]] = []
    errors: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        return rows, [str(exc)]
    for line_number, line in enumerate(lines, 1):
        row: list[float] = []
        for token in line.split(","):
            try:
                value = float(token.strip())
                if not math.isfinite(value):
                    raise ValueError("non-finite value")
                row.append(value)
            except ValueError as exc:
                errors.append(f"{path}:{line_number}: {token!r}: {exc}")
        rows.append(row)
    if not rows or not any(rows):
        errors.append(f"{path}: empty model")
    widths = {len(row) for row in rows}
    if len(widths) > 1:
        errors.append(f"{path}: ragged matrix rows: {sorted(widths)}")
    return rows, errors


def read_model(path: Path) -> tuple[list[float], list[str]]:
    rows, errors = read_matrix(path)
    return [value for row in rows for value in row], errors


def compare_models(reference_path: Path, actual_path: Path,
                   expected_shape: tuple[int, int] | None = None) -> dict[str, object]:
    reference_rows, reference_errors = read_matrix(reference_path)
    actual_rows, actual_errors = read_matrix(actual_path)
    reference = [value for row in reference_rows for value in row]
    actual = [value for row in actual_rows for value in row]
    reference_shape = (len(reference_rows), len(reference_rows[0]) if reference_rows else 0)
    actual_shape = (len(actual_rows), len(actual_rows[0]) if actual_rows else 0)
    shape_matched = (reference_shape == actual_shape
                     and (expected_shape is None or reference_shape == expected_shape))
    same_size = len(reference) == len(actual) and bool(reference)
    differences = [abs(left - right) for left, right in zip(reference, actual)]
    matched = (same_size and shape_matched and not reference_errors and not actual_errors
               and all(math.isclose(left, right, rel_tol=1e-7, abs_tol=1e-7)
                       for left, right in zip(reference, actual)))
    return {
        "matched": matched,
        "entries": len(reference),
        "actualEntries": len(actual),
        "referenceShape": list(reference_shape),
        "actualShape": list(actual_shape),
        "expectedShape": list(expected_shape) if expected_shape is not None else None,
        "shapeMatched": shape_matched,
        "finite": not reference_errors and not actual_errors,
        "nonzero": any(abs(value) > 1e-12 for value in reference),
        "maxAbsDifference": max(differences, default=None),
        "errors": reference_errors + actual_errors,
    }


def compare_selections(reference_path: Path, actual_path: Path,
                       feature_count: int) -> dict[str, object]:
    reference, reference_errors = read_matrix(reference_path)
    actual, actual_errors = read_matrix(actual_path)
    reference_shape = (len(reference), len(reference[0]) if reference else 0)
    actual_shape = (len(actual), len(actual[0]) if actual else 0)
    reference_values = reference[0] if reference_shape[0] == 1 else []
    actual_values = actual[0] if actual_shape[0] == 1 else []
    integral = (all(value.is_integer() for value in reference_values)
                and all(value.is_integer() for value in actual_values))
    reference_ints = [int(value) for value in reference_values] if integral else []
    actual_ints = [int(value) for value in actual_values] if integral else []
    valid_reference = (reference_ints == [0] or
                       (bool(reference_ints) and len(set(reference_ints)) == len(reference_ints)
                        and all(1 <= value <= feature_count for value in reference_ints)))
    shape_matched = (reference_shape == actual_shape and reference_shape[0] == 1
                     and reference_shape[1] >= 1)
    matched = (not reference_errors and not actual_errors and integral and valid_reference
               and shape_matched and reference_ints == actual_ints)
    return {
        "matched": matched,
        "referenceShape": list(reference_shape),
        "actualShape": list(actual_shape),
        "shapeMatched": shape_matched,
        "integral": integral,
        "validReference": valid_reference,
        "reference": reference_ints,
        "actual": actual_ints,
        "errors": reference_errors + actual_errors,
    }


def planner_checkpoints(text: str) -> list[dict[str, object]]:
    checkpoints: list[dict[str, object]] = []
    integer_fields = {
        "merges", "clusters", "elapsedNanos", "dpNanos", "scoringNanos",
        "validationNanos", "assignments", "retainedSlots", "improvements",
        "resourceRejected", "internalDecisions", "conditionalAttempts",
        "conditionalImprovements", "plannerElapsedNanos",
    }
    float_fields = {"lower", "upper", "relativeGap"}
    for match in CHECKPOINT.finditer(text):
        raw = dict(CHECKPOINT_FIELD.findall(match.group("body")))
        if "phase" not in raw:
            continue
        parsed: dict[str, object] = {"phase": raw["phase"]}
        for key, value in raw.items():
            if key in integer_fields:
                try:
                    parsed[key] = int(value)
                except ValueError:
                    parsed[key] = value
            elif key in float_fields:
                try:
                    parsed[key] = float(value)
                except ValueError:
                    parsed[key] = value
        checkpoints.append(parsed)
    return checkpoints


def runtime_statistics(text: str) -> dict[str, float]:
    names = {"Total compilation time": "compilationSeconds",
             "Total execution time": "executionSeconds"}
    return {names[name]: float(value) for name, value in STATISTIC.findall(text)}


def read_rc(path: Path) -> int | None:
    try:
        return int(path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def read_audits(directory: Path) -> tuple[list[dict], list[str]]:
    rows: list[dict] = []
    errors: list[str] = []
    if not directory.is_dir():
        return rows, errors
    for path in sorted(directory.rglob("*.jsonl")):
        for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if not line.strip():
                continue
            try:
                value = json.loads(line)
                if not isinstance(value, dict):
                    raise ValueError("row is not an object")
                value["_file"] = str(path.relative_to(directory))
                rows.append(value)
            except (json.JSONDecodeError, ValueError) as exc:
                errors.append(f"{path}:{line_number}: {exc}")
    return rows, errors


def action_diagnostics(log: Path, audit_rows: list[dict]) -> list[str]:
    values: set[str] = set()
    if log.is_file():
        for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
            if ACTION_PATTERN.search(line):
                values.add(line.strip()[:1000])
    for row in audit_rows:
        for key, value in row.items():
            if "action" in key.lower() and value not in (None, "", [], {}):
                values.add(f"{key}={json.dumps(value, sort_keys=True)[:900]}")
        token = row.get("plannerSyntheticActionKey")
        if token:
            values.add(f"plannerSyntheticActionKey={token}")
    return sorted(values)


def evaluate(run: Path, container_returncode: int,
             selected: tuple[Case, ...] | None = None,
             profile_jfr: bool = False,
             case_timeout_seconds: int = 300,
             jfr_profile_evidence: dict[str, object] | None = None) -> dict:
    results: list[dict] = []
    all_frontiers: list[dict] = []
    all_actions: list[str] = []
    required_action_cases: dict[str, bool] = {}
    selected_cases = selected if selected is not None else default_cases()
    for case in selected_cases:
        profile_files = (jfr_profile_evidence or {}).get("files", {})
        jfr_evidence = (profile_files.get(case.name) if profile_jfr and profile_files
                        else jfr_profile_manifest(
                            (case,), profile_jfr, case_timeout_seconds, run)["files"].get(case.name))
        jfr_passed = (not profile_jfr or bool(
            jfr_evidence and jfr_evidence["exists"] and jfr_evidence["size"] > 0
            and jfr_evidence["sha256"] and jfr_evidence["parserPassed"]))
        fed_log = run / "cases" / case.name / "fed.log"
        fed_rc = read_rc(run / "cases" / case.name / "fed.rc")
        audit_rows, audit_errors = read_audits(run / "audit" / f"{case.name}-fed")
        schemas = sorted({str(row.get("schema")) for row in audit_rows if row.get("schema")})
        frontiers = [row for row in audit_rows
                     if row.get("schema") == "fed-runtime-conversion-frontier-v1"]
        all_frontiers.extend(frontiers)
        actions = action_diagnostics(fed_log, audit_rows)
        all_actions.extend(f"{case.name}: {value}" for value in actions)
        if case.requires_action_evidence:
            required_action_cases[case.name] = bool(actions)
        text = fed_log.read_text(encoding="utf-8", errors="replace") if fed_log.is_file() else ""
        fed_no_relocation = (bool(re.search(
            r"(?m)^\[PlannerRuntimeAudit\].*opcode=\+ .*plannedTarget=FED/FOUT.*actual=FED/FOUT", text))
            and not re.search(r"(?i)stage=RELOCATE|opcode=fed_refed|plannedTarget=SYNTHETIC/REFED", text))
        branch_upload = (bool(re.search(
            r"(?m)^\[PlannerRuntimeAudit\]\[Lowering-Synthetic\] status=MATCH .*"
            r"stage=FOUT .*opcode=fed_fout .*plannedPhysical=FED/FOUT.*actual=FED/FOUT", text))
            and bool(re.search(
                r"(?m)^\[PlannerRuntimeAudit\]\[Execution\] status=MATCH .*"
                r"opcode=fed_fout .*plannedPhysical=FED/FOUT.*actual=FED/FOUT", text)))
        if case.expected_success:
            cp_rc = read_rc(run / "cases" / case.name / "cp.rc")
            cp_log = run / "cases" / case.name / "cp.log"
            cp_text = cp_log.read_text(encoding="utf-8", errors="replace") if cp_log.is_file() else ""
            reference = markers(cp_log)
            actual = markers(fed_log)
            dynamic_native = case.kind != "dynamic_reverse" or (
                "WEIGHTED" in reference
                and reference.get("ROWS") == 8 and reference.get("COLS") == 3
                and all(re.search(
                    rf"(?m)^\[PlannerRuntimeAudit\]\[Execution\] status=MATCH .*opcode={opcode} .*"
                    r"plannedTarget=FED/FOUT.*actual=FED/FOUT", text)
                    for opcode in ("rev", "exp")))
            model_comparison = (compare_models(
                run / "cases" / case.name / "cp-model.csv",
                run / "cases" / case.name / "fed-model.csv",
                (5, 1) if is_steplm(case) else None)
                if case.training else None)
            selection_comparison = (compare_selections(
                run / "cases" / case.name / "cp-selection.csv",
                run / "cases" / case.name / "fed-selection.csv", 5)
                if is_steplm(case) else None)
            checkpoints = planner_checkpoints(text) if case.training else []
            checkpoint_phases = {str(item.get("phase")) for item in checkpoints}
            trace_complete = (not case.training or
                              ("INITIAL_BOUND" in checkpoint_phases and bool(checkpoints)
                               and checkpoints[-1].get("phase") in TERMINAL_CHECKPOINT_PHASES))
            checkpoint_summary = (None if not case.training else {
                "initial": next((item for item in checkpoints
                                 if item.get("phase") == "INITIAL_BOUND"), None),
                "seedBoundary": next((item for item in checkpoints
                                      if item.get("phase") == "SEED_BOUNDARY"), None),
                "final": checkpoints[-1] if checkpoints else None,
                "seedBoundaryCount": sum(item.get("phase") == "SEED_BOUNDARY"
                                         for item in checkpoints),
            })
            audit_violations = AUDIT_VIOLATION.findall(text)
            loss_progress = (None if not case.requires_loss_progress else {
                "initial": reference.get("LOSS_INITIAL"),
                "final": reference.get("LOSS_FINAL"),
                "decreased": ("LOSS_INITIAL" in reference and "LOSS_FINAL" in reference
                              and math.isfinite(reference["LOSS_INITIAL"])
                              and math.isfinite(reference["LOSS_FINAL"])
                              and reference["LOSS_FINAL"] < reference["LOSS_INITIAL"]),
            })
            passed = (cp_rc == 0 and fed_rc == 0 and close_markers(reference, actual)
                      and dynamic_native
                      and (case.kind != "function_calls"
                           or {"CALL_C", "CALL_D"}.issubset(reference))
                      and not audit_errors
                      and not audit_violations
                      and (not case.training or bool(model_comparison and
                           model_comparison["matched"] and model_comparison["nonzero"]))
                      and (not is_steplm(case) or bool(
                           selection_comparison and selection_comparison["matched"]))
                      and jfr_passed
                      and (not case.requires_loss_progress or bool(
                           loss_progress and loss_progress["decreased"]))
                      and trace_complete
                      and (not case.requires_fed_no_relocation or fed_no_relocation))
            result = {"case": case.name, "expected": "success", "passed": passed,
                      "cpReturncode": cp_rc, "fedReturncode": fed_rc,
                      "cpFingerprint": reference, "fedFingerprint": actual,
                      "auditSchemas": schemas, "auditRows": len(audit_rows),
                      "auditErrors": audit_errors, "actionDiagnostics": actions,
                      "runtimeAuditViolations": audit_violations,
                      "modelComparison": model_comparison,
                      "selectionComparison": selection_comparison,
                      "jfrProfile": jfr_evidence,
                      "jfrProfilePassed": jfr_passed,
                      "plannerCheckpoints": checkpoints,
                      "plannerCheckpointSummary": checkpoint_summary,
                      "plannerTraceComplete": trace_complete,
                      "lossProgress": loss_progress,
                      "cpStatistics": runtime_statistics(cp_text),
                      "fedStatistics": runtime_statistics(text),
                      "dynamicNativeExecution": dynamic_native if case.kind == "dynamic_reverse" else None,
                      "requiresFedNoRelocation": case.requires_fed_no_relocation,
                      "fedNoRelocation": fed_no_relocation,
                      "requiresBranchUpload": case.requires_branch_upload,
                      "branchUploadLoweredAndExecuted": branch_upload}
            if case.requires_branch_upload:
                result["passed"] = result["passed"] and branch_upload
        else:
            # DMLScript historically reports selected compilation failures in the
            # log while returning zero. Require the exact privacy diagnostic and
            # absence of a completed numeric fingerprint instead of trusting rc.
            no_fingerprint = not close_markers(markers(fed_log), markers(fed_log))
            if case.expected_failure == "infeasible_private_tuple":
                rejection = (fed_rc is not None and no_fingerprint and bool(re.search(
                    r"LOCAL_CONFLICT_BLOCK_INFEASIBLE|No privacy-safe physical placement|"
                    r"EXACT_VE_NO_FEASIBLE_ASSIGNMENT", text)))
                expected = "infeasible_private_tuple"
            else:
                rejection = (fed_rc is not None and no_fingerprint
                             and bool(re.search(r"(?i)privacy|private", text)))
                expected = "privacy_rejection"
            result = {"case": case.name, "expected": expected,
                      "passed": rejection and not audit_errors and jfr_passed,
                      "fedReturncode": fed_rc, "rejectionDiagnostic": rejection,
                      "jfrProfile": jfr_evidence, "jfrProfilePassed": jfr_passed,
                      "auditSchemas": schemas, "auditRows": len(audit_rows),
                      "auditErrors": audit_errors, "actionDiagnostics": actions}
        results.append(result)
    runtime_conversions = [row for row in all_frontiers if row.get("conversionKind") == "RUNTIME_TO_FED"
                           or row.get("kind") == "RUNTIME_TO_FED"]
    action_gate = all(required_action_cases.values())
    model_proof_rc = read_rc(run / "model-proof.rc")
    model_proof_log = ((run / "model-proof.log").read_text(encoding="utf-8", errors="replace")
                       if (run / "model-proof.log").is_file() else "")
    model_proof_passed = model_proof_rc == 0 and bool(re.search(r"(?m)^OK \([1-9][0-9]* tests?\)$", model_proof_log))
    try:
        preflight = json.loads((run / "class-preflight-actual.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        preflight = {"schema": "systemds-joint-boundary-class-preflight-v1",
                     "status": "MISSING", "actual": {}}
    preflight_passed = preflight.get("status") == "PASSED"
    passed = (container_returncode == 0 and preflight_passed and model_proof_passed
              and all(item["passed"] for item in results)
              and not runtime_conversions and action_gate)
    return {"schema": "systemds-joint-boundary-e2e-v1",
            "status": "PASSED" if passed else "FAILED",
            "containerReturncode": container_returncode,
            "classPreflightPassed": preflight_passed,
            "classPreflight": preflight,
            # Retained for consumers of the earlier overlay-based evidence schema.
            "overlayPreflightPassed": preflight_passed,
            "overlayPreflight": preflight,
            "noFedRuntimeConversion": len(runtime_conversions) == 0,
            "runtimeConversionViolations": runtime_conversions,
            "modelProofPassed": model_proof_passed, "modelProofReturncode": model_proof_rc,
            "recordProfile": profile_jfr,
            "jfrProfilePassed": all(item["jfrProfilePassed"] for item in results),
            "actionEvidencePresent": action_gate, "requiredActionCases": required_action_cases,
            "requestedCases": [case.name for case in selected_cases],
            "actionDiagnostics": sorted(set(all_actions)),
            "cases": results}


def validate_artifacts(classes: Path, test_classes: Path, dependencies: Path,
                       main_sources: Path, test_sources: Path,
                       model_proof_class: str, dry_run: bool) -> None:
    required = (classes, test_classes, dependencies, main_sources, test_sources)
    missing = [str(path) for path in required if not path.is_dir()]
    if missing:
        raise ValueError("missing merged build/source artifacts: " + ", ".join(missing))
    if not SAFE_JAVA_CLASS.fullmatch(model_proof_class):
        raise ValueError(f"unsafe --model-proof-class: {model_proof_class!r}")
    proof = test_classes / Path(*model_proof_class.split(".")).with_suffix(".class")
    if not proof.is_file() and not dry_run:
        raise ValueError(f"joint physical model proof is not compiled: {proof}")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.timeout_seconds < 60 or args.timeout_seconds > 3600:
        raise ValueError("--timeout-seconds must be between 60 and 3600")
    if args.case_timeout_seconds < 30 or args.case_timeout_seconds > 900:
        raise ValueError("--case-timeout-seconds must be between 30 and 900")
    available = {case.name: case for case in cases()}
    requested = args.selected_cases or [case.name for case in default_cases()]
    unknown = sorted(set(requested) - set(available))
    if unknown:
        raise ValueError("unknown --case: " + ", ".join(unknown))
    if len(requested) != len(set(requested)):
        raise ValueError("duplicate --case values are not allowed")
    selected = tuple(available[name] for name in requested)
    output_root = assert_grid_root(args.output_root)
    stage_root = args.stage_root.resolve()
    classes = args.classes.resolve()
    test_classes = args.test_classes.resolve()
    dependencies = args.dependencies.resolve()
    main_sources = args.main_sources.resolve()
    test_sources = args.test_sources.resolve()
    validate_artifacts(classes, test_classes, dependencies, main_sources, test_sources,
                       args.model_proof_class, args.dry_run)
    run = allocate_run(output_root, args.run_id)
    stage = allocate_stage(stage_root, run)
    frozen = stage / "frozen-inputs"
    frozen.mkdir()
    frozen_classes = frozen / "main-classes"
    frozen_test_classes = frozen / "test-classes"
    frozen_main_sources = frozen / "main-sources"
    frozen_test_sources = frozen / "test-sources"
    frozen_dependencies = frozen / "dependencies"
    artifact_inventories = {
        "mainClasses": freeze_tree(classes, frozen_classes),
        "testClasses": freeze_tree(test_classes, frozen_test_classes),
        "mainSources": freeze_tree(main_sources, frozen_main_sources),
        "testSources": freeze_tree(test_sources, frozen_test_sources),
        "dependencies": freeze_tree(dependencies, frozen_dependencies),
    }
    preflight = (class_preflight_expectations(
        frozen_classes, frozen_test_classes, args.model_proof_class)
        if not args.dry_run else {})
    input_hashes = write_inputs(stage, selected)
    fixture_hashes = write_fixtures(stage, selected)
    script = write_container_script(
        stage, args.model_proof_class, args.case_timeout_seconds, selected,
        args.debug_fedreq, args.profile_jfr, preflight)
    container, command = docker_command(
        stage, frozen_classes, frozen_test_classes, frozen_dependencies)
    runner_source = Path(__file__).resolve()
    dispatch_source = runner_source.with_name("run_LAN_docker.sh")
    source_hashes = {
        "runner": sha256(runner_source),
        "dispatch": sha256(dispatch_source),
    }
    frozen_runner_source = run / "runner-source.py"
    frozen_dispatch_source = run / "dispatch-source.sh"
    shutil.copy2(runner_source, frozen_runner_source)
    shutil.copy2(dispatch_source, frozen_dispatch_source)
    if (sha256(frozen_runner_source) != source_hashes["runner"]
            or sha256(frozen_dispatch_source) != source_hashes["dispatch"]):
        raise ValueError("runner source changed while it was being frozen")
    manifest = {
        "schema": "systemds-joint-boundary-e2e-input-v1", "run": str(run),
        "containerStage": str(stage),
        "image": PINNED_IMAGE, "classes": str(classes),
        "testClasses": str(test_classes), "dependencies": str(dependencies),
        "mainSources": str(main_sources), "testSources": str(test_sources),
        "frozenClasses": str(run / "frozen-inputs/main-classes"),
        "frozenTestClasses": str(run / "frozen-inputs/test-classes"),
        "frozenMainSources": str(run / "frozen-inputs/main-sources"),
        "frozenTestSources": str(run / "frozen-inputs/test-sources"),
        "frozenDependencies": str(run / "frozen-inputs/dependencies"),
        "modelProofClass": args.model_proof_class,
        "artifactInventoryDigests": {key: inventory_digest(value)
                                     for key, value in artifact_inventories.items()},
        "artifactFileCounts": {key: len(value) for key, value in artifact_inventories.items()},
        "sourceSha256": source_hashes,
        "frozenRunnerSource": str(frozen_runner_source),
        "frozenDispatchSource": str(frozen_dispatch_source),
        "inputSha256": input_hashes, "fixtureSha256": fixture_hashes,
        "container": container, "dockerArgv": command, "containerScriptSha256": sha256(script),
        "network": "none (worker and coordinator use container loopback)",
        "planner": "COMPILE_COST_BASED", "noFedRuntimeConversion": True,
        "caseTimeoutSeconds": args.case_timeout_seconds,
        "classPreflightExpected": preflight,
        "debugFedreq": args.debug_fedreq,
        "jfrProfile": jfr_profile_manifest(
            selected, args.profile_jfr, args.case_timeout_seconds),
        "requestedCases": [case.name for case in selected],
        "dryRun": args.dry_run, "buildReady": classes.is_dir() and test_classes.is_dir(),
    }
    write_json(run / "manifest.json", manifest)
    write_json(run / "artifact-inventories.json", artifact_inventories)
    if args.dry_run:
        shutil.copytree(stage, run, dirs_exist_ok=True)
        shutil.rmtree(stage)
        write_json(run / "result.json", {"schema": "systemds-joint-boundary-e2e-v1",
                                          "status": "DRY_RUN", "buildReady": True,
                                          "recordProfile": args.profile_jfr,
                                          "requestedCases": [case.name for case in selected]})
        print(json.dumps({"status": "DRY_RUN", "run": str(run),
                          "buildReady": True}, sort_keys=True))
        return 0
    inspected = subprocess.check_output(
        ["docker", "image", "inspect", PINNED_IMAGE, "--format", "{{.Id}}"], text=True).strip()
    if inspected != PINNED_IMAGE:
        raise ValueError(f"pinned Docker image identity mismatch: {inspected}")
    with (run / "container.log").open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                   timeout=args.timeout_seconds, check=False)
    shutil.copytree(stage, run, dirs_exist_ok=True)
    shutil.rmtree(stage)
    manifest["jfrProfile"] = jfr_profile_manifest(
        selected, args.profile_jfr, args.case_timeout_seconds, run)
    write_json(run / "manifest.json", manifest)
    result = evaluate(run, completed.returncode, selected, args.profile_jfr,
                      args.case_timeout_seconds, manifest["jfrProfile"])
    result["run"] = str(run)
    result["image"] = inspected
    write_json(run / "result.json", result)
    print(json.dumps({"status": result["status"], "run": str(run),
                      "cases": {item["case"]: item["passed"] for item in result["cases"]},
                      "actionEvidencePresent": result["actionEvidencePresent"]}, sort_keys=True))
    return 0 if result["status"] == "PASSED" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"error: {exc}", file=os.sys.stderr)
        raise SystemExit(2)
