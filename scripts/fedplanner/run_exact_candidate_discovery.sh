#!/usr/bin/env bash
# Rediscover every inventory entry in its own Maven/Surefire JVM.  Only these
# exact-leaf candidate files are eligible for an authoritative forced manifest.
set -uo pipefail

if (( $# != 3 )); then
	echo "usage: $0 ROOT INVENTORY_JSONL OUTPUT" >&2
	exit 64
fi
ROOT="$1"
INVENTORY="$2"
OUT="$3"
SHARD_INDEX="${SHARD_INDEX:-0}"
SHARD_COUNT="${SHARD_COUNT:-1}"
SOURCE_RECEIPT="${SOURCE_RECEIPT:-}"
SOURCE_RECEIPT_SHA256="${SOURCE_RECEIPT_SHA256:-}"
EXPECTED_SOURCE_RECEIPT_SHA256="${EXPECTED_SOURCE_RECEIPT_SHA256:-}"
EXPECTED_INVENTORY_SHA256="${EXPECTED_INVENTORY_SHA256:-}"

[[ -d "$ROOT" && -s "$INVENTORY" ]] || { echo "missing root or non-empty inventory" >&2; exit 66; }
[[ -f "$SOURCE_RECEIPT" && "$SOURCE_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "SOURCE_RECEIPT and SOURCE_RECEIPT_SHA256 are required" >&2; exit 64; }
[[ "$EXPECTED_SOURCE_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "EXPECTED_SOURCE_RECEIPT_SHA256 is required" >&2; exit 64; }
[[ "$SOURCE_RECEIPT_SHA256" == "$EXPECTED_SOURCE_RECEIPT_SHA256" ]] || {
	echo "source receipt SHA-256 differs from caller authority" >&2; exit 65; }
source_receipt="$(realpath -e "$SOURCE_RECEIPT")" || exit 66
[[ "$EXPECTED_INVENTORY_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "EXPECTED_INVENTORY_SHA256 is required" >&2; exit 64; }
inventory_real="$(realpath -e "$INVENTORY")" || exit 66
[[ "$(sha256sum "$inventory_real" | cut -d' ' -f1)" == "$EXPECTED_INVENTORY_SHA256" ]] || {
	echo "inventory differs from caller authority" >&2; exit 65; }
[[ "$(sha256sum "$source_receipt" | cut -d' ' -f1)" == "$SOURCE_RECEIPT_SHA256" ]] || {
	echo "source receipt does not match explicit expected SHA-256" >&2; exit 65; }
[[ "$SHARD_INDEX" =~ ^[0-9]+$ && "$SHARD_COUNT" =~ ^[1-9][0-9]*$ ]] \
	&& (( SHARD_INDEX < SHARD_COUNT )) || { echo "invalid shard $SHARD_INDEX/$SHARD_COUNT" >&2; exit 64; }
[[ ! -e "$OUT" ]] || { echo "refusing to reuse exact-discovery output: $OUT" >&2; exit 73; }
root_real="$(realpath -e "$ROOT")" || exit 66
target="$root_real/target"
[[ -d "$target" && ! -L "$target" && "$(realpath -e "$target")" == "$target" ]] || {
	echo "exact discovery requires a physical source-local target: $target" >&2; exit 78; }
# All audit producers that may clean/compile the same checkout share this lock.
# Remote shards normally use separate physical roots, so this does not reduce
# intended cross-server parallelism.
exec 9> "$root_real/.sysds-fedplanner-authority-build.lock"
flock -n 9 || { echo "another authority build owns this source tree" >&2; exit 75; }

mkdir -p "$OUT/leaves"
OUT="$(realpath -e "$OUT")" || exit 66
cd "$root_real" || exit 66
shopt -s nullglob
inventory_copy="$OUT/INVENTORY.jsonl"
cp -- "$inventory_real" "$inventory_copy"
inventory_sha256="$(sha256sum "$inventory_copy" | cut -d' ' -f1)"
[[ "$inventory_sha256" == "$EXPECTED_INVENTORY_SHA256" ]] || {
	echo "copied inventory differs from caller authority" >&2; exit 65; }
receipt_copy="$OUT/SOURCE_RECEIPT.txt"
cp -- "$source_receipt" "$receipt_copy"
[[ "$(sha256sum "$receipt_copy" | cut -d' ' -f1)" == "$SOURCE_RECEIPT_SHA256" ]] || exit 65
verify_receipt_identity() {
	[[ -f "$receipt_copy" && ! -L "$receipt_copy" \
		&& "$(sha256sum "$receipt_copy" | cut -d' ' -f1)" == "$SOURCE_RECEIPT_SHA256" ]]
}
verify_source() {
	local log="$1"
	verify_receipt_identity || return 65
	sha256sum -c "$receipt_copy" > "$log" 2>&1 || return 65
}
source_input_paths() {
	find pom.xml src/main src/test scripts/fedplanner -type f \
		! -path '*/__pycache__/*' ! -name '*.pyc' -print | LC_ALL=C sort
}
source_input_paths > "$OUT/SOURCE_INPUT_PATHS_BEFORE.txt"
( verify_receipt_identity && sha256sum -c "$receipt_copy" && python3 - "$receipt_copy" <<'PY'
import pathlib, sys
root=pathlib.Path.cwd().resolve(); listed=set()
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if line.strip():
        _, name=line.split(None,1); listed.add(name.lstrip("*"))
required={"pom.xml"}
for directory in ("src/main","src/test","scripts/fedplanner"):
    base=root/directory
    if not base.is_dir(): raise SystemExit(f"missing required source directory: {directory}")
    required.update(p.relative_to(root).as_posix() for p in base.rglob("*")
                    if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc")
missing=sorted(required-listed)
if missing: raise SystemExit(f"incomplete source receipt: {len(missing)}; first={missing[:10]}")
print(f"complete source receipt: {len(required)} required")
PY
) > "$OUT/SOURCE_SHA256_CHECK_BEFORE.log" 2>&1 || {
	echo "source receipt mismatch before exact discovery" >&2; exit 65; }

# A physical directory alone does not prove bytecode freshness. Each isolated
# stage performs its own clean compilation before any exact leaf is discovered.
mvn -q -DskipTests clean test-compile > "$OUT/BUILD_MAVEN.log" 2>&1
build_rc=$?
echo "$build_rc" > "$OUT/BUILD_RC.txt"
(( build_rc == 0 )) || { echo "exact discovery clean compilation failed" >&2; exit 1; }
[[ -d "$target" && ! -L "$target" && "$(realpath -e "$target")" == "$target" ]] || {
	echo "clean compilation did not retain a physical source-local target" >&2; exit 78; }
verify_source "$OUT/SOURCE_SHA256_CHECK_AFTER_BUILD.log" || {
	echo "source changed during exact discovery build" >&2; exit 65; }
main_witness="$target/classes/org/apache/sysds/hops/Hop.class"
# Exact discovery and forced replay are separate Maven builds, so their build
# authority must be sealed with the same bytecode artifact.  Hashing each
# producer's runner class made equality impossible even for identical source.
test_witness="$target/test-classes/org/apache/sysds/hops/fedplanner/placement/PlannerSpaceAuditTest.class"
[[ -f "$main_witness" && -f "$test_witness" ]] || {
	echo "exact discovery build witnesses missing" >&2; exit 70; }
mapfile -t invocations < <(python3 - "$inventory_copy" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
for row in rows:
    value = row.get("invocation")
    if not isinstance(value, str) or not value.startswith("org.apache.sysds.test.") or "#" not in value:
        raise SystemExit(f"invalid exact discovery invocation: {value!r}")
    print(value)
PY
)
(( ${#invocations[@]} > 0 )) || { echo "zero exact discovery invocations" >&2; exit 65; }
selected=()
selected_indices=()
for index in "${!invocations[@]}"; do
	if (( index % SHARD_COUNT == SHARD_INDEX )); then
		selected+=("${invocations[$index]}")
		selected_indices+=("$index")
	fi
done
(( ${#selected[@]} > 0 )) || { echo "exact discovery shard selects zero invocations" >&2; exit 65; }

started="$(date -Is)"

failed=0
candidate_files=0
for local_index in "${!selected[@]}"; do
	index="${selected_indices[$local_index]}"
	invocation="${selected[$local_index]}"
	leaf_out="$OUT/leaves/$(printf '%06d' "$index")"
	mkdir -p "$leaf_out"
	printf '%s\n' "$invocation" > "$leaf_out/INVOCATION.txt"
	mvn -q \
		-Dtest=org.apache.sysds.test.functions.federated.FederatedPlannerAuditDiscoveryRunnerTest \
		-Dtest-forkCount=1 -DreuseForks=false -Dtest-threadCount=1 \
		-Dtest-perCoreThreadCount=false \
		-Dsysds.fedplanner.space.audit=true \
		-Dsysds.fedplanner.runtime.audit=true \
		-Dsysds.fedplanner.capability.audit=true \
		"-Dsysds.fedplanner.space.audit.discovery.invocation=$invocation" \
		"-Dsysds.fedplanner.space.audit.dir=$leaf_out" \
		"-Dsysds.fedplanner.capability.audit.dir=$leaf_out" \
		test > "$leaf_out/maven.log" 2>&1
	rc=$?
	echo "$rc" > "$leaf_out/MAVEN_RC.txt"
	(( rc == 0 )) || failed=$((failed + 1))
	files=("$leaf_out"/candidate-space-*.jsonl)
	if (( ${#files[@]} == 1 )) && [[ -s "${files[0]}" ]]; then
		candidate_files=$((candidate_files + 1))
	else
		echo "exact leaf produced zero or multiple candidate files: $invocation" >> "$OUT/ERRORS.txt"
		failed=$((failed + 1))
	fi
done

verify_source "$OUT/SOURCE_SHA256_CHECK_AFTER.log" || {
	echo "source changed during exact discovery" >&2; exit 65; }
source_input_paths > "$OUT/SOURCE_INPUT_PATHS_AFTER.txt"
cmp -s "$OUT/SOURCE_INPUT_PATHS_BEFORE.txt" "$OUT/SOURCE_INPUT_PATHS_AFTER.txt" || {
	echo "source input set changed during exact discovery" >&2; exit 65; }

(( failed == 0 && candidate_files == ${#selected[@]} )) || exit 1
[[ "$(sha256sum "$inventory_copy" | cut -d' ' -f1)" == "$inventory_sha256" ]] || {
	echo "exact discovery inventory changed during producer run" >&2; exit 65; }

# RUN_MANIFEST is a terminal, unique-key success receipt.  It is created only
# after every producer gate has passed, then covered by the complete stage hash.
manifest_tmp="$OUT/.RUN_MANIFEST.tmp"
{
	echo "schema=fedplanner-exact-discovery-v1"
	echo "root=$root_real"
	echo "inventory=INVENTORY.jsonl"
	echo "inventory_sha256=$inventory_sha256"
	echo "source_receipt=SOURCE_RECEIPT.txt"
	echo "source_receipt_sha256=$SOURCE_RECEIPT_SHA256"
	echo "build_contract=clean-test-compile-v1"
	echo "main_build_witness_sha256=$(sha256sum "$main_witness" | cut -d' ' -f1)"
	echo "test_build_witness_sha256=$(sha256sum "$test_witness" | cut -d' ' -f1)"
	echo "inventory_leaves=${#invocations[@]}"
	echo "shard_index=$SHARD_INDEX"
	echo "shard_count=$SHARD_COUNT"
	echo "exact_leaves=${#selected[@]}"
	echo "started=$started"
	echo "failed_leaves=$failed"
	echo "candidate_files=$candidate_files"
	echo "source_check_before_rc=0"
	echo "build_rc=0"
	echo "source_check_after_build_rc=0"
	echo "leaf_maven_rc=0"
	echo "source_check_after_rc=0"
	echo "source_paths_check_rc=0"
	echo "finished=$(date -Is)"
} > "$manifest_tmp"
mv -- "$manifest_tmp" "$OUT/RUN_MANIFEST.txt"

if find "$OUT" -type l -print -quit | grep -q .; then
	echo "exact discovery stage contains a symlink" >&2
	exit 65
fi
(
	cd "$OUT" || exit 66
	find . -type f ! -name SHA256SUMS -printf '%P\n' | LC_ALL=C sort \
		| while IFS= read -r path; do sha256sum -- "$path"; done > SHA256SUMS
	[[ -s SHA256SUMS ]] && sha256sum -c SHA256SUMS >/dev/null
) || { echo "failed to seal exact discovery stage" >&2; exit 65; }
