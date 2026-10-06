#!/usr/bin/env python3
"""Bounded two-worker runtime validation for the DP-LocalConflict planner."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time


PINNED_IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"
PROBE = "org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.CostRuntimeDockerProbe"
DEFAULT_ARTIFACT_ROOT = Path("/home/mchoi/fedplanner-remaining-20261006/runtime")
DEFAULT_COST_ENV = Path("/home/mchoi/function-boundary-ablation-20261006/cost-environment.json")


def sha256(path: Path) -> str:
	return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_args() -> argparse.Namespace:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
	parser.add_argument("--cost-environment", type=Path, default=DEFAULT_COST_ENV)
	parser.add_argument("--dry-run", action="store_true")
	return parser.parse_args()


def write_inputs(run: Path) -> dict[str, float]:
	parts = {
		"X1.csv": "1,2,1\n2,3,2\n3,4,1\n4,5,2\n",
		"X2.csv": "5,6,1\n6,7,2\n7,8,1\n8,9,2\n",
		"P1.csv": "1,2\n2,3\n3,4\n4,5\n",
		"P2.csv": "5,6\n6,7\n7,8\n8,9\n",
		"Q1.csv": "1\n2\n3\n4\n5\n6\n7\n8\n",
		"Q2.csv": "2,1\n3,2\n4,1\n5,2\n6,1\n7,2\n8,1\n9,2\n",
	}
	private_metadata = {"data_type": "matrix", "value_type": "double", "rows": 4, "cols": 3,
		"nnz": 12, "format": "csv", "header": False, "sep": ",", "privacy": "private-aggregate"}
	public_metadata = {**private_metadata, "cols": 2, "nnz": 8, "privacy": "public"}
	for name, contents in parts.items():
		(run / name).write_text(contents, encoding="utf-8")
		if name.startswith("X"):
			metadata = private_metadata
		elif name.startswith("Q"):
			metadata = {**private_metadata, "rows": 8, "cols": 1 if name == "Q1.csv" else 2,
				"nnz": 8 if name == "Q1.csv" else 16}
		else:
			metadata = public_metadata
		(run / f"{name}.mtd").write_text(json.dumps(metadata) + "\n", encoding="utf-8")
	source = (
		'X=federated(addresses=list("localhost:13000//evidence/X1.csv",'
		'"localhost:13001//evidence/X2.csv"),ranges=list(list(0,0),list(4,3),list(4,0),list(8,3)));\n'
		'P=federated(addresses=list("localhost:13000//evidence/P1.csv",'
		'"localhost:13001//evidence/P2.csv"),ranges=list(list(0,0),list(4,2),list(4,0),list(8,2)));\n'
		"A=X[,1:2];\n")
	column_source = (
		'Q=federated(addresses=list("localhost:13000//evidence/Q1.csv",'
		'"localhost:13001//evidence/Q2.csv"),ranges=list(list(0,0),list(8,1),list(0,1),list(8,3)));\n')
	branch_function = ("branchExit=function(matrix[double] Y,boolean flag)return(matrix[double] R){"
		"if(flag){Y=Y+1;}k=1;while(k<2){k=k+1;}R=Y;}\n")
	workloads = {
		"aggregate": source + 'out=sum(A)+var(A[,1])+cov(P[,1],P[,2]);print("FEDCOST_NUMERIC="+out);\n',
		"shape": source + "C=cumsum(P);WC=matrix(seq(1,16),rows=8,cols=2,byrow=TRUE);"
			"T=ctable(P[,1],P[,1]);WT=matrix(seq(1,64),rows=8,cols=8,byrow=TRUE);"
			"R=matrix(P,rows=4,cols=4,byrow=TRUE);WR=matrix(seq(1,16),rows=4,cols=4,byrow=TRUE);"
			'out=sum(A)+sum(C*WC)+sum(T*WT)+sum(R*WR);print("FEDCOST_NUMERIC="+out);\n',
		"linear": source + "G=t(P)%*%P;WG=matrix(seq(1,4),rows=2,cols=2,byrow=TRUE);"
			"q=matrix(1,rows=2,cols=1);m=t(A)%*%(A%*%q);"
			'out=sum(A)+sum(G*WG)+sum(m);print("FEDCOST_NUMERIC="+out);\n',
		"control": "guarded=function(matrix[double] M,boolean flag)return(double out){"
			"if(flag){B=M+1;}else{B=M-1;}i=1;while(i<=2){B=B+1;i=i+1;}out=sum(B);}\n" + source
			+ 'out=sum(A)+guarded(P,TRUE)+guarded(P,FALSE);print("FEDCOST_NUMERIC="+out);\n',
		"branch_true": branch_function + column_source
			+ 'Y=colSums(Q);flag=sum(Y)>0;R=branchExit(Y,flag);print(toString(R));'
			+ 'print("FEDCOST_NUMERIC="+sum(R));\n',
		"branch_false": branch_function + column_source
			+ 'Y=colSums(Q);flag=sum(Y)<0;R=branchExit(Y,flag);print(toString(R));'
			+ 'print("FEDCOST_NUMERIC="+sum(R));\n',
	}
	expected = {"aggregate": 92.0, "shape": 4480.0, "linear": 3588.0, "control": 304.0,
		"branch_true": 95.0, "branch_false": 92.0}
	for name, script in workloads.items():
		(run / f"{name}.dml").write_text(script, encoding="utf-8")
	(run / "cost.xml").write_text(
		"<root><sysds.federated.planner>compile_cost_based</sysds.federated.planner>"
		"<sysds.native.blas>none</sysds.native.blas><sysds.codegen.enabled>false</sysds.codegen.enabled>"
		"<sysds.localtmpdir>/tmp/systemds</sysds.localtmpdir>"
		"<sysds.scratch>/tmp/scratch</sysds.scratch></root>\n", encoding="utf-8")
	return expected


def run_script(workloads: list[str]) -> str:
	commands = [
		"set -euo pipefail", "cd /evidence", "mkdir -p /tmp/fedcost-home /tmp/systemds /tmp/scratch",
		"workload_failures=0",
		"export JDK_JAVA_OPTIONS='--add-modules=jdk.incubator.vector --add-opens=java.base/java.nio=ALL-UNNAMED "
		"--add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED "
		"--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.ref=ALL-UNNAMED "
		"--add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED'",
		"CP='/probe:/engine/systemds-3.4.0-SNAPSHOT.jar:/engine/lib/*'",
		"java -Xmx768m -cp \"$CP\" org.apache.sysds.api.DMLScript -w 13000 >worker-13000.log 2>&1 &",
		"worker1=$!",
		"java -Xmx768m -cp \"$CP\" org.apache.sysds.api.DMLScript -w 13001 >worker-13001.log 2>&1 &",
		"worker2=$!",
		"trap 'kill \"$worker1\" \"$worker2\" 2>/dev/null || true; wait \"$worker1\" \"$worker2\" 2>/dev/null || true' EXIT",
		"python3 - <<'PY'\nimport socket,time\nfor port in (13000,13001):\n for attempt in range(120):\n  try:\n   sock=socket.create_connection(('localhost',port),0.5);sock.close();break\n  except OSError: time.sleep(0.5)\n else: raise SystemExit(f'worker {port} did not start')\nPY",
	]
	for workload in workloads:
		commands.append(
			f"if ! timeout 300 java -Xms128m -Xmx3g -Xss1m -Duser.home=/tmp/fedcost-home "
			f"-Dsysds.fedplanner.trace=true "
			f"-Dsysds.fedplanner.trace.details=false -Dsysds.fedplanner.runtime.audit=true "
			f"-cp \"$CP\" {PROBE} {workload}.dml cost.xml {workload}.result.json "
			f">{workload}.log 2>&1; then workload_failures=1; fi")
	commands.append("exit \"$workload_failures\"")
	return "\n".join(commands) + "\n"


def main() -> int:
	args = parse_args()
	repo = Path(__file__).resolve().parents[2]
	jar = repo / "target/systemds-3.4.0-SNAPSHOT.jar"
	probe = repo / "target/test-classes" / Path(*PROBE.split(".")).with_suffix(".class")
	if not jar.is_file() or not probe.is_file():
		raise SystemExit("Build package and test classes first")
	image_id = subprocess.check_output(
		["docker", "image", "inspect", PINNED_IMAGE, "--format", "{{.Id}}"], text=True).strip()
	if image_id != PINNED_IMAGE:
		raise SystemExit(f"Pinned image mismatch: {image_id}")
	cost_environment = json.loads(args.cost_environment.read_text(encoding="utf-8"))
	if not isinstance(cost_environment, dict):
		raise SystemExit("cost environment must be a JSON object")
	root = args.artifact_root.resolve()
	root.mkdir(parents=True, exist_ok=True)
	run = Path(tempfile.mkdtemp(prefix="run-", dir=root))
	expected = write_inputs(run)
	workloads = list(expected)
	(run / "run.sh").write_text(run_script(workloads), encoding="utf-8")
	manifest = {
		"image": image_id, "jarSha256": sha256(jar), "probeSha256": sha256(probe),
		"sourcePrivacy": "mixed private-aggregate X and public P", "workers": 2,
		"workerPorts": [13000, 13001],
		"cpus": 4, "memory": "8g", "network": "none (container loopback only)",
		"planner": "DP-LocalConflict / compile_cost_based", "seed": 2026072701,
		"purpose": "correctness and runtime evidence; modeled objective is not measured wall time",
		"costEnvironment": cost_environment,
	}
	(run / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
	argv = ["docker", "run", "--rm", "--pull", "never", "--network", "none",
		"--name", f"codex-fedcost-runtime-{run.name}", "--cpus", "4", "--memory", "8g",
		"--user", f"{os.getuid()}:{os.getgid()}",
		"--volume", f"{repo / 'target'}:/engine:ro",
		"--volume", f"{repo / 'target/test-classes'}:/probe:ro",
		"--volume", f"{run}:/evidence:rw"]
	for key, value in sorted(cost_environment.items()):
		if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
			raise SystemExit(f"invalid environment name: {key}")
		argv.extend(("--env", f"{key}={value}"))
	argv.extend(("--entrypoint", "bash", PINNED_IMAGE, "/evidence/run.sh"))
	(run / "command.json").write_text(json.dumps(argv, indent=2) + "\n", encoding="utf-8")
	print(run, flush=True)
	if args.dry_run:
		(run / "receipt.json").write_text(json.dumps({"status": "dry-run"}, indent=2) + "\n")
		return 0
	started = time.monotonic_ns()
	with (run / "container.log").open("w", encoding="utf-8") as log:
		completed = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=1500)
	container_wall = time.monotonic_ns() - started
	checks = []
	for workload, expected_value in expected.items():
		log_path = run / f"{workload}.log"
		text = log_path.read_text(encoding="utf-8") if log_path.is_file() else ""
		values = re.findall(r"FEDCOST_NUMERIC=([0-9.eE+-]+)", text)
		result_path = run / f"{workload}.result.json"
		result = json.loads(result_path.read_text(encoding="utf-8")) if result_path.is_file() else {}
		passed = (len(values) == 1 and abs(float(values[0]) - expected_value) < 1e-8
			and result.get("status") == "passed" and result.get("planner") == "DP-LocalConflict"
			and result.get("runtimeFallbackCount") == 0 and result.get("runtimeRepairCount") == 0
			and bool(result.get("federatedHeavyHitters")))
		checks.append({"workload": workload, "expected": expected_value, "actual": values,
			"passed": passed, "prediction": {
				"objectiveMillis": result.get("predictedObjectiveMillis"),
				"objectiveCertificate": result.get("objectiveCertificate"),
				"selectedAssignmentSize": result.get("selectedAssignmentSize"),
				"selectedRelocations": result.get("selectedRelocations"),
				"selectedLocalMaterializations": result.get("selectedLocalMaterializations")},
			"measurement": {"compileNanos": result.get("compileNanos"),
				"executionNanos": result.get("executionNanos"), "probeWallNanos": result.get("probeWallNanos"),
				"federatedRequestCounts": result.get("federatedRequestCounts"),
				"networkTraffic": result.get("networkTraffic"),
				"runtimeFallbackCount": result.get("runtimeFallbackCount"),
				"runtimeRepairCount": result.get("runtimeRepairCount")},
			"federatedHeavyHitters": result.get("federatedHeavyHitters", {}),
			"allHeavyHitters": result.get("allHeavyHitters", {}),
			"error": result.get("error")})
	receipt = {"status": "passed" if completed.returncode == 0 and all(c["passed"] for c in checks) else "failed",
		"containerExitCode": completed.returncode, "containerWallNanos": container_wall, "checks": checks,
		"timingSemantics": "executionNanos excludes compilation; containerWallNanos includes startup and all workloads; objectiveMillis is modeled"}
	(run / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
	print(json.dumps(receipt, indent=2))
	return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
	raise SystemExit(main())
