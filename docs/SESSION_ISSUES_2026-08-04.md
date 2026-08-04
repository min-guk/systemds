# Session issues — 2026-08-04

## 1. DP의 기존 충돌 해결 결과를 required-output refinement가 다시 무효화함

- **상태**: 해결 — 핵심 회귀 및 7 workload × worker 1–4 구조 검증 통과
- **환경/조건**:
  - planner: DP
  - workload: ALS 및 StepLM 구조/컴파일 검증
  - 성능 실험 전 단계이며, 물리 호스트 `run_LAN.sh`는 사용하지 않았다.
  - StepLM 재현 HOP: 원본 `252 (rix:X_global)`, 잠긴 자식 `236 (TRead X_orig)`.
- **재현 절차**:
  - ALS 선택 검사:
    `java --add-modules jdk.incubator.vector -Xmx3g -cp "/tmp:target/classes:target/test-classes:<test-cp>" DpAlsSelectInspector`
  - StepLM 전체 DP 컴파일:
    `mvn -q -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dtest=CampaignBG014DpStepLmDecisionMapClosureRedTest test`
  - 수정 전 상세 trace:
    `SYSDS_FED_PLANNER_TRACE=1 SYSDS_FED_PLANNER_TRACE_HOPS=236,252 ...`
    (로그 `/tmp/g014_step_current_trace.log`).
- **관측 증상**:
  - `resolveOneHopConflict`가 비용을 비교한 뒤 HOP 252에 FOUT을 선택했지만,
    `enforceLockedOutputClosureFeasibility`는 자식 236의 확정 `LOUT` 잠금과 양립할 수 없음을 정확히 감지해
    252를 `LOUT`으로 교정했다.
  - 이후 `refineRequiredOutputClosureDecisions`가 잠금을 전달하지 않은
    `applyRequiredOutputDecisionClosure`를 호출했다. 이 탐색은 236을 FOUT으로 바꾸는 FOUT subtree를
    후보로 만들었고, 마지막 `applyLockedOutputDecisions`가 236만 다시 LOUT으로 덮었다.
  - 결과적으로 `<252=FOUT, 236=LOUT>`이라는 exact child-incompatible plan이 다시 만들어졌다.
    동일 cost tie에서 `directChildTiePrefersAlternative=true`가 이 불가능한 후보를 선택했다.
  - 최종 오류는 `DP output decisions do not form an executable plan forest`이며,
    conflict set은 `[252]`였다. 252에는 현재 자식 LOUT과 호환되는 합법 `CP/LOUT` arm이 실제로 존재했다.
- **원인 분석**:
  1. DP의 기존 핵심 충돌 탐지/해결기는 정상 작동했다:
     `collectConflictsSingleBFS` → `refreshConflictChoiceFeasibility` →
     `resolveOneHopConflict`/`resolveTransientWriteConflict` →
     `enforceLockedOutputClosureFeasibility`.
  2. 버그는 그 뒤의 refinement 계층이 기존 resolver의 hard boundary를 입력으로 받지 않은 데 있었다.
     즉, DP의 국소 비용 철학 문제가 아니라 **후처리 순서와 잠금 전파 누락** 문제였다.
  3. 점수 비교는 incompatible-plan 개수만 같으면 cost/tie-break를 허용했으므로, 목표 HOP의 충돌을
     그대로 가진 후보도 선택될 수 있었다.
- **해결 요약**:
  - `applyRequiredOutputDecisionClosure`와 direct-child closure에 `lockedDecisions`를 전달하는 overload를
    추가했다.
  - 기존 `RequiredOutputClosureSearch(..., lockedDecisions)`를 실제 refinement 후보 생성에도 재사용했다.
    잠긴 자식과 충돌하는 subtree는 후보 plan을 찾지 못하므로 decision map을 부분 변경하지 않는다.
  - 잠금이 있는 경로에서는 `getFedPlanAfterPrune`의 비호환 fallback을 사용하지 않는다.
  - tie/cost 후보는 해당 목표 HOP이 candidate score의 exact conflict set에서 제거된 경우에만 채택한다.
    이는 합법 후보를 닫는 정책 가드가 아니라, runtime에서 실행 불가능한 exact arm 조합을 배제하는
    기존 구조 점수 조건의 보강이다.
  - 같은 호출 사슬을 추가 감사해 마지막 `alignTransientReadsWithProducerDecisions`도 잠긴 TRead를
    덮을 수 있음을 확인했다. 모든 alignment pass에 `lockedDecisions`를 전달하고, producer와 잠긴 read가
    다르면 read lock을 보존해 이후 기존 family/exact resolver가 불일치를 처리하게 했다.
  - exact/required-output/multi-write refinement는 진입 시 hard lock을 다시 적용한다. multi-write의
    LOUT/FOUT 후보 비교도 raw total cost가 아니라 기존 `isBetterDecisionMapScore`로 missing/incompatible
    구조를 먼저 비교한 뒤 cost를 비교하도록 통일했다.
  - 기존 무잠금 overload는 유지해 기존 DP 동작과 reflection 기반 회귀 테스트의 계약을 보존했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - `mvn ... -DskipTests test-compile`: 성공.
  - 새 회귀 `testDpRequiredOutputClosureDoesNotOverrideLockedChildBoundary`: 성공.
    잠긴 LOUT 자식을 요구되는 FOUT subtree가 덮지 못하고, 부모의 기존 LOUT도 유지하며,
    partial closure를 남기지 않음을 확인했다.
  - 인접 closure/family/multi-write/ALS 테스트와 disconnected/L2SVM 회귀: 성공
    (`/tmp/g014_dp_conflict_regressions_final3.log`). 잠금 없는 TRead alignment는 producer를 따르고,
    잠긴 TRead alignment는 확정 LOUT을 보존하는 두 경우를 함께 검증했다.
  - ALS 직접 선택: `SELECT_OK terms=208 states=208`
    (`/tmp/g014_als_lockaware_after_required_closure.log`).
  - 최종 코드 기준 StepLM 전체 컴파일: 성공, FedPlanner `312.023783 sec`
    (`/tmp/g014_step_dp_conflict_final.log`).
  - 7 workload × worker 1–4 exact physical/DP/FedAll/Heuristic baseline 구조 테스트: 성공.
    `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 3,474.609 s`
    (`/tmp/g014_seven_workloads_after_lifecycle_fix_v2.log`, RC `0`).
    각 28개 workload/worker 분석에서 MinST exact optimum과 동일한 immutable analysis/cost surface로
    세 baseline을 평가하고, exact carrier 및 modeled-cost dominance 검증을 통과했다.
- **잔여 이슈**:
  - 이 검증은 구조적 합법성과 동일 modeled-cost surface 비교를 증명하며, 실제 wall-clock 정렬을 증명하지 않는다.
    새 source artifact/stage를 만든 뒤 Docker-only 성능 실험으로 별도 검증한다.
  - DP가 현재 HOP과 직접 자식 관계만 평가해 MinST 전역 최적과 다른 결정을 내리는 것은 알려진 설계 차이며,
    그 차이만으로 회귀로 판정하지 않는다.
- **잠재 회귀 위험**:
  - lock-aware closure가 지나치게 보수적이면 잠기지 않은 합법 자식 상태까지 변경하지 못할 수 있다.
    잠금 없는 기존 closure 회귀와 잠금 있는 신규 회귀를 함께 유지하고, 7-workload 구조 테스트에서
    missing root/incompatible plan을 fail-closed로 감지한다.
- **의사결정 근거**: DP의 기존 국소 conflict resolver와 비용 비교를 유지하고, 그 결과를 후속 refinement가
  실행 불가능한 조합으로 되돌리지 못하게 했다. MinST식 전역 탐색 도입, runtime fallback, 임의 후보 축소는
  사용하지 않았다.
- **적용 원칙/제약**: planner가 runtime 가능성을 사전 검증, runtime fallback 금지, 후보 임의 축소 금지,
  TRead/TWrite `<CP,LOUT>`/`<FED,FOUT>` 규칙 유지, recompile `<CP,FOUT>` 금지.

## 2. 동일 JVM에서 worker=1 다음 worker=2 구조 테스트가 이전 FULL 메타데이터를 재사용함

- **상태**: 해결 — lifecycle 수정, worker 전환 회귀 및 전체 28-case 재검증 통과
- **환경/조건**:
  - planner: worker=1 MinST emission 이후 worker=2 DP selection
  - workload: KMeans, 같은 Maven Surefire JVM
  - production planner 경로가 아니라 `CampaignBG014PlacementAuthorityTestBridge`로 분석을 직접 bind한 구조 테스트
- **재현 절차**:
  - 실패 재현:
    `mvn -q -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dtest='MinStExactProductionTractabilityCertificateTest#kmeansWorkerCountTransitionRetainsExactDpPlacementCarriers' test`
  - 실패 로그: `/tmp/g014_kmeans_w1_to_w2_dp.log`
  - lifecycle 수정 후 성공 로그: `/tmp/g014_kmeans_w1_to_w2_dp_reset.log`
- **관측 증상**:
  - worker=1 KMeans가 `X=FULL` fed-init 메타데이터를 게시한 뒤 worker=2 KMeans 분석을 test bridge로 직접 bind하면,
    formal `TRead X`의 exact legal state가 `FED/FOUT/FULL`로 고정됐다.
  - 같은 worker=2 소스의 DP plan은 literal two-partition range에서 올바른 `ROW`를 계산했다.
  - 강화된 memo 검증이 다음을 fail-closed로 탐지했다:
    `exactOwner=true, execMatches=true, outputMatches=true, fTypeMatches=false, plan=ROW, selected=FULL`.
  - worker=2를 새 JVM에서 단독 실행하거나 MinST 전처리까지만 단독 실행하면 통과했고,
    worker=1 emission 뒤 같은 JVM에서 실행할 때만 실패했다.
- **원인 분석**:
  - production의 `DMLTranslator.runFederatedPlannerAtFinalHopBoundary`는 분석 bind **전에**
    `FederatedPlannerUtils.resetFederatedPlannerRunState()`를 호출한다.
  - 28-case 구조 테스트는 test-only bridge로 analysis를 직접 bind하면서 이 lifecycle owner를 생략했다.
    그 결과 직전 프로그램이 게시한 이름 기반 fed-init/anchor 메타데이터가 다음 프로그램 분석에 유입됐다.
  - DP `selectProgram`도 run state를 초기화하지만, 이미 오염된 immutable analysis가 bind된 뒤라 너무 늦다.
    따라서 DP conflict resolver나 비용 모델의 오류가 아니었다.
- **해결 요약**:
  - 28-case 테스트가 각 프로그램의 analysis를 bind하기 직전에 production과 동일한
    `resetFederatedPlannerRunState()`를 호출하도록 수정했다.
  - worker=1 FULL publication → reset → worker=2 ROW analysis/MinST prelude/DP selection을 한 JVM에서 검증하는
    전환 회귀를 추가했다.
  - memo exact-state 검증 메시지를 identity/exec/output/FType 항목별로 세분화했다. 검증 조건은 완화하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpMemoTable.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactProductionTractabilityCertificateTest.java`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - worker=2 단독 DP + MinST prelude: 성공 (`/tmp/g014_kmeans_w2_dp_after_minst.log`).
  - reset 없는 worker=1→2 전환: 의도대로 FULL/ROW 불일치 재현 (`/tmp/g014_kmeans_w1_to_w2_dp.log`).
  - production lifecycle reset을 포함한 worker=1→2 전환: 성공 (`/tmp/g014_kmeans_w1_to_w2_dp_reset.log`).
  - 전체 7 workload × worker 1–4: 성공.
    `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, `Time elapsed: 3,474.609 s`
    (`/tmp/g014_seven_workloads_after_lifecycle_fix_v2.log`, RC `0`).
- **잔여 이슈**:
  - 실제 성능 실험은 production compile path와 Docker-only runner를 사용하므로 별도로 확인한다.
- **잠재 회귀 위험**:
  - test-only 직접 bind 호출자가 production reset 계약을 다시 생략할 수 있다. 순서 전환 회귀와 기존
    `CampaignBG014ProgramDynamicAuthorityParityRedTest`가 이를 감지한다.
- **의사결정 근거**: stale immutable analysis를 DP 후처리에서 보정하거나 exact-state 검증을 완화하지 않고,
  analysis bind 이전 lifecycle owner를 production과 동일하게 맞췄다.
- **적용 원칙/제약**: runtime fallback 금지, exact occurrence/state authority 유지, worker 수별 dataset/range 고정,
  순서 의존 global state 제거.
