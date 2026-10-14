#!/usr/bin/env python3
"""Strict 10-second gate for the sealed, actual LogReg and GLM workloads."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_memo_planning_matrix as matrix

TARGET_SECONDS = 10.0
ACTUAL_WORKLOADS = (("ml", "logreg"), ("ml", "glm"))


def expected_cells() -> list[dict[str, Any]]:
    return matrix.cells(ACTUAL_WORKLOADS, matrix.WORKERS)


def _positive_at_most(value: Any, maximum: float) -> bool:
    return type(value) in (int, float) and not isinstance(value, bool) and 0 < value <= maximum


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _input_receipt_errors(attempt: Path, result: dict[str, Any],
                          expected: tuple[str, str, dict[str, Any], dict[str, Any]]) -> list[str]:
    errors = []
    try:
        render = _read_json(attempt / "render-receipt.json")
        adaptation = _read_json(attempt / "adaptation-receipt.json")
        source = (attempt / "cell.dml").read_text(encoding="utf-8")
        config = (attempt / "execution.xml").read_text(encoding="utf-8")
    except (OSError, json.JSONDecodeError):
        return ["missing or invalid sealed input artifact"]
    expected_source, expected_config, expected_render, expected_adaptation = expected
    if source != expected_source or render != expected_render or adaptation != expected_adaptation:
        errors.append("sealed rendered input differs from pinned renderer output")
    if config != expected_config:
        errors.append("compile configuration artifact mismatch")
    digest = result.get("provenance", {}).get("dmlSha256")
    if (adaptation.get("originalSha256") != digest
            or adaptation.get("adaptedSha256") != digest
            or adaptation.get("transformations") != []
            or adaptation.get("sealedEndpointsPreserved") is not True):
        errors.append("DML source was changed after sealed rendering")
    if digest != matrix.sha256_bytes(source.encode()):
        errors.append("on-disk DML hash mismatch")
    if result.get("provenance", {}).get("configSha256") != matrix.sha256_bytes(
            config.encode()):
        errors.append("compile configuration mismatch")
    return errors


def row_errors(root: Path, cell: dict[str, Any], result: dict[str, Any], contract: dict[str, Any],
               expected_artifacts: tuple[str, str, dict[str, Any], dict[str, Any]],
               engine: Path, stage: Path) -> list[str]:
    errors = []
    execution = matrix.execution_cell(cell)
    checks = (
        (result.get("schema") == "systemds-memo-planning-attempt/v1", "wrong attempt schema"),
        (result.get("renderCell") == cell and result.get("cell") == execution, "wrong workload cell"),
        (result.get("executionPlanner") == "COMPILE_MEMO", "wrong planner"),
        (result.get("status") == "passed" and result.get("returncode") == 0, "compile did not pass"),
        (result.get("failureCategory") is None and result.get("harnessTimeout") is False, "failure or timeout"),
        (result.get("compileOnly") is True and result.get("compileOnlyVerified") is True,
         "missing compile-only receipt"),
        (result.get("reportedWorkerCountVerified") is True, "worker binding was not verified"),
        (result.get("costContract") == contract, "resource/cost contract mismatch"),
        (result.get("containerResources") == matrix.CONTAINER_RESOURCES,
         "container resource contract mismatch"),
        (result.get("image") == matrix.PINNED_IMAGE, "container image mismatch"),
    )
    errors.extend(message for valid, message in checks if not valid)
    provenance = result.get("provenance") or {}
    expected_provenance = {
        "rendererPath": str(matrix.RENDERER_PATH),
        "rendererSha256": matrix.PINNED_RENDERER_SHA256,
        "stage": str(stage),
        "stageManifestSha256": matrix.PINNED_STAGE_MANIFEST_SHA256,
        "engine": str(engine),
    }
    if any(provenance.get(key) != value for key, value in expected_provenance.items()):
        errors.append("execution provenance does not bind pinned renderer, stage, and engine")
    plans = result.get("memoPlanning")
    searches = result.get("memoSearch")
    if (not isinstance(plans, list) or not plans
            or any(row.get("status") != "OPTIMAL" or row.get("workers") != cell["workers"] for row in plans)):
        errors.append("missing OPTIMAL Memo planning receipt")
    if not isinstance(searches, list) or not searches or any(row.get("status") != "OPTIMAL" for row in searches):
        errors.append("missing OPTIMAL exact-search receipt")
    timing = result.get("timing") or {}
    if not _positive_at_most(timing.get("fullInitialPlanningSeconds"), TARGET_SECONDS):
        errors.append("full initial planning is missing or exceeds 10 seconds")
    if not _positive_at_most(timing.get("fullCompileSeconds"), TARGET_SECONDS):
        errors.append("full compile is missing or exceeds 10 seconds")
    if not _positive_at_most(timing.get("runtimeProgramSeconds"), TARGET_SECONDS):
        errors.append("missing RuntimeProgram construction receipt")
    if (type(timing.get("executionSeconds")) not in (int, float)
            or timing.get("executionSeconds") != 0):
        errors.append("runtime execution was not proven to be zero")
    if (_positive_at_most(timing.get("fullInitialPlanningSeconds"), TARGET_SECONDS)
            and _positive_at_most(timing.get("fullCompileSeconds"), TARGET_SECONDS)
            and timing["fullInitialPlanningSeconds"] > timing["fullCompileSeconds"]):
        errors.append("planning timer exceeds enclosing compile timer")
    errors.extend(_input_receipt_errors(root / "attempts" / matrix.safe_id(cell), result,
                                        expected_artifacts))
    return errors


def evaluate(root: Path) -> dict[str, Any]:
    root = root.resolve()
    errors = []
    try:
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        manifest = {}
        errors.append("missing or invalid matrix manifest")
    expected = expected_cells()
    contract = matrix.validate_cost_contract()
    renderer = None
    stage = None
    engine = None
    expected_manifest = {
        "renderCells": expected,
        "executionCells": [matrix.execution_cell(cell) for cell in expected],
        "executionPlanner": "COMPILE_MEMO",
        "rendererAuthorityPlanner": "COMPILE_EXACT",
        "image": matrix.PINNED_IMAGE,
        "rendererSha256": matrix.PINNED_RENDERER_SHA256,
        "stageManifestSha256": matrix.PINNED_STAGE_MANIFEST_SHA256,
        "renderOnly": False,
        "costContract": contract,
    }
    for key, value in expected_manifest.items():
        if manifest.get(key) != value:
            errors.append(f"manifest {key} mismatch")
    try:
        stage = matrix.validate_sealed_stage(Path(manifest["stage"]))
        renderer = matrix.renderer_module().Renderer(stage)
    except (KeyError, OSError, ValueError, json.JSONDecodeError):
        errors.append("pinned renderer or sealed stage verification failed")
    try:
        engine = Path(manifest["engine"]).resolve()
        engine_receipt = matrix.validate_engine(engine)
        if manifest.get("enginePreflight") != engine_receipt:
            errors.append("frozen engine receipt mismatch")
    except (KeyError, OSError, ValueError, json.JSONDecodeError):
        errors.append("frozen engine verification failed")
    rejected = []
    maximum_compile = None
    maximum_planning = None
    for cell in expected:
        path = root / "attempts" / matrix.safe_id(cell) / "result.json"
        try:
            result = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            rejected.append({"cell": matrix.execution_cell(cell)["id"], "reasons": ["missing result"]})
            continue
        if renderer is None or stage is None or engine is None:
            reasons = ["pinned renderer or sealed stage unavailable"]
        else:
            try:
                expected_artifacts = matrix.render_artifacts(renderer, cell)
                reasons = row_errors(root, cell, result, contract, expected_artifacts, engine, stage)
            except (OSError, ValueError, KeyError, TypeError):
                reasons = ["pinned renderer rejected sealed workload cell"]
        if reasons:
            rejected.append({"cell": matrix.execution_cell(cell)["id"], "reasons": reasons})
        else:
            timing = result["timing"]
            maximum_compile = max(maximum_compile or 0, timing["fullCompileSeconds"])
            maximum_planning = max(maximum_planning or 0, timing["fullInitialPlanningSeconds"])
    return {"schema": "systemds-actual-ml-planning-performance/v1", "root": str(root),
        "passed": not errors and not rejected, "targetSecondsInclusive": TARGET_SECONDS,
        "expectedCells": 8, "acceptedCells": 8 - len(rejected), "manifestErrors": errors,
        "rejected": rejected, "maximumFullCompileSeconds": maximum_compile,
        "maximumFullInitialPlanningSeconds": maximum_planning}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    result = evaluate(args.root)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
