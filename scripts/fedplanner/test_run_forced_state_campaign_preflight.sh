#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/run_forced_state_campaign.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
cat > "$TMP/bin/mvn" <<'SH'
#!/usr/bin/env bash
echo "$*" >> "${FAKE_MVN_CALLS:?}"
if [[ "$*" == *"-DskipTests clean test-compile"* && -n "${FAKE_BUILD_WITNESSES:-}" ]]; then
	mkdir -p target/classes/org/apache/sysds/hops \
		target/test-classes/org/apache/sysds/hops/fedplanner/placement
	printf 'shared-main\n' > target/classes/org/apache/sysds/hops/Hop.class
	printf 'shared-test\n' > target/test-classes/org/apache/sysds/hops/fedplanner/placement/PlannerSpaceAuditTest.class
fi
if [[ -n "${FAKE_MUTATE_ROOT:-}" ]]; then
	echo changed >> "$FAKE_MUTATE_ROOT/src/main/java/org/apache/sysds/hops/Hop.java"
	( cd "$FAKE_MUTATE_ROOT" && find pom.xml src/main src/test scripts/fedplanner -type f -print0 \
		| sort -z | xargs -0 sha256sum > SOURCE_SHA256SUMS.txt )
fi
exit "${FAKE_BUILD_RC:-9}"
SH
chmod +x "$TMP/bin/mvn"
export PATH="$TMP/bin:$PATH" FAKE_MVN_CALLS="$TMP/mvn-calls.txt"
: > "$FAKE_MVN_CALLS"

prepare_root() {
	local root="$1"
	mkdir -p "$root/target" "$root/src/main/java/org/apache/sysds/hops" \
		"$root/src/test/java/org/apache/sysds/test/functions/federated" \
		"$root/scripts/fedplanner"
	: > "$root/pom.xml"
	: > "$root/src/main/java/org/apache/sysds/hops/Hop.java"
	: > "$root/src/test/java/org/apache/sysds/test/functions/federated/FederatedForcedStateAuditRunnerTest.java"
	cp "$RUNNER" "$root/scripts/fedplanner/run_forced_state_campaign.sh"
	cp "$SCRIPT_DIR/exact_authority_envelope.py" \
		"$root/scripts/fedplanner/exact_authority_envelope.py"
	cat > "$root/manifest.jsonl" <<'JSON'
{"schema":"fedplanner-forced-state-manifest-v1","targetId":"abc","replayContext":"org.apache.sysds.test.Fixture#case","replayInvocation":"org.apache.sysds.test.Fixture#case","exactReplayLeaf":true}
JSON
	( cd "$root" && find pom.xml src/main src/test scripts/fedplanner -type f -print0 \
		| sort -z | xargs -0 sha256sum > SOURCE_SHA256SUMS.txt )
	local source_sha
	source_sha=$(sha256sum "$root/SOURCE_SHA256SUMS.txt" | cut -d' ' -f1)
	python3 - "$root/manifest.jsonl" "$source_sha" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
row = json.loads(path.read_text())
row["exactDiscoveryAuthority"] = {
	"authoritySha256": "1" * 64,
	"authorityTreeSha256": "7" * 64,
	"treeChecksumSha256": "2" * 64,
	"inventorySha256": "3" * 64,
	"sourceReceiptSha256": sys.argv[2],
	"mainBuildWitnessSha256": "c" * 64,
	"testBuildWitnessSha256": "d" * 64,
}
path.write_text(json.dumps(row, sort_keys=True) + "\n")
PY
}

run() {
	local root="$1" out="$2"
	export SOURCE_RECEIPT="$root/SOURCE_SHA256SUMS.txt"
	export SOURCE_RECEIPT_SHA256="$(sha256sum "$SOURCE_RECEIPT" | cut -d' ' -f1)"
	export EXPECTED_SOURCE_RECEIPT_SHA256="$SOURCE_RECEIPT_SHA256"
	export EXPECTED_FORCED_MANIFEST_SHA256="$(sha256sum "$root/manifest.jsonl" | cut -d' ' -f1)"
	"$RUNNER" "$root" "$root/manifest.jsonl" "$out" 0 1
}

# A real byte-for-byte witness produced by two independent clean-build calls
# must be accepted by both producer contracts.  Use the same sentinel bytes as
# exact discovery and let the forced runner proceed beyond witness validation;
# later fake-Maven output may fail because this preflight does not emulate the
# full Java audit protocol.
prepare_root "$TMP/shared-witness-root"
shared_main_sha="$(printf 'shared-main\n' | sha256sum | cut -d' ' -f1)"
shared_test_sha="$(printf 'shared-test\n' | sha256sum | cut -d' ' -f1)"
python3 - "$TMP/shared-witness-root/manifest.jsonl" "$shared_main_sha" "$shared_test_sha" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
row = json.loads(path.read_text())
row["exactDiscoveryAuthority"]["mainBuildWitnessSha256"] = sys.argv[2]
row["exactDiscoveryAuthority"]["testBuildWitnessSha256"] = sys.argv[3]
path.write_text(json.dumps(row, sort_keys=True) + "\n")
PY
: > "$FAKE_MVN_CALLS"
set +e
FAKE_BUILD_RC=0 FAKE_BUILD_WITNESSES=1 run "$TMP/shared-witness-root" \
	"$TMP/shared-witness-out" >/dev/null 2>&1
rc=$?
set -e
[[ -s "$TMP/shared-witness-out/RUN_MANIFEST.txt" ]]
grep -q "^main_build_witness_sha256=$shared_main_sha$" \
	"$TMP/shared-witness-out/RUN_MANIFEST.txt"
grep -q "^test_build_witness_sha256=$shared_test_sha$" \
	"$TMP/shared-witness-out/RUN_MANIFEST.txt"
! grep -q 'forced campaign bytecode differs from exact discovery authority' \
	"$TMP/shared-witness-out/BUILD_MAVEN.log"

prepare_root "$TMP/root"

# Explicit receipt authority is mandatory.
set +e
env -u SOURCE_RECEIPT -u SOURCE_RECEIPT_SHA256 -u EXPECTED_SOURCE_RECEIPT_SHA256 \
	-u EXPECTED_FORCED_MANIFEST_SHA256 \
	"$RUNNER" "$TMP/root" \
	"$TMP/root/manifest.jsonl" "$TMP/no-receipt" 0 1 >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 64 && ! -e "$TMP/no-receipt" ]]

# A producer-provided receipt digest cannot override the orchestrator authority.
: > "$FAKE_MVN_CALLS"
set +e
SOURCE_RECEIPT="$TMP/root/SOURCE_SHA256SUMS.txt" \
	SOURCE_RECEIPT_SHA256="$(sha256sum "$TMP/root/SOURCE_SHA256SUMS.txt" | cut -d' ' -f1)" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$(printf 'f%.0s' {1..64})" \
	EXPECTED_FORCED_MANIFEST_SHA256="$(sha256sum "$TMP/root/manifest.jsonl" | cut -d' ' -f1)" \
	"$RUNNER" "$TMP/root" "$TMP/root/manifest.jsonl" "$TMP/source-authority-mismatch" 0 1 \
	>/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 && ! -e "$TMP/source-authority-mismatch" && ! -s "$FAKE_MVN_CALLS" ]]

# Empty and legacy manifests fail before Maven.
for mode in empty legacy; do
	cp "$TMP/root/manifest.jsonl" "$TMP/root/manifest.saved"
	if [[ "$mode" == empty ]]; then
		: > "$TMP/root/manifest.jsonl"
	else
		echo '{"schema":"fedplanner-forced-state-manifest-v1","targetId":"old"}' > "$TMP/root/manifest.jsonl"
	fi
	: > "$FAKE_MVN_CALLS"
	set +e
	run "$TMP/root" "$TMP/$mode-out" >/dev/null 2>&1
	rc=$?
	set -e
	[[ "$rc" == 65 && ! -s "$FAKE_MVN_CALLS" ]]
	mv "$TMP/root/manifest.saved" "$TMP/root/manifest.jsonl"
done

# Strict replay isolates one target per JVM unless explicitly diagnostic.
set +e
TARGETS_PER_JVM=2 run "$TMP/root" "$TMP/multi-out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 64 && ! -e "$TMP/multi-out" ]]

# A complete, hash-pinned receipt and strict manifest reach the clean build.
: > "$FAKE_MVN_CALLS"
set +e
FAKE_BUILD_RC=9 run "$TMP/root" "$TMP/build-out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 1 ]]
grep -q -- '-DskipTests clean test-compile' "$FAKE_MVN_CALLS"
grep -q '^targets_per_jvm=1$' "$TMP/build-out/RUN_MANIFEST.txt"
grep -q '^strict_manifest_check_rc=0$' "$TMP/build-out/RUN_MANIFEST.txt"
grep -q '^source_receipt_copy=SOURCE_RECEIPT_SHA256SUMS.txt$' "$TMP/build-out/RUN_MANIFEST.txt"
[[ "$(sha256sum "$TMP/build-out/SOURCE_RECEIPT_SHA256SUMS.txt" | cut -d' ' -f1)" \
	== "$SOURCE_RECEIPT_SHA256" ]]

# Replacing both the external receipt and source during the build cannot change
# the stage-local caller-pinned receipt used by post-build verification.
prepare_root "$TMP/toctou-root"
: > "$FAKE_MVN_CALLS"
set +e
FAKE_BUILD_RC=0 FAKE_MUTATE_ROOT="$TMP/toctou-root" \
	run "$TMP/toctou-root" "$TMP/toctou-out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 ]]
grep -q '^post_build_source_receipt_rc=' "$TMP/toctou-out/RUN_MANIFEST.txt"
[[ "$(sed -n 's/^post_build_source_receipt_rc=//p' "$TMP/toctou-out/RUN_MANIFEST.txt")" != 0 ]]

# A caller manifest digest mismatch stops before campaign output/build.
set +e
SOURCE_RECEIPT="$TMP/root/SOURCE_SHA256SUMS.txt" \
	SOURCE_RECEIPT_SHA256="$(sha256sum "$TMP/root/SOURCE_SHA256SUMS.txt" | cut -d' ' -f1)" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$(sha256sum "$TMP/root/SOURCE_SHA256SUMS.txt" | cut -d' ' -f1)" \
	EXPECTED_FORCED_MANIFEST_SHA256="$(printf 'e%.0s' {1..64})" \
	"$RUNNER" "$TMP/root" "$TMP/root/manifest.jsonl" "$TMP/bad-authority" 0 1 \
	>/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 && ! -e "$TMP/bad-authority" ]]

# Receipt tampering is rejected before Maven.
echo tamper >> "$TMP/root/src/main/java/org/apache/sysds/hops/Hop.java"
: > "$FAKE_MVN_CALLS"
set +e
run "$TMP/root" "$TMP/tamper-out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 && ! -s "$FAKE_MVN_CALLS" ]]

# Infrastructure PASS alone must never certify an authoritative campaign.
cat > "$TMP/needs-triage.json" <<'JSON'
{"infrastructureStatus":"PASS","classificationStatus":"NEEDS_TRIAGE"}
JSON
set +e
python3 "$SCRIPT_DIR/validate_forced_campaign_summary.py" "$TMP/needs-triage.json" \
	>/dev/null 2>&1
rc=$?
set -e
[[ "$rc" != 0 ]]
python3 "$SCRIPT_DIR/validate_forced_campaign_summary.py" "$TMP/needs-triage.json" \
	--allow-needs-triage-diagnostic >/dev/null

echo "PASS: strict forced campaign preflight"
