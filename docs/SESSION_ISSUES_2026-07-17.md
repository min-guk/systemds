# SESSION ISSUES (2026-07-17)

## Issue 1: DP memo has no typed placement-analysis ownership seam

- **Status**: In progress (RED contract added; production implementation intentionally deferred)
- **Environment/conditions**: G011 P3C, DP planner Slice 0, `CampaignBDpMemoOwnerContractTest`; no Maven execution in this slice.
- **Reproduction**: Run the dedicated JUnit class after the sole-Maven owner authorizes the focused command. The contract currently reports `CAMPAIGN_B_DP_MEMO_TYPED_OWNER_API_MISSING`.
- **Observed symptom**: `FederatedPlannerDpMemoTable` is keyed by raw Hop IDs and can register Hop references independently of the supplied `PlacementAnalysis`; it has no exact typed occurrence owner boundary.
- **Root cause**: Memo storage, pruning, and executable lookup predate the immutable `PlacementAnalysis.HopOccurrenceProjection` identity and therefore cannot reject copied or foreign analysis occurrences at their boundary.
- **Resolution summary**: Added a test-only RED contract requiring a `PlacementAnalysis`-bound memo, typed occurrence add/get/executable lookup, stable first-in identity for equal costs, and mutation-free rejection of copied/foreign occurrences. No production behavior was changed.
- **Modified files**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBDpMemoOwnerContractTest.java`
  - `docs/SESSION_ISSUES_2026-07-17.md`
- **Verification**: `git diff --check` and source-scope inspection are required in Slice 0. Maven is intentionally reserved for the sole-Maven verification owner.
- **Remaining issues**: Implement the typed owner seam in the exclusive Slice 2 memo file, then run the dedicated contract and focused baseline comparison.
- **Potential regression risk**: Stable sorting can preserve value equality while changing object identity, or copied same-program projections can be accepted through key equality. Detect with the contract's `assertSame` checks and negative lookups.
- **Decision basis**: Fix the planner memo ownership boundary; do not weaken runtime, oracle, TRead/TWrite, recompile, or candidate legality rules.
- **Applied principles/constraints**: RED-first; one owner at a time; no runtime fallback; protected Runtime/lops/FedAll/Heuristic/MinST/shared-cost surfaces remain untouched.
