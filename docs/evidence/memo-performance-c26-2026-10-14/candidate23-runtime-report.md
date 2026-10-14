# Candidate 23 official runtime report

Candidate 23 does not meet either three-second planning target. The single official profiled runs measure
3.658750194 seconds for LogReg and 4.931508331 seconds for GLM. Relative to C22, LogReg regresses by
0.912537585 seconds (+33.23%), while GLM improves by 0.508780251 seconds (-9.35%). Relative to C20, LogReg is
11.03% slower and GLM is 5.52% faster. These one-run comparisons do not establish that the exact-decimal cache
caused either timing change.

| Workload | C20 | C21 | C22 | C23 | Target |
|---|---:|---:|---:|---:|---:|
| LogReg | 3.295243134 s | 3.680768845 s | 2.746212609 s | 3.658750194 s | FAIL |
| GLM | 5.219770475 s | 5.801478409 s | 5.440288582 s | 4.931508331 s | FAIL |

LogReg phases are facts 0.057088185, Rules 1.609796857, Model 0.805290586, Optimization 1.119164097, and projection
0.067410469 seconds. GLM phases are facts 0.066413033, Rules 2.197767515, Model 1.115436361, Optimization
1.444314228, and projection 0.107577194 seconds. Against C22, GLM Optimization improves 13.15%, while LogReg
Optimization regresses 12.51%. Rules and Model account for most of LogReg's total regression. The implementation
change is inside exact conversion, but the other phase movement shows that one cold run cannot isolate its effect.

The unchanged evaluator passes 19/23 gates. Both time gates and both original-reference memo-construction gates
fail. C23 is exactly equal to C22 for native request, native generation, native-cost reuse, and cost-projection
telemetry; the construction failures remain the previously explained C21 telemetry differences from the published
references. The raw evaluator is preserved without changing or waiving a gate. A separate telemetry-aware view is
21/23, with both time gates still failing.

Both cases pass and return `OPTIMAL`. Exact objective strings, native-rule/FED-owner/supply counts, cost-projection
primitive counts and budgets, output CSV bytes, CP/FED numerical checks, privacy, and runtime audits remain unchanged.
LogReg search counters are identical across C20 through C23. C23 GLM's counters are identical to C20/C21 and differ
from C22's one-run traversal values: checks 97,479 versus 97,344, check-cache hits 77,037 versus 76,999, support
queries 85,676 versus 85,590, support-witness hits 49,979 versus 49,805, binary-support hits 37,945 versus 37,771,
and worklist visits 14,370 versus 14,353. Nodes, exact cost, cost evaluations, bound counters, selected output, and
native universe remain equal. This reversal demonstrates that the C22 traversal counters were not a stable semantic
delta; no Java map-ordering cause is asserted.

JFR estimated total allocation falls versus C22 by 4.81% to 1,226,926,760 bytes for LogReg and by 1.18% to
2,647,937,808 bytes for GLM. Within the disjoint planner phase classification, Optimization allocation falls from
440.0 to 341.5 MB for LogReg and from 732.3 to 665.3 MB for GLM. This is consistent with avoiding repeated exact
decimal objects, but sampled allocation is not a direct count of cache savings. Whole-JVM CPU is 23.42 and 25.55
seconds, HWM is 429,976 and 803,780 KiB, and STW pause is 0.193826 and 0.286661 seconds. Cgroup throttled-period
rates are 24.26% and 19.02%; no resource metric is subtracted from planning time.

The residual GLM profile has Rules at 56 execution samples and 920.3 MB sampled allocation, Optimization at 28
samples and 665.3 MB, and Model at 19 samples and 609.1 MB. Within Rules, disjoint categories are resolver 383.4 MB,
phase/shape/head-map 199.4 MB, indexed keys 191.8 MB, cost-guard state 63.9 MB, and other 81.8 MB. The largest named
Rules allocation sites are `buildIndexedAtoms` (176.1 MB), `visitJoint` (135.2 MB), `immutableSnapshot` (78.6 MB),
`resolvedBindingShape` (76.2 MB), and `withPhase` (71.3 MB). Inclusive movement and conditional-bound subsets are
431.6 MB and 206.5 MB; these overlap phase totals and one another.

Both Docker manifests preserve the pinned image, 4 CPU, 8 GiB, network-none contract and frozen inputs. The verifier
covers all 12,700 files without a difference, both JFR recordings report no data loss, and all owned containers and
monitors terminated. Evidence is recorded in `candidate23-evaluation.json`, `candidate23-comparison.json`,
`candidate23-verification.json`, `candidate23-residual-jfr.json`, workload summaries, profiles, raw logs, and the
artifact SHA-256 list.
