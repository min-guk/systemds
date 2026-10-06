#!/usr/bin/env python3
"""Run the frozen function-boundary planning comparison in pinned Docker JVMs."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import uuid
import xml.etree.ElementTree as ET


PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
PROBE_MAIN = "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.BoundaryComparisonProbe"
P2_PROPERTY = "-Dsysds.privacy.allowPublicRecodeMetadata=true"
SAFE_CASE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
SUMMARY_LOCK = threading.Lock()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
	parser = argparse.ArgumentParser(
		description="Compile-only A/B planner comparison in the pinned campaign image.")
	parser.add_argument("--artifact-root", type=Path, required=True)
	parser.add_argument(
		"--variant", choices=("A", "B"), action="append",
		help="variant to run; repeat for both (default: A and B)")
	parser.add_argument(
		"--cases", nargs="+", metavar="CASE",
		help="case names or comma-separated names (default: cases/*/script.dml)")
	parser.add_argument("--jobs", type=int, default=2, help="maximum concurrent containers (default: 2)")
	parser.add_argument(
		"--dry-run", action="store_true",
		help="validate inputs and write command receipts without starting Docker")
	return parser.parse_args(argv)


def load_json_object(path: Path) -> dict:
	try:
		value = json.loads(path.read_text(encoding="utf-8"))
	except (OSError, json.JSONDecodeError) as exc:
		raise ValueError(f"cannot read JSON object {path}: {exc}") from exc
	if not isinstance(value, dict):
		raise ValueError(f"expected a JSON object in {path}")
	return value


def require_absolute_directory(value: object, key: str) -> Path:
	path = Path(str(value))
	if not path.is_absolute() or not path.is_dir():
		raise ValueError(f"runner setting {key} must be an existing absolute directory: {path}")
	return path.resolve()


def load_settings(root: Path) -> dict:
	settings = load_json_object(root / "runner-settings.json")
	required = {
		"base_worktree", "deps_dir", "dependency_code_dir", "probe_classes",
		"probe_main", "env_json", "image",
	}
	missing = sorted(required - settings.keys())
	if missing:
		raise ValueError(f"runner-settings.json is missing keys: {', '.join(missing)}")
	settings["base_worktree"] = require_absolute_directory(settings["base_worktree"], "base_worktree")
	settings["deps_dir"] = require_absolute_directory(settings["deps_dir"], "deps_dir")
	settings["dependency_code_dir"] = require_absolute_directory(
		settings["dependency_code_dir"], "dependency_code_dir")
	settings["probe_classes"] = require_absolute_directory(settings["probe_classes"], "probe_classes")
	settings["env_json"] = Path(str(settings["env_json"])).resolve()
	if not settings["env_json"].is_file():
		raise ValueError(f"runner setting env_json is not a file: {settings['env_json']}")
	if settings["probe_main"] != PROBE_MAIN:
		raise ValueError(f"unexpected probe_main: {settings['probe_main']}")
	if settings["image"] != PINNED_IMAGE:
		raise ValueError(f"comparison requires pinned image {PINNED_IMAGE}, got {settings['image']}")
	base_classes = settings["base_worktree"] / "target" / "classes"
	if not base_classes.is_dir():
		raise ValueError(f"base target/classes is missing: {base_classes}")
	return settings


def split_case_args(values: list[str] | None) -> list[str]:
	if values is None:
		return []
	result: list[str] = []
	for value in values:
		result.extend(part.strip() for part in value.split(",") if part.strip())
	return result


def assert_within(path: Path, parent: Path, label: str) -> None:
	try:
		path.relative_to(parent)
	except ValueError as exc:
		raise ValueError(f"{label} escapes {parent}: {path}") from exc


def discover_cases(root: Path, requested: list[str] | None) -> list[tuple[str, Path, Path]]:
	cases_root = (root / "cases").resolve()
	if not cases_root.is_dir():
		raise ValueError(f"cases directory is missing: {cases_root}")
	names = split_case_args(requested)
	if not names:
		names = [path.parent.name for path in sorted(cases_root.glob("*/script.dml"))]
	if not names:
		raise ValueError(f"no cases found below {cases_root}")
	seen: set[str] = set()
	result: list[tuple[str, Path, Path]] = []
	for raw_name in names:
		candidate = Path(raw_name)
		name = candidate.parent.name if candidate.name == "script.dml" else candidate.name
		if name.endswith(".dml"):
			name = name[:-4]
		if not SAFE_CASE_NAME.fullmatch(name):
			raise ValueError(f"unsafe case name: {raw_name}")
		if name in seen:
			continue
		seen.add(name)
		case_dir = (cases_root / name).resolve()
		assert_within(case_dir, cases_root, "case directory")
		script = case_dir / "script.dml"
		config = case_dir / "config.xml"
		if not script.is_file() or not config.is_file():
			raise ValueError(f"case {name} requires script.dml and config.xml in {case_dir}")
		assert_compile_only(config)
		result.append((name, script, config))
	return result


def assert_compile_only(config: Path) -> None:
	try:
		root = ET.parse(config).getroot()
	except (OSError, ET.ParseError) as exc:
		raise ValueError(f"cannot parse case config {config}: {exc}") from exc
	values = ["" if element.text is None else element.text.strip().lower()
		for element in root.findall(".//sysds.benchmark.compile_only")]
	if values != ["true"]:
		raise ValueError(
			f"refusing non-compile-only case {config}: expected exactly one "
			"<sysds.benchmark.compile_only>true</sysds.benchmark.compile_only>")


def classpath(root: Path, settings: dict, variant: str) -> str:
	parts: list[str] = []
	if variant == "A":
		overlay = root / "boundary-a-classes"
		if not overlay.is_dir():
			raise ValueError(f"variant A overlay is missing: {overlay}")
		parts.append(str(overlay))
	parts.extend((
		str(settings["probe_classes"]),
		str(settings["base_worktree"] / "target" / "classes"),
		"/deps/*",
	))
	return ":".join(parts)


def java_argv(root: Path, settings: dict, variant: str, name: str,
		script: Path, config: Path, output: Path) -> list[str]:
	args = [
		"java", "--add-modules", "jdk.incubator.vector", "-Xmx10g",
		"-XX:ActiveProcessorCount=4",
		"-Dsysds.fedplanner.runtime.audit=true",
		"-Dsysds.fedplanner.phaseMarkers=true",
	]
	if name.lower().startswith("p2_prep"):
		args.append(P2_PROPERTY)
	args.extend((
		"-cp", classpath(root, settings, variant), settings["probe_main"],
		str(script), str(config), str(output),
	))
	return args


def docker_argv(root: Path, settings: dict, container_name: str,
		environment: dict, java_args: list[str]) -> list[str]:
	uid = os.getuid()
	gid = os.getgid()
	args = [
		"docker", "run", "--rm", "--network", "none", "--name", container_name,
		"--user", f"{uid}:{gid}", "--cpus", "4", "--memory", "16g",
		"--workdir", str(root / "runtime"),
		"--volume", f"{root}:{root}:rw",
		"--volume", f"{settings['base_worktree']}:{settings['base_worktree']}:ro",
		"--volume", f"{settings['deps_dir']}:/deps:ro",
		"--volume", f"{settings['dependency_code_dir']}:{settings['dependency_code_dir']}:ro",
	]
	for key in sorted(environment):
		value = environment[key]
		if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
			raise ValueError(f"invalid environment variable name in env_json: {key!r}")
		if not isinstance(value, (str, int, float, bool)) or "\x00" in str(value):
			raise ValueError(f"invalid value for environment variable {key}")
		args.extend(("--env", f"{key}={value}"))
	args.append(settings["image"])
	args.extend(java_args)
	return args


def sha256(path: Path) -> str:
	digest = hashlib.sha256()
	with path.open("rb") as handle:
		for chunk in iter(lambda: handle.read(1024 * 1024), b""):
			digest.update(chunk)
	return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
	path.parent.mkdir(parents=True, exist_ok=True)
	temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
	temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
	os.replace(temporary, path)


def update_summary(path: Path, receipt: dict) -> None:
	with SUMMARY_LOCK:
		current: list[dict] = []
		if path.is_file():
			loaded = json.loads(path.read_text(encoding="utf-8"))
			if isinstance(loaded, list):
				current = loaded
		current = [item for item in current if item.get("case") != receipt["case"]]
		current.append(receipt)
		current.sort(key=lambda item: item["case"])
		write_json(path, current)


def run_case(root: Path, settings: dict, environment: dict, variant: str,
		case: tuple[str, Path, Path], dry_run: bool) -> dict:
	name, script, config = case
	destination = root / "results" / variant
	destination.mkdir(parents=True, exist_ok=True)
	output = destination / f"{name}.json"
	log = destination / f"{name}.log"
	command_path = destination / f"{name}.command.json"
	container_name = f"codex-function-boundary-{variant.lower()}-{name.lower()}-{uuid.uuid4().hex[:12]}"
	java_args = java_argv(root, settings, variant, name, script, config, output)
	docker_args = docker_argv(root, settings, container_name, environment, java_args)
	command = {
		"case": name,
		"variant": variant,
		"container_name": container_name,
		"docker_argv": docker_args,
		"java_argv": java_args,
		"cwd": str(root / "runtime"),
		"image": settings["image"],
		"script_sha256": sha256(script),
		"config_sha256": sha256(config),
		"environment": environment,
		"compile_only_validated": True,
	}
	write_json(command_path, command)
	started = time.monotonic()
	if dry_run:
		receipt = {"case": name, "variant": variant, "status": "DRY_RUN", "returncode": None,
			"wall_seconds": 0.0, "container_name": container_name}
	else:
		with log.open("w", encoding="utf-8") as handle:
			completed = subprocess.run(
				docker_args, stdout=handle, stderr=subprocess.STDOUT, check=False)
		receipt = {
			"case": name,
			"variant": variant,
			"status": "COMPLETE" if completed.returncode == 0 and output.is_file() else "FAILED",
			"returncode": completed.returncode,
			"wall_seconds": time.monotonic() - started,
			"container_name": container_name,
			"output_present": output.is_file(),
		}
	update_summary(destination / "runs.json", receipt)
	print(json.dumps(receipt, sort_keys=True), flush=True)
	return receipt


def main(argv: list[str] | None = None) -> int:
	args = parse_args(argv)
	if args.jobs < 1 or args.jobs > 2:
		raise ValueError("--jobs must be 1 or 2")
	root = args.artifact_root.resolve()
	if not root.is_dir():
		raise ValueError(f"artifact root is missing: {root}")
	runtime = root / "runtime"
	if not runtime.is_dir():
		raise ValueError(f"runtime directory is missing: {runtime}")
	settings = load_settings(root)
	environment = load_json_object(settings["env_json"])
	cases = discover_cases(root, args.cases)
	variants = list(dict.fromkeys(args.variant or ["A", "B"]))
	work = [(variant, case) for variant in variants for case in cases]
	with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
		futures = [pool.submit(run_case, root, settings, environment, variant, case, args.dry_run)
			for variant, case in work]
		results = [future.result() for future in concurrent.futures.as_completed(futures)]
	return 0 if all(item["status"] in {"COMPLETE", "DRY_RUN"} for item in results) else 1


if __name__ == "__main__":
	try:
		raise SystemExit(main())
	except (OSError, ValueError, subprocess.SubprocessError) as exc:
		print(f"error: {exc}", file=sys.stderr)
		raise SystemExit(2)
