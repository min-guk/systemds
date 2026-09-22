#!/usr/bin/env bash
# Bounded regression gate and explicit full-corpus certification handoff.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EVALUATION_ROOT="${PLAN_SPACE_EVALUATION_ROOT:-$(dirname "$ROOT")/cofee-evaluation}"
MODE="${1:-tiny}"

if [[ "$MODE" != tiny && "$MODE" != full ]]; then
	printf 'usage: %s tiny|full\n' "$0" >&2
	exit 64
fi

cd "$ROOT"
python3 scripts/fedplanner/build_plan_space_inventory.py \
	--evaluation-root "$EVALUATION_ROOT" --check
python3 scripts/fedplanner/build_plan_space_rule_ledger.py --check
python3 scripts/fedplanner/build_plan_space_exclusion_ledger.py --check
python3 "$EVALUATION_ROOT/calibration/build_plan_space_cells.py" --check
python3 -m unittest discover -s scripts/fedplanner/tests \
	-p 'test_*.py'
python3 -m unittest discover -s "$EVALUATION_ROOT/calibration/tests" \
	-p 'test_*plan_space*.py'
mvn -q -Dtest=PrimitivePlanEnumeratorTest,RuntimeCapabilityOracleTest,MatrixCapabilityOracleTest,StateCapabilityOracleTest,PrebuilderSnapshotTest,PlanningTemplateSnapshotSmokeTest,ProductionPlanSpaceCaptureTest,ProductionJointPlanSpaceProbeTest,FullProductionJointPlanExportTest,PlanSpaceFixtureArtifactAdapterTest,ExactPhysicalRawSpaceExporterTest,ExactPhysicalPlanSpaceExporterTest,ProductionDecodedPlanSpaceCompletenessTest,OracleIndependenceContractTest test
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

for name in ("PrimitivePlanEnumeratorTest", "RuntimeCapabilityOracleTest",
             "MatrixCapabilityOracleTest", "StateCapabilityOracleTest", "PrebuilderSnapshotTest",
             "PlanningTemplateSnapshotSmokeTest",
             "ProductionPlanSpaceCaptureTest", "ProductionJointPlanSpaceProbeTest",
             "FullProductionJointPlanExportTest", "PlanSpaceFixtureArtifactAdapterTest",
             "ExactPhysicalRawSpaceExporterTest",
             "ExactPhysicalPlanSpaceExporterTest", "ProductionDecodedPlanSpaceCompletenessTest",
             "OracleIndependenceContractTest"):
    reports = list(Path("target/surefire-reports").glob(f"TEST-*{name}.xml"))
    if len(reports) != 1:
        raise SystemExit(f"missing or ambiguous Surefire report for {name}")
    suite = ET.parse(reports[0]).getroot()
    if (int(suite.get("tests", "0")) - int(suite.get("skipped", "0")) < 1 or
            int(suite.get("failures", "0")) or int(suite.get("errors", "0"))):
        raise SystemExit(f"no passing executed Java tests for {name}")
PY

if [[ "$MODE" == tiny ]]; then
	printf '%s\n' 'BOUNDED_PASS: independent literal, P/E probe, and capture regressions passed; full workload certification not run.'
	exit 0
fi

if [[ -z "${PLAN_SPACE_ARTIFACT_ROOT:-}" ]]; then
	printf '%s\n' 'INCOMPLETE: full certification needs PLAN_SPACE_ARTIFACT_ROOT.' >&2
	exit 2
fi

python3 "$EVALUATION_ROOT/calibration/plan_space_verify.py" verify \
	--manifest "${PLAN_SPACE_MANIFEST:-$EVALUATION_ROOT/plan-space-cells.json}" --suite full \
	--artifact-root "$PLAN_SPACE_ARTIFACT_ROOT" \
	--expected-discovery-sha "$(sha256sum src/test/resources/fedplanner/plan-space/workloads.json | cut -d ' ' -f1)" \
	--jobs "${PLAN_SPACE_JOBS:-1}" --resume
