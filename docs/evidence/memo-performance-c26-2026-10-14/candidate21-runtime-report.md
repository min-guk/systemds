# Candidate 21 official runtime report

Candidate 21 does not meet either three-second planning target. The single official profiled runs measure
3.680768845 seconds for LogReg and 5.801478409 seconds for GLM. Relative to C20, these are regressions of
0.385525711 seconds (+11.70%) and 0.581707934 seconds (+11.14%). The raw, unchanged evaluator passes 19/23 gates:
the two time gates and the two reference memo-construction gates fail.

| Workload | C20 | C21 | Single-run change | Rules | Model | Optimization |
|---|---:|---:|---:|---:|---:|---:|
| LogReg | 3.295243134 s | 3.680768845 s | +11.70% | 1.298759084 s | 0.814734670 s | 1.365502714 s |
| GLM | 5.219770475 s | 5.801478409 s | +11.14% | 3.029351635 s | 1.086519601 s | 1.533986409 s |

The memo-construction failures are expected consequences of C21's native-result reuse, but they remain failures of
the unchanged evaluator and are not silently waived. Cost-projection telemetry is exactly equal to C20. LogReg keeps
8,880 prepared requests while reducing Oracle requests from 1,057 to 199 and increasing reused results from 7,823
to 8,681. GLM keeps 11,699 prepared requests while reducing Oracle requests from 2,250 to 450 and increasing reused
results from 9,449 to 11,249. The actual Oracle sees 199 and 450 misses and zero hits because duplicate requests are
eliminated before it. Native-generation counters consequently fall: Oracle evaluations are 811 for LogReg and 3,094
for GLM. These telemetry changes explain the two construction-gate differences; they do not change the selected
native universe.

Both cases pass and return `OPTIMAL`. Exact objective strings, every search counter, native-rule/FED-owner/supply
counts, CP/FED output agreement, LogReg reference CSV, action diagnostics, privacy and runtime audits remain valid.
The full unit suite also preserved RuleMemo roles and native descriptors. The frozen-input verification covers all
12,691 files without differences, both JFR recordings report no data loss, and all owned containers and monitors
terminated. Treating the two known construction telemetry differences separately gives 21/23 semantic checks, with
only the time targets failing; this is an additional assessment, not a replacement for the raw evaluator result.

The native-cost vector extension reduces vector builds from 2,076 to 2,024 for LogReg and from 4,412 to 3,834 for
GLM. It does not reduce total planning time in this run. LogReg allocation falls 2.59% to 1,322,266,704 bytes, while
whole-JVM CPU rises from 18.55 to 21.18 seconds. GLM allocation falls 1.85% to 2,761,479,632 bytes, while CPU rises
from 24.67 to 25.41 seconds. Cgroup throttled-period rates are 26.94% and 17.48%; these one-run resource observations
do not establish causality.

The residual JFR split ranks different next bottlenecks. GLM Rules accounts for 94 execution samples and an estimated
846.8 MB of sampled allocation. Its disjoint nearest-method categories include resolver 282.1 MB, phase/shape/head-map
200.1 MB, cost-guard state 127.0 MB, indexed keys 55.6 MB, and other 181.9 MB. The hottest named rules methods include
`resolvedBindingShape` (11 samples), `occurrenceShape` (10), and `OccurrenceTransferKey.equals` (6); the largest named
allocation sites include `immutableSnapshot` (110.6 MB), `resolvedBindingShape` (103.9 MB), and
`firstUnfixedCostGuard` (63.0 MB). For LogReg, Optimization is the largest timed phase at 1.3655 seconds and has an
estimated 449.3 MB of sampled allocation. Inclusive movement/conditional-bound subsets are 243.1/60.8 MB for LogReg
and 462.0/257.8 MB for GLM; they can overlap and must not be added to disjoint phase totals.

Evidence is recorded in `candidate21-evaluation.json`, `candidate21-comparison.json`,
`candidate21-verification.json`, `candidate21-residual-jfr.json`, the workload summaries/profiles, and the artifact
SHA-256 list.
