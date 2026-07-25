# SESSION_ISSUES_2026-07-25

## Issue: G007 temporary DP cutover source guard RED lock

- **상태**: 진행중 (RED regression lock added; production cleanup not yet applied)
- **환경/조건**:
  - Authoritative repository: `/tmp/g005-p4-task46-iter16-d1-base-20260723T132127Z/repo`.
  - Authoritative HEAD: `2971484a65440735dd21e5955072156e9dd0ffc0`.
  - Protected authoritative `target` was not built, copied, staged, or mutated by this test-engineering lane; it already appeared modified before this lane started.
  - Disposable RED/build root: `/run/user/10041/g007-cutover-red-9orCcF/repo`.
  - Scope: regression test and documentation only; no production source changes.
- **재현 절차**:
  - Add source guard test in authoritative working tree, then clone HEAD to `/run/user/10041/g007-cutover-red-9orCcF/repo` and copy only `docs/` plus the new `src/test/java` guard into the disposable clone.
  - Because the cloned HEAD `target` symlink pointed at a missing path, replace only the disposable clone's `target` symlink with a disposable directory before Maven runs.
  - RED command:
    ```bash
    cd /run/user/10041/g007-cutover-red-9orCcF/repo
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.G007TemporaryDpCutoverSourceGuardTest test
    ```
  - Baseline command:
    ```bash
    cd /run/user/10041/g007-cutover-red-9orCcF/repo
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyRefineEvaluatesCheaperContextBundleBeyondFamilyTie test
    ```
- **관측 증상**:
  - RED failed as intended with `Tests run: 1, Failures: 1, Errors: 0, Skipped: 0`.
  - Failure line: `java.lang.AssertionError: G007 temporary DP cutover control must be deleted: ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE` at `G007TemporaryDpCutoverSourceGuardTest.java:48`.
  - Baseline lower-cost bundle behavior passed with `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
- **원인 분석**:
  - `FederatedPlannerDpFedCostBased.java` still contains the temporary disabled DP controls targeted by the cleanup plan: `ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE`, `ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION`, and `ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL`, plus near-tie/toggle branch helpers.
  - The active context-bundle path already supports strict lower-cost acceptance and remains behavior-green before cleanup.
- **해결 요약**:
  - Added a lexical source guard that ignores Java comments and string/char literals, fails while the three temporary DP cutover symbols/disabled branch helpers remain in production source, and simultaneously asserts the strict lower-cost context-bundle evaluation fragments remain present.
  - No production implementation was changed.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/G007TemporaryDpCutoverSourceGuardTest.java`
  - `docs/SESSION_ISSUES_2026-07-25.md`
- **검증**:
  - RED log: `/run/user/10041/g007-cutover-red-9orCcF/logs/g007-source-guard-red-rerun.log`, rc `1`, intended assertion failure on `ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE`.
  - RED surefire report: `/run/user/10041/g007-cutover-red-9orCcF/repo/target/surefire-reports/org.apache.sysds.test.component.federated.placement.guard.G007TemporaryDpCutoverSourceGuardTest.txt`.
  - Baseline log: `/run/user/10041/g007-cutover-red-9orCcF/logs/g007-baseline-active-lower-cost-bundle-rerun.log`, rc `0`.
  - Baseline surefire report: `/run/user/10041/g007-cutover-red-9orCcF/repo/target/surefire-reports/org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest.txt`.
- **잔여 이슈**:
  - Production cleanup still needs to delete the disabled temporary DP controls while preserving strict cheaper-bundle evaluation.
  - After production cleanup, rerun the new guard and focused DP behavior locks from a disposable build root, then continue with the broader validation sequence in `docs/G007_TEMPORARY_CUTOVER_CLEANUP_PLAN_2026-07-25.md`.
- **잠재 회귀 위험**:
  - Risk: cleanup could delete the active strict lower-cost bundle path with the temporary near-tie branch. Detection: this guard requires `collectContextuallyFeasibleTransientBundleHopIDs`, `bundleAltScore.totalCost + 1e-9 < altScore.totalCost`, and `candidateScore.totalCost + 1e-9 < currentScore.totalCost`, plus the existing baseline behavior test.
  - Risk: future source comments or diagnostics could reintroduce the temporary names and cause noisy failures. Detection: the guard lexically strips comments and string/char literals before checking production source.
  - Risk: authoritative `target` symlink state could contaminate evidence. Detection: all Maven evidence for this lane was produced under `/run/user/10041/g007-cutover-red-9orCcF`; authoritative `target` was not used.
- **의사결정 근거**:
  - Test-only source guard enforces the cleanup plan without changing oracle/runtime/planner semantics; runtime fallback, candidate closure, TR/TW legality, and recompile placement rules are untouched.
- **적용 원칙/제약**:
  - Runtime fallback/implicit repair/partial-response acceptance 금지 유지.
  - Runtime-supported candidate-space를 test lane에서 닫지 않음.
  - TRead/TWrite `<CP,LOUT>` 또는 `<FED,FOUT>` 및 recompile `<CP,FOUT>` 금지 규칙 변경 없음.
  - Protected authoritative `target` 미빌드/미스테이징 유지.

## Issue: G007 temporary DP cutover production cleanup

- **상태**: 해결 (production cleanup applied; cleanup-specific guard and baseline behavior are GREEN; broader reviewer suites still expose pre-existing HEAD failures outside this cleanup diff)
- **환경/조건**:
  - Authoritative repository: `/tmp/g005-p4-task46-iter16-d1-base-20260723T132127Z/repo`.
  - Authoritative HEAD: `2971484a65440735dd21e5955072156e9dd0ffc0`.
  - Protected authoritative `target` was not built, copied, staged, or modified by this implementation lane; it remained the pre-existing modified symlink in `git status`.
  - Disposable GREEN/build root: `/run/user/10041/g007-cutover-green-XTmCcy/repo`.
  - Control HEAD comparison root for pre-existing verifier failures: `/run/user/10041/g007-cutover-control-cfW2Aw/repo`.
- **재현 절차**:
  - Apply only the authoritative production diff plus the existing uncommitted G007 source guard/docs into the disposable clone.
  - Run cleanup lock commands from the disposable clone:
    ```bash
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.G007TemporaryDpCutoverSourceGuardTest test
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyRefineEvaluatesCheaperContextBundleBeyondFamilyTie test
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpTransientFamilyRefineEvaluatesCheaperContextBundleBeyondFamilyTie,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleCarriesProvenFedSourceAcrossChainedFamily,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpContextualTransientBundleFeasibilitySkipsRightIndexSliceWithoutConcreteSource,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpSeenOnlyOutputReevaluatesCheaperAlternativePlan,org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpRewriteKeepsTransientChainConsistentWithFedParentEdge test
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.CampaignBDurableAnchorPropagationContractTest test
    mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicProvenanceContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicInvocationReceiptContractTest,org.apache.sysds.test.component.federated.placement.guard.CampaignBHeuristicRealVectorPolicyRedTest test
    mvn -q -DskipTests test-compile
    git diff --check
    ```
- **관측 증상**:
  - G007 source guard is GREEN after cleanup: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
  - Baseline strict lower-cost bundle behavior is GREEN after cleanup: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
  - The requested broader reviewer suites still show failures that reproduce on unmodified HEAD:
    - `FederatedPlannerFallbackIntegrationTest#testDpRewriteKeepsTransientChainConsistentWithFedParentEdge` errors with `Cannot invoke "org.apache.sysds.hops.fedplanner.placement.PlacementAnalysis.occurrences()" because "this.analysis" is null` both after cleanup and in the control HEAD clone.
    - `CampaignBDurableAnchorPropagationContractTest` reports two structural-oracle comparison failures both after cleanup and in the control HEAD clone.
    - `CampaignBHeuristicProvenanceContractTest` reports three structural-oracle comparison failures after cleanup; this cleanup did not edit heuristic production code or `NO_REFED_POLICY_V1`.
- **원인 분석**:
  - `FederatedPlannerDpFedCostBased.java` contained three hardcoded-disabled temporary cutover controls and their disabled branches/helpers:
    `ENABLE_TRANSIENT_FOUT_BUNDLE_NEAR_TIE`, `ENABLE_LOCKED_TRANSIENT_READ_PROPAGATION`, and `ENABLE_FORCED_TRANSIENT_NEIGHBORHOOD_REEVAL`.
  - Because all three controls were hardcoded `false`, deleting the guarded branches preserves the active behavior: strict lower-cost context-bundle evaluation remains available; near-tie bundle acceptance, locked transient-read propagation, and forced transient-neighborhood reevaluation remain inactive.
  - The broader suite failures are not introduced by this diff because representative failures reproduce in a disposable clone of unmodified HEAD.
- **해결 요약**:
  - Deleted the three disabled temporary DP controls, their disabled branch bodies, the orphaned near-tie tolerance calculation, and the now-unused forced-neighborhood helper.
  - Kept `ENABLE_TRANSIENT_FAMILY_SCORING_TRACE`, `NO_REFED_POLICY_V1`, diagnostic trace flags, candidate space, cost model, runtime behavior, TR/TW legality, and recompile legality unchanged.
  - Kept active strict lower-cost context-bundle scoring and final acceptance unchanged (`bundleAltScore.totalCost + 1e-9 < altScore.totalCost`, `candidateScore.totalCost + 1e-9 < currentScore.totalCost`).
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `docs/SESSION_ISSUES_2026-07-25.md`
- **검증**:
  - GREEN source guard log: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-source-guard-green-final.log`, rc `0`.
  - GREEN baseline lower-cost bundle log: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-baseline-active-lower-cost-bundle-final.log`, rc `0`.
  - Focused DP suite log: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-focused-dp-5.log`, rc `1`; same `testDpRewriteKeepsTransientChainConsistentWithFedParentEdge` error reproduced on unmodified HEAD at `/run/user/10041/g007-cutover-control-cfW2Aw/repo/logs/control-dp-rewrite-chain.log`, rc `1`.
  - Durable anchor suite log: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-durable-anchor.log`, rc `1`; the same two failures reproduced on unmodified HEAD at `/run/user/10041/g007-cutover-control-cfW2Aw/repo/logs/control-durable-anchor.log`, rc `1`.
  - Heuristic policy suite log: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-heuristic-policy-locks.log`, rc `1`; heuristic production code was intentionally untouched.
  - Test compile: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-test-compile.log`, rc `0`.
  - Diff hygiene: `/run/user/10041/g007-cutover-green-XTmCcy/repo/logs/g007-diff-check.log`, rc `0`.
- **잔여 이슈**:
  - The broader reviewer suite cannot currently be claimed fully GREEN at HEAD because it contains pre-existing failures outside this G007 cutover cleanup diff.
  - If those suites are required as hard gates, fix or refresh their existing structural oracles/NPE setup in a separate scoped task before treating them as cleanup blockers.
- **잠재 회귀 위험**:
  - Risk: deleting the disabled near-tie branch could accidentally remove active lower-cost bundle scoring. Detection: G007 source guard and baseline lower-cost bundle test both pass after cleanup.
  - Risk: future changes could reintroduce temporary cutover controls under comments or diagnostics. Detection: `G007TemporaryDpCutoverSourceGuardTest` strips Java comments/string/char literals before checking production source.
  - Risk: unrelated suite failures could be misattributed to this cleanup. Detection: representative focused DP and durable-anchor failures were reproduced in a disposable unmodified-HEAD control clone.
- **의사결정 근거**:
  - Planner cleanup only: delete hardcoded-disabled temporary controls without enabling behavior; no oracle/runtime/candidate-space/TR-TW/recompile legality changes.
- **적용 원칙/제약**:
  - Runtime fallback/implicit repair/partial-response acceptance 금지 유지.
  - Runtime-supported candidate-space를 닫거나 넓히지 않음.
  - TRead/TWrite `<CP,LOUT>` 또는 `<FED,FOUT>` 및 recompile `<CP,FOUT>` 금지 규칙 변경 없음.
  - Protected authoritative `target` 미빌드/미스테이징 유지.
