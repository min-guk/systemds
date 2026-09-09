#!/usr/bin/env bash
# Wait for a complete proxy-free semantic-watcher shard group, collect compact authoritative
# evidence, and run the stage-aware forced-state aggregator locally.
set -euo pipefail

verify_complete_checksum_tree() {
	local directory="$1"
	local checksum_name="${2:-SHA256SUMS.txt}"
	python3 - "$directory" "$checksum_name" <<'PY'
import hashlib, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
checksum_name = sys.argv[2]
if pathlib.Path(checksum_name).name != checksum_name or checksum_name in {"", ".", ".."}:
	raise SystemExit(f"unsafe checksum filename: {checksum_name!r}")
checksum = root / checksum_name
if not checksum.is_file():
	raise SystemExit(f"missing {checksum_name}: {root}")
symlinks = sorted(path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_symlink())
if symlinks:
	raise SystemExit(f"checksum tree contains symlinks: {symlinks[:20]} ({len(symlinks)})")
nonregular = sorted(path.relative_to(root).as_posix() for path in root.rglob("*")
	if not path.is_symlink() and not path.is_dir() and not path.is_file())
if nonregular:
	raise SystemExit(f"checksum tree contains non-regular entries: {nonregular[:20]} ({len(nonregular)})")
actual = {path.relative_to(root).as_posix() for path in root.rglob("*")
	if path.is_file() and not path.is_symlink() and path != checksum}
if not actual:
	raise SystemExit(f"checksum tree contains no regular artifact files: {root}")
lines = checksum.read_text(encoding="utf-8").splitlines()
if not lines:
	raise SystemExit(f"empty SHA256SUMS.txt: {root}")
listed = set()
for line_number, line in enumerate(lines, 1):
	digest, separator, relative = line.partition("  ")
	if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or not relative:
		raise SystemExit(f"invalid checksum entry {checksum}:{line_number}")
	path = pathlib.Path(relative.removeprefix("./"))
	normalized = path.as_posix()
	if path.is_absolute() or ".." in path.parts or normalized in {"", ".", checksum_name}:
		raise SystemExit(f"unsafe checksum path {checksum}:{line_number}")
	if normalized in listed:
		raise SystemExit(f"duplicate checksum path {checksum}:{line_number}: {normalized}")
	listed.add(normalized)
	artifact = root / path
	if not artifact.is_file() or artifact.is_symlink() \
		or hashlib.sha256(artifact.read_bytes()).hexdigest() != digest:
		raise SystemExit(f"checksum mismatch for {artifact}")
if listed != actual:
	missing, unexpected = sorted(actual - listed), sorted(listed - actual)
	raise SystemExit(f"checksum path-set mismatch for {root}: unlisted={missing[:20]} ({len(missing)}), "
		f"nonregular_or_missing={unexpected[:20]} ({len(unexpected)})")
PY
}

if (( $# < 6 || ($# - 3) % 3 != 0 )); then
	echo "usage: $0 LOCAL_OUT MANIFEST REMOTE_BASE (HOST SHARD V3_WATCHER_PID)+" >&2
	exit 64
fi

LOCAL_OUT="$1"
MANIFEST="$2"
REMOTE_BASE="$3"
shift 3
POLL_SECONDS="${POLL_SECONDS:-60}"
SCRIPT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AGGREGATOR="$SCRIPT_ROOT/scripts/fedplanner/aggregate_forced_state_results.py"
EXPECTED_SOURCE_RECEIPT_SHA256="${EXPECTED_SOURCE_RECEIPT_SHA256:-}"
EXPECTED_FORCED_MANIFEST_SHA256="${EXPECTED_FORCED_MANIFEST_SHA256:-}"

if [[ -e "$LOCAL_OUT" ]]; then
	echo "refusing to reuse local final output: $LOCAL_OUT" >&2
	exit 73
fi
if [[ ! -f "$MANIFEST" || ! -x "$AGGREGATOR" ]]; then
	echo "missing manifest or aggregator" >&2
	exit 66
fi
if [[ ! "$EXPECTED_SOURCE_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ \
	|| ! "$EXPECTED_FORCED_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
	echo "EXPECTED_SOURCE_RECEIPT_SHA256 and EXPECTED_FORCED_MANIFEST_SHA256 are required" >&2
	exit 64
fi
if [[ "$(sha256sum "$MANIFEST" | cut -d' ' -f1)" != "$EXPECTED_FORCED_MANIFEST_SHA256" ]]; then
	echo "forced manifest digest differs from caller authority" >&2
	exit 65
fi
if ! [[ "$POLL_SECONDS" =~ ^[1-9][0-9]*$ ]]; then
	echo "POLL_SECONDS must be a positive integer" >&2
	exit 64
fi

hosts=()
shards=()
watchers=()
while (( $# )); do
	hosts+=("$1")
	shards+=("$2")
	watchers+=("$3")
	shift 3
done

for index in "${!hosts[@]}"; do
	host="${hosts[$index]}"
	shard="${shards[$index]}"
	watcher="${watchers[$index]}"
	if [[ ! "$host" =~ ^so00[2-6]$ || "$host" == "so001" ]]; then
		echo "host is outside the proxy-free allow-list: $host" >&2
		exit 64
	fi
	if ! [[ "$shard" =~ ^[0-9]+$ && "$watcher" =~ ^[1-9][0-9]*$ ]]; then
		echo "invalid shard or watcher PID for $host" >&2
		exit 64
	fi
done

declare -A seen_hosts=()
declare -A seen_shards=()
for index in "${!hosts[@]}"; do
	host="${hosts[$index]}"
	shard="${shards[$index]}"
	if [[ -n "${seen_hosts[$host]:-}" ]]; then
		echo "duplicate remote host: $host" >&2
		exit 64
	fi
	if [[ -n "${seen_shards[$shard]:-}" ]]; then
		echo "duplicate shard index: $shard" >&2
		exit 64
	fi
	seen_hosts[$host]=1
	seen_shards[$shard]=1
done
shard_count=${#shards[@]}
for (( shard = 0; shard < shard_count; shard++ )); do
	if [[ -z "${seen_shards[$shard]:-}" ]]; then
		echo "primary shard topology must contain exactly shards 0..$((shard_count - 1))" >&2
		exit 64
	fi
done

echo "waiting_started=$(date -Is)"
while true; do
	all_finished=true
	for index in "${!hosts[@]}"; do
		host="${hosts[$index]}"
		watcher="${watchers[$index]}"
		if ! command_line=$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" \
			"ps -p '$watcher' -o args= 2>/dev/null || true"); then
			all_finished=false
			continue
		fi
		if [[ "$command_line" == *run_semantic_retry_after_isolated.sh* ]]; then
			all_finished=false
		fi
	done
	if $all_finished; then
		break
	fi
	sleep "$POLL_SECONDS"
done
echo "watchers_finished=$(date -Is)"

mkdir -p "$LOCAL_OUT/raw" "$LOCAL_OUT/plans"
cp "$MANIFEST" "$LOCAL_OUT/INPUT_MANIFEST.jsonl"

copy_stage() {
	local host="$1"
	local remote="$2"
	local destination="$3"
	mkdir -p "$destination"
	{
		declare -f verify_complete_checksum_tree
		printf 'verify_complete_checksum_tree %q\n' "$remote"
	} | ssh -o BatchMode=yes "$host" bash -s
	ssh -o BatchMode=yes "$host" "set -euo pipefail; cd '$remote';
		test -f SHA256SUMS.txt;
		sha256sum -c SHA256SUMS.txt >/dev/null;
		tar -cf - ." | tar -xf - -C "$destination"
	verify_complete_checksum_tree "$destination"
}

copy_tree() {
	local host="$1"
	local remote="$2"
	local destination="$3"
	mkdir -p "$destination"
	ssh -o BatchMode=yes "$host" "set -euo pipefail; cd '$remote'; tar -cf - ." \
		| tar -xf - -C "$destination"
}

primary_args=()
isolated_args=()
semantic_args=()
allowed_args=()
host_receipts=()

for index in "${!hosts[@]}"; do
	host="${hosts[$index]}"
	shard="${shards[$index]}"
	remote_primary="$REMOTE_BASE/shard-$shard-of-$shard_count"
	remote_isolated="$REMOTE_BASE/retry-isolated-shard-$shard-of-$shard_count"
	remote_semantic="$REMOTE_BASE/retry-semantic-v3-shard-$shard-of-$shard_count"
	remote_isolated_plan="$remote_isolated.plan"
	remote_semantic_plan="$remote_semantic.plan"

	semantic_status=$(ssh -o BatchMode=yes "$host" \
		"test -f '$remote_semantic_plan/WATCHER_MANIFEST.txt' && sed -n 's/^status=//p' '$remote_semantic_plan/WATCHER_MANIFEST.txt' | tail -n 1")
	if [[ "$semantic_status" != "SEMANTIC_RETRY_COMPLETE" \
		&& "$semantic_status" != "NO_RETRY_REQUIRED" ]]; then
		echo "semantic watcher did not finish cleanly on $host: $semantic_status" >&2
		exit 1
	fi

	local_primary="$LOCAL_OUT/raw/$host/primary"
	copy_stage "$host" "$remote_primary" "$local_primary"
	primary_args+=(--primary "$local_primary")

	if ssh -o BatchMode=yes "$host" "test -d '$remote_isolated'"; then
		local_isolated="$LOCAL_OUT/raw/$host/isolated"
		copy_stage "$host" "$remote_isolated" "$local_isolated"
		isolated_args+=(--isolated "$local_isolated")
	fi
	if ssh -o BatchMode=yes "$host" "test -d '$remote_semantic'"; then
		local_semantic="$LOCAL_OUT/raw/$host/semantic"
		copy_stage "$host" "$remote_semantic" "$local_semantic"
		semantic_args+=(--semantic "$local_semantic")
	fi

	copy_tree "$host" "$remote_isolated_plan" "$LOCAL_OUT/plans/$host/isolated"
	copy_tree "$host" "$remote_semantic_plan" "$LOCAL_OUT/plans/$host/semantic"
	remote_hostname=$(ssh -o BatchMode=yes "$host" hostname)
	allowed_args+=(--allowed-host "$remote_hostname")
	host_receipts+=("$host:$shard:$remote_hostname:$semantic_status")
done

"$AGGREGATOR" \
	--manifest "$LOCAL_OUT/INPUT_MANIFEST.jsonl" \
	--expected-manifest-sha256 "$EXPECTED_FORCED_MANIFEST_SHA256" \
	--expected-source-receipt-sha256 "$EXPECTED_SOURCE_RECEIPT_SHA256" \
	"${primary_args[@]}" \
	"${isolated_args[@]}" \
	"${semantic_args[@]}" \
	"${allowed_args[@]}" \
	--out-dir "$LOCAL_OUT/aggregate" \
	> "$LOCAL_OUT/AGGREGATOR_STDOUT.json"

python3 - "$LOCAL_OUT" "${host_receipts[@]}" <<'PY'
import datetime
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
summary = json.loads((root / "aggregate/SUMMARY.json").read_text(encoding="utf-8"))
hosts = []
for value in sys.argv[2:]:
	host, shard, hostname, status = value.split(":", 3)
	hosts.append({"sshHost": host, "shard": int(shard), "hostname": hostname,
		"semanticWatcherStatus": status})
status = {
	"schema": "fedplanner-remote-finalizer-status-v1",
	"finishedAt": datetime.datetime.now().astimezone().isoformat(),
	"validationStatus": summary["validationStatus"],
	"manifestTargets": summary["manifestTargets"],
	"finalOutcomes": summary["finalOutcomes"],
	"finalClassifications": summary["finalClassifications"],
	"unresolvedTargets": summary["unresolvedTargets"],
	"exactDiscoveryAuthority": summary["exactDiscoveryAuthority"],
	"hosts": hosts,
}
(root / "FINALIZER_STATUS.json").write_text(
	json.dumps(status, indent=2, sort_keys=True) + "\n", encoding="utf-8")
(root / "FINAL_REPORT.md").write_text(
	"# Forced-state authoritative aggregation\n\n"
	+ f"- Finished: {status['finishedAt']}\n"
	+ f"- Validation: {status['validationStatus']}\n"
	+ f"- Manifest targets: {status['manifestTargets']}\n"
	+ f"- Final outcomes: `{json.dumps(status['finalOutcomes'], sort_keys=True)}`\n"
	+ f"- Final classifications: `{json.dumps(status['finalClassifications'], sort_keys=True)}`\n"
	+ f"- Exact discovery authority: `{json.dumps(status['exactDiscoveryAuthority'], sort_keys=True)}`\n"
	+ f"- Unresolved targets: {status['unresolvedTargets']}\n\n"
	+ "This artifact aggregates only the proxy-free hosts listed in FINALIZER_STATUS.json. "
	+ "It does not infer Missing states from forced published-state results.\n",
	encoding="utf-8")
PY

(
	cd "$LOCAL_OUT"
	find . -type f ! -name ARTIFACT_SHA256SUMS.txt -print0 \
		| sort -z | xargs -0 sha256sum > ARTIFACT_SHA256SUMS.txt
)
verify_complete_checksum_tree "$LOCAL_OUT" ARTIFACT_SHA256SUMS.txt
echo "finalized=$(date -Is)"
cat "$LOCAL_OUT/FINALIZER_STATUS.json"
