#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
old_engine=${OLD_ENGINE:-/grid/3/cofee-lm-sweep-mchoi-20260914/rule-directed-generation-20261008/engine}
new_engine=${NEW_ENGINE:-/grid/3/cofee-lm-sweep-mchoi-20260914/factorized-plan-space-20261008/engine}
dependency_libs=${DEPENDENCY_LIBS:-/home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target/lib}
warmups=${WARMUPS:-1}
repeats=${REPEATS:-3}
classes="$here/production-classes"
results="$here/production-results"
source="$here/ProductionRealizationSupportBenchmark.java"
main=org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.ProductionRealizationSupportBenchmark

mkdir -p "$classes" "$results"
javac -cp "$old_engine/classes:$dependency_libs/*" \
	-d "$classes" "$source"
sha256sum "$classes/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ProductionRealizationSupportBenchmark.class" \
	> "$results/benchmark-class.sha256"

run_engine() {
	local label=$1
	local engine=$2
	/usr/bin/time -f 'peakRssKb=%M elapsedSeconds=%e' -o "$results/$label.time" \
		java --add-modules jdk.incubator.vector -Xms256m -Xmx2g \
		-cp "$classes:$engine/classes:$dependency_libs/*" \
		"$main" "$label" "$warmups" "$repeats" | tee "$results/$label.txt"
}

run_engine OLD "$old_engine"
run_engine NEW "$new_engine"
cat "$results/OLD.txt" "$results/OLD.time" "$results/NEW.txt" "$results/NEW.time" \
	> "$results/summary.txt"
cat "$results/summary.txt"
