#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
SOURCE_SHA=$(printf 'a%.0s' {1..64})

make_root() {
	local root="$1" emitted_source="$2"
	mkdir -p "$root/scripts/fedplanner" "$root/target"
	cat > "$root/scripts/fedplanner/run_forced_state_campaign.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
manifest="\$2"; out="\$3"
expected=\$(sha256sum "\$manifest" | cut -d' ' -f1)
[[ "\${EXPECTED_FORCED_MANIFEST_SHA256:-}" == "\$expected" ]] || {
	echo "missing/wrong retry forced-manifest authority" >&2; exit 65; }
mkdir -p "\$out/chunk-manifests"
cp "\$manifest" "\$out/chunk-manifests/chunk-00000.jsonl"
digest=\$(sha256sum "\$manifest" | cut -d' ' -f1)
cat > "\$out/RUN_MANIFEST.txt" <<MANIFEST
manifest_sha256=\$digest
source_manifest_sha256=$emitted_source
MANIFEST
cat > "\$out/CAMPAIGN_SUMMARY.json" <<SUMMARY
{"infrastructureStatus":"PASS","resultRows":1}
SUMMARY
EOF
	chmod +x "$root/scripts/fedplanner/run_forced_state_campaign.sh"
}

manifest="$TMP/full.jsonl"
printf '%s\n' '{"schema":"fedplanner-forced-state-manifest-v1","targetId":"t1","semanticOccurrenceKeyHash":"semantic-t1"}' > "$manifest"
full_sha=$(sha256sum "$manifest" | cut -d' ' -f1)

primary="$TMP/primary"
mkdir -p "$primary"
cat > "$primary/CAMPAIGN_SUMMARY.json" <<EOF
{"infrastructureStatus":"PASS","resultRows":1}
EOF
cat > "$primary/RUN_MANIFEST.txt" <<EOF
manifest_sha256=$full_sha
source_manifest_sha256=$SOURCE_SHA
EOF
printf '%s\n' '{"targetId":"t1","outcome":"TARGET_NOT_REACHED"}' > "$primary/forced-state-results-test.jsonl"

good_root="$TMP/good-root"
make_root "$good_root" "$SOURCE_SHA"
POLL_SECONDS=1 "$SCRIPT_DIR/run_isolated_retry_after_campaign.sh" 999999999 \
	"$good_root" "$manifest" "$primary" "$TMP/isolated"
grep -qx "full_manifest_sha256=$full_sha" "$TMP/isolated.plan/WATCHER_MANIFEST.txt"
subset_sha=$(sha256sum "$TMP/isolated.plan/retry-manifest.jsonl" | cut -d' ' -f1)
grep -qx "executed_subset_manifest_sha256=$subset_sha" "$TMP/isolated.plan/WATCHER_MANIFEST.txt"
grep -qx "primary_source_manifest_sha256=$SOURCE_SHA" "$TMP/isolated.plan/WATCHER_MANIFEST.txt"

bad_root="$TMP/bad-root"
make_root "$bad_root" "$(printf 'b%.0s' {1..64})"
if POLL_SECONDS=1 "$SCRIPT_DIR/run_isolated_retry_after_campaign.sh" 999999998 \
	"$bad_root" "$manifest" "$primary" "$TMP/bad-isolated" >"$TMP/bad.log" 2>&1; then
	echo "different-source isolated retry unexpectedly passed" >&2
	exit 1
fi
grep -q "source manifest digest differs from primary" "$TMP/bad.log"

tampered="$TMP/tampered.jsonl"
printf '%s\n' '{"schema":"fedplanner-forced-state-manifest-v1","targetId":"different"}' > "$tampered"
if POLL_SECONDS=1 "$SCRIPT_DIR/run_isolated_retry_after_campaign.sh" 999999997 \
	"$good_root" "$tampered" "$primary" "$TMP/tampered-isolated" >"$TMP/tampered.log" 2>&1; then
	echo "non-authoritative/non-subset retry unexpectedly passed" >&2
	exit 1
fi
grep -Eq "not executed from the supplied full manifest|do not map one-to-one" "$TMP/tampered.log"

# Semantic retry must inherit both full-manifest and source authority from the
# isolated watcher, while executing only the persistent TARGET_NOT_REACHED row.
cp "$TMP/isolated.plan/retry-manifest.jsonl" "$manifest"
full_sha=$(sha256sum "$manifest" | cut -d' ' -f1)
sed -i "s/^full_manifest_sha256=.*/full_manifest_sha256=$full_sha/" \
	"$TMP/isolated.plan/WATCHER_MANIFEST.txt"
printf '%s\n' '{"targetId":"t1","outcome":"TARGET_NOT_REACHED"}' > \
	"$TMP/isolated/forced-state-results-test.jsonl"
cat > "$TMP/isolated/CAMPAIGN_SUMMARY.json" <<EOF
{"infrastructureStatus":"PASS","resultRows":1}
EOF
POLL_SECONDS=1 "$SCRIPT_DIR/run_semantic_retry_after_isolated.sh" 999999996 \
	"$TMP/isolated" "$good_root" "$manifest" "$TMP/semantic"
grep -qx "full_manifest_sha256=$full_sha" "$TMP/semantic.plan/WATCHER_MANIFEST.txt"
grep -qx "primary_source_manifest_sha256=$SOURCE_SHA" "$TMP/semantic.plan/WATCHER_MANIFEST.txt"

echo "retry provenance preflight: PASS"
