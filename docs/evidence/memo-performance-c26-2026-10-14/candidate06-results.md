# Candidate 06 one-shot Docker/JFR results

Both authorized workloads ran exactly once from the 12,566-file frozen engine. Both harness cases passed,
produced loss-free JFRs, matched their exact objective references, matched CP output numerically, selected
native FED execution, and reported no runtime-audit violation. The performance goal did not pass because
neither Memo planning total is at most 3 seconds.

| Workload | Planning | Facts | Rules | Model | Optimization | Projection | Goal |
|---|---:|---:|---:|---:|---:|---:|---|
| LogReg | 5.297881735s | 0.054797815s | 1.887560200s | 0.904753090s | 2.358735780s | 0.092034850s | FAIL |
| GLM | 13.996137711s | 0.060752965s | 6.139058062s | 2.006728151s | 5.634159083s | 0.155439450s | FAIL |

Against the one-shot Candidate 05 measurements, LogReg planning fell 12.14% and GLM planning fell 11.59%.
Against the primary published/baseline measurements, the reductions are 31.82% and 90.81%, respectively.
These percentages compare single JFR runs and are not repeat-run confidence intervals.

The exact LogReg objective remains
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`;
the exact GLM objective remains
`163.445631891948489909499669515659725158029935698777990182861685752868652343750000`.
LogReg FED output is byte-equal to its reference. GLM's baseline has no certified FED CSV, so the fixed
policy requires the candidate's CP/FED numeric match; its maximum absolute difference is
`9.020562075079397e-17`. LogReg's maximum absolute difference is `1.6653345369377348e-16`.

Candidate 05 to 06 search changes:

- LogReg nodes: 607,119 -> 37,499 (-93.82%); optimization: 3.402542805s -> 2.358735780s (-30.68%).
- GLM nodes: 885,887 -> 685,615 (-22.61%); optimization: 6.763692807s -> 5.634159083s (-16.70%).
- GLM worklist visits: 4,327,247 -> 2,202,530; removed rules: 4,925,136 -> 3,449,848;
  support-witness hits: 22,309,295 -> 14,355,128.
- LogReg construction phases rose in this one shot (Rules +8.04%, Model +26.54%), while GLM Rules fell
  10.52% and Model fell 1.39%. The shape simplification therefore did not uniformly reduce phase time.

JFR/resource evidence:

| Workload | Sampled allocation | C05 delta | STW GC | Whole-JVM CPU observed | Max HWM |
|---|---:|---:|---:|---:|---:|
| LogReg | 3,076,745,592 B | -25.24% | 0.292229s | 29.99s | 895,640 KiB |
| GLM | 9,993,834,880 B | -9.21% | 0.786253s | 47.64s | 1,349,944 KiB |

The largest remaining GLM construction costs are still shape/view and binding resolution. Rules contains
292 execution samples and 2.971 GB sampled allocation; nearest allocations include `cloneView` 779.0 MB,
`visitJoint` 350.9 MB, `ShapeContextKey` construction 249.5 MB, `MutableEvaluation` 239.0 MB,
`relevantGuardDecisions` 235.5 MB, and `projectShapeGuards` 181.1 MB. Model contains 92 execution samples
and 1.656 GB sampled allocation; nearest allocations include `MutableEvaluation` 342.7 MB, `cloneView`
194.9 MB, `canonicalGuard` 129.9 MB, and `ShapeContextKey.equals` 88.0 MB.

The residual GLM optimizer profile has 327 samples. Mutually exclusive nearest buckets are
shape/guard cost 66 (20.18%), cache/key/domain 51 (15.60%), optimizer-other 49 (14.98%), reduced-search
control 49 (14.98%), solver-other 43 (13.15%), and model-cost-other 35 (10.70%). Inclusive methods
overlap; leading nearest methods are `enumerateCore` 20, `tryForestTail` 17, `cachedDomains` 15,
`cachedWitness` 12, and `scopedDomainHash` 11 samples.

Evidence gates are recorded in `candidate06-evaluation.json`, incremental and primary comparisons in
`candidate06-comparison.json`, and frozen-input/run verification in `candidate06-verification.json`.
