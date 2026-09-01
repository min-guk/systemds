#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$DIR/run_exact_candidate_discovery.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/root/target"
echo '{"invocation":"org.apache.sysds.test.Fixture#case"}' > "$TMP/inventory.jsonl"

set +e
env -u SOURCE_RECEIPT -u SOURCE_RECEIPT_SHA256 -u EXPECTED_SOURCE_RECEIPT_SHA256 \
	-u EXPECTED_INVENTORY_SHA256 \
	"$RUNNER" "$TMP/root" \
	"$TMP/inventory.jsonl" "$TMP/out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 64 && ! -e "$TMP/out" ]]

grep -q 'SHARD_INDEX' "$RUNNER"
grep -q 'index % SHARD_COUNT == SHARD_INDEX' "$RUNNER"
grep -q 'SOURCE_SHA256_CHECK_AFTER.log' "$RUNNER"

# Exercise a successful producer without a real Maven build. This validates the
# terminal receipt, immutable receipt copy, common lock, and complete stage seal.
ROOT="$TMP/full-root"
mkdir -p "$ROOT/target" "$ROOT/src/main" "$ROOT/src/test" "$ROOT/scripts/fedplanner" "$TMP/bin"
printf '<project/>\n' > "$ROOT/pom.xml"
echo main > "$ROOT/src/main/input.txt"
echo test > "$ROOT/src/test/input.txt"
echo script > "$ROOT/scripts/fedplanner/input.sh"
cat > "$TMP/bin/mvn" <<'MVN'
#!/usr/bin/env bash
set -euo pipefail
audit_dir=""
invocation=""
for arg in "$@"; do
	case "$arg" in
		-Dsysds.fedplanner.space.audit.dir=*) audit_dir="${arg#*=}" ;;
		-Dsysds.fedplanner.space.audit.discovery.invocation=*) invocation="${arg#*=}" ;;
	esac
done
if [[ -z "$audit_dir" ]]; then
	mkdir -p target/classes/org/apache/sysds/hops \
		target/test-classes/org/apache/sysds/hops/fedplanner/placement
	echo main > target/classes/org/apache/sysds/hops/Hop.class
	echo test > target/test-classes/org/apache/sysds/hops/fedplanner/placement/PlannerSpaceAuditTest.class
else
	printf '{"auditInvocation":"%s"}\n' "$invocation" > "$audit_dir/candidate-space-fake.jsonl"
fi
MVN
chmod +x "$TMP/bin/mvn"
(
	cd "$ROOT"
	find pom.xml src/main src/test scripts/fedplanner -type f -print | LC_ALL=C sort \
		| while IFS= read -r path; do sha256sum "$path"; done > "$TMP/receipt.txt"
)
receipt_sha="$(sha256sum "$TMP/receipt.txt" | cut -d' ' -f1)"
echo '{"invocation":"org.apache.sysds.test.Fixture#case"}' > "$TMP/full-inventory.jsonl"
inventory_sha="$(sha256sum "$TMP/full-inventory.jsonl" | cut -d' ' -f1)"
PATH="$TMP/bin:$PATH" SOURCE_RECEIPT="$TMP/receipt.txt" SOURCE_RECEIPT_SHA256="$receipt_sha" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$receipt_sha" \
	EXPECTED_INVENTORY_SHA256="$inventory_sha" \
	"$RUNNER" "$ROOT" "$TMP/full-inventory.jsonl" "$TMP/full-out"
(
	cd "$TMP/full-out"
	sha256sum -c SHA256SUMS >/dev/null
	[[ -z "$(find . -type f ! -name SHA256SUMS -printf '%P\n' | LC_ALL=C sort \
		| comm -3 - <(sed 's/^[0-9a-f]\{64\}  //' SHA256SUMS | LC_ALL=C sort))" ]]
)
python3 - "$TMP/full-out/RUN_MANIFEST.txt" <<'PY'
import pathlib, sys
values = {}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    key, value = line.split("=", 1)
    assert key not in values and value
    values[key] = value
for key in ("source_check_before_rc", "build_rc", "source_check_after_build_rc",
            "leaf_maven_rc", "source_check_after_rc", "source_paths_check_rc"):
    assert values[key] == "0"
PY

# Both clean-build producers must seal the same main/test sentinel paths.  This
# guards against reintroducing producer-specific runner hashes.
exact_test_witness="$(grep '^test_witness=' "$RUNNER" | head -1 | cut -d= -f2-)"
forced_test_witness="$(grep '^test_build_witness=' "$DIR/run_forced_state_campaign.sh" | head -1 | cut -d= -f2-)"
[[ "${exact_test_witness#\"\$target/}" == "${forced_test_witness#\"\$target_dir/}" ]]

# Caller authority and producer-provided receipt digest are separate inputs;
# disagreement must fail before output creation or Maven execution.
set +e
PATH="$TMP/bin:$PATH" SOURCE_RECEIPT="$TMP/receipt.txt" SOURCE_RECEIPT_SHA256="$receipt_sha" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$(printf 'f%.0s' {1..64})" \
	EXPECTED_INVENTORY_SHA256="$inventory_sha" \
	"$RUNNER" "$ROOT" "$TMP/full-inventory.jsonl" "$TMP/mismatch-out" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 && ! -e "$TMP/mismatch-out" ]]

# A self-consistent replacement inventory cannot override the caller pin.
set +e
PATH="$TMP/bin:$PATH" SOURCE_RECEIPT="$TMP/receipt.txt" SOURCE_RECEIPT_SHA256="$receipt_sha" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$receipt_sha" \
	EXPECTED_INVENTORY_SHA256="$(printf 'e%.0s' {1..64})" \
	"$RUNNER" "$ROOT" "$TMP/full-inventory.jsonl" "$TMP/inventory-mismatch-out" \
	>/dev/null 2>&1
rc=$?
set -e
[[ "$rc" == 65 && ! -e "$TMP/inventory-mismatch-out" ]]
echo "PASS: exact discovery strict preflight"
