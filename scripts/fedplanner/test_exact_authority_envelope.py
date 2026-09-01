#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import aggregate_forced_state_results as aggregate
import build_forced_state_manifest as builder

from exact_authority_envelope import (
	EXACT_AUTHORITY_FIELDS,
	normalize_exact_authority_envelope,
	validate_exact_authority_chain,
)


def authority(character: str) -> dict[str, str]:
	return {field: character * 64 for field in EXACT_AUTHORITY_FIELDS}


class ExactAuthorityEnvelopeTest(unittest.TestCase):
	def test_sealed_tree_to_builder_forced_and_aggregate_contract(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary)
			leaf = root / "leaf-000000"; leaf.mkdir()
			(leaf / "candidate-space-1.jsonl").write_text("{}\n")
			raw = {
				"schema": "fedplanner-exact-discovery-authority-v1",
				"inventorySha256": "a" * 64,
				"sourceReceiptSha256": "b" * 64,
				"buildContract": "clean-test-compile-v1",
				"mainBuildWitnessSha256": "c" * 64,
				"testBuildWitnessSha256": "d" * 64,
				"shardCount": 1, "shardTreeChecksumSha256": ["e" * 64],
				"inventoryLeaves": 1, "mergedLeaves": 1,
			}
			(root / "MERGE_AUTHORITY.json").write_text(json.dumps(raw) + "\n")
			paths = sorted(path for path in root.rglob("*") if path.is_file())
			(root / "SHA256SUMS").write_text("".join(
				f"{hashlib.sha256(path.read_bytes()).hexdigest()}  "
				f"{path.relative_to(root).as_posix()}\n" for path in paths))
			envelope = builder.verify_exact_authority(root)
			row = {"exactDiscoveryAuthority": envelope}
			self.assertEqual(envelope, validate_exact_authority_chain(envelope, [row], envelope))
			self.assertEqual(envelope,
				aggregate.validate_exact_discovery_authority([row], "b" * 64))
			self.assertEqual(set(EXACT_AUTHORITY_FIELDS),
				set(aggregate.RUN_MANIFEST_EXACT_AUTHORITY_FIELDS.values()))

	def test_complete_caller_pinned_chain(self) -> None:
		expected = authority("a")
		self.assertEqual(expected, validate_exact_authority_chain(
			expected, [{"exactDiscoveryAuthority": expected}], expected))

	def test_rejects_missing_seventh_digest(self) -> None:
		incomplete = authority("a"); incomplete.pop("authorityTreeSha256")
		with self.assertRaisesRegex(ValueError, "missing/invalid"):
			normalize_exact_authority_envelope(incomplete)

	def test_rejects_self_consistent_builder_and_manifest_substitution(self) -> None:
		expected = authority("a")
		substituted = authority("b")
		with self.assertRaisesRegex(ValueError, "caller authority"):
			validate_exact_authority_chain(
				substituted, [{"exactDiscoveryAuthority": substituted}], expected)


if __name__ == "__main__":
	unittest.main()
