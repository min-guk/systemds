# Session issues — 2026-08-10

이 문서는 Docker 성능 실험을 다시 시작하기 전에 수행한 planning-only 감사에서 발견한 문제를 기록한다.
이번 세션의 공통 원칙은 **runtime fallback 금지**, **플래너가 선택한 계획을 로그와 receipt로 먼저 증명**, **실제 실행은 Docker stage에서만 수행**이다.

## 1. Planning-only 모드가 플래너 trace를 끄던 문제

- **상태**: 해결
- **환경/조건**: 모든 compiled planner, `run_LAN_docker.sh --planning-only --skip-net-check`
- **재현 절차**: planning-only 실행 후 coordinator log에서 `[PlannerTrace]` 검색
- **관측 증상**: compile-only는 정상 종료하지만 플래너 trace가 없어 선택 근거를 확인할 수 없었다.
- **원인 분석**: `distributedExpNew.sh`가 일반 benchmark 정책대로 planner trace를 명시적으로 껐다. compile-only와 trace-enable이 독립 조건으로 모델링되지 않았다.
- **해결 요약**: planning-only에서만 `-Dsysds.fedplanner.trace=true`를 전달하고, worker JVM과 runtime output 생성을 금지하는 receipt 경로를 추가했다.
- **수정 파일**: harness 저장소의 `experiments/code/distributedExpNew.sh`, `experiments/run_LAN_docker.sh`, `experiments/tools/planning_receipt.py` 및 테스트
- **검증**: LAN LogReg, workers=2, DP planning-only receipt에서 `runtime_executed=false`, `execution_seconds=0.0`, runtime output 부재를 확인했다.
- **잔여 이슈**: 아래 2~4번 때문에 trace가 존재한다는 사실만으로 특정 플래너가 실제 실행됐다고 증명할 수 없었다.
- **잠재 회귀 위험**: benchmark 공통 옵션 정리 시 trace가 다시 꺼질 수 있다. `test_docker_planning_path_is_compile_only_trace_and_skips_worker_jvms`로 감지한다.
- **의사결정 근거**: runtime을 완화하지 않고 실험 하네스의 관측 경로만 수정했다.

## 2. DP 무필터 planning trace가 수 GB까지 폭증하고 trace가 production cache를 비활성화

- **상태**: 해결, 새 JAR Docker planning-only 재검증 필요
- **환경/조건**: DP, LAN, LogReg, workers=2, `SYSDS_FED_PLANNER_TRACE=1`, hop filter 없음
- **재현 절차**: immutable stage `g014-planning-audit-stage-e65d367-20260810-v2/...`에서 planning-only DP 실행
- **관측 증상**:
  - 첫 실패에서 runtime 시작 전 coordinator log가 `13,311,859`줄, `2,734,882,116`바이트까지 증가했다.
  - 첫 제한 수정 뒤에도 최신 immutable stage의 LAN LogReg workers=2 DP planning-only가 runtime 진입 전 `6,304,812`줄, `1,475,908,193`바이트까지 다시 증가했다. 원본 SHA-256은 `45482dfdaa35e7909b9d69f26edd5b4b128493a10f6b6b6c8152859b912e12bc`이고, 보존 증거는 `/home/mchoi/g014-planning-audit-stage-650d369-895fd3e-20260810-v1/g007-stage-f5a4b91333c74ce0e00c7d4400785e949f91967f558b2a10ee659458870f1e33/results/planning-failures/plan-lan-logreg-w2-dp-20260810-v3/`이다.
  - 두 번째 로그의 최다 stage는 `DP-OutputDecision-Member` 3,123,860건, `DP-ParentVariantCandidate` 1,022,287건, `DP-BoundaryShare` 442,152건, `DP-StableTRShare` 434,223건이었다.
- **원인 분석**:
  - 첫 수정은 decision-map 상세 일부만 제한했고, output decision/parent variant/boundary share 등 다른 hot-loop stage에는 전역 상한이 없었다.
  - 더 심각하게 `computeParentVariantSwitchDelta`가 trace 활성 시 `ParentVariantDeltaCache`를 의도적으로 사용하지 않았다. 즉 관측 기능이 production 계산 경로와 planning time을 바꾸고 동일 후보를 대량 재계산했다.
- **해결 요약**:
  - 명시적 hop filter가 없으면 동일 score breakdown은 결정적인 대표 hop 하나에만 기록한다.
  - root/alternative/bundle/family 상세는 `SYSDS_FED_PLANNER_TRACE_MAX_EDGES` 예산으로 제한한다.
  - 생략 개수를 `*Summary` stage로 남긴다.
  - 모든 hop-detail stage에 invocation별 기본 4096건 상한을 적용하고, 생략 건수를 정렬된 `Trace-SuppressionSummary`로 남긴다.
  - 최다 반복 DP stage는 `Supplier<String>` 기반 lazy trace로 바꿔 억제된 레코드의 `String.format` 비용도 없앴다.
  - trace 여부와 무관하게 production `ParentVariantDeltaCache`를 항상 사용하도록 복원했다. 후보/비용/선택 로직과 cache key는 변경하지 않았다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerTrace.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpCostEstimator.java`
  - `src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java`
  - `src/main/java/org/apache/sysds/parser/DMLTranslator.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/FederatedPlannerTraceBudgetTest.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBG014PlanningTraceContractTest.java`
- **검증**: 새 계약 테스트는 `trace API lacks a per-stage record budget`으로 RED였고, 수정 후 trace budget·planner identity·MinST physical oracle 관련 23 tests가 통과했다. `mvn -DskipTests package`도 성공했으며 그 과정의 기본 10 tests가 추가 통과했다. 실제 새 JAR 로그 크기와 plan hash 동일성은 다음 immutable Docker planning-only stage에서 확인한다.
- **잔여 이슈**: 반복 iteration 자체의 수는 유지된다. 이번 변경은 중복 상세만 제한하며 알고리즘 반복 횟수나 플랜을 바꾸지 않는다.
- **잠재 회귀 위험**: `logGlobal`은 identity/objective receipt를 잃지 않도록 의도적으로 상한 밖이다. 향후 hot loop에서 `logGlobal`을 사용하면 다시 폭증할 수 있으므로 source contract와 실제 log byte/record count 상한으로 감지한다.
- **의사결정 근거**: 후보 공간·비용·선택은 변경하지 않고 observability만 bounded하게 만들었으며, trace가 비활성화하던 production cache를 복원해 audit와 실제 planning 경로를 일치시켰다.

## 3. Receipt가 공통 전처리 trace를 특정 플래너 실행 증거로 오인하고 실제 진입점 identity가 누락됨

- **상태**: 해결, 새 JAR planning-only 재검증 필요
- **환경/조건**: 모든 planning-only compiled planner
- **재현 절차**:
  1. 기존 `planning_receipt.py --require-trace`에 `Neutral-*`/`PlannerRecompileState-*` trace만 포함된 log 입력
  2. 수정 JAR `b858fa43448b20cc77c8e618c2337688f6420fb3f5d0bf59efa09f77f3ed6a01`로 LAN LogReg workers=2 MinST planning-only 실행
- **관측 증상**:
  - 기존 receipt는 production MinST 선택 trace가 0개여도 성공했다.
  - v2 receipt를 적용한 첫 새-JAR canary는 `MinST-PhysicalOptimize/Complete`를 각각 1개 기록했지만 `Planner-Invoke`가 0개여서 정확히 거부됐다.
  - 실패 로그: immutable stage `g014-planning-audit-stage-650d369-dde3371-20260810-v1/.../results/fed2/mkl-min-st-cut/logreg_dataset-P2P2D_coordinator_mkl-min-st-cut_plan-lan-logreg-w2-minst-20260810-v2_lan_coordinator1.log`.
- **원인 분석**:
  - receipt가 `[PlannerTrace]` 존재만 검사해 config planner와 실행 implementation identity를 교차 검증하지 않았다.
  - 첫 identity 수정은 `IPAPassRewriteFederatedPlan`에만 적용했지만, 실제 benchmark production compile은 `DMLTranslator.runFederatedPlannerAtFinalHopBoundary`에서 factory 결과를 직접 실행한다. 따라서 사용되지 않는 진입점만 계측한 상태였다.
- **해결 요약**:
  - `IPAPassRewriteFederatedPlan`과 실제 final-hop production 진입점 모두 factory에서 얻은 동일 implementation instance의 `Planner-Invoke`/`Planner-Complete`를 기록한다.
  - receipt가 config의 `sysds.federated.planner`를 파싱하고 두 identity record가 정확히 하나이며 동일 planner인지 검사한다.
  - receipt schema를 v2로 올리고 `planner` 필드를 추가했다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/ipa/IPAPassRewriteFederatedPlan.java`
  - `src/main/java/org/apache/sysds/parser/DMLTranslator.java`
  - harness `experiments/tools/planning_receipt.py`
  - 양 저장소의 계약 테스트
- **검증**: wrong-planner fixture와 identity 누락 fixture가 RED였다. 실제 canary도 identity 0건으로 fail-closed 거부됐다. `DMLTranslator` production entry 계측을 요구하는 source test가 RED(3 tests 중 1 failure)였고 수정 후 관련 source 20 tests가 통과했다. 최종 Docker 재검증은 다음 immutable JAR에서 수행한다.
- **잔여 이슈**: 수정된 final-hop entry가 실제로 identity pair를 정확히 하나 생성하는지는 재빌드한 Docker planning-only canary로 검증한다.
- **잠재 회귀 위험**: factory 호출과 로그 대상 instance가 분리되면 잘못된 구현을 기록할 수 있다. 현재 코드는 factory 결과를 지역 변수 하나로 유지하고 그 instance를 실행한다.
- **의사결정 근거**: 플래너 선택 로직은 바꾸지 않고 실제 호출 identity를 실패-폐쇄 receipt로 강화했다.

## 4. Production MinST 물리 최적화 경로에 선택/비용 trace가 없음

- **상태**: 해결, 대표 workload 감사 진행 중
- **환경/조건**: `COMPILE_MIN_ST_CUT`, 모든 profile/workload/worker
- **재현 절차**: LAN LogReg workers=2 MinST planning-only unfiltered trace
- **관측 증상**: 756개 trace가 모두 `Neutral-PhysicalClosure`, `Neutral-TransientReplay`, `PlannerRecompileState-*`였고 `MinST-*`는 0개였다. runtime plan hash가 DP/FedAll과 같다는 사실만 알 수 있고 MinST가 같은 결과를 합리적으로 최적화했는지는 알 수 없었다.
- **원인 분석**: production root는 legacy `FederatedPlanMinSTGraph`가 아니라 `MinStExactPhysicalModel → PhysicalCostSurface → PhysicalOptimizer → PhysicalSelection → Projector`를 사용하지만, trace는 legacy 계층에만 남아 있었다.
- **해결 요약**:
  - production physical optimizer의 objective, factor/transfer 수, solver width/cell 통계, cost fingerprint를 `MinST-PhysicalOptimize`로 기록한다.
  - concrete hop마다 선택 state/authority/derived-FOUT/input authority를 `MinST-PhysicalSelect`로 기록한다.
  - 각 대안은 다른 변수를 고정했을 때 incident factor 비용 차이(`fixedOthersDelta`)를 제한된 개수만 기록한다. 이것은 별도 전역 재최적화 값이 아님을 코드와 로그 명칭에 명시했다.
  - 최종 FED/FOUT/derived/relocation 수를 `MinST-PhysicalComplete`로 기록한다.
  - MinST receipt는 optimize/complete가 각각 정확히 하나 없으면 거부한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
  - harness `experiments/tools/planning_receipt.py`
  - 양 저장소의 테스트
- **검증**: `MinStExactPhysicalPlanSpaceOracleTest` 9개를 포함한 관련 20 tests 통과; full main/test compilation 성공. 실제 objective/selection trace와 plan hash는 새 stage 대표 셀에서 확인한다.
- **잔여 이슈**: `fixedOthersDelta`는 한 변수만 바꾼 조건부 차이이며, 해당 대안을 고정하고 나머지를 다시 최적화한 global marginal objective는 아니다. 전역 최적성 증명은 solver objective/certificate와 기존 oracle tests가 담당한다.
- **잠재 회귀 위험**: trace 계산이 planning time을 오염시킬 수 있다. trace는 명시적으로 활성화한 planning-only 감사에서만 수행하고, 실제 성능 실험에서는 비활성화한다.
- **의사결정 근거**: MinST cost surface·합법 plan space·optimizer 결과는 변경하지 않고 production 선택 근거만 노출했다.

## 현재 planning-only 관측(수정 전 JAR)

- LAN LogReg workers=2에서 FedAll, DP, MinST의 canonical runtime plan SHA-256은 모두 `738ce85c9069fb1671b959ee4ec1a9283f94278e45b24c98ed1de5ac6ae9d731`이었다.
- Heuristic은 `2fd760ce927b405aeeaff594f3fe4cae9f9ce0cfbc5461f2c2968dd2c3500ec3`으로 다르고 `fed_refed` 2개가 있었다.
- 따라서 이 셀에서 FedAll/DP/MinST runtime 시간이 비슷한 것은 **실제로 같은 plan을 실행했기 때문일 수 있다**. 다만 수정 전 MinST에는 production optimizer trace가 없어 그 동일성이 합리적 선택인지까지는 증명하지 못했다.
- DP cost trace 예시에서는 주요 `ba(+*)`가 CP/LOUT 약 `3281.742664`보다 FED/LOUT 약 `1268.827332`로, 다른 `ba(+*)`는 CP/LOUT 약 `3460.784312`보다 FED/FOUT 약 `17.954474`로 낮았다. LAN 전송비가 낮은 조건에서 FedAll과 겹치는 것은 적어도 이 hop들에서는 비용 모델상 합리적이다.

## 다음 검증 순서

1. 새 commit/JAR로 immutable Docker stage 생성
2. LAN LogReg-w2, StepLM-w1, KMeans-w2, ALS-w3의 네 planner를 runtime 없이 planning-only 실행
3. identity/MinST physical/DP bounded trace receipt와 plan hash 비교
4. 로그로 증명된 비용·합법성 결함만 regression test 후 수정
5. 최종 새 stage에서 WAN-light → WAN-mid → LAN 336-cell runtime 실험을 한 번만 실행
