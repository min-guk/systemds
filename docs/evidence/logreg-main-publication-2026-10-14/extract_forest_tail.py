#!/usr/bin/env python3
"""Extract and validate the one MemoForestTail trace from an authorized run."""
import argparse
import json
import re
from pathlib import Path


TRACE = re.compile(
	r"^\[MemoForestTail\] attempts=(\d+) applicable=(\d+) completions=(\d+) cells=(\d+)$"
)


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--fed-log", required=True)
	ap.add_argument("--output", required=True)
	args = ap.parse_args()
	lines = [line for line in Path(args.fed_log).read_text().splitlines()
		if line.startswith("[MemoForestTail]")]
	matches = [TRACE.fullmatch(line) for line in lines]
	parsed = len(lines) == 1 and matches[0] is not None
	values = None
	if parsed:
		values = dict(zip(("attempts", "applicable", "completions", "cells"),
			map(int, matches[0].groups())))
	gates = {
		"exactlyOneTrace": len(lines) == 1,
		"exactFieldFormat": parsed,
		"nonnegativeCounters": parsed and all(value >= 0 for value in values.values()),
		"orderedCounts": parsed and values["attempts"] >= values["applicable"]
			>= values["completions"],
		"featureExercised": parsed and values["attempts"] > 0 and values["applicable"] > 0
			and values["completions"] > 0 and values["cells"] > 0,
	}
	out = {
		"schema": "memo-forest-tail-trace-v1",
		"passed": all(gates.values()),
		"gates": gates,
		"values": values,
		"matchingLines": len(lines),
	}
	Path(args.output).write_text(json.dumps(out, indent=2) + "\n")
	print(json.dumps(out, indent=2))
	if not out["passed"]:
		raise SystemExit(1)


if __name__ == "__main__":
	main()
