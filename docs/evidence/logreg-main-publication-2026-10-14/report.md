# Integrated-main LogReg publication validation

The integrated main candidate completed its one authorized official Docker JFR run. It passed every
publication gate and planned in 7.770025473 seconds, below the strict 10-second threshold. No retry,
GLM run, or host workload was performed.

## Timing and resources

| Measurement | Primary baseline | Candidate03 | Integrated main | Main vs primary | Main vs Candidate03 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Memo planning | 26.819446540 s | 6.672535049 s | 7.770025473 s | -71.0284% | +16.4479% |
| Optimization | 23.714206977 s | 4.237294143 s | 3.604053576 s | -84.8021% | -14.9445% |
| Compilation | 28.121125 s | 8.057937 s | 9.103289 s | -67.6283% | +12.9729% |
| Training execution | 3.034 s | 3.165 s | 2.730 s | -10.0198% | -13.7441% |
| Search nodes | 2,933,891 | 625,916 | 625,916 | -78.6660% | unchanged |
| Coordinator CPU | — | 30.50 s | 33.07 s | — | +8.4262% |
| Peak HWM | — | 721,656 KiB | 1,115,208 KiB | — | +54.5346% |
| JFR allocation estimate | 30.120 GB | 4.857 GB | 4.647 GB | -84.5713% | -4.3184% |
| GC stop-the-world pause | 0.368887 s | 0.225020 s | 0.328310 s | -11.0031% | +45.9027% |

The integrated phase split was facts 0.050421 s, rules 3.028236 s, model 1.034926 s,
optimization 3.604054 s, and projection 0.052389 s. Compared with Candidate03, optimization was
faster while rules and model construction were slower; total planning remained 2.230 seconds below
the publication threshold. CPU, memory, allocation, GC, compilation and execution are single-run
observations. The CPU interval covers the whole coordinator JVM, and JFR allocation weights are
estimates.

## Exactness and publication gates

The solver returned `OPTIMAL` with exact objective
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`.
The numerical maximum absolute error was `1.6653345369377348e-16`. The output CSV matched both frozen
references byte for byte (SHA-256 `c1afd1422eb05ef396bbc13b48fbd09d5966995de43d9f92147ebb1599d5b848`).
FED fingerprint, selected actions, normalized runtime audit, Oracle trace (0 hits, 1,057 misses),
native universe and request trace, workers, native rules, and supplies also matched both references.
The runtime audit was clean.

The frozen manifest contained 12,527 files. Manifest hashes and the Docker main classes, test classes,
main sources, and test sources all matched. The engine and staged `MemoOptimizer.class` hashes were
both `77a746bac775189f0dafa391ba39cbddb1a2e1ae58ee9095e930058289a725c5`. JFR parsed without data
loss, and the run-owned container and monitor terminated.

## Forest and profile evidence

The one forest trace reported 8,716 attempts, 3,310 applicable tails, 3,310 completions, and 570,702
cells, exactly matching Candidate03. Nodes also remained 625,916. Movement-reuse, shape-reuse, and
construction counters happened to match Candidate03; their equality was deliberately not required
because integrated privacy and return-shape fixes may legitimately change request patterns.

Optimizer execution samples were 155 versus Candidate03's 198. Estimated optimizer allocation was
4,646,890,064 bytes versus 4,856,618,320. The largest nearest-method buckets were shape/guard/cost
43 samples, optimizer-other 32, model-cost-other 24, reduced-search-control 19, and cache/key/domain
16. `tryForestTail` remained the largest individual nearest Memo method at 16 samples. These sample
counts describe this single recording and do not establish confidence intervals.

## Pre-run integration evidence

The final offline Maven build passed in 25.059 seconds. The assertions-enabled 55-class, 474-test
suite passed in 120.506 seconds with 8 MiB host stack, and the unchanged harness suite passed all 45
tests. Independent integration review approved after the test-only constructor argument fix; the
production source remained unchanged after review.
