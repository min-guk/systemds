# Candidate 16 official runtime result

## Outcome

The one-shot official JFR runs both passed execution, exact optimization, output,
privacy, lowering, runtime-audit, frozen-input, and profile-integrity gates. The
combined evaluator passed 21 of 23 gates. Both timing gates failed:

| Workload | Planning | Facts | Rules | Model | Optimization | Projection | Result |
|---|---:|---:|---:|---:|---:|---:|---|
| LogReg | 3.837223433 s | 0.053868091 | 1.575062769 | 0.695078039 | 1.407795994 | 0.105418540 | FAIL >3 s |
| GLM | 5.300551246 s | 0.059181781 | 2.278442242 | 1.211934290 | 1.650510979 | 0.100481954 | FAIL >3 s |

LogReg retained exact cost
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`
and GLM retained exact cost
`163.445631891948489909499669515659725158029935698777990182861685752868652343750000`.
Both statuses were `OPTIMAL`; nodes were 5431 and 1954. Maximum CP/FED output
differences were `1.6653345369377348e-16` and `9.020562075079397e-17`.

## One-shot comparison with C15

- LogReg planning rose from 3.521011574 s to 3.837223433 s (+8.98%). Rules fell
  9.19%, while Model rose 12.25% and Optimization rose 35.21%.
- GLM planning fell from 7.039366906 s to 5.300551246 s (-24.70%). Rules fell
  32.80%, Model 26.56%, and Optimization 8.96%.
- Search counters remained equal for both workloads. The measurement contains
  one run per condition, so the opposite LogReg/GLM wall-time movement is
  recorded rather than attributed to one implementation change.

JFR estimated total allocation fell from 1.625 GB to 1.490 GB for LogReg
(-8.35%) and from 3.375 GB to 3.212 GB for GLM (-4.85%). Stop-the-world pause
was 0.171096404 s for LogReg and 0.273186103 s for GLM. JFR reported no data
loss.

## Resource evidence

The foreground process monitor captured one coordinator JVM PID per workload:

| Workload | Samples | Observed | Whole-JVM CPU | Peak HWM |
|---|---:|---:|---:|---:|
| LogReg | 5 | 7.239 s | 19.82 s | 434,880 KiB |
| GLM | 7 | 10.270 s | 26.80 s | 811,836 KiB |

The exact Docker cgroup had a four-CPU quota. During the wider monitor interval,
LogReg recorded 42 throttled periods among 170 (24.71%) and 8.779494 aggregate
throttled seconds; GLM recorded 38 among 203 (18.72%) and 9.891884 aggregate
throttled seconds. C15 did not have this cgroup counter, and Linux throttled time
can aggregate runnable-thread delay, so it cannot be subtracted from wall time or
used to explain the C15-to-C16 delta.

## Remaining sampled hotspots

Sampling is sparse and inclusive counts overlap. Within optimizer samples,
`shape-key-guard-cost` accounted for 13/27 LogReg samples and 24/48 GLM samples.
Its estimated allocation was 162.3 MB (31.12% of optimizer allocation) and
181.3 MB (22.16%). `MemoShapes.buildIndexedContextKey` was the nearest planner
method for 3 LogReg and 8 GLM optimizer samples. `firstUnfixedCostGuard` remained
prominent in Rules/Model and accounted for 4 nearest-method LogReg optimizer
samples. These observations support further exact shape/guard work, but do not
by themselves show that the separate C17 component cache is net beneficial;
that prototype has substantial miss/key/fragment allocation risk.

## Integrity

- Both official run IDs were executed once and their containers exited.
- Frozen engine and run inputs matched all 12,659 manifest files with zero
  missing, extra, or hash-mismatched files.
- Both JFR files parsed successfully with no lost chunks.
- The evaluator's only failed gates were the two inclusive three-second gates.
- `candidate16-verification.json` contains runtime artifact hashes, process and
  cgroup summaries; `candidate16-artifact-sha256.txt` hashes the local evidence.
