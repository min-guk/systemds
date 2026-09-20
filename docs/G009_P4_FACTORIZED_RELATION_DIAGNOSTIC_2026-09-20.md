# G009 P4 factorized-relation host diagnostic

Date: 2026-09-20. Revision: `e29f4fc30d19cc228b013e786d76424cb7954d7e`.

This is the single post-P4 host diagnostic used to evaluate the factorized support
implementation and choose the next optimization. It is not the official paired
Docker E2E qualification and it is not a 10x PASS. Canonical snapshot export is
reported separately because the fixed `TcandE2E` contract excludes diagnostic
serialization.

## Frozen result

- `buildAnalysis`: `279,017 ms` (`279.017960532 s`)
- full canonical snapshot export: `368.578775850 s`
- process wall: `11:15.14`
- maximum RSS: `14,030,516 KiB`
- full snapshot: `1,966,627,354` bytes
- snapshot SHA-256: `78bb2d116061fc7d8aad97059c56cba3665367215bb3fe3a1679a613a077ed09`
- frozen-P3 byte comparison: identical
- exit status: `0`

Evidence is preserved under:

```text
/grid/3/cofee-lm-sweep-mchoi-20260914/g009-p4-grid-diagnostic-20260920-r7/
```

Against the contemporaneous initial diagnostic (`716.591801515 s`), P4 reduces
the measured build ratio to `0.389409`: a `61.06%` reduction or `2.568x`
speedup. Against P3 (`450.976635131 s`), P4 is `38.13%` shorter or `1.616x`
faster. The arithmetic tenth-time reference is `71.659180152 s`; P4 remains
`207.359 s` above it and requires another `3.894x` reduction in this narrower
build metric.

## Why the first P4 attempt did not shorten the run

The first factorized implementation retained the mathematical relation but
reconstructed canonical views during every closure pass. A 30-second JFR of the
regressed run attributed 50.2% of samples to repeated
`distinctBindingAtoms()`, 21.6% to relation union, and 20.7% to uncached route
signatures, with 125 young collections. A bounded trace then showed more than
one million singleton routes: the code represented complete Cartesian grids as
an OR of singleton tuples and later re-expanded them in relocation and closure
projections.

The accepted P4 correction therefore:

1. memoizes immutable binding/source projections and route signatures;
2. performs exact existential selection directly on the factorized relation;
3. preserves relocation filtering without exporting all leaves;
4. reuses witness-independent native-route projections; and
5. coalesces singleton routes only after proving that they form a complete
   Cartesian grid with identical metadata.

Incomplete or correlated grids remain separate, so crossed tuples cannot be
introduced. Exhaustive tests over every non-empty subset of a 2x2 relation lock
this condition.

## Work attribution

| Counter | P3 | P4 | Change |
|---|---:|---:|---:|
| proof states | 540,007 | 539,997 | -10 |
| proof alternatives | 21,822,984 | 7,918,021 | -63.72% |
| proof dependency edges | 37,065,252 | 19,685,772 | -46.89% |
| proof rows examined | 11,433,594 | 1,798,561 | -84.27% |
| support prefixes | 14,127,044 | 57,585 | -99.59% |
| support leaves / unique proofs | 5,152,026 | 53,074 | -98.97% |
| descriptor expansions | 5,101,251 | 223,455 | -95.62% |
| support memo misses | 1,231,550 | 5,129 | -99.58% |
| support memo evictions | 1,164,943 | 0 | -100% |
| factorized clauses | 99,632 | 38,032 | -61.83% |
| realization unique clauses | 17,910,669 | 5,878,988 | -67.18% |
| topology rows built | 10,406,803 | 3,837,174 | -63.13% |
| receipt relation slots | 99,632 | 103,706 | +4.09% |
| candidate receipts created | 99,632 | 99,632 | unchanged |
| maximum RSS | 35,859,276 KiB | 14,030,516 KiB | -60.87% |

The remaining exclusive build costs are closure replay `135.499 s`, proof
topology `76.201 s`, base analysis work `40.553 s`, support-product relation
materialization `12.154 s`, clause canonicalization `6.097 s`, proof grounding
`4.927 s`, and receipt/rank consumer preparation `2.352 s`. The next patch must
therefore remove repeated topology/closure work; optimizing receipt preparation
alone cannot close the remaining factor.

## Verification and decision

The accepted revision passed the focused and expanded placement suites, Maven
compilation, `git diff --check`, exact frozen-snapshot comparison, and an
independent correctness review with no findings. Project-global Checkstyle is
not a usable change gate here because the repository currently reports 362,927
pre-existing violations outside this patch.

P4 is retained: it gives a real wall-time and memory improvement while
preserving exact output. The 10x objective remains open. The next implementation
stage targets repeated immutable topology evaluation during closure replay and
keeps full relation decoding deferred to the selected-proof boundary.
