# Candidate 26 discriminator — primitive optimizer scope validation

## Outcome

The outside-only clean candidate replaces `Arrays.stream(copy).distinct().count()` in
`MemoOptimizer.uniqueScope` with primitive validation while preserving the returned scope's caller
order. It performs the required owner-array clone first, returns empty/unary scopes immediately,
checks binary scopes directly, and sorts a second validation copy only for widths above two. The
exception type and message are unchanged.

No production or `target` source was changed.

## Preserved contract

- `owners.clone()` remains the first operation. Null owners therefore throw before null predicates,
  evaluators, or malformed class metadata.
- Duplicate scopes still throw `IllegalArgumentException` with exactly
  `A shared owner appears once in a condition scope`, before later constructor validation.
- All integer values remain accepted by this helper, including negative values and both integer
  extremes. Unknown-owner validation remains in the optimizer rather than moving here.
- Successful scopes retain caller order. Constructor input and `owners()` output remain defensive
  copies.
- Empty, unary, binary, non-sorted, wide, adjacent/non-adjacent duplicate, and extreme-value scopes
  are covered by the focused test. Its literal legacy helper retains the exact old clone/stream code
  and compares result arrays or exception class/message against the candidate.

## Width evidence

A counter-only overlay of the literal original C25 validator produced these rewritten-fixture counts:

| Width | LogReg | GLM |
|---:|---:|---:|
| 0 | 1,646 | 6,809 |
| 1 | 6,879 | 21,494 |
| 2 | 4,614 | 7,179 |
| 3 | 96 | 143 |
| 4 | 10 | 56 |
| 5 | 4 | 6 |
| 6 | 0 | 6 |
| 7 | 0 | 5 |
| 8 | 0 | 2 |
| 9 | 0 | 2 |

Both probes completed `OPTIMAL`. Widths zero and one account for 64.3% of LogReg and 79.3% of GLM
calls. Binary scopes add 34.8% and 20.1%, leaving only 110 LogReg and 220 GLM validations that need a
second array and sort. This supports the direct binary equality branch without adding a broader
specialized validation scheme.

## Verification

- Isolated compilation against frozen C25: PASS.
- `scope-validation-boundary-junit.log`: 3 focused tests PASS.
- `scope-validation-optimizer-83-junit.log`: all 12 established optimizer classes, 83 tests PASS
  under assertions.
- `scope-validation-width-histogram.log`: original-validator counter fixture completed both rewritten
  workloads with `OPTIMAL` status.
- `git diff --check --no-index`: no whitespace errors; exit 1 is the expected source-content diff.

## SHA-256

```text
13727043bc1901c122676300ea4c1eb9d1bb6718d5804e09e23d3ad848a998be  frozen C25 MemoOptimizer.java
87c9836081a93d1f33e1956cad0c98f73475d109a6327b6f01805670495e5ebb  clean candidate MemoOptimizer.java
fcf735634f83d7d66474291dd99bd01e2a07172aa1bd8b058e0b37ecd5ad2c9b  MemoOptimizerScopeValidationTest.java
e51a0df12632392f46144c488ccb6393c7c4e13b2f71ad60b4fbaf8d0330798e  focused JUnit log
72e2be390fcd06f6ec5b3f5229b6d96b455d97eafacb349fbaf34838887d6949  83-test optimizer JUnit log
93af8bcf205f86a683e246dcafd21e1ec2cc4aac81f58220a0858271e05189dc  width histogram log
```

## Limits

This is correctness and structural-width evidence only. It does not measure elapsed time, allocation,
or official planning performance. The private normalized `CostFactor` constructor is exercised
indirectly by the established optimizer regressions; public `Check` and `CostTerm` constructor
precedence is covered directly.
