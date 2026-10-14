# Candidate03 LogReg result

Candidate03 completed its one authorized official Docker JFR run and passed the strict target. Memo
planning was 6.672535049 seconds, below 10 seconds. Every primary correctness, output, audit, Oracle,
native-universe, JFR and frozen-input gate passed. No retry or other workload was run.

## Performance

| Measurement | Primary baseline | Candidate02 | Candidate03 | Candidate03 vs baseline | Candidate03 vs 02 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Memo planning | 26.819446540 s | 15.926899887 s | 6.672535049 s | -75.1205% | -58.1052% |
| Optimization | 23.714206977 s | 13.318014527 s | 4.237294143 s | -82.1318% | -68.1837% |
| Compilation | 28.121125 s | 17.179331 s | 8.057937 s | -71.3456% | -53.0951% |
| Training execution | 3.034 s | 2.398 s | 3.165 s | +4.3177% | +31.9850% |
| Search nodes | 2,933,891 | 2,933,891 | 625,916 | -78.6660% | -78.6660% |
| Coordinator CPU | — | 38.93 s | 30.50 s | — | -21.6543% |
| Peak HWM | — | 875,108 KiB | 721,656 KiB | — | -17.5352% |
| JFR allocation estimate | 30.120 GB | 8.424 GB | 4.857 GB | -83.8757% | -42.3461% |
| GC stop-the-world pause | 0.368887 s | 0.354236 s | 0.225020 s | -39.0005% | -36.4775% |

Execution, CPU, memory, allocation, and GC values are single-run observations. The CPU interval covers
the whole coordinator JVM, and JFR allocation weights are estimates.

## Exactness and frozen inputs

The optimizer returned `OPTIMAL` with the unchanged exact objective
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`.
The numerical maximum absolute error stayed `1.6653345369377348e-16`. The output CSV is byte-identical
to the primary baseline (SHA-256 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`).
FED fingerprint, selected action diagnostics, normalized runtime audits, Oracle trace (0 hits, 1,057
misses), construction trace, native universe and requests, native rules, supply count, and worker count
all match the primary reference.

The frozen manifest contains 12,512 files and all manifest and Docker input comparisons passed. The
engine and staged `MemoOptimizer.class` hashes both equal
`77a746bac775189f0dafa391ba39cbddb1a2e1ae58ee9095e930058289a725c5`. JFR parsed with no data loss,
and the run-owned container and monitor terminated.

## Forest-tail evidence and residual profile

The one exact trace reports 8,716 attempts, 3,310 applicable tails, 3,310 completions, and 570,702
charged cells. The trace passed exact-format, uniqueness, nonnegative, ordering, and exercised-path
gates. Nodes fell from 2,933,891 to 625,916 while exact objective and all semantic gates remained
unchanged.

Optimizer execution samples fell from Candidate02's 1,068 to 198. Estimated optimizer allocation
fell from 8,423,739,584 to 4,856,618,320 bytes. On stacks containing `MemoOptimizer`, `enumerateCore`
fell from 1,052 inclusive / 71 leaf samples to 185 / 2; `coreLowerBound` from 314 to 59 inclusive;
`cachedDomains` from 299 / 40 to 18 / 2; `propagate` from 376 / 24 to 68 / 1. The new
`tryForestTail` path appeared in 51 inclusive and 11 leaf samples. `movementGroupCost` remained 79
inclusive samples and `visitBindingCostContexts` 25, so movement is a larger fraction of a much
smaller optimizer profile rather than evidence of increased absolute work.
