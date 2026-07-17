# G011 P3C DP guard-clean read-only discovery (worker-1)

## Scope and stop condition

- Scope: discovery and test-shape design only; no production/test source edits, Runtime/lops changes, Maven, Docker, dependencies, or `.omx/ultragoal` mutation.
- Snapshot: `3eb022ce399c7b3dc18cb679dd78154cd62d08a3` in the isolated worker worktree.
- Stop condition: exact DP ownership closure inventory, regression-lock inventory, baseline Maven handoff evidence, and an ordered implementation mini-plan.
- Applied constraints: planner owns legality and relocation; Runtime fallback/repair remains forbidden; TRead/TWrite remains `<CP,LOUT>` or `<FED,FOUT>`; recompile does not permit `<CP,FOUT>`.

## Exact direct-helper closure

Fresh non-Maven reproduction compiled the unchanged `JavaSourceTokenScanner` and `CampaignBPlannerOwnershipClosure` with `javac`, then invoked the exact DP root over `src/main/java/org/apache/sysds/hops/fedplanner`.

```text
PLANNER=DP UNITS=34 VIOLATIONS=149 ADAPTER=DpPlacementAdapter POSITIVE_BOUNDARY=PASS
```

- Fresh raw evidence: `/tmp/g011-worker1-dp-closure-evidence.txt` (`sha256 24b90e7225355310c34206d5e75db09af11756b87b9baae0dbc5efbb6e026a2e`).
- Independent subagent reproduction: `/tmp/g011-worker1-closure.tuoPwC/dp-closure.txt` (`sha256 06cc2390f761cc5f926897d994f3b5fd52fc609d4bd347b90dbb7bb8054f047e`).
- Exact edge artifact: `/tmp/g011-worker1-closure.tuoPwC/dp-edges.txt` (`sha256 5ebad75d7c605bdd7bccb60843e301396e149515d4fe31b69a6cd9c19e241f2a`).
- Guard/helper/scanner authority: `CampaignBArchitectureGuardTest.java:15-36`, `CampaignBPlannerOwnershipClosure.java:26-31,51-89`; helper/scanner hashes remain `a6286fe...` / `a80bb1b...`.

### Token and analytical-category totals

| Category | Tokens | Count |
|---|---|---:|
| legacy semantic owner | `oraclefacade` 52 + `rulescore` 20 | 72 |
| fallback/approximation | `fallback` | 62 |
| traversal owner | `getstatementblocks` 7 + `enumerateprogram` 3 + `enumeratefunctiondynamic` 2 | 12 |
| environment/property | `getproperty` | 3 |
| **total** | | **149** |

### Exact path/token matrix

| Path | Breakdown | Total |
|---|---|---:|
| `FederatedRefedPolicy.java` | fallback 21; getstatementblocks 4; getproperty 1 | 26 |
| `FederatedPlannerLogger.java` | fallback 2 | 2 |
| `FederatedPlannerTrace.java` | getproperty 1 | 1 |
| `FederatedPlannerUtils.java` | fallback 13 | 13 |
| `FederatedCostModel.java` | fallback 3; getproperty 1 | 4 |
| `OracleUtils.java` | oraclefacade 15; fallback 3 | 18 |
| `FederatedPlannerDpCostEnumerator.java` | oraclefacade 32; rulescore 4; fallback 3; getstatementblocks 1; enumerateprogram 1; enumeratefunctiondynamic 1 | 42 |
| `FederatedPlannerDpCostEstimator.java` | rulescore 2; oraclefacade 1 | 3 |
| `FederatedPlannerDpFedCostBased.java` | enumerateprogram 2; enumeratefunctiondynamic 1; getstatementblocks 1 | 4 |
| `FederatedPlannerDpMemoTable.java` | rulescore 2; oraclefacade 1 | 3 |
| `FederatedPlannerDpRewireTransTable.java` | rulescore 2; oraclefacade 1; getstatementblocks 1 | 4 |
| `RulesCore.java` | rulescore 2 | 2 |
| `Rulesets.java` | fallback 10; rulescore 2 | 12 |
| `OracleFacade.java` | fallback 7; rulescore 6; oraclefacade 2 | 15 |
| **total** | direct five DP owners 56; transitive collaborators 93 | **149** |

## Reachable ownership graph and owner boundaries

The closure is a fan-out graph, not a root/enumerator/memo/estimator/rewire chain.

- Root `FederatedPlannerDpFedCostBased` directly reaches enumerator, memo, estimator, supplied `PlacementAnalysis`, `DpPlacementAdapter`, `FederatedRefedPolicy`, planner utilities/logger/trace, and the cost model (`FederatedPlannerDpFedCostBased.java:122-159,205-265,253-339`).
- Enumerator reaches rewire, memo, estimator, `OracleUtils`, `RulesCore`, `OracleFacade`, refed policy, planner utilities, and cost model (`FederatedPlannerDpCostEnumerator.java:116-239,734-804,1366-1446`).
- Rewire reaches memo plus broad common helpers and separately traverses program/function structures (`FederatedPlannerDpRewireTransTable.java:230-327`).
- `RulesCore` reaches `Rulesets`; `OracleUtils` reaches `OracleFacade`.
- `DpPlacementAdapter` is downstream verification, not the semantic owner: it consumes legacy memo/optimal-plan state, preserves aggregate edge order and Hop identity, implements the `<=` LOUT tie, and checks raw objective bits (`DpPlacementAdapter.java:133-201`). Routing legacy selection through this adapter without moving authority would be forbidden wrapper hiding.

### Exact owner set (34 units)

1. `AFederatedPlanner`
2. `FTypes`
3. `FederatedRefedPolicy`
4. `FederatedPlannerLogger`
5. `FederatedPlannerTrace`
6. `FederatedPlannerUtils`
7. `FederatedTypePropagator`
8. `ExecPlacementPolicy`
9. `FederatedCostModel`
10. `FederatedWorkerUtils`
11. `HopUtils`
12. `OracleUtils`
13. `RewireConstants`
14. `RewireDagWalker`
15. `TransTableRewireUtils`
16. `FederatedPlannerDpCostEnumerator`
17. `FederatedPlannerDpCostEstimator`
18. `FederatedPlannerDpFedCostBased`
19. `FederatedPlannerDpMemoTable`
20. `FederatedPlannerDpRewireTransTable`
21. `MinStDiagnostics`
22. `FederatedTypeHandler`
23. `FederatedTypeHandlerFactory`
24. `HandlerResult`
25. `NeutralPlacementGraph`
26. `PlacementAnalysis`
27. `PlacementIdentity`
28. `PlacementShapeFacts`
29. `PlacementState`
30. `DpPlacementAdapter`
31. `RulesApi`
32. `RulesCore`
33. `Rulesets`
34. `OracleFacade`

## Existing zero-difference regression locks

- **Selected plans, aggregate edges, raw objective bits:** `CampaignBDpAggregateProducerContractTest.java:81-115,242-275` checks real IPA/DML B-05 paths, producer/plan/Hop identity, immutable aggregate edges, raw `doubleToRawLongBits`, tie arms/decision, and exclusion identity.
- **Tie order and semantics:** the same test at `:102-115,325-332` covers equal and one-ULP arms plus FOUT_ONLY, LOUT_ONLY, LOUT_EQUAL, LOUT_LESS, and FOUT_LESS; frozen C2-DP-03 also locks insertion ordinal/output in `g004b-c2-dp-minst-offline-literal.manifest:7`.
- **Multi-fixture identity and repeatability:** `CampaignBR4CostSelfTest.java:306-320,387-394,408-451` covers all exact DP owner fixtures, mutation-free repeatability, and copied/foreign/missing/extra/reordered receipt rejection including C2-DP-06.
- **Normalized receipts:** `FederatedPlannerDpMinSTOfflineLiteralManifestTest.java:34-109` byte-compares against the digest-backed manifest. `LegacyDpOfflineSelectedCapture.java:44-63,163-227` supplies the DP serialization, normalized sorted fields, and 12-significant-digit HALF_EVEN formatting.
- **All invocation counters:** production declares/validates all 12 at `FederatedPlannerDpFedCostBased.java:116-120,182-189`; `CampaignBDpAggregateProducerContractTest.java:276-322` asserts application/additional-root receipt order and the exact `1/1/1`, dynamic counts, and zero internal-build/old-overload/reenumeration/repair/fallback/double-application values.
- **Structural RED already present:** `CampaignBArchitectureGuardTest.java:24-37` demands closure zero and the positive shared adapter boundary; scanner-evasion/order locks are at `:39-74`.

### Missing pre-patch RED locks

1. DP-only architecture RED so each slice reports independently from the all-four aggregate guard.
2. One public `DpInvocationReceipt` canonical snapshot across the five full-path fixture shapes, preserving producer order, identities, raw bits, exclusions, application receipts, and all 12 counters.
3. Per-slice seam REDs for root, traversal/enumerator, memo, estimator, and rewire neutral owner contracts.
4. Multi-root adversarial exact selection with at least two edges mixing equality and one-ULP decisions under reversed insertion order.
5. Public normalized-receipt independence from private reflection and rounded decimal formatting; lock raw-bit hex and repeated/concurrent equality.
6. Counter matrix across plain, shared-diamond, function, clone/recompile, and additional-root/already-visited paths.
7. Negative receipt-constructor tests mutating each counter individually and proving rejection without state mutation.

## Maven baseline evidence and handoff

Worker-1 did not run Maven, by explicit campaign ownership (`worker-4 alone may run Maven`). The immediately preceding unchanged-source focused baseline is:

- Command: `mvn -Dmdep.skip=true -Dtest=<15 focused placement/guard classes> test` (full exact command in `/tmp/g010-s6-task10-task8-evidence-freeze.md`).
- Result: 128 tests, 1 failure, 0 errors, 0 skipped; 127 pass.
- Sole failure: `CampaignBArchitectureGuardTest#allFourOwnershipClosuresHaveOneSharedAnalysisBoundaryAndNoHiddenUniverse`.
- Failure multiset: exactly DP 149 + MinST 261 = 410, with zero extra/missing entries and no FedAll/Heuristic findings.
- Evidence root: `/tmp/g010-s6-worker4-integrated-20260717T093813Z`; authoritative integrated-log SHA-256 `642f9c70f9e35b9c321e34504bfee2b86a1296fb6a776d83787cfd9482705c0a`.
- Current fresh baseline must be supplied by worker-4; this report deliberately makes no claim about a new Maven run.

## Ordered no-source-edit implementation mini-plan

1. **Freeze RED diagnostics first:** add the DP-only closure RED and public zero-difference snapshot/counter matrix before production changes.
2. **Delete unused direct imports:** remove genuinely unused `RulesCore`/registry/`OracleFacade` imports from memo, estimator, and rewire (9 direct findings); do not rename or wrap tokens.
3. **Create one traversal snapshot from supplied analysis:** root validation, enumerator, and rewire consume exact ordered occurrence/root/clone metadata derived from the already supplied `PlacementAnalysis`; no independent program traversal.
4. **Move legality authority to neutral facts/capabilities:** construct required runtime/privacy/global-legality facts once in the analysis boundary; remove enumerator construction/passing of `RulesCore` registry and `OracleFacade`. Move implementation/authority, not only calls.
5. **Narrow memo and estimator inputs:** retain memo as exact state/tie/insertion owner; pass factual cost/shape inputs through existing clean types and move broad-helper implementation where necessary. Do not close supported candidates.
6. **Make rewire a typed result owner:** return exact rewire table, HopCommon/privacy/anchor/root/clone metadata; enumerator consumes it without re-traversal.
7. **Cut transitive broad helper reachability:** replace DP reachability into planner utils/cost/refed/logger/trace/oracle fallback surfaces with narrow clean owners containing the actual needed implementation. Broad helper reachability accounts for a large remaining closure even after direct DP cleanup.
8. **Narrow root application collaborators last:** preserve exact selected plans, aggregate edge/tie order, raw objective bits, normalized receipts, and all counters; no runtime repair/fallback.
9. **After every slice:** run DP-only RED/GREEN, public receipt snapshot, counter matrix, direct exact closure, then worker-4 focused Maven. Require zero semantic differences and monotonically decreasing closure; any difference needs independent-oracle proof and a dedicated RED.

## Verification summary

- PASS — unchanged helper/scanner compiled with `javac` and direct DP probe returned `34/149` plus positive adapter boundary.
- PASS — exact token totals, path/token matrix, owner set, and edge artifact independently reproduced by the ownership subagent.
- PASS — `git diff --check` and documentation-only scope check are required before commit.
- N/A by explicit ownership — Maven/test-suite/e2e execution belongs to worker-4; no source behavior changed here.
- N/A — Java linter/typecheck of production is not relevant to a documentation-only discovery artifact; the exact helper itself was freshly compiled.

## Problem record

- **Status:** ongoing design debt; discovery complete.
- **Problem definition:** DP root reachability spans 34 units and 149 forbidden ownership findings, so post-legacy adapter verification cannot establish a clean neutral ownership boundary.
- **Cause:** duplicated traversal ownership, direct legacy oracle/rules ownership, broad fallback-bearing helper reachability, and root application collaborators remain reachable from DP.
- **Resolution for this task:** exact inventory, regression-lock map, minimal owner boundaries, and ordered later implementation plan; no guard/runtime/planner weakening.
- **Remaining bug:** production closure remains 149 until the later implementation campaign.
- **Potential regression risk:** structural moves may preserve values while changing identity, tie/edge insertion order, raw objective bits, normalized receipts, or counters. Detect with the pre-patch public snapshot/counter matrix plus direct closure after every slice.
- **Decision basis:** planner/neutral-analysis ownership must be corrected; Runtime fallback and token/wrapper hiding are rejected.
