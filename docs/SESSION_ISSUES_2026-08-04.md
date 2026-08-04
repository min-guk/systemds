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

- **상태**: 해결 — 단위/인접 회귀, 실제 worker=2 KMeans runtime 재현 및 새 immutable Docker canary 통과
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
  - code commit `97f792bdbef8ea63aa2727b4f8d26e571be515f7`, JAR SHA-256
    `715ffc8f516b543be858c64ccfb6580594d156d126eda00569b8d6ab7e80cc4f`로 immutable stage
    `e103158650b2d2eeed9ec7868954a6b2b679a5f7ab747b83001693e03a8d6084`를 생성했다.
  - 과거 실패와 동일한 `workers=2|planner=FedAll|workload=kmeans|profile=lan` Docker canary가 성공했다.
    warm `18.353 s`, cold `22.007 s`, `fed_uarsqk+` 1회, `fed_fed_fout` 48회이며
    fallback 0, coordinator/worker restart 0, runtime scan clean, teardown zero resources를 확인했다.
  - canary receipt:
    `/home/mchoi/g014-docker-canary-results-97f792b-715ffc8f-20260804-v1/canary-receipt.json`
- **잔여 이슈**:
  - 새 336-cell campaign을 이 immutable stage에서 처음부터 한 번 실행하고, 모든 workload/planner/worker/profile의
    runtime 성공과 성능 정렬을 검증한다. 이전 artifact의 4개 성공 row 및 이번 canary는 진단 자료로만 보존하고
    새 campaign row와 합치지 않는다.
- **잠재 회귀 위험**:
  - 새 runtime classifier가 receipt 등록 전 derived 출력을 local로 보는 것이므로, 등록 순서가 바뀌면
    upload가 누락되거나 중복될 수 있다. 정책 회귀가 receipt 전/후 상태를 검증하고, 실제 KMeans/ALS
    function-recompile 테스트가 emitted `fed_fout`/`fed_refed` 및 zero-fallback을 감지한다.
- **의사결정 근거**: DP의 기존 exact conflict semantics를 공통 recompile/lowering 경계까지 보존했다.
  DP를 MinST식 전역 최적으로 바꾸지 않았고, DP의 국소 탐색 특성으로 생기는 합법적인 선택 차이는 유지했다.
- **적용 원칙/제약**: planner가 runtime 가능성을 사전 검증, runtime fallback 금지, exact receipt authority,
  후보 임의 축소 금지, TRead/TWrite `<CP,LOUT>`/`<FED,FOUT>` 유지, recompile `<CP,FOUT>` 금지,
  Docker-only 성능 검증.

## 6. 새 one-pass campaign이 실행 후 host disk floor에서 중단됨

- **상태**: 해결 — 외부 자원 복구 및 logical cell 생성 전 fail-closed preflight를 harness에 반영
- **환경/조건**:
  - code commit `97f792bdbef8ea63aa2727b4f8d26e571be515f7`, JAR SHA-256
    `715ffc8f516b543be858c64ccfb6580594d156d126eda00569b8d6ab7e80cc4f`
  - 기존 harness commit `44750a4ae656271916dad3b19f55e376e4fcdbe0`
  - logical cell `workers=1|planner=DP|workload=kmeans|profile=lan`
- **재현 절차**:
  - 실패 campaign:
    `/home/mchoi/g014-one-pass-results-97f792b-44750a4-20260804-v1`
  - semantic oracle:
    `cells/001-0d7aac79d30b/phases/cell-1/cold-docker-e2e/semantic_oracle.json`
- **관측 증상**:
  - SystemDS 실행은 `26.009 s`에 정상 종료됐고 출력 SHA-256
    `64dbc45ba999e58166060dda9bfd9b4c992414c7676cdd1d5d920d22774fd6ed`가 reference와 일치했다.
  - runtime scan/fallback도 clean이었으나 실행 후 semantic oracle이 frozen 5 GiB free-space floor 미달로 실패했다.
  - 즉 DP 계획/실행 실패가 아니라 host의 재생성 가능한 build 산출물이 root filesystem을 소진한 외부 자원 실패였다.
- **원인 분석**:
  - campaign runner는 시작 전에 CPU 격리 조건만 검사했고, semantic oracle의 동일 disk/inode floor를 logical cell
    디렉터리 생성 전에 검사하지 않았다. 그 결과 실행할 수 없는 host 상태가 attempt 하나를 소비했다.
- **해결 요약**:
  - 비활성 임시 worktree의 재생성 가능한 Maven `target/` 72개만 제거해 free space를 약 `2.6 GiB`에서
    `25 GiB`로 복구했다. source/Git/실험 evidence는 보존했으며 삭제 영수증은
    `/home/mchoi/g014-resource-cleanup-generated-targets-20260804.json`에 기록했다.
  - harness commit `d2f4fa494842464d8ff6203e7f57fd97bc4a6e9c`에서 campaign/oracle의 frozen floor가 같은지
    검증하고, output/results/tmp의 고유 filesystem별 free bytes/inodes를 manifest 생성 전과 각 cell 생성 직전에
    검사하도록 수정했다.
  - floor 미달은 cell directory/request를 만들기 전에 중단되므로 attempt를 소비하지 않으며, floor 자체를
    campaign manifest의 resource-isolation 계약에도 포함한다.
- **수정 파일**:
  - SystemDS 소스 변경 없음
  - harness `experiments/tools/run_one_pass_performance.py`
  - harness `experiments/tests/test_one_pass_performance.py`
  - harness `experiments/docs/SESSION_ISSUES_2026-08-04.md`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - 실패 cell의 semantic output/reference hash 일치, zero fallback, runtime scan clean을 재확인했다.
  - harness focused test 11/11, 전체 test 147/147, `py_compile`, changed-file basedpyright 0 errors/warnings,
    `git diff --check`가 통과했다.
  - cleanup 후 root filesystem은 약 25 GiB free, free inode 약 22.7M으로 frozen floor를 충족한다.
  - 새 harness로 immutable stage
    `e5ad92a4c21121de198bb78447caece934b06255bdd83b7cdb8983b6fc37ffd9`를 생성·검증했다.
    stage는 code commit `97f792b...`, harness commit `d2f4fa4...`, 동일 JAR/data/reference hash를 인증한다.
  - 새 campaign `/home/mchoi/g014-one-pass-results-97f792b-d2f4fa4-20260804-v1`의 초기 5/336 셀이
    연속 성공했다. KMeans/LAN/worker=1은 MinST `19.937 s` ≤ DP `20.655 s` ≤ FedAll `21.444 s`
    ≈ Heuristic `21.491 s`였고, 과거 실패 지점인 FedAll/KMeans/LAN/worker=2도 `18.958 s`,
    oracle/runtime scan/zero fallback/zero restart/clean teardown로 통과했다.
- **잔여 이슈**:
  - 실패 campaign은 재시도하거나 성공 row와 합치지 않는다. 새 336-cell Docker campaign의 남은 셀을 계속
    실행하고, 완료 후 3×7 그래프와 planner ordering/worker scaling을 검증한다.
- **잠재 회귀 위험**:
  - 장시간 실행 중 공간이 다시 floor 아래로 내려가면 다음 cell 전에 campaign이 중단된다. 이는 실험 row를
    오염시키지 않는 의도된 fail-closed 동작이며 pre-cell resource snapshot/error로 감지한다.
- **의사결정 근거**: semantic oracle이나 자원 기준을 완화하지 않고, 동일 계약을 실행 전 경계로 승격했다.
- **적용 원칙/제약**: Docker-only 실험, one attempt per logical cell, 실패 campaign in-place retry/stitching 금지,
  동일 seed/data/JAR 유지, runtime fallback 금지.

## 7. 사용자가 실험 profile 순서를 wan_light → wan_mid → LAN으로 변경함

- **상태**: 해결 — 기존 LAN-first campaign 폐기 및 profile-major harness 구현 완료
- **환경/조건**:
  - 폐기 campaign `/home/mchoi/g014-one-pass-results-97f792b-d2f4fa4-20260804-v1`
  - 폐기 시점: 11/336 완료, 12번째 `DP/KMeans/LAN/worker=4` 실행 중
- **재현 절차**:
  - 기존 manifest의 첫 cell과 `run_one_pass_performance.campaign_schedule()` loop 순서를 확인한다.
- **관측 증상**:
  - 기존 workload-major schedule은 KMeans의 LAN을 먼저 실행했으나, 새 요구사항은 모든 workload의
    wan_light를 먼저 완료하고 wan_mid, LAN 순서로 진행하는 것이다.
- **원인 분석**:
  - 기존 `PROFILES`와 workload-outer loop가 LAN-first/workload-major 순서를 인증 manifest에 고정했다.
- **해결 요약**:
  - 실행 중 campaign에 새 순서를 덮어쓰거나 11개 행을 재사용하지 않았다.
  - Ctrl-C 후 남은 Docker project `g007-op-6f55e9cac31f8c44`를 정확한 compose configuration으로 내려
    container/network 0개를 확인했다.
  - 기존 output에 `operator-cancelled.json`을 기록하고 `final_campaign_adoption=false`,
    `stitch_into_replacement_campaign=false`를 명시했다.
  - harness는 첫 112개 wan_light, 다음 112개 wan_mid, 마지막 112개 LAN이 되도록 변경했으며
    336 unique cell과 workload/profile 내부 Williams counterbalancing을 유지했다.
- **수정 파일**:
  - SystemDS 실행 코드 변경 없음
  - harness `experiments/tools/run_one_pass_performance.py`
  - harness `experiments/tests/test_one_pass_performance.py`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - profile-major RED test가 기존 코드에서 실패하고 변경 후 통과했다.
  - schedule 경계 1–112 wan_light, 113–224 wan_mid, 225–336 LAN 및 336/336 unique를 확인했다.
  - harness commit `cac37301b4303aa186b2327fdbc1ac290ba655ed`에서 focused 11/11 및 전체
    harness 147/147 테스트가 통과했다.
  - 변경 Python 파일 `py_compile` 및 `basedpyright` 0 errors/0 warnings/0 notes, `git diff --check`가 통과했다.
  - 새 immutable stage `a07aa85b86f5f44e3aadc8f4c0b8129479fd210b2564576f2469507345ce99e4`를
    staged validator로 검증했다. data/reference/JAR hash는 기존 고정값과 동일하다.
  - 새 manifest `2ea60881c44bd4600efe9370c1eaa68ba82b866bbafdc9ac76739d56ef201aa8`는 336/336
    unique cell, profile 경계 112/112/112, seed `2026072701`, retry policy `NONE`을 인증한다.
  - 첫 live Docker request와 실제 runner argv가 모두
    `workers=1|planner=DP|workload=kmeans|profile=wan_light`임을 확인했다.
  - 첫 cell은 semantic oracle/runtime scan을 통과하고 fallback 없이 성공했다. warm primary execution은
    52.495초, teardown 후 Docker resource 0개였으며 WAN-light 관측 RTT는 25.108 ms였다.
- **잔여 이슈**:
  - 새 output `/home/mchoi/g014-one-pass-results-97f792b-cac3730-20260804-v1`의 나머지 335개 cell을
    exactly-once로 완료하고, 실패가 없을 때만 정렬 분석과 3×7 그래프를 생성한다.
- **잠재 회귀 위험**:
  - profile 순서만 바꾸되 planner/worker period 균형이 깨질 수 있으므로 전체 harness period-count test와
    manifest 검증으로 감지한다.
- **의사결정 근거**: 과거 행을 결과에 재배열하지 않고 authenticated 실행 순서 자체를 변경했다.
- **적용 원칙/제약**: Docker-only, immutable stage/manifest, exactly once, 과거 행 stitching 금지,
  runtime fallback 금지.

## 8. MinST가 KMeans 반복 로컬 입력 전송을 목적함수에서 누락해 DP보다 느린 계획을 선택함

- **상태**: 해결 — 비용모델 회귀와 새 immutable Docker WAN-light DP/MinST canary 통과
- **환경/조건**:
  - 실패 source commit `97f792bdbef8ea63aa2727b4f8d26e571be515f7`
  - 실패 campaign `/home/mchoi/g014-one-pass-results-97f792b-cac3730-20260804-v1`
  - profile/workload/worker: WAN-light / KMeans / worker=1
  - 고정 seed `2026072701`, 동일 frozen data/reference/JAR, warm-primary 비교
- **재현 절차**:
  - 실패 MinST raw log:
    `/home/mchoi/g014-one-pass-results-97f792b-cac3730-20260804-v1/cells/003-34c16bf0f605/phases/cell-1/warm-fresh-coordinator-jvm/raw_coordinator.log`
  - 회귀 테스트:
    `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.CampaignBG014MinStKMeansWanRepeatedUploadRedTest test`
- **관측 증상**:
  - 동일 조건 warm 실행시간이 DP `52.495 s`, FedAll `62.803 s`, MinST `90.468 s`로 MinST가 가장 느렸다.
  - MinST는 `Fed Put Bytes=2,558,734,200`과 `fed_fed_refed=37.019 s` 50회를 기록했다. DP는
    `Fed Put Bytes=77,741,040`, `fed_fed_refed=1.077 s` 50회였다.
  - KMeans loop에서 `FED ba+*`가 만든 local 3000×2100 행렬(약 50.4 MB)을 뒤의 `FED b(*)`가
    `ABSENT_LOCAL` 입력으로 받아 매 iteration runtime broadcast했다.
- **원인 분석**:
  - `MinStExactPhysicalModel`은 이 경로를 합법적인 `NATIVE_LOCAL` authority로 정확히 보존했다. 문제는
    후보 합법성이 아니라 물리 목적함수였다.
  - `MinStExactCostFactsProducer.addPhysicalCompiledTransferFactors`는 FOUT→CP download와 명시적
    `RELOCATION` upload만 가격에 포함했다. FED instruction이 local matrix를 직접
    `broadcast`/`broadcastSliced`하는 `NATIVE_LOCAL` 물리 전송은 누락됐다.
  - occurrence profile은 이 edge의 loop 실행 가중치를 정확히 50으로 계산했지만, 곱할 전송 factor가 없어
    MinST가 반복 50.4 MB upload를 사실상 무료로 비교했다.
- **해결 요약**:
  - exact compiled input edge마다 선택된 FED alternative의 해당 input authority가 `NATIVE_LOCAL`일 때만
    활성화되는 categorical factor를 추가했다.
  - factor는 실제 runtime 전송 형태에 맞춰 동일 shape ROW/COL은 sliced transfer, 그 외 FULL/PART/shape
    broadcast는 replicated broadcast로 계산하고, occurrence 가중치(본 재현에서는 50)를 적용한다.
  - producer가 FOUT이면 local operand로 사용하기 위한 download도 함께 가격에 포함한다.
  - aggregate-binary/WDivMM처럼 공통 mixed FED/local stage가 이미 전체 input preparation 비용을 소유한
    경우 generic upload를 중복 계산하지 않는다.
  - planner relocation action/receipt를 만들거나 후보를 닫지 않았고 runtime fallback도 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/CampaignBG014MinStKMeansWanRepeatedUploadRedTest.java`
  - `docs/SESSION_ISSUES_2026-08-04.md`
- **검증**:
  - 신규 CLI RED는 수정 전 실제 KMeans plan에서
    `G014_MINST_CLI_SELECTED_50X_50MB_NATIVE_LOCAL_BROADCAST`로 실패했고, 수정 후 동일 명령이 통과했다.
  - direct exact-surface 회귀는 반복 edge 가중치가 정확히 `50.0`임과 수정 후 bad plan을 선택하지 않음을 확인했다.
  - `MinStExactPhysicalPlanSpaceOracleTest#outerRowsComposeWithIndependentAllBitAssignmentObjectiveOracle` 통과:
    새 factor를 포함한 variable-elimination 결과가 bounded exhaustive objective oracle과 일치했다.
  - `MinStExactPhysicalModelCertificateTest` 전체 통과 및 `mvn -q -DskipTests package` 성공.
  - 전체 `MinStExactPhysicalPlanSpaceOracleTest`의 다른 두 fixture 실패는 수정 전 HEAD에서도 동일하게 재현했다.
    하나는 현재 합법인 function FOUT→LOUT download를 불법으로 가정하는 stale assertion이고, 다른 하나는
    required derived action 없이 derived emission을 생성하는 stale fixture라 본 이슈의 factor와 무관하다.
  - source commit `9b6803c8948d30a3e9a509381e3ffa7f0d8cb7eb`, JAR SHA-256
    `283cec0a6340df6a7701781c52e43553baffe1107dd79d80e9910c9bacf638c1`, immutable stage
    `be9900f4f101e7bdbfdd5ed9d7b899ac8cdfa604c2f02b71be7120b04b58d702`에서 동일 seed/data/reference의
    WAN-light KMeans worker=1 DP와 MinST를 각각 exactly-once Docker canary로 실행했다.
  - warm primary는 MinST `45.052 s` ≤ DP `54.115 s`로 MinST가 `9.063 s` (`16.748%`) 빨랐다.
    cold도 MinST `49.401 s` ≤ DP `60.899 s`였다.
  - MinST의 matrix put은 실패 계획 `2,558,734,200 bytes`에서 `38,715,000 bytes`로 줄었고
    `fed_fed_refed 50회/37.019 s`는 `0회/0 s`로 제거됐다. 비교 DP는 `77,741,040 bytes`,
    `fed_fed_refed 50회/1.078 s`였다.
  - 두 planner의 cold/warm 모두 semantic oracle/runtime scan, normalized runtime-plan 동일성,
    instruction fingerprint 동일성, zero fallback/timeout/error, coordinator/worker restart 0,
    project container/network/volume teardown 0을 검증했다.
  - canary receipt:
    `/home/mchoi/g014-minst-native-local-canary-results-9b6803c-283cec0a-20260804-v1/canary-receipt.json`
    (SHA-256 `dd291591b1fab05429b1531812e3a63e66ff64981019738536f7c796c415f9e2`).
- **잔여 이슈**:
  - 이 canary는 KMeans/WAN-light/worker=1 결함의 해결 증거이지 7 workload × 4 worker × 3 profile 전체
    정렬 증명은 아니다. 폐기 campaign 행을 재사용하지 않고 새 profile-major 336-cell campaign을 처음부터 실행해
    나머지 plan/runtime/ordering을 검증한다.
- **잠재 회귀 위험**:
  - runtime branch와 shape 기반 sliced-vs-replicated 추론이 달라지면 전송비를 과대/과소평가할 수 있다.
    exact objective oracle과 Docker `Fed Put Bytes`/heavy-hitter 횟수로 감지한다.
  - mixed FED/local stage가 일부 입력만 가격에 포함하는 새 opcode를 추가하면 현재 stage-level 중복 방지로
    다른 local input이 누락될 수 있다. opcode별 mixed-stage 테스트와 Docker stats로 감지한다.
- **의사결정 근거**: 합법 candidate를 임의로 닫지 않고, 실제 runtime-owned 전송을 전역 MinST 목적함수에
  포함해 MinST가 최적화하는 값과 실행 비용의 불일치를 바로잡았다.
- **적용 원칙/제약**: MinST 전역 최적성은 인코딩된 합법 plan space/물리 목적함수 기준, DP의 국소 탐색 한계는
  유지, 후보 임의 축소 금지, 비용/shape/boundary 우선 수정, runtime fallback 금지, Docker-only 성능 검증.

## 9. FedAll LM의 derived FOUT left-transpose rewrite가 local 결과를 federated로 오표기함

- **상태**: 해결 — 코드/단위·통합 테스트 및 동일 Docker 실패 cell canary 통과
- **환경/조건**:
  - 실패 campaign: `/home/mchoi/g014-one-pass-results-1f7fbce-cac3730-20260804-v1`
  - logical cell: `workers=3|planner=FedAll|workload=lm|profile=wan_light`
  - 실패 cell: `cells/035-70a3ed621b33`, attempt 1, retry 없음
- **재현 절차**:
  - 실패 로그:
    `cells/035-70a3ed621b33/phases/cell-1/cold-docker-e2e/raw_coordinator.log`
  - focused integration:
    `mvn -q -Dsysds.compile.log_lop_mapping=true -DskipTests=false -Dtest=org.apache.sysds.test.functions.federated.fedplanning.FederatedLMPlanningTest#runLMFunctionPlannerFOUTPrivacyNone test`
- **관측 증상**:
  - runtime이 `FED reorg requires federated input but found local at runtime. op=r' input=_mVar17 dims=1x2100 fedOut=LOUT`로 중단했다.
  - planner가 선택한 derived `FED/FOUT`은 native aggregate-binary 결과가 LOUT으로 생성된 뒤 명시적으로 FOUT
    materialize되는 상태인데, left-transpose MM lowering이 최종 `_federatedOutput=FOUT`을 내부 multiply와 결과
    transpose에 직접 전파했다.
- **원인 분석**:
  - 일반 Hop lowering은 `getEffectiveFederatedOutput`으로 derived `FED/FOUT`의 native output을 LOUT으로 낮췄다.
  - `AggBinaryOp.constructCPLopsMMWithLeftTransposeRewrite`만 이 공통 경로를 우회하여 내부 multiply가 실제로는
    local 결과를 내면서 FOUT FederationMap을 가진 것처럼 광고했고, 뒤의 FED transpose가 이를 소비했다.
- **해결 요약**:
  - `Hop.getEffectiveFederatedOutput`을 subclass가 재사용할 수 있도록 `protected`로 변경했다.
  - left-transpose rewrite 내부 multiply와 결과 transpose의 ExecType을 최종 요청값이 아니라 effective native
    output에서 결정한다. derived FOUT이면 multiply는 LOUT, outer transpose는 CP가 되고, 최종 FOUT은 기존
    graph-owned materialization action만 생성한다.
  - candidate를 닫거나 runtime fallback을 추가하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/Hop.java`
  - `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedLMPlanningTest.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
- **검증**:
  - 신규 lowering 회귀는 수정 전 outer transpose가 FED여서 실패했고 수정 후 CP/LOUT을 확인했다.
  - `FederatedRefedPolicyTest` 전체 통과.
  - LM privacy-none integration이 성공했으며 LOP mapping에서 left-transpose multiply는 `FED ba+* ... LOUT`,
    결과 transpose는 `CP r'`로 생성됐다. 실행시간 `1.075 s`, fallback/error 0.
  - source commit `96858a152a0a6adec0a7e8cae70a3d5addf0d3fc`, JAR SHA-256
    `7d812e31f220b41e2d734235dbba8aa20905ce2736c3cbfff06cd5e5356c88f3`, immutable stage
    `268670c1927b77d7bcfffe47f254230556976f2c1986baaf20d429ec5654285c`에서 과거와 동일한
    `WAN-light/worker=3/FedAll/LM` Docker canary를 exactly-once 실행했다.
  - cold `13.219 s`, warm `12.509 s`로 모두 성공했다. cold/warm runtime plan SHA-256은 동일한
    `8716c339d846790f9a1347bc765b9f96a6000e58fc4988207fac915b3613d12d`이고 instruction fingerprint도
    `fed_ba+*:1;fed_fedinit:2;fed_r':1`로 동일하다.
  - runtime plan에서 입력 `y` transpose만 `FED r' ... FOUT`, native multiply는 `FED ba+* ... LOUT`,
    그 결과 transpose는 `CP r'`임을 확인했다. 과거 오류의 `_mVar17` 대상 `FED r'`는 존재하지 않는다.
  - 두 phase 모두 semantic oracle/runtime scan, zero fallback/timeout/error, coordinator/worker restart 0,
    container/network/volume teardown 0을 검증했다.
  - canary receipt:
    `/home/mchoi/g014-fixed-canary-results-96858a1-7d812e31-20260804-v1/canary-receipt.json`
    (SHA-256 `df7dda0ae25d8909b9244559de76d68f5c6ce7b0da6864f4cfb4224b10b2f44c`).
- **잔여 이슈**:
  - 이 canary는 정확한 실패 셀의 해결 증거다. 새 336-cell campaign 전체에서 다른 worker/profile과
    모든 workload의 FedAll 실행 가능성 및 정렬을 별도로 검증한다.
- **잠재 회귀 위험**:
  - native FOUT이 가능한 비-derived rewrite까지 CP로 낮추면 불필요한 download가 생길 수 있다. 회귀 테스트는
    derived bit가 있을 때만 native LOUT으로 낮아지는지와 planner-selected 최종 FOUT authority 보존을 함께 확인한다.
- **의사결정 근거**: planner의 derived materialization 계약을 lowering이 그대로 실행하도록 수정했다.
- **적용 원칙/제약**: planner 계획 그대로 실행, runtime fallback 금지, graph-owned exact materialization authority,
  후보 임의 축소 금지, Docker-only 성능 검증.

## 10. MinST PCA worker=1이 unknown-dimension sentinel을 실제 broadcast 크기로 사용함

- **상태**: 해결 — 비용모델/회귀 테스트 및 Docker 성능 canary 통과
- **환경/조건**:
  - campaign: `/home/mchoi/g014-one-pass-results-1f7fbce-cac3730-20260804-v1`
  - `WAN-light/PCA/worker=1`, 고정 seed/data/JAR
- **재현 절차**:
  - 기존 MinST cell `cells/025-ce492e271b49`
  - 기존 DP cell `cells/026-9a9725a896f2`
  - 회귀:
    `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.fedCostBased.fedMinSTCut.MinStPcaAuthorityClosureAndTWriteMetadataTest test`
- **관측 증상**:
  - warm primary가 MinST `106.830 s`, DP `54.857 s`였다.
  - MinST는 50000×2100 정규화 입력을 coordinator로 받고 `CP tsmm`에 `58.683 s`를 사용했으나, DP는
    같은 covariance TSMM을 FED로 실행해 `5.178 s`였다. worker=2–4 MinST는 FED TSMM을 선택했다.
- **원인 분석**:
  - PCA recompile 경계의 `Components` TRead는 compile-time 차원이 `-1×-1`이지만 runtime은 약 `2100×10`이다.
  - MinST private `estimatedBytes`는 positive raw `getOutputMemEstimate()`를 먼저 반환했고, unknown-dimension
    sentinel 약 `3.15 GiB`를 실제 broadcast 크기로 가격 책정했다.
  - 공통 `FederatedCostModel.getEffectiveOutputMemEstimate`는 동일 HOP을 `256 MiB` bounded estimate로 처리한다.
    MinST만 이 공통 경로를 우회하여 final projection FED 비용을 과대평가했고, 이미 다운로드한 X 재사용 가정 때문에
    앞의 covariance TSMM까지 CP가 근소하게 싸다고 잘못 선택했다.
- **해결 요약**:
  - matrix shape가 실제로 해결된 경우에만 raw positive estimate를 즉시 사용한다.
  - unknown shape이면 먼저 immutable placement fact에서 증명 가능한 exact shape를 찾고, 없으면 DP와 공유하는
    effective output estimate를 사용한 뒤 raw/anchor fallback 순서로 처리한다.
  - candidate/ExecType 조합을 닫지 않고 실제 비용 입력만 바로잡았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactCostFactsProducer.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStPcaAuthorityClosureAndTWriteMetadataTest.java`
- **검증**:
  - 신규 회귀는 수정 전 covariance TSMM이 CP여서 실패했고 수정 후 covariance와 final projection 모두 FED임을 확인했다.
  - unknown raw estimate가 공통 effective estimate보다 큼을 fixture에서 직접 검증한다.
  - `MinStPcaAuthorityClosureAndTWriteMetadataTest` 3/3, `MinStExactPhysicalModelCertificateTest` 8/8,
    `MinStExactPhysicalPlanSpaceOracleTest` 9/9 및 legacy-representable small-fixture parity 2/2가 통과했다.
  - source/JAR/stage는 issue 9와 같은 `96858a1...` / `7d812e31...` / `268670c...`이며,
    동일 seed/data/reference의 `WAN-light/worker=1/MinST/PCA` Docker canary를 exactly-once 실행했다.
  - cold `57.145 s`, warm `54.483 s`로 성공했다. 과거 실패 계획의 warm `106.830 s`보다
    `52.347 s` (`49.000%`) 감소했고, 같은 과거 campaign의 DP `54.857 s`와 동등한 범위로 복구됐다.
  - cold/warm runtime plan SHA-256은 동일한
    `da76cc5e34b8ea51340b7e827a96e48bf900c8b85c1386cba546099d61cb3e23`이다.
    실제 plan은 covariance를 `FED tsmm ... LOUT`, final projection을 `FED ba+* X ... FOUT`으로 실행하며,
    전체 `X`의 `CP prefetch`는 없다. 작은 1x2100 mean만 CP로 prefetch한다.
  - 두 phase 모두 semantic oracle/runtime scan, zero fallback/timeout/error, coordinator/worker restart 0,
    container/network/volume teardown 0을 검증했다. canary receipt와 SHA-256은 issue 9에 기록했다.
- **잔여 이슈**:
  - 단일 canary는 이 PCA worker=1 결함의 해결 증거다. 새 336-cell campaign에서 모든 worker/profile의
    MinST plan과 실행시간 정렬을 별도로 검증한다.
- **잠재 회귀 위험**:
  - 실제 크기가 immutable fact로 알려진 큰 source를 generic 256 MiB로 축소하면 반대로 upload를 과소평가할 수 있다.
    따라서 exact immutable shape가 effective fallback보다 먼저 적용되며 테스트가 이 순서를 보호한다.
- **의사결정 근거**: DP/MinST가 공유해야 하는 개별 HOP 크기 비용 의미를 공통 effective estimate와 일치시켰다.
- **적용 원칙/제약**: MinST 전역 최적성은 인코딩된 물리 목적함수 기준, 비용/shape 추정 우선 수정,
  후보 임의 축소 금지, runtime fallback 금지, Docker-only 성능 검증.

## 11. exact physical 회귀 테스트 세 건이 현재 계약 이전의 가정을 고정함

- **상태**: 해결
- **환경/조건**: MinST exact physical test suite, source HEAD `1f7fbce3402e`
- **재현 절차**:
  - `MinStExactProductionTractabilityCertificateTest#physicalAlternativeFactorsAreBitExactWithLegacyCutOnSmallFixture`
  - 전체 `MinStExactPhysicalPlanSpaceOracleTest`
- **관측 증상**:
  - legacy parity fixture가 새 physical-only `NATIVE_LOCAL` runtime transfer factor까지 legacy cut과 bit-exact여야 한다고 가정했다.
  - function boundary fixture가 합법이고 비용이 부과되는 FOUT→LOUT 전달도 동일 placement identity여야 한다고 가정했다.
  - derived emission fixture가 graph-owned materialization action 없이 derived bit만 생성해 constructor invariant에서 실패했다.
- **원인 분석**: issue 8의 NATIVE_LOCAL 비용 추가, function value-transfer 비대칭 계약, exact derived-action authority가
  도입된 뒤 fixture/assertion이 갱신되지 않았다. 현재 production 코드 결함이나 issue 9/10 변경 회귀는 아니었다.
- **해결 요약**:
  - legacy parity fixture는 legacy가 표현 가능한 scalar-input 범위에서 bit-exact를 계속 완전열거한다.
  - function boundary는 공통 `constraintSatisfied`의 합법성을 검증하고, FOUT target일 때만 exact FOUT/FType 전달을 요구한다.
  - derived/native 구분 테스트는 실제 graph-owned derived action을 가진 분석에서 emission을 가져와 동일 state의 native
    counterpart를 구성하므로 invalid authority를 만들지 않는다.
- **수정 파일**:
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactProductionTractabilityCertificateTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalPlanSpaceOracleTest.java`
- **검증**: 관련 exact physical 묶음 22 tests, failures/errors 0.
- **잔여 이슈**: 28 campaign-shape workload/worker를 포함한 장시간 production certificate는 DP baseline 열거에서
  13분 이상 소요되어 중단했다. 새 비용 변경과 직접 관련된 exact physical/plan-space 검증은 모두 통과했다.
- **잠재 회귀 위험**: parity fixture 범위를 다시 matrix NATIVE_LOCAL 입력까지 넓힐 때는 legacy equality가 아니라
  physical-only factor를 독립 계산해 차이를 검증해야 한다.
- **의사결정 근거**: production 계약을 완화하지 않고 stale test가 유효한 권한/비용 의미를 검증하도록 고쳤다.
- **적용 원칙/제약**: exact graph authority, 합법 candidate space 유지, runtime transfer 비용 누락 금지.

## 12. systemd user service가 Docker 보조 그룹을 상속하지 않아 새 campaign 첫 요청이 실행 전 실패함

- **상태**: 해결 — 실패 output 보존·미채택 후 명시적 Docker group 경계로 새 campaign 정상 시작
- **환경/조건**:
  - 실패 output: `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v1`
  - 정상 output: `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2`
  - systemd user unit에서 시작한 profile-major 336-cell Docker campaign
- **재현 절차**:
  - 실패 로그:
    `g014-one-pass-results-96858a1-cac3730-20260804-v1/cells/001-1d2a37e2ae1b/runner.stderr.log`
  - 직접 shell의 `id`와 `systemd-run --user --wait --collect --pipe id`를 비교한다.
- **관측 증상**:
  - 첫 logical request는 생성됐지만 `permission denied while trying to connect to the docker API at
    unix:///var/run/docker.sock`로 Docker container 생성 전에 중단됐다.
  - 실패 output은 row 0개, `failures.jsonl` 1개이며 in-place retry하지 않았다.
- **원인 분석**:
  - 로그인 shell은 supplementary group `docker(10001)`를 가지지만 오래 실행 중인 systemd user manager는
    primary group `employees(5500)`만 상속했다. Docker socket은 `root:docker 0660`이라 service child가 거부됐다.
  - planner/runtime/harness 코드 결함이 아니라 detached launcher의 OS group 경계 문제다.
- **해결 요약**:
  - 실패 output을 성공 결과에 합치거나 재시도하지 않고 그대로 보존했다.
  - 새 `v2` output은 launcher에서 `/usr/bin/sg docker -c ...`로 Docker group을 명시적으로 설정한다.
  - source/JAR/harness/stage/seed/data/reference는 변경하지 않았고 새 manifest로 0/336부터 시작했다.
- **수정 파일**:
  - SystemDS/harness production code 변경 없음
  - campaign artifact:
    `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2/launcher-command.sh`
- **검증**:
  - 동일 systemd user 경계에서 `sg docker -c '/snap/bin/docker version ...'`가 server `29.6.1`을 반환했다.
  - `v2`가 실제 Docker project `g007-op-1d2a37e2ae1b849c`를 생성하고 첫 셀
    `WAN-light/worker=1/DP/KMeans`를 exactly-once 완료했다.
  - 첫 row는 cold `58.856 s`, warm `52.583 s`, semantic oracle/runtime scan 통과, fallback 0,
    coordinator/worker restart 0, teardown zero resources이다.
  - launch receipt:
    `/home/mchoi/g014-one-pass-results-96858a1-cac3730-20260804-v2/launch-receipt.json`
    (SHA-256 `5348fb1005f745a4da204f47fa3591f9735563332bd8d9d4083b96bb47010633`).
- **잔여 이슈**:
  - `v2`의 남은 335개 셀을 계속 실행하고, 실패 0으로 완료된 뒤 3x7 그래프와 planner ordering/worker
    scaling을 검증한다.
- **잠재 회귀 위험**:
  - host 재로그인/daemon 변경으로 group/socket 계약이 바뀔 수 있다. 매 launch 전에 service 경계의 Docker API
    probe를 실행하고 첫 실제 project 생성 및 teardown 영수증으로 감지한다.
- **의사결정 근거**: 실행되지 않은 실패 attempt를 성공으로 덮지 않고 새 authenticated manifest/output을 사용했다.
- **적용 원칙/제약**: Docker-only, exactly-once, retry/stitching 금지, 동일 immutable stage/seed/data 유지.
