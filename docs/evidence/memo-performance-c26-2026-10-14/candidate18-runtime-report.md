# Candidate 18 official runtime report

## Outcome

Candidate 18 preserves every semantic, frozen-input, output, privacy, audit, and JFR integrity gate, but it does not meet the three-second planning target. The fixed one-shot measurements are 3.153343246 seconds for LogReg and 5.618328827 seconds for GLM. The evaluator passes 21 of 23 gates; only the two planning-time gates fail.

| Workload | C17 planning | C18 planning | Change | Result |
|---|---:|---:|---:|---|
| LogReg | 3.715094059 s | 3.153343246 s | -0.561750813 s (-15.12%) | FAIL by 0.153343246 s |
| GLM | 5.772636909 s | 5.618328827 s | -0.154308082 s (-2.67%) | FAIL by 2.618328827 s |

These are single profiled runs per condition. The changes should not be generalized beyond the recorded runs.

## Phase measurements

| Workload | Phase | C17 | C18 | Change |
|---|---|---:|---:|---:|
| LogReg | Facts | 0.056960631 | 0.055062056 | -3.33% |
| LogReg | Rules | 1.789788382 | 1.406202100 | -21.43% |
| LogReg | Model | 0.717871464 | 0.558604704 | -22.13% |
| LogReg | Optimization | 1.067641896 | 1.058939917 | -0.81% |
| LogReg | Projection | 0.082831686 | 0.074534469 | -10.02% |
| GLM | Facts | 0.058753401 | 0.062132140 | +5.75% |
| GLM | Rules | 2.484626375 | 2.584413564 | +4.02% |
| GLM | Model | 1.042099314 | 1.115512054 | +7.04% |
| GLM | Optimization | 2.072012601 | 1.680967311 | -18.87% |
| GLM | Projection | 0.115145218 | 0.175303758 | +52.24% |

The C18 cache changes reduce observed LogReg Rules and Model time and GLM Optimization time. GLM Rules, Model, and Projection are higher in this one run. Search counters are byte-for-value identical to C17 for both workloads, so the optimizer phase difference is not caused by a changed search space.

## Semantic gates

- Both cases return success and `OPTIMAL`.
- Exact objectives equal C17 and the certified references: LogReg `1301.1526531142989790221751587629165844428591469750244868919253349304199218750`; GLM `163.445631891948489909499669515659725158029935698777990182861685752868652343750000`.
- Nodes remain 5,431 for LogReg and 1,954 for GLM. All search counters match C17.
- LogReg output is byte-equal to the reference. Both CP comparisons match; maximum numeric errors are `1.6653345369377348e-16` and `9.020562075079397e-17`.
- Native universe, construction counters, selected action diagnostics, privacy checks, lowering/runtime audit, and return codes pass the evaluator policies.
- Both frozen-input comparisons cover 12,675 files with zero missing, extra, or hash-mismatched files.
- Both JFR recordings report no data-loss events.

## Resources and profile

| Workload | Allocation C17 → C18 | STW C18 | JVM CPU C18 | Peak HWM C18 | Cgroup throttled periods |
|---|---:|---:|---:|---:|---:|
| LogReg | 1,488,355,616 → 1,436,146,240 B (-3.51%) | 0.187795 s | 23.67 s | 463,356 KiB | 48/171 (28.07%) |
| GLM | 3,199,719,288 → 3,107,990,512 B (-2.87%) | 0.285465 s | 30.30 s | 797,524 KiB | 30/179 (16.76%) |

The CPU values cover the whole coordinator JVM during the monitor interval. Cgroup throttling and single-run noise prevent attributing wall-time changes solely to the source changes.

The GLM optimizer has 38 execution samples. Inclusive stacks contain `movementGroupCost` in 28/38 samples (73.7%) and the conditional-cost-bound chain in 22/38 (57.9%); these overlap. Its largest allocation buckets are shape/key/guard cost at 267,214,072 estimated bytes, optimizer-other at 218,886,520, and model-cost-other at 152,765,832. LogReg has only 14 optimizer samples, so its method percentages are low-resolution evidence.

## Trace effects

The planned context projection reuse is visible without semantic drift. LogReg guard-relevance requests fall from 279,836 to 196,734 and guard-atom requests from 685,807 to 340,491. GLM guard-relevance requests fall from 3,213,909 to 2,412,234 and guard-atom requests from 2,134,148 to 1,177,205. Minor direct-root and phase-shape request count changes reflect avoided upstream calls; native generation, cost projection, movement, context-analysis, native-cost, check-projection, forest-tail, and search traces otherwise retain their required values.

## Evidence

- `candidate18-evaluation.json`: 21/23 gates, with only both time gates false
- `candidate18-comparison.json`: C17-to-C18 phase, counter, output, resource, and trace comparison
- `candidate18-verification.json`: one-run counts, frozen-input verification, monitors, JFR integrity, and runtime artifact hashes
- `candidate18-{logreg,glm}-summary.json`: parsed runtime evidence
- `candidate18-{logreg,glm}-{profile,hotspots,phase-hotspots}.json`: JFR analysis

