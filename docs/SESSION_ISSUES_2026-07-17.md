# SESSION ISSUES (2026-07-17)

## Issue 1: DP memo has no typed placement-analysis ownership seam

- **Status**: In progress (RED contract added; production implementation intentionally deferred)
- **Environment/conditions**: G011 P3C, DP planner Slice 0, `CampaignBDpMemoOwnerContractTest`; no Maven execution in this slice.
- **Reproduction**: Run the dedicated JUnit class after the sole-Maven owner authorizes the focused command. The contract currently reports `CAMPAIGN_B_DP_MEMO_TYPED_OWNER_API_MISSING`.
- **Observed symptom**: `FederatedPlannerDpMemoTable` is keyed by raw Hop IDs and can register Hop references independently of the supplied `PlacementAnalysis`; it has no exact typed occurrence owner boundary.
- **Root cause**: Memo storage, pruning, and executable lookup predate the immutable `PlacementAnalysis.HopOccurrenceProjection` identity and therefore cannot reject copied or foreign analysis occurrences at their boundary.
- **Resolution summary**: Added a direct compile-time, non-reflective test-only RED contract requiring a `PlacementAnalysis`-bound memo, typed occurrence add/get/executable lookup, stable first-in identity for equal costs, and mutation-free rejection of copied/foreign occurrences. No production behavior was changed.
- **Modified files**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBDpMemoOwnerContractTest.java`
  - `docs/SESSION_ISSUES_2026-07-17.md`
- **Verification**: `git diff --check`, forbidden-token scan (`java.lang.reflect|Method|Constructor|InvocationTargetException|Object`) and source-scope inspection are required in Slice 0. Maven is intentionally reserved for the sole-Maven verification owner.
- **Remaining issues**: Implement the typed owner seam in the exclusive Slice 2 memo file, then run the dedicated contract and focused baseline comparison.
- **Potential regression risk**: Stable sorting can preserve value equality while changing object identity, or copied same-program projections can be accepted through key equality. Detect with the contract's `assertSame` checks and negative lookups.
- **Decision basis**: Fix the planner memo ownership boundary; do not weaken runtime, oracle, TRead/TWrite, recompile, or candidate legality rules.
- **Applied principles/constraints**: RED-first; one owner at a time; no runtime fallback; protected Runtime/lops/FedAll/Heuristic/MinST/shared-cost surfaces remain untouched.

## Issue 2: DP estimator has no typed placement-analysis owner seam

- **Status**: In progress (compile-time RED contract added; production implementation intentionally deferred)
- **Environment/conditions**: G011 P3C DP Slice 3, `CampaignBDpEstimatorOwnerContractTest`; static verification only, with Maven reserved for the separately assigned verification owner.
- **Reproduction**: Compile the dedicated JUnit class after the exclusive estimator production owner implements the typed seam. The current RED references the missing `EstimatorRequest`, `EstimatorReceipt`, `ChildCostReceipt`, and `estimateExact` API directly.
- **Observed symptom**: `FederatedPlannerDpCostEstimator` accepts mutable `HopCommon`, raw Hop lists/maps, and cost buffers without an exact `PlacementAnalysis` occurrence/key plus `FedPlan` owner boundary. Its results therefore cannot directly prove producer identity, ordered child evidence, or mutation freedom.
- **Root cause**: Estimator arithmetic/share helpers predate the immutable placement analysis and still expose analysis-free inputs and mutable output buffers.
- **Resolution summary**: Added a direct, non-reflective compile-time RED requiring an analysis/memo/occurrence/exact-plan request and an immutable typed receipt that returns the exact supplied memo identity. The contract freezes hexadecimal self, forwarding, ordered child cumulative/forwarding, and cumulative raw bits; rejects copied/foreign occurrences, copied/foreign `HopCommon` plans, and a distinct empty memo bound to the same analysis; and snapshots analysis plus every registered root/child plan's identity, raw costs, child edges/order, exec type, and FType. It also freezes exact memo lookup, variant-container identity, retained variant ordering, and the complete legal memo presence universe (every unique analysis-owned Hop id crossed with every `FederatedOutput` value through `memo.contains`) before and after success and after each individual rejection, including a separate before/after presence proof for the alternate memo.
- **Modified files**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBDpEstimatorOwnerContractTest.java`
  - `docs/SESSION_ISSUES_2026-07-17.md`
- **Verification**: Static scope/diff checks, `git diff --check`, forbidden reflection/Object-erasure scan, and direct typed-symbol scan. Maven is intentionally not run in this RED-only task.
- **Remaining issues**: The exclusive production owner must implement the seam in `FederatedPlannerDpCostEstimator.java` only, make the RED GREEN, and preserve the frozen candidate set, selected identities, raw bits, child order, counters, and the three baseline failure signatures. `FederatedCostModel.java` remains separately authorized Slice 3b work only.
- **Potential regression risk**: A receipt could preserve numeric equality while accepting a copied `HopCommon`, reorder equal child evidence, canonicalize NaN/negative-zero bits, or mutate the selected plan. Detect through `assertSame`, `Double.doubleToRawLongBits`, ordered receipt assertions, and before/after snapshots.
- **Decision basis**: Add a planner-estimator ownership boundary over already-proven facts; do not weaken runtime/oracle legality or move shared cost-model behavior into this slice.
- **Applied principles/constraints**: RED-first; estimator single-file production ownership; no runtime fallback; no candidate closing; no Runtime/lops/FedAll/Heuristic/MinST/enumerator/root/rewire/shared-cost edits; TRead/TWrite and recompile rules unchanged.
