# Candidate 17 official runtime result

## Outcome

Both official one-shot JFR runs passed execution, `OPTIMAL` exact optimization,
output, privacy, lowering, runtime-audit, frozen-input, and profile-integrity
checks. The evaluator passed 21 of 23 gates. Only the two inclusive three-second
planning gates failed.

| Workload | Planning | Facts | Rules | Model | Optimization | Projection |
|---|---:|---:|---:|---:|---:|---:|
| LogReg | 3.715094059 s | 0.056960631 | 1.789788382 | 0.717871464 | 1.067641896 | 0.082831686 |
| GLM | 5.772636909 s | 0.058753401 | 2.484626375 | 1.042099314 | 2.072012601 | 0.115145218 |

Exact costs, native universes, outputs, action diagnostics, and runtime audits
matched the references. LogReg and GLM retained 5431 and 1954 search nodes. Their
maximum CP/FED differences were `1.6653345369377348e-16` and
`9.020562075079397e-17`.

## C16 comparison

- LogReg planning changed 3.837223433 -> 3.715094059 s (-3.18%). Rules rose
  13.63%, while Optimization fell 24.16%.
- GLM planning changed 5.300551246 -> 5.772636909 s (+8.91%). Rules rose 9.05%,
  Model fell 14.01%, and Optimization rose 25.54%.
- Every recorded search counter was identical to C16 for both workloads.
- Estimated allocation was nearly unchanged: LogReg 1.489695000 -> 1.488355616
  GB (-0.09%), GLM 3.211631640 -> 3.199719288 GB (-0.37%).

The opposing phase movements, identical search work, and single run per condition
do not support assigning the GLM optimization increase to a specific C17 change.

## Resource evidence

| Workload | Process samples | Observed | Whole-JVM CPU | Peak HWM | STW pause |
|---|---:|---:|---:|---:|---:|
| LogReg | 6 | 9.216 s | 23.26 s | 438,984 KiB | 0.201570656 s |
| GLM | 7 | 10.579 s | 26.56 s | 801,596 KiB | 0.246318194 s |

The four-CPU cgroup monitor recorded LogReg throttling in 65/196 periods
(33.16%, 24.048623 aggregate throttled seconds) and GLM throttling in 43/198
periods (21.72%, 9.202107 aggregate seconds). LogReg's throttling was materially
higher than C16's 24.71%; GLM's was modestly higher than C16's 18.72%. Aggregate
throttled time cannot be subtracted from wall time, and these counters do not
establish causality for individual planner phases.

JFR reported no data loss. The optimizer profile remained dominated by the broad
shape/key/guard category: 9/19 samples for LogReg and 29/75 for GLM. GLM's
`buildIndexedContextKey` was the nearest planner method for 10 samples. Sampling
counts differ substantially between the one-shot recordings, so absolute method
samples are retained rather than converted into a causal speed estimate.

## Integrity

- Each workload ran exactly once; owned containers exited.
- Frozen engine and run inputs matched all 12,668 manifest files.
- Both JFR files parsed without data loss.
- The evaluator's only failures were the two timing gates.
