#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
MODE=${1:-prepare}
if [[ $# -gt 0 ]]; then shift; fi
OUT=${G009_P0_OUT:-"$ROOT/build/g009-p0/$(date +%Y%m%dT%H%M%S%z)"}
HARNESS=${G009_DOCKER_HARNESS:-}
IDENTITY=${G009_GLM_IDENTITY:-}
VALIDATOR=${G009_GLM_VALIDATOR:-}
ALLOWLIST=${G009_GLM_ALLOWLIST:-}
STAGE_DESCRIPTOR=${G009_STAGE_DESCRIPTOR:-}
COMMAND_RECEIPT=${G009_COMMAND_RECEIPT:-}
PLANNING_RECEIPT=${G009_PLANNING_RECEIPT:-}
COORDINATOR_LOG=${G009_COORDINATOR_LOG:-}
RUN_STDOUT=${G009_RUN_STDOUT:-}
JAR="$ROOT/target/SystemDS.jar"

usage() {
	cat <<EOF
Usage:
  $0 prepare
  G009_DOCKER_HARNESS=/absolute/path/run_LAN_docker.sh $0 docker-smoke -- <fixed args>
  G009_STAGE_DESCRIPTOR=... G009_RUN_TOKEN=... $0 qualify-glm

prepare records a reproducible source/class/JAR/config/command artifact. Only
qualify-glm can set the official flag only through the stage-owned runner and
validator contract. docker-smoke is always diagnostic.
EOF
}

[[ "$MODE" == prepare || "$MODE" == docker-smoke || "$MODE" == qualify-glm ]] \
	|| { usage >&2; exit 2; }
if [[ ! -f "$JAR" ]]; then
	echo "Missing $JAR; run: mvn -DskipTests package" >&2
	exit 2
fi
if [[ -e "$OUT" || -L "$OUT" ]]; then
	echo "Refusing existing or symlink output path: $OUT" >&2
	exit 2
fi

mkdir -p "$OUT/classes" "$OUT/config"
git -C "$ROOT" status --short --branch > "$OUT/git-status.txt"
git -C "$ROOT" rev-parse HEAD > "$OUT/git-head.txt"
git -C "$ROOT" diff --binary > "$OUT/source.patch"
tar -C "$ROOT" -czf "$OUT/source-snapshot.tar.gz" src/main/java src/test/java conf
find "$ROOT/src/main/java" "$ROOT/src/test/java" -type f -print0 \
	| sort -z | xargs -0 sha256sum > "$OUT/source.sha256"
sha256sum "$JAR" > "$OUT/SystemDS.jar.sha256"
cp "$JAR" "$OUT/SystemDS.jar"

mapfile -d '' CLASS_FILES < <(find "$ROOT/target/classes" -type f -name '*.class' \
	\( -path '*/org/apache/sysds/hops/fedplanner/placement/*' \
		-o -path '*/org/apache/sysds/hops/fedplanner/fedCostBased/fedExact/*' \
		-o -path '*/org/apache/sysds/parser/DMLTranslator*.class' \
		-o -path '*/org/apache/sysds/utils/Statistics*.class' \) -print0 | sort -z)
[[ ${#CLASS_FILES[@]} -gt 0 ]] || { echo "No compiled P0 class tree; package first" >&2; exit 2; }
for path in "${CLASS_FILES[@]}"; do
	class=${path#"$ROOT/target/classes/"}
	sha256sum "$path" >> "$OUT/classes.sha256"
	target_hash=$(sha256sum "$path" | awk '{print $1}')
	jar_hash=$(unzip -p "$JAR" "$class" | sha256sum | awk '{print $1}')
	[[ "$target_hash" == "$jar_hash" ]] \
		|| { echo "JAR contains stale class: $class" >&2; exit 2; }
	printf '%s  %s\n' "$jar_hash" "$class" >> "$OUT/jar-classes.sha256"
done

find "$ROOT/conf" -maxdepth 2 -type f -print0 | sort -z | xargs -0 sha256sum \
	> "$OUT/config/all-conf.sha256"
cat > "$OUT/commands.txt" <<EOF
mvn -DskipTests package
G009_P0_OUT='<new-output>' '$0' prepare
G009_P0_OUT='<new-output>' G009_DOCKER_HARNESS='<absolute-run_LAN_docker.sh>' '$0' docker-smoke -- <fixed-LM-args>
G009_P0_OUT='<new-output>' G009_STAGE_DESCRIPTOR='<stage>/stage-descriptor.json' G009_RUN_TOKEN='<token>' '$0' qualify-glm
EOF

python3 - "$ROOT" "$OUT" "$MODE" "$JAR" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root, out, mode, jar = map(pathlib.Path, sys.argv[1:])
def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()
def artifact(name):
    return {"path": name, "sha256": sha(out / name)}
manifest = {
    "schema": "g009-p0-candidate-e2e-baseline-v1",
    "qualification": "Docker-only run_LAN_docker.sh; host results are diagnostic only",
    "mode": str(mode),
    "source_root": str(root.resolve()),
    "git_head": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
    "jar": {"path": str((out / "SystemDS.jar").resolve()), "sha256": sha(out / "SystemDS.jar")},
    "metric": "Compile Phase FedPlanner CandidateE2E Total",
    "boundary": "before final physical normalization through successful receiptConsumer return",
    "fresh_jvm_required": True,
    "trace_required": False,
    "official_glm_executed": False,
    "files": {
        name: artifact(name) for name in (
            "source.sha256", "source-snapshot.tar.gz", "source.patch", "git-status.txt",
            "git-head.txt", "classes.sha256", "jar-classes.sha256",
            "config/all-conf.sha256", "commands.txt")
    },
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
PY

if [[ "$MODE" == prepare ]]; then
	echo "$OUT"
	exit 0
fi

if [[ "$MODE" == qualify-glm ]]; then
	[[ $# -eq 0 ]] || { echo "qualify-glm accepts no arbitrary harness arguments" >&2; exit 2; }
	[[ -z $(git -C "$ROOT" status --porcelain) ]] \
		|| { echo "qualify-glm requires a clean current SystemDS worktree" >&2; exit 2; }
	CURRENT_HEAD=$(git -C "$ROOT" rev-parse HEAD)
	CURRENT_TREE=$(git -C "$ROOT" write-tree)
	[[ -n "$STAGE_DESCRIPTOR" && -f "$STAGE_DESCRIPTOR" && ! -L "$STAGE_DESCRIPTOR" ]] \
		|| { echo "qualify-glm requires regular G009_STAGE_DESCRIPTOR" >&2; exit 2; }
	[[ -n ${G009_RUN_TOKEN:-} ]] || { echo "qualify-glm requires G009_RUN_TOKEN" >&2; exit 2; }
	stage_root=$(dirname "$(realpath "$STAGE_DESCRIPTOR")")
	HARNESS="$stage_root/harness/experiments/run_g009_glm_p0.sh"
	VALIDATOR="$stage_root/harness/experiments/tools/g009_glm_contract.py"
	ALLOWLIST="$stage_root/harness/experiments/config/g009_glm_workload_allowlist_v1.json"
	for required_path in "$HARNESS" "$VALIDATOR" "$ALLOWLIST"; do
		[[ -f "$required_path" && ! -L "$required_path" ]] \
			|| { echo "qualify-glm stage tool missing or symlinked: $required_path" >&2; exit 2; }
	done
	RUN_COMMAND=("$HARNESS" --stage-descriptor "$(realpath "$STAGE_DESCRIPTOR")" \
		--run-token "$G009_RUN_TOKEN")
else
	[[ ${1:-} == -- ]] || { echo "docker-smoke requires -- followed by fixed harness args" >&2; exit 2; }
	shift
	[[ $# -gt 0 ]] || { echo "docker-smoke requires explicit fixed harness args" >&2; exit 2; }
	[[ -n "$HARNESS" && -x "$HARNESS" && $(basename "$HARNESS") == run_LAN_docker.sh ]] \
		|| { echo "G009_DOCKER_HARNESS must name executable run_LAN_docker.sh" >&2; exit 2; }
	HARNESS=$(realpath "$HARNESS")
	RUN_COMMAND=("$HARNESS" "$@")
	for required_path in "$PLANNING_RECEIPT" "$COORDINATOR_LOG"; do
		[[ -n "$required_path" ]] \
			|| { echo "docker-smoke requires explicit receipt and coordinator log paths" >&2; exit 2; }
	done
fi
jar_sha256=$(sha256sum "$JAR" | awk '{print $1}')
harness_sha256=$(sha256sum "$HARNESS" | awk '{print $1}')
argv_sha256=$(python3 - "${RUN_COMMAND[@]:1}" <<'PY'
import hashlib, json, sys
encoded = json.dumps(sys.argv[1:], ensure_ascii=False, separators=(",", ":")).encode()
print(hashlib.sha256(encoded).hexdigest())
PY
)
if [[ -n "$IDENTITY" ]]; then
	[[ -f "$IDENTITY" && ! -L "$IDENTITY" ]] || { echo "Invalid optional identity archive" >&2; exit 2; }
	cp "$IDENTITY" "$OUT/glm-identity-untrusted.json"
fi
docker info >/dev/null
printf '%q ' "${RUN_COMMAND[@]}" > "$OUT/qualification-command.txt"
printf '\n' >> "$OUT/qualification-command.txt"
{
	echo "started_at=$(date --iso-8601=seconds)"
	echo "docker_server=$(docker version --format '{{.Server.Version}}')"
	echo "harness=$HARNESS"
	echo "jar_sha256=$jar_sha256"
	echo "harness_sha256=$harness_sha256"
	echo "argv_sha256=$argv_sha256"
	sha256sum "$HARNESS"
} > "$OUT/docker-environment.txt"

set +e
set -o pipefail
"${RUN_COMMAND[@]}" 2>&1 | tee "$OUT/qualification.log"
status=${PIPESTATUS[0]}
set +o pipefail
set -e
printf '%s\n' "$status" > "$OUT/qualification.exit"
if [[ $status -ne 0 ]]; then
	exit "$status"
fi
if [[ "$MODE" == qualify-glm ]]; then
	mapfile -t command_markers < <(sed -n 's/^COMMAND_RECEIPT=//p' "$OUT/qualification.log")
	mapfile -t stdout_markers < <(sed -n 's/^RUN_STDOUT=//p' "$OUT/qualification.log")
	[[ ${#command_markers[@]} -eq 1 && ${#stdout_markers[@]} -eq 1 ]] \
		|| { echo "official runner did not emit one command/stdout path" >&2; exit 1; }
	COMMAND_RECEIPT=${command_markers[0]}
	RUN_STDOUT=${stdout_markers[0]}
	[[ -f "$COMMAND_RECEIPT" && ! -L "$COMMAND_RECEIPT" && -f "$RUN_STDOUT" && ! -L "$RUN_STDOUT" ]] \
		|| { echo "official runner receipt/stdout path invalid" >&2; exit 1; }
	mapfile -t planning_markers < <(sed -n 's/^PLANNING_RECEIPT=//p' "$RUN_STDOUT")
	[[ ${#planning_markers[@]} -eq 1 ]] \
		|| { echo "runner stdout does not contain exactly one planning receipt" >&2; exit 1; }
	PLANNING_RECEIPT=${planning_markers[0]}
	COORDINATOR_LOG=$(python3 - "$PLANNING_RECEIPT" <<'PY'
import json, pathlib, sys
print(pathlib.Path(json.loads(pathlib.Path(sys.argv[1]).read_text())["coordinator_log"]).resolve())
PY
)
fi
RECEIPT_STREAM=${RUN_STDOUT:-$OUT/qualification.log}
python3 - "$RECEIPT_STREAM" "$PLANNING_RECEIPT" "$COORDINATOR_LOG" <<'PY'
import hashlib, json, pathlib, re, sys
wrapper = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
markers = [line.split("=", 1)[1].strip() for line in wrapper if line.startswith("PLANNING_RECEIPT=")]
if len(markers) != 1:
    raise SystemExit(f"expected exactly one PLANNING_RECEIPT marker, found {len(markers)}")
declared_receipt = pathlib.Path(sys.argv[2]).resolve()
if pathlib.Path(markers[0]).resolve() != declared_receipt or not declared_receipt.is_file() or declared_receipt.is_symlink():
    raise SystemExit("wrapper planning receipt does not match explicit regular receipt")
receipt = json.loads(declared_receipt.read_text())
declared_log = pathlib.Path(sys.argv[3]).resolve()
if pathlib.Path(receipt.get("coordinator_log", "")).resolve() != declared_log \
        or not declared_log.is_file() or declared_log.is_symlink():
    raise SystemExit("planning receipt coordinator log does not match explicit regular log")
actual_hash = hashlib.sha256(declared_log.read_bytes()).hexdigest()
if receipt.get("coordinator_log_sha256") != actual_hash:
    raise SystemExit("planning receipt coordinator log hash mismatch")
PY
grep -F "CandidateE2EReceipt schema=candidate-e2e-v1" "$COORDINATOR_LOG" \
	> "$OUT/candidate-e2e-statistics.txt" || true
python3 - "$COORDINATOR_LOG" "$OUT/candidate-e2e-statistics.txt" "$MODE" \
	"$OUT/qualification.log" <<'PY'
import pathlib, re, sys
log = pathlib.Path(sys.argv[1]).read_text(errors="replace").splitlines()
rows = [(i, line) for i, line in enumerate(log) if "CandidateE2EReceipt schema=candidate-e2e-v1" in line]
if len(rows) != 1:
    raise SystemExit(f"expected exactly one CandidateE2E receipt, found {len(rows)}")
index, row = rows[0]
pairs = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=(-?[0-9]+)", row))
phases = ("commonPreparationNanos", "analysisNanos", "plannerSetupNanos", "modelNanos",
          "costSurfaceNanos", "optimizerNanos", "selectionNanos", "otherPlanningNanos",
          "diagnosticsNanos", "conversionNanos", "applicationNanos",
          "finalVerificationNanos", "registrationNanos", "receiptHandoffNanos")
required = phases + ("totalNanos", "calls", "exactPhaseCalls")
missing = [name for name in required if name not in pairs]
if missing:
    raise SystemExit(f"missing timing fields: {missing}")
values = {name: int(pairs[name]) for name in required}
if any(values[name] < 0 for name in phases + ("totalNanos",)):
    raise SystemExit("negative timing field")
if values["totalNanos"] <= 0:
    raise SystemExit("non-positive candidate E2E total")
if sum(values[name] for name in phases) != values["totalNanos"]:
    raise SystemExit("exclusive phase sum differs from total")
if sys.argv[3] == "qualify-glm":
    if values["calls"] != 1 or values["exactPhaseCalls"] != 1:
        raise SystemExit("official GLM requires calls=1 and exactPhaseCalls=1")
elif values["calls"] < 1 or not 0 <= values["exactPhaseCalls"] <= values["calls"]:
    raise SystemExit("invalid smoke invocation counts")
fatal = re.compile(r"FATAL|OutOfMemoryError|DMLRuntimeException|\bERROR\b|\bTIMEOUT\b|"
                   r"Exception in thread|BUILD FAILURE", re.IGNORECASE)
wrapper = pathlib.Path(sys.argv[4]).read_text(errors="replace").splitlines()
if any(fatal.search(line) for line in log + wrapper):
    raise SystemExit("fatal marker present in qualification log")
pathlib.Path(sys.argv[2]).write_text(row + "\n")
PY
if [[ "$MODE" == qualify-glm ]]; then
	proof="$OUT/g009-glm-p0-proof.json"
	python3 "$VALIDATOR" validate-stage-run \
		--allowlist "$ALLOWLIST" \
		--harness-root "$(dirname "$(dirname "$VALIDATOR")")" \
		--stage-descriptor "$STAGE_DESCRIPTOR" \
		--command-receipt "$COMMAND_RECEIPT" \
		--planning-receipt "$PLANNING_RECEIPT" \
		--coordinator-log "$COORDINATOR_LOG" \
		--output "$proof" | tee "$OUT/stage-validator.log"
	python3 - "$proof" "$jar_sha256" "$CURRENT_HEAD" "$CURRENT_TREE" \
		"$STAGE_DESCRIPTOR" "$HARNESS" "$VALIDATOR" "$ALLOWLIST" \
		"$COMMAND_RECEIPT" "$PLANNING_RECEIPT" "$COORDINATOR_LOG" <<'PY'
import hashlib, json, pathlib, sys
p = pathlib.Path(sys.argv[1]); proof = json.loads(p.read_text())
claimed = proof.get("payload_sha256")
payload = dict(proof); payload.pop("payload_sha256", None)
canonical = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
if proof.get("schema") != "g009-glm-p0-proof-v1" or claimed != hashlib.sha256(canonical).hexdigest():
    raise SystemExit("invalid stage-validator proof payload")
jar, head, tree = sys.argv[2:5]
descriptor, runner, validator, allowlist, command, receipt, log = map(pathlib.Path, sys.argv[5:12])
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
stage, contracts = proof.get("stage", {}), proof.get("contracts", {})
invocation, result = proof.get("invocation", {}), proof.get("result", {})
executed_jar = pathlib.Path(stage.get("executed_jar", ""))
checks = {
    "stage.jar_sha256": stage.get("jar_sha256") == jar,
    "stage.systemds_commit": stage.get("systemds_commit") == head,
    "stage.systemds_tree": stage.get("systemds_tree") == tree,
    "stage.descriptor": pathlib.Path(stage.get("descriptor", "")).resolve() == descriptor.resolve(),
    "stage.descriptor_sha256": stage.get("descriptor_sha256") == sha(descriptor),
    "stage.executed_jar": executed_jar.is_file() and not executed_jar.is_symlink()
                          and sha(executed_jar) == jar,
    "stage.stage_id": bool(stage.get("stage_id")),
    "stage.harness_commit": bool(stage.get("harness_commit")),
    "stage.harness_tree": bool(stage.get("harness_tree")),
    "stage.harness_tree_sha256": bool(stage.get("harness_tree_sha256")),
    "contracts.runner_sha256": contracts.get("runner_sha256") == sha(runner),
    "contracts.validator_sha256": contracts.get("validator_sha256") == sha(validator),
    "contracts.allowlist_sha256": contracts.get("allowlist_sha256") == sha(allowlist),
    "invocation.command_receipt": pathlib.Path(invocation.get("command_receipt", "")).resolve() == command.resolve(),
    "invocation.command_receipt_sha256": invocation.get("command_receipt_sha256") == sha(command),
    "result.planning_receipt": pathlib.Path(result.get("planning_receipt", "")).resolve() == receipt.resolve(),
    "result.planning_receipt_sha256": result.get("planning_receipt_sha256") == sha(receipt),
    "result.coordinator_log": pathlib.Path(result.get("coordinator_log", "")).resolve() == log.resolve(),
    "result.coordinator_log_sha256": result.get("coordinator_log_sha256") == sha(log),
    "runtime": bool(proof.get("runtime")),
    "invocation.argv_sha256": bool(invocation.get("argv_sha256")),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("stage proof is not bound to current source/artifacts: " + ",".join(failed))
PY
fi
find "$ROOT/src/main/java" "$ROOT/src/test/java" -type f -print0 \
	| sort -z | xargs -0 sha256sum > "$OUT/source-after.sha256"
cmp "$OUT/source.sha256" "$OUT/source-after.sha256"
python3 - "$OUT/manifest.json" "$MODE" "$HARNESS" "$argv_sha256" \
	"$PLANNING_RECEIPT" "$COORDINATOR_LOG" "$VALIDATOR" "$COMMAND_RECEIPT" "$RUN_STDOUT" \
	"$STAGE_DESCRIPTOR" "$ALLOWLIST" "${proof:-}" <<'PY'
import hashlib, json, pathlib, sys
p = pathlib.Path(sys.argv[1]); data = json.loads(p.read_text())
data["official_glm_executed"] = sys.argv[2] == "qualify-glm"
data["qualification_workload"] = "glm" if sys.argv[2] == "qualify-glm" else "diagnostic-smoke"
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
data["runtime_identity"] = {
    "runner": {"path": str(pathlib.Path(sys.argv[3]).resolve()),
               "sha256": sha(pathlib.Path(sys.argv[3]))},
    "argv_sha256": sys.argv[4],
    "planning_receipt": {"path": str(pathlib.Path(sys.argv[5]).resolve()),
                         "sha256": sha(pathlib.Path(sys.argv[5]))},
    "coordinator_log": {"path": str(pathlib.Path(sys.argv[6]).resolve()),
                        "sha256": sha(pathlib.Path(sys.argv[6]))},
}
if sys.argv[7]:
    data["runtime_identity"]["validator"] = {"path": str(pathlib.Path(sys.argv[7]).resolve()),
                                              "sha256": sha(pathlib.Path(sys.argv[7]))}
if sys.argv[8]:
    data["runtime_identity"]["command_receipt"] = {"path": str(pathlib.Path(sys.argv[8]).resolve()),
                                                    "sha256": sha(pathlib.Path(sys.argv[8]))}
if sys.argv[9]:
    data["runtime_identity"]["run_stdout"] = {"path": str(pathlib.Path(sys.argv[9]).resolve()),
                                              "sha256": sha(pathlib.Path(sys.argv[9]))}
if sys.argv[10]:
    data["runtime_identity"]["stage_descriptor"] = {"path": str(pathlib.Path(sys.argv[10]).resolve()),
                                                     "sha256": sha(pathlib.Path(sys.argv[10]))}
if sys.argv[11]:
    data["runtime_identity"]["allowlist"] = {"path": str(pathlib.Path(sys.argv[11]).resolve()),
                                              "sha256": sha(pathlib.Path(sys.argv[11]))}
if sys.argv[12]:
    proof = json.loads(pathlib.Path(sys.argv[12]).read_text())
    data["runtime_identity"]["validator_proof"] = {
        key: proof[key] for key in ("schema", "payload_sha256", "stage", "contracts",
                                   "runtime", "invocation", "result")
    }
for name in ("glm-identity-untrusted.json", "qualification.log", "qualification.exit",
             "docker-environment.txt", "qualification-command.txt",
             "candidate-e2e-statistics.txt", "source-after.sha256",
             "g009-glm-p0-proof.json", "stage-validator.log"):
    path = p.parent / name
    if path.exists():
        data["files"][name] = {"path": name, "sha256": sha(path)}
p.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
PY
echo "$OUT"
