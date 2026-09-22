#!/usr/bin/env bash
# One-command, fail-closed gate for the closed P/E + historical comparison.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVALUATION_ROOT="${PLAN_SPACE_EVALUATION_ROOT:-$(dirname "$ROOT")/cofee-evaluation}"
BASELINE=ffb7be5bd85367156ed9ea86dacbaff4be0f035d
BRIDGE=d8fbd30b5476a1ceef460c9f3886381a369ac619
CURRENT=snapshot
CASES="$ROOT/src/test/resources/fedplanner/plan-space/closed-comparison-cases.json"
ARTIFACT_ROOT=/grid/3/cofee-lm-sweep-mchoi-20260914/pe-history-20260921
JOBS=4
RESUME=false
CAMPAIGN_MANIFEST=

while (($#)); do
	case "$1" in
		--baseline) BASELINE="$2"; shift 2 ;;
		--bridge) BRIDGE="$2"; shift 2 ;;
		--current) CURRENT="$2"; shift 2 ;;
		--manifest) CASES="$2"; shift 2 ;;
		--campaign-manifest) CAMPAIGN_MANIFEST="$2"; shift 2 ;;
		--artifact-root) ARTIFACT_ROOT="$2"; shift 2 ;;
		--jobs) JOBS="$2"; shift 2 ;;
		--resume) RESUME=true; shift ;;
		*) printf 'Unknown argument: %s\n' "$1" >&2; exit 64 ;;
	esac
done

if [[ "$BASELINE" != ffb7be5bd85367156ed9ea86dacbaff4be0f035d ||
	"$BRIDGE" != d8fbd30b5476a1ceef460c9f3886381a369ac619 ||
	"$CURRENT" != snapshot ]]; then
	printf '%s\n' 'Pinned version boundary changed' >&2
	exit 64
fi
if [[ "$(git -C "$ROOT" rev-parse "$BRIDGE^")" != "$BASELINE" ]]; then
	printf '%s\n' 'Baseline ancestry changed' >&2
	exit 1
fi
if [[ ! "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
	printf '%s\n' 'Jobs must be positive' >&2
	exit 64
fi
mkdir -p "$ARTIFACT_ROOT"
python3 "$ROOT/scripts/fedplanner/build_closed_comparison_cases.py" --check --output "$CASES"
python3 "$ROOT/scripts/fedplanner/snapshot_plan_space_versions.py" create \
	--output "$ARTIFACT_ROOT/snapshots" > "$ARTIFACT_ROOT/latest-source-snapshot.json"

python3 - "$CASES" "$CAMPAIGN_MANIFEST" "$ARTIFACT_ROOT" <<'PY'
import json
from pathlib import Path
import sys

cases, campaign, root = sys.argv[1:]
catalog = json.loads(Path(cases).read_text())
pending = [(cell['id'], pair['left'], pair['right'])
           for cell in catalog['cells'] for pair in cell['expectedPairs']
           if pair['applicability'] == 'UNDETERMINED']
unfrozen = [cell['id'] for cell in catalog['cells']
            if cell['inventoryStatus'] == 'IN_SCOPE' and cell['nativeInputCapture'] != 'COMPLETE']
result = {'contract': 'closed-model-pe-history-v1', 'status': 'INCOMPLETE',
          'unclassifiedHistoricalPairs': len(pending),
          'uncapturedCurrentCells': len(unfrozen),
          'campaignManifestPresent': bool(campaign and Path(campaign).is_file()),
          'runtimeSemanticCoverage': 'NOT_ASSESSED_BY_THIS_CONTRACT'}
Path(root, 'preflight.json').write_text(json.dumps(result, sort_keys=True, indent=2) + '\n')
if pending or unfrozen or not result['campaignManifestPresent']:
    print(json.dumps(result, sort_keys=True))
    sys.exit(2)
PY

SNAPSHOT="$(python3 - "$ARTIFACT_ROOT/latest-source-snapshot.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['snapshot'])
PY
)"
python3 "$ROOT/scripts/fedplanner/snapshot_plan_space_versions.py" check --snapshot "$SNAPSHOT"
(
	cd "$SNAPSHOT/trees/systemds"
	mvn -q -Dtest=FullProductionJointPlanExportTest,ExactPhysicalRawSpaceExporterTest,ExactPhysicalPlanSpaceExporterTest,PlanSpaceFixtureArtifactAdapterTest test
)
args=(run --manifest "$CAMPAIGN_MANIFEST" --artifact-root "$ARTIFACT_ROOT" --jobs "$JOBS")
if "$RESUME"; then args+=(--resume); fi
if RUN_RESULT="$(python3 "$EVALUATION_ROOT/calibration/plan_space_compare.py" "${args[@]}")"; then
	RUN_STATUS=0
else
	RUN_STATUS=$?
fi
if ((RUN_STATUS > 1)); then
	printf '%s\n' "$RUN_RESULT" >&2
	exit "$RUN_STATUS"
fi
printf '%s\n' "$RUN_RESULT"
CERTIFICATE="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["certificate"])' "$RUN_RESULT")"
CAMPAIGN_SHA="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["campaignSha256"])' "$RUN_RESULT")"
python3 "$EVALUATION_ROOT/calibration/plan_space_compare.py" check-certificate \
	--certificate "$CERTIFICATE" --expected-campaign-sha "$CAMPAIGN_SHA" --offline
