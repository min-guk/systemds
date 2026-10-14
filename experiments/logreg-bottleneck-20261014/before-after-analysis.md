# LogReg bottleneck BEFORE/AFTER profile comparison

Both authorized runs passed under the same official Docker wrapper, 4 CPU / 8 GiB / three-worker
configuration, JFR profile, and LogReg-only workload. Each condition was executed once.

## Observed result

| Metric | BEFORE | AFTER | Observed change |
| --- | ---: | ---: | ---: |
| Memo planning | 37.329601749 s | 26.819446540 s | -10.510155209 s (-28.1550%) |
| Optimization phase | 34.797404037 s | 23.714206977 s | -11.083197060 s (-31.8506%) |
| Compilation | 38.704098 s | 28.121125 s | -10.582973 s (-27.3433%) |
| Runtime | 2.811 s | 3.034 s | +0.223 s (+7.9331%) |
| Monitored coordinator CPU | 63.98 s | 51.61 s | -12.37 s (-19.3342%) |
| JFR optimizer execution samples | 2,651 | 1,696 | -955 (-36.0241%) |
| JFR optimizer allocation weight | 39.031 GB | 30.120 GB | -8.911 GB (-22.8304%) |
| GC stop-the-world pause | 0.4067 s | 0.3689 s | -0.0378 s (-9.2864%) |
| Peak JVM HWM | 673,780 KiB | 785,668 KiB | +111,888 KiB (+16.6060%) |

The execution-time increase and HWM increase are reported as observed. They are not optimizer
regressions proven by one short profiled run; the process monitor covers the whole coordinator JVM,
and JFR allocation weights are statistical estimates.

## Correctness and search parity

The exact BigDecimal objective, 2,933,891 search nodes, 920 native rules, one supply, selected action
diagnostics, federated fingerprint, runtime-audit result, Oracle/native-generation counters,
cost-projection counters, and shape-reuse counters are identical. The generated `fed-model.csv` is
byte-identical (332 bytes, SHA-256
`c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`). Both comparisons have a
maximum CP/FED numeric difference of `1.6653345369377348e-16`.

The Memo planner trace also retained `oracleHits=0` and `oracleMisses=1057`. Runtime audit files are
not byte-identical because their run-local `pid` changed from 206 to 205. Both pairs have identical
sizes and become exactly equal after removing only that `pid` field (13 capability rows and 20
conversion-frontier rows); both result summaries report zero audit violations.

`supportQueries` means support-cache misses that execute DFS in both versions. It fell from
38,286,830 to 171,750 (-99.5514%). BEFORE did not expose `supportCacheHits`, so its hit and total
request counts remain unavailable. AFTER recorded 38,226,419 hits and 171,750 misses among
38,398,169 requests, a 99.5527% hit rate. This is consistent with exact scoped-domain reuse; it is
not a change in counter meaning.

The 82,050,731 to 286,080 reduction in generic truth-cache hits is secondary evidence that support
requests now return from the scoped support cache before repeatedly entering predicate evaluation.
Check evaluations changed only from 60,690 to 60,227, while worklist visits, removed rules, bound
prunes, conditional-bound queries/hits, cost evaluations/hits, and wide-bound relaxations are
identical.

## Profile attribution

Absolute inclusive optimizer samples fell as follows. These counts overlap and are statistical:

| Path | BEFORE | AFTER | Change |
| --- | ---: | ---: | ---: |
| `propagate` | 1,030 | 388 | -62.3% |
| `support` | 934 | 333 | -64.3% |
| `supportDfs` | 516 | 153 | -70.3% |
| `cached` (old tuple cache) | 438 | 1 | -99.8% |
| `ReducedSearch.coreLowerBound` | 1,042 | 923 | -11.4% |
| `computeConditionalCostBound` | 888 | 763 | -14.1% |
| `movementGroupCost` | 826 | 711 | -13.9% |
| `visitBindingCostContexts` | 616 | 543 | -11.9% |

The comparable exclusive cache/key/domain bucket includes the new `cachedDomains`,
`scopedDomainHash`, and `ScopedDomainIdentity.sameDomains` methods. It fell from 546 to 244 samples
(-55.3%) and from 4.375 GB to 0.844 GB of allocation weight (-80.7%). Direct support allocation
weight fell from 5.711 GB to 0.006 GB (-99.89%).

The residual bottleneck has shifted to conditional bound and movement/context work. In AFTER,
`coreLowerBound` appears in 54.4% of optimizer samples, `computeConditionalCostBound` in 45.0%,
`movementGroupCost` in 41.9%, `visitJoint` in 44.7%, and `visitBindingCostContexts` in 32.0%.
These are inclusive percentages and overlap along the same nested path. The conditional-bound
allocation bucket changed only from 5.666 GB to 5.544 GB (-2.17%) because it includes
`minimumTerm`, movement cost, resolver, and shape work; it must not be interpreted as cache-key
allocation alone. Shape/key/guard allocation weight rose from 9.705 GB to 10.275 GB in the samples,
making exact contextual shape and guard processing the clearest next profiling target.

## Reproducibility

The AFTER engine manifest contains 12,498 files. A fresh full hash verification found no missing,
extra, or mismatched files. Recursive diffs between the AFTER engine and the Docker run's
`frozen-inputs` are empty for main classes, test classes, main sources, and test sources. The wrapper
class preflight passed, the JFR parser passed with no data-loss events, and the run container and
monitor process were absent after completion.

Raw JFR SHA-256 values:

- BEFORE: `927add24ce5c3c4a8bfac374e530391f755a1c767b8b0adec852b25583eaa83f`
- AFTER: `cc213bda455d79e441fbf88daaa7ade0cc2e9303dc3d9ae48d3d3c65b28571e5`

The timing changes are single-run observations under JFR. They establish that this measured run
improved while preserving all recorded semantic gates; they do not establish a performance
distribution or confidence interval.
