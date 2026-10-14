# LogReg bottleneck BEFORE profile

The single authorized BEFORE run `logreg-bottleneck-before-01` passed. Its Memo planning time was
37.329601749 s, of which optimization was 34.797404037 s. Compilation was 38.704098 s and runtime
was 2.811 s. The selected result remained `OPTIMAL` with 2,933,891 nodes, 920 native rules, one
supply, objective
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`, and a maximum
numeric difference of `1.6653345369377348e-16` from the CP result.

## Profile interpretation

JFR collected 2,651 execution samples inside `MemoProgramModel.optimize`. Inclusive sample counts
overlap and are not method timers. They identify one dominant nested path:

| Inclusive method | Samples | Optimizer samples |
| --- | ---: | ---: |
| `ReducedSearch.coreLowerBound` | 1,042 | 39.3% |
| `propagate` | 1,030 | 38.9% |
| `support` | 934 | 35.2% |
| `computeConditionalCostBound` | 888 | 33.5% |
| `movementGroupCost` | 826 | 31.2% |
| `visitBindingCostContexts` | 616 | 23.2% |
| `cached` | 438 | 16.5% |

Exclusive attribution to the nearest Memo frame puts 20.6% of samples in search cache/key/domain
work, 17.6% in shape/key/guard work, 13.3% in reduced-search control, 9.6% directly in support,
7.7% directly in conditional-bound work, 4.9% in binding-context resolution, and 2.3% directly in
propagation. The low direct movement share is expected because its nested shape and resolver work is
attributed to those deeper frames; the inclusive 31.2% is the relevant movement-path measure.

The strongest concrete optimization targets are:

1. Conditional bounds receive 11,135,047 queries and 11,132,120 cache hits (99.974%). Before the
   lookup, `computeConditionalCostBound` scans every participating domain twice and materializes the
   complete set of active values into an `int[]` key
   (`MemoOptimizer.java:1046-1067`). An allocation-free lookup probe over the exact live domain and
   cardinalities can avoid the transient key on the overwhelmingly common hit path without
   weakening the exact-domain cache identity.
2. Constraint propagation issues 38,286,830 support queries. `support` builds a revision stamp and a
   new revision-derived `int[]` before every lookup, then inserts an immutable cloned tuple on a miss
   (`MemoOptimizer.java:455-471`). Direct support work owns 9.6% of execution samples and an estimated
   5.71 GB of sampled allocation weight. Reusing per-check revision keys or caching by fixed
   owner/value under a revision epoch is the next high-value target.
3. Movement-bound evaluation reaches `movementGroupCost` in 31.2% of optimizer samples and
   `visitBindingCostContexts` in 23.2%. That path rebuilds exact environments and contextual HOP
   views (`MemoProgramModel.java:643-675`, `:1041-1050`). Shape caches are effective after lookup
   (binding guard hit 744,987/767,951; repeat proof hit 64,956/66,164), but key projection,
   canonical-guard lookup, resolver maps, and view cloning remain visible. Optimize this path only
   after preserving exact call-frame, guard, loop phase, and cardinality semantics.

JFR allocation sampling estimates 39.03 GB under the optimizer: 24.9% shape/key/guard, 14.6%
support, 14.5% conditional-bound, 11.2% cache/key/domain, and 10.9% binding-context resolution.
There were 178 collections, but total stop-the-world pause was only 0.407 s (maximum 0.013 s), so GC
pause is not the observed 34.8 s bottleneck. Allocation churn still consumes CPU and is evidence for
removing transient keys and context maps.

The external `/proc` monitor captured 28 samples from one coordinator JVM PID over 42.685 s. Peak
RSS/HWM was 652,276/673,780 KiB. Its CPU-tick delta was 6,398; conversion to CPU seconds assumes the
host clock tick rate and is intentionally omitted.

## Reproducibility

The JFR is
`/grid/3/cofee-lm-sweep-mchoi-20260914/fedplanner-oracle-cost-docker-20261013/logreg-bottleneck-before-01/cases/ml_logreg/fed.jfr`
(SHA-256 `927add24ce5c3c4a8bfac374e530391f755a1c767b8b0adec852b25583eaa83f`, 8,578,331 bytes).
It parsed without data-loss events. Recursive diffs between the harness `frozen-inputs` and
`experiments/optimizer-reuse-20261014/after-engine` are empty for main classes, test classes, main
sources, and test sources. The harness class preflight also passed all seven checked classes.

This is a single profiled run. Sample percentages locate CPU paths; they do not establish a stable
wall-clock improvement until the one authorized AFTER run is compared with the same settings.

The planned AFTER implementation uses exact scoped `BitSet` support-cache identity and an exact
live-domain/cardinality bound probe. It does not substitute a revision-only approximation for the
domain key; the comparison must therefore evaluate exact-result parity as well as speed.
