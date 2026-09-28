"""Barrier-preserving parallel execution for matrix Docker lifecycle setup.

Only independent commands inside one lifecycle stage run concurrently.  The
stage order remains identical to ``multihost_docker.execute_plan``: every
worker is created before the coordinator, traffic control is applied only
after that, traffic-control verification follows application, and readiness
is checked last.  Thus this changes setup wall time, not the fresh-container,
fresh-JVM, worker-isolation, resource, or network-shaping measurement contract.
"""

from concurrent.futures import ThreadPoolExecutor, wait
import re
from typing import Any, Callable, Iterable, Sequence


_RUN_WORKER = re.compile(r"run-worker-([1-9][0-9]*)\Z")


class InvalidStartPlan(ValueError):
	"""The supplied plan cannot be executed with the start-stage contract."""


class LifecycleStageError(RuntimeError):
	"""At least one command in a fully-drained lifecycle stage failed."""

	def __init__(self, stage: str, failures: Sequence[tuple[str, Exception]]):
		self.stage = stage
		self.failures = tuple(failures)
		detail = "; ".join(f"{label}: {error}" for label, error in failures)
		super().__init__(f"lifecycle stage {stage} failed ({detail})")


def _label(item: Any) -> str:
	label = getattr(item, "label", None)
	if not isinstance(label, str) or not label:
		raise InvalidStartPlan("every start command must have a non-empty label")
	return label


def _validate_start_plan(plan: Any) -> tuple[tuple[str, tuple[Any, ...]], ...]:
	if getattr(plan, "action", None) != "start":
		raise InvalidStartPlan("execute_start requires a start command plan")
	try:
		commands = tuple(plan.commands)
	except (AttributeError, TypeError) as error:
		raise InvalidStartPlan("start plan must expose an iterable commands field") from error

	labels = tuple(_label(command) for command in commands)
	if len(labels) != len(set(labels)):
		raise InvalidStartPlan("start plan contains duplicate command labels")

	worker_labels = tuple(label for label in labels if _RUN_WORKER.fullmatch(label))
	if not worker_labels:
		raise InvalidStartPlan("start plan has no worker run commands")
	worker_numbers = tuple(int(_RUN_WORKER.fullmatch(label).group(1)) for label in worker_labels)
	if worker_numbers != tuple(range(1, len(worker_numbers) + 1)):
		raise InvalidStartPlan("worker run labels must be ordered and contiguous from 1")

	expected = worker_labels + ("run-coordinator",)
	expected += ("tc-coordinator",) + tuple(f"tc-worker-{number}" for number in worker_numbers)
	expected += ("tc-verify-coordinator",) + tuple(
		f"tc-verify-worker-{number}" for number in worker_numbers
	)
	expected += ("readiness-sweep",)
	if labels != expected:
		missing = tuple(label for label in expected if label not in labels)
		unknown = tuple(label for label in labels if label not in expected)
		raise InvalidStartPlan(
			"non-canonical start plan"
			+ (f"; missing={missing}" if missing else "")
			+ (f"; unknown={unknown}" if unknown else "")
			+ ("; command order is invalid" if not missing and not unknown else "")
		)

	by_label = dict(zip(labels, commands))
	return (
		("run-workers", tuple(by_label[label] for label in worker_labels)),
		("run-coordinator", (by_label["run-coordinator"],)),
		("traffic-control", tuple(
			by_label[label] for label in expected if label.startswith("tc-")
			and not label.startswith("tc-verify-")
		)),
		("traffic-control-verification", tuple(
			by_label[label] for label in expected if label.startswith("tc-verify-")
		)),
		("readiness", (by_label["readiness-sweep"],)),
	)


def _checked_call(function: Callable[[Any], Any], item: Any) -> Any:
	result = function(item)
	returncode = getattr(result, "returncode", 0)
	if returncode != 0:
		raise RuntimeError(f"command returned non-zero status {returncode}")
	return result


def _run_parallel(stage: str, items: Sequence[Any], function: Callable[[Any], Any],
		max_workers: int) -> tuple[Any, ...]:
	workers = min(max_workers, len(items))
	with ThreadPoolExecutor(max_workers=workers, thread_name_prefix=f"matrix-{stage}") as executor:
		futures = tuple(executor.submit(_checked_call, function, item) for item in items)
		# Drain all launched work before inspecting failures.  Exact cleanup can
		# therefore begin only after no setup command remains in flight.
		wait(futures)
		results = []
		failures = []
		for item, future in zip(items, futures):
			try:
				results.append(future.result())
			except Exception as error:  # retain every launched task's failure
				failures.append((_label(item), error))
		if failures:
			raise LifecycleStageError(stage, failures) from failures[0][1]
		return tuple(results)


def execute_start(plan: Any, runner_callable: Callable[[Any], Any], *,
		max_workers: int = 8) -> tuple[Any, ...]:
	"""Execute a validated start plan with strict inter-stage barriers.

	Validation completes before the first command is launched.  If any command
	fails, all other commands already launched in that stage are awaited and no
	later stage starts.
	"""
	if not isinstance(max_workers, int) or isinstance(max_workers, bool) or max_workers < 1:
		raise ValueError("max_workers must be a positive integer")
	stages = _validate_start_plan(plan)
	results = []
	for stage, commands in stages:
		results.extend(_run_parallel(stage, commands, runner_callable, max_workers))
	return tuple(results)


def prepare_nodes(nodes: Iterable[Any], prepare_callable: Callable[[Any], Any], *,
		max_workers: int = 8) -> tuple[Any, ...]:
	"""Run an untimed, bounded preparation action independently on each node."""
	if not isinstance(max_workers, int) or isinstance(max_workers, bool) or max_workers < 1:
		raise ValueError("max_workers must be a positive integer")
	items = tuple(nodes)
	if not items:
		return ()
	# Node preparation has no lifecycle labels, so use a direct bounded pool and
	# still drain all launched actions before surfacing a failure.
	with ThreadPoolExecutor(max_workers=min(max_workers, len(items)),
			thread_name_prefix="matrix-prepare") as executor:
		futures = tuple(executor.submit(prepare_callable, node) for node in items)
		wait(futures)
		results = []
		failures = []
		for index, future in enumerate(futures):
			try:
				results.append(future.result())
			except Exception as error:
				failures.append((f"node-{index}", error))
		if failures:
			raise LifecycleStageError("prepare-nodes", failures) from failures[0][1]
		return tuple(results)
