#!/usr/bin/env python3
"""Compile a small observer and run real common plan-space analysis in Docker."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid


IMAGE = "sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--engine-target", required=True, type=Path,
                        help="Existing build containing SystemDS.jar and lib/")
    parser.add_argument("--privacy", choices=["PRIVATE_AGGREGATE", "PRIVATE"],
                        default="PRIVATE_AGGREGATE")
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--merge-diagnostics", type=int, default=0,
                        help="Opt-in duplicate-merge tracing; maximum detailed events retained")
    args = parser.parse_args()
    script = args.script.resolve(strict=True)
    engine = args.engine_target.resolve(strict=True)
    source = Path(__file__).with_name("PlanSpaceExample.java")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    shutil.copy2(script, output / "input.dml")
    shutil.copy2(source, output / source.name)
    compile_command = ["javac", "-cp", f"{engine}/SystemDS.jar:{engine}/lib/*",
                       "-d", str(classes), str(output / source.name)]
    with (output / "compile.log").open("w") as log:
        subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=60)
    container = "systemds-plan-space-example-" + uuid.uuid4().hex[:12]
    command = ["docker", "run", "--rm", "--pull", "never", "--network", "none",
               "--name", container, "--cpus", "2", "--memory", "4g",
               "--user", f"{os.getuid()}:{os.getgid()}",
               "--volume", f"{output}:/example:rw", "--volume", f"{engine}:/engine:ro",
               "--workdir", "/example", "--entrypoint", "java", IMAGE,
               "--add-modules", "jdk.incubator.vector", "-Xmx2g",
               "-cp", "/example/classes:/engine/SystemDS.jar:/engine/lib/*",
               "org.apache.sysds.hops.fedplanner.placement.PlanSpaceExample",
               "/example/input.dml", args.privacy]
    if args.merge_diagnostics:
        command.append(str(args.merge_diagnostics))
    repo = Path(__file__).resolve().parents[2]
    provenance = {
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
        "engine": str(engine), "engineJarSha256": digest(engine / "SystemDS.jar"),
        "probeSha256": digest(source), "scriptSha256": digest(script),
        "dependencySha256": {p.name: digest(p) for p in sorted((engine / "lib").glob("*.jar"))},
        "privacy": args.privacy, "dockerImage": IMAGE, "dockerCommand": command,
        "compileCommand": compile_command,
        "scope": "DML parse, HOP rewrite, production common analysis; no worker matrix execution",
    }
    try:
        with (output / "trace.json").open("w") as trace, (output / "diagnostic.log").open("w") as log:
            result = subprocess.run(command, stdout=trace, stderr=log, timeout=args.timeout_seconds)
        provenance["returncode"] = result.returncode
    except subprocess.TimeoutExpired:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True, timeout=15)
        provenance["returncode"] = 124
    finally:
        (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    if provenance["returncode"] != 0:
        raise SystemExit(f"Probe failed ({provenance['returncode']}): {output / 'diagnostic.log'}")
    data = json.loads((output / "trace.json").read_text())
    print(json.dumps({"output": str(output), "analysisMillis": data["analysisMillis"],
                      "rules": len(data["rules"]), "realizations": len(data["realizations"]),
                      "supports": sum(row["supportCount"] for row in data["realizations"])}, indent=2))
    print("hop\tline\tname\top\trules\trealizations\tsupports")
    for hop in data["hops"]:
        if hop["dataType"] != "MATRIX" and not hop["op"].startswith("ua("):
            continue
        rules = [r for r in data["rules"] if r["owner"] == hop["id"]]
        rows = [r for r in data["realizations"] if r["owner"] == hop["id"]]
        print("\t".join(map(str, [hop["id"], hop["line"], hop["name"], hop["op"],
                                  len(rules), len(rows), sum(r["supportCount"] for r in rows)])))


if __name__ == "__main__":
    main()
