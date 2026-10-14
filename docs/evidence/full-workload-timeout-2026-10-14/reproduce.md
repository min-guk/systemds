# Diagnostic reproduction — unchanged actual ML inputs

These are diagnostic runs authorized after the once-per-condition matrix. They do not replace the original official timings. Start each workload sequentially, using a **fresh output root** (the runner fails closed on incompatible resume metadata). Do not modify DML/config, engine, renderer, stage, costs, privacy, ranges, worker count, or search limits.

```bash
cd /home/mchoi/fedplanner-under3-20261014
bash scripts/fedplanner/run_LAN_docker.sh --memo-planning-matrix \
  --engine "$PWD/experiments/memo-under3-20261014/candidate26-engine" \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --output-root /home/mchoi/full-workload-timeout-diagnostic-logreg-20261014 \
  --workloads ml:logreg --workers 3
# After completion, same command with ml:glm and a new glm output root.
```

Executed roots are `/home/mchoi/full-workload-timeout-diagnostic-{logreg,glm}-20261014`. Each contains exactly one attempt and summary returncode124; no optimization/MemoSearch completion receipt. Per-case `command.json`, `container-run.sh`, `cell.dml`, `execution.xml`, input receipts and coordinator logs are preserved alongside this document.

## Live observational attach

The pinned image has a Java17 JRE without jcmd; **host JDK17 jcmd successfully attached to the host PID of the Docker JVM**. This neither swaps the Java executable nor changes the production engine. Discover the correct measured JVM by Docker name, not the first Java PID on the host:

```bash
container=sysds-memo-plan-e307dbd52bc178ca # LogReg W3; GLM W3: sysds-memo-plan-529ac865dd4557cf
# docker top shows host PIDs. Select exactly the java process invoking DMLScript.
docker top "$container" -eo pid,comm,args
pid=...  # verified measured JVM from docker top, never a stale PID
jcmd=/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/jcmd
"$jcmd" "$pid" Thread.print > thread.txt
"$jcmd" "$pid" GC.heap_info > heap.txt
"$jcmd" "$pid" JFR.start name=timeout settings=profile duration=60s filename=/evidence/logreg-live.jfr
# GLM uses glm-live.jfr. /evidence is the attempt bind mount.
```

Actual host PIDs were LogReg228962 and GLM235973; they have exited and **must not be reused**. LogReg attached near169s and GLM near31s elapsed. Thread dumps preserve their own elapsed/CPU values; file labels such as `160s` or `260s` are approximate labels, not exact timers. GLM `thread-260s.txt` actually records280.37s. Attachment introduces profiling overhead; sampled results are not official nonprofiled planning times. Record wall/CPU/RSS and attach time separately in future comparisons. No build or CPU-heavy exporter ran concurrently with either measured JVM.

## Export and summarize after the measured JVM exits

Use built-in Java JFR consumer only; no new dependencies:

```bash
mkdir -p /home/mchoi/jfr-export-build
javac -d /home/mchoi/jfr-export-build docs/evidence/full-workload-timeout-2026-10-14/JfrExtract.java
java -cp /home/mchoi/jfr-export-build JfrExtract path/to/logreg-live.jfr logreg-events.jsonl
python3 docs/evidence/full-workload-timeout-2026-10-14/summarize_timeout.py logreg-events.jsonl logreg-profile.json
```

Counts use unique **inclusive method names per main-thread ExecutionSample**, including stacks whose outer planner frames are absent. Do not use the older generic phase classifier alone: GLM's deep recursive stack can lack `MemoProgramModel.optimize`, causing an erroneous outside-planner classification.

Allocation totals use main `ThreadAllocationStatistics` deltas over about60s. The first attach-time allocation sample can include all allocation before recording; exclude it from class distributions and never sum weights as the60s total. GC pause totals are directly from pause events. Percentages are sampling estimates, not exact stage timers. Stack depth does not establish visited-node count or feasible Cartesian-product size.

Raw JFR and exported event paths/hashes are in `provenance.json`. Compact copied evidence is hashed in `artifacts-sha256.json`. Production optimizer/model source hashes match frozen C26; `git diff HEAD -- src/main src/test scripts/fedplanner` is empty. Copying profiler artifacts/documentation does not establish regression tests or completed OPTIMAL for these timeout runs.
