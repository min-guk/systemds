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

- **상태**: 해결
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
- **검증**: 새 계약 테스트는 `trace API lacks a per-stage record budget`으로 RED였고, 수정 후 trace budget·planner identity·MinST physical oracle 관련 23 tests가 통과했다. `mvn -DskipTests package`도 성공했으며 그 과정의 기본 10 tests가 추가 통과했다. commit `cf87ae49ed2665e90e092d8988fa1b00887d0748`의 immutable Docker stage에서 LAN LogReg workers=2 DP planning-only가 성공했다. `runtime_executed=false`, `execution_seconds=0.0`, `Planner-Invoke/Complete=1/1`, runtime-plan SHA-256 `738ce85c9069fb1671b959ee4ec1a9283f94278e45b24c98ed1de5ac6ae9d731`로 기존 canonical plan과 같았다. coordinator log는 `18,123,598`바이트/`86,464`줄로, 직전 실패의 `1,475,908,193`바이트/`6,304,812`줄보다 크게 제한됐다. 생략된 hot-loop 상세는 14개의 정렬된 `Trace-SuppressionSummary`로 남았다.
- **잔여 이슈**: 반복 iteration 자체의 수는 유지된다. 이번 변경은 중복 상세만 제한하며 알고리즘 반복 횟수나 플랜을 바꾸지 않는다.
- **잠재 회귀 위험**: `logGlobal`은 identity/objective receipt를 잃지 않도록 의도적으로 상한 밖이다. 향후 hot loop에서 `logGlobal`을 사용하면 다시 폭증할 수 있으므로 source contract와 실제 log byte/record count 상한으로 감지한다.
- **의사결정 근거**: 후보 공간·비용·선택은 변경하지 않고 observability만 bounded하게 만들었으며, trace가 비활성화하던 production cache를 복원해 audit와 실제 planning 경로를 일치시켰다.

## 3. Receipt가 공통 전처리 trace를 특정 플래너 실행 증거로 오인하고 실제 진입점 identity가 누락됨

- **상태**: 해결
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
- **검증 추가**: commit `cf87ae49ed...` immutable Docker stage의 LAN LogReg workers=2 네 플래너 모두 `Planner-Invoke/Complete=1/1`을 기록했고, config와 구현 identity가 일치하는 v2 receipt가 성공했다.
- **잔여 이슈**: 없음. 향후 entry point 변경은 source contract와 Docker receipt가 fail-closed로 감지한다.
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
- **검증**: `MinStExactPhysicalPlanSpaceOracleTest` 9개를 포함한 관련 20 tests 통과; full main/test compilation 성공. commit `cf87ae49ed...` immutable Docker stage의 LAN LogReg workers=2에서 `MinST-PhysicalOptimize/Complete=1/1`, `MinST-PhysicalSelect=489`, `MinST-PhysicalAlternative=792`를 기록했고 planning-only receipt가 성공했다.
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

## 5. FedAll/Heuristic 정책 선택 근거가 planning trace에 없던 문제

- **상태**: 해결, 대표 workload 확대 감사 진행 중
- **환경/조건**: `COMPILE_FED_ALL`, `COMPILE_FED_HEURISTIC`, 모든 planning-only workload
- **재현 절차**: commit `cf87ae49ed...` immutable stage에서 LAN LogReg workers=2를 FedAll/Heuristic으로 planning-only 실행하고 trace stage를 집계한다.
- **관측 증상**:
  - FedAll과 Heuristic 로그에는 `Planner-Invoke/Complete`와 공통 `Neutral-*` trace만 존재했다.
  - 최종 runtime-plan SHA/fingerprint는 비교할 수 있었지만 FedAll의 `MAX_FED → MAX_FOUT → MIN_RELOCATIONS` 점수와 Heuristic의 demotion/local-prefix/re-entry 정책이 실제 선택에 적용됐는지 로그만으로 검증할 수 없었다.
- **원인 분석**: 공통 analysis와 emission 경로를 일원화하면서 DP/MinST에는 선택 trace를 남겼으나, adapter 기반 FedAll/Heuristic entry에는 정책 결과 trace를 추가하지 않았다.
- **해결 요약**:
  - FedAll은 `FedAll-PolicySummary`에 FED/FOUT/relocation 점수, selected-state 수, exact-search explored/pruned, normalized plan fingerprint를 기록하고 `FedAll-Select`로 concrete hop별 선택을 기록한다.
  - Heuristic은 `Heuristic-PolicySummary`에 marker/local-prefix/frontier 수와 FED/FOUT/relocation 점수, plan fingerprint를 기록하고 `Heuristic-Demotion` 및 `Heuristic-Select`로 정책 및 결과를 기록한다.
  - hop별 상세는 공통 invocation/stage budget을 사용하므로 무필터 audit에서도 bounded하다.
  - Docker planning receipt는 각 정책 summary가 정확히 하나 없으면 해당 planner audit를 거부한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedAll/FederatedPlannerFedAll.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedHeuristic/FederatedPlannerFedHeuristic.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBG014PlanningTraceContractTest.java`
  - harness `experiments/tools/planning_receipt.py`
  - harness `experiments/tests/test_planning_receipt.py`
- **검증**: source contract는 `missing FedAll policy trace stage FedAll-PolicySummary`로 RED였고, harness는 FedAll/Heuristic summary 누락 fixture 2개를 잘못 허용해 RED였다. 구현 후 source의 trace/FedAll/Heuristic 관련 테스트와 harness 13 tests가 통과했다. commit `a7712ed3a2...`/JAR SHA-256 `d8e5be8e...c775`의 immutable Docker stage에서 LAN LogReg workers=2 planning-only를 검증했다. FedAll은 `fedCount=73`, `foutCount=65`, relocation 0을 선택했고, Heuristic은 demotion marker/local prefix 5개, FED 73, FOUT 63, relocation 2를 선택해 최종 runtime explain에 `fed_refed:2`가 추가됐다. 두 receipt 모두 `runtime_executed=false`, `execution_seconds=0.0`이었다.
- **잔여 이슈**: 대표 workload에서 선택 score와 최종 emitted runtime plan의 관계를 대조해야 한다. 같은 plan hash 자체는 버그 증거가 아니며, 정책 objective·합법 후보·비용 선택이 그 결과를 각각 설명해야 한다.
- **잠재 회귀 위험**: 정책 summary만 남고 hop 선택 trace가 제거되면 aggregate count와 실제 선택을 대조할 수 없다. source contract와 대표 Docker log의 stage count로 감지한다.
- **의사결정 근거**: 후보 공간·오라클·비용·runtime을 변경하지 않고 planner-owned selection observability와 fail-closed receipt만 강화했다.

## 6. 플래너 내부 선택과 최종 runtime explain 사이의 차이를 occurrence 단위로 대조할 수 없던 문제

- **상태**: 진행중 — 공통 emission trace 구현/테스트 완료, 새 Docker planning-only 증거 대기
- **환경/조건**: LAN, LogReg, workers=2, 네 compiled planner, planning-only
- **재현 절차**: immutable stage의 네 planner receipt에서 policy summary와 `runtime_plan_sha256`를 비교한다. FedAll/MinST 로그는 각각 `FedAll-Select`와 `MinST-PhysicalSelect`를 집계한다.
- **관측 증상**:
  - FedAll은 내부적으로 FED 73/FOUT 65, MinST는 FED 72/FOUT 64를 선택했다.
  - 그런데 FedAll·DP·MinST의 canonical runtime explain SHA-256은 모두 `738ce85c...d731`로 byte-identical했다.
  - Hop ID로만 대조하면 MinST가 hop 287/427/786의 FED/LOUT FType을 ROW로, FedAll은 COL로 선택한 차이가 보였지만, 같은 Hop ID가 함수/재컴파일 occurrence에서 재사용되므로 정확한 차이인지 판정할 수 없었다.
- **원인 분석**: planner별 trace 형식이 달랐다. FedAll은 `CompiledHopKey`를 독립 필드로 남겼지만 MinST는 매우 긴 alternative signature 내부에 중첩해 기록했고, 공통 emission 경계는 정규화 선택·synthetic/concrete 구분·실제 Hop/registry mutation 수를 기록하지 않았다. 따라서 “합법적인 synthetic/FType 메타데이터 차이”와 “projector/emission이 선택을 유실한 버그”를 로그만으로 구분할 수 없었다.
- **해결 요약**:
  - `PlacementEmissionTransaction`이 전체 prevalidation 뒤 첫 mutation 전에 모든 planner의 exact normalized authority를 공통 `Emission-Select`로 기록한다.
  - 각 레코드에 planner, exact `CompiledHopKey`, node kind, emitted-work 여부, concrete compiled occurrence 여부, placement state와 derived-FOUT을 남긴다.
  - `Emission-Summary`에 analysis/normalized-plan fingerprint, decision partition, FED/FOUT/derived 수, relocation/local materialization 수, 실제 Hop mutation과 registry write 수를 남긴다.
  - planning-only receipt schema를 v3로 올리고 summary가 정확히 하나이며 configured planner identity·SHA-256·decision partition과 모든 정수 필드가 유효한지 fail-closed 검증한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBG014PlanningTraceContractTest.java`
  - harness `experiments/tools/planning_receipt.py`
  - harness `experiments/tests/test_planning_receipt.py`
- **검증**: 새 source guard와 harness 누락-summary 테스트는 구현 전 각각 공통 emission stage 부재/누락 summary 허용으로 RED였다. 구현 후 emission/MinST/trace 관련 source 51 tests와 harness planning receipt 14 tests가 통과했다. 아직 새 JAR Docker planning-only 로그로 occurrence별 차이를 판정하지 않았으므로 플래너 의미론 수정은 하지 않았다.
- **잔여 이슈**: 새 immutable stage에서 MinST LogReg 한 번만 다시 compile하여 기존 FedAll exact key와 대조한다. 차이가 synthetic 또는 downstream instruction에 영향 없는 FType authority이면 정상으로 문서화하고, concrete exec/output/registry authority가 emission에서 유실됐으면 회귀 테스트 후 projector/emission을 수정한다.
- **잠재 회귀 위험**: 공통 trace가 hot loop로 이동하거나 `logGlobal` per-decision으로 바뀌면 감사 자체가 커질 수 있다. 현재 per-decision은 invocation-scoped stage budget의 `logLazy`를 사용하고 summary만 `logGlobal`이다. 실제 record/byte 상한으로 감지한다.
- **의사결정 근거**: 아직 버그가 증명되지 않았으므로 candidate·cost·runtime을 바꾸지 않고, planner가 넘긴 exact authority와 emission mutation의 경계를 먼저 증명한다.

## 7. FedAll/Heuristic의 `MIN_RELOCATIONS`가 실제 물리 전송 일부를 세지 않던 문제

- **상태**: 해결, 새 Docker planning-only 증거 대기
- **환경/조건**: 공통 neutral graph, FedAll/Heuristic exact selector, candidate row에 `ABSENT_LOCAL` 또는 derived FED/FOUT이 포함되는 경우
- **재현 절차**:
  - `CampaignBG014AbsentLocalMaterializationLoweringRedTest`에서 동일 assignment 아래 candidate row만 바꿔 LOCAL lowering 수와 selector 점수를 비교한다.
  - `CampaignBG014FedAllKMeansDerivedFoutAuthorityRedTest`에서 derived FOUT action이 선택된 경우 selector relocation score를 비교한다.
- **관측 증상**:
  - explicit REFED action만 `distinctRelocationCount`에 포함되어, FED/FOUT producer를 로컬로 받는 PREFETCH와 FED/LOUT→FOUT output materialization이 0-cost tie처럼 보였다.
  - relocation effect만 같은 candidate row를 조기에 합치면서 PRESENT/ABSENT 입력 패턴과 native/derived emission 차이가 사라질 수 있었다.
- **원인 분석**: selector의 3차 목적함수 이름은 `MIN_RELOCATIONS`였지만 구현은 `RelocationActionKey` 수만 집계했다. lowering 단계가 별도로 만드는 LOCAL/FOUT registry action이 selector score와 분리돼 있었다.
- **해결 요약**:
  - `LocalMaterializationSelections`를 canonical derivation으로 분리해 normalization과 selector가 동일한 exact obligation을 사용한다.
  - candidate selection 결과에 explicit relocation, LOCAL materialization, planner-created FOUT materialization의 물리 action 수를 모두 보존한다.
  - candidate row dedup key에 exact emission, PRESENT/ABSENT 입력 패턴, relocation effect를 모두 포함한다.
  - exact-search component graph는 ABSENT_LOCAL edge와 output-materialization anchor owner까지 연결한다.
  - FedAll/Heuristic adapter가 selector score와 canonical lowering action 합계가 다르면 fail closed 한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/LocalMaterializationSelections.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/{FedAllPlacementAdapter,HeuristicPlacementAdapter,NormalizedPlannerResults}.java`
  - 관련 FedAll/local/trace regression tests
- **검증**: LOCAL undercount RED에서는 물리 전송 3개인데 selector score가 0이었고, derived-FOUT RED에서는 선택된 output upload가 score에서 누락됐다. 수정 후 관련 focused tests 및 최종 broad 묶음 79 tests가 모두 통과했다.
- **잔여 이슈**: 새 immutable Docker stage에서 FedAll/Heuristic policy summary의 `relocationCount`가 explicit+LOCAL+CP/FOUT+derived-FOUT 합과 일치하는지 representative workload별로 확인해야 한다.
- **잠재 회귀 위험**: exact component가 커져 planning 시간이 늘 수 있다. 후보를 닫지 않고 planning-only의 explored/pruned 및 planner time으로 감지한다.
- **의사결정 근거**: runtime이 지원하는 후보를 제거하지 않고, 실제 lowering이 수행할 물리 전송을 선택 목적함수에 정확히 반영했다.

## 8. 선택된 CP/FOUT이 exact FOUT materialization authority 없이 lowering되던 문제

- **상태**: 해결, Docker planning-only 및 runtime 검증 대기
- **환경/조건**: non-recompile matrix candidate, durable FederationMap anchor가 존재하고 `CP/LOUT → CP/FOUT`이 합법인 경우; B-11 shadow fixture
- **재현 절차**: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.placement.CampaignBG014CpFoutMaterializationAuthorityRedTest test`
- **관측 증상**: 실제 builder가 만든 합법 total assignment에서 CP/FOUT을 선택해 transaction을 적용해도 producer의 `FederatedFoutMaterializeRegistry` entry가 없었다. 즉 Hop에는 CP/FOUT 비트만 찍히고, 실제 CP 결과를 어느 worker/range/FType으로 올릴지 authority가 유실됐다.
- **원인 분석**:
  - builder는 derived FED/LOUT→FED/FOUT에만 exact output action을 붙였다.
  - CP/FOUT candidate를 만들 때 검증에 사용한 durable anchor/owner를 버렸다.
  - emission transaction은 selected derived action만 FOUT registry로 내렸으므로 CP/FOUT은 runtime fallback 없이는 실행 의미가 완성되지 않았다.
- **해결 요약**:
  - 기존 serialized type 이름은 호환성을 위해 유지하되, graph-owned output materialization action의 의미를 CP/LOUT→CP/FOUT과 FED/LOUT→FED/FOUT 모두로 확장했다.
  - builder가 모든 CP/FOUT candidate에 exact candidate rule, source placement, durable anchor, anchor owner/FType, statement-block scope를 결합한다.
  - candidate reachability는 full assignment에서 anchor owner가 실제 FOUT/FType으로 선택됐는지 검증한다.
  - transaction은 선택된 모든 planner-created FOUT action을 prevalidate한 뒤 동일 atomic FOUT registry write로 낮춘다.
  - MinST physical domain/factor도 CP/FOUT action과 anchor owner를 함께 모델링한다.
  - FedAll/Heuristic 물리 전송 점수와 trace에 CP/FOUT materialization을 별도 집계한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/{PlacementIdentity,PlacementAnalysis,NeutralPlacementGraphBuilder,CandidateSelections,PlacementEmissionTransaction}.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/MinStExactPhysicalModel.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/selector/ExactPlacementSelector.java`
  - FedAll/Heuristic planner 및 adapter trace/score 파일
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/CampaignBG014CpFoutMaterializationAuthorityRedTest.java`
- **검증**:
  - 수정 전 신규 test는 `selected CP/FOUT must write exact producer FOUT materialization authority`로 RED였다.
  - 수정 후 해당 test GREEN, derived-FOUT/transaction/MinST/FedAll focused tests GREEN.
  - broad regression: **79 tests, failures=0, errors=0, skipped=0** (`/tmp/g014-cpfout-broad-20260810.log`).
  - `mvn -q -DskipTests test-compile` 및 `mvn -q -DskipTests package` 성공, `git diff --check` 성공.
  - 전체 `mvn -q -DskipTests checkstyle:check`는 변경 파일과 무관한 기존/generated 소스까지 검사해 **358,852건의 baseline 위반**으로 실패했다. 따라서 이 저장소에서는 전체 checkstyle 결과를 변경분 판정 근거로 사용할 수 없고, 컴파일·관련 테스트·diff whitespace 검사를 검증 근거로 사용한다.
- **잔여 이슈**: 새 Docker trace에서 실제 정책이 CP/FOUT을 선택하는 셀이 있으면 `Emission-Candidate.foutMaterializationAction`, `Emission-RegistryWrite kind=FOUT`, policy summary의 CP/FOUT count가 일치해야 한다. 그 후에만 runtime 실험을 재개한다.
- **잠재 회귀 위험**: output action owner dependency로 exact-search component가 커질 수 있고 planning time이 증가할 수 있다. 이는 합법성 보존 비용이며 후보 skip으로 우회하지 않고 planning-time trace로 계측한다.
- **의사결정 근거**: “planner가 실행 가능성을 완전히 계획하고 runtime은 그대로 실행한다”는 원칙에 따라 runtime fallback이나 CP/FOUT 후보 폐쇄 대신 누락된 exact placement authority를 복원했다.

## 9. Heuristic 정책 투영이 합법적인 output-materialization 앵커 소유자를 제거함

- **상태**: 소스 수정/회귀 검증 완료, 최종 immutable Docker planning-only 재검증 대기
- **환경/조건**: LAN planning-only, LogReg, workers=2, `compile_fed_heuristic`, commit `18d7f05764...` stage
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --workers 2 --dataset P2P2D --conf mkl-heuristic --alg logreg ...`
  - 소스 회귀: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.fedHeuristic.CampaignBG014HeuristicLogRegFoutAnchorProjectionRedTest test`
  - 실패 로그: `/home/mchoi/g014-planning-audit-harness-20260810-v2/sigmod2021-exdra-p523/experiments/results/fed2/mkl-heuristic/logreg_dataset-P2P2D_coordinator_mkl-heuristic_plan-18d7f05-lan-logreg-w2-heuristic-20260810-r1_lan_coordinator1.log`
- **관측 증상**: runtime 실행 전 Heuristic filtered graph 생성 중 `Derived FOUT anchor owner cannot expose the required exact FOUT layout`로 종료했다. `Q`의 CP/LOUT→CP/FOUT action은 `fed-init:X`의 ROW worker/range 배치를 사용하지만, `durableAnchorOwner`가 직전 `ba(+*)` 중간 hop이었다. Heuristic local-prefix 투영이 그 중간 hop을 FED/LOUT만 남기자 action의 FOUT owner 제약이 깨졌다.
- **원인 분석**:
  - durable anchor 자체는 원본 federated DataOp의 실제 FederationMap에서 전파됐지만, output action 생성 시 owner를 immediate input node로 저장했다.
  - 배치 메타데이터의 근원과 현재 dataflow predecessor를 혼동한 것이다. 중간 hop의 선택은 바뀔 수 있으므로 durable authority가 아니다.
  - 처음 수정에서 모든 fallback owner가 직접 `Node.anchors`를 가져야 한다고 제한했으나, KMeans에는 다른 graph-owned action/relocation으로 권한을 잇는 합법 경로가 있어 과도하게 보수적이었다. focused tests가 이를 즉시 검출했고 기존 fallback 경로를 복원했다.
  - canonical owner가 선택된 뒤 기존 bind 단계가 raw statement-block ID를 action fingerprint에 넣어 동일 소스의 fresh compilation 간 plan hash가 달라지는 별도 비결정성도 노출됐다.
- **해결 요약**:
  - whole-program graph에 같은 exact durable anchor를 가진 literal federated DataOp가 있으면 이를 canonical owner로 우선 결합한다. exact anchor source가 없을 때만 기존 graph-owned non-literal owner를 보존한다.
  - Heuristic projection은 producer source/target뿐 아니라 owner의 exact FOUT/FType 상태도 남아 있는 action만 투영한다. 이것은 성능을 위한 후보 폐쇄가 아니라 graph-owned 실행 권한이 없는 target을 제거하는 전역 합법성 검사다.
  - action scope identity는 raw SBID 대신 producer의 deterministic `ControlRegionKey.normalizedSignature()`를 사용한다. 실제 registry scope는 emission 시 `HopOccurrenceProjection.scopeId()`에서 계속 얻으므로 lowering 동작은 바뀌지 않는다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/HeuristicPlacementAdapter.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedHeuristic/CampaignBG014HeuristicLogRegFoutAnchorProjectionRedTest.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionDerivedAuthorityRedTest.java`
- **검증**:
  - 신규 LogReg 회귀는 수정 전 Docker와 동일한 exception으로 RED였고 수정 후 compile-only 성공, `Total execution time: 0.000 sec.`를 기록했다 (`/tmp/g014-heuristic-logreg-anchor-green3-20260810.log`).
  - 첫 focused 묶음이 KMeans non-literal authority 과축소 3건과 fresh-compilation fingerprint 비결정성 1건을 검출했다. fallback 복원 및 deterministic scope 수정 후 해당 KMeans/FedAll, MinST oracle, Heuristic determinism 테스트가 각각 통과했다.
  - 최종 관련 묶음은 **67 tests, failures=0, errors=0, skipped=0** (`/tmp/g014-anchor-focused-final-20260810.log`).
  - `mvn -q -DskipTests test-compile`, `mvn -q -DskipTests package`, `git diff --check` 통과.
- **잔여 이슈**: 새 commit/JAR의 immutable stage에서 실패했던 Heuristic LogReg-w2부터 planning-only를 재실행하고, 대표 KMeans/ALS/StepLM 및 MinST의 action owner/selected plan/registry trace를 확인해야 한다. 실제 runtime campaign은 이 검증 전 시작하지 않는다.
- **잠재 회귀 위험**: 동일 worker pool에 여러 literal anchors가 존재할 수 있다. resolver는 exact durable-anchor equality를 physical-pool equivalence보다 먼저 사용하며, 회귀 테스트는 모든 LogReg action owner가 exact anchor를 직접 소유하는 federated DataOp인지 확인한다. 향후 non-literal authority chain이 Heuristic projection에서 끊기면 filtered graph의 fail-closed validation 또는 source-reachability 검사로 검출된다.
- **의사결정 근거**: runtime fallback이나 opcode 가드를 추가하지 않고, 실제 FederationMap metadata의 canonical 소유권과 deterministic planner authority를 바로잡았다.

## 10. DP 부분 recurrence가 아직 선택되지 않은 전역 exact FOUT owner를 즉시 요구함

- **상태**: 해결
- **환경/조건**: LAN planning-only, DP, P2P2D; KMeans workers=2, ALS workers=3, StepLM workers=1; commit `1608be47ba...` stage
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --dataset P2P2D --conf mkl-cost --alg <kmeans|als|steplm> --workers <2|3|1> ...`
  - 최소 회귀: `mvn -q -DskipTests=false -Dtest=PlacementEmissionDerivedAuthorityRedTest test`
  - KMeans 실패 로그: `/home/mchoi/g014-planning-audit-stage-715e910-1608be4-20260810-v1/g007-stage-a03aabf18eb5eef88ab8a53dd99cab367d213901f21dde1d94c8d3144febae89/results/fed2/mkl-cost/kmeans_dataset-P2P2D_coordinator_mkl-cost_plan-1608be4-lan-kmeans-w2-dp-20260810-r1_lan_coordinator1.log`
- **관측 증상**: 세 workload 모두 runtime 진입 전 DP의 exact relocation 선택에서 `Active exact candidate has no source-reachable row`로 실패했다. 선택된 derived `FED/FOUT` row는 합법적인 exact ROW anchor를 가졌지만, 그 canonical owner가 현재 parent/child closure 밖에 있었다.
- **원인 분석**:
  - DP recurrence는 현재 parent와 선택된 child closure만 부분 assignment에 포함한다.
  - `resolveAndValidatePartial`이 완성 플랜용 `feasibleVariants`를 그대로 호출해, authority graph에는 존재하지만 현재 부분 assignment에는 아직 없는 canonical FOUT owner까지 즉시 `FED/FOUT`으로 선택돼 있어야 한다고 요구했다.
  - 이는 런타임 제약이나 후보 불법성이 아니라 **부분 상태와 완성 상태 검증 시점의 혼동**이었다. whole-program graph validation은 owner와 exact anchor/FType의 존재·합법성을 이미 검증하고, 최종 normalization은 owner의 실제 선택 상태를 다시 엄격하게 검증한다.
- **해결 요약**:
  - public/full `feasibleVariants`와 `resolveAndValidate`는 기존처럼 owner가 exact FOUT/FType으로 선택됐음을 계속 요구한다.
  - DP의 `resolveAndValidatePartial`에서만 owner가 assignment에 진짜로 미할당인 경우 검사를 유예한다. owner가 할당됐지만 잘못된 state/FType이면 유예하지 않는다.
  - 새 회귀는 같은 derived-FOUT row가 부분 assignment에서는 유지되지만, owner가 빠진 완성 assignment에서는 반드시 실패함을 동시에 고정한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/CandidateSelections.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionDerivedAuthorityRedTest.java`
- **검증**:
  - 신규 회귀는 수정 전 `Active exact candidate has no source-reachable row`로 RED였다 (`/tmp/g014-dp-partial-owner-red-20260810.log`).
  - 수정 후 exact authority/selector/DP/MinST/Heuristic/FedAll 관련 **33 tests, failures=0, errors=0, skipped=0** (`/tmp/g014-dp-partial-owner-focused-20260810.log`).
  - commit `2c4bf98e23...`, JAR SHA-256 `1889225d...ab3`의 immutable Docker stage에서 기존 실패 셀을 runtime 없이 재검증했다. KMeans-w2, ALS-w3, StepLM-w1 모두 planning receipt가 성공했고 `runtime_executed=false`, `execution_seconds=0.0`이었다. emitted/runtime plan SHA-256은 각각 `160d3602...`/`2cc214a9...`, `7adeb92d...`/`16b81d5f...`, `a4b0ac71...`/`84d22ad6...`이었다.
- **잔여 이슈**: 없음. 최종 성능 stage에서도 complete normalization과 receipt가 fail closed로 유지되는지 확인한다.
- **잠재 회귀 위험**: 부분 recurrence가 owner와 불일치하는 row를 일시 보존할 수 있어 탐색량이 늘 수 있다. 그러나 owner가 나중에 다른 state/FType으로 선택되면 최종 full validation이 fail closed해야 하며, 신규 회귀가 이 경계를 감지한다.
- **의사결정 근거**: 후보를 닫거나 runtime fallback을 추가하지 않고, DP의 국소 recurrence와 whole-program exact authority 사이의 올바른 partial-validation 의미를 복원했다.

## 11. single-worker `FType.FULL` vector가 Heuristic demotion marker에서 누락됨

- **상태**: 소스 수정/회귀 검증 완료, 새 immutable Docker planning-only 재검증 대기
- **환경/조건**: LAN planning-only, Heuristic, P2P2D StepLM, workers=1; commit `1608be47ba...` stage
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --dataset P2P2D --conf mkl-heuristic --alg steplm --workers 1 ...`
  - 최소 회귀: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.hops.fedplanner.placement.CampaignBHeuristicRealVectorPolicyRedTest test`
- **관측 증상**:
  - 실제 선택 로그에 aggregate-binary hop의 `FED/LOUT/FULL/SHAPE_DEPENDENT` 상태가 존재했지만 `Heuristic-PolicySummary markerCount=0, localPrefixCount=0`이었다.
  - 같은 셀의 FedAll과 Heuristic은 exact placement/candidate/runtime plan fingerprint가 모두 같았다. 즉 단순 실행시간 잡음이 아니라 Heuristic 정책이 적용되지 않은 동일 계획이었다.
- **원인 분석**: `NeutralPlacementGraphBuilder.isAggregateBinaryVectorInput(...)`이 vector 입력을 `FType.ROW`와 `FType.COL` 방향 규칙으로만 인식했다. single-worker federation의 전체 matrix를 소유하는 `FType.FULL`도 vector shape이면 같은 forced-LOUT 정책 대상이지만 누락됐다.
- **해결 요약**:
  - ROW/COL의 방향별 판정은 그대로 유지한다.
  - `FType.FULL`은 one-worker whole-matrix placement이므로 orientation과 무관하게 `isVector(shape)`인 경우에만 aggregate-binary vector 입력으로 인식한다.
  - scalar나 일반 matrix까지 marker로 확대하지 않도록 vector shape 조건을 유지한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/guard/CampaignBHeuristicRealVectorPolicyRedTest.java`
- **검증**:
  - 신규 `FULL_4x1` 회귀는 수정 전 `expected:<1> but was:<0>`으로 RED였다 (`/tmp/g014-heuristic-full-marker-red-20260810.log`).
  - 수정 후 focused test가 통과했다 (`/tmp/g014-heuristic-full-marker-green-20260810.log`).
  - Heuristic policy/provenance/re-entry, common emission/authority, DP/MinST/FedAll 대표 회귀를 포함한 **59 tests, failures=0, errors=0, skipped=0** (`/tmp/g014-heuristic-full-broad-20260810.log`).
- **잔여 이슈**: 새 commit/JAR의 immutable Docker stage에서 StepLM workers=1의 FedAll과 Heuristic을 planning-only로 실행해 Heuristic marker가 1개 이상이고 exact emitted/runtime plan이 실제로 달라지는지 확인한다. 그 전에는 성능 runtime을 시작하지 않는다.
- **잠재 회귀 위험**: FULL을 shape 확인 없이 모두 vector로 취급하면 matrix aggregate-binary까지 과도하게 demote할 수 있다. `isVector(shape)` 회귀와 실제 StepLM planning receipt로 감지한다.
- **의사결정 근거**: candidate-space를 임의로 닫거나 runtime을 보정하지 않고, 기존 Heuristic 정책이 single-worker의 실제 `FType.FULL` 표현에도 동일하게 적용되도록 정확한 FType 지원을 복원했다.

## 12. LM FedAll에서 선택된 REFED source hop이 MapMultChain fusion으로 사라짐

- **상태**: 해결
- **환경/조건**: LAN planning-only, FedAll, P2P2D LM, workers=1; commit `fdf63b1458...` immutable stage
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --workers 1 --dataset P2P2D --conf mkl-fedall --alg lm ...`
  - 실패 증거: `/home/mchoi/g014-planning-audit-stage-715e910-fdf63b1-20260810-v1/g007-stage-7fda686c9befa1d4d9a0afe18fdab664c330eefb5ec7f6307982af0477a50b87/results/failures/`
  - 최소 회귀: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.FederatedDagExactRefedInputProjectionTest test`
- **관측 증상**: runtime 실행 전 Lop lowering에서 `fed_refed lowering requires a local lop for hop=245`로 실패했다. planner는 LM의 중간 matrix multiply hop을 REFED source로 정확히 선택하고 registry에 기록했지만, 이후 `XtXv`/`XtwXv` MapMultChain fusion이 그 중간 hop의 Lop 자체를 제거했다.
- **원인 분석**: `AggBinaryOp.checkMapMultChain()`은 planner가 부여한 explicit relocation/materialization boundary를 확인하지 않고 식 패턴만으로 fusion을 허용했다. 따라서 planner-selected authority와 physical lowering 최적화 사이의 계약이 깨졌다. 이는 잘못된 후보 선택이나 런타임 미지원이 아니라, 선택된 concrete hop을 후속 rewrite가 지운 문제다.
- **해결 요약**:
  - transpose, inner aggregate-binary, binary weight/residual hop 중 fusion으로 소실될 hop에 planner materialization boundary가 있으면 MapMultChain fusion을 적용하지 않는다.
  - boundary가 없는 기존 expression은 계속 `XtXv`로 fusion되므로 일반 최적화는 유지한다.
  - 후보를 닫거나 runtime fallback을 추가하지 않고, planner가 선택한 explicit physical boundary를 lowering까지 보존한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/AggBinaryOp.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedDagExactRefedInputProjectionTest.java`
- **검증**:
  - 신규 회귀는 수정 전 selected intermediate source가 여전히 `XtXv`로 fusion되어 RED였고, 수정 후 `ChainType.NONE`을 반환해 GREEN이었다.
  - exact Dag/refed, emission/authority, FedAll/Heuristic/DP/MinST 대표 회귀를 포함한 최종 묶음 **48 tests, failures=0, errors=0, skipped=0** (`/tmp/g014-planning-fixes-focused-final-20260810.log`).
  - `git diff --check` 통과.
  - commit `3d1e477c55...`, JAR SHA-256 `a5fb4e4e...` immutable Docker stage에서 LM-w1 FedAll planning-only가 성공했다. receipt는 `runtime_executed=false`, `execution_seconds=0.0`, runtime-plan SHA-256 `fd5b4a62...`, FED/FOUT `25/25`, relocation/local-materialization `3/3`을 기록했다.
- **잔여 이슈**: 없음. 다른 workload에서 동일한 planner-selected fusion boundary가 나타나면 같은 registry/lowering invariant로 검증한다.
- **잠재 회귀 위험**: planner registry가 남은 채 compile이 재사용되면 불필요하게 fusion을 막을 수 있다. registry lifecycle/transaction tests와 boundary가 없는 expression의 `XtXv` 유지 assertion으로 감지한다.
- **의사결정 근거**: runtime fallback이나 후보 축소가 아니라, planner가 선택한 concrete relocation boundary를 후속 Hop→Lop rewrite가 지우지 못하도록 planner/runtime 경계 계약을 복원했다.

## 13. LogReg DP에서 optional formal overwrite의 함수 입력 pass-through가 CFG에서 소실됨

- **상태**: 해결
- **환경/조건**: DP planning, LogReg 함수 내부 `if(hasNaNs) X=replace(X, ...)`처럼 else 없는 optional formal overwrite 이후 `rowSums(X^2)`를 사용하는 경로
- **재현 절차**:
  - 최소 CFG 회귀: `mvn -q -DskipTests=false -Dtest=org.apache.sysds.test.component.federated.placement.core.NeutralPlacementGraphExactCfgIdentityTest test`
  - production-shape 회귀: `mvn -q -DskipTests=false -Dtest='org.apache.sysds.test.component.federated.FederatedPlannerFallbackIntegrationTest#testDpLogRegKeepsInitialFederatedFormalForRowSumSquares' test`
- **관측 증상**: optional replacement 뒤의 `X` TRead가 branch join이 아니라 branch TWrite 하나만 reaching definition으로 인식됐다. caller의 federated formal `X`가 no-write 경로에서 사라져 `rowSums(X^2)`가 값싼 `CP/LOUT`으로 선택됐고, 함수 입력의 실제 FederationMap authority와 비용이 반영되지 않았다.
- **원인 분석**:
  - CFG reaching-definition 분석은 function body 입구를 빈 state로 시작했다.
  - else 없는 if에서 write branch는 `X` TWrite를 내보내고 no-write branch는 빈 state를 내보냈으므로, merge 결과에는 TWrite만 남았다.
  - 이 때문에 function input과 branch definition이 함께 도달하는 정확한 phi, logical function-input fact, worker-pool 교집합 검증이 모두 생성되지 않았다.
- **해결 요약**:
  - 각 named function의 formal input을 explicit CFG entry definition으로 seed하고, occurrence ordinal과 충돌하지 않는 function-input sentinel을 별도 boolean authority로 분리한다.
  - function-input + TWrite가 함께 도달하면 exact `BRANCH_JOIN_PHI`/`LOOP_HEAD_PHI`와 `cfg-function-input:<namespace>:<variable>` predecessor를 생성한다.
  - logical function-input boundary와 CFG TWrite constraint를 둘 다 merged read에 결합한다.
  - durable worker-pool authority는 function source와 모든 CFG definition이 같은 physical worker pool을 증명할 때만 교집합으로 보존한다. 어느 한쪽이 없거나 불일치하면 anchor를 만들지 않는다.
  - DP adapter는 node-kind 추측이 아니라 이미 검증된 exact `LogicalFunctionInputFact`를 authority로 사용한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementAnalysis.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/adapter/DpPlacementAdapter.java`
  - `src/test/java/org/apache/sysds/test/component/federated/placement/core/NeutralPlacementGraphExactCfgIdentityTest.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - exact CFG suite **11 tests, failures=0, errors=0** (`/tmp/g014-cfg-function-pass-through-green-final-20260810.log`). 신규 test는 formal pass-through predecessor 1개, TWrite predecessor 1개, `BRANCH_JOIN_PHI`, SAME_PLACEMENT와 CONJUNCTIVE constraint를 모두 확인한다.
  - production LogReg DP 회귀 **1 test, failures=0, errors=0** (`/tmp/g014-logreg-function-pass-through-green-final-20260810.log`). post-branch X TRead/TWrite와 `rowSums(X^2)`가 모두 exact `FED/FOUT`을 선택하고 두 CFG 경로 placement가 동일함을 확인한다.
  - 관련 FedAll/Heuristic/DP/MinST 및 emission/authority 회귀 **48 tests, failures=0, errors=0** (`/tmp/g014-planning-fixes-focused-final-20260810.log`).
  - `git diff --check` 통과.
  - commit `3d1e477c55...`, JAR SHA-256 `a5fb4e4e...` immutable Docker stage에서 LogReg-w1 DP planning-only가 성공했다. receipt는 `runtime_executed=false`, `execution_seconds=0.0`, runtime-plan SHA-256 `94838af8...`, FED/FOUT `51/45`, local-materialization `5`를 기록했다.
- **잔여 이슈**: 같은 workload의 FedAll/Heuristic/MinST plan 차이 감사는 전체 planning-only matrix에서 계속한다.
- **잠재 회귀 위험**: 다중 call-site, loop backedge, 서로 다른 worker pool에서 들어오는 optional overwrite에서 잘못된 anchor를 합성할 수 있다. exact predecessor/constraint tests와 worker-pool 교집합의 fail-closed 동작, Docker planning receipt로 감지한다.
- **의사결정 근거**: DP의 국소 최적화 철학은 변경하지 않았다. 누락된 함수-entry CFG source와 물리 anchor authority만 복원했으며, runtime 지원 후보를 닫거나 TR/TW 규칙을 완화하지 않았다.

## 14. single-worker L2SVM의 function-input 재폐쇄가 CP/FOUT materialization을 비단조 축소로 오판함

- **상태**: 해결
- **환경/조건**: LAN, workers=1, P2P2D L2SVM, `compile_cost_based`, planning-only, single-range `FType.FULL`
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --workers 1 --dataset P2P2D --conf mkl-cost --alg l2svm ...`
  - 소스 회귀: `mvn -q -DskipTests=false -Dtest='org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpL2SvmRefedSourceLoweringRedTest#l2SvmSingleWorkerReclosesDerivedFoutCandidates' test`
  - 실패 로그: immutable stage의 `results/fed1/mkl-cost/l2svm_dataset-P2P2D_coordinator_mkl-cost_plan-55ff7a3-lan-l2svm-w1-dp-20260810-r1_lan_coordinator1.log`
- **관측 증상**:
  - runtime 진입 전 `IllegalStateException: Post-CFG physical candidate closure is not monotone`로 planning receipt가 거부됐다.
  - physical closure에서 hop 421이 `CP/LOUT`에서 `CP/LOUT + FED/FOUT/FULL`로 넓어진 뒤, 하위 hop 174를 재계산할 때 이전 worker-pool closure가 추가한 `CP/FOUT/FULL`만 임시로 빠졌다.
  - coordinator log에는 execution-time footer가 없고 worker JVM/runtime workload는 실행되지 않았다.
- **원인 분석**:
  - function-input closure는 `buildNode`로 oracle-owned base candidate row를 먼저 재구축한 다음 worker-pool materialization closure를 다시 적용한다.
  - 이전 pass의 `CP/FOUT`/derived `FED/FOUT` action은 base rebuild 동안 일시적으로 제거되는 것이 정상인데, 비단조성 검증은 같은 candidate key의 action-bearing emission delta를 인식하지 못했다.
  - 따라서 native capability/input domain이 그대로인 합법 재계산까지 candidate-space 축소로 오판했다.
- **해결 요약**:
  - refined matrix predecessor가 실제로 있는 경우에만 같은 candidate row의 provisional materialization delta를 재계산 대상으로 인정한다.
  - status/capability/shape proof/profile/failure code와 action이 없는 native emission은 모두 완전히 동일해야 한다. 차이가 허용되는 것은 exact `DerivedFoutMaterializationActionKey`가 붙은 CP/FOUT 또는 derived FED/FOUT emission뿐이다.
  - 제거된 legal state도 그 action-bearing emission이 정확히 publish한 state인 경우에만 허용한다. 이후 기존 worker-pool closure와 final authority binding이 action/anchor owner를 다시 fail-closed 검증한다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/NeutralPlacementGraphBuilder.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/CampaignBG014DpL2SvmRefedSourceLoweringRedTest.java`
- **검증**:
  - 신규 single-worker compile-only 회귀는 수정 전 동일 exception으로 RED였다 (`/tmp/g014-l2svm-single-worker-closure-red-20260810.log`).
  - 수정 후 신규 회귀가 GREEN이었다 (`/tmp/g014-l2svm-single-worker-closure-green1-20260810.log`).
  - CFG strict-refinement, CP/FOUT/derived-FOUT authority, MinST FType membership 관련 focused 묶음은 **31 tests, failures=0, errors=0, skipped=9**였다 (`/tmp/g014-l2svm-closure-focused-green2-20260810.log`; skipped는 고정된 PUBLIC 케이스).
  - commit `3d1e477c55...`, JAR SHA-256 `a5fb4e4e...` immutable Docker stage의 L2SVM-w1 DP planning-only가 성공했다. receipt는 `runtime_executed=false`, `execution_seconds=0.0`, `forbidden_output_absent=true`, runtime-plan SHA-256 `252ed93d...`, FED/FOUT `13/11`, local-materialization `1`을 기록했다.
  - 같은 stage에서 DP worker=1의 7 workload 전체가 planning-only로 성공했으며 모두 runtime 미실행이었다. LM/PCA/KMeans/LogReg의 runtime-plan SHA는 수정 전 성공 stage와 동일해 이 closure 수정이 무관한 기존 DP plan 철학을 바꾸지 않았음을 확인했다.
- **잔여 이슈**: 없음. workers=2 L2SVM의 별도 component coherence 문제는 Issue 15에서 추적한다.
- **잠재 회귀 위험**: native oracle emission까지 materialization delta로 오인하면 실제 불법 축소를 숨길 수 있다. helper는 action 없는 emission과 모든 rule evidence의 exact equality를 요구하며 기존 stale `PRESENT OTHER` 제거 회귀로 감지한다.
- **의사결정 근거**: 후보를 닫거나 runtime fallback을 추가하지 않고, 이미 검증된 worker-pool materialization 후보의 올바른 고정점 재계산 순서만 복원했다.

## 15. 기존 two-worker L2SVM DP의 exact root-plan component coherence 실패

- **상태**: 진행중 — 현재 single-worker 우선 planning audit와 분리해 보존
- **환경/조건**: workers=2, L2SVM, `compile_cost_based`, compile-only; source baseline commit `55ff7a37...`와 현재 수정본 모두
- **재현 절차**: `mvn -q -DskipTests=false -Dtest='org.apache.sysds.hops.fedplanner.fedCostBased.fedDp.CampaignBG014DpL2SvmRefedSourceLoweringRedTest#l2SvmTwoWorkersLowersEverySelectedRefedSource' test`
- **관측 증상**: `DP component has no locally ranked coherent exact root-plan forest`로 실패한다. branch TWrite `Y` component는 CP/LOUT root만 갖지만 외부의 exact TRead `Y`는 CFG CONJUNCTIVE constraint로 FED/FOUT/ROW에 고정돼 있다.
- **원인 분석**: 아직 확정하지 않았다. baseline commit을 별도 detached worktree에서 실행해 동일 실패를 재현했으므로 Issue 14 수정으로 유발된 회귀는 아니다. DP component 분해가 foreign fixed logical source constraint를 root domain에 전달하지 못하거나, exact TWrite FED/FOUT carrier를 component domain에서 누락한 가능성이 있다.
- **해결 요약**: 없음. single-worker 7-workload 한 바퀴의 빠른 feedback loop를 우선 유지하고, workers=2 audit 진입 전에 exact component/foreign-fixed receipt를 별도 회귀로 고정해 수정한다.
- **수정 파일**: 없음
- **검증**: baseline detached worktree 로그 `/tmp/g014-baseline-l2svm-two-worker-20260810.log`와 현재 로그 `/tmp/g014-l2svm-two-worker-after-closure-20260810.log`가 같은 failure class를 보인다.
- **잔여 이슈**: DP component domain, TWrite/TRead logical authority, foreign fixed selection을 occurrence 단위로 대조해야 한다.
- **잠재 회귀 위험**: 이를 단순 constraint 완화 또는 TWrite CP/FOUT 허용으로 우회하면 최상위 TR/TW 규칙을 위반한다. exact FED/FOUT TWrite carrier 복원 또는 component constraint propagation으로만 해결해야 한다.
- **의사결정 근거**: baseline 문제를 새 수정의 회귀로 오인하지 않되 숨기지도 않고, worker=1 우선순위와 빠른 planning feedback loop를 유지하면서 후속 DP 수정 대상으로 명시했다.

## 16. FedAll PCA의 multi-return `FUNCTIONOUTPUT` descriptor가 물리 consumer로 잘못 등록됨

- **상태**: 소스 수정/회귀 검증 완료, 새 immutable Docker planning-only 재검증 대기
- **환경/조건**: LAN, workers=1, P2P2D PCA, FedAll(`mkl-fout`), planning-only; commit `3d1e477c55...` immutable stage
- **재현 절차**:
  - Docker: `run_LAN_docker.sh --planning-only --skip-net-check --net-profile lan --workers 1 --dataset P2P2D --conf mkl-fout --alg pca ...`
  - 최소 회귀: `mvn -q -DskipTests=false -Dtest='org.apache.sysds.hops.fedplanner.placement.PlacementEmissionTransactionRedTest#multiReturnFunctionOutputMaterializationTargetsTheExactPhysicalCallInput' test`
  - 실패 로그: `/home/mchoi/g014-planning-audit-stage-715e910-3d1e477-20260810-v1/g007-stage-1e7c69e5d02f251eb4cd260cbb1b389f59bde58e3d89e120bcf451533c653dda/results/fed1/mkl-fout/pca_dataset-P2P2D_coordinator_mkl-fout_plan-3d1e477-lan-pca-w1-fedall-20260810-r1_lan_coordinator1.log`
- **관측 증상**:
  - FedAll은 hop 198을 `FED/FOUT/FULL`, multi-return `EIGEN` call과 두 output descriptor를 `CP/LOUT`으로 선택하고 local materialization을 명시적으로 계획했다.
  - emission registry는 같은 source의 consumer를 `[hop39 output-0/input0, hop40 output-1/input0, hop41 FunctionOp/input0]`으로 기록했다.
  - 실제 Lop DAG에는 source를 소비하는 `FunctionCallCP hop41/input0` 하나만 있어, runtime 진입 전 lowering이 `cannot project exact input authority through a fused or mismatched consumer hop=39`로 fail-closed 했다.
- **원인 분석**:
  - multi-return builtin의 concrete `DataOp(FUNCTIONOUTPUT)`은 결과 shape/name을 소유하는 논리 output descriptor이다. descriptor의 input은 결과 계산을 별도로 실행하는 물리 edge가 아니며, 실제 입력 Lop은 descriptor를 소유한 `FunctionOp`의 `FunctionCallCP`가 한 번 소비한다.
  - neutral graph의 논리 compiled-input obligation을 registry의 물리 consumer identity로 내릴 때 이 차이를 투영하지 않고 descriptor hop ID를 그대로 사용했다.
  - 따라서 선택 plan 자체보다 **planner emission의 logical→physical authority projection**이 잘못됐다. lowerer가 임의 parent를 찾거나 runtime fallback으로 보정할 문제가 아니다.
- **해결 요약**:
  - REFED/FOUT/LOCAL registry 세 경로가 공통으로 사용하는 exact physical-consumer projection을 추가했다.
  - 일반 compiled input은 기존 `(consumerHopId,inputPosition)`을 그대로 보존한다.
  - multi-return `FUNCTIONOUTPUT`만, 동일 source의 identity parent 중 해당 descriptor를 `getOutputs()`로 소유하는 `MULTIRETURN_BUILTIN FunctionOp`를 찾고, 같은 scope의 유일한 compiled FunctionOp occurrence와 exact compiled source edge를 모두 증명한 뒤 실제 call input으로 투영한다.
  - 둘 이상이거나 0개이면 후보를 닫거나 추측하지 않고 emission 단계에서 fail-closed 한다. 두 output descriptor와 direct call obligation은 동일 물리 입력으로 canonical dedup된다.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransaction.java`
  - `src/test/java/org/apache/sysds/hops/fedplanner/placement/PlacementEmissionTransactionRedTest.java`
- **검증**:
  - 신규 synthetic single-worker `eigen(X)` 회귀는 수정 전 local registry가 output hop 74/75와 call hop 76을 모두 기록해 RED였다 (`/tmp/g014-pca-fedall-physical-consumer-red-20260810.log`).
  - 수정 후 registry가 오직 `FunctionOp hop76,input0`만 기록했고, Hop→Lop/Dag instruction 생성까지 runtime 실행 없이 GREEN이었다 (`/tmp/g014-pca-fedall-physical-consumer-green-20260810.log`).
  - transaction, exact REFED projection, LOCAL lowering, multi-return owner 회귀 묶음은 **29 tests, failures=0, errors=0, skipped=0**였다 (`/tmp/g014-pca-fedall-physical-consumer-focused-20260810.log`).
  - `mvn -q -DskipTests package`, `git diff --check` 통과. 수정 JAR SHA-256은 `4801e947...`이다.
- **잔여 이슈**: 수정 commit/JAR로 immutable stage를 만든 뒤 실패했던 PCA-w1 FedAll을 먼저 planning-only 재실행하고, registry trace가 `localInputs=[hop41/input0]`만 포함하며 receipt의 `runtime_executed=false`, `execution_seconds=0.0`인지 확인한다. 그 다음 아직 실행하지 않은 FedAll workload부터 진행한다.
- **잠재 회귀 위험**: 동일 source/output descriptor가 여러 FunctionOp에 부정확하게 공유되거나 compiled occurrence가 중복되면 잘못 dedup할 수 있다. projection은 FunctionOp/output identity, 동일 scope, exact compiled source edge, 유일성 네 조건을 모두 요구하며 모호한 경우 fail-closed한다. 일반 consumer는 기존 identity를 그대로 보존한다.
- **의사결정 근거**: runtime fallback이나 유효 candidate 폐쇄 없이, planner가 선택한 논리 obligation을 compiler-owned 실제 `FunctionCallCP` 입력 authority로 정확히 내리는 emission 계약을 복원했다.
