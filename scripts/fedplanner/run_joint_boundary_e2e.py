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
WORKER_PORT = 13000
POOL_A_PORT = 13001
POOL_B_PORT = 13002
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,95}$")
SAFE_JAVA_CLASS = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
DEFAULT_MODEL_PROOF_CLASS = (
    "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact."
    "JointBoundaryPhysicalModelProofTest")
MARKER = re.compile(
    r"^JOINT_E2E_(SUM|NORM2|ROWS|COLS|CALL_C|CALL_D)="
    r"([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?)$",
    re.MULTILINE)
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
        Case("joint_function_private_mix_negative", "function_private_mix_negative",
             expected_success=False, expected_failure="infeasible_private_tuple"),
        Case("joint_branch_upload", "branch_upload", requires_action_evidence=True,
             requires_branch_upload=True),
        Case("l2svm_protected_y_negative", "l2svm", (0, 1, 0, 1, 0, 1, 0, 1),
             y_privacy="private", expected_success=False),
    )


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
    prefix = federated_read("X", 8, 3) if federated else local_read("X_PUBLIC") + "X=X_PUBLIC;\n"
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
    for case in selected or cases():
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
    for case in selected or cases():
        case_dir = run / "cases" / case.name
        case_dir.mkdir(parents=True)
        cp = case_dir / "cp.dml"
        fed = case_dir / "fed.dml"
        cp.write_text(program(case, False), encoding="utf-8")
        fed.write_text(program(case, True), encoding="utf-8")
        fixture_hashes[case.name] = {"cp": sha256(cp), "fed": sha256(fed)}
    fixture_hashes["config"] = {"sha256": sha256(config)}
    return fixture_hashes


def java_command(case: Case, mode: str, case_timeout_seconds: int = 300) -> str:
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
    return (f'timeout {case_timeout_seconds} java --add-modules jdk.incubator.vector -Xmx3g '
            f'-XX:ActiveProcessorCount=4 {properties} -cp "$CP" '
            f'org.apache.sysds.api.DMLScript -f /evidence/cases/{case.name}/{mode}.dml '
            '-config /evidence/config.xml -exec singlenode -seed 7 '
            '-noFedRuntimeConversion -stats 100 -explain runtime')


def write_container_script(run: Path, model_proof_class: str = DEFAULT_MODEL_PROOF_CLASS,
                           case_timeout_seconds: int = 300,
                           selected: tuple[Case, ...] | None = None,
                           debug_fedreq: bool = False,
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
    for case in selected or cases():
        modes = ("fed",) if not case.expected_success else ("cp", "fed")
        for mode in modes:
            command = java_command(case, mode, case_timeout_seconds)
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
             selected: tuple[Case, ...] | None = None) -> dict:
    results: list[dict] = []
    all_frontiers: list[dict] = []
    all_actions: list[str] = []
    required_action_cases: dict[str, bool] = {}
    selected_cases = selected or cases()
    for case in selected_cases:
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
            reference = markers(run / "cases" / case.name / "cp.log")
            actual = markers(fed_log)
            passed = (cp_rc == 0 and fed_rc == 0 and close_markers(reference, actual)
                      and (case.kind != "function_calls"
                           or {"CALL_C", "CALL_D"}.issubset(reference))
                      and not audit_errors
                      and (not case.requires_fed_no_relocation or fed_no_relocation))
            result = {"case": case.name, "expected": "success", "passed": passed,
                      "cpReturncode": cp_rc, "fedReturncode": fed_rc,
                      "cpFingerprint": reference, "fedFingerprint": actual,
                      "auditSchemas": schemas, "auditRows": len(audit_rows),
                      "auditErrors": audit_errors, "actionDiagnostics": actions,
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
                      "passed": rejection and not audit_errors,
                      "fedReturncode": fed_rc, "rejectionDiagnostic": rejection,
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
    requested = args.selected_cases or list(available)
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
    script = write_container_script(stage, args.model_proof_class, args.case_timeout_seconds,
                                    selected, args.debug_fedreq, preflight)
    container, command = docker_command(
        stage, frozen_classes, frozen_test_classes, frozen_dependencies)
    runner_source = Path(__file__).resolve()
    dispatch_source = runner_source.with_name("run_LAN_docker.sh")
    source_hashes = {
        "runner": sha256(runner_source),
        "dispatch": sha256(dispatch_source),
    }
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
        "inputSha256": input_hashes, "fixtureSha256": fixture_hashes,
        "container": container, "dockerArgv": command, "containerScriptSha256": sha256(script),
        "network": "none (worker and coordinator use container loopback)",
        "planner": "COMPILE_COST_BASED", "noFedRuntimeConversion": True,
        "caseTimeoutSeconds": args.case_timeout_seconds,
        "classPreflightExpected": preflight,
        "debugFedreq": args.debug_fedreq,
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
    result = evaluate(run, completed.returncode, selected)
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
