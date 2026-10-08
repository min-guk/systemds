# Rule-directed generation Docker comparison

## Workload contract

The non-default `weighted_quaternary_with_private_aggregate` fixture executes
WSLOSS and WCEMM over a public federated operand. A separate federated
`PRIVATE_AGGREGATE_TO_PUBLIC` input is aggregated in the same plan. This is not
a public-only workload, but the weighted operands themselves are public.

Both weighted kernels must appear as runtime heavy hitters and as AVAILABLE
candidate-audit rows. CP and FED output fingerprints, canonical objective,
analysis fingerprint, normalized-plan fingerprint, and cost-surface fingerprint
must match.

Initial direct-protected probes used a single federated range, which is a FULL
layout. The weighted rules explicitly reject that layout, and privacy closure
reported no safe placement. Those negative FULL probes are retained under
`weighted-baseline-probe-v1` and `weighted-baseline-probe-v2`; they do not show
that protected weighted execution is generally unsupported.

A bounded follow-up used two 4x3 private-aggregate ROW shards. Direct WSLOSS
and WCEMM succeeded on both baseline and optimized engines. Output, objective,
analysis/plan/cost fingerprints, model counts, runtime heavy hitters, and
AVAILABLE candidate-audit evidence matched exactly. See
`protected-row-parity.json`. Its single run per engine is semantic parity
evidence and is not included in the performance statistics below.

## Three-run result

| Metric | Baseline median [min, max] | Optimized median [min, max] | Median change |
|---|---:|---:|---:|
| FED compilation | 2.225791 s [1.895206, 2.531338] | 1.888799 s [1.631315, 2.172761] | -15.14% |
| Candidate planning total | 1.613485 s [1.117159, 1.681843] | 1.221276 s [1.084147, 1.429378] | -24.31% |
| Candidate analysis | 0.922607 s [0.816034, 1.187763] | 0.926258 s [0.782085, 1.059468] | +0.40% |
| JFR heap at GC boundaries | 78.83 MiB [63.08, 78.97] | 66.69 MiB [64.76, 78.91] | -15.40% |
| Sampled whole container memory | 754.20 MiB [748.80, 839.90] | 783.10 MiB [682.80, 805.10] | +3.83% |

All six measured runs passed. The following remained identical across engines:

- output fingerprint and canonical objective bits;
- analysis, normalized-plan, and physical cost-surface fingerprints;
- 53 candidate audit rows, 55 pre-privacy states, 54 published states;
- 58 alternatives and 53 decisions;
- WSLOSS/WCEMM runtime and candidate-audit evidence.

The compile and total-planning medians improved, but the three-run ranges
overlap. Analysis time, the phase most directly associated with candidate
generation, was effectively unchanged. This small workload establishes semantic
parity and a repeatable measurement path; it does not establish a statistically
reliable candidate-generation speedup. Docker memory is sampled at roughly
two-second effective intervals and is a lower-bound observation.

## Regression run

The optimized engine passed one canonical-proof Docker run containing:

- `joint_correlated_aa`: CP/FED output match and canonical proof pass;
- `joint_function_calls`: CP/FED output match and canonical proof pass;
- `joint_independent_private_ab_negative`: expected infeasible-private-tuple
  rejection preserved.

Machine-readable results are `weighted-comparison.json`,
`weighted-baseline-3runs.json`, `weighted-after-3runs.json`, and
`regression-after-summary.json`. Protected ROW parity is recorded in
`protected-row-parity.json`. Raw manifests, logs, audits, JFR recordings,
and memory samples are under
`/grid/3/cofee-lm-sweep-mchoi-20260914/rule-directed-generation-20261008/verification`.
