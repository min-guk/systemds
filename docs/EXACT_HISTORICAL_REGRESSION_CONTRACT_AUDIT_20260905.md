# Exact Historical Regression Contract Audit (2026-09-05)

## Scope and cleanup plan

This audit updates four historical Exact-planner regression tests whose assertions no longer express the current production contract. Production planner and runtime code are out of scope.

1. **Sparse function-boundary cost:** compute the expected reusable materialization download cost with the same worker-aware physical-cost primitive as production, while separately proving that sparse source bytes—not dense logical bytes—are used.
2. **B09 invocation predecessor:** derive each positional predecessor from the clone Hop's actual input occurrence and that input node's CFG reference signature instead of either pinning an obsolete serialized constant or conflating dataflow with the separate SAME_ORIGIN relation; retain SAME_ORIGIN, canonical-state, and exclusion assertions.
3. **L2SVM relocation normalization:** replace the invalid “multiple consumers imply multiple durable anchors” assertion with obligation-, consumer-input-, and physical-emission coverage. Every choice is resolved against an exact graph obligation, while only choices whose resolved option requires emission must appear in `selectedRelocations` and the lowering registry; legal direct/suppressed choices are intentionally non-emitted.
4. **StepLM runtime REFED:** capture emitted REFED instruction identities and committed relocation receipts, then reconcile source, consumer, and placement. A raw heavy-hitter count is not an acceptance criterion. Any runtime REFED without an authorized committed relocation remains a production defect and must be reported rather than normalized by raising a threshold.

## Validation contract

- No tolerance widening, threshold raising, source helper changes, or fixture weakening.
- The four focused tests must compile and pass after the frequency-fix Maven lane is released.
- Diagnostic output must retain enough identity to distinguish repeated execution of one authorized relocation from unmatched coordinator-only relocation.
- Historical immutable-211 failures remain baseline evidence only; this audit changes the asserted contract where the old assertion was invalid, not the production behavior.

## Status

Test-contract edits are complete and pass `git diff --check`. The four-class focused Maven run is green (6 tests):

`/home/mchoi/g014-runtime-4net-w1357-20260901-control/historical-contract-focused-green-20260905.log`

SHA-256: `636b09470eb6867902b08fbf21ee53427c52d072c27d8ee9e29802ab164e258b`.

The StepLM assertion records each executed REFED `(synthetic action, Hop, Lop, physical placement, count)` and requires it to match an emission-requiring committed relocation action and exact registry consumer-input authority; a mismatch remains a production failure.
