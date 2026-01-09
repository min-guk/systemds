#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT="${LOG_ROOT:-${ROOT_DIR}/logs/fedall}"
COMPARE_LOG_FORMATS="${COMPARE_LOG_FORMATS:-hopid,oracle,oracle}"
COMPARE_OUTPUT_NAME="${COMPARE_OUTPUT_NAME:-oracle_decision_diff.csv}"

usage() {
  cat <<'EOF'
Usage: run_fedall_planning_compare.sh [TEST_DIR ...]

Runs compare_oracle_decisions.py for existing logs only.

Args:
  TEST_DIR    Optional test directory name(s). If omitted, all subdirs of LOG_ROOT are used.

Env:
  LOG_ROOT              Root log directory (default: logs/fedall)
  COMPARE_LOG_FORMATS   Comma-separated formats for cost/fedall/heuristic (default: hopid,oracle,oracle)
  COMPARE_OUTPUT_NAME   Output file name (default: oracle_decision_diff.csv)
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi

FAILED_DIRS=()
SKIPPED_DIRS=()

run_compare_dir() {
  local dir="$1"
  local cost_log="${dir}/test_cost.log"
  local fedall_log="${dir}/test_fedall.log"
  local heuristic_log="${dir}/test_heuristic.log"
  local output_path="${dir}/${COMPARE_OUTPUT_NAME}"

  local missing=()
  [[ -f "$cost_log" ]] || missing+=("test_cost.log")
  [[ -f "$fedall_log" ]] || missing+=("test_fedall.log")
  [[ -f "$heuristic_log" ]] || missing+=("test_heuristic.log")
  if [[ ${#missing[@]} -ne 0 ]]; then
    echo "==> Skipping ${dir} (missing: ${missing[*]})" >&2
    SKIPPED_DIRS+=("${dir}")
    return 0
  fi

  echo "==> Compare ${dir}"
  set +e
  python3 "${ROOT_DIR}/scripts/compare_oracle_decisions.py" \
    "$cost_log" \
    "$fedall_log" \
    "$heuristic_log" \
    --log-formats "$COMPARE_LOG_FORMATS" \
    --format csv \
    --output "$output_path"
  local rc=$?
  set -e
  if [[ $rc -ne 0 ]]; then
    FAILED_DIRS+=("${dir}")
  fi
}

if [[ $# -gt 0 ]]; then
  for entry in "$@"; do
    if [[ "$entry" = /* ]]; then
      run_compare_dir "$entry"
    else
      run_compare_dir "${LOG_ROOT}/${entry}"
    fi
  done
else
  for dir in "${LOG_ROOT}"/*; do
    [[ -d "$dir" ]] || continue
    run_compare_dir "$dir"
  done
fi

if [[ ${#FAILED_DIRS[@]} -ne 0 ]]; then
  echo "==> Completed with failures:" >&2
  printf '  - %s\n' "${FAILED_DIRS[@]}" >&2
  exit 1
fi

if [[ ${#SKIPPED_DIRS[@]} -ne 0 ]]; then
  echo "==> Skipped directories:" >&2
  printf '  - %s\n' "${SKIPPED_DIRS[@]}" >&2
fi
