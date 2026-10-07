#!/usr/bin/env python3
"""Pure-Python regression tests for the v69 pinned-runtime witness contract."""

from __future__ import annotations

import copy
from dataclasses import dataclass
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest import mock


TEST_DIRECTORY = Path(__file__).resolve().parent
FEDPLANNER_DIRECTORY = TEST_DIRECTORY.parent
RUNNER = FEDPLANNER_DIRECTORY / "run_matrix_campaign.py"
SOURCE_TESTS = TEST_DIRECTORY / "test_run_matrix_campaign.py"
SOURCE_REPO = os.environ.get("FEDS_SOURCE_REPO")
sys.path.insert(0, str(FEDPLANNER_DIRECTORY))
if not SOURCE_TESTS.is_file():
	if SOURCE_REPO is None:
		raise RuntimeError("isolated scratch tests require FEDS_SOURCE_REPO")
	source_fedplanner = Path(SOURCE_REPO).resolve() / "scripts/fedplanner"
	SOURCE_TESTS = source_fedplanner / "tests/test_run_matrix_campaign.py"
	sys.path.insert(1, str(source_fedplanner))
SPEC = importlib.util.spec_from_file_location("run_matrix_campaign_pinned_runtime", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAMPAIGN)
HELPER_SPEC = importlib.util.spec_from_file_location("run_matrix_campaign_test_helpers", SOURCE_TESTS)
HELPERS = importlib.util.module_from_spec(HELPER_SPEC)
assert HELPER_SPEC.loader is not None
HELPER_SPEC.loader.exec_module(HELPERS)

IMAGE = "sha256:" + "d" * 64


def image_inspect(image=IMAGE):
	return [{"Id": image, "Architecture": "amd64", "Os": "linux",
		"Created": "2026-07-08T03:24:10Z", "Config": {"Cmd": ["/bin/bash"]},
		"RootFS": {"Layers": ["sha256:" + "1" * 64, "sha256:" + "2" * 64]}}]


def transported_source_image_inspect():
	"""The immutable fields captured from the so002 image before save/load transport."""
	return [{"Id": "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434",
		"Architecture": "amd64", "Os": "linux",
		"Created": "2026-07-08T03:24:10.274024016+02:00",
		"Config": {"Env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
			"DEBIAN_FRONTEND=noninteractive"], "Cmd": ["/bin/bash"],
			"WorkingDir": "/workspace/experiments", "Labels": {
				"com.docker.compose.project": "g005_c7696a3f_pca4_fedall_20260726t113522z",
				"com.docker.compose.service": "coordinator",
				"com.docker.compose.version": "5.1.1",
				"org.opencontainers.image.version": "22.04"}},
		"RootFS": {"Layers": [
			"sha256:10f9108f6cebf96e05cadfab3c188071f3c6d084abe680fd0bc9f61c268e854d",
			"sha256:aa91d6acba8a6c4f60cb2ca93937c28f94658177bfc83fe0b34718f5a94778cd",
			"sha256:789b8f3beb63c25af6aaa44c3f188addff41cc5e6c5d11b5609d84caf7a1b097",
			"sha256:a47b6ea4c4a6f5b3735b0e5bc9f406e340b852b47ec4e6d7e8e620006b86693d"]}}]


def sha_bytes(raw):
	return hashlib.sha256(raw).hexdigest()


def fixture(root):
	cost = {key: "1.25" for key in CAMPAIGN.PINNED_COST_KEYS}
	config = {"sysds.native.blas": "none", "sysds.codegen.enabled": "false"}
	files = {"cost_environment": root / "cost.json",
		"configuration": root / "execution.xml", "command": root / "command.json",
		"image_inspect": root / "image-inspect.json"}
	files["cost_environment"].write_text(json.dumps(cost, sort_keys=True) + "\n")
	files["configuration"].write_text("<root><sysds.native.blas>none</sysds.native.blas>"
		"<sysds.codegen.enabled>false</sysds.codegen.enabled></root>\n")
	files["command"].write_text(json.dumps({"image": IMAGE, "environment": cost,
		"docker_argv": ["docker", "run", "--cpus", "4", "--memory", "16g"],
		"java_argv": ["java", "-Xmx10g", "-XX:ActiveProcessorCount=4"]}, sort_keys=True) + "\n")
	files["image_inspect"].write_text(json.dumps(image_inspect(), sort_keys=True) + "\n")
	provenance = {}
	for name, path in files.items():
		provenance[name + "_source"] = str(path)
		provenance[name + "_sha256"] = sha_bytes(path.read_bytes())
	_, content = CAMPAIGN.normalized_image_content(image_inspect())
	value = {"schema": CAMPAIGN.PINNED_RUNTIME_SCHEMA, "image": IMAGE,
		"runtime_image": "cofee-experiment:content-" + content,
		"cost_environment": cost, "config": config,
		"resources": {"container_cpus": "4", "container_memory": "16g",
			"jvm_max_heap": "10g", "active_processor_count": 4}, "provenance": provenance}
	path = root / "contract.json"
	path.write_text(json.dumps(value, sort_keys=True) + "\n")
	return path, value, files


def write_contract(path, value):
	path.write_text(json.dumps(value, sort_keys=True) + "\n")
	return path


def make_repo(root):
	repo = root / "repo"
	(repo / "src/main").mkdir(parents=True)
	(repo / "scripts/builtin").mkdir(parents=True)
	(repo / "target").mkdir()
	(repo / "pom.xml").write_text("pom\n")
	for name in CAMPAIGN.BUILTIN_SCRIPTS:
		(repo / "scripts/builtin" / name).write_text(name + "\n")
	probe = repo / "MatrixCampaignProbe.java"
	probe.write_text("class MatrixCampaignProbe {}\n")
	jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
	jar.write_bytes(b"jar")
	now = max(path.stat().st_mtime_ns for path in [repo / "pom.xml",
		*(repo / "scripts/builtin").iterdir()]) + 1_000_000
	os.utime(jar, ns=(now, now))
	return repo, probe


def pinned_receipt(contract, script_hash="a" * 64, config_hash="b" * 64,
		fingerprints=None):
	fingerprints = fingerprints or ["1" * 64, "2" * 64]
	return {"scriptSha256": script_hash, "configSha256": config_hash,
		"effectiveCostEnvironment": dict(contract["cost_environment"]),
		"executionSettings": {**contract["config"],
			"jvmArguments": ["-Xmx10g", "-XX:ActiveProcessorCount=4"],
			"availableProcessors": 4, "maxHeapBytes": 8 * 1024 ** 3,
			"maxHeapSizeBytes": 10 * 1024 ** 3},
		"plannerAuthorityGenerations": [{"sequence": index, "planFingerprint": fingerprint,
			"analysisFingerprint": format(index + 10, "064x")}
			for index, fingerprint in enumerate(fingerprints)],
		"initialSelectionFingerprint": fingerprints[0],
		"finalSelectionFingerprint": fingerprints[-1],
		"plannerRuntimeAudit": {"plan": fingerprints[-1],
			"authorityGenerations": len(set(fingerprints))}}


@dataclass(frozen=True)
class Command:
	label: str
	host: str
	argv: tuple

	def as_dict(self):
		return {"label": self.label, "host": self.host, "argv": list(self.argv)}


@dataclass(frozen=True)
class Plan:
	action: str
	campaign_id: str
	commands: tuple

	def as_dict(self):
		return {"action": self.action, "campaign_id": self.campaign_id,
			"commands": [command.as_dict() for command in self.commands]}


class ContractParsingTest(unittest.TestCase):
	def test_valid_contract_requires_exact_java_compatible_positive_cost_vector(self):
		with tempfile.TemporaryDirectory() as directory:
			path, expected, _ = fixture(Path(directory))
			raw, actual, digest = CAMPAIGN.read_pinned_runtime_contract(path)
			self.assertEqual(expected, actual)
			self.assertEqual(sha_bytes(raw), digest)
			self.assertEqual(11, len(actual["cost_environment"]))


class ImageContentContractTest(unittest.TestCase):
	def test_actual_source_inspect_has_the_recorded_content_identity(self):
		image_id, content = CAMPAIGN.normalized_image_content(transported_source_image_inspect())
		self.assertEqual("sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434",
			image_id)
		self.assertEqual("0861f4ff197c868f42abf6478b66505f650325c267caa8be2e82b4b6317c7ff0",
			content)

	def test_tampered_or_semantically_changed_inspect_is_rejected(self):
		for name, mutation, update_hash, expected in (
			("tampered provenance", lambda row: row[0]["Config"].update({"User": "root"}),
				False, "provenance"),
			("architecture", lambda row: row[0].__setitem__("Architecture", "arm64"),
				True, "content tag"),
			("layer", lambda row: row[0]["RootFS"]["Layers"].append("sha256:" + "3" * 64),
				True, "content tag"),
			("config", lambda row: row[0]["Config"].update({"User": "root"}),
				True, "content tag"),
			("missing", lambda row: row[0].pop("RootFS"), True, "content fields"),
			("source id", lambda row: row[0].__setitem__("Id", "sha256:" + "e" * 64),
				True, "Docker image differs")):
			with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
				path, value, files = fixture(Path(directory))
				inspect = json.loads(files["image_inspect"].read_text())
				mutation(inspect)
				files["image_inspect"].write_text(json.dumps(inspect))
				if update_hash:
					value["provenance"]["image_inspect_sha256"] = sha_bytes(
						files["image_inspect"].read_bytes())
				write_contract(path, value)
				with self.assertRaisesRegex(ValueError, expected):
					CAMPAIGN.read_pinned_runtime_contract(path)
	def test_malformed_types_and_numeric_tokens_fail_closed(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			_, good, _ = fixture(root)
			cases = (
				("cost map", lambda value: value.__setitem__("cost_environment", [])),
				("missing cost", lambda value: value["cost_environment"].pop(next(iter(CAMPAIGN.PINNED_COST_KEYS)))),
				("numeric cost type", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", 1.25)),
				("zero", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "0")),
				("negative", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "-1")),
				("nan", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "NaN")),
				("infinity", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "1e999")),
				("underscore", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "1_000")),
				("unicode digits", lambda value: value["cost_environment"].__setitem__("SYSDS_FED_COST_FLOPS", "١٢.٥")),
				("boolean processor", lambda value: value["resources"].__setitem__("active_processor_count", True)),
				("numeric cpus", lambda value: value["resources"].__setitem__("container_cpus", 4)),
				("mutable image", lambda value: value.__setitem__("image", "cofee:latest")),
				("mutable runtime image", lambda value: value.__setitem__("runtime_image", "cofee:latest")),
				("foreign runtime repository", lambda value: value.__setitem__("runtime_image",
					"foreign:content-" + "a" * 64)),
				("old schema", lambda value: value.__setitem__("schema",
					"w1357-pinned-runtime-contract/v2")))
			for index, (name, mutation) in enumerate(cases):
				value = copy.deepcopy(good)
				mutation(value)
				path = write_contract(root / f"bad-{index}.json", value)
				with self.subTest(name=name), self.assertRaises(ValueError):
					CAMPAIGN.read_pinned_runtime_contract(path)

	def test_source_hash_and_extracted_values_are_both_verified(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			path, value, files = fixture(root)
			files["command"].write_text("{}\n")
			with self.assertRaisesRegex(ValueError, "provenance hash mismatch"):
				CAMPAIGN.read_pinned_runtime_contract(path)
			path, value, _ = fixture(root)
			value["cost_environment"]["SYSDS_FED_COST_FLOPS"] = "2.5"
			write_contract(path, value)
			with self.assertRaisesRegex(ValueError, "provenance values mismatch"):
				CAMPAIGN.read_pinned_runtime_contract(path)
			path, value, _ = fixture(root)
			value["config"]["sysds.native.blas"] = "mkl"
			write_contract(path, value)
			with self.assertRaisesRegex(ValueError, "provenance values mismatch"):
				CAMPAIGN.read_pinned_runtime_contract(path)


class FreezeAndDefaultsTest(unittest.TestCase):
	def test_initialize_freezes_source_bytes_and_resume_uses_frozen_copies(self):
		with tempfile.TemporaryDirectory() as directory:
			base = Path(directory)
			repo, probe = make_repo(base)
			contract_path, _, sources = fixture(base)
			root = base / "campaign"
			with mock.patch.object(CAMPAIGN, "REPO", repo), \
					mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
					mock.patch.object(CAMPAIGN, "verify_builtin_sync"), \
					mock.patch.object(CAMPAIGN, "sha", side_effect=lambda path:
						sha_bytes(Path(path).read_bytes()) if Path(path).is_file() else "a" * 64), \
					mock.patch.object(CAMPAIGN, "run", return_value="mocked"):
				manifest = CAMPAIGN.initialize(root, base / "stage", direct_runtime=True,
					pinned_runtime_contract=contract_path)
				self.assertEqual(contract_path.read_bytes(),
					(root / CAMPAIGN.PINNED_RUNTIME_FILE).read_bytes())
				self.assertEqual(sha_bytes(contract_path.read_bytes()),
					manifest["identity"]["pinned_runtime_contract_sha256"])
				for name, source in sources.items():
					self.assertEqual(source.read_bytes(),
						(root / CAMPAIGN.PINNED_SOURCE_DIRECTORY / name).read_bytes())
				for source in sources.values():
					source.unlink()
				self.assertEqual(manifest, CAMPAIGN.initialize(root, base / "stage",
					direct_runtime=True, pinned_runtime_contract=contract_path))
				frozen = root / CAMPAIGN.PINNED_SOURCE_DIRECTORY / "command"
				frozen.write_bytes(frozen.read_bytes() + b" ")
				with self.assertRaisesRegex(ValueError, "provenance hash mismatch"):
					CAMPAIGN.initialize(root, base / "stage", direct_runtime=True,
						pinned_runtime_contract=contract_path)

	def test_frozen_contract_digest_tampering_is_rejected(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			path, _, files = fixture(root)
			raw, _, digest = CAMPAIGN.read_pinned_runtime_contract(path)
			(root / CAMPAIGN.PINNED_RUNTIME_FILE).write_bytes(raw)
			frozen = root / CAMPAIGN.PINNED_SOURCE_DIRECTORY
			frozen.mkdir()
			for name, source in files.items():
				(frozen / name).write_bytes(source.read_bytes())
			manifest = {"identity": {"pinned_runtime_contract_sha256": digest}}
			self.assertIsNotNone(CAMPAIGN.frozen_pinned_runtime_contract(root, manifest))
			(root / CAMPAIGN.PINNED_RUNTIME_FILE).write_bytes(raw + b" ")
			with self.assertRaisesRegex(RuntimeError, "contract changed"):
				CAMPAIGN.frozen_pinned_runtime_contract(root, manifest)

	def test_default_java_config_and_lifecycle_are_preserved(self):
		with tempfile.TemporaryDirectory() as directory:
			_, value, _ = fixture(Path(directory))
			self.assertEqual(list(CAMPAIGN.JAVA), CAMPAIGN.runtime_java())
			default_xml = CAMPAIGN.ET.fromstring(CAMPAIGN.config(CAMPAIGN.matrix()[0], "runtime"))
			self.assertEqual("mkl", default_xml.findtext("sysds.native.blas"))
			self.assertIsNone(default_xml.find("sysds.codegen.enabled"))
			self.assertIn("-Xmx10g", CAMPAIGN.runtime_java(value))
			life = {"container": {"cpuset_cpus": "0-7", "memory": "24g", "keep": "yes"},
				"coordinator": {"environment": {"KEEP": "yes", "SYSDS_FED_COST_FLOPS": "old"}},
				"workers": [{"environment": {"KEEP": "worker"}}]}
			CAMPAIGN.apply_pinned_runtime_contract(life, value, "a" * 64)
			self.assertEqual({"cpuset_cpus": "0-7", "memory": "16g", "keep": "yes"}, life["container"])
			self.assertEqual("yes", life["coordinator"]["environment"]["KEEP"])
			self.assertEqual("worker", life["workers"][0]["environment"]["KEEP"])

	def test_default_execution_hands_manifest_image_to_lifecycle(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			selected_image = "sha256:" + "e" * 64
			observed = []
			node = SimpleNamespace(host="so007")
			spec = SimpleNamespace(coordinator=node, workers=(),
				container_name=lambda selected: "coordinator")
			base = SimpleNamespace(parse_manifest=lambda value: spec,
				prepare_remote_directories=lambda *args: None,
				prepare_cost_profile=mock.Mock(side_effect=RuntimeError("stop after lifecycle handoff")))
			def lifecycle(cell, stage, remote, **kwargs):
				observed.append(kwargs["image"])
				return {"mounts": [], "coordinator": {"environment": {}}, "workers": []}
			campaign = SimpleNamespace(bounded_pilot_lifecycle=lifecycle,
				remote_resource_preflight=lambda *args: {"passed": True},
				_strict_experiment_cleanup=mock.Mock())
			with mock.patch.object(CAMPAIGN, "run", return_value=""), \
					mock.patch.object(CAMPAIGN, "ssh", return_value=""), \
					mock.patch("sys.stdout", new=io.StringIO()):
				result = CAMPAIGN.execute_cell(root, {"remote_root": "/remote", "image": selected_image,
					"identity": {"jar_sha256": "a" * 64}}, CAMPAIGN.matrix()[0], "runtime",
					SimpleNamespace(stage=Path("/stage")), campaign, base,
					SimpleNamespace(render=lambda cell: {"source": "print(1);"}))
			self.assertIn("stop after lifecycle handoff", result["errors"])
			self.assertEqual([selected_image], observed)

	def test_cli_requires_explicit_direct_runtime_before_dependencies(self):
		with tempfile.TemporaryDirectory() as directory:
			path, _, _ = fixture(Path(directory))
			for argv in (["--root", directory, "--pinned-runtime-contract", str(path)],
					["--root", directory, "--phase", "runtime", "--pinned-runtime-contract", str(path)]):
				with self.subTest(argv=argv), mock.patch.object(CAMPAIGN, "dependencies") as dependencies, \
						mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as raised:
					CAMPAIGN.main(argv)
				self.assertEqual(2, raised.exception.code)
				dependencies.assert_not_called()


class StartPlanAndObservationTest(unittest.TestCase):
	def test_quota_adapter_changes_only_docker_run_commands_and_preserves_all_stages(self):
		with tempfile.TemporaryDirectory() as directory:
			_, value, _ = fixture(Path(directory))
			commands = (Command("run-worker-1", "so002", ("ssh", "so002", "docker run --cpuset-cpus 0-3 --memory 24g image worker")),
				Command("run-coordinator", "so007", ("ssh", "so007", "docker run --cpuset-cpus 0-3 --memory 24g image coordinator")),
				Command("tc-worker-1", "so002", ("ssh", "so002", "tc qdisc show")),
				Command("verify-worker-1", "so002", ("ssh", "so002", "docker inspect worker")),
				Command("readiness", "so007", ("ssh", "so007", "curl ready")))
			plan = Plan("start", "campaign", commands)
			self.assertIs(plan, CAMPAIGN.pinned_runtime_start_plan(plan, None))
			adapted = CAMPAIGN.pinned_runtime_start_plan(plan, value)
			self.assertEqual([command.label for command in commands], [command.label for command in adapted.commands])
			for before, after in zip(commands, adapted.commands):
				if before.label.startswith("run-"):
					self.assertIn("--cpus 4", after.argv[-1])
					self.assertNotIn("--cpuset-cpus", after.argv[-1])
				else:
					self.assertEqual(before, after)

	def test_observed_container_quota_memory_image_and_environment_mismatches_fail(self):
		with tempfile.TemporaryDirectory() as directory:
			_, value, _ = fixture(Path(directory))
			node = SimpleNamespace(host="so007")
			spec = SimpleNamespace(container_name=lambda selected: "coordinator")
			runtime_id = "sha256:" + "e" * 64
			base_record = {"Name": "/coordinator", "Id": "cid", "Image": runtime_id,
				"HostConfig": {"NanoCpus": 4_000_000_000, "CpusetCpus": "", "Memory": 16 * 1024 ** 3},
				"Config": {"Env": [f"{key}={item}" for key, item in value["cost_environment"].items()]}}
			def observed(row=base_record, inspected=None):
				inspected = image_inspect(runtime_id) if inspected is None else inspected
				return lambda host, argv, **kwargs: json.dumps(inspected if "image" in argv else [row])
			with mock.patch.object(CAMPAIGN.lifecycle, "prepare_nodes", side_effect=lambda nodes, fn: [fn(n) for n in nodes]):
				with mock.patch.object(CAMPAIGN, "ssh", side_effect=observed()):
					receipt = CAMPAIGN.pinned_container_resources(spec, [node], value)[0]
					self.assertEqual(4_000_000_000, receipt["nanoCpus"])
					self.assertEqual(runtime_id, receipt["actualImageId"])
					self.assertEqual(value["runtime_image"].removeprefix(
						"cofee-experiment:content-"), receipt["contentSha256"])
				mutations = (("quota", lambda row: row["HostConfig"].__setitem__("NanoCpus", 3_000_000_000)),
					("affinity", lambda row: row["HostConfig"].__setitem__("CpusetCpus", "0-3")),
					("memory", lambda row: row["HostConfig"].__setitem__("Memory", 8 * 1024 ** 3)),
					("image", lambda row: row.__setitem__("Image", "sha256:" + "f" * 64)),
					("environment", lambda row: row["Config"].__setitem__("Env", [])))
				for name, mutation in mutations:
					record = copy.deepcopy(base_record)
					mutation(record)
					with self.subTest(name=name), mock.patch.object(CAMPAIGN, "ssh", side_effect=observed(record)), \
							self.assertRaisesRegex(ValueError, "observed"):
						CAMPAIGN.pinned_container_resources(spec, [node], value)
				altered = image_inspect(runtime_id)
				altered[0]["Config"]["Cmd"] = ["changed"]
				with self.assertRaisesRegex(ValueError, "observed"), \
						mock.patch.object(CAMPAIGN, "ssh", side_effect=observed(inspected=altered)):
					CAMPAIGN.pinned_container_resources(spec, [node], value)


class ReceiptTest(unittest.TestCase):
	def test_two_and_repeated_generations_link_initial_and_final_authority(self):
		with tempfile.TemporaryDirectory() as directory:
			_, value, _ = fixture(Path(directory))
			for fingerprints in (["1" * 64, "2" * 64], ["1" * 64, "2" * 64, "2" * 64]):
				with self.subTest(generations=len(fingerprints)):
					CAMPAIGN.validate_pinned_runtime_receipt(pinned_receipt(value, fingerprints=fingerprints),
						value, "a" * 64, "b" * 64)

	def test_executed_hash_cost_config_jvm_and_authority_mismatches_fail(self):
		with tempfile.TemporaryDirectory() as directory:
			_, value, _ = fixture(Path(directory))
			mutations = (("script", lambda row: row.__setitem__("scriptSha256", "f" * 64)),
				("config hash", lambda row: row.__setitem__("configSha256", "f" * 64)),
				("cost", lambda row: row.__setitem__("effectiveCostEnvironment", {})),
				("config", lambda row: row["executionSettings"].__setitem__("sysds.native.blas", "mkl")),
				("heap", lambda row: row["executionSettings"].__setitem__("jvmArguments", ["-Xmx9g", "-XX:ActiveProcessorCount=4"])),
				("processors", lambda row: row["executionSettings"].__setitem__("availableProcessors", 3)),
				("effective heap override", lambda row: row["executionSettings"].update({"jvmArguments": ["-Xmx10g", "-XX:MaxHeapSize=1g", "-XX:ActiveProcessorCount=4"], "maxHeapBytes": 1024 ** 3, "maxHeapSizeBytes": 1024 ** 3})),
				("initial", lambda row: row.__setitem__("initialSelectionFingerprint", "f" * 64)),
				("final", lambda row: row.__setitem__("finalSelectionFingerprint", "f" * 64)),
				("audit", lambda row: row["plannerRuntimeAudit"].__setitem__("plan", "f" * 64)),
				("generation count", lambda row: row["plannerRuntimeAudit"].__setitem__("authorityGenerations", 99)),
				("sequence", lambda row: row["plannerAuthorityGenerations"][1].__setitem__("sequence", 7)))
			for name, mutation in mutations:
				receipt = pinned_receipt(value)
				mutation(receipt)
				with self.subTest(name=name), self.assertRaises(ValueError):
					CAMPAIGN.validate_pinned_runtime_receipt(receipt, value, "a" * 64, "b" * 64)


class CompleteExecutionTest(unittest.TestCase):
	def _execute(self, root, contract, digest, bad_receipt):
		cell, source = CAMPAIGN.matrix()[0], "print(1);"
		xml = CAMPAIGN.config(cell, "runtime", contract)
		parsed = CAMPAIGN.timing(HELPERS.timing_log())
		receipt = HELPERS.valid_receipt(cell, "runtime", parsed)
		generic_audit = receipt["plannerRuntimeAudit"]
		pinned = pinned_receipt(contract, sha_bytes(source.encode()), sha_bytes(xml.encode()))
		receipt.update(pinned)
		receipt["plannerRuntimeAudit"] = {**generic_audit, **pinned["plannerRuntimeAudit"]}
		if bad_receipt:
			receipt["scriptSha256"] = "f" * 64
		node = SimpleNamespace(host="so007")
		spec = SimpleNamespace(coordinator=node, workers=(), container_name=lambda selected: "coordinator")
		plan = Plan("start", "campaign", (Command("run-coordinator", "so007",
			("ssh", "so007", "docker run --cpuset-cpus 0-3 --memory 24g image")),))
		base = SimpleNamespace(parse_manifest=lambda value: spec,
			prepare_remote_directories=lambda *args: None,
			prepare_cost_profile=mock.Mock(side_effect=AssertionError("profiler must be bypassed")),
			build_plan=lambda *args: plan, capture_network_snapshot=lambda *args: {},
			validate_network_quality=lambda *args: {"valid": True})
		cleanup = mock.Mock(return_value={"resolved": True})
		campaign = SimpleNamespace(bounded_pilot_lifecycle=lambda *args, **kwargs: {
			"container": {"cpuset_cpus": "0-3", "memory": "24g"}, "mounts": [],
			"coordinator": {"environment": {}}, "workers": []},
			remote_resource_preflight=lambda *args: {"passed": True},
			capture_remote_output_identity=mock.Mock(return_value={"out": {
				"sha256": "3" * 64, "metadata_sha256": "4" * 64}}),
			_strict_experiment_cleanup=cleanup)
		renderer = SimpleNamespace(render=lambda selected: {"source": source,
			"outputs": [{"path": "out", "format": "binary", "role": "result"}]})
		comparison = {"receipt": {"passed": True, "metrics": {"artifact_bindings": {"actual": {
			"result": {"campaign_sha256": "3" * 64, "metadata_sha256": "4" * 64}}}}}}
		def process(command, stdout, **kwargs):
			stdout.write(HELPERS.timing_log())
			return SimpleNamespace(returncode=0)
		def ssh(host, argv, **kwargs):
			return json.dumps(receipt) if "receipt.json" in " ".join(map(str, argv)) else ""
		manifest = {"remote_root": "/remote", "image": IMAGE, "identity": {
			"jar_sha256": "5" * 64, "pinned_runtime_contract_sha256": digest}}
		with mock.patch.object(CAMPAIGN, "run", return_value=""), mock.patch.object(CAMPAIGN, "ssh", side_effect=ssh), \
				mock.patch.object(CAMPAIGN.lifecycle, "execute_start"), \
				mock.patch.object(CAMPAIGN, "pinned_container_resources", return_value=[{"host": "so007"}]), \
				mock.patch.object(CAMPAIGN, "collect_node_evidence", return_value={
					"errors": [], "health_collection_errors": [], "log_collection_errors": []}), \
				mock.patch.object(CAMPAIGN.subprocess, "run", side_effect=process), \
				mock.patch.object(CAMPAIGN.runtime_compare, "compare", return_value=comparison) as compare, \
				mock.patch("sys.stdout", new=io.StringIO()):
			result = CAMPAIGN.execute_cell(root, manifest, cell, "runtime", SimpleNamespace(stage=Path("/stage")),
				campaign, base, renderer, reference_manifest={"reference": True})
		return result, base, cleanup, compare

	def test_complete_success_bypasses_profiler_compares_numerics_and_cleans_up_then_bad_receipt_fails(self):
		for bad in (False, True):
			with self.subTest(bad_receipt=bad), tempfile.TemporaryDirectory() as directory:
				root = Path(directory)
				path, contract, files = fixture(root)
				raw, _, digest = CAMPAIGN.read_pinned_runtime_contract(path)
				(root / CAMPAIGN.PINNED_RUNTIME_FILE).write_bytes(raw)
				frozen = root / CAMPAIGN.PINNED_SOURCE_DIRECTORY
				frozen.mkdir()
				for name, source in files.items():
					(frozen / name).write_bytes(source.read_bytes())
				result, base, cleanup, compare = self._execute(root, contract, digest, bad)
				base.prepare_cost_profile.assert_not_called()
				cleanup.assert_called_once()
				self.assertTrue(result["cleanup_resolved"])
				self.assertEqual(0.0, result["profiling_seconds"])
				if bad:
					self.assertEqual("failed", result["status"])
					self.assertTrue(any("executed input/cost binding mismatch" in error for error in result["errors"]))
					compare.assert_not_called()
				else:
					self.assertEqual("passed", result["status"])
					self.assertTrue(result["comparison"]["receipt"]["passed"])
					compare.assert_called_once()


if __name__ == "__main__":
	unittest.main()
