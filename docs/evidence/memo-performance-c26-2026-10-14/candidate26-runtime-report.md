# Candidate 26 official runtime report

## Result

Candidate 26 improves both workloads relative to the accepted C24 baseline, but still fails the joint target. The official Docker workload completed once per case with no retry or warm-up.

| Workload | C26 | vs C24 accepted baseline | vs C25 rejected candidate | vs C23 | 3 s gate |
| --- | ---: | ---: | ---: | ---: | --- |
| LogReg | 3.028378372 s | -0.087285861 s (-2.80%) | -0.496652438 s (-14.09%) | -0.630371822 s (-17.23%) | FAIL by 28.378 ms |
| GLM | 5.072434165 s | -0.153059196 s (-2.93%) | -1.061235407 s (-17.30%) | +0.140925834 s (+2.86%) | FAIL |

C25 was withdrawn for its two workload regressions. C26 was built from C24 and is compared primarily with C24; C25 is retained only as rejected-candidate context.

## Phase timing

| Workload | Facts | Rules | Model | Optimization | Projection |
| --- | ---: | ---: | ---: | ---: | ---: |
| LogReg C26 | 0.043807693 | 1.170639383 | 0.761008536 | 0.972974974 | 0.079947786 |
| Change vs C24 | -26.97% | -14.72% | +16.48% | +1.87% | +7.24% |
| GLM C26 | 0.067610869 | 2.279118496 | 1.242508682 | 1.375370440 | 0.107825678 |
| Change vs C24 | +19.88% | +11.45% | +19.91% | -29.15% | -26.41% |

The C26 total improves modestly in both cases, but the phase movement is mixed. LogReg Rules improves while Model, Optimization, and Projection regress; GLM Optimization and Projection improve while the other phases regress. One official cold run per candidate cannot establish the cause of these timing changes.

## Correctness and evaluator gates

The unchanged raw evaluator reports **19/23 PASS**. Failures are both 3-second gates and both original-reference `MemoConstructionEqualReference` gates. C26 is exactly equal to C24 for `nativeRequests`, `nativeGeneration`, and `costProjection`. A separate telemetry-aware classification is **21/23 PASS**, with only the time gates failing; it does not replace the raw result.

Both workloads retain exact objective, `OPTIMAL`, full native universe, byte-identical CSV, model comparison, privacy/runtime audit, frozen input verification, and loss-free JFR.

LogReg search counters equal C24, C25, and C23. GLM counters equal C23 and differ from C24/C25 in six traversal fields: checks 97,344→97,479, cache hits 76,999→77,037, support queries 85,590→85,676, witness hits 49,805→49,979, binary-support hits 37,771→37,945, and worklist visits 14,353→14,370. Cost evaluations, cost-cache hits, bound counters, exact result, and output remain unchanged. The alternating counter sets across candidates are reported separately and not assigned to a specific change.

Oracle and native construction telemetry remain unchanged: LogReg prepared 8,880, reused 8,681, Oracle requests 199 with actual cache 0 hit / 199 miss; GLM prepared 11,699, reused 11,249, Oracle requests 450 with 0 hit / 450 miss. Movement-reuse counters are also identical to C24 for every logged field.

Shape telemetry changes slightly despite exact semantics: LogReg phase-shape requests/hits change 181,750/132,307→181,747/132,306, guard-relevance 197,155/197,088→197,153/197,086, and occurrence-transfer 86,097/85,207→86,095/85,205. GLM phase-shape is unchanged from C24, while guard-relevance changes 2,414,131/2,413,901→2,414,139/2,413,909 and guard-atom 1,178,259/1,178,215→1,178,263/1,178,219. These are diagnostic traversal counts, not semantic failures.

## Allocation, CPU, and residual priorities

| Workload | CPU | JFR allocation | Change vs C24 | STW pause | Max HWM | Throttled periods |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LogReg | 21.84 s | 1,155,177,248 B | -0.84% | 0.179046864 s | 506,128 KiB | 33.52% |
| GLM | 23.95 s | 2,411,462,616 B | -0.56% | 0.253926433 s | 694,264 KiB | 22.28% |

| Workload | Rules allocation | Model allocation | Optimization allocation | CPU samples R/M/O |
| --- | ---: | ---: | ---: | ---: |
| LogReg | 254,174,768 B (+0.91%) | 220,988,736 B (-7.70%) | 357,188,888 B (+1.79%) | 19 / 10 / 8 |
| GLM | 637,848,816 B (-10.24%) | 533,005,000 B (-3.93%) | 623,284,192 B (-2.60%) | 53 / 37 / 30 |

The C24 `MemoOptimizer.uniqueScope` allocation samples (12,575,800 B LogReg, 39,819,296 B GLM) are absent in C26, consistent with removal of the boxed-stream scope validation. JFR sampling does not provide an exact allocation count, and this absence is not a timing attribution.

The requested next-iteration sites remain visible. In GLM Optimization, `LoopDecision.equals` falls from C24 15,719,816 B / 2 CPU samples to C26 11,527,864 B / 2 samples. `MemoProgramModel.phaseGuard` falls from 29,343,592 B / 0 samples to 12,575,880 B / 0 samples in Optimization, but an additional 13,623,816 B / 1 sample appears in Model. Across all three phases, C26 still samples 47,425,128 B at `LoopDecision.equals` and 26,199,696 B at `phaseGuard`; they remain concrete iteration-allocation targets. For LogReg, the corresponding totals are 20,959,736 B and 44,015,392 B, respectively.

## Reproducibility

The unchanged `run_LAN_docker.sh` contract used the pinned image, 4 CPUs, 8 GiB, disabled network, foreground JVM/cgroup monitors, JFR and audits, and no concurrent owned CPU work during either measured window. The frozen engine has 12,707 files; manifest SHA-256 is `40f4b2f1044b38851df23c176e7bc75e204d431082ab65566b8927c295dd84fa`. Both monitors and containers exited normally; no C26-owned process remains.

Machine-readable evidence is in `candidate26-evaluation.json`, `candidate26-comparison.json`, `candidate26-verification.json`, `candidate26-residual-jfr.json`, and `candidate26-artifact-sha256.txt`.
