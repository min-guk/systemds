#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/fedplanner/run_full_fed_space_audit.sh"

set +e
"$SCRIPT" > /tmp/fedplanner-full-audit-preflight.out 2>&1
rc=$?
set -e
[[ "$rc" == 64 ]]

grep -q -- '--require-isolated-runtime-context' "$SCRIPT"
grep -q 'run_exact_candidate_discovery.sh' "$SCRIPT"
grep -q 'component_rc == 0 && runtime_rc == 0' "$SCRIPT"
grep -q 'manifest_rows > 0' "$SCRIPT"
grep -q 'source receipt is incomplete' "$SCRIPT"
grep -q 'run_forced_state_campaign.sh' "$SCRIPT"
grep -q 'TARGETS_PER_JVM=1' "$SCRIPT"
grep -q 'ALLOW_NEEDS_TRIAGE_DIAGNOSTIC=0' "$SCRIPT"
grep -q 'EXACT_SHARD_COUNT" == 1' "$SCRIPT"
grep -q 'FORCED_SHARD_COUNT" == 1' "$SCRIPT"
grep -q 'closure_scope=scoped-discovered-candidates' "$SCRIPT"
grep -q 'coverage_complete=false' "$SCRIPT"
grep -q 'SCOPED_AUDIT_STATUS.json' "$SCRIPT"
grep -q '"globalClosureClaimed": False' "$SCRIPT"
grep -q 'SOURCE_SHA256_CHECK_FINAL.log' "$SCRIPT"
grep -q '.sysds-fedplanner-authority-build.lock' "$SCRIPT"
grep -q 'source receipt authority SHA-256 changed before verification' "$SCRIPT"
grep -q 'EXPECTED_FORCED_MANIFEST_SHA256="$forced_manifest_sha256"' "$SCRIPT"
[[ "$(grep -c 'EXPECTED_SOURCE_RECEIPT_SHA256="$EXPECTED_RECEIPT_SHA256"' "$SCRIPT")" -ge 2 ]]
grep -q -- '--expected-exact-authority-sha256 "$exact_merge_receipt_sha256"' "$SCRIPT"
grep -q -- '--expected-exact-inventory-sha256 "$exact_inventory_sha256"' "$SCRIPT"
grep -q -- '--expected-source-receipt-sha256 "$EXPECTED_RECEIPT_SHA256"' "$SCRIPT"
grep -q -- '--expected-exact-tree-sha256 "$exact_merged_authority_sha256"' "$SCRIPT"
grep -q 'EXPECTED_INVENTORY_SHA256="$exact_inventory_sha256"' "$SCRIPT"
[[ "$(grep -c '^exact_inventory_sha256=' "$SCRIPT")" == 1 ]]
grep -q 'manifest-build.log' "$SCRIPT"
grep -q 'exact_merge_main_build_witness_sha256' "$SCRIPT"
grep -q 'exact_merge_test_build_witness_sha256' "$SCRIPT"
grep -q 'validate_exact_authority_chain' "$SCRIPT"
! grep -q 'row.get("exactDiscoveryAuthority") != authority' "$SCRIPT"
! grep -q 'maven.test.failure.ignore=true' "$SCRIPT"
! grep -q 'exploratory/.\+candidate.*cp -p' "$SCRIPT"

# Adversarial TOCTOU: let the initial receipt and stage-local-copy digest calls
# return the original authority, but replace both the copied receipt and its
# referenced source before the first source verification. The full driver must
# reject the new, internally consistent pair because its immutable caller SHA
# no longer matches the receipt bytes.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
attack_root="$tmp/attack-root"
attack_out="$tmp/attack-out"
mkdir -p "$attack_root/src/main" "$attack_root/src/test" \
	"$attack_root/scripts/fedplanner" "$tmp/fake-bin"
printf 'original\n' > "$attack_root/pom.xml"
(cd "$attack_root" && /usr/bin/sha256sum pom.xml) > "$tmp/attack-receipt.txt"
attack_sha="$(/usr/bin/sha256sum "$tmp/attack-receipt.txt" | cut -d' ' -f1)"
cat > "$tmp/fake-bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
count=0
[[ -f "$COUNTER_FILE" ]] && count="$(cat "$COUNTER_FILE")"
count=$((count + 1)); printf '%s\n' "$count" > "$COUNTER_FILE"
output="$(/usr/bin/sha256sum "$@")"
if (( count == 2 )); then
	printf 'replacement\n' > "$SWAP_SOURCE"
	digest="$(/usr/bin/sha256sum "$SWAP_SOURCE" | cut -d' ' -f1)"
	printf '%s  pom.xml\n' "$digest" > "$SWAP_RECEIPT"
fi
printf '%s\n' "$output"
EOF
cat > "$tmp/fake-bin/mvn" <<'EOF'
#!/usr/bin/env bash
touch "$MAVEN_CALLED"
exit 99
EOF
chmod +x "$tmp/fake-bin/sha256sum" "$tmp/fake-bin/mvn"
set +e
PATH="$tmp/fake-bin:$PATH" COUNTER_FILE="$tmp/sha-count" \
	SWAP_SOURCE="$attack_root/pom.xml" \
	SWAP_RECEIPT="$attack_out/SOURCE_RECEIPT.txt" MAVEN_CALLED="$tmp/maven-called" \
	OUT="$attack_out" RUN_ID=toctou-test \
	"$SCRIPT" "$attack_root" "$tmp/attack-receipt.txt" "$attack_sha" \
	> "$tmp/toctou.out" 2>&1
attack_rc=$?
set -e
[[ "$attack_rc" == 65 ]]
grep -q 'source receipt verification failed' "$tmp/toctou.out"
grep -q 'source receipt authority SHA-256 changed before verification' \
	"$attack_out/SOURCE_SHA256_CHECK_BEFORE.log"
[[ ! -e "$tmp/maven-called" ]]

# A direct exact/forced/full producer holding the common source-root lock must
# exclude the full driver's own clean/broad phase before Maven is launched.
lock_root="$tmp/lock-root"
lock_out="$tmp/lock-out"
mkdir -p "$lock_root/src/main" "$lock_root/src/test" "$lock_root/scripts/fedplanner"
printf 'locked\n' > "$lock_root/pom.xml"
(cd "$lock_root" && /usr/bin/sha256sum pom.xml) > "$tmp/lock-receipt.txt"
lock_sha="$(/usr/bin/sha256sum "$tmp/lock-receipt.txt" | cut -d' ' -f1)"
set +e
flock "$lock_root/.sysds-fedplanner-authority-build.lock" \
	env OUT="$lock_out" RUN_ID=lock-test \
	"$SCRIPT" "$lock_root" "$tmp/lock-receipt.txt" "$lock_sha" \
	> "$tmp/lock.out" 2>&1
lock_rc=$?
set -e
[[ "$lock_rc" == 75 ]]
grep -q 'another authority build owns this source tree' "$tmp/lock.out"

echo "run_full_fed_space_audit strict preflight: PASS"
