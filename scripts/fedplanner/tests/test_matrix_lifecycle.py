"""Pure-mock tests for barrier-preserving matrix lifecycle concurrency."""

from dataclasses import dataclass
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import matrix_lifecycle as lifecycle


@dataclass(frozen=True)
class Command:
	label: str


def start_plan(workers=3):
	labels = [f"run-worker-{number}" for number in range(1, workers + 1)]
	labels += ["run-coordinator", "tc-coordinator"]
	labels += [f"tc-worker-{number}" for number in range(1, workers + 1)]
	labels += ["tc-verify-coordinator"]
	labels += [f"tc-verify-worker-{number}" for number in range(1, workers + 1)]
	labels += ["readiness-sweep"]
	return SimpleNamespace(action="start", commands=tuple(Command(label) for label in labels))


class ExecuteStartTest(unittest.TestCase):
	def test_preserves_all_barriers_while_parallelizing_independent_commands(self):
		lock = threading.Lock()
		completed = set()
		workers = {f"run-worker-{number}" for number in range(1, 4)}
		tc = {"tc-coordinator", *(f"tc-worker-{number}" for number in range(1, 4))}
		verify = {"tc-verify-coordinator",
			*(f"tc-verify-worker-{number}" for number in range(1, 4))}

		def runner(command):
			with lock:
				if command.label == "run-coordinator":
					self.assertTrue(workers <= completed)
				elif command.label in tc:
					self.assertIn("run-coordinator", completed)
				elif command.label in verify:
					self.assertTrue(tc <= completed)
				elif command.label == "readiness-sweep":
					self.assertTrue(verify <= completed)
			time.sleep(0.005)
			with lock:
				completed.add(command.label)
			return command.label

		result = lifecycle.execute_start(start_plan(), runner)

		self.assertEqual(len(start_plan().commands), len(result))
		self.assertEqual({command.label for command in start_plan().commands}, completed)

	def test_failure_waits_for_launched_peers_and_does_not_start_next_stage(self):
		worker_two_finished = threading.Event()
		started = []

		def runner(command):
			started.append(command.label)
			if command.label == "run-worker-1":
				raise RuntimeError("injected failure")
			if command.label == "run-worker-2":
				time.sleep(0.05)
				worker_two_finished.set()

		with self.assertRaisesRegex(lifecycle.LifecycleStageError, "run-workers"):
			lifecycle.execute_start(start_plan(workers=2), runner)

		self.assertTrue(worker_two_finished.is_set())
		self.assertEqual({"run-worker-1", "run-worker-2"}, set(started))
		self.assertNotIn("run-coordinator", started)

	def test_nonzero_returncode_is_a_stage_failure(self):
		started = []

		def runner(command):
			started.append(command.label)
			return SimpleNamespace(returncode=7 if command.label == "run-worker-1" else 0)

		with self.assertRaisesRegex(lifecycle.LifecycleStageError, "non-zero status 7"):
			lifecycle.execute_start(start_plan(workers=2), runner)
		self.assertEqual(["run-worker-1", "run-worker-2"], started)

	def test_invalid_plans_fail_before_any_command_runs(self):
		valid = start_plan(workers=2)
		invalid_plans = (
			SimpleNamespace(action="cleanup", commands=valid.commands),
			SimpleNamespace(action="start", commands=valid.commands + (valid.commands[0],)),
			SimpleNamespace(action="start", commands=valid.commands[:-1]),
			SimpleNamespace(action="start", commands=valid.commands + (Command("surprise"),)),
		)
		for plan in invalid_plans:
			with self.subTest(labels=[command.label for command in plan.commands]):
				calls = []
				with self.assertRaises(lifecycle.InvalidStartPlan):
					lifecycle.execute_start(plan, calls.append)
				self.assertEqual([], calls)


class PrepareNodesTest(unittest.TestCase):
	def test_runs_bounded_node_preparation_and_preserves_result_order(self):
		lock = threading.Lock()
		active = 0
		peak = 0

		def prepare(node):
			nonlocal active, peak
			with lock:
				active += 1
				peak = max(peak, active)
			time.sleep(0.01)
			with lock:
				active -= 1
			return node * 2

		self.assertEqual((0, 2, 4, 6), lifecycle.prepare_nodes(range(4), prepare, max_workers=2))
		self.assertLessEqual(peak, 2)
		self.assertGreaterEqual(peak, 2)


if __name__ == "__main__":
	unittest.main()
