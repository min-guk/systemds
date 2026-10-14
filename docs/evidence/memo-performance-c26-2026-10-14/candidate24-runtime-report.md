# Candidate 24 official runtime report

## Result

Candidate 24 does not meet the joint target. The official cold Docker run completed successfully once per workload, but LogReg took **3.115664233 s** and GLM took **5.225493361 s**. Both exceed the inclusive 3-second limit. No retry or warm-up run was performed.

| Workload | C24 | vs C23 | vs C22 | vs C20 | 3 s gate |
| --- | ---: | ---: | ---: | ---: | --- |
| LogReg | 3.115664233 s | -0.543085961 s (-14.84%) | +0.369451624 s (+13.45%) | -0.179578901 s (-5.45%) | FAIL |
| GLM | 5.225493361 s | +0.293985030 s (+5.96%) | -0.214795221 s (-3.95%) | +0.005722886 s (+0.11%) | FAIL |

C23 was a mixed result: LogReg 3.658750194 s and GLM 4.931508331 s. C24 improves the former but regresses the latter. A single cold JVM run does not isolate causality for whole-phase timing changes.

## Phase timing

| Workload | Facts | Rules | Model | Optimization | Projection |
| --- | ---: | ---: | ---: | ---: | ---: |
| LogReg C24 | 0.059985631 | 1.372660212 | 0.653317637 | 0.955147095 | 0.074553658 |
| LogReg change vs C23 | +5.08% | -14.73% | -18.87% | -14.66% | +10.60% |
| GLM C24 | 0.056401101 | 2.045053885 | 1.036191000 | 1.941330320 | 0.146517055 |
| GLM change vs C23 | -15.08% | -6.95% | -7.10% | +34.41% | +36.20% |

The static empty-head proof and bounded Shapes cleanup are structurally visible in the profile: the C23 GLM samples attributed 59,694,096 allocation bytes to `LoopDependencyState.<init>` and 24,103,376 bytes to `collectLoopHeads`; neither site appears in the C24 residual profile. GLM Rules allocation fell from 920,333,088 to 710,616,472 bytes (-22.79%). This structural evidence supports removal of that repeated analysis. It does not explain the 34.41% Optimization-time increase, so C24 does not establish a total-runtime win over C23 for GLM.

## Correctness and evaluator gates

The unchanged evaluator reports **19/23 PASS**. The four failures are:

- `logregPlanningAtMost3Seconds`
- `glmPlanningAtMost3Seconds`
- `logregMemoConstructionEqualReference`
- `glmMemoConstructionEqualReference`

The two construction failures are the inherited C21 native request/generation telemetry differences from the original reference. C24 is exactly equal to C23 for `nativeRequests`, `nativeGeneration`, and `costProjection` on both workloads. Reporting those two failures separately gives a supplementary telemetry-aware view of **21/23 PASS**, with only the time gates failing. This view does not replace or waive the raw evaluator result.

Both workloads retain:

- `OPTIMAL` status and the exact reference objective;
- the complete native universe (`nativeRules`, `fedOwners`, and `supplies`);
- byte-identical output CSV hashes through C20/C22/C23/C24;
- clean model comparison, privacy audit, and runtime audit;
- loss-free JFR profiles and verified frozen inputs.

LogReg search counters are identical to C20, C22, and C23. GLM counters equal C22. Compared with C23, GLM has checks 97,479→97,344, cache hits 77,037→76,999, support queries 85,676→85,590, support-witness hits 49,979→49,805, binary-support hits 37,945→37,771, and worklist visits 14,370→14,353. Exact objective and full output remain unchanged. The alternating C22/C23/C24 traversal observations must not be assigned to a specific implementation change from these single runs.

Oracle and native construction telemetry are unchanged from C23:

| Workload | Prepared | Reused | Oracle requests | Actual cache hit/miss | Canonical created | Uncacheable |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LogReg | 8,880 | 8,681 | 199 | 0 / 199 | 84 | 115 |
| GLM | 11,699 | 11,249 | 450 | 0 / 450 | 121 | 329 |

Native cost reuse also remains unchanged: LogReg 5,950 requests, 4,756 hits, and 1,666 vector builds; GLM 7,861 requests, 6,033 hits, and 3,484 vector builds.

## Resource evidence and remaining bottleneck

| Workload | CPU | JFR allocation | STW pause | Max HWM | Throttled periods |
| --- | ---: | ---: | ---: | ---: | ---: |
| LogReg | 23.24 s | 1,164,949,064 B | 0.182423099 s | 471,268 KiB | 25.14% |
| GLM | 30.11 s | 2,424,958,720 B | 0.271049705 s | 832,284 KiB | 20.38% |

Total sampled allocation falls versus C23 by 5.05% for LogReg and 8.42% for GLM. Residual disjoint phase allocation is:

| Workload | Rules | Model | Optimization | CPU samples R/M/O |
| --- | ---: | ---: | ---: | ---: |
| LogReg | 251,874,880 B | 239,413,184 B | 350,919,512 B | 33 / 7 / 14 |
| GLM | 710,616,472 B | 554,821,568 B | 639,953,248 B | 57 / 19 / 41 |

For GLM, Rules remains the largest allocation phase, while Optimization is now the largest measured phase time and has 41 CPU samples. Its prominent residual allocation sites include `uniqueScope` (39,819,296 B), `EvaluationContext.<init>` (33,535,480 B), `OriginIdentity.equals` (33,535,432 B), and `phaseGuard` (29,343,592 B). These are profile priorities, not proof that any one site caused the observed time.

## Reproducibility

The runs used the unchanged `run_LAN_docker.sh` contract with the pinned image, 4 CPUs, 8 GiB, disabled network, the frozen 12,704-file engine, foreground JVM/cgroup monitors, and no concurrent owned CPU work. Manifest SHA-256 is `a40dc474788845b7305704268f2f88889e2a5c6d60e9590f89f1fd82528cceb3`. Both run containers and monitors exited normally; no C24-owned container or monitor remains.

Machine-readable evidence is in `candidate24-evaluation.json`, `candidate24-comparison.json`, `candidate24-verification.json`, `candidate24-residual-jfr.json`, and `candidate24-artifact-sha256.txt`.
