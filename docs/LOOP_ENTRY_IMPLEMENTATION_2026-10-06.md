# Loop entry placement: implementation and validation

## Scope

Allow the planner to pay for a one-time conversion of the initial loop-carried matrix, then use a different fixed placement for the repeated body. Keep the original local plan. This is not iteration-specific scheduling, loop unrolling, or a relaxation of all-reaching-definition validity.

Worktree: `/home/mchoi/w1357-loop-entry-20261006`; baseline snapshot `88e733d303fb453158ae67a2157deaeb80961f6a` preserves the current source workspace, including its uncommitted source changes. Existing workspaces and running campaigns remain untouched.

## Implementation plan

1. Capture a failing real-compiler fixture with a local initializer and a legal federated carried update. Compare finite legal placements against an independent expected relation, not only the generated candidates.
2. Reuse existing materialization/relocation authority at the initial value's production boundary where possible. Preserve strict transient identity; every path still supplies the selected reader placement. Require an actual available FederationMap and valid shape/privacy/ordering.
3. Charge entry materialization at its source execution frequency, never at every inner iteration. Preserve repeated-operation and source-version costs.
4. Validate optional movement selection, lowering, and execution, plus zero/one/multiple iteration behavior and negative cases (no anchor, incompatible layouts, privacy, unavailable/different path anchor).
5. Run adjacent loop, transient, cost, emission, and recompile regressions; rebuild and compile the original training/validation script. Review simplification and completeness independently.

## Acceptance and completeness boundary

- At least one full compiler-to-runtime witness demonstrates a local initializer, one entry transfer, and a federated repeated state.
- Local and transferred entry alternatives remain selectable; illegal alternatives stay rejected.
- A finite independent reference universe matches admitted implementation plans in both directions. Dropped valid rows and inserted invalid rows must be detectable.
- A bounded exhaustive test is not a claim that all DML programs, layouts, or iteration-dependent schedules are covered. Any unsupported valid class discovered is recorded explicitly.
- No new dependencies, runtime fallback, fake success, weakening of privacy or transient identity, or changes to unrelated live workspaces.

## Implemented contract

The initializer keeps its ordinary CP/LOUT candidate. For each concrete federated source available at that producer, the existing materialization action can additionally compute locally and upload to ROW, COL, FULL, or BROADCAST when the runtime can construct that layout. The upload belongs to the initializer, before the initial transient write. No new loop node, scheduling layer, or runtime fallback is introduced.

```text
initial local computation -- one paid upload --> stored p⁰ in S
                                                   |
                                                   v
                                  read pⁱ in S -> body -> store pⁱ⁺¹ in S
                                       ^                         |
                                       +------ next iteration ---+
```

`S` is the exact worker/range/type layout, not the numerical parameter value. The body can use other intermediate layouts. The initial computation and final external consumer do not need to use `S`; their explicit conversions are priced normally. Every reaching transient write must still support the selected reader layout. Multiple branch initializers seed their common supported relation, and final replay checks every backedge.

For a top-level initializer and `T` body executions, the entry upload has weight 1 and body work has weight `T`. A matching stored/read layout requires no feedback transfer. The `T - 1` logical feedback uses for `T >= 1` are not additional uploads. This change does not add trip-specific unrolling.

The existing physical upload implementation is reused. Planner layout prediction and exact lowering now share range order, retain complete 2-D ranges, and use the selected durable placement key. Different upload authorities remain distinct even when they produce equal physical layouts. Transient aliases, including LOOP_PHI aliases, cannot acquire CP/FOUT candidates.

## Validation evidence

- `LoopEntryCompletePlacementSpaceTest`: independently defines the finite universe for a protected-source, two-worker, 8×2 identity loop. It exhausts all 29,400 raw assignments, validates all 280 admitted receipts, and finds exactly seven physical entry/steady-placement combinations: local/local, three upload/retain layouts, and three upload/gather-to-local layouts. Unsupported FULL on two workers is absent. This is a set-equality check, not merely a check of candidates that survived generation.
- The same test verifies a positive entry upload cost that is identical at `T=1,2,10`.
- `LoopEntryMaterializationTest`: separate anchors, equal worker pools with different source layouts, one-worker FULL, multiple branch initializers, outside local consumers, missing anchors, and loop-body-only anchors.
- `MaterializedOutputLayoutTest`: uneven partitions, reversed worker/range ordering, changed shape/axis, complete and legacy keys, invalid geometry and cardinality.
- `MaterializedContinuityTest`: only a declared action with source, owner, and proof can establish an upload root; unsupported or generated-only evidence is rejected.
- `LoopEntryRuntimeWitnessTest`: real two-worker ROW and BROADCAST executions, forced FED steady read/update/write, exactly one entry `fed_fout`, three `fed_+` executions, correct numerical update, range-sensitive ROW result, and zero observed fallback/repair. The witness permits all compatible realizations of each forced state and forbids unrelated uploads, rather than forcing an arbitrary first receipt.
- `BinaryElemwiseBroadcastMatrixRuleTest`: runtime-supported BROADCAST/local and BROADCAST/BROADCAST matrix pairs, representation guard, full matrix shape, and immutable profile inputs.

Isolated snapshot aggregate result: **119 tests, 0 failures, 0 errors, 7 pre-existing PUBLIC-only skips; 112 passed**. Maven compiled production/test sources, and `git diff --check` passed. `LoopSeedReplayWideningTest` also verifies that canonical authority rebinding merges duplicate actions without losing realization support clauses.

The original training/validation script compiled successfully through both HOP explanation and runtime instruction generation with the requested sizes, privacy, two workers, and WAN-Mid configuration. The selected plan keeps `p` local and contains four explicit prefetch/refed sites in the loop body. This is a selected compile-only plan, not a full-data execution or a global optimality proof.

Commands, checksums, and counts are in [validation.json](../.omx/loop-entry-evidence/validation.json); the aggregate log is [final-regressions-3.log](../.omx/loop-entry-evidence/final-regressions-3.log). The original graph script was reused to render [the selected plan](../.omx/loop-entry-evidence/training-validation/figures/cost_based_plan.svg) and [all HOPs](../.omx/loop-entry-evidence/training-validation/figures/full_hops.svg). The renderer verifies 58 HOPs against selection records and projects 21 main nodes with 24 dependency paths. Initialization and logical feedback are labeled with one and nine uses for `T=10`.

## Completeness limits

The exhaustive claim applies to the declared finite static loop fixture. Candidate enumeration additionally covers each available concrete entry anchor and runtime-supported target type; this is not a proof for every DML program or every possible physical partition.

Function-parametric anchors, branch-correlated different worker pools, and iteration-dependent schedules require the existing broader relation-model work. They are not modeled by this patch. The CFG's conservative treatment of zero/single-trip loop backedges is unchanged: the `T=1` regression proves cost weighting, not completeness of specialized single-iteration schedules. Unknown geometry and recompile-region CP/FOUT remain subject to existing legality constraints. These limits must not be described as runtime impossibility or global plan-space completeness.

## Controlled before/after planning comparison

Follow-up comparison rebuilt all nine changed production source files from baseline `88e733d` into a separate classpath overlay, with the same unchanged classes, script path, metadata, WAN-Mid environment, and CLI flags. Both baseline HOP/runtime compilations succeeded. All 92 selected HOP placements are identical, including local initialization, loop read, and update of `p`; neither selected plan uploads `p` at entry. The emitted relocation sites decrease from eight to four. The planner's estimated objective changes from 805,909.669779 ms to 649,005.710584 ms (19.469% lower); this is not measured training time. This comparison covers the combined patch, not an ablation of the loop-entry change alone. Commands and results: [comparison.json](../.omx/loop-entry-evidence/ab-planning/comparison.json) and adjacent baseline build/run receipts.

## Integration onto origin/main

Only this feature's 20-file delta was applied to `origin/main` at `adaebee9cc706cda52760f19ec8328a065118b8d`, in `/home/mchoi/w1357-loop-entry-main-20261006`. Unrelated source and experiment changes in the original snapshot were excluded. In particular, `PlacementCostSemantics` retains main's cost model and adds only the materialized-output layout helpers. The comparison above describes the isolated snapshot; its objective values are not a before/after measurement on main.

A fresh production/test compilation and the same targeted test selection on the integrated tree passed: **116 tests, 0 failures, 0 errors, 7 pre-existing skips; 109 passed**. Both real-worker runtime witnesses and all six loop replay regressions passed. Main has three fewer tests in the selected existing classes than the isolated snapshot. `git diff --check` and an independent integration dependency review also passed.

Reproduction command:

```sh
mvn -B '-Dtest=LoopEntryRuntimeWitnessTest,LoopEntryCompletePlacementSpaceTest,LoopEntryMaterializationTest,MaterializedOutputLayoutTest,MaterializedContinuityTest,FederatedRefedPolicyAnchorAxisTest,BinaryElemwiseBroadcastMatrixRuleTest,TransientPlacementAlternativesTest,LoopSeedReplayWideningTest,IndependentCompletePlacementSpaceTest,BaseCandidateOwnershipTest,DerivedFoutNormalizationTest,DerivedFoutMaterializationAuthorityTest,NativeMixedWorkerPoolContinuityTest,NativeLineagePlanSpaceCompletenessTest,CandidateReceiptAssignmentCompletenessTest,PlacementEmissionTransactionRedTest,CampaignBG014DerivedFoutRecompileStateRedTest,CampaignBG014CpFoutMaterializationAuthorityRedTest,FederationUtilsRefedReuseLayoutTest,FederatedPlannerFallbackIntegrationTest#testDagRegistryRefed*' -DtrimStackTrace=false -DforkCount=1 -DfailIfNoTests=false test
```

Local integration log: `.omx/loop-entry-publish/main-regressions.log`. The `.omx` evidence and graph links in this document are local artifacts, not tracked repository files.
