#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/fedplanner/finalize_remote_forced_state_campaign.sh"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
printf '{"targetId":"t1"}\n' > "$TEMP/manifest.jsonl"
EXPECTED_SOURCE=$(printf 'a%.0s' {1..64})
EXPECTED_MANIFEST=$(sha256sum "$TEMP/manifest.jsonl" | cut -d' ' -f1)
FINALIZER=(env "EXPECTED_SOURCE_RECEIPT_SHA256=$EXPECTED_SOURCE"
	"EXPECTED_FORCED_MANIFEST_SHA256=$EXPECTED_MANIFEST" "$SCRIPT")

# Exercise the exact verifier used after transfer without initiating SSH.
eval "$(sed -n '/^verify_complete_checksum_tree()/,/^}/p' "$SCRIPT")"
valid="$TEMP/valid"
mkdir -p "$valid/sub"
printf 'one\n' > "$valid/required.txt"
printf 'two\n' > "$valid/sub/evidence.txt"
(
	cd "$valid"
	find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt
)
verify_complete_checksum_tree "$valid"

# The final artifact seal must cover the exact regular-file path set and reject
# symlinks/non-regular entries that `find -type f` would otherwise omit.
final_valid="$TEMP/final-valid"
mkdir -p "$final_valid/plans"
printf 'status\n' > "$final_valid/FINALIZER_STATUS.json"
printf 'plan\n' > "$final_valid/plans/receipt.txt"
(
	cd "$final_valid"
	find . -type f ! -name ARTIFACT_SHA256SUMS.txt -print0 \
		| sort -z | xargs -0 sha256sum > ARTIFACT_SHA256SUMS.txt
)
verify_complete_checksum_tree "$final_valid" ARTIFACT_SHA256SUMS.txt

final_symlink="$TEMP/final-symlink"
cp -a "$final_valid" "$final_symlink"
ln -s /etc/passwd "$final_symlink/plans/unsealed-link"
if verify_complete_checksum_tree "$final_symlink" ARTIFACT_SHA256SUMS.txt \
		>"$TEMP/final-symlink.log" 2>&1; then
	echo "final artifact symlink unexpectedly passed" >&2
	exit 1
fi
grep -q 'contains symlinks' "$TEMP/final-symlink.log"

final_unlisted="$TEMP/final-unlisted"
cp -a "$final_valid" "$final_unlisted"
printf 'injected\n' > "$final_unlisted/plans/unlisted.txt"
if verify_complete_checksum_tree "$final_unlisted" ARTIFACT_SHA256SUMS.txt \
		>"$TEMP/final-unlisted.log" 2>&1; then
	echo "final artifact unlisted file unexpectedly passed" >&2
	exit 1
fi
grep -q 'checksum path-set mismatch' "$TEMP/final-unlisted.log"

for case in empty partial duplicate unlisted symlink; do
	tree="$TEMP/$case"
	cp -a "$valid" "$tree"
	case "$case" in
		empty) : > "$tree/SHA256SUMS.txt" ;;
		partial) sed -i '/required.txt/d' "$tree/SHA256SUMS.txt" ;;
		duplicate) head -n 1 "$tree/SHA256SUMS.txt" >> "$tree/SHA256SUMS.txt" ;;
		unlisted)
			sed -i '/required.txt/d' "$tree/SHA256SUMS.txt"
			printf 'tampered\n' >> "$tree/required.txt"
			;;
		symlink) ln -s required.txt "$tree/authority-link" ;;
	esac
	if verify_complete_checksum_tree "$tree" >"$TEMP/$case.log" 2>&1; then
		echo "incomplete checksum case unexpectedly passed: $case" >&2
		exit 1
	fi
done

set +e
output=$("${FINALIZER[@]}" "$TEMP/out" "$TEMP/manifest.jsonl" /remote/base \
	so002 0 1 so003 0 2 so004 2 3 2>&1)
rc=$?
set -e
(( rc == 64 ))
[[ "$output" == *"duplicate shard index"* ]]
[[ ! -e "$TEMP/out" ]]

# Both legacy 3-way and current 4-way complete topologies pass argument
# validation; timeout stops at the intentionally unreachable SSH wait loop.
for groups in \
	"so002 0 9999991 so003 1 9999992 so004 2 9999993" \
	"so003 0 9999981 so004 1 9999982 so005 2 9999983 so006 3 9999984"; do
	set +e
	output=$(timeout 1 env POLL_SECONDS=1 "EXPECTED_SOURCE_RECEIPT_SHA256=$EXPECTED_SOURCE" \
		"EXPECTED_FORCED_MANIFEST_SHA256=$EXPECTED_MANIFEST" "$SCRIPT" "$TEMP/out-valid" \
		"$TEMP/manifest.jsonl" /remote/base $groups 2>&1)
	rc=$?
	set -e
	(( rc == 124 ))
	[[ "$output" == *"waiting_started="* ]]
	[[ "$output" != *"primary shard topology"* ]]
done

grep -q 'shard-\$shard-of-\$shard_count' "$SCRIPT"

set +e
output=$("${FINALIZER[@]}" "$TEMP/out" "$TEMP/manifest.jsonl" /remote/base \
	so002 0 1 so002 1 2 so004 2 3 2>&1)
rc=$?
set -e
(( rc == 64 ))
[[ "$output" == *"duplicate remote host"* ]]
[[ ! -e "$TEMP/out" ]]

set +e
output=$(EXPECTED_SOURCE_RECEIPT_SHA256="$EXPECTED_SOURCE" \
	EXPECTED_FORCED_MANIFEST_SHA256="$(printf 'e%.0s' {1..64})" \
	"$SCRIPT" "$TEMP/out" "$TEMP/manifest.jsonl" /remote/base so002 0 1 2>&1)
rc=$?
set -e
(( rc == 65 ))
[[ "$output" == *"manifest digest differs from caller authority"* ]]

echo "finalize remote forced-state preflight tests: PASS"
