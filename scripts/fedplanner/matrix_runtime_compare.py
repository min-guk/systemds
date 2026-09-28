#!/usr/bin/env python3
"""Trusted post-timer output comparison for the W1357 matrix campaign.

This is deliberately a thin adapter around the frozen campaign comparator.  It
does not execute a workload, regenerate references, or reinterpret a failed
reference set as usable.  The adapter only fixes the legacy driver's stale
request grammar by binding the immutable, host-aware cpref5 reference source.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
from typing import Any


REFERENCE_HOST = "so007"
REFERENCE_ROOT = Path("/home/mchoi/w1357-reference-20260923-cpref5")
REFERENCE_RESULT_SHA256 = "76963e5a6c0cdb0b60b2f42fc4c8bac0fa523cb0fc687f4fde82cb9ed2f89416"
REFERENCE_PLAN_SHA256 = "06e15c6b331deab04840dcd3dc1247cf394b5499daf5e9c60d1ba06fa95de641"
REFERENCE_RESULT_SCHEMA = "cofee-w1357-reference-generation-result/v1"
REFERENCE_PLAN_SCHEMA = "cofee-w1357-reference-generation-plan/v1"
REFERENCE_PIN_SCHEMA = "cofee-w1357-reference-pin/v1"
WORKLOAD_REFERENCE_PIN_SCHEMA = "cofee-w1357-workload-reference-pin/v1"
P2_SELECTOR = "p2:P2_PREP"
P2_ATTEMPT = "20260924-p2ref1"
P2_REFERENCE_ROOT = Path("/home/mchoi/w1357-reference-20260924-p2ref1")
GENERATOR = Path("/home/mchoi/cofee-evaluation/campaign/generate_w1357_references.py")
REFERENCE_BUILDER = Path("/home/mchoi/cofee-evaluation/campaign/w1357_reference.py")
REQUEST_SCHEMA = "cofee-w1357-output-comparison-request-v1"
SHA256 = re.compile(r"[0-9a-f]{64}")
ATTEMPT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


class RuntimeComparisonError(RuntimeError):
	"""The comparison could not be performed with trustworthy evidence."""


def _sha256_bytes(value: bytes) -> str:
	return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as stream:
		for block in iter(lambda: stream.read(1024 * 1024), b""):
			digest.update(block)
	return digest.hexdigest()


def _canonical(value: Any) -> bytes:
	return (json.dumps(value, sort_keys=True, separators=(",", ":"),
		allow_nan=False) + "\n").encode("utf-8")


def _safe_local_file(path: Path, label: str) -> bytes:
	fd = -1
	try:
		fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
		metadata = os.fstat(fd)
		if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 64 * 1024 * 1024:
			raise RuntimeComparisonError(f"{label} is not a bounded regular file")
		with os.fdopen(fd, "rb") as stream:
			fd = -1
			return stream.read()
	except OSError as exc:
		raise RuntimeComparisonError(f"cannot read {label}: {exc}") from exc
	finally:
		if fd >= 0:
			os.close(fd)


def _fetch_remote_reference(source_root: Path = REFERENCE_ROOT) -> tuple[bytes, bytes]:
	# Read both files in one same-host snapshot. O_NOFOLLOW and the fixed names
	# prevent a caller-controlled path from entering the reference authority.
	script = r'''import base64,json,os,pathlib,stat,sys
root=pathlib.Path(sys.argv[1])
if not root.is_absolute() or root.is_symlink() or not root.is_dir(): raise SystemExit("unsafe reference root")
out={}
for name in ("result-manifest.json","generation-plan.json"):
 p=root/name; fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)
 try:
  s=os.fstat(fd)
  if not stat.S_ISREG(s.st_mode) or s.st_size>64*1024*1024: raise SystemExit("unsafe reference file")
  chunks=[]
  while True:
   block=os.read(fd,1024*1024)
   if not block: break
   chunks.append(block)
  data=b"".join(chunks)
 finally: os.close(fd)
 out[name]=base64.b64encode(data).decode("ascii")
print(json.dumps(out,sort_keys=True,separators=(",",":")))'''
	command = ("ssh", "-o", "BatchMode=yes", "--", REFERENCE_HOST,
		shlex.join(("python3", "-c", script, str(source_root))))
	try:
		completed = subprocess.run(command, capture_output=True, check=False, timeout=120)
	except (OSError, subprocess.SubprocessError) as exc:
		raise RuntimeComparisonError(f"reference fetch failed: {exc}") from exc
	if completed.returncode:
		raise RuntimeComparisonError(
			"reference fetch failed: " + completed.stderr.decode(errors="replace")[-500:])
	try:
		payload = json.loads(completed.stdout)
		return (base64.b64decode(payload["result-manifest.json"], validate=True),
			base64.b64decode(payload["generation-plan.json"], validate=True))
	except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
		raise RuntimeComparisonError("reference fetch returned malformed evidence") from exc


def _reference_bytes(reference_manifest: Path | None) -> tuple[bytes, bytes]:
	if reference_manifest is None:
		return _fetch_remote_reference()
	manifest = Path(reference_manifest)
	return (_safe_local_file(manifest, "reference result manifest"),
		_safe_local_file(manifest.with_name("generation-plan.json"),
			"reference generation plan"))


def _load_reference(reference_manifest: Path | None
		) -> tuple[bytes, bytes, dict[str, Any], dict[str, Any]]:
	result_bytes, plan_bytes = _reference_bytes(reference_manifest)
	if _sha256_bytes(result_bytes) != REFERENCE_RESULT_SHA256:
		raise RuntimeComparisonError("cpref5 result manifest SHA-256 mismatch")
	if _sha256_bytes(plan_bytes) != REFERENCE_PLAN_SHA256:
		raise RuntimeComparisonError("cpref5 generation plan SHA-256 mismatch")
	try:
		result, plan = json.loads(result_bytes), json.loads(plan_bytes)
	except (UnicodeDecodeError, json.JSONDecodeError) as exc:
		raise RuntimeComparisonError("cpref5 reference JSON is invalid") from exc
	if not isinstance(result, dict) or result.get("schema") != REFERENCE_RESULT_SCHEMA:
		raise RuntimeComparisonError("cpref5 result manifest schema mismatch")
	if (not isinstance(plan, dict) or plan.get("schema") != REFERENCE_PLAN_SCHEMA
			or plan.get("host") != REFERENCE_HOST):
		raise RuntimeComparisonError("cpref5 generation plan identity mismatch")
	if Path(str(result.get("output_root", ""))) != REFERENCE_ROOT:
		raise RuntimeComparisonError("cpref5 output root mismatch")
	return result_bytes, plan_bytes, result, plan


def _reference_ready(manifest: dict[str, Any], cell: dict[str, Any]) -> tuple[bool, str]:
	rows = manifest.get("workloads")
	if not isinstance(rows, list):
		return False, "reference manifest has no workload rows"
	selected = [row for row in rows if isinstance(row, dict)
		and row.get("suite") == cell.get("suite")
		and row.get("workload") == cell.get("workload")]
	if len(selected) != 1:
		return False, "immutable reference workload is absent or duplicated"
	status = selected[0].get("status")
	if (not isinstance(status, dict) or status.get("returncode") != 0
			or status.get("executions") != 1):
		return False, "immutable reference workload did not complete exactly once"
	return True, "ready"


def _reference_source(pin: dict[str, Any] | None = None) -> dict[str, Any]:
	if pin is not None:
		return {
			"source_host": pin["source_host"],
			"source_root": pin["source_root"],
			"runtime_root": "/mnt",
			"result_manifest": {"path": pin["result_manifest"]["remote_path"],
				"sha256": pin["result_manifest"]["sha256"]},
			"generation_plan": {"path": pin["generation_plan"]["remote_path"],
				"sha256": pin["generation_plan"]["sha256"]},
		}
	return {
		"source_host": REFERENCE_HOST,
		"source_root": str(REFERENCE_ROOT),
		"runtime_root": "/mnt",
		"result_manifest": {
			"path": str(REFERENCE_ROOT / "result-manifest.json"),
			"sha256": REFERENCE_RESULT_SHA256,
		},
		"generation_plan": {
			"path": str(REFERENCE_ROOT / "generation-plan.json"),
			"sha256": REFERENCE_PLAN_SHA256,
		},
	}


def pin_reference(root: Path, reference_manifest: Path | None = None) -> dict[str, Any]:
	"""Freeze the exact remote cpref5 manifests into an append-only local pin.

	On resume the existing pin is verified byte-for-byte and returned.  A
	partial, rewritten, or differently sourced pin fails closed; this function
	never replaces reference evidence.
	"""
	root = Path(root)
	if not root.is_absolute():
		raise RuntimeComparisonError("reference pin root must be absolute")
	pin_root = root / "reference-cpref5"
	receipt_path = pin_root / "reference-pin.json"
	result_path = pin_root / "result-manifest.json"
	plan_path = pin_root / "generation-plan.json"
	if receipt_path.exists() or receipt_path.is_symlink():
		if receipt_path.is_symlink() or not receipt_path.is_file():
			raise RuntimeComparisonError("reference pin receipt is unsafe")
		try:
			receipt = json.loads(receipt_path.read_bytes())
		except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
			raise RuntimeComparisonError("reference pin receipt is invalid") from exc
		expected = {
			"schema": REFERENCE_PIN_SCHEMA,
			"source_host": REFERENCE_HOST,
			"source_root": str(REFERENCE_ROOT),
			"result_manifest": {"local_path": str(result_path),
				"remote_path": str(REFERENCE_ROOT / "result-manifest.json"),
				"sha256": REFERENCE_RESULT_SHA256},
			"generation_plan": {"local_path": str(plan_path),
				"remote_path": str(REFERENCE_ROOT / "generation-plan.json"),
				"sha256": REFERENCE_PLAN_SHA256},
		}
		if receipt != expected:
			raise RuntimeComparisonError("reference pin receipt identity mismatch")
		_load_reference(result_path)
		return receipt
	if pin_root.exists():
		raise RuntimeComparisonError("partial reference pin exists without receipt")
	result_bytes, plan_bytes, _, _ = _load_reference(reference_manifest)
	pin_root.mkdir(parents=True, exist_ok=False)
	try:
		with result_path.open("xb") as stream:
			stream.write(result_bytes)
		with plan_path.open("xb") as stream:
			stream.write(plan_bytes)
		receipt = {
			"schema": REFERENCE_PIN_SCHEMA,
			"source_host": REFERENCE_HOST,
			"source_root": str(REFERENCE_ROOT),
			"result_manifest": {"local_path": str(result_path),
				"remote_path": str(REFERENCE_ROOT / "result-manifest.json"),
				"sha256": REFERENCE_RESULT_SHA256},
			"generation_plan": {"local_path": str(plan_path),
				"remote_path": str(REFERENCE_ROOT / "generation-plan.json"),
				"sha256": REFERENCE_PLAN_SHA256},
		}
		with receipt_path.open("xb") as stream:
			stream.write(_canonical(receipt))
	except Exception:
		# Preserve partial evidence rather than deleting or overwriting it.  A
		# later resume will fail closed and require explicit operator diagnosis.
		raise
	return receipt


def _load_workload_pin(reference_manifest: Path) -> tuple[
		bytes, bytes, dict[str, Any], dict[str, Any], dict[str, Any]]:
	pin_path = reference_manifest.with_name("reference-pin.json")
	try:
		pin = json.loads(_safe_local_file(pin_path, "workload reference pin"))
	except (UnicodeDecodeError, json.JSONDecodeError) as exc:
		raise RuntimeComparisonError("workload reference pin is invalid") from exc
	if (not isinstance(pin, dict) or pin.get("schema") != WORKLOAD_REFERENCE_PIN_SCHEMA
			or pin.get("source_host") != REFERENCE_HOST
			or pin.get("workload_selector") != P2_SELECTOR
			or pin.get("source_root") != str(P2_REFERENCE_ROOT)):
		raise RuntimeComparisonError("workload reference pin identity mismatch")
	result_spec, plan_spec = pin.get("result_manifest"), pin.get("generation_plan")
	if not isinstance(result_spec, dict) or not isinstance(plan_spec, dict):
		raise RuntimeComparisonError("workload reference pin bindings missing")
	if result_spec.get("local_path") != str(reference_manifest):
		raise RuntimeComparisonError("workload reference local manifest path mismatch")
	plan_path = reference_manifest.with_name("generation-plan.json")
	if plan_spec.get("local_path") != str(plan_path):
		raise RuntimeComparisonError("workload reference local plan path mismatch")
	result_bytes = _safe_local_file(reference_manifest, "workload result manifest")
	plan_bytes = _safe_local_file(plan_path, "workload generation plan")
	if (_sha256_bytes(result_bytes) != result_spec.get("sha256")
			or _sha256_bytes(plan_bytes) != plan_spec.get("sha256")):
		raise RuntimeComparisonError("workload reference pin SHA-256 mismatch")
	try:
		result, plan = json.loads(result_bytes), json.loads(plan_bytes)
	except (UnicodeDecodeError, json.JSONDecodeError) as exc:
		raise RuntimeComparisonError("workload reference JSON is invalid") from exc
	_validate_p2_reference(result, plan, pin.get("provenance"))
	return result_bytes, plan_bytes, result, plan, pin


def _validate_p2_reference(result: Any, plan: Any, provenance: Any) -> None:
	if (not isinstance(result, dict) or result.get("schema") != REFERENCE_RESULT_SCHEMA
			or result.get("status") != "READY" or result.get("ready") is not True
			or result.get("errors") != [] or result.get("verification_gaps") != []
			or Path(str(result.get("output_root", ""))) != P2_REFERENCE_ROOT):
		raise RuntimeComparisonError("selective P2 result is not a verified READY reference")
	if (not isinstance(plan, dict) or plan.get("schema") != REFERENCE_PLAN_SCHEMA
			or plan.get("host") != REFERENCE_HOST or plan.get("attempt") != P2_ATTEMPT
			or plan.get("workload_selector") != P2_SELECTOR
			or Path(str(plan.get("output_root", ""))) != P2_REFERENCE_ROOT
			or plan.get("planner") != "none" or plan.get("execution_mode") != "singlenode"):
		raise RuntimeComparisonError("selective P2 generation plan identity mismatch")
	identity = {
		"stage_seal_sha256": "ecf77f4ef2362bfbcf9ca41cbc40d2844493d6b128b08feb4df90e711c095838",
		"jar_sha256": "1dc2482d37d4d7b06e50bcd21f54a2c136b7a023108b8c375de9fa6644b992f2",
		"correctness_contract_sha256": "79bb0bf4e0a2a2f63fd71fd176c7cc40b3e6c2ae7031dccba6e7ef84aaf10149",
		"decoder_source_sha256": "ad0616ab9e3ba792efa205f8e76f567ef05ea1480bf7dc591fb7d21c4409951b",
	}
	if any(plan.get(key) != value for key, value in identity.items()):
		raise RuntimeComparisonError("selective P2 frozen identity mismatch")
	rows, contracts = result.get("workloads"), plan.get("contracts")
	if not isinstance(rows, list) or len(rows) != 1 or not isinstance(contracts, list) or len(contracts) != 1:
		raise RuntimeComparisonError("selective P2 reference is not singleton")
	row, contract = rows[0], contracts[0]
	if ((row.get("suite"), row.get("workload")) != ("p2", "P2_PREP")
			or (contract.get("suite"), contract.get("workload")) != ("p2", "P2_PREP")):
		raise RuntimeComparisonError("selective P2 workload identity mismatch")
	status = row.get("status")
	if (not isinstance(status, dict) or status.get("returncode") != 0
			or status.get("executions") != 1
			or status.get("dmlscript_fatal_marker") is not False):
		raise RuntimeComparisonError("selective P2 workload execution is not clean")
	expected = {item.get("role"): item.get("data_type")
		for item in contract.get("outputs", []) if isinstance(item, dict)}
	outputs = row.get("outputs")
	if expected != {"result": "matrix", "transform_metadata": "frame"} or not isinstance(outputs, list):
		raise RuntimeComparisonError("selective P2 typed output contract mismatch")
	seen = {}
	for output in outputs:
		if not isinstance(output, dict) or output.get("role") in seen:
			raise RuntimeComparisonError("selective P2 output record malformed")
		role = output.get("role")
		decoded = output.get("decoded")
		if (role not in expected or output.get("data_type") != expected[role]
				or SHA256.fullmatch(str(output.get("metadata_sha256"))) is None
				or not isinstance(decoded, dict)
				or SHA256.fullmatch(str(decoded.get("sha256"))) is None
				or not isinstance(output.get("tree"), dict)
				or SHA256.fullmatch(str(output["tree"].get("tree_sha256"))) is None):
			raise RuntimeComparisonError("selective P2 typed output evidence is incomplete")
		seen[role] = output
	if set(seen) != set(expected):
		raise RuntimeComparisonError("selective P2 typed output set is incomplete")
	result_provenance = result.get("provenance")
	if not isinstance(result_provenance, dict):
		raise RuntimeComparisonError("selective P2 result provenance missing")
	for key in (*identity, "planner", "execution_mode", "source_sha256"):
		if result_provenance.get(key) != plan.get(key):
			raise RuntimeComparisonError(f"selective P2 result/plan provenance mismatch: {key}")
	if not isinstance(provenance, dict) or provenance.get("generator_sha256") != plan.get(
		"source_sha256", {}).get("generator") or provenance.get(
			"reference_builder_sha256") != plan.get("source_sha256", {}).get("reference_builder"):
		raise RuntimeComparisonError("selective P2 pinned source provenance mismatch")


def prepare_workload_reference(root: Path, stage: Path, cell: dict[str, Any],
		compile_gate_passed: bool) -> dict[str, Any]:
	"""Generate and pin the frozen P2 repair only after the global compile gate."""
	if compile_gate_passed is not True:
		raise RuntimeComparisonError("global 896-cell compile gate has not passed")
	if (cell.get("suite"), cell.get("workload")) != ("p2", "P2_PREP"):
		raise RuntimeComparisonError("selective reference repair is P2_PREP-only")
	root, stage = Path(root), Path(stage)
	if not root.is_absolute() or not stage.is_absolute():
		raise RuntimeComparisonError("reference pin root and stage must be absolute")
	pin_root = root / "reference-p2ref1"
	result_path = pin_root / "result-manifest.json"
	plan_path = pin_root / "generation-plan.json"
	pin_path = pin_root / "reference-pin.json"
	if pin_path.exists() or pin_path.is_symlink():
		_, _, _, _, pin = _load_workload_pin(result_path)
		return pin
	if pin_root.exists():
		raise RuntimeComparisonError("partial selective P2 reference pin exists")
	command = ["python3", str(GENERATOR), "--mode", "run", "--stage-root", str(stage),
		"--output-root", str(P2_REFERENCE_ROOT), "--attempt", P2_ATTEMPT,
		"--workload", P2_SELECTOR]
	try:
		completed = subprocess.run(command, text=True, capture_output=True,
			check=False, timeout=14400)
	except (OSError, subprocess.SubprocessError) as exc:
		raise RuntimeComparisonError(f"selective P2 reference generation failed: {exc}") from exc
	if completed.returncode:
		raise RuntimeComparisonError(
			"selective P2 reference generation failed: " + completed.stderr[-1000:])
	try:
		generated = json.loads(completed.stdout)
	except json.JSONDecodeError as exc:
		raise RuntimeComparisonError("selective P2 generator returned invalid JSON") from exc
	if generated.get("status") != "READY" or generated.get("ready") is not True:
		raise RuntimeComparisonError("selective P2 generator did not publish READY")
	result_bytes, plan_bytes = _fetch_remote_reference(P2_REFERENCE_ROOT)
	try:
		result, plan = json.loads(result_bytes), json.loads(plan_bytes)
	except (UnicodeDecodeError, json.JSONDecodeError) as exc:
		raise RuntimeComparisonError("generated P2 reference JSON is invalid") from exc
	provenance = {"generator_sha256": _sha256_file(GENERATOR),
		"reference_builder_sha256": _sha256_file(REFERENCE_BUILDER),
		"command": command, "generator_receipt": generated}
	_validate_p2_reference(result, plan, provenance)
	pin_root.mkdir(parents=True, exist_ok=False)
	with result_path.open("xb") as stream:
		stream.write(result_bytes)
	with plan_path.open("xb") as stream:
		stream.write(plan_bytes)
	pin = {
		"schema": WORKLOAD_REFERENCE_PIN_SCHEMA, "source_host": REFERENCE_HOST,
		"source_root": str(P2_REFERENCE_ROOT), "workload_selector": P2_SELECTOR,
		"result_manifest": {"local_path": str(result_path),
			"remote_path": str(P2_REFERENCE_ROOT / "result-manifest.json"),
			"sha256": _sha256_bytes(result_bytes)},
		"generation_plan": {"local_path": str(plan_path),
			"remote_path": str(P2_REFERENCE_ROOT / "generation-plan.json"),
			"sha256": _sha256_bytes(plan_bytes)},
		"provenance": provenance,
	}
	with pin_path.open("xb") as stream:
		stream.write(_canonical(pin))
	return pin


def _validate_actual_bindings(receipt: dict[str, Any], request: dict[str, Any]) -> bool:
	if receipt.get("passed") is not True:
		return False
	expected = set(request.get("actual_artifacts", {}))
	actual = receipt.get("metrics", {}).get("artifact_bindings", {}).get("actual")
	if not expected or not isinstance(actual, dict) or set(actual) != expected:
		raise RuntimeComparisonError("trusted comparator actual artifact binding set mismatch")
	for role, binding in actual.items():
		if not isinstance(binding, dict):
			raise RuntimeComparisonError(f"trusted comparator binding is malformed: {role}")
		for key in ("tree_sha256", "decoded_sha256", "metadata_sha256", "campaign_sha256"):
			if SHA256.fullmatch(str(binding.get(key))) is None:
				raise RuntimeComparisonError(
					f"trusted comparator binding lacks {key}: {role}")
	return True


def _persist_receipt(path: Path, receipt: dict[str, Any]) -> str:
	path.parent.mkdir(parents=True, exist_ok=True)
	if path.exists() or path.is_symlink():
		raise RuntimeComparisonError("comparison receipt already exists or is unsafe")
	with path.open("xb") as stream:
		stream.write(_canonical(receipt))
	return _sha256_file(path)


def compare(campaign: Any, cell: dict[str, Any], attempt: str, stage: Path,
		remote_run_root: Path, local_receipt: Path,
		reference_manifest: Path | None, image: str) -> dict[str, Any]:
	"""Compare one already-produced runtime output against immutable cpref5.

	The returned mapping always includes the comparator ``receipt`` (and thus
	its ``passed`` boolean) plus ownership-checked container cleanup evidence.
	A missing P2 reference is recorded as blocked; it is never synthesized from
	the overall failed cpref5 manifest.
	"""
	if ATTEMPT.fullmatch(str(attempt)) is None:
		raise RuntimeComparisonError("unsafe comparison attempt identity")
	stage, remote_run_root, local_receipt = map(
		Path, (stage, remote_run_root, local_receipt))
	if not stage.is_absolute() or not remote_run_root.is_absolute():
		raise RuntimeComparisonError("stage and remote run root must be absolute")
	if local_receipt.exists() or local_receipt.is_symlink():
		raise RuntimeComparisonError("comparison receipt already exists or is unsafe")

	workload_pin = None
	if reference_manifest is not None:
		candidate_pin = Path(reference_manifest).with_name("reference-pin.json")
		if candidate_pin.is_file() and not candidate_pin.is_symlink():
			try:
				candidate_schema = json.loads(candidate_pin.read_bytes()).get("schema")
			except (UnicodeDecodeError, json.JSONDecodeError, AttributeError) as exc:
				raise RuntimeComparisonError("reference pin receipt is invalid") from exc
			if candidate_schema == WORKLOAD_REFERENCE_PIN_SCHEMA:
				result_bytes, plan_bytes, manifest, _, workload_pin = _load_workload_pin(
					Path(reference_manifest))
			else:
				result_bytes, plan_bytes, manifest, _ = _load_reference(reference_manifest)
		else:
			result_bytes, plan_bytes, manifest, _ = _load_reference(reference_manifest)
	else:
		result_bytes, plan_bytes, manifest, _ = _load_reference(None)
	source = _reference_source(workload_pin)
	result_sha = source["result_manifest"]["sha256"]
	plan_sha = source["generation_plan"]["sha256"]
	local_receipt.parent.mkdir(parents=True, exist_ok=True)
	with tempfile.TemporaryDirectory(prefix="w1357-cpref5-", dir=local_receipt.parent) as temporary:
		cache = Path(temporary)
		cached_result = cache / "result-manifest.json"
		cached_plan = cache / "generation-plan.json"
		cached_result.write_bytes(result_bytes)
		cached_plan.write_bytes(plan_bytes)
		plan = campaign.comparator_container_plan(
			cell, attempt, stage, remote_run_root, cached_result, image)
		request = dict(plan.get("request", {}))
		request.pop("reference_manifest_path", None)
		request.pop("reference_manifest_sha256", None)
		request.update({
			"schema": REQUEST_SCHEMA,
			"scratch_attempt": attempt,
			"reference_source": source,
		})
		plan["request"] = request
		plan["reference_manifest_sha256"] = result_sha
		plan["reference_generation_plan_sha256"] = plan_sha

		ready, reason = _reference_ready(manifest, cell)
		if not ready:
			cleanup = campaign._cleanup_comparator_container(plan, None)
			if cleanup.get("resolved") is not True:
				raise RuntimeComparisonError(
					"blocked comparison container absence was not proven")
			receipt = {
				"schema": "cofee-w1357-output-comparison-blocked-v1",
				"suite": cell.get("suite"), "workload": cell.get("workload"),
				"status": "blocked", "correctness": "unverified", "passed": False,
				"reason": reason, "reference_source": source,
			}
			digest = _persist_receipt(local_receipt, receipt)
			return {"receipt": receipt, "passed": False, "path": str(local_receipt),
				"sha256": digest, "cleanup": cleanup,
				"raw_output_hashes_verified": False,
				"reference": source}

		publication = campaign._publish_comparator_inputs(plan)
		container_id = None
		outcome: dict[str, Any] | None = None
		primary_error: Exception | None = None
		try:
			create = ("ssh", "-o", "BatchMode=yes", "--", plan["host"],
				shlex.join(plan["create_argv"]))
			created = subprocess.run(create, text=True, capture_output=True,
				check=False, timeout=60)
			container_id = created.stdout.strip()
			if created.returncode or re.fullmatch(r"[0-9a-f]{64}", container_id) is None:
				raise RuntimeComparisonError("comparator container creation failed")
			start = ("ssh", "-o", "BatchMode=yes", "--", plan["host"],
				shlex.join(("docker", "container", "start", "-a", "--", container_id)))
			completed = subprocess.run(start, text=True, capture_output=True,
				check=False, timeout=3900)
			if completed.returncode not in (0, 2):
				raise RuntimeComparisonError("comparator container transport failed")
			try:
				receipt = json.loads(completed.stdout)
			except json.JSONDecodeError as exc:
				raise RuntimeComparisonError("comparator returned invalid JSON") from exc
			if not isinstance(receipt, dict) or not isinstance(receipt.get("passed"), bool):
				raise RuntimeComparisonError("comparator receipt lacks passed boolean")
			hashes_verified = _validate_actual_bindings(receipt, request)
			receipt.update({
				"cell_id": cell.get("id"),
				"reference_set_manifest_sha256": result_sha,
				"reference_generation_plan_sha256": plan_sha,
			})
			digest = _persist_receipt(local_receipt, receipt)
			outcome = {"receipt": receipt, "passed": receipt["passed"],
				"path": str(local_receipt), "sha256": digest,
				"publication": publication,
				"raw_output_hashes_verified": hashes_verified,
				"reference": source}
		except Exception as exc:  # cleanup must run for every post-create failure
			primary_error = exc
		finally:
			cleanup = campaign._cleanup_comparator_container(plan, container_id)
		if cleanup.get("resolved") is not True:
			detail = "comparator container cleanup absence proof failed"
			if primary_error is not None:
				raise RuntimeComparisonError(f"{primary_error}; {detail}") from primary_error
			raise RuntimeComparisonError(detail)
		if primary_error is not None:
			raise primary_error
		assert outcome is not None
		outcome["cleanup"] = cleanup
		return outcome
