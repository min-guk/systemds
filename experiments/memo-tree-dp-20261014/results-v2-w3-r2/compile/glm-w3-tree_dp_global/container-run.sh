#!/usr/bin/env bash
set -euo pipefail
cd /workspace/experiments
mkdir -p /evidence/tmp/local /evidence/tmp/scratch
CP=/engine/classes:/deps/*
exec java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 -Dsysds.fedplanner.memo.trace=true -Dsysds.fedplanner.memo.algorithm=TREE_DP_GLOBAL -Dsysds.fedplanner.memo.tree.searchNodes=2000 -Dsysds.fedplanner.memo.tree.factorCells=256 -Dsysds.fedplanner.memo.tree.cacheEntries=4096 -Dsysds.fedplanner.memo.tree.frontierNodes=10000 -cp "$CP" org.apache.sysds.api.DMLScript -f /evidence/cell.dml -config /evidence/execution.xml -exec singlenode -seed 1011081480 -noFedRuntimeConversion -stats 100 -explain runtime
