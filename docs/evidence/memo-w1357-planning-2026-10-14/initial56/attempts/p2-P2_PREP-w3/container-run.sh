#!/usr/bin/env bash
set -euo pipefail
cd /workspace/experiments
mkdir -p /evidence/tmp/local /evidence/tmp/scratch
CP=/engine/classes:/deps/*
touch /evidence/setup-complete
set +e
timeout --signal=TERM --kill-after=15 300 java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 -Dsysds.fedplanner.memo.trace=true -Dsysds.privacy.allowPublicRecodeMetadata=true -cp "$CP" org.apache.sysds.api.DMLScript -f /evidence/cell.dml -config /evidence/execution.xml -exec singlenode -seed 1011081480 -noFedRuntimeConversion -stats 100 -explain runtime > /evidence/coordinator.log 2>&1
rc=$?
printf '%s\n' "$rc" > /evidence/coordinator.rc
exit "$rc"
