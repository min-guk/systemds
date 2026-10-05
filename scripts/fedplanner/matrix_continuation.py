#!/usr/bin/env python3
"""Immutable, fail-closed continuation snapshots for runtime campaigns."""
from __future__ import annotations

import ast
import copy
import hashlib
import json
import os
from pathlib import Path
from typing import Callable


SCHEMA = "w1357-matrix-continuation/v1"
CONTRACT_NAMES = ("IMAGE", "PLANNERS", "PROFILES", "WORKERS", "WORKLOAD_TIMEOUT_SECONDS",
	"WORKLOADS", "ENUMS", "PROBE", "JAVA", "CP")
CONTRACT_FUNCTIONS = ("config", "coordinator_java")
MEASUREMENT_PARALLEL_KEYS = {"parallel_setup_only", "parallel_untimed_only"}
MAX_CHAIN_DEPTH = 8


def _canonical(value) -> bytes:
	return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def _sha(path: Path) -> str:
	digest = hashlib.sha256()
	with Path(path).open("rb") as stream:
		for block in iter(lambda: stream.read(1024 * 1024), b""):
			digest.update(block)
	return digest.hexdigest()


def _read_json(path: Path):
	try:
		return json.loads(path.read_text())
	except (OSError, json.JSONDecodeError) as error:
		raise ValueError(f"invalid continuation evidence {path}: {error}") from error


def _process_start_ticks(pid: int) -> str | None:
	try:
		# Field 22 follows a parenthesized comm which may itself contain spaces.
		return Path(f"/proc/{int(pid)}/stat").read_text().rsplit(")", 1)[1].split()[19]
	except (OSError, ValueError, IndexError):
		return None


def _assignment_name(node: ast.AST) -> str | None:
	if isinstance(node, (ast.Assign, ast.AnnAssign)):
		targets = node.targets if isinstance(node, ast.Assign) else [node.target]
		if len(targets) == 1 and isinstance(targets[0], ast.Name):
			return targets[0].id
	return None


def _runner_contract(path: Path) -> dict[str, str]:
	tree = ast.parse(path.read_text(), filename=str(path))
	result = {}
	for node in tree.body:
		name = _assignment_name(node)
		if name in CONTRACT_NAMES:
			result[name] = ast.dump(node, include_attributes=False)
		elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in CONTRACT_FUNCTIONS:
			result[node.name] = ast.dump(node, include_attributes=False)
	missing = set(CONTRACT_NAMES + CONTRACT_FUNCTIONS) - result.keys()
	if missing:
		raise ValueError(f"timed runner contract missing symbols: {sorted(missing)}")
	return result


def _runner_contract_compatible(old_path: Path, new_path: Path) -> bool:
	old, new = _runner_contract(old_path), _runner_contract(new_path)
	old_timeout = old.pop("WORKLOAD_TIMEOUT_SECONDS")
	new_timeout = new.pop("WORKLOAD_TIMEOUT_SECONDS")
	old.pop("config")
	new.pop("config")
	return old == new and _config_contract_compatible(old_path, new_path) and (old_timeout == new_timeout
		or ("Constant(value=60)" in old_timeout and "Constant(value=None)" in new_timeout))


def _config_contract_compatible(old_path: Path, new_path: Path) -> bool:
	old_function = _function(ast.parse(old_path.read_text()), "config")
	new_function = _function(ast.parse(new_path.read_text()), "config")
	if ast.dump(old_function, include_attributes=False) == ast.dump(new_function, include_attributes=False):
		return True

	def values_dict(function: ast.FunctionDef) -> ast.Dict | None:
		for node in function.body:
			if _assignment_name(node) == "values" and isinstance(node, ast.Assign) \
					and isinstance(node.value, ast.Dict):
				return node.value
		return None

	old_values, new_values = values_dict(old_function), values_dict(new_function)
	if old_values is None or new_values is None:
		return False
	old_keys = [key.value for key in old_values.keys if isinstance(key, ast.Constant)]
	new_keys = [key.value for key in new_values.keys if isinstance(key, ast.Constant)]
	if "sysds.federated.timeout" in old_keys or new_keys.count("sysds.federated.timeout") != 1:
		return False
	index = new_keys.index("sysds.federated.timeout")
	value = new_values.values[index]
	if not isinstance(value, ast.Constant) or value.value != "-1":
		return False
	del new_values.keys[index]
	del new_values.values[index]
	return ast.dump(old_function, include_attributes=False) == ast.dump(new_function, include_attributes=False)


def _function(tree: ast.Module, name: str) -> ast.FunctionDef:
	for node in tree.body:
		if isinstance(node, ast.FunctionDef) and node.name == name:
			return node
	raise ValueError(f"integrated runner missing {name}")


def _reviewed_integrated_contract(old_path: Path, new_path: Path) -> None:
	old_tree = ast.parse(old_path.read_text(), filename=str(old_path))
	new_tree = ast.parse(new_path.read_text(), filename=str(new_path))
	old_other = [node for node in old_tree.body
		if not isinstance(node, ast.FunctionDef) or node.name != "comparator_container_plan"]
	new_other = [node for node in new_tree.body
		if not isinstance(node, ast.FunctionDef) or node.name != "comparator_container_plan"]
	if ast.dump(ast.Module(old_other, []), include_attributes=False) != \
			ast.dump(ast.Module(new_other, []), include_attributes=False):
		raise ValueError("unreviewed integrated runner change outside comparator_container_plan")

	def split(function):
		metric = next((i for i, node in enumerate(function.body)
			if _assignment_name(node) == "metric_root"), None)
		create = next((i for i, node in enumerate(function.body)
			if _assignment_name(node) == "create"), None)
		if metric is None or create is None or create <= metric:
			raise ValueError("reviewed decoder-smoke boundaries missing")
		create_node = copy.deepcopy(function.body[create])
		if not isinstance(create_node, ast.Assign) or not isinstance(create_node.value, ast.List) \
				or len(create_node.value.elts) < 2:
			raise ValueError("reviewed comparator create command missing")
		if not isinstance(create_node.value.elts[-1], ast.Name):
			raise ValueError("reviewed comparator command must be a precomputed local value")
		# Only the shell command expression (the final list element) may consume the
		# reviewed decoder-smoke policy. Mounts, image, and container limits are fixed.
		create_node.value.elts[-1] = ast.Constant(value="<reviewed-command>")
		return (function.body[:metric + 1], create_node, function.body[create + 1:])

	old_prefix, old_create, old_suffix = split(_function(old_tree, "comparator_container_plan"))
	new_prefix, new_create, new_suffix = split(_function(new_tree, "comparator_container_plan"))
	for label, old, new in (("prefix", old_prefix, new_prefix), ("create", old_create, new_create),
			("suffix", old_suffix, new_suffix)):
		if ast.dump(ast.Module(old if isinstance(old, list) else [old], []), include_attributes=False) != \
				ast.dump(ast.Module(new if isinstance(new, list) else [new], []), include_attributes=False):
			raise ValueError(f"unreviewed integrated runner comparator {label} change")
	# The variable command slice is permitted to construct only values consumed by
	# the final create command; assignments and conditionals are accepted, calls
	# that introduce side effects before container execution are not.
	for node in _function(new_tree, "comparator_container_plan").body[len(new_prefix):]:
		if _assignment_name(node) == "create":
			break
		for child in ast.walk(node):
			if isinstance(child, (ast.Import, ast.ImportFrom, ast.With, ast.Try, ast.Raise,
					ast.Delete, ast.Global, ast.Nonlocal, ast.Await, ast.Yield, ast.YieldFrom)):
				raise ValueError("reviewed decoder-smoke slice contains side-effecting control flow")
			if isinstance(child, ast.Call):
				allowed_name = isinstance(child.func, ast.Name) and child.func.id in {
					"all", "any", "dict", "isinstance", "len", "set", "sorted", "str", "tuple"}
				allowed_get = isinstance(child.func, ast.Attribute) and child.func.attr == "get"
				if not (allowed_name or allowed_get):
					raise ValueError("reviewed decoder-smoke slice contains an unreviewed call")


def _integrated_entry(identity: dict) -> tuple[str, str]:
	entries = [(path, digest) for path, digest in identity.get("external_sha256", {}).items()
		if Path(path).name == "run_w1357_integrated.py"]
	if len(entries) != 1:
		raise ValueError("identity must pin exactly one integrated runner")
	return entries[0]


def _manifest_contract_sha(manifest: dict) -> str:
	value = copy.deepcopy(manifest)
	value.get("identity", {}).pop("continuation_sha256", None)
	return hashlib.sha256(_canonical(value)).hexdigest()


def _identity_contract(source: dict, current: dict, old_integrated: str, new_integrated: str) -> None:
	old = copy.deepcopy(source)
	new = copy.deepcopy(current)
	old_timeouts, new_timeouts = old.get("timeout_seconds"), new.get("timeout_seconds")
	if not _timeout_transition(old_timeouts, new_timeouts):
		raise ValueError("continuation timeout identity change is not the reviewed 60-to-none migration")
	for value in (old, new):
		value.pop("runner_sha256", None)
		value.pop("continuation_sha256", None)
		value.pop("continuation_module_sha256", None)
		value["timeout_seconds"] = "<reviewed-timeout-policy>"
	old_external = old.get("external_sha256", {})
	new_external = new.get("external_sha256", {})
	old_external.pop(old_integrated, None)
	new_external.pop(new_integrated, None)
	if old != new:
		raise ValueError("continuation identity changed outside reviewed harness hashes")


def _measurement_contract(source: dict, current: dict) -> None:
	if not _timeout_transition(source.get("timeout_seconds"), current.get("timeout_seconds")):
		raise ValueError("continuation measurement timeout change is not reviewed")
	old = {k: v for k, v in source.items()
		if k not in MEASUREMENT_PARALLEL_KEYS | {"timeout_seconds", "timeout_semantics"}}
	new = {k: v for k, v in current.items()
		if k not in MEASUREMENT_PARALLEL_KEYS | {"timeout_seconds", "timeout_semantics"}}
	old_semantics, new_semantics = source.get("timeout_semantics"), current.get("timeout_semantics")
	semantics_valid = (old_semantics == new_semantics or
		(old_semantics == "unresolved failure, not infeasibility"
		and new_semantics == "no workload deadline"))
	parallel_valid = ((source.get("parallel_setup_only") is True
		and source.get("parallel_untimed_only") is None
		and current.get("parallel_setup_only") is False
		and current.get("parallel_untimed_only") is True)
		or (source.get("parallel_setup_only") is False
			and source.get("parallel_untimed_only") is True
			and current.get("parallel_setup_only") is False
			and current.get("parallel_untimed_only") is True))
	if old != new or not parallel_valid or not semantics_valid:
		raise ValueError("continuation measurement changed outside reviewed untimed parallel policy")


def _timeout_transition(old: object, new: object) -> bool:
	return old == new or (old == {"compile": 60, "runtime": 60}
		and new == {"compile": None, "runtime": None})


def _terminal_proof(source_root: Path) -> dict:
	finished = source_root / "finished-at.txt"
	exit_code = source_root / "exit-code.txt"
	if not finished.is_file() or not finished.read_text().strip() or not exit_code.is_file():
		raise ValueError("source campaign terminal wrapper proof is missing")
	try:
		code = int(exit_code.read_text().strip())
	except ValueError as error:
		raise ValueError("source campaign terminal exit code is invalid") from error
	launch = _read_json(source_root / "launch.json")
	pid = launch.get("pid")
	start = str(launch.get("process_start_ticks"))
	if isinstance(pid, int) and _process_start_ticks(pid) == start:
		raise ValueError("source campaign wrapper is still alive")
	releases = sorted(source_root.glob("lease-release-*.json"))
	if not releases or _read_json(releases[-1]).get("released") is not True:
		raise ValueError("source campaign latest lease release is not proven")
	return {"finished_at": finished.read_text().strip(), "exit_code": code,
		"launch_sha256": _sha(source_root / "launch.json"),
		"lease_release_path": str(releases[-1].relative_to(source_root)),
		"lease_release_sha256": _sha(releases[-1])}


def _resolved_cleanup(path: Path) -> bool:
	return path.is_file() and _read_json(path).get("resolved") is True


def _artifact(source_root: Path, path: Path) -> dict:
	return {"path": str(path.relative_to(source_root)), "sha256": _sha(path)}


def _prestart_resource_exclusion(source_root: Path, path: Path, row: dict,
		result_path: Path) -> dict | None:
	"""Recognize only a proven, untimed resource-preflight failure."""
	preflight_path = path / "resource-preflight.json"
	lifecycle_path = path / "lifecycle.json"
	post_cleanup_path = path / "post-stop-cleanup.json"
	forbidden_files = ("cleanup.json", "command.json", "receipt.json", "comparison.json")
	forbidden_fields = ("cleanup_resolved", "process_seconds", "returncode", "receipt", "comparison")
	shape_matches = (row.get("status") == "failed"
		and row.get("errors") == ["host resource preflight failed"]
		and all(field not in row for field in forbidden_fields)
		and all(not (path / name).exists() for name in forbidden_files)
		and preflight_path.is_file() and lifecycle_path.is_file())
	if not shape_matches:
		return None
	preflight = _read_json(preflight_path)
	lifecycle = _read_json(lifecycle_path)
	failed_hosts = [host for host, evidence in preflight.get("hosts", {}).items()
		if isinstance(evidence, dict) and evidence.get("passed") is False]
	if preflight.get("passed") is not False or not failed_hosts or not isinstance(lifecycle, dict):
		return None
	if not _resolved_cleanup(post_cleanup_path):
		raise ValueError(f"pre-start resource failure post-stop cleanup unresolved: {path}")
	return {"path": str(path.relative_to(source_root)),
		"reason": "pre-start resource failure; post-stop cleanup resolved",
		"failed_preflight_hosts": sorted(failed_hosts),
		"artifacts": [_artifact(source_root, artifact) for artifact in
			(result_path, preflight_path, lifecycle_path, post_cleanup_path)]}


def _validate_attempt(source_root: Path, path: Path, manifest: dict,
		validate_probe_receipt: Callable) -> tuple[dict | None, dict | None]:
	result_path = path / "result.json"
	cleanup_path = path / "cleanup.json"
	if not result_path.is_file():
		if _resolved_cleanup(cleanup_path):
			return None, {"path": str(path.relative_to(source_root)),
				"reason": "terminal interrupted attempt without result; cleanup resolved",
				"artifacts": [_artifact(source_root, cleanup_path)]}
		raise ValueError(f"unexplained result-less attempt or unresolved cleanup: {path}")
	row = _read_json(result_path)
	if row.get("diagnostic_only") is True or row.get("diagnostic_runtime_cell") is True:
		raise ValueError(f"diagnostic attempt cannot be imported by continuation: {path}")
	cells = {cell["id"]: cell for cell in manifest["cells"]}
	cell = row.get("cell")
	if not isinstance(cell, dict) or cell.get("id") not in cells or cell != cells[cell["id"]]:
		raise ValueError(f"attempt has noncanonical cell: {path}")
	expected_timeout = manifest.get("identity", {}).get("timeout_seconds", {}).get("runtime")
	if row.get("phase") != "runtime" or "timeout_seconds" not in row \
			or row.get("timeout_seconds") != expected_timeout \
			or row.get("jar_sha256") != manifest["identity"].get("jar_sha256"):
		raise ValueError(f"attempt runtime/timeout/JAR contract mismatch: {path}")
	prestart_exclusion = _prestart_resource_exclusion(source_root, path, row, result_path)
	if prestart_exclusion is not None:
		return None, prestart_exclusion
	if row.get("cleanup_resolved") is not True or not _resolved_cleanup(cleanup_path):
		raise ValueError(f"attempt cleanup unresolved: {path}")
	artifacts = [_artifact(source_root, result_path), _artifact(source_root, cleanup_path)]
	status = row.get("status")
	if row.get("receipt") is not None:
		receipt_path = path / "receipt.json"
		if not receipt_path.is_file() or _read_json(receipt_path) != row.get("receipt"):
			raise ValueError(f"attempt receipt bytes/row disagree: {path}")
		artifacts.append(_artifact(source_root, receipt_path))
	if row.get("comparison") is not None:
		comparison_path = path / "comparison.json"
		comparison = row.get("comparison")
		if not isinstance(comparison, dict) or not comparison_path.is_file() \
				or _read_json(comparison_path) != comparison.get("receipt") \
				or _sha(comparison_path) != comparison.get("sha256"):
			raise ValueError(f"attempt comparison bytes/row disagree: {path}")
		artifacts.append(_artifact(source_root, comparison_path))
	if status == "passed":
		if row.get("errors") != []:
			raise ValueError(f"passed attempt contains errors: {path}")
		comparison = row.get("comparison")
		if not isinstance(comparison, dict) \
				or comparison.get("passed") is not True \
				or comparison.get("raw_output_hashes_verified") is not True \
				or comparison.get("receipt", {}).get("passed") is not True \
				or comparison.get("cleanup", {}).get("resolved") is not True:
			raise ValueError(f"passed attempt lacks trusted comparison evidence: {path}")
		validate_probe_receipt(row["receipt"], cell, "runtime", {
			"candidate_phases_ns": row.get("candidate_phases_ns"),
			"compile_seconds": row.get("compile_seconds")})
	elif status == "failed":
		if not row.get("errors"):
			if row.get("receipt") is None or row.get("receipt", {}).get("workloadExecutionCompleted") is not True:
				return None, {"path": str(path.relative_to(source_root)),
					"reason": "terminal interrupted failed row without completed JVM evidence",
					"artifacts": artifacts}
			raise ValueError(f"failed attempt lacks error evidence: {path}")
	else:
		raise ValueError(f"attempt has invalid status: {path}")
	return {"row": row, "artifacts": artifacts,
		"source_path": str(result_path.relative_to(source_root))}, None


def build_snapshot(source_root: Path, current_manifest: dict, current_runner: Path,
		validate_probe_receipt: Callable) -> dict:
	"""Validate a stopped campaign and return a canonical immutable snapshot value."""
	source_root, current_runner = Path(source_root).resolve(), Path(current_runner).resolve()
	terminal = _terminal_proof(source_root)
	module_sha = current_manifest.get("identity", {}).get("continuation_module_sha256")
	if module_sha != _sha(Path(__file__)):
		raise ValueError("current manifest does not pin this continuation module")
	source_manifest_path = source_root / "manifest.json"
	source_manifest = _read_json(source_manifest_path)
	for label, manifest in (("source", source_manifest), ("target", current_manifest)):
		diagnostic = manifest.get("identity", {}).get("diagnostic", {})
		measurement = manifest.get("measurement", {})
		if diagnostic.get("diagnostic_runtime_cell") is True \
				or measurement.get("diagnostic_runtime_cell") is True:
			raise ValueError(f"diagnostic runtime campaign cannot be continuation {label}")
	old_runner = source_root / "frozen-harness/run_matrix_campaign.py"
	if _sha(old_runner) != source_manifest.get("identity", {}).get("runner_sha256"):
		raise ValueError("source frozen runner does not match its manifest")
	if _sha(current_runner) != current_manifest.get("identity", {}).get("runner_sha256"):
		raise ValueError("current runner does not match current manifest")
	if not _runner_contract_compatible(old_runner, current_runner):
		raise ValueError("timed runner contract changed")
	source_module_sha = source_manifest.get("identity", {}).get("continuation_module_sha256")
	if source_manifest.get("identity", {}).get("continuation_sha256"):
		source_module = source_root / "frozen-harness/matrix_continuation.py"
		if not source_module_sha or not source_module.is_file() or _sha(source_module) != source_module_sha:
			raise ValueError("chained source continuation module pin mismatch")
		# Recursively validates the source snapshot, all transitive artifacts, and
		# acyclicity before any inherited row is flattened into the new snapshot.
		inherited_rows = load_rows(source_root, source_manifest)
		source_snapshot = _read_json(source_root / "continuation.json")
		inherited_items = {item["cell_id"]: copy.deepcopy(item)
			for item in source_snapshot.get("rows", [])}
		for cell_id, row in inherited_rows.items():
			item = inherited_items.get(cell_id)
			if item is None:
				raise ValueError("source continuation rows and snapshot disagree")
			item["row"] = copy.deepcopy(row)
			item.setdefault("artifact_root", source_snapshot["source_root"])
	else:
		inherited_items = {}
	old_integrated, old_integrated_sha = _integrated_entry(source_manifest["identity"])
	new_integrated, new_integrated_sha = _integrated_entry(current_manifest["identity"])
	old_integrated_path = source_root / "frozen-harness/run_w1357_integrated.py"
	new_integrated_path = Path(new_integrated)
	if _sha(old_integrated_path) != old_integrated_sha or _sha(new_integrated_path) != new_integrated_sha:
		raise ValueError("integrated runner pin mismatch")
	_reviewed_integrated_contract(old_integrated_path, new_integrated_path)
	_identity_contract(source_manifest["identity"], current_manifest["identity"],
		old_integrated, new_integrated)
	_measurement_contract(source_manifest.get("measurement", {}), current_manifest.get("measurement", {}))
	if source_manifest.get("image") != current_manifest.get("image") \
			or source_manifest.get("cells") != current_manifest.get("cells"):
		raise ValueError("continuation image or canonical cells changed")

	attempts = sorted(path for path in (source_root / "attempts/runtime").glob("*") if path.is_dir())
	validated, excluded = [], []
	for attempt in attempts:
		row, exclusion = _validate_attempt(source_root, attempt, source_manifest, validate_probe_receipt)
		if row:
			validated.append(row)
		if exclusion:
			excluded.append(exclusion)
	if len(excluded) > 1 or (excluded and excluded[0]["path"] != str(attempts[-1].relative_to(source_root))):
		raise ValueError("result-less/interrupted attempt is not the sole latest attempt")
	latest = dict(inherited_items)
	for item in validated:
		latest[item["row"]["cell"]["id"]] = item
	rows = []
	for cell_id in sorted(latest):
		item = latest[cell_id]
		rows.append({"cell_id": cell_id, "row": item["row"], "artifacts": item["artifacts"],
			"source_path": item["source_path"],
			"artifact_root": item.get("artifact_root", str(source_root))})
	source_artifact_paths = [source_manifest_path, source_root / "launch.json",
		source_root / "finished-at.txt", source_root / "exit-code.txt",
		source_root / terminal["lease_release_path"], old_runner, old_integrated_path]
	if source_manifest.get("identity", {}).get("continuation_sha256"):
		source_artifact_paths += [source_root / "continuation.json",
			source_root / "frozen-harness/matrix_continuation.py"]
	return {"schema": SCHEMA, "source_root": str(source_root),
		"source_manifest_sha256": _sha(source_manifest_path),
		"target_manifest_contract_sha256": _manifest_contract_sha(current_manifest),
		"source_artifacts": [_artifact(source_root, p) for p in source_artifact_paths],
		"terminal": terminal,
		"compatibility": {"source_runner_sha256": _sha(old_runner),
			"current_runner_sha256": _sha(current_runner),
			"source_integrated_sha256": old_integrated_sha,
			"current_integrated_sha256": new_integrated_sha},
		"rows": rows, "excluded": excluded}


def _artifact_path(root: Path, relative: str) -> Path:
	path = Path(relative)
	if path.is_absolute() or ".." in path.parts:
		raise ValueError("continuation artifact path is unsafe")
	return root / path


def load_rows(root: Path, manifest: dict | None = None) -> dict:
	"""Load imported rows after validating the bounded acyclic source chain."""
	return _load_rows(Path(root), manifest, set(), 0)


def _load_rows(root: Path, manifest: dict | None, seen: set[Path], depth: int) -> dict:
	root = root.resolve()
	if depth > MAX_CHAIN_DEPTH:
		raise ValueError("continuation source chain exceeds bounded depth")
	if root in seen:
		raise ValueError("continuation source chain is cyclic")
	seen.add(root)
	path = root / "continuation.json"
	if not path.is_file():
		if manifest is None and (root / "manifest.json").is_file():
			manifest = _read_json(root / "manifest.json")
		if manifest is not None and manifest.get("identity", {}).get("continuation_sha256"):
			raise ValueError("manifest-pinned continuation snapshot is missing")
		return {}
	manifest = manifest if manifest is not None else _read_json(root / "manifest.json")
	expected = manifest.get("identity", {}).get("continuation_sha256")
	if not expected or _sha(path) != expected:
		raise ValueError("continuation snapshot mutated or is not pinned by manifest")
	snapshot = _read_json(path)
	if snapshot.get("schema") != SCHEMA:
		raise ValueError("continuation snapshot schema invalid")
	if _manifest_contract_sha(manifest) != snapshot.get("target_manifest_contract_sha256"):
		raise ValueError("continuation target manifest contract changed")
	source_root = Path(snapshot.get("source_root", ""))
	manifest_path = source_root / "manifest.json"
	if not manifest_path.is_file() or _sha(manifest_path) != snapshot.get("source_manifest_sha256"):
		raise ValueError("continuation source manifest mutated")
	source_manifest = _read_json(manifest_path)
	if source_manifest.get("identity", {}).get("continuation_sha256"):
		_load_rows(source_root, source_manifest, seen, depth + 1)
	for artifact in snapshot.get("source_artifacts", []):
		artifact_path = _artifact_path(source_root, artifact["path"])
		if not artifact_path.is_file() or _sha(artifact_path) != artifact.get("sha256"):
			raise ValueError(f"continuation source artifact mutated: {artifact.get('path')}")
	for exclusion in snapshot.get("excluded", []):
		for artifact in exclusion.get("artifacts", []):
			artifact_path = _artifact_path(source_root, artifact["path"])
			if not artifact_path.is_file() or _sha(artifact_path) != artifact.get("sha256"):
				raise ValueError(f"continuation excluded artifact mutated: {artifact.get('path')}")
	rows = {}
	for item in snapshot.get("rows", []):
		artifact_root = Path(item.get("artifact_root", str(source_root)))
		if not artifact_root.is_absolute():
			raise ValueError("continuation row artifact root is not absolute")
		for artifact in item.get("artifacts", []):
			artifact_path = _artifact_path(artifact_root, artifact["path"])
			if not artifact_path.is_file() or _sha(artifact_path) != artifact.get("sha256"):
				raise ValueError(f"continuation source artifact mutated: {artifact.get('path')}")
		row = copy.deepcopy(item["row"])
		cell_id = item.get("cell_id")
		if row.get("cell", {}).get("id") != cell_id or cell_id in rows:
			raise ValueError("continuation snapshot row identity invalid")
		if "continuation_origin" not in row:
			row["continuation_origin"] = {"root": str(artifact_root),
				"result_path": item["source_path"],
				"source_manifest_sha256": snapshot["source_manifest_sha256"],
				"result_sha256": next(a["sha256"] for a in item["artifacts"]
					if a["path"] == item["source_path"])}
		rows[cell_id] = row
	return rows
