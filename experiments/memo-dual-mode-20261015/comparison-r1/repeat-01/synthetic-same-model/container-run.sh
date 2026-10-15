#!/usr/bin/env bash
set -euo pipefail
CP=/engine/test-classes:/engine/classes:/deps/*
java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 -cp "$CP" org.apache.sysds.hops.fedplanner.memo.MemoTreeDpBenchmark 7
exec java --add-modules jdk.incubator.vector -Xmx3g -XX:ActiveProcessorCount=4 -cp "$CP" org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.MemoTreeDpRegionalBenchmark
