#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("build_forced_state_manifest.py")
SPEC = importlib.util.spec_from_file_location("build_forced_state_manifest", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class IsolatedRuntimeContextTest(unittest.TestCase):
	def candidate(self, rows: list[dict[str, object]]) -> tuple[tempfile.TemporaryDirectory, Path]:
		temporary = tempfile.TemporaryDirectory()
		path = Path(temporary.name) / "candidate-space-7.jsonl"
		path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
		return temporary, path

	def assert_rejected(self, rows: list[dict[str, object]], pattern: str) -> None:
		temporary, path = self.candidate(rows)
		with temporary:
			with self.assertRaisesRegex(ValueError, pattern):
				MODULE.require_isolated_runtime_contexts([path])

	def test_accepts_multiple_analyses_for_one_exact_functions_leaf(self) -> None:
		context = "org.apache.sysds.test.functions.Fixture#colLayout"
		temporary, path = self.candidate([{
			"auditContext": context,
			"auditInvocation": context + "[workers=2]",
			"analysisFingerprint": fingerprint,
		} for fingerprint in ["initial", "dynamic"]])
		with temporary:
			MODULE.require_isolated_runtime_contexts([path])

	def test_accepts_component_test_leaf(self) -> None:
		context = "org.apache.sysds.test.component.federated.Fixture#layout"
		temporary, path = self.candidate([{
			"auditContext": context,
			"auditInvocation": context,
		}])
		with temporary:
			MODULE.require_isolated_runtime_contexts([path])

	def test_rejects_functions_and_component_mix(self) -> None:
		self.assert_rejected([{
			"auditContext": "org.apache.sysds.test.functions.Fixture#layout",
			"auditInvocation": "org.apache.sysds.test.functions.Fixture#layout",
		}, {
			"auditContext": "org.apache.sysds.test.component.Fixture#layout",
			"auditInvocation": "org.apache.sysds.test.component.Fixture#layout",
		}], "exactly one runtime JUnit leaf")

	def test_rejects_two_component_leaves(self) -> None:
		self.assert_rejected([{
			"auditContext": f"org.apache.sysds.test.component.Fixture#{method}",
			"auditInvocation": f"org.apache.sysds.test.component.Fixture#{method}",
		} for method in ["rowLayout", "colLayout"]], "exactly one runtime JUnit leaf")

	def test_rejects_unknown_context(self) -> None:
		self.assert_rejected([{
			"auditContext": "discovery-label",
			"auditInvocation": "discovery-label",
		}], "unknown/non-JUnit runtime auditContext")

	def test_rejects_null_context(self) -> None:
		self.assert_rejected([{
			"auditContext": None,
			"auditInvocation": None,
		}], "missing/null runtime auditContext")

	def test_rejects_zero_invocations(self) -> None:
		self.assert_rejected([], "zero runtime audit invocations")

	def test_rejects_missing_exact_invocation_identity(self) -> None:
		self.assert_rejected([{
			"auditContext": "org.apache.sysds.test.functions.Fixture#layout",
		}], "missing exact auditInvocation")

	def test_rejects_same_method_parameter_siblings(self) -> None:
		context = "org.apache.sysds.test.functions.Fixture#layout"
		self.assert_rejected([{
			"auditContext": context,
			"auditInvocation": context + suffix,
		} for suffix in ["[workers=1]", "[workers=2]"]],
			"exactly one runtime JUnit leaf")

	def test_rejects_invocation_from_different_method(self) -> None:
		self.assert_rejected([{
			"auditContext": "org.apache.sysds.test.functions.Fixture#layout",
			"auditInvocation": "org.apache.sysds.test.functions.Fixture#other",
		}], "does not belong to auditContext")


class ExactAuthorityConsumptionTest(unittest.TestCase):
	def authority(self, root: Path) -> None:
		leaf = root / "leaf-000000"; leaf.mkdir(parents=True)
		(leaf / "candidate-space-1.jsonl").write_text("{}\n")
		authority = {
			"schema": "fedplanner-exact-discovery-authority-v1",
			"inventorySha256": "a" * 64, "sourceReceiptSha256": "b" * 64,
			"buildContract": "clean-test-compile-v1",
			"mainBuildWitnessSha256": "c" * 64,
			"testBuildWitnessSha256": "d" * 64,
			"shardCount": 1, "shardTreeChecksumSha256": ["e" * 64],
			"inventoryLeaves": 1, "mergedLeaves": 1,
		}
		(root / "MERGE_AUTHORITY.json").write_text(json.dumps(authority) + "\n")
		paths = sorted(path for path in root.rglob("*") if path.is_file()
			and path.name != "SHA256SUMS")
		(root / "SHA256SUMS").write_text("".join(
			f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(root).as_posix()}\n"
			for path in paths))

	def test_accepts_and_binds_exact_authority(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			value = MODULE.verify_exact_authority(root)
			self.assertEqual(value["inventorySha256"], "a" * 64)
			self.assertEqual(len(value["treeChecksumSha256"]), 64)
			self.assertEqual(len(value["authorityTreeSha256"]), 64)

	def test_rejects_candidate_injection_and_symlink(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			(root / "leaf-000000" / "candidate-space-2.jsonl").write_text("{}\n")
			with self.assertRaisesRegex(ValueError, "checksum tree is incomplete"):
				MODULE.verify_exact_authority(root)
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			(root / "link").symlink_to(root / "MERGE_AUTHORITY.json")
			with self.assertRaisesRegex(ValueError, "contains symlink"):
				MODULE.verify_exact_authority(root)

	def test_main_binds_verified_authority_into_each_forced_row(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			container = Path(temporary); root = container / "authority"; root.mkdir()
			self.authority(root)
			context = "org.apache.sysds.test.functions.Fixture#case"
			row = {"auditContext": context, "auditInvocation": context,
				"analysisFingerprint": "analysis", "occurrenceKeyHash": "occurrence",
				"semanticReplayOccurrenceKeyHash": "semantic", "inputSignature": [],
				"publishedStatesP": [{"exec": "FED", "output": "LOUT",
					"fType": "ROW", "signature": "native"}]}
			candidate = root / "leaf-000000" / "candidate-space-1.jsonl"
			candidate.write_text(json.dumps(row) + "\n")
			# Re-seal after replacing the fixture candidate with a useful row.
			(root / "SHA256SUMS").unlink(); self.authority_reseal(root)
			output = container / "forced.jsonl"
			authority = MODULE.verify_exact_authority(root)
			with patch("sys.argv", ["build", "--candidate-dir", str(root),
				"--require-isolated-runtime-context",
				*self.pinned_args(authority), "--output", str(output)]):
				MODULE.main()
			forced = json.loads(output.read_text().strip())
			self.assertEqual(forced["exactDiscoveryAuthority"]["inventorySha256"], "a" * 64)

	def test_strict_main_requires_all_caller_pinned_digests(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			output = root / "forced.jsonl"
			with patch("sys.argv", ["build", "--candidate-dir", str(root),
				"--require-isolated-runtime-context", "--output", str(output)]), \
				self.assertRaises(SystemExit):
				MODULE.main()
			self.assertFalse(output.exists())

	def test_rejects_self_consistent_resealed_tree_not_pinned_by_caller(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			pinned = MODULE.verify_exact_authority(root)
			candidate = root / "leaf-000000" / "candidate-space-1.jsonl"
			candidate.write_text('{"substituted":true}\n', encoding="utf-8")
			(root / "SHA256SUMS").unlink()
			self.authority_reseal(root)
			output = root / "forced.jsonl"
			with patch("sys.argv", ["build", "--candidate-dir", str(root),
				"--require-isolated-runtime-context", *self.pinned_args(pinned),
				"--output", str(output)]), \
				self.assertRaisesRegex(ValueError,
					"caller-pinned exact authority mismatch: authorityTreeSha256"):
				MODULE.main()
			self.assertFalse(output.exists())

	def test_rejects_wrong_pinned_digest_before_output(self) -> None:
		with tempfile.TemporaryDirectory() as temporary:
			root = Path(temporary); self.authority(root)
			authority = MODULE.verify_exact_authority(root)
			authority["inventorySha256"] = "f" * 64
			output = root / "forced.jsonl"
			with patch("sys.argv", ["build", "--candidate-dir", str(root),
				"--require-isolated-runtime-context", *self.pinned_args(authority),
				"--output", str(output)]), \
				self.assertRaisesRegex(ValueError,
					"caller-pinned exact authority mismatch: inventorySha256"):
				MODULE.main()
			self.assertFalse(output.exists())

	def pinned_args(self, authority: dict[str, str]) -> list[str]:
		return [
			"--expected-exact-authority-sha256", authority["authoritySha256"],
			"--expected-exact-inventory-sha256", authority["inventorySha256"],
			"--expected-source-receipt-sha256", authority["sourceReceiptSha256"],
			"--expected-exact-tree-sha256", authority["authorityTreeSha256"],
		]

	def authority_reseal(self, root: Path) -> None:
		paths = sorted(path for path in root.rglob("*") if path.is_file()
			and path.name != "SHA256SUMS")
		(root / "SHA256SUMS").write_text("".join(
			f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(root).as_posix()}\n"
			for path in paths))


if __name__ == "__main__":
	unittest.main()
