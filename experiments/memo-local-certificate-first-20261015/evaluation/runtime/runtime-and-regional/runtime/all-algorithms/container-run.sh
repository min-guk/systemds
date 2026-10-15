#!/usr/bin/env bash
set -euo pipefail
cd /workspace
CP=/engine/test-classes:/engine/classes:/deps/*
exec java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 -Dsysds.fedplanner.memo.trace=true -Dsysds.fedplanner.memo.algorithm=EXACT -Dsysds.fedplanner.memo.tree.searchNodes=2000 -Dsysds.fedplanner.memo.tree.factorCells=256 -Dsysds.fedplanner.memo.tree.cacheEntries=4096 -Dsysds.fedplanner.memo.tree.frontierNodes=10000 -cp "$CP" org.junit.runner.JUnitCore org.apache.sysds.hops.fedplanner.memo.MemoPlannerFederatedRuntimeTest org.apache.sysds.hops.fedplanner.memo.MemoTreeDpTrainingRuntimeTest org.apache.sysds.hops.fedplanner.memo.MemoSharedSupplyRuntimeTest org.apache.sysds.hops.fedplanner.memo.MemoMultiOutputBuiltinTest
