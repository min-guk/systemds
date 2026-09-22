#!/usr/bin/env python3
"""Freeze registered planner rule families and audited transformations as OPEN work."""

import argparse
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
RULES = ROOT / "src/main/java/org/apache/sysds/hops/fedplanner/rules/RulesCore.java"
AUDIT = ROOT / "docs/PLAN_SPACE_AUDIT_2026-09-18.md"
OUTPUT = ROOT / "src/test/resources/fedplanner/plan-space/rule-ledger.json"
AUDITED_TREES = (
	ROOT / "src/main/java/org/apache/sysds/hops/fedplanner",
	ROOT / "src/main/java/org/apache/sysds/runtime/instructions/fed",
)


def sha(path):
	return hashlib.sha256(path.read_bytes()).hexdigest()


def build():
	rules = []
	for number, line in enumerate(RULES.read_text().splitlines(), 1):
		match = re.search(r'rr\.register\(new Rulesets\.([A-Za-z0-9_]+)\(\)\);', line)
		if match:
			rules.append({"id": match.group(1), "registryLine": number,
				"runtimeTupleCoverage": "OPEN", "positiveFixture": None,
				"negativeFixture": None, "reason": "Runtime branch/axis parity not certified"})
	if len(rules) != 48:
		raise ValueError(f"expected audited 48 families; found {len(rules)}; review registry drift")
	transforms = []
	for line in AUDIT.read_text().splitlines():
		match = re.match(r'^\| ([UCARP]\d\d) \| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|', line)
		if match:
			ident, source, description, category, status = (x.strip() for x in match.groups())
			transforms.append({"id": ident, "source": source, "description": description,
				"category": category, "preservationProof": status})
	if len(transforms) != 56 or len({row['id'] for row in transforms}) != 56:
		raise ValueError(f"expected audited 56 unique transformations; found {len(transforms)}")
	# Conservative invalidation: a changed or newly added rule, builder, selector
	# or FED runtime file forces the ledger to be reviewed and regenerated. This
	# does not itself prove the 56 historical transformation rows are complete.
	files = sorted(path for tree in AUDITED_TREES for path in tree.rglob("*.java"))
	if not files:
		raise ValueError("audited production source trees are absent")
	audited_sources = {str(path.relative_to(ROOT)): sha(path) for path in files}
	return {"schemaVersion": 1, "certificationStatus": "OPEN",
		"ruleSourceSha256": sha(RULES), "auditSourceSha256": sha(AUDIT),
		"auditedSourceFiles": audited_sources,
		"ruleFamilies": rules, "transformations": transforms}


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--check", action="store_true")
	args = parser.parse_args()
	content = json.dumps(build(), indent=2, ensure_ascii=False) + "\n"
	if args.check:
		if not OUTPUT.is_file() or OUTPUT.read_text() != content:
			parser.error("rule ledger drift; regenerate and review")
		print("rule ledger matches current source and audit")
	else:
		OUTPUT.parent.mkdir(parents=True, exist_ok=True)
		OUTPUT.write_text(content)
		print(f"wrote {OUTPUT}")


if __name__ == "__main__":
	main()
