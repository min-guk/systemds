#!/usr/bin/env bash
# Wait for one primary forced-state shard, then replay every non-success target
# in its own Maven/Surefire JVM. The primary and retry outputs remain separate.
set -euo pipefail

if (( $# != 5 )); then
	echo "usage: $0 PRIMARY_PID ROOT FULL_MANIFEST PRIMARY_OUT RETRY_OUT" >&2
	exit 64
fi

PRIMARY_PID="$1"
ROOT="$2"
FULL_MANIFEST="$3"
PRIMARY_OUT="$4"
RETRY_OUT="$5"
POLL_SECONDS="${POLL_SECONDS:-60}"

if ! [[ "$PRIMARY_PID" =~ ^[1-9][0-9]*$ && "$POLL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
	echo "PRIMARY_PID and POLL_SECONDS must be positive integers" >&2
	exit 64
fi
if [[ ! -d "$ROOT" || ! -f "$FULL_MANIFEST" ]]; then
	echo "missing source root or full manifest" >&2
	exit 66
fi
if [[ -e "$RETRY_OUT" || -e "$RETRY_OUT.plan" ]]; then
	echo "refusing to reuse retry output or plan" >&2
	exit 73
fi

while kill -0 "$PRIMARY_PID" 2>/dev/null; do
	sleep "$POLL_SECONDS"
done

if [[ ! -f "$PRIMARY_OUT/CAMPAIGN_SUMMARY.json" ]]; then
	echo "primary campaign exited without CAMPAIGN_SUMMARY.json" >&2
	exit 1
fi

mkdir -p "$RETRY_OUT.plan"
python3 - "$FULL_MANIFEST" "$PRIMARY_OUT" "$RETRY_OUT.plan/retry-manifest.jsonl" \
	> "$RETRY_OUT.plan/RETRY_PLAN.json" <<'PY'
import collections
import hashlib
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
primary = pathlib.Path(sys.argv[2])
retry_manifest = pathlib.Path(sys.argv[3])
summary = json.loads((primary / "CAMPAIGN_SUMMARY.json").read_text(encoding="utf-8"))
if summary.get("infrastructureStatus") != "PASS":
	raise SystemExit("primary campaign infrastructure status is not PASS")
run_manifest = {}
for line in (primary / "RUN_MANIFEST.txt").read_text(encoding="utf-8").splitlines():
	key, separator, value = line.partition("=")
	if separator:
		run_manifest[key] = value
full_manifest_sha256 = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
if run_manifest.get("manifest_sha256") != full_manifest_sha256:
	raise SystemExit("primary campaign was not executed from the supplied full manifest")
primary_source_sha256 = run_manifest.get("source_manifest_sha256", "")
if len(primary_source_sha256) != 64:
	raise SystemExit("primary campaign has no valid source manifest digest")

results = []
for path in sorted(primary.rglob("forced-state-results-*.jsonl")):
	for line in path.read_text(encoding="utf-8").splitlines():
		if line.strip():
			results.append(json.loads(line))
if len(results) != summary.get("resultRows"):
	raise SystemExit("primary result count does not match its campaign summary")
result_ids = [str(row.get("targetId")) for row in results]
if any(not target_id for target_id in result_ids) or len(result_ids) != len(set(result_ids)):
	raise SystemExit("primary results contain missing or duplicate target IDs")
retry_rows = [row for row in results if row.get("outcome") != "SUCCESS"]
retry_ids = {str(row.get("targetId")) for row in retry_rows}
manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines()
	if line.strip()]
manifest_ids = [str(row.get("targetId")) for row in manifest]
if len(manifest_ids) != len(set(manifest_ids)):
	raise SystemExit("duplicate target IDs in the authoritative full manifest")
if not set(result_ids).issubset(set(manifest_ids)):
	raise SystemExit("primary result target IDs are not a subset of the authoritative full manifest")
selected = [row for row in manifest if str(row.get("targetId")) in retry_ids]
selected_ids = {str(row.get("targetId")) for row in selected}
if selected_ids != retry_ids or len(selected) != len(retry_ids):
	raise SystemExit("retry target IDs do not map one-to-one to the authoritative manifest")
retry_manifest.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in selected),
	encoding="utf-8")
executed_subset_sha256 = hashlib.sha256(retry_manifest.read_bytes()).hexdigest()
print(json.dumps({
	"schema": "fedplanner-forced-isolated-retry-plan-v1",
	"full_manifest_sha256": full_manifest_sha256,
	"executed_subset_manifest_sha256": executed_subset_sha256,
	"primary_source_manifest_sha256": primary_source_sha256,
	"primaryResults": len(results),
	"retryTargets": len(selected),
	"primaryOutcomes": dict(sorted(collections.Counter(
		str(row.get("outcome")) for row in results).items())),
	"retryTargetIds": sorted(retry_ids),
}, indent=2, sort_keys=True))
PY

retry_targets=$(wc -l < "$RETRY_OUT.plan/retry-manifest.jsonl")
{
	echo "schema=fedplanner-forced-isolated-retry-v1"
	echo "primary_pid=$PRIMARY_PID"
	echo "primary_out=$PRIMARY_OUT"
	echo "full_manifest_sha256=$(sha256sum "$FULL_MANIFEST" | cut -d' ' -f1)"
	echo "executed_subset_manifest_sha256=$(sha256sum "$RETRY_OUT.plan/retry-manifest.jsonl" | cut -d' ' -f1)"
	echo "primary_source_manifest_sha256=$(sed -n 's/^source_manifest_sha256=//p' "$PRIMARY_OUT/RUN_MANIFEST.txt" | tail -n 1)"
	echo "retry_targets=$retry_targets"
	echo "started=$(date -Is)"
} > "$RETRY_OUT.plan/WATCHER_MANIFEST.txt"

if (( retry_targets == 0 )); then
	echo "status=NO_RETRY_REQUIRED" >> "$RETRY_OUT.plan/WATCHER_MANIFEST.txt"
	echo "finished=$(date -Is)" >> "$RETRY_OUT.plan/WATCHER_MANIFEST.txt"
	exit 0
fi

EXPECTED_FORCED_MANIFEST_SHA256="$(sha256sum "$RETRY_OUT.plan/retry-manifest.jsonl" | cut -d' ' -f1)" \
	TARGETS_PER_JVM=1 "$ROOT/scripts/fedplanner/run_forced_state_campaign.sh" \
	"$ROOT" "$RETRY_OUT.plan/retry-manifest.jsonl" "$RETRY_OUT" 0 1
python3 - "$PRIMARY_OUT/RUN_MANIFEST.txt" "$RETRY_OUT/RUN_MANIFEST.txt" \
	"$RETRY_OUT.plan/retry-manifest.jsonl" <<'PY'
import hashlib, pathlib, sys
def values(path):
	result = {}
	for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
		key, separator, value = line.partition("=")
		if separator:
			result[key] = value
	return result
primary, retry = values(sys.argv[1]), values(sys.argv[2])
expected_subset = hashlib.sha256(pathlib.Path(sys.argv[3]).read_bytes()).hexdigest()
if retry.get("source_manifest_sha256") != primary.get("source_manifest_sha256"):
	raise SystemExit("isolated retry source manifest digest differs from primary")
if retry.get("manifest_sha256") != expected_subset:
	raise SystemExit("isolated retry campaign manifest digest differs from executed subset")
PY
echo "status=RETRY_COMPLETE" >> "$RETRY_OUT.plan/WATCHER_MANIFEST.txt"
echo "finished=$(date -Is)" >> "$RETRY_OUT.plan/WATCHER_MANIFEST.txt"
