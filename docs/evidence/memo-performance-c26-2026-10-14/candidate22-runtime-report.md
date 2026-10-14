# Candidate 22 official runtime report

Candidate 22 brings LogReg below the target for the first time, at 2.746212609 seconds, but GLM remains above the
target at 5.440288582 seconds. Relative to C21, the single official profiled runs are 0.934556236 seconds faster
(-25.39%) for LogReg and 0.361189827 seconds faster (-6.23%) for GLM. Relative to C20, LogReg is 16.66% faster and
GLM is 4.22% slower. These are one-run comparisons and do not isolate the effect of any individual C22 change.

| Workload | C20 | C21 | C22 | Target |
|---|---:|---:|---:|---:|
| LogReg | 3.295243134 s | 3.680768845 s | 2.746212609 s | PASS |
| GLM | 5.219770475 s | 5.801478409 s | 5.440288582 s | FAIL |

LogReg phases are facts 0.044627954, Rules 1.052233002, Model 0.572325546, Optimization 0.994724945, and projection
0.082301162 seconds. GLM phases are facts 0.050745147, Rules 2.338806076, Model 1.231029501, Optimization
1.662934425, and projection 0.156773433 seconds. Compared with C21, GLM Rules falls 22.80%, while Model rises 13.30%,
Optimization rises 8.41%, and projection rises 62.06%.

The unchanged evaluator passes 20/23 gates. Its failures are the GLM time gate and both reference
memo-construction gates. The construction failures are inherited from C21: C22 is exactly equal to C21 for native
request, native-generation, and cost-projection telemetry. The evaluator and its raw result are preserved without
waiving those failures. Separately classifying the two already-explained telemetry differences gives 22/23 semantic
checks, with only the GLM time target failing; this supplementary classification does not replace the raw result.

Both cases pass and return `OPTIMAL`. Exact objective strings, native-rule/FED-owner/supply counts, cost-projection
primitive counts and budgets, frozen runtime inputs, CP/FED output values, output CSV bytes, privacy, and runtime
audits remain unchanged. Both Docker manifests record the pinned image, `--cpus 4`, `--memory 8g`, no network, and
the same 2,150 MB local SystemDS memory budget. LogReg search counters are identical to C21. GLM has the same 1,954
nodes, exact objective, native universe, cost evaluations, cost-cache hits, bound counters, and output, but six
traversal counters differ: checks 97,479→97,344, check-cache hits 77,037→76,999, support queries 85,676→85,590,
support-witness hits 49,979→49,805, binary-support hits 37,945→37,771, and worklist visits 14,370→14,353. This is
reported as an observable search-path difference; the single run does not identify Java map ordering or any one C22
change as its cause.

Native-cost vector builds fall from C21's 2,024 to 1,666 for LogReg and from 3,834 to 3,484 for GLM. Occurrence
transfer builds and entries remain 890/582 and 1,820/907; the additional requests are all hits. The C21 GLM JFR had
six execution samples at `OccurrenceTransferKey.equals`; C22 has none for GLM and one for LogReg. This sampled change
is consistent with reduced key overhead but is not a causal timing measurement.

Estimated JFR allocation falls against C21 by 2.52% to 1,288,879,888 bytes for LogReg and by 2.96% to 2,679,641,912
bytes for GLM. Whole-JVM CPU is 16.54 and 31.25 seconds; GLM CPU, HWM (870,464 KiB), and STW pause (0.321113 seconds)
all rise despite lower sampled allocation. Cgroup throttled-period rates are 30.64% and 22.71%. No resource metric is
subtracted from planning time.

The C22 residual profile still places GLM Rules first by elapsed time, with 61 execution samples and 807.3 MB of
sampled allocation, followed by Optimization with 49 samples and 732.3 MB, then Model with 27 samples and 536.3 MB.
Within Rules, disjoint allocation categories are resolver 371.5 MB, phase/shape/head-map 236.0 MB, cost-guard state
69.3 MB, indexed keys 51.7 MB, and other 78.8 MB. The largest named Rules allocation sites are
`immutableSnapshot` (168.4 MB), `occurrenceShape` (91.9 MB), `resolvedBindingShape` (79.3 MB), and
`LoopDependencyState` construction (66.0 MB). Inclusive movement and conditional-bound subsets are 458.9 MB and
214.7 MB for GLM; they overlap phase totals and each other.

The frozen-input verifier covers all 12,698 files without a difference, both JFR recordings report no data loss,
and all owned containers and monitors terminated. Evidence is recorded in `candidate22-evaluation.json`,
`candidate22-comparison.json`, `candidate22-verification.json`, `candidate22-residual-jfr.json`, workload summaries,
profiles, raw logs, and the artifact SHA-256 list.
