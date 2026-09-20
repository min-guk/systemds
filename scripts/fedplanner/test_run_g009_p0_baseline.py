#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

from __future__ import annotations

import re
import unittest
from pathlib import Path


RUNNER = Path(__file__).with_name("run_g009_p0_baseline.sh")


def qualification_fatal_pattern() -> re.Pattern[str]:
	source = RUNNER.read_text(encoding="utf-8")
	match = re.search(
		r"(?ms)^fatal = re\.compile\((.*?)\)\nwrapper = pathlib\.Path", source)
	if match is None:
		raise AssertionError("qualification fatal-marker pattern not found")
	namespace = {"re": re}
	exec("fatal = re.compile(" + match.group(1) + ")", namespace)
	return namespace["fatal"]


class G009P0QualificationFatalPatternTest(unittest.TestCase):
	def setUp(self) -> None:
		self.fatal = qualification_fatal_pattern()

	def assertBenign(self, line: str) -> None:
		self.assertIsNone(self.fatal.search(line), line)

	def assertFatal(self, line: str) -> None:
		self.assertIsNotNone(self.fatal.search(line), line)

	def test_planner_diagnostics_with_error_operand_are_benign(self) -> None:
		self.assertBenign(
			"PlannerTrace LiteralOp value=GLM Input Error placement=CP")
		self.assertBenign(
			"CP instructions: CP°createvar°GLM Input Error°false°MATRIX°binary")

	def test_explicit_fatal_markers_are_fatal(self) -> None:
		for line in (
			"FATAL coordinator failure",
			"java.lang.OutOfMemoryError: Java heap space",
			"org.apache.sysds.runtime.DMLRuntimeException: GLM Input Error",
			"ERROR: Runtime error in program block - GLM Input Error",
			"TIMEOUT after 600 seconds",
			'Exception in thread "main" java.lang.IllegalStateException',
			"[INFO] BUILD FAILURE",
			"No valid federated plan for root 42",
			"fed_fout requires a federated anchor",
			"operation requires federated input but found local at runtime",
			"java.lang.StackOverflowError",
			"Segmentation fault (core dumped)",
		):
			with self.subTest(line=line):
				self.assertFatal(line)


class G009P0ProofContractTest(unittest.TestCase):
	def test_runner_requires_v2_full_initial_planning_proof(self) -> None:
		source = RUNNER.read_text(encoding="utf-8")
		self.assertIn('proof.get("schema") != "g009-glm-p0-proof-v2"', source)
		self.assertIn(
			'planning_full.get("schema") == "planning-full-initial-v1"', source)
		self.assertIn(
			'planning_full.get("Tplanning_full_initial_nanos"), int', source)
		self.assertIn(
			'planning_full["Tplanning_full_initial_nanos"] > 0', source)


if __name__ == "__main__":
	unittest.main()
