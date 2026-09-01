#!/usr/bin/env bash
# Authoritative end-to-end P/L/R audit orchestration.
#
# The broad suites are exploratory only: they inventory candidate-producing
# JUnit methods and runtime witnesses.  Every candidate admitted to the forced
# manifest is rediscovered through one exact JUnit leaf in one fresh Surefire
# JVM.  Mixed-context/direct candidate files never enter the strict manifest.
set -uo pipefail

if (( $# != 3 )); then
	echo "usage: $0 ROOT SOURCE_RECEIPT EXPECTED_RECEIPT_SHA256" >&2
	exit 64
fi
ROOT="$1"
SOURCE_RECEIPT="$2"
EXPECTED_RECEIPT_SHA256="$3"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
OUT="${OUT:-$ROOT/audit/full-$RUN_ID}"
FORKS="${FORKS:-1}"
EXACT_SHARD_INDEX="${EXACT_SHARD_INDEX:-0}"
EXACT_SHARD_COUNT="${EXACT_SHARD_COUNT:-1}"
FORCED_SHARD_INDEX="${FORCED_SHARD_INDEX:-0}"
FORCED_SHARD_COUNT="${FORCED_SHARD_COUNT:-1}"

[[ -d "$ROOT" && -f "$SOURCE_RECEIPT" ]] || { echo "missing root or source receipt" >&2; exit 66; }
[[ "$EXPECTED_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "expected source-receipt SHA-256 must be explicit" >&2; exit 64; }
[[ "$FORKS" == 1 ]] || {
	echo "authoritative discovery requires FORKS=1; parallel broad runs are diagnostic only" >&2; exit 64; }
[[ "$EXACT_SHARD_INDEX" == 0 && "$EXACT_SHARD_COUNT" == 1 \
	&& "$FORCED_SHARD_INDEX" == 0 && "$FORCED_SHARD_COUNT" == 1 ]] || {
	echo "single-process full audit requires complete 0/1 exact and forced campaigns; use a separate distributed orchestrator for shards" >&2
	exit 64
}
[[ ! -e "$OUT" ]] || { echo "refusing to reuse audit output: $OUT" >&2; exit 73; }
root_real="$(realpath -e "$ROOT")" || exit 66
receipt_real="$(realpath -e "$SOURCE_RECEIPT")" || exit 66
OUT="$(realpath -m "$OUT")"
[[ "$(sha256sum "$receipt_real" | cut -d' ' -f1)" == "$EXPECTED_RECEIPT_SHA256" ]] || {
	echo "source receipt does not match explicit expected SHA-256" >&2; exit 65; }

verify_source_receipt() {
	local log="$1"
	(
		cd "$root_real" || exit 66
		# The pathname is not the authority: EXPECTED_RECEIPT_SHA256 is.  Recheck
		# the stage-local receipt bytes immediately before every source walk so a
		# receipt and its referenced source cannot be swapped together after the
		# initial preflight and then silently become a new authority.
		[[ "$(sha256sum "$receipt_real" | cut -d' ' -f1)" == "$EXPECTED_RECEIPT_SHA256" ]] || {
			echo "source receipt authority SHA-256 changed before verification" >&2
			exit 65
		}
		sha256sum -c "$receipt_real"
		python3 - "$receipt_real" <<'PY'
import pathlib, sys
root = pathlib.Path.cwd().resolve()
receipt = pathlib.Path(sys.argv[1]).resolve()
listed = set()
for number, line in enumerate(receipt.read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip():
        continue
    try:
        digest, name = line.split(None, 1)
    except ValueError as exc:
        raise SystemExit(f"malformed source receipt line {number}") from exc
    name = name.lstrip("*")
    path = pathlib.PurePosixPath(name)
    if len(digest) != 64 or path.is_absolute() or ".." in path.parts or name in listed:
        raise SystemExit(f"unsafe/duplicate source receipt entry at line {number}: {name!r}")
    listed.add(name)
required = {"pom.xml"}
for directory in ("src/main", "src/test", "scripts/fedplanner"):
    base = root / directory
    if not base.is_dir():
        raise SystemExit(f"missing required audit source directory: {directory}")
    for path in base.rglob("*"):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
            required.add(path.relative_to(root).as_posix())
missing = sorted(required - listed)
if missing:
    raise SystemExit(f"source receipt is incomplete for {len(missing)} audit inputs; first={missing[:10]}")
print(f"verified complete audit source receipt: {len(listed)} entries, {len(required)} required")
PY
	) > "$log" 2>&1
}

mkdir -p "$OUT" "$OUT/exploratory/component" "$OUT/exploratory/runtime" \
	"$OUT/reports-component" "$OUT/reports-runtime"
cp -p "$receipt_real" "$OUT/SOURCE_RECEIPT.txt"
[[ "$(sha256sum "$OUT/SOURCE_RECEIPT.txt" | cut -d' ' -f1)" == "$EXPECTED_RECEIPT_SHA256" ]] \
	|| { echo "immutable source-receipt copy mismatch" >&2; exit 65; }
receipt_real="$OUT/SOURCE_RECEIPT.txt"
chmod 0444 "$receipt_real"
verify_source_receipt "$OUT/SOURCE_SHA256_CHECK_BEFORE.log" || {
	echo "source receipt verification failed" >&2; exit 65; }

if ! command -v flock >/dev/null 2>&1; then
	echo "missing flock" >&2; exit 69
fi
# All authority-producing clean builds on one physical source tree use this
# one lock pathname.  Hold it only for the full driver's own clean/broad-test
# phase; exact discovery and forced replay acquire the same lock themselves.
# This avoids both clean/test-compile races and a parent/child self-deadlock.
authority_build_lock="$root_real/.sysds-fedplanner-authority-build.lock"
exec 9> "$authority_build_lock"
flock -n 9 || { echo "another authority build owns this source tree" >&2; exit 75; }

cd "$root_real" || exit 66
{
	echo "schema=fedplanner-full-audit-v2"
	echo "run_id=$RUN_ID"
	echo "root=$root_real"
	echo "out=$OUT"
	echo "source_receipt=$receipt_real"
	echo "source_receipt_expected_sha256=$EXPECTED_RECEIPT_SHA256"
	echo "authority_build_lock=$authority_build_lock"
	echo "authority_build_lock_contract=source-root-authority-build-v1"
	echo "candidate_authority=exact-junit-leaf-only"
	echo "closure_scope=scoped-discovered-candidates"
	echo "coverage_complete=false"
	echo "forced_targets_per_jvm=1"
	echo "exact_shard=$EXACT_SHARD_INDEX/$EXACT_SHARD_COUNT"
	echo "forced_shard=$FORCED_SHARD_INDEX/$FORCED_SHARD_COUNT"
	echo "started=$(date -Is)"
} > "$OUT/RUN_MANIFEST.txt"

mvn -q -DskipTests clean test-compile > "$OUT/BUILD_MAVEN.log" 2>&1
build_rc=$?
echo "build_rc=$build_rc" >> "$OUT/RUN_MANIFEST.txt"
(( build_rc == 0 )) || exit 1
[[ -d target && ! -L target && "$(realpath -e target)" == "$root_real/target" ]] || {
	echo "clean build did not create a physical source-local target" >&2; exit 78; }

class_list() {
	find "$1" -name '*Test.java' -type f -print | sort \
		| sed -e 's#^src/test/java/##' -e 's#/#.#g' -e 's#\.java$##' | paste -sd, -
}

tree_authority_sha256() {
	python3 - "$1" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
if not root.is_dir():
    raise SystemExit(f"authority tree is not a directory: {root}")
digest = hashlib.sha256()
paths = sorted(path for path in root.rglob("*") if path.is_file() or path.is_symlink())
if not paths:
    raise SystemExit(f"authority tree is empty: {root}")
for path in paths:
    if path.is_symlink():
        raise SystemExit(f"authority tree contains symlink: {path}")
    relative = path.relative_to(root).as_posix().encode()
    content = path.read_bytes()
    digest.update(len(relative).to_bytes(8, "big")); digest.update(relative)
    digest.update(len(content).to_bytes(8, "big")); digest.update(content)
print(digest.hexdigest())
PY
}

copy_reports() {
	local phase="$1" classes="$2" destination="$OUT/reports-$phase" class report
	IFS=',' read -ra selected <<<"$classes"
	for class in "${selected[@]}"; do
		for report in "target/surefire-reports/TEST-$class.xml" "target/surefire-reports/$class.txt"; do
			[[ -f "$report" ]] && cp -p "$report" "$destination/"
		done
	done
}

common_args=(-q -Dtest-forkCount=1 -DreuseForks=false -Dtest-threadCount=1
	-Dtest-perCoreThreadCount=false -Dsysds.fedplanner.space.audit=true
	-Dsysds.fedplanner.runtime.audit=true -Dsysds.fedplanner.capability.audit=true)

component_classes="$(class_list src/test/java/org/apache/sysds/test/component/federated)"
runtime_classes="$(class_list src/test/java/org/apache/sysds/test/functions/federated)"
[[ -n "$component_classes" && -n "$runtime_classes" ]] || {
	echo "zero broad audit classes" >&2; exit 65; }

mvn "${common_args[@]}" "-Dtest=$component_classes" \
	"-Dsysds.fedplanner.space.audit.dir=$OUT/exploratory/component" \
	"-Dsysds.fedplanner.capability.audit.dir=$OUT/exploratory/component" \
	test > "$OUT/component-tests.log" 2>&1
component_rc=$?
copy_reports component "$component_classes"
echo "component_maven_rc=$component_rc" >> "$OUT/RUN_MANIFEST.txt"

mvn "${common_args[@]}" "-Dtest=$runtime_classes" \
	"-Dsysds.fedplanner.space.audit.dir=$OUT/exploratory/runtime" \
	"-Dsysds.fedplanner.capability.audit.dir=$OUT/exploratory/runtime" \
	test > "$OUT/runtime-tests.log" 2>&1
runtime_rc=$?
copy_reports runtime "$runtime_classes"
echo "runtime_maven_rc=$runtime_rc" >> "$OUT/RUN_MANIFEST.txt"
(( component_rc == 0 && runtime_rc == 0 )) || {
	echo "broad exploratory test phase failed; refusing authoritative discovery" >&2; exit 1; }

# Downstream exact/forced phases own the same lock while mutating target/.
# Release the full driver's phase lock before invoking either child.
flock -u 9
exec 9>&-

python3 scripts/fedplanner/build_exact_discovery_inventory.py \
	--candidate-dir "$OUT/exploratory/component" \
	--candidate-dir "$OUT/exploratory/runtime" \
	--surefire-dir "$OUT/reports-component" \
	--surefire-dir "$OUT/reports-runtime" \
	--output "$OUT/exact-leaf-inventory.jsonl" > "$OUT/inventory-build.log" 2>&1
inventory_rc=$?
echo "inventory_rc=$inventory_rc" >> "$OUT/RUN_MANIFEST.txt"
(( inventory_rc == 0 )) || exit 1
exact_inventory_sha256="$(sha256sum "$OUT/exact-leaf-inventory.jsonl" | cut -d' ' -f1)"
[[ "$exact_inventory_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 65

SOURCE_RECEIPT="$receipt_real" SOURCE_RECEIPT_SHA256="$EXPECTED_RECEIPT_SHA256" \
EXPECTED_SOURCE_RECEIPT_SHA256="$EXPECTED_RECEIPT_SHA256" \
EXPECTED_INVENTORY_SHA256="$exact_inventory_sha256" \
SHARD_INDEX="$EXACT_SHARD_INDEX" SHARD_COUNT="$EXACT_SHARD_COUNT" \
scripts/fedplanner/run_exact_candidate_discovery.sh "$root_real" \
	"$OUT/exact-leaf-inventory.jsonl" "$OUT/exact-discovery"
exact_rc=$?
echo "exact_discovery_rc=$exact_rc" >> "$OUT/RUN_MANIFEST.txt"
(( exact_rc == 0 )) || exit 1

merge_args=(--inventory "$OUT/exact-leaf-inventory.jsonl"
	--inventory-sha256 "$exact_inventory_sha256"
	--shard-dir "$OUT/exact-discovery")
python3 scripts/fedplanner/merge_exact_candidate_discovery.py "${merge_args[@]}" \
	--source-receipt "$receipt_real" \
	--source-receipt-sha256 "$EXPECTED_RECEIPT_SHA256" \
	--output "$OUT/exact-authoritative" > "$OUT/exact-merge.log" 2>&1
merge_rc=$?
echo "exact_merge_rc=$merge_rc" >> "$OUT/RUN_MANIFEST.txt"
(( merge_rc == 0 )) || exit 1
exact_merged_authority_sha256="$(tree_authority_sha256 "$OUT/exact-authoritative")" || exit 65
exact_merge_receipt_sha256="$(sha256sum "$OUT/exact-authoritative/MERGE_AUTHORITY.json" | cut -d' ' -f1)"
exact_merge_checksums_sha256="$(sha256sum "$OUT/exact-authoritative/SHA256SUMS" | cut -d' ' -f1)"
readarray -t exact_merge_build_witnesses < <(python3 - \
	"$OUT/exact-authoritative/MERGE_AUTHORITY.json" <<'PY'
import json, pathlib, re, sys
authority = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
for field in ("mainBuildWitnessSha256", "testBuildWitnessSha256"):
    value = authority.get(field)
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SystemExit(f"malformed exact merge build witness: {field}")
    print(value)
PY
)
(( ${#exact_merge_build_witnesses[@]} == 2 )) || exit 65
exact_merge_main_build_witness_sha256="${exact_merge_build_witnesses[0]}"
exact_merge_test_build_witness_sha256="${exact_merge_build_witnesses[1]}"
[[ "$exact_inventory_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_merged_authority_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_merge_receipt_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_merge_checksums_sha256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "failed to establish exact-discovery downstream authority" >&2; exit 65; }
echo "exact_inventory_sha256=$exact_inventory_sha256" >> "$OUT/RUN_MANIFEST.txt"
echo "exact_merged_authority_sha256=$exact_merged_authority_sha256" >> "$OUT/RUN_MANIFEST.txt"
echo "exact_merge_receipt_sha256=$exact_merge_receipt_sha256" >> "$OUT/RUN_MANIFEST.txt"
echo "exact_merge_checksums_sha256=$exact_merge_checksums_sha256" >> "$OUT/RUN_MANIFEST.txt"

# Detect source changes between the explicit producer receipt, compilation,
# exploratory inventory, and exact discovery before certifying a manifest.
verify_source_receipt "$OUT/SOURCE_SHA256_CHECK_AFTER.log" || {
	echo "source changed during audit; refusing manifest" >&2; exit 65; }

python3 scripts/fedplanner/build_forced_state_manifest.py \
	--candidate-dir "$OUT/exact-authoritative" \
	--surefire-dir "$OUT/reports-component" \
	--surefire-dir "$OUT/reports-runtime" \
	--require-passing-context \
	--require-isolated-runtime-context \
	--expected-exact-authority-sha256 "$exact_merge_receipt_sha256" \
	--expected-exact-inventory-sha256 "$exact_inventory_sha256" \
	--expected-source-receipt-sha256 "$EXPECTED_RECEIPT_SHA256" \
	--expected-exact-tree-sha256 "$exact_merged_authority_sha256" \
	--output "$OUT/forced-state-manifest.jsonl" > "$OUT/manifest-build.log" 2>&1
manifest_rc=$?
manifest_rows=0
[[ -f "$OUT/forced-state-manifest.jsonl" ]] \
	&& manifest_rows="$(grep -cve '^[[:space:]]*$' "$OUT/forced-state-manifest.jsonl")"
echo "manifest_rc=$manifest_rc" >> "$OUT/RUN_MANIFEST.txt"
echo "manifest_rows=$manifest_rows" >> "$OUT/RUN_MANIFEST.txt"
(( manifest_rc == 0 && manifest_rows > 0 )) || {
	echo "strict manifest is empty or invalid" >&2; exit 1; }
[[ "$(tree_authority_sha256 "$OUT/exact-authoritative")" == "$exact_merged_authority_sha256" ]] || {
	echo "exact merged authority changed while building forced manifest" >&2; exit 65; }
forced_manifest_sha256="$(sha256sum "$OUT/forced-state-manifest.jsonl" | cut -d' ' -f1)"
[[ "$forced_manifest_sha256" =~ ^[0-9a-f]{64}$ ]] || {
	echo "failed to establish forced-manifest authority SHA-256" >&2; exit 65; }
echo "forced_manifest_sha256=$forced_manifest_sha256" >> "$OUT/RUN_MANIFEST.txt"
python3 - "$OUT/forced-state-manifest.jsonl" \
	"$OUT/manifest-build.log" \
	"$exact_merge_receipt_sha256" "$exact_merged_authority_sha256" \
	"$exact_merge_checksums_sha256" "$exact_inventory_sha256" \
	"$EXPECTED_RECEIPT_SHA256" "$exact_merge_main_build_witness_sha256" \
	"$exact_merge_test_build_witness_sha256" \
	<<'PY' > "$OUT/STRICT_MANIFEST_CHECK.log"
import json, pathlib, sys
from scripts.fedplanner.exact_authority_envelope import validate_exact_authority_chain
path, build_log_path = map(pathlib.Path, sys.argv[1:3])
expected_fields = ("authoritySha256", "authorityTreeSha256", "treeChecksumSha256",
    "inventorySha256", "sourceReceiptSha256", "mainBuildWitnessSha256",
    "testBuildWitnessSha256")
expected_authority = dict(zip(expected_fields, sys.argv[3:], strict=True))
build_receipts = [json.loads(line) for line in build_log_path.read_text(encoding="utf-8").splitlines()
    if line.strip()]
if len(build_receipts) != 1:
    raise SystemExit("manifest builder did not emit one canonical authority receipt")
rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
if not rows:
    raise SystemExit("empty strict manifest")
ids = []
try:
    validate_exact_authority_chain(
        build_receipts[0].get("exactDiscoveryAuthority"), rows, expected_authority)
except ValueError as exc:
    raise SystemExit(str(exc)) from exc
for index, row in enumerate(rows, 1):
    if row.get("schema") != "fedplanner-forced-state-manifest-v1":
        raise SystemExit(f"row {index}: unexpected manifest schema")
    if row.get("exactReplayLeaf") is not True or not row.get("replayInvocation"):
        raise SystemExit(f"row {index}: non-exact/legacy replay target")
    if row.get("replayInvocation", "").split("[", 1)[0] != row.get("replayContext"):
        raise SystemExit(f"row {index}: invocation/context mismatch")
    ids.append(row.get("targetId"))
if None in ids or len(ids) != len(set(ids)):
    raise SystemExit("missing/duplicate strict targetId")
print(f"strict authoritative manifest: schema=v1+exact-leaf-contract rows={len(rows)}")
PY
strict_manifest_rc=$?
echo "strict_manifest_check_rc=$strict_manifest_rc" >> "$OUT/RUN_MANIFEST.txt"
(( strict_manifest_rc == 0 )) || exit 1

# The default full path is genuinely end-to-end. For multi-server campaigns,
# copy the identical source/receipt/manifest to distinct physical stages and
# set FORCED_SHARD_INDEX/COUNT per server; aggregate their outputs separately.
SOURCE_RECEIPT="$receipt_real" SOURCE_RECEIPT_SHA256="$EXPECTED_RECEIPT_SHA256" \
	EXPECTED_SOURCE_RECEIPT_SHA256="$EXPECTED_RECEIPT_SHA256" \
	EXPECTED_FORCED_MANIFEST_SHA256="$forced_manifest_sha256" \
	TARGETS_PER_JVM=1 ALLOW_NEEDS_TRIAGE_DIAGNOSTIC=0 \
	scripts/fedplanner/run_forced_state_campaign.sh \
	"$root_real" "$OUT/forced-state-manifest.jsonl" "$OUT/forced-campaign" \
	"$FORCED_SHARD_INDEX" "$FORCED_SHARD_COUNT"
forced_rc=$?
echo "forced_campaign_rc=$forced_rc" >> "$OUT/RUN_MANIFEST.txt"
(( forced_rc == 0 )) || exit 1

# Comparison consumes only exact-leaf P rows. Broad mixed-context candidate
# files remain under exploratory/ as diagnostic evidence and are never copied.
mkdir -p "$OUT/authoritative-combined"
counter=0
while IFS= read -r -d '' file; do
	cp -p "$file" "$OUT/authoritative-combined/exact-candidate-space-$counter.jsonl"
	counter=$((counter + 1))
done < <(find "$OUT/exact-authoritative" -name 'candidate-space-*.jsonl' -type f -print0 | sort -z)
# Runtime capability receipts from the forced campaign are part of R: they
# prove that selector-published states survived constrained lowering/execution.
# Forced candidate rows are deliberately excluded because P authority comes
# only from the isolated exact-discovery stage above.
while IFS= read -r -d '' file; do
	cp -p "$file" "$OUT/authoritative-combined/runtime-capability-$counter.jsonl"
	counter=$((counter + 1))
done < <(find "$OUT/exploratory" "$OUT/exact-authoritative" "$OUT/forced-campaign" \
	-name 'runtime-capability-*.jsonl' -type f -print0 | sort -z)
(( counter > 0 )) || { echo "zero authoritative evidence files" >&2; exit 1; }

python3 scripts/fedplanner/compare_candidate_runtime_space.py \
	--candidate-dir "$OUT/authoritative-combined" \
	--runtime-dir "$OUT/authoritative-combined" \
	--source-root "$root_real" --out-dir "$OUT/differential" > "$OUT/compare.log" 2>&1
compare_rc=$?
echo "compare_rc=$compare_rc" >> "$OUT/RUN_MANIFEST.txt"
verify_source_receipt "$OUT/SOURCE_SHA256_CHECK_FINAL.log" || {
	echo "source changed before final audit certification" >&2; exit 65; }
(( compare_rc == 0 )) || exit 1
python3 - "$OUT/SCOPED_AUDIT_STATUS.json" "$OUT/forced-state-manifest.jsonl" \
	"$OUT/forced-campaign/CAMPAIGN_SUMMARY.json" <<'PY'
import json, pathlib, sys
output, manifest_path, forced_summary_path = map(pathlib.Path, sys.argv[1:])
targets = sum(1 for line in manifest_path.read_text(encoding="utf-8").splitlines() if line.strip())
forced = json.loads(forced_summary_path.read_text(encoding="utf-8"))
status = {
    "schema": "fedplanner-scoped-audit-status-v1",
    "status": "SCOPED_AUTHORITATIVE_PASS",
    "closureScope": "scoped-discovered-candidates",
    "coverageComplete": False,
    "globalClosureClaimed": False,
    "manifestTargets": targets,
    "forcedInfrastructureStatus": forced.get("infrastructureStatus"),
    "forcedClassificationStatus": forced.get("classificationStatus"),
}
output.write_text(json.dumps(status, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
echo "finished=$(date -Is)" >> "$OUT/RUN_MANIFEST.txt"
