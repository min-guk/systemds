#!/usr/bin/env python3

from __future__ import annotations

import collections
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import aggregate_forced_state_results as subject


SCRIPT = Path(__file__).with_name("aggregate_forced_state_results.py")
SOURCE_RECEIPT = b"source receipt authority\n"
SOURCE_SHA = hashlib.sha256(SOURCE_RECEIPT).hexdigest()
MAIN_WITNESS = "c" * 64
TEST_WITNESS = "d" * 64
EXACT_AUTHORITY = {
	"authoritySha256": "1" * 64,
	"authorityTreeSha256": "7" * 64,
	"treeChecksumSha256": "2" * 64,
	"inventorySha256": "3" * 64,
	"sourceReceiptSha256": SOURCE_SHA,
	"mainBuildWitnessSha256": MAIN_WITNESS,
	"testBuildWitnessSha256": TEST_WITNESS,
}


def write_checksums(directory: Path) -> None:
	lines = []
	for path in sorted(path for path in directory.rglob("*")
		if path.is_file() and path.name != "SHA256SUMS.txt"):
		lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(directory)}")
	(directory / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_rows(directory: Path, pattern: str) -> list[dict[str, object]]:
	return [json.loads(line) for path in directory.rglob(pattern)
		for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def refresh_summary(directory: Path) -> None:
	rows = read_rows(directory, "forced-state-results-*.jsonl")
	capabilities_rows = read_rows(directory, "runtime-capability-*.jsonl")
	capability_targets = {
		path.relative_to(directory).parts[path.relative_to(directory).parts.index("targets") + 1]
		for path in directory.rglob("runtime-capability-*.jsonl")
	}
	result_ids = {str(row["targetId"]) for row in rows}
	summary = {
		"infrastructureStatus": "PASS",
		"expectedTargets": len(rows),
		"resultRows": len(rows),
		"missingResultTargetIds": [],
		"unexpectedResultTargetIds": [],
		"duplicateResultRows": 0,
		"missingRuntimeCapabilityTargetIds": sorted(result_ids - capability_targets),
		"unexpectedRuntimeCapabilityTargetIds": sorted(capability_targets - result_ids),
		"runtimeCapabilityOutcomes": dict(sorted(collections.Counter(
			str(row.get("outcome")) for row in capabilities_rows).items())),
	}
	(directory / "CAMPAIGN_SUMMARY.json").write_text(
		json.dumps(summary) + "\n", encoding="utf-8")
	write_checksums(directory)


def campaign(root: Path, name: str, manifest: Path, rows: list[dict[str, object]],
	shard_index: int = 0, shard_count: int = 1, source_sha: str = SOURCE_SHA,
	retry_subset: bool = False, main_witness: str = MAIN_WITNESS,
	test_witness: str = TEST_WITNESS, source_receipt: bytes = SOURCE_RECEIPT) -> Path:
	directory = root / name
	directory.mkdir()
	manifest_rows = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines()
		if line.strip()]
	for row in manifest_rows:
		row.setdefault("exactDiscoveryAuthority", EXACT_AUTHORITY)
	manifest.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in manifest_rows))
	result_ids = {str(row["targetId"]) for row in rows}
	executed_rows = [row for row in manifest_rows if str(row.get("targetId")) in result_ids]
	executed_bytes = "".join(json.dumps(row, sort_keys=True) + "\n" for row in executed_rows).encode()
	manifest_sha = hashlib.sha256(executed_bytes if retry_subset else manifest.read_bytes()).hexdigest()
	(directory / "RUN_MANIFEST.txt").write_text(
		f"schema=fedplanner-forced-campaign-v1\nhost=dams-so003\n"
		f"manifest_sha256={manifest_sha}\nsource_manifest=SOURCE_RECEIPT_SHA256SUMS.txt\n"
		f"source_manifest_sha256={source_sha}\nshard_index={shard_index}\n"
		f"shard_count={shard_count}\nmax_targets=unbounded\ntargets_per_jvm=1\n"
		"source_receipt_copy=SOURCE_RECEIPT_SHA256SUMS.txt\n"
		f"exact_discovery_authority_sha256={EXACT_AUTHORITY['authoritySha256']}\n"
		f"exact_discovery_authority_tree_sha256={EXACT_AUTHORITY['authorityTreeSha256']}\n"
		f"exact_discovery_tree_checksum_sha256={EXACT_AUTHORITY['treeChecksumSha256']}\n"
		f"exact_discovery_inventory_sha256={EXACT_AUTHORITY['inventorySha256']}\n"
		f"exact_discovery_source_receipt_sha256={EXACT_AUTHORITY['sourceReceiptSha256']}\n"
		f"exact_discovery_main_build_witness_sha256={EXACT_AUTHORITY['mainBuildWitnessSha256']}\n"
		f"exact_discovery_test_build_witness_sha256={EXACT_AUTHORITY['testBuildWitnessSha256']}\n"
		f"build_contract=clean-test-compile-v1\n"
		f"main_build_witness_sha256={main_witness}\n"
		f"test_build_witness_sha256={test_witness}\n"
		"strict_manifest_check_rc=0\nsource_receipt_rc=0\nbuild_rc=0\n"
		"post_build_source_receipt_rc=0\nmaven_rc=0\nsummary_rc=0\n"
		"final_source_receipt_rc=0\nauthoritative_summary_validation_rc=0\n",
		encoding="utf-8")
	(directory / "SOURCE_RECEIPT_SHA256SUMS.txt").write_bytes(source_receipt)
	(directory / "forced-state-results-test.jsonl").write_text(
		"".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
	chunks = directory / "chunk-manifests"
	chunks.mkdir()
	(chunks / "chunk-00000.jsonl").write_bytes(executed_bytes)
	refresh_summary(directory)
	return directory


def capabilities(directory: Path, target_id: str, outcomes: list[str]) -> None:
	target = directory / "targets" / target_id / "attempt-0000"
	target.mkdir(parents=True)
	(target / "runtime-capability-test.jsonl").write_text(
		"".join(json.dumps({"outcome": outcome}) + "\n" for outcome in outcomes),
		encoding="utf-8")
	refresh_summary(directory)


def invoke(manifest: Path, output: Path, primary: list[Path],
	isolated: list[Path] | None = None, semantic: list[Path] | None = None,
	expected_source_sha: str = SOURCE_SHA, expected_manifest_sha: str | None = None):
	command = [str(SCRIPT), "--manifest", str(manifest),
		"--expected-manifest-sha256", expected_manifest_sha or hashlib.sha256(
			manifest.read_bytes()).hexdigest(),
		"--expected-source-receipt-sha256", expected_source_sha]
	for directory in primary:
		command += ["--primary", str(directory)]
	for directory in isolated or []:
		command += ["--isolated", str(directory)]
	for directory in semantic or []:
		command += ["--semantic", str(directory)]
	command += ["--allowed-host", "dams-so003", "--out-dir", str(output)]
	return subprocess.run(command, text=True, capture_output=True, check=False)


class AggregateForcedStateResultsTest(unittest.TestCase):
	def test_stage_reads_checksum_verified_snapshot_not_mutated_producer(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			capabilities(primary, "t1", ["SUCCESS"])
			manifest_rows = subject.read_jsonl([manifest])
			manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
			result_path = primary / "forced-state-results-test.jsonl"
			real_read_jsonl = subject.read_jsonl
			mutated = False

			def mutate_source_then_read(paths):
				nonlocal mutated
				if not mutated:
					result_path.write_text(json.dumps({"targetId": "t1", "outcome": "forged"}) + "\n")
					mutated = True
				return real_read_jsonl(paths)

			with patch.object(subject, "read_jsonl", side_effect=mutate_source_then_read):
				stage = subject.load_stage("primary", [primary], {"dams-so003"},
					manifest_sha, manifest_rows, {"t1": manifest_rows[0]}, SOURCE_SHA,
					subject.validate_exact_discovery_authority(manifest_rows, SOURCE_SHA))
			self.assertEqual("SUCCESS", stage.rows["t1"]["outcome"])

	def test_output_failure_does_not_publish_partial_aggregate(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			capabilities(primary, "t1", ["SUCCESS"])
			output = root / "output"
			argv = ["aggregate", "--manifest", str(manifest),
				"--expected-manifest-sha256", hashlib.sha256(manifest.read_bytes()).hexdigest(),
				"--expected-source-receipt-sha256", SOURCE_SHA,
				"--primary", str(primary), "--allowed-host", "dams-so003",
				"--out-dir", str(output)]
			with patch("sys.argv", argv), patch.object(subject, "write_breakdown",
					side_effect=OSError("write failed")):
				with self.assertRaisesRegex(OSError, "write failed"):
					subject.main()
			self.assertFalse(output.exists())

	def test_stage_precedence_and_classification(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp)
			manifest = root / "manifest.jsonl"
			manifest.write_text("".join(json.dumps({
				"targetId": target, "replayContext": "ctx", "opcode": target,
				"state": "CP/LOUT/-", "privacy": ["PUBLIC"],
				"occurrenceKeyHash": target, "semanticOccurrenceKeyHash": "s" + target,
			}) + "\n" for target in ("t1", "t2", "t3", "t4")), encoding="utf-8")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True},
				{"targetId": "t2", "outcome": "TARGET_NOT_REACHED", "constraintSatisfied": False},
				{"targetId": "t3", "outcome": "WHOLE_PROGRAM_INFEASIBLE", "constraintSatisfied": False},
				{"targetId": "t4", "outcome": "FAILURE_REQUIRES_TRIAGE", "constraintSatisfied": True},
			])
			isolated = campaign(root, "isolated", manifest, [
				{"targetId": "t2", "outcome": "TARGET_NOT_REACHED", "constraintSatisfied": False},
				{"targetId": "t3", "outcome": "WHOLE_PROGRAM_INFEASIBLE", "constraintSatisfied": False},
				{"targetId": "t4", "outcome": "SUCCESS", "constraintSatisfied": True},
			], retry_subset=True)
			semantic = campaign(root, "semantic", manifest, [
				{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True},
			], retry_subset=True)
			capabilities(primary, "t1", ["SUCCESS"])
			capabilities(isolated, "t4", ["SUCCESS"])
			capabilities(semantic, "t2", ["SUCCESS"])
			output = root / "output"
			completed = invoke(manifest, output, [primary], [isolated], [semantic])
			self.assertEqual(0, completed.returncode, completed.stderr)
			summary = json.loads((output / "SUMMARY.json").read_text())
			self.assertEqual({"SUCCESS": 3, "WHOLE_PROGRAM_INFEASIBLE": 1},
				summary["finalOutcomes"])
			self.assertEqual({"isolated": 2, "primary": 1, "semantic": 1},
				summary["authoritativeStageCounts"])
			self.assertEqual(0, summary["unresolvedTargets"])
			self.assertEqual(SOURCE_SHA, summary["primarySourceManifestSha256"])
			self.assertEqual({
				"source_manifest_sha256": SOURCE_SHA,
				"main_build_witness_sha256": MAIN_WITNESS,
				"test_build_witness_sha256": TEST_WITNESS,
			}, summary["authorityWitness"])
			self.assertEqual(hashlib.sha256(manifest.read_bytes()).hexdigest(),
				summary["full_manifest_sha256"])
			isolated_receipt = summary["stageReceipts"]["isolated"][0]
			self.assertEqual(summary["authorityWitness"], isolated_receipt["authorityWitness"])
			self.assertEqual(summary["full_manifest_sha256"],
				isolated_receipt["full_manifest_sha256"])
			self.assertEqual(isolated_receipt["manifestSha256"],
				isolated_receipt["executed_subset_manifest_sha256"])
			self.assertTrue((output / "SHA256SUMS.txt").is_file())

	def test_rejects_incomplete_isolated_coverage(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "TARGET_NOT_REACHED", "constraintSatisfied": False}])
			completed = invoke(manifest, root / "output", [primary])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("isolated versus primary non-success", completed.stderr)

	def test_preserves_assertion_after_successful_runtime_evidence(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "FAILURE_REQUIRES_TRIAGE",
				"constraintSatisfied": True, "failures": ["AssertionError: expected heavy hitter"]}
			primary = campaign(root, "primary", manifest, [row])
			isolated = campaign(root, "isolated", manifest, [row], retry_subset=True)
			capabilities(isolated, "t1", ["SUCCESS", "SUCCESS"])
			output = root / "output"
			completed = invoke(manifest, output, [primary], [isolated])
			self.assertEqual(0, completed.returncode, completed.stderr)
			summary = json.loads((output / "SUMMARY.json").read_text())
			self.assertEqual({"TRIAGE_ASSERTION_AFTER_SUCCESSFUL_RUNTIME": 1},
				summary["finalClassifications"])

	def test_rejects_success_without_successful_runtime_capability(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			completed = invoke(manifest, root / "output", [primary])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("lacks exclusively successful runtime capability", completed.stderr)

	def test_rejects_tampered_campaign_checksum(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			capabilities(primary, "t1", ["SUCCESS"])
			with (primary / "forced-state-results-test.jsonl").open("a", encoding="utf-8") as handle:
				handle.write("tamper\n")
			completed = invoke(manifest, root / "output", [primary])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("checksum mismatch", completed.stderr)

	def test_checksum_manifest_must_be_nonempty_complete_and_unique(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}
			for case in ("empty", "partial", "duplicate", "unlisted-required"):
				directory = campaign(root, case, manifest, [row])
				capabilities(directory, "t1", ["SUCCESS"])
				checksums = directory / "SHA256SUMS.txt"
				lines = checksums.read_text().splitlines()
				if case == "empty":
					checksums.write_text("")
				elif case == "partial":
					checksums.write_text("\n".join(lines[:-1]) + "\n")
				elif case == "duplicate":
					checksums.write_text("\n".join(lines + [lines[0]]) + "\n")
				else:
					checksums.write_text("\n".join(line for line in lines
						if not line.endswith("  RUN_MANIFEST.txt")) + "\n")
					with (directory / "RUN_MANIFEST.txt").open("a") as handle:
						handle.write("unlisted_tamper=true\n")
				completed = invoke(manifest, root / f"out-{case}", [directory])
				self.assertNotEqual(0, completed.returncode, case)
				if case == "empty":
					self.assertIn("empty SHA256SUMS", completed.stderr)
				elif case == "duplicate":
					self.assertIn("duplicate checksum path", completed.stderr)
				else:
					self.assertIn("checksum path-set mismatch", completed.stderr)

	def test_checksum_tree_rejects_symlink(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			capabilities(primary, "t1", ["SUCCESS"])
			(primary / "authority-link").symlink_to("RUN_MANIFEST.txt")
			completed = invoke(manifest, root / "output", [primary])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("checksum tree contains symlinks", completed.stderr)

	def test_run_manifest_authority_fields_fail_closed(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}
			for case in ("missing", "nonzero", "malformed-entry", "duplicate", "malformed-witness"):
				with self.subTest(case=case):
					directory = campaign(root, case, manifest, [row])
					capabilities(directory, "t1", ["SUCCESS"])
					run = directory / "RUN_MANIFEST.txt"
					lines = run.read_text().splitlines()
					if case == "missing":
						lines = [line for line in lines if not line.startswith("build_rc=")]
					elif case == "nonzero":
						lines = ["summary_rc=1" if line.startswith("summary_rc=") else line
							for line in lines]
					elif case == "malformed-entry":
						lines.append("not-a-key-value-entry")
					elif case == "duplicate":
						lines.append("schema=fedplanner-forced-campaign-v1")
					else:
						lines = ["main_build_witness_sha256=ABC" if line.startswith(
							"main_build_witness_sha256=") else line for line in lines]
					run.write_text("\n".join(lines) + "\n")
					write_checksums(directory)
					completed = invoke(manifest, root / f"out-authority-{case}", [directory])
					self.assertNotEqual(0, completed.returncode)
					if case == "duplicate":
						self.assertIn("duplicate key", completed.stderr)
					elif case == "malformed-entry":
						self.assertIn("malformed key-value", completed.stderr)
					elif case == "malformed-witness":
						self.assertIn("build witness SHA-256", completed.stderr)
					else:
						self.assertIn("authority field must be string 0", completed.stderr)

	def test_rejects_cross_primary_authority_witness_mismatch(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text("".join(json.dumps({"targetId": target}) + "\n"
				for target in ("t1", "t2")))
			first = campaign(root, "first", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}], 0, 2)
			second = campaign(root, "second", manifest, [
				{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True}], 1, 2,
				main_witness="e" * 64)
			capabilities(first, "t1", ["SUCCESS"]); capabilities(second, "t2", ["SUCCESS"])
			completed = invoke(manifest, root / "output", [first, second])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("disagree on the build authority witness", completed.stderr)

	def test_rejects_retry_authority_witness_mismatch(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "TARGET_NOT_REACHED",
				"constraintSatisfied": False}
			primary = campaign(root, "primary", manifest, [row])
			isolated = campaign(root, "isolated", manifest, [row], retry_subset=True,
				test_witness="e" * 64)
			completed = invoke(manifest, root / "output", [primary], [isolated])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("build authority witness differs from primary", completed.stderr)

	def test_rejects_swapped_primary_shard_membership(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text("".join(json.dumps({"targetId": target}) + "\n"
				for target in ("t1", "t2")))
			first = campaign(root, "first", manifest, [
				{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True}], 0, 2)
			second = campaign(root, "second", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}], 1, 2)
			capabilities(first, "t2", ["SUCCESS"]); capabilities(second, "t1", ["SUCCESS"])
			completed = invoke(manifest, root / "output", [first, second])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("deterministic shard membership", completed.stderr)

	def test_rejects_primary_max_targets_scope(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary = campaign(root, "primary", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}])
			capabilities(primary, "t1", ["SUCCESS"])
			run = primary / "RUN_MANIFEST.txt"
			run.write_text(run.read_text().replace("max_targets=unbounded", "max_targets=1"))
			write_checksums(primary)
			completed = invoke(manifest, root / "output", [primary])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("forbids MAX_TARGETS", completed.stderr)

	def test_rejects_untrusted_manifest_or_source_receipt_authority(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}
			primary = campaign(root, "primary", manifest, [row])
			capabilities(primary, "t1", ["SUCCESS"])
			completed = invoke(manifest, root / "out-manifest", [primary],
				expected_manifest_sha="e" * 64)
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("manifest digest differs from caller authority", completed.stderr)
			completed = invoke(manifest, root / "out-source", [primary],
				expected_source_sha="e" * 64)
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("source digest differs from caller authority", completed.stderr)

	def test_rejects_missing_or_arbitrary_self_contained_source_receipt(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}
			for case in ("missing", "arbitrary", "nonexistent-path"):
				primary = campaign(root, case, manifest, [row])
				capabilities(primary, "t1", ["SUCCESS"])
				receipt = primary / "SOURCE_RECEIPT_SHA256SUMS.txt"
				if case == "missing":
					receipt.unlink()
				elif case == "arbitrary":
					receipt.write_bytes(b"arbitrary synthetic receipt\n")
				else:
					run = primary / "RUN_MANIFEST.txt"
					run.write_text(run.read_text().replace(
						"source_manifest=SOURCE_RECEIPT_SHA256SUMS.txt",
						"source_manifest=/nonexistent/arbitrary-receipt"))
				write_checksums(primary)
				completed = invoke(manifest, root / f"out-{case}", [primary])
				self.assertNotEqual(0, completed.returncode)
				if case == "nonexistent-path":
					self.assertIn("source_manifest must name the self-contained receipt",
						completed.stderr)
				else:
					self.assertIn("source receipt", completed.stderr)

	def test_rejects_missing_malformed_or_disagreeing_exact_authority(self) -> None:
		for case in ("missing", "malformed", "disagreeing"):
			with self.subTest(case=case), tempfile.TemporaryDirectory() as temp:
				root = Path(temp); manifest = root / "manifest.jsonl"
				rows = [{"targetId": "t1"}, {"targetId": "t2"}]
				manifest.write_text("".join(json.dumps(row) + "\n" for row in rows))
				first = campaign(root, "first", manifest, [
					{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}], 0, 2)
				second = campaign(root, "second", manifest, [
					{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True}], 1, 2)
				capabilities(first, "t1", ["SUCCESS"]); capabilities(second, "t2", ["SUCCESS"])
				manifest_rows = [json.loads(line) for line in manifest.read_text().splitlines()]
				if case == "missing":
					manifest_rows[0].pop("exactDiscoveryAuthority")
				elif case == "malformed":
					manifest_rows[0]["exactDiscoveryAuthority"]["authoritySha256"] = "ABC"
				else:
					manifest_rows[1]["exactDiscoveryAuthority"]["authoritySha256"] = "e" * 64
				manifest.write_text("".join(json.dumps(row, sort_keys=True) + "\n"
					for row in manifest_rows))
				completed = invoke(manifest, root / "output", [first, second])
				self.assertNotEqual(0, completed.returncode)
				if case == "missing":
					self.assertIn("missing/invalid exactDiscoveryAuthority", completed.stderr)
				elif case == "malformed":
					self.assertIn("malformed exact authority digest", completed.stderr)
				else:
					self.assertIn("disagree on exactDiscoveryAuthority", completed.stderr)

	def test_rejects_primary_digest_and_topology_mismatch(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text("".join(json.dumps({"targetId": target}) + "\n"
				for target in ("t1", "t2")))
			first = campaign(root, "first", manifest, [
				{"targetId": "t1", "outcome": "SUCCESS", "constraintSatisfied": True}], 0, 3)
			second = campaign(root, "second", manifest, [
				{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True}], 1, 3)
			capabilities(first, "t1", ["SUCCESS"]); capabilities(second, "t2", ["SUCCESS"])
			completed = invoke(manifest, root / "out-topology", [first, second])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("incomplete or duplicate shard topology", completed.stderr)
			bad_digest = campaign(root, "bad-digest", manifest, [
				{"targetId": "t2", "outcome": "SUCCESS", "constraintSatisfied": True}],
				1, 2, source_sha="b" * 64)
			capabilities(bad_digest, "t2", ["SUCCESS"])
			completed = invoke(manifest, root / "out-digest", [first, bad_digest])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("source receipt digest", completed.stderr)

	def test_rejects_retry_from_different_source(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			row = {"targetId": "t1", "outcome": "TARGET_NOT_REACHED",
				"constraintSatisfied": False}
			primary = campaign(root, "primary", manifest, [row])
			isolated = campaign(root, "isolated", manifest, [row],
				source_sha="b" * 64, retry_subset=True)
			completed = invoke(manifest, root / "output", [primary], [isolated])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("differs from primary", completed.stderr)

	def test_rejects_tampered_retry_subset_payload(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1", "opcode": "good"}) + "\n")
			row = {"targetId": "t1", "outcome": "TARGET_NOT_REACHED",
				"constraintSatisfied": False}
			primary = campaign(root, "primary", manifest, [row])
			isolated = campaign(root, "isolated", manifest, [row], retry_subset=True)
			chunk = isolated / "chunk-manifests/chunk-00000.jsonl"
			chunk.write_text(json.dumps({"targetId": "t1", "opcode": "tampered"},
				sort_keys=True) + "\n")
			digest = hashlib.sha256(chunk.read_bytes()).hexdigest()
			run_manifest = (isolated / "RUN_MANIFEST.txt").read_text()
			old_digest = next(line.split("=", 1)[1] for line in run_manifest.splitlines()
				if line.startswith("manifest_sha256="))
			(isolated / "RUN_MANIFEST.txt").write_text(run_manifest.replace(old_digest, digest))
			write_checksums(isolated)
			completed = invoke(manifest, root / "output", [primary], [isolated])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("payload differs from full manifest", completed.stderr)

	def test_rejects_retry_target_outside_full_manifest(self) -> None:
		with tempfile.TemporaryDirectory() as temp:
			root = Path(temp); manifest = root / "manifest.jsonl"
			manifest.write_text(json.dumps({"targetId": "t1"}) + "\n")
			primary_row = {"targetId": "t1", "outcome": "TARGET_NOT_REACHED",
				"constraintSatisfied": False}
			primary = campaign(root, "primary", manifest, [primary_row])
			isolated = campaign(root, "isolated", manifest, [primary_row], retry_subset=True)
			outside = {"targetId": "outside"}
			chunk = isolated / "chunk-manifests/chunk-00000.jsonl"
			chunk.write_text(json.dumps(outside, sort_keys=True) + "\n")
			(isolated / "forced-state-results-test.jsonl").write_text(json.dumps({
				"targetId": "outside", "outcome": "TARGET_NOT_REACHED",
				"constraintSatisfied": False}) + "\n")
			digest = hashlib.sha256(chunk.read_bytes()).hexdigest()
			run_manifest = (isolated / "RUN_MANIFEST.txt").read_text()
			old_digest = next(line.split("=", 1)[1] for line in run_manifest.splitlines()
				if line.startswith("manifest_sha256="))
			(isolated / "RUN_MANIFEST.txt").write_text(run_manifest.replace(old_digest, digest))
			refresh_summary(isolated)
			completed = invoke(manifest, root / "output", [primary], [isolated])
			self.assertNotEqual(0, completed.returncode)
			self.assertIn("not in full manifest", completed.stderr)


if __name__ == "__main__":
	unittest.main()
