#!/usr/bin/env python3
"""Aggregate primary, isolated, and semantic forced-state campaign results.

The authoritative result for a target is the latest applicable stage:
semantic retry, structural isolated retry, then primary.  This tool validates
the exact target sets between stages before producing any classification.
It deliberately does not infer Missing states, and it keeps replay failures
separate from physical infeasibility.
"""

from __future__ import annotations

import argparse
import atexit
import collections
import csv
import hashlib
import json
import re
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from exact_authority_envelope import (
	RUN_MANIFEST_EXACT_AUTHORITY_FIELDS,
	normalize_exact_authority_envelope,
)


@dataclass(frozen=True)
class StageData:
	name: str
	rows: dict[str, dict[str, Any]]
	capabilities: dict[str, list[dict[str, Any]]]
	directories: list[dict[str, Any]]


def read_jsonl(paths: Iterable[Path]) -> list[dict[str, Any]]:
	rows: list[dict[str, Any]] = []
	for path in sorted(paths):
		for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
			if not line.strip():
				continue
			try:
				rows.append(json.loads(line))
			except json.JSONDecodeError as exc:
				raise ValueError(f"{path}:{line_no}: invalid JSON: {exc}") from exc
	return rows


def key_value_manifest(path: Path) -> dict[str, str]:
	values: dict[str, str] = {}
	for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
		key, separator, value = line.partition("=")
		if not separator or not re.fullmatch(r"[A-Za-z0-9_]+", key) or not value:
			raise ValueError(f"{path}:{line_no}: malformed key-value manifest entry")
		if key in values:
			raise ValueError(f"{path}:{line_no}: duplicate key in manifest: {key}")
		values[key] = value
	return values


def validate_run_manifest_authority(path: Path, values: dict[str, str]) -> tuple[str, str]:
	if values.get("schema") != "fedplanner-forced-campaign-v1":
		raise ValueError(f"invalid forced campaign RUN_MANIFEST schema: {path}")
	for field in (
		"strict_manifest_check_rc", "source_receipt_rc", "build_rc",
		"post_build_source_receipt_rc", "maven_rc", "summary_rc",
		"final_source_receipt_rc", "authoritative_summary_validation_rc",
	):
		if values.get(field) != "0":
			raise ValueError(f"RUN_MANIFEST authority field must be string 0: {field}: {path}")
	if values.get("build_contract") != "clean-test-compile-v1":
		raise ValueError(f"invalid RUN_MANIFEST build contract: {path}")
	if values.get("targets_per_jvm") != "1":
		raise ValueError(f"RUN_MANIFEST targets_per_jvm must be 1: {path}")
	witnesses = tuple(values.get(field, "") for field in (
		"main_build_witness_sha256", "test_build_witness_sha256"))
	if any(not re.fullmatch(r"[0-9a-f]{64}", witness) for witness in witnesses):
		raise ValueError(f"invalid RUN_MANIFEST build witness SHA-256: {path}")
	return witnesses


def validate_exact_discovery_authority(rows: list[dict[str, Any]],
	expected_source_receipt_sha256: str) -> dict[str, str]:
	authorities: list[dict[str, str]] = []
	for index, row in enumerate(rows, 1):
		authorities.append(normalize_exact_authority_envelope(
			row.get("exactDiscoveryAuthority"), label=f"forced manifest row {index}"))
	first = authorities[0] if authorities else None
	if first is None or any(authority != first for authority in authorities[1:]):
		raise ValueError("forced manifest rows disagree on exactDiscoveryAuthority")
	if first["sourceReceiptSha256"] != expected_source_receipt_sha256:
		raise ValueError("exact discovery authority source digest differs from caller authority")
	return first


def outcome_counts(rows: Iterable[dict[str, Any]]) -> dict[str, int]:
	return dict(sorted(collections.Counter(str(row.get("outcome")) for row in rows).items()))


def target_id_from_path(directory: Path, path: Path) -> str | None:
	parts = path.relative_to(directory).parts
	try:
		position = parts.index("targets")
	except ValueError:
		return None
	return parts[position + 1] if position + 1 < len(parts) else None


def capture_checksum_tree(directory: Path) -> tuple[dict[str, bytes], bytes]:
	"""Validate a campaign tree and capture the exact bytes consumed later."""
	checksum_path = directory / "SHA256SUMS.txt"
	if not checksum_path.is_file():
		raise ValueError(f"missing SHA256SUMS.txt: {directory}")
	symlinks = sorted(path.relative_to(directory).as_posix()
		for path in directory.rglob("*") if path.is_symlink())
	if symlinks:
		raise ValueError(f"checksum tree contains symlinks: {symlinks[:20]} ({len(symlinks)})")
	actual = {
		path.relative_to(directory).as_posix()
		for path in directory.rglob("*")
		if path.is_file() and not path.is_symlink() and path != checksum_path
	}
	if not actual:
		raise ValueError(f"checksum tree contains no regular artifact files: {directory}")
	listed: set[str] = set()
	captured: dict[str, bytes] = {}
	checksum_bytes = checksum_path.read_bytes()
	lines = checksum_bytes.decode("utf-8").splitlines()
	if not lines:
		raise ValueError(f"empty SHA256SUMS.txt: {directory}")
	for line_no, line in enumerate(lines, 1):
		digest, separator, relative = line.partition("  ")
		if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest) or not relative:
			raise ValueError(f"invalid checksum entry {checksum_path}:{line_no}")
		relative_path = Path(relative.removeprefix("./"))
		normalized = relative_path.as_posix()
		if relative_path.is_absolute() or ".." in relative_path.parts \
			or normalized in {"", ".", "SHA256SUMS.txt"}:
			raise ValueError(f"unsafe checksum path {checksum_path}:{line_no}")
		if normalized in listed:
			raise ValueError(f"duplicate checksum path {checksum_path}:{line_no}: {normalized}")
		listed.add(normalized)
		path = directory / relative_path
		if not path.is_file() or path.is_symlink():
			raise ValueError(f"checksum path became non-physical: {path}")
		content = path.read_bytes()
		if hashlib.sha256(content).hexdigest() != digest:
			raise ValueError(f"checksum mismatch for {path}")
		captured[normalized] = content
	if listed != actual:
		missing = sorted(actual - listed)
		unexpected = sorted(listed - actual)
		raise ValueError(
			f"checksum path-set mismatch for {directory}: unlisted={missing[:20]} ({len(missing)}), "
			f"nonregular_or_missing={unexpected[:20]} ({len(unexpected)})")
	return captured, checksum_bytes


def verify_checksums(directory: Path) -> None:
	capture_checksum_tree(directory)


def materialize_checksum_tree(directory: Path, files: dict[str, bytes], checksum_bytes: bytes) -> None:
	for name, content in files.items():
		path = directory / name
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_bytes(content)
	(directory / "SHA256SUMS.txt").write_bytes(checksum_bytes)
	verify_checksums(directory)


def load_stage(name: str, directories: list[Path], allowed_hosts: set[str],
	full_manifest_sha256: str, full_manifest_rows: list[dict[str, Any]],
	full_manifest_by_id: dict[str, dict[str, Any]], expected_source_receipt_sha256: str,
	expected_exact_discovery_authority: dict[str, str],
	primary_source_sha256: str | None = None,
	primary_authority_witness: tuple[str, str] | None = None) -> StageData:
	by_target: dict[str, dict[str, Any]] = {}
	capabilities: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
	receipts: list[dict[str, Any]] = []
	snapshot_root = Path(tempfile.mkdtemp(prefix=f"fedplanner-{name}-campaigns-"))
	atexit.register(shutil.rmtree, snapshot_root, True)
	for directory_index, source_directory in enumerate(directories):
		directory = source_directory
		if not directory.is_dir():
			raise ValueError(f"{name}: missing campaign directory: {directory}")
		if any(directory.rglob("HARNESS_OBSOLETE.txt")):
			raise ValueError(f"{name}: obsolete harness marker under {directory}")
		captured_files, checksum_bytes = capture_checksum_tree(directory)
		directory = snapshot_root / f"campaign-{directory_index}"
		directory.mkdir()
		materialize_checksum_tree(directory, captured_files, checksum_bytes)
		summary_path = directory / "CAMPAIGN_SUMMARY.json"
		run_manifest_path = directory / "RUN_MANIFEST.txt"
		if not summary_path.is_file() or not run_manifest_path.is_file():
			raise ValueError(f"{name}: missing campaign summary or run manifest: {directory}")
		summary = json.loads(summary_path.read_text(encoding="utf-8"))
		if summary.get("infrastructureStatus") != "PASS":
			raise ValueError(f"{name}: infrastructure is not PASS: {directory}")
		if summary.get("missingResultTargetIds") or summary.get("unexpectedResultTargetIds"):
			raise ValueError(f"{name}: campaign summary has missing/unexpected target IDs: {directory}")
		if int(summary.get("duplicateResultRows", -1)) != 0:
			raise ValueError(f"{name}: campaign summary has duplicate result rows: {directory}")
		if summary.get("unexpectedRuntimeCapabilityTargetIds"):
			raise ValueError(f"{name}: unexpected runtime capability target IDs: {directory}")

		run_manifest = key_value_manifest(run_manifest_path)
		authority_witness = validate_run_manifest_authority(run_manifest_path, run_manifest)
		for run_field, authority_field in RUN_MANIFEST_EXACT_AUTHORITY_FIELDS.items():
			if run_manifest.get(run_field) != expected_exact_discovery_authority[authority_field]:
				raise ValueError(f"{name}: exact discovery authority receipt mismatch: {run_field}: {directory}")
		if primary_authority_witness is not None and authority_witness != primary_authority_witness:
			raise ValueError(f"{name}: build authority witness differs from primary: {directory}")
		input_manifest_sha256 = run_manifest.get("manifest_sha256", "")
		if len(input_manifest_sha256) != 64 or any(
			character not in "0123456789abcdef" for character in input_manifest_sha256):
			raise ValueError(f"{name}: invalid input manifest digest: {directory}")
		if name == "primary" and input_manifest_sha256 != full_manifest_sha256:
			raise ValueError(f"{name}: full input manifest digest mismatch: {directory}")
		source_sha = run_manifest.get("source_manifest_sha256", "")
		if len(source_sha) != 64 or any(character not in "0123456789abcdef" for character in source_sha):
			raise ValueError(f"{name}: invalid source manifest digest: {directory}")
		if primary_source_sha256 is not None and source_sha != primary_source_sha256:
			raise ValueError(f"{name}: source manifest digest differs from primary: {directory}")
		if source_sha != expected_source_receipt_sha256:
			raise ValueError(f"{name}: source receipt digest differs from caller authority: {directory}")
		receipt_copy_name = run_manifest.get("source_receipt_copy", "")
		if receipt_copy_name != "SOURCE_RECEIPT_SHA256SUMS.txt":
			raise ValueError(f"{name}: invalid self-contained source receipt path: {directory}")
		if run_manifest.get("source_manifest") != receipt_copy_name:
			raise ValueError(f"{name}: source_manifest must name the self-contained receipt: {directory}")
		receipt_copy = directory / receipt_copy_name
		if not receipt_copy.is_file() or receipt_copy.is_symlink():
			raise ValueError(f"{name}: missing self-contained source receipt: {directory}")
		if hashlib.sha256(receipt_copy.read_bytes()).hexdigest() != expected_source_receipt_sha256:
			raise ValueError(f"{name}: self-contained source receipt digest mismatch: {directory}")
		host = run_manifest.get("host", "")
		if allowed_hosts and host not in allowed_hosts:
			raise ValueError(f"{name}: host {host!r} is outside the allow-list: {directory}")
		rows = read_jsonl(directory.rglob("forced-state-results-*.jsonl"))
		if len(rows) != int(summary.get("resultRows", -1)):
			raise ValueError(f"{name}: result count disagrees with summary: {directory}")
		for row in rows:
			target_id = str(row.get("targetId", ""))
			if not target_id:
				raise ValueError(f"{name}: result without targetId: {directory}")
			if target_id in by_target:
				raise ValueError(f"{name}: duplicate target across campaign directories: {target_id}")
			by_target[target_id] = row
		chunk_paths = sorted(directory.glob("chunk-manifests/chunk-*.jsonl"))
		if not chunk_paths:
			raise ValueError(f"{name}: missing executed chunk manifests: {directory}")
		executed_bytes = b"".join(path.read_bytes() for path in chunk_paths)
		executed_rows = read_jsonl(chunk_paths)
		executed_ids: list[str] = []
		for row in executed_rows:
			target_id = str(row.get("targetId", ""))
			if not target_id or target_id in executed_ids:
				raise ValueError(f"{name}: missing/duplicate executed target ID: {directory}")
			executed_ids.append(target_id)
			if target_id not in full_manifest_by_id:
				raise ValueError(f"{name}: executed target is not in full manifest: {target_id}")
			if row != full_manifest_by_id[target_id]:
				raise ValueError(f"{name}: executed subset payload differs from full manifest: {target_id}")
		if set(executed_ids) != {str(row.get("targetId")) for row in rows}:
			raise ValueError(f"{name}: executed subset target IDs differ from result target IDs: {directory}")
		executed_subset_sha256 = hashlib.sha256(executed_bytes).hexdigest()
		try:
			shard_index = int(run_manifest.get("shard_index", ""))
			shard_count = int(run_manifest.get("shard_count", ""))
		except ValueError as exc:
			raise ValueError(f"{name}: malformed shard coordinates: {directory}") from exc
		if shard_count <= 0 or shard_index < 0 or shard_index >= shard_count:
			raise ValueError(f"{name}: invalid shard coordinates: {directory}")
		if name == "primary":
			if run_manifest.get("max_targets") != "unbounded":
				raise ValueError(f"primary: authoritative campaign forbids MAX_TARGETS: {directory}")
			expected_shard_rows = [row for index, row in enumerate(full_manifest_rows)
				if index % shard_count == shard_index]
			if executed_rows != expected_shard_rows:
				raise ValueError(
					f"primary: executed rows do not match deterministic shard membership: {directory}")
		if name != "primary" and input_manifest_sha256 != executed_subset_sha256:
			raise ValueError(f"{name}: retry input digest differs from executed subset digest: {directory}")
		capability_rows: list[dict[str, Any]] = []
		directory_capability_targets: set[str] = set()
		for path in sorted(directory.rglob("runtime-capability-*.jsonl")):
			target_id = target_id_from_path(directory, path)
			if target_id is None:
				continue
			path_rows = read_jsonl([path])
			capability_rows.extend(path_rows)
			capabilities[target_id].extend(path_rows)
			directory_capability_targets.add(target_id)
		if any(target_id not in by_target for target_id in capabilities):
			raise ValueError(f"{name}: runtime capability references a target without a result")
		directory_result_ids = {str(row.get("targetId")) for row in rows}
		expected_missing_capabilities = sorted(directory_result_ids - directory_capability_targets)
		if summary.get("missingRuntimeCapabilityTargetIds") != expected_missing_capabilities:
			raise ValueError(f"{name}: runtime capability missing-target summary mismatch: {directory}")
		if summary.get("runtimeCapabilityOutcomes") != outcome_counts(capability_rows):
			raise ValueError(f"{name}: runtime capability outcome summary mismatch: {directory}")
		for row in rows:
			if row.get("outcome") != "SUCCESS":
				continue
			target_id = str(row.get("targetId"))
			target_outcomes = outcome_counts(capabilities.get(target_id, []))
			if not target_outcomes or set(target_outcomes) != {"SUCCESS"}:
				raise ValueError(
					f"{name}: successful target lacks exclusively successful runtime capability: {target_id}")
		receipts.append({
			"directory": str(source_directory),
			"host": host,
			"expectedTargets": summary.get("expectedTargets"),
			"resultRows": len(rows),
			"resultOutcomes": outcome_counts(rows),
			"runtimeCapabilityRows": len(capability_rows),
			"runtimeCapabilityOutcomes": outcome_counts(capability_rows),
			"sourceManifest": run_manifest.get("source_manifest"),
			"sourceManifestSha256": run_manifest.get("source_manifest_sha256"),
			"authorityWitness": {
				"source_manifest_sha256": source_sha,
				"main_build_witness_sha256": authority_witness[0],
				"test_build_witness_sha256": authority_witness[1],
			},
			"exactDiscoveryAuthority": expected_exact_discovery_authority,
			"manifestSha256": input_manifest_sha256,
			"full_manifest_sha256": full_manifest_sha256,
			"executed_subset_manifest_sha256": executed_subset_sha256,
			"shardIndex": shard_index,
			"shardCount": shard_count,
		})
	shutil.rmtree(snapshot_root)
	return StageData(name, by_target, dict(capabilities), receipts)


def validate_primary_topology(primary: StageData,
	expected_manifest_sha256: str) -> tuple[str, tuple[str, str]]:
	if not primary.directories:
		raise ValueError("primary: no campaign directories")
	manifest_digests = {receipt["manifestSha256"] for receipt in primary.directories}
	source_digests = {receipt["sourceManifestSha256"] for receipt in primary.directories}
	shard_counts = {receipt["shardCount"] for receipt in primary.directories}
	if manifest_digests != {expected_manifest_sha256}:
		raise ValueError("primary: campaign directories disagree on the input manifest digest")
	if len(source_digests) != 1:
		raise ValueError("primary: campaign directories disagree on the source manifest digest")
	witnesses = {
		(receipt["authorityWitness"]["main_build_witness_sha256"],
			receipt["authorityWitness"]["test_build_witness_sha256"])
		for receipt in primary.directories
	}
	if len(witnesses) != 1:
		raise ValueError("primary: campaign directories disagree on the build authority witness")
	if len(shard_counts) != 1 or next(iter(shard_counts)) <= 0:
		raise ValueError("primary: campaign directories disagree on shard_count")
	shard_count = next(iter(shard_counts))
	indices = [receipt["shardIndex"] for receipt in primary.directories]
	if len(indices) != len(set(indices)) or set(indices) != set(range(shard_count)):
		raise ValueError(
			f"primary: incomplete or duplicate shard topology; indices={sorted(indices)}, count={shard_count}")
	return next(iter(source_digests)), next(iter(witnesses))


def validate_retry_provenance(stage: StageData, primary_source_sha256: str,
	full_manifest_sha256: str) -> None:
	for receipt in stage.directories:
		if receipt["sourceManifestSha256"] != primary_source_sha256:
			raise ValueError(f"{stage.name}: retry source digest differs from primary")
		if receipt["full_manifest_sha256"] != full_manifest_sha256:
			raise ValueError(f"{stage.name}: full manifest authority digest mismatch")


def require_exact_set(label: str, actual: set[str], expected: set[str]) -> None:
	if actual == expected:
		return
	missing = sorted(expected - actual)
	unexpected = sorted(actual - expected)
	raise ValueError(
		f"{label}: target-set mismatch; missing={missing[:20]} ({len(missing)}), "
		f"unexpected={unexpected[:20]} ({len(unexpected)})"
	)


def classify(row: dict[str, Any], capabilities: list[dict[str, Any]]) -> str:
	outcome = str(row.get("outcome"))
	capability_outcomes = outcome_counts(capabilities)
	if outcome == "SUCCESS":
		if row.get("constraintSatisfied") is not True:
			raise ValueError(f"successful target lacks a satisfied forced constraint: {row.get('targetId')}")
		if not capabilities or set(capability_outcomes) != {"SUCCESS"}:
			raise ValueError(
				f"successful target lacks exclusively successful runtime capability: {row.get('targetId')}")
		return "PUBLISHED_LEGAL_EXECUTED"
	if outcome == "WHOLE_PROGRAM_INFEASIBLE":
		return "PUBLISHED_NOT_GLOBALLY_FEASIBLE"
	if outcome == "TARGET_NOT_REACHED":
		return "UNTESTED_TARGET_NOT_EMITTED"
	if outcome == "REPLAY_IDENTITY_AMBIGUOUS":
		return "UNTESTED_REPLAY_IDENTITY_AMBIGUOUS"
	if outcome == "FAILURE_REQUIRES_TRIAGE":
		if row.get("constraintSatisfied") is not True:
			return "TRIAGE_CONSTRAINT_NOT_SATISFIED"
		if capability_outcomes.get("FAILURE", 0):
			return "TRIAGE_RUNTIME_CAPABILITY_FAILURE"
		if capabilities and set(capability_outcomes) == {"SUCCESS"}:
			return "TRIAGE_ASSERTION_AFTER_SUCCESSFUL_RUNTIME"
		return "TRIAGE_NO_RUNTIME_CAPABILITY_EVIDENCE"
	return "TRIAGE_" + outcome.replace(" ", "_").upper()


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
	path.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows),
		encoding="utf-8")


def write_breakdown(path: Path, final_rows: list[dict[str, Any]], field: str) -> None:
	counts: collections.Counter[tuple[str, str, str]] = collections.Counter()
	for row in final_rows:
		value = row.get(field)
		if isinstance(value, list):
			value = ",".join(str(item) for item in value)
		counts[(str(value or ""), row["outcome"], row["classification"])] += 1
	with path.open("w", newline="", encoding="utf-8") as handle:
		writer = csv.writer(handle)
		writer.writerow([field, "outcome", "classification", "targets"])
		for (value, outcome, classification), count in sorted(counts.items()):
			writer.writerow([value, outcome, classification, count])


def write_checksums(output: Path) -> None:
	lines: list[str] = []
	for path in sorted(p for p in output.rglob("*") if p.is_file() and p.name != "SHA256SUMS.txt"):
		digest = hashlib.sha256(path.read_bytes()).hexdigest()
		lines.append(f"{digest}  {path.relative_to(output)}")
	(output / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
	parser = argparse.ArgumentParser()
	parser.add_argument("--manifest", type=Path, required=True)
	parser.add_argument("--expected-manifest-sha256", required=True)
	parser.add_argument("--expected-source-receipt-sha256", required=True)
	parser.add_argument("--primary", type=Path, action="append", default=[], required=True)
	parser.add_argument("--isolated", type=Path, action="append", default=[])
	parser.add_argument("--semantic", type=Path, action="append", default=[])
	parser.add_argument("--allowed-host", action="append", default=[])
	parser.add_argument("--out-dir", type=Path, required=True)
	args = parser.parse_args()

	if args.out_dir.exists():
		raise SystemExit(f"refusing to reuse output directory: {args.out_dir}")
	expected_manifest_sha256 = hashlib.sha256(args.manifest.read_bytes()).hexdigest()
	for label, digest in (("expected manifest", args.expected_manifest_sha256),
		("expected source receipt", args.expected_source_receipt_sha256)):
		if not re.fullmatch(r"[0-9a-f]{64}", digest):
			raise SystemExit(f"invalid caller-trusted {label} SHA-256")
	if expected_manifest_sha256 != args.expected_manifest_sha256:
		raise SystemExit("forced manifest digest differs from caller authority")
	manifest_rows = read_jsonl([args.manifest])
	manifest_by_id: dict[str, dict[str, Any]] = {}
	for row in manifest_rows:
		target_id = str(row.get("targetId", ""))
		if not target_id or target_id in manifest_by_id:
			raise SystemExit(f"invalid or duplicate manifest targetId: {target_id!r}")
		manifest_by_id[target_id] = row
	manifest_ids = set(manifest_by_id)
	allowed_hosts = set(args.allowed_host)

	try:
		exact_discovery_authority = validate_exact_discovery_authority(
			manifest_rows, args.expected_source_receipt_sha256)
		primary = load_stage("primary", args.primary, allowed_hosts, expected_manifest_sha256,
			manifest_rows, manifest_by_id, args.expected_source_receipt_sha256,
			exact_discovery_authority)
		primary_source_sha256, primary_authority_witness = validate_primary_topology(
			primary, expected_manifest_sha256)
		if primary_authority_witness != (
			exact_discovery_authority["mainBuildWitnessSha256"],
			exact_discovery_authority["testBuildWitnessSha256"]):
			raise ValueError("forced campaign bytecode witness differs from exact discovery authority")
		require_exact_set("primary versus manifest", set(primary.rows), manifest_ids)
		expected_isolated = {
			target_id for target_id, row in primary.rows.items() if row.get("outcome") != "SUCCESS"
		}
		isolated = load_stage("isolated", args.isolated, allowed_hosts, expected_manifest_sha256,
			manifest_rows, manifest_by_id, args.expected_source_receipt_sha256,
			exact_discovery_authority,
			primary_source_sha256, primary_authority_witness)
		require_exact_set("isolated versus primary non-success", set(isolated.rows), expected_isolated)
		validate_retry_provenance(isolated, primary_source_sha256, expected_manifest_sha256)
		expected_semantic = {
			target_id for target_id, row in isolated.rows.items()
			if row.get("outcome") == "TARGET_NOT_REACHED"
		}
		semantic = load_stage("semantic", args.semantic, allowed_hosts, expected_manifest_sha256,
			manifest_rows, manifest_by_id, args.expected_source_receipt_sha256,
			exact_discovery_authority,
			primary_source_sha256, primary_authority_witness)
		require_exact_set("semantic versus persistent TARGET_NOT_REACHED",
			set(semantic.rows), expected_semantic)
		validate_retry_provenance(semantic, primary_source_sha256, expected_manifest_sha256)
	except ValueError as exc:
		raise SystemExit(str(exc)) from exc

	final_rows: list[dict[str, Any]] = []
	stage_counts: collections.Counter[str] = collections.Counter()
	for target_id in sorted(manifest_ids):
		if target_id in semantic.rows:
			stage, stage_data, result = "semantic", semantic, semantic.rows[target_id]
		elif target_id in isolated.rows:
			stage, stage_data, result = "isolated", isolated, isolated.rows[target_id]
		else:
			stage, stage_data, result = "primary", primary, primary.rows[target_id]
		capabilities = stage_data.capabilities.get(target_id, [])
		try:
			classification = classify(result, capabilities)
		except ValueError as exc:
			raise SystemExit(str(exc)) from exc
		manifest_row = manifest_by_id[target_id]
		stage_counts[stage] += 1
		final_rows.append({
			"schema": "fedplanner-forced-authoritative-result-v1",
			"targetId": target_id,
			"authoritativeStage": stage,
			"outcome": str(result.get("outcome")),
			"classification": classification,
			"constraintSatisfied": result.get("constraintSatisfied"),
			"runtimeCapabilityRows": len(capabilities),
			"runtimeCapabilityOutcomes": outcome_counts(capabilities),
			"replayContext": manifest_row.get("replayContext"),
			"auditContexts": manifest_row.get("auditContexts", []),
			"opcode": manifest_row.get("opcode"),
			"hopClass": manifest_row.get("hopClass"),
			"state": manifest_row.get("state"),
			"privacy": manifest_row.get("privacy", []),
			"occurrenceKeyHash": manifest_row.get("occurrenceKeyHash"),
			"semanticOccurrenceKeyHash": manifest_row.get("semanticOccurrenceKeyHash"),
			"result": result,
		})

	final_outcomes = outcome_counts(final_rows)
	classifications = dict(sorted(collections.Counter(
		row["classification"] for row in final_rows).items()))
	authoritative_capability_outcomes: collections.Counter[str] = collections.Counter()
	for row in final_rows:
		authoritative_capability_outcomes.update(row["runtimeCapabilityOutcomes"])
	unresolved = [row for row in final_rows if row["classification"].startswith(("UNTESTED_", "TRIAGE_"))]
	summary = {
		"schema": "fedplanner-forced-authoritative-summary-v1",
		"validationStatus": "PASS",
		"manifestTargets": len(manifest_ids),
		"manifestSha256": expected_manifest_sha256,
		"full_manifest_sha256": expected_manifest_sha256,
		"manifestProvenance": {
			"full_manifest_sha256": expected_manifest_sha256,
			"executed_subset_manifest_sha256": {
				stage.name: [receipt["executed_subset_manifest_sha256"]
					for receipt in stage.directories]
				for stage in (primary, isolated, semantic)
			},
		},
		"primarySourceManifestSha256": primary_source_sha256,
		"authorityWitness": {
			"source_manifest_sha256": primary_source_sha256,
			"main_build_witness_sha256": primary_authority_witness[0],
			"test_build_witness_sha256": primary_authority_witness[1],
		},
		"exactDiscoveryAuthority": exact_discovery_authority,
		"primaryRetryRequirement": len(expected_isolated),
		"semanticRetryRequirement": len(expected_semantic),
		"stageReceipts": {
			"primary": primary.directories,
			"isolated": isolated.directories,
			"semantic": semantic.directories,
		},
		"stageOutcomes": {
			"primary": outcome_counts(primary.rows.values()),
			"isolated": outcome_counts(isolated.rows.values()),
			"semantic": outcome_counts(semantic.rows.values()),
		},
		"authoritativeStageCounts": dict(sorted(stage_counts.items())),
		"finalOutcomes": final_outcomes,
		"finalClassifications": classifications,
		"authoritativeRuntimeCapabilityOutcomes": dict(sorted(authoritative_capability_outcomes.items())),
		"unresolvedTargets": len(unresolved),
		"missingInference": "NOT_PERFORMED_BY_FORCED_PUBLISHED_STATE_AGGREGATION",
	}

	args.out_dir.parent.mkdir(parents=True, exist_ok=True)
	with tempfile.TemporaryDirectory(prefix=f".{args.out_dir.name}.tmp-",
			dir=args.out_dir.parent) as temporary_output:
		staged_output = Path(temporary_output) / "aggregate"
		staged_output.mkdir()
		write_jsonl(staged_output / "FINAL_RESULTS.jsonl", final_rows)
		write_jsonl(staged_output / "UNRESOLVED_RESULTS.jsonl", unresolved)
		(staged_output / "SUMMARY.json").write_text(
			json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
		write_breakdown(staged_output / "BY_CONTEXT.csv", final_rows, "replayContext")
		write_breakdown(staged_output / "BY_OPCODE.csv", final_rows, "opcode")
		write_breakdown(staged_output / "BY_STATE.csv", final_rows, "state")
		write_breakdown(staged_output / "BY_PRIVACY.csv", final_rows, "privacy")
		write_checksums(staged_output)
		staged_output.rename(args.out_dir)
	print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
	main()
