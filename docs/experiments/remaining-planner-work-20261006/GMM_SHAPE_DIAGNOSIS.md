> This is the diagnosis of the intermediate carrier implementation. The final definite-assignment fix removes invented undefined reads. Final GMM W3 objective is 18,305.956166703723ms and W1 is 25,042.74958424706ms; both compile successfully. See validation.json and all14-comparison.json for the final source.

# GMM W3 branch-carrier cost decomposition

## Result

The objective increase is caused by loss of exact matrix dimensions across the
new branch carriers.  It is not carrier execution cost, a selected-state change,
an auxiliary operation cost, or an execution-frequency change.

The comparison was run in the pinned Docker image with the same GMM W3 script,
configuration, cost environment, planner classes, and probe.  Variant A overlays
a `DMLTranslator` compiled from the current source with only the call to
`FederatedBranchExitNormalizer.normalize` removed.  Variant B uses the current
translator.  An isolated `LocalPhysicalOptimizer` overlay evaluates and prints
every selected contribution before emission.

| Metric | No branch normalization (A) | Current normalization (B) |
| --- | ---: | ---: |
| Objective (ms) | 18,305.956166703723 | 8,760,104.448983088 |
| Graph nodes | 392 | 408 |
| Decision nodes | 389 | 405 |
| Contributions | 1,309 | 1,366 |
| Selected local materializations | 0 | 0 |
| Selected relocations | 1 | 1 |

For both variants, the sum rebuilt from the individual contributions is bitwise
equal to the selected objective certificate.  This avoids recosting a mutable,
post-lowering HOP graph.

## Dominant contribution changes

Five matched operator groups account for 8,728,632.098 ms, or 99.849% of the
8,741,798.493 ms objective increase.

| GMM source | A contribution | B contribution | Increase |
| --- | ---: | ---: | ---: |
| line 275 matrix multiply | 4,501.374 | 2,276,752.226 | 2,272,250.851 |
| line 276 matrix multiply | 4,501.374 | 2,276,752.226 | 2,272,250.851 |
| line 143 matrix multiply | 1,147.384 | 1,797,429.445 | 1,796,282.061 |
| line 168 matrix multiply | 1,147.384 | 1,797,429.445 | 1,796,282.061 |
| shared transpose/multiply group | 4,658.145 | 596,224.417 | 591,566.272 |

The exact execution weights are unchanged: line 143/168 operators have weight
`11.0`, and line 275/276 operators have weight `10.5` in both variants.

## Shape evidence

Without carriers, immutable abstract-shape evidence resolves the affected
outputs:

- Lines 143 and 168: `4 x 128`, dense bytes `4,248`.
- Lines 275 and 276: `50,000 x 4`, dense bytes `1,600,152`.
- The shared transpose: `4 x 50,000`, dense bytes `1,600,152`.

With carriers, the affected dimensions become unknown:

- `precision_chol` has an `UNKNOWN x 128` branch join.  Its VVI else path alone
  is `4 x 128`, while the other model branch has a different row count.
- `predict_prob` has a `50,000 x UNKNOWN` carrier shape.
- Downstream line 143/168 results become `UNKNOWN x 128`; line 275/276 results
  become `50,000 x UNKNOWN`.

Their exact and bounded byte estimates therefore become `NaN`.  Cost estimation
then reaches the unresolved HOP output estimate, which is the
`10,737,418,240`-byte unknown-shape envelope.  Repeated matrix-multiply costs on
that envelope create the observed objective increase.

The carrier nodes themselves have zero direct execution cost.  The selected
placement states and relocation count are unchanged for the compared common
work.  The additional cost is downstream fallout from conservative shape joins.

## Evidence

- `results/A.log` and `results/B.log`: pre-emission contribution, shape, byte,
  and execution-weight records.
- `results/A.json` and `results/B.json`: selected plans and objective
  certificates.
- `results/A.command.json` and `results/B.command.json`: exact pinned Docker
  invocations.
- `src/.../LocalPhysicalOptimizer.java`: isolated contribution instrumentation.
- `noop-src/org/apache/sysds/parser/DMLTranslator.java`: current translator with
  only branch normalization disabled.

No production source or shared build output was modified by this audit.
