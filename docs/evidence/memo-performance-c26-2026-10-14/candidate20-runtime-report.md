# Candidate 20 official runtime report

Candidate 20 preserves all semantic gates but misses both three-second planning targets. The official single
profiled runs measure 3.295243134 seconds for LogReg and 5.219770475 seconds for GLM. The evaluator passes 21/23
gates; only the two time gates fail.

| Workload | C18 | C20 | Single-run change | Allocation change |
|---|---:|---:|---:|---:|
| LogReg | 3.153343246 s | 3.295243134 s | +4.50% | -5.48% |
| GLM | 5.618328827 s | 5.219770475 s | -7.09% | -9.47% |

Relative to the published starting measurements, C20 reduces LogReg from 7.770025473 seconds by 57.59% and GLM
from 152.372563001 seconds by 96.57%. These are one-run comparisons and do not establish causal performance.

LogReg phases are facts 0.054991021, Rules 1.597530597, Model 0.561796747, Optimization 1.007294486, and projection
0.073630283 seconds. GLM phases are facts 0.060483316, Rules 2.373493699, Model 1.218965753, Optimization
1.469478239, and projection 0.097349468 seconds.

Both cases pass and return `OPTIMAL`. Exact objectives, nodes, search counters, reference output policies, native
universe, native construction counters, action diagnostics, privacy, lowering/runtime audit, and CP/FED return codes
satisfy the evaluator. Frozen input verification covers all 12,683 files without a difference, both JFR recordings
report no data loss, and both owned containers and monitors terminated. Prefix/native-generation counters are
identical to C18. Shape-reuse telemetry differs as expected from C20's private model and prefix analysis changes;
it is recorded separately and is not treated as a semantic equality gate.

LogReg allocates an estimated 1,357,390,928 bytes, has 0.187927 seconds of STW pause, uses 18.55 whole-JVM CPU
seconds, and reaches 461,344 KiB HWM. GLM allocates 2,813,599,016 bytes, has 0.235481 seconds STW, uses 24.67 CPU
seconds, and reaches 713,884 KiB HWM. Cgroup throttled-period rates are 23.43% and 16.92%. Resource and wall-time
deltas come from one run each and do not establish causality.

Evidence is recorded in `candidate20-evaluation.json`, `candidate20-comparison.json`,
`candidate20-verification.json`, the workload summaries/profiles, and the artifact SHA-256 manifest.
