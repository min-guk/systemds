#!/usr/bin/env bash
# One-shot Docker-only DP search-space validation entrypoint.
#
# This deliberately delegates to the frozen P5 evidence harness. It does not
# invoke run_LAN.sh, run Java on the host, execute a workload, resume the paused
# all14 performance goal, or retry/select a favorable result.
set -euo pipefail

readonly HARNESS=/home/mchoi/w1357-diagnostics/paper-refactor-20260928/perf-harness
exec python3 "$HARNESS/run_LAN_docker.py" "$@"
