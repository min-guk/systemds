# Candidate02 LogReg result

Candidate02 completed its one authorized official Docker JFR run. The run passed all semantic,
output, audit, Oracle, native-universe and frozen-input gates, but missed the strict planning target:
15.926899887 s versus the required value below 10 s.

## Timing

| Measurement | Primary baseline | Candidate01 | Candidate02 | Candidate01 to 02 |
| --- | ---: | ---: | ---: | ---: |
| Memo planning | 26.819446540 s | 16.103018585 s | 15.926899887 s | -0.176118698 s (-1.0937%) |
| Optimization | 23.714206977 s | 13.563777113 s | 13.318014527 s | -0.245762586 s (-1.8119%) |
| Compilation | — | 17.307357 s | 17.179331 s | -0.128026 s |
| Execution | — | 2.690 s | 2.398 s | -0.292 s |
| Coordinator CPU, monitored interval | — | 39.61 s | 38.93 s | -0.68 s |
| Peak HWM | — | 667,616 KiB | 875,108 KiB | +207,492 KiB |

The process metrics cover the whole coordinator JVM during the sampled interval and are not
optimizer-only. Each candidate is one JFR observation, so small timing and sample differences are
not confidence intervals.

## Correctness and reproducibility gates

The run and case passed, the solver returned `OPTIMAL`, and the exact objective remained
`1301.1526531142989790221751587629165844428591469750244868919253349304199218750`.
The output CSV bytes, FED fingerprint, action diagnostics, normalized runtime audit, Oracle trace
(0 hits, 1,057 misses), native universe and request traces, native-rule count, supply count and worker
count all match the primary baseline. Construction, movement-reuse and shape-reuse traces also match
candidate01 exactly. The frozen manifest contains 12,509 files; all engine hashes and all four Docker
input trees match. The staged `MemoOptimizer.class` SHA-256 is
`6621c011f052cb46ad580c0e64e9ca34444ec4d9afd4e5594a308d979fb198eb`, exactly equal to the frozen
engine class. Search nodes remained 2,933,891, satisfying the candidate01 differential gate.

## Fresh profile

Candidate02 recorded 1,068 optimizer samples and 8,423,739,584 estimated allocation bytes, versus
candidate01's 1,110 and 10,103,625,680. JFR reported no data-loss events. GC stop-the-world pause was
0.354236 s versus 0.289754 s, and maximum sampled heap was 442,676,256 bytes versus 293,119,488.

Focused inclusive samples on stacks containing `MemoOptimizer` show the intended representation
change executed: `HashMap$TreeNode.getTreeNode`, `BitSet.hashCode`, `BitSet.equals`, and
`branchConnectivity` fell from 94/98/73/54 to 0/0/0/0. `enumerateCore` leaf samples fell from 155 to
71, although it remained on 1,052 of 1,065 optimizer stacks. The gain was offset by persistent or
larger solver work: `cachedDomains` was inclusive in 299 samples and leaf in 40 (333/9 before),
`scopedDomainHash` 110/110 (140/42), `sameDomains` 56/54 (89/16), and `coreLowerBound` inclusive 314
(291 before). Movement was stable rather than a new bottleneck: `movementGroupCost` 97 inclusive
samples and `visitBindingCostContexts` 29, versus 84 and 28.

The nearest-method buckets similarly leave 330 samples (30.90%) in search cache/key/domain, 208
(19.48%) in solver-other, 171 (16.01%) in reduced search control, and 125 (11.70%) in conditional
cost bound. The compact representation eliminated the prior library collision/BitSet leaf frames,
but cache probing, mixed hashing/equality, lower-bound invalidation and unchanged 2.93M-node traversal
still dominate enough work to limit the end-to-end improvement to 1.09% planning in this run.
