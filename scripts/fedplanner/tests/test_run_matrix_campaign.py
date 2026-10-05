#!/usr/bin/env python3
"""Local contract tests for the four-planner Docker campaign runner.

These tests deliberately exercise only pure helpers and mocked/local campaign
state.  They never start Java, Docker, SSH, or a workload.
"""

from __future__ import annotations

import importlib.util
import io
import fcntl
import json
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


RUNNER = Path(__file__).resolve().parents[1] / "run_matrix_campaign.py"
SPEC = importlib.util.spec_from_file_location("run_matrix_campaign", RUNNER)
CAMPAIGN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CAMPAIGN)


def candidate_phases(**overrides):
	values = {
		"commonPreparationNanos": 2,
		"analysisNanos": 3,
		"plannerSetupNanos": 5,
		"modelNanos": 7,
		"costSurfaceNanos": 11,
		"optimizerNanos": 13,
		"selectionNanos": 17,
		"otherPlanningNanos": 19,
		"diagnosticsNanos": 23,
		"conversionNanos": 29,
		"applicationNanos": 31,
		"finalVerificationNanos": 37,
		"registrationNanos": 41,
		"receiptHandoffNanos": 43,
	}
	values.update(overrides)
	values["totalNanos"] = sum(values.values())
	return values


def timing_log(phases=None, compile_seconds="0.000001"):
	phases = phases or candidate_phases()
	fields = " ".join(f"{key}={value}" for key, value in phases.items())
	return (
		"SystemDS Statistics:\n"
		f"Total compilation time:\t\t{compile_seconds} sec.\n"
		"CandidateE2EReceipt schema=candidate-e2e-v1 calls=1 "
		f"exactPhaseCalls=1 {fields}\n"
	)


def valid_receipt(cell, phase, parsed):
	compile_nanos = round(parsed["compile_seconds"] * 1e9)
	return {
		"status": "success",
		"actualPlannerCanonical": cell["planner_enum"],
		"actualCompileOnly": phase == "compile",
		"runtimeProgramConstructed": True,
		"workloadExecutionStarted": phase == "runtime",
		"workloadExecutionCompleted": phase == "runtime",
		"executionNanos": 0 if phase == "compile" else 1,
		"observedRunNanos": 0 if phase == "compile" else 1,
		"compileNanos": compile_nanos,
		"planningFullInitialNanos": max(1, compile_nanos - 1),
		"candidateE2E": dict(parsed["candidate_phases_ns"]),
		"runtimeAuditEnabled": True,
		"plannerRuntimeAudit": {
			"plannedPhysicalHops": 1,
			"loweredPhysicalHops": 1,
			"missingPhysicalHops": 0,
			"missingSynthetic": 0,
			"mismatches": 0,
			"runtimeInstructionKinds": 0 if phase == "compile" else 1,
			"federatedDispatchKinds": 0 if phase == "compile" else 1,
			"workerFragmentKinds": 0,
		},
	}


class MatrixContractTest(unittest.TestCase):
	def test_exact_canonical_896_cell_cartesian_product(self):
		rows = CAMPAIGN.matrix()
		self.assertEqual(896, len(rows))
		self.assertEqual(896, len({row["id"] for row in rows}))
		self.assertEqual(set(CAMPAIGN.PLANNERS), {row["planner"] for row in rows})
		self.assertEqual(set(CAMPAIGN.PROFILES), {row["profile"] for row in rows})
		self.assertEqual(set(CAMPAIGN.WORKERS), {row["workers"] for row in rows})
		self.assertEqual(set(CAMPAIGN.WORKLOADS), {
			(row["suite"], row["workload"]) for row in rows})
		for planner, enum in CAMPAIGN.ENUMS.items():
			self.assertEqual({enum}, {
				row["planner_enum"] for row in rows if row["planner"] == planner})

	def test_compile_order_is_dp_then_fedfirst_agglocal_exact(self):
		planners = [row["planner"] for row in CAMPAIGN.schedule("compile")]
		self.assertEqual(
			[planner for planner in CAMPAIGN.PLANNERS for _ in range(224)], planners)

	def test_runtime_order_is_logreg_then_l2svm_then_remaining_workloads(self):
		rows = CAMPAIGN.schedule("runtime")
		blocks = [rows[index:index + 64] for index in range(0, len(rows), 64)]
		self.assertEqual(list(CAMPAIGN.WORKLOADS), [
			(block[0]["suite"], block[0]["workload"]) for block in blocks])
		for expected, block in zip(CAMPAIGN.WORKLOADS, blocks):
			self.assertEqual(64, len(block))
			self.assertEqual({expected}, {
				(row["suite"], row["workload"]) for row in block})


class UnlimitedWorkloadContractTest(unittest.TestCase):
	def test_cli_has_no_compile_or_runtime_deadline_options(self):
		parse_args = CAMPAIGN.argparse.ArgumentParser.parse_args

		def inspect_args(parser, argv):
			args = parse_args(parser, argv)
			self.assertFalse(hasattr(args, "compile_timeout"))
			self.assertFalse(hasattr(args, "runtime_timeout"))
			return args

		for options in ([],):
			with self.subTest(options=options), \
					mock.patch.object(CAMPAIGN.argparse.ArgumentParser, "parse_args", inspect_args), \
					mock.patch.object(CAMPAIGN, "dependencies",
						side_effect=RuntimeError("REACHED_VALIDATED_ARGUMENTS")):
				with self.assertRaisesRegex(RuntimeError, "REACHED_VALIDATED_ARGUMENTS"):
					CAMPAIGN.main(["--root", "/tmp/not-used", *options])

	def test_cli_rejects_any_workload_timeout_before_external_work(self):
		for flag in ("--compile-timeout", "--runtime-timeout"):
			for value in ("0", "59", "60", "61", "900", "3600"):
				with self.subTest(flag=flag, value=value), \
						mock.patch.object(CAMPAIGN, "dependencies") as dependencies, \
						redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
					CAMPAIGN.main(["--root", "/tmp/not-used", flag, value])
				self.assertEqual(2, raised.exception.code)
				dependencies.assert_not_called()

	def test_policy_is_frozen_in_identity_and_measurement_and_checked_on_resume(self):
		with tempfile.TemporaryDirectory() as directory:
			repo = Path(directory)
			(repo / "src/main").mkdir(parents=True)
			(repo / "pom.xml").write_text("pom")
			probe = repo / "Probe.java"
			probe.write_text("probe")
			jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
			jar.parent.mkdir()
			jar.write_text("jar")
			root, stage = repo / "campaign", repo / "stage"
			with mock.patch.object(CAMPAIGN, "REPO", repo), \
					mock.patch.object(CAMPAIGN, "PROBE_SOURCE", probe), \
					mock.patch.object(CAMPAIGN, "sha", return_value="a" * 64), \
					mock.patch.object(CAMPAIGN, "run", return_value="mocked") as commands:
				manifest = CAMPAIGN.initialize(root, stage)
				commands.assert_any_call(["javac", "-encoding", "UTF-8", "-cp",
					f"{jar}:{repo}/target/lib/*", "-d", str(root / "overlay/probe/classes"),
					str(probe)], timeout=None)
				for section in ("identity", "measurement"):
					self.assertEqual({"compile": None, "runtime": None},
						manifest[section]["timeout_seconds"])
				self.assertEqual(manifest, CAMPAIGN.initialize(root, stage))
				for section in ("identity", "measurement"):
					for timeout in (None, {"compile": 900, "runtime": 60}):
						with self.subTest(section=section, timeout=timeout):
							altered = json.loads(json.dumps(manifest))
							if timeout is None:
								del altered[section]["timeout_seconds"]
							else:
								altered[section]["timeout_seconds"] = timeout
							CAMPAIGN.dump(root / "manifest.json", altered)
							with self.assertRaisesRegex(RuntimeError, "campaign .* changed"):
								CAMPAIGN.initialize(root, stage)

	def test_gate_requires_explicit_unlimited_attempt_provenance(self):
		for timeout in ("missing", 60, 900, 3600):
			passed = {row["id"]: {"status": "passed", "timeout_seconds": None}
				for row in CAMPAIGN.matrix()}
			row = passed[CAMPAIGN.matrix()[0]["id"]]
			if timeout == "missing":
				del row["timeout_seconds"]
			else:
				row["timeout_seconds"] = timeout
			with self.subTest(timeout=timeout), \
					mock.patch.object(CAMPAIGN, "latest", return_value=passed):
				self.assertFalse(CAMPAIGN.compile_gate(Path("unused")))

	def test_comparison_csv_records_timeout_without_fabricating_success_timing(self):
		cell = CAMPAIGN.matrix()[0]
		row = {"status": "failed", "timeout_seconds": 60, "attempt": "timeout"}
		with tempfile.TemporaryDirectory() as directory, \
				mock.patch.object(CAMPAIGN, "latest", side_effect=({cell["id"]: row}, {})), \
				mock.patch.object(CAMPAIGN, "schedule", return_value=[cell]), \
				mock.patch.object(CAMPAIGN, "compile_gate", return_value=False):
			root = Path(directory)
			CAMPAIGN.summarize(root)
			with (root / "compile-comparison.csv").open() as stream:
				exported = next(CAMPAIGN.csv.DictReader(stream))
			self.assertEqual("60", exported["timeout_seconds"])
			self.assertEqual("failed", exported["status"])
			self.assertEqual("", exported["compile_seconds"])

	def test_actual_commands_have_no_deadline_and_failure_still_runs_exact_cleanup(self):
		for phase in ("compile", "runtime"):
			with self.subTest(phase=phase), tempfile.TemporaryDirectory() as directory:
				root = Path(directory)
				node = SimpleNamespace(host="so007")
				spec = SimpleNamespace(coordinator=node, workers=[],
					container_name=lambda selected: "test-coordinator")
				base = SimpleNamespace(parse_manifest=lambda value: spec,
					prepare_remote_directories=lambda *args: None,
					build_plan=lambda *args: None,
					capture_network_snapshot=lambda *args: {},
					validate_network_quality=lambda *args: {"valid": True})
				cleanup = mock.Mock(return_value={"resolved": True})
				campaign = SimpleNamespace(
					bounded_pilot_lifecycle=lambda *args, **kwargs: {
						"mounts": [], "coordinator": {"environment": {}}, "workers": []},
					remote_resource_preflight=lambda *args: {"passed": True},
					_strict_experiment_cleanup=cleanup)
				renderer = SimpleNamespace(render=lambda cell: {"source": "print(1);"})
				# Stale caller fields cannot accidentally revive any finite budget.
				args = SimpleNamespace(stage=Path("/stage"), compile_timeout=900,
					runtime_timeout=3600)
				with mock.patch.object(CAMPAIGN, "run", return_value=""), \
						mock.patch.object(CAMPAIGN, "ssh", side_effect=lambda host, argv, **kw:
							"null" if "receipt.json" in " ".join(map(str, argv)) else ""), \
						mock.patch.object(CAMPAIGN.lifecycle, "execute_start"), \
						mock.patch.object(CAMPAIGN, "capture_container_health", return_value={
							"state": {"OOMKilled": False}, "oom_events": []}), \
						mock.patch.object(CAMPAIGN.subprocess, "run",
							return_value=SimpleNamespace(returncode=1)) as process, \
						mock.patch("sys.stdout", new=io.StringIO()):
					result = CAMPAIGN.execute_cell(root, {
						"remote_root": "/remote", "identity": {"jar_sha256": "a" * 64}},
						CAMPAIGN.matrix()[0], phase, args, campaign, base, renderer)
				self.assertIsNone(result["timeout_seconds"])
				self.assertEqual(1, result["returncode"])
				self.assertEqual("failed", result["status"])
				self.assertNotIn("compile_seconds", result)
				self.assertTrue(result["cleanup_resolved"])
				cleanup.assert_called_once_with(base, spec)
				command = CAMPAIGN.shlex.split(process.call_args.args[0][-1])
				self.assertEqual(["docker", "exec", "test-coordinator", "java"], command[:4])
				self.assertNotIn("timeout", command)
				self.assertNotIn("--kill-after=30s", command)
				self.assertIsNone(process.call_args.kwargs.get("timeout"))

	def test_federated_request_read_deadline_is_disabled_for_both_phases(self):
		for phase in ("compile", "runtime"):
			xml = CAMPAIGN.ET.fromstring(CAMPAIGN.config(CAMPAIGN.matrix()[0], phase))
			self.assertEqual("-1", xml.findtext("sysds.federated.timeout"))


class TimingContractTest(unittest.TestCase):
	def test_exclusive_phases_reconcile_and_searchspace_includes_common_preparation(self):
		phases = candidate_phases()
		result = CAMPAIGN.timing(timing_log(phases))
		self.assertEqual(phases, result["candidate_phases_ns"])
		self.assertEqual(2e-9, result["common_preparation_seconds"])
		self.assertEqual(3e-9, result["analysis_seconds"])
		self.assertEqual(5e-9, result["searchspace_seconds"])
		self.assertEqual((phases["totalNanos"] - 5) / 1e9,
			result["planning_after_analysis_seconds"])

	def test_timing_rejects_nonexclusive_or_duplicate_receipts(self):
		phases = candidate_phases()
		phases["totalNanos"] += 1
		with self.assertRaisesRegex(ValueError, "do not reconcile"):
			CAMPAIGN.timing(timing_log(phases))
		valid = timing_log()
		with self.assertRaisesRegex(ValueError, "observed 2"):
			CAMPAIGN.timing(valid + valid)

	def test_probe_receipt_must_reconcile_planner_mode_timing_and_audit(self):
		parsed = CAMPAIGN.timing(timing_log())
		cell = CAMPAIGN.matrix()[0]
		receipt = valid_receipt(cell, "compile", parsed)
		CAMPAIGN.validate_probe_receipt(receipt, cell, "compile", parsed)

		mutations = (
			("actualPlannerCanonical", "COMPILE_EXACT"),
			("executionNanos", 1),
			("runtimeAuditEnabled", False),
		)
		for key, value in mutations:
			with self.subTest(key=key):
				bad = {**receipt, key: value}
				with self.assertRaises(ValueError):
					CAMPAIGN.validate_probe_receipt(bad, cell, "compile", parsed)

		bad_audit = {**receipt, "plannerRuntimeAudit": {
			**receipt["plannerRuntimeAudit"], "mismatches": 1}}
		with self.assertRaises(ValueError):
			CAMPAIGN.validate_probe_receipt(bad_audit, cell, "compile", parsed)

	def test_probe_receipt_rejects_candidate_and_rounded_compile_disagreement(self):
		parsed = CAMPAIGN.timing(timing_log())
		cell = CAMPAIGN.matrix()[0]
		receipt = valid_receipt(cell, "compile", parsed)
		bad_candidate = {**receipt, "candidateE2E": {
			**receipt["candidateE2E"], "analysisNanos": 999}}
		with self.assertRaisesRegex(ValueError, "candidate timings differ"):
			CAMPAIGN.validate_probe_receipt(bad_candidate, cell, "compile", parsed)
		bad_compile = {**receipt, "compileNanos": receipt["compileNanos"] + 502}
		with self.assertRaisesRegex(ValueError, "total compile timings differ"):
			CAMPAIGN.validate_probe_receipt(bad_compile, cell, "compile", parsed)


class GateAndResumeContractTest(unittest.TestCase):
	def _run_mocked_p2_handoff(self, prepare):
		cell = next(row for row in CAMPAIGN.schedule("runtime")
			if (row["suite"], row["workload"]) == ("p2", "P2_PREP"))
		events = []
		acquisitions = iter((["initial-lease"], ["reacquired-lease"]))

		def acquire(hosts, stage):
			leases = next(acquisitions)
			events.append(("acquire", tuple(leases)))
			return leases

		def release(leases):
			events.append(("release", tuple(leases)))
			return {"released": True}

		def verify_stage(hosts, stage):
			events.append(("verify-stage", len([event for event in events
				if event[0] == "verify-stage"]) + 1))
			return {host: "verified" for host in hosts}

		campaign = SimpleNamespace(
			acquire_remote_stage_leases=acquire,
			release_remote_stage_leases=release,
			remote_stage_leases_alive=lambda leases: bool(leases),
			verify_remote_image_content=lambda hosts, image: {
				host: "verified" for host in hosts},
			verify_remote_bounded_stage=verify_stage,
		)
		renderer = SimpleNamespace(Renderer=lambda stage: object())
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory).resolve()
			lane = root / "runtime.lock"
			lane.touch()
			base = SimpleNamespace(RUNTIME_LANE=lane)

			def execute(*args):
				events.append(("execute", args[-1]))
				return {"status": "passed", "cleanup_resolved": True}

			def prepare_bound(root_arg, stage, selected, gate, *, direct_runtime=False):
				self.assertFalse(direct_runtime)
				with lane.open("a+") as contender:
					with self.assertRaises(BlockingIOError):
						fcntl.flock(contender, fcntl.LOCK_EX | fcntl.LOCK_NB)
				events.append(("physical-lane-held", True))
				events.append(("prepare", gate))
				return prepare(root, selected, gate)

			patches = (
				mock.patch.object(CAMPAIGN, "dependencies",
					return_value=(campaign, base, renderer)),
				mock.patch.object(CAMPAIGN, "initialize", return_value={}),
				mock.patch.object(CAMPAIGN, "compile_gate", return_value=True),
				mock.patch.object(CAMPAIGN, "publish_overlay", return_value="VERIFIED"),
				mock.patch.object(CAMPAIGN, "latest", return_value={}),
				mock.patch.object(CAMPAIGN, "schedule", return_value=[cell]),
				mock.patch.object(CAMPAIGN, "execute_cell", side_effect=execute),
				mock.patch.object(CAMPAIGN.runtime_compare, "pin_reference",
					return_value={}),
				mock.patch.object(CAMPAIGN.runtime_compare,
					"prepare_workload_reference", side_effect=prepare_bound),
				mock.patch.object(CAMPAIGN, "summarize", return_value={
					"compile_gate": True,
					"compile": {"passed": 896, "failed": 0, "pending": 0},
					"runtime": {"passed": 0, "failed": 0, "pending": 896},
				}),
			)
			with patches[0], patches[1], patches[2], patches[3], patches[4], \
					patches[5], patches[6], patches[7], patches[8], patches[9]:
				try:
					outcome = CAMPAIGN.main(["--root", str(root), "--phase", "runtime",
						"--max-cells", "1"])
				except Exception as error:
					error.handoff_events = events
					raise
		return outcome, events

	def test_compile_gate_requires_every_latest_cell_to_pass(self):
		passed = {row["id"]: {"status": "passed", "timeout_seconds": None}
			for row in CAMPAIGN.matrix()}
		with mock.patch.object(CAMPAIGN, "latest", return_value=passed):
			self.assertTrue(CAMPAIGN.compile_gate(Path("unused")))
		failed = dict(passed)
		failed[CAMPAIGN.matrix()[0]["id"]] = {"status": "failed"}
		with mock.patch.object(CAMPAIGN, "latest", return_value=failed):
			self.assertFalse(CAMPAIGN.compile_gate(Path("unused")))

	def test_latest_attempt_wins_without_erasing_failed_evidence(self):
		cell = CAMPAIGN.matrix()[0]
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			for attempt, status in (("0001-first", "failed"), ("0002-second", "passed")):
				CAMPAIGN.dump(root / "attempts/compile" / attempt / "result.json", {
					"cell": cell, "status": status, "attempt": attempt})
			self.assertEqual("passed", CAMPAIGN.latest(root, "compile")[cell["id"]]["status"])
			self.assertTrue((root / "attempts/compile/0001-first/result.json").is_file())

	def test_runtime_filters_are_rejected_before_any_external_dependency(self):
		with mock.patch.object(CAMPAIGN, "dependencies") as dependencies:
			with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
				CAMPAIGN.main(["--root", "/tmp/not-used", "--phase", "runtime",
					"--planner", "FedFirst"])
		dependencies.assert_not_called()

	def test_resume_refuses_to_skip_a_failed_cell_without_explicit_retry(self):
		cell = CAMPAIGN.schedule("compile")[0]
		campaign = SimpleNamespace(
			acquire_remote_stage_leases=lambda hosts, stage: [],
			verify_remote_image_content=lambda hosts, image: {host: "ok" for host in hosts},
			verify_remote_bounded_stage=lambda hosts, stage: {host: "ok" for host in hosts},
		)
		renderer = SimpleNamespace(Renderer=lambda stage: object())
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory).resolve()
			lane = root / "runtime.lock"
			lane.touch()
			base = SimpleNamespace(RUNTIME_LANE=lane)
			with mock.patch.object(CAMPAIGN, "dependencies",
					return_value=(campaign, base, renderer)), \
					mock.patch.object(CAMPAIGN, "initialize", return_value={}), \
					mock.patch.object(CAMPAIGN, "publish_overlay", return_value="VERIFIED"), \
					mock.patch.object(CAMPAIGN, "latest",
						return_value={cell["id"]: {"cell": cell, "status": "failed"}}), \
					mock.patch.object(CAMPAIGN, "summarize", return_value={
						"compile_gate": False,
						"compile": {"passed": 0, "failed": 1, "pending": 895},
						"runtime": {"passed": 0, "failed": 0, "pending": 896},
					}):
				with self.assertRaisesRegex(RuntimeError,
						"previous failed cell requires diagnosis and --retry-failed"):
					CAMPAIGN.main(["--root", str(root), "--phase", "compile"])

	def test_p2_reference_handoff_releases_then_reacquires_and_rehashes_before_runtime(self):
		def prepare(root, cell, gate):
			self.assertTrue(gate)
			return {"result_manifest": {
				"local_path": str(root / "reference-p2ref1/result-manifest.json")}}

		outcome, events = self._run_mocked_p2_handoff(prepare)
		self.assertEqual(0, outcome)
		labels = [event[0] for event in events]
		self.assertEqual([
			"acquire", "verify-stage", "release", "physical-lane-held", "prepare", "acquire",
			"verify-stage", "execute", "release"], labels)
		self.assertEqual(("initial-lease",), events[2][1])
		self.assertEqual(("reacquired-lease",), events[-1][1])
		self.assertTrue(str(events[7][1]).endswith(
			"reference-p2ref1/result-manifest.json"))

	def test_p2_reference_failure_still_reacquires_rehashes_and_releases_stage(self):
		def fail_prepare(root, cell, gate):
			self.assertTrue(gate)
			raise RuntimeError("reference generation failed")

		with self.assertRaisesRegex(RuntimeError, "reference generation failed") as raised:
			self._run_mocked_p2_handoff(fail_prepare)
		self.assertEqual([
			"acquire", "verify-stage", "release", "physical-lane-held", "prepare", "acquire",
			"verify-stage", "release"],
			[event[0] for event in raised.exception.handoff_events])


class LifecycleIntegrationTest(unittest.TestCase):
	def test_health_captures_exec_child_oom_when_init_state_is_not_oomkilled(self):
		node = SimpleNamespace(host="so007")
		spec = SimpleNamespace(container_name=lambda selected: "matrix-coordinator")
		container_id = "a" * 64
		record = [{
			"Id": container_id,
			"State": {"Running": True, "OOMKilled": False,
				"StartedAt": "2026-09-28T12:00:00.000000000Z"},
			"HostConfig": {"Memory": 24 * 1024**3},
		}]
		non_oom = {"Action": "exec_start", "id": container_id, "Type": "container"}
		oom = {"Action": "oom", "id": container_id, "Type": "container"}

		def ssh(host, argv, **kwargs):
			self.assertEqual("so007", host)
			if argv[:2] == ["docker", "inspect"]:
				return json.dumps(record)
			if argv[:2] == ["date", "-u"]:
				return "2026-09-28T12:01:00.000000000Z\n"
			if argv[:2] == ["docker", "events"]:
				self.assertNotIn("event=oom", argv)
				self.assertIn("container=" + container_id, argv)
				return json.dumps(non_oom) + "\n" + json.dumps(oom) + "\n"
			if argv[:2] == ["docker", "exec"]:
				return ("FILE=/sys/fs/cgroup/memory.events\noom_kill 1\n"
					"FILE=/sys/fs/cgroup/memory.current\n1234\n")
			raise AssertionError(argv)

		with mock.patch.object(CAMPAIGN, "ssh", side_effect=ssh):
			health = CAMPAIGN.capture_container_health(spec, node)

		self.assertFalse(health["state"]["OOMKilled"])
		self.assertEqual([non_oom, oom], health["events"])
		self.assertEqual([oom], health["oom_events"])
		self.assertEqual(24 * 1024**3, health["memory_limit"])
		self.assertIn("oom_kill 1", health["memory_cgroup_raw"])
		self.assertIn("memory.current", health["memory_cgroup_raw"])

	def test_worker_start_failure_drains_launched_peers_before_exact_cleanup(self):
		worker_two_finished = threading.Event()
		started = []
		cleanup_observations = []

		def command(label):
			return SimpleNamespace(label=label, argv=[label])

		labels = ["run-worker-1", "run-worker-2", "run-coordinator",
			"tc-coordinator", "tc-worker-1", "tc-worker-2",
			"tc-verify-coordinator", "tc-verify-worker-1", "tc-verify-worker-2",
			"readiness-sweep"]
		plan = SimpleNamespace(action="start",
			commands=tuple(command(label) for label in labels))
		coordinator = SimpleNamespace(host="so007")
		workers = (SimpleNamespace(host="so002", port=8001),
			SimpleNamespace(host="so003", port=8002))
		spec = SimpleNamespace(coordinator=coordinator, workers=workers,
			container_name=lambda node: "container-" + node.host)
		base = SimpleNamespace(
			parse_manifest=lambda life: spec,
			prepare_remote_directories=lambda spec, remote: None,
			build_plan=lambda spec, action: plan,
			capture_network_snapshot=lambda spec: {"captured": True},
		)
		life = {"mounts": [], "coordinator": {"environment": {}}, "workers": [
			{"host": "so002", "port": 8001, "environment": {}},
			{"host": "so003", "port": 8002, "environment": {}},
		]}

		def cleanup(base_arg, spec_arg):
			cleanup_observations.append(worker_two_finished.is_set())
			return {"resolved": True}

		campaign = SimpleNamespace(
			bounded_pilot_lifecycle=lambda *args, **kwargs: life,
			remote_resource_preflight=lambda *args: {"passed": True},
			_strict_experiment_cleanup=cleanup,
		)
		renderer = SimpleNamespace(render=lambda cell: {"source": "print(1);", "outputs": []})
		cell = CAMPAIGN.matrix()[0]
		args = SimpleNamespace(stage=Path("/stage"), compile_timeout=10,
			runtime_timeout=10)

		def run(argv, **kwargs):
			if argv[0] == "rsync":
				return ""
			label = argv[0]
			started.append(label)
			if label == "run-worker-1":
				raise RuntimeError("injected worker start failure")
			if label == "run-worker-2":
				time.sleep(0.05)
				worker_two_finished.set()
			return "started"

		with tempfile.TemporaryDirectory() as directory, \
				mock.patch.object(CAMPAIGN, "run", side_effect=run), \
				mock.patch.object(CAMPAIGN, "ssh", return_value=""):
			root = Path(directory)
			with mock.patch("sys.stdout", new=io.StringIO()):
				result = CAMPAIGN.execute_cell(root, {
					"remote_root": "/remote/campaign",
					"identity": {"jar_sha256": "a" * 64},
				}, cell, "compile", args, campaign, base, renderer)

		self.assertEqual("failed", result["status"])
		self.assertTrue(result["cleanup_resolved"])
		self.assertTrue(worker_two_finished.is_set())
		self.assertEqual([True], cleanup_observations)
		self.assertEqual({"run-worker-1", "run-worker-2"}, set(started))
		self.assertNotIn("run-coordinator", started)
		self.assertIn("lifecycle stage run-workers failed", result["errors"][0])


if __name__ == "__main__":
	unittest.main()
