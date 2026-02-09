# SESSION ISSUES (2026-02-09)

## 이슈 1: MinST가 OPTIONAL 입력에 업로드 힌트를 강제로 남겨 `CP->FOUT/fed_fout`가 과삽입됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`
  - 워크로드/프로파일: `lm`, `lan` / `wan_light`
  - 로그: `experiments/results/fed2/mkl-min-st-cut/lm_*_lan.log`, `experiments/results/fed2/mkl-min-st-cut/lm_*_wan_light.log`
- **재현 절차**:
  1. MinST 실행 로그에서 Oracle 입력 타입과 `CP->FOUT insert` 횟수를 비교한다.
  2. 같은 입력에서 `mkl-cost` 대비 `mkl-min-st-cut`의 `fed_fout` 삽입 및 Fed Put 수가 급증하는지 확인한다.
- **관측 증상**:
  - MinST에서 OPTIONAL 입력 경로에서도 업로드가 비용 모델에 반영되어, 반복 루프에서 `CP->FOUT/fed_fout`가 과삽입됨.
  - 결과적으로 Fed Put count/bytes와 `fed_fed_fout` heavy hitter가 증가.
- **원인 분석**:
  - `FederatedPlanMinSTRewire`가 OPTIONAL 입력(`InputRequirement.OPTIONAL`)에 대해서도 `markParentChildUploadHint`를 등록.
  - `FederatedPlanMinSTGraph.addParentChildNetEdge`가 이 hint를 근거로 upload hyperedge를 추가하여 불필요한 업로드 경로를 MinST에 강제.
- **해결 요약**:
  - `FederatedPlanMinSTRewire`에서 OPTIONAL 입력 경로의 `markParentChildUploadHint` 등록을 제거.
  - MinST가 OPTIONAL 입력을 기본적으로 local로 두고, 부모 실행 결정 시점에만 업로드를 선택하도록 정책 정렬.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
  - 결과:
    - `FederatedPlannerFallbackIntegrationTest.testMinSTOptionalInputDoesNotMarkUploadHint` 통과
    - `FederatedRefedPolicyTest` 포함 전체 테스트 통과
- **잔여 이슈**:
  - 실험 로그 기준 성능 회복 확인(특히 `lm`의 `lan`/`wan_light`)은 별도 실험 재실행 필요.
- **잠재 회귀 위험**:
  - 특정 연산에서 OPTIONAL 입력 업로드가 실제로 필요한 경우 비용이 과소평가될 수 있음.
  - 감지 방법: MinST hyperedge/rewire 테스트에서 OPTIONAL 입력 경로 검증 추가.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 플래너( MinST rewire )의 과도한 업로드 가정이 원인이므로 플래너 정책을 수정.

## 이슈 2: MinST가 refed 불가능 벡터 홉에도 `BROADCAST`를 강제해 WAN에서 FED 연산이 CP로 기울어짐

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`
  - 워크로드/프로파일: `pca`, `wan_light`
  - 로그: `experiments/results/fed2/mkl-min-st-cut/pca_*_wan_light.log`
- **재현 절차**:
  1. Oracle 로그에서 `hop=227 (ua(meanC))`가 `exec=FED`임을 확인한다.
  2. 같은 실행의 최종 MinST 계획/LOP에서 `hop=227`이 `CP?uacmean`으로 배치되는지 확인한다.
- **관측 증상**:
  - MinST에서 `hop=227`이 CP로 선택되어 `uacmean`의 CP heavy hitter 시간이 급증.
  - DP(`mkl-cost`) 대비 WAN_LIGHT 성능 악화.
- **원인 분석**:
  - `FederatedPlanMinSTRewire`의 벡터 처리에서 `canGenerateCpfoutCandidate==false`인 홉까지 `fType=BROADCAST`로 강제.
  - 해당 값이 MinST의 비용/배치 판단에 들어가 WAN 프로파일에서 CP 선택이 과도하게 유리해짐.
- **해결 요약**:
  - 벡터 홉에서 `canGenerateCpfoutCandidate(...)`가 거짓이면 FType 강제 오버라이드(BROADCAST/axis)를 하지 않도록 변경.
  - 즉, refed 불가능한 경우 Oracle/기존 FType을 유지해 불필요한 BROADCAST 바이어스를 제거.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTRewire.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlannerFallbackIntegrationTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
  - 결과:
    - `FederatedPlannerFallbackIntegrationTest.testMinSTVectorWithoutRefedDoesNotForceBroadcast` 통과
    - 전체 테스트 통과
- **잔여 이슈**:
  - `pca` WAN_LIGHT 실험의 실제 성능 회복 여부는 end-to-end 재실행으로 추가 확인 필요.
- **잠재 회귀 위험**:
  - 일부 벡터 연산에서 BROADCAST 비용 반영이 약해져 FED 선호가 과도해질 수 있음.
  - 감지 방법: MinST 벡터/브로드캐스트 관련 테스트 및 LM/PCA 회귀 실험 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback 없이, 플래너 입력 타입 결정(Oracle 정렬/브로드캐스트 힌트)만 수정.

## 이슈 3: 비용 fallback의 injected default가 고정 8B/cell이라 ValueType별 메모리 특성을 반영하지 못함

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_dp`, `fed_min_st_cut` (공통 `FederatedCostModel` 경유)
  - 대상 로직: `getEffectiveInputMemEstimate`, `getEffectiveOutputMemEstimate`
- **재현 절차**:
  1. 비용 모델 fallback이 필요한 홉(메모리 추정치가 0/미정인 홉)을 구성한다.
  2. `FederatedCostModel`의 fallback이 ValueType과 무관하게 항상 동일 injected default(`8B`)를 사용하는지 확인한다.
- **관측 증상**:
  - BOOLEAN/FP32/STRING 등 ValueType 차이가 있어도 fallback이 동일 셀 크기를 사용해 비용 모델 편향 가능성이 존재.
- **원인 분석**:
  - `FederatedCostModel`이 고정 상수 `DEFAULT_MEM_ESTIMATE_PER_CELL = 8.0`만 사용하고, `Hop.getValueType()` 기반 분기를 하지 않음.
- **해결 요약**:
  - `FederatedCostModel`에 `Hop.getValueType()` 기반 injected default 분기를 추가.
    - BOOLEAN: `1B`, INT32/HASH32/UINT 계열: `4B`, INT64/HASH64: `8B`, FP32: `4B`, FP64: `8B`, STRING: `100B` 기준.
  - `getEffectiveOutputMemEstimate`가 고정 8B 대신 ValueType별 default를 사용하도록 변경.
  - `getEffectiveInputMemEstimate`는 부모 홉 공통 default 대신 각 입력 홉의 ValueType 기반 output fallback을 합산하도록 변경(중복 입력 처리 유지).
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/commons/FederatedCostModel.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedCostModelFallbackTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedCostModelFallbackTest,FederatedPlanMinSTHyperedgeTest,FederatedPlannerFallbackIntegrationTest,FederatedRefedPolicyTest test`
    - `mvn -q -DskipTests package`
  - 결과:
    - `FederatedCostModelFallbackTest`의 타입별 injected default 검증 테스트 통과
    - MinST/DP 관련 회귀 테스트 통과
    - 패키지 빌드 성공
- **잔여 이슈**:
  - UNKNOWN 타입은 기존 호환성을 위해 8B fallback을 유지했으므로, 필요 시 별도 경험값/프로파일 기반 튜닝 여지 존재.
- **잠재 회귀 위험**:
  - ValueType 분기 도입 시 기존 8B 가정 대비 네트워크/연산비 가중치가 바뀌어 일부 워크로드의 planner 선택이 변할 수 있음.
  - 감지 방법: DP/MinST 회귀 테스트 + LM/PCA LAN/WAN 로그 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 fallback이 아니라 플래너 비용 모델(공통 cost helper) 수정.

## 이슈 4: MinST에서 planner가 BROADCAST를 계획해도 register 단계에서 `fed_refed`로 바뀌어 DP와 실행 패턴이 어긋남

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - 비교 대상: `mkl-cost(DP)` vs `mkl-min-st-cut`
  - 워크로드/프로파일: `pca`, `wan_mid`
  - 로그:
    - `experiments/results/fed2/mkl-cost/pca_P2P_coordinator_mkl-cost_20260209_030122_2035026_wan_mid.log`
    - `experiments/results/fed2/mkl-min-st-cut/pca_P2P_coordinator_mkl-min-st-cut_20260209_044507_2375513_wan_mid.log`
- **재현 절차**:
  1. 동일한 홉(`226/232`)에 대해 DP와 MinST 로그의 CP->FOUT 삽입 instruction(`fed_fout` vs `fed_refed`)을 비교한다.
  2. MinST Hop table에서 해당 홉의 planned FType이 `BROADCAST`인지 확인한다.
- **관측 증상**:
  - MinST Hop table은 `226/232`를 `FType=BROADCAST`로 계획했지만, register 단계에서는 `fed_refed`가 삽입되어 DP(`fed_fout`)와 materialization 방식이 달라짐.
  - 결과적으로 동일 workload에서 DP 대비 MinST의 WAN_MID 실행시간이 더 길어지는 편차가 관측됨.
- **원인 분석**:
  - `registerCpfoutWithSelection(...)`가 planner가 미리 확정한 hop FType(`BROADCAST`)를 우선하지 않고 anchor 기반 일반 분기로 들어가 `fed_refed`를 선택할 수 있었음.
- **해결 요약**:
  - `FederatedRefedPolicy.registerCpfoutWithSelection`에 정책 추가:
    - **planner가 hop FType을 `BROADCAST`로 확정한 CP->FOUT 후보는 항상 `fed_fout(BROADCAST)` materialize로 고정**
    - 해당 경우 `FederatedRefedRegistry`는 비우고 `FederatedFoutMaterializeRegistry`만 등록.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/FederatedRefedPolicy.java`
  - `src/test/java/org/apache/sysds/test/functions/federated/fedplanning/FederatedRefedPolicyTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedRefedPolicyTest,FederatedPlannerFallbackIntegrationTest test`
  - 결과:
    - 신규 회귀 테스트 `testPlannedBroadcastPrefersFoutMaterializeOverRefed` 통과
    - 기존 fallback integration 테스트와 함께 전체 통과
- **잔여 이슈**:
  - `lm/pca` WAN_MID end-to-end 성능 수치 재확인은 동일 환경 재실행(노이즈 포함)으로 추가 확인 필요.
- **잠재 회귀 위험**:
  - planner가 BROADCAST를 과도하게 부여하는 케이스가 있다면 `fed_fout` 경로 비중이 증가할 수 있음.
  - 감지 방법: planner 로그의 hop FType(BROADCAST) 비율과 `fed_fout/fed_refed` heavy hitter 비율을 함께 모니터링.
- **의사결정 근거(oracle/런타임/플래너)**:
  - 런타임 우회 없이, 플래너가 확정한 FType을 register/rewire 단계에서도 일관되게 보존하도록 정책 정렬.

## 이슈 5: MinST/DP의 LOUT hop FType 힌트 유실로 `fed_refed`가 재선택됨

- **상태**: 해결
- **환경/조건**:
  - Repository: `/home/mchoi/exdra_run/systemds`
  - Planner: `fed_min_st_cut`, `fed_dp`
  - 워크로드/프로파일: `lm`, `pca` / `wan_mid`
- **재현 절차**:
  1. MinST 계획 표에서 LOUT hop이 `FType=BROADCAST`로 보이는 케이스를 확인한다.
  2. 동일 hop의 LOP 삽입 결과가 `fed_fout(BROADCAST)`가 아니라 `fed_refed`로 나오는지 확인한다.
- **관측 증상**:
  - planner 단계에서 BROADCAST 의도가 있었지만 register 단계에서 힌트를 못 보고 `fed_refed`를 재선택했다.
  - 특히 dims 미확정(unknown) 경로에서 dim/axis mismatch 검출이 약해져 `fed_refed`로 기울었다.
- **원인 분석**:
  - MinST의 `buildPlannedFTypeMap`이 LOUT hop에서 `cpFoutType`만 사용해, 값이 null이면 FType 힌트가 유실되었다.
  - DP도 LOUT hop의 FType을 전부 제거해 register 단계에서 planner 의도를 재사용하기 어려웠다.
- **해결 요약**:
  - MinST:
    - LOUT hop의 `cpFoutType`이 null일 때 `vertex.getDataType()`을 fallback 힌트로 유지.
  - DP:
    - non-transient LOUT hop의 FType 힌트를 유지해 CP->FOUT register가 planner 결정(BROADCAST/ROW/COL)을 참조 가능하도록 정렬.
    - transient read/write는 기존처럼 제외해 local TR/TW를 federated source로 오판하지 않도록 보존.
- **수정 파일**:
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedMinSTCut/FederatedPlanMinSTCut.java`
  - `src/main/java/org/apache/sysds/hops/fedplanner/fedCostBased/fedDp/FederatedPlannerDpFedCostBased.java`
  - `src/test/java/org/apache/sysds/test/component/federated/FederatedPlanMinSTHyperedgeTest.java`
- **검증**:
  - 실행 커맨드:
    - `mvn -q -Dtest=FederatedPlanMinSTHyperedgeTest,FederatedRefedPolicyTest,FederatedPlannerFallbackIntegrationTest test`
    - `mvn -q -DskipTests package`
  - 결과:
    - 신규 회귀 테스트 `testBuildPlannedFTypeMapKeepsLoutHintWhenCpFoutTypeMissing` 통과
    - 관련 회귀 테스트/패키지 빌드 통과
- **잔여 이슈**:
  - end-to-end 성능 수치(lm/pca wan_mid)는 동일 환경에서 실험 재실행 후 재확인 필요.
- **잠재 회귀 위험**:
  - non-transient LOUT 힌트 유지로 planner 영향이 기존보다 넓어져 일부 케이스에서 `fed_fout` 비율이 증가할 수 있음.
  - 감지 방법: planner FType 분포와 `fed_fout/fed_refed` heavy hitter를 함께 비교.
- **의사결정 근거(oracle/런타임/플래너)**:
  - runtime 우회가 아니라 planner가 만든 FType 힌트 전달 경로를 보강해 register 결정과 일치시킴.
