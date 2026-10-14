# Candidate 25 official runtime report

## Result

Candidate 25 fails the joint performance target. The unchanged official Docker command completed successfully exactly once per workload, but LogReg took **3.525030810 s** and GLM took **6.133669572 s**. No retry or warm-up was run.

| Workload | C25 | vs C24 | vs C23 | vs C22 | 3 s gate |
| --- | ---: | ---: | ---: | ---: | --- |
| LogReg | 3.525030810 s | +0.409366577 s (+13.14%) | -0.133719384 s (-3.65%) | +0.778818201 s (+28.36%) | FAIL |
| GLM | 6.133669572 s | +0.908176211 s (+17.38%) | +1.202161241 s (+24.38%) | +0.693380990 s (+12.75%) | FAIL |

Both totals regress from the C24 primary reference. C25 also reduces sampled allocation, so the single cold-run pair does not support attributing the timing regression to the snapshot change or to GC pressure.

## Phase timing

| Workload | Facts | Rules | Model | Optimization | Projection |
| --- | ---: | ---: | ---: | ---: | ---: |
| LogReg C25 | 0.053825554 | 1.206282522 | 0.799707994 | 1.351841506 | 0.113373234 |
| Change vs C24 | -10.27% | -12.12% | +22.41% | +41.53% | +52.07% |
| GLM C25 | 0.091310540 | 2.454767612 | 1.736173891 | 1.686853966 | 0.164563563 |
| Change vs C24 | +61.89% | +20.03% | +67.55% | -13.11% | +12.32% |

The changes are broad and inconsistent across phases: LogReg Rules improves while Model and Optimization regress; GLM Optimization improves while Facts, Rules, Model, and Projection regress. One run per candidate is insufficient to assign causality to one phase or implementation site.

## Correctness and raw evaluator

The unchanged evaluator reports **19/23 PASS**. Failures are the two time gates and the inherited original-reference `MemoConstructionEqualReference` gates. C25 equals C24 exactly for `nativeRequests`, `nativeGeneration`, and `costProjection`; the construction failures remain confined to telemetry introduced in C21. A separate telemetry-aware classification is **21/23 PASS** with only the two time failures. It does not replace or waive the raw result.

Both workloads preserve:

- `OPTIMAL` and the exact reference objective;
- `nativeRules`, `fedOwners`, `supplies`, and the full cost projection;
- byte-identical output CSV hashes across C22/C23/C24/C25;
- model comparison, privacy audit, and runtime audit;
- verified frozen inputs and loss-free JFR profiles.

LogReg search counters equal C24. GLM search counters also equal C24 and C22; they retain the six traversal-count differences from C23. Exact results and full output remain stable.

## Reuse telemetry

No dedicated production counter records `immutableSnapshot` input-identity reuse. Existing resolver and guard telemetry was retained unchanged and reports:

| Workload | Direct roots: Shapes request / fast | Direct roots: Privacy request / fast | Context analysis request / hit | Guard relevance request / hit | Guard atom request / hit |
| --- | ---: | ---: | ---: | ---: | ---: |
| LogReg | 49,441 / 49,441 | 12,461 / 5,284 | 28,415 / 28,385 | 175,308 / 175,241 | 292,342 / 292,332 |
| GLM | 226,760 / 226,707 | 37,485 / 19,923 | 67,403 / 67,242 | 1,811,067 / 1,810,837 | 667,665 / 667,621 |

Oracle/native telemetry also equals C24: LogReg prepared 8,880, reused 8,681, Oracle requests 199, actual cache 0 hit / 199 miss; GLM prepared 11,699, reused 11,249, Oracle requests 450, actual cache 0 hit / 450 miss. Native vector reuse is 5,950 requests / 4,756 hits / 1,666 builds for LogReg and 7,861 / 6,033 / 3,484 for GLM.

## Allocation and resource evidence

| Workload | CPU | JFR allocation | Change vs C24 | STW pause | Max HWM | Throttled periods |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LogReg | 26.33 s | 1,147,122,584 B | -1.53% | 0.186321523 s | 427,152 KiB | 30.29% |
| GLM | 30.06 s | 2,237,853,648 B | -7.72% | 0.257943853 s | 604,428 KiB | 17.41% |

Residual disjoint phase evidence is:

| Workload | Rules allocation | Model allocation | Optimization allocation | CPU samples R/M/O |
| --- | ---: | ---: | ---: | ---: |
| LogReg | 238,135,544 B (-5.45%) | 209,497,080 B (-12.50%) | 364,492,112 B (+3.87%) | 32 / 14 / 25 |
| GLM | 625,289,840 B (-12.01%) | 507,986,176 B (-8.44%) | 644,341,368 B (+0.69%) | 92 / 53 / 40 |

The snapshot optimization has structural evidence in sampled allocations. `MemoBindingResolver.immutableSnapshot` falls from 35,546,208 to 23,911,496 bytes across LogReg Rules+Model (-32.73%), and from 128,942,520 to 37,727,480 bytes across GLM Rules+Model+Optimization (-70.74%). These are JFR weighted samples, not exact allocation counters. Despite the reduction, measured total runtime regresses, so the result does not establish a performance benefit in this official pair.

The C25 GLM residual has 92 Rules CPU samples, 53 Model samples, and 40 Optimization samples. Prominent nearest allocation sites include Rules `visitJoint` 75,389,400 B and `resolvedBindingShape` 65,876,952 B; Model `canonicalGuard` 53,447,392 B and `firstUnfixedCostGuard` 41,785,696 B; Optimization `cachedIndexedContextKey` 42,967,600 B and `visitDirectDefinitions` 42,967,552 B. These are prioritization signals rather than causal proof.

## Reproducibility

The run used the unchanged `run_LAN_docker.sh` contract: pinned image, 4 CPUs, 8 GiB, network disabled, foreground JVM/cgroup monitor, JFR and audits enabled, and no concurrent owned CPU jobs during either window. The frozen engine contains 12,706 files; manifest SHA-256 is `768020ae5f12b5dffda6adc47bc75cc3b48e5648b22ab5f70d85baaf1aa08b09`. Both monitors and run containers exited normally, and no C25-owned process remains.

Machine-readable evidence is in `candidate25-evaluation.json`, `candidate25-comparison.json`, `candidate25-verification.json`, `candidate25-residual-jfr.json`, and `candidate25-artifact-sha256.txt`.
