# G009 P3 final host diagnostic

Date: 2026-09-20. Revision: `c0dc78e8193bd54e37b3e27f4524799e02604e76`.

This is the single post-P3 host diagnostic used to decide the P4 entry gate. It is
not an official Docker performance qualification and it is not a 10x PASS.

## Frozen result

- `buildAnalysis`: `450,976 ms` (`450.976635131 s`)
- full canonical snapshot export: `365.349828672 s`
- process wall: `13:43.49`
- maximum RSS: `35,859,276 KiB`
- full snapshot: `1,966,627,354` bytes
- snapshot SHA-256: `78bb2d116061fc7d8aad97059c56cba3665367215bb3fe3a1679a613a077ed09`
- exit status: `0`

Evidence is preserved under:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p3-final-diagnostic-20260920/
```

The prior contemporaneous acyclic-fast-path diagnostic was `716.591801515 s`.
The post-P3 build ratio is therefore `0.629335`, a `37.07%` reduction or
`1.589x` speedup. The declared tenth-time budget is `71.659180152 s`, so P3 is
still `379.317 s` above the budget.

## Work attribution

| Counter | Prior | Post-P3 | Change |
|---|---:|---:|---:|
| proof alternatives | 284,280,699 | 21,822,984 | -92.32% |
| proof dependency edges | 513,715,666 | 37,065,252 | -92.78% |
| owner compaction scan | 284,280,699 | 3,641,748 | -98.72% |
| support leaves | 4,114,384 | 5,152,026 | +25.22% |
| receipt relation slots/materialized receipts | 99,632 | 99,632 | unchanged |

The main exclusive build costs were closure replay `304.303 s`, proof topology
`41.197 s`, public proof materialization `41.026 s`, support-product
materialization `10.957 s`, and proof grounding `6.897 s`. Selective CFG replay
executed 18 passes, reused 3,465 reader results, recomputed 11,396, and used 30
unsafe full fallbacks. The exact full snapshot export then added `365.350 s` and
materialized all 99,632 receipt slots.

## Decision

P1-P3 removed most graph-alternative scanning but did not remove support-product,
closure publication, or full receipt-consumer costs. Both fixed candidate
formation and its existing full consumer remain far above the tenth-time budget.
The P4 factorized support-to-consumer path is therefore required; stopping at P3
or reporting the counter reductions as wall-clock speedup would be incorrect.
