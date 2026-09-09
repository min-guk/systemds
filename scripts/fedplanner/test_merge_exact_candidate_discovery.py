#!/usr/bin/env python3
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import merge_exact_candidate_discovery as subject


class MergeExactDiscoveryTest(unittest.TestCase):
	def fixture(self, root: Path, values: list[str]):
		inventory = root / "inventory.jsonl"
		inventory.write_text("".join(json.dumps({"invocation": value}) + "\n"
			for value in values), encoding="utf-8")
		receipt = root / "SOURCE_SHA256SUMS.txt"
		receipt.write_text("receipt\n", encoding="utf-8")
		return inventory, receipt, hashlib.sha256(receipt.read_bytes()).hexdigest()

	def shard(self, root: Path, inventory: Path, receipt_sha: str, index: int,
		count: int, values: list[tuple[int, str]], main_witness: str = "a" * 64,
		test_witness: str = "b" * 64) -> Path:
		shard = root / f"shard-{index}"
		for leaf_index, value in values:
			leaf = shard / "leaves" / f"{leaf_index:06d}"
			leaf.mkdir(parents=True)
			(leaf / "INVOCATION.txt").write_text(value + "\n")
			(leaf / "MAVEN_RC.txt").write_text("0\n")
			(leaf / f"candidate-space-{leaf_index}.jsonl").write_text(
				json.dumps({"auditInvocation": value}) + "\n")
		manifest = {
			"schema": "fedplanner-exact-discovery-v1",
			"root": str(root), "inventory": "INVENTORY.jsonl",
			"inventory_sha256": hashlib.sha256(inventory.read_bytes()).hexdigest(),
			"source_receipt": "SOURCE_RECEIPT.txt",
			"source_receipt_sha256": receipt_sha,
			"build_contract": "clean-test-compile-v1",
			"main_build_witness_sha256": main_witness,
			"test_build_witness_sha256": test_witness,
			"inventory_leaves": str(sum(1 for line in inventory.read_text().splitlines() if line)),
			"shard_index": str(index), "shard_count": str(count),
			"exact_leaves": str(len(values)), "candidate_files": str(len(values)),
			"failed_leaves": "0",
			"source_check_before_rc": "0", "build_rc": "0",
			"source_check_after_build_rc": "0", "leaf_maven_rc": "0",
			"source_check_after_rc": "0", "source_paths_check_rc": "0",
			"started": "now", "finished": "now",
		}
		(shard / "SOURCE_RECEIPT.txt").write_bytes((root / "SOURCE_SHA256SUMS.txt").read_bytes())
		(shard / "INVENTORY.jsonl").write_bytes(inventory.read_bytes())
		(shard / "BUILD_RC.txt").write_text("0\n")
		for name in ("SOURCE_SHA256_CHECK_BEFORE.log",
			"SOURCE_SHA256_CHECK_AFTER_BUILD.log", "SOURCE_SHA256_CHECK_AFTER.log"):
			(shard / name).write_text("PASS\n")
		(shard / "RUN_MANIFEST.txt").write_text(
			"".join(f"{key}={value}\n" for key, value in manifest.items()))
		self.seal(shard)
		return shard

	def seal(self, root: Path) -> None:
		paths = sorted((path for path in root.rglob("*") if path.is_file()
			and path.name != "SHA256SUMS"), key=lambda path: path.relative_to(root).as_posix())
		(root / "SHA256SUMS").write_text("".join(
			f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(root).as_posix()}\n"
			for path in paths))

	def invoke(self, inventory, receipt, receipt_sha, shards, output,
		expected_inventory_sha: str | None = None):
		argv = ["merge", "--inventory", str(inventory), "--source-receipt", str(receipt),
			"--inventory-sha256", expected_inventory_sha or hashlib.sha256(
				inventory.read_bytes()).hexdigest(), "--source-receipt-sha256", receipt_sha]
		for shard in shards:
			argv += ["--shard-dir", str(shard)]
		argv += ["--output", str(output)]
		with patch("sys.argv", argv):
			subject.main()

	def test_shards_cover_inventory_once_with_identical_authority(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary)
			values = ["org.apache.sysds.test.Fixture#case[0]",
				"org.apache.sysds.test.Fixture#case[1]"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shards = [self.shard(root, inventory, receipt_sha, 0, 2, [(0, values[0])]),
				self.shard(root, inventory, receipt_sha, 1, 2, [(1, values[1])])]
			self.invoke(inventory, receipt, receipt_sha, shards, root / "merged")
			self.assertEqual(len(list((root / "merged").glob("leaf-*"))), 2)
			self.assertTrue((root / "merged" / "MERGE_AUTHORITY.json").is_file())
			subject.verify_complete_tree(root / "merged")

	def test_rejects_incomplete_topology_and_digest_mismatch(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 2, [(0, values[0])])
			with self.assertRaisesRegex(ValueError, "topology is incomplete"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "missing")
			with self.assertRaisesRegex(ValueError, "source receipt"):
				self.invoke(inventory, receipt, "0" * 64, [shard], root / "digest")

	def test_rejects_self_consistent_inventory_substitution_against_caller_pin(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary)
			inventory, receipt, receipt_sha = self.fixture(root, ["old#case"])
			caller_sha = hashlib.sha256(inventory.read_bytes()).hexdigest()
			inventory.write_text(json.dumps({"invocation": "new#case"}) + "\n")
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, "new#case")])
			with self.assertRaisesRegex(ValueError, "caller-pinned"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "substituted",
					caller_sha)

	def test_rejects_swapped_deterministic_shard_assignment(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary)
			values = ["org.apache.sysds.test.Fixture#case[0]",
				"org.apache.sysds.test.Fixture#case[1]"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shards = [self.shard(root, inventory, receipt_sha, 0, 2, [(1, values[1])]),
				self.shard(root, inventory, receipt_sha, 1, 2, [(0, values[0])])]
			with self.assertRaisesRegex(ValueError, "deterministic shard assignment"):
				self.invoke(inventory, receipt, receipt_sha, shards, root / "swapped")

	def test_rejects_different_clean_build_witnesses(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary)
			values = ["org.apache.sysds.test.Fixture#case[0]",
				"org.apache.sysds.test.Fixture#case[1]"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shards = [self.shard(root, inventory, receipt_sha, 0, 2, [(0, values[0])]),
				self.shard(root, inventory, receipt_sha, 1, 2, [(1, values[1])],
					main_witness="c" * 64)]
			with self.assertRaisesRegex(ValueError, "different clean-build witnesses"):
				self.invoke(inventory, receipt, receipt_sha, shards, root / "witness")

	def test_rejects_missing_build_rc_and_incomplete_checksum(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, values[0])])
			(shard / "BUILD_RC.txt").unlink(); self.seal(shard)
			with self.assertRaises((ValueError, FileNotFoundError)):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "missing-build")
			(shard / "BUILD_RC.txt").write_text("0\n"); self.seal(shard)
			lines = (shard / "SHA256SUMS").read_text().splitlines()
			(shard / "SHA256SUMS").write_text("\n".join(lines[:-1]) + "\n")
			with self.assertRaisesRegex(ValueError, "incomplete checksum tree"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "incomplete")

	def test_rejects_duplicate_or_malformed_terminal_manifest(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, values[0])])
			with (shard / "RUN_MANIFEST.txt").open("a") as handle:
				handle.write("schema=duplicate\n")
			self.seal(shard)
			with self.assertRaisesRegex(ValueError, "duplicate manifest key"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "duplicate")
			(shard / "RUN_MANIFEST.txt").write_text("malformed\n")
			self.seal(shard)
			with self.assertRaisesRegex(ValueError, "malformed/empty manifest"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "malformed")

	def test_rejects_forged_candidate_and_symlink(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, values[0])])
			candidate = next(shard.rglob("candidate-space-*.jsonl"))
			candidate.write_text(json.dumps({"auditInvocation": "forged"}) + "\n")
			with self.assertRaisesRegex(ValueError, "checksum mismatch"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "forged")
			self.seal(shard)
			(shard / "link").symlink_to(candidate)
			with self.assertRaisesRegex(ValueError, "contains symlink"):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "symlink")

	def test_merge_copies_verified_snapshot_not_post_validation_mutation(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, values[0])])
			candidate = next(shard.rglob("candidate-space-*.jsonl"))
			original = candidate.read_bytes()
			real_copy2 = subject.shutil.copy2
			mutated = False

			def mutate_producer_then_copy(source, destination, *args, **kwargs):
				nonlocal mutated
				if not mutated:
					candidate.write_text(json.dumps({"auditInvocation": "forged"}) + "\n")
					mutated = True
				return real_copy2(source, destination, *args, **kwargs)

			with patch.object(subject.shutil, "copy2", side_effect=mutate_producer_then_copy):
				self.invoke(inventory, receipt, receipt_sha, [shard], root / "merged")
			merged_candidate = next((root / "merged").rglob("candidate-space-*.jsonl"))
			self.assertEqual(merged_candidate.read_bytes(), original)
			subject.verify_complete_tree(root / "merged")

	def test_copy_failure_does_not_publish_partial_output(self):
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); values = ["org.apache.sysds.test.Fixture#case"]
			inventory, receipt, receipt_sha = self.fixture(root, values)
			shard = self.shard(root, inventory, receipt_sha, 0, 1, [(0, values[0])])
			output = root / "merged"
			with patch.object(subject.shutil, "copy2", side_effect=OSError("copy failed")):
				with self.assertRaisesRegex(OSError, "copy failed"):
					self.invoke(inventory, receipt, receipt_sha, [shard], output)
			self.assertFalse(output.exists())


if __name__ == "__main__":
	unittest.main()
