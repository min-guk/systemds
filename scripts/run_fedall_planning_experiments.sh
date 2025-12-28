#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT="${LOG_ROOT:-${ROOT_DIR}/logs/fedall}"
TEST_PKG="org.apache.sysds.test.functions.federated.fedplanning"
MVN_CMD="${MVN_CMD:-mvn}"
MVN_ARGS="${MVN_ARGS:-}"

TEST_MATRIX=(
  "FederatedCNNPlanningTest runCNNFOUTTest runCNNHeuristicTest runCNNCostBasedTestPrivateAggregate"
  "FederatedKMeansPlanningTest runKMeansFOUTTest runKMeansHeuristicTest runKMeansCostBasedTestPrivateAggregate"
  "FederatedL2SVMPlanningTest runL2SVMFOUTTest runL2SVMHeuristicTest runL2SVMCostBasedTestPrivateAggregate"
  "FederatedLMPlanningTest runLMFOUTTest runLMHeuristicTest runLMCostBasedTestPrivateAggregate"
  "FederatedLogRegPlanningTest runLogRegFOUTTest runLogRegHeuristicTest runLogRegCostBasedTestPrivateAggregate"
  "FederatedP2FFNPlanningTest runP2FFNFOUTTest runP2FFNHeuristicTest runP2FFNCostBasedTestPrivateAggregate"
  "FederatedPCAPlanningTest runPCAFOUTTest runPCAHeuristicTest runPCACostBasedTestPrivateAggregate"
)

FAILED_TESTS=()

run_test() {
  local test_name="$1"
  local method_name="$2"
  local log_path="$3"

  mkdir -p "$(dirname "$log_path")"
  echo "==> ${test_name}#${method_name}"
  set +e
  (
    cd "$ROOT_DIR"
    "$MVN_CMD" $MVN_ARGS -Dtest="${TEST_PKG}.${test_name}#${method_name}" test 2>&1 | tee "$log_path"
  )
  local rc=${PIPESTATUS[0]}
  set -e
  return "$rc"
}

run_trace() {
  local log_path="$1"
  local trace_path="$2"

  mkdir -p "$(dirname "$trace_path")"
  set +e
  python3 "${ROOT_DIR}/scripts/trace_fout_propagation.py" \
    --log "$log_path" \
    --name-regex "^TRead X$" \
    --tree \
    > "$trace_path" 2>&1
  local trace_rc=$?
  set -e
  if [[ $trace_rc -ne 0 ]]; then
    echo "trace_fout_propagation exited with code ${trace_rc} for ${log_path}" >&2
  fi
  return "$trace_rc"
}

run_compare() {
  local cost_log="$1"
  local fedall_log="$2"
  local heuristic_log="$3"
  local output_path="$4"

  mkdir -p "$(dirname "$output_path")"
  python3 "${ROOT_DIR}/scripts/compare_oracle_decisions.py" \
    "$cost_log" \
    "$fedall_log" \
    "$heuristic_log" \
    --format csv \
    --output "$output_path"
}

for entry in "${TEST_MATRIX[@]}"; do
  read -r test_name fout_method heuristic_method cost_method <<< "$entry"

  test_dir="${LOG_ROOT}/${test_name}"
  fout_log="${test_dir}/test_fedall.log"
  heuristic_log="${test_dir}/test_heuristic.log"
  cost_log="${test_dir}/test_cost.log"
  trace_log="${test_dir}/trace_fout_tree.txt"
  compare_log="${test_dir}/oracle_decision_diff.csv"

  if ! run_test "$test_name" "$fout_method" "$fout_log"; then
    FAILED_TESTS+=("${test_name}#${fout_method}")
  fi
  if ! run_test "$test_name" "$heuristic_method" "$heuristic_log"; then
    FAILED_TESTS+=("${test_name}#${heuristic_method}")
  fi
  if ! run_test "$test_name" "$cost_method" "$cost_log"; then
    FAILED_TESTS+=("${test_name}#${cost_method}")
  fi

  if ! run_trace "$cost_log" "$trace_log"; then
    FAILED_TESTS+=("${test_name}#trace")
  fi
  if ! run_compare "$cost_log" "$fout_log" "$heuristic_log" "$compare_log"; then
    FAILED_TESTS+=("${test_name}#compare")
  fi
done

if [[ ${#FAILED_TESTS[@]} -ne 0 ]]; then
  echo "==> Completed with failures:" >&2
  printf '  - %s\n' "${FAILED_TESTS[@]}" >&2
  exit 1
fi
