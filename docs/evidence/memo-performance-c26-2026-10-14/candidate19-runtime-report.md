# Candidate 19 official runtime report

Candidate 19 preserves all semantic gates but misses both three-second planning targets. The official single profiled runs measure 3.450021828 seconds for LogReg and 6.774694799 seconds for GLM. The evaluator passes 21/23 gates; only the two time gates fail.

| Workload | C18 | C19 | Single-run change | Allocation change |
|---|---:|---:|---:|---:|
| LogReg | 3.153343246 s | 3.450021828 s | +9.41% | -6.01% |
| GLM | 5.618328827 s | 6.774694799 s | +20.58% | -0.93% |

LogReg phases are facts 0.054938465, Rules 1.519467162, Model 0.671680027, Optimization 1.123691504, and projection 0.080244670 seconds. GLM phases are facts 0.065924848, Rules 2.863088273, Model 1.725427482, Optimization 1.945970136, and projection 0.174284060 seconds.

Both cases pass and return `OPTIMAL`. Exact objectives, nodes, search counters, reference output policies, native universe, construction counters, action diagnostics, privacy, lowering/runtime audit, and CP/FED return codes satisfy the evaluator. Frozen input verification covers 12,686 files without a difference, and both JFR recordings report no data loss.

LogReg allocates an estimated 1,349,905,408 bytes, has 0.173256 seconds of STW pause, uses 24.25 whole-JVM CPU seconds, and reaches 419,112 KiB HWM. GLM allocates 3,079,205,568 bytes, has 0.307867 seconds STW, uses 33.91 CPU seconds, and reaches 807,468 KiB HWM. Cgroup throttled-period rates are 26.14% and 17.94%. The wall-time regressions and allocation reductions come from one run each and do not establish causality.

Evidence is recorded in `candidate19-evaluation.json`, `candidate19-comparison.json`, `candidate19-verification.json`, the workload summaries/profiles, and the artifact SHA-256 manifest.
