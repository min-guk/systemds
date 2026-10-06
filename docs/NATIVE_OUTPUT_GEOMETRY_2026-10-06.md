# Native output geometry repair

Scope: fix loss of already-known complete output geometry when a native operation consumes VALUE_MAP. Starting revision: `8f6bb285e5`. No source-grounding checks, candidate truncation, solver budgets or runtime fallback changes.

## Reproduction and cause

Restoring the independent loop/branch/function fixture to its literal 8x2 source and asserting the native output map produces a failure before production edits: expected column ends 2, actual 1. See `.omx/native-map-geometry-evidence/red.log`.

`NativePoolWitness` is intentionally an equivalence witness: ROW/COL retain only their partition axis, allowing operations such as column append to preserve ROW continuity. `asAnchor()` pads the other dimension with 1. `bindDirectInputSupport` previously published that abstract witness as an exact native output map when a DIRECT VALUE_MAP input prevented durable publication, even though a complete output anchor had already been computed.

## Repair plan

1. Keep axis-only continuity/equality/cache semantics.
2. At native-output publication, use the already-computed complete output anchor for exact proofs, retaining NATIVE_LINEAGE authority and canonical worker endpoints. Keep existing dynamic/unknown-output behavior unchanged.
3. Restore the 8x2 independent fixture and its complete expected plan-set checks. Add ROW/COL and changed-output-dimension regressions as appropriate.
4. Verify existing dynamic-layout and transpose/append behavior, adjacent complete-space tests, and Docker runtime through `run_LAN_docker.sh`.

The repair is limited to losing a complete output map already established by the analysis. It does not infer unknown dimensions from an arbitrary external seed or change the meaning of dynamic residency proofs.

## Final implementation and regression evidence

Production change: five lines in `PlacementRelationClosure.bindDirectNativeCandidateRealizationsMeasured`. When an exact continuity proof and a complete `outputAnchor` are both available, publish its complete coordinates via the existing `normalizedNativeLayout` helper. Preserve the proof's placement identity, canonical worker endpoints and NATIVE_LINEAGE authority. A VALUE_MAP input is not promoted to DURABLE_MAP. Dynamic and unknown-output paths retain their existing behavior.

The original 8x2 protected loop/branch/function fixture is restored. Its native `+1`, `-1` and function-result receipts now retain column ends 2. The independent complete-space oracle still validates every raw assignment against the literal legal relation for both PRIVATE and PRIVATE_AGGREGATE.

New `NativeOutputGeometryTest` covers:

- COL 8x4, partitioned across columns: the nonpartitioned row ends remain 8.
- ROW `cbind(D,D)`, 8x2 to 8x4: the published column ends are 4, not the input width or a placeholder.

Both tests require actual exact NATIVE_LINEAGE candidates with DIRECT VALUE_MAP support. Running these same compiled tests against the frozen unmodified publication engine fails with row ends 1 and column ends 1 respectively. Together with the original 8x2 failing assertion, this establishes three meaningful regressions rather than changing expected counts to match the implementation.

| Evidence | Result |
| --- | --- |
| Original 8x2 assertion before production edit | FAIL: expected width 2, actual 1 |
| Restored 8x2 complete-space class | 7 PASS |
| COL / CBIND against frozen baseline | 2 FAIL with incorrect extent 1 |
| COL / CBIND against repaired code | 2 PASS |
| Final combined adjacent regression results | 147 tests: 145 PASS, 1 existing skip, 1 baseline-reproduced error |
| Independent read-only review | CLEAR |

The combined results include the 14-class regression, its final replacement run of `NativeOutputGeometryTest`, and the separately executed five-test dynamic-layout class. Production code did not change between those runs. The first COL fixture targeted post-join EXP, which did not expose the intended native alternative; it was corrected to the loop's matrix-scalar PLUS/MINUS path without weakening the nonempty candidate requirement.

Commands:

```bash
mvn -q -Dtest=IndependentCompletePlacementSpaceTest#nativeMapsFromValueInputsPreserveFullGeometry -Dcheckstyle.skip -Drat.skip=true test
mvn -q -Dtest=NativeOutputGeometryTest,IndependentCompletePlacementSpaceTest,NativePlacementContinuityTest,DirectNativeDerivedLineageNormalizationTest,LogicalBoundaryRealizationsTest,JointValueMapRelationsTest,JointBoundaryPhysicalModelProofTest,LoopEntryCompletePlacementSpaceTest,CandidateReceiptAssignmentCompletenessTest,LoopSeedReplayWideningTest,MaterializedOutputLayoutTest,StepLmNegatedTransposePlacementTest,CfgNativeLineageNormalizationTest,NativePlacementContinuityTransformEncodeTest -Dcheckstyle.skip -Drat.skip=true test
mvn -q -Dtest=NativeOutputGeometryTest -Dcheckstyle.skip -Drat.skip=true test
```

Evidence under `.omx/native-map-geometry-evidence/`: `red.log`, `targeted.log`, `regressions.log`, `geometry-tests.log`, `geometry-baseline.log`, `validation.json`. The JSON records final class totals and changed-source SHA256 values. `git diff --check` passes.

## Existing failure and limits

`DynamicNativeLayoutCompositionTest.transientReplayPreservesDynamicReverseAuthority` fails with `One realization cannot mix unproven or physically distinct native worker pools`. Running the unchanged test against the frozen baseline production classes reproduces the same constructor/merge failure; the other four dynamic-layout tests pass on both versions. Logs: `dynamic-stack.log`, `dynamic-baseline.log`. Baseline: `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/main-publication/frozen-inputs/main-classes`.

This existing failure is not hidden by ignoring or deleting the test. Its repair is outside the known-output geometry fix. Unknown-shape placeholder representation is also unchanged; the result is not a proof that every possible native layout is now exact.

## Docker verification

```bash
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --run-id native-output-geometry \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/native-output-geometry-20261006 \
  --model-proof-class org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.IndependentCompletePlacementSpaceTest \
  --case joint_loop_toggle --case joint_function_calls
```

**PASSED**: frozen class-hash preflight, all seven updated oracle tests inside Docker, and both FED runtime cases. The loop result is 8x3 with sum 54 and squared norm 140, matching CP. Function-call fingerprints also match CP. Audit errors and runtime conversion violations are zero. These are numerical regression checks, not timing/performance claims.

Result: `/grid/3/cofee-lm-sweep-mchoi-20260914/native-output-geometry-20261006/native-output-geometry/result.json`.
Final source SHA256 values still match the Java validation manifest. No source edit followed final verification.
