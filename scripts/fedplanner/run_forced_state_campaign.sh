#!/usr/bin/env bash
# Replay one deterministic shard of a pass-qualified placement-state manifest.
# All audit recorders are mandatory here so a successful JUnit replay cannot be
# mistaken for a formal runtime-capability receipt when instrumentation is off.
set -uo pipefail

if (( $# != 5 )); then
	echo "usage: $0 ROOT MANIFEST OUTPUT SHARD_INDEX SHARD_COUNT" >&2
	exit 64
fi

ROOT="$1"
MANIFEST="$2"
OUT="$3"
SHARD_INDEX="$4"
SHARD_COUNT="$5"
MAX_TARGETS="${MAX_TARGETS:-}"
TARGETS_PER_JVM="${TARGETS_PER_JVM:-1}"
SOURCE_RECEIPT="${SOURCE_RECEIPT:-}"
SOURCE_RECEIPT_SHA256="${SOURCE_RECEIPT_SHA256:-}"
EXPECTED_SOURCE_RECEIPT_SHA256="${EXPECTED_SOURCE_RECEIPT_SHA256:-}"
EXPECTED_FORCED_MANIFEST_SHA256="${EXPECTED_FORCED_MANIFEST_SHA256:-}"
ALLOW_NEEDS_TRIAGE_DIAGNOSTIC="${ALLOW_NEEDS_TRIAGE_DIAGNOSTIC:-0}"

if [[ ! -d "$ROOT" || ! -f "$MANIFEST" ]]; then
	echo "missing source root or manifest" >&2
	exit 66
fi
if [[ -z "$SOURCE_RECEIPT" || ! -f "$SOURCE_RECEIPT" \
	|| ! "$SOURCE_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
	echo "SOURCE_RECEIPT and SOURCE_RECEIPT_SHA256 are required" >&2
	exit 64
fi
if [[ ! "$EXPECTED_SOURCE_RECEIPT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
	echo "EXPECTED_SOURCE_RECEIPT_SHA256 is required" >&2
	exit 64
fi
if [[ "$SOURCE_RECEIPT_SHA256" != "$EXPECTED_SOURCE_RECEIPT_SHA256" ]]; then
	echo "source receipt SHA-256 differs from caller authority" >&2
	exit 65
fi
if [[ ! "$EXPECTED_FORCED_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]] \
	|| [[ "$(sha256sum "$MANIFEST" | cut -d' ' -f1)" != "$EXPECTED_FORCED_MANIFEST_SHA256" ]]; then
	echo "forced manifest does not match explicit caller authority SHA-256" >&2
	exit 65
fi
source_receipt="$(realpath -e "$SOURCE_RECEIPT")" || exit 66
if [[ "$(sha256sum "$source_receipt" | cut -d' ' -f1)" != "$SOURCE_RECEIPT_SHA256" ]]; then
	echo "source receipt does not match explicit expected SHA-256" >&2
	exit 65
fi
root_real="$(realpath -e "$ROOT")" || exit 66
target_dir="$ROOT/target"
if [[ -L "$target_dir" ]]; then
	echo "refusing non-isolated build target symlink: $target_dir -> $(readlink "$target_dir")" >&2
	exit 78
fi
if [[ ! -d "$target_dir" ]]; then
	echo "missing physical source-local build target: $target_dir" >&2
	exit 66
fi
target_real="$(realpath -e "$target_dir")" || exit 66
if [[ "$target_real" != "$root_real/target" ]]; then
	echo "refusing non-source-local build target: $target_dir -> $target_real" >&2
	exit 78
fi
if ! [[ "$SHARD_INDEX" =~ ^[0-9]+$ && "$SHARD_COUNT" =~ ^[1-9][0-9]*$ ]] \
	|| (( SHARD_INDEX >= SHARD_COUNT )); then
	echo "invalid shard $SHARD_INDEX/$SHARD_COUNT" >&2
	exit 64
fi
if [[ -n "$MAX_TARGETS" ]] && ! [[ "$MAX_TARGETS" =~ ^[1-9][0-9]*$ ]]; then
	echo "MAX_TARGETS must be a positive integer" >&2
	exit 64
fi
if ! [[ "$TARGETS_PER_JVM" =~ ^[1-9][0-9]*$ ]]; then
	echo "TARGETS_PER_JVM must be a positive integer" >&2
	exit 64
fi
if (( TARGETS_PER_JVM != 1 )) && [[ "${ALLOW_MULTI_TARGET_JVM_DIAGNOSTIC:-0}" != 1 ]]; then
	echo "strict replay requires TARGETS_PER_JVM=1; multi-target JVMs are diagnostic only" >&2
	exit 64
fi
[[ "$ALLOW_NEEDS_TRIAGE_DIAGNOSTIC" == 0 || "$ALLOW_NEEDS_TRIAGE_DIAGNOSTIC" == 1 ]] || {
	echo "ALLOW_NEEDS_TRIAGE_DIAGNOSTIC must be 0 or 1" >&2; exit 64; }
if [[ -e "$OUT" ]]; then
	echo "refusing to reuse campaign output: $OUT" >&2
	exit 73
fi

# A forced-state receipt is only meaningful if the bytecode was built from the
# staged source.  In particular, rsync may preserve an older source mtime than
# an existing class file, which makes Maven's incremental compiler accept stale
# bytecode.  Serialize campaigns sharing a physical source tree and force one
# clean test compilation before any target JVM is launched.  The lock lives in
# the staged source root so every process that can mutate the same physical
# target contends on the same filesystem object. A host-local /tmp lock is not
# sufficient when a stage is shared across hosts.
if ! command -v flock >/dev/null 2>&1; then
	echo "missing required campaign build lock utility: flock" >&2
	exit 69
fi
campaign_lock="$root_real/.sysds-fedplanner-authority-build.lock"
exec 9> "$campaign_lock"
if ! flock -n 9; then
	echo "refusing concurrent campaign on shared source root: $root_real" >&2
	exit 75
fi

mkdir -p "$OUT"
cd "$ROOT" || exit 66
source_receipt_copy="$OUT/SOURCE_RECEIPT_SHA256SUMS.txt"
cp -- "$source_receipt" "$source_receipt_copy"
if [[ "$(sha256sum "$source_receipt_copy" | cut -d' ' -f1)" != "$SOURCE_RECEIPT_SHA256" ]]; then
	echo "stage-local source receipt copy does not match caller authority" >&2
	exit 65
fi
source_receipt="$(realpath -e "$source_receipt_copy")" || exit 66

python3 - "$MANIFEST" "$SOURCE_RECEIPT_SHA256" > "$OUT/STRICT_MANIFEST_CHECK.log" <<'PY'
import json, pathlib, sys
from scripts.fedplanner.exact_authority_envelope import (
    EXACT_AUTHORITY_FIELDS, normalize_exact_authority_envelope)
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines() if line.strip()]
if not rows:
    raise SystemExit("empty forced-state manifest")
ids = []
authorities = []
for index, row in enumerate(rows, 1):
    if row.get("schema") != "fedplanner-forced-state-manifest-v1":
        raise SystemExit(f"row {index}: unexpected schema")
    invocation = row.get("replayInvocation")
    if row.get("exactReplayLeaf") is not True or not isinstance(invocation, str):
        raise SystemExit(f"row {index}: legacy/non-exact manifest target")
    if invocation.split("[", 1)[0] != row.get("replayContext"):
        raise SystemExit(f"row {index}: invocation/context mismatch")
    try:
        authority = normalize_exact_authority_envelope(row.get("exactDiscoveryAuthority"),
            label=f"row {index}", expected_source_receipt_sha256=sys.argv[2])
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc
    authorities.append(authority)
    ids.append(row.get("targetId"))
if None in ids or len(ids) != len(set(ids)):
    raise SystemExit("missing/duplicate targetId")
if any(authority != authorities[0] for authority in authorities[1:]):
    raise SystemExit("forced manifest rows disagree on exact discovery authority")
print(f"strict exact-leaf targets={len(rows)}")
for key in EXACT_AUTHORITY_FIELDS:
    print(f"exact_{key}={authorities[0][key]}")
PY
strict_manifest_rc=$?
if (( strict_manifest_rc != 0 )); then
	echo "invalid legacy/empty forced-state manifest" >&2
	exit 65
fi
exact_discovery_authority_sha256=$(sed -n 's/^exact_authoritySha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_discovery_authority_tree_sha256=$(sed -n 's/^exact_authorityTreeSha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_discovery_tree_checksum_sha256=$(sed -n 's/^exact_treeChecksumSha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_discovery_inventory_sha256=$(sed -n 's/^exact_inventorySha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_discovery_source_receipt_sha256=$(sed -n 's/^exact_sourceReceiptSha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_main_build_witness_sha256=$(sed -n 's/^exact_mainBuildWitnessSha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
exact_test_build_witness_sha256=$(sed -n 's/^exact_testBuildWitnessSha256=//p' \
	"$OUT/STRICT_MANIFEST_CHECK.log")
[[ "$exact_discovery_authority_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_discovery_authority_tree_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_discovery_tree_checksum_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_discovery_inventory_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_discovery_source_receipt_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_main_build_witness_sha256" =~ ^[0-9a-f]{64}$ \
	&& "$exact_test_build_witness_sha256" =~ ^[0-9a-f]{64}$ ]] || exit 65

{
	echo "schema=fedplanner-forced-campaign-v1"
	echo "root=$ROOT"
	echo "manifest=$MANIFEST"
	echo "manifest_sha256=$(sha256sum "$MANIFEST" | cut -d' ' -f1)"
	echo "source_manifest=SOURCE_RECEIPT_SHA256SUMS.txt"
	echo "source_manifest_sha256=$SOURCE_RECEIPT_SHA256"
	echo "source_receipt_copy=SOURCE_RECEIPT_SHA256SUMS.txt"
	echo "exact_discovery_authority_sha256=$exact_discovery_authority_sha256"
	echo "exact_discovery_authority_tree_sha256=$exact_discovery_authority_tree_sha256"
	echo "exact_discovery_tree_checksum_sha256=$exact_discovery_tree_checksum_sha256"
	echo "exact_discovery_inventory_sha256=$exact_discovery_inventory_sha256"
	echo "exact_discovery_source_receipt_sha256=$exact_discovery_source_receipt_sha256"
	echo "exact_discovery_main_build_witness_sha256=$exact_main_build_witness_sha256"
	echo "exact_discovery_test_build_witness_sha256=$exact_test_build_witness_sha256"
	echo "strict_manifest_check_rc=$strict_manifest_rc"
	echo "campaign_lock=$campaign_lock"
	echo "campaign_lock_stat=$(stat -Lc 'device=%d inode=%i type=%F' "$campaign_lock")"
	echo "build_target=$target_real"
	echo "build_target_stat=$(stat -Lc 'device=%d inode=%i type=%F' "$target_dir")"
	echo "host=$(hostname)"
	echo "shard_index=$SHARD_INDEX"
	echo "shard_count=$SHARD_COUNT"
	echo "max_targets=${MAX_TARGETS:-unbounded}"
	echo "targets_per_jvm=$TARGETS_PER_JVM"
	echo "allow_needs_triage_diagnostic=$ALLOW_NEEDS_TRIAGE_DIAGNOSTIC"
	echo "started=$(date -Is)"
} > "$OUT/RUN_MANIFEST.txt"

# Build receipts are meaningful only when the staged source still matches its
# producer receipt. Missing, malformed, or mismatched receipts stop before Maven
# so stale/mixed source can never be certified by freshly compiled bytecode.
verify_campaign_source_receipt() {
	[[ "$(sha256sum "$source_receipt" | cut -d' ' -f1)" == "$SOURCE_RECEIPT_SHA256" ]] || {
		echo "stage-local source receipt changed from caller authority" >&2
		return 65
	}
	( cd "$root_real" && sha256sum -c "$source_receipt" && python3 - "$source_receipt" <<'PY'
import pathlib, sys
root = pathlib.Path.cwd().resolve()
listed = set()
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if line.strip():
        _, name = line.split(None, 1)
        listed.add(name.lstrip("*"))
required = {"pom.xml"}
for directory in ("src/main", "src/test", "scripts/fedplanner"):
    base = root / directory
    if not base.is_dir():
        raise SystemExit(f"missing required source directory: {directory}")
    required.update(path.relative_to(root).as_posix() for path in base.rglob("*")
                    if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
missing = sorted(required - listed)
if missing:
    raise SystemExit(f"incomplete source receipt: {len(missing)} files; first={missing[:10]}")
print(f"complete source receipt: {len(required)} required")
PY
	)
}
source_input_paths() {
	find pom.xml src/main src/test scripts/fedplanner -type f \
		! -path '*/__pycache__/*' ! -name '*.pyc' -print | LC_ALL=C sort
}
source_input_paths > "$OUT/SOURCE_INPUT_PATHS_BEFORE.txt"
source_receipt_rc=0
if [[ ! -f "$source_receipt" ]]; then
	echo "missing required source receipt: $source_receipt" \
		> "$OUT/SOURCE_SHA256_CHECK.log"
	source_receipt_rc=66
else
	verify_campaign_source_receipt > "$OUT/SOURCE_SHA256_CHECK.log" 2>&1 \
		|| source_receipt_rc=$?
fi
echo "source_receipt_rc=$source_receipt_rc" >> "$OUT/RUN_MANIFEST.txt"
if (( source_receipt_rc != 0 )); then
	echo "source receipt verification failed; refusing campaign build" >&2
	exit 65
fi

echo "build_contract=clean-test-compile-v1" >> "$OUT/RUN_MANIFEST.txt"
echo "build_started=$(date -Is)" >> "$OUT/RUN_MANIFEST.txt"
mvn -q -DskipTests clean test-compile > "$OUT/BUILD_MAVEN.log" 2>&1
build_rc=$?
echo "build_rc=$build_rc" >> "$OUT/RUN_MANIFEST.txt"
echo "build_finished=$(date -Is)" >> "$OUT/RUN_MANIFEST.txt"
if (( build_rc != 0 )); then
	echo "campaign clean test compilation failed; refusing forced-state execution" >&2
	exit 1
fi

# Recheck source inputs after the clean build to close the receipt/build TOCTOU
# window before launching any forced target JVM.
verify_campaign_source_receipt \
	> "$OUT/SOURCE_SHA256_CHECK_AFTER_BUILD.log" 2>&1
post_build_source_rc=$?
echo "post_build_source_receipt_rc=$post_build_source_rc" >> "$OUT/RUN_MANIFEST.txt"
(( post_build_source_rc == 0 )) || {
	echo "source changed during campaign build" >&2; exit 65; }

# Maven owns target during the clean build.  Re-check isolation afterward so a
# build extension cannot silently redirect the campaign to shared bytecode.
if [[ -L "$target_dir" ]]; then
	echo "refusing post-build target symlink: $target_dir -> $(readlink "$target_dir")" >&2
	exit 78
fi
if [[ ! -d "$target_dir" ]]; then
	echo "clean test compilation did not create build target: $target_dir" >&2
	exit 70
fi
post_build_target_real="$(realpath -e "$target_dir")" || exit 70
if [[ "$post_build_target_real" != "$root_real/target" ]]; then
	echo "refusing post-build non-source-local target: $target_dir -> $post_build_target_real" >&2
	exit 78
fi
main_build_witness="$target_dir/classes/org/apache/sysds/hops/Hop.class"
# This path intentionally matches run_exact_candidate_discovery.sh.  The two
# producers execute different runner tests, but certify one shared clean-build
# authority from the same source snapshot.
test_build_witness="$target_dir/test-classes/org/apache/sysds/hops/fedplanner/placement/PlannerSpaceAuditTest.class"
if [[ ! -f "$main_build_witness" || ! -f "$test_build_witness" ]]; then
	echo "clean test compilation is missing required campaign bytecode witnesses" >&2
	exit 70
fi
echo "post_build_target_stat=$(stat -Lc 'device=%d inode=%i type=%F' "$target_dir")" \
	>> "$OUT/RUN_MANIFEST.txt"
echo "main_build_witness_sha256=$(sha256sum "$main_build_witness" | cut -d' ' -f1)" \
	>> "$OUT/RUN_MANIFEST.txt"
echo "test_build_witness_sha256=$(sha256sum "$test_build_witness" | cut -d' ' -f1)" \
	>> "$OUT/RUN_MANIFEST.txt"
if [[ "$(sha256sum "$main_build_witness" | cut -d' ' -f1)" \
		!= "$exact_main_build_witness_sha256" \
	|| "$(sha256sum "$test_build_witness" | cut -d' ' -f1)" \
		!= "$exact_test_build_witness_sha256" ]]; then
	echo "forced campaign bytecode differs from exact discovery authority" >&2
	exit 65
fi

mkdir -p "$OUT/chunk-manifests" "$OUT/chunks"
python3 - "$MANIFEST" "$OUT/chunk-manifests" "$SHARD_INDEX" "$SHARD_COUNT" \
	"${MAX_TARGETS:-0}" "$TARGETS_PER_JVM" > "$OUT/CHUNK_SUMMARY.json" <<'PY'
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
shard_index = int(sys.argv[3])
shard_count = int(sys.argv[4])
max_targets = int(sys.argv[5])
chunk_size = int(sys.argv[6])
rows = [line for line in manifest_path.read_text(encoding="utf-8").splitlines()
	if line.strip()]
selected = [line for index, line in enumerate(rows) if index % shard_count == shard_index]
if max_targets:
	selected = selected[:max_targets]
if not selected:
	raise SystemExit("forced campaign shard selects zero targets")
for offset in range(0, len(selected), chunk_size):
	path = output / f"chunk-{offset // chunk_size:05d}.jsonl"
	path.write_text("\n".join(selected[offset:offset + chunk_size]) + "\n",
		encoding="utf-8")
print(json.dumps({"selectedTargets": len(selected),
	"targetsPerJvm": chunk_size,
	"chunks": (len(selected) + chunk_size - 1) // chunk_size},
	indent=2, sort_keys=True))
PY

maven_rc=0
shopt -s nullglob
chunk_manifests=("$OUT"/chunk-manifests/chunk-*.jsonl)
echo "chunks=${#chunk_manifests[@]}" >> "$OUT/RUN_MANIFEST.txt"
for chunk_manifest in "${chunk_manifests[@]}"; do
	chunk_name="$(basename "$chunk_manifest" .jsonl)"
	chunk_out="$OUT/chunks/$chunk_name"
	mkdir -p "$chunk_out"
	mvn -q \
		-Dtest=org.apache.sysds.test.functions.federated.FederatedForcedStateAuditRunnerTest \
		-Dtest-forkCount=1 \
		-Dtest-threadCount=1 \
		-Dtest-perCoreThreadCount=false \
		-Dsysds.fedplanner.space.audit=true \
		-Dsysds.fedplanner.runtime.audit=true \
		-Dsysds.fedplanner.capability.audit=true \
		"-Dsysds.fedplanner.space.audit.force.manifest=$chunk_manifest" \
		"-Dsysds.fedplanner.space.audit.force.campaign.dir=$chunk_out" \
		-Dsysds.fedplanner.space.audit.force.shard.index=0 \
		-Dsysds.fedplanner.space.audit.force.shard.count=1 \
		test > "$chunk_out/maven.log" 2>&1
	chunk_rc=$?
	echo "$chunk_rc" > "$chunk_out/MAVEN_RC.txt"
	if (( chunk_rc != 0 )); then
		maven_rc=1
	fi
done
echo "maven_rc=$maven_rc" >> "$OUT/RUN_MANIFEST.txt"

python3 - "$MANIFEST" "$OUT" "$SHARD_INDEX" "$SHARD_COUNT" \
	"${MAX_TARGETS:-0}" > "$OUT/CAMPAIGN_SUMMARY.json" <<'PY'
import collections
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
shard_index = int(sys.argv[3])
shard_count = int(sys.argv[4])
max_targets = int(sys.argv[5])

manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines()
	if line.strip()]
expected = [row for index, row in enumerate(manifest) if index % shard_count == shard_index]
if max_targets:
	expected = expected[:max_targets]

results = []
for path in sorted(output.rglob("forced-state-results-*.jsonl")):
	results.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
		if line.strip())
capabilities = []
capability_targets = set()
def target_id_for(path):
	parts = path.relative_to(output).parts
	try:
		position = parts.index("targets")
	except ValueError:
		return None
	return parts[position + 1] if position + 1 < len(parts) else None

for path in sorted(output.rglob("runtime-capability-*.jsonl")):
	target_id = target_id_for(path)
	if target_id:
		capability_targets.add(target_id)
	capabilities.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
		if line.strip())
candidate_rows = 0
for path in output.rglob("candidate-space-*.jsonl"):
	candidate_rows += sum(1 for line in path.read_text(encoding="utf-8").splitlines()
		if line.strip())

expected_ids = {str(row.get("targetId")) for row in expected}
result_ids = {str(row.get("targetId")) for row in results}
outcomes = collections.Counter(str(row.get("outcome")) for row in results)
capability_outcomes = collections.Counter(str(row.get("outcome")) for row in capabilities)
summary = {
	"schema": "fedplanner-forced-campaign-summary-v1",
	"expectedTargets": len(expected),
	"resultRows": len(results),
	"missingResultTargetIds": sorted(expected_ids - result_ids),
	"unexpectedResultTargetIds": sorted(result_ids - expected_ids),
	"duplicateResultRows": len(results) - len(result_ids),
	"constraintSatisfied": sum(row.get("constraintSatisfied") is True for row in results),
	"resultOutcomes": dict(sorted(outcomes.items())),
	"candidateRows": candidate_rows,
	"runtimeCapabilityRows": len(capabilities),
	"runtimeCapabilityOutcomes": dict(sorted(capability_outcomes.items())),
	"targetsWithRuntimeCapability": len(capability_targets),
	"missingRuntimeCapabilityTargetIds": sorted(expected_ids - capability_targets),
	"unexpectedRuntimeCapabilityTargetIds": sorted(capability_targets - expected_ids),
	"jvmChunks": len(list((output / "chunk-manifests").glob("chunk-*.jsonl"))),
	"failedJvmChunks": sum(path.read_text(encoding="utf-8").strip() != "0"
		for path in output.glob("chunks/chunk-*/MAVEN_RC.txt")),
}
summary["infrastructureStatus"] = "PASS" if (
	len(results) == len(expected)
	and not summary["missingResultTargetIds"]
	and not summary["unexpectedResultTargetIds"]
	and summary["duplicateResultRows"] == 0
) else "FAIL"
summary["classificationStatus"] = "ALL_SUCCESS" if (
	outcomes == {"SUCCESS": len(expected)}
	and summary["constraintSatisfied"] == len(expected)
	and not summary["missingRuntimeCapabilityTargetIds"]
	and not summary["unexpectedRuntimeCapabilityTargetIds"]
	and capability_outcomes == {"SUCCESS": len(capabilities)}
) else "NEEDS_TRIAGE"
print(json.dumps(summary, indent=2, sort_keys=True))
PY
summary_rc=$?
echo "summary_rc=$summary_rc" >> "$OUT/RUN_MANIFEST.txt"
if (( summary_rc == 0 )); then
	python3 - "$OUT/CAMPAIGN_SUMMARY.json" "$OUT/RUN_MANIFEST.txt" <<'PY'
import json
import pathlib
import sys

summary = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
with pathlib.Path(sys.argv[2]).open("a", encoding="utf-8") as handle:
	handle.write(f"infrastructure_status={summary['infrastructureStatus']}\n")
	handle.write(f"classification_status={summary['classificationStatus']}\n")
	handle.write(f"expected_targets={summary['expectedTargets']}\n")
	handle.write(f"result_rows={summary['resultRows']}\n")
	handle.write(f"constraint_satisfied={summary['constraintSatisfied']}\n")
	handle.write(f"runtime_capability_rows={summary['runtimeCapabilityRows']}\n")
PY
fi
echo "finished=$(date -Is)" >> "$OUT/RUN_MANIFEST.txt"

verify_campaign_source_receipt > "$OUT/SOURCE_SHA256_CHECK_FINAL.log" 2>&1
final_source_rc=$?
source_input_paths > "$OUT/SOURCE_INPUT_PATHS_FINAL.txt"
cmp -s "$OUT/SOURCE_INPUT_PATHS_BEFORE.txt" "$OUT/SOURCE_INPUT_PATHS_FINAL.txt" \
	|| final_source_rc=65
echo "final_source_receipt_rc=$final_source_rc" >> "$OUT/RUN_MANIFEST.txt"
(( final_source_rc == 0 )) || {
	echo "source changed during forced campaign" >&2; exit 65; }

if (( maven_rc != 0 || summary_rc != 0 )); then
	exit 1
fi
validation_args=("$OUT/CAMPAIGN_SUMMARY.json")
if [[ "$ALLOW_NEEDS_TRIAGE_DIAGNOSTIC" == 1 ]]; then
	validation_args+=(--allow-needs-triage-diagnostic)
fi
python3 scripts/fedplanner/validate_forced_campaign_summary.py "${validation_args[@]}" \
	> "$OUT/AUTHORITATIVE_SUMMARY_VALIDATION.json"
authoritative_validation_rc=$?
echo "authoritative_summary_validation_rc=$authoritative_validation_rc" \
	>> "$OUT/RUN_MANIFEST.txt"

( cd "$OUT" && find . -type f ! -name SHA256SUMS.txt -print0 \
	| LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS.txt )
(( authoritative_validation_rc == 0 ))
