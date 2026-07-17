# G011 P3C DP guard-clean RED-first execution handoff

## Verdict

Discovery is complete and implementation is **not yet authorized to change production behavior**. The exact DP ownership baseline is `34 units / 149 violations` with a positive `DpPlacementAdapter` boundary. The focused Maven baseline has 263 passing tests and three REDs; two require explicit scope adjudication before the first refactor slice. Runtime fallback-zero also lacks executable telemetry proof.

This handoff is read-only. It does not edit source/tests, run Maven again, change Runtime/lops, weaken a guard, or mutate `.omx/ultragoal`.

## Frozen evidence

### Ownership closure

- DP: **34 reachable units / 149 violations**.
- Categories: legacy oracle/rules ownership `72`; fallback ownership/vocabulary `62`; planner traversal ownership `12`; ambient property ownership `3`.
- Direct DP owners: root `4`, enumerator `42`, memo `3`, estimator `3`, rewire `4` = `56`.
- Transitive collaborators: `93`.
- Worker-1 closure: `/tmp/g011-worker1-dp-closure-evidence.txt`, SHA-256 `a4000143804d759fc8afc6bc41fa5e0d89e3f0fe0125ae8bb8542b09347a2df7`.
- Worker-4 independent closure: SHA-256 `605813035ae5bcd9cfb548e31ec1d675d5854f135ba229f195bb44801d934391`.
- Exact owner/path/token matrix and graph: `docs/G011_P3C_DP_DISCOVERY_WORKER1_2026-07-17.md`.

### Focused Maven baseline

- Sole Maven owner/run: worker-4, exactly one execution.
- Command: the 16-class command recorded verbatim in `/tmp/g011-worker4-baseline-report.md`.
- Result: **266 tests / 3 failures / 0 errors / 0 skipped**; 263 pass.
- Evidence root: `/tmp/g011-worker4-baseline-20260717T100434Z`.
- Maven log SHA-256: `3f3aa6c48b00dc5fb25044e1e52fa943c3e7e926d49e59199bce23c6c9975bea`.
- Surefire totals SHA-256: `7d0d04f08576dba342605c55d7497dd4e32b50f6eeda0e5d230255d478996036`.
- XML manifest SHA-256: `27bef05083689d1e509fb01b4ace95fe5f4d0ec6ab94c26d8f2c464fff245b1e`.
- Source/runtime/lops before-after: byte-identical. Canonical source `aac4898c...`, Runtime `30aa020d...`, lops `b0a2ea95...`.

### Baseline RED adjudication required before refactoring

1. `CampaignBArchitectureGuardTest#allFourOwnershipClosuresHaveOneSharedAnalysisBoundaryAndNoHiddenUniverse`
   - Expected structural RED.
   - Before G011: DP 149 + frozen MinST 261.
   - After G011 DP closure: failure must contain exactly MinST 261, zero DP/FedAll/Heuristic entries, until G012 closes MinST.
2. `FederatedPlanCostEnumeratorTest#testFederatedPlanCostEnumerator7`
   - Current failure: `Placement analysis is foreign to the supplied program`.
   - DP-scoped baseline defect or stale test contract; it must receive independent owner/oracle analysis and a minimal RED before any semantic patch.
   - Do not hide it by using the old analysis-free overload, weakening program ownership, rebuilding analysis internally, or accepting foreign analysis.
3. `FederatedPlannerFallbackIntegrationTest#testFedAllCpfoutChain`
   - Current failure: expected FOUT on Z, got NONE.
   - FedAll/out-of-scope for G011. Freeze the exact signature and do not repair it in DP work. Any change is a regression unless separately authorized by the FedAll owner.

## Zero-difference locks that remain authoritative

- `CampaignBDpAggregateProducerContractTest`: supplied-analysis identity, selected root/aggregate edge/Hop identities, raw objective bits, all tie modes, application/additional-root receipts, and all 12 invocation counters.
- `CampaignBR4CostSelfTest`: exact owner fixtures, aggregate/memo ownership, repeatability, copied/foreign/reordered/missing/extra receipt rejection.
- `CampaignBCostPlannerDifferentialContractTest`, `R4ExactPrivateCostFixtureLibraryTest`, and `FederatedPlannerDpMinSTOfflineLiteralManifestTest`: frozen normalized typed surfaces and field-specific corruption sensitivity.
- `NeutralPlacementGraphExactCfgIdentityTest` and `CampaignBB09ExplicitRecompileFixtureContractTest`: clone/recompile/stable-origin identity.
- `FederatedPlannerDpRewireTransTableTest`: rewire collateral.
- `FederatedCostModelFallbackTest` and `FederatedPlanTReadWriteConsistencyTest`: estimator and TRead/TWrite collateral.
- Structural guard: exact positive `DpPlacementAdapter` boundary and monotonically decreasing direct closure.

## Missing RED locks: add before production changes

1. **Root seam:** direct typed supplied-analysis invocation; one enumeration, one selection, one application; immutable ordered receipts; every forbidden counter zero.
2. **Enumerator owner:** typed `PlacementAnalysis` input and enumerator-owned aggregate receipt; two-root mixed equal/one-ULP fixture freezes edge/tie order, selected identities, and raw cumulative objective bits.
3. **Memo owner:** analysis-bound memo; first-in equal-cost plan identity retained; exact occurrence/executable lookup; copied/foreign analysis rejected.
4. **Estimator owner:** request binds exact analysis occurrence/key and `FedPlan`; fixed hexadecimal self/forwarding/child/cumulative bits; foreign/copied `HopCommon` rejected; no mutation.
5. **Rewire owner:** B-09 typed receipt freezes CLONE/SAME_ORIGIN/recompile exclusions, `cloneToOrig`, additional roots, multiplicities, and ordered normalized identities; B-05 freezes no-clone behavior.
6. **Normalized public receipt:** repeated/concurrent projection from public exact receipt fields is byte-identical and identity-preserving; no private reflection, rounded-only authority, wrappers, or `Object` erasure.
7. **Counter matrix:** plain, shared diamond, function, clone/recompile, and additional-root/already-visited paths assert all 12 counters.
8. **Negative counter constructor:** mutate each counter individually; reject without analysis/memo/Hop/application mutation.
9. **Runtime zero telemetry:** observe, without repairing, the smallest generated-instruction paths for FEDFout, FEDRefed, Reorg, and AggregateTernary fallback-capable branches; assert runtime fallback/repair exactly zero. Planner `fallbackCount=0` alone is insufficient.

## Ordered one-owner-at-a-time slices

| Slice | Exclusive owner/files | Required change shape | Gate before next slice |
|---:|---|---|---|
| 0 | Test owner: DP aggregate/guard/new owner contracts | Add the missing REDs above; adjudicate the foreign-analysis DP failure; freeze the FedAll failure as out-of-scope | REDs fail for the intended missing seams only; existing frozen receipts unchanged |
| 1 | Memo, estimator, rewire import owners | Deletion-only removal of demonstrably unused `RulesCore`/registry/`OracleFacade` imports | Expected direct closure delta exactly `-9`; semantic/fingerprint/counter delta zero |
| 2 | `FederatedPlannerDpMemoTable.java` | Storage, prune, insertion, and tie ownership only; remove dead API before adding anything | Exact first-in tie, plan identity, occurrence lookup, and copied-analysis rejection GREEN |
| 3 | `FederatedPlannerDpCostEstimator.java` | Arithmetic/share only over proven facts; preserve raw-bit costs and child order | Estimator RED GREEN; no candidate/cost/tie delta |
| 3b | Separately owned `FederatedCostModel.java` slice, only if proven necessary | Replace fallback-bearing helper path with explicit proven paths; do not close candidates | Independent cost oracle and raw-bit differential GREEN |
| 4 | `FederatedPlannerDpRewireTransTable.java` | Topology/clone/transient linkage only; consume ordered analysis/Unroll facts rather than walking program blocks | B-05/B-09, loop/function/clone identities and rewire tests GREEN |
| 5 | `FederatedPlannerDpCostEnumerator.java` | Consume supplied neutral graph/exclusion/shape/capability facts; remove local rules/oracle construction and duplicate legality ownership | Candidate set, costs, insertion/tie order, aggregate carrier, and receipts byte/identity equal |
| 6 | `FederatedPlannerDpFedCostBased.java` | Application-only root; `analysis.assertProgramOwner(prog)`; exactly one enumeration/selection/application | Root seam, counters, receipt/fingerprint identity GREEN |
| 7 | Shared helper owner after DP edges disappear | Delete obsolete DP-only fallback/oracle/rules/helper API; no token renaming | DP direct closure falls by edge removal; FedAll/Heuristic remain closure-zero |
| 8 | Verification owner | No implementation; run final scoped and aggregate evidence | Stop criteria below all satisfied |

Slices are sequential. Do not parallelize files with shared ownership, and do not combine memo/estimator/rewire/enumerator/root in one commit.

## File ownership boundaries

- **Memo owner:** `FederatedPlannerDpMemoTable.java` only, plus its dedicated RED test.
- **Estimator owner:** `FederatedPlannerDpCostEstimator.java` only. `FederatedCostModel.java` requires a separately reviewed shared-owner slice.
- **Rewire owner:** `FederatedPlannerDpRewireTransTable.java` only, plus rewire/clone contracts.
- **Enumerator owner:** `FederatedPlannerDpCostEnumerator.java`; it may consume existing neutral facts but must not silently expand `PlacementAnalysis` without a failing immutable-fact RED.
- **Root owner:** `FederatedPlannerDpFedCostBased.java`; changes to `AFederatedPlanner` are separate cross-owner work.
- **Shared helper cleanup owner:** `FederatedRefedPolicy`, planner utils/logger/trace, `OracleUtils`, `RulesCore`, `Rulesets`, and `OracleFacade` only after DP dependency edges have been removed. Do not edit shared behavior merely to reduce token counts.
- **Protected/out-of-scope:** Runtime, lops, FedAll, Heuristic, MinST, Docker, dependencies, guard weakening, privacy/TRead/TWrite/recompile relaxation.

## Per-slice verification and rollback

For every slice:

1. Run the smallest owner RED/GREEN tests, then the full 16-class focused command through the single Maven owner.
2. Run the unchanged direct closure helper; require the documented expected delta and no new token/category/path.
3. Compare selected-plan identities, aggregate edges/order, tie arms/order, raw objective bits, exclusion/application receipts, normalized receipt bytes, fingerprints, clone/original IDs, and every counter.
4. Compare source scope and Runtime/lops manifests; Runtime/lops must remain byte-identical.
5. Revert the newest slice immediately on any unexplained semantic, identity, raw-bit, order, fingerprint, counter, or candidate-set delta.

Rollback is one owner/smell commit at a time. Never roll forward by adding a runtime fallback, accepting a partial response, weakening a guard, closing a supported candidate, rebuilding foreign analysis, hiding tokens behind wrappers/reflection/renaming/`Object`, or combining unrelated repairs.

## Stop criteria

G011 P3C DP implementation closes only when all are true:

- DP direct ownership closure is **0**, with positive shared `DpPlacementAdapter`/`PlacementAnalysis` boundary.
- Aggregate architecture failure contains exactly frozen MinST debt and zero DP/FedAll/Heuristic findings, or is fully green if MinST has separately closed.
- All DP-focused tests are GREEN, including the independently adjudicated `testFederatedPlanCostEnumerator7` contract.
- The out-of-scope FedAll failure is byte/signature-identical to baseline or separately fixed by its authorized owner; G011 does not claim it.
- Selected plans, objective raw bits, tie and aggregate edge ordering, normalized receipts, fingerprints, clone/recompile identity, and every counter are zero-difference.
- Runtime fallback/repair telemetry is executable and exactly zero; planner counters alone are not accepted as proof.
- TRead/TWrite remains only `<CP,LOUT>` or `<FED,FOUT>`; recompile never permits `<CP,FOUT>`.
- No unsupported candidate is closed; legality derives only from runtime capability, privacy/policy, or documented global constraints, with costs/relocation modeled before selection.
- Source changes are limited to approved DP/test/shared-owner slices; Runtime/lops and unrelated planners remain unchanged.
- Fresh focused Maven evidence, direct closure evidence, manifests, hashes, `git diff --check`, and session issue documentation are recorded.

## Problem record

- **Status:** discovery resolved; implementation debt remains.
- **Problem:** DP owns or reaches traversal, legacy rules/oracles, fallback-bearing helpers, cost, rewiring, memo, and application surfaces, creating 149 forbidden findings and preventing a neutral ownership boundary.
- **Resolution:** RED-first sequential owner transfer using existing neutral facts, deletion before abstraction, exact zero-difference receipts/counters, and direct closure after each slice.
- **Remaining bugs:** DP closure 149; foreign supplied-analysis focused-test failure; missing executable Runtime fallback-zero proof. The FedAll FOUT/NONE failure is separate out-of-scope debt.
- **Potential regression risk:** value equality can hide changed identity, raw bits, insertion/tie order, candidate sets, clone mapping, or counters. Detect using public exact receipt snapshots plus owner contracts and the focused Maven suite after every slice.
- **Decision basis:** fix planner/neutral ownership and missing observation contracts; do not weaken Runtime, oracle, legality, TRead/TWrite, recompile, or architecture guards.

