#!/usr/bin/env python3
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import build_exact_discovery_inventory as subject


class ExactDiscoveryInventoryTest(unittest.TestCase):
	def setUp(self):
		self.temp = tempfile.TemporaryDirectory()
		self.root = Path(self.temp.name)
		(self.root / "candidates").mkdir()
		(self.root / "reports").mkdir()

	def tearDown(self):
		self.temp.cleanup()

	def candidate(self, context):
		(self.root / "candidates" / "candidate-space-1.jsonl").write_text(
			json.dumps({"auditContext": context}) + "\n", encoding="utf-8")

	def report(self, cases):
		suite = ET.Element("testsuite")
		for class_name, name, status in cases:
			case = ET.SubElement(suite, "testcase", classname=class_name, name=name)
			if status != "PASS":
				ET.SubElement(case, "failure" if status == "FAIL" else status.lower())
		ET.ElementTree(suite).write(self.root / "reports" / "TEST-fixture.xml")

	def test_expands_parameterized_method_to_exact_leaves(self):
		context = "org.apache.sysds.test.functions.Fixture#layout"
		self.candidate(context)
		self.report([("org.apache.sysds.test.functions.Fixture", "layout[ROW]", "PASS"),
			("org.apache.sysds.test.functions.Fixture", "layout[COL]", "PASS")])
		leaves = subject.exact_passing_leaves([self.root / "reports"],
			subject.candidate_contexts([self.root / "candidates"]))
		self.assertEqual(leaves, [context + "[COL]", context + "[ROW]"])

	def test_rejects_failed_leaf_and_empty_candidates(self):
		with self.assertRaisesRegex(ValueError, "zero candidate files"):
			subject.candidate_contexts([self.root / "candidates"])
		self.candidate("org.apache.sysds.test.functions.Fixture#layout")
		self.report([("org.apache.sysds.test.functions.Fixture", "layout[ROW]", "FAIL")])
		with self.assertRaisesRegex(ValueError, "did not all pass"):
			subject.exact_passing_leaves([self.root / "reports"],
				subject.candidate_contexts([self.root / "candidates"]))


if __name__ == "__main__":
	unittest.main()
