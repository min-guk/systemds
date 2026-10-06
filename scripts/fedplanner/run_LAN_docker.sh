#!/usr/bin/env bash
# Docker-only validation/campaign entrypoint.
#
# Explicit lanes below run the requested Docker validation/campaign. The default
# still delegates to the frozen P5 evidence harness. Neither lane invokes
# run_LAN.sh or resumes the unrelated paused all14 performance goal.
set -euo pipefail

if [[ "${1:-}" == "--pruning-ablation" ]]; then
    shift
    exec python3 "$(dirname "$0")/run_pruning_ablation.py" "$@"
fi

if [[ "${1:-}" == "--cost-runtime-validation" ]]; then
    shift
    exec python3 "$(dirname "$0")/validate_cost_runtime_docker.py" "$@"
fi

if [[ "${1:-}" == "--function-boundary-compare" ]]; then
    shift
    exec python3 "$(dirname "$0")/check_function_boundary_plans.py" "$@"
fi

if [[ "${1:-}" == "--campaign" ]]; then
    shift
    exec python3 "$(dirname "$0")/run_matrix_campaign.py" "$@"
fi

# Bounded, artifact-pinned GET/PUT calibration. This is the only supported
# entrypoint for the transport lane; the Python runner owns its Docker lease.
if [[ "${1:-}" == "--transport-calibration" ]]; then
    shift
    exec python3 "$(dirname "$0")/transport_calibration.py" "$@"
fi

# Explicit repo-owned correctness lane. The historical frozen P5 path below is unchanged.
if [[ "${1:-}" == "--greedy-validation" ]]; then
    shift
    exec python3 "$(dirname "$0")/validate_greedy_docker.py" "$@"
fi

readonly HARNESS=/home/mchoi/w1357-diagnostics/paper-refactor-20260928/perf-harness
exec python3 "$HARNESS/run_LAN_docker.py" "$@"
