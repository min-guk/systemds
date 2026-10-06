# VALUE_MAP test-oracle update

Scope: repair only the four obsolete test expectations identified after integration at `eb64f9c939`. Publication is integrated on `origin/main` at `7b0656c29c`. No production changes, factor-overflow work, candidate pruning, or runtime changes.

Plan before test edits:

1. Preserve the observed baseline failures: the certificate test assumes one result, and the complete-space tests require an attached anchor even for VALUE_MAP.
2. Assert that certificate alternatives preserve selected input receipts and stable surviving proof identities, not just a new result count.
3. Resolve VALUE_MAP geometry in test-owned code from each selected receipt's selected support clause. Validate exact reference ownership, missing/mismatched selected inputs, cycles and incompatible leaf geometry; never substitute the expected geometry for an absent anchor.
4. Update the independent finite fixture oracle to include VALUE_MAP and explicit support choices. Generate expectations from literal fixture semantics, not production survivors or hard-factor results. Preserve complete-set equality, unique witness projection, deliberate deletion and rejected-row mutation checks.
5. Run targeted failures, adjacent boundary/model tests and a Docker test lane. Record results and residual limitations. Keep changes confined to tests and session documentation.

Evidence directory: `.omx/value-map-oracle-evidence/`.

## Implementation

Only two test classes change. Production planner, DP factors and runtime code are unchanged.

- `NativePlacementContinuityTest`: verify receipt-specific native-proof identities separately from endpoint-certificate identity. Latest main coalesces equivalent endpoint certificates into one result; the test preserves that contract while checking all distinct selected-input proofs. Adding/removing/restoring input alternatives preserves surviving proof identities; seed geometry, worker compatibility, evidence, precision classes and candidate nonmutation remain checked. Equivalent endpoint certificates are deduplicated; receipt identity is checked separately on the native proofs.
- `IndependentCompletePlacementSpaceTest`: resolve VALUE_MAP through the selected receipt's selected support clause. Require the exact independently declared input owners (including initial D and both branch writes), binding positions/kinds and selected realization references. Resolve concrete leaf worker/range maps; preserve all selected incoming maps and each named branch role. No expected-map default is used when an anchor is absent.
- Independently enumerate four read/operation routes per branch. For each pair, allow the native join route and two VALUE_MAP output routes; add the durable join route only when both branch operations are durable. Thus the expected universe has `4*4*(1+2) + 2*2 = 52` witnesses for each protected privacy level. Expected routes never come from the model's admitted rows.
- Stream every raw categorical assignment, validate every admitted row with production selection validation, and compare full witness sets. Keep raw ordinal/alternative tuple/signature injectivity, deletion of an admitted row, rejected-row injection, and fail-closed UNKNOWN checks. Raw-domain and factor-count snapshots are replaced by exhaustive classification accounting and independent accepted-set equality.
- Add negative checks for missing receipts, replacing a required receipt with a different realization of the same owner and geometry, and deleting each entry/branch binding itself from a constructor-valid re-owned clause. Equal-map branches must remain distinct required inputs.

## Fixture boundary and discovered limitation

The original unreduced 8x2 fixture published a native width-1 output witness, alongside its literal width-2 source map. A diagnostic fixture-only assignment of 8x2 HOP dimensions did not remove that discrepancy. This work does not diagnose or fix production shape inference, and does not accept that discrepancy as a correct runtime map.

The bounded complete-space fixture now uses a literal 8x1 column vector, retaining the same loop, branches, function call, privacy and receipt-selection structure. Its expected ranges follow the literal input and shape-preserving scalar operations. No compiler rewrites or manual shape overrides remain. Consequently, the new 52-witness universe is a proof for this revised fixture, not an assertion that the old 8/68-witness universes were preserved. Multi-column native shape inference remains outside this test-only change.

## Validation

- Baseline failures preserved in `.omx/structural-grounding-evidence/main-integration-tests.log`: one certificate-count failure and three complete-space anchor failures.
- Targeted vector-fixture run: 85 tests, 84 passed, one existing skip, no failures/errors. Log: `.omx/value-map-oracle-evidence/vector-targeted-tests.log`.
- Independent read-only review: CLEAR; checked expectation independence, exact input ownership and binding-deletion mutation checks.
- Final adjacent regression and Docker evidence is recorded below.

Pre-publication Java regression command (also rerun after integration):

```bash
mvn -q -Dtest=NativePlacementContinuityTest,IndependentCompletePlacementSpaceTest,LogicalBoundaryRealizationsTest,JointValueMapRelationsTest,JointBoundaryPhysicalModelProofTest,LoopEntryCompletePlacementSpaceTest,CandidateReceiptAssignmentCompletenessTest,LoopSeedReplayWideningTest -Dcheckstyle.skip -Drat.skip=true test
```

Result: **124 tests, 123 passed, one existing skip, zero failures/errors**. This includes the final binding-deletion mutations. The revised complete-space fixture checks **279,936 raw assignments per privacy level**, admitting exactly **52** and rejecting **279,884**, with no UNKNOWN rows. Logs and machine-readable results: `.omx/value-map-oracle-evidence/final-regressions.log`, `final-validation.json`, `final-space-metrics.log`.

The build compiles the changed Java tests. `git diff --check` passes. No new ignored tests, dependencies, production changes, DP factors or source-grounding operations were introduced.


Docker command:

```bash
bash scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e \
  --run-id protected-loop-function \
  --output-root /grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006 \
  --model-proof-class org.apache.sysds.hops.fedplanner.fedCostBased.fedExact.IndependentCompletePlacementSpaceTest \
  --case joint_loop_toggle --case joint_function_calls
```

Result: **PASSED**. Frozen class hash preflight passed; the updated oracle passed all six tests inside Docker. Both loop and function FED runs matched their CP reference fingerprints, with no runtime conversion violations or audit errors.

Docker result: `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/protected-loop-function/result.json`.
Image: `sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`.

Remaining limitation: this is a bounded test-oracle repair. It neither fixes nor measures the unrelated large-logreg factor overflow or the original two-column fixture's native witness shape discrepancy.


## Latest-main publication integration

Before push, `origin/main` had advanced from `eb64f9c939` to `7b0656c29c`. Commit `547f4799cd` coalesces identical endpoint certificates in `PlacementRelationClosure`. The updated test therefore expects one endpoint certificate while independently requiring all 2/3/2 selected-input native proofs and their stable identities. No upstream production change was reverted. Both appended session-document sections were retained when resolving the rebase conflict.

The validation above records the initial implementation. Fresh integrated Java and Docker results are recorded below; they are the publication evidence.


Integrated publication validation on `7b0656c29c`:

- Same eight-class Maven regression command: **124 tests, 123 passed, one existing skip, zero failures/errors**. Log: `.omx/value-map-oracle-evidence/main-publication-regressions.log`.
- Same Docker command with `--run-id main-publication`: **PASSED**, six oracle tests and both loop/function cases passed, class hashes matched, no runtime conversion violations or audit errors.
- Docker receipt: `/grid/3/cofee-lm-sweep-mchoi-20260914/value-map-oracle-20261006/main-publication/result.json`.
- Final diff against integrated main contains only the two test classes and two documentation files.
