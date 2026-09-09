#!/usr/bin/env python3
"""Validate and merge deterministic exact-discovery shards."""
from __future__ import annotations

import argparse
import atexit
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path


HEX = set("0123456789abcdef")


def sha256(path: Path) -> str:
	return hashlib.sha256(path.read_bytes()).hexdigest()


def key_value_manifest(path: Path) -> dict[str, str]:
	values: dict[str, str] = {}
	for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
		if not line or "=" not in line:
			raise ValueError(f"{path}:{line_no}: malformed/empty manifest line")
		key, value = line.split("=", 1)
		if not re.fullmatch(r"[a-z][a-z0-9_]*", key) or not value:
			raise ValueError(f"{path}:{line_no}: malformed/empty manifest key/value")
		if key in values:
			raise ValueError(f"{path}:{line_no}: duplicate manifest key: {key}")
		values[key] = value
	return values


def capture_complete_tree(root: Path) -> tuple[str, dict[str, bytes], bytes]:
	"""Validate a shard and capture the exact verified bytes used downstream.

	Returning immutable bytes closes the validation-to-copy race: callers never
	re-open producer paths after their content has passed the checksum gate.
	"""
	if not root.is_dir() or root.is_symlink():
		raise ValueError(f"exact shard is not a physical directory: {root}")
	links = [path for path in root.rglob("*") if path.is_symlink()]
	if links:
		raise ValueError(f"exact shard contains symlink: {links[0]}")
	checksum_path = root / "SHA256SUMS"
	if not checksum_path.is_file() or not checksum_path.stat().st_size:
		raise ValueError(f"exact shard is missing non-empty SHA256SUMS: {root}")
	listed: dict[str, str] = {}
	checksum_bytes = checksum_path.read_bytes()
	for line_no, line in enumerate(checksum_bytes.decode("utf-8").splitlines(), 1):
		parts = line.split("  ", 1)
		if len(parts) != 2 or len(parts[0]) != 64 or any(c not in HEX for c in parts[0]):
			raise ValueError(f"{checksum_path}:{line_no}: malformed checksum")
		name = parts[1]
		path = Path(name)
		if not name or path.is_absolute() or ".." in path.parts or name == "SHA256SUMS":
			raise ValueError(f"{checksum_path}:{line_no}: unsafe checksum path")
		if name in listed:
			raise ValueError(f"{checksum_path}:{line_no}: duplicate checksum path: {name}")
		listed[name] = parts[0]
	actual = {path.relative_to(root).as_posix() for path in root.rglob("*")
		if path.is_file() and path.name != "SHA256SUMS"}
	if set(listed) != actual:
		raise ValueError(f"incomplete checksum tree: missing={sorted(actual-set(listed))}, "
			f"unlisted={sorted(set(listed)-actual)}")
	captured: dict[str, bytes] = {}
	for name, expected in listed.items():
		path = root / name
		if not path.is_file() or path.is_symlink():
			raise ValueError(f"checksum path became non-physical: {path}")
		content = path.read_bytes()
		if hashlib.sha256(content).hexdigest() != expected:
			raise ValueError(f"checksum mismatch: {root / name}")
		captured[name] = content
	return hashlib.sha256(checksum_bytes).hexdigest(), captured, checksum_bytes


def verify_complete_tree(root: Path) -> str:
	return capture_complete_tree(root)[0]


def materialize_captured_tree(root: Path, files: dict[str, bytes], checksum_bytes: bytes) -> None:
	for name, content in files.items():
		path = root / name
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_bytes(content)
	(root / "SHA256SUMS").write_bytes(checksum_bytes)
	verify_complete_tree(root)


def seal_tree(root: Path) -> str:
	paths = sorted((path for path in root.rglob("*") if path.is_file()
		and path.name != "SHA256SUMS"), key=lambda p: p.relative_to(root).as_posix())
	(root / "SHA256SUMS").write_text("".join(
		f"{sha256(path)}  {path.relative_to(root).as_posix()}\n" for path in paths),
		encoding="utf-8")
	return verify_complete_tree(root)


def load_inventory(path: Path) -> list[str]:
	values = [json.loads(line).get("invocation") for line in
		path.read_text(encoding="utf-8").splitlines() if line.strip()]
	if not values or any(not isinstance(value, str) for value in values):
		raise ValueError("invalid/empty exact discovery inventory")
	if len(values) != len(set(values)):
		raise ValueError("duplicate exact discovery inventory invocation")
	return values


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--inventory", type=Path, required=True)
	parser.add_argument("--inventory-sha256", required=True)
	parser.add_argument("--shard-dir", type=Path, action="append", required=True)
	parser.add_argument("--source-receipt", type=Path, required=True)
	parser.add_argument("--source-receipt-sha256", required=True)
	parser.add_argument("--output", type=Path, required=True)
	args = parser.parse_args()
	if args.output.exists():
		raise ValueError(f"refusing to reuse merged output: {args.output}")
	inventory_bytes = args.inventory.read_bytes()
	expected = [json.loads(line).get("invocation") for line in
		inventory_bytes.decode("utf-8").splitlines() if line.strip()]
	if not expected or any(not isinstance(value, str) for value in expected):
		raise ValueError("invalid/empty exact discovery inventory")
	if len(expected) != len(set(expected)):
		raise ValueError("duplicate exact discovery inventory invocation")
	expected_index = {invocation: index for index, invocation in enumerate(expected)}
	inventory_sha = hashlib.sha256(inventory_bytes).hexdigest()
	if not re.fullmatch(r"[0-9a-f]{64}", args.inventory_sha256):
		raise ValueError("expected inventory SHA-256 is malformed")
	if inventory_sha != args.inventory_sha256:
		raise ValueError("inventory does not match caller-pinned SHA-256")
	receipt_bytes = args.source_receipt.read_bytes()
	receipt_sha = hashlib.sha256(receipt_bytes).hexdigest()
	if len(args.source_receipt_sha256) != 64 or any(
		character not in "0123456789abcdef" for character in args.source_receipt_sha256):
		raise ValueError("expected source receipt SHA-256 is malformed")
	if receipt_sha != args.source_receipt_sha256:
		raise ValueError("source receipt does not match expected SHA-256")
	observed: dict[str, Path] = {}
	shard_indices: set[int] = set()
	shard_count: int | None = None
	build_witnesses: tuple[str, str] | None = None
	shard_tree_sha256: list[str] = []
	snapshot_root = Path(tempfile.mkdtemp(prefix="fedplanner-exact-shards-"))
	atexit.register(shutil.rmtree, snapshot_root, True)
	for snapshot_index, source_shard in enumerate(args.shard_dir):
		shard_checksum, captured_files, checksum_bytes = capture_complete_tree(source_shard)
		shard_tree_sha256.append(shard_checksum)
		shard = snapshot_root / f"shard-{snapshot_index}"
		shard.mkdir()
		materialize_captured_tree(shard, captured_files, checksum_bytes)
		manifest_path = shard / "RUN_MANIFEST.txt"
		if not manifest_path.is_file():
			raise ValueError(f"exact shard is missing RUN_MANIFEST.txt: {shard}")
		manifest = key_value_manifest(manifest_path)
		expected_manifest_keys = {"schema", "root", "inventory", "inventory_sha256",
			"source_receipt", "source_receipt_sha256", "build_contract",
			"main_build_witness_sha256", "test_build_witness_sha256",
			"inventory_leaves", "shard_index", "shard_count", "exact_leaves",
			"started", "failed_leaves", "candidate_files", "source_check_before_rc",
			"build_rc", "source_check_after_build_rc", "leaf_maven_rc",
			"source_check_after_rc", "source_paths_check_rc", "finished"}
		if set(manifest) != expected_manifest_keys:
			raise ValueError(f"exact shard terminal manifest key set mismatch: {shard}")
		if manifest.get("schema") != "fedplanner-exact-discovery-v1":
			raise ValueError(f"exact shard has invalid schema: {shard}")
		if manifest.get("inventory_sha256") != inventory_sha:
			raise ValueError(f"exact shard inventory SHA mismatch: {shard}")
		if manifest.get("inventory") != "INVENTORY.jsonl" \
			or sha256(shard / "INVENTORY.jsonl") != inventory_sha:
			raise ValueError(f"exact shard embedded inventory mismatch: {shard}")
		if manifest.get("source_receipt_sha256") != receipt_sha:
			raise ValueError(f"exact shard source receipt SHA mismatch: {shard}")
		if manifest.get("source_receipt") != "SOURCE_RECEIPT.txt" \
			or sha256(shard / "SOURCE_RECEIPT.txt") != receipt_sha:
			raise ValueError(f"exact shard embedded source receipt mismatch: {shard}")
		required_zero = ("source_check_before_rc", "build_rc",
			"source_check_after_build_rc", "leaf_maven_rc",
			"source_check_after_rc", "source_paths_check_rc")
		if any(manifest.get(key) != "0" for key in required_zero):
			raise ValueError(f"exact shard producer gate is missing/nonzero: {shard}")
		if (shard / "BUILD_RC.txt").read_text(encoding="utf-8").strip() != "0":
			raise ValueError(f"exact shard BUILD_RC is missing/nonzero: {shard}")
		for log_name in ("SOURCE_SHA256_CHECK_BEFORE.log",
			"SOURCE_SHA256_CHECK_AFTER_BUILD.log", "SOURCE_SHA256_CHECK_AFTER.log"):
			if not (shard / log_name).is_file():
				raise ValueError(f"exact shard source gate evidence missing: {shard / log_name}")
		witnesses = (manifest.get("main_build_witness_sha256", ""),
			manifest.get("test_build_witness_sha256", ""))
		if manifest.get("build_contract") != "clean-test-compile-v1" \
			or any(len(value) != 64 or any(character not in "0123456789abcdef"
				for character in value) for value in witnesses):
			raise ValueError(f"exact shard lacks clean-build witnesses: {shard}")
		if build_witnesses is None:
			build_witnesses = witnesses
		elif witnesses != build_witnesses:
			raise ValueError(f"exact shards have different clean-build witnesses: {shard}")
		try:
			index = int(manifest["shard_index"]); count = int(manifest["shard_count"])
			inventory_leaves = int(manifest["inventory_leaves"])
			exact_leaves = int(manifest["exact_leaves"])
			candidate_files = int(manifest["candidate_files"])
			failed_leaves = int(manifest["failed_leaves"])
		except (KeyError, ValueError) as exc:
			raise ValueError(f"exact shard has malformed topology/counts: {shard}") from exc
		if shard_count is None:
			shard_count = count
		if count != shard_count or not 0 <= index < count or index in shard_indices:
			raise ValueError(f"exact shard topology is inconsistent/duplicate: {shard}")
		if inventory_leaves != len(expected) or failed_leaves != 0 or candidate_files != exact_leaves:
			raise ValueError(f"exact shard reports failed/incomplete leaves: {shard}")
		shard_indices.add(index)
		before = len(observed)
		shard_invocations: set[str] = set()
		for invocation_file in sorted(shard.glob("leaves/*/INVOCATION.txt")):
			invocation = invocation_file.read_text(encoding="utf-8").strip()
			inventory_index = expected_index.get(invocation)
			if inventory_index is None:
				raise ValueError(f"unexpected exact leaf: {invocation}")
			if inventory_index % count != index:
				raise ValueError(
					f"exact leaf violates deterministic shard assignment: {invocation}")
			if invocation in observed:
				raise ValueError(f"duplicate exact leaf across shards: {invocation}")
			leaf = invocation_file.parent
			if leaf.name != f"{inventory_index:06d}":
				raise ValueError(f"exact leaf has wrong inventory index path: {invocation}")
			rc = (leaf / "MAVEN_RC.txt").read_text(encoding="utf-8").strip()
			candidates = sorted(leaf.glob("candidate-space-*.jsonl"))
			if rc != "0" or len(candidates) != 1 or not candidates[0].stat().st_size:
				raise ValueError(f"incomplete exact discovery leaf: {invocation}")
			for line in candidates[0].read_text(encoding="utf-8").splitlines():
				if line.strip() and json.loads(line).get("auditInvocation") != invocation:
					raise ValueError(f"candidate row has wrong exact invocation: {invocation}")
			observed[invocation] = leaf
			shard_invocations.add(invocation)
		if len(observed) - before != exact_leaves:
			raise ValueError(f"exact shard leaf count disagrees with manifest: {shard}")
		expected_shard = {invocation for inventory_index, invocation in enumerate(expected)
			if inventory_index % count == index}
		if shard_invocations != expected_shard:
			raise ValueError(f"exact shard has incomplete deterministic assignment: {shard}")
	if shard_count is None or shard_indices != set(range(shard_count)):
		raise ValueError(
			f"exact shard topology is incomplete: observed={sorted(shard_indices)}, count={shard_count}")
	missing = sorted(set(expected) - set(observed))
	unexpected = sorted(set(observed) - set(expected))
	if missing or unexpected:
		raise ValueError(f"exact shard coverage mismatch: missing={missing}, unexpected={unexpected}")
	args.output.parent.mkdir(parents=True, exist_ok=True)
	with tempfile.TemporaryDirectory(prefix=f".{args.output.name}.tmp-",
			dir=args.output.parent) as temporary_output:
		staged_output = Path(temporary_output) / "merged"
		staged_output.mkdir()
		for index, invocation in enumerate(expected):
			leaf = observed[invocation]
			destination = staged_output / f"leaf-{index:06d}"
			destination.mkdir()
			shutil.copy2(leaf / "INVOCATION.txt", destination / "INVOCATION.txt")
			for pattern in ("candidate-space-*.jsonl", "runtime-capability-*.jsonl"):
				for path in sorted(leaf.glob(pattern)):
					shutil.copy2(path, destination / path.name)
		authority = {
		"schema": "fedplanner-exact-discovery-authority-v1",
		"inventorySha256": inventory_sha,
		"sourceReceiptSha256": receipt_sha,
		"buildContract": "clean-test-compile-v1",
		"mainBuildWitnessSha256": build_witnesses[0] if build_witnesses else None,
		"testBuildWitnessSha256": build_witnesses[1] if build_witnesses else None,
		"shardCount": shard_count,
		"shardTreeChecksumSha256": sorted(shard_tree_sha256),
		"inventoryLeaves": len(expected),
		"mergedLeaves": len(observed),
		}
		(staged_output / "MERGE_AUTHORITY.json").write_text(
			json.dumps(authority, sort_keys=True) + "\n", encoding="utf-8")
		merged_tree_sha = seal_tree(staged_output)
		staged_output.rename(args.output)
	shutil.rmtree(snapshot_root)
	print(json.dumps({"schema": "fedplanner-exact-discovery-merge-v1",
		"inventoryLeaves": len(expected), "mergedLeaves": len(observed),
		"sourceReceiptSha256": receipt_sha, "shards": shard_count,
		"mergedTreeChecksumSha256": merged_tree_sha,
		"output": str(args.output)}, sort_keys=True))


if __name__ == "__main__":
	main()
