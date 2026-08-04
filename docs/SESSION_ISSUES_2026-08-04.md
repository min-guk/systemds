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

## 3. 추적된 `target` symlink의 공유 build tree가 기존 immutable CP stage hardlink를 변경함

- **상태**: 해결 — 새 artifact snapshot 분리 및 기존 CP stage source 복구/검증 완료
- **환경/조건**:
  - source worktree들의 추적된 `target`이 공통 `/tmp/g007-bb30-fresh-target-20260727`을 가리킴
  - Maven package가 동일 target의 `SystemDS.jar`, `classes`, `lib`를 갱신
  - 기존 CP/reference stage는 이 target의 dependency tree를 hardlink로 소유
- **재현 절차**:
  - clean source worktree에서 package 후 기존 CP stage validator를 실행한다.
  - 수정 당시 package 로그: `/tmp/g014_source_package_71fe725_20260804.log`
  - 복구 후 검증 로그: `/tmp/g014_cp_base_stage_validate_restored2.out`
- **관측 증상**:
  - 새 source package 자체는 성공했지만, 공유 target의 dependency inode가 갱신되면서 과거 immutable CP stage의
    source-tree hash가 달라졌다. 데이터와 reference output bytes는 바뀌지 않았지만 stage validator는 이를
    정확히 거부했다.
- **원인 분석**:
  - 여러 worktree가 독립 build tree가 아니라 하나의 추적된 target symlink를 공유했다.
  - CP stage가 dependency 파일을 hardlink한 상태에서 Maven이 그 inode를 갱신해, 논리적으로 과거 stage인
    디렉터리의 content-addressed 입력이 사후 변경됐다.
- **해결 요약**:
  - 새 build 결과를 `/home/mchoi/g014-systemds-build-71fe725-1cd9add9`에 별도 snapshot으로 복사하고
    `SystemDS.jar` SHA-256 `1cd9add98f5673ecda2f319524586b42a109ccd91c8aa596d00bc4ff77cb7ab3`을 고정했다.
  - 갱신된 dependency tree는
    `/tmp/g007-bb30-fresh-target-20260727/lib.build-71fe725-1cd9add9-20260804`에 보존했다.
  - 공유 target의 기존 `lib`는 원래 CP stage source와 다시 hardlink하고 디렉터리 권한을 `755`로 복구했다.
  - 새 실험 stage는 공유 target을 직접 참조하지 않고 artifact snapshot만 소비하도록 구성했다.
- **수정 파일**:
  - 소스 변경 없음; build/stage 운영 경계 수정
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - 기존 CP base stage validator 재실행 RC `0` (`/tmp/g014_cp_base_stage_validate_restored2.out`).
  - canonical reference bundle validator 통과.
  - 새 stage
    `/home/mchoi/g014-one-pass-71fe725-44750a4-20260804-v1/g007-stage-20780b5451e957480f8fb210803b8621e251fe4875ae90d3c9a88b9286da7d13`
    의 source/harness/data/reference/runtime resource 계약 검증 통과.
- **잔여 이슈**:
  - 다음 package도 공유 target을 갱신하므로, build 직후 새 snapshot을 만들고 CP source를 동일 절차로 복구해야 한다.
- **잠재 회귀 위험**:
  - snapshot 전에 다른 Maven 실행이 target을 덮으면 artifact/source correspondence가 깨질 수 있다.
    package 종료 직후 JAR hash와 clean source commit을 함께 기록하고 stage validator로 감지한다.
- **의사결정 근거**: 과거 실험 입력을 재라벨하거나 validator를 완화하지 않고, mutable build output과 immutable
  stage input의 소유권을 분리했다.
- **적용 원칙/제약**: immutable stage/content addressing, 동일 frozen data/reference 유지, Docker-only 실험,
  과거 결과와 새 artifact 결과의 stitching 금지.

## 4. FedAll worker=1 KMeans에서 파생 FOUT action이 이전 세대 value-version identity를 보유함

- **상태**: 해결 — 원인 수정, Docker-shaped 회귀 및 새 immutable artifact의 worker=1 Docker 실행 통과
- **환경/조건**:
  - planner: FedAll (`compile_fed_all`)
  - workload: KMeans, worker=1 FULL, LAN profile
  - source commit `71fe7251caaa5f204da14d8693d468f945a836d0`, harness commit
    `44750a4ae656271916dad3b19f55e376e4fcdbe0`, JAR `1cd9add9...ab3`
  - Docker-only one-pass campaign의 두 번째 logical cell
- **재현 절차**:
  - Docker 실패 로그:
    `/home/mchoi/g014-one-pass-results-71fe725-44750a4-20260804-v1/cells/002-4ca6f7687f8b/phases/cell-1/cold-docker-e2e/raw_coordinator.log`
  - 최소 production-path 회귀:
    `mvn -DskipCheckstyle -DskipSpotlessCheck -Dtest=org.apache.sysds.hops.fedplanner.fedAll.CampaignBG014FedAllKMeansDerivedFoutAuthorityRedTest test`
  - 수정 전 로그: `/tmp/g014_fedall_kmeans_docker_shaped_red.log`
  - 수정 후 로그: `/tmp/g014_fedall_kmeans_docker_shaped_green.log`
- **관측 증상**:
  - Docker cold compile가
    `Selected derived FOUT action is not the exact graph-owned producer authority`로 fail-closed 종료했다.
  - 필드별 진단 결과는 `graphIdentityCount=1`, producer/candidateRule/targetPlacement identity 모두 `true`였고,
    `producerValueVersionIdentity=false`, `producerValueVersionEqual=true`만 달랐다.
  - 실패 producer는 builtin KMeans loop 내부 `AggBinaryOp ba(+*)`였다.
- **원인 분석**:
  - 파생 FOUT 후보는 materialization closure 시점의 node `ValueVersionKey`를 action에 보관했다.
  - 이후 function-input/post-CFG replay가 producer node를 동등한 새 `ValueVersionKey` 객체로 재구축할 수 있었다.
  - 두 번째 materialization closure는 이미 CP/FOUT이 열린 node를 다시 만들지 않아 기존 action을 보존했고,
    마지막 scope bind도 오래된 value-version 객체를 그대로 복사했다.
  - 따라서 구조 값과 graph action identity는 맞지만 최종 graph node가 소유한 exact value-version identity만 stale했다.
- **해결 요약**:
  - 마지막 scope-only bind를 `bindExactDerivedFoutAuthorities`로 확장해 최종 node identity map을 입력으로 받는다.
  - provisional producer/value-version이 최종 node와 구조적으로 동일한지 먼저 fail-closed 검증한 뒤,
    action을 최종 `producer.key()`와 `producer.valueVersion()` 객체로 다시 생성한다.
  - emission의 identity 검증은 완화하지 않았고, 진단 메시지만 필드별로 세분화했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllKMeansDerivedFoutAuthorityRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - Docker-shaped KMeans worker=1 production compile/lowering 회귀: 수정 전 정확히 재현, 수정 후 성공.
  - 회귀는 모든 graph-owned derived action에 대해
    `action.producerValueVersion() == graph.node(action.producer()).valueVersion()`을 확인한다.
  - 인접 derived-FOUT/emission/FedAll receipt 테스트 29개 통과
    (`/tmp/g014_fedall_derived_fout_adjacent_green.log`).
  - FedAll Docker-shaped ALS/KMeans/L2SVM/StepLM/LogReg 5개 통과
    (`/tmp/g014_fedall_docker_shaped_suite_green.log`).
  - 후속 source commit `98675082a1cf1ac886401f00e454b39213c6778c`, immutable artifact
    `/home/mchoi/g014-systemds-build-9867508-874e409d`에서 KMeans worker=1 LAN Docker 실행 성공.
    warm 실행시간은 DP `20.136 s`, FedAll `20.165 s`, MinST `20.047 s`, Heuristic `20.342 s`이며,
    네 플래너 모두 semantic oracle, runtime scan, zero-restart/zero-fallback 검증을 통과했다.
- **잔여 이슈**:
  - worker=2에서 별도의 runtime recompile derived-FOUT authority 문제가 발견되었으며 아래 이슈 5에서 처리한다.
    이슈 4의 worker=1 identity 버그와 원인은 다르다.
- **잠재 회귀 위험**:
  - 향후 node replay가 value-version을 구조적으로도 변경한다면 final bind가 예외를 내야 한다. 구조 동일성 guard와
    Docker-shaped 회귀가 이를 감지한다.
- **의사결정 근거**: identity 검증을 `.equals()`로 완화하거나 runtime fallback을 넣지 않고, graph builder가
  최종 소유 객체를 action에 게시하도록 authority 생성 순서를 바로잡았다.
- **적용 원칙/제약**: exact graph-owned authority, planner 선검증, runtime fallback 금지, 후보군 축소 금지,
  worker=1 FULL 유지, Docker-only 성능 근거.

## 5. runtime recompile이 DP exact state의 derived-FOUT 비트를 잃고 업로드를 건너뜀

- **상태**: 해결 — 단위/인접 회귀 및 실제 worker=2 KMeans runtime 재현 통과, 새 immutable Docker canary 대기
- **환경/조건**:
  - planner: FedAll (`compile_fed_all`)
  - workload: KMeans, worker=2 ROW, LAN profile
  - 실패 artifact: `/home/mchoi/g014-systemds-build-9867508-874e409d`
  - 실패 campaign: `/home/mchoi/g014-one-pass-results-9867508-44750a4-20260804-v1`
  - 실패 logical cell: `workers=2|planner=FedAll|workload=kmeans|profile=lan` (5번째 cell)
- **재현 절차**:
  - Docker 원본 로그:
    `/home/mchoi/g014-one-pass-results-9867508-44750a4-20260804-v1/cells/005-664ab033fd07/phases/cell-1/cold-docker-e2e/raw_coordinator.log`
  - 정책 RED/GREEN:
    `mvn -q -Dtest=org.apache.sysds.test.functions.federated.fedplanning.FederatedRefedPolicyTest#testRuntimeRecompileRegistersDerivedFoutProducerBeforeFedConsumer test`
  - 실제 function recompile RED/GREEN:
    `mvn -q -Dtest=org.apache.sysds.hops.fedplanner.fedAll.CampaignBG014FedAllKMeansRuntimeRecompileDerivedFoutRedTest test`
- **관측 증상**:
  - Docker cold 실행이 다음 fail-closed 오류로 중단됐다:
    `FED aggregate unary requires a planner-provided federated input; runtime CP fallback is forbidden.`
    실패 instruction은 `FED uarsqk+ ... FOUT`이었다.
  - planner trace에서 KMeans loop의 `ba(+*)` producer는 처음부터 `FED/FOUT/derived=true`로 선택되었다.
    그러나 function recompile snapshot/restore는 `ExecType`과 `FederatedOutput`만 보존해 derived 비트를 잃었다.
  - derived 비트를 보존한 첫 수정 뒤에도 `FederatedRefedPolicy.isRuntimeFederatedInput`이 placement만 보고 이를
    이미 물리적으로 federated인 입력으로 판정했다. 따라서 `validateAndRegister`가 `fed_fout`/`fed_refed`
    receipt를 만들기 전에 조기 종료했고, FED consumer는 실제 federated 입력 없이 lowering되었다.
- **원인 분석**:
  1. DP의 exact occurrence 충돌 탐지는 이미 `SelectedDpState.exactState`뿐 아니라
     `SelectedDpState.derivedFedFout` 차이도 충돌로 취급한다.
     `countIncompatibleDecisionMapPlans`와 `coalesceSelectedState` 모두 동일 occurrence에서 derived 비트가 다르면
     fail-closed로 충돌을 보고한다.
  2. 공통 `PlannerRecompileState`와 `Recompiler.HopState`에는 이 exact bit가 없었다. 즉 DP resolver가 구분한
     두 물리 상태가 runtime recompile 경계에서 같은 `FED/FOUT`으로 축약됐다.
  3. derived `FED/FOUT`은 FED 연산의 출력이 물리적으로 LOUT으로 떨어진 뒤 다시 FOUT으로 재배치되는 상태다.
     따라서 exact materialize/REFED registry receipt 전에는 runtime-federated input이 아니다. 기존 분류기는
     선택 placement를 물리 완료 증거로 오인했다.
- **해결 요약**:
  - `PlannerRecompileState`, immutable snapshot, `Recompiler.HopState`에
    `federatedOutputDerived`를 추가하고 snapshot/restore/동등성/진단에 포함했다.
  - derived 상태는 오직 `FED/FOUT`에만 허용하는 invariant를 snapshot 등록·복원 시 fail-closed 검증한다.
  - DP가 clone/target HOP에 기존 recompile state를 재사용할 때도 derived 비트까지 같아야 동일 상태로 본다.
  - runtime 분류기는 derived producer에 대해 exact materialize/REFED registry receipt가 있을 때만
    runtime-federated input으로 인정한다. receipt가 없으면 local producer로 유지해 기존 planner-selected
    REFED/FOUT 등록 경로가 실행되게 했다.
  - runtime fallback, TRead/TWrite 규칙 완화, opcode별 candidate skip은 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerUtils.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/main/java/org/apache/sysds/hops/recompile/Recompiler.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
  - `src/test/java/org/apache/sysds/hops/recompile/CampaignBG014DerivedFoutRecompileStateRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedAll/CampaignBG014FedAllKMeansRuntimeRecompileDerivedFoutRedTest.java`
- **검증**:
  - 신규 정책 테스트는 수정 전 정확히 실패했고 수정 후 성공했다.
  - 실제 2-worker KMeans FedAll test 성공: execution `0.634 s`, function recompile 1회,
    `fed_fed_fout` 4회, `fed_uarsqk+` 1회, fallback/repair 0.
  - `FederatedRefedPolicyTest`: 64 tests, failures/errors 0.
  - derived-FOUT recompile snapshot, FedAll ALS runtime recompile, KMeans derived authority,
    DP disconnected exact-conflict completion, DP program/dynamic authority parity,
    captured feasibility authority, placement transaction 인접 회귀를 함께 실행해 모두 통과했다.
- **잔여 이슈**:
  - source를 clean commit과 immutable build/stage로 고정한 뒤, 정확히 실패했던 worker=2 FedAll KMeans를
    `run_LAN_docker.sh` canary로 재검증한다.
  - canary 성공 뒤에만 새 336-cell campaign을 처음부터 한 번 실행한다. 이전 artifact의 4개 성공 row는
    진단 자료로만 보존하고 새 결과와 합치지 않는다.
- **잠재 회귀 위험**:
  - 새 runtime classifier가 receipt 등록 전 derived 출력을 local로 보는 것이므로, 등록 순서가 바뀌면
    upload가 누락되거나 중복될 수 있다. 정책 회귀가 receipt 전/후 상태를 검증하고, 실제 KMeans/ALS
    function-recompile 테스트가 emitted `fed_fout`/`fed_refed` 및 zero-fallback을 감지한다.
- **의사결정 근거**: DP의 기존 exact conflict semantics를 공통 recompile/lowering 경계까지 보존했다.
  DP를 MinST식 전역 최적으로 바꾸지 않았고, DP의 국소 탐색 특성으로 생기는 합법적인 선택 차이는 유지했다.
- **적용 원칙/제약**: planner가 runtime 가능성을 사전 검증, runtime fallback 금지, exact receipt authority,
  후보 임의 축소 금지, TRead/TWrite `<CP,LOUT>`/`<FED,FOUT>` 유지, recompile `<CP,FOUT>` 금지,
  Docker-only 성능 검증.
