#!/usr/bin/env python3
"""Build a deterministic physical-state forcing manifest from candidate JSONL.

Each manifest row is one selector-visible physical state keyed by exact compiled
occurrence and ordered input signature. Duplicate native/derived authorities are
retained as provenance counts but collapse to one runtime state request.
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

from exact_authority_envelope import normalize_exact_authority_envelope


HEX = set("0123456789abcdef")


def file_sha256(path: Path) -> str:
	return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_authority_sha256(root: Path) -> str:
	"""Return the caller-visible digest of every physical file in an authority tree."""
	digest = hashlib.sha256()
	paths = sorted(path for path in root.rglob("*") if path.is_file() or path.is_symlink())
	if not paths:
		raise ValueError(f"exact-authority tree is empty: {root}")
	for path in paths:
		if path.is_symlink():
			raise ValueError(f"exact-authority tree contains symlink: {path}")
		relative = path.relative_to(root).as_posix().encode()
		content = path.read_bytes()
		digest.update(len(relative).to_bytes(8, "big"))
		digest.update(relative)
		digest.update(len(content).to_bytes(8, "big"))
		digest.update(content)
	return digest.hexdigest()


def verify_exact_authority(candidate_dir: Path) -> dict[str, Any]:
	"""Verify and return the sealed exact-discovery authority envelope."""
	if not candidate_dir.is_dir() or candidate_dir.is_symlink():
		raise ValueError("candidate directory is not a physical exact-authority tree")
	links = [path for path in candidate_dir.rglob("*") if path.is_symlink()]
	if links:
		raise ValueError(f"exact-authority tree contains symlink: {links[0]}")
	checksum = candidate_dir / "SHA256SUMS"
	if not checksum.is_file() or not checksum.stat().st_size:
		raise ValueError("exact-authority tree is missing non-empty SHA256SUMS")
	listed: dict[str, str] = {}
	for line_no, line in enumerate(checksum.read_text(encoding="utf-8").splitlines(), 1):
		parts = line.split("  ", 1)
		if len(parts) != 2 or len(parts[0]) != 64 or any(c not in HEX for c in parts[0]):
			raise ValueError(f"SHA256SUMS:{line_no}: malformed checksum")
		name = parts[1]; path = Path(name)
		if not name or path.is_absolute() or ".." in path.parts or name == "SHA256SUMS":
			raise ValueError(f"SHA256SUMS:{line_no}: unsafe checksum path")
		if name in listed:
			raise ValueError(f"SHA256SUMS:{line_no}: duplicate checksum path: {name}")
		listed[name] = parts[0]
	actual = {path.relative_to(candidate_dir).as_posix()
		for path in candidate_dir.rglob("*") if path.is_file() and path.name != "SHA256SUMS"}
	if set(listed) != actual:
		raise ValueError("exact-authority checksum tree is incomplete")
	for name, digest in listed.items():
		if file_sha256(candidate_dir / name) != digest:
			raise ValueError(f"exact-authority checksum mismatch: {name}")
	authority_path = candidate_dir / "MERGE_AUTHORITY.json"
	try:
		authority = json.loads(authority_path.read_text(encoding="utf-8"))
	except (OSError, json.JSONDecodeError) as exc:
		raise ValueError("missing/invalid exact MERGE_AUTHORITY.json") from exc
	if authority.get("schema") != "fedplanner-exact-discovery-authority-v1":
		raise ValueError("invalid exact authority schema")
	for key in ("inventorySha256", "sourceReceiptSha256",
		"mainBuildWitnessSha256", "testBuildWitnessSha256"):
		value = authority.get(key)
		if not isinstance(value, str) or len(value) != 64 or any(c not in HEX for c in value):
			raise ValueError(f"exact authority has malformed {key}")
	if authority.get("buildContract") != "clean-test-compile-v1":
		raise ValueError("exact authority has invalid build contract")
	shard_count = authority.get("shardCount")
	shard_digests = authority.get("shardTreeChecksumSha256")
	if not isinstance(shard_count, int) or shard_count < 1 \
		or not isinstance(shard_digests, list) or len(shard_digests) != shard_count \
		or any(not isinstance(value, str) or len(value) != 64
			or any(c not in HEX for c in value) for value in shard_digests):
		raise ValueError("exact authority has malformed shard provenance")
	candidate_files = list(candidate_dir.rglob("candidate-space-*.jsonl"))
	if not isinstance(authority.get("inventoryLeaves"), int) \
		or authority.get("inventoryLeaves") < 1 \
		or authority.get("mergedLeaves") != authority.get("inventoryLeaves") \
		or authority.get("mergedLeaves") != len(candidate_files):
		raise ValueError("exact authority leaf/candidate count mismatch")
	return normalize_exact_authority_envelope({
		"authoritySha256": file_sha256(authority_path),
		"authorityTreeSha256": tree_authority_sha256(candidate_dir),
		"treeChecksumSha256": file_sha256(checksum),
		"inventorySha256": authority["inventorySha256"],
		"sourceReceiptSha256": authority["sourceReceiptSha256"],
		"mainBuildWitnessSha256": authority["mainBuildWitnessSha256"],
		"testBuildWitnessSha256": authority["testBuildWitnessSha256"],
	})


def verify_caller_pinned_exact_authority(authority: dict[str, Any],
		expected_authority_sha256: str, expected_inventory_sha256: str,
		expected_source_receipt_sha256: str, expected_tree_sha256: str) -> None:
	"""Reject a valid but substituted/resealed exact-discovery authority tree."""
	expected = {
		"authoritySha256": expected_authority_sha256,
		"inventorySha256": expected_inventory_sha256,
		"sourceReceiptSha256": expected_source_receipt_sha256,
		"authorityTreeSha256": expected_tree_sha256,
	}
	for key, value in expected.items():
		if not isinstance(value, str) or len(value) != 64 or any(c not in HEX for c in value):
			raise ValueError(f"malformed caller-pinned exact authority digest: {key}")
		if authority.get(key) != value:
			raise ValueError(
				f"caller-pinned exact authority mismatch: {key}: "
				f"expected={value} actual={authority.get(key)}")


def rows(paths: Iterable[Path]) -> Iterable[dict[str, Any]]:
	for path in sorted(paths):
		with path.open(encoding="utf-8") as handle:
			for line_no, line in enumerate(handle, 1):
				if not line.strip():
					continue
				try:
					yield json.loads(line)
				except json.JSONDecodeError as exc:
					raise ValueError(f"{path}:{line_no}: invalid JSONL: {exc}") from exc


def exact_runtime_invocation(row: dict[str, Any]) -> str:
	"""Return the producer-supplied exact JUnit leaf identity, fail closed.

	A ``class#method`` stack-frame context is not an exact invocation for a
	parameterized test: multiple parameter siblings share it.  The producer must
	therefore persist ``auditInvocation`` (including the parameter/display-name
	suffix when applicable).  Do not infer this identity from row order, analysis
	fingerprints, or Surefire reports; none of those joins a candidate row to one
	parameter leaf.
	"""
	context = row.get("auditContext")
	invocation = row.get("auditInvocation")
	if not isinstance(context, str) or not context.strip():
		raise ValueError("missing/null runtime auditContext")
	context = context.strip()
	if not context.startswith("org.apache.sysds.test.") or "#" not in context:
		raise ValueError(f"unknown/non-JUnit runtime auditContext: {context!r}")
	if not isinstance(invocation, str) or not invocation.strip():
		raise ValueError(
			"missing exact auditInvocation; class#method auditContext cannot "
			"distinguish parameterized JUnit leaf invocations")
	invocation = invocation.strip()
	if not invocation.startswith("org.apache.sysds.test.") or "#" not in invocation:
		raise ValueError(f"unknown/non-JUnit auditInvocation: {invocation!r}")
	base = invocation.split("[", 1)[0]
	if base != context:
		raise ValueError(
			f"auditInvocation does not belong to auditContext: {invocation!r} != {context!r}")
	return invocation


def require_isolated_runtime_contexts(paths: Iterable[Path]) -> None:
	"""Require exactly one replayable JUnit leaf invocation per PID file.

	A candidate file is PID-scoped.  Mixing test methods in one JVM allows
	compiler/static state from an earlier method to change a later method's
	predecessor FType and rewrite/shape proof.  The forced campaign, however,
	replays one method in a fresh JVM.  A mixed-context manifest can therefore
	publish a boundary signature that no isolated replay can reconstruct.
	"""
	for path in sorted(paths):
		file_rows = list(rows([path]))
		if not file_rows:
			raise ValueError(f"zero runtime audit invocations in candidate file: {path}")
		try:
			invocations = sorted({exact_runtime_invocation(row) for row in file_rows})
		except ValueError as exc:
			raise ValueError(f"{path}: {exc}") from exc
		if len(invocations) != 1:
			raise ValueError(
				"candidate PID file must contain exactly one runtime JUnit leaf "
				"invocation; rerun each leaf in an isolated Maven/Surefire "
				f"invocation: {path}: {invocations}"
			)


def input_signature(row: dict[str, Any]) -> str:
	return ",".join(
		f"{item.get('presence')}:{item.get('fType') or '-'}"
		for item in row.get("inputSignature", [])
	)


def physical_state(emission: dict[str, Any]) -> str:
	return "/".join(
		[str(emission.get("exec")), str(emission.get("output")),
		 str(emission.get("fType") or "-")]
	)


def target_id(key: tuple[str, ...]) -> str:
	return hashlib.sha256("\0".join(key).encode()).hexdigest()[:16]


def decoded_tokens(encoded: str, separator: str, expected: int | None = None) -> list[str]:
	"""Decode PlacementIdentity's length-prefixed fields/list serialization."""
	values: list[str] = []
	position = 0
	while position < len(encoded):
		colon = encoded.find(":", position)
		if colon < 0 or not encoded[position:colon].isdigit():
			raise ValueError(f"invalid length-prefixed signature at offset {position}")
		length = int(encoded[position:colon])
		start = colon + 1
		end = start + length
		if end > len(encoded):
			raise ValueError(f"truncated length-prefixed signature at offset {position}")
		values.append(encoded[start:end])
		position = end
		if position < len(encoded):
			if encoded[position] != separator:
				raise ValueError(
					f"expected separator {separator!r} at offset {position}")
			position += 1
	if expected is not None and len(values) != expected:
		raise ValueError(f"expected {expected} signature fields, found {len(values)}")
	return values


def normalized_control_path(path: str) -> str:
	return "/".join("*" if segment.isdigit() else segment
		for segment in path.split("/"))


def normalized_volatile_values(value: str) -> str:
	return re.sub(r"(?i)(localhost|127\.0\.0\.1|\[::1\]):[0-9]{1,5}",
		r"\1:<port>", value)


def replay_occurrence_hash(signature: str) -> str:
	"""Recompute the current Java audit replay identity from a captured occurrence.

	The production occurrence remains exact. Only audit replay normalizes numeric
	statement-block ordinals and loopback worker ports that can change between
	discovery and an otherwise identical isolated replay.
	"""
	compiled = decoded_tokens(signature, "|", 7)
	region = decoded_tokens(compiled[4], "|", 5)
	region_path = decoded_tokens(region[2], ",") if region[2] else []
	stable = "\0".join([
		compiled[1],
		normalized_control_path(compiled[2]),
		compiled[3],
		region[1],
		"\1".join(normalized_control_path(path) for path in region_path),
		normalized_control_path(region[3]),
		region[4],
		compiled[5],
		normalized_volatile_values(compiled[6]),
	])
	return hashlib.sha256(stable.encode()).hexdigest()[:16]


def semantic_replay_occurrence_hash(signature: str) -> str:
	"""Hash the replay-stable semantic source/control identity.

	Unlike the primary replay key, this secondary key omits the emitted HOP
	root/input path because dynamic rewrites may change that path in a clean JVM.
	The Java forcing hook accepts this key only when it identifies exactly one
	decision domain in the replay analysis.
	"""
	compiled = decoded_tokens(signature, "|", 7)
	region = decoded_tokens(compiled[4], "|", 5)
	region_path = decoded_tokens(region[2], ",") if region[2] else []
	stable = "\0".join([
		compiled[1],
		normalized_control_path(compiled[2]),
		compiled[3],
		region[1],
		"\1".join(normalized_control_path(path) for path in region_path),
		normalized_control_path(region[3]),
		region[4],
		normalized_volatile_values(compiled[6]),
	])
	return hashlib.sha256(stable.encode()).hexdigest()[:16]


def replay_context(contexts: list[str]) -> str | None:
	runtime = sorted(context for context in contexts
		if context.startswith("org.apache.sysds.test.")
		and "FederatedForcedStateAuditRunnerTest#" not in context)
	return runtime[0] if runtime else None


def surefire_context_status(report_dirs: Iterable[Path]) -> dict[str, str]:
	"""Return conservative JUnit-method status from Surefire XML reports.

	Candidate capture records the Java method rather than the parameterized case
	name.  A method is replay-safe only when every reported parameter instance
	passed.  This prevents an unrelated failing fixture from being used to label
	a physically executable planner state as a runtime failure.
	"""
	outcomes: dict[str, list[str]] = defaultdict(list)
	for directory in report_dirs:
		for path in sorted(directory.rglob("TEST-*.xml")):
			try:
				root = ET.parse(path).getroot()
			except ET.ParseError as exc:
				raise ValueError(f"{path}: invalid Surefire XML: {exc}") from exc
			for case in root.iter("testcase"):
				class_name = case.get("classname")
				method_name = case.get("name")
				if not class_name or not method_name:
					continue
				# JUnit 4 parameterized names use method[case description].
				method_name = method_name.split("[", 1)[0]
				context = f"{class_name}#{method_name}"
				if case.find("failure") is not None or case.find("error") is not None:
					outcomes[context].append("FAIL")
				elif case.find("skipped") is not None:
					outcomes[context].append("SKIP")
				else:
					outcomes[context].append("PASS")
	status: dict[str, str] = {}
	for context, values in outcomes.items():
		if "FAIL" in values:
			status[context] = "FAIL"
		elif "SKIP" in values:
			status[context] = "SKIP"
		else:
			status[context] = "PASS"
	return status


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--candidate-dir", type=Path, required=True)
	parser.add_argument("--output", type=Path, required=True)
	parser.add_argument("--surefire-dir", type=Path, action="append", default=[])
	parser.add_argument("--require-passing-context", action="store_true")
	parser.add_argument("--require-isolated-runtime-context", action="store_true")
	parser.add_argument("--expected-exact-authority-sha256")
	parser.add_argument("--expected-exact-inventory-sha256")
	parser.add_argument("--expected-source-receipt-sha256")
	parser.add_argument("--expected-exact-tree-sha256")
	args = parser.parse_args()
	if args.require_passing_context and not args.surefire_dir:
		parser.error("--require-passing-context requires at least one --surefire-dir")
	pinned_values = (args.expected_exact_authority_sha256,
		args.expected_exact_inventory_sha256, args.expected_source_receipt_sha256,
		args.expected_exact_tree_sha256)
	if args.require_isolated_runtime_context and any(value is None for value in pinned_values):
		parser.error("strict isolated manifest construction requires all caller-pinned "
			"exact authority SHA-256 arguments")
	if not args.require_isolated_runtime_context and any(value is not None for value in pinned_values):
		parser.error("caller-pinned exact authority arguments require "
			"--require-isolated-runtime-context")
	exact_authority = (verify_exact_authority(args.candidate_dir)
		if args.require_isolated_runtime_context else None)
	if exact_authority is not None:
		verify_caller_pinned_exact_authority(exact_authority, *pinned_values)
	context_status = surefire_context_status(args.surefire_dir)
	excluded: dict[str, int] = defaultdict(int)
	candidate_paths = sorted(args.candidate_dir.rglob("*candidate-space-*.jsonl"))
	if args.require_isolated_runtime_context:
		require_isolated_runtime_contexts(candidate_paths)

	grouped: dict[tuple[str, str, str, str, str, str],
		list[tuple[dict[str, Any], dict[str, Any]]]] = defaultdict(list)
	recomputed_occurrences = 0
	fallback_occurrences = 0
	for row in rows(candidate_paths):
		analysis = str(row.get("analysisFingerprint") or "")
		occurrence_signature = str(row.get("occurrence") or "")
		if occurrence_signature:
			try:
				occurrence = replay_occurrence_hash(occurrence_signature)
				semantic_occurrence = semantic_replay_occurrence_hash(occurrence_signature)
			except ValueError as exc:
				raise ValueError("invalid candidate occurrence signature for "
					f"{row.get('auditContext')}: {exc}") from exc
			recomputed_occurrences += 1
		else:
			occurrence = str(row.get("replayOccurrenceKeyHash")
				or row.get("occurrenceKeyHash") or "")
			semantic_occurrence = str(row.get("semanticReplayOccurrenceKeyHash") or "")
			fallback_occurrences += 1
		context = str(row.get("auditContext") or analysis)
		invocation = str(row.get("auditInvocation") or "")
		if args.require_passing_context:
			if not context.startswith("org.apache.sysds.test."):
				excluded["nonRuntimeContext"] += 1
				continue
			status = context_status.get(context, "UNKNOWN")
			if status != "PASS":
				excluded[f"context{status.title()}"] += 1
				continue
		inputs = input_signature(row)
		if not analysis or not occurrence:
			continue
		for emission in row.get("publishedStatesP", []):
			grouped[(context, invocation, occurrence, semantic_occurrence, inputs,
				physical_state(emission))].append((row, emission))

	args.output.parent.mkdir(parents=True, exist_ok=True)
	if args.output.exists():
		raise ValueError(f"refusing to overwrite forced manifest: {args.output}")
	output_tmp = args.output.with_name(args.output.name + ".tmp")
	if output_tmp.exists():
		raise ValueError(f"stale forced manifest temporary output: {output_tmp}")
	buffer = io.StringIO()
	with contextlib.nullcontext(buffer) as handle:
		for key in sorted(grouped):
			context, invocation, occurrence, semantic_occurrence, inputs, state = key
			members = grouped[key]
			first = members[0][0]
			analyses = sorted({str(row.get("analysisFingerprint")) for row, _ in members})
			raw_occurrences = sorted({str(row.get("occurrenceKeyHash")) for row, _ in members})
			discovery_replay_occurrences = sorted({str(row.get("replayOccurrenceKeyHash"))
				for row, _ in members if row.get("replayOccurrenceKeyHash")})
			contexts = sorted({str(row.get("auditContext")) for row, _ in members
				if row.get("auditContext")})
			out = {
				"schema": "fedplanner-forced-state-manifest-v1",
				"targetId": target_id((context, invocation, occurrence, inputs, state)
					if invocation else (context, occurrence, inputs, state)),
				"analysisFingerprint": analyses[0],
				"discoveryAnalysisFingerprints": analyses,
				"occurrenceKeyHash": occurrence,
				"semanticOccurrenceKeyHash": semantic_occurrence,
				"discoveryOccurrenceKeyHashes": raw_occurrences,
				"discoverySemanticReplayOccurrenceKeyHashes": sorted({
					str(row.get("semanticReplayOccurrenceKeyHash"))
					for row, _ in members if row.get("semanticReplayOccurrenceKeyHash")}),
				"discoveryReplayOccurrenceKeyHashes": discovery_replay_occurrences,
				"inputSignature": inputs,
				"state": state,
				"opcode": first.get("opcode"),
				"hopClass": first.get("hopClass"),
				"privacy": sorted({str(row.get("privacy")) for row, _ in members}),
				"auditContexts": contexts,
				"replayContext": replay_context(contexts),
				"replayInvocation": invocation or None,
				"exactReplayLeaf": bool(invocation),
				"authorityVariants": len({str(emission.get("signature"))
					for _, emission in members}),
				"derivedFedFout": any(bool(emission.get("derivedFedFout"))
					for _, emission in members),
			}
			if exact_authority is not None:
				out["exactDiscoveryAuthority"] = exact_authority
			handle.write(json.dumps(out, sort_keys=True) + "\n")
	if exact_authority is not None:
		# Detect candidate/authority mutation while the manifest was being built.
		final_authority = verify_exact_authority(args.candidate_dir)
		verify_caller_pinned_exact_authority(final_authority, *pinned_values)
		if final_authority != exact_authority:
			raise ValueError("exact authority changed during forced manifest construction")
	output_tmp.write_text(buffer.getvalue(), encoding="utf-8")
	output_tmp.replace(args.output)

	print(json.dumps({"candidateDirectory": str(args.candidate_dir),
		"output": str(args.output), "forcedTargets": len(grouped),
		"knownTestContexts": len(context_status),
		"isolatedRuntimeContextRequired": args.require_isolated_runtime_context,
		"recomputedOccurrenceRows": recomputed_occurrences,
		"fallbackOccurrenceRows": fallback_occurrences,
		"exactDiscoveryAuthority": exact_authority,
		"excludedCandidateRows": dict(sorted(excluded.items()))}, sort_keys=True))


if __name__ == "__main__":
	main()
