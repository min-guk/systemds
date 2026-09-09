#!/usr/bin/env bash
# After the structural isolated retry completes, replay only targets that still
# report TARGET_NOT_REACHED with the v3 semantic occurrence fallback. The v2
# and v3 source trees and outputs remain separate and immutable.
set -euo pipefail

if (( $# != 5 )); then
	echo "usage: $0 V2_WATCHER_PID V2_RETRY_OUT V3_ROOT V3_FULL_MANIFEST V3_RETRY_OUT" >&2
	exit 64
fi

V2_WATCHER_PID="$1"
V2_RETRY_OUT="$2"
V3_ROOT="$3"
V3_FULL_MANIFEST="$4"
V3_RETRY_OUT="$5"
POLL_SECONDS="${POLL_SECONDS:-60}"

if ! [[ "$V2_WATCHER_PID" =~ ^[1-9][0-9]*$ && "$POLL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
	echo "V2_WATCHER_PID and POLL_SECONDS must be positive integers" >&2
	exit 64
fi
if [[ ! -d "$V3_ROOT" || ! -f "$V3_FULL_MANIFEST" ]]; then
	echo "missing v3 source root or full manifest" >&2
	exit 66
fi
if [[ -e "$V3_RETRY_OUT" || -e "$V3_RETRY_OUT.plan" ]]; then
	echo "refusing to reuse v3 retry output or plan" >&2
	exit 73
fi

while kill -0 "$V2_WATCHER_PID" 2>/dev/null; do
	sleep "$POLL_SECONDS"
done

v2_summary="$V2_RETRY_OUT/CAMPAIGN_SUMMARY.json"
v2_watcher_manifest="$V2_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
full_manifest_sha256=$(sha256sum "$V3_FULL_MANIFEST" | cut -d' ' -f1)
if [[ -f "$v2_watcher_manifest" ]]; then
	v2_full_manifest_sha256=$(sed -n 's/^full_manifest_sha256=//p' \
		"$v2_watcher_manifest" | tail -n 1)
	if [[ "$v2_full_manifest_sha256" != "$full_manifest_sha256" ]]; then
		echo "semantic retry full manifest differs from isolated retry authority" >&2
		exit 65
	fi
fi
if [[ ! -f "$v2_summary" ]]; then
	if [[ -f "$v2_watcher_manifest" ]] \
		&& grep -qx 'status=NO_RETRY_REQUIRED' "$v2_watcher_manifest"; then
		mkdir -p "$V3_RETRY_OUT.plan"
		cat > "$V3_RETRY_OUT.plan/RETRY_PLAN.json" <<'EOF'
{
  "schema": "fedplanner-forced-semantic-retry-plan-v1",
  "status": "NO_STRUCTURAL_RETRY_REQUIRED",
  "retryTargets": 0
}
EOF
		{
			echo "schema=fedplanner-forced-semantic-retry-v1"
			echo "v2_watcher_pid=$V2_WATCHER_PID"
			echo "v2_retry_out=$V2_RETRY_OUT"
			echo "full_manifest_sha256=$full_manifest_sha256"
			echo "executed_subset_manifest_sha256=$(printf '' | sha256sum | cut -d' ' -f1)"
			echo "primary_source_manifest_sha256=$(sed -n 's/^primary_source_manifest_sha256=//p' "$v2_watcher_manifest" | tail -n 1)"
			echo "status=NO_RETRY_REQUIRED"
			echo "finished=$(date -Is)"
		} > "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
		exit 0
	fi
	echo "v2 isolated watcher exited without a campaign summary" >&2
	exit 1
fi

mkdir -p "$V3_RETRY_OUT.plan"
python3 - "$V3_FULL_MANIFEST" "$V2_RETRY_OUT" "$v2_watcher_manifest" \
	"$V3_RETRY_OUT.plan/retry-manifest.jsonl" \
	> "$V3_RETRY_OUT.plan/RETRY_PLAN.json" <<'PY'
import collections
import hashlib
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
v2_retry = pathlib.Path(sys.argv[2])
v2_watcher_path = pathlib.Path(sys.argv[3])
retry_manifest = pathlib.Path(sys.argv[4])

def key_values(path):
	result = {}
	for line in path.read_text(encoding="utf-8").splitlines():
		key, separator, value = line.partition("=")
		if separator:
			result[key] = value
	return result

v2_watcher = key_values(v2_watcher_path)
v2_run = key_values(v2_retry / "RUN_MANIFEST.txt")
full_manifest_sha256 = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
if v2_watcher.get("full_manifest_sha256") != full_manifest_sha256:
	raise SystemExit("semantic retry full manifest differs from isolated retry authority")
if v2_run.get("manifest_sha256") != v2_watcher.get("executed_subset_manifest_sha256"):
	raise SystemExit("isolated retry actual manifest digest differs from its execution plan")
primary_source_sha256 = v2_watcher.get("primary_source_manifest_sha256", "")
if v2_run.get("source_manifest_sha256") != primary_source_sha256:
	raise SystemExit("isolated retry source manifest digest differs from primary")

summary = json.loads((v2_retry / "CAMPAIGN_SUMMARY.json").read_text(encoding="utf-8"))
if summary.get("infrastructureStatus") != "PASS":
	raise SystemExit("v2 isolated retry infrastructure status is not PASS")

results = []
for path in sorted(v2_retry.rglob("forced-state-results-*.jsonl")):
	for line in path.read_text(encoding="utf-8").splitlines():
		if line.strip():
			results.append(json.loads(line))
if len(results) != summary.get("resultRows"):
	raise SystemExit("v2 isolated result count does not match its campaign summary")
result_ids = [str(row.get("targetId")) for row in results]
if any(not target_id for target_id in result_ids) or len(result_ids) != len(set(result_ids)):
	raise SystemExit("v2 isolated results contain missing or duplicate target IDs")

persistent = [row for row in results if row.get("outcome") == "TARGET_NOT_REACHED"]
persistent_ids = [str(row.get("targetId")) for row in persistent]
if len(persistent_ids) != len(set(persistent_ids)):
	raise SystemExit("duplicate persistent TARGET_NOT_REACHED target IDs")
retry_ids = set(persistent_ids)

manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines()
	if line.strip()]
manifest_ids = [str(row.get("targetId")) for row in manifest]
if len(manifest_ids) != len(set(manifest_ids)):
	raise SystemExit("duplicate target IDs in the authoritative v3 manifest")
selected = [row for row in manifest if str(row.get("targetId")) in retry_ids]
selected_ids = {str(row.get("targetId")) for row in selected}
if selected_ids != retry_ids or len(selected) != len(retry_ids):
	raise SystemExit("persistent target IDs do not map one-to-one to the v3 manifest")
if any(not str(row.get("semanticOccurrenceKeyHash", "")).strip() for row in selected):
	raise SystemExit("v3 retry target is missing semanticOccurrenceKeyHash")

retry_manifest.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in selected),
	encoding="utf-8")
executed_subset_sha256 = hashlib.sha256(retry_manifest.read_bytes()).hexdigest()
print(json.dumps({
	"schema": "fedplanner-forced-semantic-retry-plan-v1",
	"full_manifest_sha256": full_manifest_sha256,
	"executed_subset_manifest_sha256": executed_subset_sha256,
	"primary_source_manifest_sha256": primary_source_sha256,
	"v2IsolatedResults": len(results),
	"retryTargets": len(selected),
	"v2IsolatedOutcomes": dict(sorted(collections.Counter(
		str(row.get("outcome")) for row in results).items())),
	"retryTargetIds": sorted(retry_ids),
	"uniqueSemanticOccurrenceKeys": len({row["semanticOccurrenceKeyHash"] for row in selected}),
}, indent=2, sort_keys=True))
PY

retry_targets=$(wc -l < "$V3_RETRY_OUT.plan/retry-manifest.jsonl")
{
	echo "schema=fedplanner-forced-semantic-retry-v1"
	echo "v2_watcher_pid=$V2_WATCHER_PID"
	echo "v2_retry_out=$V2_RETRY_OUT"
	echo "v3_root=$V3_ROOT"
	echo "full_manifest_sha256=$full_manifest_sha256"
	echo "executed_subset_manifest_sha256=$(sha256sum "$V3_RETRY_OUT.plan/retry-manifest.jsonl" | cut -d' ' -f1)"
	echo "primary_source_manifest_sha256=$(sed -n 's/^primary_source_manifest_sha256=//p' "$v2_watcher_manifest" | tail -n 1)"
	echo "retry_targets=$retry_targets"
	echo "started=$(date -Is)"
} > "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"

if (( retry_targets == 0 )); then
	echo "status=NO_RETRY_REQUIRED" >> "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
	echo "finished=$(date -Is)" >> "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
	exit 0
fi

EXPECTED_FORCED_MANIFEST_SHA256="$(sha256sum "$V3_RETRY_OUT.plan/retry-manifest.jsonl" | cut -d' ' -f1)" \
	TARGETS_PER_JVM=1 "$V3_ROOT/scripts/fedplanner/run_forced_state_campaign.sh" \
	"$V3_ROOT" "$V3_RETRY_OUT.plan/retry-manifest.jsonl" "$V3_RETRY_OUT" 0 1

python3 - "$v2_watcher_manifest" "$V3_RETRY_OUT/RUN_MANIFEST.txt" \
	"$V3_RETRY_OUT.plan/retry-manifest.jsonl" <<'PY'
import hashlib, pathlib, sys
def values(path):
	result = {}
	for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
		key, separator, value = line.partition("=")
		if separator:
			result[key] = value
	return result
watcher, retry = values(sys.argv[1]), values(sys.argv[2])
expected_subset = hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest()
if retry.get("source_manifest_sha256") != watcher.get("primary_source_manifest_sha256"):
	raise SystemExit("semantic retry source manifest digest differs from primary")
if retry.get("manifest_sha256") != expected_subset:
	raise SystemExit("semantic retry campaign manifest digest differs from executed subset")
PY

python3 - "$V3_RETRY_OUT/CAMPAIGN_SUMMARY.json" \
	"$V3_RETRY_OUT.plan/SEMANTIC_RETRY_SUMMARY.json" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
if not source.is_file():
	raise SystemExit("semantic retry exited without CAMPAIGN_SUMMARY.json")
summary = json.loads(source.read_text(encoding="utf-8"))
if summary.get("infrastructureStatus") != "PASS":
	raise SystemExit("semantic retry infrastructure status is not PASS")
destination.write_text(json.dumps({
	"schema": "fedplanner-forced-semantic-retry-summary-v1",
	"infrastructureStatus": summary.get("infrastructureStatus"),
	"expectedTargets": summary.get("expectedTargets"),
	"resultRows": summary.get("resultRows"),
	"resultOutcomes": summary.get("resultOutcomes"),
	"constraintSatisfied": summary.get("constraintSatisfied"),
	"runtimeCapabilityOutcomes": summary.get("runtimeCapabilityOutcomes"),
}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

echo "status=SEMANTIC_RETRY_COMPLETE" >> "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
echo "finished=$(date -Is)" >> "$V3_RETRY_OUT.plan/WATCHER_MANIFEST.txt"
