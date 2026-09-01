# G007 Temporary Cutover Controls Cleanup Plan

Repo: `/tmp/g005-p4-task46-iter16-d1-base-20260723T132127Z/repo`

Purpose: remove temporary cutover controls without changing planner semantics, while preserving exact current behavior through regression locks before any source deletion.

## Scope

Targeted sources:

- `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
- `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java`

Targeted behaviors:

- DP transient-family refinement and rewire handling behind the three hardcoded `false` gates
- Heuristic no-refed provenance policy literal `NO_REFED_POLICY_V1`

## Classification

| Symbol | Classification | Current role | Cleanup disposition | Regression lock before deletion |
| --- | --- | --- | --- | --- |
| `ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE` | Temporary behavioral gate | Permits a near-tie FOUT bundle acceptance path only when the bundle score is within the explicit tolerance | Delete the disabled branch and its unused tie-tolerance constant if no other call sites remain; do **not** enable semantics | `FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyRefineEvaluatesCheaperContextBundleBeyondFamilyTie` plus a new/retained negative assertion that near-tie acceptance does not activate when the bundle is not strictly cheaper |
| `ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION` | Temporary behavioral gate | Mirrors a locked transient-write decision onto linked transient reads | Delete the disabled propagation branch in both decision and simulation paths; preserve ordinary cost-based selection and existing transient family wiring | `FederatedPlannerFallbackIntegrationTest#testDpRewriteKeepsTransientChainConsistentWithFedParentEdge`, `FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleCarriesProvenFedSourceAcrossChainedFamily`, and `CampaignBDurableAnchorPropagationContractTest#h03RecurringTWriteTReadPreservesSameDurableAnchor` |
| `ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL` | Temporary behavioral gate | Forces a neighborhood re-evaluation path around transient boundaries even when the observed output is already stable | Delete the forced reevaluation branch; keep the ordinary cheaper-alternative reevaluation path intact | `FederatedPlannerFallbackIntegrationTest#testDpSeenOnlyOutputReevaluatesCheaperAlternativePlan` and `FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleFeasibilitySkipsRightIndexSliceWithoutConcreteSource` |
| `NO_REFED_POLICY_V1` | Permanent policy / diagnostic literal | Heuristic provenance policy fingerprint and selector-graph legality contract | Keep as policy, not as a temporary gate; only refactor to a shared named constant if the exact fingerprint stays byte-for-byte stable | `CampaignBHeuristicProvenanceContractTest#allTenLiteralFixturesRequireExactStablePolicySelections`, `CampaignBHeuristicInvocationReceiptContractTest#factorySuppliedAnalysisRouteIsTypedDeterministicAndMutationFree`, and `CampaignBHeuristicRealVectorPolicyRedTest` |

## Non-goals

- No candidate-space closure or widening logic changes.
- No runtime fallback, partial-success masking, or “pick one good response” behavior.
- No TR/TW relaxation.
- No recompile-path CP→FOUT relaxation.
- No Heuristic selector closure or provenance policy weakening.
- No DP/FedAll/MinST semantic changes outside the exact dead-code cleanup slice.

## Cleanup sequence

### 1) Freeze current behavior with tests

Before deleting anything, lock the current behavior of each gate with focused regression coverage:

- DP bundle/tie behavior
  - verify the existing cheaper-bundle path still wins
  - verify the disabled near-tie branch remains off unless the score is strictly better
- DP locked transient read propagation
  - verify transient read chains still follow the selected parent edge through ordinary cost-based reasoning
  - verify no new propagation path appears just because the lock hook existed
- DP forced neighborhood reevaluation
  - verify the cheaper alternative is still chosen through the existing non-forced path
  - verify no extra neighborhood scan is required for the current green path
- Heuristic policy literal
  - verify `NO_REFED_POLICY_V1` still produces the exact same policy-view fingerprint, exclusions, and immutable receipt shape
  - verify no accidental selector-graph widening or provenance fingerprint drift

### 2) Delete dead cutover branches and orphaned constants

After the tests are in place, remove the disabled gates rather than turning them on:

- delete the near-tie bundle gate branch
- delete the locked transient-read propagation branch
- delete the forced neighborhood reevaluation branch
- delete only now-orphaned helper constants/comments that existed solely to support those gates

Keep the ordinary cost model, parent/child traversal, and exact receipt/state plumbing unchanged.

### 3) Treat the Heuristic policy literal as permanent policy, not temporary cutover

`NO_REFED_POLICY_V1` should stay in the policy/diagnostic lane unless a rename-to-constant refactor can be proven behavior-preserving via exact fingerprint tests.

If it is refactored, the only acceptable change is a pure readability move:

- same exclusion set
- same candidate universe
- same policy-view fingerprint
- same immutable result shape

### 4) Verify after deletion

Run the focused tests first, then the broader guard/integration set, then compile checks.

## Exact validation commands

Run these in order:

1. Focused DP behavior locks

```bash
mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyRefineEvaluatesCheaperContextBundleBeyondFamilyTie,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleCarriesProvenFedSourceAcrossChainedFamily,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleFeasibilitySkipsRightIndexSliceWithoutConcreteSource,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpSeenOnlyOutputReevaluatesCheaperAlternativePlan,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpRewriteKeepsTransientChainConsistentWithFedParentEdge test
```

2. Focused durable-anchor / rewire guards

```bash
mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.CampaignBDurableAnchorPropagationContractTest test
```

3. Focused Heuristic policy locks

```bash
mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicProvenanceContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicInvocationReceiptContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicRealVectorPolicyRedTest test
```

4. Broader planner/guard sanity pass

```bash
mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBDurableAnchorPropagationContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicProvenanceContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicInvocationReceiptContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicRealVectorPolicyRedTest test
```

5. Compile and diff hygiene

```bash
mvn -q -DskipTests test-compile
git diff --check
```

## Rollback risks

- **Risk: deleting a disabled DP gate also removes a still-useful trace breadcrumb.**
  - Detection: the focused DP tests above should still pass and the emitted DP plan should remain identical except for the removed dead branch.
  - Rollback: restore the branch only if a focused test proves the current decision shape changed unexpectedly.

- **Risk: `NO_REFED_POLICY_V1` refactoring changes the Heuristic fingerprint or exclusion ordering.**
  - Detection: the exact policy/fingerprint tests must remain byte-identical on repeat and fresh fixture runs.
  - Rollback: revert the refactor and keep the policy literal unchanged.

- **Risk: a cleanup change accidentally enables a broader semantic path, especially around transient reads or refederation.**
  - Detection: the regression set above must still show no runtime fallback, no candidate closure widening, no TR/TW relaxation, and no recompile CP→FOUT relaxation.
  - Rollback: revert the semantic cleanup and re-run the exact same focused tests before retrying a narrower deletion.

## Recommendation

Proceed in delete-first order:

1. lock current behavior with the focused tests,
2. remove the three disabled DP gates and any now-orphaned helper constants,
3. leave `NO_REFED_POLICY_V1` as a permanent policy literal unless a pure readability refactor can preserve every fingerprint and exclusion exactly,
4. verify with the exact commands above.

This keeps the cleanup behavior-preserving and avoids candidate-space closure, runtime fallback, TR/TW relaxation, or recompile-path semantic changes.
