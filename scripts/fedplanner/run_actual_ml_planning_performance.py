#!/usr/bin/env python3
"""Run each sealed actual LogReg/GLM W1/3/5/7 cell once, then apply the 10s gate."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluate_actual_ml_planning as evaluator
import run_memo_planning_matrix as matrix


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--sealed-stage", required=True, type=Path)
    args = parser.parse_args(argv)
    if args.output_root.exists():
        raise ValueError("official performance output root must not already exist (one attempt per cell)")
    matrix_rc = matrix.main(["--engine", str(args.engine), "--output-root", str(args.output_root),
        "--sealed-stage", str(args.sealed_stage), "--workers", "1,3,5,7",
        "--workloads", "ml:logreg,ml:glm"])
    gate_rc = evaluator.main(["--root", str(args.output_root),
        "--output", str(args.output_root / "acceptance.json")])
    return matrix_rc if matrix_rc != 0 else gate_rc


if __name__ == "__main__":
    raise SystemExit(main())
