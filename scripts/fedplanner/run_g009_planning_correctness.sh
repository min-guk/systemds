#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

set -uo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
STAMP=$(date +%Y%m%dT%H%M%S%z)
EVIDENCE_DIR=${1:-"$ROOT/build/plan-space-audit-$(date +%Y%m%d)/g009-correctness-$STAMP"}

BOUNDED_TESTS=(
	org.apache.sysds.test.functions.fedplanner.rules.RuleBasicsTest
	org.apache.sysds.test.functions.fedplanner.rules.CovarianceRuleTest
	org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuityTransformEncodeTest
	org.apache.sysds.hops.fedplanner.placement.PlacementRealizationAuthorityTest
	org.apache.sysds.hops.fedplanner.placement.CandidateRealizationCanonicalizationTest
	org.apache.sysds.hops.fedplanner.placement.ExecutableProjectionAuthorityTest
	org.apache.sysds.hops.fedplanner.placement.NativePlacementContinuityTest
	org.apache.sysds.hops.fedplanner.placement.RelocationSelectionsPhysicalAnchorTest
	org.apache.sysds.hops.fedplanner.placement.RelocationActionPlanSpaceCompletenessTest
	org.apache.sysds.hops.fedplanner.placement.CandidateInputBottomDomainTest
	org.apache.sysds.runtime.instructions.fed.ReorgFEDInstructionFullTest
	org.apache.sysds.hops.fedplanner.placement.CandidateAffectingBranchInventoryTest
	org.apache.sysds.hops.fedplanner.placement.GlobalReceiptPlanSpaceCompletenessTest
	org.apache.sysds.hops.fedplanner.placement.CandidateIncomingSupportCompletenessTest
	org.apache.sysds.hops.fedplanner.placement.CampaignBG014B13OtherMatrixScalarRuleFactRedTest
	org.apache.sysds.hops.fedplanner.placement.RelocationPrivacyIndexReuseTest
	org.apache.sysds.hops.fedplanner.placement.CandidateReceiptAssignmentCompletenessTest
	org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.PolicyQuotientIsolationTest
	org.apache.sysds.hops.fedplanner.placement.ProductionDecodedPlanSpaceCompletenessTest
	org.apache.sysds.hops.fedplanner.placement.IndependentPlanSpaceGenerationCompletenessTest
	org.apache.sysds.hops.fedplanner.placement.DynamicNativeLayoutCompositionTest
	org.apache.sysds.hops.fedplanner.placement.NeutralPlacementFixedPointCompositionTest
	org.apache.sysds.hops.fedplanner.placement.NeutralPlacementBindingAssignmentStreamingTest
	org.apache.sysds.hops.fedplanner.placement.SharedPlannerFunctionPlanPropagationRedTest
	org.apache.sysds.hops.fedplanner.placement.LogicalBoundaryRealizationsTest
	org.apache.sysds.hops.fedplanner.placement.NativeLineagePlanSpaceCompletenessTest
)
LONG_TEST=org.apache.sysds.hops.fedplanner.placement.NeutralPlacementGraphUploadRelocationRedTest
LONG_METHOD=rewrittenInlinedOutputRetainsItsCompilerDeclaredTargetAuthority

mkdir -p "$EVIDENCE_DIR" "$EVIDENCE_DIR/bounded-xml" "$EVIDENCE_DIR/long-xml"
cd "$ROOT"

source_manifest() {
	find src/main/java src/test/java -type f -print0 | sort -z | xargs -0 sha256sum
}

join_by_comma() {
	local IFS=,
	echo "$*"
}

cat > "$EVIDENCE_DIR/environment.txt" <<EOF
started_at=$(date --iso-8601=seconds)
head=$(git rev-parse HEAD)
branch=$(git branch --show-current)
java=$(java -version 2>&1 | head -1)
maven=$(mvn -version 2>&1 | head -1)
bounded_class_count=${#BOUNDED_TESTS[@]}
long_test=$LONG_TEST#$LONG_METHOD
timeout_policy=no shell timeout; selected long method has no JUnit timeout
EOF
git status --short --branch > "$EVIDENCE_DIR/git-status-before.txt"
source_manifest > "$EVIDENCE_DIR/source-before.sha256"
git diff --check > "$EVIDENCE_DIR/diff-check-before.txt" 2>&1

bounded_selector=$(join_by_comma "${BOUNDED_TESTS[@]}")
printf 'mvn -Dtest=%q -Dtest-parallel=none -Dtest-threadCount=1 -Dtest-perCoreThreadCount=false -Dtest-forkCount=1 -Drerun.failing.tests.count=0 test\n' \
	"$bounded_selector" > "$EVIDENCE_DIR/commands.txt"
printf 'mvn -f <root-level-32g-pom> -Djacoco.skip=true -Dtest=%q -Dtest-parallel=none -Dtest-threadCount=1 -Dtest-perCoreThreadCount=false -Dtest-forkCount=1 -Drerun.failing.tests.count=0 surefire:test\n' \
	"$LONG_TEST#$LONG_METHOD" >> "$EVIDENCE_DIR/commands.txt"

rm -rf target/surefire-reports
set +e
set -o pipefail
/usr/bin/time -v mvn \
	-Dtest="$bounded_selector" \
	-Dtest-parallel=none -Dtest-threadCount=1 -Dtest-perCoreThreadCount=false \
	-Dtest-forkCount=1 -Drerun.failing.tests.count=0 test \
	2>&1 | tee "$EVIDENCE_DIR/bounded-maven.log"
bounded_exit=${PIPESTATUS[0]}
set +o pipefail
printf '%s\n' "$bounded_exit" > "$EVIDENCE_DIR/bounded-maven.exit"
cp target/surefire-reports/TEST-*.xml "$EVIDENCE_DIR/bounded-xml/" 2>/dev/null || true

rm -rf target/surefire-reports
long_pom="$ROOT/.g009-correctness-pom-$$.xml"
trap 'rm -f "$long_pom"' EXIT
python3 - "$ROOT/pom.xml" "$long_pom" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
old = "<argLine>-Xms3000m -Xmx3000m -Xmn300m</argLine>"
new = "<argLine>-Xms8g -Xmx32g -Xmn1g</argLine>"
if source.count(old) != 1:
	raise SystemExit("expected exactly one default argLine in pom.xml")
Path(sys.argv[2]).write_text(source.replace(old, new))
PY
cp "$long_pom" "$EVIDENCE_DIR/pom-32g.xml"
set -o pipefail
/usr/bin/time -v mvn -f "$long_pom" \
	-Djacoco.skip=true \
	-Dtest="$LONG_TEST#$LONG_METHOD" \
	-Dtest-parallel=none -Dtest-threadCount=1 -Dtest-perCoreThreadCount=false \
	-Dtest-forkCount=1 -Drerun.failing.tests.count=0 surefire:test \
	2>&1 | tee "$EVIDENCE_DIR/long-maven.log"
long_exit=${PIPESTATUS[0]}
set +o pipefail
rm -f "$long_pom"
trap - EXIT
printf '%s\n' "$long_exit" > "$EVIDENCE_DIR/long-maven.exit"
cp target/surefire-reports/TEST-*.xml "$EVIDENCE_DIR/long-xml/" 2>/dev/null || true

source_manifest > "$EVIDENCE_DIR/source-after.sha256"
cmp "$EVIDENCE_DIR/source-before.sha256" "$EVIDENCE_DIR/source-after.sha256" \
	> "$EVIDENCE_DIR/source-hash.cmp"
source_hash_exit=$?
git diff --check > "$EVIDENCE_DIR/diff-check-after.txt" 2>&1
diff_check_exit=$?
git status --short --branch > "$EVIDENCE_DIR/git-status-after.txt"
printf 'finished_at=%s\n' "$(date --iso-8601=seconds)" >> "$EVIDENCE_DIR/environment.txt"

python3 - "$EVIDENCE_DIR" "$bounded_exit" "$long_exit" <<'PY'
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])

def suites(directory):
	rows = []
	for path in sorted(directory.glob("TEST-*.xml")):
		suite = ET.parse(path).getroot()
		rows.append({
			"class": suite.attrib.get("name", path.stem[5:]),
			"tests": int(suite.attrib.get("tests", 0)),
			"failures": int(suite.attrib.get("failures", 0)),
			"errors": int(suite.attrib.get("errors", 0)),
			"skipped": int(suite.attrib.get("skipped", 0)),
			"time_seconds": float(suite.attrib.get("time", 0)),
			"testcases": [
				{
					"name": case.attrib.get("name"),
					"skipped": case.find("skipped") is not None,
					"failed": case.find("failure") is not None,
					"error": case.find("error") is not None,
				}
				for case in suite.findall("testcase")
			],
		})
	return rows

bounded = suites(root / "bounded-xml")
long_run = suites(root / "long-xml")

def totals(rows):
	return {key: sum(row[key] for row in rows) for key in ("tests", "failures", "errors", "skipped")}

by_class = {row["class"]: row for row in bounded}
required_oracles = [
	"org.apache.sysds.hops.fedplanner.placement.GlobalReceiptPlanSpaceCompletenessTest",
	"org.apache.sysds.hops.fedplanner.placement.ProductionDecodedPlanSpaceCompletenessTest",
	"org.apache.sysds.hops.fedplanner.placement.IndependentPlanSpaceGenerationCompletenessTest",
	"org.apache.sysds.hops.fedplanner.placement.CandidateReceiptAssignmentCompletenessTest",
	"org.apache.sysds.hops.fedplanner.placement.NativeLineagePlanSpaceCompletenessTest",
]
oracle_green = all(name in by_class and by_class[name]["failures"] == 0
	and by_class[name]["errors"] == 0 for name in required_oracles)

result = {
	"scope": "declared finite G009 corpus",
	"bounded_maven_exit": int(sys.argv[2]),
	"long_maven_exit": int(sys.argv[3]),
	"bounded": {"totals": totals(bounded), "suites": bounded},
	"long": {"totals": totals(long_run), "suites": long_run},
	"finite_oracle": {
		"complete": int(sys.argv[2]) == 0 and oracle_green,
		"missing_plans": 0 if int(sys.argv[2]) == 0 and oracle_green else None,
		"illegal_extra_plans": 0 if int(sys.argv[2]) == 0 and oracle_green else None,
		"basis": "Passing set-equality assertions in the five independent/bounded oracle suites; mutation sentinels in GlobalReceipt verify both diff directions.",
	},
	"global_proof": {
		"complete": False,
		"reason": "Finite JUnit equality is not a proof for all supported programs; obligation and rule-family closure must be reviewed separately.",
	},
}
(root / "evidence.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
PY
aggregate_exit=$?

if (( bounded_exit != 0 || long_exit != 0 || source_hash_exit != 0 || diff_check_exit != 0
	|| aggregate_exit != 0 )); then
	exit 1
fi
