#!/usr/bin/env bash
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../../.." && pwd)
old_engine=${OLD_ENGINE:-/grid/3/cofee-lm-sweep-mchoi-20260914/rule-directed-generation-20261008/engine}
new_engine=${NEW_ENGINE:-/grid/3/cofee-lm-sweep-mchoi-20260914/factorized-plan-space-20261008/engine}
domain=${DOMAIN:-4000}
warmups=${WARMUPS:-2}
repeats=${REPEATS:-5}
classes="$here/classes"
results="$here/results"
source="$here/FunctionalMapSparseBenchmark.java"
main=org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.FunctionalMapSparseBenchmark

mkdir -p "$classes" "$results"
test -f "$old_engine/classes/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.class"
test -f "$new_engine/classes/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/ExactCategoricalSolver.class"

# Compile once against OLD. Both executions below load this exact benchmark class file.
javac -cp "$old_engine/classes:$repo/target/lib/*" -d "$classes" "$source"
sha256sum "$classes/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/FunctionalMapSparseBenchmark.class" \
	> "$results/benchmark-class.sha256"
printf 'domain=%s\nwarmups=%s\nrepeats=%s\noldEngine=%s\nnewEngine=%s\n' \
	"$domain" "$warmups" "$repeats" "$old_engine" "$new_engine" > "$results/run-config.txt"

run_engine() {
	local label=$1
	local engine=$2
	/usr/bin/time -f 'peakRssKb=%M elapsedSeconds=%e' -o "$results/$label.time" \
		java -Xms256m -Xmx2g -cp "$classes:$engine/classes:$repo/target/lib/*" \
		"$main" "$label" "$domain" "$warmups" "$repeats" \
		| tee "$results/$label.txt"
}

run_engine OLD "$old_engine"
run_engine NEW "$new_engine"

cat "$results/OLD.txt" "$results/OLD.time" "$results/NEW.txt" "$results/NEW.time" \
	> "$results/summary.txt"
cat "$results/summary.txt"
